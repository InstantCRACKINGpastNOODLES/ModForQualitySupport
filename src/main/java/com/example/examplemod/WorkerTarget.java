package com.example.examplemod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Saved coordinates only; resolving a target never loads its chunk. */
public record WorkerTarget(ResourceLocation dimension, BlockPos position) {
    public static final Codec<WorkerTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(WorkerTarget::dimension),
            BlockPos.CODEC.fieldOf("position").forGetter(WorkerTarget::position)
    ).apply(instance, WorkerTarget::new));
}