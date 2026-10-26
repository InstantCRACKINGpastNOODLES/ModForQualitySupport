package com.example.examplemod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

class WorkerInteractionRegistryTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation ACTION = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "inspect");
    private static final WorkerProfile PROFILE = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE,
            Optional.of(WorkerJob.assigned(WorkerJobs.WAIT)));

    private static WorkerInteraction.Request request(UUID requester, boolean available) {
        return new WorkerInteraction.Request(ACTION, PROFILE, requester, ENTITY, DIMENSION,
                new WorkerInteraction.Block(new BlockPos(1, 2, 3)), true, available, Map.of());
    }

    private static WorkerInteractionRegistry.Handler handler(ResourceLocation action, AtomicInteger calls,
                                                              WorkerInteraction.Outcome outcome) {
        return new WorkerInteractionRegistry.Handler() {
            @Override
            public boolean supports(WorkerInteraction.Request request) {
                return action.equals(request.action());
            }

            @Override
            public WorkerInteraction.Result validate(WorkerInteraction.Request request) {
                calls.incrementAndGet();
                return new WorkerInteraction.Result(outcome, "preflight");
            }
        };
    }

    @Test
    void supportedRequestRoutesToMatchingHandler() {
        var registry = new WorkerInteractionRegistry();
        var calls = new AtomicInteger();
        registry.register(handler(ResourceLocation.withDefaultNamespace("other"), calls, WorkerInteraction.Outcome.SUCCESS));
        registry.register(handler(ACTION, calls, WorkerInteraction.Outcome.SUCCESS));
        assertEquals(WorkerInteraction.Outcome.SUCCESS, registry.dispatch(request(OWNER, true)).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void unsupportedRequestIsRejected() {
        assertEquals(WorkerInteraction.Outcome.REJECTED, new WorkerInteractionRegistry().dispatch(request(OWNER, true)).outcome());
    }

    @Test
    void invalidAndUnauthorizedRequestsNeverInvokeHandler() {
        var registry = new WorkerInteractionRegistry();
        var calls = new AtomicInteger();
        registry.register(handler(ACTION, calls, WorkerInteraction.Outcome.SUCCESS));
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.dispatch(request(ENTITY, true)).outcome());
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.dispatch(new WorkerInteraction.Request(ACTION, PROFILE,
                OWNER, ENTITY, DIMENSION, new WorkerInteraction.Entity(null), true, true, Map.of())).outcome());
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.dispatch(new WorkerInteraction.Request(ACTION, PROFILE,
                OWNER, ENTITY, DIMENSION, new WorkerInteraction.Endpoint(ACTION), false, true, Map.of())).outcome());
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.dispatch(new WorkerInteraction.Request(ACTION, PROFILE,
                OWNER, UUID.randomUUID(), DIMENSION, new WorkerInteraction.Block(BlockPos.ZERO), true, true, Map.of())).outcome());
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.dispatch(new WorkerInteraction.Request(ACTION, PROFILE,
                OWNER, ENTITY, DIMENSION, new WorkerInteraction.Block(BlockPos.ZERO), true, true, null)).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void ambiguousHandlersRejectWithoutValidation() {
        var registry = new WorkerInteractionRegistry();
        var calls = new AtomicInteger();
        registry.register(handler(ACTION, calls, WorkerInteraction.Outcome.SUCCESS));
        registry.register(handler(ACTION, calls, WorkerInteraction.Outcome.SUCCESS));
        assertEquals(WorkerInteraction.Outcome.REJECTED, registry.dispatch(request(OWNER, true)).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void unavailableTargetDoesNotInvokeHandler() {
        var registry = new WorkerInteractionRegistry();
        var calls = new AtomicInteger();
        registry.register(handler(ACTION, calls, WorkerInteraction.Outcome.SUCCESS));
        assertEquals(WorkerInteraction.Outcome.UNAVAILABLE, registry.dispatch(request(OWNER, false)).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void handlerRejectionAndFailureDoNotChangeJob() {
        for (var outcome : new WorkerInteraction.Outcome[]{WorkerInteraction.Outcome.REJECTED, WorkerInteraction.Outcome.FAILED}) {
            var registry = new WorkerInteractionRegistry();
            registry.register(handler(ACTION, new AtomicInteger(), outcome));
            assertEquals(outcome, registry.dispatch(request(OWNER, true)).outcome());
            assertEquals(JobStatus.ASSIGNED, PROFILE.job().orElseThrow().status());
        }
    }

    @Test
    void registryInstancesDoNotShareHandlers() {
        var first = new WorkerInteractionRegistry();
        var second = new WorkerInteractionRegistry();
        first.register(handler(ACTION, new AtomicInteger(), WorkerInteraction.Outcome.SUCCESS));
        assertEquals(WorkerInteraction.Outcome.SUCCESS, first.dispatch(request(OWNER, true)).outcome());
        assertEquals(WorkerInteraction.Outcome.REJECTED, second.dispatch(request(OWNER, true)).outcome());
    }
}