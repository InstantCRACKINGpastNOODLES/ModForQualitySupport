package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

public record WorkerJob(
        ResourceLocation type,
        JobStatus status,
        int progress,
        Optional<String> failure,
        Optional<WorkerTarget> target,
        Optional<MinerJobData> minerData,
        Optional<FarmerJobData> farmerData,
        Optional<UUID> jobId
) {
    public static final Codec<WorkerJob> CODEC = RecordCodecBuilder.<WorkerJob>create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("type").forGetter(WorkerJob::type),
            JobStatus.CODEC.fieldOf("status").forGetter(WorkerJob::status),
            Codec.intRange(0, 100).fieldOf("progress").forGetter(WorkerJob::progress),
            Codec.STRING.optionalFieldOf("failure").forGetter(WorkerJob::failure),
            WorkerTarget.CODEC.optionalFieldOf("target").forGetter(WorkerJob::target),
            MinerJobData.CODEC.optionalFieldOf("miner_data").forGetter(WorkerJob::minerData),
            FarmerJobData.CODEC.optionalFieldOf("farmer_data").forGetter(WorkerJob::farmerData),
            UUIDUtil.CODEC.optionalFieldOf("job_id").forGetter(WorkerJob::jobId)
    ).apply(instance, WorkerJob::new)).flatXmap(WorkerJob::validate, WorkerJob::validate);

    public WorkerJob(ResourceLocation type, JobStatus status, int progress, Optional<String> failure,
                     Optional<WorkerTarget> target, Optional<MinerJobData> minerData, Optional<FarmerJobData> farmerData) {
        this(type, status, progress, failure, target, minerData, farmerData, Optional.of(UUID.randomUUID()));
    }

    private static DataResult<WorkerJob> validate(WorkerJob job) {
        boolean allowsReason = (job.status() == JobStatus.FAILED || job.status() == JobStatus.WAITING_FOR_TOOL ||
                job.status() == JobStatus.WAITING_FOR_STORAGE || job.status() == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                job.status() == JobStatus.WAITING_FOR_FALLING_BLOCK || job.status() == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION ||
                job.status() == JobStatus.WAITING_FOR_CROPS);
        if (!WorkerJobs.isKnown(job.type()) || job.status() == JobStatus.IDLE ||
                (job.type().equals(WorkerJobs.WAIT) && job.target().isPresent()) ||
                (job.type().equals(WorkerJobs.MOVE) && job.target().isEmpty()) ||
                (job.type().equals(WorkerJobs.MINE) && (job.target().isEmpty() || job.minerData().isEmpty())) ||
                (job.type().equals(WorkerJobs.FARM) && (job.target().isEmpty() || job.farmerData().isEmpty())) ||
                (job.status() == JobStatus.ASSIGNED && job.progress() != 0) ||
                (job.status() == JobStatus.COMPLETED && job.progress() != 100) ||
                (job.status() == JobStatus.FAILED && job.failure().filter(reason -> !reason.isBlank()).isEmpty()) ||
                (!allowsReason && job.failure().isPresent())) {
            return DataResult.error(() -> "Invalid worker job state");
        }
        if (job.type().equals(WorkerJobs.MINE) && job.minerData().isPresent()) {
            MinerJobData data = job.minerData().get();
            if (job.status() == JobStatus.COMPLETED && !data.isFullyCompleted()) {
                return DataResult.error(() -> "Miner job cannot be COMPLETED while items or blocks remain pending");
            }
        }
        if (job.type().equals(WorkerJobs.FARM) && job.farmerData().isPresent()) {
            FarmerJobData data = job.farmerData().get();
            if (job.status() == JobStatus.COMPLETED && !data.isFullyCompleted()) {
                return DataResult.error(() -> "Farmer job cannot be COMPLETED while items or crops remain pending");
            }
        }
        return DataResult.success(job);
    }

    public static WorkerJob assigned(ResourceLocation type) {
        return new WorkerJob(type, JobStatus.ASSIGNED, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static WorkerJob assignedMiner(ResourceLocation type, WorkerTarget worksite, MinerJobData minerData) {
        return new WorkerJob(type, JobStatus.ASSIGNED, 0, Optional.empty(), Optional.of(worksite), Optional.of(minerData), Optional.empty());
    }

    public static WorkerJob assignedFarmer(ResourceLocation type, WorkerTarget worksite, FarmerJobData farmerData) {
        return new WorkerJob(type, JobStatus.ASSIGNED, 0, Optional.empty(), Optional.of(worksite), Optional.empty(), Optional.of(farmerData));
    }

    public WorkerJob(ResourceLocation type, JobStatus status, int progress, Optional<String> failure, Optional<WorkerTarget> target, Optional<MinerJobData> minerData) {
        this(type, status, progress, failure, target, minerData, Optional.empty());
    }

    public WorkerJob(ResourceLocation type, JobStatus status, int progress, Optional<String> failure, Optional<WorkerTarget> target) {
        this(type, status, progress, failure, target, Optional.empty(), Optional.empty());
    }

    public WorkerJob(ResourceLocation type, JobStatus status, int progress, Optional<String> failure) {
        this(type, status, progress, failure, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public Optional<WorkerJob> transition(JobStatus next, int newProgress, String reason) {
        boolean allowed = switch (status) {
            case ASSIGNED -> next == JobStatus.RUNNING || next == JobStatus.PAUSED || next == JobStatus.FAILED ||
                             next == JobStatus.DELIVERING || next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.WAITING_FOR_TOOL ||
                             next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                             next == JobStatus.WAITING_FOR_CROPS;
            case RUNNING -> next == JobStatus.PAUSED || next == JobStatus.COMPLETED || next == JobStatus.FAILED ||
                            next == JobStatus.DELIVERING || next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.WAITING_FOR_TOOL ||
                            next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                            next == JobStatus.WAITING_FOR_CROPS;
            case DELIVERING -> next == JobStatus.COMPLETED || next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.PAUSED || next == JobStatus.FAILED || next == JobStatus.RUNNING ||
                               next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                               next == JobStatus.WAITING_FOR_CROPS;
            case WAITING_FOR_STORAGE -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.COMPLETED || next == JobStatus.PAUSED || next == JobStatus.FAILED ||
                                        next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                                        next == JobStatus.WAITING_FOR_CROPS;
            case WAITING_FOR_TOOL -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.PAUSED || next == JobStatus.FAILED ||
                                     next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                                     next == JobStatus.WAITING_FOR_CROPS;
            case WAITING_FOR_CROPS -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.PAUSED || next == JobStatus.FAILED ||
                                     next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.WAITING_FOR_TOOL || next == JobStatus.COMPLETED;
            case WAITING_FOR_FALLING_BLOCK -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL || next == JobStatus.PAUSED || next == JobStatus.FAILED;
            case RECOVERING_TUNNEL_OBSTRUCTION -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.WAITING_FOR_SAFE_TUNNEL || next == JobStatus.WAITING_FOR_TOOL || next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.PAUSED || next == JobStatus.FAILED;
            case WAITING_FOR_SAFE_TUNNEL -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.PAUSED || next == JobStatus.FAILED;
            case PAUSED -> next == JobStatus.RUNNING || next == JobStatus.DELIVERING || next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.WAITING_FOR_TOOL ||
                           next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || next == JobStatus.WAITING_FOR_SAFE_TUNNEL || next == JobStatus.FAILED ||
                           next == JobStatus.WAITING_FOR_CROPS;
            default -> false;
        };
        boolean allowsReason = (next == JobStatus.FAILED || next == JobStatus.WAITING_FOR_TOOL ||
                next == JobStatus.WAITING_FOR_STORAGE || next == JobStatus.WAITING_FOR_SAFE_TUNNEL ||
                next == JobStatus.WAITING_FOR_FALLING_BLOCK || next == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION ||
                next == JobStatus.WAITING_FOR_CROPS);
        if (!allowed || newProgress < progress || newProgress > 100 ||
                (next == JobStatus.COMPLETED && newProgress != 100) ||
                (next == JobStatus.FAILED && (reason == null || reason.isBlank())) ||
                (!allowsReason && reason != null)) {
            return Optional.empty();
        }
        if (next == JobStatus.COMPLETED && minerData.isPresent() && !minerData.get().isFullyCompleted()) {
            return Optional.empty();
        }
        if (next == JobStatus.COMPLETED && farmerData.isPresent() && !farmerData.get().isFullyCompleted()) {
            return Optional.empty();
        }
        Optional<String> failureReason = allowsReason ?
                (reason != null && !reason.isBlank() ? Optional.of(reason) : failure) : Optional.empty();
        return Optional.of(new WorkerJob(type, next, newProgress, failureReason, target, minerData, farmerData, jobId));
    }

    public Optional<WorkerJob> withMinerData(MinerJobData updatedMinerData) {
        if (minerData.isEmpty() || updatedMinerData == null) {
            return Optional.empty();
        }
        long progressAchieved = (updatedMinerData.objectiveMode() == MinerJobData.ObjectiveMode.ITEM_COUNT)
                ? (updatedMinerData.itemsDelivered() + updatedMinerData.itemsPendingDelivery())
                : updatedMinerData.blocksMined();
        int newProgress = (updatedMinerData.requestedBlocks() > 0)
                ? (int) Math.min(100L, (progressAchieved * 100L) / updatedMinerData.requestedBlocks())
                : progress;
        return Optional.of(new WorkerJob(type, status, newProgress, failure, target, Optional.of(updatedMinerData), farmerData, jobId));
    }

    public Optional<WorkerJob> withFarmerData(FarmerJobData updatedFarmerData) {
        if (farmerData.isEmpty() || updatedFarmerData == null) {
            return Optional.empty();
        }
        int newProgress = (updatedFarmerData.requestedBlocks() > 0)
                ? (int) Math.min(100L, (updatedFarmerData.cropsHarvested() * 100L) / updatedFarmerData.requestedBlocks())
                : progress;
        return Optional.of(new WorkerJob(type, status, newProgress, failure, target, minerData, Optional.of(updatedFarmerData), jobId));
    }

    public WorkerJob withJobId(UUID id) {
        return new WorkerJob(type, status, progress, failure, target, minerData, farmerData, Optional.ofNullable(id));
    }
}
