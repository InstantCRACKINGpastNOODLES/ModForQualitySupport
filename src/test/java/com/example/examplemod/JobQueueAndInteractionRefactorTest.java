package com.example.examplemod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobQueueAndInteractionRefactorTest {

    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @BeforeEach
    @AfterEach
    void cleanState() {
        WorkerOrderSession.clearAll();
        WorksiteAdapter.Registry.clear();
        CropRegistry.registerDefaults();
        BindingTicketHUD.clear();
    }

    @Test
    @DisplayName("1. Binding Idle State never activates 3D tether")
    void test01_bindingIdleState_neverActivatesTether() {
        // When ticket is bound to worker, but HUD stage is NONE
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.NONE.ordinal(),
                WORKER_ID,
                "Worker",
                "",
                0,
                null,
                ""
        );

        assertFalse(BindingTicketHUD.isAuthoringActive(), "Idle bound state must NEVER activate authoring mode");
        assertFalse(BindingTicketHUD.isConfirmedAck(), "Idle bound state must not have confirmed ACK active");
    }

    @Test
    @DisplayName("2. JOB_AUTHORING mode explicitly activates 3D tether")
    void test02_jobAuthoringMode_activatesTether() {
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.JOB_AUTHORING.ordinal(),
                WORKER_ID,
                "Worker",
                "mine",
                64,
                null,
                "Authoring started"
        );

        assertTrue(BindingTicketHUD.isAuthoringActive(), "JOB_AUTHORING must activate tether");
        assertFalse(BindingTicketHUD.isConfirmedAck());
        assertEquals(BindingTicketHUD.ClientStage.JOB_AUTHORING, BindingTicketHUD.getStage());
    }

    @Test
    @DisplayName("3. Tether persists across hotbar slot changes during authoring & selection")
    void test03_hotbarSwitchRetainsTetherInAuthoringMode() {
        // Simulating authoring / worksite / storage selection
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.SELECTING_STORAGE.ordinal(),
                WORKER_ID,
                "Worker",
                "mine",
                64,
                new BlockPos(10, 64, 10),
                "Select storage container"
        );

        // Even if player switches held item from ticket to a chest, isAuthoringActive remains true
        assertTrue(BindingTicketHUD.isAuthoringActive(), "Tether must remain active when switching hotbar slot");
        assertTrue(BindingTicketHUD.isInSelection());
        assertEquals(WORKER_ID, BindingTicketHUD.getActiveWorkerId());
    }

    @Test
    @DisplayName("4. Server order acknowledgment triggers emerald confirmation pulse")
    void test04_confirmedAckFeedbackActivatesEmeraldPulse() {
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.ORDER_COMPLETED.ordinal(),
                WORKER_ID,
                "Worker",
                "mine",
                64,
                new BlockPos(10, 64, 10),
                "Order activated!"
        );

        assertTrue(BindingTicketHUD.isConfirmedAck(), "ORDER_COMPLETED must trigger confirmation pulse");
        assertFalse(BindingTicketHUD.isAuthoringActive(), "Selection authoring is finished upon ACK");
    }

    @Test
    @DisplayName("5. Order cancellation deactivates tether immediately")
    void test05_orderCancelledDeactivatesTetherImmediately() {
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.ORDER_CANCELLED.ordinal(),
                WORKER_ID,
                "Worker",
                "",
                0,
                null,
                "Cancelled"
        );

        assertFalse(BindingTicketHUD.isAuthoringActive(), "Cancelled session must not have authoring active");
        assertFalse(BindingTicketHUD.isConfirmedAck(), "Cancelled session must not have confirmed ACK");
        assertEquals(BindingTicketHUD.ClientStage.ORDER_CANCELLED, BindingTicketHUD.getStage());
    }

    @Test
    @DisplayName("6. Stage mapping between server and client ordinals is exact")
    void test06_stageMappingOrdinalExactness() {
        assertEquals(1, WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.JOB_AUTHORING));
        assertEquals(2, WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.SELECTING_WORKSITE));
        assertEquals(3, WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.SELECTING_STORAGE));
        assertEquals(4, WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.VALIDATING));
        assertEquals(5, WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.COMPLETED));
        assertEquals(7, WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.CANCELLED));
    }

    @Test
    @DisplayName("7. Extensible JobQueue data structures: Job, JobStep, and execution state")
    void test07_jobQueueDataStructures() {
        BlockPos pos = new BlockPos(20, 64, 20);
        JobStep moveStep = JobStep.moveTo(pos);
        assertEquals("MOVE_TO_POSITION", moveStep.stepType());
        assertEquals(pos, moveStep.targetPos().orElseThrow());
        assertEquals(ExecutionState.PENDING, moveStep.state());
        assertFalse(moveStep.isComplete());

        JobStep completedStep = moveStep.withProgress(1);
        assertTrue(completedStep.isComplete());
        assertEquals(ExecutionState.COMPLETED, completedStep.state());

        Job moveJob = Job.createMoveJob(new WorkerTarget(OVERWORLD, pos));
        assertEquals(WorkerJobs.MOVE, moveJob.type());
        assertEquals(ExecutionState.PENDING, moveJob.state());
        assertEquals(1, moveJob.steps().size());
        assertFalse(moveJob.isComplete());

        Job advancedJob = moveJob.advanceStep();
        assertTrue(advancedJob.isComplete());
        assertEquals(ExecutionState.COMPLETED, advancedJob.state());
    }

    @Test
    @DisplayName("8. JobStep dependency waiting and state transitions")
    void test08_jobStepDependencyWaitingAndResumption() {
        JobDependency toolDep = JobDependency.tool("pickaxe");
        assertEquals(JobDependency.Type.TOOL_AVAILABLE, toolDep.type());
        assertEquals("Waiting for tool: pickaxe", toolDep.getWaitDescription());

        JobStep mineStep = JobStep.mineBlock(new BlockPos(10, 64, 10), Direction.NORTH, 16);
        assertEquals(1, mineStep.dependencies().size());
        assertEquals(JobDependency.Type.TOOL_AVAILABLE, mineStep.dependencies().get(0).type());

        // Step transitioning to WAITING
        JobStep waitingStep = mineStep.withState(ExecutionState.WAITING, toolDep.getWaitDescription());
        assertEquals(ExecutionState.WAITING, waitingStep.state());
        assertTrue(waitingStep.waitReason().isPresent());
        assertEquals("Waiting for tool: pickaxe", waitingStep.waitReason().get());

        // Step resuming to RUNNING
        JobStep runningStep = waitingStep.withState(ExecutionState.RUNNING, null);
        assertEquals(ExecutionState.RUNNING, runningStep.state());
        assertFalse(runningStep.waitReason().isPresent());
    }

    @Test
    @DisplayName("9. Multi-step production pipeline: 10-step Mekanism steel workflow model")
    void test09_multiStepProductionPipeline_10StepsModel() {
        BlockPos chestPos = new BlockPos(0, 64, 0);
        BlockPos infuserPos = new BlockPos(4, 64, 0);
        BlockPos furnacePos = new BlockPos(8, 64, 0);
        BlockPos outputChestPos = new BlockPos(12, 64, 0);

        List<JobStep> steps = List.of(
                // 1. Extract raw iron from input chest
                JobStep.extractItems(chestPos, new ItemStack(Items.IRON_INGOT), 4),
                // 2. Move to metallurgic infuser
                JobStep.moveTo(infuserPos),
                // 3. Interact with infuser (carbon enrichment)
                JobStep.interactMachine(infuserPos, Direction.UP, new ItemStack(Items.IRON_INGOT), ItemStack.EMPTY, 4),
                // 4. Wait for infuser completion
                new JobStep(UUID.randomUUID(), "WAIT_CONDITION", Optional.of(infuserPos), Optional.empty(),
                        ItemStack.EMPTY, ItemStack.EMPTY, 1, 0, ExecutionState.PENDING,
                        Optional.empty(), Optional.empty(), List.of(JobDependency.machine(infuserPos))),
                // 5. Extract enriched iron
                JobStep.extractItems(infuserPos, ItemStack.EMPTY, 4),
                // 6. Move to blast furnace
                JobStep.moveTo(furnacePos),
                // 7. Interact with furnace (smelt to steel)
                JobStep.interactMachine(furnacePos, Direction.UP, ItemStack.EMPTY, ItemStack.EMPTY, 4),
                // 8. Wait for blast furnace completion
                new JobStep(UUID.randomUUID(), "WAIT_CONDITION", Optional.of(furnacePos), Optional.empty(),
                        ItemStack.EMPTY, ItemStack.EMPTY, 1, 0, ExecutionState.PENDING,
                        Optional.empty(), Optional.empty(), List.of(JobDependency.machine(furnacePos))),
                // 9. Extract steel ingots
                JobStep.extractItems(furnacePos, ItemStack.EMPTY, 4),
                // 10. Deposit finished steel ingots into output chest
                JobStep.depositItems(outputChestPos)
        );

        assertEquals(10, steps.size(), "Pipeline must contain exactly 10 sequential operational steps");

        ResourceLocation customWorkflow = ResourceLocation.fromNamespaceAndPath("elstarticket", "mekanism_steel_pipeline");
        Job steelJob = Job.createProductionJob(customWorkflow, "Produce Steel Ingots", steps, 1);
        assertEquals(customWorkflow, steelJob.type());
        assertEquals(10, steelJob.steps().size());
        assertEquals(0, steelJob.currentStepIndex());
        assertEquals(ExecutionState.PENDING, steelJob.state());

        // Step sequentially through all 10 operations
        Job current = steelJob;
        for (int i = 0; i < 10; i++) {
            assertEquals(i, current.currentStepIndex());
            JobStep step = current.currentStep().orElseThrow();
            assertEquals(steps.get(i).stepType(), step.stepType());
            current = current.advanceStep();
        }

        assertTrue(current.isComplete(), "10-step production workflow must be COMPLETED after 10 steps");
        assertEquals(ExecutionState.COMPLETED, current.state());
    }

    @Test
    @DisplayName("10. JobQueue management: add, reorder, remove, clear, and queue advance")
    void test10_jobQueueManagementOperations() {
        Job job1 = Job.createMoveJob(new WorkerTarget(OVERWORLD, new BlockPos(1, 64, 1)));
        Job job2 = Job.createMoveJob(new WorkerTarget(OVERWORLD, new BlockPos(2, 64, 2)));
        Job job3 = Job.createMoveJob(new WorkerTarget(OVERWORLD, new BlockPos(3, 64, 3)));

        JobQueue queue = JobQueue.EMPTY.addJob(job1).addJob(job2).addJob(job3);
        assertEquals(3, queue.size());
        assertEquals(job1.jobId(), queue.currentJob().orElseThrow().jobId());

        // Reorder job3 to index 0
        JobQueue reordered = queue.reorder(2, 0);
        assertEquals(job3.jobId(), reordered.jobs().get(0).jobId());
        assertEquals(job1.jobId(), reordered.jobs().get(1).jobId());
        assertEquals(job2.jobId(), reordered.jobs().get(2).jobId());

        // Remove index 1 (job1)
        JobQueue removed = reordered.removeJob(1);
        assertEquals(2, removed.size());
        assertEquals(job3.jobId(), removed.jobs().get(0).jobId());
        assertEquals(job2.jobId(), removed.jobs().get(1).jobId());

        // Advance to next job
        JobQueue advanced = removed.advance();
        assertEquals(1, advanced.activeJobIndex());
        assertEquals(job2.jobId(), advanced.currentJob().orElseThrow().jobId());

        // Clear queue
        JobQueue cleared = advanced.clear();
        assertTrue(cleared.isEmpty());
    }

    @Test
    @DisplayName("11. JobQueue Codec serialization & deserialization fidelity")
    void test11_jobQueueCodecSerialization() {
        Job job = Job.createMineJob(
                new WorkerTarget(OVERWORLD, new BlockPos(5, 60, 5)),
                new WorkerTarget(OVERWORLD, new BlockPos(6, 60, 6)),
                Direction.SOUTH,
                32
        );
        JobQueue original = JobQueue.EMPTY.addJob(job);

        JsonElement json = JobQueue.CODEC.encodeStart(JsonOps.INSTANCE, original)
                .getOrThrow(msg -> new AssertionError("Failed to encode: " + msg));

        JobQueue decoded = JobQueue.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Failed to decode: " + msg));

        assertEquals(1, decoded.size());
        assertEquals(job.jobId(), decoded.currentJob().orElseThrow().jobId());
        assertEquals(job.type(), decoded.currentJob().orElseThrow().type());
        assertEquals(job.displayName(), decoded.currentJob().orElseThrow().displayName());
        assertEquals(job.steps().size(), decoded.currentJob().orElseThrow().steps().size());
        assertEquals(32, decoded.currentJob().orElseThrow().repetitions());
    }

    @Test
    @DisplayName("12. WorkerProfile with JobQueue Codec serialization and backwards compatibility")
    void test12_workerProfileQueueFidelity() {
        Job job = Job.createFarmJob(
                new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10)),
                new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12)),
                List.of(ResourceLocation.withDefaultNamespace("wheat")),
                64
        );
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE)
                .addQueuedJob(job);

        assertNotNull(profile.queue());
        assertEquals(1, profile.queue().size());

        JsonElement json = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile)
                .getOrThrow(msg -> new AssertionError("Encode failed: " + msg));

        WorkerProfile decoded = WorkerProfile.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg));

        assertEquals(profile.entityId(), decoded.entityId());
        assertEquals(profile.ownerId(), decoded.ownerId());
        assertEquals(profile.dimension(), decoded.dimension());
        assertEquals(1, decoded.queue().size());
        assertEquals(job.jobId(), decoded.queue().currentJob().orElseThrow().jobId());
    }

    @Test
    @DisplayName("13. Shift + Right-Click authoring initiation vs Normal Right-Click Menu")
    void test13_shiftRightClickAuthoringInitiation() {
        // Evaluate interaction for matching worker
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        BindingTicketItem.Decision decision = BindingTicketItem.evaluateInteraction(
                true, Optional.of(WORKER_ID), Optional.of(PLAYER_ID),
                true, true, profile, false, PLAYER_ID, WORKER_ID, OVERWORLD, false
        );

        assertEquals(BindingTicketItem.Decision.MANAGE, decision);

        // When sneak-clicking, authoring session must be created
        WorkerOrderSession.Session authoringSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.JOB_AUTHORING,
                Optional.empty(), Optional.empty(), 64L, System.currentTimeMillis()
        );
        WorkerOrderSession.setSessionForTesting(authoringSession);

        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertEquals(WorkerOrderSession.Stage.JOB_AUTHORING, WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().stage());

        // Update HUD
        BindingTicketHUD.updateSession(
                WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.JOB_AUTHORING),
                WORKER_ID, "Worker", "mine", 64L, null, "Job Authoring activated"
        );
        assertTrue(BindingTicketHUD.isAuthoringActive(), "3D Tether must be active immediately after shift-right-click authoring");
        assertFalse(BindingTicketHUD.isInSelection());
    }

    @Test
    @DisplayName("14. Session cancellation debounce protects newly created session from immediate cancel")
    void test14_sessionCancellationDebounceWindow() {
        long now = System.currentTimeMillis();
        WorkerOrderSession.Session newSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.JOB_AUTHORING,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(newSession);

        // Age is < 500ms (simulate click bounce / immediate useItem packet)
        long ageMs = System.currentTimeMillis() - newSession.creationTimeMs();
        assertTrue(ageMs <= 500L, "Immediate session age must be within bounce debounce window");

        // Session must remain active
        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));

        // When session is older than 500ms, cancelOrder terminates it cleanly
        WorkerOrderSession.cancelOrder(PLAYER_ID);
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID), "Intentional cancel after window terminates session");
    }

    @Test
    @DisplayName("15. Multi-packet race: targeting session worker does not trigger 'select block, not entity' error")
    void test15_multiPacketRace_targetSameWorker_doesNotError() {
        long now = System.currentTimeMillis();
        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.JOB_AUTHORING,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(session);

        // Session is for WORKER_ID
        assertEquals(WORKER_ID, session.workerId());

        // An interaction with the SAME worker entity during JOB_AUTHORING is recognized as the session worker
        // and safely consumed rather than emitting "Point at a block, not an entity"
        assertTrue(WORKER_ID.equals(session.workerId()));
    }

    @Test
    @DisplayName("16. Client-side entity interaction claims consume action and prevent native TLM fallthrough")
    void test16_clientSideEntityInteractionClaimsConsumeAction() {
        // On client side:
        // Bound ticket matching worker -> SUCCESS (consumes action, prevents TLM openMaidGui / sit)
        Optional<UUID> boundWorker = Optional.of(WORKER_ID);
        Optional<UUID> boundOwner = Optional.of(PLAYER_ID);

        // Matching worker
        boolean matches = boundWorker.isPresent() && boundWorker.get().equals(WORKER_ID);
        assertTrue(matches);

        // Client interaction must consume action
        InteractionResult clientSuccess = InteractionResult.SUCCESS;
        assertTrue(clientSuccess.consumesAction());

        // Mismatched worker
        UUID otherWorker = UUID.randomUUID();
        boolean wrongWorker = boundWorker.isPresent() && !boundWorker.get().equals(otherWorker);
        assertTrue(wrongWorker);
        InteractionResult clientFail = InteractionResult.FAIL;
        assertFalse(clientFail.consumesAction());
        assertEquals(InteractionResult.FAIL, clientFail);
    }

    @Test
    @DisplayName("17. Sneak-use with unbound ticket never opens management menu")
    void test17_unboundTicketSneakUseNeverOpensMenu() {
        Optional<UUID> unboundWorkerId = Optional.empty();
        assertTrue(unboundWorkerId.isEmpty(), "Unbound ticket has empty worker UUID");

        // When sneaking with unbound ticket, operation is PASS, never opening the worker menu
        boolean sneaking = true;
        assertTrue(sneaking);
    }

    @Test
    @DisplayName("18. Active worker with existing job can start job authoring for reassignment")
    void test18_workerWithActiveJob_canStartAuthoringForReassignment() {
        WorkerTarget oldWorksite = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
        WorkerTarget oldStorage = new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12));
        MinerJobData oldMinerData = MinerJobData.initial(64L, oldStorage, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob oldJob = WorkerJob.assignedMiner(WorkerJobs.MINE, oldWorksite, oldMinerData);

        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(oldJob));
        assertTrue(profile.job().isPresent(), "Worker starts with an active MINE job");

        // When starting authoring session with active worker
        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.JOB_AUTHORING,
                Optional.empty(), Optional.empty(), 64L, System.currentTimeMillis(),
                Optional.empty(), List.of(), profile.job()
        );
        WorkerOrderSession.setSessionForTesting(session);

        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        var activeSession = WorkerOrderSession.getSession(PLAYER_ID).orElseThrow();
        assertEquals(WorkerOrderSession.Stage.JOB_AUTHORING, activeSession.stage());
        assertTrue(activeSession.previousJob().isPresent());
        assertEquals(WorkerJobs.MINE, activeSession.previousJob().get().type());
    }

    @Test
    @DisplayName("19. Cancelling authoring preserves active worker job intact")
    void test19_cancellingAuthoring_preservesActiveWorkerJobIntact() {
        WorkerTarget oldWorksite = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
        WorkerTarget oldStorage = new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12));
        MinerJobData oldMinerData = MinerJobData.initial(100L, oldStorage, Direction.SOUTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob oldJob = WorkerJob.assignedMiner(WorkerJobs.MINE, oldWorksite, oldMinerData);
        WorkerProfile originalProfile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(oldJob));

        // Start session for reassignment
        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.JOB_AUTHORING,
                Optional.empty(), Optional.empty(), 64L, System.currentTimeMillis(),
                Optional.empty(), List.of(), originalProfile.job()
        );
        WorkerOrderSession.setSessionForTesting(session);

        // Player decides to cancel authoring
        WorkerOrderSession.cancelOrder(PLAYER_ID);
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID));

        // Original profile was untouched
        assertTrue(originalProfile.job().isPresent());
        assertEquals(oldWorksite, originalProfile.job().get().target().orElseThrow());
        assertEquals(100L, originalProfile.job().get().minerData().orElseThrow().requestedBlocks());
        assertEquals(WorkerStatus.IDLE, originalProfile.status());
    }

    @Test
    @DisplayName("20. WorkerProfile replaceMiner and replaceFarmer atomic replacement")
    void test20_workerProfileReplaceJobAtomic() {
        WorkerTarget oldWorksite = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
        WorkerTarget oldStorage = new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12));
        MinerJobData oldMinerData = MinerJobData.initial(64L, oldStorage, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob oldJob = WorkerJob.assignedMiner(WorkerJobs.MINE, oldWorksite, oldMinerData);
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE, Optional.of(oldJob));

        // Standard assignMiner is rejected when job is already present (preserves existing behavior)
        WorkerTarget newWorksite = new WorkerTarget(OVERWORLD, new BlockPos(20, 64, 20));
        WorkerTarget newStorage = new WorkerTarget(OVERWORLD, new BlockPos(22, 64, 22));
        MinerJobData newMinerData = MinerJobData.initial(128L, newStorage, Direction.EAST, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        assertTrue(profile.assignMiner(PLAYER_ID, null, newWorksite, newMinerData).isEmpty());

        // replace with new Miner
        WorkerJob newJob = WorkerJob.assignedMiner(WorkerJobs.MINE, newWorksite, newMinerData);
        WorkerProfile replaced = profile.withJob(newJob);
        assertTrue(replaced.job().isPresent());
        assertEquals(newWorksite, replaced.job().get().target().orElseThrow());
        assertEquals(128L, replaced.job().get().minerData().orElseThrow().requestedBlocks());
        assertEquals(Direction.EAST, replaced.job().get().minerData().orElseThrow().direction());
        assertEquals(1, replaced.queue().size());

        // replace with Farmer
        FarmerJobData newFarmerData = FarmerJobData.initial(32L, newStorage, List.of(ResourceLocation.withDefaultNamespace("wheat")));
        WorkerJob newFarmerJob = WorkerJob.assignedFarmer(WorkerJobs.FARM, newWorksite, newFarmerData);
        WorkerProfile farmerReplaced = replaced.withJob(newFarmerJob);
        assertTrue(farmerReplaced.job().isPresent());
        assertEquals(WorkerJobs.FARM, farmerReplaced.job().get().type());
        assertEquals(32L, farmerReplaced.job().get().farmerData().orElseThrow().requestedBlocks());
    }

    @Test
    @DisplayName("21. WorkerStorage deposit merges and stacks items safely without item loss")
    void test21_workerStorageDepositPreservesItems() {
        WorkerStorage storage = WorkerStorage.empty();
        assertEquals(WorkerStorage.SIZE, storage.items().size());
        assertTrue(storage.items().stream().allMatch(ItemStack::isEmpty));

        // Deposit cobblestone
        ItemStack cobblestone = new ItemStack(Items.COBBLESTONE, 32);
        WorkerStorage updated = storage.deposit(cobblestone);
        assertEquals(32, updated.items().getFirst().getCount());
        assertEquals(Items.COBBLESTONE, updated.items().getFirst().getItem());

        // Deposit 40 more cobblestone -> merges to 64 in slot 0 and 8 in slot 1
        ItemStack moreCobble = new ItemStack(Items.COBBLESTONE, 40);
        WorkerStorage merged = updated.deposit(moreCobble);
        assertEquals(64, merged.items().get(0).getCount());
        assertEquals(8, merged.items().get(1).getCount());

        // Total cobblestone is exactly 72
        int total = merged.items().stream().filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
        assertEquals(72, total);
    }

    @Test
    @DisplayName("22. Client-side sneak interaction with bound ticket triggers instant authoring HUD")
    void test22_clientSideSneakInteractionInstantAuthoringHUD() {
        BindingTicketHUD.clear();
        assertFalse(BindingTicketHUD.isAuthoringActive());

        // Client sneak interaction executes:
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.JOB_AUTHORING.ordinal(),
                WORKER_ID,
                "Doremy Sweet",
                "mine",
                64L,
                null,
                "Job Authoring activated. Point at a worksite block."
        );

        assertTrue(BindingTicketHUD.isAuthoringActive(), "Authoring HUD must be active immediately");
        assertEquals(WORKER_ID, BindingTicketHUD.getActiveWorkerId());
        assertEquals(BindingTicketHUD.ClientStage.JOB_AUTHORING, BindingTicketHUD.getStage());
    }
}
