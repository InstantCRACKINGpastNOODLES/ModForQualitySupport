package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

/**
 * A high-level work order comprised of sequential steps, prerequisites, repetitions, and execution state.
 */
public record Job(
        UUID jobId,
        ResourceLocation type,
        String displayName,
        ExecutionState state,
        int currentStepIndex,
        List<JobStep> steps,
        int repetitions,
        int completedRepetitions,
        Optional<String> waitReason,
        Optional<String> failureReason,
        List<JobDependency> dependencies
) {
    public static final Codec<Job> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("job_id").forGetter(Job::jobId),
            ResourceLocation.CODEC.fieldOf("type").forGetter(Job::type),
            Codec.STRING.optionalFieldOf("display_name", "Job").forGetter(Job::displayName),
            ExecutionState.CODEC.fieldOf("state").forGetter(Job::state),
            Codec.INT.optionalFieldOf("current_step_index", 0).forGetter(Job::currentStepIndex),
            JobStep.CODEC.listOf().optionalFieldOf("steps", List.of()).forGetter(Job::steps),
            Codec.INT.optionalFieldOf("repetitions", 1).forGetter(Job::repetitions),
            Codec.INT.optionalFieldOf("completed_repetitions", 0).forGetter(Job::completedRepetitions),
            Codec.STRING.optionalFieldOf("wait_reason").forGetter(Job::waitReason),
            Codec.STRING.optionalFieldOf("failure_reason").forGetter(Job::failureReason),
            JobDependency.CODEC.listOf().optionalFieldOf("dependencies", List.of()).forGetter(Job::dependencies)
    ).apply(instance, Job::new));

    public static Job createMoveJob(UUID jobId, WorkerTarget target) {
        JobStep step = JobStep.moveTo(target.position());
        return new Job(
                jobId != null ? jobId : UUID.randomUUID(),
                WorkerJobs.MOVE,
                "Move to " + target.position().toShortString(),
                ExecutionState.PENDING,
                0,
                List.of(step),
                1,
                0,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );
    }

    public static Job createMoveJob(WorkerTarget target) {
        return createMoveJob(UUID.randomUUID(), target);
    }

    public static Job createMineJob(UUID jobId, WorkerTarget worksite, WorkerTarget storage, Direction tunnelDir, long quota) {
        int q = (int) Math.min(quota, Integer.MAX_VALUE);
        JobStep mineStep = JobStep.mineBlock(worksite.position(), tunnelDir, q);
        JobStep depositStep = JobStep.depositItems(storage.position());
        return new Job(
                jobId != null ? jobId : UUID.randomUUID(),
                WorkerJobs.MINE,
                "Mine (" + q + " blocks)",
                ExecutionState.PENDING,
                0,
                List.of(mineStep, depositStep),
                q,
                0,
                Optional.empty(),
                Optional.empty(),
                List.of(JobDependency.tool("pickaxe"))
        );
    }

    public static Job createMineJob(WorkerTarget worksite, WorkerTarget storage, Direction tunnelDir, long quota) {
        return createMineJob(UUID.randomUUID(), worksite, storage, tunnelDir, quota);
    }

    public static Job createFarmJob(UUID jobId, WorkerTarget worksite, WorkerTarget storage, List<ResourceLocation> permittedCrops, long quota) {
        int q = (int) Math.min(quota, Integer.MAX_VALUE);
        JobStep farmStep = JobStep.harvestCrop(worksite.position(), q);
        JobStep depositStep = JobStep.depositItems(storage.position());
        return new Job(
                jobId != null ? jobId : UUID.randomUUID(),
                WorkerJobs.FARM,
                "Farm (" + q + " crops)",
                ExecutionState.PENDING,
                0,
                List.of(farmStep, depositStep),
                q,
                0,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );
    }

    public static Job createFarmJob(WorkerTarget worksite, WorkerTarget storage, List<ResourceLocation> permittedCrops, long quota) {
        return createFarmJob(UUID.randomUUID(), worksite, storage, permittedCrops, quota);
    }

    public static Job createProductionJob(ResourceLocation type, String name, List<JobStep> steps, int repetitions) {
        return new Job(
                UUID.randomUUID(),
                type,
                name,
                ExecutionState.PENDING,
                0,
                List.copyOf(steps),
                repetitions,
                0,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );
    }

    public Optional<JobStep> currentStep() {
        if (steps.isEmpty() || currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            return Optional.empty();
        }
        return Optional.of(steps.get(currentStepIndex));
    }

    public boolean isComplete() {
        return state == ExecutionState.COMPLETED ||
                (currentStepIndex >= steps.size() && completedRepetitions >= repetitions);
    }

    public Job advanceStep() {
        int nextIndex = currentStepIndex + 1;
        if (nextIndex >= steps.size()) {
            int nextCompleted = completedRepetitions + 1;
            if (nextCompleted >= repetitions) {
                return new Job(
                        jobId, type, displayName, ExecutionState.COMPLETED,
                        nextIndex, steps, repetitions, nextCompleted,
                        Optional.empty(), Optional.empty(), dependencies
                );
            } else {
                // Repeat steps from 0
                List<JobStep> resetSteps = steps.stream()
                        .map(s -> s.withProgress(0).withState(ExecutionState.PENDING, null))
                        .toList();
                return new Job(
                        jobId, type, displayName, ExecutionState.RUNNING,
                        0, resetSteps, repetitions, nextCompleted,
                        Optional.empty(), Optional.empty(), dependencies
                );
            }
        }
        return new Job(
                jobId, type, displayName, state,
                nextIndex, steps, repetitions, completedRepetitions,
                waitReason, failureReason, dependencies
        );
    }

    public Job updateCurrentStep(JobStep updated) {
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            return this;
        }
        List<JobStep> newSteps = new ArrayList<>(steps);
        newSteps.set(currentStepIndex, updated);
        return new Job(
                jobId, type, displayName, state,
                currentStepIndex, List.copyOf(newSteps), repetitions, completedRepetitions,
                waitReason, failureReason, dependencies
        );
    }

    public Job withState(ExecutionState newState, String reason) {
        Optional<String> wait = (newState == ExecutionState.WAITING) ? Optional.ofNullable(reason) : Optional.empty();
        Optional<String> fail = (newState == ExecutionState.FAILED) ? Optional.ofNullable(reason) : Optional.empty();
        return new Job(
                jobId, type, displayName, newState,
                currentStepIndex, steps, repetitions, completedRepetitions,
                wait, fail, dependencies
        );
    }
}
