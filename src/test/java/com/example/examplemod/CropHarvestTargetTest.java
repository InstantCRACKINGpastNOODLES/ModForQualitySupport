package com.example.examplemod;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropHarvestTargetTest {
    @Test
    void matureCropRequiresLoadedPermittedNearbyTarget() {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        var mature = wheat.defaultBlockState().setValue(CropBlock.AGE, wheat.getMaxAge());
        assertTrue(CropHarvestTarget.eligible(mature, true, true, 64));
        assertFalse(CropHarvestTarget.eligible(mature, false, true, 0));
        assertFalse(CropHarvestTarget.eligible(mature, true, false, 0));
        assertFalse(CropHarvestTarget.eligible(mature, true, true, 65));
        assertFalse(CropHarvestTarget.eligible(null, true, true, 0));
    }

    @Test
    void immatureAndNonCropBlocksAreNotHarvestTargets() {
        assertFalse(CropHarvestTarget.eligible(Blocks.WHEAT.defaultBlockState(), true, true, 0));
        assertFalse(CropHarvestTarget.eligible(Blocks.STONE.defaultBlockState(), true, true, 0));
    }
}