package com.example.examplemod;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

public record WorkerJob(ResourceLocation type, JobStatus status, int progress, Optional<String> failure,
                        Optional<WorkerTarget> target) {
    public static final Codec<WorkerJob> CODEC = RecordCodecBuilder.<WorkerJob>create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("type").forGetter(WorkerJob::type),
            JobStatus.CODEC.fieldOf("status").forGetter(WorkerJob::status),
            Codec.intRange(0, 100).fieldOf("progress").forGetter(WorkerJob::progress),
            Codec.STRING.optionalFieldOf("failure").forGetter(WorkerJob::failure),
            WorkerTarget.CODEC.optionalFieldOf("target").forGetter(WorkerJob::target)
    ).apply(instance, WorkerJob::new)).flatXmap(WorkerJob::validate, WorkerJob::validate);

    private static DataResult<WorkerJob> validate(WorkerJob job) {
        if (!WorkerJobs.isKnown(job.type()) || job.status() == JobStatus.IDLE ||
                (job.type().equals(WorkerJobs.WAIT) && job.target().isPresent()) ||
                (job.type().equals(WorkerJobs.MOVE) && job.target().isEmpty()) ||
                (job.status() == JobStatus.ASSIGNED && job.progress() != 0) ||
                (job.status() == JobStatus.COMPLETED && job.progress() != 100) ||
                (job.status() == JobStatus.FAILED && job.failure().filter(reason -> !reason.isBlank()).isEmpty()) ||
                (job.status() != JobStatus.FAILED && job.failure().isPresent())) {
            return DataResult.error(() -> "Invalid worker job state");
        }
        return DataResult.success(job);
    }

    public static WorkerJob assigned(ResourceLocation type) {
        return new WorkerJob(type, JobStatus.ASSIGNED, 0, Optional.empty(), Optional.empty());
    }

    public WorkerJob(ResourceLocation type, JobStatus status, int progress, Optional<String> failure) {
        this(type, status, progress, failure, Optional.empty());
    }

    public Optional<WorkerJob> transition(JobStatus next, int newProgress, String reason) {
        boolean allowed = switch (status) {
            case ASSIGNED -> next == JobStatus.RUNNING || next == JobStatus.PAUSED || next == JobStatus.FAILED;
            case RUNNING -> next == JobStatus.PAUSED || next == JobStatus.COMPLETED || next == JobStatus.FAILED;
            case PAUSED -> next == JobStatus.RUNNING || next == JobStatus.FAILED;
            default -> false;
        };
        if (!allowed || newProgress < progress || newProgress > 100 ||
                (next == JobStatus.COMPLETED && newProgress != 100) ||
                (next == JobStatus.FAILED && (reason == null || reason.isBlank())) ||
                (next != JobStatus.FAILED && reason != null)) {
            return Optional.empty();
        }
        return Optional.of(new WorkerJob(type, next, newProgress,
                next == JobStatus.FAILED ? Optional.of(reason) : Optional.empty(), target));
    }
}