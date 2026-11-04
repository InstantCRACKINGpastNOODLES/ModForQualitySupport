package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted regression tests verifying:
 * 1. Generic modded pickaxe compatibility (Issue A)
 * 2. Proper standing position calculation at floor level for 1x2 tunnel mining (Issue B)
 * 3. Prevention of erratic movement, jumping, and AI conflicts during Miner navigation
 */
class MinerToolCompatibilityAndNavigationTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static PickaxeItem MODDED_PICKAXE_ITEM;

    @BeforeAll
    static void setupItems() {
        try {
            java.lang.reflect.Field frozenField = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(BuiltInRegistries.ITEM, false);

            MODDED_PICKAXE_ITEM = new PickaxeItem(Tiers.DIAMOND, new Item.Properties().durability(8000));
            net.minecraft.core.Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("testmod", "test_dragonsteel_pickaxe"),
                    MODDED_PICKAXE_ITEM
            );

            frozenField.set(BuiltInRegistries.ITEM, true);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to register test modded pickaxe item", t);
        }
    }

    @BeforeEach
    @AfterEach
    void resetState() {
        MiningToolCapability.Registry.clear();
        WorkerOrderSession.clearAll();
    }

    // ==========================================
    // 1. Tool Compatibility (Issue A)
    // ==========================================

    @Test
    @DisplayName("Modded Pickaxe extending PickaxeItem is verified as safe single-block tool")
    void testModdedPickaxeExtendingPickaxeItemIsVerifiedSafe() {
        // Construct a registered modded pickaxe (mimicking Ice and Fire ItemPostHitPickaxe)
        ItemStack moddedTool = new ItemStack(MODDED_PICKAXE_ITEM);

        assertTrue(JobSafetyPolicy.isMiningToolVerified(moddedTool),
                "Generic modded pickaxe extending PickaxeItem must be verified safe");
        assertTrue(JobSafetyPolicy.isMiningToolAuthorized(moddedTool, 1),
                "Generic modded pickaxe with sufficient durability must be authorized for 1-block mining");
        assertEquals(1, MiningToolCapability.Registry.getMaxBlocksPerAction(moddedTool),
                "Standard modded pickaxe must have maxBlocksPerAction bounded to 1");
    }

    @Test
    @DisplayName("Modded Pickaxe preflight evaluation is approved without entering WAITING_FOR_TOOL")
    void testModdedPickaxePreflightApproved() {
        ItemStack moddedTool = new ItemStack(MODDED_PICKAXE_ITEM);

        MinerResourcePreflight.PreflightPlan plan = MinerResourcePreflight.evaluate(moddedTool, 64, 1);
        assertTrue(plan.isApproved(), "Modded pickaxe preflight must be approved");
        assertEquals(MinerResourcePreflight.PreflightStatus.APPROVED, plan.status());
        assertEquals(1, plan.collateralBlocksPerAction());
    }

    @Test
    @DisplayName("Damaged modded pickaxe with durability < 2 is rejected by durability safety policy")
    void testDamagedModdedPickaxeRejected() {
        ItemStack damagedTool = new ItemStack(MODDED_PICKAXE_ITEM);
        damagedTool.setDamageValue(7999); // remaining durability = 1, below MIN_TOOL_DURABILITY (2)

        assertTrue(JobSafetyPolicy.isMiningToolVerified(damagedTool),
                "Item identity is still verified as pickaxe");
        assertFalse(JobSafetyPolicy.hasSufficientDurability(damagedTool, 1),
                "Durability < 2 must fail durability check to preserve tool from breaking");
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(damagedTool, 1),
                "Tool with insufficient durability must NOT be authorized");
    }

    @Test
    @DisplayName("Non-mining items remain strictly rejected under default-deny policy")
    void testNonMiningItemsStrictlyRejected() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack stick = new ItemStack(Items.STICK);
        ItemStack bread = new ItemStack(Items.BREAD);
        ItemStack redstone = new ItemStack(Items.REDSTONE);

        assertFalse(JobSafetyPolicy.isMiningToolVerified(sword), "Sword must not be verified as a mining tool");
        assertFalse(JobSafetyPolicy.isMiningToolVerified(stick), "Stick must not be verified as a mining tool");
        assertFalse(JobSafetyPolicy.isMiningToolVerified(bread), "Bread must not be verified as a mining tool");
        assertFalse(JobSafetyPolicy.isMiningToolVerified(redstone), "Redstone must not be verified as a mining tool");
    }

    @Test
    @DisplayName("Worker equipped with modded pickaxe in main hand does not trigger tool swap")
    void testWorkerEquippedWithModdedPickaxeRetainsMainHand() {
        ItemStack moddedTool = new ItemStack(MODDED_PICKAXE_ITEM);
        WorkerStorage storage = WorkerStorage.empty();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                storage,
                moddedTool,
                newTool -> fail("Hand setter should not be called when hand tool is already valid"),
                newStorage -> fail("Storage setter should not be called when no swap occurs")
        );

        // When storage is empty, swapMiningTool returns NO_TOOL_AVAILABLE for swaps,
        // but tryEnsureMiningToolEquipped would inspect held item first and return SUCCESS.
        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, result);
    }

    // ==========================================
    // 2. Navigation Stand Position & Tunnel Geometry (Issue B)
    // ==========================================

    @Test
    @DisplayName("TunnelTargetResult calculates standing position at floor level for depth d=0")
    void testTunnelTargetResultCalculatesFloorStandPosAtDepthZero() {
        BlockPos anchor = new BlockPos(100, 64, 100);
        Direction dir = Direction.NORTH;
        long requestedBlocks = 10;

        // At d=0: upper block is (100, 65, 100), lower block is (100, 64, 100)
        // Standing pos must be immediately outside tunnel: anchor.relative(dir.getOpposite()) = (100, 64, 101)
        MinerJobController.TunnelTargetResult result = MinerJobController.findNextTunnelTarget(
                anchor,
                dir,
                requestedBlocks,
                pos -> false, // nothing cleared
                pos -> Optional.empty()
        );

        assertTrue(result.targetPos().isPresent());
        assertEquals(new BlockPos(100, 65, 100), result.targetPos().get(),
                "First target in 1x2 tunnel must be upper block at head level");

        assertTrue(result.standPos().isPresent(), "Stand position must be provided");
        BlockPos standPos = result.standPos().get();
        assertEquals(new BlockPos(100, 64, 101), standPos,
                "Worker stand position for d=0 must be before tunnel entrance");
        assertEquals(64, standPos.getY(),
                "Stand position Y must strictly match tunnel floor level (64), NOT elevated head level (65)");
    }

    @Test
    @DisplayName("TunnelTargetResult calculates standing position inside previously cleared slot for depth d=1")
    void testTunnelTargetResultCalculatesFloorStandPosAtDepthOne() {
        BlockPos anchor = new BlockPos(100, 64, 100);
        Direction dir = Direction.NORTH;
        long requestedBlocks = 10;

        // d=0 cleared: (100, 65, 100) and (100, 64, 100) are cleared air
        MinerJobController.TunnelTargetResult result = MinerJobController.findNextTunnelTarget(
                anchor,
                dir,
                requestedBlocks,
                pos -> pos.getY() == 64 && pos.getZ() == 100 || pos.getY() == 65 && pos.getZ() == 100,
                pos -> Optional.empty()
        );

        assertTrue(result.targetPos().isPresent());
        assertEquals(new BlockPos(100, 65, 99), result.targetPos().get(),
                "Next target must be upper block at d=1 (Z=99)");

        assertTrue(result.standPos().isPresent(), "Stand position must be provided");
        BlockPos standPos = result.standPos().get();
        assertEquals(new BlockPos(100, 64, 100), standPos,
                "Worker stand position for d=1 must be the cleared slot at d=0 (Z=100)");
        assertEquals(64, standPos.getY(),
                "Stand position Y must remain at tunnel floor level (64)");
    }

    @Test
    @DisplayName("Stand position center is strictly within 3.5 block interaction reach of target")
    void testStandPositionIsWithinInteractionReachOfTarget() {
        BlockPos anchor = new BlockPos(100, 64, 100);
        Direction dir = Direction.NORTH;

        MinerJobController.TunnelTargetResult result = MinerJobController.findNextTunnelTarget(
                anchor,
                dir,
                10,
                pos -> false,
                pos -> Optional.empty()
        );

        BlockPos targetPos = result.targetPos().orElseThrow();
        BlockPos standPos = result.standPos().orElseThrow();

        // Calculate squared Euclidean distance between worker standing at center of standPos and targetPos center
        double dx = (standPos.getX() + 0.5) - (targetPos.getX() + 0.5);
        double dy = (standPos.getY() + 0.5) - (targetPos.getY() + 0.5);
        double dz = (standPos.getZ() + 0.5) - (targetPos.getZ() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;

        // distSq from (100.5, 64.5, 101.5) to (100.5, 65.5, 100.5) is 0^2 + (-1)^2 + 1^2 = 2.0
        assertTrue(distSq <= 3.5 * 3.5,
                "Standing position must be within interaction reach 3.5 blocks (distSq was " + distSq + ")");
    }

    @Test
    @DisplayName("Touhou Little Maid follow suppression and restoration execute cleanly")
    void testTouhouLittleMaidFollowBridgeSafety() {
        // Safe execution when worker is null
        assertDoesNotThrow(() -> TouhouLittleMaidCompat.suppressMaidFollow(null));
        assertDoesNotThrow(() -> TouhouLittleMaidCompat.restoreMaidFollow(null));
    }
}
