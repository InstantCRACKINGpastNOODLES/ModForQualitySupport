package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 Verification Suite:
 * 1. Unified Job Item Reservation Contract (Miner, Farmer, Job Queue, In-Flight mutations, custom adapters).
 * 2. Quantity-aware stack accounting and DataComponent fidelity.
 * 3. Chunk-safe emergency shelter placement without force-loading.
 * 4. Safe waiting and resumption on chunk availability.
 */
class JobReservationAndChunkSafetyTest {

    private UUID workerId;
    private UUID ownerId;
    private ResourceLocation dimension;
    private WorkerTarget worksite;
    private WorkerTarget storageTarget;

    @BeforeEach
    void setUp() {
        workerId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        worksite = new WorkerTarget(dimension, new BlockPos(100, 64, 100));
        storageTarget = new WorkerTarget(dimension, new BlockPos(105, 64, 100));
        JobReservationRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        JobReservationRegistry.clear();
    }

    // ========================================================
    // 1. Unified Reservation: Miner Pending Output Protection
    // ========================================================
    @Test
    @DisplayName("1. Miner pending delivery output stacks are protected from shelter extraction")
    void test1_minerPendingOutputProtected() {
        ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 32);
        MinerJobData minerData = new MinerJobData(
                100, 32, 32, 0,
                List.of(pendingCobble),
                storageTarget,
                Optional.empty(),
                Direction.NORTH,
                MinerJobData.ObjectiveMode.BLOCK_COUNT
        );
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, worksite, minerData);
        WorkerProfile profile = new WorkerProfile(workerId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(job));

        List<ItemStack> reserved = profile.getReservedItemStacks();
        assertEquals(1, reserved.size());
        assertEquals(32, reserved.get(0).getCount());
        assertEquals(Items.COBBLESTONE, reserved.get(0).getItem());

        // WorkerStorage contains 32 cobblestone (all reserved)
        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 32));
        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(0, available, "All 32 cobblestones belong to miner pending delivery and must be protected");

        var extraction = EquipmentCapabilityPolicy.extractBuildingBlock(storage, reserved);
        assertTrue(extraction.isEmpty(), "Extraction must fail when all building blocks are reserved by Miner");
    }

    // ========================================================
    // 2. Unified Reservation: Farmer Pending Output Protection
    // ========================================================
    @Test
    @DisplayName("2. Farmer pending produce output stacks are protected from shelter extraction")
    void test2_farmerPendingOutputProtected() {
        // Farmer harvested pumpkins and oak wood (both are solid BlockItems)
        ItemStack pendingPumpkins = new ItemStack(Blocks.PUMPKIN.asItem(), 16);
        ItemStack pendingLogs = new ItemStack(Blocks.OAK_LOG.asItem(), 8);

        FarmerJobData farmerData = new FarmerJobData(
                50, 24, 24, 0,
                List.of(pendingPumpkins, pendingLogs),
                storageTarget,
                Optional.empty(),
                List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "pumpkin"))
        );
        WorkerJob job = WorkerJob.assignedFarmer(WorkerJobs.FARM, worksite, farmerData);
        WorkerProfile profile = new WorkerProfile(workerId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(job));

        List<ItemStack> reserved = profile.getReservedItemStacks();
        assertEquals(2, reserved.size());

        // Storage has 16 pumpkins, 8 logs, and 10 stone blocks
        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Blocks.PUMPKIN.asItem(), 16))
                .deposit(new ItemStack(Blocks.OAK_LOG.asItem(), 8))
                .deposit(new ItemStack(Items.STONE, 10));

        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(10, available, "Only the 10 unreserved stone blocks should be available; pumpkins and logs are reserved");

        // Extracting 1 block must take STONE, never reserved pumpkins or logs
        var extraction = EquipmentCapabilityPolicy.extractBuildingBlock(storage, reserved);
        assertTrue(extraction.isPresent());
        assertEquals(Items.STONE, extraction.get().extractedBlock().getItem());
    }

    // ========================================================
    // 3. Unified Reservation: Job Queue Step Protection
    // ========================================================
    @Test
    @DisplayName("3. Job Queue item requirements and expected outputs are protected")
    void test3_jobQueueRequirementsAndOutputsProtected() {
        ItemStack reqBricks = new ItemStack(Blocks.BRICKS.asItem(), 20);
        ItemStack outIron = new ItemStack(Blocks.IRON_BLOCK.asItem(), 5);

        JobStep step1 = new JobStep(
                UUID.randomUUID(),
                "MACHINE_CRAFT",
                Optional.of(new BlockPos(100, 64, 100)),
                Optional.empty(),
                reqBricks,
                outIron,
                1, 0,
                ExecutionState.RUNNING,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        Job job = new Job(
                UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("test", "craft_job"),
                "Craft Iron Blocks",
                ExecutionState.RUNNING,
                0,
                List.of(step1),
                1, 0,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
        WorkerProfile profile = new WorkerProfile(
                workerId, ownerId, dimension, WorkerStatus.IDLE,
                Optional.empty(), WorkerBehavior.STAND, Optional.empty(), queue
        );

        List<ItemStack> reserved = profile.getReservedItemStacks();
        // Active step: only physical item requirement (20 bricks) is reserved; predicted output (5 iron blocks) is NOT reserved
        assertEquals(1, reserved.size(), "Only physical item requirement is reserved; predicted output must not be ghost-reserved");
        assertEquals(Blocks.BRICKS.asItem(), reserved.get(0).getItem());
        assertEquals(20, reserved.get(0).getCount());

        // Storage has 20 bricks and 5 cobblestone
        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Blocks.BRICKS.asItem(), 20))
                .deposit(new ItemStack(Items.COBBLESTONE, 5));

        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(5, available, "Only the 5 cobblestone are available; the 20 bricks are required by the active job step");

        var extraction = EquipmentCapabilityPolicy.extractBuildingBlock(storage, reserved);
        assertTrue(extraction.isPresent());
        assertEquals(Items.COBBLESTONE, extraction.get().extractedBlock().getItem());
    }

    // ========================================================
    // 4. In-Flight Mutation & Harvest Protection
    // ========================================================
    @Test
    @DisplayName("4. In-flight mutation and in-flight harvest drops are protected")
    void test4_inFlightDropsProtected() {
        ItemStack expectedDrop = new ItemStack(Blocks.DEEPSLATE.asItem(), 1);
        MinerJobData.InFlightMutation mutation = new MinerJobData.InFlightMutation(
                new BlockPos(100, 64, 100),
                ResourceLocation.fromNamespaceAndPath("minecraft", "deepslate"),
                List.of(expectedDrop)
        );

        MinerJobData minerData = new MinerJobData(
                10, 0, 0, 0,
                List.of(),
                storageTarget,
                Optional.of(mutation),
                Direction.NORTH,
                MinerJobData.ObjectiveMode.BLOCK_COUNT
        );
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, worksite, minerData);
        WorkerProfile profile = new WorkerProfile(workerId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(job));

        List<ItemStack> reserved = profile.getReservedItemStacks();
        assertEquals(1, reserved.size());
        assertEquals(Blocks.DEEPSLATE.asItem(), reserved.get(0).getItem());

        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Blocks.DEEPSLATE.asItem(), 1));

        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(0, available, "In-flight mutation drop must be protected");
    }

    // ========================================================
    // 5. Quantity-Aware Stack Accounting
    // ========================================================
    @Test
    @DisplayName("5. Quantity-aware accounting: 20 Cobblestone in storage with 12 reserved allows exactly 8 available")
    void test5_quantityAwareAccountingExactMath() {
        // Scenario from user specification:
        // WorkerStorage contains 20 cobblestone. 12 cobblestone are reserved for pending delivery.
        // Exactly 8 cobblestone may be considered available.
        ItemStack reservation = new ItemStack(Items.COBBLESTONE, 12);
        List<ItemStack> reserved = List.of(reservation);

        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Items.COBBLESTONE, 20));

        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(8, available, "Exactly 8 cobblestones must be available (20 in storage - 12 reserved)");

        // Extract 8 times successfully
        for (int i = 0; i < 8; i++) {
            var result = EquipmentCapabilityPolicy.extractBuildingBlock(storage, reserved);
            assertTrue(result.isPresent(), "Step " + (i + 1) + " must succeed");
            storage = result.get().updatedStorage();
        }

        // 9th extraction must fail: only the 12 reserved cobblestones remain
        var failedResult = EquipmentCapabilityPolicy.extractBuildingBlock(storage, reserved);
        assertTrue(failedResult.isEmpty(), "9th extraction must fail; remaining 12 cobblestone are strictly reserved");

        // Verify remaining count in storage is exactly 12
        int remainingCobble = storage.items().stream()
                .filter(s -> !s.isEmpty() && s.getItem() == Items.COBBLESTONE)
                .mapToInt(ItemStack::getCount)
                .sum();
        assertEquals(12, remainingCobble, "All 12 reserved cobblestones must remain intact in storage");
    }

    // ========================================================
    // 6. DataComponent Separation in Stack Accounting
    // ========================================================
    @Test
    @DisplayName("6. Component-distinct stacks are separated; custom-named items are not merged with vanilla items")
    void test6_componentDistinctStacksSeparated() {
        ItemStack vanillaStone = new ItemStack(Items.STONE, 10);
        ItemStack customNamedStone = new ItemStack(Items.STONE, 5);
        customNamedStone.set(DataComponents.CUSTOM_NAME, Component.literal("Special Building Stone"));

        // Reservation is for vanilla stone
        List<ItemStack> reserved = List.of(vanillaStone);

        // Storage contains both vanilla stone and custom named stone
        WorkerStorage storage = WorkerStorage.empty()
                .deposit(vanillaStone)
                .deposit(customNamedStone);

        // Available count: vanilla stone (10 in storage - 10 reserved = 0) + custom named stone (5 in storage - 0 reserved = 5) = 5
        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(5, available);

        // Extraction takes from customNamedStone, leaving vanillaStone completely untouched
        var result = EquipmentCapabilityPolicy.extractBuildingBlock(storage, reserved);
        assertTrue(result.isPresent());
        assertEquals("Special Building Stone", result.get().extractedBlock().getHoverName().getString());
    }

    // ========================================================
    // 7. Multiple Reservations Do Not Double-Subtract
    // ========================================================
    @Test
    @DisplayName("7. Multiple distinct reservations sum correctly without double-subtracting")
    void test7_multipleReservationsSumCorrectly() {
        ItemStack res1 = new ItemStack(Items.COBBLESTONE, 5);
        ItemStack res2 = new ItemStack(Items.COBBLESTONE, 7);
        List<ItemStack> reserved = List.of(res1, res2); // Total 12 reserved

        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Items.COBBLESTONE, 15));

        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(3, available, "15 total - (5 + 7 reserved) = 3 available");
    }

    // ========================================================
    // 8. Future Job Adapter Registration
    // ========================================================
    @Test
    @DisplayName("8. Future job adapter registered via JobReservationRegistry exposes reservations without core edits")
    void test8_customJobAdapterRegistry() {
        ResourceLocation customType = ResourceLocation.fromNamespaceAndPath("custommod", "quarry");

        JobReservationRegistry.register(customType, prof -> {
            return List.of(new ItemStack(Blocks.SANDSTONE.asItem(), 15));
        });

        WorkerJob customJob = new WorkerJob(
                customType, JobStatus.RUNNING, 0, Optional.empty(), Optional.of(worksite), Optional.empty(), Optional.empty()
        );
        WorkerProfile profile = new WorkerProfile(workerId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(customJob));

        List<ItemStack> reserved = profile.getReservedItemStacks();
        assertEquals(1, reserved.size());
        assertEquals(Blocks.SANDSTONE.asItem(), reserved.get(0).getItem());
        assertEquals(15, reserved.get(0).getCount());

        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Blocks.SANDSTONE.asItem(), 20));

        int available = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reserved);
        assertEquals(5, available, "Custom job adapter reservations must be respected (20 - 15 = 5)");
    }

    // ========================================================
    // 9. Uncertain Active Job Fails Safely
    // ========================================================
    @Test
    @DisplayName("9. Active job with unknown type and no registered provider fails safe (uncertain reservations)")
    void test9_uncertainActiveJobFailsSafe() {
        ResourceLocation unknownType = ResourceLocation.fromNamespaceAndPath("unknownmod", "mystic_job");
        WorkerJob unknownJob = new WorkerJob(
                unknownType, JobStatus.RUNNING, 0, Optional.empty(), Optional.of(worksite), Optional.empty(), Optional.empty()
        );
        WorkerProfile profile = new WorkerProfile(workerId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(unknownJob));

        assertTrue(profile.hasUncertainReservation(), "Unregistered active job type must be flagged as uncertain");
    }

    // ========================================================
    // 10. Chunk-Safe Placement: Loaded Chunk Places Normally
    // ========================================================
    @Test
    @DisplayName("10. Shelter placement in loaded chunk proceeds normally")
    void test10_loadedChunkPlacementProceeds() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);

        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));

        // When chunk is loaded (predicate returns true), placement succeeds
        var result = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> true);

        assertTrue(result.placedBlock());
        assertFalse(result.halted());
        assertEquals(1, state.getPlacedCount());
        assertEquals(1, state.getNextIndex());
    }

    // ========================================================
    // 11. Chunk-Safe Placement: Unloaded Chunk Triggers WAITING_FOR_CHUNK
    // ========================================================
    @Test
    @DisplayName("11. Target in unloaded chunk halts placement with WAITING_FOR_CHUNK; 0 materials consumed, index preserved")
    void test11_unloadedChunkHaltsWithWaitingForChunk() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);

        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));

        // Chunk is not loaded (predicate returns false)
        var result = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> false);

        assertFalse(result.placedBlock(), "Must not place block in unloaded chunk");
        assertTrue(result.halted(), "Must halt when target chunk is unloaded");
        assertTrue(state.isHalted());
        assertEquals("WAITING_FOR_CHUNK", state.getHaltReason().orElse(""));

        // Invariant: Next index remains 0, placed count remains 0
        assertEquals(0, state.getNextIndex(), "Index must NOT advance on unloaded chunk");
        assertEquals(0, state.getPlacedCount());
        assertFalse(state.isComplete());

        // Invariant: 0 materials consumed (10 cobblestones intact)
        int remaining = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(result.updatedStorage(), List.of());
        assertEquals(10, remaining, "No materials may be consumed for an unloaded chunk target");
    }

    // ========================================================
    // 12. Chunk-Safe Placement: Natural Loading Allows Resumption
    // ========================================================
    @Test
    @DisplayName("12. Natural loading of chunk clears halt and resumes construction from preserved nextIndex")
    void test12_naturalChunkLoadingResumesConstruction() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.DIRECTIONAL_BARRIER, Direction.NORTH, 3
        );
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);

        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));

        // Step 1: Placed block 0 in loaded chunk
        var r1 = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> true);
        assertTrue(r1.placedBlock());
        assertEquals(1, state.getNextIndex());
        storage = r1.updatedStorage();

        // Step 2: Target pos 1 is in unloaded chunk -> halts!
        var r2 = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> false);
        assertTrue(r2.halted());
        assertEquals("WAITING_FOR_CHUNK", state.getHaltReason().orElse(""));
        assertEquals(1, state.getNextIndex(), "nextIndex preserved at 1");

        // Step 3: Chunk becomes naturally loaded -> un-halt and step again
        state.resumeWithMaterials();
        assertFalse(state.isHalted());

        var r3 = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> true);
        assertTrue(r3.placedBlock(), "Placement must resume successfully once chunk loads");
        assertEquals(2, state.getNextIndex(), "nextIndex advances to 2");
        assertEquals(2, state.getPlacedCount(), "Total placed count is 2 (no duplicate placements)");
    }

    // ========================================================
    // 13. Serialization of WAITING_FOR_CHUNK State
    // ========================================================
    @Test
    @DisplayName("13. Serialization roundtrip preserves WAITING_FOR_CHUNK halt state across world saves")
    void test13_waitingForChunkSerializationRoundtrip() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);

        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));

        // Step placement into unloaded chunk to enter WAITING_FOR_CHUNK
        state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> false);
        assertTrue(state.isHalted());
        assertEquals("WAITING_FOR_CHUNK", state.getHaltReason().orElse(""));

        // Serialize to NBT tag
        var tag = state.toTag();
        assertTrue(tag.getBoolean("IsHalted"));
        assertEquals("WAITING_FOR_CHUNK", tag.getString("HaltReason"));

        // Deserialize from NBT tag
        var restored = EquipmentCapabilityPolicy.ShelterConstructionState.fromTag(tag);
        assertTrue(restored.isHalted());
        assertEquals("WAITING_FOR_CHUNK", restored.getHaltReason().orElse(""));
        assertEquals(state.getNextIndex(), restored.getNextIndex());
        assertEquals(state.getPlan().mode(), restored.getPlan().mode());
    }

    // ========================================================
    // 14. Pipeline Sequential Step Non-Double-Counting
    // ========================================================
    @Test
    @DisplayName("14. Pipeline sequential steps do not double-count inputs or ghost-reserve unproduced outputs")
    void test14_pipelineSequentialStepNoDoubleCounting() {
        JobStep step0 = JobStep.extractItems(new BlockPos(10, 64, 10), new ItemStack(Items.COBBLESTONE, 10), 1);
        JobStep step1 = JobStep.interactMachine(new BlockPos(20, 64, 20), Direction.UP, new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.STONE, 10), 1);
        JobStep step2 = JobStep.depositItems(new BlockPos(30, 64, 30));

        Job job = new Job(
                UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("test", "pipeline_job"),
                "Smelt Cobblestone Pipeline",
                ExecutionState.RUNNING,
                0,
                List.of(step0, step1, step2),
                1, 0,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
        List<ItemStack> reserved = queue.getReservedItemStacks();

        // When step 0 is active, only step 0's 10 Cobblestone should be reserved
        assertEquals(1, reserved.size(), "Only active step 0 should have its requirement reserved");
        assertEquals(10, reserved.get(0).getCount(), "Must reserve exactly 10 cobblestone, not double-counted 20");
        assertEquals(Items.COBBLESTONE, reserved.get(0).getItem());

        // Stone (unproduced output of future step 1) must NOT be present as a ghost reservation
        boolean hasStone = reserved.stream().anyMatch(s -> s.getItem() == Items.STONE);
        assertFalse(hasStone, "Future unproduced outputs must never be ghost-reserved");
    }

    // ========================================================
    // 15. Completed Step Output Delivery Preservation
    // ========================================================
    @Test
    @DisplayName("15. Completed step output is protected awaiting deposit in subsequent step")
    void test15_completedStepOutputPreservedAwaitingDeposit() {
        JobStep step0 = JobStep.extractItems(new BlockPos(10, 64, 10), new ItemStack(Items.COBBLESTONE, 10), 1)
                .withState(ExecutionState.COMPLETED, null);
        JobStep step1 = JobStep.interactMachine(new BlockPos(20, 64, 20), Direction.UP, new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.STONE, 10), 1)
                .withState(ExecutionState.COMPLETED, null);
        JobStep step2 = JobStep.depositItems(new BlockPos(30, 64, 30))
                .withState(ExecutionState.RUNNING, null);

        // Job has advanced to step 2 (DEPOSIT_ITEMS)
        Job job = new Job(
                UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("test", "pipeline_job"),
                "Smelt Cobblestone Pipeline",
                ExecutionState.RUNNING,
                2,
                List.of(step0, step1, step2),
                1, 0,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
        List<ItemStack> reserved = queue.getReservedItemStacks();

        // While deposit step is active, the 10 stone produced by completed step 1 must be reserved
        assertEquals(1, reserved.size(), "Completed step 1's output must be protected awaiting deposit");
        assertEquals(Items.STONE, reserved.get(0).getItem());
        assertEquals(10, reserved.get(0).getCount());
    }

    // ========================================================
    // 16. Transactional Exception Safety on Placement Failure
    // ========================================================
    @Test
    @DisplayName("16. Exception during block placement triggers clean rollback: storage intact, index unchanged")
    void test16_transactionalExceptionSafetyOnPlacementFailure() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);

        WorkerStorage initialStorage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));

        // Placer throws an unexpected runtime exception
        var result = state.stepPlacement(null, initialStorage, List.of(), null,
                (pos, blockState) -> { throw new RuntimeException("Simulated unexpected placement exception"); },
                null);

        assertFalse(result.placedBlock(), "Must report failed placement");
        assertEquals(0, state.getNextIndex(), "nextIndex must NOT advance on exception");
        assertEquals(0, state.getPlacedCount(), "placedCount must NOT increment on exception");
        int cobbleCount = result.updatedStorage().items().stream()
                .filter(s -> s.is(Items.COBBLESTONE))
                .mapToInt(ItemStack::getCount)
                .sum();
        assertEquals(10, cobbleCount, "WorkerStorage materials must remain 100% intact");
    }

    // ========================================================
    // 17. Automatic Resumption from WAITING_FOR_CHUNK
    // ========================================================
    @Test
    @DisplayName("17. WAITING_FOR_CHUNK automatically resumes on next step when chunk loads without manual call")
    void test17_waitingForChunkAutomaticResumption() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);

        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));

        // Step 1: Chunk is unloaded -> state enters WAITING_FOR_CHUNK
        var r1 = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> false);
        assertTrue(r1.halted());
        assertEquals("WAITING_FOR_CHUNK", state.getHaltReason().orElse(""));

        // Step 2: Next step, chunk is now naturally loaded (e.g. player approached)
        // Does NOT call resumeWithMaterials(); stepPlacement automatically self-heals
        var r2 = state.stepPlacement(null, storage, List.of(), null, (p, b) -> true, pos -> true);
        assertFalse(state.isHalted(), "Halt state must automatically clear once chunk is loaded");
        assertTrue(r2.placedBlock(), "Block must be placed successfully");
        assertEquals(1, state.getNextIndex(), "nextIndex must advance to 1");
    }

    // ========================================================
    // 18. WorkerProfile Queue Deduplication
    // ========================================================
    @Test
    @DisplayName("18. WorkerProfile deduplicates reservations when queue is adapted from active WorkerJob")
    void test18_workerProfileQueueDeduplication() {
        ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 32);
        MinerJobData minerData = new MinerJobData(
                100, 32, 32, 0,
                List.of(pendingCobble),
                storageTarget,
                Optional.empty(),
                Direction.NORTH,
                MinerJobData.ObjectiveMode.BLOCK_COUNT
        );
        WorkerJob job = WorkerJob.assignedMiner(WorkerJobs.MINE, worksite, minerData);
        WorkerProfile profile = new WorkerProfile(workerId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(job));

        List<ItemStack> reserved = profile.getReservedItemStacks();
        assertEquals(1, reserved.size(), "Must have exactly 1 reservation entry");
        assertEquals(32, reserved.get(0).getCount(), "Must have exactly 32 cobblestone reserved");
        assertEquals(Items.COBBLESTONE, reserved.get(0).getItem());
    }
}
