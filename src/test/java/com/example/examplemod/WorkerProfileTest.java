package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerProfileTest {
    private static final UUID ENTITY = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace("overworld");

    @Test
    void roundTripWithAndWithoutJob() {
        WorkerProfile bare = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE);
        assertEquals(bare, reload(bare));
        WorkerJob failed = WorkerJob.assigned(WorkerJobs.WAIT).transition(JobStatus.FAILED, 25, "blocked").orElseThrow();
        WorkerProfile assigned = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE, Optional.of(failed));
        assertEquals(assigned, reload(assigned));
    }

    @Test
    void corruptDataIsNotAcceptedAsValidProfile() {
        JsonObject saved = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE,
                new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE, Optional.of(WorkerJob.assigned(WorkerJobs.WAIT))))
                .result().orElseThrow().getAsJsonObject();
        var owner = saved.get("owner").deepCopy();
        saved.remove("owner");
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
        saved.add("owner", owner);
        saved.getAsJsonObject("job").addProperty("progress", 101);
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
        saved.getAsJsonObject("job").addProperty("progress", 0);
        saved.getAsJsonObject("job").addProperty("status", "not_a_status");
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
        saved.remove("job");
        assertEquals(Optional.empty(), WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().orElseThrow().job());
    }

    @Test
    void decisionRejectsDuplicateUnavailableAndUnauthorizedRequests() {
        WorkerProfile profile = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE);
        assertEquals(BindingTicketItem.Decision.BIND, decide(true, true, false, false, false, null, OWNER));
        assertEquals(BindingTicketItem.Decision.MANAGE, decide(true, true, true, false, false, profile, OWNER));
        assertEquals(BindingTicketItem.Decision.DENIED, decide(true, true, true, false, false, profile, ENTITY));
        assertEquals(BindingTicketItem.Decision.DUPLICATE, decide(true, true, false, true, false, null, OWNER));
        assertEquals(BindingTicketItem.Decision.UNAVAILABLE, decide(false, true, false, false, false, null, OWNER));
        assertEquals(BindingTicketItem.Decision.UNAVAILABLE, decide(true, false, false, false, false, null, OWNER));
        assertEquals(BindingTicketItem.Decision.DENIED, decide(true, true, true, false, true, profile, ENTITY));
        assertEquals(BindingTicketItem.Decision.MANAGE, decide(true, true, true, false, true, profile, OWNER));
        assertEquals(BindingTicketItem.Decision.DENIED, BindingTicketItem.decide(true, true, true, false,
                true, profile, OWNER, OWNER, DIMENSION));
        assertEquals(BindingTicketItem.Decision.DENIED, BindingTicketItem.decide(true, true, true, false,
                true, profile, OWNER, ENTITY, ResourceLocation.withDefaultNamespace("the_nether")));
    }

    @Test
    void jobTransitionsRejectInvalidProgressAndFailure() {
        WorkerJob job = WorkerJob.assigned(WorkerJobs.WAIT);
        assertTrue(job.transition(JobStatus.COMPLETED, 100, null).isEmpty());
        assertTrue(job.transition(JobStatus.FAILED, 0, " ").isEmpty());
        assertTrue(job.transition(JobStatus.RUNNING, 101, null).isEmpty());
        assertEquals(50, job.transition(JobStatus.RUNNING, 50, null).orElseThrow().progress());
    }

    @Test
    void ownerCanCancelPersistedJobButOthersCannot() {
        WorkerProfile assigned = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE,
                Optional.of(WorkerJob.assigned(WorkerJobs.WAIT)));
        assertTrue(assigned.cancel(ENTITY).isEmpty());
        assertEquals(assigned, reload(assigned));
        WorkerProfile cancelled = assigned.cancel(OWNER).orElseThrow();
        assertTrue(cancelled.job().isEmpty());
        assertEquals(cancelled, reload(cancelled));
        assertTrue(cancelled.cancel(OWNER).isEmpty());
    }

    @Test
    void jobLifecycleRetainsProgressAndFailureThroughReload() {
        WorkerProfile assigned = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE,
                Optional.of(WorkerJob.assigned(WorkerJobs.WAIT)));
        WorkerProfile running = assigned.transition(JobStatus.RUNNING, 35, null).orElseThrow();
        WorkerProfile paused = running.transition(JobStatus.PAUSED, 35, null).orElseThrow();
        assertEquals(paused, reload(paused));
        assertTrue(paused.transition(JobStatus.RUNNING, 34, null).isEmpty());
        WorkerProfile failed = paused.transition(JobStatus.FAILED, 35, "blocked").orElseThrow();
        assertEquals(failed, reload(failed));
        assertEquals(Optional.of("blocked"), failed.job().orElseThrow().failure());
        assertTrue(failed.transition(JobStatus.RUNNING, 36, null).isEmpty());
        WorkerProfile completed = running.transition(JobStatus.COMPLETED, 100, null).orElseThrow();
        assertEquals(completed, reload(completed));
        assertTrue(completed.transition(JobStatus.RUNNING, 100, null).isEmpty());
    }

    @Test
    void invalidSavedJobStatesDoNotBecomeValidAssignments() {
        JsonObject saved = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE,
                new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE, Optional.of(WorkerJob.assigned(WorkerJobs.WAIT))))
                .result().orElseThrow().getAsJsonObject();
        JsonObject job = saved.getAsJsonObject("job");
        job.addProperty("type", "examplemod:missing");
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
        job.addProperty("type", WorkerJobs.WAIT.toString());
        job.addProperty("status", "completed");
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
        job.addProperty("status", "assigned");
        job.addProperty("failure", "blocked");
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
        job.addProperty("status", "failed");
        job.remove("failure");
        assertTrue(WorkerProfile.CODEC.parse(JsonOps.INSTANCE, saved).result().isEmpty());
    }

    @Test
    void duplicateAndUnauthorizedOperationsPreserveExistingJob() {
        WorkerProfile assigned = new WorkerProfile(ENTITY, OWNER, DIMENSION, WorkerStatus.IDLE,
                Optional.of(WorkerJob.assigned(WorkerJobs.WAIT)));
        assertTrue(assigned.assign(OWNER, null, WorkerJobs.WAIT).isEmpty());
        assertTrue(assigned.cancel(ENTITY).isEmpty());
        assertEquals(assigned, reload(assigned));
        WorkerProfile running = assigned.transition(JobStatus.RUNNING, 20, null).orElseThrow();
        assertTrue(running.transition(JobStatus.RUNNING, 21, null).isEmpty());
        assertTrue(running.transition(JobStatus.PAUSED, 21, "unexpected").isEmpty());
        assertEquals(running, reload(running));
    }

    private static BindingTicketItem.Decision decide(boolean server, boolean available, boolean bound,
                                                      boolean legacy, boolean managing, WorkerProfile profile, UUID requester) {
        return BindingTicketItem.decide(server, available, bound, legacy, managing, profile, requester, ENTITY, DIMENSION);
    }

    private static WorkerProfile reload(WorkerProfile profile) {
        return WorkerProfile.CODEC.parse(JsonOps.INSTANCE,
                WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).result().orElseThrow()).result().orElseThrow();
    }
}