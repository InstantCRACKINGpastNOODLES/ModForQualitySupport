package com.example.examplemod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/**
 * Registry and resolver for worker interactions.
 * Hosts both legacy request handlers for backward compatibility and Phase 1A interaction executors.
 */
public final class WorkerInteractionRegistry {

    // ---------------------------------------------------------
    // Phase 1A Interaction Core Executor Registry & Resolver
    // ---------------------------------------------------------

    private final Map<ResourceLocation, InteractionExecutor> executors = new LinkedHashMap<>();

    public void registerExecutor(InteractionExecutor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(executor.id(), "executor ID must not be null");
        if (executors.containsKey(executor.id())) {
            throw new IllegalArgumentException("Duplicate executor ID: " + executor.id());
        }
        executors.put(executor.id(), executor);
    }

    public boolean unregisterExecutor(ResourceLocation id) {
        Objects.requireNonNull(id, "id must not be null");
        return executors.remove(id) != null;
    }

    public Optional<InteractionExecutor> getExecutor(ResourceLocation id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(executors.get(id));
    }

    public List<InteractionExecutor> getAllExecutors() {
        return List.copyOf(executors.values());
    }

    public int getExecutorCount() {
        return executors.size();
    }

    public void clearExecutors() {
        executors.clear();
    }

    public ResolutionResult resolve(InteractionIntent intent) {
        return resolve(intent, RoutingPolicy.GENERIC_FIRST);
    }

    public ResolutionResult resolve(InteractionIntent intent, RoutingPolicy policy) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        // 1. Target & Action compatibility check
        List<InteractionExecutor> handlingExecutors = executors.values().stream()
                .filter(e -> e.canHandle(intent))
                .toList();

        if (handlingExecutors.isEmpty()) {
            return ResolutionResult.noCompatibleExecutor("No registered executor supports the given target and action");
        }

        // 2. Execution mode support check
        List<InteractionExecutor> modeMatching = handlingExecutors.stream()
                .filter(e -> e.supportsMode(intent.executionMode()))
                .toList();

        if (modeMatching.isEmpty()) {
            return ResolutionResult.unsupportedMode(intent.executionMode(),
                    "No compatible executor supports mode: " + intent.executionMode());
        }

        // 3. Partition by category
        List<InteractionExecutor> genericList = modeMatching.stream()
                .filter(e -> e.category() == InteractionExecutor.Category.GENERIC_PLAYER)
                .toList();
        List<InteractionExecutor> adapterList = modeMatching.stream()
                .filter(e -> e.category() == InteractionExecutor.Category.SPECIALIZED_ADAPTER)
                .toList();
        List<InteractionExecutor> capabilityList = modeMatching.stream()
                .filter(e -> e.category() == InteractionExecutor.Category.CAPABILITY_HANDLER)
                .toList();

        // 4. Apply routing policy
        List<InteractionExecutor> candidatePool;
        switch (policy) {
            case CAPABILITY_ONLY -> {
                if (capabilityList.isEmpty()) {
                    return ResolutionResult.rejectedByPolicy("No capability handler matches for CAPABILITY_ONLY policy");
                }
                candidatePool = capabilityList;
            }
            case GENERIC_STRICT -> {
                if (genericList.isEmpty()) {
                    return ResolutionResult.noCompatibleExecutor("No generic player executor available under GENERIC_STRICT policy");
                }
                candidatePool = genericList;
            }
            case SPECIALIZED_FIRST -> {
                if (!adapterList.isEmpty()) {
                    candidatePool = adapterList;
                } else if (!genericList.isEmpty()) {
                    candidatePool = genericList;
                } else {
                    return ResolutionResult.noCompatibleExecutor("No compatible player executor or specialized adapter found");
                }
            }
            case GENERIC_FIRST -> {
                if (!genericList.isEmpty()) {
                    candidatePool = genericList;
                } else if (!adapterList.isEmpty()) {
                    candidatePool = adapterList;
                } else if (intent.target() instanceof InteractionTarget.EndpointTarget && !capabilityList.isEmpty()) {
                    candidatePool = capabilityList;
                } else {
                    return ResolutionResult.noCompatibleExecutor("No compatible player executor or specialized adapter found");
                }
            }
            default -> throw new IllegalArgumentException("Unknown routing policy: " + policy);
        }

        // 5. Explicit priority evaluation & deterministic tie handling
        int maxPriority = candidatePool.stream()
                .mapToInt(InteractionExecutor::priority)
                .max()
                .orElse(Integer.MIN_VALUE);

        List<InteractionExecutor> topCandidates = candidatePool.stream()
                .filter(e -> e.priority() == maxPriority)
                .toList();

        if (topCandidates.size() == 1) {
            return ResolutionResult.resolved(topCandidates.get(0));
        }

        // Priority tie: sort lexicographically by resource location string to guarantee determinism
        List<ResourceLocation> tiedIds = topCandidates.stream()
                .map(InteractionExecutor::id)
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();

        return ResolutionResult.ambiguousRouting(tiedIds,
                "Ambiguous resolution between executors with equal priority " + maxPriority + ": " + tiedIds);
    }

    // ---------------------------------------------------------
    // Legacy Handler API (Preserved for compatibility)
    // ---------------------------------------------------------

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
