package com.example.examplemod;

/**
 * State machine for continuous interaction sessions (HOLD / PRESS_RELEASE).
 */
public enum HoldSessionState {
    /** Session created but startContinuous has not yet executed or completed. */
    IDLE,
    /** startContinuous succeeded; button press initiated on server. */
    PRESSED,
    /** Button press confirmed active across ticks. */
    HELD,
    /** Session completed and button released normally. Terminal state. */
    RELEASED,
    /** Session aborted, interrupted, timed out, or cancelled and cleanup confirmed. Terminal state. */
    CANCELLED,
    /** Release or cancellation failed; cleanup fallback is pending retry. Worker remains locked. */
    CLEANUP_PENDING,
    /** Cleanup retries exhausted without confirmed release. Worker remains quarantined. */
    CLEANUP_FAILED;

    public boolean isTerminal() {
        return this == RELEASED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == PRESSED || this == HELD;
    }

    public boolean isQuarantined() {
        return this == CLEANUP_PENDING || this == CLEANUP_FAILED;
    }

    public boolean canTransitionTo(HoldSessionState next) {
        if (next == null) {
            return false;
        }
        if (this == next) {
            // HELD, CLEANUP_PENDING, CLEANUP_FAILED can remain in current state
            return this == HELD || this == CLEANUP_PENDING || this == CLEANUP_FAILED;
        }
        return switch (this) {
            case IDLE -> next == PRESSED || next == CANCELLED || next == CLEANUP_PENDING;
            case PRESSED -> next == HELD || next == RELEASED || next == CANCELLED || next == CLEANUP_PENDING;
            case HELD -> next == RELEASED || next == CANCELLED || next == CLEANUP_PENDING;
            case CLEANUP_PENDING -> next == CANCELLED || next == CLEANUP_FAILED;
            case CLEANUP_FAILED -> next == CANCELLED;
            case RELEASED, CANCELLED -> false; // Terminal states are final
        };
    }
}
