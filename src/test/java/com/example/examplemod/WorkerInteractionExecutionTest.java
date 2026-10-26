package com.example.examplemod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

class WorkerInteractionExecutionTest {
    private static final ResourceLocation ACTION = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "inspect");
    private final UUID entity = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    private final WorkerProfile profile = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE);

    private WorkerInteraction.Request request() {
        return new WorkerInteraction.Request(ACTION, profile, owner, entity, dimension,
                new WorkerInteraction.Block(BlockPos.ZERO), true, true, Map.of());
    }

    private WorkerInteractionRegistry registry(WorkerInteraction.Outcome preflight, WorkerInteraction.Outcome attempt,
                                               boolean verified, AtomicInteger executions, AtomicInteger verifications) {
        var registry = new WorkerInteractionRegistry();
        registry.register(new WorkerInteractionRegistry.ExecutableHandler() {
            public boolean supports(WorkerInteraction.Request request) { return ACTION.equals(request.action()); }
            public WorkerInteraction.Result validate(WorkerInteraction.Request request) {
                return new WorkerInteraction.Result(preflight, "preflight");
            }
            public WorkerInteraction.Result execute(WorkerInteraction.Request request, ServerLevel level, Mob worker) {
                executions.incrementAndGet();
                return new WorkerInteraction.Result(attempt, "attempt");
            }
            public boolean verify(WorkerInteraction.Request request, ServerLevel level, Mob worker) {
                verifications.incrementAndGet();
                return verified;
            }
        });
        return registry;
    }

    @Test
    void preflightRejectionPreventsExecution() {
        var executions = new AtomicInteger();
        var verifications = new AtomicInteger();
        var registry = registry(WorkerInteraction.Outcome.REJECTED, WorkerInteraction.Outcome.SUCCESS, true, executions, verifications);
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.executeValidated(request(), null, null).outcome());
        assertEquals(0, executions.get());
        assertEquals(0, verifications.get());
    }

    @Test
    void failedAndUnavailableAttemptsCannotClaimSuccess() {
        for (var outcome : new WorkerInteraction.Outcome[]{WorkerInteraction.Outcome.FAILED, WorkerInteraction.Outcome.UNAVAILABLE}) {
            var executions = new AtomicInteger();
            var verifications = new AtomicInteger();
            var registry = registry(WorkerInteraction.Outcome.SUCCESS, outcome, true, executions, verifications);
            assertEquals(outcome, registry.executeValidated(request(), null, null).outcome());
            assertEquals(1, executions.get());
            assertEquals(0, verifications.get());
        }
    }

    @Test
    void successRequiresVerificationAndDoesNotCompleteJob() {
        for (boolean verified : new boolean[]{false, true}) {
            var executions = new AtomicInteger();
            var verifications = new AtomicInteger();
            var registry = registry(WorkerInteraction.Outcome.SUCCESS, WorkerInteraction.Outcome.SUCCESS, verified, executions, verifications);
            assertEquals(verified ? WorkerInteraction.Outcome.SUCCESS : WorkerInteraction.Outcome.UNAVAILABLE,
                    registry.executeValidated(request(), null, null).outcome());
            assertEquals(1, executions.get());
            assertEquals(1, verifications.get());
            assertEquals(profile, request().worker());
        }
    }

    @Test
    void missingServerContextAndUnauthorizedRequestPreventExecution() {
        var executions = new AtomicInteger();
        var registry = registry(WorkerInteraction.Outcome.SUCCESS, WorkerInteraction.Outcome.SUCCESS, true, executions, new AtomicInteger());
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.execute(request(), null, null, null, ignored -> true).outcome());
        var unauthorized = new WorkerInteraction.Request(ACTION, profile, UUID.randomUUID(), entity, dimension,
                new WorkerInteraction.Block(BlockPos.ZERO), true, true, Map.of());
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.execute(unauthorized, null, null, null, ignored -> true).outcome());
        assertEquals(0, executions.get());
    }
}