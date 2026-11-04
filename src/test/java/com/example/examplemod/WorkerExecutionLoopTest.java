package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerExecutionLoopTest {
    private final UUID entity = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    private final WorkerProfile assigned = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE,
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

    @Test
    void followBehaviorEnforcesDistanceAndThrottlingAndJobPrecedence() {
        WorkerProfile idleFollow = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE);
        assertEquals(WorkerBehavior.FOLLOW, idleFollow.behavior());

        // Close enough: distance <= 3 blocks (distSq <= 9.0) -> SKIPPED
        assertEquals(WorkerExecutionLoop.Result.SKIPPED,
                WorkerExecutionLoop.follow(idleFollow, 4.0, false, 0, () -> true));

        // Too far: distance > 32 blocks (distSq > 1024.0) -> UNAVAILABLE
        assertEquals(WorkerExecutionLoop.Result.UNAVAILABLE,
                WorkerExecutionLoop.follow(idleFollow, 1025.0, false, 0, () -> true));

        // Within follow range (e.g. 5 blocks -> distSq = 25.0):
        // When not navigating, initiates path
        assertEquals(WorkerExecutionLoop.Result.REQUESTED,
                WorkerExecutionLoop.follow(idleFollow, 25.0, false, 0, () -> true));

        // When navigating, throttles requests to tick % 20 == 0
        assertEquals(WorkerExecutionLoop.Result.SKIPPED,
                WorkerExecutionLoop.follow(idleFollow, 25.0, true, 5, () -> true));
        assertEquals(WorkerExecutionLoop.Result.REQUESTED,
                WorkerExecutionLoop.follow(idleFollow, 25.0, true, 20, () -> true));

        // Active running job takes precedence over follow
        WorkerJob moveJob = new WorkerJob(WorkerJobs.MOVE, JobStatus.ASSIGNED, 0,
                Optional.empty(), Optional.of(new WorkerTarget(dimension, new BlockPos(10, 64, 10))));
        WorkerProfile runningJob = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE, Optional.of(moveJob))
                .transition(JobStatus.RUNNING, 5, null).orElseThrow();
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE,
                WorkerExecutionLoop.follow(runningJob, 25.0, false, 0, () -> true));

        // Stand mode suppresses follow
        WorkerProfile standProfile = idleFollow.setBehavior(owner, WorkerBehavior.STAND).orElseThrow();
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE,
                WorkerExecutionLoop.follow(standProfile, 25.0, false, 0, () -> true));
    }

    @Test
    void standBehaviorStopsAutonomousMovement() {
        WorkerProfile standProfile = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE)
                .setBehavior(owner, WorkerBehavior.STAND).orElseThrow();

        AtomicBoolean stopped = new AtomicBoolean(false);
        assertEquals(WorkerExecutionLoop.Result.REQUESTED,
                WorkerExecutionLoop.stand(standProfile, true, () -> stopped.set(true)));
        assertTrue(stopped.get());

        // When not navigating, returns SKIPPED
        assertEquals(WorkerExecutionLoop.Result.SKIPPED,
                WorkerExecutionLoop.stand(standProfile, false, () -> fail("should not run")));

        // Follow profile is ineligible for stand handling
        WorkerProfile followProfile = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE);
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE,
                WorkerExecutionLoop.stand(followProfile, true, () -> fail("should not run")));
    }

    @Test
    void homeRoamingBoundaryEnforcesThreeByThreeChunks() {
        BlockPos home = new BlockPos(0, 64, 0); // Chunk (0, 0)
        WorkerProfile profile = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE).setHome(owner, home).orElseThrow();

        // Center chunk (0, 0)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(0, 64, 0)));
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(15, 64, 15)));

        // 1 chunk away in any direction (within 3x3 chunks)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(16, 64, 0)));   // Chunk (1, 0)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(-16, 64, 0)));  // Chunk (-1, 0)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(0, 64, 16)));   // Chunk (0, 1)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(0, 64, -16)));  // Chunk (0, -1)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(16, 64, 16)));  // Chunk (1, 1)
        assertTrue(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(-16, 64, -16)));// Chunk (-1, -1)

        // 2 chunks away in X or Z (outside 3x3 chunks)
        assertFalse(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(32, 64, 0)));  // Chunk (2, 0)
        assertFalse(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(-32, 64, 0))); // Chunk (-2, 0)
        assertFalse(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(0, 64, 32)));  // Chunk (0, 2)
        assertFalse(WorkerExecutionLoop.isInsideHome(profile, new BlockPos(0, 64, -32))); // Chunk (0, -2)
    }

    @Test
    void executeBlockActionGuardsAgainstNullContext() {
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE,
                WorkerExecutionLoop.executeBlockAction(null, null, null, null, null, null, null, 1, 0, 100, () -> WorkerExecutionLoop.Result.SKIPPED));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE,
                WorkerExecutionLoop.executeBlockAction(assigned, null, null, null, null, null, null, 1, 0, 100, () -> WorkerExecutionLoop.Result.SKIPPED));
    }
}
