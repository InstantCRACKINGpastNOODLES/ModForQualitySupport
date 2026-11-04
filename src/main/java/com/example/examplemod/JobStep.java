package com.example.examplemod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/**
 * A discrete operational step within a worker Job.
 */
public record JobStep(
        UUID stepId,
        String stepType,
        Optional<BlockPos> targetPos,
        Optional<Direction> direction,
        ItemStack itemRequirement,
        ItemStack expectedOutput,
        int repetitions,
        int completedRepetitions,
        ExecutionState state,
        Optional<String> waitReason,
        Optional<String> failureReason,
        List<JobDependency> dependencies
) {
    public static final Codec<JobStep> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("step_id").forGetter(JobStep::stepId),
            Codec.STRING.fieldOf("step_type").forGetter(JobStep::stepType),
            BlockPos.CODEC.optionalFieldOf("target_pos").forGetter(JobStep::targetPos),
            Direction.CODEC.optionalFieldOf("direction").forGetter(JobStep::direction),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("item_requirement", ItemStack.EMPTY).forGetter(JobStep::itemRequirement),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("expected_output", ItemStack.EMPTY).forGetter(JobStep::expectedOutput),
            Codec.INT.optionalFieldOf("repetitions", 1).forGetter(JobStep::repetitions),
            Codec.INT.optionalFieldOf("completed_repetitions", 0).forGetter(JobStep::completedRepetitions),
            ExecutionState.CODEC.fieldOf("state").forGetter(JobStep::state),
            Codec.STRING.optionalFieldOf("wait_reason").forGetter(JobStep::waitReason),
            Codec.STRING.optionalFieldOf("failure_reason").forGetter(JobStep::failureReason),
            JobDependency.CODEC.listOf().optionalFieldOf("dependencies", List.of()).forGetter(JobStep::dependencies)
    ).apply(instance, JobStep::new));

    public static JobStep moveTo(BlockPos pos) {
        return new JobStep(
                UUID.randomUUID(),
                "MOVE_TO_POSITION",
                Optional.of(pos),
                Optional.empty(),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                1,
                0,
                ExecutionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );
    }

    public static JobStep mineBlock(BlockPos pos, Direction dir, int repetitions) {
        return new JobStep(
                UUID.randomUUID(),
                "MINE_BLOCK",
                Optional.of(pos),
                Optional.ofNullable(dir),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                repetitions,
                0,
                ExecutionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                List.of(JobDependency.tool("pickaxe"))
        );
    }

    public static JobStep harvestCrop(BlockPos pos, int repetitions) {
        return new JobStep(
                UUID.randomUUID(),
                "HARVEST_CROP",
                Optional.of(pos),
                Optional.empty(),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                repetitions,
                0,
                ExecutionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );
    }

    public static JobStep depositItems(BlockPos storagePos) {
        return new JobStep(
                UUID.randomUUID(),
                "DEPOSIT_ITEMS",
                Optional.of(storagePos),
                Optional.empty(),
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                1,
                0,
                ExecutionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                List.of(JobDependency.storageSpace(storagePos))
        );
    }

    public static JobStep extractItems(BlockPos storagePos, ItemStack requirement, int repetitions) {
        return new JobStep(
                UUID.randomUUID(),
                "EXTRACT_ITEMS",
                Optional.of(storagePos),
                Optional.empty(),
                requirement,
                ItemStack.EMPTY,
                repetitions,
                0,
                ExecutionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                List.of()
        );
    }

    public static JobStep interactMachine(BlockPos machinePos, Direction face, ItemStack inputItem, ItemStack expectedOutput, int repetitions) {
        return new JobStep(
                UUID.randomUUID(),
                "INTERACT_MACHINE",
                Optional.of(machinePos),
                Optional.ofNullable(face),
                inputItem,
                expectedOutput,
                repetitions,
                0,
                ExecutionState.PENDING,
                Optional.empty(),
                Optional.empty(),
                List.of(JobDependency.machine(machinePos))
        );
    }

    public boolean isComplete() {
        return state == ExecutionState.COMPLETED || completedRepetitions >= repetitions;
    }

    public JobStep withProgress(int completed) {
        ExecutionState nextState = (completed >= repetitions) ? ExecutionState.COMPLETED : state;
        return new JobStep(
                stepId, stepType, targetPos, direction, itemRequirement, expectedOutput,
                repetitions, completed, nextState, waitReason, failureReason, dependencies
        );
    }

    public JobStep withState(ExecutionState newState, String reason) {
        Optional<String> wait = (newState == ExecutionState.WAITING) ? Optional.ofNullable(reason) : Optional.empty();
        Optional<String> fail = (newState == ExecutionState.FAILED) ? Optional.ofNullable(reason) : Optional.empty();
        return new JobStep(
                stepId, stepType, targetPos, direction, itemRequirement, expectedOutput,
                repetitions, completedRepetitions, newState, wait, fail, dependencies
        );
    }
}
