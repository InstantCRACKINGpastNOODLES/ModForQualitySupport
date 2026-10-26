package com.example.examplemod;

import java.util.List;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.MenuProvider;

public class BindingTicketItem extends Item {
    enum Decision {
        BIND, MANAGE, DUPLICATE, DENIED, UNAVAILABLE
    }

    static Decision decide(boolean server, boolean available, boolean bound, boolean legacyBound,
                           boolean managing, WorkerProfile profile, UUID playerId, UUID entityId,
                           ResourceLocation dimension) {
        if (!server || !available) {
            return Decision.UNAVAILABLE;
        }
        if (bound) {
            return profile != null && profile.ownerId().equals(playerId) && profile.entityId().equals(entityId)
                    && profile.dimension().equals(dimension) ? Decision.MANAGE : Decision.DENIED;
        }
        return bound || legacyBound ? Decision.DUPLICATE : Decision.BIND;
    }

    public BindingTicketItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level world, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level))
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), world.isClientSide());
        var eligible = WorkerManagementMenu.eligible(level.getEntitiesOfClass(Mob.class,
                new AABB(player.blockPosition()).inflate(8)), mob -> WorkerManagementMenu.authorized(player, mob))
                .stream().limit(64).toList();
        List<Integer> ids = eligible.stream().map(Mob::getId).toList();
        List<UUID> uuids = eligible.stream().map(Mob::getUUID).toList();
        if (ids.isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.no_workers"), true);
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        serverPlayer.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() { return Component.translatable("gui.elstarticket.workers"); }
            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory, Player owner) {
                return new WorkerManagementMenu(id, inventory, ids, uuids);
            }
        }, buffer -> WorkerManagementMenu.writeCandidates(buffer, ids, uuids));
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    static boolean validMoveTarget(WorkerProfile profile, UUID requester, UUID workerId,
                                   ResourceLocation dimension, BlockPos pos, boolean loaded,
                                   boolean inBounds, boolean permitted, boolean near) {
        return profile != null && profile.job().isEmpty() && profile.ownerId().equals(requester) &&
                profile.entityId().equals(workerId) && profile.dimension().equals(dimension) &&
                pos != null && loaded && inBounds && permitted && near;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(player.level() instanceof ServerLevel level))
            return InteractionResult.SUCCESS;
        if (!level.getServer().isSameThread()) return InteractionResult.FAIL;

        if (context.getClickedFace() != Direction.UP) return InteractionResult.FAIL;
        BlockPos pos = context.getClickedPos().above();
        var workers = level.getEntitiesOfClass(PathfinderMob.class,
                new AABB(player.blockPosition()).inflate(8), mob -> mob.isAlive() && !mob.isRemoved() &&
                        mob.hasData(ExampleMod.WORKER_PROFILE) &&
                        mob.getData(ExampleMod.WORKER_PROFILE).ownerId().equals(player.getUUID()));
        if (workers.size() != 1) {
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_worker_count"), true);
            return InteractionResult.FAIL;
        }
        PathfinderMob worker = workers.getFirst();
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        boolean valid = validMoveTarget(profile, player.getUUID(), worker.getUUID(), level.dimension().location(),
                pos, level.hasChunkAt(pos), level.isInWorldBounds(pos), player.mayBuild(),
                worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 32 * 32);
        if (!valid || !level.getBlockState(pos).isAir() ||
                profile.assignMove(player.getUUID(), worker, new WorkerTarget(level.dimension().location(), pos))
                        .map(updated -> { worker.setData(ExampleMod.WORKER_PROFILE, updated); return true; }).isEmpty()) {
            serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_invalid"), true);
            return InteractionResult.FAIL;
        }
        serverPlayer.displayClientMessage(Component.translatable("message.elstarticket.move_assigned"), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
        if (!(entity instanceof Mob)) {
            return InteractionResult.PASS;
        }

        if (!(entity.level() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        boolean bound = entity.hasData(ExampleMod.WORKER_PROFILE);
        Decision decision = decide(true, entity.isAlive() && !entity.isRemoved() && player.level() == entity.level(),
                bound, entity.hasData(ExampleMod.WORKER_OWNER), true,
                bound ? entity.getData(ExampleMod.WORKER_PROFILE) : null,
                player.getUUID(), entity.getUUID(), level.dimension().location());
        if (decision == Decision.UNAVAILABLE) {
            player.displayClientMessage(Component.translatable("message.elstarticket.unavailable"), true);
            return InteractionResult.FAIL;
        }
        if (decision == Decision.DENIED) {
            player.displayClientMessage(Component.translatable("message.elstarticket.job_denied"), true);
            return InteractionResult.FAIL;
        }
        if (decision == Decision.MANAGE) {
            if (!(player instanceof ServerPlayer serverPlayer) || !WorkerManagementMenu.authorized(player, (Mob) entity))
                return InteractionResult.FAIL;
            int workerId = entity.getId();
            UUID workerUuid = entity.getUUID();
            serverPlayer.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() { return Component.translatable("gui.elstarticket.workers"); }
                @Override
                public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory, Player owner) {
                    return new WorkerManagementMenu(id, inventory, List.of(workerId), List.of(workerUuid));
                }
            }, buffer -> WorkerManagementMenu.writeCandidates(buffer, List.of(workerId), List.of(workerUuid)));
            return InteractionResult.SUCCESS;
        }
        if (decision == Decision.DUPLICATE) {
            player.displayClientMessage(Component.translatable("message.elstarticket.already_bound"), true);
            return InteractionResult.FAIL;
        }
        entity.setData(ExampleMod.WORKER_PROFILE, new WorkerProfile(entity.getUUID(), player.getUUID(), level.dimension().location(), WorkerStatus.IDLE));
        ((Mob) entity).setPersistenceRequired();
        player.displayClientMessage(Component.translatable("message.elstarticket.bound"), true);
        return InteractionResult.SUCCESS;
    }
}