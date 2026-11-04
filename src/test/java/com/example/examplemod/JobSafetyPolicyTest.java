package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobSafetyPolicyTest {

    @BeforeEach
    @AfterEach
    void resetRegistries() {
        MiningToolCapability.Registry.clear();
    }

    @Test
    void workBoundsEnforcesHorizontalRadiusAndVerticalDelta() {
        BlockPos center = new BlockPos(100, 64, 100);

        // Center position
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, center));

        // Horizontal boundary: 32 blocks away (dx=32, dz=0)
        BlockPos edge32 = new BlockPos(132, 64, 100);
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, edge32));

        // Horizontal outside boundary: 33 blocks away
        BlockPos outside33 = new BlockPos(133, 64, 100);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, outside33));

        // Diagonal within radius: 22^2 + 22^2 = 484 + 484 = 968 <= 32^2 (1024)
        BlockPos diagIn = new BlockPos(122, 64, 122);
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, diagIn));

        // Diagonal outside radius: 23^2 + 23^2 = 529 + 529 = 1058 > 1024
        BlockPos diagOut = new BlockPos(123, 64, 123);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, diagOut));

        // Vertical boundary with explicit 8-block limit: +8 accepted, +9 rejected
        BlockPos vert8 = new BlockPos(100, 72, 100);
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, vert8, 32, 8));
        BlockPos vertMinus8 = new BlockPos(100, 56, 100);
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, vertMinus8, 32, 8));
        BlockPos vert9 = new BlockPos(100, 73, 100);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, vert9, 32, 8));
        BlockPos vertMinus9 = new BlockPos(100, 55, 100);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, vertMinus9, 32, 8));

        // Default 3D vertical shaft support: 64 blocks vertical delta
        BlockPos vert64 = new BlockPos(100, 128, 100);
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, vert64));
        BlockPos vertMinus64 = new BlockPos(100, 0, 100);
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(center, vertMinus64));
        BlockPos vert65 = new BlockPos(100, 129, 100);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, vert65));
        BlockPos vertMinus65 = new BlockPos(100, -1, 100);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, vertMinus65));

        // Null checks
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(null, center));
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(center, null));
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(null, null));
    }

    @Test
    void storageDistanceEnforcesConservativeRadius() {
        BlockPos worksite = new BlockPos(0, 64, 0);

        // Same position
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, worksite));

        // 16 blocks horizontal: within 32
        BlockPos at16 = new BlockPos(16, 64, 0);
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, at16));

        // Exactly 32 blocks horizontal (dx=32, dy=0, dz=0): 32^2 = 1024 <= 1024
        BlockPos at32 = new BlockPos(32, 64, 0);
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, at32));

        // 33 blocks horizontal: 33^2 = 1089 > 1024
        BlockPos at33 = new BlockPos(33, 64, 0);
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, at33));

        // 3D diagonal distance: dx=20, dy=20, dz=20 -> 400+400+400 = 1200 > 1024
        BlockPos diag3D = new BlockPos(20, 84, 20);
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, diag3D));

        // 3D diagonal distance within: dx=18, dy=18, dz=18 -> 324+324+324 = 972 <= 1024
        BlockPos diag3DIn = new BlockPos(18, 82, 18);
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, diag3DIn));

        // Null checks
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(null, worksite));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, null));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(null, null));
    }

    @Test
    void toolDurabilityPreventsToolDestruction() {
        // Null or empty stacks cannot be safely used as tools
        assertFalse(JobSafetyPolicy.isToolSafeToUse(null));
        assertFalse(JobSafetyPolicy.isToolSafeToUse(ItemStack.EMPTY));

        // Non-damageable items have sufficient durability
        ItemStack nonDamageable = new ItemStack(Items.APPLE);
        assertTrue(JobSafetyPolicy.isToolSafeToUse(nonDamageable));

        // Damageable tool: undamaged pickaxe
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        assertTrue(JobSafetyPolicy.isToolSafeToUse(pick));

        // Safe tool with remaining durability = MIN_TOOL_DURABILITY (2)
        pick.setDamageValue(pick.getMaxDamage() - JobSafetyPolicy.MIN_TOOL_DURABILITY);
        assertTrue(JobSafetyPolicy.isToolSafeToUse(pick));

        // Unsafe tool with remaining durability = 1 (< MIN_TOOL_DURABILITY)
        pick.setDamageValue(pick.getMaxDamage() - 1);
        assertFalse(JobSafetyPolicy.isToolSafeToUse(pick));

        // Completely broken tool (durability = 0)
        pick.setDamageValue(pick.getMaxDamage());
        assertFalse(JobSafetyPolicy.isToolSafeToUse(pick));
    }

    @Test
    void unverifiedToolsAreRejectedByDefaultDeny() {
        // Unknown or non-tool items are strictly rejected for autonomous mining
        ItemStack stick = new ItemStack(Items.STICK);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        ItemStack bread = new ItemStack(Items.BREAD);

        assertFalse(JobSafetyPolicy.isMiningToolVerified(stick));
        assertFalse(JobSafetyPolicy.isMiningToolVerified(redstone));
        assertFalse(JobSafetyPolicy.isMiningToolVerified(bread));
        assertFalse(JobSafetyPolicy.isMiningToolVerified(null));
        assertFalse(JobSafetyPolicy.isMiningToolVerified(ItemStack.EMPTY));

        // Even though a stick is non-damageable, it is NOT authorized for mining
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(stick, 1));
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(redstone, 1));

        // Explicitly verified vanilla tools are authorized
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        ItemStack netheriteShovel = new ItemStack(Items.NETHERITE_SHOVEL);
        ItemStack stoneAxe = new ItemStack(Items.STONE_AXE);

        assertTrue(JobSafetyPolicy.isMiningToolVerified(diamondPick));
        assertTrue(JobSafetyPolicy.isMiningToolVerified(ironPick));
        assertTrue(JobSafetyPolicy.isMiningToolVerified(netheriteShovel));
        assertTrue(JobSafetyPolicy.isMiningToolVerified(stoneAxe));

        assertTrue(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, 1));
        assertTrue(JobSafetyPolicy.isMiningToolAuthorized(ironPick, 1));
    }

    @Test
    void unboundedOrAoeActionEffectsAreRejected() {
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Standard 1 block action is authorized
        assertTrue(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, 1));

        // Multi-block actions (e.g. 3x3 = 9 blocks, or 2 blocks) exceed per-action budget of 1
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, 2));
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, 9));
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, 27));

        // Non-positive block counts are rejected
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, 0));
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(diamondPick, -1));

        // A capability provider with unbounded or zero max blocks is rejected
        ResourceLocation testProviderId = ResourceLocation.fromNamespaceAndPath("test", "unbounded_tool");
        MiningToolCapability unboundedProvider = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return testProviderId; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.STICK); }
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 0; } // Unbounded or 0
        };
        MiningToolCapability.Registry.register(unboundedProvider);

        ItemStack stick = new ItemStack(Items.STICK);
        assertTrue(JobSafetyPolicy.isMiningToolVerified(stick)); // Provider recognizes it
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(stick, 1)); // But budget check fails because maxBlocks = 0
    }

    @Test
    void playerRequestedQuantityAndPerTickLimitsAreEnforced() {
        // Requirement 1: Player-controlled requested quantity supports massive quantities (e.g. 100,000,000) without arbitrary caps
        long hundredMillion = 100_000_000L;
        assertTrue(JobSafetyPolicy.isProgressWithinTarget(0L, 1, hundredMillion));
        assertTrue(JobSafetyPolicy.isProgressWithinTarget(99_999_999L, 1, hundredMillion));
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(hundredMillion, 1, hundredMillion), "Progress must stop once target is reached");

        // Requirement 2: Per-tick rate limit is enforced independently of total requested quantity
        assertTrue(JobSafetyPolicy.isTickRateSafe(1, 1));
        assertFalse(JobSafetyPolicy.isTickRateSafe(2, 1));
        assertFalse(JobSafetyPolicy.isTickRateSafe(0, 1));

        // Requirement 3: Action cannot change more blocks than remaining quota (pre-mutation rejection)
        // If 1 block remains, an action attempting 2 blocks must be rejected before mutation
        assertTrue(JobSafetyPolicy.isProgressWithinTarget(9L, 1, 10L));
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(9L, 2, 10L), "Action planned blocks (2) exceeds remaining quota (1)");
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(10L, 1, 10L), "No action permitted when target already met");
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(-1L, 1, 10L));

        // 64-bit overflow safety beyond 32-bit int bounds
        long beyondInt = 5_000_000_000L;
        assertTrue(JobSafetyPolicy.isProgressWithinTarget(beyondInt - 1L, 1, beyondInt));
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(beyondInt, 1, beyondInt));
    }

    @Test
    void toolReplacementAndDamageStopsExecution() {
        ItemStack expectedPick = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack matchingPick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Identical tool with full durability is consistent
        assertTrue(JobSafetyPolicy.isToolConsistent(matchingPick, expectedPick));

        // Replaced with an unverified item (e.g. stick) is rejected
        ItemStack replacedStick = new ItemStack(Items.STICK);
        assertFalse(JobSafetyPolicy.isToolConsistent(replacedStick, expectedPick));

        // Replaced with a different tool type (e.g. iron pickaxe instead of diamond) is rejected
        ItemStack differentTool = new ItemStack(Items.IRON_PICKAXE);
        assertFalse(JobSafetyPolicy.isToolConsistent(differentTool, expectedPick));

        // Tool damaged below minimum durability threshold is rejected
        matchingPick.setDamageValue(matchingPick.getMaxDamage() - 1); // 1 durability remaining
        assertFalse(JobSafetyPolicy.isToolConsistent(matchingPick, expectedPick));

        // Empty or null current tool is rejected
        assertFalse(JobSafetyPolicy.isToolConsistent(ItemStack.EMPTY, expectedPick));
        assertFalse(JobSafetyPolicy.isToolConsistent(null, expectedPick));
    }

    @Test
    void externalAdapterCannotBypassCentralSafetyPolicy() {
        // Register an external adapter claiming a custom item is safe
        ResourceLocation adapterId = ResourceLocation.fromNamespaceAndPath("test", "external_adapter");
        MiningToolCapability externalAdapter = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return adapterId; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.BLAZE_ROD); }
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 1; }
        };
        MiningToolCapability.Registry.register(externalAdapter);

        ItemStack blazeRod = new ItemStack(Items.BLAZE_ROD);
        assertTrue(JobSafetyPolicy.isMiningToolVerified(blazeRod));

        // Safety Policy Hard Limits remain strictly non-bypassable:
        // 1. Hard work bounds check (32m horizontal, 8m vertical)
        BlockPos worksite = new BlockPos(0, 64, 0);
        BlockPos distantTarget = new BlockPos(50, 64, 0);
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(worksite, distantTarget));

        // 2. Authoritative player requested quantity limit cannot be bypassed
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(100L, 1, 100L));

        // 3. Pre-mutation remaining quota check cannot be bypassed (remaining=1, planned=2)
        assertFalse(JobSafetyPolicy.isProgressWithinTarget(99L, 2, 100L));

        // 4. Action budget hard limit cannot be bypassed
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(blazeRod, 2));

        // 5. Tool durability threshold cannot be bypassed
        ItemStack damagedPick = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedPick.setDamageValue(damagedPick.getMaxDamage() - 1);
        assertFalse(JobSafetyPolicy.hasSufficientDurability(damagedPick));
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(damagedPick, 1));
    }

    @Test
    void workerMiningCapabilityContractEnforcesStrictDefaultDeny() {
        ItemStack stick = new ItemStack(Items.STICK);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Null worker or null tool is rejected
        assertFalse(WorkerJobCapability.Registry.canPerformMining(null, diamondPick));
        assertFalse(WorkerJobCapability.Registry.canPerformMining(null, null));

        // Stick is rejected by default-deny even with valid parameters
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(stick, 1));
    }
}
