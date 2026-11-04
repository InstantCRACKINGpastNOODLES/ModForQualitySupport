package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative state machine managing in-flight worker order and job-authoring sessions.
 * Enforces valid transitions, timeouts, distance checks, worksite/storage adapters, and safety policies.
 */
public final class WorkerOrderSession {
    public enum Stage {
        JOB_AUTHORING,
        SELECTING_WORKSITE,
        SELECTING_STORAGE,
        VALIDATING,
        COMPLETED,
        CANCELLED
    }

    public enum StepResult {
        AWAITING_WORKSITE,
        AWAITING_STORAGE,
        COMPLETED,
        REJECTED_RANGE_WORKER,
        REJECTED_RANGE_PLAYER,
        REJECTED_RANGE_STORAGE,
        REJECTED_NO_PERMISSION,
        REJECTED_BLOCK_TYPE,
        REJECTED_BLACKLISTED,
        REJECTED_BLOCK_ENTITY,
        REJECTED_INDESTRUCTIBLE,
        REJECTED_SURFACE_OBSTRUCTED,
        REJECTED_WORKER_BUSY,
        REJECTED_WRONG_TICKET,
        INVALID_WORKSITE,
        INVALID_STORAGE,
        UNAVAILABLE,
        DENIED,
        CANCELLED
    }

    public record MinerConfig(
            MinerJobData.MiningMode mode,
            int maxTravelDistance,
            boolean stairAscending,
            int targetY,
            int searchRadius,
            List<String> targetBlockIds,
            List<String> targetBlockTags
    ) {}

    public record Session(
            UUID playerId,
            UUID workerId,
            ResourceLocation dimension,
            ResourceLocation jobType,
            Stage stage,
            Optional<WorkerTarget> worksite,
            Optional<WorkerTarget> storage,
            long requestedQuantity,
            long creationTimeMs,
            Optional<Direction> direction,
            List<ResourceLocation> permittedCrops,
            Optional<WorkerJob> previousJob,
            Optional<MinerConfig> minerConfig,
            CropSelectionMode cropSelectionMode
    ) {
        public boolean isExpired(long currentTimeMs, long maxAgeMs) {
            return (currentTimeMs - creationTimeMs) > maxAgeMs;
        }

        public Session(UUID playerId, UUID workerId, ResourceLocation dimension, ResourceLocation jobType,
                       Stage stage, Optional<WorkerTarget> worksite, Optional<WorkerTarget> storage,
                       long requestedQuantity, long creationTimeMs, Optional<Direction> direction,
                       List<ResourceLocation> permittedCrops, Optional<WorkerJob> previousJob,
                       Optional<MinerConfig> minerConfig) {
            this(playerId, workerId, dimension, jobType, stage, worksite, storage, requestedQuantity, creationTimeMs, direction, permittedCrops, previousJob, minerConfig, CropSelectionMode.ALL_SUPPORTED);
        }

        public Session(UUID playerId, UUID workerId, ResourceLocation dimension, ResourceLocation jobType,
                       Stage stage, Optional<WorkerTarget> worksite, Optional<WorkerTarget> storage,
                       long requestedQuantity, long creationTimeMs, Optional<Direction> direction,
                       List<ResourceLocation> permittedCrops, Optional<WorkerJob> previousJob) {
            this(playerId, workerId, dimension, jobType, stage, worksite, storage, requestedQuantity, creationTimeMs, direction, permittedCrops, previousJob, Optional.empty(), CropSelectionMode.ALL_SUPPORTED);
        }

        public Session(UUID playerId, UUID workerId, ResourceLocation dimension, ResourceLocation jobType,
                       Stage stage, Optional<WorkerTarget> worksite, Optional<WorkerTarget> storage,
                       long requestedQuantity, long creationTimeMs, Optional<Direction> direction,
                       List<ResourceLocation> permittedCrops) {
            this(playerId, workerId, dimension, jobType, stage, worksite, storage, requestedQuantity, creationTimeMs, direction, permittedCrops, Optional.empty(), Optional.empty(), CropSelectionMode.ALL_SUPPORTED);
        }

        public Session(UUID playerId, UUID workerId, ResourceLocation dimension, ResourceLocation jobType,
                       Stage stage, Optional<WorkerTarget> worksite, Optional<WorkerTarget> storage, long requestedQuantity, long creationTimeMs) {
            this(playerId, workerId, dimension, jobType, stage, worksite, storage, requestedQuantity, creationTimeMs, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), CropSelectionMode.ALL_SUPPORTED);
        }

        public Session(UUID playerId, UUID workerId, ResourceLocation dimension, ResourceLocation jobType,
                       Stage stage, Optional<WorkerTarget> worksite, Optional<WorkerTarget> storage, long creationTimeMs) {
            this(playerId, workerId, dimension, jobType, stage, worksite, storage, 64L, creationTimeMs, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), CropSelectionMode.ALL_SUPPORTED);
        }
    }

    /** Maximum session lifetime: 60 seconds of inactivity before auto-cancellation. */
    public static final long SESSION_TIMEOUT_MS = 60_000L;

    private static final Map<UUID, Session> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private WorkerOrderSession() {
    }

    public static int toClientStageOrdinal(Stage stage) {
        if (stage == null) return 0; // NONE
        return switch (stage) {
            case JOB_AUTHORING -> 1;
            case SELECTING_WORKSITE -> 2;
            case SELECTING_STORAGE -> 3;
            case VALIDATING -> 4;
            case COMPLETED -> 5;
            case CANCELLED -> 7;
        };
    }

    public static boolean hasActiveSession(UUID playerId) {
        if (playerId == null) return false;
        cleanExpired(playerId);
        return ACTIVE_SESSIONS.containsKey(playerId);
    }

    public static Optional<Session> getSession(UUID playerId) {
        if (playerId == null) return Optional.empty();
        cleanExpired(playerId);
        return Optional.ofNullable(ACTIVE_SESSIONS.get(playerId));
    }

    public static void cancelOrder(UUID playerId) {
        if (playerId != null) {
            ACTIVE_SESSIONS.remove(playerId);
        }
    }

    public static void cancelOrder(ServerPlayer player) {
        if (player != null) {
            cancelOrder(player.getUUID());
            sendSync(player, 7, "Order configuration cancelled.");
        }
    }

    public static void preservePendingJobOutputs(Mob worker) {
        if (worker == null || !worker.hasData(ExampleMod.WORKER_PROFILE)) return;
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        if (profile.job().isEmpty()) return;
        WorkerJob job = profile.job().get();

        WorkerStorage wStorage = worker.hasData(ExampleMod.WORKER_STORAGE) ?
                worker.getData(ExampleMod.WORKER_STORAGE) : WorkerStorage.empty();

        if (job.minerData().isPresent()) {
            MinerJobData md = job.minerData().get();
            for (ItemStack stack : md.pendingStacks()) {
                if (stack != null && !stack.isEmpty()) {
                    wStorage = wStorage.deposit(stack);
                }
            }
        }
        if (job.farmerData().isPresent()) {
            FarmerJobData fd = job.farmerData().get();
            for (ItemStack stack : fd.pendingStacks()) {
                if (stack != null && !stack.isEmpty()) {
                    wStorage = wStorage.deposit(stack);
                }
            }
        }
        worker.setData(ExampleMod.WORKER_STORAGE, wStorage);
    }

    public static void sendSync(ServerPlayer player, int stageOrdinal, String message) {
        if (player == null) return;
        Session session = ACTIVE_SESSIONS.get(player.getUUID());
        UUID wId = session != null ? session.workerId() : new UUID(0, 0);
        String jType = session != null && session.jobType() != null ? session.jobType().getPath() : "";
        long qty = session != null ? session.requestedQuantity() : 0L;
        BlockPos site = (session != null && session.worksite().isPresent()) ? session.worksite().get().position() : null;
        String name = "Worker";
        if (session != null && player.serverLevel() != null) {
            var ent = player.serverLevel().getEntity(session.workerId());
            if (ent != null) name = ent.getDisplayName().getString();
        }
        try {
            PacketDistributor.sendToPlayer(player,
                    new OrderSessionSyncPayload(wId, name, jType, stageOrdinal, qty, site, message != null ? message : ""));
        } catch (Throwable t) {
            // Safe in test environments without active network pipeline; logged for debug in gameplay
            ExampleMod.LOGGER.debug("OrderSessionSync send notice: {}", t.getMessage());
        }
    }

    public static void syncSessionToPlayer(ServerPlayer player) {
        if (player == null) return;
        Session session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session != null) {
            sendSync(player, toClientStageOrdinal(session.stage()), "");
        } else {
            sendSync(player, 0, "");
        }
    }

    public static void clearAll() {
        ACTIVE_SESSIONS.clear();
    }

    static void setSessionForTesting(Session session) {
        if (session != null) {
            ACTIVE_SESSIONS.put(session.playerId(), session);
        }
    }

    private static void cleanExpired(UUID playerId) {
        Session session = ACTIVE_SESSIONS.get(playerId);
        if (session != null && session.isExpired(System.currentTimeMillis(), SESSION_TIMEOUT_MS)) {
            ACTIVE_SESSIONS.remove(playerId);
        }
    }

    /**
     * Enters explicit JOB_AUTHORING mode for a player and bound worker.
     * Activates 3D tether and prepares for worksite target selection.
     */
    public static boolean startAuthoring(ServerPlayer player, Mob worker) {
        if (player == null || worker == null ||
                !(worker.level() instanceof ServerLevel level) || player.level() != level ||
                !worker.isAlive() || worker.isRemoved() || !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            return false;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        if (!profile.ownerId().equals(player.getUUID()) || !profile.entityId().equals(worker.getUUID()) ||
                !profile.dimension().equals(level.dimension().location())) {
            return false;
        }

        // Determine natural job candidate based on equipped tools or existing job
        ResourceLocation candidateJob = WorkerJobs.MOVE;
        if (profile.job().isPresent()) {
            candidateJob = profile.job().get().type();
        } else {
            ItemStack held = worker.getMainHandItem();
            if (WorkerJobCapability.Registry.canPerformMining(worker, held)) {
                candidateJob = WorkerJobs.MINE;
            } else if (WorkerJobCapability.Registry.canPerformFarming(worker, held)) {
                candidateJob = WorkerJobs.FARM;
            }
        }

        Session session = new Session(
                player.getUUID(),
                worker.getUUID(),
                level.dimension().location(),
                candidateJob,
                Stage.JOB_AUTHORING,
                Optional.empty(),
                Optional.empty(),
                64L,
                System.currentTimeMillis(),
                Optional.empty(),
                List.of(),
                profile.job()
        );
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        String msg = profile.job().isPresent() ?
                "Job Reassignment activated. Point at a worksite block." :
                "Job Authoring activated. Point at a worksite block.";
        sendSync(player, toClientStageOrdinal(Stage.JOB_AUTHORING), msg);
        return true;
    }

    /**
     * Initiates order configuration mode for a player and authorized worker with explicit requested quantity and crop selection mode.
     */
    public static boolean startOrder(ServerPlayer player, Mob worker, ResourceLocation jobType, long requestedQuantity, CropSelectionMode cropSelectionMode) {
        if (player == null || worker == null || jobType == null || requestedQuantity <= 0 ||
                !(worker.level() instanceof ServerLevel level) || player.level() != level ||
                !worker.isAlive() || worker.isRemoved() || !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            return false;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        if (!profile.ownerId().equals(player.getUUID()) || !profile.entityId().equals(worker.getUUID()) ||
                !profile.dimension().equals(level.dimension().location())) {
            return false;
        }

        // Check worker capability for requested job type (auto-equip compatible tool from storage if available)
        WorkerExecutionLoop.tryEnsureToolEquippedForJob(worker, jobType);
        ItemStack heldTool = worker.getMainHandItem();
        if (!WorkerJobCapability.Registry.canPerform(jobType, worker, heldTool)) {
            return false;
        }

        Session session = new Session(
                player.getUUID(),
                worker.getUUID(),
                level.dimension().location(),
                jobType,
                Stage.SELECTING_WORKSITE,
                Optional.empty(),
                Optional.empty(),
                requestedQuantity,
                System.currentTimeMillis(),
                Optional.empty(),
                List.of(),
                profile.job(),
                Optional.empty(),
                cropSelectionMode != null ? cropSelectionMode : CropSelectionMode.ALL_SUPPORTED
        );
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        sendSync(player, toClientStageOrdinal(Stage.SELECTING_WORKSITE), "");
        return true;
    }

    public static boolean startOrder(ServerPlayer player, Mob worker, ResourceLocation jobType, long requestedQuantity) {
        return startOrder(player, worker, jobType, requestedQuantity, CropSelectionMode.ALL_SUPPORTED);
    }

    public static boolean startOrder(ServerPlayer player, Mob worker, ResourceLocation jobType) {
        return startOrder(player, worker, jobType, 64L, CropSelectionMode.ALL_SUPPORTED);
    }

    /**
     * Calculates the squared distance from a player eye position to the closest point on a block's bounding box.
     */
    public static double distanceSqrToBlock(Vec3 eyePos, BlockPos pos) {
        if (eyePos == null || pos == null) {
            return Double.MAX_VALUE;
        }
        return new AABB(pos).distanceToSqr(eyePos);
    }

    /**
     * Authoritative Minecraft block reach distance check matching vanilla ServerGamePacketListenerImpl.
     */
    public static boolean isPlayerReachValid(ServerPlayer player, BlockPos targetPos) {
        if (player == null || targetPos == null) {
            return false;
        }
        try {
            return player.canInteractWithBlock(targetPos, 1.0);
        } catch (Throwable fallback) {
            double distSqr = distanceSqrToBlock(player.getEyePosition(), targetPos);
            double maxReach = player.blockInteractionRange() + 1.0;
            return distSqr < maxReach * maxReach;
        }
    }

    private static boolean hasMatchingTicket(ServerPlayer player, UUID workerId) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        if (isTicketForWorker(mainHand, workerId) || isTicketForWorker(offHand, workerId)) {
            return true;
        }
        // Also check player inventory so hotbar switching does not reject
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isTicketForWorker(player.getInventory().getItem(i), workerId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTicketForWorker(ItemStack stack, UUID workerId) {
        if (stack.getItem() instanceof BindingTicketItem) {
            Optional<UUID> boundId = BindingTicketItem.getBoundWorkerId(stack);
            return boundId.isPresent() && boundId.get().equals(workerId);
        }
        return false;
    }

    /**
     * Processes worksite selection when player clicks a block.
     */
    public static StepResult selectWorksite(ServerPlayer player, BlockPos clickedPos, Direction clickedFace) {
        if (player == null || clickedPos == null || !(player.level() instanceof ServerLevel level)) {
            return StepResult.UNAVAILABLE;
        }
        Direction face = (clickedFace != null) ? clickedFace : Direction.UP;
        cleanExpired(player.getUUID());
        Session session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null || (session.stage() != Stage.SELECTING_WORKSITE && session.stage() != Stage.JOB_AUTHORING)) {
            return StepResult.UNAVAILABLE;
        }
        if (!session.dimension().equals(level.dimension().location())) {
            cancelOrder(player.getUUID());
            sendSync(player, 7, "Order dimension mismatch.");
            return StepResult.DENIED;
        }

        // 1. Resolve live worker
        var entity = level.getEntity(session.workerId());
        if (!(entity instanceof Mob worker) || !worker.isAlive() || worker.isRemoved() ||
                !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            cancelOrder(player.getUUID());
            sendSync(player, 7, "Worker entity no longer available.");
            return StepResult.UNAVAILABLE;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        if (!profile.ownerId().equals(player.getUUID()) || !profile.dimension().equals(level.dimension().location())) {
            cancelOrder(player.getUUID());
            sendSync(player, 7, "Unauthorized worker owner.");
            return StepResult.DENIED;
        }

        // 2. Validate ticket binding (held or in inventory)
        if (!hasMatchingTicket(player, session.workerId())) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Must possess the binding ticket for the worker.");
            return StepResult.REJECTED_WRONG_TICKET;
        }

        // 3. Player build permission check
        if (!player.mayBuild()) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Action denied: you lack build permissions at this location.");
            return StepResult.REJECTED_NO_PERMISSION;
        }

        // 4. Server-authoritative player reach distance check
        if (!isPlayerReachValid(player, clickedPos)) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Worksite is out of reach from player.");
            return StepResult.REJECTED_RANGE_PLAYER;
        }

        // 5. Smart job type resolution if in JOB_AUTHORING stage
        ResourceLocation jobType = session.jobType();
        BlockState state = level.getBlockState(clickedPos);
        if (session.stage() == Stage.JOB_AUTHORING) {
            if (CropRegistry.isSupportedCrop(state.getBlock()) || state.is(Blocks.FARMLAND)) {
                jobType = WorkerJobs.FARM;
            } else if (JobSafetyPolicy.isBlockBreakingSafe(level, clickedPos, state, player)) {
                jobType = WorkerJobs.MINE;
            }
        }

        // 6. Worksite adapter resolution
        WorksiteAdapter adapter = WorksiteAdapter.Registry.get(jobType).orElse(null);
        if (adapter == null) {
            cancelOrder(player.getUUID());
            return StepResult.DENIED;
        }

        // 7. Detailed job-specific worksite checks (never false-rejecting as Move)
        if (WorkerJobs.MINE.equals(jobType)) {
            JobSafetyPolicy.SafetyValidationResult safety = JobSafetyPolicy.evaluateBlockBreakingSafety(level, clickedPos, state, player);
            if (!safety.isPermitted()) {
                StepResult stepResult = switch (safety) {
                    case REJECTED_BLACKLISTED_BLOCK -> StepResult.REJECTED_BLACKLISTED;
                    case REJECTED_BLOCK_ENTITY -> StepResult.REJECTED_BLOCK_ENTITY;
                    case REJECTED_INDESTRUCTIBLE -> StepResult.REJECTED_INDESTRUCTIBLE;
                    case REJECTED_NO_PERMISSION -> StepResult.REJECTED_NO_PERMISSION;
                    default -> StepResult.INVALID_WORKSITE;
                };
                sendSync(player, toClientStageOrdinal(session.stage()), "Unsafe block for mining: check safety policies.");
                return stepResult;
            }
        } else if (WorkerJobs.FARM.equals(jobType)) {
            if (!adapter.isValidWorksite(level, clickedPos, face, state, player)) {
                sendSync(player, toClientStageOrdinal(session.stage()), "Invalid farm target: select a crop or farmland.");
                return StepResult.REJECTED_BLOCK_TYPE;
            }
        } else if (WorkerJobs.MOVE.equals(jobType)) {
            if (!adapter.isValidWorksite(level, clickedPos, face, state, player)) {
                sendSync(player, toClientStageOrdinal(session.stage()), "Invalid move target: surface above must be clear air.");
                return StepResult.REJECTED_SURFACE_OBSTRUCTED;
            }
        } else {
            if (!adapter.isValidWorksite(level, clickedPos, face, state, player)) {
                sendSync(player, toClientStageOrdinal(session.stage()), "Invalid worksite for this job type.");
                return StepResult.INVALID_WORKSITE;
            }
        }

        Optional<WorkerTarget> targetOpt = adapter.createTarget(level, clickedPos, face, state);
        if (targetOpt.isEmpty()) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Invalid worksite location.");
            return StepResult.INVALID_WORKSITE;
        }
        WorkerTarget target = targetOpt.get();

        // 8. Safety distance check from worker to worksite in true 3D space
        int maxWorksiteDist = Config.getMaxWorksiteDistance();
        if (worker.distanceToSqr(target.position().getX() + 0.5, target.position().getY() + 0.5, target.position().getZ() + 0.5) >
                (double) maxWorksiteDist * maxWorksiteDist) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Worksite too far: must be within " + maxWorksiteDist + " blocks of worker.");
            return StepResult.REJECTED_RANGE_WORKER;
        }

        // 9. If job is MOVE, it requires only a worksite; complete order immediately
        if (WorkerJobs.MOVE.equals(jobType)) {
            if (!(worker instanceof PathfinderMob pathfinder)) {
                cancelOrder(player.getUUID());
                return StepResult.UNAVAILABLE;
            }
            if (profile.job().isPresent()) {
                MinerJobController.reconcileInFlightMutation(worker);
                preservePendingJobOutputs(worker);
                worker.getNavigation().stop();
            }
            Optional<WorkerProfile> updated = profile.job().isPresent() ?
                    profile.replaceMove(player.getUUID(), pathfinder, target) :
                    profile.assignMove(player.getUUID(), pathfinder, target);
            if (updated.isEmpty()) {
                sendSync(player, toClientStageOrdinal(session.stage()), "Failed to assign move target.");
                return StepResult.INVALID_WORKSITE;
            }
            worker.setData(ExampleMod.WORKER_PROFILE, updated.get());
            cancelOrder(player.getUUID());
            sendSync(player, 5, "Move target assigned! Worker is navigating.");
            return StepResult.COMPLETED;
        }

        // Multi-stage jobs requiring storage transition to SELECTING_STORAGE
        Optional<Direction> tunnelDirection = Optional.empty();
        List<ResourceLocation> permittedCrops = List.of();

        if (WorkerJobs.MINE.equals(jobType)) {
            Direction dir = (face.getAxis().isHorizontal()) ?
                    face.getOpposite() : player.getDirection();
            tunnelDirection = Optional.of(dir);

            // Transmit OpenMinerConfigPayload to player for interactive configuration
            try {
                ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                List<String> candidateTags = state.getTags()
                        .map(tagKey -> "#" + tagKey.location().toString())
                        .toList();
                OpenMinerConfigPayload openPayload = new OpenMinerConfigPayload(
                        session.workerId(),
                        clickedPos,
                        dir,
                        session.requestedQuantity(),
                        blockKey != null ? blockKey.toString() : "",
                        candidateTags
                );
                PacketDistributor.sendToPlayer(player, openPayload);
            } catch (Throwable t) {
                ExampleMod.LOGGER.debug("OpenMinerConfigPayload send notice: {}", t.getMessage());
            }
        } else if (WorkerJobs.FARM.equals(jobType)) {
            if (session.cropSelectionMode() == CropSelectionMode.ALL_SUPPORTED) {
                // In ALL_SUPPORTED mode, clicking an anchor block (e.g. wheat, carrots, pumpkin, melon, cocoa, farmland)
                // establishes the worksite anchor, but does NOT restrict permitted crops.
                permittedCrops = List.of();
            } else {
                if (CropRegistry.isSupportedCrop(state.getBlock())) {
                    permittedCrops = List.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                } else if (level.isInWorldBounds(clickedPos.above()) &&
                        CropRegistry.isSupportedCrop(level.getBlockState(clickedPos.above()).getBlock())) {
                    permittedCrops = List.of(BuiltInRegistries.BLOCK.getKey(level.getBlockState(clickedPos.above()).getBlock()));
                } else if (state.is(Blocks.FARMLAND)) {
                    permittedCrops = CropRegistry.DEFAULT_FARMLAND_CROP_IDS;
                } else if (!session.permittedCrops().isEmpty()) {
                    permittedCrops = session.permittedCrops();
                } else {
                    permittedCrops = CropRegistry.DEFAULT_FARMLAND_CROP_IDS;
                }
            }
        }

        Session updatedSession = new Session(
                session.playerId(),
                session.workerId(),
                session.dimension(),
                jobType,
                Stage.SELECTING_STORAGE,
                Optional.of(target),
                Optional.empty(),
                session.requestedQuantity(),
                System.currentTimeMillis(),
                tunnelDirection,
                permittedCrops,
                session.previousJob(),
                session.minerConfig(),
                session.cropSelectionMode()
        );
        ACTIVE_SESSIONS.put(player.getUUID(), updatedSession);
        sendSync(player, toClientStageOrdinal(Stage.SELECTING_STORAGE), "");
        return StepResult.AWAITING_STORAGE;
    }

    /**
     * Processes storage destination selection when in SELECTING_STORAGE stage.
     */
    public static StepResult selectStorage(ServerPlayer player, BlockPos clickedPos) {
        if (player == null || clickedPos == null || !(player.level() instanceof ServerLevel level)) {
            return StepResult.UNAVAILABLE;
        }
        cleanExpired(player.getUUID());
        Session session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null || session.stage() != Stage.SELECTING_STORAGE || session.worksite().isEmpty()) {
            return StepResult.UNAVAILABLE;
        }
        if (!session.dimension().equals(level.dimension().location())) {
            cancelOrder(player.getUUID());
            sendSync(player, 7, "Order dimension mismatch.");
            return StepResult.DENIED;
        }

        // 1. Validate ticket binding (held or in inventory)
        if (!hasMatchingTicket(player, session.workerId())) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Must possess the binding ticket for the worker.");
            return StepResult.REJECTED_WRONG_TICKET;
        }

        // 2. Player build permission check
        if (!player.mayBuild()) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Action denied: you lack build permissions at this location.");
            return StepResult.REJECTED_NO_PERMISSION;
        }

        // 3. Server-authoritative player reach distance check
        if (!isPlayerReachValid(player, clickedPos)) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Storage container is out of reach from player.");
            return StepResult.REJECTED_RANGE_PLAYER;
        }

        // 4. Safety policy check: storage must be within bounded distance of worksite in true 3D space
        BlockPos worksitePos = session.worksite().get().position();
        int maxStorageDist = Config.getMaxStorageDistance();
        if (!JobSafetyPolicy.isStorageDistanceSafe(worksitePos, clickedPos, maxStorageDist)) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Storage container too far! Must be within " + maxStorageDist + " blocks of worksite.");
            return StepResult.REJECTED_RANGE_STORAGE;
        }

        // 5. Worksite revalidation before finalizing job assignment
        WorksiteAdapter worksiteAdapter = WorksiteAdapter.Registry.get(session.jobType()).orElse(null);
        if (worksiteAdapter == null) {
            cancelOrder(player.getUUID());
            return StepResult.DENIED;
        }
        BlockState worksiteState = level.getBlockState(worksitePos);
        if (WorkerJobs.MINE.equals(session.jobType())) {
            JobSafetyPolicy.SafetyValidationResult safety = JobSafetyPolicy.evaluateBlockBreakingSafety(level, worksitePos, worksiteState, player);
            if (!safety.isPermitted()) {
                cancelOrder(player.getUUID());
                sendSync(player, 7, "Worksite is no longer safe or valid.");
                return StepResult.INVALID_WORKSITE;
            }
        } else if (WorkerJobs.FARM.equals(session.jobType())) {
            if (!worksiteAdapter.isValidWorksite(level, worksitePos, Direction.UP, worksiteState, player)) {
                cancelOrder(player.getUUID());
                sendSync(player, 7, "Farm worksite is no longer valid.");
                return StepResult.INVALID_WORKSITE;
            }
        }

        BlockState state = level.getBlockState(clickedPos);
        if (!StorageDestinationAdapter.Registry.isValidStorage(level, clickedPos, state, player)) {
            sendSync(player, toClientStageOrdinal(session.stage()), "Invalid storage: click an accessible chest or barrel.");
            return StepResult.INVALID_STORAGE;
        }

        // 6. Resolve live worker and activate
        var entity = level.getEntity(session.workerId());
        if (!(entity instanceof Mob worker) || !worker.isAlive() || worker.isRemoved() ||
                !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            cancelOrder(player.getUUID());
            sendSync(player, 7, "Worker entity no longer available.");
            return StepResult.UNAVAILABLE;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        boolean isReplacement = profile.job().isPresent();
        if (isReplacement) {
            // 1. Reconcile in-flight mutation if active
            MinerJobController.reconcileInFlightMutation(worker);
            profile = worker.getData(ExampleMod.WORKER_PROFILE);
            if (profile.job().flatMap(WorkerJob::minerData).flatMap(MinerJobData::inFlightMutation).isPresent()) {
                sendSync(player, toClientStageOrdinal(session.stage()), "Cannot replace job: active block mutation unresolved.");
                return StepResult.REJECTED_WORKER_BUSY;
            }
            // 2. Preserve completed work / pending output items in WorkerStorage
            preservePendingJobOutputs(worker);
            // 3. Stop old navigation
            worker.getNavigation().stop();
        }

        WorkerTarget storageTarget = new WorkerTarget(level.dimension().location(), clickedPos);
        if (WorkerJobs.MINE.equals(session.jobType())) {
            WorkerExecutionLoop.ensureMiningToolEquipped(worker);
            Direction dir = session.direction().orElse(Direction.NORTH);
            MinerConfig config = session.minerConfig().orElse(null);
            MinerJobData minerData;
            if (config != null) {
                minerData = switch (config.mode()) {
                    case STAIR -> MinerJobData.initialStair(
                            session.requestedQuantity(),
                            storageTarget,
                            dir,
                            config.stairAscending(),
                            config.targetY(),
                            config.maxTravelDistance()
                    );
                    case SPECIFIC_BLOCK -> MinerJobData.initialSpecificBlock(
                            session.requestedQuantity(),
                            storageTarget,
                            dir,
                            config.searchRadius(),
                            config.targetBlockIds(),
                            config.targetBlockTags()
                    );
                    default -> MinerJobData.initialTunnel(
                            session.requestedQuantity(),
                            storageTarget,
                            dir,
                            config.maxTravelDistance()
                    );
                };
            } else {
                minerData = MinerJobData.initial(session.requestedQuantity(), storageTarget, dir, MinerJobData.ObjectiveMode.BLOCK_COUNT);
            }
            Optional<WorkerProfile> updated = isReplacement ?
                    profile.replaceMiner(player.getUUID(), worker, session.worksite().get(), minerData) :
                    profile.assignMiner(player.getUUID(), worker, session.worksite().get(), minerData);
            if (updated.isEmpty()) {
                cancelOrder(player.getUUID());
                sendSync(player, 7, "Failed to assign miner job.");
                return StepResult.DENIED;
            }
            worker.setData(ExampleMod.WORKER_PROFILE, updated.get());
        } else if (WorkerJobs.FARM.equals(session.jobType())) {
            WorkerExecutionLoop.ensureFarmingToolEquipped(worker);
            FarmerJobData farmerData = FarmerJobData.initial(
                    session.requestedQuantity(),
                    storageTarget,
                    session.cropSelectionMode(),
                    session.permittedCrops()
            );
            Optional<WorkerProfile> updated = isReplacement ?
                    profile.replaceFarmer(player.getUUID(), worker, session.worksite().get(), farmerData) :
                    profile.assignFarmer(player.getUUID(), worker, session.worksite().get(), farmerData);
            if (updated.isEmpty()) {
                cancelOrder(player.getUUID());
                sendSync(player, 7, "Failed to assign farmer job.");
                return StepResult.DENIED;
            }
            worker.setData(ExampleMod.WORKER_PROFILE, updated.get());
        }

        cancelOrder(player.getUUID());
        String completeMsg = isReplacement ?
                "Job replaced! Worker starting new task." :
                "Work order activated! Worker starting task.";
        sendSync(player, 5, completeMsg);
        return StepResult.COMPLETED;
    }

    /**
     * Authoritatively processes a MinerConfigSubmitPayload from a client.
     * Validates session presence, ownership, permissions, worker reach, and mode parameters.
     */
    public static boolean handleMinerConfigSubmit(ServerPlayer player, MinerConfigSubmitPayload payload) {
        if (player == null || payload == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        cleanExpired(player.getUUID());
        Session session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null) {
            sendConfigResponse(player, false, "No active order session found.");
            return false;
        }
        if (!session.workerId().equals(payload.workerId())) {
            sendConfigResponse(player, false, "Worker ID does not match active session.");
            return false;
        }
        if (!session.dimension().equals(level.dimension().location())) {
            sendConfigResponse(player, false, "Dimension mismatch.");
            return false;
        }
        if (!player.mayBuild()) {
            sendConfigResponse(player, false, "Action denied: you lack build permissions.");
            return false;
        }

        // Validate worksite distance from worker
        var entity = level.getEntity(session.workerId());
        if (!(entity instanceof Mob worker) || !worker.isAlive() || worker.isRemoved()) {
            sendConfigResponse(player, false, "Worker entity unavailable.");
            return false;
        }
        int maxWorksiteDist = Config.getMaxWorksiteDistance();
        if (worker.distanceToSqr(payload.anchorPos().getX() + 0.5, payload.anchorPos().getY() + 0.5, payload.anchorPos().getZ() + 0.5) >
                (double) maxWorksiteDist * maxWorksiteDist) {
            sendConfigResponse(player, false, "Worksite is too far from worker.");
            return false;
        }

        // Validate mode
        MinerJobData.MiningMode mode;
        try {
            mode = MinerJobData.MiningMode.valueOf(payload.mode());
        } catch (Exception e) {
            sendConfigResponse(player, false, "Invalid mining mode: " + payload.mode());
            return false;
        }

        // Validate quota
        long quota = payload.quota();
        if (quota < 1 || quota > 64000) {
            sendConfigResponse(player, false, "Quota must be between 1 and 64000.");
            return false;
        }

        // Validate travel distance
        int maxTravel = payload.maxTravelDistance();
        if (maxTravel <= 0 || maxTravel > Config.getMaxTravelDistance()) {
            maxTravel = Config.getMaxTravelDistance();
        }

        // Validate direction
        Direction dir = payload.direction();
        if (dir == null || !dir.getAxis().isHorizontal()) {
            dir = Direction.NORTH;
        }

        // Validate mode-specific parameters
        int targetY = payload.targetY();
        if (mode == MinerJobData.MiningMode.STAIR) {
            if (targetY < level.getMinBuildHeight() || targetY >= level.getMaxBuildHeight()) {
                sendConfigResponse(player, false, "Target Y is outside world boundaries.");
                return false;
            }
            if (payload.stairAscending() && targetY < payload.anchorPos().getY()) {
                sendConfigResponse(player, false, "Ascending stair requires Target Y >= anchor Y.");
                return false;
            }
            if (!payload.stairAscending() && targetY > payload.anchorPos().getY()) {
                sendConfigResponse(player, false, "Descending stair requires Target Y <= anchor Y.");
                return false;
            }
        }

        int searchRadius = payload.searchRadius();
        List<String> validIds = new ArrayList<>();
        List<String> validTags = new ArrayList<>();
        if (mode == MinerJobData.MiningMode.SPECIFIC_BLOCK) {
            if (searchRadius < 1 || searchRadius > 32) {
                sendConfigResponse(player, false, "Search radius must be between 1 and 32.");
                return false;
            }
            if (payload.targetBlockIds() != null) {
                for (String id : payload.targetBlockIds()) {
                    try {
                        ResourceLocation rl = ResourceLocation.parse(id);
                        if (BuiltInRegistries.BLOCK.containsKey(rl)) {
                            if (!validIds.contains(id)) {
                                validIds.add(id);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (payload.targetBlockTags() != null) {
                for (String tag : payload.targetBlockTags()) {
                    if (tag.startsWith("#")) {
                        try {
                            ResourceLocation.parse(tag.substring(1));
                            if (!validTags.contains(tag)) {
                                validTags.add(tag);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (validIds.isEmpty() && validTags.isEmpty()) {
                BlockState anchorState = level.getBlockState(payload.anchorPos());
                ResourceLocation anchorId = BuiltInRegistries.BLOCK.getKey(anchorState.getBlock());
                validIds.add(anchorId.toString());
            }
        }

        MinerConfig minerConfig = new MinerConfig(mode, maxTravel, payload.stairAscending(), targetY,
                searchRadius, validIds, validTags);

        Session updatedSession = new Session(
                session.playerId(),
                session.workerId(),
                session.dimension(),
                WorkerJobs.MINE,
                Stage.SELECTING_STORAGE,
                session.worksite().isPresent() ? session.worksite() : Optional.of(new WorkerTarget(session.dimension(), payload.anchorPos())),
                session.storage(),
                quota,
                System.currentTimeMillis(),
                Optional.of(dir),
                session.permittedCrops(),
                session.previousJob(),
                Optional.of(minerConfig),
                session.cropSelectionMode()
        );
        ACTIVE_SESSIONS.put(player.getUUID(), updatedSession);

        sendConfigResponse(player, true, "Miner configuration accepted! Right-click a storage container.");
        sendSync(player, toClientStageOrdinal(Stage.SELECTING_STORAGE), "Miner configuration accepted! Right-click a storage container.");
        return true;
    }

    private static void sendConfigResponse(ServerPlayer player, boolean accepted, String message) {
        if (player == null) return;
        try {
            PacketDistributor.sendToPlayer(player, new MinerConfigResponsePayload(accepted, message));
        } catch (Throwable t) {
            ExampleMod.LOGGER.debug("MinerConfigResponsePayload send notice: {}", t.getMessage());
        }
    }
}
