package com.example.examplemod;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Authoritative receipt detailing item changes, secondary item handoffs, and pending states.
 */
public record ItemReconciliationReceipt(
        ItemStack resultingHandItem,
        List<ItemStack> transferredSecondaryItems,
        List<ItemStack> pendingSecondaryItems,
        List<ItemStack> unhandledSecondaryItems,
        boolean mainHandTransferred,
        boolean consumed,
        boolean partiallyConsumed,
        boolean damaged,
        boolean transformed
) {
    public ItemReconciliationReceipt {
        Objects.requireNonNull(resultingHandItem, "resultingHandItem must not be null");
        transferredSecondaryItems = transferredSecondaryItems != null ? List.copyOf(transferredSecondaryItems) : List.of();
        pendingSecondaryItems = pendingSecondaryItems != null ? List.copyOf(pendingSecondaryItems) : List.of();
        unhandledSecondaryItems = unhandledSecondaryItems != null ? List.copyOf(unhandledSecondaryItems) : List.of();
    }

    public boolean hasPendingItems() {
        return !pendingSecondaryItems.isEmpty();
    }

    public boolean hasUnhandledItems() {
        return !unhandledSecondaryItems.isEmpty();
    }

    public boolean isClean() {
        return mainHandTransferred && unhandledSecondaryItems.isEmpty();
    }

    public static ItemReconciliationReceipt noOp(ItemStack stack) {
        return new ItemReconciliationReceipt(
                stack != null ? stack : ItemStack.EMPTY,
                List.of(),
                List.of(),
                List.of(),
                true,
                false,
                false,
                false,
                false
        );
    }
}
