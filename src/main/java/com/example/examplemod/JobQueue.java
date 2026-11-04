package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Ordered queue of jobs assigned to a worker.
 * Supports sequential execution, waiting states, progress tracking, and reordering.
 */
public record JobQueue(
        List<Job> jobs,
        int activeJobIndex,
        ExecutionState queueState
) implements JobItemReservation {
    public static final JobQueue EMPTY = new JobQueue(List.of(), 0, ExecutionState.PENDING);

    public static final Codec<JobQueue> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            JobStep.CODEC.listOf().optionalFieldOf("jobs_legacy", List.of()).forGetter(q -> List.of()),
            Job.CODEC.listOf().optionalFieldOf("jobs", List.of()).forGetter(JobQueue::jobs),
            Codec.INT.optionalFieldOf("active_job_index", 0).forGetter(JobQueue::activeJobIndex),
            ExecutionState.CODEC.optionalFieldOf("queue_state", ExecutionState.PENDING).forGetter(JobQueue::queueState)
    ).apply(instance, (legacy, jobList, activeIdx, state) -> new JobQueue(jobList, activeIdx, state)));

    public JobQueue(List<Job> jobs, int activeJobIndex, ExecutionState queueState) {
        this.jobs = jobs != null ? List.copyOf(jobs) : List.of();
        this.activeJobIndex = Math.max(0, activeJobIndex);
        this.queueState = queueState != null ? queueState : ExecutionState.PENDING;
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    public int size() {
        return jobs.size();
    }

    public Optional<Job> currentJob() {
        if (isEmpty() || activeJobIndex < 0 || activeJobIndex >= jobs.size()) {
            return Optional.empty();
        }
        return Optional.of(jobs.get(activeJobIndex));
    }

    public JobQueue addJob(Job job) {
        if (job == null) return this;
        List<Job> list = new ArrayList<>(jobs);
        list.add(job);
        ExecutionState nextState = queueState == ExecutionState.COMPLETED ? ExecutionState.RUNNING : queueState;
        return new JobQueue(list, activeJobIndex, nextState);
    }

    public JobQueue removeJob(int index) {
        if (index < 0 || index >= jobs.size()) return this;
        List<Job> list = new ArrayList<>(jobs);
        list.remove(index);
        int newIndex = activeJobIndex;
        if (newIndex >= list.size()) {
            newIndex = Math.max(0, list.size() - 1);
        }
        return new JobQueue(list, newIndex, queueState);
    }

    public JobQueue reorder(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= jobs.size() || toIndex < 0 || toIndex >= jobs.size() || fromIndex == toIndex) {
            return this;
        }
        List<Job> list = new ArrayList<>(jobs);
        Job item = list.remove(fromIndex);
        list.add(toIndex, item);
        return new JobQueue(list, activeJobIndex, queueState);
    }

    public JobQueue clear() {
        return EMPTY;
    }

    public JobQueue updateCurrentJob(Job updated) {
        if (activeJobIndex < 0 || activeJobIndex >= jobs.size() || updated == null) {
            return this;
        }
        List<Job> list = new ArrayList<>(jobs);
        list.set(activeJobIndex, updated);
        ExecutionState nextQueueState = updated.state();
        return new JobQueue(list, activeJobIndex, nextQueueState);
    }

    public JobQueue advance() {
        int nextIndex = activeJobIndex + 1;
        if (nextIndex >= jobs.size()) {
            return new JobQueue(jobs, activeJobIndex, ExecutionState.COMPLETED);
        }
        List<Job> list = new ArrayList<>(jobs);
        Job nextJob = list.get(nextIndex);
        if (nextJob.state() == ExecutionState.PENDING) {
            nextJob = nextJob.withState(ExecutionState.RUNNING, null);
            list.set(nextIndex, nextJob);
        }
        return new JobQueue(list, nextIndex, ExecutionState.RUNNING);
    }

    public boolean isAllComplete() {
        if (jobs.isEmpty()) return true;
        for (Job j : jobs) {
            if (j.state() != ExecutionState.COMPLETED) return false;
        }
        return true;
    }

    public static JobQueue fromJob(Optional<WorkerJob> workerJob) {
        if (workerJob == null || workerJob.isEmpty()) {
            return EMPTY;
        }
        WorkerJob wj = workerJob.get();
        java.util.UUID id = wj.jobId().orElseGet(java.util.UUID::randomUUID);
        Job adapted;
        if (WorkerJobs.MOVE.equals(wj.type()) && wj.target().isPresent()) {
            adapted = Job.createMoveJob(id, wj.target().get());
        } else if (WorkerJobs.MINE.equals(wj.type()) && wj.target().isPresent() && wj.minerData().isPresent()) {
            MinerJobData data = wj.minerData().get();
            adapted = Job.createMineJob(id, wj.target().get(), data.storage(), data.direction(), data.requestedBlocks());
        } else if (WorkerJobs.FARM.equals(wj.type()) && wj.target().isPresent() && wj.farmerData().isPresent()) {
            FarmerJobData data = wj.farmerData().get();
            adapted = Job.createFarmJob(id, wj.target().get(), data.storage(), data.permittedCrops(), data.requestedBlocks());
        } else {
            adapted = new Job(
                    id,
                    wj.type(),
                    wj.type().getPath(),
                    ExecutionState.RUNNING,
                    0,
                    List.of(),
                    1,
                    0,
                    Optional.empty(),
                    wj.failure(),
                    List.of()
            );
        }
        return new JobQueue(List.of(adapted), 0, ExecutionState.RUNNING);
    }

    @Override
    public List<net.minecraft.world.item.ItemStack> getReservedItemStacks() {
        if (isEmpty() || activeJobIndex < 0 || activeJobIndex >= jobs.size()) {
            return List.of();
        }
        Job activeJob = jobs.get(activeJobIndex);
        if (activeJob.state() == ExecutionState.COMPLETED || activeJob.state() == ExecutionState.FAILED) {
            return List.of();
        }

        List<net.minecraft.world.item.ItemStack> reserved = new ArrayList<>();
        List<JobStep> steps = activeJob.steps();
        if (steps != null && !steps.isEmpty()) {
            int currentIdx = activeJob.currentStepIndex();

            // Find index of the latest completed DEPOSIT_ITEMS step (if any)
            int lastCompletedDepositIdx = -1;
            for (int i = 0; i < currentIdx && i < steps.size(); i++) {
                JobStep step = steps.get(i);
                if (step.state() == ExecutionState.COMPLETED && "DEPOSIT_ITEMS".equalsIgnoreCase(step.stepType())) {
                    lastCompletedDepositIdx = i;
                }
            }

            // 1. Outputs produced by completed steps after the last completed deposit step
            for (int i = lastCompletedDepositIdx + 1; i < currentIdx && i < steps.size(); i++) {
                JobStep completedStep = steps.get(i);
                if (completedStep.state() == ExecutionState.COMPLETED &&
                        completedStep.expectedOutput() != null && !completedStep.expectedOutput().isEmpty()) {
                    reserved.add(completedStep.expectedOutput().copy());
                }
            }

            // 2. Active step input requirement (physically held in storage for this step)
            // Note: activeStep.expectedOutput() is merely PREDICTED output, NOT physical inventory.
            // It must NOT be reserved while the step is executing (prevents ghost reservations).
            if (currentIdx >= 0 && currentIdx < steps.size()) {
                JobStep activeStep = steps.get(currentIdx);
                if (activeStep.state() != ExecutionState.COMPLETED && activeStep.state() != ExecutionState.FAILED) {
                    if (activeStep.itemRequirement() != null && !activeStep.itemRequirement().isEmpty()) {
                        reserved.add(activeStep.itemRequirement().copy());
                    }
                }
            }
            // Future steps (i > currentIdx) are NOT active: their inputs are not yet in-flight
            // and their outputs are unproduced (preventing double-counting and ghost reservations).
        }
        return java.util.Collections.unmodifiableList(reserved);
    }
}
