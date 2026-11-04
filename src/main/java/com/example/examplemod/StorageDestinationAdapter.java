package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validates delivery storage destinations for worker jobs.
 * Ensures storage is accessible, in loaded chunks, within safe distance, and permitted.
 */
public interface StorageDestinationAdapter {
    boolean isValidStorage(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player);

    final class Registry {
        private static final List<StorageDestinationAdapter> ADAPTERS = new ArrayList<>();

        static {
            register(new VanillaContainerStorageAdapter());
        }

        private Registry() {
        }

        public static synchronized void register(StorageDestinationAdapter adapter) {
            ADAPTERS.add(Objects.requireNonNull(adapter));
        }

        public static synchronized boolean isValidStorage(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
            for (StorageDestinationAdapter adapter : ADAPTERS) {
                if (adapter.isValidStorage(level, pos, state, player)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Default adapter for vanilla containers (chests, barrels, etc.).
     */
    final class VanillaContainerStorageAdapter implements StorageDestinationAdapter {
        @Override
        public boolean isValidStorage(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
            if (level == null || pos == null || state == null || player == null ||
                    !level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !player.mayBuild()) {
                return false;
            }
            if (state.getBlock() instanceof ChestBlock chestBlock) {
                Container container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
                if (container != null) {
                    return true;
                }
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            return blockEntity != null && !blockEntity.isRemoved() && blockEntity instanceof Container;
        }
    }

    /**
     * Safely resolves the live Container at a position, supporting compound Double Chests
     * and verifying that underlying BlockEntities are not removed.
     */
    static Optional<Container> resolveContainer(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (container != null) {
                return Optional.of(container);
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null && !blockEntity.isRemoved() && blockEntity instanceof Container container) {
            return Optional.of(container);
        }
        return Optional.empty();
    }

    /**
     * Durable storage delivery helper.
     * Inserts pending output first into the designated primary storage destination (with full compound double chest support).
     * If secondary storage is explicitly provided and authorized, remaining pending items are delivered there.
     * Automatic adjacent neighbor scanning is intentionally excluded to prevent unauthorized item leakage into private containers/furnaces/hoppers.
     * If authorized destinations cannot accept all items, transitions to WAITING_FOR_STORAGE without discarding items.
     */
    static WorkerProfile deliverToAuthorizedStorage(
            WorkerProfile profile,
            ServerLevel level,
            BlockPos primaryPos,
            Optional<BlockPos> secondaryPos,
            ServerPlayer owner
    ) {
        if (profile == null || level == null || primaryPos == null) {
            return profile;
        }

        return deliverToAuthorizedStorage(
                profile,
                primaryPos,
                secondaryPos,
                pos -> {
                    if (!level.hasChunkAt(pos)) {
                        return Optional.empty();
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!Registry.isValidStorage(level, pos, state, owner)) {
                        return Optional.empty();
                    }
                    return resolveContainer(level, pos);
                }
        );
    }

    /**
     * Pure container-resolver delivery engine.
     * Guarantees that only primaryPos and an optional authorized secondaryPos can ever be inspected or delivered to.
     * Adjacent neighbors are never inspected or scanned under any condition.
     */
    public static WorkerProfile deliverToAuthorizedStorage(
            WorkerProfile profile,
            BlockPos primaryPos,
            Optional<BlockPos> secondaryPos,
            java.util.function.Function<BlockPos, Optional<Container>> containerResolver
    ) {
        if (profile == null || primaryPos == null || containerResolver == null) {
            return profile;
        }

        WorkerProfile current = profile;
        java.util.Set<Container> visited = new java.util.HashSet<>();

        // 1. Deliver to designated primary storage
        Optional<Container> containerOpt = containerResolver.apply(primaryPos);
        if (containerOpt.isPresent()) {
            Container container = containerOpt.get();
            visited.add(container);
            current = deliverToSingleContainer(current, container);
        }

        // 2. Deliver to explicitly authorized secondary storage ONLY (if provided and distinct)
        if (hasPendingItems(current) && secondaryPos != null && secondaryPos.isPresent()) {
            BlockPos secPos = secondaryPos.get();
            if (!secPos.equals(primaryPos)) {
                Optional<Container> secContainerOpt = containerResolver.apply(secPos);
                if (secContainerOpt.isPresent()) {
                    Container secContainer = secContainerOpt.get();
                    if (visited.add(secContainer)) {
                        current = deliverToSingleContainer(current, secContainer);
                    }
                }
            }
        }

        return current;
    }

    /**
     * Strictly delivers pending items to the designated storage container.
     * Fully preserves vanilla compound double-chest resolution.
     * Arbitrary adjacent neighbor container scanning is strictly eliminated to prevent unauthorized container leakage.
     */
    public static WorkerProfile deliverToDesignatedStorage(
            WorkerProfile profile,
            ServerLevel level,
            BlockPos storagePos,
            ServerPlayer owner
    ) {
        return deliverToAuthorizedStorage(profile, level, storagePos, Optional.empty(), owner);
    }

    public static WorkerProfile deliverToDesignatedStorage(
            WorkerProfile profile,
            BlockPos storagePos,
            java.util.function.Function<BlockPos, Optional<Container>> containerResolver
    ) {
        return deliverToAuthorizedStorage(profile, storagePos, Optional.empty(), containerResolver);
    }

    /**
     * Backward-compatible alias for deliverToDesignatedStorage.
     * Note: Neighbor fallback was completely removed; only the designated storage receives items.
     */
    @Deprecated
    public static WorkerProfile deliverWithNeighborFallback(
            WorkerProfile profile,
            ServerLevel level,
            BlockPos storagePos,
            ServerPlayer owner
    ) {
        return deliverToDesignatedStorage(profile, level, storagePos, owner);
    }

    private static WorkerProfile deliverToSingleContainer(WorkerProfile profile, Container container) {
        if (profile == null || profile.job().isEmpty() || container == null) {
            return profile;
        }
        var jobType = profile.job().get().type();
        if (WorkerJobs.MINE.equals(jobType)) {
            return MinerJobController.deliverToContainer(profile, container);
        } else if (WorkerJobs.FARM.equals(jobType)) {
            return FarmerJobController.deliverToContainer(profile, container);
        }
        return profile;
    }

    private static boolean hasPendingItems(WorkerProfile profile) {
        if (profile == null || profile.job().isEmpty()) return false;
        var job = profile.job().get();
        if (job.minerData().isPresent()) {
            return job.minerData().get().itemsPendingDelivery() > 0;
        } else if (job.farmerData().isPresent()) {
            return job.farmerData().get().itemsPendingDelivery() > 0;
        }
        return false;
    }
}
