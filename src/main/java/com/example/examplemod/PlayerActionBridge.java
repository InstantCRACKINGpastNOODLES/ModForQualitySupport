package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bridge interface abstracting Minecraft server interaction invocations.
 * Allows decoupling GenericPlayerInteractionExecutor from raw engine calls
 * for deterministic unit testing and safe mock verification.
 */
public interface PlayerActionBridge {

    InteractionResult rightClickBlock(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult
    );

    InteractionResult leftClickBlock(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            BlockPos pos,
            Direction face,
            ExecutionMode mode,
            int elapsedTicks
    );

    InteractionResult rightClickEntity(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            Entity targetEntity,
            InteractionHand hand,
            Vec3 hitVec
    );

    InteractionResult leftClickEntity(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            Entity targetEntity
    );

    InteractionResult rightClickAir(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            ItemStack stack,
            InteractionHand hand
    );

    boolean cancelContinuousAction(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            InteractionIntent intent,
            int elapsedTicks
    );

    /**
     * Confirms the normal release of an in-progress continuous interaction session.
     * Uses normal release / STOP semantics (e.g. STOP_DESTROY_BLOCK for blocks, stopUsingItem for items).
     */
    default boolean stopContinuousAction(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            InteractionIntent intent,
            int elapsedTicks
    ) {
        return cancelContinuousAction(executorId, player, level, intent, elapsedTicks);
    }
}
