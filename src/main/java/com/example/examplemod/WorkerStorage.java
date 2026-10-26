package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
}