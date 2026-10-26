package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.PathfinderMob;

public record WorkerProfile(UUID entityId, UUID ownerId, ResourceLocation dimension, WorkerStatus status, Optional<WorkerJob> job) {
    public static final Codec<WorkerProfile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("entity").forGetter(WorkerProfile::entityId),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(WorkerProfile::ownerId),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(WorkerProfile::dimension),
            WorkerStatus.CODEC.fieldOf("status").forGetter(WorkerProfile::status),
            WorkerJob.CODEC.optionalFieldOf("job").forGetter(WorkerProfile::job)
    ).apply(instance, WorkerProfile::new));

    public WorkerProfile(UUID entityId, UUID ownerId, ResourceLocation dimension, WorkerStatus status) {
        this(entityId, ownerId, dimension, status, Optional.empty());
    }

    public Optional<WorkerProfile> assign(UUID requester, net.minecraft.world.entity.Mob mob, ResourceLocation type) {
        if (WorkerJobs.MOVE.equals(type) || job.isPresent() || mob == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel) ||
                !ownerId.equals(requester) || !entityId.equals(mob.getUUID()) ||
                !dimension.equals(mob.level().dimension().location()) ||
                !mob.isAlive() || mob.isRemoved()) {
            return Optional.empty();
        }
        return WorkerJobs.assign(type, mob).map(assignment -> new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(assignment)));
    }

    public Optional<WorkerProfile> cancel(UUID requester) {
        if (!ownerId.equals(requester) || job.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status));
    }

    public Optional<WorkerProfile> assignMove(UUID requester, PathfinderMob mob, WorkerTarget target) {
        if (job.isPresent() || mob == null || target == null || target.position() == null ||
                !dimension.equals(target.dimension()) || !ownerId.equals(requester) ||
                !entityId.equals(mob.getUUID()) || !(mob.level() instanceof net.minecraft.server.level.ServerLevel) ||
                !dimension.equals(mob.level().dimension().location()) || !mob.isAlive() || mob.isRemoved()) {
            return Optional.empty();
        }
        WorkerJob assigned = new WorkerJob(WorkerJobs.MOVE, JobStatus.ASSIGNED, 0,
                Optional.empty(), Optional.of(target));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(assigned)));
    }

    public Optional<WorkerProfile> transition(JobStatus next, int progress, String failure) {
        return job.flatMap(current -> current.transition(next, progress, failure))
                .map(updated -> new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(updated)));
    }

    public static WorkerProfile unbound() {
        return new WorkerProfile(new UUID(0, 0), new UUID(0, 0), ResourceLocation.withDefaultNamespace("overworld"), WorkerStatus.IDLE);
    }
}