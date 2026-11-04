package com.example.examplemod;

import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Central, non-bypassable safety policy for worker operations, order validation, and world modification.
 * Enforces strict DEFAULT-DENY tool validation, per-tick world-change rate limits, player-controlled
 * target quantities, bounded work envelopes, block-entity protection, and tool durability constraints.
 */
public final class JobSafetyPolicy {

    public enum SafetyValidationResult {
        PERMITTED,
        REJECTED_UNVERIFIED_TOOL,
        REJECTED_UNBOUNDED_EFFECT,
        REJECTED_TOOL_DURABILITY,
        REJECTED_TOOL_REPLACED,
        REJECTED_OUT_OF_BOUNDS,
        REJECTED_BLACKLISTED_BLOCK,
        REJECTED_BLOCK_ENTITY,
        REJECTED_INDESTRUCTIBLE,
        REJECTED_ACTION_BUDGET_EXCEEDED,
        REJECTED_JOB_BUDGET_EXCEEDED,
        REJECTED_WORKER_QUOTA_EXCEEDED,
        REJECTED_TICK_RATE_EXCEEDED,
        REJECTED_TARGET_QUANTITY_REACHED,
        REJECTED_NO_PERMISSION,
        REJECTED_UNAVAILABLE;

        public boolean isPermitted() {
            return this == PERMITTED;
        }
    }

    /** Maximum allowed horizontal distance from a configured worksite (blocks). */
    public static final int DEFAULT_MAX_WORK_RADIUS = 32;

    /** Maximum allowed scan radius around a worksite (blocks). Prevents world-scale scanning. */
    public static final int DEFAULT_MAX_SCAN_RADIUS = 16;

    /** Maximum allowed vertical delta (+/- blocks) from a configured worksite Y level, supporting deep shafts. */
    public static final int DEFAULT_MAX_VERTICAL_DELTA = 64;

    /** Hard ceiling on blocks that may be altered in a single tick / discrete action. */
    public static final int DEFAULT_MAX_BLOCKS_PER_ACTION = 1;
    public static final int DEFAULT_MAX_BLOCKS_PER_TICK = 1;

    /** Default allowed distance between a worksite and its designated delivery storage (blocks). */
    public static final int DEFAULT_MAX_STORAGE_DISTANCE = 32;

    /** Default allowed distance between worker entity and worksite at order authoring time (blocks). */
    public static final int DEFAULT_MAX_WORKSITE_ASSIGNMENT_DISTANCE = 128;

    /** Default allowed travel distance for worker navigation (blocks). */
    public static final int DEFAULT_MAX_TRAVEL_DISTANCE = 128;

    /** Minimum remaining durability on a damageable tool before stopping worker action. */
    public static final int MIN_TOOL_DURABILITY = 2;

    /** Hard block blacklist that workers must NEVER break or alter under any circumstances. */
    private static final Set<Block> HARD_BLACKLIST = Set.of(
            Blocks.BEDROCK,
            Blocks.BARRIER,
            Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK,
            Blocks.STRUCTURE_BLOCK,
            Blocks.JIGSAW,
            Blocks.SPAWNER,
            Blocks.END_PORTAL,
            Blocks.END_PORTAL_FRAME,
            Blocks.END_GATEWAY,
            Blocks.NETHER_PORTAL
    );

    public static boolean isBlockBlacklisted(Block block) {
        return block != null && HARD_BLACKLIST.contains(block);
    }

    private JobSafetyPolicy() {
    }

    /**
     * Verifies that a target block is within safe bounded distance of the worksite.
     * Enforces horizontal radius and vertical delta limits in true 3D space with 64-bit numerical safety.
     * Promotes coordinates to long prior to subtraction to prevent 32-bit integer overflow.
     */
    public static boolean isWithinWorkBounds(BlockPos worksite, BlockPos targetPos, int maxRadius, int maxVerticalDelta) {
        if (worksite == null || targetPos == null) {
            return false;
        }
        long dx = (long) targetPos.getX() - worksite.getX();
        long dy = (long) targetPos.getY() - worksite.getY();
        long dz = (long) targetPos.getZ() - worksite.getZ();
        return (dx * dx + dz * dz <= (long) maxRadius * maxRadius) && Math.abs(dy) <= maxVerticalDelta;
    }

    public static boolean isWithinWorkBounds(BlockPos worksite, BlockPos targetPos) {
        return isWithinWorkBounds(worksite, targetPos, Config.getMaxWorkRadius(), Config.getMaxVerticalDelta());
    }

    /**
     * Verifies that a target block is within true 3D Euclidean distance of the worksite.
     * Promotes coordinates to long prior to subtraction to prevent 32-bit integer overflow.
     */
    public static boolean isWithin3DWorkBounds(BlockPos worksite, BlockPos targetPos, double maxDistance) {
        if (worksite == null || targetPos == null) {
            return false;
        }
        long dx = (long) targetPos.getX() - worksite.getX();
        long dy = (long) targetPos.getY() - worksite.getY();
        long dz = (long) targetPos.getZ() - worksite.getZ();
        return ((double) (dx * dx + dy * dy + dz * dz)) <= maxDistance * maxDistance;
    }

    public static boolean isWithin3DWorkBounds(BlockPos worksite, BlockPos targetPos) {
        return isWithin3DWorkBounds(worksite, targetPos, Config.getMaxWorkRadius());
    }

    /**
     * Verifies that a target block is strictly contained within a straight 1x2 humanoid tunnel corridor.
     * Enforces:
     * - Height is strictly feet (dy=0) or head (dy=1).
     * - Perpendicular horizontal axis offset is strictly 0 (no lateral deviation or widening).
     * - Distance along direction vector is non-negative and strictly within maxDepth.
     * - Completely independent of horizontal operating radius (MAX_WORK_RADIUS).
     */
    public static boolean isWithinTunnelBounds(BlockPos anchor, Direction dir, BlockPos targetPos, int maxDepth) {
        if (anchor == null || dir == null || targetPos == null || maxDepth <= 0) {
            return false;
        }
        long dx = (long) targetPos.getX() - anchor.getX();
        long dy = (long) targetPos.getY() - anchor.getY();
        long dz = (long) targetPos.getZ() - anchor.getZ();

        if (dy < 0 || dy > 1) {
            return false;
        }

        long stepX = dir.getStepX();
        long stepZ = dir.getStepZ();

        if (stepX != 0) {
            if (dz != 0) return false;
            long d = dx * stepX;
            return d >= 0 && d < maxDepth;
        } else if (stepZ != 0) {
            if (dx != 0) return false;
            long d = dz * stepZ;
            return d >= 0 && d < maxDepth;
        }
        return false;
    }

    /**
     * Verifies that the tool has sufficient durability to perform an operation of the given durability cost
     * without breaking the tool and without depleting remaining durability below MIN_TOOL_DURABILITY.
     * Rejects damageable tools with unknown or non-positive durability costs (<= 0).
     * Non-damageable tools (including verified infinite tools) have no durability consumption and return true.
     */
    public static boolean hasSufficientDurability(ItemStack tool, int durabilityCost) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        if (!tool.isDamageableItem()) {
            return true;
        }
        if (durabilityCost <= 0) {
            return false;
        }
        int remaining = tool.getMaxDamage() - tool.getDamageValue();
        return remaining > durabilityCost && (remaining - durabilityCost) >= MIN_TOOL_DURABILITY;
    }

    /**
     * Checks whether a tool currently has at least MIN_TOOL_DURABILITY remaining.
     * Non-damageable tools (e.g. Avaritia infinite tools) always return true
     * because they do not lose durability and cannot break.
     */
    public static boolean hasSufficientDurability(ItemStack tool) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        if (!tool.isDamageableItem()) {
            return true;
        }
        return (tool.getMaxDamage() - tool.getDamageValue()) >= MIN_TOOL_DURABILITY;
    }

    /**
     * Backward-compatible check for tool durability safety.
     * Note: Durability alone does NOT authorize a tool for autonomous block breaking.
     * For autonomous mining/breaking authorization, use isMiningToolAuthorized.
     */
    public static boolean isToolSafeToUse(ItemStack tool) {
        return hasSufficientDurability(tool);
    }

    /**
     * Verifies that a tool is explicitly recognized as safe by a registered MiningToolCapability provider.
     * Enforces default-deny for all unknown, nonstandard, or modded tools.
     */
    public static boolean isMiningToolVerified(ItemStack tool) {
        return MiningToolCapability.Registry.isVerifiedSafe(tool);
    }

    /**
     * Authorizes a tool for autonomous mining by checking durability, verified capability,
     * and bounded action effect against the per-tick rate limit.
     */
    public static boolean isMiningToolAuthorized(ItemStack tool, int plannedBlocksInTick, int maxBlocksPerTick) {
        if (tool == null || tool.isEmpty() || !isMiningToolVerified(tool)) {
            return false;
        }
        if (!hasSufficientDurability(tool, plannedBlocksInTick)) {
            return false;
        }
        int maxBlocks = MiningToolCapability.Registry.getMaxBlocksPerAction(tool);
        if (maxBlocks <= 0) {
            return false;
        }
        return plannedBlocksInTick > 0 && plannedBlocksInTick <= maxBlocks && plannedBlocksInTick <= maxBlocksPerTick;
    }

    public static boolean isMiningToolAuthorized(ItemStack tool, int plannedBlocks) {
        return isMiningToolAuthorized(tool, plannedBlocks, DEFAULT_MAX_BLOCKS_PER_TICK);
    }

    /**
     * Verifies that the currently held tool matches the tool that was validated at job start,
     * and that it has not been replaced with an unknown or damaged tool.
     */
    public static boolean isToolConsistent(ItemStack currentTool, ItemStack expectedTool) {
        if (currentTool == null || currentTool.isEmpty() || expectedTool == null || expectedTool.isEmpty()) {
            return false;
        }
        if (currentTool.getItem() != expectedTool.getItem()) {
            return false;
        }
        return isMiningToolVerified(currentTool) && hasSufficientDurability(currentTool);
    }

    /**
     * Verifies that storage is within the allowed safe radius of a worksite in true 3D space.
     * Uses 64-bit integer arithmetic to prevent squared-distance overflow.
     * Promotes coordinates to long prior to subtraction to prevent 32-bit integer overflow.
     */
    public static boolean isStorageDistanceSafe(BlockPos worksite, BlockPos storagePos, int maxDistance) {
        if (worksite == null || storagePos == null) {
            return false;
        }
        long dx = (long) storagePos.getX() - worksite.getX();
        long dy = (long) storagePos.getY() - worksite.getY();
        long dz = (long) storagePos.getZ() - worksite.getZ();
        return (dx * dx + dy * dy + dz * dz) <= (long) maxDistance * maxDistance;
    }

    public static boolean isStorageDistanceSafe(BlockPos worksite, BlockPos storagePos) {
        return isStorageDistanceSafe(worksite, storagePos, DEFAULT_MAX_STORAGE_DISTANCE);
    }

    public static boolean isConfiguredStorageDistanceSafe(BlockPos worksite, BlockPos storagePos) {
        return isStorageDistanceSafe(worksite, storagePos, Config.getMaxStorageDistance());
    }

    /**
     * Validates an operation count against a player-requested target quantity.
     */
    public static boolean isBudgetAvailable(long performedCount, long targetQuantity) {
        return performedCount >= 0 && targetQuantity > 0 && performedCount < targetQuantity;
    }

    public static boolean isJobBudgetAvailable(long performedCount, int plannedBlocks, long targetQuantity) {
        return isProgressWithinTarget(performedCount, plannedBlocks, targetQuantity);
    }

    /**
     * Validates whether progress is within the player-controlled requested quantity.
     * Enforces pre-mutation safety: planned blocks in the tick must not exceed the remaining quota.
     * Uses 64-bit long arithmetic to safely support arbitrarily large player requests (e.g. 100,000,000 blocks).
     */
    public static boolean isProgressWithinTarget(long performedQuantity, int plannedBlocksInTick, long playerRequestedQuantity) {
        if (performedQuantity < 0 || plannedBlocksInTick <= 0 || playerRequestedQuantity <= 0) {
            return false;
        }
        if (performedQuantity >= playerRequestedQuantity) {
            return false;
        }
        long remaining = playerRequestedQuantity - performedQuantity;
        return (long) plannedBlocksInTick <= remaining;
    }

    /**
     * Validates whether a per-tick block alteration rate is within configured limits.
     */
    public static boolean isTickRateSafe(int blocksInTick, int maxBlocksPerTick) {
        return blocksInTick > 0 && blocksInTick <= maxBlocksPerTick;
    }

    public static boolean isTickRateSafe(int blocksInTick) {
        return isTickRateSafe(blocksInTick, DEFAULT_MAX_BLOCKS_PER_TICK);
    }

    /**
     * Evaluates block-breaking safety and returns specific rejection reason or PERMITTED.
     */
    public static SafetyValidationResult evaluateBlockBreakingSafety(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer owner) {
        if (level == null || pos == null || state == null || owner == null ||
                !level.getServer().isSameThread() || owner.level() != level || owner.isRemoved() ||
                !level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
            return SafetyValidationResult.REJECTED_UNAVAILABLE;
        }

        if (!owner.mayBuild()) {
            return SafetyValidationResult.REJECTED_NO_PERMISSION;
        }

        if (HARD_BLACKLIST.contains(state.getBlock())) {
            return SafetyValidationResult.REJECTED_BLACKLISTED_BLOCK;
        }

        if (state.getDestroySpeed(level, pos) < 0) {
            return SafetyValidationResult.REJECTED_INDESTRUCTIBLE;
        }

        if (state.hasBlockEntity()) {
            return SafetyValidationResult.REJECTED_BLOCK_ENTITY;
        }

        return SafetyValidationResult.PERMITTED;
    }

    /**
     * Central authorization check for block-breaking operations without full context.
     */
    public static boolean isBlockBreakingSafe(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer owner) {
        return evaluateBlockBreakingSafety(level, pos, state, owner).isPermitted();
    }

    /**
     * Central, non-bypassable pre-mutation validation for all autonomous block-modifying actions.
     * Evaluates multiple independent hard limits BEFORE any world change can occur:
     * - Server thread, loaded chunk, world bounds, owner build permissions.
     * - Hard blacklists (bedrock, command blocks, portals, etc.).
     * - Block entity protection (chests, machines, etc.).
     * - Worksite boundary limits (horizontal radius and vertical delta).
     * - Explicit tool verification (default-deny for unknown/Avaritia tools).
     * - Tool replacement and durability check (for damageable items).
     * - Discrete per-tick world change limit.
     * - Player-controlled requested total quantity.
     */
    public static SafetyValidationResult validateBlockMutation(
            ServerLevel level,
            BlockPos worksite,
            BlockPos targetPos,
            BlockState state,
            ServerPlayer owner,
            ItemStack currentTool,
            ItemStack expectedTool,
            int plannedBlocksInTick,
            long performedQuantity,
            long playerRequestedQuantity,
            int maxBlocksPerTick
    ) {
        if (level == null || targetPos == null || state == null || owner == null ||
                !level.getServer().isSameThread() || owner.level() != level || owner.isRemoved() ||
                !level.isInWorldBounds(targetPos) || !level.hasChunkAt(targetPos)) {
            return SafetyValidationResult.REJECTED_UNAVAILABLE;
        }

        if (!owner.mayBuild()) {
            return SafetyValidationResult.REJECTED_NO_PERMISSION;
        }

        if (worksite != null && !isWithinWorkBounds(worksite, targetPos)) {
            return SafetyValidationResult.REJECTED_OUT_OF_BOUNDS;
        }

        if (HARD_BLACKLIST.contains(state.getBlock())) {
            return SafetyValidationResult.REJECTED_BLACKLISTED_BLOCK;
        }

        if (state.getDestroySpeed(level, targetPos) < 0) {
            return SafetyValidationResult.REJECTED_INDESTRUCTIBLE;
        }

        if (state.hasBlockEntity()) {
            return SafetyValidationResult.REJECTED_BLOCK_ENTITY;
        }

        if (expectedTool != null && !isToolConsistent(currentTool, expectedTool)) {
            return SafetyValidationResult.REJECTED_TOOL_REPLACED;
        }

        if (currentTool == null || currentTool.isEmpty() || !isMiningToolVerified(currentTool)) {
            return SafetyValidationResult.REJECTED_UNVERIFIED_TOOL;
        }

        if (!hasSufficientDurability(currentTool, plannedBlocksInTick)) {
            return SafetyValidationResult.REJECTED_TOOL_DURABILITY;
        }

        int maxToolBlocks = MiningToolCapability.Registry.getMaxBlocksPerAction(currentTool);
        if (maxToolBlocks <= 0) {
            return SafetyValidationResult.REJECTED_UNBOUNDED_EFFECT;
        }

        if (plannedBlocksInTick <= 0 || plannedBlocksInTick > maxToolBlocks || plannedBlocksInTick > maxBlocksPerTick) {
            return SafetyValidationResult.REJECTED_TICK_RATE_EXCEEDED;
        }

        if (!isProgressWithinTarget(performedQuantity, plannedBlocksInTick, playerRequestedQuantity)) {
            return SafetyValidationResult.REJECTED_TARGET_QUANTITY_REACHED;
        }

        return SafetyValidationResult.PERMITTED;
    }

    public static SafetyValidationResult validateBlockMutation(
            ServerLevel level,
            BlockPos worksite,
            BlockPos targetPos,
            BlockState state,
            ServerPlayer owner,
            ItemStack currentTool,
            ItemStack expectedTool,
            int plannedBlocksInTick,
            long performedQuantity,
            long playerRequestedQuantity
    ) {
        return validateBlockMutation(
                level, worksite, targetPos, state, owner, currentTool, expectedTool,
                plannedBlocksInTick, performedQuantity, playerRequestedQuantity, DEFAULT_MAX_BLOCKS_PER_TICK
        );
    }
}
