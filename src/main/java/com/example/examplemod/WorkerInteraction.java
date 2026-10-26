package com.example.examplemod;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Describes an interaction; dispatch does not execute world changes. */
public final class WorkerInteraction {
    private WorkerInteraction() {
    }

    public sealed interface Target permits Block, Entity, Endpoint {
    }

    public record Block(BlockPos position) implements Target {
    }

    public record Entity(UUID id) implements Target {
    }

    public record Endpoint(ResourceLocation id) implements Target {
    }

    public record Request(ResourceLocation action, WorkerProfile worker, UUID requester, UUID entityId,
                          ResourceLocation dimension, Target target, boolean server, boolean available,
                          Map<String, String> parameters) {
        public Request {
            parameters = parameters == null || parameters.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getValue() == null) ? null : Map.copyOf(parameters);
        }

        public boolean valid() {
            return server && action != null && worker != null && requester != null && entityId != null &&
                    dimension != null && target != null && parameters != null &&
                    worker.ownerId().equals(requester) && worker.entityId().equals(entityId) &&
                    worker.dimension().equals(dimension) && switch (target) {
                        case Block block -> block.position() != null;
                        case Entity entity -> entity.id() != null;
                        case Endpoint endpoint -> endpoint.id() != null;
                    };
        }
    }

    public enum Outcome { SUCCESS, REJECTED, FAILED, UNAVAILABLE }

    public record Result(Outcome outcome, String reason) {
        public Result {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
        }
    }
}