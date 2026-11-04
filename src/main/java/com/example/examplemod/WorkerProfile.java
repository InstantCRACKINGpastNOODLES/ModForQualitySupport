package com.example.examplemod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;

public record WorkerProfile(
        UUID entityId,
        UUID ownerId,
        ResourceLocation dimension,
        WorkerStatus status,
        Optional<WorkerJob> job,
        WorkerBehavior behavior,
        Optional<WorkerTarget> home,
        JobQueue queue) {

    public static final Codec<WorkerProfile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("entity").forGetter(WorkerProfile::entityId),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(WorkerProfile::ownerId),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(WorkerProfile::dimension),
            WorkerStatus.CODEC.fieldOf("status").forGetter(WorkerProfile::status),
            WorkerJob.CODEC.optionalFieldOf("job").forGetter(WorkerProfile::job),
            WorkerBehavior.CODEC.optionalFieldOf("behavior", WorkerBehavior.FOLLOW).forGetter(WorkerProfile::behavior),
            WorkerTarget.CODEC.optionalFieldOf("home").forGetter(WorkerProfile::home),
            JobQueue.CODEC.optionalFieldOf("queue", JobQueue.EMPTY).forGetter(WorkerProfile::queue)
    ).apply(instance, WorkerProfile::new));

    public WorkerProfile(UUID entityId, UUID ownerId, ResourceLocation dimension, WorkerStatus status,
                         Optional<WorkerJob> job, WorkerBehavior behavior, Optional<WorkerTarget> home) {
        this(entityId, ownerId, dimension, status, job, behavior, home, JobQueue.fromJob(job));
    }

    public WorkerProfile(UUID entityId, UUID ownerId, ResourceLocation dimension, WorkerStatus status) {
        this(entityId, ownerId, dimension, status, Optional.empty(), WorkerBehavior.FOLLOW, Optional.empty(), JobQueue.EMPTY);
    }

    public WorkerProfile(UUID entityId, UUID ownerId, ResourceLocation dimension, WorkerStatus status, Optional<WorkerJob> job) {
        this(entityId, ownerId, dimension, status, job, WorkerBehavior.FOLLOW, Optional.empty(), JobQueue.fromJob(job));
    }

    public Optional<WorkerProfile> assign(UUID requester, net.minecraft.world.entity.Mob mob, ResourceLocation type) {
        if (WorkerJobs.MOVE.equals(type) || job.isPresent() || mob == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel) ||
                !ownerId.equals(requester) || !entityId.equals(mob.getUUID()) ||
                !dimension.equals(mob.level().dimension().location()) ||
                !mob.isAlive() || mob.isRemoved()) {
            return Optional.empty();
        }
        return WorkerJobs.assign(type, mob).map(assignment -> {
            JobQueue newQueue = JobQueue.fromJob(Optional.of(assignment));
            return new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(assignment), behavior, home, newQueue);
        });
    }

    public Optional<WorkerProfile> cancel(UUID requester) {
        if (!ownerId.equals(requester) || (job.isEmpty() && (queue == null || queue.isEmpty()))) {
            return Optional.empty();
        }
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, Optional.empty(), behavior, home, JobQueue.EMPTY));
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
        JobQueue newQueue = JobQueue.fromJob(Optional.of(assigned));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(assigned), behavior, home, newQueue));
    }

    public Optional<WorkerProfile> replaceMove(UUID requester, PathfinderMob mob, WorkerTarget target) {
        if (mob == null || target == null || target.position() == null ||
                !dimension.equals(target.dimension()) || !ownerId.equals(requester) ||
                !entityId.equals(mob.getUUID()) || !(mob.level() instanceof net.minecraft.server.level.ServerLevel) ||
                !dimension.equals(mob.level().dimension().location()) || !mob.isAlive() || mob.isRemoved()) {
            return Optional.empty();
        }
        WorkerJob assigned = new WorkerJob(WorkerJobs.MOVE, JobStatus.ASSIGNED, 0,
                Optional.empty(), Optional.of(target));
        JobQueue newQueue = JobQueue.fromJob(Optional.of(assigned));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(assigned), behavior, home, newQueue));
    }

    public Optional<WorkerProfile> assignMiner(UUID requester, Mob mob, WorkerTarget worksite, MinerJobData minerData) {
        if (job.isPresent() || mob == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel level) ||
                !entityId.equals(mob.getUUID()) || !ownerId.equals(requester) ||
                !dimension.equals(level.dimension().location()) || worksite == null || minerData == null) {
            return Optional.empty();
        }
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, worksite, minerData);
        JobQueue newQueue = JobQueue.fromJob(Optional.of(minerJob));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(minerJob), behavior, home, newQueue));
    }

    public Optional<WorkerProfile> replaceMiner(UUID requester, Mob mob, WorkerTarget worksite, MinerJobData minerData) {
        if (mob == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel level) ||
                !entityId.equals(mob.getUUID()) || !ownerId.equals(requester) ||
                !dimension.equals(level.dimension().location()) || worksite == null || minerData == null) {
            return Optional.empty();
        }
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, worksite, minerData);
        JobQueue newQueue = JobQueue.fromJob(Optional.of(minerJob));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(minerJob), behavior, home, newQueue));
    }

    public Optional<WorkerProfile> assignFarmer(UUID requester, Mob mob, WorkerTarget worksite, FarmerJobData farmerData) {
        if (job.isPresent() || mob == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel level) ||
                !entityId.equals(mob.getUUID()) || !ownerId.equals(requester) ||
                !dimension.equals(level.dimension().location()) || worksite == null || farmerData == null) {
            return Optional.empty();
        }
        WorkerJob farmerJob = WorkerJob.assignedFarmer(WorkerJobs.FARM, worksite, farmerData);
        JobQueue newQueue = JobQueue.fromJob(Optional.of(farmerJob));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(farmerJob), behavior, home, newQueue));
    }

    public Optional<WorkerProfile> replaceFarmer(UUID requester, Mob mob, WorkerTarget worksite, FarmerJobData farmerData) {
        if (mob == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel level) ||
                !entityId.equals(mob.getUUID()) || !ownerId.equals(requester) ||
                !dimension.equals(level.dimension().location()) || worksite == null || farmerData == null) {
            return Optional.empty();
        }
        WorkerJob farmerJob = WorkerJob.assignedFarmer(WorkerJobs.FARM, worksite, farmerData);
        JobQueue newQueue = JobQueue.fromJob(Optional.of(farmerJob));
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, WorkerStatus.IDLE, Optional.of(farmerJob), behavior, home, newQueue));
    }

    public WorkerProfile withJob(WorkerJob newJob) {
        JobQueue newQueue = JobQueue.fromJob(Optional.ofNullable(newJob));
        return new WorkerProfile(entityId, ownerId, dimension, status, Optional.ofNullable(newJob), behavior, home, newQueue);
    }

    public WorkerProfile withQueue(JobQueue newQueue) {
        return new WorkerProfile(entityId, ownerId, dimension, status, job, behavior, home, newQueue != null ? newQueue : JobQueue.EMPTY);
    }

    public WorkerProfile addQueuedJob(Job newJob) {
        JobQueue baseQueue = queue != null ? queue : JobQueue.EMPTY;
        JobQueue updated = baseQueue.addJob(newJob);
        return withQueue(updated);
    }

    public Optional<WorkerProfile> transition(JobStatus next, int progress, String failure) {
        return job.flatMap(current -> current.transition(next, progress, failure))
                .map(updated -> new WorkerProfile(entityId, ownerId, dimension, status, Optional.of(updated), behavior, home, queue));
    }

    public Optional<WorkerProfile> setBehavior(UUID requester, WorkerBehavior newBehavior) {
        if (!ownerId.equals(requester) || newBehavior == null) {
            return Optional.empty();
        }
        Optional<WorkerJob> updatedJob = job.map(current -> {
            if (newBehavior == WorkerBehavior.STAND && current.status() == JobStatus.RUNNING) {
                return current.transition(JobStatus.PAUSED, current.progress(), null).orElse(current);
            } else if (newBehavior == WorkerBehavior.FOLLOW && current.status() == JobStatus.PAUSED) {
                return current.transition(JobStatus.RUNNING, current.progress(), null).orElse(current);
            }
            return current;
        });
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, updatedJob, newBehavior, home, queue));
    }

    public Optional<WorkerProfile> setHome(UUID requester, WorkerTarget newHome) {
        if (!ownerId.equals(requester) || newHome == null || !dimension.equals(newHome.dimension())) {
            return Optional.empty();
        }
        return Optional.of(new WorkerProfile(entityId, ownerId, dimension, status, job, behavior, Optional.of(newHome), queue));
    }

    public Optional<WorkerProfile> setHome(UUID requester, BlockPos pos) {
        return pos == null ? Optional.empty() : setHome(requester, new WorkerTarget(dimension, pos));
    }

    public boolean isWithinHomeArea(BlockPos checkPos) {
        return home.map(h -> isWithinHomeChunks(h.position(), checkPos)).orElse(false);
    }

    public static boolean isWithinHomeChunks(BlockPos homePos, BlockPos checkPos) {
        if (homePos == null || checkPos == null) return false;
        int homeChunkX = homePos.getX() >> 4;
        int homeChunkZ = homePos.getZ() >> 4;
        int checkChunkX = checkPos.getX() >> 4;
        int checkChunkZ = checkPos.getZ() >> 4;
        return Math.abs(checkChunkX - homeChunkX) <= 1 && Math.abs(checkChunkZ - homeChunkZ) <= 1;
    }

    public static WorkerProfile unbound() {
        return new WorkerProfile(new UUID(0, 0), new UUID(0, 0),
                ResourceLocation.withDefaultNamespace("overworld"), WorkerStatus.IDLE,
                Optional.empty(), WorkerBehavior.FOLLOW, Optional.empty(), JobQueue.EMPTY);
    }

    public List<net.minecraft.world.item.ItemStack> getReservedItemStacks() {
        List<net.minecraft.world.item.ItemStack> reserved = new java.util.ArrayList<>();
        if (job.isPresent()) {
            WorkerJob currentJob = job.get();
            if (currentJob.minerData().isPresent()) {
                reserved.addAll(currentJob.minerData().get().getReservedItemStacks());
            }
            if (currentJob.farmerData().isPresent()) {
                reserved.addAll(currentJob.farmerData().get().getReservedItemStacks());
            }
            var provider = JobReservationRegistry.getProvider(currentJob.type());
            if (provider != null) {
                java.util.Collection<net.minecraft.world.item.ItemStack> custom = provider.apply(this);
                if (custom != null) {
                    for (net.minecraft.world.item.ItemStack s : custom) {
                        if (s != null && !s.isEmpty()) {
                            reserved.add(s.copy());
                        }
                    }
                }
            }
        }
        if (queue != null && !queue.isEmpty()) {
            boolean isSameLogicalJob = job.isPresent() &&
                    job.get().jobId().isPresent() &&
                    queue.currentJob().map(j -> j.jobId().equals(job.get().jobId().get())).orElse(false);
            if (!isSameLogicalJob) {
                reserved.addAll(queue.getReservedItemStacks());
            }
        }
        return java.util.Collections.unmodifiableList(reserved);
    }

    public boolean hasUncertainReservation() {
        if (job.isEmpty()) {
            return false;
        }
        WorkerJob currentJob = job.get();
        ResourceLocation type = currentJob.type();
        if (WorkerJobs.MOVE.equals(type) || WorkerJobs.WAIT.equals(type)) {
            return false;
        }
        if (WorkerJobs.MINE.equals(type) && currentJob.minerData().isPresent()) {
            return false;
        }
        if (WorkerJobs.FARM.equals(type) && currentJob.farmerData().isPresent()) {
            return false;
        }
        return !JobReservationRegistry.hasProvider(type);
    }
}
