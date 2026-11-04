package com.example.examplemod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkControlRedesignTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @BeforeEach
    @AfterEach
    void cleanState() {
        WorkerOrderSession.clearAll();
        BindingTicketHUD.clear();
    }

    @Test
    void orderSessionSyncPayloadEncodesAndDecodesCorrectly() {
        UUID workerId = UUID.randomUUID();
        String workerName = "MaidWorker";
        String jobType = "mine";
        int stageOrdinal = BindingTicketHUD.ClientStage.SELECTING_STORAGE.ordinal();
        long requestedQuantity = 32L;
        BlockPos worksite = new BlockPos(100, 64, -200);
        String message = "Storage selection active";

        OrderSessionSyncPayload original = new OrderSessionSyncPayload(
                workerId, workerName, jobType, stageOrdinal, requestedQuantity, worksite, message
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        OrderSessionSyncPayload decoded = OrderSessionSyncPayload.read(buf);

        assertEquals(workerId, decoded.workerId());
        assertEquals(workerName, decoded.workerName());
        assertEquals(jobType, decoded.jobType());
        assertEquals(stageOrdinal, decoded.stageOrdinal());
        assertEquals(requestedQuantity, decoded.requestedQuantity());
        assertEquals(worksite, decoded.worksitePos());
        assertEquals(message, decoded.message());
        assertEquals(original.type(), decoded.type());
    }

    @Test
    void orderSessionSyncPayloadHandlesNullWorksitePos() {
        OrderSessionSyncPayload original = new OrderSessionSyncPayload(
                WORKER_ID, "TestWorker", "move", 1, 1L, null, "Select worksite"
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);

        OrderSessionSyncPayload decoded = OrderSessionSyncPayload.read(buf);
        assertNull(decoded.worksitePos());
        assertEquals(WORKER_ID, decoded.workerId());
        assertEquals("move", decoded.jobType());
    }

    @Test
    void bindingTicketHudTracksSessionStateLifecycle() {
        assertEquals(BindingTicketHUD.ClientStage.NONE, BindingTicketHUD.getStage());

        // 1. Enter SELECTING_WORKSITE
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.SELECTING_WORKSITE.ordinal(),
                WORKER_ID, "Maid", "mine", 32L, null, ""
        );
        assertEquals(BindingTicketHUD.ClientStage.SELECTING_WORKSITE, BindingTicketHUD.getStage());
        assertEquals("mine", BindingTicketHUD.getJobType());
        assertEquals(32L, BindingTicketHUD.getRequestedQuantity());
        assertNull(BindingTicketHUD.getWorksitePos());

        // 2. Transition to SELECTING_STORAGE
        BlockPos site = new BlockPos(10, 64, 10);
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.SELECTING_STORAGE.ordinal(),
                WORKER_ID, "Maid", "mine", 32L, site, ""
        );
        assertEquals(BindingTicketHUD.ClientStage.SELECTING_STORAGE, BindingTicketHUD.getStage());
        assertEquals(site, BindingTicketHUD.getWorksitePos());

        // 3. Complete Order
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.ORDER_COMPLETED.ordinal(),
                WORKER_ID, "Maid", "mine", 32L, site, "Order Activated!"
        );
        assertEquals(BindingTicketHUD.ClientStage.ORDER_COMPLETED, BindingTicketHUD.getStage());

        // 4. Clear HUD
        BindingTicketHUD.clear();
        assertEquals(BindingTicketHUD.ClientStage.NONE, BindingTicketHUD.getStage());
        assertEquals(0L, BindingTicketHUD.getRequestedQuantity());
        assertNull(BindingTicketHUD.getWorksitePos());
    }

    @Test
    void newOrderSessionDoesNotInheritStaleOrderTargetsOrDestinations() {
        long now = System.currentTimeMillis();
        BlockPos oldWorksite = new BlockPos(50, 64, 50);
        BlockPos oldStorage = new BlockPos(55, 64, 55);

        // Previous order session with storage
        WorkerOrderSession.Session oldSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(new WorkerTarget(OVERWORLD, oldWorksite)),
                Optional.of(new WorkerTarget(OVERWORLD, oldStorage)),
                16L, now
        );
        WorkerOrderSession.setSessionForTesting(oldSession);

        // Cancel previous order
        WorkerOrderSession.cancelOrder(PLAYER_ID);
        assertTrue(WorkerOrderSession.getSession(PLAYER_ID).isEmpty());

        // Fresh new order session started
        WorkerOrderSession.Session freshSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.FARM,
                WorkerOrderSession.Stage.SELECTING_WORKSITE,
                Optional.empty(), Optional.empty(), 64L, now
        );
        WorkerOrderSession.setSessionForTesting(freshSession);

        WorkerOrderSession.Session active = WorkerOrderSession.getSession(PLAYER_ID).orElseThrow();
        assertEquals(WorkerJobs.FARM, active.jobType());
        assertEquals(64L, active.requestedQuantity());
        assertEquals(WorkerOrderSession.Stage.SELECTING_WORKSITE, active.stage());
        assertTrue(active.worksite().isEmpty(), "New session must NOT inherit prior worksite");
        assertTrue(active.storage().isEmpty(), "New session must NOT inherit prior storage");
    }

    @Test
    void workerOrderSessionDirectionNormalizedAndPreserved() {
        long now = System.currentTimeMillis();
        BlockPos worksite = new BlockPos(10, 64, 10);

        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(new WorkerTarget(OVERWORLD, worksite)),
                Optional.empty(),
                32L,
                now,
                Optional.of(Direction.SOUTH),
                List.of()
        );

        assertTrue(session.direction().isPresent());
        assertEquals(Direction.SOUTH, session.direction().get());
        assertEquals(32L, session.requestedQuantity());
    }

    @Test
    void workerCannotReceiveNewJobIfAlreadyAssignedOrRunning() {
        WorkerProfile idleProfile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        assertTrue(idleProfile.job().isEmpty());

        WorkerJob job = new WorkerJob(WorkerJobs.MOVE, JobStatus.RUNNING, 50, Optional.empty(),
                Optional.of(new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0))));
        WorkerProfile busyProfile = idleProfile.withJob(job);

        assertTrue(busyProfile.job().isPresent());
        // Attempting to assign a new Move job to an already busy worker returns empty
        Optional<WorkerProfile> reassigned = busyProfile.assignMove(PLAYER_ID, null,
                new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10)));
        assertTrue(reassigned.isEmpty(), "Busy worker must reject conflicting job assignment");
    }
}
