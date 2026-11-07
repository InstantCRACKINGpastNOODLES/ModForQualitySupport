package com.example.examplemod;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Generic Player-like Interaction Executor for Elstar Ticket.
 * Performs discrete and continuous Minecraft interactions through standard player mechanics,
 * enforcing Worker identity isolation, reach/LOS safety, server authorization, and zero-loss item reconciliation.
 */
public class GenericPlayerInteractionExecutor implements InteractionExecutor {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "generic_player_executor");
    public static final int DEFAULT_PRIORITY = 100;

    private static final Set<ExecutionMode> SUPPORTED_MODES = Set.of(
            ExecutionMode.CLICK,
            ExecutionMode.HOLD,
            ExecutionMode.PRESS_RELEASE,
            ExecutionMode.REPEAT
    );

    public record ContinuousSessionState(
            UUID workerId,
            InteractionIntent intent,
            ItemReconciliationSink sink,
            InteractionItemReconciler.PreparationSnapshot snapshot,
            int startTick,
            BlockPos targetBlockPos,
            UUID targetEntityId,
            int durationTicks
    ) {}

    private final ResourceLocation id;
    private final int priority;
    private final PlayerActionBridge bridge;
    private final Map<UUID, ContinuousSessionState> activeContinuousSessions = new ConcurrentHashMap<>();

    public GenericPlayerInteractionExecutor(ResourceLocation id, int priority, PlayerActionBridge bridge) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.priority = priority;
        this.bridge = Objects.requireNonNull(bridge, "bridge must not be null");
    }

    public GenericPlayerInteractionExecutor(PlayerActionBridge bridge) {
        this(ID, DEFAULT_PRIORITY, bridge);
    }

    public GenericPlayerInteractionExecutor() {
        this(ID, DEFAULT_PRIORITY, DefaultPlayerActionBridge.INSTANCE);
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public Category category() {
        return Category.GENERIC_PLAYER;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Set<ExecutionMode> supportedModes() {
        return SUPPORTED_MODES;
    }

    @Override
    public boolean canHandle(InteractionIntent intent) {
        if (intent == null || intent.target() == null) {
            return false;
        }

        return switch (intent.target()) {
            case InteractionTarget.BlockTarget b -> true;
            case InteractionTarget.EntityTarget e -> true;
            case InteractionTarget.AirTarget a -> intent.actionType() == ActionType.RIGHT_CLICK;
            case InteractionTarget.EndpointTarget ep -> false; // Endpoints are capability handlers only
        };
    }

    protected InteractionItemReconciler.PlayerInventoryAccess getPlayerInventoryAccess(ServerPlayer player) {
        return InteractionItemReconciler.PlayerInventoryAccess.of(player);
    }

    private ItemReconciliationSink resolveSink(InteractionContext context, InteractionHand hand) {
        if (context instanceof WorkerInteractionContext wCtx) {
            if (wCtx.itemSink().isPresent()) {
                return wCtx.itemSink().get();
            }
            return ItemReconciliationSink.of(wCtx.itemAccess(hand));
        }
        return context != null ? context.itemSink().orElseGet(ItemReconciliationSink::buffering) : ItemReconciliationSink.buffering();
    }

    @Override
    public InteractionResult executeDiscrete(InteractionIntent intent, InteractionContext context) {
        Objects.requireNonNull(intent, "intent must not be null");
        if (context == null) {
            return InteractionResult.failed("NULL_CONTEXT", "InteractionContext must not be null", id);
        }

        // 1. Validate Target and Action
        if (!canHandle(intent)) {
            return InteractionResult.unsupported("UNSUPPORTED_TARGET_OR_ACTION", "Executor cannot handle intent: " + intent, id);
        }

        // 2. Validate Server Environment
        Optional<ServerLevel> levelOpt = context.level();
        if (levelOpt.isEmpty()) {
            return InteractionResult.unavailable("NO_SERVER_LEVEL");
        }
        ServerLevel level = levelOpt.get();

        if (!isServerThread(level)) {
            return InteractionResult.rejected("WRONG_THREAD", "Interactions must run on the authoritative server thread");
        }

        // 3. Resolve Worker Identity
        Optional<UUID> workerIdOpt = context.workerId();
        if (workerIdOpt.isEmpty()) {
            return InteractionResult.rejected("MISSING_WORKER_IDENTITY", "Worker identity required for interaction");
        }
        UUID workerId = workerIdOpt.get();

        // 4. Preflight Authorization & Permissions
        if (context instanceof WorkerInteractionContext wCtx && !wCtx.isPermitted(intent)) {
            return InteractionResult.rejected("PERMISSION_DENIED", "Interaction authorization failed");
        }

        // 5. Spatial Validation: Reach & Line of Sight
        Vec3 workerPos = context.workerPosition().orElse(Vec3.ZERO);
        Vec3 workerEyePos = context.workerEyePosition().orElse(workerPos.add(0, 1.5, 0));

        InteractionResult spatialCheck = validateSpatial(intent, level, workerEyePos);
        if (spatialCheck != null) {
            return spatialCheck;
        }

        // 6. Target Availability Check
        Entity resolvedEntity = null;
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            if (!checkChunkLoaded(level, bt.pos())) {
                return InteractionResult.unavailable("TARGET_CHUNK_UNLOADED");
            }
        } else if (intent.target() instanceof InteractionTarget.EntityTarget et) {
            TargetResolution resolution = checkEntityTarget(level, et.entityId());
            if (!resolution.available()) {
                return InteractionResult.unavailable(resolution.unavailableReason() != null ? resolution.unavailableReason() : "TARGET_ENTITY_UNAVAILABLE");
            }
            resolvedEntity = resolution.entity();
        }

        // 7. Obtain Player Identity (Isolated FakePlayer or context override)
        ServerPlayer player = obtainPlayer(context, level, workerId, workerPos, intent.target());
        if (player == null) {
            return InteractionResult.unavailable("PLAYER_UNAVAILABLE");
        }

        // Spawn protection check for blocks
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            if (checkSpawnProtected(level, player, bt.pos())) {
                return InteractionResult.rejected("SPAWN_PROTECTED", "Block interaction blocked by spawn protection");
            }
        }

        // 8. Item Exposure and Safe Reconciliation via Sink
        ItemReconciliationSink sink = resolveSink(context, intent.hand());
        InteractionItemReconciler.PlayerInventoryAccess invAccess = getPlayerInventoryAccess(player);
        ItemStack workerHeld = ItemStack.EMPTY;
        if (context instanceof WorkerInteractionContext wCtx) {
            workerHeld = wCtx.itemAccess(intent.hand()).get();
        }
        if (workerHeld == null || workerHeld.isEmpty()) {
            workerHeld = invAccess.getItemInHand(intent.hand());
        }
        InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepareWithStack(invAccess, intent.hand(), workerHeld);

        InteractionResult executionResult;
        try {
            executionResult = dispatchDiscrete(intent, player, level, resolvedEntity);
        } catch (Throwable t) {
            InteractionItemReconciler.reconcileOnException(invAccess, intent.hand(), sink, prep);
            return InteractionResult.failed("EXECUTION_EXCEPTION", t.getMessage(), id);
        }

        // 9. Reconcile items safely
        ItemReconciliationReceipt receipt = InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
        return executionResult != null ? executionResult : InteractionResult.failed("NULL_RESULT", "Bridge returned null result", id);
    }

    @Override
    public InteractionResult startContinuous(InteractionIntent intent, InteractionContext context) {
        Objects.requireNonNull(intent, "intent must not be null");
        if (context == null) {
            return InteractionResult.failed("NULL_CONTEXT", "InteractionContext must not be null", id);
        }

        // Execution mode validation: continuous session cannot be started for discrete modes
        if (intent.executionMode() == ExecutionMode.CLICK || intent.executionMode() == ExecutionMode.REPEAT) {
            return InteractionResult.unsupported("INVALID_CONTINUOUS_MODE", "ExecutionMode " + intent.executionMode() + " is discrete, not continuous", id);
        }

        // Target/Action semantic validation:
        // Entity attack does not support continuous hold in Minecraft
        if (intent.target() instanceof InteractionTarget.EntityTarget && intent.actionType() == ActionType.LEFT_CLICK) {
            return InteractionResult.unsupported("CONTINUOUS_ENTITY_ATTACK_NOT_SUPPORTED", "Minecraft entity attack does not support continuous hold; use REPEAT mode", id);
        }
        if (intent.target() instanceof InteractionTarget.AirTarget && intent.actionType() == ActionType.LEFT_CLICK) {
            return InteractionResult.unsupported("AIR_LEFT_CLICK_NOT_SUPPORTED", "Air left-click is not supported", id);
        }

        if (!canHandle(intent)) {
            return InteractionResult.unsupported("UNSUPPORTED_TARGET_OR_ACTION", "Executor cannot handle intent: " + intent, id);
        }

        Optional<ServerLevel> levelOpt = context.level();
        if (levelOpt.isEmpty()) {
            return InteractionResult.unavailable("NO_SERVER_LEVEL");
        }
        ServerLevel level = levelOpt.get();

        if (!isServerThread(level)) {
            return InteractionResult.rejected("WRONG_THREAD", "Interactions must run on the authoritative server thread");
        }

        Optional<UUID> workerIdOpt = context.workerId();
        if (workerIdOpt.isEmpty()) {
            return InteractionResult.rejected("MISSING_WORKER_IDENTITY", "Worker identity required for interaction");
        }
        UUID workerId = workerIdOpt.get();

        if (context instanceof WorkerInteractionContext wCtx && !wCtx.isPermitted(intent)) {
            return InteractionResult.rejected("PERMISSION_DENIED", "Interaction authorization failed");
        }

        // Target availability
        Entity resolvedEntity = null;
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            if (!checkChunkLoaded(level, bt.pos())) {
                return InteractionResult.unavailable("TARGET_CHUNK_UNLOADED");
            }
        } else if (intent.target() instanceof InteractionTarget.EntityTarget et) {
            TargetResolution resolution = checkEntityTarget(level, et.entityId());
            if (!resolution.available()) {
                return InteractionResult.unavailable(resolution.unavailableReason() != null ? resolution.unavailableReason() : "TARGET_ENTITY_UNAVAILABLE");
            }
            resolvedEntity = resolution.entity();
        }

        Vec3 workerPos = context.workerPosition().orElse(Vec3.ZERO);
        Vec3 workerEyePos = context.workerEyePosition().orElse(workerPos.add(0, 1.5, 0));

        InteractionResult spatialCheck = validateSpatial(intent, level, workerEyePos);
        if (spatialCheck != null) {
            return spatialCheck;
        }

        ServerPlayer player = obtainPlayer(context, level, workerId, workerPos, intent.target());
        if (player == null) {
            return InteractionResult.unavailable("PLAYER_UNAVAILABLE");
        }

        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            if (checkSpawnProtected(level, player, bt.pos())) {
                return InteractionResult.rejected("SPAWN_PROTECTED", "Block interaction blocked by spawn protection");
            }
        }

        // Prepare item into player hand for continuous interaction
        ItemReconciliationSink sink = resolveSink(context, intent.hand());
        InteractionItemReconciler.PlayerInventoryAccess invAccess = getPlayerInventoryAccess(player);
        ItemStack workerHeld = ItemStack.EMPTY;
        if (context instanceof WorkerInteractionContext wCtx) {
            workerHeld = wCtx.itemAccess(intent.hand()).get();
        }
        if (workerHeld == null || workerHeld.isEmpty()) {
            workerHeld = invAccess.getItemInHand(intent.hand());
        }
        InteractionItemReconciler.PreparationSnapshot prep = InteractionItemReconciler.prepareWithStack(invAccess, intent.hand(), workerHeld);

        // Store active continuous session state to guarantee snapshot and sink retention across ticks
        ContinuousSessionState sessionState = new ContinuousSessionState(
                workerId,
                intent,
                sink,
                prep,
                0,
                (intent.target() instanceof InteractionTarget.BlockTarget bt) ? bt.pos() : null,
                (intent.target() instanceof InteractionTarget.EntityTarget et) ? et.entityId() : null,
                intent.durationTicks().orElse(0)
        );
        activeContinuousSessions.put(workerId, sessionState);

        // Initiate continuous action via bridge
        try {
            if (intent.actionType() == ActionType.LEFT_CLICK && intent.target() instanceof InteractionTarget.BlockTarget bt) {
                InteractionResult leftBlockRes = bridge.leftClickBlock(id, player, level, bt.pos(), bt.face().orElse(Direction.UP), intent.executionMode(), 0);
                if (leftBlockRes == null) {
                    activeContinuousSessions.remove(workerId);
                    InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
                    return InteractionResult.failed("NULL_INITIAL_RESULT", "Bridge returned null result", id);
                }
                if (!leftBlockRes.outcome().isSuccess() && leftBlockRes.outcome() != InteractionOutcome.WAITING) {
                    activeContinuousSessions.remove(workerId);
                    InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
                    return leftBlockRes;
                }
                if (leftBlockRes.outcome().isSuccess() && "INSTANT_BREAK".equals(leftBlockRes.reasonCode())) {
                    activeContinuousSessions.remove(workerId);
                    InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
                    return leftBlockRes;
                }
                return leftBlockRes;
            } else if (intent.actionType() == ActionType.RIGHT_CLICK) {
                InteractionResult initialRes;
                if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
                    Direction face = bt.face().orElse(Direction.UP);
                    Vec3 hitVec = bt.hitVec().orElseGet(() -> Vec3.atCenterOf(bt.pos()));
                    BlockHitResult hitResult = new BlockHitResult(hitVec, face, bt.pos(), false);
                    initialRes = bridge.rightClickBlock(id, player, level, invAccess.getItemInHand(intent.hand()), intent.hand(), hitResult);
                } else if (intent.target() instanceof InteractionTarget.AirTarget) {
                    initialRes = bridge.rightClickAir(id, player, level, invAccess.getItemInHand(intent.hand()), intent.hand());
                } else if (intent.target() instanceof InteractionTarget.EntityTarget et) {
                    initialRes = bridge.rightClickEntity(id, player, level, resolvedEntity, intent.hand(), et.hitVec().orElse(null));
                } else {
                    initialRes = InteractionResult.unsupported("UNSUPPORTED_TARGET");
                }

                if (initialRes == null) {
                    activeContinuousSessions.remove(workerId);
                    InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
                    return InteractionResult.failed("NULL_INITIAL_RESULT", "Bridge returned null result", id);
                }

                // If initial action is not SUCCESS or WAITING (i.e. FAILED, BLOCKED, REJECTED, UNSUPPORTED, UNKNOWN): abort immediately!
                if (!initialRes.outcome().isSuccess() && initialRes.outcome() != InteractionOutcome.WAITING) {
                    activeContinuousSessions.remove(workerId);
                    InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
                    return initialRes;
                }

                if (intent.executionMode() == ExecutionMode.PRESS_RELEASE) {
                    return InteractionResult.waiting("HOLDING_BUTTON");
                }

                boolean using = false;
                try {
                    using = player.isUsingItem();
                } catch (Throwable ignored) {
                }
                if (using) {
                    return InteractionResult.waiting("USING_ITEM");
                }

                // Non-continuous right-click: reject continuous hold
                activeContinuousSessions.remove(workerId);
                InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, prep);
                String reasonCode = switch (intent.target()) {
                    case InteractionTarget.BlockTarget b -> "BLOCK_RIGHT_CLICK_NOT_CONTINUOUS";
                    case InteractionTarget.AirTarget a -> "AIR_ITEM_NOT_CONTINUOUS";
                    case InteractionTarget.EntityTarget e -> "ENTITY_RIGHT_CLICK_NOT_CONTINUOUS";
                    default -> "RIGHT_CLICK_NOT_CONTINUOUS";
                };
                return InteractionResult.unsupported(reasonCode, "Target does not support continuous right-click hold", id);
            }
            return InteractionResult.waiting("CONTINUOUS_IN_PROGRESS");
        } catch (Throwable t) {
            activeContinuousSessions.remove(workerId);
            try {
                bridge.cancelContinuousAction(id, player, level, intent, 0);
            } catch (Throwable ignored) {
            }
            InteractionItemReconciler.reconcileOnException(invAccess, intent.hand(), sink, prep);
            return InteractionResult.failed("START_CONTINUOUS_EXCEPTION", t.getMessage(), id);
        }
    }

    @Override
    public InteractionResult tickContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
        Objects.requireNonNull(intent, "intent must not be null");
        if (context == null) {
            return InteractionResult.failed("NULL_CONTEXT", "InteractionContext must not be null", id);
        }

        Optional<ServerLevel> levelOpt = context.level();
        if (levelOpt.isEmpty()) {
            return InteractionResult.unavailable("NO_SERVER_LEVEL");
        }
        ServerLevel level = levelOpt.get();

        // Validation Parity: Server thread enforcement
        if (!isServerThread(level)) {
            return InteractionResult.rejected("WRONG_THREAD", "Interactions must run on the authoritative server thread");
        }

        Optional<UUID> workerIdOpt = context.workerId();
        if (workerIdOpt.isEmpty()) {
            return InteractionResult.rejected("MISSING_WORKER_IDENTITY", "Worker identity required");
        }
        UUID workerId = workerIdOpt.get();

        // Validation Parity: Permission re-evaluation during continuous ticks
        if (context instanceof WorkerInteractionContext wCtx && !wCtx.isPermitted(intent)) {
            return InteractionResult.rejected("PERMISSION_DENIED", "Interaction permission revoked");
        }

        // Validation Parity: Target availability re-evaluation
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            if (!checkChunkLoaded(level, bt.pos())) {
                return InteractionResult.unavailable("TARGET_CHUNK_UNLOADED");
            }
        } else if (intent.target() instanceof InteractionTarget.EntityTarget et) {
            TargetResolution resolution = checkEntityTarget(level, et.entityId());
            if (!resolution.available()) {
                return InteractionResult.unavailable(resolution.unavailableReason() != null ? resolution.unavailableReason() : "TARGET_ENTITY_UNAVAILABLE");
            }
        }

        Vec3 workerPos = context.workerPosition().orElse(Vec3.ZERO);
        Vec3 workerEyePos = context.workerEyePosition().orElse(workerPos.add(0, 1.5, 0));

        // Spatial validation during continuation: detect target moved or out of reach
        InteractionResult spatialCheck = validateSpatial(intent, level, workerEyePos);
        if (spatialCheck != null) {
            return spatialCheck;
        }

        ServerPlayer player = obtainPlayer(context, level, workerId, workerPos, intent.target());
        if (player == null) {
            return InteractionResult.unavailable("PLAYER_UNAVAILABLE");
        }

        // Validation Parity: Spawn protection re-evaluation for blocks
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            if (checkSpawnProtected(level, player, bt.pos())) {
                return InteractionResult.rejected("SPAWN_PROTECTED", "Block interaction blocked by spawn protection");
            }
        }

        try {
            if (intent.actionType() == ActionType.LEFT_CLICK && intent.target() instanceof InteractionTarget.BlockTarget bt) {
                try {
                    BlockState state = level.getBlockState(bt.pos());
                    if (state != null && state.isAir()) {
                        return new InteractionResult(InteractionOutcome.SUCCESS, "BLOCK_BROKEN", Optional.of("BLOCK_BROKEN"), Optional.of(id), true);
                    }
                } catch (Throwable ignored) {
                }
                return bridge.leftClickBlock(id, player, level, bt.pos(), bt.face().orElse(Direction.UP), intent.executionMode(), elapsedTicks);
            } else if (intent.actionType() == ActionType.RIGHT_CLICK) {
                if (intent.executionMode() == ExecutionMode.PRESS_RELEASE) {
                    int duration = intent.durationTicks().orElse(1);
                    if (elapsedTicks < duration) {
                        return InteractionResult.waiting("HOLDING_BUTTON");
                    }
                    return InteractionResult.success(id, true, "PRESS_RELEASE_COMPLETED");
                }

                boolean isUsing = false;
                try {
                    isUsing = player.isUsingItem();
                } catch (Throwable ignored) {
                }
                if (isUsing) {
                    int remaining = 0;
                    try {
                        remaining = player.getUseItemRemainingTicks();
                    } catch (Throwable ignored) {
                    }
                    return new InteractionResult(
                            InteractionOutcome.WAITING,
                            "USING_ITEM",
                            Optional.of("use_item_remaining=" + remaining),
                            Optional.of(id),
                            true
                    );
                }
                return InteractionResult.success(id, true, "ITEM_USE_COMPLETED");
            }
            return InteractionResult.waiting("CONTINUOUS_IN_PROGRESS");
        } catch (Throwable t) {
            return InteractionResult.failed("TICK_CONTINUOUS_EXCEPTION", t.getMessage(), id);
        }
    }

    @Override
    public InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
        Objects.requireNonNull(intent, "intent must not be null");
        Optional<ServerLevel> levelOpt = context != null ? context.level() : Optional.empty();
        Optional<UUID> workerIdOpt = context != null ? context.workerId() : Optional.empty();

        if (levelOpt.isEmpty() || workerIdOpt.isEmpty()) {
            return InteractionResult.failed("MISSING_CONTEXT", "Level or worker ID missing for release", id);
        }

        UUID workerId = workerIdOpt.get();
        ServerLevel level = levelOpt.get();
        Vec3 workerPos = context.workerPosition().orElse(Vec3.ZERO);
        ServerPlayer player = obtainPlayer(context, level, workerId, workerPos, intent.target());

        ContinuousSessionState sessionState = activeContinuousSessions.get(workerId);
        ItemReconciliationSink sink = sessionState != null ? sessionState.sink() : resolveSink(context, intent.hand());
        InteractionItemReconciler.PreparationSnapshot snapshot = sessionState != null ? sessionState.snapshot() : new InteractionItemReconciler.PreparationSnapshot(ItemStack.EMPTY, 0, 0, true);

        boolean bridgeConfirmed = false;
        Throwable bridgeEx = null;
        if (player != null) {
            try {
                bridgeConfirmed = bridge.stopContinuousAction(id, player, level, intent, elapsedTicks);
            } catch (Throwable t) {
                bridgeEx = t;
            }

            // Always reconcile items regardless of bridge outcome to guarantee zero item loss
            InteractionItemReconciler.PlayerInventoryAccess invAccess = getPlayerInventoryAccess(player);
            InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, snapshot);
        }

        if (bridgeEx != null) {
            return InteractionResult.failed("RELEASE_BRIDGE_EXCEPTION", bridgeEx.getMessage(), id);
        }
        if (!bridgeConfirmed) {
            return InteractionResult.failed("RELEASE_NOT_CONFIRMED", "Bridge failed to confirm continuous release", id);
        }

        // Only remove session state once cleanup is verified and confirmed
        activeContinuousSessions.remove(workerId);
        return new InteractionResult(InteractionOutcome.SUCCESS, "RELEASE_CONFIRMED", Optional.of("RELEASE_CONFIRMED"), Optional.of(id), true);
    }

    @Override
    public InteractionResult cancelContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks, String reason) {
        Objects.requireNonNull(intent, "intent must not be null");
        Optional<ServerLevel> levelOpt = context != null ? context.level() : Optional.empty();
        Optional<UUID> workerIdOpt = context != null ? context.workerId() : Optional.empty();

        if (levelOpt.isEmpty() || workerIdOpt.isEmpty()) {
            return InteractionResult.failed("MISSING_CONTEXT", "Level or worker ID missing for cancel", id);
        }

        UUID workerId = workerIdOpt.get();
        ServerLevel level = levelOpt.get();
        Vec3 workerPos = context.workerPosition().orElse(Vec3.ZERO);
        ServerPlayer player = obtainPlayer(context, level, workerId, workerPos, intent.target());

        ContinuousSessionState sessionState = activeContinuousSessions.get(workerId);
        ItemReconciliationSink sink = sessionState != null ? sessionState.sink() : resolveSink(context, intent.hand());
        InteractionItemReconciler.PreparationSnapshot snapshot = sessionState != null ? sessionState.snapshot() : new InteractionItemReconciler.PreparationSnapshot(ItemStack.EMPTY, 0, 0, true);

        boolean bridgeConfirmed = false;
        Throwable bridgeEx = null;
        if (player != null) {
            try {
                bridgeConfirmed = bridge.cancelContinuousAction(id, player, level, intent, elapsedTicks);
            } catch (Throwable t) {
                bridgeEx = t;
            }

            // Always reconcile items to guarantee zero item loss or duplication upon cancellation
            InteractionItemReconciler.PlayerInventoryAccess invAccess = getPlayerInventoryAccess(player);
            InteractionItemReconciler.reconcile(invAccess, intent.hand(), sink, snapshot);
        }

        if (bridgeEx != null) {
            return InteractionResult.failed("CANCEL_BRIDGE_EXCEPTION", bridgeEx.getMessage(), id);
        }
        if (!bridgeConfirmed) {
            return InteractionResult.failed("CANCEL_NOT_CONFIRMED", "Bridge failed to confirm continuous cancellation", id);
        }

        // Only remove session state once cleanup is verified and confirmed
        activeContinuousSessions.remove(workerId);
        return new InteractionResult(InteractionOutcome.SUCCESS, "CANCEL_CONFIRMED", Optional.of("CANCEL_CONFIRMED: " + reason), Optional.of(id), true);
    }

    @Override
    public boolean verify(InteractionIntent intent, InteractionContext context, InteractionResult result) {
        if (intent == null || result == null || !result.outcome().isSuccess()) {
            return false;
        }

        if (context == null || context.level().isEmpty()) {
            return result.verified();
        }

        ServerLevel level = context.level().get();
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            try {
                if (intent.actionType() == ActionType.LEFT_CLICK) {
                    return level.getBlockState(bt.pos()).isAir();
                }
            } catch (Throwable ignored) {
            }
            return result.verified();
        } else if (intent.target() instanceof InteractionTarget.EntityTarget et) {
            try {
                Entity entity = level.getEntity(et.entityId());
                return entity != null && result.verified();
            } catch (Throwable ignored) {
            }
        }

        return result.verified();
    }

    protected boolean isServerThread(ServerLevel level) {
        try {
            if (level.getServer() != null) {
                return level.getServer().isSameThread();
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    protected boolean checkChunkLoaded(ServerLevel level, BlockPos pos) {
        try {
            return level.hasChunkAt(pos);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public record TargetResolution(
            boolean available,
            Entity entity,
            String unavailableReason
    ) {
        public static TargetResolution success(Entity e) {
            return new TargetResolution(true, e, null);
        }

        public static TargetResolution unavailable(String reason) {
            return new TargetResolution(false, null, reason);
        }
    }

    protected TargetResolution checkEntityTarget(ServerLevel level, UUID entityId) {
        try {
            Entity resolved = level.getEntity(entityId);
            if (resolved == null || !resolved.isAlive() || resolved.isRemoved()) {
                return TargetResolution.unavailable("TARGET_ENTITY_UNAVAILABLE");
            }
            return TargetResolution.success(resolved);
        } catch (Throwable ignored) {
            return TargetResolution.unavailable("TARGET_ENTITY_UNAVAILABLE");
        }
    }

    protected boolean checkSpawnProtected(ServerLevel level, ServerPlayer player, BlockPos pos) {
        try {
            return !level.mayInteract(player, pos);
        } catch (Throwable ignored) {
            return false;
        }
    }

    protected boolean checkLineOfSight(ServerLevel level, Vec3 eyePos, Vec3 targetCenter, InteractionTarget target) {
        try {
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos,
                    targetCenter,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    (Entity) null
            ));
            if (hit.getType() == HitResult.Type.BLOCK) {
                if (target instanceof InteractionTarget.BlockTarget bt) {
                    return hit.getBlockPos().equals(bt.pos());
                }
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private InteractionResult validateSpatial(InteractionIntent intent, ServerLevel level, Vec3 eyePos) {
        Vec3 targetCenter;
        if (intent.target() instanceof InteractionTarget.BlockTarget bt) {
            targetCenter = Vec3.atCenterOf(bt.pos());
        } else if (intent.target() instanceof InteractionTarget.EntityTarget et) {
            try {
                Entity e = level.getEntity(et.entityId());
                targetCenter = e != null ? e.position().add(0, e.getEyeHeight() * 0.5, 0) : null;
            } catch (Throwable t) {
                targetCenter = null;
            }
        } else if (intent.target() instanceof InteractionTarget.AirTarget at) {
            targetCenter = at.origin();
        } else {
            return InteractionResult.unsupported("UNSUPPORTED_TARGET", "Unsupported target type", id);
        }

        if (targetCenter != null) {
            double dist = eyePos.distanceTo(targetCenter);
            if (dist > intent.maxReachDistance()) {
                return InteractionResult.blocked("OUT_OF_REACH", "Target distance " + dist + " exceeds reach " + intent.maxReachDistance());
            }

            if (intent.requireLineOfSight()) {
                if (!checkLineOfSight(level, eyePos, targetCenter, intent.target())) {
                    return InteractionResult.blocked("LINE_OF_SIGHT_OBSTRUCTED", "Line of sight blocked");
                }
            }
        }

        return null;
    }

    private ServerPlayer obtainPlayer(
            InteractionContext context,
            ServerLevel level,
            UUID workerId,
            Vec3 workerPos,
            InteractionTarget target
    ) {
        if (context.player().isPresent()) {
            ServerPlayer override = context.player().get();
            try {
                override.setPos(workerPos.x, workerPos.y, workerPos.z);
            } catch (Throwable ignored) {
            }
            return override;
        }

        // Derive deterministic FakePlayer GameProfile from Worker UUID
        UUID fakeUuid = UUID.nameUUIDFromBytes(("elstar_worker:" + workerId).getBytes(StandardCharsets.UTF_8));
        String fakeName = "ElstarWorker_" + workerId.toString().substring(0, 8);
        GameProfile profile = new GameProfile(fakeUuid, fakeName);

        try {
            ServerPlayer fakePlayer = FakePlayerFactory.get(level, profile);
            try {
                fakePlayer.setPos(workerPos.x, workerPos.y, workerPos.z);
                if (target instanceof InteractionTarget.BlockTarget bt) {
                    Vec3 targetCenter = Vec3.atCenterOf(bt.pos());
                    Vec3 dir = targetCenter.subtract(workerPos).normalize();
                    double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
                    double pitch = Math.toDegrees(-Math.asin(dir.y));
                    fakePlayer.setYRot((float) yaw);
                    fakePlayer.setXRot((float) pitch);
                }
            } catch (Throwable ignored) {
            }
            return fakePlayer;
        } catch (Throwable t) {
            return null;
        }
    }

    private InteractionResult dispatchDiscrete(
            InteractionIntent intent,
            ServerPlayer player,
            ServerLevel level,
            Entity resolvedEntity
    ) {
        InteractionItemReconciler.PlayerInventoryAccess invAccess = getPlayerInventoryAccess(player);
        ItemStack held = invAccess.getItemInHand(intent.hand());

        return switch (intent.target()) {
            case InteractionTarget.BlockTarget bt -> {
                Direction face = bt.face().orElse(Direction.UP);
                Vec3 hitVec = bt.hitVec().orElseGet(() -> Vec3.atCenterOf(bt.pos()));
                BlockHitResult hitResult = new BlockHitResult(hitVec, face, bt.pos(), false);

                if (intent.actionType() == ActionType.RIGHT_CLICK) {
                    yield bridge.rightClickBlock(id, player, level, held, intent.hand(), hitResult);
                } else {
                    yield bridge.leftClickBlock(id, player, level, bt.pos(), face, intent.executionMode(), 0);
                }
            }
            case InteractionTarget.EntityTarget et -> {
                Vec3 hitVec = et.hitVec().orElse(null);
                if (intent.actionType() == ActionType.RIGHT_CLICK) {
                    yield bridge.rightClickEntity(id, player, level, resolvedEntity, intent.hand(), hitVec);
                } else {
                    yield bridge.leftClickEntity(id, player, level, resolvedEntity);
                }
            }
            case InteractionTarget.AirTarget at -> {
                if (intent.actionType() == ActionType.RIGHT_CLICK) {
                    yield bridge.rightClickAir(id, player, level, held, intent.hand());
                } else {
                    yield InteractionResult.unsupported("UNSUPPORTED_ACTION", "Air left-click is not supported", id);
                }
            }
            case InteractionTarget.EndpointTarget ep ->
                    InteractionResult.unsupported("UNSUPPORTED_TARGET", "Endpoints are capability handlers only", id);
        };
    }
}
