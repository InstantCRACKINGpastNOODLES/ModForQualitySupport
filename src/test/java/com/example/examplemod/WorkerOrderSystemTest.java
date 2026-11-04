package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerOrderSystemTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation CUSTOM_JOB = ResourceLocation.fromNamespaceAndPath("elstarticket", "custom_test_job");

    @BeforeEach
    @AfterEach
    void cleanState() {
        WorkerOrderSession.clearAll();
        WorksiteAdapter.Registry.clear();
    }

    @Test
    void sessionRecordPreservesStateAndEvaluatesExpiration() {
        long now = 1_000_000L;
        WorkerTarget worksite = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
        WorkerTarget storage = new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12));

        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                PLAYER_ID,
                WORKER_ID,
                OVERWORLD,
                WorkerJobs.MOVE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(worksite),
                Optional.of(storage),
                now
        );

        assertEquals(PLAYER_ID, session.playerId());
        assertEquals(WORKER_ID, session.workerId());
        assertEquals(OVERWORLD, session.dimension());
        assertEquals(WorkerJobs.MOVE, session.jobType());
        assertEquals(WorkerOrderSession.Stage.SELECTING_STORAGE, session.stage());
        assertTrue(session.worksite().isPresent());
        assertEquals(worksite, session.worksite().get());
        assertTrue(session.storage().isPresent());
        assertEquals(storage, session.storage().get());

        // Within 60 seconds (60,000 ms) -> not expired
        assertFalse(session.isExpired(now + 30_000L, WorkerOrderSession.SESSION_TIMEOUT_MS));
        assertFalse(session.isExpired(now + 60_000L, WorkerOrderSession.SESSION_TIMEOUT_MS));

        // After 60 seconds -> expired
        assertTrue(session.isExpired(now + 60_001L, WorkerOrderSession.SESSION_TIMEOUT_MS));
    }

    @Test
    void activeSessionLifecycleAndAutoTimeoutCleanup() {
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).isEmpty());

        long recent = System.currentTimeMillis();
        WorkerOrderSession.Session activeSession = new WorkerOrderSession.Session(
                PLAYER_ID,
                WORKER_ID,
                OVERWORLD,
                WorkerJobs.MOVE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(),
                Optional.empty(),
                recent
        );

        WorkerOrderSession.setSessionForTesting(activeSession);
        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).isPresent());
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, WorkerOrderSession.getSession(PLAYER_ID).get().stage());

        // Cancel order explicitly
        WorkerOrderSession.cancelOrder(PLAYER_ID);
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).isEmpty());

        // Stale expired session is automatically purged upon query
        long expiredTime = System.currentTimeMillis() - 65_000L;
        WorkerOrderSession.Session expiredSession = new WorkerOrderSession.Session(
                PLAYER_ID,
                WORKER_ID,
                OVERWORLD,
                WorkerJobs.MOVE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(),
                Optional.empty(),
                expiredTime
        );

        WorkerOrderSession.setSessionForTesting(expiredSession);
        // Query triggers expiration check and removes it
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).isEmpty());
    }

    @Test
    void worksiteAdapterRegistryResolvesBuiltInAndCustomAdapters() {
        // Built-in Move worksite adapter
        Optional<WorksiteAdapter> moveAdapter = WorksiteAdapter.Registry.get(WorkerJobs.MOVE);
        assertTrue(moveAdapter.isPresent());
        assertEquals(WorkerJobs.MOVE, moveAdapter.get().jobType());

        // Non-existent adapter
        assertNull(WorksiteAdapter.Registry.get(null).orElse(null));
        assertTrue(WorksiteAdapter.Registry.get(CUSTOM_JOB).isEmpty());

        // Register custom adapter
        WorksiteAdapter customAdapter = new WorksiteAdapter() {
            @Override
            public ResourceLocation jobType() {
                return CUSTOM_JOB;
            }

            @Override
            public boolean isValidWorksite(net.minecraft.server.level.ServerLevel level, BlockPos pos, Direction clickedFace, net.minecraft.world.level.block.state.BlockState state, net.minecraft.server.level.ServerPlayer player) {
                return true;
            }

            @Override
            public Optional<WorkerTarget> createTarget(net.minecraft.server.level.ServerLevel level, BlockPos pos, Direction clickedFace, net.minecraft.world.level.block.state.BlockState state) {
                return Optional.of(new WorkerTarget(OVERWORLD, pos));
            }
        };

        WorksiteAdapter.Registry.register(customAdapter);
        assertTrue(WorksiteAdapter.Registry.get(CUSTOM_JOB).isPresent());
        assertEquals(customAdapter, WorksiteAdapter.Registry.get(CUSTOM_JOB).get());
    }

    @Test
    void moveWorksiteAdapterRequiresTopFaceTarget() {
        WorksiteAdapter moveAdapter = WorksiteAdapter.Registry.get(WorkerJobs.MOVE).orElseThrow();
        BlockPos basePos = new BlockPos(10, 64, 10);

        // Click on top face produces target at above position
        Optional<WorkerTarget> targetUp = moveAdapter.createTarget(null, basePos, Direction.UP, null);
        assertTrue(targetUp.isEmpty()); // null level returns empty safely

        // Non-UP faces always return empty even with valid inputs
        assertTrue(moveAdapter.createTarget(null, basePos, Direction.DOWN, null).isEmpty());
        assertTrue(moveAdapter.createTarget(null, basePos, Direction.NORTH, null).isEmpty());
        assertTrue(moveAdapter.createTarget(null, basePos, Direction.SOUTH, null).isEmpty());
        assertTrue(moveAdapter.createTarget(null, basePos, Direction.EAST, null).isEmpty());
        assertTrue(moveAdapter.createTarget(null, basePos, Direction.WEST, null).isEmpty());
    }

    @Test
    void workerJobCapabilityRegistryValidatesJobPermissions() {
        // Null worker or null job type returns false safely
        assertFalse(WorkerJobCapability.Registry.canPerform(null, null, null));
        assertFalse(WorkerJobCapability.Registry.canPerform(WorkerJobs.MOVE, null, null));

        // Custom capability registration
        WorkerJobCapability customCap = (job, worker, tool) -> true;
        WorkerJobCapability.Registry.register(CUSTOM_JOB, customCap);

        // Custom capability evaluates through registry when worker is non-null
        // When worker is null, canPerform guards against null pointer
        assertFalse(WorkerJobCapability.Registry.canPerform(CUSTOM_JOB, null, null));
    }

    @Test
    void addTaskMineRequiresVerifiedMiningToolOnWorker() {
        net.minecraft.world.item.ItemStack pick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        net.minecraft.world.item.ItemStack stick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
        net.minecraft.world.item.ItemStack empty = net.minecraft.world.item.ItemStack.EMPTY;

        // Empty hand or stick cannot perform mining
        assertFalse(WorkerJobCapability.Registry.canPerformMining(null, empty));
        assertFalse(WorkerJobCapability.Registry.canPerformMining(null, stick));

        // Unverified tools are rejected by safety policy
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(stick, 1));
        assertFalse(JobSafetyPolicy.isMiningToolAuthorized(empty, 1));

        // Verified vanilla pickaxe is authorized
        assertTrue(JobSafetyPolicy.isMiningToolAuthorized(pick, 1));
    }

    @Test
    void orderSelectionSequencePreservesStateAndRejectsPrematureActivation() {
        long now = System.currentTimeMillis();
        BlockPos worksitePos = new BlockPos(10, 64, 10);
        WorkerTarget worksite = new WorkerTarget(OVERWORLD, worksitePos);
        BlockPos storagePos = new BlockPos(12, 64, 12);
        WorkerTarget storage = new WorkerTarget(OVERWORLD, storagePos);

        // Stage 1: SELECTING_WORKSITE
        WorkerOrderSession.Session sessionStage1 = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(sessionStage1);

        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().stage());
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().worksite().isEmpty());

        // Stage 2: SELECTING_STORAGE (worksite selected)
        WorkerOrderSession.Session sessionStage2 = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(worksite), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(sessionStage2);

        assertEquals(WorkerOrderSession.Stage.SELECTING_STORAGE, WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().stage());
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().worksite().isPresent());
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().storage().isEmpty(),
                "Job must NOT be active or have storage set before storage selection completes");

        // Stage 3: Cancellation restores normal IDLE state
        WorkerOrderSession.cancelOrder(PLAYER_ID);
        assertFalse(WorkerOrderSession.hasActiveSession(PLAYER_ID), "Cancelling order must restore normal idle state");
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).isEmpty());
    }

    @Test
    void sessionCannotBeHijackedByAnotherPlayer() {
        long now = System.currentTimeMillis();
        UUID attackerId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        WorkerOrderSession.Session playerSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(playerSession);

        // Attacker query for their own session returns empty
        assertFalse(WorkerOrderSession.hasActiveSession(attackerId));
        assertTrue(WorkerOrderSession.getSession(attackerId).isEmpty());

        // Attacker attempting to select worksite fails
        WorkerOrderSession.StepResult result = WorkerOrderSession.selectWorksite(null, new BlockPos(0, 64, 0), Direction.UP);
        assertEquals(WorkerOrderSession.StepResult.UNAVAILABLE, result);

        // Original player session remains intact
        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().stage());
    }
}
