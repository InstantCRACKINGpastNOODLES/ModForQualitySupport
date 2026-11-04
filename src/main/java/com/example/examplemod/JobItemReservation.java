package com.example.examplemod;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Common extensible abstraction for jobs that hold or reserve ItemStacks in WorkerStorage.
 * Guarantees that pending delivery outputs, items required for future steps, and in-flight
 * mutation products are never consumed by Emergency Shelter construction.
 */
@FunctionalInterface
public interface JobItemReservation {
    /**
     * Returns an unmodifiable list of ItemStacks reserved by this job.
     * Implementations must return copied stacks or safe views to prevent callers
     * from mutating original job state.
     */
    List<ItemStack> getReservedItemStacks();
}
