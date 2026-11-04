package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A condition that must be satisfied before a job or job step can transition to RUNNING.
 * If unsatisfied, the job transitions to WAITING with an informative wait reason.
 */
public record JobDependency(
        Type type,
        String parameter,
        int count
) {
    public enum Type {
        MATERIAL_AVAILABLE,
        STORAGE_SPACE_AVAILABLE,
        MACHINE_AVAILABLE,
        TOOL_AVAILABLE,
        STEP_COMPLETED,
        COOLDOWN_EXPIRED
    }

    public static final Codec<JobDependency> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(Type::valueOf, Type::name).fieldOf("type").forGetter(JobDependency::type),
            Codec.STRING.optionalFieldOf("parameter", "").forGetter(JobDependency::parameter),
            Codec.INT.optionalFieldOf("count", 1).forGetter(JobDependency::count)
    ).apply(instance, JobDependency::new));

    public JobDependency(Type type, String parameter) {
        this(type, parameter, 1);
    }

    public static JobDependency tool(String toolType) {
        return new JobDependency(Type.TOOL_AVAILABLE, toolType, 1);
    }

    public static JobDependency material(ResourceLocation itemId, int count) {
        return new JobDependency(Type.MATERIAL_AVAILABLE, itemId.toString(), count);
    }

    public static JobDependency storageSpace(BlockPos pos) {
        return new JobDependency(Type.STORAGE_SPACE_AVAILABLE, pos.asLong() + "", 1);
    }

    public static JobDependency machine(BlockPos pos) {
        return new JobDependency(Type.MACHINE_AVAILABLE, pos.asLong() + "", 1);
    }

    public static JobDependency step(String stepId) {
        return new JobDependency(Type.STEP_COMPLETED, stepId, 1);
    }

    public boolean isSatisfied(ServerLevel level, Mob worker, WorkerProfile profile, BlockPos targetPos) {
        if (level == null || worker == null || profile == null) return false;
        return switch (type) {
            case TOOL_AVAILABLE -> {
                ItemStack mainHand = worker.getMainHandItem();
                if ("pickaxe".equalsIgnoreCase(parameter)) {
                    yield WorkerJobCapability.Registry.canPerformMining(worker, mainHand);
                } else if ("hoe".equalsIgnoreCase(parameter)) {
                    yield WorkerJobCapability.Registry.canPerformFarming(worker, mainHand);
                }
                yield !mainHand.isEmpty();
            }
            case MATERIAL_AVAILABLE -> {
                if (parameter.isEmpty()) yield true;
                ResourceLocation itemLoc = ResourceLocation.tryParse(parameter);
                if (itemLoc == null) yield true;
                Item targetItem = BuiltInRegistries.ITEM.get(itemLoc);
                if (targetItem == null) yield true;

                int found = 0;
                if (worker.hasData(ExampleMod.WORKER_STORAGE)) {
                    WorkerStorage ws = worker.getData(ExampleMod.WORKER_STORAGE);
                    for (ItemStack s : ws.items()) {
                        if (s.is(targetItem)) found += s.getCount();
                    }
                }
                yield found >= count;
            }
            case STORAGE_SPACE_AVAILABLE -> {
                BlockPos pos = targetPos;
                if (!parameter.isEmpty()) {
                    try {
                        pos = BlockPos.of(Long.parseLong(parameter));
                    } catch (NumberFormatException ignored) {}
                }
                if (pos == null || !level.hasChunkAt(pos)) yield false;
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        if (container.getItem(i).isEmpty()) yield true;
                    }
                    yield false;
                }
                yield true;
            }
            case MACHINE_AVAILABLE -> {
                BlockPos pos = targetPos;
                if (!parameter.isEmpty()) {
                    try {
                        pos = BlockPos.of(Long.parseLong(parameter));
                    } catch (NumberFormatException ignored) {}
                }
                if (pos == null || !level.hasChunkAt(pos)) yield false;
                yield level.getBlockEntity(pos) != null;
            }
            case STEP_COMPLETED, COOLDOWN_EXPIRED -> true;
        };
    }

    public String getWaitDescription() {
        return switch (type) {
            case TOOL_AVAILABLE -> "Waiting for tool: " + parameter;
            case MATERIAL_AVAILABLE -> "Waiting for material: " + count + "x " + parameter;
            case STORAGE_SPACE_AVAILABLE -> "Waiting for storage space";
            case MACHINE_AVAILABLE -> "Waiting for machine at target";
            case STEP_COMPLETED -> "Waiting for preceding step: " + parameter;
            case COOLDOWN_EXPIRED -> "Waiting for cooldown";
        };
    }
}
