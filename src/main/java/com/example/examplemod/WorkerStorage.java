package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public record WorkerStorage(List<ItemStack> items) {
    public static final int SIZE = 15;
    public static final Codec<WorkerStorage> CODEC = ItemStack.OPTIONAL_CODEC.listOf().flatXmap(
            items -> items.size() == SIZE ? DataResult.success(new WorkerStorage(items)) :
                    DataResult.error(() -> "Invalid worker inventory size"),
            storage -> storage.items.size() == SIZE ? DataResult.success(storage.items) :
                    DataResult.error(() -> "Invalid worker inventory size"));

    public WorkerStorage {
        if (items.size() != SIZE) throw new IllegalArgumentException("Invalid worker inventory size");
        items = List.copyOf(items.stream().map(ItemStack::copy).toList());
    }

    public static WorkerStorage empty() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) items.add(ItemStack.EMPTY);
        return new WorkerStorage(items);
    }

    public boolean matchesContainer(Container container) {
        if (container == null || container.getContainerSize() != SIZE) return false;
        for (int i = 0; i < SIZE; i++) {
            ItemStack inContainer = container.getItem(i);
            if (inContainer == null) inContainer = ItemStack.EMPTY;
            if (!ItemStack.matches(this.items.get(i), inContainer)) {
                return false;
            }
        }
        return true;
    }

    public WorkerStorage deposit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return this;
        List<ItemStack> newItems = new ArrayList<>(items);
        ItemStack remaining = stack.copy();

        // 1. Try merging into existing matching stacks
        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            ItemStack slot = newItems.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, remaining.getCount());
                    ItemStack updated = slot.copy();
                    updated.grow(toAdd);
                    remaining.shrink(toAdd);
                    newItems.set(i, updated);
                }
            }
        }

        // 2. Try inserting into empty slots
        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            ItemStack slot = newItems.get(i);
            if (slot.isEmpty()) {
                newItems.set(i, remaining.copy());
                remaining.setCount(0);
                break;
            }
        }

        return new WorkerStorage(newItems);
    }
}
