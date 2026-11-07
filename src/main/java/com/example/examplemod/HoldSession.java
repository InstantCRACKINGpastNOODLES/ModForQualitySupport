package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Runtime state representation of an active continuous interaction session (HOLD / PRESS_RELEASE).
 * Not persistent; exists only in server memory for the duration of the hold.
 */
public final class HoldSession {

    private final UUID sessionId;
    private final UUID workerId;
    private final InteractionIntent intent;
    private final InteractionExecutor executor;
    private final int startTick;
    private final Optional<Integer> expectedDurationTicks;
    private final int maxHoldTicks;

    private HoldSessionState state;
    private int elapsedTicks;
    private int lastExecutorTick;
    private int lastProgressTick;
    private int consecutiveWaitingTicks;
    private int cleanupRetryCount;
    private boolean isCancelling = false;
    private InteractionResult lastResult;
    private Optional<String> cancellationReason = Optional.empty();
    private Optional<String> failureDetail = Optional.empty();

    public HoldSession(
            UUID sessionId,
            UUID workerId,
            InteractionIntent intent,
            InteractionExecutor executor,
            int startTick,
            int maxHoldTicks,
            InteractionResult initialResult
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
        this.intent = Objects.requireNonNull(intent, "intent must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        if (maxHoldTicks <= 0) {
            throw new IllegalArgumentException("maxHoldTicks must be positive, got: " + maxHoldTicks);
        }
        this.startTick = startTick;
        this.elapsedTicks = 0;
        this.lastExecutorTick = startTick;
        this.lastProgressTick = startTick;
        this.consecutiveWaitingTicks = 0;
        this.cleanupRetryCount = 0;
        this.maxHoldTicks = maxHoldTicks;
        this.expectedDurationTicks = intent.durationTicks();
        this.state = HoldSessionState.PRESSED;
        this.lastResult = Objects.requireNonNull(initialResult, "initialResult must not be null");
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID workerId() {
        return workerId;
    }

    public InteractionIntent intent() {
        return intent;
    }

    public InteractionExecutor executor() {
        return executor;
    }

    public ResourceLocation executorId() {
        return executor.id();
    }

    public synchronized HoldSessionState state() {
        return state;
    }

    public int startTick() {
        return startTick;
    }

    public synchronized int lastTick() {
        return lastExecutorTick;
    }

    public synchronized int lastExecutorTick() {
        return lastExecutorTick;
    }

    public synchronized int lastProgressTick() {
        return lastProgressTick;
    }

    public synchronized int consecutiveWaitingTicks() {
        return consecutiveWaitingTicks;
    }

    public synchronized int cleanupRetryCount() {
        return cleanupRetryCount;
    }

    public synchronized void incrementCleanupRetry() {
        this.cleanupRetryCount++;
    }

    public synchronized boolean isCancelling() {
        return isCancelling;
    }

    public synchronized void setCancelling(boolean cancelling) {
        this.isCancelling = cancelling;
    }

    public synchronized int elapsedTicks() {
        return elapsedTicks;
    }

    public Optional<Integer> expectedDurationTicks() {
        return expectedDurationTicks;
    }

    public int maxHoldTicks() {
        return maxHoldTicks;
    }

    public synchronized InteractionResult lastResult() {
        return lastResult;
    }

    public synchronized Optional<String> cancellationReason() {
        return cancellationReason;
    }

    public synchronized Optional<String> failureDetail() {
        return failureDetail;
    }

    public synchronized boolean isTerminal() {
        return state.isTerminal();
    }

    public synchronized boolean isActive() {
        return state.isActive();
    }

    public synchronized boolean isQuarantined() {
        return state.isQuarantined();
    }

    public synchronized boolean transitionTo(HoldSessionState nextState, String reason) {
        Objects.requireNonNull(nextState, "nextState must not be null");
        if (!this.state.canTransitionTo(nextState)) {
            return false;
        }
        this.state = nextState;
        if (nextState == HoldSessionState.CANCELLED && reason != null) {
            this.cancellationReason = Optional.of(reason);
        }
        if ((nextState == HoldSessionState.CLEANUP_PENDING || nextState == HoldSessionState.CLEANUP_FAILED) && reason != null) {
            this.failureDetail = Optional.of(reason);
        }
        return true;
    }

    public synchronized void recordProgress(int currentTick) {
        this.lastProgressTick = currentTick;
        this.consecutiveWaitingTicks = 0;
    }

    public synchronized void recordTick(int currentTick, InteractionResult tickResult) {
        if (state.isTerminal()) {
            throw new IllegalStateException("Cannot record tick on terminal session " + sessionId);
        }
        this.elapsedTicks++;
        this.lastExecutorTick = currentTick;
        if (tickResult != null) {
            this.lastResult = tickResult;
            if (tickResult.outcome() == InteractionOutcome.WAITING) {
                this.consecutiveWaitingTicks++;
            } else if (tickResult.outcome().isSuccess()) {
                recordProgress(currentTick);
            }
        }
    }

    public synchronized void recordCleanupFailure(String detail) {
        this.failureDetail = Optional.ofNullable(detail);
    }
}
