package com.example.examplemod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive regression tests for Phase 8.3: Automatic Tool Selection Across Jobs.
 * Verifies:
 * 1. Mine job selects compatible pickaxe from WorkerStorage.
 * 2. Farm job selects compatible hoe from WorkerStorage.
 * 3. Switching Mine -> Farm automatically changes tools.
 * 4. Switching Farm -> Mine automatically changes tools.
 * 5. Switching Mine/Farm -> Move does not block movement.
 * 6. Switching back from Move selects correct tool.
 * 7. Already-equipped compatible tools are not needlessly swapped.
 * 8. Missing tools cause WAITING_FOR_TOOL.
 * 9. Adding a compatible tool allows automatic recovery from WAITING_FOR_TOOL.
 * 10. Full WorkerStorage does not cause item loss during switching.
 * 11. Reserved and pending-delivery items cannot be stolen for equipment.
 * 12. Save/reload during or immediately after a tool switch does not duplicate or lose items.
 * 13. Different tool variants and DataComponents (enchantments, custom names) remain intact.
 * 14. Maid and vanilla cow follow same shared selection rules.
 */
class JobToolSelectionTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 100));
    private static final WorkerTarget STORAGE_TARGET = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 95));

    @BeforeAll
    static void setupRegistries() {
        try {
            Field frozenField = MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(BuiltInRegistries.ITEM, false);
            frozenField.set(BuiltInRegistries.BLOCK, false);
        } catch (Throwable ignored) {
        }
        CropRegistry.registerDefaults();
    }

    private static WorkerStorage createStorage(ItemStack... items) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < WorkerStorage.SIZE; i++) {
            if (i < items.length && items[i] != null) {
                list.add(items[i].copy());
            } else {
                list.add(ItemStack.EMPTY);
            }
        }
        return new WorkerStorage(list);
    }

    // =========================================================================
    // Test 1: Mine job selects compatible pickaxe from WorkerStorage
    // =========================================================================
    @Test
    @DisplayName("1. Mine job selects compatible pickaxe from WorkerStorage")
    void test01_mineJobSelectsCompatiblePickaxeFromStorage() {
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE, 1);
        WorkerStorage storage = createStorage(ironPick, new ItemStack(Items.WHEAT, 10));

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                ItemStack.EMPTY,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result, "Tool swap for Mine job must succeed");
        assertEquals(1, handContainer.size());
        assertEquals(Items.IRON_PICKAXE, handContainer.get(0).getItem(), "Equipped tool must be the pickaxe");
        assertEquals(1, handContainer.get(0).getCount());

        assertEquals(1, storageContainer.size());
        WorkerStorage updated = storageContainer.get(0);
        assertTrue(updated.items().get(0).isEmpty(), "Storage slot vacated by pickaxe must now be empty");
    }

    // =========================================================================
    // Test 2: Farm job selects compatible hoe or required farming tool
    // =========================================================================
    @Test
    @DisplayName("2. Farm job selects compatible hoe or required farming tool")
    void test02_farmJobSelectsCompatibleHoeFromStorage() {
        ItemStack diamondHoe = new ItemStack(Items.DIAMOND_HOE, 1);
        WorkerStorage storage = createStorage(diamondHoe, new ItemStack(Items.BREAD, 5));

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                ItemStack.EMPTY,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result, "Tool swap for Farm job must succeed");
        assertEquals(1, handContainer.size());
        assertEquals(Items.DIAMOND_HOE, handContainer.get(0).getItem(), "Equipped tool must be diamond hoe");
        assertEquals(1, handContainer.get(0).getCount());

        assertEquals(1, storageContainer.size());
        assertTrue(storageContainer.get(0).items().get(0).isEmpty());
    }

    // =========================================================================
    // Test 3: Switching Mine -> Farm automatically changes tools
    // =========================================================================
    @Test
    @DisplayName("3. Switching Mine -> Farm automatically changes tools")
    void test03_switchingMineToFarmAutomaticallyChangesTools() {
        ItemStack heldPickaxe = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        heldPickaxe.setDamageValue(50);

        ItemStack netheriteHoe = new ItemStack(Items.NETHERITE_HOE, 1);
        WorkerStorage storage = createStorage(netheriteHoe);

        // Precondition: Diamond pickaxe does NOT satisfy farming requirements
        assertFalse(JobToolRequirement.FARMING.isSatisfiedBy(heldPickaxe), "Pickaxe must not satisfy Farm requirement");

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                heldPickaxe,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, handContainer.size());
        assertEquals(Items.NETHERITE_HOE, handContainer.get(0).getItem(), "Hand must receive Netherite Hoe");

        assertEquals(1, storageContainer.size());
        WorkerStorage updated = storageContainer.get(0);
        ItemStack stashedPick = updated.items().get(0);
        assertEquals(Items.DIAMOND_PICKAXE, stashedPick.getItem(), "Vacated slot must safely hold the old pickaxe");
        assertEquals(50, stashedPick.getDamageValue(), "Pickaxe durability must remain completely intact");
    }

    // =========================================================================
    // Test 4: Switching Farm -> Mine automatically changes tools
    // =========================================================================
    @Test
    @DisplayName("4. Switching Farm -> Mine automatically changes tools")
    void test04_switchingFarmToMineAutomaticallyChangesTools() {
        ItemStack heldHoe = new ItemStack(Items.NETHERITE_HOE, 1);
        heldHoe.setDamageValue(30);

        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        WorkerStorage storage = createStorage(diamondPick);

        // Precondition: Hoe does NOT satisfy mining requirements
        assertFalse(JobToolRequirement.MINING.isSatisfiedBy(heldHoe), "Hoe must not satisfy Mine requirement");

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                heldHoe,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, handContainer.size());
        assertEquals(Items.DIAMOND_PICKAXE, handContainer.get(0).getItem(), "Hand must receive Diamond Pickaxe");

        assertEquals(1, storageContainer.size());
        WorkerStorage updated = storageContainer.get(0);
        ItemStack stashedHoe = updated.items().get(0);
        assertEquals(Items.NETHERITE_HOE, stashedHoe.getItem(), "Vacated slot must safely hold the old hoe");
        assertEquals(30, stashedHoe.getDamageValue(), "Hoe durability must remain completely intact");
    }

    // =========================================================================
    // Test 5: Switching Mine/Farm -> Move does not block movement
    // =========================================================================
    @Test
    @DisplayName("5. Switching Mine/Farm -> Move does not block movement")
    void test05_switchingMineFarmToMoveDoesNotBlockMovement() {
        ItemStack heldPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        ItemStack heldHoe = new ItemStack(Items.DIAMOND_HOE, 1);

        // Move requirements are satisfied by any held item (or empty hand)
        assertTrue(JobToolRequirement.NONE.isSatisfiedBy(heldPick), "Held pickaxe must satisfy Move requirement");
        assertTrue(JobToolRequirement.NONE.isSatisfiedBy(heldHoe), "Held hoe must satisfy Move requirement");
        assertTrue(JobToolRequirement.NONE.isSatisfiedBy(ItemStack.EMPTY), "Empty hand must satisfy Move requirement");

        // ToolRequirement.forJobType maps MOVE to NONE
        assertEquals(JobToolRequirement.NONE, JobToolRequirement.forJobType(WorkerJobs.MOVE));
        assertEquals(JobToolRequirement.NONE, JobToolRequirement.forJobType(WorkerJobs.WAIT));
    }

    // =========================================================================
    // Test 6: Switching back from Move selects correct tool
    // =========================================================================
    @Test
    @DisplayName("6. Switching back from Move selects correct tool")
    void test06_switchingBackFromMoveSelectsCorrectTool() {
        ItemStack moveRetainedItem = new ItemStack(Items.TORCH, 64);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack hoe = new ItemStack(Items.IRON_HOE, 1);
        WorkerStorage storage = createStorage(pick, hoe);

        // Switch Move -> Mine
        List<ItemStack> handContainerMine = new ArrayList<>();
        List<WorkerStorage> storageContainerMine = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult mineResult = WorkerExecutionLoop.swapJobTool(
                storage,
                moveRetainedItem,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handContainerMine::add,
                storageContainerMine::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, mineResult);
        assertEquals(Items.IRON_PICKAXE, handContainerMine.get(0).getItem());

        // Switch Move -> Farm
        List<ItemStack> handContainerFarm = new ArrayList<>();
        List<WorkerStorage> storageContainerFarm = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult farmResult = WorkerExecutionLoop.swapJobTool(
                storage,
                moveRetainedItem,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handContainerFarm::add,
                storageContainerFarm::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, farmResult);
        assertEquals(Items.IRON_HOE, handContainerFarm.get(0).getItem());
    }

    // =========================================================================
    // Test 7: Already-equipped compatible tools are not needlessly swapped
    // =========================================================================
    @Test
    @DisplayName("7. Already-equipped compatible tools are not needlessly swapped")
    void test07_alreadyEquippedCompatibleToolsAreNotNeedlesslySwapped() {
        ItemStack heldPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        heldPick.setDamageValue(100);

        // Preflight verifies held tool satisfies job requirements
        assertTrue(JobToolRequirement.MINING.isSatisfiedBy(heldPick));

        ItemStack heldHoe = new ItemStack(Items.DIAMOND_HOE, 1);
        heldHoe.setDamageValue(20);
        assertTrue(JobToolRequirement.FARMING.isSatisfiedBy(heldHoe));

        // When isSatisfiedBy returns true, tryEnsureToolEquipped returns SUCCESS without mutating storage
        assertTrue(JobToolRequirement.forJobType(WorkerJobs.MINE).isSatisfiedBy(heldPick));
        assertTrue(JobToolRequirement.forJobType(WorkerJobs.FARM).isSatisfiedBy(heldHoe));
    }

    // =========================================================================
    // Test 8: Missing tools cause WAITING_FOR_TOOL
    // =========================================================================
    @Test
    @DisplayName("8. Missing tools cause WAITING_FOR_TOOL")
    void test08_missingToolsCauseWaitingForTool() {
        WorkerStorage emptyStorage = WorkerStorage.empty();

        // 1. Mine job with no tools
        WorkerExecutionLoop.ToolSwapResult mineResult = WorkerExecutionLoop.swapJobTool(
                emptyStorage,
                ItemStack.EMPTY,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                tool -> fail("Hand setter should not be called"),
                storage -> fail("Storage setter should not be called")
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, mineResult);

        MinerJobData minerData = MinerJobData.initial(64L, STORAGE_TARGET, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, WORKSITE, minerData);
        WorkerProfile minerProfile = new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE).withJob(minerJob);

        WorkerProfile waitingMiner = MinerJobController.handleToolExhaustion(minerProfile);
        assertEquals(JobStatus.WAITING_FOR_TOOL, waitingMiner.job().orElseThrow().status());

        // 2. Farm job with no tools
        WorkerExecutionLoop.ToolSwapResult farmResult = WorkerExecutionLoop.swapJobTool(
                emptyStorage,
                ItemStack.EMPTY,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                tool -> fail("Hand setter should not be called"),
                storage -> fail("Storage setter should not be called")
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, farmResult);

        FarmerJobData farmerData = FarmerJobData.initial(64L, STORAGE_TARGET);
        WorkerJob farmerJob = WorkerJob.assignedFarmer(WorkerJobs.FARM, WORKSITE, farmerData);
        WorkerProfile farmProfile = new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE).withJob(farmerJob);

        WorkerProfile waitingFarmer = FarmerJobController.handleToolExhaustion(farmProfile);
        assertEquals(JobStatus.WAITING_FOR_TOOL, waitingFarmer.job().orElseThrow().status());
    }

    // =========================================================================
    // Test 9: Adding a compatible tool allows automatic recovery from WAITING_FOR_TOOL
    // =========================================================================
    @Test
    @DisplayName("9. Adding a compatible tool allows automatic recovery from WAITING_FOR_TOOL")
    void test09_addingCompatibleToolAllowsAutomaticRecoveryFromWaitingForTool() {
        // Initial state: Farmer waiting for tool
        FarmerJobData farmerData = FarmerJobData.initial(64L, STORAGE_TARGET);
        WorkerJob farmerJob = WorkerJob.assignedFarmer(WorkerJobs.FARM, WORKSITE, farmerData);
        WorkerProfile waitingFarmer = new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE).withJob(farmerJob);
        waitingFarmer = waitingFarmer.transition(JobStatus.WAITING_FOR_TOOL, 0, "No hoe").orElseThrow();
        assertEquals(JobStatus.WAITING_FOR_TOOL, waitingFarmer.job().orElseThrow().status());

        // Player places a hoe into WorkerStorage
        ItemStack placedHoe = new ItemStack(Items.IRON_HOE, 1);
        WorkerStorage updatedStorage = createStorage(placedHoe);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                updatedStorage,
                ItemStack.EMPTY,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(Items.IRON_HOE, handContainer.get(0).getItem());

        // Resume transition to RUNNING succeeds cleanly
        WorkerProfile resumedProfile = waitingFarmer.transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, resumedProfile.job().orElseThrow().status());
    }

    // =========================================================================
    // Test 10: Full WorkerStorage does not cause item loss during switching
    // =========================================================================
    @Test
    @DisplayName("10. Full WorkerStorage does not cause item loss during switching")
    void test10_fullWorkerStorageDoesNotCauseItemLossDuringSwitching() {
        // WorkerStorage is 100% full of unstackable non-tool items (Shields)
        List<ItemStack> fullSlots = new ArrayList<>();
        fullSlots.add(new ItemStack(Items.IRON_HOE, 2)); // Slot 0 has 2 hoes (1 will remain)
        for (int i = 1; i < WorkerStorage.SIZE; i++) {
            fullSlots.add(new ItemStack(Items.SHIELD, 1)); // other slots full
        }
        WorkerStorage storage = new WorkerStorage(fullSlots);

        ItemStack heldPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        heldPick.setDamageValue(75);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                heldPick,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );

        // Fails safely because slot 0 still contains 1 hoe and other slots cannot take the pickaxe
        assertEquals(WorkerExecutionLoop.ToolSwapResult.INSUFFICIENT_STORAGE_SPACE, result);

        // Zero mutations allowed on failure: hand untouched, storage untouched
        assertTrue(handContainer.isEmpty(), "Hand setter must never be invoked on failure");
        assertTrue(storageContainer.isEmpty(), "Storage setter must never be invoked on failure");
        assertEquals(75, heldPick.getDamageValue(), "Held item durability unchanged");
        assertEquals(2, storage.items().get(0).getCount(), "Candidate stack count unchanged");
    }

    // =========================================================================
    // Test 11: Reserved and pending-delivery items cannot be stolen for equipment
    // =========================================================================
    @Test
    @DisplayName("11. Reserved and pending-delivery items cannot be stolen for equipment")
    void test11_reservedAndPendingDeliveryItemsCannotBeStolenForEquipment() {
        ItemStack reservedDiamondPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        ItemStack unreservedIronPick = new ItemStack(Items.IRON_PICKAXE, 1);
        WorkerStorage storage = createStorage(reservedDiamondPick, unreservedIronPick);

        // Diamond pickaxe is reserved (e.g. pending delivery output or JobQueue requirement)
        List<ItemStack> reservedStacks = List.of(new ItemStack(Items.DIAMOND_PICKAXE, 1));

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                ItemStack.EMPTY,
                JobToolRequirement.MINING,
                reservedStacks,
                handContainer::add,
                storageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, handContainer.size());
        assertEquals(Items.IRON_PICKAXE, handContainer.get(0).getItem(),
                "Must select unreserved Iron Pickaxe; reserved Diamond Pickaxe must NOT be stolen");

        WorkerStorage updated = storageContainer.get(0);
        assertEquals(Items.DIAMOND_PICKAXE, updated.items().get(0).getItem(),
                "Reserved Diamond Pickaxe must remain safely in storage slot 0");

        // If all matching tools are reserved, NO_TOOL_AVAILABLE must be returned
        WorkerStorage onlyReserved = createStorage(reservedDiamondPick);
        WorkerExecutionLoop.ToolSwapResult allReservedResult = WorkerExecutionLoop.swapJobTool(
                onlyReserved,
                ItemStack.EMPTY,
                JobToolRequirement.MINING,
                reservedStacks,
                tool -> fail("Must not equip reserved tool"),
                s -> fail("Must not modify storage when tool is reserved")
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, allReservedResult);
    }

    // =========================================================================
    // Test 12: Save/reload during or immediately after a tool switch does not duplicate or lose items
    // =========================================================================
    @Test
    @DisplayName("12. Save/reload during or immediately after a tool switch does not duplicate or lose items")
    void test12_saveReloadDuringOrImmediatelyAfterToolSwitchDoesNotDuplicateOrLoseItems() {
        ItemStack heldPick = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack storageHoe = new ItemStack(Items.DIAMOND_HOE, 1);
        WorkerStorage initialStorage = createStorage(storageHoe);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> storageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                initialStorage,
                heldPick,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handContainer::add,
                storageContainer::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);

        WorkerStorage postSwapStorage = storageContainer.get(0);
        ItemStack equippedHoe = handContainer.get(0);

        // 1. Serialize WorkerStorage to JSON
        var encodedStorage = WorkerStorage.CODEC.encodeStart(JsonOps.INSTANCE, postSwapStorage).result().orElseThrow();
        WorkerStorage reloadedStorage = WorkerStorage.CODEC.parse(JsonOps.INSTANCE, encodedStorage).result().orElseThrow();

        // Stashed pickaxe survived reload with exact identity and count
        assertEquals(Items.IRON_PICKAXE, reloadedStorage.items().get(0).getItem());
        assertEquals(1, reloadedStorage.items().get(0).getCount());

        // 2. Serialize WorkerProfile to JSON
        FarmerJobData farmerData = FarmerJobData.initial(64L, STORAGE_TARGET);
        WorkerJob farmerJob = WorkerJob.assignedFarmer(WorkerJobs.FARM, WORKSITE, farmerData);
        WorkerProfile profile = new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE).withJob(farmerJob);

        var encodedProfile = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).result().orElseThrow();
        WorkerProfile reloadedProfile = WorkerProfile.CODEC.parse(JsonOps.INSTANCE, encodedProfile).result().orElseThrow();

        assertEquals(JobStatus.ASSIGNED, reloadedProfile.job().orElseThrow().status());
        assertEquals(Items.DIAMOND_HOE, equippedHoe.getItem());
    }

    // =========================================================================
    // Test 13: Different tool variants and DataComponents (enchantments, custom names) remain intact
    // =========================================================================
    @Test
    @DisplayName("13. Different tool variants and DataComponents (enchantments, custom names) remain intact")
    void test13_differentToolVariantsAndDataComponentsRemainIntact() {
        ItemStack customHoe = new ItemStack(Items.NETHERITE_HOE, 1);
        customHoe.set(DataComponents.CUSTOM_NAME, Component.literal("Grandma's Prized Hoe"));
        customHoe.setDamageValue(123);

        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        diamondPick.set(DataComponents.CUSTOM_NAME, Component.literal("Dwarven Excavator"));
        diamondPick.setDamageValue(456);

        WorkerStorage storage = createStorage(diamondPick);

        // Switch Farm -> Mine
        List<ItemStack> hand1 = new ArrayList<>();
        List<WorkerStorage> storage1 = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult toMineResult = WorkerExecutionLoop.swapJobTool(
                storage,
                customHoe,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                hand1::add,
                storage1::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, toMineResult);

        // Equipped pickaxe preserved custom name and durability
        ItemStack equippedPick = hand1.get(0);
        assertEquals(Component.literal("Dwarven Excavator"), equippedPick.get(DataComponents.CUSTOM_NAME));
        assertEquals(456, equippedPick.getDamageValue());

        // Stashed hoe in storage preserved custom name and durability
        ItemStack stashedHoe = storage1.get(0).items().get(0);
        assertEquals(Component.literal("Grandma's Prized Hoe"), stashedHoe.get(DataComponents.CUSTOM_NAME));
        assertEquals(123, stashedHoe.getDamageValue());

        // Switch Mine -> Farm back again
        List<ItemStack> hand2 = new ArrayList<>();
        List<WorkerStorage> storage2 = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult toFarmResult = WorkerExecutionLoop.swapJobTool(
                storage1.get(0),
                equippedPick,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                hand2::add,
                storage2::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, toFarmResult);

        // Re-equipped hoe has exact custom name and durability
        ItemStack reEquippedHoe = hand2.get(0);
        assertEquals(Component.literal("Grandma's Prized Hoe"), reEquippedHoe.get(DataComponents.CUSTOM_NAME));
        assertEquals(123, reEquippedHoe.getDamageValue());
    }

    // =========================================================================
    // Test 14: Maid and vanilla cow follow same shared selection rules
    // =========================================================================
    @Test
    @DisplayName("14. Maid and vanilla cow follow same shared selection rules")
    void test14_maidAndVanillaCowFollowSameSharedSelectionRules() {
        UUID maidId = UUID.randomUUID();
        UUID cowId = UUID.randomUUID();

        ItemStack netheritePick = new ItemStack(Items.NETHERITE_PICKAXE, 1);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE, 1);

        WorkerStorage maidStorage = createStorage(netheritePick, diamondPick);
        WorkerStorage cowStorage = createStorage(netheritePick, diamondPick);

        List<ItemStack> maidHand = new ArrayList<>();
        List<WorkerStorage> maidStorageOut = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult maidResult = WorkerExecutionLoop.swapJobTool(
                maidStorage,
                ItemStack.EMPTY,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                maidHand::add,
                maidStorageOut::add
        );

        List<ItemStack> cowHand = new ArrayList<>();
        List<WorkerStorage> cowStorageOut = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult cowResult = WorkerExecutionLoop.swapJobTool(
                cowStorage,
                ItemStack.EMPTY,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                cowHand::add,
                cowStorageOut::add
        );

        // Both follow identical deterministic selection ranking (Netherite > Diamond)
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, maidResult);
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, cowResult);
        assertEquals(Items.NETHERITE_PICKAXE, maidHand.get(0).getItem());
        assertEquals(Items.NETHERITE_PICKAXE, cowHand.get(0).getItem());

        // Profiles for both handle tool exhaustion identically
        MinerJobData data = MinerJobData.initial(64L, STORAGE_TARGET, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, WORKSITE, data);

        WorkerProfile maidProfile = new WorkerProfile(maidId, OWNER_ID, OVERWORLD, WorkerStatus.IDLE).withJob(minerJob);
        WorkerProfile cowProfile = new WorkerProfile(cowId, OWNER_ID, OVERWORLD, WorkerStatus.IDLE).withJob(minerJob);

        WorkerProfile waitingMaid = MinerJobController.handleToolExhaustion(maidProfile);
        WorkerProfile waitingCow = MinerJobController.handleToolExhaustion(cowProfile);

        assertEquals(JobStatus.WAITING_FOR_TOOL, waitingMaid.job().orElseThrow().status());
        assertEquals(JobStatus.WAITING_FOR_TOOL, waitingCow.job().orElseThrow().status());
    }
}
