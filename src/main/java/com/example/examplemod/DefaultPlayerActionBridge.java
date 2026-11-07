package com.example.examplemod;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Default implementation of PlayerActionBridge that invokes official Minecraft and NeoForge server interaction APIs.
 */
public class DefaultPlayerActionBridge implements PlayerActionBridge {

    public static final DefaultPlayerActionBridge INSTANCE = new DefaultPlayerActionBridge();

    protected DefaultPlayerActionBridge() {}

    @Override
    public InteractionResult rightClickBlock(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        BlockPos pos = hitResult.getBlockPos();
        PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, hand, pos, hitResult);
        if (event.isCanceled()) {
            return InteractionResult.blocked("EVENT_CANCELLED", "RightClickBlock event was cancelled by event handler");
        }

        net.minecraft.world.InteractionResult mcResult = player.gameMode.useItemOn(player, level, stack, hand, hitResult);
        if (mcResult.consumesAction()) {
            return InteractionResult.success(executorId, true, "BLOCK_USE_" + mcResult.name());
        } else if (mcResult == net.minecraft.world.InteractionResult.FAIL) {
            return InteractionResult.failed("USE_ITEM_ON_FAIL", "useItemOn returned FAIL", executorId);
        } else {
            return InteractionResult.failed("USE_ITEM_ON_PASS", "No block or item interaction occurred", executorId);
        }
    }

    @Override
    public InteractionResult leftClickBlock(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            BlockPos pos,
            Direction face,
            ExecutionMode mode,
            int elapsedTicks
    ) {
        if (face == null) {
            face = Direction.UP;
        }

        PlayerInteractEvent.LeftClickBlock event = CommonHooks.onLeftClickBlock(
                player,
                pos,
                face,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
        );
        if (event.isCanceled()) {
            return InteractionResult.blocked("EVENT_CANCELLED", "LeftClickBlock event was cancelled by event handler");
        }

        if (mode == ExecutionMode.CLICK) {
            player.gameMode.handleBlockBreakAction(
                    pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    face,
                    level.getMaxBuildHeight(),
                    0
            );
            BlockState current = level.getBlockState(pos);
            if (current.isAir()) {
                return InteractionResult.success(executorId, true, "INSTANT_BREAK");
            }
            return InteractionResult.success(executorId, false, "ATTACK_BLOCK_DISCRETE");
        } else {
            // Continuous breaking
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return InteractionResult.success(executorId, true, "BLOCK_BROKEN");
            }

            if (elapsedTicks <= 1) {
                player.gameMode.handleBlockBreakAction(
                        pos,
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        face,
                        level.getMaxBuildHeight(),
                        0
                );
            }

            // Check if block was broken
            if (level.getBlockState(pos).isAir()) {
                return InteractionResult.success(executorId, true, "BLOCK_BROKEN");
            }

            float progress = state.getDestroyProgress(player, level, pos);
            return new InteractionResult(
                    InteractionOutcome.WAITING,
                    "BREAKING_BLOCK",
                    Optional.of("destroy_progress=" + progress),
                    Optional.of(executorId),
                    progress > 0.0f
            );
        }
    }

    @Override
    public InteractionResult rightClickEntity(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            Entity targetEntity,
            InteractionHand hand,
            Vec3 hitVec
    ) {
        if (!targetEntity.isAlive()) {
            return InteractionResult.unavailable("TARGET_ENTITY_DEAD");
        }

        if (hitVec != null) {
            net.minecraft.world.InteractionResult hookAt = CommonHooks.onInteractEntityAt(player, targetEntity, hitVec, hand);
            if (hookAt != null && hookAt.consumesAction()) {
                return InteractionResult.success(executorId, true, "INTERACT_ENTITY_AT_" + hookAt.name());
            }
        }

        net.minecraft.world.InteractionResult hook = CommonHooks.onInteractEntity(player, targetEntity, hand);
        if (hook != null && hook.consumesAction()) {
            return InteractionResult.success(executorId, true, "INTERACT_ENTITY_" + hook.name());
        }

        net.minecraft.world.InteractionResult result = player.interactOn(targetEntity, hand);
        if (result.consumesAction()) {
            return InteractionResult.success(executorId, true, "INTERACT_ON_" + result.name());
        } else {
            return InteractionResult.failed("INTERACT_ENTITY_FAILED", result.name(), executorId);
        }
    }

    @Override
    public InteractionResult leftClickEntity(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            Entity targetEntity
    ) {
        if (!targetEntity.isAlive()) {
            return InteractionResult.unavailable("TARGET_ENTITY_DEAD");
        }

        player.attack(targetEntity);
        return InteractionResult.success(executorId, true, "ATTACK_ENTITY_SUCCESS");
    }

    @Override
    public InteractionResult rightClickAir(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            ItemStack stack,
            InteractionHand hand
    ) {
        net.minecraft.world.InteractionResult hookResult = CommonHooks.onItemRightClick(player, hand);
        if (hookResult.consumesAction()) {
            return InteractionResult.success(executorId, true, "USE_AIR_ITEM_" + hookResult.name());
        } else if (hookResult == net.minecraft.world.InteractionResult.FAIL) {
            return InteractionResult.failed("USE_AIR_ITEM_FAIL", "onItemRightClick returned FAIL", executorId);
        }

        net.minecraft.world.InteractionResult result = player.gameMode.useItem(player, level, stack, hand);
        if (result.consumesAction()) {
            return InteractionResult.success(executorId, true, "USE_AIR_ITEM_" + result.name());
        } else {
            return InteractionResult.failed("USE_AIR_ITEM_PASS", result.name(), executorId);
        }
    }

    @Override
    public boolean stopContinuousAction(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            InteractionIntent intent,
            int elapsedTicks
    ) {
        if (player == null || level == null || intent == null) {
            return false;
        }

        try {
            if (intent.target() instanceof InteractionTarget.BlockTarget blockTarget) {
                Direction face = blockTarget.face().orElse(Direction.UP);
                player.gameMode.handleBlockBreakAction(
                        blockTarget.pos(),
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        face,
                        level.getMaxBuildHeight(),
                        0
                );
            }

            if (player.isUsingItem()) {
                player.stopUsingItem();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean cancelContinuousAction(
            ResourceLocation executorId,
            ServerPlayer player,
            ServerLevel level,
            InteractionIntent intent,
            int elapsedTicks
    ) {
        if (player == null || level == null || intent == null) {
            return false;
        }

        try {
            if (intent.target() instanceof InteractionTarget.BlockTarget blockTarget) {
                Direction face = blockTarget.face().orElse(Direction.UP);
                player.gameMode.handleBlockBreakAction(
                        blockTarget.pos(),
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        face,
                        level.getMaxBuildHeight(),
                        0
                );
            }

            if (player.isUsingItem()) {
                player.stopUsingItem();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
