package com.example.examplemod;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Result of an individual item transfer to an ItemReconciliationSink.
 */
public record ItemTransferResult(
        Status status,
        ItemStack remainingStack,
        String message
) {
    public enum Status {
        ACCEPTED,
        RETAINED_PENDING,
        REJECTED
    }

    public ItemTransferResult {
        Objects.requireNonNull(status, "status must not be null");
        remainingStack = remainingStack != null ? remainingStack : ItemStack.EMPTY;
        message = message != null ? message : "";
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public boolean isRetainedPending() {
        return status == Status.RETAINED_PENDING;
    }

    public boolean isRejected() {
        return status == Status.REJECTED;
    }

    public boolean isSuccess() {
        return status == Status.ACCEPTED || status == Status.RETAINED_PENDING;
    }

    public static ItemTransferResult accepted() {
        return new ItemTransferResult(Status.ACCEPTED, ItemStack.EMPTY, "ACCEPTED");
    }

    public static ItemTransferResult retainedPending(ItemStack pending, String message) {
        return new ItemTransferResult(Status.RETAINED_PENDING, pending != null ? pending.copy() : ItemStack.EMPTY, message);
    }

    public static ItemTransferResult rejected(ItemStack rejected, String message) {
        return new ItemTransferResult(Status.REJECTED, rejected != null ? rejected.copy() : ItemStack.EMPTY, message);
    }
}
