package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.InteractionHand;

/**
 * Immutable interaction specification.
 * Describes an intended action without owning or mutating actual game items or world state.
 */
public record InteractionIntent(
        UUID intentId,
        ActionType actionType,
        ExecutionMode executionMode,
        InteractionTarget target,
        InteractionHand hand,
        Optional<ItemDescriptor> itemDescriptor,
        double maxReachDistance,
        boolean requireLineOfSight,
        Optional<Integer> durationTicks,
        Optional<Integer> repeatIntervalTicks
) {
    public static final double DEFAULT_REACH_DISTANCE = 4.5d;

    public InteractionIntent {
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(executionMode, "executionMode must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(hand, "hand must not be null");
        Objects.requireNonNull(itemDescriptor, "itemDescriptor must not be null");
        Objects.requireNonNull(durationTicks, "durationTicks must not be null");
        Objects.requireNonNull(repeatIntervalTicks, "repeatIntervalTicks must not be null");

        if (Double.isNaN(maxReachDistance) || Double.isInfinite(maxReachDistance) || maxReachDistance <= 0.0) {
            throw new IllegalArgumentException("maxReachDistance must be positive and finite, got: " + maxReachDistance);
        }

        if (durationTicks.isPresent() && durationTicks.get() <= 0) {
            throw new IllegalArgumentException("durationTicks must be positive when specified, got: " + durationTicks.get());
        }

        if (repeatIntervalTicks.isPresent() && repeatIntervalTicks.get() <= 0) {
            throw new IllegalArgumentException("repeatIntervalTicks must be positive when specified, got: " + repeatIntervalTicks.get());
        }

        switch (executionMode) {
            case CLICK -> {
                if (durationTicks.isPresent()) {
                    throw new IllegalArgumentException("CLICK mode must not specify durationTicks");
                }
                if (repeatIntervalTicks.isPresent()) {
                    throw new IllegalArgumentException("CLICK mode must not specify repeatIntervalTicks");
                }
            }
            case HOLD -> {
                if (repeatIntervalTicks.isPresent()) {
                    throw new IllegalArgumentException("HOLD mode must not specify repeatIntervalTicks");
                }
            }
            case REPEAT -> {
                if (repeatIntervalTicks.isEmpty()) {
                    throw new IllegalArgumentException("REPEAT mode requires repeatIntervalTicks");
                }
            }
            case PRESS_RELEASE -> {
                if (durationTicks.isEmpty()) {
                    throw new IllegalArgumentException("PRESS_RELEASE mode requires durationTicks");
                }
                if (repeatIntervalTicks.isPresent()) {
                    throw new IllegalArgumentException("PRESS_RELEASE mode must not specify repeatIntervalTicks");
                }
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID intentId = UUID.randomUUID();
        private ActionType actionType = ActionType.RIGHT_CLICK;
        private ExecutionMode executionMode = ExecutionMode.CLICK;
        private InteractionTarget target;
        private InteractionHand hand = InteractionHand.MAIN_HAND;
        private Optional<ItemDescriptor> itemDescriptor = Optional.empty();
        private double maxReachDistance = DEFAULT_REACH_DISTANCE;
        private boolean requireLineOfSight = true;
        private Optional<Integer> durationTicks = Optional.empty();
        private Optional<Integer> repeatIntervalTicks = Optional.empty();

        public Builder intentId(UUID intentId) {
            this.intentId = intentId;
            return this;
        }

        public Builder actionType(ActionType actionType) {
            this.actionType = actionType;
            return this;
        }

        public Builder executionMode(ExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder target(InteractionTarget target) {
            this.target = target;
            return this;
        }

        public Builder hand(InteractionHand hand) {
            this.hand = hand;
            return this;
        }

        public Builder itemDescriptor(ItemDescriptor itemDescriptor) {
            this.itemDescriptor = Optional.ofNullable(itemDescriptor);
            return this;
        }

        public Builder itemDescriptor(Optional<ItemDescriptor> itemDescriptor) {
            this.itemDescriptor = Objects.requireNonNull(itemDescriptor, "itemDescriptor must not be null");
            return this;
        }

        public Builder maxReachDistance(double maxReachDistance) {
            this.maxReachDistance = maxReachDistance;
            return this;
        }

        public Builder requireLineOfSight(boolean requireLineOfSight) {
            this.requireLineOfSight = requireLineOfSight;
            return this;
        }

        public Builder durationTicks(int durationTicks) {
            this.durationTicks = Optional.of(durationTicks);
            return this;
        }

        public Builder durationTicks(Optional<Integer> durationTicks) {
            this.durationTicks = Objects.requireNonNull(durationTicks, "durationTicks must not be null");
            return this;
        }

        public Builder repeatIntervalTicks(int repeatIntervalTicks) {
            this.repeatIntervalTicks = Optional.of(repeatIntervalTicks);
            return this;
        }

        public Builder repeatIntervalTicks(Optional<Integer> repeatIntervalTicks) {
            this.repeatIntervalTicks = Objects.requireNonNull(repeatIntervalTicks, "repeatIntervalTicks must not be null");
            return this;
        }

        public InteractionIntent build() {
            return new InteractionIntent(
                    intentId,
                    actionType,
                    executionMode,
                    target,
                    hand,
                    itemDescriptor,
                    maxReachDistance,
                    requireLineOfSight,
                    durationTicks,
                    repeatIntervalTicks
            );
        }
    }
}
