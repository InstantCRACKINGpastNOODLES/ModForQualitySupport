package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Context contract for interaction execution.
 * Preserves the Phase 1A marker contract and provides non-breaking default accessors
 * for runtime execution environments (level, worker, owner, positions, player, itemSink).
 */
public interface InteractionContext {
    InteractionContext EMPTY = new InteractionContext() {};

    default Optional<ServerLevel> level() {
        return Optional.empty();
    }

    default Optional<UUID> workerId() {
        return Optional.empty();
    }

    default Optional<UUID> ownerId() {
        return Optional.empty();
    }

    default Optional<Vec3> workerPosition() {
        return Optional.empty();
    }

    default Optional<Vec3> workerEyePosition() {
        return Optional.empty();
    }

    default Optional<ServerPlayer> player() {
        return Optional.empty();
    }

    default Optional<ItemReconciliationSink> itemSink() {
        return Optional.empty();
    }
}
