package com.example.examplemod;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class BindingTicketItem extends Item {
    public static final String TAG_WORKER_ID = "WorkerId";
    public static final String TAG_OWNER_ID = "OwnerId";

    private static final Map<UUID, UUID> ARMED_HOME_SELECTION = new ConcurrentHashMap<>();

    public static final class InteractionKey {
        private final boolean isClient;
        private final UUID playerId;
        private final InteractionHand hand;
        private final int entityId;

        public InteractionKey(boolean isClient, UUID playerId, InteractionHand hand, int entityId) {
            this.isClient = isClient;
            this.playerId = playerId;
            this.hand = hand;
            this.entityId = entityId;
        }

        public InteractionKey(UUID playerId, InteractionHand hand, int entityId) {
            this(false, playerId, hand, entityId);
        }

        public boolean isClient() { return isClient; }
        public UUID playerId() { return playerId; }
        public InteractionHand hand() { return hand; }
        public int entityId() { return entityId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InteractionKey that = (InteractionKey) o;
            return isClient == that.isClient && entityId == that.entityId && hand == that.hand && playerId.equals(that.playerId);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(isClient);
            result = 31 * result + playerId.hashCode();
            result = 31 * result + hand.hashCode();
            result = 31 * result + entityId;
            return result;
        }
    }

    private static record HandledEntityInteraction(long tick, InteractionResult result) {}
    private static final Map<InteractionKey, HandledEntityInteraction> RECENT_ENTITY_INTERACTIONS = new ConcurrentHashMap<>();

    public static void clearRecentInteractionsForTesting() {
        RECENT_ENTITY_INTERACTIONS.clear();
    }

    public static void armHomeSelection(UUID playerId, UUID workerId) {
        ARMED_HOME_SELECTION.put(playerId, workerId);
    }

    public static boolean isHomeSelectionArmed(UUID playerId) {
        return ARMED_HOME_SELECTION.containsKey(playerId);
    }

    public static void clearHomeSelection(UUID playerId) {
        ARMED_HOME_SELECTION.remove(playerId);
    }

    public static Optional<UUID> getBoundWorkerId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && customData.contains(TAG_WORKER_ID)) {
            CompoundTag tag = customData.copyTag();
            if (tag.hasUUID(TAG_WORKER_ID)) {
                return Optional.of(tag.getUUID(TAG_WORKER_ID));
            }
        }
        return Optional.empty();
    }

    public static Optional<UUID> getBoundOwnerId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && customData.contains(TAG_OWNER_ID)) {
            CompoundTag tag = customData.copyTag();
            if (tag.hasUUID(TAG_OWNER_ID)) {
                return Optional.of(tag.getUUID(TAG_OWNER_ID));
            }
        }
        return Optional.empty();
    }

    public static boolean isTicketBound(ItemStack stack) {
        return getBoundWorkerId(stack).isPresent();
    }

    public static boolean bindTicket(ItemStack stack, UUID workerId, UUID ownerId) {
        if (stack == null || stack.isEmpty() || workerId == null || ownerId == null) return false;
        if (isTicketBound(stack)) {
            // Invariant: A bound ticket must NEVER silently bind a second mob or change its worker UUID
            return false;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID(TAG_WORKER_ID, workerId);
            tag.putUUID(TAG_OWNER_ID, ownerId);
        });
        return true;
    }

    public static void unbindTicket(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(TAG_WORKER_ID);
            tag.remove(TAG_OWNER_ID);
        });
    }

    public enum Decision {
        BIND, MANAGE, WRONG_WORKER, DUPLICATE, DENIED, UNAVAILABLE, PASS
    }

    public enum InteractionClaim {
        HANDLE_SUCCESS(InteractionResult.SUCCESS, true),
        HANDLE_DENIED(InteractionResult.FAIL, true),
        PASS(InteractionResult.PASS, false);

        private final InteractionResult result;
        private final boolean cancelEvent;

        InteractionClaim(InteractionResult result, boolean cancelEvent) {
            this.result = result;
            this.cancelEvent = cancelEvent;
        }

        public InteractionResult result() { return result; }
        public boolean shouldCancelEvent() { return cancelEvent; }
    }

    public static InteractionClaim toClaim(Decision decision) {
        return switch (decision) {
            case MANAGE, BIND -> InteractionClaim.HANDLE_SUCCESS;
            case DENIED, DUPLICATE, UNAVAILABLE, WRONG_WORKER -> InteractionClaim.HANDLE_DENIED;
            case PASS -> InteractionClaim.PASS;
        };
    }

    /**
     * Legacy decision helper retained for backward compatibility with existing tests and callers.
     */
    public static Decision decide(boolean server, boolean available, boolean bound, boolean legacyBound,
                                  boolean managing, WorkerProfile profile, UUID playerId, UUID entityId,
                                  ResourceLocation dimension) {
        if (!server || !available) {
            return Decision.UNAVAILABLE;
        }
        if (bound) {
            return profile != null && profile.ownerId().equals(playerId) && profile.entityId().equals(entityId)
                    && profile.dimension().equals(dimension) ? Decision.MANAGE : Decision.DENIED;
        }
        return bound || legacyBound ? Decision.DUPLICATE : Decision.BIND;
    }

    /**
     * Authoritative decision engine evaluating item-first interaction priority.
     */
    public static Decision evaluateInteraction(
            boolean hasTicket,
            Optional<UUID> ticketWorkerId,
            Optional<UUID> ticketOwnerId,
            boolean isMob,
            boolean isAvailable,
            WorkerProfile profile,
            boolean legacyBound,
            UUID playerId,
            UUID entityId,
            ResourceLocation dimension,
            boolean foreignTamed) {
        if (!hasTicket) {
            return Decision.PASS;
        }
        if (!isMob) {
            return Decision.PASS;
        }
        if (!isAvailable) {
            return Decision.UNAVAILABLE;
        }
        if (ticketWorkerId.isPresent()) {
            UUID boundWorker = ticketWorkerId.get();
            if (!boundWorker.equals(entityId)) {
                // Rule 2: Bound ticket + wrong worker: do not open GUI, preserve normal interaction
                return Decision.WRONG_WORKER;
            }
            if (ticketOwnerId.isPresent() && !ticketOwnerId.get().equals(playerId)) {
                // Rule 3: Ticket bound to another player's worker: deny unauthorized management
                return Decision.DENIED;
            }
            if (profile != null) {
                if (!profile.ownerId().equals(playerId) || !profile.entityId().equals(entityId)
                        || !profile.dimension().equals(dimension)) {
                    return Decision.DENIED;
                }
                // Rule 1: Correct bound ticket + matching worker + authorized owner
                return Decision.MANAGE;
            } else {
                return Decision.UNAVAILABLE;
            }
        }

        // Rule 4: Unbound ticket
        if (profile != null) {
            if (profile.ownerId().equals(playerId) && profile.entityId().equals(entityId)
                    && profile.dimension().equals(dimension)) {
                return Decision.MANAGE;
            }
            return Decision.DENIED;
        }
        if (legacyBound) {
            return Decision.DUPLICATE;
        }
        if (foreignTamed) {
            return Decision.DENIED;
        }
        return Decision.BIND;
    }

    public BindingTicketItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        getBoundWorkerId(stack).ifPresentOrElse(
                workerUuid -> tooltipComponents.add(Component.translatable("tooltip.elstarticket.bound_worker", workerUuid.toString().substring(0, 8) + "...")),
                () -> tooltipComponents.add(Component.translatable("tooltip.elstarticket.unbound"))
        );
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level))
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), world.isClientSide());

        // Check if player is in an active order configuration session
        if (WorkerOrderSession.hasActiveSession(player.getUUID())) {
            var sessionOpt = WorkerOrderSession.getSession(player.getUUID());
            if (player.isShiftKeyDown()) {
                // Protect against immediate race: only cancel if session was created > 500ms ago
                long sessionAgeMs = sessionOpt.map(s -> System.currentTimeMillis() - s.creationTimeMs()).orElse(1000L);
                if (sessionAgeMs > 500L) {
                    WorkerOrderSession.cancelOrder(player.getUUID());
                    serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_cancelled"), true);
                    logDiagnostic("use", hand, getBoundWorkerId(player.getItemInHand(hand)), null, "ORDER_SESSION", "CANCELLED");
                }
                return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), world.isClientSide());
            }
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_in_progress"), true);
            logDiagnostic("use", hand, getBoundWorkerId(player.getItemInHand(hand)), null, "ORDER_SESSION", "IN_PROGRESS");
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), world.isClientSide());
        }

        ItemStack stack = player.getItemInHand(hand);
        Optional<UUID> boundWorkerId = getBoundWorkerId(stack);

        // A bound ticket has exclusive identity: it manages ONLY its bound worker
        if (boundWorkerId.isPresent()) {
            UUID workerUuid = boundWorkerId.get();
            var entity = level.getEntity(workerUuid);
            if (entity instanceof Mob mob && mob.isAlive() && !mob.isRemoved() &&
                    mob.hasData(ExampleMod.WORKER_PROFILE) &&
                    mob.getData(ExampleMod.WORKER_PROFILE).ownerId().equals(player.getUUID()) &&
                    player.distanceToSqr(mob) <= 64 * 64) {

                // CRITICAL SAFETY FIX: SNEAK-RIGHT-CLICK WITH BOUND TICKET IN HAND MUST INITIATE JOB AUTHORING, NEVER OPEN MENU!
                if (player.isShiftKeyDown()) {
                    if (WorkerOrderSession.startAuthoring(serverPlayer, mob)) {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_started"), true);
                        logDiagnostic("use", hand, boundWorkerId, mob, "AUTHORING_START", "CLAIMED_SUCCESS");
                    } else {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.unavailable"), true);
                        logDiagnostic("use", hand, boundWorkerId, mob, "AUTHORING_START", "CLAIMED_FAILURE");
                    }
                    return InteractionResultHolder.sidedSuccess(stack, world.isClientSide());
                }

                // Normal (non-sneak) Right-click opens the WorkerManagementMenu
                int workerId = mob.getId();
                serverPlayer.openMenu(new MenuProvider() {
                    @Override
                    public Component getDisplayName() { return Component.translatable("gui.elstarticket.workers"); }
                    @Override
                    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player owner) {
                        return new WorkerManagementMenu(id, inventory, List.of(workerId), List.of(workerUuid));
                    }
                }, buffer -> WorkerManagementMenu.writeCandidates(buffer, List.of(workerId), List.of(workerUuid)));
                logDiagnostic("use", hand, boundWorkerId, mob, "IDLE", "OPEN_BOUND_WORKER_MENU");
                return InteractionResultHolder.success(stack);
            } else {
                serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.no_workers"), true);
                logDiagnostic("use", hand, boundWorkerId, null, "IDLE", "BOUND_WORKER_NOT_NEARBY");
                return InteractionResultHolder.fail(stack);
            }
        }

        // An unbound ticket: NEVER open menu when sneaking
        if (player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }

        // An unbound ticket: opens menu for nearby eligible workers if any
        var eligible = WorkerManagementMenu.eligible(level.getEntitiesOfClass(Mob.class,
                new AABB(player.blockPosition()).inflate(8)), mob -> WorkerManagementMenu.authorized(player, mob))
                .stream().limit(64).toList();
        List<Integer> ids = eligible.stream().map(Mob::getId).toList();
        List<UUID> uuids = eligible.stream().map(Mob::getUUID).toList();
        if (ids.isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.no_workers"), true);
            logDiagnostic("use", hand, Optional.empty(), null, "IDLE", "UNBOUND_NO_WORKERS");
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        serverPlayer.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() { return Component.translatable("gui.elstarticket.workers"); }
            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player owner) {
                return new WorkerManagementMenu(id, inventory, ids, uuids);
            }
        }, buffer -> WorkerManagementMenu.writeCandidates(buffer, ids, uuids));
        logDiagnostic("use", hand, Optional.empty(), null, "IDLE", "OPEN_UNBOUND_MULTI_MENU");
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    static boolean validMoveTarget(WorkerProfile profile, UUID requester, UUID workerId,
                                   ResourceLocation dimension, BlockPos pos, boolean loaded,
                                   boolean inBounds, boolean permitted, boolean near) {
        return profile != null && profile.job().isEmpty() && profile.ownerId().equals(requester) &&
                profile.entityId().equals(workerId) && profile.dimension().equals(dimension) &&
                pos != null && loaded && inBounds && permitted && near;
    }

    public static void reportOrderStepFailure(ServerPlayer player, WorkerOrderSession.StepResult step) {
        if (player == null || step == null) return;
        Component msg = switch (step) {
            case REJECTED_RANGE_WORKER -> Component.translatable("message.elstarticket.order_rejected_range_worker");
            case REJECTED_RANGE_PLAYER -> Component.translatable("message.elstarticket.order_rejected_range_player");
            case REJECTED_RANGE_STORAGE -> Component.translatable("message.elstarticket.order_rejected_range_storage");
            case REJECTED_NO_PERMISSION -> Component.translatable("message.elstarticket.order_rejected_no_permission");
            case REJECTED_BLOCK_TYPE -> Component.translatable("message.elstarticket.order_rejected_block_type");
            case REJECTED_BLACKLISTED -> Component.translatable("message.elstarticket.order_rejected_blacklisted");
            case REJECTED_BLOCK_ENTITY -> Component.translatable("message.elstarticket.order_rejected_block_entity");
            case REJECTED_INDESTRUCTIBLE -> Component.translatable("message.elstarticket.order_rejected_indestructible");
            case REJECTED_SURFACE_OBSTRUCTED -> Component.translatable("message.elstarticket.order_rejected_surface_obstructed");
            case REJECTED_WORKER_BUSY -> Component.translatable("message.elstarticket.order_rejected_worker_busy");
            case REJECTED_WRONG_TICKET -> Component.translatable("message.elstarticket.order_rejected_wrong_ticket");
            case INVALID_STORAGE -> Component.translatable("message.elstarticket.order_invalid_storage");
            case INVALID_WORKSITE -> Component.translatable("message.elstarticket.order_invalid_worksite");
            default -> Component.translatable("message.elstarticket.order_safety_rejected");
        };
        player.displayClientMessage(msg, true);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(player.level() instanceof ServerLevel level))
            return InteractionResult.SUCCESS;
        if (!level.getServer().isSameThread()) return InteractionResult.FAIL;

        // Check explicit Set Home selection mode
        if (ARMED_HOME_SELECTION.containsKey(player.getUUID())) {
            UUID workerUuid = ARMED_HOME_SELECTION.get(player.getUUID());
            BlockPos clickedPos = context.getClickedPos();
            if (!level.hasChunkAt(clickedPos) || !level.isInWorldBounds(clickedPos) || !player.mayBuild()) {
                serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.home_invalid"), true);
                return InteractionResult.FAIL;
            }
            var entity = level.getEntity(workerUuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isRemoved() ||
                    !mob.hasData(ExampleMod.WORKER_PROFILE)) {
                ARMED_HOME_SELECTION.remove(player.getUUID());
                serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.unavailable"), true);
                return InteractionResult.FAIL;
            }
            WorkerProfile profile = mob.getData(ExampleMod.WORKER_PROFILE);
            if (!profile.ownerId().equals(player.getUUID()) || !profile.dimension().equals(level.dimension().location())) {
                ARMED_HOME_SELECTION.remove(player.getUUID());
                serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.job_denied"), true);
                return InteractionResult.FAIL;
            }
            WorkerTarget homeTarget = new WorkerTarget(level.dimension().location(), clickedPos);
            profile.setHome(player.getUUID(), homeTarget).ifPresent(updated -> mob.setData(ExampleMod.WORKER_PROFILE, updated));
            ARMED_HOME_SELECTION.remove(player.getUUID());
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.home_set"), true);
            return InteractionResult.SUCCESS;
        }

        // Check active order configuration session
        if (WorkerOrderSession.hasActiveSession(player.getUUID())) {
            var sessionOpt = WorkerOrderSession.getSession(player.getUUID());
            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                if (session.stage() == WorkerOrderSession.Stage.SELECTING_WORKSITE ||
                        session.stage() == WorkerOrderSession.Stage.JOB_AUTHORING) {
                    WorkerOrderSession.StepResult step = WorkerOrderSession.selectWorksite(serverPlayer, context.getClickedPos(), context.getClickedFace());
                    if (step == WorkerOrderSession.StepResult.COMPLETED) {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_completed"), true);
                        return InteractionResult.SUCCESS;
                    } else if (step == WorkerOrderSession.StepResult.AWAITING_STORAGE) {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_worksite_set"), true);
                        return InteractionResult.SUCCESS;
                    } else {
                        reportOrderStepFailure(serverPlayer, step);
                        return InteractionResult.FAIL;
                    }
                } else if (session.stage() == WorkerOrderSession.Stage.SELECTING_STORAGE) {
                    WorkerOrderSession.StepResult step = WorkerOrderSession.selectStorage(serverPlayer, context.getClickedPos());
                    if (step == WorkerOrderSession.StepResult.COMPLETED) {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_completed"), true);
                        return InteractionResult.SUCCESS;
                    } else {
                        reportOrderStepFailure(serverPlayer, step);
                        return InteractionResult.FAIL;
                    }
                }
            }
        }

        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (context.getClickedFace() != Direction.UP) return InteractionResult.FAIL;
        BlockPos pos = context.getClickedPos().above();
        var workers = level.getEntitiesOfClass(PathfinderMob.class,
                new AABB(player.blockPosition()).inflate(8), mob -> mob.isAlive() && !mob.isRemoved() &&
                        mob.hasData(ExampleMod.WORKER_PROFILE) &&
                        mob.getData(ExampleMod.WORKER_PROFILE).ownerId().equals(player.getUUID()));

        Optional<UUID> boundWorkerId = getBoundWorkerId(context.getItemInHand());
        if (boundWorkerId.isPresent()) {
            workers = workers.stream().filter(w -> w.getUUID().equals(boundWorkerId.get())).toList();
        }

        if (workers.size() != 1) {
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_worker_count"), true);
            return InteractionResult.FAIL;
        }
        PathfinderMob worker = workers.getFirst();
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        boolean valid = validMoveTarget(profile, player.getUUID(), worker.getUUID(), level.dimension().location(),
                pos, level.hasChunkAt(pos), level.isInWorldBounds(pos), player.mayBuild(),
                worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 32 * 32);
        if (!valid || !level.getBlockState(pos).isAir() ||
                profile.assignMove(player.getUUID(), worker, new WorkerTarget(level.dimension().location(), pos))
                        .map(updated -> { worker.setData(ExampleMod.WORKER_PROFILE, updated); return true; }).isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_invalid"), true);
            return InteractionResult.FAIL;
        }
        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_assigned"), true);
        return InteractionResult.SUCCESS;
    }

    public static InteractionResult processEntityInteraction(
            ItemStack stack,
            Player player,
            Entity entity,
            InteractionHand hand
    ) {
        if (!stack.is(ExampleMod.BINDING_TICKET.get())) {
            return InteractionResult.PASS;
        }
        if (!(entity instanceof Mob mob)) {
            return InteractionResult.PASS;
        }

        Level level = player.level();
        boolean isAvailable = mob.isAlive() && !mob.isRemoved();
        Optional<UUID> ticketWorkerId = getBoundWorkerId(stack);
        Optional<UUID> ticketOwnerId = getBoundOwnerId(stack);

        // Section C: Client-side early claim to suppress native maid sit behavior
        if (level.isClientSide()) {
            if (ticketWorkerId.isPresent()) {
                if (ticketWorkerId.get().equals(mob.getUUID())) {
                    if (ticketOwnerId.isPresent() && !ticketOwnerId.get().equals(player.getUUID())) {
                        return InteractionResult.FAIL;
                    }
                    if (player.isShiftKeyDown()) {
                        BindingTicketHUD.updateSession(
                                BindingTicketHUD.ClientStage.JOB_AUTHORING.ordinal(),
                                mob.getUUID(),
                                mob.getDisplayName().getString(),
                                "",
                                64L,
                                null,
                                "Job Authoring activated. Point at a worksite block."
                        );
                    }
                    // Valid matching bound ticket on client: consume action early to prevent native sit toggle!
                    return InteractionResult.SUCCESS;
                }
                // Bound ticket on wrong/different worker on client: fail closed early to suppress native sit toggle!
                return InteractionResult.FAIL;
            }
            // Unbound ticket on client:
            return InteractionResult.SUCCESS;
        }

        // Server-side authoritative validation
        WorkerProfile profile = mob.hasData(ExampleMod.WORKER_PROFILE) ? mob.getData(ExampleMod.WORKER_PROFILE) : null;
        boolean legacyBound = mob.hasData(ExampleMod.WORKER_OWNER);
        boolean foreignTamed = false;
        if (mob instanceof TamableAnimal tamable) {
            if (tamable.isTame() && tamable.getOwnerUUID() != null && !tamable.getOwnerUUID().equals(player.getUUID())) {
                foreignTamed = true;
            }
        }

        Decision decision = evaluateInteraction(
                true,
                ticketWorkerId,
                ticketOwnerId,
                true,
                isAvailable,
                profile,
                legacyBound,
                player.getUUID(),
                mob.getUUID(),
                level.dimension().location(),
                foreignTamed
        );

        if (decision == Decision.PASS) {
            logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "PASS");
            return InteractionResult.PASS;
        }

        switch (decision) {
            case WRONG_WORKER -> {
                player.displayClientMessage(Component.translatable("message.elstarticket.wrong_worker"), true);
                logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "DENIED_WRONG_WORKER");
                return InteractionResult.FAIL;
            }
            case MANAGE -> {
                if (!(player instanceof ServerPlayer serverPlayer) || !WorkerManagementMenu.authorized(player, mob)) {
                    logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "DENIED_UNAUTHORIZED");
                    return InteractionResult.FAIL;
                }
                int workerId = mob.getId();
                UUID workerUuid = mob.getUUID();
                if (ticketWorkerId.isEmpty()) {
                    bindTicket(stack, workerUuid, player.getUUID());
                    player.setItemInHand(hand, stack);
                    serverPlayer.containerMenu.broadcastChanges();
                }

                // Sneak-right-click initiates job authoring mode (NEVER opens menu)
                if (player.isShiftKeyDown()) {
                    if (WorkerOrderSession.startAuthoring(serverPlayer, mob)) {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_started"), true);
                        logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "AUTHORING_START", "CLAIMED_SUCCESS");
                    } else {
                        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.unavailable"), true);
                        logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "AUTHORING_START", "CLAIMED_FAILURE");
                    }
                    return InteractionResult.SUCCESS;
                }

                serverPlayer.openMenu(new MenuProvider() {
                    @Override
                    public Component getDisplayName() { return Component.translatable("gui.elstarticket.workers"); }
                    @Override
                    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player owner) {
                        return new WorkerManagementMenu(id, inventory, List.of(workerId), List.of(workerUuid));
                    }
                }, buffer -> WorkerManagementMenu.writeCandidates(buffer, List.of(workerId), List.of(workerUuid)));
                logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "OPEN_MENU", "CLAIMED_SUCCESS");
                return InteractionResult.SUCCESS;
            }
            case BIND -> {
                if (!(level instanceof ServerLevel serverLevel)) {
                    return InteractionResult.SUCCESS;
                }
                // Server-authoritative check: ticket MUST be unbound!
                if (isTicketBound(stack)) {
                    player.displayClientMessage(Component.translatable("message.elstarticket.ticket_already_bound"), true);
                    logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "BIND", "DENIED_ALREADY_BOUND");
                    return InteractionResult.FAIL;
                }
                mob.setData(ExampleMod.WORKER_PROFILE, new WorkerProfile(
                        mob.getUUID(), player.getUUID(), serverLevel.dimension().location(),
                        WorkerStatus.IDLE, Optional.empty(), WorkerBehavior.FOLLOW, Optional.empty()
                ));
                mob.setPersistenceRequired();
                bindTicket(stack, mob.getUUID(), player.getUUID());
                player.setItemInHand(hand, stack);
                if (player instanceof ServerPlayer sp) {
                    sp.containerMenu.broadcastChanges();
                }
                player.displayClientMessage(Component.translatable("message.elstarticket.bound"), true);
                logDiagnostic("processEntityInteraction", hand, Optional.of(mob.getUUID()), mob, "BIND", "CLAIMED_SUCCESS");
                return InteractionResult.SUCCESS;
            }
            case DENIED -> {
                player.displayClientMessage(Component.translatable("message.elstarticket.job_denied"), true);
                logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "DENIED");
                return InteractionResult.FAIL;
            }
            case DUPLICATE -> {
                player.displayClientMessage(Component.translatable("message.elstarticket.already_bound"), true);
                logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "DENIED_DUPLICATE");
                return InteractionResult.FAIL;
            }
            case UNAVAILABLE -> {
                player.displayClientMessage(Component.translatable("message.elstarticket.unavailable"), true);
                logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "DENIED_UNAVAILABLE");
                return InteractionResult.FAIL;
            }
            default -> {
                logDiagnostic("processEntityInteraction", hand, ticketWorkerId, mob, "IDLE", "PASS");
                return InteractionResult.PASS;
            }
        }
    }

    private static void setEventCancellation(PlayerInteractEvent event, InteractionResult result) {
        if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) {
            specific.setCancellationResult(result);
            specific.setCanceled(true);
        } else if (event instanceof PlayerInteractEvent.EntityInteract generic) {
            generic.setCancellationResult(result);
            generic.setCanceled(true);
        }
    }

    private static void handleEntityInteraction(
            PlayerInteractEvent event,
            Player player,
            InteractionHand hand,
            ItemStack stack,
            Entity target,
            boolean isSpecific
    ) {
        if (event instanceof ICancellableEvent cancellable && cancellable.isCanceled()) {
            return;
        }

        // Section 2: Preserve emergency native maid control
        // If the hand participating in this interaction is NOT holding a Binding Ticket,
        // Elstar must NOT intercept or cancel. Native TLM/maid behavior (empty hand GUI, sit command) is preserved.
        if (!stack.is(ExampleMod.BINDING_TICKET.get())) {
            return;
        }

        // 1. Deduplicate hands: if ticket is held in main hand, ignore offhand event to prevent duplicate processing
        if (hand == InteractionHand.OFF_HAND && player.getItemInHand(InteractionHand.MAIN_HAND).is(ExampleMod.BINDING_TICKET.get())) {
            setEventCancellation(event, InteractionResult.FAIL);
            return;
        }

        // 2. Specific / generic deduplication within a 2-tick window (handles multi-tick packet delivery)
        boolean isClient = player.level().isClientSide();
        long currentTick = isClient ? (long) player.tickCount : player.level().getGameTime();
        UUID playerId = player.getUUID();
        int targetId = target.getId();
        InteractionKey key = new InteractionKey(isClient, playerId, hand, targetId);

        HandledEntityInteraction prior = RECENT_ENTITY_INTERACTIONS.get(key);
        if (prior != null) {
            if (Math.abs(currentTick - prior.tick()) <= 2) {
                if (prior.result().consumesAction() || prior.result() == InteractionResult.FAIL) {
                    setEventCancellation(event, prior.result());
                    return;
                }
            } else {
                RECENT_ENTITY_INTERACTIONS.remove(key);
            }
        }

        // On client: evaluate client-side claim immediately to consume action and suppress native maid GUI / sit toggle
        if (isClient) {
            InteractionResult clientResult = processEntityInteraction(stack, player, target, hand);
            RECENT_ENTITY_INTERACTIONS.put(key, new HandledEntityInteraction(currentTick, clientResult));
            // In sneak-right-click authoring mode, do NOT cancel the event on client so Minecraft transmits
            // ServerboundInteractPacket to the server for authoritative session creation!
            // (TLM sit toggle is neutralized on both sides by TouhouLittleMaidCompat's HIGHEST priority InteractMaidEvent listener)
            if (player.isShiftKeyDown() && stack.is(ExampleMod.BINDING_TICKET.get())) {
                return;
            }
            if (clientResult.consumesAction()) {
                setEventCancellation(event, clientResult);
            } else if (clientResult == InteractionResult.FAIL) {
                setEventCancellation(event, InteractionResult.FAIL);
            }
            return;
        }

        // 3. Separate worker management from worksite selection:
        // If an authorized worker order is actively selecting a worksite or storage, clicking an entity must NOT open a worker GUI,
        // bind the entity, or trigger a maid sit command!
        if (WorkerOrderSession.hasActiveSession(player.getUUID())) {
            var sessionOpt = WorkerOrderSession.getSession(player.getUUID());
            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                // If the clicked entity is the session's worker itself, consume the event silently!
                // This prevents duplicate packet delivery or accidental clicks on the maid right after
                // authoring start from displaying "Invalid worksite target: point at a block, not an entity".
                if (target instanceof Mob mob && mob.getUUID().equals(session.workerId())) {
                    RECENT_ENTITY_INTERACTIONS.put(key, new HandledEntityInteraction(currentTick, InteractionResult.SUCCESS));
                    setEventCancellation(event, InteractionResult.SUCCESS);
                    return;
                }

                if (session.stage() == WorkerOrderSession.Stage.SELECTING_WORKSITE ||
                        session.stage() == WorkerOrderSession.Stage.JOB_AUTHORING) {
                    player.displayClientMessage(Component.translatable("message.elstarticket.order_select_block_not_entity"), true);
                    logDiagnostic(isSpecific ? "onEntityInteractSpecific" : "onEntityInteract", hand, getBoundWorkerId(stack), target, "SELECTING_WORKSITE", "CLAIMED_DENIED_ENTITY");
                    RECENT_ENTITY_INTERACTIONS.put(key, new HandledEntityInteraction(currentTick, InteractionResult.SUCCESS));
                    setEventCancellation(event, InteractionResult.SUCCESS);
                    return;
                } else if (session.stage() == WorkerOrderSession.Stage.SELECTING_STORAGE) {
                    player.displayClientMessage(Component.translatable("message.elstarticket.order_select_storage_not_entity"), true);
                    logDiagnostic(isSpecific ? "onEntityInteractSpecific" : "onEntityInteract", hand, getBoundWorkerId(stack), target, "SELECTING_STORAGE", "CLAIMED_DENIED_ENTITY");
                    RECENT_ENTITY_INTERACTIONS.put(key, new HandledEntityInteraction(currentTick, InteractionResult.SUCCESS));
                    setEventCancellation(event, InteractionResult.SUCCESS);
                    return;
                }
            }
        }

        InteractionResult result = processEntityInteraction(stack, player, target, hand);
        RECENT_ENTITY_INTERACTIONS.put(key, new HandledEntityInteraction(currentTick, result));
        if (result.consumesAction()) {
            setEventCancellation(event, result);
        } else if (result == InteractionResult.FAIL) {
            setEventCancellation(event, InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        handleEntityInteraction(event, event.getEntity(), event.getHand(), event.getItemStack(), event.getTarget(), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        handleEntityInteraction(event, event.getEntity(), event.getHand(), event.getItemStack(), event.getTarget(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return;
        }
        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack stack = event.getItemStack();

        // 1. Only route when a Binding Ticket is held in the interacting hand
        if (!stack.is(ExampleMod.BINDING_TICKET.get())) {
            return;
        }

        // 2. Offhand deduplication
        if (hand == InteractionHand.OFF_HAND && player.getItemInHand(InteractionHand.MAIN_HAND).is(ExampleMod.BINDING_TICKET.get())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        BlockPos clickedPos = event.getPos();
        Direction clickedFace = event.getFace();
        Level world = player.level();

        // On client: do NOT cancel RightClickBlock so MultiPlayerGameMode transmits
        // ServerboundUseItemOnPacket to the server for authoritative session / home processing.
        if (world.isClientSide()) {
            return;
        }

        // 3. Selection session intercept (SELECTING_WORKSITE / SELECTING_STORAGE)
        if (WorkerOrderSession.hasActiveSession(player.getUUID())) {
            var sessionOpt = WorkerOrderSession.getSession(player.getUUID());
            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                if (session.stage() == WorkerOrderSession.Stage.SELECTING_WORKSITE ||
                        session.stage() == WorkerOrderSession.Stage.JOB_AUTHORING) {
                    if (player instanceof ServerPlayer serverPlayer) {
                        WorkerOrderSession.StepResult step = WorkerOrderSession.selectWorksite(serverPlayer, clickedPos, clickedFace);
                        if (step == WorkerOrderSession.StepResult.COMPLETED) {
                            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_completed"), true);
                        } else if (step == WorkerOrderSession.StepResult.AWAITING_STORAGE) {
                            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_worksite_set"), true);
                        } else {
                            reportOrderStepFailure(serverPlayer, step);
                            event.setCancellationResult(InteractionResult.FAIL);
                            event.setCanceled(true);
                            return;
                        }
                    }
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                } else if (session.stage() == WorkerOrderSession.Stage.SELECTING_STORAGE) {
                    if (player instanceof ServerPlayer serverPlayer) {
                        WorkerOrderSession.StepResult step = WorkerOrderSession.selectStorage(serverPlayer, clickedPos);
                        if (step == WorkerOrderSession.StepResult.COMPLETED) {
                            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.order_completed"), true);
                        } else {
                            reportOrderStepFailure(serverPlayer, step);
                            event.setCancellationResult(InteractionResult.FAIL);
                            event.setCanceled(true);
                            return;
                        }
                    }
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }
            }
        }

        // 4. Set Home selection intercept
        if (ARMED_HOME_SELECTION.containsKey(player.getUUID())) {
            if (player instanceof ServerPlayer serverPlayer && world instanceof ServerLevel serverLevel) {
                if (!serverLevel.hasChunkAt(clickedPos) || !serverLevel.isInWorldBounds(clickedPos) || !player.mayBuild()) {
                    serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.home_invalid"), true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                    return;
                }
                UUID workerUuid = ARMED_HOME_SELECTION.get(player.getUUID());
                var entity = serverLevel.getEntity(workerUuid);
                if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isRemoved() ||
                        !mob.hasData(ExampleMod.WORKER_PROFILE)) {
                    ARMED_HOME_SELECTION.remove(player.getUUID());
                    serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.unavailable"), true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                    return;
                }
                WorkerProfile profile = mob.getData(ExampleMod.WORKER_PROFILE);
                if (!profile.ownerId().equals(player.getUUID()) || !profile.dimension().equals(serverLevel.dimension().location())) {
                    ARMED_HOME_SELECTION.remove(player.getUUID());
                    serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.job_denied"), true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                    return;
                }
                WorkerTarget homeTarget = new WorkerTarget(serverLevel.dimension().location(), clickedPos);
                profile.setHome(player.getUUID(), homeTarget).ifPresent(updated -> mob.setData(ExampleMod.WORKER_PROFILE, updated));
                ARMED_HOME_SELECTION.remove(player.getUUID());
                serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.home_set"), true);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // 5. Shift-right-click to move worker
        if (player.isShiftKeyDown() && clickedFace == Direction.UP) {
            BlockPos targetPos = clickedPos.above();
            if (player instanceof ServerPlayer serverPlayer && world instanceof ServerLevel serverLevel) {
                var workers = serverLevel.getEntitiesOfClass(PathfinderMob.class,
                        new AABB(player.blockPosition()).inflate(8), mob -> mob.isAlive() && !mob.isRemoved() &&
                                mob.hasData(ExampleMod.WORKER_PROFILE) &&
                                mob.getData(ExampleMod.WORKER_PROFILE).ownerId().equals(player.getUUID()));

                Optional<UUID> boundWorkerId = getBoundWorkerId(stack);
                if (boundWorkerId.isPresent()) {
                    workers = workers.stream().filter(w -> w.getUUID().equals(boundWorkerId.get())).toList();
                }

                if (workers.size() != 1) {
                    serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_worker_count"), true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                    return;
                }
                PathfinderMob worker = workers.getFirst();
                WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
                boolean valid = validMoveTarget(profile, player.getUUID(), worker.getUUID(), serverLevel.dimension().location(),
                        targetPos, serverLevel.hasChunkAt(targetPos), serverLevel.isInWorldBounds(targetPos), player.mayBuild(),
                        worker.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5) <= 32 * 32);
                if (!valid || !serverLevel.getBlockState(targetPos).isAir() ||
                        profile.assignMove(player.getUUID(), worker, new WorkerTarget(serverLevel.dimension().location(), targetPos))
                                .map(updated -> { worker.setData(ExampleMod.WORKER_PROFILE, updated); return true; }).isEmpty()) {
                    serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_invalid"), true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                    return;
                }
                serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_assigned"), true);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
        // onEntityInteract at EventPriority.HIGHEST is the authoritative event entry point.
        // If interactLivingEntity is reached without an event bus dispatch (e.g. synthetic test call),
        // delegate safely to processEntityInteraction without duplicate processing.
        return processEntityInteraction(stack, player, entity, hand);
    }

    public static void logDiagnostic(
            String eventType,
            InteractionHand hand,
            Optional<UUID> boundWorker,
            Object target,
            String sessionStage,
            String decision
    ) {
        try {
            if (Config.DEBUG_LOGGING != null && Config.DEBUG_LOGGING.get()) {
                ExampleMod.LOGGER.info("[ElstarDiag] {} | Hand: {} | BoundWorker: {} | Target: {} | Session: {} | Decision: {}",
                        eventType,
                        hand,
                        boundWorker.map(UUID::toString).orElse("unbound"),
                        target != null ? target.toString() : "null",
                        sessionStage,
                        decision
                );
            }
        } catch (Exception ignored) {
            // Safe fallback during bootstrap/tests
        }
    }
}
