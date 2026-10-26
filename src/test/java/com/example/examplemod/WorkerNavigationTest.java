package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerNavigationTest {
    private final ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
    private final WorkerTarget target = new WorkerTarget(dimension, new BlockPos(4, 64, 8));
    private final WorkerProfile profile = new WorkerProfile(UUID.randomUUID(), UUID.randomUUID(),
            dimension, WorkerStatus.IDLE, Optional.of(new WorkerJob(WorkerJobs.MOVE,
            JobStatus.ASSIGNED, 0, Optional.empty(), Optional.of(target))));

    @Test
    void targetAndJobRoundTripWithoutChangingLegacyWait() {
        var encoded = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).result().orElseThrow();
        assertEquals(profile, WorkerProfile.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow());
        assertTrue(WorkerJob.CODEC.parse(JsonOps.INSTANCE,
                WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, WorkerJob.assigned(WorkerJobs.WAIT))
                        .result().orElseThrow()).result().orElseThrow().target().isEmpty());
    }

    @Test
    void missingOrInvalidTargetIsRejected() {
        var encoded = WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, profile.job().orElseThrow()).result().orElseThrow();
        encoded.getAsJsonObject().remove("target");
        assertTrue(WorkerJob.CODEC.parse(JsonOps.INSTANCE, encoded).result().isEmpty());
        encoded.getAsJsonObject().addProperty("target", "broken");
        assertTrue(WorkerJob.CODEC.parse(JsonOps.INSTANCE, encoded).result().isEmpty());
    }

    @Test
    void dimensionCancellationPermissionAndUnavailableTargetBlockRequests() {
        AtomicInteger calls = new AtomicInteger();
        var request = (java.util.function.Supplier<Boolean>) () -> { calls.incrementAndGet(); return true; };
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.navigate(profile,
                ResourceLocation.withDefaultNamespace("the_nether"), true, true, true, false, 40, request));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.navigate(profile.cancel(profile.ownerId()).orElseThrow(),
                dimension, true, true, true, false, 40, request));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.navigate(profile,
                dimension, true, true, false, false, 40, request));
        assertEquals(WorkerExecutionLoop.Result.INELIGIBLE, WorkerExecutionLoop.navigate(profile,
                dimension, true, false, true, false, 40, request));
        assertEquals(WorkerExecutionLoop.Result.UNAVAILABLE, WorkerExecutionLoop.navigate(profile,
                dimension, false, true, true, false, 40, request));
        assertEquals(0, calls.get());
    }

    @Test
    void pathRequestsAreBoundedAndFailureDoesNotCompleteJob() {
        AtomicInteger calls = new AtomicInteger();
        var request = (java.util.function.Supplier<Boolean>) () -> { calls.incrementAndGet(); return false; };
        assertEquals(WorkerExecutionLoop.Result.SKIPPED, WorkerExecutionLoop.navigate(profile,
                dimension, true, true, true, true, 40, request));
        assertEquals(WorkerExecutionLoop.Result.SKIPPED, WorkerExecutionLoop.navigate(profile,
                dimension, true, true, true, false, 41, request));
        assertEquals(WorkerExecutionLoop.Result.FAILED, WorkerExecutionLoop.navigate(profile,
                dimension, true, true, true, false, 80, request));
        assertEquals(1, calls.get());
        assertEquals(JobStatus.ASSIGNED, profile.job().orElseThrow().status());
    }
}