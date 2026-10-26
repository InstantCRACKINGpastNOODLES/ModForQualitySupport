package com.example.examplemod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

public enum WorkerStatus {
    IDLE;

    public static final Codec<WorkerStatus> CODEC = Codec.STRING.comapFlatMap(
            value -> value.equals("idle") ? DataResult.success(IDLE) : DataResult.error(() -> "Unknown worker status: " + value),
            value -> "idle");
}