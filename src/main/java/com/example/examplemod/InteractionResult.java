package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Rich result of an interaction evaluation or execution.
 */
public record InteractionResult(
        InteractionOutcome outcome,
        String reasonCode,
        Optional<String> diagnosticDetail,
        Optional<ResourceLocation> executorId,
        boolean verified
) {
    public InteractionResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(diagnosticDetail, "diagnosticDetail must not be null");
        Objects.requireNonNull(executorId, "executorId must not be null");
    }

    public static InteractionResult success(ResourceLocation executorId, boolean verified, String detail) {
        return new InteractionResult(InteractionOutcome.SUCCESS, "OK", Optional.ofNullable(detail), Optional.ofNullable(executorId), verified);
    }

    public static InteractionResult success(ResourceLocation executorId, boolean verified) {
        return success(executorId, verified, null);
    }

    public static InteractionResult unsupported(String reasonCode, String detail, ResourceLocation executorId) {
        return new InteractionResult(InteractionOutcome.UNSUPPORTED, reasonCode, Optional.ofNullable(detail), Optional.ofNullable(executorId), false);
    }

    public static InteractionResult unsupported(String reasonCode) {
        return unsupported(reasonCode, null, null);
    }

    public static InteractionResult rejected(String reasonCode, String detail) {
        return new InteractionResult(InteractionOutcome.REJECTED, reasonCode, Optional.ofNullable(detail), Optional.empty(), false);
    }

    public static InteractionResult rejected(String reasonCode) {
        return rejected(reasonCode, null);
    }

    public static InteractionResult failed(String reasonCode, String detail, ResourceLocation executorId) {
        return new InteractionResult(InteractionOutcome.FAILED, reasonCode, Optional.ofNullable(detail), Optional.ofNullable(executorId), false);
    }

    public static InteractionResult failed(String reasonCode) {
        return failed(reasonCode, null, null);
    }

    public static InteractionResult blocked(String reasonCode, String detail) {
        return new InteractionResult(InteractionOutcome.BLOCKED, reasonCode, Optional.ofNullable(detail), Optional.empty(), false);
    }

    public static InteractionResult waiting(String reasonCode) {
        return new InteractionResult(InteractionOutcome.WAITING, reasonCode, Optional.empty(), Optional.empty(), false);
    }

    public static InteractionResult retryable(String reasonCode, String detail) {
        return new InteractionResult(InteractionOutcome.RETRYABLE, reasonCode, Optional.ofNullable(detail), Optional.empty(), false);
    }

    public static InteractionResult unavailable(String reasonCode) {
        return new InteractionResult(InteractionOutcome.UNAVAILABLE, reasonCode, Optional.empty(), Optional.empty(), false);
    }

    public static InteractionResult unknown(ResourceLocation executorId, String reasonCode) {
        return new InteractionResult(InteractionOutcome.UNKNOWN, reasonCode, Optional.empty(), Optional.ofNullable(executorId), false);
    }
}
