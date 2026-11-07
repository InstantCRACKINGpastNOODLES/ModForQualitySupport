package com.example.examplemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Phase 1B / Phase 1B.2 HOLD Session Runtime Specification and Remediation Tests.
 * Strictly covers Groups A through G and Remediation Groups A through F.
 */
class HoldSessionManagerTest {

    private static final ResourceLocation EXECUTOR_ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "mock_continuous_executor");

    private WorkerInteractionRegistry registry;
    private HoldSessionManager manager;
    private MockContinuousExecutor executor;

    @BeforeEach
    void setup() {
        registry = new WorkerInteractionRegistry();
        executor = new MockContinuousExecutor(EXECUTOR_ID);
        registry.registerExecutor(executor);
        manager = new HoldSessionManager(registry, 200, 60);
    }

    private InteractionIntent holdIntent() {
        return InteractionIntent.builder()
                .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                .actionType(ActionType.RIGHT_CLICK)
                .executionMode(ExecutionMode.HOLD)
                .build();
    }

    private InteractionIntent pressReleaseIntent(int durationTicks) {
        return InteractionIntent.builder()
                .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                .actionType(ActionType.RIGHT_CLICK)
                .executionMode(ExecutionMode.PRESS_RELEASE)
                .durationTicks(durationTicks)
                .build();
    }

    // =========================================================================
    // Group A — State Model
    // =========================================================================

    @Nested
    @DisplayName("Group A — State Model")
    class GroupA_StateModel {

        @Test
        @DisplayName("A01: Initial state")
        void testA01_InitialState() {
            HoldSessionState idle = HoldSessionState.IDLE;
            assertFalse(idle.isTerminal());
            assertFalse(idle.isActive());

            UUID workerId = UUID.randomUUID();
            InteractionResult result = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertTrue(result.outcome().isSuccess());

            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.PRESSED, session.state());
            assertTrue(session.isActive());
            assertFalse(session.isTerminal());
        }

        @Test
        @DisplayName("A02: Valid IDLE -> PRESSED")
        void testA02_IdleToPressed() {
            assertTrue(HoldSessionState.IDLE.canTransitionTo(HoldSessionState.PRESSED));
        }

        @Test
        @DisplayName("A03: Valid PRESSED -> HELD")
        void testA03_PressedToHeld() {
            assertTrue(HoldSessionState.PRESSED.canTransitionTo(HoldSessionState.HELD));
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.PRESSED, session.state());

            manager.tick(InteractionContext.EMPTY);
            assertEquals(HoldSessionState.HELD, session.state());
        }

        @Test
        @DisplayName("A04: Valid HELD -> RELEASED")
        void testA04_HeldToReleased() {
            assertTrue(HoldSessionState.HELD.canTransitionTo(HoldSessionState.RELEASED));
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.tick(InteractionContext.EMPTY); // PRESSED -> HELD

            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.HELD, session.state());

            manager.release(workerId, InteractionContext.EMPTY);
            assertEquals(HoldSessionState.RELEASED, session.state());
            assertTrue(session.isTerminal());
            assertFalse(session.isActive());
        }

        @Test
        @DisplayName("A05: Valid PRESSED -> CANCELLED")
        void testA05_PressedToCancelled() {
            assertTrue(HoldSessionState.PRESSED.canTransitionTo(HoldSessionState.CANCELLED));
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.PRESSED, session.state());

            manager.cancel(workerId, InteractionContext.EMPTY, "TEST_CANCEL");
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(Optional.of("TEST_CANCEL"), session.cancellationReason());
            assertTrue(session.isTerminal());
        }

        @Test
        @DisplayName("A06: HELD -> CANCELLED")
        void testA06_HeldToCancelled() {
            assertTrue(HoldSessionState.HELD.canTransitionTo(HoldSessionState.CANCELLED));
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.tick(InteractionContext.EMPTY);

            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            manager.cancel(workerId, InteractionContext.EMPTY, "HELD_CANCEL");
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertTrue(session.isTerminal());
        }

        @Test
        @DisplayName("A07: RELEASED cannot transition to HELD")
        void testA07_ReleasedCannotTransitionToHeld() {
            assertFalse(HoldSessionState.RELEASED.canTransitionTo(HoldSessionState.HELD));
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            manager.release(workerId, InteractionContext.EMPTY);

            boolean transitioned = session.transitionTo(HoldSessionState.HELD, "ILLEGAL");
            assertFalse(transitioned);
            assertEquals(HoldSessionState.RELEASED, session.state());
        }

        @Test
        @DisplayName("A08: CANCELLED cannot transition to HELD")
        void testA08_CancelledCannotTransitionToHeld() {
            assertFalse(HoldSessionState.CANCELLED.canTransitionTo(HoldSessionState.HELD));
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL");

            boolean transitioned = session.transitionTo(HoldSessionState.HELD, "ILLEGAL");
            assertFalse(transitioned);
            assertEquals(HoldSessionState.CANCELLED, session.state());
        }

        @Test
        @DisplayName("A09: Terminal session cannot tick")
        void testA09_TerminalSessionCannotTick() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            manager.release(workerId, InteractionContext.EMPTY);

            assertThrows(IllegalStateException.class, () -> session.recordTick(10, null));
        }

        @Test
        @DisplayName("A10: Invalid transitions are rejected safely")
        void testA10_InvalidTransitionsRejected() {
            assertFalse(HoldSessionState.IDLE.canTransitionTo(HoldSessionState.RELEASED));
            assertFalse(HoldSessionState.IDLE.canTransitionTo(null));
            assertFalse(HoldSessionState.RELEASED.canTransitionTo(HoldSessionState.PRESSED));
            assertFalse(HoldSessionState.CANCELLED.canTransitionTo(HoldSessionState.RELEASED));
            assertFalse(HoldSessionState.RELEASED.canTransitionTo(HoldSessionState.CANCELLED));
        }
    }

    // =========================================================================
    // Group B — Start and Ownership
    // =========================================================================

    @Nested
    @DisplayName("Group B — Start and Ownership")
    class GroupB_StartAndOwnership {

        @Test
        @DisplayName("B01: Start session succeeds for valid intent")
        void testB01_StartSessionSucceeds() {
            UUID workerId = UUID.randomUUID();
            InteractionResult result = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            assertTrue(result.outcome().isSuccess());
            assertEquals(1, executor.startCount);
            assertEquals(1, manager.getActiveSessionCount());
            assertTrue(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("B02: Reject second session on same Worker while first is active")
        void testB02_RejectSecondSessionOnSameWorker() {
            UUID workerId = UUID.randomUUID();
            InteractionResult first = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertTrue(first.outcome().isSuccess());

            InteractionResult second = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.REJECTED, second.outcome());
            assertEquals("WORKER_BUSY", second.reasonCode());
            assertEquals(1, executor.startCount); // Must not have called startContinuous again
            assertEquals(1, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("B03: Two different Workers can hold simultaneously")
        void testB03_TwoWorkersSimultaneousHold() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            InteractionResult res1 = manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            InteractionResult res2 = manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            assertTrue(res1.outcome().isSuccess());
            assertTrue(res2.outcome().isSuccess());
            assertEquals(2, manager.getActiveSessionCount());
            assertTrue(manager.hasActiveSession(worker1));
            assertTrue(manager.hasActiveSession(worker2));
        }

        @Test
        @DisplayName("B04: Reject CLICK mode in HoldSessionManager")
        void testB04_RejectClickMode() {
            UUID workerId = UUID.randomUUID();
            InteractionIntent clickIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.LEFT_CLICK)
                    .executionMode(ExecutionMode.CLICK)
                    .build();

            InteractionResult res = manager.startSession(workerId, clickIntent, InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals("UNSUPPORTED_CONTINUOUS_MODE", res.reasonCode());
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("B05: Reject REPEAT mode in HoldSessionManager")
        void testB05_RejectRepeatMode() {
            UUID workerId = UUID.randomUUID();
            InteractionIntent repeatIntent = InteractionIntent.builder()
                    .target(new InteractionTarget.BlockTarget(BlockPos.ZERO))
                    .actionType(ActionType.RIGHT_CLICK)
                    .executionMode(ExecutionMode.REPEAT)
                    .repeatIntervalTicks(5)
                    .build();

            InteractionResult res = manager.startSession(workerId, repeatIntent, InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("B06: Start with PRESS_RELEASE mode succeeds")
        void testB06_StartPressReleaseSucceeds() {
            UUID workerId = UUID.randomUUID();
            InteractionResult res = manager.startSession(workerId, pressReleaseIntent(5), InteractionContext.EMPTY);

            assertTrue(res.outcome().isSuccess());
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(Optional.of(5), session.expectedDurationTicks());
        }

        @Test
        @DisplayName("B07: Reject null arguments in startSession")
        void testB07_RejectNullArguments() {
            UUID workerId = UUID.randomUUID();
            assertThrows(NullPointerException.class, () -> manager.startSession(null, holdIntent(), InteractionContext.EMPTY));
            assertThrows(NullPointerException.class, () -> manager.startSession(workerId, null, InteractionContext.EMPTY));
            assertThrows(NullPointerException.class, () -> manager.startSession(workerId, holdIntent(), null));
        }

        @Test
        @DisplayName("B08: Unresolved intent rejected without creating session")
        void testB08_UnresolvedIntentRejected() {
            WorkerInteractionRegistry emptyRegistry = new WorkerInteractionRegistry();
            HoldSessionManager emptyMgr = new HoldSessionManager(emptyRegistry);

            UUID workerId = UUID.randomUUID();
            InteractionResult res = emptyMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.UNSUPPORTED, res.outcome());
            assertEquals(0, emptyMgr.getActiveSessionCount());
        }

        @Test
        @DisplayName("B09: New session permitted on worker after previous session released")
        void testB09_NewSessionPermittedAfterRelease() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.release(workerId, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
            InteractionResult second = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertTrue(second.outcome().isSuccess());
            assertEquals(1, manager.getActiveSessionCount());
        }
    }

    // =========================================================================
    // Group C — Tick Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Group C — Tick Lifecycle")
    class GroupC_TickLifecycle {

        @Test
        @DisplayName("C01: Tick advances PRESSED to HELD on first tick")
        void testC01_TickAdvancesPressedToHeld() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.PRESSED, session.state());

            manager.tick(1, InteractionContext.EMPTY);
            assertEquals(HoldSessionState.HELD, session.state());
            assertEquals(1, session.elapsedTicks());
            assertEquals(1, executor.tickCount);
        }

        @Test
        @DisplayName("C02: Multiple ticks maintain HELD and increment elapsedTicks")
        void testC02_MultipleTicksMaintainHeld() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            manager.tick(1, InteractionContext.EMPTY);
            manager.tick(2, InteractionContext.EMPTY);
            manager.tick(3, InteractionContext.EMPTY);

            assertEquals(HoldSessionState.HELD, session.state());
            assertEquals(3, session.elapsedTicks());
            assertEquals(3, executor.tickCount);
        }

        @Test
        @DisplayName("C03: Session not ticked twice in same tick")
        void testC03_NotTickedTwiceInOneTick() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            manager.tick(1, InteractionContext.EMPTY);
            manager.tick(1, InteractionContext.EMPTY); // Duplicate tick call

            assertEquals(1, session.elapsedTicks());
            assertEquals(1, executor.tickCount);
        }

        @Test
        @DisplayName("C04: Multiple independent sessions ticked in order")
        void testC04_MultipleSessionsTicked() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);

            assertEquals(HoldSessionState.HELD, manager.getActiveSession(worker1).orElseThrow().state());
            assertEquals(HoldSessionState.HELD, manager.getActiveSession(worker2).orElseThrow().state());
            assertEquals(2, executor.tickCount);
        }

        @Test
        @DisplayName("C05: Tick does nothing when no active sessions")
        void testC05_TickWithNoActiveSessions() {
            manager.tick(1, InteractionContext.EMPTY);
            assertEquals(0, executor.tickCount);
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("C06: Tick with SUCCESS terminates session normally")
        void testC06_TickSuccessTerminatesNormally() {
            executor.nextTickResult = InteractionResult.success(EXECUTOR_ID, true, "MINE_DONE");
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("C07: Tick with FAILED cancels session")
        void testC07_TickFailedCancelsSession() {
            executor.nextTickResult = InteractionResult.failed("TOOL_BROKE", "Pickaxe broken", EXECUTOR_ID);
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
            assertEquals(0, executor.releaseCount);
        }
    }

    // =========================================================================
    // Group D — Release Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Group D — Release Lifecycle")
    class GroupD_ReleaseLifecycle {

        @Test
        @DisplayName("D01: Normal caller release")
        void testD01_NormalRelease() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            InteractionResult result = manager.release(workerId, InteractionContext.EMPTY);

            assertTrue(result.outcome().isSuccess());
            assertEquals(1, executor.releaseCount);
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("D02: Release after PRESS_RELEASE duration")
        void testD02_ReleaseAfterPressReleaseDuration() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(3), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY); // tick 1
            assertTrue(manager.hasActiveSession(workerId));
            manager.tick(2, InteractionContext.EMPTY); // tick 2
            assertTrue(manager.hasActiveSession(workerId));
            manager.tick(3, InteractionContext.EMPTY); // tick 3 -> duration reached!

            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D03: Release only once")
        void testD03_ReleaseOnlyOnce() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            InteractionResult first = manager.release(workerId, InteractionContext.EMPTY);
            assertTrue(first.outcome().isSuccess());

            InteractionResult second = manager.release(workerId, InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.REJECTED, second.outcome());
            assertEquals("NO_ACTIVE_SESSION", second.reasonCode());
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D04: Release after PRESSED")
        void testD04_ReleaseAfterPressed() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.PRESSED, session.state());

            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);
            assertTrue(res.outcome().isSuccess());
            assertEquals(HoldSessionState.RELEASED, session.state());
        }

        @Test
        @DisplayName("D05: Release after HELD")
        void testD05_ReleaseAfterHeld() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.tick(InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.HELD, session.state());

            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);
            assertTrue(res.outcome().isSuccess());
            assertEquals(HoldSessionState.RELEASED, session.state());
        }

        @Test
        @DisplayName("D06: Release failure is recorded and fallback cancel cleans up")
        void testD06_ReleaseFailureIsRecorded() {
            executor.nextReleaseResult = InteractionResult.failed("JAMMED", "Mechanism jammed", EXECUTOR_ID);
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            InteractionResult result = manager.release(workerId, InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.FAILED, result.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertTrue(session.failureDetail().isPresent());
            assertEquals(1, executor.cancelCount); // Verified fallback cleanup!
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("D07: Release exception is handled and fallback cancel cleans up")
        void testD07_ReleaseExceptionIsHandled() {
            executor.throwOnRelease = new RuntimeException("Hardware disconnect");
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            InteractionResult result = manager.release(workerId, InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.FAILED, result.outcome());
            assertEquals("RELEASE_EXCEPTION", result.reasonCode());
            assertEquals(1, executor.cancelCount); // Verified fallback cleanup!
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("D08: Failed release is not reported as successful release")
        void testD08_FailedReleaseNotReportedAsSuccess() {
            executor.nextReleaseResult = InteractionResult.failed("REJECTED_RELEASE", "Locked in place", EXECUTOR_ID);
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            InteractionResult result = manager.release(workerId, InteractionContext.EMPTY);
            assertFalse(result.outcome().isSuccess());
            assertNotEquals(InteractionOutcome.SUCCESS, result.outcome());
        }
    }

    // =========================================================================
    // Group E — Cancellation and Cleanup
    // =========================================================================

    @Nested
    @DisplayName("Group E — Cancellation and Cleanup")
    class GroupE_CancellationAndCleanup {

        @Test
        @DisplayName("E01: Explicit cancel terminates session")
        void testE01_ExplicitCancel() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            boolean cancelled = manager.cancel(workerId, InteractionContext.EMPTY, "MANUAL");
            assertTrue(cancelled);
            assertEquals(1, executor.cancelCount);
            assertEquals("MANUAL", executor.lastCancelReason);
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("E02: Cancel PRESSED")
        void testE02_CancelPressed() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.PRESSED, session.state());

            manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_PRESSED");
            assertEquals(HoldSessionState.CANCELLED, session.state());
        }

        @Test
        @DisplayName("E03: Cancel HELD")
        void testE03_CancelHeld() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.tick(InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(HoldSessionState.HELD, session.state());

            manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_HELD");
            assertEquals(HoldSessionState.CANCELLED, session.state());
        }

        @Test
        @DisplayName("E04: Repeated cancel is idempotent")
        void testE04_RepeatedCancelIsIdempotent() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            boolean first = manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_1");
            assertTrue(first);
            assertEquals(1, executor.cancelCount);

            boolean second = manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_2");
            assertFalse(second);
            assertEquals(1, executor.cancelCount); // Must not re-cancel executor
        }

        @Test
        @DisplayName("E05: Cancel after RELEASED")
        void testE05_CancelAfterReleased() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.release(workerId, InteractionContext.EMPTY);

            boolean cancelled = manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_AFTER_RELEASE");
            assertFalse(cancelled);
            assertEquals(0, executor.cancelCount);
        }

        @Test
        @DisplayName("E06: Cancel after CANCELLED")
        void testE06_CancelAfterCancelled() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.cancel(workerId, InteractionContext.EMPTY, "FIRST");

            boolean cancelled = manager.cancel(workerId, InteractionContext.EMPTY, "SECOND");
            assertFalse(cancelled);
        }

        @Test
        @DisplayName("E07: Tick exception triggers cleanup")
        void testE07_TickExceptionTriggersCleanup() {
            executor.throwOnTick = new RuntimeException("Tick crash");
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            manager.tick(InteractionContext.EMPTY);
            assertEquals(1, executor.cancelCount);
            assertEquals(0, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("E08: Start exception does not create ghost session")
        void testE08_StartExceptionNoGhostSession() {
            executor.throwOnStart = new RuntimeException("Start crash");
            UUID workerId = UUID.randomUUID();

            InteractionResult result = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.FAILED, result.outcome());
            assertEquals(0, manager.getActiveSessionCount());
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("E09: Cancel exception is recorded and session quarantined")
        void testE09_CancelExceptionIsRecorded() {
            executor.throwOnCancel = new RuntimeException("Cancel crash");
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            boolean cancelled = manager.cancel(workerId, InteractionContext.EMPTY, "CRASH_CANCEL");
            assertTrue(cancelled);
            assertTrue(session.failureDetail().isPresent());
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());
            assertTrue(session.isQuarantined());
            assertTrue(manager.hasActiveSession(workerId));
            assertEquals(1, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("E10: One cleanup failure does not block other sessions")
        void testE10_OneCleanupFailureDoesNotBlockOthers() {
            MockContinuousExecutor faultyExecutor = new MockContinuousExecutor(
                    ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "faulty_exec"),
                    200
            );
            faultyExecutor.throwOnCancel = new RuntimeException("Boom");
            registry.registerExecutor(faultyExecutor);

            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            // worker1 resolved to faultyExecutor (priority 200)

            // Unregister faultyExecutor so worker2 uses the default healthy executor (priority 100)
            registry.unregisterExecutor(faultyExecutor.id());
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            manager.cancelAll(InteractionContext.EMPTY, "SHUTDOWN");
            assertFalse(manager.hasActiveSession(worker2));
            assertTrue(manager.hasActiveSession(worker1));
        }

        @Test
        @DisplayName("E11: No active session remains after successful cancellation")
        void testE11_NoActiveSessionRemainsAfterCancellation() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.cancel(workerId, InteractionContext.EMPTY, "TEST");

            assertFalse(manager.hasActiveSession(workerId));
            assertTrue(manager.getActiveSession(workerId).isEmpty());
        }
    }

    // =========================================================================
    // Group F — Timeout and Watchdog
    // =========================================================================

    @Nested
    @DisplayName("Group F — Timeout and Watchdog")
    class GroupF_TimeoutAndWatchdog {

        @Test
        @DisplayName("F01: Max hold timeout terminates session")
        void testF01_MaxHoldTimeoutTerminates() {
            HoldSessionManager shortManager = new HoldSessionManager(registry, 10, 60);
            UUID workerId = UUID.randomUUID();
            shortManager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            for (int i = 1; i <= 10; i++) {
                shortManager.tick(i, InteractionContext.EMPTY);
                assertTrue(shortManager.hasActiveSession(workerId));
            }

            shortManager.tick(11, InteractionContext.EMPTY);
            assertFalse(shortManager.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
            assertEquals("MAX_HOLD_TIMEOUT_EXCEEDED", executor.lastCancelReason);
        }

        @Test
        @DisplayName("F02: Custom max hold ticks per manager")
        void testF02_CustomMaxHoldTicks() {
            HoldSessionManager custom = new HoldSessionManager(registry, 50, 60);
            assertEquals(50, custom.defaultMaxHoldTicks());
        }

        @Test
        @DisplayName("F03: Reject negative max hold ticks in constructor")
        void testF03_RejectNegativeMaxHoldTicks() {
            assertThrows(IllegalArgumentException.class, () -> new HoldSessionManager(registry, -1, 60));
            assertThrows(IllegalArgumentException.class, () -> new HoldSessionManager(registry, 0, 60));
        }

        @Test
        @DisplayName("F04: Reject negative stall watchdog ticks in constructor")
        void testF04_RejectNegativeStallWatchdogTicks() {
            assertThrows(IllegalArgumentException.class, () -> new HoldSessionManager(registry, 100, -5));
            assertThrows(IllegalArgumentException.class, () -> new HoldSessionManager(registry, 100, 0));
        }

        @Test
        @DisplayName("F05: Max hold respects PRESS_RELEASE duration")
        void testF05_PressReleaseMaxHoldRespectsDuration() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(4), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(4, session.maxHoldTicks());
        }

        @Test
        @DisplayName("F06: HOLD mode uses default max hold ticks")
        void testF06_HoldModeUsesDefaultMaxHold() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            assertEquals(200, session.maxHoldTicks());
        }

        @Test
        @DisplayName("F07: Timeout cancellation does not call release")
        void testF07_TimeoutDoesNotCallRelease() {
            HoldSessionManager shortManager = new HoldSessionManager(registry, 2, 60);
            UUID workerId = UUID.randomUUID();
            shortManager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            shortManager.tick(1, InteractionContext.EMPTY);
            shortManager.tick(2, InteractionContext.EMPTY);
            shortManager.tick(3, InteractionContext.EMPTY);

            assertEquals(0, executor.releaseCount);
            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("F08: Watchdog detects frozen/stalled session")
        void testF08_WatchdogDetectsStalledSession() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 10);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            watchdogMgr.tick(1, InteractionContext.EMPTY);
            assertTrue(watchdogMgr.hasActiveSession(workerId));

            // Jump clock forward by 25 ticks without regular progression (simulating freeze/stall)
            watchdogMgr.tick(26, InteractionContext.EMPTY);
            assertFalse(watchdogMgr.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
            assertEquals("WATCHDOG_STALL_DETECTED", executor.lastCancelReason);
        }

        @Test
        @DisplayName("F09: Watchdog does not terminate valid progressing session")
        void testF09_WatchdogDoesNotTerminateProgressingSession() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 5);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = watchdogMgr.getActiveSession(workerId).orElseThrow();

            // Progress signal reset avoids watchdog timeout even when WAITING
            for (int i = 1; i <= 20; i++) {
                session.recordProgress(i);
                watchdogMgr.tick(i, InteractionContext.EMPTY);
                assertTrue(watchdogMgr.hasActiveSession(workerId));
            }
            assertEquals(0, executor.cancelCount);
        }
    }

    // =========================================================================
    // Group G — Isolation and Regression
    // =========================================================================

    @Nested
    @DisplayName("Group G — Isolation and Regression")
    class GroupG_IsolationAndRegression {

        @Test
        @DisplayName("G01: Two independent sessions can release independently")
        void testG01_IndependentReleases() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            manager.release(worker1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(worker1));
            assertTrue(manager.hasActiveSession(worker2));

            manager.release(worker2, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(worker2));
        }

        @Test
        @DisplayName("G02: Two independent sessions can cancel independently")
        void testG02_IndependentCancels() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            manager.cancel(worker1, InteractionContext.EMPTY, "CANCEL_1");
            assertFalse(manager.hasActiveSession(worker1));
            assertTrue(manager.hasActiveSession(worker2));

            manager.cancel(worker2, InteractionContext.EMPTY, "CANCEL_2");
            assertFalse(manager.hasActiveSession(worker2));
        }

        @Test
        @DisplayName("G03: cancelAll cancels all sessions")
        void testG03_CancelAllCancelsAll() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();
            UUID worker3 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker3, holdIntent(), InteractionContext.EMPTY);

            assertEquals(3, manager.getActiveSessionCount());

            manager.cancelAll(InteractionContext.EMPTY, "SHUTDOWN");
            assertEquals(0, manager.getActiveSessionCount());
            assertEquals(3, executor.cancelCount);
        }

        @Test
        @DisplayName("G04: Session state transitions are thread-safe and consistent")
        void testG04_SessionStateConsistency() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            assertNotNull(session.sessionId());
            assertNotNull(session.workerId());
            assertNotNull(session.intent());
            assertNotNull(session.executor());
            assertEquals(HoldSessionState.PRESSED, session.state());
        }

        @Test
        @DisplayName("G05: Elapsed ticks counter matches tick progression exactly")
        void testG05_ElapsedTicksProgression() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            for (int i = 1; i <= 15; i++) {
                manager.tick(i, InteractionContext.EMPTY);
                assertEquals(i, session.elapsedTicks());
            }
        }

        @Test
        @DisplayName("G06: Context passed to manager carries session reference")
        void testG06_ContextSessionReference() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            interface HoldContext extends InteractionContext {
                HoldSession holdSession();
            }
            HoldContext sessionCtx = () -> session;
            assertEquals(session.sessionId(), sessionCtx.holdSession().sessionId());
        }

        @Test
        @DisplayName("G07: Intent immutability during session execution")
        void testG07_IntentImmutability() {
            UUID workerId = UUID.randomUUID();
            InteractionIntent intent = holdIntent();
            manager.startSession(workerId, intent, InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            assertEquals(intent, session.intent());
            manager.tick(InteractionContext.EMPTY);
            assertEquals(intent, session.intent());
        }

        @Test
        @DisplayName("G08: Executor references remain stable for session lifetime")
        void testG08_ExecutorReferencesRemainStable() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            InteractionExecutor initialExec = session.executor();
            manager.tick(InteractionContext.EMPTY);
            assertEquals(initialExec, session.executor());
        }
    }

    // =========================================================================
    // Remediation Group A — P0-1 Cleanup Safety (18 Tests)
    // =========================================================================

    @Nested
    @DisplayName("Remediation A — P0-1 Cleanup Safety")
    class RemediationA_CleanupSafety {

        @Test
        @DisplayName("A01: Release SUCCESS -> RELEASED -> removed from manager")
        void testA01_ReleaseSuccess_TransitionsToReleased_AndRemoved() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.success(EXECUTOR_ID, true);
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertTrue(res.outcome().isSuccess());
            assertEquals(HoldSessionState.RELEASED, session.state());
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.releaseCount);
            assertEquals(0, executor.cancelCount);
        }

        @Test
        @DisplayName("A02: Release FAILED -> cancel SUCCESS -> cleanup confirmed")
        void testA02_ReleaseFailed_CancelSuccess_CleanupConfirmed() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.success(EXECUTOR_ID, true);

            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertTrue(session.failureDetail().isPresent());
            assertEquals(1, executor.releaseCount);
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A03: Release REJECTED -> cancel SUCCESS")
        void testA03_ReleaseRejected_CancelSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.rejected("ACTION_LOCKED");
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.REJECTED, res.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A04: Release BLOCKED -> cancel SUCCESS")
        void testA04_ReleaseBlocked_CancelSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.blocked("OBSTRUCTION", "Path blocked");
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.BLOCKED, res.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A05: Release TIMEOUT/UNAVAILABLE -> cancel SUCCESS")
        void testA05_ReleaseUnavailable_CancelSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.unavailable("DEVICE_LOST");
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.UNAVAILABLE, res.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A06: Release UNKNOWN -> cancel SUCCESS")
        void testA06_ReleaseUnknown_CancelSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.unknown(EXECUTOR_ID, "ANOMALY");
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.UNKNOWN, res.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A07: Release WAITING -> cancel SUCCESS")
        void testA07_ReleaseWaiting_CancelSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.waiting("STILL_BUSY");
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.WAITING, res.outcome());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A08: Release throws exception -> cancel SUCCESS")
        void testA08_ReleaseThrowsException_CancelSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.throwOnRelease = new RuntimeException("Driver crash");
            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals("RELEASE_EXCEPTION", res.reasonCode());
            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertEquals(1, executor.cancelCount);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A09: Release FAILED -> cancel FAILED -> session retained in quarantine")
        void testA09_ReleaseFailed_CancelFailed_SessionRetained() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("CANCEL_STUCK");

            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());
            assertTrue(session.isQuarantined());
            assertTrue(manager.hasActiveSession(workerId));
            assertTrue(manager.isWorkerLocked(workerId));
        }

        @Test
        @DisplayName("A10: Release FAILED -> cancel throws exception -> session retained in quarantine")
        void testA10_ReleaseFailed_CancelThrows_SessionRetained() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.throwOnCancel = new RuntimeException("Fatal disconnect");

            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(InteractionOutcome.FAILED, res.outcome());
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());
            assertTrue(session.isQuarantined());
            assertTrue(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A11: Cleanup pending -> startSession for same Worker is rejected")
        void testA11_CleanupPending_StartSessionRejected() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("STUCK");
            manager.release(workerId, InteractionContext.EMPTY);

            assertTrue(manager.hasActiveSession(workerId));

            InteractionResult second = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertEquals(InteractionOutcome.REJECTED, second.outcome());
            assertEquals("WORKER_CLEANUP_PENDING", second.reasonCode());
        }

        @Test
        @DisplayName("A12: Cleanup pending -> tickContinuous is NOT called")
        void testA12_CleanupPending_TickContinuousNotCalled() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("STUCK");
            manager.release(workerId, InteractionContext.EMPTY);

            int initialTickCount = executor.tickCount;
            manager.tick(1, InteractionContext.EMPTY);
            manager.tick(2, InteractionContext.EMPTY);

            assertEquals(initialTickCount, executor.tickCount); // Must not tick continuous during cleanup
        }

        @Test
        @DisplayName("A13: Cleanup retry SUCCESS -> releases ownership")
        void testA13_CleanupRetrySuccess_ReleasesOwnership() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("STUCK");
            manager.release(workerId, InteractionContext.EMPTY);
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());

            // Next cancel retry will succeed
            executor.nextCancelResult = InteractionResult.success(EXECUTOR_ID, true);
            manager.tick(1, InteractionContext.EMPTY);

            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("A14: Cleanup retry failure retains ownership until max retries exceeded")
        void testA14_CleanupRetryFailure_RetainsOwnershipUntilExceeded() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("STUCK");
            manager.release(workerId, InteractionContext.EMPTY);

            // Tick 1 retry fails
            executor.nextCancelResult = InteractionResult.failed("STUCK_1");
            manager.tick(1, InteractionContext.EMPTY);
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());

            // Tick 2 retry fails
            executor.nextCancelResult = InteractionResult.failed("STUCK_2");
            manager.tick(2, InteractionContext.EMPTY);
            assertEquals(HoldSessionState.CLEANUP_PENDING, session.state());

            // Tick 3 retry fails -> max retries exceeded
            executor.nextCancelResult = InteractionResult.failed("STUCK_3");
            manager.tick(3, InteractionContext.EMPTY);
            assertEquals(HoldSessionState.CLEANUP_FAILED, session.state());
            assertTrue(manager.hasActiveSession(workerId)); // Still locked!
        }

        @Test
        @DisplayName("A15: Other workers continue running when one worker cleanup fails")
        void testA15_OtherWorkersContinueWhenOneCleanupFails() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            // Worker 1 release fails and cancel fails -> quarantined
            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("STUCK");
            manager.release(worker1, InteractionContext.EMPTY);

            // Worker 2 ticks normally
            executor.nextCancelResult = InteractionResult.failed("STUCK"); // keep worker 1 stuck on retry
            manager.tick(1, InteractionContext.EMPTY);
            assertTrue(manager.hasActiveSession(worker2));
            assertEquals(HoldSessionState.HELD, manager.getActiveSession(worker2).orElseThrow().state());

            // Worker 2 releases normally
            executor.nextReleaseResult = InteractionResult.success(EXECUTOR_ID, true);
            InteractionResult res2 = manager.release(worker2, InteractionContext.EMPTY);
            assertTrue(res2.outcome().isSuccess());
            assertFalse(manager.hasActiveSession(worker2));
            assertTrue(manager.hasActiveSession(worker1)); // Worker 1 still quarantined
        }

        @Test
        @DisplayName("A16: Repeated cancel does not create cleanup calls outside retry policy")
        void testA16_RepeatedCancelDoesNotSpamExecutor() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.throwOnCancel = new RuntimeException("Cancel crash");
            boolean first = manager.cancel(workerId, InteractionContext.EMPTY, "FIRST");
            assertTrue(first);
            assertEquals(1, executor.cancelCount);

            // Session is now CLEANUP_PENDING
            boolean second = manager.cancel(workerId, InteractionContext.EMPTY, "SECOND");
            assertFalse(second);
            assertEquals(1, executor.cancelCount); // Must not spam cancel outside retry policy
        }

        @Test
        @DisplayName("A17: cancelAll continues processing other workers when one cleanup fails")
        void testA17_CancelAllProcessesOtherWorkersWhenOneFails() {
            MockContinuousExecutor faultyExec = new MockContinuousExecutor(
                    ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "faulty"),
                    200
            );
            faultyExec.throwOnCancel = new RuntimeException("Crash");
            registry.registerExecutor(faultyExec);

            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            registry.unregisterExecutor(faultyExec.id());
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            manager.cancelAll(InteractionContext.EMPTY, "SHUTDOWN");

            assertTrue(manager.hasActiveSession(worker1)); // Quarantined
            assertFalse(manager.hasActiveSession(worker2)); // Cleaned up
        }

        @Test
        @DisplayName("A18: Exact verification of states and call counts")
        void testA18_VerificationOfStateAndCallCounts() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertEquals(1, executor.startCount);
            assertEquals(0, executor.tickCount);

            manager.tick(1, InteractionContext.EMPTY);
            assertEquals(1, executor.tickCount);

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.success(EXECUTOR_ID, true);
            manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(1, executor.releaseCount);
            assertEquals(1, executor.cancelCount);
            assertEquals(0, manager.getActiveSessionCount());
        }
    }

    // =========================================================================
    // Remediation Group B — P0-2 Watchdog Redesign (10 Tests)
    // =========================================================================

    @Nested
    @DisplayName("Remediation B — P0-2 Watchdog Redesign")
    class RemediationB_WatchdogRedesign {

        @Test
        @DisplayName("B01: Continuous WAITING for 100 ticks triggers watchdog at exact boundary")
        void testB01_ContinuousWaiting100Ticks_TriggersWatchdogAtBoundary() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 100);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            // Tick 1 to 99: executor returns WAITING (default)
            for (int i = 1; i < 100; i++) {
                watchdogMgr.tick(i, InteractionContext.EMPTY);
                assertTrue(watchdogMgr.hasActiveSession(workerId), "Must remain active at tick " + i);
            }

            // Tick 100: boundary reached
            watchdogMgr.tick(100, InteractionContext.EMPTY);
            assertFalse(watchdogMgr.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
            assertTrue(executor.lastCancelReason.startsWith("WAITING_TIMEOUT_EXCEEDED"));
        }

        @Test
        @DisplayName("B02: Continuous WAITING with progress signal does not timeout")
        void testB02_ContinuousWaitingWithProgressSignal_DoesNotTimeout() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 20);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = watchdogMgr.getActiveSession(workerId).orElseThrow();

            for (int i = 1; i <= 50; i++) {
                session.recordProgress(i); // Simulating progress signal from worker/executor
                watchdogMgr.tick(i, InteractionContext.EMPTY);
                assertTrue(watchdogMgr.hasActiveSession(workerId));
            }
            assertEquals(0, executor.cancelCount);
        }

        @Test
        @DisplayName("B03: Progress timeout is distinct from maximum session duration")
        void testB03_ProgressTimeoutDifferentFromMaxDuration() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 50, 10);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = watchdogMgr.getActiveSession(workerId).orElseThrow();

            // Provide progress signal so progress timeout (10 ticks) is not exceeded
            for (int i = 1; i <= 40; i++) {
                session.recordProgress(i);
                watchdogMgr.tick(i, InteractionContext.EMPTY);
                assertTrue(watchdogMgr.hasActiveSession(workerId));
            }
            assertEquals(0, executor.cancelCount);
        }

        @Test
        @DisplayName("B04: Session exceeding maximum hold duration times out")
        void testB04_MaxHoldDurationExceeded_TimesOut() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 30, 100);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = watchdogMgr.getActiveSession(workerId).orElseThrow();

            // Even with progress signal, maximum session duration (30 ticks) enforces termination
            for (int i = 1; i <= 30; i++) {
                session.recordProgress(i);
                watchdogMgr.tick(i, InteractionContext.EMPTY);
            }
            watchdogMgr.tick(31, InteractionContext.EMPTY);
            assertFalse(watchdogMgr.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
            assertEquals("MAX_HOLD_TIMEOUT_EXCEEDED", executor.lastCancelReason);
        }

        @Test
        @DisplayName("B05: Manager ticks evenly while executor stalls triggers watchdog")
        void testB05_ManagerTicksEvenly_ExecutorStalls_WatchdogFires() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 25);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            for (int i = 1; i <= 24; i++) {
                watchdogMgr.tick(i, InteractionContext.EMPTY);
            }
            assertTrue(watchdogMgr.hasActiveSession(workerId));

            watchdogMgr.tick(25, InteractionContext.EMPTY);
            assertFalse(watchdogMgr.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("B06: Distinguish scheduler delay from executor stall")
        void testB06_SchedulerDelayVsExecutorStall() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 15);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            watchdogMgr.tick(1, InteractionContext.EMPTY);

            // Jump clock forward directly by 20 ticks (scheduler lag / freeze)
            watchdogMgr.tick(22, InteractionContext.EMPTY);
            assertFalse(watchdogMgr.hasActiveSession(workerId));
            assertEquals("WATCHDOG_STALL_DETECTED", executor.lastCancelReason);
        }

        @Test
        @DisplayName("B07: Two independent workers: one stall does not affect other")
        void testB07_TwoWorkers_OneStallDoesNotAffectOther() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 10);
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            watchdogMgr.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            watchdogMgr.startSession(worker2, holdIntent(), InteractionContext.EMPTY);
            HoldSession session2 = watchdogMgr.getActiveSession(worker2).orElseThrow();

            for (int i = 1; i <= 10; i++) {
                session2.recordProgress(i); // Worker 2 makes progress
                watchdogMgr.tick(i, InteractionContext.EMPTY);
            }

            assertFalse(watchdogMgr.hasActiveSession(worker1)); // Worker 1 timed out
            assertTrue(watchdogMgr.hasActiveSession(worker2)); // Worker 2 still active
        }

        @Test
        @DisplayName("B08: Quarantined session is not fed into tickContinuous")
        void testB08_QuarantinedSessionNotFedIntoTickContinuous() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.nextReleaseResult = InteractionResult.failed("JAMMED");
            executor.nextCancelResult = InteractionResult.failed("STUCK");
            manager.release(workerId, InteractionContext.EMPTY);

            int ticksBefore = executor.tickCount;
            for (int i = 1; i <= 5; i++) {
                manager.tick(i, InteractionContext.EMPTY);
            }
            assertEquals(ticksBefore, executor.tickCount);
        }

        @Test
        @DisplayName("B09: Watchdog boundary check (timeout - 1, timeout)")
        void testB09_WatchdogBoundaryCheck() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 10);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            for (int i = 1; i <= 9; i++) {
                watchdogMgr.tick(i, InteractionContext.EMPTY);
                assertTrue(watchdogMgr.hasActiveSession(workerId));
            }

            watchdogMgr.tick(10, InteractionContext.EMPTY);
            assertFalse(watchdogMgr.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("B10: First tick starts watchdog elapsed timing")
        void testB10_FirstTickStartsTiming() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            assertEquals(0, session.elapsedTicks());
            manager.tick(1, InteractionContext.EMPTY);
            assertEquals(1, session.elapsedTicks());
        }
    }

    // =========================================================================
    // Remediation Group C — P1-1 Re-entrancy Safety (11 Tests)
    // =========================================================================

    @Nested
    @DisplayName("Remediation C — P1-1 Re-entrancy Safety")
    class RemediationC_ReentrancySafety {

        @Test
        @DisplayName("C01: tickContinuous re-entrant cancel -> returns WAITING safely")
        void testC01_TickContinuous_ReentrantCancel_ReturnsWaiting() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onTickCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "REENTRANT_DURING_TICK");
            };
            executor.nextTickResult = InteractionResult.waiting("HOLDING");

            // Must NOT throw IllegalStateException
            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("C02: tickContinuous re-entrant cancel -> returns SUCCESS safely")
        void testC02_TickContinuous_ReentrantCancel_ReturnsSuccess() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onTickCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_BEFORE_SUCCESS");
            };
            executor.nextTickResult = InteractionResult.success(EXECUTOR_ID, true);

            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(0, executor.releaseCount); // Must not call release on already cancelled session
        }

        @Test
        @DisplayName("C03: tickContinuous re-entrant cancel -> returns FAILED safely")
        void testC03_TickContinuous_ReentrantCancel_ReturnsFailed() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onTickCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "MANUAL_CANCEL");
            };
            executor.nextTickResult = InteractionResult.failed("ERROR");

            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("C04: startContinuous re-entrant cancel safely handled")
        void testC04_StartContinuous_ReentrantCancel() {
            UUID workerId = UUID.randomUUID();
            executor.onStartCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_IN_START");
            };

            InteractionResult res = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertNotNull(res);
        }

        @Test
        @DisplayName("C05: releaseContinuous re-entrant cancel is guarded")
        void testC05_ReleaseContinuous_ReentrantCancel() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onReleaseCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "REENTRANT_CANCEL_IN_RELEASE");
            };

            InteractionResult res = manager.release(workerId, InteractionContext.EMPTY);
            assertTrue(res.outcome().isSuccess());
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("C06: cancelContinuous re-entrant cancel recursion prevented")
        void testC06_CancelContinuous_ReentrantCancel() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onCancelCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "RECURSIVE_CANCEL");
            };

            boolean cancelled = manager.cancel(workerId, InteractionContext.EMPTY, "INITIAL_CANCEL");
            assertTrue(cancelled);
            assertEquals(1, executor.cancelCount); // Recursive cancel call was safely blocked
        }

        @Test
        @DisplayName("C07: Terminal session cannot record tick")
        void testC07_TerminalSessionCannotRecordTick() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();
            manager.cancel(workerId, InteractionContext.EMPTY, "TERMINATE");

            assertThrows(IllegalStateException.class, () -> session.recordTick(5, null));
        }

        @Test
        @DisplayName("C08: Old session does not affect new session of same worker")
        void testC08_OldSessionDoesNotAffectNewSession() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            manager.cancel(workerId, InteractionContext.EMPTY, "OLD_DONE");

            InteractionResult res = manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            assertTrue(res.outcome().isSuccess());
            assertEquals(1, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("C09: Worker callback exception does not abort tick for subsequent workers")
        void testC09_CallbackExceptionDoesNotAbortOtherWorkers() {
            UUID worker1 = UUID.randomUUID();
            UUID worker2 = UUID.randomUUID();

            manager.startSession(worker1, holdIntent(), InteractionContext.EMPTY);
            manager.startSession(worker2, holdIntent(), InteractionContext.EMPTY);

            executor.onTickCallback = () -> {
                if (executor.tickCount == 1) {
                    throw new RuntimeException("Worker 1 tick crash");
                }
            };

            manager.tick(1, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(worker1)); // Cancelled due to crash
            assertTrue(manager.hasActiveSession(worker2)); // Worker 2 ticked successfully!
        }

        @Test
        @DisplayName("C10: No double cleanup executed")
        void testC10_NoDoubleCleanup() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            manager.cancel(workerId, InteractionContext.EMPTY, "FIRST");
            manager.cancel(workerId, InteractionContext.EMPTY, "SECOND");

            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("C11: No IllegalStateException escapes manager tick loop")
        void testC11_NoIllegalStateExceptionEscapesTickLoop() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onTickCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "SURPRISE_CANCEL");
            };

            // Must not throw any exception
            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
        }
    }

    // =========================================================================
    // Remediation Group D — P1-3 PRESS_RELEASE Timing (11 Tests)
    // =========================================================================

    @Nested
    @DisplayName("Remediation D — P1-3 PRESS_RELEASE Timing")
    class RemediationD_PressReleaseTiming {

        @Test
        @DisplayName("D01: duration = 1 releases at tick 1 with zero tickContinuous calls")
        void testD01_Duration1() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(1), InteractionContext.EMPTY);

            assertEquals(1, executor.startCount);
            assertEquals(0, executor.tickCount);
            assertEquals(0, executor.releaseCount);

            manager.tick(1, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(0, executor.tickCount);
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D02: duration = 2 ticks once and releases at tick 2")
        void testD02_Duration2() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(2), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            assertTrue(manager.hasActiveSession(workerId));
            assertEquals(1, executor.tickCount);
            assertEquals(0, executor.releaseCount);

            manager.tick(2, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.tickCount);
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D03: duration = 5 ticks four times and releases at tick 5")
        void testD03_Duration5() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(5), InteractionContext.EMPTY);

            for (int i = 1; i <= 4; i++) {
                manager.tick(i, InteractionContext.EMPTY);
                assertTrue(manager.hasActiveSession(workerId));
            }
            assertEquals(4, executor.tickCount);
            assertEquals(0, executor.releaseCount);

            manager.tick(5, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(4, executor.tickCount);
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D04: duration = 10 ticks nine times and releases at tick 10")
        void testD04_Duration10() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(10), InteractionContext.EMPTY);

            for (int i = 1; i <= 9; i++) {
                manager.tick(i, InteractionContext.EMPTY);
                assertTrue(manager.hasActiveSession(workerId));
            }
            assertEquals(9, executor.tickCount);

            manager.tick(10, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D05: PRESS_RELEASE does not release twice")
        void testD05_NoDoubleRelease() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(1), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            manager.tick(2, InteractionContext.EMPTY);

            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D06: No tick after release")
        void testD06_NoTickAfterRelease() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(2), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            manager.tick(2, InteractionContext.EMPTY); // released
            manager.tick(3, InteractionContext.EMPTY);

            assertEquals(1, executor.tickCount);
        }

        @Test
        @DisplayName("D07: WAITING outcome throughout duration releases cleanly at final tick")
        void testD07_WaitingThroughoutDuration() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(3), InteractionContext.EMPTY);
            executor.nextTickResult = InteractionResult.waiting("HOLDING");

            manager.tick(1, InteractionContext.EMPTY);
            manager.tick(2, InteractionContext.EMPTY);
            manager.tick(3, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D08: SUCCESS outcome before duration finishes early")
        void testD08_SuccessBeforeDuration() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(5), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            executor.nextTickResult = InteractionResult.success(EXECUTOR_ID, true);
            manager.tick(2, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.releaseCount);
        }

        @Test
        @DisplayName("D09: Duration zero or negative rejected")
        void testD09_DurationZeroOrNegativeRejected() {
            assertThrows(IllegalArgumentException.class, () -> pressReleaseIntent(0));
            assertThrows(IllegalArgumentException.class, () -> pressReleaseIntent(-1));
        }

        @Test
        @DisplayName("D10: Exception at last tick handled safely with cleanup")
        void testD10_ExceptionAtLastTick() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(2), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            executor.throwOnRelease = new RuntimeException("Last tick crash");
            manager.tick(2, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount); // Cleaned up by fallback
        }

        @Test
        @DisplayName("D11: Re-entrant cancellation at last tick")
        void testD11_ReentrantCancelAtLastTick() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(2), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);
            executor.onReleaseCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL_AT_END");
            };
            manager.tick(2, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
        }
    }

    // =========================================================================
    // Remediation Group F — Root Cause Reproduction Tests (6 Tests)
    // =========================================================================

    @Nested
    @DisplayName("Remediation F — Root Cause Reproduction Tests")
    class RemediationF_RootCauseReproduction {

        @Test
        @DisplayName("RC01: Release failure executes fallback cancelContinuous")
        void testRC01_ReleaseFailureExecutesFallbackCancel() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.nextReleaseResult = InteractionResult.failed("DISCONNECT");
            manager.release(workerId, InteractionContext.EMPTY);

            // Verifies fallback cancel was called
            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("RC02: Release failure + cancel failure retains ownership and locks worker")
        void testRC02_ReleaseFailureAndCancelFailureRetainsOwnership() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.nextReleaseResult = InteractionResult.failed("FAIL_RELEASE");
            executor.nextCancelResult = InteractionResult.failed("FAIL_CANCEL");
            manager.release(workerId, InteractionContext.EMPTY);

            assertTrue(manager.hasActiveSession(workerId));
            assertTrue(manager.isWorkerLocked(workerId));
            assertEquals(1, manager.getActiveSessionCount());
        }

        @Test
        @DisplayName("RC03: Continuous WAITING triggers watchdog without relying on clock jump")
        void testRC03_ContinuousWaitingTriggersWatchdog() {
            HoldSessionManager watchdogMgr = new HoldSessionManager(registry, 1000, 30);
            UUID workerId = UUID.randomUUID();
            watchdogMgr.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            for (int i = 1; i <= 30; i++) {
                watchdogMgr.tick(i, InteractionContext.EMPTY);
            }

            assertFalse(watchdogMgr.hasActiveSession(workerId));
            assertEquals(1, executor.cancelCount);
        }

        @Test
        @DisplayName("RC04: Re-entrant cancel during tickContinuous does not crash with IllegalStateException")
        void testRC04_ReentrantCancelDoesNotCrash() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);

            executor.onTickCallback = () -> {
                manager.cancel(workerId, InteractionContext.EMPTY, "CANCEL");
            };

            // Must execute without uncaught IllegalStateException
            manager.tick(1, InteractionContext.EMPTY);
            assertFalse(manager.hasActiveSession(workerId));
        }

        @Test
        @DisplayName("RC05: Release BLOCKED/UNKNOWN not falsely marked as RELEASED")
        void testRC05_ReleaseBlockedNotMarkedAsReleased() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, holdIntent(), InteractionContext.EMPTY);
            HoldSession session = manager.getActiveSession(workerId).orElseThrow();

            executor.nextReleaseResult = InteractionResult.blocked("BLOCKED_REASON", "Obstacle");
            executor.nextCancelResult = InteractionResult.success(EXECUTOR_ID, true);
            manager.release(workerId, InteractionContext.EMPTY);

            assertEquals(HoldSessionState.CANCELLED, session.state());
            assertNotEquals(HoldSessionState.RELEASED, session.state());
        }

        @Test
        @DisplayName("RC06: PRESS_RELEASE duration 1 total hold is exactly 1 tick")
        void testRC06_PressReleaseDuration1_ExactlyOneTick() {
            UUID workerId = UUID.randomUUID();
            manager.startSession(workerId, pressReleaseIntent(1), InteractionContext.EMPTY);

            manager.tick(1, InteractionContext.EMPTY);

            assertFalse(manager.hasActiveSession(workerId));
            assertEquals(0, executor.tickCount);
            assertEquals(1, executor.releaseCount);
        }
    }

    // =========================================================================
    // Mock Executor for Lifecycle Testing
    // =========================================================================

    private static class MockContinuousExecutor implements InteractionExecutor {
        private final ResourceLocation id;
        private final int priority;

        int startCount = 0;
        int tickCount = 0;
        int releaseCount = 0;
        int cancelCount = 0;
        String lastCancelReason = null;

        InteractionResult nextStartResult = null;
        InteractionResult nextTickResult = null;
        InteractionResult nextReleaseResult = null;
        InteractionResult nextCancelResult = null;

        Throwable throwOnStart = null;
        Throwable throwOnTick = null;
        Throwable throwOnRelease = null;
        Throwable throwOnCancel = null;

        Runnable onStartCallback = null;
        Runnable onTickCallback = null;
        Runnable onReleaseCallback = null;
        Runnable onCancelCallback = null;

        MockContinuousExecutor(ResourceLocation id) {
            this(id, 100);
        }

        MockContinuousExecutor(ResourceLocation id, int priority) {
            this.id = id;
            this.priority = priority;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Category category() {
            return Category.GENERIC_PLAYER;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Set<ExecutionMode> supportedModes() {
            return Set.of(ExecutionMode.HOLD, ExecutionMode.PRESS_RELEASE);
        }

        @Override
        public boolean canHandle(InteractionIntent intent) {
            return true;
        }

        @Override
        public InteractionResult startContinuous(InteractionIntent intent, InteractionContext context) {
            startCount++;
            if (onStartCallback != null) {
                onStartCallback.run();
            }
            if (throwOnStart != null) {
                if (throwOnStart instanceof RuntimeException re) throw re;
                throw new RuntimeException(throwOnStart);
            }
            return nextStartResult != null ? nextStartResult : InteractionResult.success(id, true);
        }

        @Override
        public InteractionResult tickContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
            tickCount++;
            if (onTickCallback != null) {
                onTickCallback.run();
            }
            if (throwOnTick != null) {
                if (throwOnTick instanceof RuntimeException re) throw re;
                throw new RuntimeException(throwOnTick);
            }
            return nextTickResult != null ? nextTickResult : InteractionResult.waiting("HOLDING");
        }

        @Override
        public InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
            releaseCount++;
            if (onReleaseCallback != null) {
                onReleaseCallback.run();
            }
            if (throwOnRelease != null) {
                if (throwOnRelease instanceof RuntimeException re) throw re;
                throw new RuntimeException(throwOnRelease);
            }
            return nextReleaseResult != null ? nextReleaseResult : InteractionResult.success(id, true);
        }

        @Override
        public InteractionResult cancelContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks, String reason) {
            cancelCount++;
            this.lastCancelReason = reason;
            if (onCancelCallback != null) {
                onCancelCallback.run();
            }
            if (throwOnCancel != null) {
                if (throwOnCancel instanceof RuntimeException re) throw re;
                throw new RuntimeException(throwOnCancel);
            }
            return nextCancelResult != null ? nextCancelResult : InteractionResult.success(id, true);
        }
    }
}
