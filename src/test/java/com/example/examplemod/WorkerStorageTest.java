package com.example.examplemod;

import com.google.gson.JsonArray;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerStorageTest {
    @Test
    void inventoryRoundTripsWithStacksAndEmptySlots() {
        WorkerStorage empty = WorkerStorage.empty();
        assertEquals(WorkerStorage.SIZE, empty.items().size());
        var contents = new java.util.ArrayList<>(empty.items());
        contents.set(0, new ItemStack(Items.APPLE, 12));
        contents.set(14, new ItemStack(Items.SHIELD));
        WorkerStorage stored = new WorkerStorage(contents);
        var encoded = WorkerStorage.CODEC.encodeStart(JsonOps.INSTANCE, stored).result().orElseThrow();
        WorkerStorage loaded = WorkerStorage.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
        assertTrue(ItemStack.matches(stored.items().get(0), loaded.items().get(0)));
        assertTrue(ItemStack.matches(stored.items().get(14), loaded.items().get(14)));
        assertTrue(loaded.items().get(1).isEmpty());
        contents.get(0).shrink(5);
        assertEquals(12, stored.items().get(0).getCount());
    }

    @Test
    void wrongInventorySizeCannotBeLoaded() {
        assertTrue(WorkerStorage.CODEC.parse(JsonOps.INSTANCE, new JsonArray()).result().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new WorkerStorage(java.util.List.of(ItemStack.EMPTY)));
    }
}