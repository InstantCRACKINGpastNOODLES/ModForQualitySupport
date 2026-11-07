package com.example.examplemod;

/**
 * Execution modes describing the temporal characteristic of an interaction.
 */
public enum ExecutionMode {
    /** Execute a single discrete interaction (press and release within one tick). */
    CLICK,
    /** Maintain continuous press state over multiple ticks until released or canceled. */
    HOLD,
    /** Multiple independent discrete clicks with defined interval. */
    REPEAT,
    /** Press, maintain for an explicitly specified tick duration, then release. */
    PRESS_RELEASE;

    public boolean isContinuous() {
        return this == HOLD || this == PRESS_RELEASE;
    }

    public boolean isDiscrete() {
        return this == CLICK || this == REPEAT;
    }
}
