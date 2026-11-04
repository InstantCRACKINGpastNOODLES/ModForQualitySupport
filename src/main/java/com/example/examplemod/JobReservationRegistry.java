package com.example.examplemod;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Registry enabling future or custom job types to expose ItemStacks reserved in WorkerStorage
 * without requiring core Emergency Shelter code modifications.
 */
public final class JobReservationRegistry {

    private static final Map<ResourceLocation, Function<WorkerProfile, Collection<ItemStack>>> ADAPTERS = new ConcurrentHashMap<>();

    private JobReservationRegistry() {
    }

    /**
     * Registers a custom reservation provider for a specific job type.
     */
    public static void register(ResourceLocation jobType, Function<WorkerProfile, Collection<ItemStack>> provider) {
        Objects.requireNonNull(jobType, "jobType must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        ADAPTERS.put(jobType, provider);
    }

    /**
     * Unregisters a custom reservation provider.
     */
    public static void unregister(ResourceLocation jobType) {
        if (jobType != null) {
            ADAPTERS.remove(jobType);
        }
    }

    /**
     * Retrieves the custom provider for the specified job type, if present.
     */
    public static Function<WorkerProfile, Collection<ItemStack>> getProvider(ResourceLocation jobType) {
        if (jobType == null) {
            return null;
        }
        return ADAPTERS.get(jobType);
    }

    /**
     * Determines whether a reservation provider is registered for the specified job type.
     */
    public static boolean hasProvider(ResourceLocation jobType) {
        return jobType != null && ADAPTERS.containsKey(jobType);
    }

    /**
     * Clears all registered custom providers (primarily for unit test isolation).
     */
    public static void clear() {
        ADAPTERS.clear();
    }
}
