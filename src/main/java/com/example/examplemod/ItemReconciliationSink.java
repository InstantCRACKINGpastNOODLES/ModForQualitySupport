package com.example.examplemod;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Abstraction for accepting and safely preserving interaction item outcomes.
 * Guarantees zero item voiding and zero item duplication without coupling
 * to specific container implementations.
 */
public interface ItemReconciliationSink {

    /**
     * Transfers or updates the primary hand item on the recipient.
     * @param resultingStack updated hand ItemStack (may be ItemStack.EMPTY if consumed)
     */
    ItemTransferResult acceptMainHandItem(ItemStack resultingStack);

    /**
     * Transfers an auxiliary or secondary item produced during the interaction.
     * @param secondaryStack auxiliary item produced (must not be empty)
     */
    ItemTransferResult acceptSecondaryItem(ItemStack secondaryStack);

    /**
     * Returns an unmodifiable view of all pending items held by this sink that could not be immediately transferred.
     */
    List<ItemStack> getPendingItems();

    /**
     * Drains and returns all pending items from this sink.
     */
    List<ItemStack> drainPendingItems();

    /**
     * Creates an ItemReconciliationSink backed by a WorkerInteractionContext.ItemAccess.
     * Secondary items are automatically buffered in pending storage so they are never lost.
     */
    static ItemReconciliationSink of(WorkerInteractionContext.ItemAccess itemAccess) {
        return new BufferingItemReconciliationSink(itemAccess);
    }

    /**
     * Creates an in-memory buffering sink with no bound primary hand.
     */
    static ItemReconciliationSink buffering() {
        return new BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess.empty());
    }

    /**
     * Creates a terminal sink that immediately deposits secondary items into a persistent destination consumer.
     */
    static ItemReconciliationSink terminal(WorkerInteractionContext.ItemAccess itemAccess, java.util.function.Consumer<ItemStack> secondaryConsumer) {
        return new ItemReconciliationSink() {
            @Override
            public ItemTransferResult acceptMainHandItem(ItemStack resultingStack) {
                try {
                    if (itemAccess != null) {
                        itemAccess.set(resultingStack != null ? resultingStack : ItemStack.EMPTY);
                    }
                    return ItemTransferResult.accepted();
                } catch (Throwable t) {
                    return ItemTransferResult.rejected(resultingStack, t.getMessage());
                }
            }

            @Override
            public ItemTransferResult acceptSecondaryItem(ItemStack secondaryStack) {
                if (secondaryStack == null || secondaryStack.isEmpty()) {
                    return ItemTransferResult.accepted();
                }
                try {
                    if (secondaryConsumer != null) {
                        secondaryConsumer.accept(secondaryStack.copy());
                        return ItemTransferResult.accepted();
                    }
                    return ItemTransferResult.rejected(secondaryStack, "NO_CONSUMER");
                } catch (Throwable t) {
                    return ItemTransferResult.rejected(secondaryStack, t.getMessage());
                }
            }

            @Override
            public List<ItemStack> getPendingItems() {
                return List.of();
            }

            @Override
            public List<ItemStack> drainPendingItems() {
                return List.of();
            }
        };
    }
}
