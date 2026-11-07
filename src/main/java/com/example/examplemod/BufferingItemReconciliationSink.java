package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Thread-safe buffering item reconciliation sink that retains unconsumed or secondary items in-memory.
 */
public class BufferingItemReconciliationSink implements ItemReconciliationSink {

    private final WorkerInteractionContext.ItemAccess mainItemAccess;
    private final List<ItemStack> pendingSecondaryItems = new ArrayList<>();
    private final List<ItemStack> transferredSecondaryItems = new ArrayList<>();

    public BufferingItemReconciliationSink(WorkerInteractionContext.ItemAccess mainItemAccess) {
        this.mainItemAccess = mainItemAccess != null ? mainItemAccess : WorkerInteractionContext.ItemAccess.empty();
    }

    @Override
    public synchronized ItemTransferResult acceptMainHandItem(ItemStack resultingStack) {
        try {
            mainItemAccess.set(resultingStack != null ? resultingStack : ItemStack.EMPTY);
            return ItemTransferResult.accepted();
        } catch (Throwable t) {
            ItemStack stackCopy = resultingStack != null ? resultingStack.copy() : ItemStack.EMPTY;
            pendingSecondaryItems.add(stackCopy);
            return ItemTransferResult.retainedPending(stackCopy, "MAIN_HAND_ERROR: " + t.getMessage());
        }
    }

    @Override
    public synchronized ItemTransferResult acceptSecondaryItem(ItemStack secondaryStack) {
        if (secondaryStack == null || secondaryStack.isEmpty()) {
            return ItemTransferResult.accepted();
        }
        ItemStack copy = secondaryStack.copy();
        pendingSecondaryItems.add(copy);
        return ItemTransferResult.retainedPending(copy, "BUFFERED_IN_MEMORY");
    }

    @Override
    public synchronized List<ItemStack> getPendingItems() {
        return Collections.unmodifiableList(new ArrayList<>(pendingSecondaryItems));
    }

    @Override
    public synchronized List<ItemStack> drainPendingItems() {
        List<ItemStack> drained = new ArrayList<>(pendingSecondaryItems);
        pendingSecondaryItems.clear();
        return drained;
    }

    public synchronized List<ItemStack> getTransferredItems() {
        return Collections.unmodifiableList(new ArrayList<>(transferredSecondaryItems));
    }
}
