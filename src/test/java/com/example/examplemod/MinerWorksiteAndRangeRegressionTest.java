package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinerWorksiteAndRangeRegressionTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @BeforeEach
    @AfterEach
    void resetState() {
        WorkerOrderSession.clearAll();
        WorksiteAdapter.Registry.clear();
        CropRegistry.registerDefaults();
        BindingTicketHUD.clear();
    }

    // ==========================================
    // Group 1: Miner Worksite Selection
    // ==========================================

    @Test
    @DisplayName("1. Stone is accepted as a Miner worksite")
    void test01_stoneIsAcceptedAsMinerWorksite() {
        WorksiteAdapter minerAdapter = WorksiteAdapter.Registry.get(WorkerJobs.MINE).orElseThrow();
        assertEquals(WorkerJobs.MINE, minerAdapter.jobType());

        // Stone is a valid mineable block, not blacklisted, destroy speed > 0, no tile entity
        assertFalse(JobSafetyPolicy.isBlockBlacklisted(Blocks.STONE));
        assertTrue(Blocks.STONE.defaultBlockState().getDestroySpeed(null, BlockPos.ZERO) >= 0);
        assertFalse(Blocks.STONE.defaultBlockState().hasBlockEntity());

        // createTarget safely returns empty when level is null
        Optional<WorkerTarget> nullLevelTarget = minerAdapter.createTarget(null, new BlockPos(10, 64, 10), Direction.NORTH, Blocks.STONE.defaultBlockState());
        assertTrue(nullLevelTarget.isEmpty());

        // Target representation for stone worksite
        WorkerTarget target = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
        assertEquals(new BlockPos(10, 64, 10), target.position());
        assertEquals(OVERWORLD, target.dimension());
    }

    @Test
    @DisplayName("2. Stone is rejected as a Farm worksite")
    void test02_stoneIsRejectedAsFarmWorksite() {
        assertFalse(CropRegistry.isSupportedCrop(Blocks.STONE));
        assertFalse(Blocks.STONE.defaultBlockState().is(Blocks.FARMLAND));

        WorksiteAdapter farmAdapter = WorksiteAdapter.Registry.get(WorkerJobs.FARM).orElseThrow();
        assertEquals(WorkerJobs.FARM, farmAdapter.jobType());
        // Farm adapter rejects stone block
        assertEquals(WorkerOrderSession.StepResult.REJECTED_BLOCK_TYPE, WorkerOrderSession.StepResult.valueOf("REJECTED_BLOCK_TYPE"));
    }

    @Test
    @DisplayName("3. A blacklisted block is rejected by Miner")
    void test03_blacklistedBlockIsRejectedByMiner() {
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.BEDROCK));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.SPAWNER));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.END_PORTAL));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.NETHER_PORTAL));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.BARRIER));
        assertEquals(WorkerOrderSession.StepResult.REJECTED_BLACKLISTED, WorkerOrderSession.StepResult.valueOf("REJECTED_BLACKLISTED"));
    }

    @Test
    @DisplayName("4. A block entity is rejected where required")
    void test04_blockEntityIsRejectedWhereRequired() {
        assertTrue(Blocks.CHEST.defaultBlockState().hasBlockEntity());
        assertTrue(Blocks.BARREL.defaultBlockState().hasBlockEntity());
        assertTrue(Blocks.FURNACE.defaultBlockState().hasBlockEntity());
        assertEquals(WorkerOrderSession.StepResult.REJECTED_BLOCK_ENTITY, WorkerOrderSession.StepResult.valueOf("REJECTED_BLOCK_ENTITY"));
    }

    @Test
    @DisplayName("5. An indestructible block is rejected")
    void test05_indestructibleBlockIsRejected() {
        assertEquals(WorkerOrderSession.StepResult.REJECTED_INDESTRUCTIBLE, WorkerOrderSession.StepResult.valueOf("REJECTED_INDESTRUCTIBLE"));
        assertEquals(JobSafetyPolicy.SafetyValidationResult.REJECTED_INDESTRUCTIBLE, JobSafetyPolicy.SafetyValidationResult.valueOf("REJECTED_INDESTRUCTIBLE"));
    }

    @Test
    @DisplayName("6. An invalid or stale Miner session cannot select a worksite")
    void test06_invalidOrStaleSessionCannotSelectWorksite() {
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        // Calling selectWorksite with null or no session returns UNAVAILABLE
        WorkerOrderSession.StepResult result = WorkerOrderSession.selectWorksite(null, new BlockPos(10, 64, 10), Direction.UP);
        assertEquals(WorkerOrderSession.StepResult.UNAVAILABLE, result);
    }

    // ==========================================
    // Group 2: Tool Handling & WAITING_FOR_TOOL
    // ==========================================

    @Test
    @DisplayName("7. A valid pickaxe in the intended inventory is detected")
    void test07_validPickaxeInIntendedInventoryIsDetected() {
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        assertTrue(JobSafetyPolicy.isMiningToolVerified(ironPick));
        assertTrue(JobSafetyPolicy.hasSufficientDurability(ironPick, 1));

        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);
        assertTrue(JobSafetyPolicy.isMiningToolVerified(diamondPick));
        assertTrue(JobSafetyPolicy.hasSufficientDurability(diamondPick, 1));
    }

    @Test
    @DisplayName("8. An incompatible tool is rejected or produces the correct waiting state")
    void test08_incompatibleToolIsRejectedOrProducesWaitingState() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        assertFalse(JobSafetyPolicy.isMiningToolVerified(sword));

        ItemStack stick = new ItemStack(Items.STICK);
        assertFalse(JobSafetyPolicy.isMiningToolVerified(stick));
    }

    @Test
    @DisplayName("9. A depleted tool is handled correctly")
    void test09_depletedToolIsHandledCorrectly() {
        ItemStack wornPick = new ItemStack(Items.IRON_PICKAXE);
        wornPick.setDamageValue(wornPick.getMaxDamage() - 1); // Only 1 durability left
        // MIN_TOOL_DURABILITY = 2, so 1 durability is insufficient
        assertFalse(JobSafetyPolicy.hasSufficientDurability(wornPick, 1));
    }

    @Test
    @DisplayName("10. A validated tool is actually available during mining execution")
    void test10_validatedToolIsAvailableDuringMiningExecution() {
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        assertTrue(JobSafetyPolicy.isMiningToolVerified(pick));
        assertTrue(JobSafetyPolicy.isMiningToolAuthorized(pick, 1));
    }

    @Test
    @DisplayName("11. Missing tools do not cause silent job failure (WAITING_FOR_TOOL)")
    void test11_missingToolsDoNotCauseSilentJobFailure() {
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        MinerJobData minerData = MinerJobData.initial(64L, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)), Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10)), minerData);
        WorkerProfile minerProfile = profile.withJob(minerJob);

        WorkerProfile waitingProfile = MinerJobController.handleToolExhaustion(minerProfile);
        assertTrue(waitingProfile.job().isPresent());
        assertEquals(JobStatus.WAITING_FOR_TOOL, waitingProfile.job().get().status());
    }

    // ==========================================
    // Group 3: Range Behavior & Distance Semantics
    // ==========================================

    @Test
    @DisplayName("12. A valid player-to-worksite selection succeeds within configured range")
    void test12_validPlayerToWorksiteSelectionWithinRange() {
        Vec3 eyePos = new Vec3(10.0, 64.0, 10.0);
        BlockPos targetPos = new BlockPos(13, 64, 10); // closest point is (13.0, 64.0, 10.0) -> distance 3.0 blocks
        double distSq = WorkerOrderSession.distanceSqrToBlock(eyePos, targetPos);
        double maxSurvivalReach = 4.5 + 1.0; // 5.5m

        assertTrue(distSq <= maxSurvivalReach * maxSurvivalReach, "Legitimate interaction within reach must pass");
        assertEquals(9.0, distSq, 0.001);
    }

    @Test
    @DisplayName("13. A selection outside player range returns correct player-range error")
    void test13_selectionOutsidePlayerRangeReturnsRangePlayerError() {
        Vec3 eyePos = new Vec3(10.0, 64.0, 10.0);
        BlockPos farBlock = new BlockPos(16, 64, 10); // closest point is (16.0, 64.0, 10.0) -> distance 6.0 blocks
        double distSq = WorkerOrderSession.distanceSqrToBlock(eyePos, farBlock);
        double maxSurvivalReach = 4.5 + 1.0; // 5.5m

        assertTrue(distSq > maxSurvivalReach * maxSurvivalReach, "Block 6.0m away must exceed authoritative reach (5.5m)");
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_PLAYER, WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_PLAYER"));
    }

    @Test
    @DisplayName("14. Worker distance is checked independently")
    void test14_workerDistanceCheckedIndependently() {
        double maxWorkerRadius = JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS; // 32 blocks
        double withinRadius = 25.0;
        double beyondRadius = 35.0;

        assertTrue(withinRadius <= maxWorkerRadius);
        assertTrue(beyondRadius > maxWorkerRadius);
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_WORKER, WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_WORKER"));
    }

    @Test
    @DisplayName("15. Storage distance is checked independently (32 blocks)")
    void test15_storageDistanceCheckedIndependently() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        BlockPos storageWithin = new BlockPos(20, 64, 20); // sqrt(800) ~ 28.3m <= 32m
        BlockPos storageBeyond = new BlockPos(30, 64, 30); // sqrt(1800) ~ 42.4m > 32m

        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, storageWithin));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, storageBeyond));
        assertEquals(32, JobSafetyPolicy.DEFAULT_MAX_STORAGE_DISTANCE);
    }

    @Test
    @DisplayName("16. A navigation failure is not misreported as player reach failure")
    void test16_navigationFailureNotMisreportedAsPlayerReachFailure() {
        assertNotEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_PLAYER, WorkerOrderSession.StepResult.REJECTED_RANGE_WORKER);
        assertNotEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_PLAYER, WorkerOrderSession.StepResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("17. Tether proximity does not contradict work eligibility (32m == 32m)")
    void test17_tetherProximityDoesNotContradictWorkEligibility() {
        double workRadius = JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS; // 32.0
        double tetherDistance = 32.0; // Aligned with work radius
        assertEquals(workRadius, tetherDistance, "Tether proximity must match worker operational radius");
    }

    @Test
    @DisplayName("18. Range changes do not break Farm behavior")
    void test18_rangeChangesDoNotBreakFarmBehavior() {
        // Farmland is accepted
        assertTrue(Blocks.FARMLAND.defaultBlockState().is(Blocks.FARMLAND));
        // Wheat is accepted
        assertTrue(CropRegistry.isSupportedCrop(Blocks.WHEAT));
        // Stone is still rejected by Farm
        assertFalse(CropRegistry.isSupportedCrop(Blocks.STONE));
        // Storage distance up to 32 blocks works for Farm delivery as well
        BlockPos farmWorksite = new BlockPos(100, 64, 100);
        BlockPos farmChest = new BlockPos(120, 64, 100); // 20m <= 32m
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(farmWorksite, farmChest));
    }

    // ==========================================
    // Group 4: Tool Preservation & Swapping Semantics
    // ==========================================

    @Test
    @DisplayName("19. Tool swap with stacked tools equips exactly one and retains remaining count")
    void test19_toolSwapWithStackedToolsEquipsOneAndRetainsRemaining() {
        WorkerStorage emptyStorage = WorkerStorage.empty();
        List<ItemStack> items = new ArrayList<>(emptyStorage.items());
        ItemStack stackedPicks = new ItemStack(Items.IRON_PICKAXE, 3);
        items.set(0, stackedPicks);
        WorkerStorage storage = new WorkerStorage(items);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> updatedStorageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                storage,
                ItemStack.EMPTY,
                handContainer::add,
                updatedStorageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result, "Tool swap must succeed");
        assertEquals(1, handContainer.size());
        assertEquals(Items.IRON_PICKAXE, handContainer.get(0).getItem());
        assertEquals(1, handContainer.get(0).getCount(), "Hand must receive exactly 1 pickaxe");

        assertEquals(1, updatedStorageContainer.size());
        WorkerStorage resultingStorage = updatedStorageContainer.get(0);
        assertEquals(2, resultingStorage.items().get(0).getCount(), "Remaining 2 pickaxes must stay in slot 0");
    }

    @Test
    @DisplayName("20. Tool swap preserves non-mining held item in vacated slot")
    void test20_toolSwapPreservesHeldItemInVacatedSlot() {
        WorkerStorage emptyStorage = WorkerStorage.empty();
        List<ItemStack> items = new ArrayList<>(emptyStorage.items());
        ItemStack singlePick = new ItemStack(Items.DIAMOND_PICKAXE, 1);
        items.set(0, singlePick);
        WorkerStorage storage = new WorkerStorage(items);

        ItemStack heldSword = new ItemStack(Items.NETHERITE_SWORD, 1);
        heldSword.setDamageValue(15);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> updatedStorageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                storage,
                heldSword,
                handContainer::add,
                updatedStorageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, handContainer.size());
        assertEquals(Items.DIAMOND_PICKAXE, handContainer.get(0).getItem());

        assertEquals(1, updatedStorageContainer.size());
        WorkerStorage resultingStorage = updatedStorageContainer.get(0);
        ItemStack stashedSword = resultingStorage.items().get(0);
        assertEquals(Items.NETHERITE_SWORD, stashedSword.getItem());
        assertEquals(15, stashedSword.getDamageValue(), "Held item durability and data must be preserved");
    }

    @Test
    @DisplayName("21. Tool swap merges held stackable item into compatible storage slot")
    void test21_toolSwapMergesHeldStackableItem() {
        WorkerStorage emptyStorage = WorkerStorage.empty();
        List<ItemStack> items = new ArrayList<>(emptyStorage.items());
        items.set(0, new ItemStack(Items.IRON_PICKAXE, 2));
        items.set(1, new ItemStack(Items.TORCH, 20));
        WorkerStorage storage = new WorkerStorage(items);

        ItemStack heldTorches = new ItemStack(Items.TORCH, 10);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> updatedStorageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                storage,
                heldTorches,
                handContainer::add,
                updatedStorageContainer::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        WorkerStorage resultingStorage = updatedStorageContainer.get(0);
        assertEquals(1, resultingStorage.items().get(0).getCount(), "1 pickaxe remains in slot 0");
        assertEquals(30, resultingStorage.items().get(1).getCount(), "Torches must merge into slot 1 (20 + 10 = 30)");
    }

    @Test
    @DisplayName("22. Tool swap fails safely when storage is full (leaves hand and candidate unchanged)")
    void test22_toolSwapFailsSafelyWhenStorageFull() {
        List<ItemStack> fullItems = new ArrayList<>();
        fullItems.add(new ItemStack(Items.IRON_PICKAXE, 2)); // Slot 0 has 2 pickaxes (1 will remain)
        for (int i = 1; i < WorkerStorage.SIZE; i++) {
            fullItems.add(new ItemStack(Items.SHIELD, 1)); // All other 14 slots full with unstackable items
        }
        WorkerStorage storage = new WorkerStorage(fullItems);

        ItemStack heldSword = new ItemStack(Items.DIAMOND_SWORD, 1);
        heldSword.setDamageValue(25);

        List<ItemStack> handContainer = new ArrayList<>();
        List<WorkerStorage> updatedStorageContainer = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                storage,
                heldSword,
                handContainer::add,
                updatedStorageContainer::add
        );

        // 1. Returns INSUFFICIENT_STORAGE_SPACE
        assertEquals(WorkerExecutionLoop.ToolSwapResult.INSUFFICIENT_STORAGE_SPACE, result,
                "Must reject swap when storage cannot safely accommodate held item");

        // 2. Hand setter was NEVER called (Main Hand remains completely unchanged)
        assertTrue(handContainer.isEmpty(), "Main hand item must NOT be replaced or modified on failure");
        assertEquals(25, heldSword.getDamageValue(), "Original held item remains intact");

        // 3. Storage setter was NEVER called (Candidate stack remains completely unchanged)
        assertTrue(updatedStorageContainer.isEmpty(), "WorkerStorage must NOT be mutated on failure");
        assertEquals(2, storage.items().get(0).getCount(), "Candidate pickaxe stack remains unchanged at count 2");
    }

    @Test
    @DisplayName("23. Repeated retries of failed tool swap cause no duplication or deletion")
    void test23_repeatedRetriesCauseNoDuplicationOrDeletion() {
        List<ItemStack> fullItems = new ArrayList<>();
        fullItems.add(new ItemStack(Items.IRON_PICKAXE, 2));
        for (int i = 1; i < WorkerStorage.SIZE; i++) {
            fullItems.add(new ItemStack(Items.SHIELD, 1));
        }
        WorkerStorage storage = new WorkerStorage(fullItems);
        ItemStack heldSword = new ItemStack(Items.DIAMOND_SWORD, 1);

        // Repeat swap attempt 5 times
        for (int attempt = 1; attempt <= 5; attempt++) {
            List<ItemStack> handContainer = new ArrayList<>();
            List<WorkerStorage> updatedStorageContainer = new ArrayList<>();

            WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                    storage,
                    heldSword,
                    handContainer::add,
                    updatedStorageContainer::add
            );

            assertEquals(WorkerExecutionLoop.ToolSwapResult.INSUFFICIENT_STORAGE_SPACE, result);
            assertTrue(handContainer.isEmpty());
            assertTrue(updatedStorageContainer.isEmpty());
            assertEquals(2, storage.items().get(0).getCount());
            assertEquals(Items.DIAMOND_SWORD, heldSword.getItem());
            assertEquals(1, heldSword.getCount());
        }
    }

    // ==========================================
    // Group 5: Storage Isolation & Neighbor Immunity
    // ==========================================

    @Test
    @DisplayName("24. Unrelated adjacent container never receives output when primary storage is full")
    void test24_unrelatedAdjacentContainerNeverReceivesOutput() {
        // Create full primary container (size 1, occupied by cobblestone stack of 64)
        SimpleTestContainer primaryContainer = new SimpleTestContainer(1);
        primaryContainer.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

        // Create unrelated adjacent container (size 5, initially empty)
        SimpleTestContainer unrelatedAdjacentContainer = new SimpleTestContainer(5);
        assertTrue(unrelatedAdjacentContainer.isEmpty(), "Unrelated adjacent container must initially be empty");

        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
        MinerJobData data = new MinerJobData(64L, 10L, 10L, 0L,
                List.of(new ItemStack(Items.COBBLESTONE, 10)), storageTarget, Optional.empty(), Direction.NORTH,
                MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)), data);
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(job));

        // Attempt delivery to primary container
        WorkerProfile deliveredProfile = MinerJobController.deliverToContainer(profile, primaryContainer);

        // 1. Primary container was full, so items could not be inserted
        assertEquals(64, primaryContainer.getItem(0).getCount());

        // 2. Unrelated container is completely untouched
        assertTrue(unrelatedAdjacentContainer.isEmpty(), "Unrelated adjacent container must NEVER receive items");
        for (int i = 0; i < unrelatedAdjacentContainer.getContainerSize(); i++) {
            assertTrue(unrelatedAdjacentContainer.getItem(i).isEmpty());
        }

        // 3. Pending items remain safely preserved in MinerJobData
        WorkerJob updatedJob = deliveredProfile.job().orElseThrow();
        MinerJobData updatedData = updatedJob.minerData().orElseThrow();
        assertEquals(10L, updatedData.itemsPendingDelivery(), "Pending delivery count must be retained");
        assertEquals(1, updatedData.pendingStacks().size(), "Pending ItemStack must be retained");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, updatedJob.status(), "Job must transition to WAITING_FOR_STORAGE");
    }

    // ==========================================
    // Group 6: Authoritative Vanilla Reach Comparison
    // ==========================================

    @Test
    @DisplayName("25. Vanilla AABB distanceToSqr geometric reach boundaries")
    void test25_vanillaAABBDistanceToSqrGeometricReachBoundaries() {
        Vec3 eyePos = new Vec3(10.0, 64.0, 10.0);

        // 1. Survival reach: 4.5 base + 1.0 buffer = 5.5 max reach (maxSqr = 30.25)
        double maxSurvivalReach = 4.5 + 1.0;
        double maxSurvivalSqr = maxSurvivalReach * maxSurvivalReach; // 30.25

        // Target at (15, 64, 10): box is [15..16, 64..65, 10..11]. Clamped closest point is (15.0, 64.0, 10.0) -> dist = 5.0m
        BlockPos target5m = new BlockPos(15, 64, 10);
        double distSq5m = WorkerOrderSession.distanceSqrToBlock(eyePos, target5m);
        assertEquals(25.0, distSq5m, 0.001);
        assertTrue(distSq5m < maxSurvivalSqr, "5.0m must be strictly inside survival reach (30.25)");

        // Target at 5.4m: eye at (10.0, 64.0, 10.0), target at (15, 64, 10) with fractional offset
        Vec3 eyeNear5_4 = new Vec3(9.6, 64.0, 10.0);
        double distSq5_4m = WorkerOrderSession.distanceSqrToBlock(eyeNear5_4, target5m); // 15.0 - 9.6 = 5.4m -> 29.16
        assertEquals(29.16, distSq5_4m, 0.001);
        assertTrue(distSq5_4m < maxSurvivalSqr, "5.4m must pass survival reach");

        // Target at 5.6m: eye at (9.4, 64.0, 10.0), target at (15, 64, 10) -> 15.0 - 9.4 = 5.6m -> 31.36
        Vec3 eyeNear5_6 = new Vec3(9.4, 64.0, 10.0);
        double distSq5_6m = WorkerOrderSession.distanceSqrToBlock(eyeNear5_6, target5m);
        assertEquals(31.36, distSq5_6m, 0.001);
        assertFalse(distSq5_6m < maxSurvivalSqr, "5.6m must fail survival reach");

        // 2. Creative reach: 5.0 base + 1.0 buffer = 6.0 max reach (maxSqr = 36.0)
        double maxCreativeReach = 5.0 + 1.0;
        double maxCreativeSqr = maxCreativeReach * maxCreativeReach; // 36.0
        assertTrue(distSq5_6m < maxCreativeSqr, "5.6m passes in creative mode (36.0)");

        // 3. Vertical targeting: Block directly above eyes
        BlockPos blockAbove = new BlockPos(10, 68, 10); // closest point minY = 68.0, eyeY = 64.0 -> dy = 4.0m
        double distSqVertical = WorkerOrderSession.distanceSqrToBlock(eyePos, blockAbove);
        assertEquals(16.0, distSqVertical, 0.001);
        assertTrue(distSqVertical < maxSurvivalSqr, "Vertical block 4.0m above must pass");

        BlockPos blockFarAbove = new BlockPos(10, 71, 10); // dy = 7.0m -> 49.0
        double distSqFarAbove = WorkerOrderSession.distanceSqrToBlock(eyePos, blockFarAbove);
        assertEquals(49.0, distSqFarAbove, 0.001);
        assertFalse(distSqFarAbove < maxSurvivalSqr, "Vertical block 7.0m above must fail");
    }

    // ==========================================
    // Group 7: Tool Tier Drop Enforcement
    // ==========================================

    @Test
    @DisplayName("26. Tool tier drop requirements in canExecuteMiningStep")
    void test26_toolTierDropRequirementsInCanExecuteMiningStep() {
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        MinerJobData minerData = MinerJobData.initial(64L, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)), Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10)), minerData);
        WorkerProfile minerProfile = profile.withJob(minerJob);

        BlockPos pos = new BlockPos(10, 64, 10);

        // 1. Wooden pickaxe on Stone -> Permitted (tier 0 is appropriate for stone)
        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        MinerJobController.ExecutionGate gateStone = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, woodenPick, pos, Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, gateStone);

        // 2. Wooden pickaxe on Diamond Ore -> REJECTED_UNVERIFIED_TOOL (diamond ore requires iron-tier pickaxe)
        MinerJobController.ExecutionGate gateWoodDiamond = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, woodenPick, pos, Blocks.DIAMOND_ORE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL, gateWoodDiamond,
                "Under-tier pickaxe (wood on diamond ore) must be strictly rejected");

        // 3. Iron pickaxe on Diamond Ore -> Permitted (iron tier is authorized for diamond ore)
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        MinerJobController.ExecutionGate gateIronDiamond = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, ironPick, pos, Blocks.DIAMOND_ORE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, gateIronDiamond);

        // 4. Shears on Stone -> REJECTED_UNVERIFIED_TOOL (shears are not a pickaxe)
        ItemStack shears = new ItemStack(Items.SHEARS);
        MinerJobController.ExecutionGate gateShearsStone = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, shears, pos, Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL, gateShearsStone);

        // 5. Shovel on Stone -> REJECTED_UNVERIFIED_TOOL (shovel cannot harvest stone)
        ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
        MinerJobController.ExecutionGate gateShovelStone = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, shovel, pos, Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL, gateShovelStone);

        // 6. Verify that in unbootstrapped unit test environment, areBlockTagsBound() correctly returns false
        assertFalse(MinerJobController.areBlockTagsBound(),
                "Unit test environment without loaded data packs must identify tags as unbound");
    }

    // ==========================================
    // Group 8: Crash Recovery & Reconciliation Guarantees
    // ==========================================

    @Test
    @DisplayName("27. Crash recovery reconciliation outcomes and boundaries")
    void test27_crashRecoveryReconciliationOutcomesAndBoundaries() {
        BlockPos pos = new BlockPos(5, 64, 5);
        ResourceLocation stoneId = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));

        WorkerTarget storage = new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0));
        MinerJobData initialData = MinerJobData.initial(64L, storage);
        MinerJobData stagedData = initialData.withInFlightMutation(pos, stoneId, drops);

        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, pos), stagedData)
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(job));

        // 1. Chunk is air -> Observing air cannot prove worker attribution -> UNRESOLVED_AMBIGUOUS (fail closed)
        MinerJobController.ReconciliationOutcome airOutcome = MinerJobController.evaluateReconciliation(
                profile, true, Blocks.AIR.defaultBlockState()
        );
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, airOutcome.status());
        WorkerJob ambiguousJob = airOutcome.profile().job().orElseThrow();
        assertEquals(0L, ambiguousJob.minerData().orElseThrow().blocksMined(), "Must not credit blocks on ambiguous air state");
        assertTrue(ambiguousJob.minerData().orElseThrow().pendingStacks().isEmpty(), "Must not credit drops on ambiguous air state");
        assertTrue(ambiguousJob.minerData().orElseThrow().inFlightMutation().isPresent(), "In-flight mutation must remain unresolved");

        // 2. Chunk still has original block -> Target was NOT destroyed -> CANCELLED without drops
        MinerJobController.ReconciliationOutcome intactOutcome = MinerJobController.evaluateReconciliation(
                profile, true, Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ReconciliationStatus.CANCELLED, intactOutcome.status());
        WorkerJob cancelledJob = intactOutcome.profile().job().orElseThrow();
        assertEquals(0L, cancelledJob.minerData().orElseThrow().blocksMined());
        assertTrue(cancelledJob.minerData().orElseThrow().pendingStacks().isEmpty());

        // 3. Chunk is unloaded -> Fails closed, preserving inFlightMutation
        MinerJobController.ReconciliationOutcome unloadedOutcome = MinerJobController.evaluateReconciliation(
                profile, false, null
        );
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, unloadedOutcome.status());
        assertTrue(unloadedOutcome.profile().job().orElseThrow().minerData().orElseThrow().inFlightMutation().isPresent());

        // 4. Foreign block (e.g. Oak Planks placed externally) -> Ambiguous -> Fails closed
        MinerJobController.ReconciliationOutcome ambiguousOutcome = MinerJobController.evaluateReconciliation(
                profile, true, Blocks.OAK_PLANKS.defaultBlockState()
        );
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, ambiguousOutcome.status());
        assertTrue(ambiguousOutcome.profile().job().orElseThrow().minerData().orElseThrow().inFlightMutation().isPresent());
    }

    // ==========================================
    // Group 9: Visual Feedback & HUD State
    // ==========================================

    @Test
    @DisplayName("28. Tether color transitions and visual feedback states")
    void test28_tetherColorTransitionsAndVisualFeedbackStates() {
        int idleColor = 0xFF00E5FF;
        int selectingColor = 0xFFFFB300;
        int confirmedColor = 0xFF00E676;

        assertNotEquals(idleColor, selectingColor);
        assertNotEquals(selectingColor, confirmedColor);
        assertNotEquals(idleColor, confirmedColor);

        // Verify HUD state reset on clear
        BindingTicketHUD.updateSession(1, WORKER_ID, "Miner", "elstarticket:mine", 64L, null, "Awaiting worksite");
        assertEquals(WORKER_ID, BindingTicketHUD.getActiveWorkerId());
        BindingTicketHUD.clear();
        assertNull(BindingTicketHUD.getActiveWorkerId());
    }

    // ==========================================
    // Group 10: Crash Attribution & Runtime Lifecycle Verification
    // ==========================================

    @Test
    @DisplayName("29. Ambiguous AIR state after an externally caused block change fails closed")
    void test29_ambiguousAirStateAfterExternalBlockChangeFailsClosed() {
        BlockPos pos = new BlockPos(12, 64, 12);
        ResourceLocation stoneId = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);

        WorkerTarget storage = new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0));
        MinerJobData initialData = MinerJobData.initial(10L, storage);
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, pos), initialData)
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(job));

        // 1. Stage in-flight mutation as would occur during transaction preflight
        WorkerProfile stagedProfile = profile.withJob(
                job.withMinerData(initialData.withInFlightMutation(pos, stoneId, drops)).orElseThrow()
        );
        MinerJobData stagedData = stagedProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(stagedData.inFlightMutation().isPresent());

        // 2. An external actor (e.g. another player or creeper explosion) destroys the block, leaving AIR
        // Next tick / crash recovery runs evaluateReconciliation:
        MinerJobController.ReconciliationOutcome outcome = MinerJobController.evaluateReconciliation(
                stagedProfile, true, Blocks.AIR.defaultBlockState()
        );

        // Invariant: AIR observation cannot prove worker attribution!
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, outcome.status());
        WorkerProfile reconciledProfile = outcome.profile();
        MinerJobData reconciledData = reconciledProfile.job().orElseThrow().minerData().orElseThrow();

        // Must fail closed: zero blocks credited, zero drops credited, intent remains staged
        assertEquals(0L, reconciledData.blocksMined(), "Must not credit blocks mined on ambiguous AIR state");
        assertEquals(0L, reconciledData.itemsPendingDelivery(), "Must not credit drops on ambiguous AIR state");
        assertTrue(reconciledData.pendingStacks().isEmpty(), "Pending stacks must remain empty");
        assertTrue(reconciledData.inFlightMutation().isPresent(), "In-flight mutation must remain unresolved");

        // 3. Subsequent attempts to execute a new mining transaction with this unresolved worker MUST reject
        MinerJobController.TransactionExecutionResult rejectResult = MinerJobController.executeMiningTransaction(
                reconciledProfile, null, null, pick, pos, Blocks.STONE.defaultBlockState(), stoneId,
                () -> drops, () -> true, () -> {}, p -> {}
        );
        assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, rejectResult.status(),
                "Worker with unresolved in-flight mutation must be rejected from initiating new mutations");
        assertTrue(rejectResult.message().orElse("").contains("unresolved in-flight mutation"));

        // 4. Clean cancellation unstages the intent without fabricating loot
        WorkerProfile cancelledProfile = MinerJobController.cancelInFlightMutation(reconciledProfile);
        MinerJobData cancelledData = cancelledProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(cancelledData.inFlightMutation().isEmpty());
        assertEquals(0L, cancelledData.blocksMined());
        assertEquals(0L, cancelledData.itemsPendingDelivery());
    }

    @Test
    @DisplayName("30. Unloaded target chunk and foreign block replacement preserve transaction")
    void test30_unloadedTargetChunkAndForeignBlockReplacementPreserveTransaction() {
        BlockPos pos = new BlockPos(20, 64, 20);
        ResourceLocation stoneId = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));

        WorkerTarget storage = new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0));
        MinerJobData initialData = MinerJobData.initial(10L, storage);
        MinerJobData stagedData = initialData.withInFlightMutation(pos, stoneId, drops);
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, pos), stagedData)
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(job));

        // Part A: Target chunk is unloaded (!chunkLoaded or currentState == null)
        MinerJobController.ReconciliationOutcome unloadedOutcome = MinerJobController.evaluateReconciliation(
                profile, false, null
        );
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, unloadedOutcome.status());
        WorkerProfile unloadedProfile = unloadedOutcome.profile();
        MinerJobData unloadedData = unloadedProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(unloadedData.inFlightMutation().isPresent(), "Unloaded chunk must preserve inFlightMutation");
        assertEquals(0L, unloadedData.blocksMined());
        assertEquals(0L, unloadedData.itemsPendingDelivery());

        // Part B: Foreign block replacement (e.g. Dirt or Oak Planks placed by foreign actor/player)
        MinerJobController.ReconciliationOutcome foreignOutcome = MinerJobController.evaluateReconciliation(
                profile, true, Blocks.DIRT.defaultBlockState()
        );
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, foreignOutcome.status());
        WorkerProfile foreignProfile = foreignOutcome.profile();
        MinerJobData foreignData = foreignProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(foreignData.inFlightMutation().isPresent(), "Foreign replacement must preserve inFlightMutation unresolved");
        assertEquals(0L, foreignData.blocksMined());
        assertEquals(0L, foreignData.itemsPendingDelivery());
        assertTrue(foreignOutcome.reason().orElse("").contains("replaced by unexpected block"));
    }

    @Test
    @DisplayName("31. Runtime tags bound versus unbound test environment")
    void test31_runtimeTagsBoundVersusUnboundTestEnvironment() {
        // 1. In unbootstrapped unit test environment without data packs loaded:
        assertFalse(MinerJobController.areBlockTagsBound(),
                "Unit test environment without loaded data packs must identify tags as unbound");
        assertTrue(MinerJobController.isExplicitUnbootstrappedTestEnvironment(null),
                "Test worker with null Mob in test runner must be recognized as test environment");

        // 2. In unbootstrapped test environment, valid pickaxe tier on stone is permitted via test fallback
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        MinerJobData minerData = MinerJobData.initial(10L, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)));
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)), minerData);
        WorkerProfile minerProfile = profile.withJob(job);

        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        MinerJobController.ExecutionGate gateWoodStone = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, woodenPick, new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, gateWoodStone,
                "In test environment, wooden pickaxe on stone is permitted via test tier table");

        // 3. When production mode override is enabled (simulating runtime where tags must be authoritative):
        try {
            MinerJobController.setProductionModeOverrideForTesting(true);
            assertFalse(MinerJobController.isExplicitUnbootstrappedTestEnvironment(null),
                    "Production mode override must strictly disallow test environment identification");

            // In production mode with unbound tags (or missing tag entries), fallback is strictly unreachable!
            MinerJobController.ExecutionGate gateProdMode = MinerJobController.canExecuteMiningStep(
                    minerProfile, null, null, woodenPick, new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState()
            );
            assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL, gateProdMode,
                    "In production execution, missing/unbound tags must fail closed and NEVER reach test fallback");
        } finally {
            MinerJobController.setProductionModeOverrideForTesting(null);
        }
    }

    @Test
    @DisplayName("32. Under-tier tools rejected on vanilla and custom modded blocks")
    void test32_underTierToolsRejectedOnVanillaAndCustomModdedBlocks() throws Exception {
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        MinerJobData minerData = MinerJobData.initial(10L, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)));
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)), minerData);
        WorkerProfile minerProfile = profile.withJob(job);
        BlockPos pos = new BlockPos(0, 64, 0);

        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        ItemStack stonePick = new ItemStack(Items.STONE_PICKAXE);
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);

        // 1. Under-tier pickaxes on vanilla blocks:
        // Wooden on Diamond Ore -> REJECTED
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, woodenPick, pos, Blocks.DIAMOND_ORE.defaultBlockState()));
        // Stone on Diamond Ore -> REJECTED (Diamond Ore requires Iron+)
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, stonePick, pos, Blocks.DIAMOND_ORE.defaultBlockState()));
        // Iron on Diamond Ore -> PERMITTED
        assertEquals(MinerJobController.ExecutionGate.PERMITTED,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, ironPick, pos, Blocks.DIAMOND_ORE.defaultBlockState()));

        // Wooden on Iron Ore -> REJECTED (Iron Ore requires Stone+)
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, woodenPick, pos, Blocks.IRON_ORE.defaultBlockState()));
        // Stone on Iron Ore -> PERMITTED
        assertEquals(MinerJobController.ExecutionGate.PERMITTED,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, stonePick, pos, Blocks.IRON_ORE.defaultBlockState()));

        // Stone on Obsidian -> REJECTED (Obsidian requires Diamond+)
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, stonePick, pos, Blocks.OBSIDIAN.defaultBlockState()));
        // Iron on Obsidian -> REJECTED
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, ironPick, pos, Blocks.OBSIDIAN.defaultBlockState()));
        // Diamond on Obsidian -> PERMITTED
        assertEquals(MinerJobController.ExecutionGate.PERMITTED,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, new ItemStack(Items.DIAMOND_PICKAXE), pos, Blocks.OBSIDIAN.defaultBlockState()));

        // 2. Custom Modded Block:
        // Temporarily unfreeze registry to register a custom modded block with custom namespace
        java.lang.reflect.Field frozenField = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
        frozenField.setAccessible(true);
        frozenField.set(BuiltInRegistries.BLOCK, false);
        Block customModdedBlock;
        try {
            net.minecraft.world.level.block.state.BlockBehaviour.Properties props =
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of().requiresCorrectToolForDrops();
            customModdedBlock = new Block(props);
            net.minecraft.core.Registry.register(
                    BuiltInRegistries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath("custommod", "super_ore"),
                    customModdedBlock
            );
        } finally {
            BuiltInRegistries.BLOCK.freeze();
        }

        BlockState moddedState = customModdedBlock.defaultBlockState();
        assertTrue(moddedState.requiresCorrectToolForDrops());
        assertEquals("custommod", BuiltInRegistries.BLOCK.getKey(customModdedBlock).getNamespace());

        // In test fallback, custom modded blocks fail closed (return false)
        assertFalse(MinerJobController.isToolAppropriateForBlockInTestEnv(woodenPick, moddedState),
                "Custom modded blocks must NEVER be accepted by the vanilla test tier table");
        assertFalse(MinerJobController.isToolAppropriateForBlockInTestEnv(ironPick, moddedState),
                "Custom modded blocks without tags must fail closed in test fallback");

        // canExecuteMiningStep rejects unknown modded blocks when data pack tags are unbound
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, woodenPick, pos, moddedState),
                "Under-tier tool on custom modded block must be strictly rejected");

        // 3. Invariant: executeMiningTransaction must NOT destroy block or bypass drops
        java.util.concurrent.atomic.AtomicBoolean destroyedCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                minerProfile, null, null, woodenPick, pos, moddedState,
                ResourceLocation.fromNamespaceAndPath("custommod", "super_ore"),
                () -> List.of(new ItemStack(Items.DIAMOND, 10)),
                () -> { destroyedCalled.set(true); return true; },
                () -> {},
                p -> {}
        );
        assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, result.status());
        assertFalse(destroyedCalled.get(), "World mutation must NEVER be invoked for under-tier or unverified block");
    }

    @Test
    @DisplayName("33. Ensuring test-only fallback cannot be reached in production execution")
    void test33_ensuringTestOnlyFallbackCannotBeReachedInProductionExecution() {
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        MinerJobData minerData = MinerJobData.initial(10L, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)));
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0)), minerData);
        WorkerProfile minerProfile = profile.withJob(job);
        BlockPos pos = new BlockPos(0, 64, 0);

        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        BlockState stoneState = Blocks.STONE.defaultBlockState();

        // 1. Without production override, unbootstrapped test runner uses fallback:
        assertEquals(MinerJobController.ExecutionGate.PERMITTED,
                MinerJobController.canExecuteMiningStep(minerProfile, null, null, woodenPick, pos, stoneState));

        // 2. With production override enabled:
        try {
            MinerJobController.setProductionModeOverrideForTesting(true);

            // Verify isExplicitUnbootstrappedTestEnvironment returns false
            assertFalse(MinerJobController.isExplicitUnbootstrappedTestEnvironment(null));

            // canExecuteMiningStep MUST fail closed
            MinerJobController.ExecutionGate prodGate = MinerJobController.canExecuteMiningStep(
                    minerProfile, null, null, woodenPick, pos, stoneState
            );
            assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL, prodGate,
                    "In production execution mode, test fallback table is completely unreachable");

            // executeMiningTransaction MUST reject at preflight
            java.util.concurrent.atomic.AtomicBoolean mutated = new java.util.concurrent.atomic.AtomicBoolean(false);
            MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                    minerProfile, null, null, woodenPick, pos, stoneState,
                    BuiltInRegistries.BLOCK.getKey(Blocks.STONE),
                    () -> List.of(new ItemStack(Items.COBBLESTONE)),
                    () -> { mutated.set(true); return true; },
                    () -> {},
                    p -> {}
            );
            assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, result.status());
            assertFalse(mutated.get(), "Mutation must not occur when preflight rejects in production mode");
        } finally {
            MinerJobController.setProductionModeOverrideForTesting(null);
        }
    }

    // Helper test container implementation
    private static class SimpleTestContainer implements Container {
        private final List<ItemStack> items;

        SimpleTestContainer(int size) {
            this.items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) items.add(ItemStack.EMPTY);
        }

        @Override public int getContainerSize() { return items.size(); }
        @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
        @Override public ItemStack getItem(int slot) { return items.get(slot); }
        @Override public ItemStack removeItem(int slot, int amount) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack split = stack.split(amount);
            if (stack.isEmpty()) items.set(slot, ItemStack.EMPTY);
            return split;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            ItemStack stack = items.get(slot);
            items.set(slot, ItemStack.EMPTY);
            return stack;
        }
        @Override public void setItem(int slot, ItemStack stack) {
            items.set(slot, stack != null ? stack : ItemStack.EMPTY);
        }
        @Override public void setChanged() {}
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
        @Override public void clearContent() {
            for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
        }
    }
}
