package com.example.examplemod;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Architectural contract for interaction executors.
 * Explicitly declares supported modes (CLICK, HOLD, REPEAT, PRESS_RELEASE) and lifecycle methods.
 */
public interface InteractionExecutor {

    enum Category {
        /** Generic Player-like executor. Default route for standard interactions. */
        GENERIC_PLAYER,
        /** Specialized mod adapter. Chosen only when policy or specific routing demands it. */
        SPECIALIZED_ADAPTER,
        /** Capability handler. Used for capability/endpoint operations, never default click executor. */
        CAPABILITY_HANDLER
    }

    ResourceLocation id();

    Category category();

    int priority();

    Set<ExecutionMode> supportedModes();

    default boolean supportsMode(ExecutionMode mode) {
        return supportedModes() != null && supportedModes().contains(mode);
    }

    boolean canHandle(InteractionIntent intent);

    default InteractionResult executeDiscrete(InteractionIntent intent, InteractionContext context) {
        return InteractionResult.unsupported("DISCRETE_NOT_SUPPORTED", "Executor does not support discrete execution", id());
    }

    default InteractionResult startContinuous(InteractionIntent intent, InteractionContext context) {
        return InteractionResult.unsupported("CONTINUOUS_NOT_SUPPORTED", "Executor does not support continuous execution", id());
    }

    default InteractionResult tickContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
        return InteractionResult.unsupported("CONTINUOUS_NOT_SUPPORTED", "Executor does not support continuous execution", id());
    }

    default InteractionResult releaseContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks) {
        return InteractionResult.unsupported("CONTINUOUS_NOT_SUPPORTED", "Executor does not support continuous execution", id());
    }

    default InteractionResult cancelContinuous(InteractionIntent intent, InteractionContext context, int elapsedTicks, String reason) {
        return InteractionResult.unsupported("CONTINUOUS_NOT_SUPPORTED", "Executor does not support continuous execution", id());
    }

    default boolean verify(InteractionIntent intent, InteractionContext context, InteractionResult result) {
        return false;
    }
}
