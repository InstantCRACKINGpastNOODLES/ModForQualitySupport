package com.example.examplemod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manager and coordinator for continuous interaction sessions (HOLD / PRESS_RELEASE).
 * Enforces one active session per Worker, lifecycle transitions, timeout/watchdog safety,
 * exception-safe cleanup, quarantine handling, and executor coordination.
 * <p>
 * Server-authoritative: designed for execution on the server thread without client-authoritative state.
 */
public class HoldSessionManager {

    public static final int DEFAULT_MAX_HOLD_TICKS = 1200;
    public static final int DEFAULT_MAX_WAITING_TICKS = 60;
    public static final int DEFAULT_STALL_WATCHDOG_TICKS = 60;
    public static final int DEFAULT_MAX_CLEANUP_RETRIES = 3;

    public record CleanupDiagnosticEvent(
            UUID sessionId,
            UUID workerId,
            net.minecraft.resources.ResourceLocation executorId,
            InteractionTarget target,
            HoldSessionState lastState,
            String failureReason,
            int retryCount,
            boolean quarantined
    ) {}

    @FunctionalInterface
    public interface CleanupDiagnosticListener {
        void onCleanupDiagnostic(CleanupDiagnosticEvent event);
    }

    private final WorkerInteractionRegistry registry;
    private final int defaultMaxHoldTicks;
    private final int maxWaitingTicks;
    private final int maxCleanupRetries;

    private final Map<UUID, HoldSession> activeSessions = new LinkedHashMap<>();
    private final Set<UUID> tickedThisTick = new HashSet<>();
    private final List<CleanupDiagnosticListener> cleanupListeners = new CopyOnWriteArrayList<>();
    private int currentManagerTick = 0;

    public void addCleanupDiagnosticListener(CleanupDiagnosticListener listener) {
        if (listener != null) {
            cleanupListeners.add(listener);
        }
    }

    public boolean removeCleanupDiagnosticListener(CleanupDiagnosticListener listener) {
        return cleanupListeners.remove(listener);
    }

    private void notifyCleanupFailure(HoldSession session, String reason) {
        if (cleanupListeners.isEmpty()) return;
        CleanupDiagnosticEvent event = new CleanupDiagnosticEvent(
                session.sessionId(),
                session.workerId(),
                session.executorId(),
                session.intent().target(),
                session.state(),
                reason,
                session.cleanupRetryCount(),
                session.isQuarantined()
        );
        for (CleanupDiagnosticListener listener : cleanupListeners) {
            try {
                listener.onCleanupDiagnostic(event);
            } catch (Throwable ignored) {
                // Diagnostic listener exception MUST NEVER corrupt cleanup or unlock worker
            }
        }
    }

    public synchronized boolean signalProgress(UUID workerId) {
        HoldSession session = activeSessions.get(workerId);
        if (session != null && session.isActive()) {
            session.recordProgress(currentManagerTick);
            return true;
        }
        return false;
    }

    public HoldSessionManager(
            WorkerInteractionRegistry registry,
            int defaultMaxHoldTicks,
            int maxWaitingTicks,
            int maxCleanupRetries
    ) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        if (defaultMaxHoldTicks <= 0) {
            throw new IllegalArgumentException("defaultMaxHoldTicks must be positive, got: " + defaultMaxHoldTicks);
        }
        if (maxWaitingTicks <= 0) {
            throw new IllegalArgumentException("maxWaitingTicks must be positive, got: " + maxWaitingTicks);
        }
        if (maxCleanupRetries < 0) {
            throw new IllegalArgumentException("maxCleanupRetries must be non-negative, got: " + maxCleanupRetries);
        }
        this.defaultMaxHoldTicks = defaultMaxHoldTicks;
        this.maxWaitingTicks = maxWaitingTicks;
        this.maxCleanupRetries = maxCleanupRetries;
    }

    public HoldSessionManager(WorkerInteractionRegistry registry, int defaultMaxHoldTicks, int maxWaitingTicks) {
        this(registry, defaultMaxHoldTicks, maxWaitingTicks, DEFAULT_MAX_CLEANUP_RETRIES);
    }

    public HoldSessionManager(WorkerInteractionRegistry registry, int defaultMaxHoldTicks) {
        this(registry, defaultMaxHoldTicks, DEFAULT_MAX_WAITING_TICKS, DEFAULT_MAX_CLEANUP_RETRIES);
    }

    public HoldSessionManager(WorkerInteractionRegistry registry) {
        this(registry, DEFAULT_MAX_HOLD_TICKS, DEFAULT_MAX_WAITING_TICKS, DEFAULT_MAX_CLEANUP_RETRIES);
    }

    public int defaultMaxHoldTicks() {
        return defaultMaxHoldTicks;
    }

    public int stallWatchdogTicks() {
        return maxWaitingTicks;
    }

    public int maxWaitingTicks() {
        return maxWaitingTicks;
    }

    public int maxCleanupRetries() {
        return maxCleanupRetries;
    }

    public synchronized int currentTick() {
        return currentManagerTick;
    }

    public synchronized int getActiveSessionCount() {
        return (int) activeSessions.values().stream().filter(s -> !s.isTerminal()).count();
    }

    public synchronized boolean hasActiveSession(UUID workerId) {
        if (workerId == null) return false;
        HoldSession session = activeSessions.get(workerId);
        return session != null && !session.isTerminal();
    }

    public synchronized Optional<HoldSession> getActiveSession(UUID workerId) {
        if (workerId == null) return Optional.empty();
        HoldSession session = activeSessions.get(workerId);
        if (session != null && !session.isTerminal()) {
            return Optional.of(session);
        }
        return Optional.empty();
    }

    public synchronized boolean isWorkerLocked(UUID workerId) {
        return hasActiveSession(workerId);
    }

    public synchronized InteractionResult startSession(UUID workerId, InteractionIntent intent, InteractionContext context) {
        return startSession(workerId, intent, context, RoutingPolicy.GENERIC_FIRST);
    }

    public synchronized InteractionResult startSession(UUID workerId, InteractionIntent intent, InteractionContext context, RoutingPolicy policy) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        // 1. Validate ExecutionMode: Only continuous modes are supported
        if (intent.executionMode() != ExecutionMode.HOLD && intent.executionMode() != ExecutionMode.PRESS_RELEASE) {
            return InteractionResult.unsupported(
                    "UNSUPPORTED_CONTINUOUS_MODE",
                    "HoldSessionManager only supports HOLD and PRESS_RELEASE modes, got: " + intent.executionMode(),
                    null
            );
        }

        if (intent.executionMode() == ExecutionMode.PRESS_RELEASE) {
            if (intent.durationTicks().isEmpty() || intent.durationTicks().get() <= 0) {
                return InteractionResult.rejected(
                        "INVALID_DURATION",
                        "PRESS_RELEASE mode requires positive durationTicks"
                );
            }
        }

        // 2. Validate Worker exclusivity: One active session per Worker
        HoldSession existing = activeSessions.get(workerId);
        if (existing != null) {
            if (!existing.isTerminal()) {
                String code = existing.isQuarantined() ? "WORKER_CLEANUP_PENDING" : "WORKER_BUSY";
                return InteractionResult.rejected(
                        code,
                        "Worker " + workerId + " already has active/quarantined hold session: " + existing.sessionId()
                );
            } else {
                activeSessions.remove(workerId);
            }
        }

        // 3. Resolve Executor
        ResolutionResult resolution = registry.resolve(intent, policy);
        if (!resolution.isResolved()) {
            return InteractionResult.unsupported(resolution.status().name(), resolution.message(), null);
        }
        InteractionExecutor executor = resolution.executor().get();

        // 4. Initiate continuous interaction with exception protection
        InteractionResult startResult;
        try {
            startResult = executor.startContinuous(intent, context);
        } catch (Throwable t) {
            return InteractionResult.failed(
                    "START_EXCEPTION",
                    "Executor threw exception during startContinuous: " + t.getMessage(),
                    executor.id()
            );
        }

        if (startResult == null) {
            return InteractionResult.failed(
                    "NULL_START_RESULT",
                    "Executor returned null result for startContinuous",
                    executor.id()
            );
        }

        // 5. Check outcome: Only SUCCESS or WAITING initiates an active session
        if (startResult.outcome() != InteractionOutcome.SUCCESS && startResult.outcome() != InteractionOutcome.WAITING) {
            return startResult;
        }

        // 6. Create active HoldSession
        int maxHold = intent.durationTicks().orElse(defaultMaxHoldTicks);
        if (intent.executionMode() == ExecutionMode.PRESS_RELEASE) {
            maxHold = intent.durationTicks().get();
        }

        HoldSession session = new HoldSession(
                UUID.randomUUID(),
                workerId,
                intent,
                executor,
                currentManagerTick,
                maxHold,
                startResult
        );

        activeSessions.put(workerId, session);
        return startResult;
    }

    public synchronized void tick(InteractionContext context) {
        tick(currentManagerTick + 1, context);
    }

    public synchronized void tick(int currentTick, InteractionContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (currentTick != this.currentManagerTick) {
            this.currentManagerTick = currentTick;
            this.tickedThisTick.clear();
        }

        List<HoldSession> snapshot = new ArrayList<>(activeSessions.values());
        for (HoldSession session : snapshot) {
            UUID workerId = session.workerId();

            // Guard: Each session ticked at most once per manager tick
            if (tickedThisTick.contains(workerId)) {
                continue;
            }
            if (session.lastExecutorTick() == currentTick && session.elapsedTicks() > 0) {
                continue;
            }
            tickedThisTick.add(workerId);

            if (session.isTerminal()) {
                activeSessions.remove(workerId);
                continue;
            }

            // Quarantine handling: do NOT run continuous interaction; retry cleanup if pending
            if (session.isQuarantined()) {
                if (session.state() == HoldSessionState.CLEANUP_PENDING) {
                    attemptCleanupRetry(session, context);
                }
                continue;
            }

            // Watchdog: detect maximum safety hold timeout
            if (session.elapsedTicks() >= session.maxHoldTicks()) {
                cancelSession(session, context, "MAX_HOLD_TIMEOUT_EXCEEDED");
                continue;
            }

            // Watchdog: detect scheduler delay / clock freeze
            if (currentTick - session.lastExecutorTick() > maxWaitingTicks) {
                cancelSession(session, context, "WATCHDOG_STALL_DETECTED");
                continue;
            }

            // Watchdog: detect consecutive waiting duration stall
            if (session.consecutiveWaitingTicks() >= maxWaitingTicks) {
                cancelSession(session, context, "WAITING_TIMEOUT_EXCEEDED: " + session.consecutiveWaitingTicks() + " ticks waiting without progress");
                continue;
            }

            // Check PRESS_RELEASE duration completion
            if (session.intent().executionMode() == ExecutionMode.PRESS_RELEASE) {
                int expected = session.expectedDurationTicks().orElse(session.maxHoldTicks());
                if (session.elapsedTicks() + 1 >= expected) {
                    session.recordTick(currentTick, null);
                    releaseSession(session, context, "PRESS_RELEASE_DURATION_REACHED");
                    continue;
                }
            }

            // Transition PRESSED -> HELD on continuation
            if (session.state() == HoldSessionState.PRESSED) {
                session.transitionTo(HoldSessionState.HELD, "TICK_CONTINUATION");
            }

            // Invoke executor tick
            int nextElapsed = session.elapsedTicks() + 1;
            InteractionResult tickResult;
            try {
                tickResult = session.executor().tickContinuous(session.intent(), context, nextElapsed);
            } catch (Throwable t) {
                cancelSession(session, context, "TICK_EXCEPTION: " + t.getMessage());
                continue;
            }

            // Re-entrancy verification: session might have been cancelled during tickContinuous
            if (session.isTerminal() || session.isQuarantined() || activeSessions.get(workerId) != session) {
                continue;
            }

            if (tickResult == null) {
                cancelSession(session, context, "NULL_TICK_RESULT");
                continue;
            }

            // Evaluate tick result outcome
            switch (tickResult.outcome()) {
                case WAITING -> {
                    if (tickResult.verified()) {
                        session.recordProgress(currentTick);
                    }
                    session.recordTick(currentTick, tickResult);
                    if (session.consecutiveWaitingTicks() >= maxWaitingTicks) {
                        cancelSession(session, context, "WAITING_TIMEOUT_EXCEEDED: " + session.consecutiveWaitingTicks() + " ticks waiting without progress");
                    }
                }
                case SUCCESS -> {
                    session.recordTick(currentTick, tickResult);
                    releaseSession(session, context, "COMPLETED_SUCCESSFULLY");
                }
                case FAILED -> {
                    cancelSession(session, context, "EXECUTOR_FAILED: " + tickResult.reasonCode());
                }
                case BLOCKED -> {
                    cancelSession(session, context, "BLOCKED: " + tickResult.reasonCode());
                }
                case RETRYABLE -> {
                    session.recordTick(currentTick, tickResult);
                }
                case UNKNOWN -> {
                    cancelSession(session, context, "EXECUTOR_UNKNOWN: " + tickResult.reasonCode());
                }
                case UNSUPPORTED, UNAVAILABLE, REJECTED -> {
                    cancelSession(session, context, "EXECUTOR_TERMINATED: " + tickResult.outcome());
                }
            }
        }
    }

    public synchronized InteractionResult release(UUID workerId, InteractionContext context) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(context, "context must not be null");

        HoldSession session = activeSessions.get(workerId);
        if (session == null || session.isTerminal()) {
            return InteractionResult.rejected("NO_ACTIVE_SESSION", "No active session for worker: " + workerId);
        }

        if (session.isQuarantined()) {
            return InteractionResult.rejected("CLEANUP_PENDING", "Session is quarantined undergoing cleanup for worker: " + workerId);
        }

        return releaseSession(session, context, "CALLER_RELEASE");
    }

    private InteractionResult releaseSession(HoldSession session, InteractionContext context, String reason) {
        if (session.isTerminal()) {
            return session.lastResult();
        }
        if (session.isQuarantined()) {
            return session.lastResult();
        }

        InteractionResult releaseResult;
        try {
            releaseResult = session.executor().releaseContinuous(session.intent(), context, session.elapsedTicks());
        } catch (Throwable t) {
            releaseResult = InteractionResult.failed("RELEASE_EXCEPTION", t.getMessage(), session.executorId());
        }

        if (releaseResult == null) {
            releaseResult = InteractionResult.failed("NULL_RELEASE_RESULT", "Executor returned null release result", session.executorId());
        }

        // Re-entrancy verification
        if (session.isTerminal() || session.isQuarantined() || activeSessions.get(session.workerId()) != session) {
            return releaseResult;
        }

        // Remediation A & P1-2: ONLY SUCCESS is considered a confirmed successful release
        if (releaseResult.outcome() == InteractionOutcome.SUCCESS) {
            session.transitionTo(HoldSessionState.RELEASED, reason);
            activeSessions.remove(session.workerId());
            return releaseResult;
        }

        // Release was unconfirmed / failed -> trigger fallback cleanup via cancelContinuous
        session.recordCleanupFailure("RELEASE_UNSUCCESSFUL: " + releaseResult.outcome() + " - " + releaseResult.reasonCode());

        InteractionResult cancelFallbackResult = null;
        Throwable cancelFallbackEx = null;
        try {
            session.setCancelling(true);
            cancelFallbackResult = session.executor().cancelContinuous(
                    session.intent(),
                    context,
                    session.elapsedTicks(),
                    "RELEASE_FALLBACK: " + releaseResult.reasonCode()
            );
        } catch (Throwable t) {
            cancelFallbackEx = t;
        } finally {
            session.setCancelling(false);
        }

        if (cancelFallbackEx != null) {
            session.recordCleanupFailure("CANCEL_FALLBACK_EXCEPTION: " + cancelFallbackEx.getMessage());
            session.transitionTo(HoldSessionState.CLEANUP_PENDING, "CANCEL_FALLBACK_EXCEPTION: " + cancelFallbackEx.getMessage());
            notifyCleanupFailure(session, "CANCEL_FALLBACK_EXCEPTION: " + cancelFallbackEx.getMessage());
            // Worker remains locked! Session retained in activeSessions
            return releaseResult;
        }

        if (cancelFallbackResult != null && cancelFallbackResult.outcome() == InteractionOutcome.SUCCESS) {
            // Cleanup confirmed via cancel fallback
            session.transitionTo(HoldSessionState.CANCELLED, "RELEASE_FAILED_CANCEL_CONFIRMED: " + releaseResult.reasonCode());
            activeSessions.remove(session.workerId());
            return releaseResult;
        } else {
            // Cancel fallback failed or unconfirmed -> place in quarantine
            String code = cancelFallbackResult != null ? cancelFallbackResult.reasonCode() : "NULL_CANCEL_RESULT";
            session.recordCleanupFailure("CANCEL_FALLBACK_FAILED: " + code);
            session.transitionTo(HoldSessionState.CLEANUP_PENDING, "CANCEL_FALLBACK_FAILED: " + code);
            notifyCleanupFailure(session, "CANCEL_FALLBACK_FAILED: " + code);
            // Worker remains locked! Session retained in activeSessions
            return releaseResult;
        }
    }

    public synchronized boolean cancel(UUID workerId, InteractionContext context, String reason) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        HoldSession session = activeSessions.get(workerId);
        if (session == null || session.isTerminal()) {
            return false;
        }
        if (session.isQuarantined()) {
            return false; // Repeated cancel call outside retry policy rejected
        }
        cancelSession(session, context, reason != null ? reason : "MANUAL_CANCEL");
        return true;
    }

    private void cancelSession(HoldSession session, InteractionContext context, String reason) {
        if (session.isTerminal() || session.isQuarantined()) {
            return;
        }
        if (session.isCancelling()) {
            return;
        }

        session.setCancelling(true);
        InteractionResult cancelResult = null;
        Throwable cancelEx = null;
        try {
            cancelResult = session.executor().cancelContinuous(session.intent(), context, session.elapsedTicks(), reason);
        } catch (Throwable t) {
            cancelEx = t;
        } finally {
            session.setCancelling(false);
        }

        if (cancelEx != null) {
            session.recordCleanupFailure("CANCEL_EXCEPTION: " + cancelEx.getMessage());
            session.transitionTo(HoldSessionState.CLEANUP_PENDING, "CANCEL_EXCEPTION: " + cancelEx.getMessage());
            notifyCleanupFailure(session, "CANCEL_EXCEPTION: " + cancelEx.getMessage());
            // Worker remains locked!
            return;
        }

        if (cancelResult != null && cancelResult.outcome() == InteractionOutcome.SUCCESS) {
            session.transitionTo(HoldSessionState.CANCELLED, reason);
            activeSessions.remove(session.workerId());
        } else {
            String code = cancelResult != null ? cancelResult.reasonCode() : "NULL_CANCEL_RESULT";
            session.recordCleanupFailure("CANCEL_FAILED: " + code);
            session.transitionTo(HoldSessionState.CLEANUP_PENDING, "CANCEL_FAILED: " + code);
            notifyCleanupFailure(session, "CANCEL_FAILED: " + code);
            // Worker remains locked!
        }
    }

    private void attemptCleanupRetry(HoldSession session, InteractionContext context) {
        if (session.cleanupRetryCount() >= maxCleanupRetries) {
            if (session.state() != HoldSessionState.CLEANUP_FAILED) {
                session.transitionTo(HoldSessionState.CLEANUP_FAILED, "MAX_CLEANUP_RETRIES_EXCEEDED");
                notifyCleanupFailure(session, "MAX_CLEANUP_RETRIES_EXCEEDED");
            }
            return;
        }

        session.incrementCleanupRetry();
        InteractionResult retryResult = null;
        Throwable retryEx = null;
        try {
            session.setCancelling(true);
            retryResult = session.executor().cancelContinuous(
                    session.intent(),
                    context,
                    session.elapsedTicks(),
                    "CLEANUP_RETRY_" + session.cleanupRetryCount()
            );
        } catch (Throwable t) {
            retryEx = t;
        } finally {
            session.setCancelling(false);
        }

        if (retryEx == null && retryResult != null && retryResult.outcome() == InteractionOutcome.SUCCESS) {
            session.transitionTo(HoldSessionState.CANCELLED, "CLEANUP_RETRY_SUCCESS");
            activeSessions.remove(session.workerId());
        } else {
            String detail = retryEx != null ? retryEx.getMessage() : (retryResult != null ? retryResult.reasonCode() : "NULL_RESULT");
            session.recordCleanupFailure("RETRY_" + session.cleanupRetryCount() + "_FAILED: " + detail);
            if (session.cleanupRetryCount() >= maxCleanupRetries) {
                session.transitionTo(HoldSessionState.CLEANUP_FAILED, "MAX_CLEANUP_RETRIES_EXCEEDED");
                notifyCleanupFailure(session, "MAX_CLEANUP_RETRIES_EXCEEDED");
            }
        }
    }

    public synchronized boolean cancelForWorker(UUID workerId, String reason) {
        return cancel(workerId, InteractionContext.EMPTY, reason);
    }

    public synchronized void cancelAll(InteractionContext context, String reason) {
        List<HoldSession> snapshot = new ArrayList<>(activeSessions.values());
        for (HoldSession session : snapshot) {
            try {
                cancelSession(session, context, reason != null ? reason : "CANCEL_ALL");
            } catch (Throwable ignored) {
            }
        }
        // Only remove sessions whose cleanup was confirmed (terminal)
        activeSessions.entrySet().removeIf(e -> e.getValue().isTerminal());
    }
}
