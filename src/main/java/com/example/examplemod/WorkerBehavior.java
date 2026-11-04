package com.example.examplemod;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

public enum WorkerBehavior {
    FOLLOW,
    STAND;

    public static final Codec<WorkerBehavior> CODEC = Codec.STRING.comapFlatMap(
            str -> {
                try {
                    return DataResult.success(WorkerBehavior.valueOf(str.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Unknown worker behavior: " + str);
                }
            },
            behavior -> behavior.name().toLowerCase(Locale.ROOT)
    );
}
