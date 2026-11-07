package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Safe item exposure and reconciliation utility for player-like interactions.
 * Guarantees zero item duplication, zero item voiding, safe handling of consumption,
 * item damage, item transformation, and secondary inventory drops with explicit sink handoff.
 */
public final class InteractionItemReconciler {

    /**
     * Abstraction over player inventory access allowing safe unit testing and mocking.
     */
    public interface PlayerInventoryAccess {
        ItemStack getItemInHand(InteractionHand hand);
        void setItemInHand(InteractionHand hand, ItemStack stack);
        List<ItemStack> getSecondaryItems();
        void clearSecondaryItems();

        static PlayerInventoryAccess of(ServerPlayer player) {
            return new PlayerInventoryAccess() {
                @Override
                public ItemStack getItemInHand(InteractionHand hand) {
                    if (player == null) return ItemStack.EMPTY;
                    try {
                        ItemStack item = player.getItemInHand(hand);
                        return item != null ? item : ItemStack.EMPTY;
                    } catch (Throwable t) {
                        return ItemStack.EMPTY;
                    }
                }

                @Override
                public void setItemInHand(InteractionHand hand, ItemStack stack) {
                    if (player != null) {
                        try {
                            player.setItemInHand(hand, stack != null ? stack : ItemStack.EMPTY);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public List<ItemStack> getSecondaryItems() {
                    if (player == null) return List.of();
                    try {
                        if (player.getInventory() == null) return List.of();
                        List<ItemStack> items = new ArrayList<>();
                        int size = player.getInventory().items.size();
                        for (int i = 0; i < size; i++) {
                            if (i == player.getInventory().selected) continue;
                            ItemStack s = player.getInventory().items.get(i);
                            if (s != null && !s.isEmpty()) {
                                items.add(s.copy());
                            }
                        }
                        return items;
                    } catch (Throwable t) {
                        return List.of();
                    }
                }

                @Override
                public void clearSecondaryItems() {
                    if (player == null) return;
                    try {
                        if (player.getInventory() == null) return;
                        int size = player.getInventory().items.size();
                        for (int i = 0; i < size; i++) {
                            if (i == player.getInventory().selected) continue;
                            player.getInventory().items.set(i, ItemStack.EMPTY);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void retainOnlySecondaryItems(List<ItemStack> unhandled) {
                    if (player == null) return;
                    try {
                        if (player.getInventory() == null) return;
                        List<ItemStack> remaining = new ArrayList<>();
                        if (unhandled != null) {
                            for (ItemStack u : unhandled) {
                                if (u != null && !u.isEmpty()) {
                                    remaining.add(u.copy());
                                }
                            }
                        }
                        int size = player.getInventory().items.size();
                        for (int i = 0; i < size; i++) {
                            if (i == player.getInventory().selected) continue;
                            ItemStack current = player.getInventory().items.get(i);
                            if (current == null || current.isEmpty()) continue;

                            boolean keep = false;
                            for (java.util.Iterator<ItemStack> it = remaining.iterator(); it.hasNext(); ) {
                                ItemStack rem = it.next();
                                if (ItemStack.isSameItemSameComponents(current, rem) && current.getCount() == rem.getCount()) {
                                    keep = true;
                                    it.remove();
                                    break;
                                }
                            }
                            if (!keep) {
                                player.getInventory().items.set(i, ItemStack.EMPTY);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
        }

        default void retainOnlySecondaryItems(List<ItemStack> unhandled) {
            clearSecondaryItems();
        }

        static PlayerInventoryAccess simple(ItemStack initialHand) {
            return new SimpleInventoryAccess(initialHand);
        }
    }

    public static class SimpleInventoryAccess implements PlayerInventoryAccess {
        private ItemStack mainHand;
        private ItemStack offHand = ItemStack.EMPTY;
        private final List<ItemStack> secondary = new ArrayList<>();

        public SimpleInventoryAccess(ItemStack initialMainHand) {
            this.mainHand = initialMainHand != null ? initialMainHand : ItemStack.EMPTY;
        }

        @Override
        public ItemStack getItemInHand(InteractionHand hand) {
            return hand == InteractionHand.OFF_HAND ? offHand : mainHand;
        }

        @Override
        public void setItemInHand(InteractionHand hand, ItemStack stack) {
            ItemStack val = stack != null ? stack : ItemStack.EMPTY;
            if (hand == InteractionHand.OFF_HAND) {
                this.offHand = val;
            } else {
                this.mainHand = val;
            }
        }

        public void addSecondaryItem(ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                secondary.add(stack);
            }
        }

        @Override
        public List<ItemStack> getSecondaryItems() {
            return new ArrayList<>(secondary);
        }

        @Override
        public void clearSecondaryItems() {
            secondary.clear();
        }

        @Override
        public void retainOnlySecondaryItems(List<ItemStack> unhandled) {
            secondary.clear();
            if (unhandled != null) {
                for (ItemStack u : unhandled) {
                    if (u != null && !u.isEmpty()) {
                        secondary.add(u.copy());
                    }
                }
            }
        }
    }

    public record PreparationSnapshot(
            ItemStack initialStack,
            int initialCount,
            int initialDamage,
            boolean wasEmpty
    ) {}

    public record ReconciliationResult(
            ItemStack resultingHandItem,
            List<ItemStack> secondaryItems,
            boolean consumed,
            boolean partiallyConsumed,
            boolean damaged,
            boolean transformed
    ) {
        public ReconciliationResult {
            Objects.requireNonNull(resultingHandItem, "resultingHandItem must not be null");
            Objects.requireNonNull(secondaryItems, "secondaryItems must not be null");
        }

        public static ReconciliationResult noOp(ItemStack stack) {
            return new ReconciliationResult(
                    stack != null ? stack : ItemStack.EMPTY,
                    List.of(),
                    false,
                    false,
                    false,
                    false
            );
        }
    }

    private InteractionItemReconciler() {}

    // -------------------------------------------------------------------------
    // Preparation
    // -------------------------------------------------------------------------

    public static PreparationSnapshot prepare(ServerPlayer player, InteractionHand hand, WorkerInteractionContext.ItemAccess itemAccess) {
        return prepare(PlayerInventoryAccess.of(player), hand, itemAccess != null ? ItemReconciliationSink.of(itemAccess) : ItemReconciliationSink.buffering());
    }

    public static PreparationSnapshot prepare(PlayerInventoryAccess inv, InteractionHand hand, WorkerInteractionContext.ItemAccess itemAccess) {
        return prepare(inv, hand, itemAccess != null ? ItemReconciliationSink.of(itemAccess) : ItemReconciliationSink.buffering());
    }

    public static PreparationSnapshot prepare(ServerPlayer player, InteractionHand hand, ItemReconciliationSink sink) {
        return prepare(PlayerInventoryAccess.of(player), hand, sink);
    }

    public static PreparationSnapshot prepare(PlayerInventoryAccess inv, InteractionHand hand, ItemReconciliationSink sink) {
        if (inv == null) {
            return new PreparationSnapshot(ItemStack.EMPTY, 0, 0, true);
        }

        ItemStack workerItem = ItemStack.EMPTY;
        ItemStack currentHand = inv.getItemInHand(hand);
        workerItem = (currentHand != null && !currentHand.isEmpty()) ? currentHand : ItemStack.EMPTY;

        if (workerItem.isEmpty()) {
            inv.setItemInHand(hand, ItemStack.EMPTY);
            return new PreparationSnapshot(ItemStack.EMPTY, 0, 0, true);
        }

        ItemStack workingCopy = workerItem.copy();
        inv.setItemInHand(hand, workingCopy);

        return new PreparationSnapshot(
                workerItem.copy(),
                workerItem.getCount(),
                workerItem.getDamageValue(),
                false
        );
    }

    public static PreparationSnapshot prepareWithStack(PlayerInventoryAccess inv, InteractionHand hand, ItemStack workerStack) {
        if (inv == null) {
            return new PreparationSnapshot(ItemStack.EMPTY, 0, 0, true);
        }
        if (workerStack == null || workerStack.isEmpty()) {
            inv.setItemInHand(hand, ItemStack.EMPTY);
            return new PreparationSnapshot(ItemStack.EMPTY, 0, 0, true);
        }

        ItemStack workingCopy = workerStack.copy();
        inv.setItemInHand(hand, workingCopy);

        return new PreparationSnapshot(
                workerStack.copy(),
                workerStack.getCount(),
                workerStack.getDamageValue(),
                false
        );
    }

    // -------------------------------------------------------------------------
    // Reconciliation with ItemReconciliationSink
    // -------------------------------------------------------------------------

    public static ItemReconciliationReceipt reconcile(
            ServerPlayer player,
            InteractionHand hand,
            ItemReconciliationSink sink,
            PreparationSnapshot prep
    ) {
        return reconcile(PlayerInventoryAccess.of(player), hand, sink, prep);
    }

    public static ItemReconciliationReceipt reconcile(
            PlayerInventoryAccess inv,
            InteractionHand hand,
            ItemReconciliationSink sink,
            PreparationSnapshot prep
    ) {
        if (inv == null || prep == null) {
            return ItemReconciliationReceipt.noOp(ItemStack.EMPTY);
        }
        if (sink == null) {
            sink = ItemReconciliationSink.buffering();
        }

        ItemStack handAfter = inv.getItemInHand(hand);
        ItemStack resultingStack;
        boolean consumed = false;
        boolean partiallyConsumed = false;
        boolean damaged = false;
        boolean transformed = false;

        if (handAfter == null || handAfter.isEmpty()) {
            resultingStack = ItemStack.EMPTY;
            consumed = !prep.wasEmpty();
        } else {
            resultingStack = handAfter.copy();
            if (prep.wasEmpty()) {
                transformed = true;
            } else {
                if (handAfter.getItem() != prep.initialStack().getItem()) {
                    transformed = true;
                } else {
                    if (handAfter.getCount() < prep.initialCount()) {
                        partiallyConsumed = true;
                    }
                    if (handAfter.getDamageValue() > prep.initialDamage()) {
                        damaged = true;
                    }
                }
            }
        }

        // 1. Primary hand item handoff to sink
        ItemTransferResult mainTransfer = sink.acceptMainHandItem(resultingStack);
        boolean mainTransferred = mainTransfer.isSuccess();

        // 2. Secondary items collection & safe handoff
        List<ItemStack> secondaryFromInv = inv.getSecondaryItems();
        List<ItemStack> transferredSecondary = new ArrayList<>();
        List<ItemStack> pendingSecondary = new ArrayList<>();
        List<ItemStack> unhandledSecondary = new ArrayList<>();

        for (ItemStack sec : secondaryFromInv) {
            if (sec == null || sec.isEmpty()) continue;
            try {
                ItemTransferResult transfer = sink.acceptSecondaryItem(sec.copy());
                if (transfer.status() == ItemTransferResult.Status.ACCEPTED) {
                    transferredSecondary.add(sec.copy());
                } else if (transfer.status() == ItemTransferResult.Status.RETAINED_PENDING) {
                    pendingSecondary.add(sec.copy());
                } else {
                    unhandledSecondary.add(sec.copy());
                }
            } catch (Throwable t) {
                unhandledSecondary.add(sec.copy());
            }
        }

        // 3. Clear FakePlayer secondary items only if they are transferred or pending!
        // Unhandled/rejected items remain in source inventory to prevent silent voiding.
        inv.retainOnlySecondaryItems(unhandledSecondary);

        // 4. Wipe player hand slot only if main hand transfer was accepted or pending
        if (mainTransferred) {
            inv.setItemInHand(hand, ItemStack.EMPTY);
        }

        return new ItemReconciliationReceipt(
                resultingStack,
                transferredSecondary,
                pendingSecondary,
                unhandledSecondary,
                mainTransferred,
                consumed,
                partiallyConsumed,
                damaged,
                transformed
        );
    }

    // -------------------------------------------------------------------------
    // Backward Compatibility Reconcile (Returns ReconciliationResult)
    // -------------------------------------------------------------------------

    public static ReconciliationResult reconcile(
            ServerPlayer player,
            InteractionHand hand,
            WorkerInteractionContext.ItemAccess itemAccess,
            PreparationSnapshot prep
    ) {
        return reconcile(PlayerInventoryAccess.of(player), hand, itemAccess, prep);
    }

    public static ReconciliationResult reconcile(
            PlayerInventoryAccess inv,
            InteractionHand hand,
            WorkerInteractionContext.ItemAccess itemAccess,
            PreparationSnapshot prep
    ) {
        ItemReconciliationSink sink = ItemReconciliationSink.of(itemAccess);
        ItemReconciliationReceipt receipt = reconcile(inv, hand, sink, prep);

        List<ItemStack> allSecondary = new ArrayList<>();
        allSecondary.addAll(receipt.transferredSecondaryItems());
        allSecondary.addAll(receipt.pendingSecondaryItems());

        return new ReconciliationResult(
                receipt.resultingHandItem(),
                Collections.unmodifiableList(allSecondary),
                receipt.consumed(),
                receipt.partiallyConsumed(),
                receipt.damaged(),
                receipt.transformed()
        );
    }

    // -------------------------------------------------------------------------
    // Exception Safety
    // -------------------------------------------------------------------------

    public static ItemReconciliationReceipt reconcileOnException(
            ServerPlayer player,
            InteractionHand hand,
            ItemReconciliationSink sink,
            PreparationSnapshot prep
    ) {
        return reconcile(PlayerInventoryAccess.of(player), hand, sink, prep);
    }

    public static ItemReconciliationReceipt reconcileOnException(
            PlayerInventoryAccess inv,
            InteractionHand hand,
            ItemReconciliationSink sink,
            PreparationSnapshot prep
    ) {
        return reconcile(inv, hand, sink, prep);
    }

    public static ReconciliationResult reconcileOnException(
            ServerPlayer player,
            InteractionHand hand,
            WorkerInteractionContext.ItemAccess itemAccess,
            PreparationSnapshot prep
    ) {
        return reconcile(PlayerInventoryAccess.of(player), hand, itemAccess, prep);
    }

    public static ReconciliationResult reconcileOnException(
            PlayerInventoryAccess inv,
            InteractionHand hand,
            WorkerInteractionContext.ItemAccess itemAccess,
            PreparationSnapshot prep
    ) {
        return reconcile(inv, hand, itemAccess, prep);
    }
}
