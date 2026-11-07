package com.example.examplemod;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Deterministic outcome of resolver executor selection.
 */
public record ResolutionResult(
        Status status,
        Optional<InteractionExecutor> executor,
        List<ResourceLocation> candidates,
        String message
) {
    public enum Status {
        RESOLVED,
        NO_COMPATIBLE_EXECUTOR,
        UNSUPPORTED_MODE,
        UNSUPPORTED_TARGET,
        AMBIGUOUS_ROUTING,
        REJECTED_BY_POLICY
    }

    public ResolutionResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        Objects.requireNonNull(message, "message must not be null");
    }

    public static ResolutionResult resolved(InteractionExecutor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        return new ResolutionResult(Status.RESOLVED, Optional.of(executor), List.of(executor.id()), "Executor resolved successfully");
    }

    public static ResolutionResult noCompatibleExecutor(String message) {
        return new ResolutionResult(Status.NO_COMPATIBLE_EXECUTOR, Optional.empty(), List.of(), message);
    }

    public static ResolutionResult unsupportedMode(ExecutionMode mode, String message) {
        return new ResolutionResult(Status.UNSUPPORTED_MODE, Optional.empty(), List.of(), message != null ? message : "Mode unsupported: " + mode);
    }

    public static ResolutionResult unsupportedTarget(InteractionTarget target, String message) {
        return new ResolutionResult(Status.UNSUPPORTED_TARGET, Optional.empty(), List.of(), message != null ? message : "Target unsupported: " + target);
    }

    public static ResolutionResult ambiguousRouting(List<ResourceLocation> candidateIds, String message) {
        return new ResolutionResult(Status.AMBIGUOUS_ROUTING, Optional.empty(), candidateIds, message != null ? message : "Ambiguous routing conflict");
    }

    public static ResolutionResult rejectedByPolicy(String message) {
        return new ResolutionResult(Status.REJECTED_BY_POLICY, Optional.empty(), List.of(), message != null ? message : "Rejected by routing policy");
    }

    public boolean isResolved() {
        return status == Status.RESOLVED && executor.isPresent();
    }
}
