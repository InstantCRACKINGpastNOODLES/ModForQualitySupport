package com.example.examplemod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * Defines the crop selection policy for autonomous worker farming:
 * - ALL_SUPPORTED: Discovers and harvests every registered and supported crop in the worksite (default).
 * - SELECTED_ONLY: Restricts harvesting strictly to an explicit allowlist of crop block IDs.
 */
public enum CropSelectionMode {
    ALL_SUPPORTED,
    SELECTED_ONLY;

    public static final Codec<CropSelectionMode> CODEC = Codec.STRING.comapFlatMap(
            name -> {
                for (CropSelectionMode mode : values()) {
                    if (mode.name().equalsIgnoreCase(name)) {
                        return DataResult.success(mode);
                    }
                }
                return DataResult.error(() -> "Unknown crop selection mode: " + name);
            },
            mode -> mode.name().toLowerCase(java.util.Locale.ROOT)
    );
}
