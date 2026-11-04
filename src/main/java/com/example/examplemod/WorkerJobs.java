package com.example.examplemod;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;

public final class WorkerJobs {
    public static final ResourceLocation WAIT = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "wait");
    public static final ResourceLocation MOVE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "move");
    public static final ResourceLocation MINE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "mine");
    public static final ResourceLocation FARM = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "farm");

    private static final Map<ResourceLocation, Predicate<Mob>> DEFINITIONS = Map.of(
            WAIT, mob -> mob instanceof PathfinderMob,
            MOVE, mob -> mob instanceof PathfinderMob,
            MINE, mob -> mob instanceof PathfinderMob,
            FARM, mob -> mob instanceof PathfinderMob);

    private WorkerJobs() {
    }

    public static Optional<WorkerJob> assign(ResourceLocation type, Mob mob) {
        Predicate<Mob> eligibility = DEFINITIONS.get(type);
        return eligibility != null && eligibility.test(mob) ? Optional.of(WorkerJob.assigned(type)) : Optional.empty();
    }

    public static boolean isKnown(ResourceLocation type) {
        return DEFINITIONS.containsKey(type);
    }
}
