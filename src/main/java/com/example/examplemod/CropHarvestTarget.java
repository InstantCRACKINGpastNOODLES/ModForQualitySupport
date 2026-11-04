package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validates whether a crop block in the world is eligible for autonomous harvesting.
 * Integrates with CropRegistry to support vanilla and modded crop adapters.
 */
public final class CropHarvestTarget {
    public static final int MAX_DISTANCE = 8;

    private CropHarvestTarget() {
    }

    public static boolean eligible(BlockState state, boolean loaded, boolean permitted, double distanceSquared) {
        if (!loaded || !permitted || distanceSquared > MAX_DISTANCE * MAX_DISTANCE || state == null) {
            return false;
        }
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return CropRegistry.isMature(null, BlockPos.ZERO, state);
    }

    public static boolean eligible(ServerLevel level, Mob worker, ServerPlayer owner, BlockPos pos) {
        if (level == null || worker == null || owner == null || pos == null ||
                !level.getServer().isSameThread() || worker.level() != level || owner.level() != level ||
                !worker.isAlive() || worker.isRemoved() || !level.hasChunkAt(pos) ||
                !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            return false;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        if (!profile.entityId().equals(worker.getUUID()) || !profile.ownerId().equals(owner.getUUID()) ||
                !profile.dimension().equals(level.dimension().location())) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        double distSq = worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > MAX_DISTANCE * MAX_DISTANCE || !owner.mayBuild()) {
            return false;
        }
        return CropRegistry.isMature(level, pos, state);
    }
}
