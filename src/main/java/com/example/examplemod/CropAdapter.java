package com.example.examplemod;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Extensible adapter interface representing a crop type for autonomous worker farming.
 * Defines maturity verification, planting item requirements, replanting conditions, and placement.
 * Implementations allow seamless integration with vanilla crops and third-party modded crops
 * (e.g. Farmer's Delight, Mystical Agriculture) without hard compile-time dependencies.
 */
public interface CropAdapter {

    /**
     * Unique identifier for this crop adapter.
     */
    ResourceLocation id();

    /**
     * Determines whether this adapter handles the specified block.
     */
    boolean matches(Block block);

    /**
     * Checks if the crop at the specified world position is fully mature and eligible for harvesting.
     */
    boolean isMature(LevelReader level, BlockPos pos, BlockState state);

    /**
     * Checks if the given block state represents a young or replanted state (e.g. age 0).
     */
    default boolean isReplantedState(LevelReader level, BlockPos pos, BlockState state) {
        return false;
    }

    /**
     * Identifies the item required to replant this crop at the specified position.
     * Returns Optional.empty() if this crop does not require or support replanting.
     */
    Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state);

    /**
     * Validates whether all environmental and support requirements are satisfied to replant
     * at the given position (e.g. farmland below for standard crops, jungle log surface for cocoa).
     */
    boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState);

    /**
     * Returns the BlockState that should be placed upon replanting (typically age 0 with matching orientation).
     */
    Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState);

    /**
     * Executes the server-authoritative replanting mutation in the world.
     * Returns true if placement was successfully applied and verified.
     */
    boolean replant(ServerLevel level, BlockPos pos, BlockState previousState);

    /**
     * Replants using LevelAccessor for test and level abstraction compatibility.
     */
    default boolean replant(LevelAccessor level, BlockPos pos, BlockState previousState) {
        if (level instanceof ServerLevel sl) {
            return replant(sl, pos, previousState);
        }
        return false;
    }

    /**
     * Indicates whether this crop requires seed deduction and replanting upon harvest.
     * Fruit-yielding crops (like Pumpkin and Melon) preserve stems and return false.
     */
    default boolean requiresReplant() {
        return getPlantingItem(null, BlockPos.ZERO, null).isPresent();
    }

    /**
     * Number of quota units credited upon successfully harvesting this crop.
     */
    default int getHarvestQuotaUnits(BlockState state) {
        return 1;
    }

    /**
     * Validates a candidate crop target, categorizing readiness.
     */
    default CropOperationResult validateTarget(LevelReader level, BlockPos pos, BlockState state) {
        if (state == null || !matches(state.getBlock())) {
            return CropOperationResult.INVALID_TARGET;
        }
        if (!isMature(level, pos, state)) {
            return CropOperationResult.NOT_MATURE;
        }
        return CropOperationResult.HARVESTED;
    }
}
