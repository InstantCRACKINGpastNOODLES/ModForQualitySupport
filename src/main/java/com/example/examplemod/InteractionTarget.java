package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Sealed hierarchy of targets for worker interactions.
 */
public sealed interface InteractionTarget permits
        InteractionTarget.BlockTarget,
        InteractionTarget.EntityTarget,
        InteractionTarget.AirTarget,
        InteractionTarget.EndpointTarget {

    record BlockTarget(BlockPos pos, Optional<Direction> face, Optional<Vec3> hitVec) implements InteractionTarget {
        public BlockTarget {
            Objects.requireNonNull(pos, "pos must not be null");
            Objects.requireNonNull(face, "face must not be null");
            Objects.requireNonNull(hitVec, "hitVec must not be null");
        }

        public BlockTarget(BlockPos pos) {
            this(pos, Optional.empty(), Optional.empty());
        }

        public BlockTarget(BlockPos pos, Direction face, Vec3 hitVec) {
            this(pos, Optional.ofNullable(face), Optional.ofNullable(hitVec));
        }
    }

    record EntityTarget(UUID entityId, Optional<Integer> networkId, Optional<Vec3> hitVec) implements InteractionTarget {
        public EntityTarget {
            Objects.requireNonNull(entityId, "entityId must not be null (UUID is primary identity)");
            Objects.requireNonNull(networkId, "networkId must not be null");
            Objects.requireNonNull(hitVec, "hitVec must not be null");
        }

        public EntityTarget(UUID entityId) {
            this(entityId, Optional.empty(), Optional.empty());
        }

        public EntityTarget(UUID entityId, int networkId, Vec3 hitVec) {
            this(entityId, Optional.of(networkId), Optional.ofNullable(hitVec));
        }
    }

    record AirTarget(Vec3 origin, Vec3 direction) implements InteractionTarget {
        public AirTarget {
            Objects.requireNonNull(origin, "origin must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
        }
    }

    record EndpointTarget(ResourceLocation endpointId) implements InteractionTarget {
        public EndpointTarget {
            Objects.requireNonNull(endpointId, "endpointId must not be null");
        }
    }
}
