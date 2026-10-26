package com.example.examplemod;

import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** One synchronous job check per ticking worker; MOVE requests paths but never completes a job. */
public final class WorkerExecutionLoop {
    enum Result { INELIGIBLE, UNAVAILABLE, SKIPPED, FAILED, REQUESTED }
    private static final Map<PathfinderMob, WorkerTarget> REQUESTED_PATHS = new WeakHashMap<>();

    private WorkerExecutionLoop() {
    }

    static Result step(WorkerProfile profile, UUID entityId, UUID ownerId, boolean server,
                       boolean available, boolean permitted, Supplier<Result> action) {
        if (!server || profile == null || entityId == null || ownerId == null ||
                !profile.entityId().equals(entityId) || !profile.ownerId().equals(ownerId) ||
                profile.job().isEmpty() || !WorkerJobs.isKnown(profile.job().get().type()) ||
                (profile.job().get().status() != JobStatus.ASSIGNED &&
                        profile.job().get().status() != JobStatus.RUNNING)) {
            return Result.INELIGIBLE;
        }
        if (!available) {
            return Result.UNAVAILABLE;
        }
        if (!permitted || action == null) {
            return Result.INELIGIBLE;
        }
        try {
            Result result = action.get();
            return result == null ? Result.FAILED : result;
        } catch (RuntimeException exception) {
            return Result.FAILED;
        }
    }

    static Result navigate(WorkerProfile profile, net.minecraft.resources.ResourceLocation dimension,
                           boolean loaded, boolean valid, boolean permitted, boolean navigating,
                           long tick, Supplier<Boolean> request) {
        if (profile == null || profile.job().isEmpty() ||
                !WorkerJobs.MOVE.equals(profile.job().get().type()) ||
                (profile.job().get().status() != JobStatus.ASSIGNED &&
                        profile.job().get().status() != JobStatus.RUNNING) ||
                profile.job().get().target().isEmpty() ||
                !profile.dimension().equals(dimension) ||
                !profile.job().get().target().get().dimension().equals(dimension) ||
                !permitted || !valid) {
            return Result.INELIGIBLE;
        }
        if (!loaded) return Result.UNAVAILABLE;
        if (navigating || tick % 40 != 0) return Result.SKIPPED;
        try {
            return request != null && Boolean.TRUE.equals(request.get()) ? Result.REQUESTED : Result.FAILED;
        } catch (RuntimeException exception) {
            return Result.FAILED;
        }
    }

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob worker) || !(worker.level() instanceof ServerLevel level) ||
                !level.getServer().isSameThread() || !worker.isAlive() || worker.isRemoved() ||
                !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            return;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(profile.ownerId());
        boolean available = owner != null && owner.level() == level && !owner.isRemoved();
        if (worker instanceof PathfinderMob pathfinder && REQUESTED_PATHS.containsKey(pathfinder)) {
            WorkerTarget requested = REQUESTED_PATHS.get(pathfinder);
            if (!available || !owner.mayBuild() || profile.job().isEmpty() ||
                    !WorkerJobs.MOVE.equals(profile.job().get().type()) ||
                    (profile.job().get().status() != JobStatus.ASSIGNED &&
                            profile.job().get().status() != JobStatus.RUNNING) ||
                    !profile.job().get().target().equals(java.util.Optional.of(requested)) ||
                    !requested.dimension().equals(level.dimension().location()) ||
                    !level.hasChunkAt(requested.position())) {
                pathfinder.getNavigation().stop();
                REQUESTED_PATHS.remove(pathfinder);
            } else if (pathfinder.getNavigation().isDone()) {
                REQUESTED_PATHS.remove(pathfinder);
            }
        }
        step(profile, worker.getUUID(), profile.ownerId(), true, available,
                available && owner.mayBuild() && profile.dimension().equals(level.dimension().location()),
                () -> {
                    if (!(worker instanceof PathfinderMob pathfinder) || profile.job().isEmpty() ||
                            profile.job().get().target().isEmpty()) return Result.SKIPPED;
                    WorkerTarget target = profile.job().get().target().get();
                    var pos = target.position();
                    boolean loaded = level.hasChunkAt(pos);
                    return navigate(profile, level.dimension().location(), loaded,
                            level.isInWorldBounds(pos) && worker.distanceToSqr(pos.getX() + 0.5,
                                    pos.getY() + 0.5, pos.getZ() + 0.5) <= 32 * 32,
                            owner.mayBuild(), !pathfinder.getNavigation().isDone(), level.getGameTime(),
                            () -> {
                                boolean started = pathfinder.getNavigation().moveTo(pos.getX() + 0.5,
                                        pos.getY(), pos.getZ() + 0.5, 1.0);
                                if (started) REQUESTED_PATHS.put(pathfinder, target);
                                return started;
                            });
                });
    }
}