package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class WorkerManagementMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> TYPES = DeferredRegister.create(Registries.MENU, ExampleMod.MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<WorkerManagementMenu>> TYPE =
            TYPES.register("worker_management", () -> IMenuTypeExtension.create(WorkerManagementMenu::new));

    public static final int[] EQUIPMENT_SLOT_X = {10, 30, 10, 30, 66, 88};
    public static final int[] EQUIPMENT_SLOT_Y = {72, 72, 92, 92, 82, 82};
    public static final int EQUIPMENT_X = 10;
    public static final int EQUIPMENT_Y = 72;
    public static final int STORAGE_X = 14;
    public static final int STORAGE_Y = 126;
    public static final int PLAYER_INV_X = 126;
    public static final int PLAYER_INV_Y = 126;
    public static final int HOTBAR_X = 126;
    public static final int HOTBAR_Y = 184;

    private int activeTab = -1;
    private final Inventory inventory;
    private final List<Integer> candidates;
    private List<UUID> snapshots;
    private int selected;
    private int jobType;
    private int jobStatus;
    private boolean hasTarget;
    private int behavior;
    private boolean hasHome;

    private static MenuType<?> resolveMenuType() {
        try {
            if (TYPE != null && TYPE.isBound()) {
                return TYPE.get();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean suppressStorageWriteBack = false;
    private boolean testMode = false;
    private Supplier<WorkerStorage> storageReader;
    private Consumer<WorkerStorage> storageWriter;

    private WorkerStorage defaultReadStorage() {
        if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) {
            return null;
        }
        Mob mob = worker();
        return (mob != null && mob.hasData(ExampleMod.WORKER_STORAGE)) ? mob.getData(ExampleMod.WORKER_STORAGE) : null;
    }

    private void defaultWriteStorage(WorkerStorage ws) {
        if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) {
            return;
        }
        Mob mob = worker();
        if (mob != null && (testMode || (inventory != null && inventory.player != null && sessionValid(inventory.player, mob))) && candidateCount() == 1) {
            mob.setData(ExampleMod.WORKER_STORAGE, ws);
        }
    }

    private final SimpleContainer storage = new SimpleContainer(WorkerStorage.SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            if (suppressStorageWriteBack) return;
            if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) return;
            if (storageWriter != null) {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < WorkerStorage.SIZE; i++) items.add(getItem(i).copy());
                storageWriter.accept(new WorkerStorage(items));
            }
        }
    };

    private void loadStorageFromSnapshot(List<ItemStack> snapshot) {
        suppressStorageWriteBack = true;
        try {
            for (int i = 0; i < WorkerStorage.SIZE; i++) {
                ItemStack item = (snapshot != null && i < snapshot.size()) ? snapshot.get(i).copy() : ItemStack.EMPTY;
                storage.setItem(i, item);
            }
        } finally {
            suppressStorageWriteBack = false;
        }
    }

    private final EquipmentSlot[] equipment = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private final SimpleContainer equipped = new SimpleContainer(6) {
        @Override public ItemStack getItem(int index) {
            Mob mob = worker();
            return mob == null ? ItemStack.EMPTY : mob.getItemBySlot(equipment[index]).copy();
        }
        @Override public void setItem(int index, ItemStack stack) {
            Mob mob = worker();
            if (mob != null && sessionValid(inventory.player, mob) && candidateCount() == 1 && !inventory.player.level().isClientSide())
                mob.setItemSlot(equipment[index], stack);
        }
        @Override public ItemStack removeItem(int index, int count) {
            ItemStack stack = getItem(index).copy();
            ItemStack taken = stack.split(count);
            setItem(index, stack);
            return taken;
        }
    };

    private record CandidateData(List<Integer> ids, List<UUID> uuids) {}

    private static CandidateData readCandidateData(RegistryFriendlyByteBuf data) {
        int size = data.readVarInt();
        if (size < 0 || size > 64) return new CandidateData(List.of(), List.of());
        List<Integer> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(data.readVarInt());
        List<UUID> uuids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) uuids.add(data.readUUID());
        return new CandidateData(ids, uuids);
    }

    public WorkerManagementMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, inventory, readCandidateData(data));
    }

    private WorkerManagementMenu(int id, Inventory inventory, CandidateData candidateData) {
        this(id, inventory, candidateData.ids(), candidateData.uuids(), null, null);
    }

    public WorkerManagementMenu(int id, Inventory inventory, List<Integer> candidates) {
        this(id, inventory, candidates, null, null, null);
    }

    public WorkerManagementMenu(int id, Inventory inventory, List<Integer> candidates, List<UUID> uuids) {
        this(id, inventory, candidates, uuids, null, null);
    }

    public WorkerManagementMenu(int id, Inventory inventory, List<Integer> candidates, Supplier<WorkerStorage> reader, Consumer<WorkerStorage> writer) {
        this(id, inventory, candidates, null, reader, writer);
    }

    public WorkerManagementMenu(int id, Inventory inventory, List<Integer> candidates, List<UUID> uuids, Supplier<WorkerStorage> reader, Consumer<WorkerStorage> writer) {
        super(resolveMenuType(), id);
        this.inventory = inventory;
        this.candidates = List.copyOf(candidates);
        this.snapshots = (uuids != null && uuids.size() == candidates.size())
                ? List.copyOf(uuids)
                : (inventory != null && inventory.player != null && inventory.player.level() != null)
                ? candidates.stream().map(value -> {
                    var entity = inventory.player.level().getEntity(value);
                    return entity == null ? new UUID(0, 0) : entity.getUUID();
                }).toList()
                : Collections.emptyList();
        this.storageReader = (reader != null) ? reader : this::defaultReadStorage;
        this.storageWriter = (writer != null) ? writer : this::defaultWriteStorage;
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
                        WorkerJobs.WAIT.equals(job.type()) ? 1 :
                        WorkerJobs.MOVE.equals(job.type()) ? 2 :
                        WorkerJobs.MINE.equals(job.type()) ? 3 :
                        WorkerJobs.FARM.equals(job.type()) ? 4 : 0).orElse(0);
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
        addDataSlot(new DataSlot() {
            @Override public int get() {
                Mob mob = worker();
                if (mob == null || !mob.hasData(ExampleMod.WORKER_PROFILE)) return 0;
                return mob.getData(ExampleMod.WORKER_PROFILE).behavior() == WorkerBehavior.STAND ? 1 : 0;
            }
            @Override public void set(int value) { behavior = value; }
        });
        addDataSlot(new DataSlot() {
            @Override public int get() {
                Mob mob = worker();
                return mob != null && mob.hasData(ExampleMod.WORKER_PROFILE) &&
                        mob.getData(ExampleMod.WORKER_PROFILE).home().isPresent() ? 1 : 0;
            }
            @Override public void set(int value) { hasHome = value == 1; }
        });
        if (candidates.size() == 1) {
            WorkerStorage initialStorage = storageReader.get();
            if (initialStorage != null) {
                List<ItemStack> snapshot = List.copyOf(initialStorage.items().stream().map(ItemStack::copy).toList());
                loadStorageFromSnapshot(snapshot);
            }
        }
        ResourceLocation[] icons = {
                InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
                InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
                InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
                InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
                ResourceLocation.withDefaultNamespace("item/empty_slot_sword"),
                InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD
        };
        for (int i = 0; i < equipment.length; i++) {
            EquipmentSlot target = equipment[i];
            int slotX = candidates.size() == 1 ? EQUIPMENT_SLOT_X[i] : -1000;
            int slotY = candidates.size() == 1 ? EQUIPMENT_SLOT_Y[i] : -1000;
            Slot slot = new Slot(equipped, i, slotX, slotY) {
                @Override public boolean mayPlace(ItemStack stack) {
                    if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) {
                        return candidateCount() == 1 && (target == EquipmentSlot.MAINHAND || target == EquipmentSlot.OFFHAND);
                    }
                    Mob mob = worker();
                    return (testMode || (mob != null && inventory != null && sessionValid(inventory.player, mob))) && candidateCount() == 1 &&
                            (target == EquipmentSlot.MAINHAND || target == EquipmentSlot.OFFHAND || (mob != null && mob.getEquipmentSlotForItem(stack) == target));
                }
                @Override public boolean mayPickup(Player player) {
                    if (player != null && player.level().isClientSide()) return candidateCount() == 1;
                    return (testMode || sessionValid(player, worker())) && candidateCount() == 1;
                }
                @Override public boolean isActive() {
                    return isSlotActive(this.index);
                }
            };
            slot.setBackground(InventoryMenu.BLOCK_ATLAS, icons[i]);
            addSlot(slot);
        }
        for (int i = 0; i < WorkerStorage.SIZE; i++) {
            addSlot(new Slot(storage, i, candidates.size() == 1 ? STORAGE_X + (i % 5) * 18 : -1000,
                    candidates.size() == 1 ? STORAGE_Y + (i / 5) * 18 : -1000) {
                @Override public boolean mayPlace(ItemStack stack) {
                    if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) return candidateCount() == 1;
                    return candidateCount() == 1 && (testMode || (inventory != null && sessionValid(inventory.player, worker())));
                }
                @Override public boolean mayPickup(Player player) {
                    if (player != null && player.level().isClientSide()) return candidateCount() == 1;
                    return candidateCount() == 1 && (testMode || sessionValid(player, worker()));
                }
                @Override public boolean isActive() {
                    return isSlotActive(this.index);
                }
            });
        }
        Container playerInv = inventory != null ? inventory : new SimpleContainer(36);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9,
                        candidates.size() == 1 ? PLAYER_INV_X + col * 18 : -1000,
                        candidates.size() == 1 ? PLAYER_INV_Y + row * 18 : -1000) {
                    @Override public boolean isActive() {
                        return isSlotActive(this.index);
                    }
                });
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col,
                    candidates.size() == 1 ? HOTBAR_X + col * 18 : -1000,
                    candidates.size() == 1 ? HOTBAR_Y : -1000) {
                @Override public boolean isActive() {
                    return isSlotActive(this.index);
                }
            });
        }
        updateSlotPositions();
    }



    public static void writeCandidates(RegistryFriendlyByteBuf data, List<Integer> ids, List<UUID> uuids) {
        if (ids.size() != uuids.size() || ids.size() > 64) throw new IllegalArgumentException("Invalid worker selection");
        data.writeVarInt(ids.size());
        ids.forEach(data::writeVarInt);
        uuids.forEach(data::writeUUID);
    }

    public int activeTab() { return activeTab; }
    public void setActiveTab(int tab) {
        this.activeTab = tab;
        updateSlotPositions();
    }

    public void updateSlotPositions() {
        if (candidateCount() != 1 || slots.size() < 57) return;
        if (activeTab == -2) {
            return;
        }
        boolean itemsTab = (activeTab == 1);
        int storageBaseY = itemsTab ? 36 : STORAGE_Y;
        int playerInvBaseY = itemsTab ? 36 : PLAYER_INV_Y;
        int hotbarBaseY = itemsTab ? 94 : HOTBAR_Y;

        // Storage slots (indices 6..20, total 15 slots)
        for (int i = 0; i < WorkerStorage.SIZE; i++) {
            int menuIndex = 6 + i;
            int x = candidates.size() == 1 ? STORAGE_X + (i % 5) * 18 : -1000;
            int y = candidates.size() == 1 ? storageBaseY + (i / 5) * 18 : -1000;
            Slot newSlot = new Slot(storage, i, x, y) {
                @Override public boolean mayPlace(ItemStack stack) {
                    if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) return candidateCount() == 1;
                    return candidateCount() == 1 && (testMode || (inventory != null && sessionValid(inventory.player, worker())));
                }
                @Override public boolean mayPickup(Player player) {
                    if (player != null && player.level().isClientSide()) return candidateCount() == 1;
                    return candidateCount() == 1 && (testMode || sessionValid(player, worker()));
                }
                @Override public boolean isActive() {
                    return isSlotActive(this.index);
                }
            };
            newSlot.index = menuIndex;
            this.slots.set(menuIndex, newSlot);
        }
        // Player inventory (indices 21..47, total 27 slots)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int menuIndex = 21 + col + row * 9;
                int invIndex = col + row * 9 + 9;
                int x = candidates.size() == 1 ? PLAYER_INV_X + col * 18 : -1000;
                int y = candidates.size() == 1 ? playerInvBaseY + row * 18 : -1000;
                Container invContainer = inventory != null ? inventory : this.slots.get(menuIndex).container;
                Slot newSlot = new Slot(invContainer, invIndex, x, y) {
                    @Override public boolean isActive() {
                        return isSlotActive(this.index);
                    }
                };
                newSlot.index = menuIndex;
                this.slots.set(menuIndex, newSlot);
            }
        }
        // Hotbar (indices 48..56, total 9 slots)
        for (int col = 0; col < 9; col++) {
            int menuIndex = 48 + col;
            int invIndex = col;
            int x = candidates.size() == 1 ? HOTBAR_X + col * 18 : -1000;
            int y = candidates.size() == 1 ? hotbarBaseY : -1000;
            Container invContainer = inventory != null ? inventory : this.slots.get(menuIndex).container;
            Slot newSlot = new Slot(invContainer, invIndex, x, y) {
                @Override public boolean isActive() {
                    return isSlotActive(this.index);
                }
            };
            newSlot.index = menuIndex;
            this.slots.set(menuIndex, newSlot);
        }
    }
    public static boolean isSlotActive(int candidateCount, int activeTab, int slotIndex) {
        if (candidateCount != 1) return false;
        if (activeTab == -2) return false;
        if (activeTab == -1) return true;
        if (activeTab == 0) return slotIndex < 6;
        if (activeTab == 1) return slotIndex >= 6;
        return true;
    }

    public boolean isSlotActive(int slotIndex) {
        return isSlotActive(candidateCount(), activeTab, slotIndex);
    }

    public int candidateCount() { return candidates.size(); }
    public Mob candidate(int index) {
        if (index < 0 || index >= candidates.size()) return null;
        if (inventory == null || inventory.player == null || inventory.player.level() == null) return null;
        Entity entity = null;
        if (snapshots != null && index < snapshots.size()) {
            UUID expectedUuid = snapshots.get(index);
            if (expectedUuid != null && !expectedUuid.equals(new UUID(0, 0))) {
                if (inventory.player.level() instanceof ServerLevel serverLevel) {
                    entity = serverLevel.getEntity(expectedUuid);
                }
            }
        }
        if (entity == null) {
            entity = inventory.player.level().getEntity(candidates.get(index));
        }
        return entity instanceof Mob mob && (snapshots == null || index >= snapshots.size() || candidateMatches(snapshots.get(index), mob.getUUID())) ? mob : null;
    }
    public int selectedIndex() { return selected; }
    public int jobType() { return jobType; }
    public int jobStatus() { return jobStatus; }
    public boolean hasTarget() { return hasTarget; }
    public int behavior() { return behavior; }
    public boolean hasHome() { return hasHome; }

    static <T> List<T> eligible(List<T> candidates, Predicate<T> allowed) {
        return candidates.stream().filter(allowed).toList();
    }

    static boolean candidateMatches(UUID expected, UUID actual) {
        return expected != null && expected.equals(actual);
    }

    public Mob worker() {
        return candidate(selected);
    }

    public static boolean sessionValid(Player player, Mob mob) {
        if (player == null || mob == null || !mob.isAlive() || mob.isRemoved() || mob.level() != player.level()) return false;
        if (player.level().isClientSide()) return true;
        if (!mob.hasData(ExampleMod.WORKER_PROFILE)) return false;
        WorkerProfile profile = mob.getData(ExampleMod.WORKER_PROFILE);
        return authorizedProfile(profile, player.getUUID(), mob.getUUID(), player.level().dimension().location());
    }

    public static boolean authorized(Player player, Mob mob) {
        if (mob == null || player.distanceToSqr(mob) > 64) return false;
        return sessionValid(player, mob);
    }

    static boolean authorizedProfile(WorkerProfile profile, UUID requester, UUID entity, ResourceLocation dimension) {
        return profile != null && profile.ownerId().equals(requester) && profile.entityId().equals(entity) &&
                profile.dimension().equals(dimension);
    }

    public boolean syncFromEntityStorageIfChanged() {
        if (candidateCount() != 1) return false;
        if (inventory != null && inventory.player != null && inventory.player.level().isClientSide()) {
            return false;
        }
        WorkerStorage currentAttachment = storageReader.get();
        if (currentAttachment == null) return false;
        if (currentAttachment.matchesContainer(storage)) {
            return false;
        }
        loadStorageFromSnapshot(currentAttachment.items());
        return true;
    }

    @Override
    public void broadcastChanges() {
        syncFromEntityStorageIfChanged();
        super.broadcastChanges();
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        syncFromEntityStorageIfChanged();
        super.clicked(slotIndex, button, clickType, player);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!testMode && !(player instanceof ServerPlayer)) return false;
        if (!stillValid(player)) return false;
        syncFromEntityStorageIfChanged();
        if (id >= 0 && id < candidates.size()) {
            Mob mob = candidate(id);
            if (sessionValid(player, mob)) {
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
        if (id == 110) {
            setActiveTab(-1);
            return true;
        } else if (id == 111) {
            setActiveTab(0);
            return true;
        } else if (id == 112) {
            setActiveTab(1);
            return true;
        }
        Mob mob = worker();
        if (!testMode && !sessionValid(player, mob)) return false;
        if (mob == null || !mob.hasData(ExampleMod.WORKER_PROFILE)) return false;
        WorkerProfile profile = mob.getData(ExampleMod.WORKER_PROFILE);
        if (id == 100) {
            return profile.cancel(player.getUUID()).map(updated -> {
                mob.setData(ExampleMod.WORKER_PROFILE, updated);
                player.displayClientMessage(Component.translatable("message.elstarticket.job_cancelled"), true);
                return true;
            }).orElse(false);
        } else if (id == 101) {
            return profile.setBehavior(player.getUUID(), WorkerBehavior.FOLLOW).map(updated -> {
                mob.setData(ExampleMod.WORKER_PROFILE, updated);
                player.displayClientMessage(Component.translatable("message.elstarticket.behavior_follow"), true);
                return true;
            }).orElse(false);
        } else if (id == 102) {
            return profile.setBehavior(player.getUUID(), WorkerBehavior.STAND).map(updated -> {
                mob.setData(ExampleMod.WORKER_PROFILE, updated);
                player.displayClientMessage(Component.translatable("message.elstarticket.behavior_stand"), true);
                return true;
            }).orElse(false);
        } else if (id == 103) {
            BindingTicketItem.armHomeSelection(player.getUUID(), mob.getUUID());
            player.closeContainer();
            player.displayClientMessage(Component.translatable("message.elstarticket.home_selection_armed"), true);
            return true;
        } else if (id == 104 || (id >= 201 && id <= 203)) {
            // Explicit Job Choice: Mine Task (104=default 64, 201=16, 202=32, 203=64)
            long quota = id == 201 ? 16L : id == 202 ? 32L : 64L;
            WorkerExecutionLoop.ensureMiningToolEquipped(mob);
            syncFromEntityStorageIfChanged();
            ItemStack heldTool = mob.getMainHandItem();
            boolean hasTool = WorkerJobCapability.Registry.canPerformMining(mob, heldTool);

            if (player instanceof ServerPlayer serverPlayer) {
                if (WorkerOrderSession.startOrder(serverPlayer, mob, WorkerJobs.MINE, quota)) {
                    player.closeContainer();
                    if (hasTool) {
                        player.displayClientMessage(Component.translatable("message.elstarticket.order_select_worksite_mine"), true);
                    } else {
                        player.displayClientMessage(Component.translatable("message.elstarticket.order_select_worksite_mine_need_tool"), true);
                    }
                    return true;
                }
            }
            return false;
        } else if (id == 105) {
            // Explicit Job Choice: Move Task
            if (player instanceof ServerPlayer serverPlayer) {
                if (WorkerOrderSession.startOrder(serverPlayer, mob, WorkerJobs.MOVE)) {
                    player.closeContainer();
                    player.displayClientMessage(Component.translatable("message.elstarticket.order_select_worksite_move"), true);
                    return true;
                }
            }
            return false;
        } else if (id == 106 || (id >= 211 && id <= 213) || (id >= 221 && id <= 223)) {
            // Explicit Job Choice: Farm Task
            // 106=default 64 (ALL_SUPPORTED)
            // 211=16, 212=32, 213=64 (ALL_SUPPORTED)
            // 221=16, 222=32, 223=64 (SELECTED_ONLY)
            long quota = (id == 211 || id == 221) ? 16L : (id == 212 || id == 222) ? 32L : 64L;
            CropSelectionMode cropMode = (id >= 221 && id <= 223) ? CropSelectionMode.SELECTED_ONLY : CropSelectionMode.ALL_SUPPORTED;
            WorkerExecutionLoop.ensureFarmingToolEquipped(mob);
            syncFromEntityStorageIfChanged();
            ItemStack heldTool = mob.getMainHandItem();
            boolean hasTool = JobToolRequirement.FARMING.isSatisfiedBy(heldTool);

            if (player instanceof ServerPlayer serverPlayer) {
                if (WorkerOrderSession.startOrder(serverPlayer, mob, WorkerJobs.FARM, quota, cropMode)) {
                    player.closeContainer();
                    if (hasTool) {
                        player.displayClientMessage(Component.translatable("message.elstarticket.order_select_worksite_farm"), true);
                    } else {
                        player.displayClientMessage(Component.translatable("message.elstarticket.order_select_worksite_mine_need_tool"), true);
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        if (testMode) return true;
        if (player == null) return false;
        if (player.level().isClientSide()) return true;
        if (candidates.size() == 1) return sessionValid(player, worker());
        if (candidates.size() > 64) return false;
        for (int index = 0; index < candidates.size(); index++) {
            Mob mob = candidate(index);
            if (sessionValid(player, mob)) return true;
        }
        return false;
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || candidateCount() != 1 || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        syncFromEntityStorageIfChanged();
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

    public SimpleContainer getStorageContainer() {
        return storage;
    }

    public boolean isStorageWriteBackSuppressed() {
        return suppressStorageWriteBack;
    }

    void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }

    void configureStorageBindingsForTesting(Supplier<WorkerStorage> reader, Consumer<WorkerStorage> writer) {
        if (reader != null) this.storageReader = reader;
        if (writer != null) this.storageWriter = writer;
    }
}
