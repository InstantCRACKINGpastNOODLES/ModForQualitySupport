package com.example.examplemod;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable Elstar Ticket interaction diagnostic logging")
            .define("debugLogging", false);

    // ==========================================
    // 3D Spatial & Range Architecture Policies
    // ==========================================

    public static final ModConfigSpec.IntValue MAX_WORKSITE_DISTANCE = BUILDER
            .comment("Maximum allowed 3D distance between worker and worksite when authoring/assigning a job (blocks)")
            .defineInRange("maxWorksiteDistance", 128, 16, 1024);

    public static final ModConfigSpec.IntValue MAX_STORAGE_DISTANCE = BUILDER
            .comment("Maximum allowed 3D distance between worksite and designated storage container (blocks)")
            .defineInRange("maxStorageDistance", 64, 16, 1024);

    public static final ModConfigSpec.IntValue MAX_VERTICAL_DELTA = BUILDER
            .comment("Maximum allowed vertical delta (+/- blocks) from worksite for operations (supports vertical shafts)")
            .defineInRange("maxVerticalDelta", 64, 8, 384);

    public static final ModConfigSpec.IntValue MAX_WORK_RADIUS = BUILDER
            .comment("Maximum allowed horizontal radius (blocks) around worksite for block operations")
            .defineInRange("maxWorkRadius", 32, 16, 512);

    public static final ModConfigSpec.IntValue MAX_TRAVEL_DISTANCE = BUILDER
            .comment("Maximum allowed navigation search distance (blocks) for worker travel")
            .defineInRange("maxTravelDistance", 128, 32, 1024);

    public static final ModConfigSpec.IntValue MAX_TETHER_DISTANCE = BUILDER
            .comment("Maximum visual tether render distance (blocks)")
            .defineInRange("maxTetherDistance", 48, 16, 256);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    static final ModConfigSpec SPEC = BUILDER.build();

    // Test overrides
    private static volatile Integer storageDistanceOverrideForTesting = null;
    private static volatile Integer worksiteDistanceOverrideForTesting = null;
    private static volatile Integer verticalDeltaOverrideForTesting = null;
    private static volatile Integer workRadiusOverrideForTesting = null;
    private static volatile Integer travelDistanceOverrideForTesting = null;
    private static volatile Integer tetherDistanceOverrideForTesting = null;

    public static void setStorageDistanceOverrideForTesting(Integer val) {
        storageDistanceOverrideForTesting = val;
    }

    public static void setWorksiteDistanceOverrideForTesting(Integer val) {
        worksiteDistanceOverrideForTesting = val;
    }

    public static void setVerticalDeltaOverrideForTesting(Integer val) {
        verticalDeltaOverrideForTesting = val;
    }

    public static void setWorkRadiusOverrideForTesting(Integer val) {
        workRadiusOverrideForTesting = val;
    }

    public static void setTravelDistanceOverrideForTesting(Integer val) {
        travelDistanceOverrideForTesting = val;
    }

    public static void setTetherDistanceOverrideForTesting(Integer val) {
        tetherDistanceOverrideForTesting = val;
    }

    public static void clearTestOverrides() {
        storageDistanceOverrideForTesting = null;
        worksiteDistanceOverrideForTesting = null;
        verticalDeltaOverrideForTesting = null;
        workRadiusOverrideForTesting = null;
        travelDistanceOverrideForTesting = null;
        tetherDistanceOverrideForTesting = null;
    }

    public static int getMaxWorksiteDistance() {
        if (worksiteDistanceOverrideForTesting != null) {
            return worksiteDistanceOverrideForTesting;
        }
        try {
            if (SPEC.isLoaded()) {
                return MAX_WORKSITE_DISTANCE.get();
            }
        } catch (Throwable ignored) {
        }
        return JobSafetyPolicy.DEFAULT_MAX_WORKSITE_ASSIGNMENT_DISTANCE;
    }

    public static int getMaxStorageDistance() {
        if (storageDistanceOverrideForTesting != null) {
            return storageDistanceOverrideForTesting;
        }
        try {
            if (SPEC.isLoaded()) {
                return MAX_STORAGE_DISTANCE.get();
            }
        } catch (Throwable ignored) {
        }
        return 64;
    }

    public static int getMaxVerticalDelta() {
        if (verticalDeltaOverrideForTesting != null) {
            return verticalDeltaOverrideForTesting;
        }
        try {
            if (SPEC.isLoaded()) {
                return MAX_VERTICAL_DELTA.get();
            }
        } catch (Throwable ignored) {
        }
        return JobSafetyPolicy.DEFAULT_MAX_VERTICAL_DELTA;
    }

    public static int getMaxWorkRadius() {
        if (workRadiusOverrideForTesting != null) {
            return workRadiusOverrideForTesting;
        }
        try {
            if (SPEC.isLoaded()) {
                return MAX_WORK_RADIUS.get();
            }
        } catch (Throwable ignored) {
        }
        return JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS;
    }

    public static int getMaxTravelDistance() {
        if (travelDistanceOverrideForTesting != null) {
            return travelDistanceOverrideForTesting;
        }
        try {
            if (SPEC.isLoaded()) {
                return MAX_TRAVEL_DISTANCE.get();
            }
        } catch (Throwable ignored) {
        }
        return JobSafetyPolicy.DEFAULT_MAX_TRAVEL_DISTANCE;
    }

    public static int getMaxTetherDistance() {
        if (tetherDistanceOverrideForTesting != null) {
            return tetherDistanceOverrideForTesting;
        }
        try {
            if (SPEC.isLoaded()) {
                return MAX_TETHER_DISTANCE.get();
            }
        } catch (Throwable ignored) {
        }
        return 48;
    }

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
