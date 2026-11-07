package com.example.examplemod;

/**
 * Rich outcome classification for interaction operations.
 */
public enum InteractionOutcome {
    SUCCESS,
    FAILED,
    BLOCKED,
    WAITING,
    UNSUPPORTED,
    UNAVAILABLE,
    REJECTED,
    RETRYABLE,
    UNKNOWN;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isTerminal() {
        return this != WAITING && this != RETRYABLE;
    }
}
