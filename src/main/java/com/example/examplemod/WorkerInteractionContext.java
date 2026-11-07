package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Rich, immutable interaction execution context for worker interactions.
 * Supplies authoritative server level, worker identity, owner identity, spatial positions,
 * hand item access, item reconciliation sink, and permission checking without mutating game state directly.
 */
public final class WorkerInteractionContext implements InteractionContext {

    @FunctionalInterface
    public interface ItemAccess {
        ItemStack get();

        default void set(ItemStack stack) {
            // Default no-op if read-only
        }

        static ItemAccess of(Supplier<ItemStack> getter, Consumer<ItemStack> setter) {
            Objects.requireNonNull(getter, "getter must not be null");
            Objects.requireNonNull(setter, "setter must not be null");
            return new ItemAccess() {
                @Override
                public ItemStack get() {
                    return getter.get();
                }

                @Override
                public void set(ItemStack stack) {
                    setter.accept(stack != null ? stack : ItemStack.EMPTY);
                }
            };
        }

        static ItemAccess readOnly(Supplier<ItemStack> getter) {
            Objects.requireNonNull(getter, "getter must not be null");
            return getter::get;
        }

        static ItemAccess ofMutable(ItemStack initial) {
            final ItemStack[] holder = new ItemStack[]{initial != null ? initial : ItemStack.EMPTY};
            return new ItemAccess() {
                @Override
                public ItemStack get() {
                    return holder[0];
                }

                @Override
                public void set(ItemStack stack) {
                    holder[0] = stack != null ? stack : ItemStack.EMPTY;
                }
            };
        }

        static ItemAccess empty() {
            return () -> ItemStack.EMPTY;
        }
    }

    private final ServerLevel level;
    private final UUID workerId;
    private final UUID ownerId;
    private final Vec3 workerPosition;
    private final Vec3 workerEyePosition;
    private final ServerPlayer playerOverride;
    private final ItemAccess mainHandItem;
    private final ItemAccess offHandItem;
    private final ItemReconciliationSink itemSink;
    private final Predicate<InteractionIntent> permissionPredicate;

    private WorkerInteractionContext(
            ServerLevel level,
            UUID workerId,
            UUID ownerId,
            Vec3 workerPosition,
            Vec3 workerEyePosition,
            ServerPlayer playerOverride,
            ItemAccess mainHandItem,
            ItemAccess offHandItem,
            ItemReconciliationSink itemSink,
            Predicate<InteractionIntent> permissionPredicate
    ) {
        this.level = level;
        this.workerId = workerId;
        this.ownerId = ownerId;
        this.workerPosition = workerPosition;
        this.workerEyePosition = workerEyePosition;
        this.playerOverride = playerOverride;
        this.mainHandItem = mainHandItem != null ? mainHandItem : ItemAccess.empty();
        this.offHandItem = offHandItem != null ? offHandItem : ItemAccess.empty();
        this.itemSink = itemSink != null ? itemSink : ItemReconciliationSink.of(this.mainHandItem);
        this.permissionPredicate = permissionPredicate;
    }

    @Override
    public Optional<ServerLevel> level() {
        return Optional.ofNullable(level);
    }

    @Override
    public Optional<UUID> workerId() {
        return Optional.ofNullable(workerId);
    }

    @Override
    public Optional<UUID> ownerId() {
        return Optional.ofNullable(ownerId);
    }

    @Override
    public Optional<Vec3> workerPosition() {
        return Optional.ofNullable(workerPosition);
    }

    @Override
    public Optional<Vec3> workerEyePosition() {
        return Optional.ofNullable(workerEyePosition != null ? workerEyePosition : workerPosition);
    }

    @Override
    public Optional<ServerPlayer> player() {
        return Optional.ofNullable(playerOverride);
    }

    @Override
    public Optional<ItemReconciliationSink> itemSink() {
        return Optional.ofNullable(itemSink);
    }

    public ItemReconciliationSink getItemSink() {
        return itemSink;
    }

    public ItemAccess itemAccess(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? offHandItem : mainHandItem;
    }

    public Optional<Predicate<InteractionIntent>> permissionPredicate() {
        return Optional.ofNullable(permissionPredicate);
    }

    public boolean isPermitted(InteractionIntent intent) {
        if (permissionPredicate == null) {
            return true;
        }
        return permissionPredicate.test(intent);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ServerLevel level;
        private UUID workerId;
        private UUID ownerId;
        private Vec3 workerPosition;
        private Vec3 workerEyePosition;
        private ServerPlayer playerOverride;
        private ItemAccess mainHandItem = ItemAccess.empty();
        private ItemAccess offHandItem = ItemAccess.empty();
        private ItemReconciliationSink itemSink;
        private Predicate<InteractionIntent> permissionPredicate;

        public Builder level(ServerLevel level) {
            this.level = level;
            return this;
        }

        public Builder workerId(UUID workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder ownerId(UUID ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder workerPosition(Vec3 workerPosition) {
            this.workerPosition = workerPosition;
            return this;
        }

        public Builder workerEyePosition(Vec3 workerEyePosition) {
            this.workerEyePosition = workerEyePosition;
            return this;
        }

        public Builder playerOverride(ServerPlayer playerOverride) {
            this.playerOverride = playerOverride;
            return this;
        }

        public Builder mainHandItem(ItemAccess mainHandItem) {
            this.mainHandItem = mainHandItem;
            return this;
        }

        public Builder offHandItem(ItemAccess offHandItem) {
            this.offHandItem = offHandItem;
            return this;
        }

        public Builder itemSink(ItemReconciliationSink itemSink) {
            this.itemSink = itemSink;
            return this;
        }

        public Builder permissionPredicate(Predicate<InteractionIntent> permissionPredicate) {
            this.permissionPredicate = permissionPredicate;
            return this;
        }

        public WorkerInteractionContext build() {
            return new WorkerInteractionContext(
                    level,
                    workerId,
                    ownerId,
                    workerPosition,
                    workerEyePosition,
                    playerOverride,
                    mainHandItem,
                    offHandItem,
                    itemSink,
                    permissionPredicate
            );
        }
    }
}
