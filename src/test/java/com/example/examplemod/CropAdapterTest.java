package com.example.examplemod;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CropAdapterTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        CropRegistry.registerDefaults();
    }

    @Test
    void wheatMaturityAndPlantingItem() {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState mature = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);
        BlockState immature = wheat.defaultBlockState().setValue(CropBlock.AGE, 6);

        assertTrue(CropRegistry.isSupportedCrop(Blocks.WHEAT));
        assertTrue(CropRegistry.isMature(null, BlockPos.ZERO, mature));
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, immature));

        Optional<Item> seed = CropRegistry.getPlantingItem(null, BlockPos.ZERO, mature);
        assertTrue(seed.isPresent());
        assertEquals(Items.WHEAT_SEEDS, seed.get());

        Optional<BlockState> replantState = CropRegistry.getReplantState(null, BlockPos.ZERO, mature);
        assertTrue(replantState.isPresent());
        assertEquals(0, replantState.get().getValue(CropBlock.AGE));
        assertEquals(Blocks.WHEAT, replantState.get().getBlock());
        assertTrue(CropRegistry.isReplanted(null, BlockPos.ZERO, replantState.get()));
        assertFalse(CropRegistry.isReplanted(null, BlockPos.ZERO, mature));
    }

    @Test
    void carrotMaturityAndPlantingItem() {
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        BlockState mature = carrots.defaultBlockState().setValue(CropBlock.AGE, 7);
        BlockState immature = carrots.defaultBlockState().setValue(CropBlock.AGE, 2);

        assertTrue(CropRegistry.isSupportedCrop(Blocks.CARROTS));
        assertTrue(CropRegistry.isMature(null, BlockPos.ZERO, mature));
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, immature));

        Optional<Item> seed = CropRegistry.getPlantingItem(null, BlockPos.ZERO, mature);
        assertTrue(seed.isPresent());
        assertEquals(Items.CARROT, seed.get());

        Optional<BlockState> replantState = CropRegistry.getReplantState(null, BlockPos.ZERO, mature);
        assertTrue(replantState.isPresent());
        assertEquals(0, replantState.get().getValue(CropBlock.AGE));
    }

    @Test
    void potatoMaturityAndPlantingItem() {
        CropBlock potatoes = (CropBlock) Blocks.POTATOES;
        BlockState mature = potatoes.defaultBlockState().setValue(CropBlock.AGE, 7);
        BlockState immature = potatoes.defaultBlockState().setValue(CropBlock.AGE, 0);

        assertTrue(CropRegistry.isSupportedCrop(Blocks.POTATOES));
        assertTrue(CropRegistry.isMature(null, BlockPos.ZERO, mature));
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, immature));

        Optional<Item> seed = CropRegistry.getPlantingItem(null, BlockPos.ZERO, mature);
        assertTrue(seed.isPresent());
        assertEquals(Items.POTATO, seed.get());

        Optional<BlockState> replantState = CropRegistry.getReplantState(null, BlockPos.ZERO, mature);
        assertTrue(replantState.isPresent());
        assertEquals(0, replantState.get().getValue(CropBlock.AGE));
    }

    @Test
    void beetrootMaturityAndPlantingItem() {
        BeetrootBlock beetroot = (BeetrootBlock) Blocks.BEETROOTS;
        BlockState mature = beetroot.defaultBlockState().setValue(BeetrootBlock.AGE, 3);
        BlockState immature = beetroot.defaultBlockState().setValue(BeetrootBlock.AGE, 2);

        assertTrue(CropRegistry.isSupportedCrop(Blocks.BEETROOTS));
        assertTrue(CropRegistry.isMature(null, BlockPos.ZERO, mature));
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, immature));

        Optional<Item> seed = CropRegistry.getPlantingItem(null, BlockPos.ZERO, mature);
        assertTrue(seed.isPresent());
        assertEquals(Items.BEETROOT_SEEDS, seed.get());

        Optional<BlockState> replantState = CropRegistry.getReplantState(null, BlockPos.ZERO, mature);
        assertTrue(replantState.isPresent());
        assertEquals(0, replantState.get().getValue(BeetrootBlock.AGE));
    }

    @Test
    void cocoaMaturityFacingAndPlantingItem() {
        BlockState matureNorth = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 2);
        BlockState immature = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 1);

        assertTrue(CropRegistry.isSupportedCrop(Blocks.COCOA));
        assertTrue(CropRegistry.isMature(null, BlockPos.ZERO, matureNorth));
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, immature));

        Optional<Item> seed = CropRegistry.getPlantingItem(null, BlockPos.ZERO, matureNorth);
        assertTrue(seed.isPresent());
        assertEquals(Items.COCOA_BEANS, seed.get());

        // Replant state must preserve directional facing
        Optional<BlockState> replantState = CropRegistry.getReplantState(null, BlockPos.ZERO, matureNorth);
        assertTrue(replantState.isPresent());
        assertEquals(Direction.NORTH, replantState.get().getValue(CocoaBlock.FACING));
        assertEquals(0, replantState.get().getValue(CocoaBlock.AGE));
        assertTrue(CropRegistry.isReplanted(null, BlockPos.ZERO, replantState.get()));
        assertFalse(CropRegistry.isReplanted(null, BlockPos.ZERO, matureNorth));
    }

    @Test
    void nonCropsAreIgnoredSafely() {
        assertFalse(CropRegistry.isSupportedCrop(Blocks.STONE));
        assertFalse(CropRegistry.isSupportedCrop(Blocks.DIRT));
        assertFalse(CropRegistry.isSupportedCrop(Blocks.AIR));
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, Blocks.STONE.defaultBlockState()));
        assertTrue(CropRegistry.getPlantingItem(null, BlockPos.ZERO, Blocks.STONE.defaultBlockState()).isEmpty());
        assertTrue(CropRegistry.getReplantState(null, BlockPos.ZERO, Blocks.STONE.defaultBlockState()).isEmpty());
    }

    @Test
    void extensibleCropAdapterRegistration() {
        ResourceLocation customId = ResourceLocation.fromNamespaceAndPath("testmod", "bamboo_crop");
        CropAdapter mockAdapter = new CropAdapter() {
            @Override
            public ResourceLocation id() { return customId; }
            @Override
            public boolean matches(Block block) { return block == Blocks.BAMBOO; }
            @Override
            public boolean isMature(LevelReader level, BlockPos pos, BlockState state) { return true; }
            @Override
            public Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state) {
                return Optional.of(Items.BAMBOO);
            }
            @Override
            public boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState) { return true; }
            @Override
            public Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState) {
                return Optional.of(Blocks.BAMBOO.defaultBlockState());
            }
            @Override
            public boolean replant(ServerLevel level, BlockPos pos, BlockState previousState) { return true; }
        };

        assertFalse(CropRegistry.isSupportedCrop(Blocks.BAMBOO));
        CropRegistry.register(mockAdapter);
        assertTrue(CropRegistry.isSupportedCrop(Blocks.BAMBOO));
        assertTrue(CropRegistry.isMature(null, BlockPos.ZERO, Blocks.BAMBOO.defaultBlockState()));
        assertEquals(Items.BAMBOO, CropRegistry.getPlantingItem(null, BlockPos.ZERO, Blocks.BAMBOO.defaultBlockState()).orElseThrow());
    }
}
