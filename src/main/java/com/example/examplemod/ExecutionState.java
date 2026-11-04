package com.example.examplemod;

import com.mojang.serialization.Codec;

/**
 * Execution states for worker jobs, job steps, and job queues.
 */
public enum ExecutionState {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    PAUSED;

    public static final Codec<ExecutionState> CODEC = Codec.STRING.xmap(
            ExecutionState::valueOf,
            ExecutionState::name
    );

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isActive() {
        return this == RUNNING || this == WAITING;
    }
}
