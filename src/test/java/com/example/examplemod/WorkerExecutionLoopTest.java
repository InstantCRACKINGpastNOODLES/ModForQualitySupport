package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerExecutionLoopTest {
    private final UUID entity = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final WorkerProfile assigned = new WorkerProfile(entity, owner,
            ResourceLocation.withDefaultNamespace("overworld"), WorkerStatus.IDLE,
            Optional.of(WorkerJob.assigned(WorkerJobs.WAIT)));

    @Test
    void eligibleWorkerChecksAtMostOnceWithoutChangingJob() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(WorkerExecutionLoop.Result.SKIPPED, WorkerExecutionLoop.step(assigned, entity, owner,
                true, true, true, () -> { calls.incrementAndGet(); return WorkerExecutionLoop.Result.SKIPPED; }));
        assertEquals(1, calls.get());
        assertEquals(JobStatus.ASSIGNED, assigned.job().orElseThrow().status());
    }

    @Test
    void cancelledInvalidAndUnauthorizedJobsCannotRun() {
        AtomicInteger calls = new AtomicInteger();
        var action = (java.util.function.Supplier<WorkerExecutionLoop.Result>) () -> {
            calls.incrementAndGet(); return WorkerExecutionLoop.Result.SKIPPED;
        };
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.step(
                assigned.cancel(owner).orElseThrow(), entity, owner, true, true, true, action));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.step(assigned, UUID.randomUUID(), owner, true, true, true, action));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.step(assigned, entity, UUID.randomUUID(), true, true, true, action));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.step(assigned, entity, owner, false, true, true, action));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.step(assigned, entity, owner, true, true, false, action));
        assertEquals(0, calls.get());
    }

    @Test
    void unavailableAndFailedChecksNeverCompleteJob() {
        assertEquals(WorkerExecutionLoop.Result.UNAVAILABLE, WorkerExecutionLoop.step(assigned, entity, owner,
                true, false, true, () -> { throw new AssertionError("must not run"); }));
        assertEquals(WorkerExecutionLoop.Result.FAILED, WorkerExecutionLoop.step(assigned, entity, owner,
                true, true, true, () -> { throw new IllegalStateException("failed"); }));
        assertEquals(JobStatus.ASSIGNED, assigned.job().orElseThrow().status());
    }
}