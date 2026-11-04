package com.example.examplemod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

public enum JobStatus {
    IDLE, ASSIGNED, RUNNING, PAUSED, COMPLETED, FAILED, DELIVERING, WAITING_FOR_STORAGE, WAITING_FOR_TOOL,
    WAITING_FOR_FALLING_BLOCK, RECOVERING_TUNNEL_OBSTRUCTION, WAITING_FOR_SAFE_TUNNEL, WAITING_FOR_CROPS;

    public static final Codec<JobStatus> CODEC = Codec.STRING.comapFlatMap(
            name -> {
                for (JobStatus status : values()) {
                    if (status.name().equalsIgnoreCase(name)) {
                        return DataResult.success(status);
                    }
                }
                return DataResult.error(() -> "Unknown job status: " + name);
            }, status -> status.name().toLowerCase(java.util.Locale.ROOT));
}
