package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class WorkerManagementMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> TYPES = DeferredRegister.create(Registries.MENU, ExampleMod.MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<WorkerManagementMenu>> TYPE =
            TYPES.register("worker_management", () -> IMenuTypeExtension.create(WorkerManagementMenu::new));
    private final Inventory inventory;
    private final List<Integer> candidates;
    private List<UUID> snapshots;
    private int selected;
    private int jobType;
    private int jobStatus;
    private boolean hasTarget;
    private final SimpleContainer storage = new SimpleContainer(WorkerStorage.SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            Mob mob = worker();
            if (mob != null && authorized(inventory.player, mob) && !inventory.player.level().isClientSide() && candidateCount() == 1) {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < WorkerStorage.SIZE; i++) items.add(getItem(i).copy());
                mob.setData(ExampleMod.WORKER_STORAGE, new WorkerStorage(items));
            }
        }
    };
    private final EquipmentSlot[] equipment = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private final SimpleContainer equipped = new SimpleContainer(6) {
        @Override public ItemStack getItem(int index) {
            Mob mob = worker();
            return mob == null ? ItemStack.EMPTY : mob.getItemBySlot(equipment[index]).copy();
        }
        @Override public void setItem(int index, ItemStack stack) {
            Mob mob = worker();
            if (mob != null && authorized(inventory.player, mob) && candidateCount() == 1 && !inventory.player.level().isClientSide())
                mob.setItemSlot(equipment[index], stack);
        }
        @Override public ItemStack removeItem(int index, int count) {
            ItemStack stack = getItem(index).copy();
            ItemStack taken = stack.split(count);
            setItem(index, stack);
            return taken;
        }
    };

    public WorkerManagementMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, inventory, readCandidates(data));
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) uuids.add(data.readUUID());
        snapshots = List.copyOf(uuids);
    }

    public WorkerManagementMenu(int id, Inventory inventory, List<Integer> candidates) {
        super(TYPE.get(), id);
        this.inventory = inventory;
        this.candidates = List.copyOf(candidates);
        this.snapshots = candidates.stream().map(value -> {
            var entity = inventory.player.level().getEntity(value);
            return entity == null ? new UUID(0, 0) : entity.getUUID();
        }).toList();
        this.selected = candidates.size() == 1 ? 0 : -1;
        addDataSlot(new DataSlot() {
            @Override public int get() { return selected; }
            @Override public void set(int value) { selected = value; }
        });
        addDataSlot(new DataSlot() {
            @Override public int get() {
                Mob mob = worker();
                if (mob == null || !mob.hasData(ExampleMod.WORKER_PROFILE)) return 0;
                return mob.getData(ExampleMod.WORKER_PROFILE).job().map(job ->
                        WorkerJobs.WAIT.equals(job.type()) ? 1 : WorkerJobs.MOVE.equals(job.type()) ? 2 : 0).orElse(0);
            }
            @Override public void set(int value) { jobType = value; }
        });
        addDataSlot(new DataSlot() {
            @Override public int get() {
                Mob mob = worker();
                if (mob == null || !mob.hasData(ExampleMod.WORKER_PROFILE)) return -1;
                return mob.getData(ExampleMod.WORKER_PROFILE).job().map(job -> job.status().ordinal()).orElse(-1);
            }
            @Override public void set(int value) { jobStatus = value; }
        });
        addDataSlot(new DataSlot() {
            @Override public int get() {
                Mob mob = worker();
                return mob != null && mob.hasData(ExampleMod.WORKER_PROFILE) &&
                        mob.getData(ExampleMod.WORKER_PROFILE).job().flatMap(WorkerJob::target).isPresent() ? 1 : 0;
            }
            @Override public void set(int value) { hasTarget = value == 1; }
        });
        if (candidates.size() == 1) {
            Mob mob = worker();
            if (mob != null && mob.hasData(ExampleMod.WORKER_STORAGE)) {
                for (int i = 0; i < WorkerStorage.SIZE; i++) storage.setItem(i, mob.getData(ExampleMod.WORKER_STORAGE).items().get(i).copy());
            }
        }
        for (int i = 0; i < equipment.length; i++) {
            EquipmentSlot target = equipment[i];
            addSlot(new Slot(equipped, i, candidates.size() == 1 ? 178 + i % 2 * 18 : -1000, 23 + i / 2 * 18) {
                @Override public boolean mayPlace(ItemStack stack) { Mob mob = worker(); return mob != null && authorized(inventory.player, mob) && candidateCount() == 1 && (target == EquipmentSlot.MAINHAND || target == EquipmentSlot.OFFHAND || mob.getEquipmentSlotForItem(stack) == target); }
                @Override public boolean mayPickup(Player player) { return authorized(player, worker()) && candidateCount() == 1; }
            });
        }
        for (int i = 0; i < WorkerStorage.SIZE; i++) {
            addSlot(new Slot(storage, i, candidates.size() == 1 ? 34 + i % 5 * 18 : -1000, 85 + i / 5 * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return candidateCount() == 1 && authorized(inventory.player, worker()); }
                @Override public boolean mayPickup(Player player) { return candidateCount() == 1 && authorized(player, worker()); }
            });
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, candidates.size() == 1 ? 34 + col * 18 : -1000, 191 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, candidates.size() == 1 ? 34 + col * 18 : -1000, 249));
    }

    public WorkerManagementMenu(int id, Inventory inventory, List<Integer> candidates, List<UUID> uuids) {
        this(id, inventory, candidates);
        if (candidates.size() != uuids.size()) throw new IllegalArgumentException("Worker identity count mismatch");
        snapshots = List.copyOf(uuids);
    }

    private static List<Integer> readCandidates(RegistryFriendlyByteBuf data) {
        int size = data.readVarInt();
        if (size < 0 || size > 64) return List.of();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < size; i++) ids.add(data.readVarInt());
        return ids;
    }

    public static void writeCandidates(RegistryFriendlyByteBuf data, List<Integer> ids, List<UUID> uuids) {
        if (ids.size() != uuids.size() || ids.size() > 64) throw new IllegalArgumentException("Invalid worker selection");
        data.writeVarInt(ids.size());
        ids.forEach(data::writeVarInt);
        uuids.forEach(data::writeUUID);
    }

    public int candidateCount() { return candidates.size(); }
    public Mob candidate(int index) {
        if (index < 0 || index >= candidates.size()) return null;
        var entity = inventory.player.level().getEntity(candidates.get(index));
        return entity instanceof Mob mob && candidateMatches(snapshots.get(index), mob.getUUID()) ? mob : null;
    }
    public int selectedIndex() { return selected; }
    public int jobType() { return jobType; }
    public int jobStatus() { return jobStatus; }
    public boolean hasTarget() { return hasTarget; }

    static <T> List<T> eligible(List<T> candidates, Predicate<T> allowed) {
        return candidates.stream().filter(allowed).toList();
    }

    static boolean candidateMatches(UUID expected, UUID actual) {
        return expected != null && expected.equals(actual);
    }

    public Mob worker() {
        return candidate(selected);
    }

    public static boolean authorized(Player player, Mob mob) {
        if (mob == null || !mob.isAlive() || mob.isRemoved() || mob.level() != player.level() ||
                player.distanceToSqr(mob) > 64 || !mob.hasData(ExampleMod.WORKER_PROFILE)) return false;
        WorkerProfile profile = mob.getData(ExampleMod.WORKER_PROFILE);
        return authorizedProfile(profile, player.getUUID(), mob.getUUID(), player.level().dimension().location());
    }

    static boolean authorizedProfile(WorkerProfile profile, UUID requester, UUID entity, ResourceLocation dimension) {
        return profile != null && profile.ownerId().equals(requester) && profile.entityId().equals(entity) &&
                profile.dimension().equals(dimension);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer) || !stillValid(player)) return false;
        if (id >= 0 && id < candidates.size()) {
            Mob mob = candidate(id);
            if (authorized(player, mob)) {
                if (candidates.size() > 1) {
                    int workerId = mob.getId();
                    UUID workerUuid = mob.getUUID();
                    ((ServerPlayer) player).openMenu(new net.minecraft.world.MenuProvider() {
                        @Override public Component getDisplayName() { return Component.translatable("gui.elstarticket.workers"); }
                        @Override public AbstractContainerMenu createMenu(int menuId, Inventory playerInventory, Player owner) {
                            return new WorkerManagementMenu(menuId, playerInventory, List.of(workerId), List.of(workerUuid));
                        }
                    }, buffer -> writeCandidates(buffer, List.of(workerId), List.of(workerUuid)));
                } else selected = id;
                return true;
            }
            return false;
        }
        Mob mob = worker();
        if (id != 100 || !authorized(player, mob)) return false;
        WorkerProfile profile = mob.getData(ExampleMod.WORKER_PROFILE);
        return profile.cancel(player.getUUID()).map(updated -> {
            mob.setData(ExampleMod.WORKER_PROFILE, updated);
            player.displayClientMessage(Component.translatable("message.elstarticket.job_cancelled"), true);
            return true;
        }).orElse(false);
    }

    @Override
    public boolean stillValid(Player player) {
        if (candidates.size() == 1) return authorized(player, worker());
        if (candidates.size() > 64) return false;
        for (int index = 0; index < candidates.size(); index++) {
            Mob mob = candidate(index);
            if (authorized(player, mob)) return true;
        }
        return false;
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || candidateCount() != 1 || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 21) {
            if (!moveItemStackTo(stack, 21, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 6, 21, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.set(stack);
        return original;
    }
}