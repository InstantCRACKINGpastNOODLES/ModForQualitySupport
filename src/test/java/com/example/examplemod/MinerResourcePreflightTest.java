package com.example.examplemod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinerResourcePreflightTest {

    private static final ResourceLocation TEST_AVARITIA_PROVIDER =
            ResourceLocation.fromNamespaceAndPath("test", "infinite_tool_provider");

    @BeforeEach
    @AfterEach
    void resetRegistries() {
        MiningToolCapability.Registry.clear();
    }

    @Test
    void infiniteDurabilityToolDoesNotRequireReplacementOrUseWearAsStoppingCondition() {
        // Register a verified adapter for an infinite-durability tool that affects exactly 1 block per action
        MiningToolCapability infiniteToolAdapter = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return TEST_AVARITIA_PROVIDER; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.NETHER_STAR); } // simulated non-damageable tool
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 1; }
        };
        MiningToolCapability.Registry.register(infiniteToolAdapter);

        ItemStack infiniteTool = new ItemStack(Items.NETHER_STAR);
        assertFalse(infiniteTool.isDamageableItem(), "Simulated tool must be non-damageable");

        // Player requests a large job: 10,000 blocks
        int requestedQuantity = 10_000;
        int maxBlocksPerTick = 1;

        MinerResourcePreflight.PreflightPlan plan =
                MinerResourcePreflight.evaluate(infiniteTool, requestedQuantity, maxBlocksPerTick);

        assertTrue(plan.isApproved(), "Verified infinite tool must be approved");
        assertTrue(plan.hasInfiniteDurability(), "Tool must be recognized as having infinite durability");
        assertEquals(Long.MAX_VALUE, plan.usableDurability());

        // Stopping condition is the player's requested quantity, NOT tool wear
        assertEquals(requestedQuantity, plan.playerRequestedQuantity());
        assertEquals(0L, plan.replacementToolsNeeded(), "Infinite durability tool must NEVER require replacement tools");

        // Progress estimate is calculated strictly from per-tick rate limit
        assertEquals(10_000L, plan.estimatedTicksToComplete());
    }

    @Test
    void infiniteDurabilityToolIsRejectedIfUnverifiedByDefaultDeny() {
        // Nether Star is non-damageable, but without a verified adapter it is strictly rejected
        ItemStack unverifiedInfinite = new ItemStack(Items.NETHER_STAR);

        MinerResourcePreflight.PreflightPlan plan =
                MinerResourcePreflight.evaluate(unverifiedInfinite, 100, 1);

        assertFalse(plan.isApproved());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_UNVERIFIED_TOOL, plan.status());
        assertTrue(plan.detail().orElse("").contains("Tool is not verified"));
    }

    @Test
    void infiniteDurabilityToolIsRejectedIfCollateralIsUnbounded() {
        // An adapter claims the tool is safe, but cannot bound the collateral effect (e.g. 0 or negative)
        MiningToolCapability unboundedAdapter = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return TEST_AVARITIA_PROVIDER; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.NETHER_STAR); }
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 0; } // Unbounded
        };
        MiningToolCapability.Registry.register(unboundedAdapter);

        ItemStack infiniteTool = new ItemStack(Items.NETHER_STAR);
        MinerResourcePreflight.PreflightPlan plan =
                MinerResourcePreflight.evaluate(infiniteTool, 100, 1);

        assertFalse(plan.isApproved());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_UNBOUNDED_COLLATERAL, plan.status());
    }

    @Test
    void infiniteDurabilityToolIsRejectedIfCollateralExceedsPerTickLimit() {
        // A tool that affects 9 blocks per action (e.g. 3x3 AOE), but per-tick limit is 1
        MiningToolCapability aoeAdapter = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return TEST_AVARITIA_PROVIDER; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.NETHER_STAR); }
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 9; }
        };
        MiningToolCapability.Registry.register(aoeAdapter);

        ItemStack infiniteTool = new ItemStack(Items.NETHER_STAR);
        MinerResourcePreflight.PreflightPlan plan =
                MinerResourcePreflight.evaluate(infiniteTool, 100, 1);

        assertFalse(plan.isApproved());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_RATE_LIMIT_EXCEEDED, plan.status());
        assertTrue(plan.detail().orElse("").contains("exceeds configured per-tick limit"));
    }

    @Test
    void damageableToolProtectsDurabilityAndCalculatesReplacementsWithoutTruncatingQuantity() {
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Small job within durability
        MinerResourcePreflight.PreflightPlan smallPlan =
                MinerResourcePreflight.evaluate(diamondPick, 50, 1);
        assertTrue(smallPlan.isApproved());
        assertFalse(smallPlan.hasInfiniteDurability());
        assertEquals(50L, smallPlan.playerRequestedQuantity());
        assertEquals(0L, smallPlan.replacementToolsNeeded());

        // Damaged tool with 1 durability remaining (< MIN_TOOL_DURABILITY = 2)
        diamondPick.setDamageValue(diamondPick.getMaxDamage() - 1);
        MinerResourcePreflight.PreflightPlan damagedPlan =
                MinerResourcePreflight.evaluate(diamondPick, 50, 1);
        assertFalse(damagedPlan.isApproved());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_INSUFFICIENT_DURABILITY, damagedPlan.status());

        // Large job exceeding single tool durability (e.g. 5000 blocks with fresh diamond pick)
        ItemStack freshPick = new ItemStack(Items.DIAMOND_PICKAXE);
        MinerResourcePreflight.PreflightPlan largePlan =
                MinerResourcePreflight.evaluate(freshPick, 5000, 1);
        assertTrue(largePlan.isApproved());
        assertEquals(5000, largePlan.playerRequestedQuantity(), "Requested quantity must NOT be truncated to tool wear");
        assertTrue(largePlan.replacementToolsNeeded() > 0, "Estimates spare tools needed without capping job");
    }

    @Test
    void invalidParametersAreSafelyRejected() {
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Zero or negative quantity
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_INVALID_QUANTITY,
                MinerResourcePreflight.evaluate(diamondPick, 0, 1).status());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_INVALID_QUANTITY,
                MinerResourcePreflight.evaluate(diamondPick, -5, 1).status());

        // Missing tool
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_MISSING_TOOL,
                MinerResourcePreflight.evaluate(null, 10, 1).status());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_MISSING_TOOL,
                MinerResourcePreflight.evaluate(ItemStack.EMPTY, 10, 1).status());
    }
}
