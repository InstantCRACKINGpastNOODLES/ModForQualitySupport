package com.example.examplemod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Clean runtime reflective bridge for JourneyMap integration.
 *
 * Guarantees:
 * 1. Dedicated servers NEVER load or resolve JourneyMap client classes.
 * 2. Compiles cleanly without requiring JourneyMap API on the compile classpath.
 * 3. Soft-dependency architecture: safely executes if JourneyMap is loaded at runtime.
 * 4. Any missing classes, method changes, or API failures fail closed and quietly return false.
 */
public final class JourneyMapBridge {

    private static Boolean journeyMapPresent = null;

    private JourneyMapBridge() {
    }

    public static boolean isJourneyMapAvailable() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return false;
        }
        if (journeyMapPresent == null) {
            try {
                journeyMapPresent = ModList.get() != null && ModList.get().isLoaded("journeymap");
            } catch (Throwable t) {
                journeyMapPresent = false;
            }
        }
        return Boolean.TRUE.equals(journeyMapPresent);
    }

    public static void setMockJourneyMapPresent(Boolean present) {
        journeyMapPresent = present;
    }

    /**
     * Attempts to create or update a waypoint in JourneyMap via reflection.
     * Returns true if successfully dispatched to JourneyMap, false otherwise.
     */
    public static boolean createOrUpdateWaypoint(
            String waypointId,
            ResourceLocation dimension,
            BlockPos pos,
            String name,
            String reason
    ) {
        if (!isJourneyMapAvailable() || waypointId == null || pos == null) {
            return false;
        }

        try {
            Class<?> waypointStoreClass = Class.forName("journeymap.client.waypoint.WaypointStore");
            Method getLoadedMethod = waypointStoreClass.getMethod("getLoaded");
            Object store = getLoadedMethod.invoke(null);
            if (store == null) {
                return false;
            }

            ResourceKey<Level> dimKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    dimension != null ? dimension : ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
            );

            // Attempt to find Waypoint class (checking official and internal paths)
            Class<?> waypointClass = null;
            try {
                waypointClass = Class.forName("journeymap.client.api.display.Waypoint");
            } catch (ClassNotFoundException e) {
                try {
                    waypointClass = Class.forName("journeymap.client.waypoint.Waypoint");
                } catch (ClassNotFoundException ignored) {}
            }

            if (waypointClass == null) {
                return false;
            }

            Object waypointInstance = null;

            // Attempt instantiation using matching constructor signatures
            for (Constructor<?> c : waypointClass.getConstructors()) {
                try {
                    c.setAccessible(true);
                    Class<?>[] pTypes = c.getParameterTypes();
                    if (pTypes.length == 4 && pTypes[0] == String.class && pTypes[1] == String.class
                            && pTypes[2] == ResourceKey.class && pTypes[3] == BlockPos.class) {
                        waypointInstance = c.newInstance(waypointId, name != null ? name : "Rescue", dimKey, pos);
                        break;
                    } else if (pTypes.length == 5 && pTypes[0] == String.class && pTypes[1] == String.class
                            && pTypes[2] == BlockPos.class && pTypes[3] == ResourceKey.class) {
                        waypointInstance = c.newInstance(ExampleMod.MODID, name != null ? name : "Rescue", pos, dimKey, java.awt.Color.RED);
                        break;
                    } else if (pTypes.length == 3 && pTypes[0] == String.class && pTypes[1] == String.class && pTypes[2] == BlockPos.class) {
                        waypointInstance = c.newInstance(waypointId, name != null ? name : "Rescue", pos);
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            // Fallback: check static factory methods
            if (waypointInstance == null) {
                for (Method m : waypointClass.getMethods()) {
                    if (m.getName().equals("of") || m.getName().equals("create")) {
                        try {
                            m.setAccessible(true);
                            Class<?>[] pTypes = m.getParameterTypes();
                            if (pTypes.length == 3 && pTypes[0] == String.class && pTypes[1] == String.class && pTypes[2] == BlockPos.class) {
                                waypointInstance = m.invoke(null, waypointId, name, pos);
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (waypointInstance == null) {
                ExampleMod.LOGGER.debug("Failed to instantiate JourneyMap Waypoint for id={}", waypointId);
                return false;
            }

            // Save waypoint into WaypointStore
            for (Method m : waypointStoreClass.getMethods()) {
                if (m.getName().equals("save") || m.getName().equals("put") || m.getName().equals("add") || m.getName().equals("show")) {
                    try {
                        m.setAccessible(true);
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 1 && pTypes[0].isInstance(waypointInstance)) {
                            m.invoke(store, waypointInstance);
                            ExampleMod.LOGGER.debug("JourneyMap waypoint registered: id={} pos={}", waypointId, pos);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            ExampleMod.LOGGER.debug("JourneyMap waypoint create notice (soft bridge): {}", t.getMessage());
        }
        return false;
    }

    /**
     * Attempts to remove a waypoint from JourneyMap via reflection.
     * Returns true if successfully removed, false otherwise.
     */
    public static boolean removeWaypoint(String waypointId, ResourceLocation dimension) {
        if (!isJourneyMapAvailable() || waypointId == null) {
            return false;
        }

        try {
            Class<?> waypointStoreClass = Class.forName("journeymap.client.waypoint.WaypointStore");
            Method getLoadedMethod = waypointStoreClass.getMethod("getLoaded");
            Object store = getLoadedMethod.invoke(null);
            if (store == null) {
                return false;
            }

            for (Method m : waypointStoreClass.getMethods()) {
                if (m.getName().equals("remove") || m.getName().equals("delete")) {
                    try {
                        m.setAccessible(true);
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 1 && pTypes[0] == String.class) {
                            m.invoke(store, waypointId);
                            ExampleMod.LOGGER.debug("JourneyMap waypoint removed: id={}", waypointId);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Try get(id) followed by remove(waypoint)
            Method getMethod = null;
            try {
                getMethod = waypointStoreClass.getMethod("get", String.class);
            } catch (Throwable ignored) {}

            if (getMethod != null) {
                Object existingWaypoint = getMethod.invoke(store, waypointId);
                if (existingWaypoint != null) {
                    for (Method m : waypointStoreClass.getMethods()) {
                        if (m.getName().equals("remove") || m.getName().equals("delete")) {
                            try {
                                m.setAccessible(true);
                                Class<?>[] pTypes = m.getParameterTypes();
                                if (pTypes.length == 1 && pTypes[0].isInstance(existingWaypoint)) {
                                    m.invoke(store, existingWaypoint);
                                    ExampleMod.LOGGER.debug("JourneyMap waypoint removed by instance: id={}", waypointId);
                                    return true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable t) {
            ExampleMod.LOGGER.debug("JourneyMap waypoint remove notice (soft bridge): {}", t.getMessage());
        }
        return false;
    }
}
