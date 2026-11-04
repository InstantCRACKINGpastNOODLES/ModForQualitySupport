package com.example.examplemod;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Adapter interface for validating whether a clicked block is a valid worksite for a specific job type.
 * Core systems interact with this contract instead of hardcoding mod blocks.
 */
public interface WorksiteAdapter {
    ResourceLocation jobType();

    boolean isValidWorksite(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state, ServerPlayer player);

    Optional<WorkerTarget> createTarget(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state);

    /**
     * Built-in registry for worksite adapters.
     */
    final class Registry {
        private static final Map<ResourceLocation, WorksiteAdapter> ADAPTERS = new ConcurrentHashMap<>();

        static {
            register(new MoveWorksiteAdapter());
            register(new MineWorksiteAdapter());
            register(new FarmWorksiteAdapter());
        }

        private Registry() {
        }

        public static void register(WorksiteAdapter adapter) {
            Objects.requireNonNull(adapter);
            ADAPTERS.put(adapter.jobType(), adapter);
        }

        public static Optional<WorksiteAdapter> get(ResourceLocation jobType) {
            if (jobType == null) return Optional.empty();
            return Optional.ofNullable(ADAPTERS.get(jobType));
        }

        public static void clear() {
            ADAPTERS.clear();
            register(new MoveWorksiteAdapter());
            register(new MineWorksiteAdapter());
            register(new FarmWorksiteAdapter());
        }
    }

    /**
     * Default worksite adapter for WorkerJobs.MOVE.
     * Requires top-face click on a solid block with air above, inside world bounds.
     */
    final class MoveWorksiteAdapter implements WorksiteAdapter {
        @Override
        public ResourceLocation jobType() {
            return WorkerJobs.MOVE;
        }

        @Override
        public boolean isValidWorksite(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state, ServerPlayer player) {
            if (level == null || pos == null || clickedFace == null || state == null || player == null ||
                    !level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !player.mayBuild()) {
                return false;
            }
            if (clickedFace != Direction.UP) {
                return false;
            }
            BlockPos above = pos.above();
            return level.isInWorldBounds(above) && level.getBlockState(above).isAir();
        }

        @Override
        public Optional<WorkerTarget> createTarget(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state) {
            if (level == null || pos == null || clickedFace != Direction.UP) {
                return Optional.empty();
            }
            return Optional.of(new WorkerTarget(level.dimension().location(), pos.above()));
        }
    }

    /**
     * Default worksite adapter for WorkerJobs.MINE.
     * Validates that the targeted block is within world bounds, permitted, and safe to break.
     */
    final class MineWorksiteAdapter implements WorksiteAdapter {
        @Override
        public ResourceLocation jobType() {
            return WorkerJobs.MINE;
        }

        @Override
        public boolean isValidWorksite(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state, ServerPlayer player) {
            if (level == null || pos == null || clickedFace == null || state == null || player == null ||
                    !level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !player.mayBuild()) {
                return false;
            }
            return JobSafetyPolicy.isBlockBreakingSafe(level, pos, state, player);
        }

        @Override
        public Optional<WorkerTarget> createTarget(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state) {
            if (level == null || pos == null) {
                return Optional.empty();
            }
            return Optional.of(new WorkerTarget(level.dimension().location(), pos));
        }
    }

    /**
     * Default worksite adapter for WorkerJobs.FARM.
     * Validates that the targeted block is within world bounds, permitted, and a valid farm anchor.
     */
    final class FarmWorksiteAdapter implements WorksiteAdapter {
        @Override
        public ResourceLocation jobType() {
            return WorkerJobs.FARM;
        }

        @Override
        public boolean isValidWorksite(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state, ServerPlayer player) {
            if (level == null || pos == null || state == null || player == null ||
                    !level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !player.mayBuild()) {
                return false;
            }
            if (JobSafetyPolicy.isBlockBlacklisted(state.getBlock())) {
                return false;
            }
            // Case 1: Directly targeted block is a supported crop
            if (CropRegistry.isSupportedCrop(state.getBlock())) {
                return true;
            }
            // Case 2: Block immediately above is a supported crop (e.g. clicking farmland beneath wheat)
            BlockPos above = pos.above();
            if (level.isInWorldBounds(above) && level.hasChunkAt(above) &&
                    CropRegistry.isSupportedCrop(level.getBlockState(above).getBlock())) {
                return true;
            }
            // Case 3: Farmland anchor (allows selecting tilled farm plots before or during crop growth)
            if (state.is(Blocks.FARMLAND)) {
                return true;
            }
            // Case 4: Jungle log anchor (supports cocoa plantations)
            if (state.is(BlockTags.JUNGLE_LOGS) || state.is(Blocks.JUNGLE_LOG) ||
                    state.is(Blocks.STRIPPED_JUNGLE_LOG) || state.is(Blocks.JUNGLE_WOOD) ||
                    state.is(Blocks.STRIPPED_JUNGLE_WOOD)) {
                return true;
            }
            // Case 5: Positional anchor on farm soil when session is active
            Optional<WorkerOrderSession.Session> sessionOpt = WorkerOrderSession.getSession(player.getUUID());
            if (sessionOpt.isPresent()) {
                if (!sessionOpt.get().permittedCrops().isEmpty()) {
                    return true;
                }
                if (sessionOpt.get().cropSelectionMode() == CropSelectionMode.ALL_SUPPORTED &&
                        (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT) ||
                         state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Optional<WorkerTarget> createTarget(ServerLevel level, BlockPos pos, Direction clickedFace, BlockState state) {
            if (level == null || pos == null) {
                return Optional.empty();
            }
            return Optional.of(new WorkerTarget(level.dimension().location(), pos));
        }
    }
}
