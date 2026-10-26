package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/** Instance-scoped adapter registry. Call only from trusted server code with server-checked availability. */
public final class WorkerInteractionRegistry {
    public interface Handler {
        boolean supports(WorkerInteraction.Request request);

        /** Preflight only: must not change the world or mutate a worker job. */
        WorkerInteraction.Result validate(WorkerInteraction.Request request);
    }

    /** Execution and verification run synchronously on the server; neither step changes a worker job. */
    public interface ExecutableHandler extends Handler {
        WorkerInteraction.Result execute(WorkerInteraction.Request request, ServerLevel level, Mob worker);

        /** Return true only after independently confirming the action's documented postcondition. */
        boolean verify(WorkerInteraction.Request request, ServerLevel level, Mob worker);
    }

    private final List<Handler> handlers = new ArrayList<>();

    public void register(Handler handler) {
        handlers.add(Objects.requireNonNull(handler));
    }

    public WorkerInteraction.Result dispatch(WorkerInteraction.Request request) {
        if (request == null || !request.valid()) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "Invalid or unauthorized request");
        }
        if (!request.available()) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.UNAVAILABLE, "Target unavailable");
        }
        Handler selected = null;
        for (Handler handler : handlers) {
            if (handler.supports(request)) {
                if (selected != null) {
                    return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "Ambiguous handlers");
                }
                selected = handler;
            }
        }
        if (selected == null) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "Unsupported action");
        }
        WorkerInteraction.Result result = selected.validate(request);
        return result == null ? new WorkerInteraction.Result(WorkerInteraction.Outcome.FAILED, "Handler returned no result") : result;
    }

    /** The permission predicate must check the target's actual permissions, not just worker ownership. */
    public WorkerInteraction.Result execute(WorkerInteraction.Request request, ServerLevel level, Mob worker,
                                            ServerPlayer player, Predicate<WorkerInteraction.Request> permitted) {
        if (request == null || !request.valid() || level == null || !level.getServer().isSameThread() ||
                worker == null || player == null || permitted == null ||
                worker.level() != level || player.level() != level || !worker.isAlive() || worker.isRemoved() ||
                !worker.getUUID().equals(request.entityId()) || !player.getUUID().equals(request.requester()) ||
                !level.dimension().location().equals(request.dimension()) || !worker.hasData(ExampleMod.WORKER_PROFILE) ||
                !worker.getData(ExampleMod.WORKER_PROFILE).equals(request.worker())) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "Invalid live worker or owner");
        }
        if (request.target() instanceof WorkerInteraction.Block block && !level.hasChunkAt(block.position())) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.UNAVAILABLE, "Target chunk unavailable");
        }
        if (!request.available()) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.UNAVAILABLE, "Target unavailable");
        }
        if (!permitted.test(request)) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "Target permission denied");
        }
        return executeValidated(request, level, worker);
    }

    WorkerInteraction.Result executeValidated(WorkerInteraction.Request request, ServerLevel level, Mob worker) {
        Handler selected = null;
        for (Handler handler : handlers) {
            if (handler.supports(request)) {
                if (selected != null) {
                    return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "Ambiguous handlers");
                }
                selected = handler;
            }
        }
        if (!(selected instanceof ExecutableHandler executable)) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.REJECTED, "No executable handler");
        }
        WorkerInteraction.Result preflight = executable.validate(request);
        if (preflight == null || preflight.outcome() != WorkerInteraction.Outcome.SUCCESS) {
            return preflight == null ? new WorkerInteraction.Result(WorkerInteraction.Outcome.FAILED, "No preflight result") : preflight;
        }
        WorkerInteraction.Result attempt = executable.execute(request, level, worker);
        if (attempt == null) {
            return new WorkerInteraction.Result(WorkerInteraction.Outcome.FAILED, "No execution result");
        }
        if (attempt.outcome() != WorkerInteraction.Outcome.SUCCESS) {
            return attempt;
        }
        return executable.verify(request, level, worker)
                ? attempt : new WorkerInteraction.Result(WorkerInteraction.Outcome.UNAVAILABLE, "Result not verified");
    }
}