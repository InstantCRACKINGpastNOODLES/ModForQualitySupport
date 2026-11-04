package com.example.examplemod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6.2 Audit Tests:
 * 1. Job Queue Reservation Semantics (P0)
 * 2. WorkerProfile Identity Deduplication (P1)
 * 3. Shelter Placement Exception Safety & Rollback (P1)
 */
public class ReservationSemanticsAndIdentityAuditTest {

    private UUID workerId;
    private UUID ownerId;
    private ResourceLocation dimension;
    private WorkerTarget worksite;
    private WorkerTarget storageTarget;

    @BeforeEach
    void setUp() {
        workerId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        dimension = ResourceLocation.withDefaultNamespace("overworld");
        worksite = new WorkerTarget(dimension, new BlockPos(100, 64, 100));
        storageTarget = new WorkerTarget(dimension, new BlockPos(100, 64, 110));
    }

    // =========================================================================
    // SECTION 1: Job Queue Reservation Semantics (P0)
    // =========================================================================
    @Nested
    @DisplayName("Section 1: Job Queue Reservation Semantics")
    class ReservationSemanticsTests {

        @Test
        @DisplayName("Scenario 1: Active step with input requirement but no output produced")
        void scenario1_activeStepInputRequirementNoOutputProduced() {
            ItemStack reqBricks = new ItemStack(Blocks.BRICKS.asItem(), 20);
            ItemStack predictedIron = new ItemStack(Blocks.IRON_BLOCK.asItem(), 5);

            JobStep activeStep = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    reqBricks, predictedIron, 1, 0, ExecutionState.RUNNING,
                    Optional.empty(), Optional.empty(), List.of()
            );

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "craft"), "Craft Job",
                    ExecutionState.RUNNING, 0, List.of(activeStep), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertEquals(1, reserved.size(), "Only input requirement must be reserved");
            assertEquals(Blocks.BRICKS.asItem(), reserved.get(0).getItem());
            assertEquals(20, reserved.get(0).getCount());
            assertFalse(reserved.stream().anyMatch(s -> s.is(Blocks.IRON_BLOCK.asItem())),
                    "Predicted output must not be ghost-reserved while step is active");
        }

        @Test
        @DisplayName("Scenario 2: Active step with predicted output only")
        void scenario2_activeStepPredictedOutputOnly() {
            ItemStack predictedWheat = new ItemStack(Items.WHEAT, 10);

            JobStep activeStep = new JobStep(
                    UUID.randomUUID(), "HARVEST_MACHINE", Optional.of(worksite.position()), Optional.empty(),
                    ItemStack.EMPTY, predictedWheat, 1, 0, ExecutionState.RUNNING,
                    Optional.empty(), Optional.empty(), List.of()
            );

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "harvest"), "Harvest Job",
                    ExecutionState.RUNNING, 0, List.of(activeStep), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertTrue(reserved.isEmpty(), "Active step with only predicted output must reserve 0 items");
        }

        @Test
        @DisplayName("Scenario 3: Active step with actual in-flight output (Miner / Farmer in-flight drop)")
        void scenario3_activeStepWithActualInFlightOutput() {
            ItemStack inFlightCobble = new ItemStack(Items.COBBLESTONE, 1);
            MinerJobData.InFlightMutation mutation = new MinerJobData.InFlightMutation(
                    worksite.position(), ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone"),
                    List.of(inFlightCobble)
            );
            MinerJobData minerData = new MinerJobData(
                    10, 0, 0, 0, List.of(), storageTarget, Optional.of(mutation),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );

            List<ItemStack> reserved = minerData.getReservedItemStacks();
            assertEquals(1, reserved.size());
            assertEquals(Items.COBBLESTONE, reserved.get(0).getItem());
            assertEquals(1, reserved.get(0).getCount());
        }

        @Test
        @DisplayName("Scenario 4: Completed step with output awaiting delivery")
        void scenario4_completedStepWithOutputAwaitingDelivery() {
            JobStep step0 = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.STONE, 10),
                    1, 1, ExecutionState.COMPLETED, Optional.empty(), Optional.empty(), List.of()
            );
            JobStep step1 = JobStep.depositItems(storageTarget.position())
                    .withState(ExecutionState.RUNNING, null);

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "smelt"), "Smelt Job",
                    ExecutionState.RUNNING, 1, List.of(step0, step1), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertEquals(1, reserved.size(), "Completed step output must be reserved awaiting deposit");
            assertEquals(Items.STONE, reserved.get(0).getItem());
            assertEquals(10, reserved.get(0).getCount());
        }

        @Test
        @DisplayName("Scenario 5: Completed step whose output has already been delivered")
        void scenario5_completedStepWhoseOutputHasAlreadyBeenDelivered() {
            JobStep step0 = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.STONE, 10),
                    1, 1, ExecutionState.COMPLETED, Optional.empty(), Optional.empty(), List.of()
            );
            JobStep step1 = JobStep.depositItems(storageTarget.position())
                    .withState(ExecutionState.COMPLETED, null);
            JobStep step2 = JobStep.moveTo(worksite.position())
                    .withState(ExecutionState.RUNNING, null);

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "smelt_and_move"), "Smelt & Move",
                    ExecutionState.RUNNING, 2, List.of(step0, step1, step2), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertTrue(reserved.isEmpty(),
                    "Once a DEPOSIT_ITEMS step completes, prior outputs are delivered and must not be reserved");
        }

        @Test
        @DisplayName("Scenario 6: Failed step reserves nothing")
        void scenario6_failedStepReservesNothing() {
            JobStep failedStep = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.STONE, 10),
                    1, 0, ExecutionState.FAILED, Optional.empty(), Optional.of("Machine broken"), List.of()
            );

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "smelt"), "Smelt Job",
                    ExecutionState.FAILED, 0, List.of(failedStep), 1, 0,
                    Optional.empty(), Optional.of("Machine broken"), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.FAILED);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertTrue(reserved.isEmpty(), "Failed job or step must reserve 0 items");
        }

        @Test
        @DisplayName("Scenario 7: Pending future step reserves nothing")
        void scenario7_pendingFutureStepReservesNothing() {
            JobStep activeStep = JobStep.moveTo(worksite.position())
                    .withState(ExecutionState.RUNNING, null);
            JobStep futureStep = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Items.COBBLESTONE, 20), new ItemStack(Items.STONE, 20),
                    1, 0, ExecutionState.PENDING, Optional.empty(), Optional.empty(), List.of()
            );

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "future"), "Future Job",
                    ExecutionState.RUNNING, 0, List.of(activeStep, futureStep), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertTrue(reserved.isEmpty(), "Pending future steps must not reserve inputs or outputs");
        }

        @Test
        @DisplayName("Scenario 8: Multiple sequential steps requiring the same item do not double-count")
        void scenario8_multipleSequentialStepsNoDoubleCount() {
            JobStep step0 = JobStep.extractItems(worksite.position(), new ItemStack(Items.COBBLESTONE, 16), 1)
                    .withState(ExecutionState.RUNNING, null);
            JobStep step1 = new JobStep(
                    UUID.randomUUID(), "INTERACT_MACHINE", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.STONE, 16),
                    1, 0, ExecutionState.PENDING, Optional.empty(), Optional.empty(), List.of()
            );
            JobStep step2 = JobStep.depositItems(storageTarget.position());

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "pipeline"), "Pipeline Job",
                    ExecutionState.RUNNING, 0, List.of(step0, step1, step2), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertEquals(1, reserved.size());
            assertEquals(16, reserved.get(0).getCount(), "Must reserve exactly 16 cobblestone, not 32");
        }

        @Test
        @DisplayName("Scenario 9: Save/load during an active step preserves reservation semantics identically")
        void scenario9_saveLoadActiveStepPreservesSemantics() {
            JobStep activeStep = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Blocks.BRICKS.asItem(), 20), new ItemStack(Blocks.IRON_BLOCK.asItem(), 5),
                    1, 0, ExecutionState.RUNNING, Optional.empty(), Optional.empty(), List.of()
            );

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "craft"), "Craft Job",
                    ExecutionState.RUNNING, 0, List.of(activeStep), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.RUNNING);
            List<ItemStack> beforeSave = queue.getReservedItemStacks();

            // Serialize & deserialize via Codec
            var tag = JobQueue.CODEC.encodeStart(NbtOps.INSTANCE, queue).getOrThrow();
            JobQueue restored = JobQueue.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

            List<ItemStack> afterLoad = restored.getReservedItemStacks();
            assertEquals(beforeSave.size(), afterLoad.size());
            assertEquals(beforeSave.get(0).getItem(), afterLoad.get(0).getItem());
            assertEquals(beforeSave.get(0).getCount(), afterLoad.get(0).getCount());
        }

        @Test
        @DisplayName("Scenario 10: Failed machine interaction followed by retry maintains input reservation")
        void scenario10_failedMachineInteractionRetryMaintainsInput() {
            JobStep stepWaiting = new JobStep(
                    UUID.randomUUID(), "MACHINE_CRAFT", Optional.of(worksite.position()), Optional.empty(),
                    new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.STONE, 10),
                    1, 0, ExecutionState.WAITING, Optional.of("Machine full, retrying"), Optional.empty(), List.of()
            );

            Job job = new Job(
                    UUID.randomUUID(), ResourceLocation.fromNamespaceAndPath("test", "retry"), "Retry Job",
                    ExecutionState.WAITING, 0, List.of(stepWaiting), 1, 0,
                    Optional.of("Waiting for machine"), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(job), 0, ExecutionState.WAITING);
            List<ItemStack> reserved = queue.getReservedItemStacks();

            assertEquals(1, reserved.size());
            assertEquals(Items.COBBLESTONE, reserved.get(0).getItem());
            assertEquals(10, reserved.get(0).getCount(), "Input requirement must remain reserved during retry/waiting");
            assertFalse(reserved.stream().anyMatch(s -> s.is(Items.STONE)), "Predicted output is not reserved");
        }
    }

    // =========================================================================
    // SECTION 2: WorkerProfile Queue Deduplication (P1)
    // =========================================================================
    @Nested
    @DisplayName("Section 2: WorkerProfile Queue Deduplication")
    class WorkerProfileIdentityTests {

        @Test
        @DisplayName("1. Same logical job mirrored in WorkerProfile and JobQueue is counted exactly once")
        void test1_sameLogicalJobMirroredCountedOnce() {
            UUID stableJobId = UUID.randomUUID();
            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 32);
            MinerJobData minerData = new MinerJobData(
                    100, 32, 32, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );

            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 50, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty(), Optional.of(stableJobId)
            );

            // Adapted queue created with matching stableJobId
            Job queueJob = Job.createMineJob(stableJobId, worksite, storageTarget, Direction.NORTH, 100);
            JobQueue queue = new JobQueue(List.of(queueJob), 0, ExecutionState.RUNNING);

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            assertEquals(1, reserved.size(), "Same logical job must be counted exactly once");
            assertEquals(32, reserved.get(0).getCount());
        }

        @Test
        @DisplayName("2. Two distinct jobs with the same type reserve both correctly")
        void test2_twoDistinctJobsSameTypeBothReserved() {
            UUID job1Id = UUID.randomUUID();
            UUID job2Id = UUID.randomUUID();

            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 16);
            MinerJobData minerData = new MinerJobData(
                    50, 16, 16, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );

            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 50, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty(), Optional.of(job1Id)
            );

            // Queue has a distinct job of the SAME type (MINE) with job2Id and its own requirement
            JobStep extractStep = JobStep.extractItems(worksite.position(), new ItemStack(Items.COBBLESTONE, 24), 1)
                    .withState(ExecutionState.RUNNING, null);
            Job queueJob = new Job(
                    job2Id, WorkerJobs.MINE, "Second Mine Job", ExecutionState.RUNNING,
                    0, List.of(extractStep), 1, 0, Optional.empty(), Optional.empty(), List.of()
            );
            JobQueue queue = new JobQueue(List.of(queueJob), 0, ExecutionState.RUNNING);

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            assertEquals(2, reserved.size(), "Both distinct jobs must have their reservations preserved");
            int totalCobble = reserved.stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
            assertEquals(40, totalCobble, "16 from job 1 + 24 from job 2 = 40 cobblestone total");
        }

        @Test
        @DisplayName("3. Same type but different job IDs: no accidental deduplication")
        void test3_sameTypeDifferentJobIdsNoAccidentalDeduplication() {
            UUID idA = UUID.randomUUID();
            UUID idB = UUID.randomUUID();

            ItemStack pumpkins = new ItemStack(Blocks.PUMPKIN.asItem(), 10);
            FarmerJobData farmerData = new FarmerJobData(
                    50, 10, 10, 0, List.of(pumpkins), storageTarget, Optional.empty(),
                    List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "pumpkin"))
            );
            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.FARM, JobStatus.RUNNING, 20, Optional.empty(),
                    Optional.of(worksite), Optional.empty(), Optional.of(farmerData), Optional.of(idA)
            );

            JobStep farmStep = JobStep.extractItems(worksite.position(), new ItemStack(Blocks.PUMPKIN.asItem(), 15), 1)
                    .withState(ExecutionState.RUNNING, null);
            Job queueJob = new Job(
                    idB, WorkerJobs.FARM, "Second Farm Job", ExecutionState.RUNNING,
                    0, List.of(farmStep), 1, 0, Optional.empty(), Optional.empty(), List.of()
            );
            JobQueue queue = new JobQueue(List.of(queueJob), 0, ExecutionState.RUNNING);

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            assertEquals(2, reserved.size(), "Distinct jobs must NOT be accidentally deduplicated");
            int totalPumpkins = reserved.stream().filter(s -> s.is(Blocks.PUMPKIN.asItem())).mapToInt(ItemStack::getCount).sum();
            assertEquals(25, totalPumpkins);
        }

        @Test
        @DisplayName("4. Different types: preserve both reservations")
        void test4_differentTypesPreserveBoth() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 10);
            MinerJobData minerData = new MinerJobData(
                    50, 10, 10, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );
            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 20, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty(), Optional.of(id1)
            );

            JobStep craftStep = JobStep.extractItems(worksite.position(), new ItemStack(Items.IRON_INGOT, 5), 1)
                    .withState(ExecutionState.RUNNING, null);
            Job queueJob = new Job(
                    id2, ResourceLocation.fromNamespaceAndPath("test", "craft"), "Craft Job",
                    ExecutionState.RUNNING, 0, List.of(craftStep), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );
            JobQueue queue = new JobQueue(List.of(queueJob), 0, ExecutionState.RUNNING);

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            assertEquals(2, reserved.size());
            assertTrue(reserved.stream().anyMatch(s -> s.is(Items.COBBLESTONE)));
            assertTrue(reserved.stream().anyMatch(s -> s.is(Items.IRON_INGOT)));
        }

        @Test
        @DisplayName("5. Empty queue: WorkerProfile job reservations preserved normally")
        void test5_emptyQueueWorkerProfilePreserved() {
            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 10);
            MinerJobData minerData = new MinerJobData(
                    50, 10, 10, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );
            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 20, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty()
            );

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), JobQueue.EMPTY
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            assertEquals(1, reserved.size());
            assertEquals(10, reserved.get(0).getCount());
        }

        @Test
        @DisplayName("6. Multiple queued jobs: active job deduplicated, future distinct job handled correctly")
        void test6_multipleQueuedJobs() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 10);
            MinerJobData minerData = new MinerJobData(
                    50, 10, 10, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );
            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 20, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty(), Optional.of(id1)
            );

            // Job 1 mirrors workerJob (ID id1)
            Job queueJob1 = Job.createMineJob(id1, worksite, storageTarget, Direction.NORTH, 50);
            // Job 2 is a distinct pending job (ID id2)
            JobStep step = JobStep.extractItems(worksite.position(), new ItemStack(Items.OAK_LOG, 8), 1);
            Job queueJob2 = new Job(
                    id2, ResourceLocation.fromNamespaceAndPath("test", "lumber"), "Lumber Job",
                    ExecutionState.PENDING, 0, List.of(step), 1, 0,
                    Optional.empty(), Optional.empty(), List.of()
            );

            JobQueue queue = new JobQueue(List.of(queueJob1, queueJob2), 0, ExecutionState.RUNNING);
            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            // Active job in queue is id1 (same as workerJob) -> deduplicated to 1 entry
            assertEquals(1, reserved.size());
            assertEquals(Items.COBBLESTONE, reserved.get(0).getItem());
        }

        @Test
        @DisplayName("7. Save/load identity preservation across serialization")
        void test7_saveLoadIdentityPreservation() {
            UUID stableId = UUID.randomUUID();
            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 12);
            MinerJobData minerData = new MinerJobData(
                    50, 12, 12, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );
            WorkerJob workerJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 20, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty(), Optional.of(stableId)
            );

            Job queueJob = Job.createMineJob(stableId, worksite, storageTarget, Direction.NORTH, 50);
            JobQueue queue = new JobQueue(List.of(queueJob), 0, ExecutionState.RUNNING);

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(workerJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            // Encode to NBT and decode back
            var tag = WorkerProfile.CODEC.encodeStart(NbtOps.INSTANCE, profile).getOrThrow();
            WorkerProfile restored = WorkerProfile.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

            assertTrue(restored.job().isPresent());
            assertTrue(restored.job().get().jobId().isPresent());
            assertEquals(stableId, restored.job().get().jobId().get());
            assertEquals(stableId, restored.queue().currentJob().get().jobId());

            List<ItemStack> reserved = restored.getReservedItemStacks();
            assertEquals(1, reserved.size(), "Deduplication must remain active after save/load");
            assertEquals(12, reserved.get(0).getCount());
        }

        @Test
        @DisplayName("8. Legacy data without a job ID does not crash or accidentally deduplicate distinct jobs")
        void test8_legacyDataWithoutJobId() {
            // WorkerJob with Optional.empty() jobId (legacy save)
            ItemStack pendingCobble = new ItemStack(Items.COBBLESTONE, 10);
            MinerJobData minerData = new MinerJobData(
                    50, 10, 10, 0, List.of(pendingCobble), storageTarget, Optional.empty(),
                    Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT
            );
            WorkerJob legacyJob = new WorkerJob(
                    WorkerJobs.MINE, JobStatus.RUNNING, 20, Optional.empty(),
                    Optional.of(worksite), Optional.of(minerData), Optional.empty(), Optional.empty()
            );
            assertTrue(legacyJob.jobId().isEmpty(), "Legacy job has no job_id");

            // Queue with a distinct job of same type
            JobStep step = JobStep.extractItems(worksite.position(), new ItemStack(Items.COBBLESTONE, 20), 1)
                    .withState(ExecutionState.RUNNING, null);
            Job queueJob = new Job(
                    UUID.randomUUID(), WorkerJobs.MINE, "Queue Mine Job", ExecutionState.RUNNING,
                    0, List.of(step), 1, 0, Optional.empty(), Optional.empty(), List.of()
            );
            JobQueue queue = new JobQueue(List.of(queueJob), 0, ExecutionState.RUNNING);

            WorkerProfile profile = new WorkerProfile(
                    workerId, ownerId, dimension, WorkerStatus.IDLE,
                    Optional.of(legacyJob), WorkerBehavior.FOLLOW, Optional.empty(), queue
            );

            List<ItemStack> reserved = profile.getReservedItemStacks();
            // Because legacyJob has no jobId, it does not falsely match queueJob -> both are preserved safely!
            assertEquals(2, reserved.size());
            int total = reserved.stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
            assertEquals(30, total);
        }
    }

    // =========================================================================
    // SECTION 3: Shelter Placement Exception Safety & Rollback (P1)
    // =========================================================================
    @Nested
    @DisplayName("Section 3: Shelter Placement Exception Safety & Rollback")
    class ShelterPlacementExceptionSafetyTests {

        private EquipmentCapabilityPolicy.EmergencyShelterPlan plan;
        private EquipmentCapabilityPolicy.ShelterConstructionState state;
        private WorkerStorage initialStorage;

        @BeforeEach
        void init() {
            plan = EquipmentCapabilityPolicy.generateShelterPlan(
                    worksite.position(), EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
            );
            state = new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, UUID.randomUUID(), plan, Direction.NORTH);
            initialStorage = WorkerStorage.empty().deposit(new ItemStack(Items.COBBLESTONE, 10));
        }

        @Test
        @DisplayName("1. Successful placement advances nextIndex and consumes exactly 1 block")
        void test1_successfulPlacement() {
            var result = state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> true, null);

            assertTrue(result.placedBlock());
            assertEquals(1, state.getNextIndex());
            assertEquals(1, state.getPlacedCount());
            assertEquals(9, result.updatedStorage().items().stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum());
        }

        @Test
        @DisplayName("2. Placement returns false: material not consumed, index unchanged")
        void test2_placementReturnsFalse() {
            var result = state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> false, null);

            assertFalse(result.placedBlock());
            assertEquals(0, state.getNextIndex());
            assertEquals(0, state.getPlacedCount());
            assertEquals(10, result.updatedStorage().items().stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum());
        }

        @Test
        @DisplayName("3. Expected recoverable exception (IllegalStateException) enters clean rollback")
        void test3_expectedRecoverableExceptionHandled() {
            var result = state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> {
                throw new IllegalStateException("Simulated recoverable block state conflict");
            }, null);

            assertFalse(result.placedBlock());
            assertEquals(0, state.getNextIndex());
            assertEquals(0, state.getPlacedCount());
            assertEquals(10, result.updatedStorage().items().stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum());
        }

        @Test
        @DisplayName("4. Unexpected runtime exception enters clean rollback without crashing execution")
        void test4_unexpectedRuntimeExceptionHandled() {
            var result = state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> {
                throw new NullPointerException("Simulated unexpected null pointer during block placement");
            }, null);

            assertFalse(result.placedBlock());
            assertEquals(0, state.getNextIndex());
            assertEquals(0, state.getPlacedCount());
            assertEquals(10, result.updatedStorage().items().stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum());
        }

        @Test
        @DisplayName("5. Fatal JVM error (VirtualMachineError / OutOfMemoryError) is NOT swallowed by broad catch")
        void test5_fatalJvmErrorNotSwallowed() {
            assertThrows(OutOfMemoryError.class, () -> {
                state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> {
                    throw new OutOfMemoryError("Simulated fatal JVM out of memory error");
                }, null);
            }, "Fatal Error must propagate and never be swallowed by shelter placement catch blocks");
        }

        @Test
        @DisplayName("6. Storage remains unchanged on failure")
        void test6_storageRemainsUnchangedOnFailure() {
            var result = state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> false, null);
            assertEquals(initialStorage, result.updatedStorage());
        }

        @Test
        @DisplayName("7. nextIndex remains unchanged on failure")
        void test7_nextIndexRemainsUnchangedOnFailure() {
            state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> false, null);
            assertEquals(0, state.getNextIndex());
        }

        @Test
        @DisplayName("8. placedPositions remains unchanged on failure")
        void test8_placedPositionsRemainsUnchangedOnFailure() {
            state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> false, null);
            assertTrue(state.getPlacedPositions().isEmpty());
        }

        @Test
        @DisplayName("9. Retry after failure does not duplicate placement or consume extra material")
        void test9_retryAfterFailureSucceedsCleanly() {
            // First attempt fails
            var r1 = state.stepPlacement(null, initialStorage, List.of(), null, (pos, bs) -> false, null);
            assertFalse(r1.placedBlock());
            assertEquals(0, state.getNextIndex());

            // Second attempt succeeds
            var r2 = state.stepPlacement(null, r1.updatedStorage(), List.of(), null, (pos, bs) -> true, null);
            assertTrue(r2.placedBlock());
            assertEquals(1, state.getNextIndex());
            assertEquals(1, state.getPlacedCount());
            int remaining = r2.updatedStorage().items().stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
            assertEquals(9, remaining, "Exactly 1 block consumed across the failed attempt and successful retry");
        }
    }
}
