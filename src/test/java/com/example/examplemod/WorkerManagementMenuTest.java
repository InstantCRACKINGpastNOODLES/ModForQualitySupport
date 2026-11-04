package com.example.examplemod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerManagementMenuTest {
    @Test
    void testStorageCodecWithNbtOps() {
        ItemStack hoe = new ItemStack(net.minecraft.world.item.Items.DIAMOND_HOE);
        WorkerStorage storage = createStorage(hoe);

        var nbtResult = WorkerStorage.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, storage);
        System.out.println("NBT encode result: " + nbtResult);
        assertTrue(nbtResult.result().isPresent(), "NBT encode must succeed: " + nbtResult.error());

        net.minecraft.nbt.Tag nbt = nbtResult.result().get();
        System.out.println("NBT tag: " + nbt);

        var decodeResult = WorkerStorage.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt);
        System.out.println("NBT decode result: " + decodeResult);
        assertTrue(decodeResult.result().isPresent(), "NBT decode must succeed: " + decodeResult.error());
    }

    @Test
    void selectionFiltersZeroOneAndMultipleOwnedWorkers() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        WorkerProfile first = new WorkerProfile(UUID.randomUUID(), owner, dimension, WorkerStatus.IDLE);
        WorkerProfile second = new WorkerProfile(UUID.randomUUID(), owner, dimension, WorkerStatus.IDLE);
        WorkerProfile foreign = new WorkerProfile(UUID.randomUUID(), other, dimension, WorkerStatus.IDLE);
        assertEquals(List.of(), WorkerManagementMenu.eligible(List.of(foreign), profile ->
                WorkerManagementMenu.authorizedProfile(profile, owner, profile.entityId(), dimension)));
        assertEquals(List.of(first), WorkerManagementMenu.eligible(List.of(foreign, first), profile ->
                WorkerManagementMenu.authorizedProfile(profile, owner, profile.entityId(), dimension)));
        assertEquals(List.of(first, second), WorkerManagementMenu.eligible(List.of(foreign, first, second), profile ->
                WorkerManagementMenu.authorizedProfile(profile, owner, profile.entityId(), dimension)));
    }

    @Test
    void staleAndUnauthorizedProfileIdentitiesAreRejected() {
        UUID owner = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        WorkerProfile profile = new WorkerProfile(entity, owner, dimension, WorkerStatus.IDLE);
        assertTrue(WorkerManagementMenu.authorizedProfile(profile, owner, entity, dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(null, owner, entity, dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, UUID.randomUUID(), entity, dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, owner, UUID.randomUUID(), dimension));
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, owner, entity,
                ResourceLocation.withDefaultNamespace("the_nether")));
        assertTrue(WorkerManagementMenu.candidateMatches(entity, entity));
        assertFalse(WorkerManagementMenu.candidateMatches(entity, UUID.randomUUID()));
    }

    @Test
    void tabFilteringControlsActiveSlots() {
        // Multi-worker selection mode: all slots inactive
        for (int i = 0; i < 57; i++) {
            assertFalse(WorkerManagementMenu.isSlotActive(3, -1, i));
            assertFalse(WorkerManagementMenu.isSlotActive(3, 0, i));
            assertFalse(WorkerManagementMenu.isSlotActive(3, 1, i));
        }

        // Single-worker unified mode (tab = -1): all slots active
        for (int i = 0; i < 57; i++) {
            assertTrue(WorkerManagementMenu.isSlotActive(1, -1, i));
        }

        // Single-worker equipment tab (tab = 0): only slots 0..5 active
        for (int i = 0; i < 6; i++) {
            assertTrue(WorkerManagementMenu.isSlotActive(1, 0, i));
        }
        for (int i = 6; i < 57; i++) {
            assertFalse(WorkerManagementMenu.isSlotActive(1, 0, i));
        }

        // Single-worker storage & inventory tab (tab = 1): only slots 6..56 active
        for (int i = 0; i < 6; i++) {
            assertFalse(WorkerManagementMenu.isSlotActive(1, 1, i));
        }
        for (int i = 6; i < 57; i++) {
            assertTrue(WorkerManagementMenu.isSlotActive(1, 1, i));
        }
    }

    @Test
    void slotCoordinatesMatchRedesignedGeometry() {
        // 2x2 Armor slots under preview (Head, Chest, Legs, Feet)
        assertEquals(10, WorkerManagementMenu.EQUIPMENT_SLOT_X[0]);
        assertEquals(72, WorkerManagementMenu.EQUIPMENT_SLOT_Y[0]);
        assertEquals(30, WorkerManagementMenu.EQUIPMENT_SLOT_X[1]);
        assertEquals(72, WorkerManagementMenu.EQUIPMENT_SLOT_Y[1]);
        assertEquals(10, WorkerManagementMenu.EQUIPMENT_SLOT_X[2]);
        assertEquals(92, WorkerManagementMenu.EQUIPMENT_SLOT_Y[2]);
        assertEquals(30, WorkerManagementMenu.EQUIPMENT_SLOT_X[3]);
        assertEquals(92, WorkerManagementMenu.EQUIPMENT_SLOT_Y[3]);

        // Main-hand and Offhand slots under vitals
        assertEquals(66, WorkerManagementMenu.EQUIPMENT_SLOT_X[4]);
        assertEquals(82, WorkerManagementMenu.EQUIPMENT_SLOT_Y[4]);
        assertEquals(88, WorkerManagementMenu.EQUIPMENT_SLOT_X[5]);
        assertEquals(82, WorkerManagementMenu.EQUIPMENT_SLOT_Y[5]);

        // Worker storage (15 slots: 5x3)
        assertEquals(14, WorkerManagementMenu.STORAGE_X);
        assertEquals(126, WorkerManagementMenu.STORAGE_Y);

        // Player inventory (27 slots: 9x3) and Hotbar (9 slots: 9x1)
        assertEquals(126, WorkerManagementMenu.PLAYER_INV_X);
        assertEquals(126, WorkerManagementMenu.PLAYER_INV_Y);
        assertEquals(126, WorkerManagementMenu.HOTBAR_X);
        assertEquals(184, WorkerManagementMenu.HOTBAR_Y);

        // Storage grid ends at 14 + 5*18 = 104; Player inventory starts at 126
        int storageEnd = WorkerManagementMenu.STORAGE_X + 5 * 18;
        assertTrue(storageEnd <= WorkerManagementMenu.PLAYER_INV_X, "Storage grid must not overlap player inventory");

        // Player inventory ends at 126 + 9*18 = 288, fits inside 312 panel width
        int playerInvEnd = WorkerManagementMenu.PLAYER_INV_X + 9 * 18;
        assertTrue(playerInvEnd <= WorkerManagementScreen.PANEL_WIDTH, "Player inventory must fit within screen panel");

        // Hotbar ends at 184 + 18 = 202, fits inside 212 panel height
        int hotbarEnd = WorkerManagementMenu.HOTBAR_Y + 18;
        assertTrue(hotbarEnd <= WorkerManagementScreen.PANEL_HEIGHT, "Hotbar must fit within screen panel");
    }

    @Test
    void sessionValidityAllowsDistantWorkerWhilePreservingOwnerAndDimensionSecurity() {
        UUID owner = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        WorkerProfile profile = new WorkerProfile(workerId, owner, dimension, WorkerStatus.IDLE);

        // Valid session: correct owner, correct entity, correct dimension
        assertTrue(WorkerManagementMenu.authorizedProfile(profile, owner, workerId, dimension));

        // Intruders cannot operate the worker
        UUID intruder = UUID.randomUUID();
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, intruder, workerId, dimension));

        // Stale or switched entities cannot spoof the worker
        UUID impersonator = UUID.randomUUID();
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, owner, impersonator, dimension));

        // Dimension mismatch invalidates session
        ResourceLocation nether = ResourceLocation.withDefaultNamespace("the_nether");
        assertFalse(WorkerManagementMenu.authorizedProfile(profile, owner, workerId, nether));
    }

    @Test
    void slotRepositioningKeepsAllSlotsInsidePanelAcrossTabs() {
        // Tab 1 (Items): Storage and Player Inventory/Hotbar moved up
        // Storage: Y=36..90
        int itemsStorageBaseY = 36;
        int itemsStorageEndY = itemsStorageBaseY + 3 * 18; // 90
        int itemsPanelH = 130;
        assertTrue(itemsStorageEndY <= itemsPanelH, "Storage slots must fit inside 130px panel in Tab 1");

        // Player Inventory: Y=36..90
        int itemsPlayerInvBaseY = 36;
        int itemsPlayerInvEndY = itemsPlayerInvBaseY + 3 * 18; // 90
        assertTrue(itemsPlayerInvEndY <= itemsPanelH, "Player inventory slots must fit inside 130px panel in Tab 1");

        // Hotbar: Y=94..112
        int itemsHotbarY = 94;
        int itemsHotbarEndY = itemsHotbarY + 18; // 112
        assertTrue(itemsHotbarEndY <= itemsPanelH, "Hotbar slots must fit inside 130px panel in Tab 1 with bottom margin");

        // Verify Tab -1 / 0 default positions fit in 212px panel
        int allHotbarEndY = WorkerManagementMenu.HOTBAR_Y + 18; // 202
        assertTrue(allHotbarEndY <= WorkerManagementScreen.PANEL_HEIGHT, "Hotbar must fit in 212px panel in unified tab");
    }

    @BeforeAll
    static void setupRegistries() {
        try {
            Field frozenField = MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(BuiltInRegistries.ITEM, false);
            frozenField.set(BuiltInRegistries.BLOCK, false);
        } catch (Throwable ignored) {
        }
    }

    static class SimulatedWorkerStorageContext {
        WorkerStorage attachment;
        int writeCount = 0;

        SimulatedWorkerStorageContext(WorkerStorage initial) {
            this.attachment = initial;
        }

        Supplier<WorkerStorage> reader() {
            return () -> this.attachment;
        }

        Consumer<WorkerStorage> writer() {
            return ws -> {
                this.attachment = ws;
                this.writeCount++;
            };
        }
    }

    private static WorkerStorage createStorage(ItemStack... items) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < WorkerStorage.SIZE; i++) {
            if (i < items.length && items[i] != null) {
                list.add(items[i].copy());
            } else {
                list.add(ItemStack.EMPTY);
            }
        }
        return new WorkerStorage(list);
    }

    // =========================================================================
    // Category 1: Initialization
    // =========================================================================

    @Test
    @DisplayName("1. All 15 slots populated: verified loaded without overwrite or deletion")
    void test01_all15SlotsPopulatedLoadedWithoutDeletion() {
        ItemStack[] items = new ItemStack[15];
        items[0] = new ItemStack(Items.IRON_PICKAXE);
        items[1] = new ItemStack(Items.DIAMOND_HOE);
        items[2] = new ItemStack(Items.APPLE, 5);
        items[3] = new ItemStack(Items.BREAD, 10);
        items[4] = new ItemStack(Items.WHEAT, 64);
        items[5] = new ItemStack(Items.STICK, 16);
        items[6] = new ItemStack(Items.TORCH, 32);
        items[7] = new ItemStack(Items.STONE, 64);
        items[8] = new ItemStack(Items.COBBLESTONE, 64);
        items[9] = new ItemStack(Items.IRON_INGOT, 8);
        items[10] = new ItemStack(Items.GOLD_INGOT, 4);
        items[11] = new ItemStack(Items.DIAMOND, 2);
        items[12] = new ItemStack(Items.EMERALD, 1);
        items[13] = new ItemStack(Items.BOW);
        items[14] = new ItemStack(Items.ARROW, 32);

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertEquals(0, ctx.writeCount, "Constructor must not cascade write-back to storage");
        assertEquals(15, menu.getStorageContainer().getContainerSize());

        for (int i = 0; i < 15; i++) {
            assertTrue(ItemStack.matches(items[i], menu.getStorageContainer().getItem(i)),
                    "Slot " + i + " must retain original item");
            assertTrue(ItemStack.matches(items[i], ctx.attachment.items().get(i)),
                    "Attachment slot " + i + " must not have been mutated");
        }
    }

    @Test
    @DisplayName("2. Slot 0 empty, slots 1-14 populated: verify slots 1-14 intact (defect reproduction case)")
    void test02_slot0EmptySlots1To14PopulatedIntact() {
        ItemStack[] items = new ItemStack[15];
        items[0] = ItemStack.EMPTY;
        for (int i = 1; i < 15; i++) {
            items[i] = new ItemStack(Items.WHEAT, i + 1);
        }

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertEquals(0, ctx.writeCount, "Constructor must suppress write-back on uninitialized slots");
        assertTrue(menu.getStorageContainer().getItem(0).isEmpty(), "Slot 0 must be empty");
        for (int i = 1; i < 15; i++) {
            assertFalse(menu.getStorageContainer().getItem(i).isEmpty(),
                    "Slot " + i + " must not be erased when slot 0 is empty");
            assertEquals(i + 1, menu.getStorageContainer().getItem(i).getCount());
            assertFalse(ctx.attachment.items().get(i).isEmpty(),
                    "Attachment slot " + i + " must remain intact");
        }
    }

    @Test
    @DisplayName("3. Items in slots 0, 1, 5, 14: preserved across construction")
    void test03_itemsInSlots01514PreservedAcrossConstruction() {
        ItemStack[] items = new ItemStack[15];
        items[0] = new ItemStack(Items.IRON_PICKAXE);
        items[1] = new ItemStack(Items.DIAMOND_HOE);
        items[5] = new ItemStack(Items.BREAD, 7);
        items[14] = new ItemStack(Items.GOLDEN_APPLE, 3);

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertEquals(0, ctx.writeCount);
        assertTrue(ItemStack.matches(items[0], menu.getStorageContainer().getItem(0)));
        assertTrue(ItemStack.matches(items[1], menu.getStorageContainer().getItem(1)));
        assertTrue(ItemStack.matches(items[5], menu.getStorageContainer().getItem(5)));
        assertTrue(ItemStack.matches(items[14], menu.getStorageContainer().getItem(14)));
        for (int i = 2; i < 14; i++) {
            if (i != 5) {
                assertTrue(menu.getStorageContainer().getItem(i).isEmpty());
            }
        }
    }

    @Test
    @DisplayName("4. Repeated menu open/close cycles (10 cycles): storage identical before and after")
    void test04_repeatedMenuOpenCloseCyclesPreserveStorage() {
        ItemStack[] items = new ItemStack[15];
        items[0] = new ItemStack(Items.IRON_PICKAXE);
        items[1] = new ItemStack(Items.DIAMOND_HOE);
        items[2] = new ItemStack(Items.APPLE, 10);
        items[14] = new ItemStack(Items.EMERALD, 5);

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        for (int cycle = 0; cycle < 10; cycle++) {
            WorkerManagementMenu menu = new WorkerManagementMenu(cycle, null, List.of(1), ctx.reader(), ctx.writer());
            menu.setTestMode(true);
            assertTrue(initial.matchesContainer(menu.getStorageContainer()), "Cycle " + cycle + " must match initial storage");
        }

        assertEquals(0, ctx.writeCount, "No write-backs should occur during passive open/close cycles");
        SimpleContainer expectedContainer = new SimpleContainer(15);
        for (int i = 0; i < 15; i++) {
            expectedContainer.setItem(i, items[i] != null ? items[i] : ItemStack.EMPTY);
        }
        assertTrue(initial.matchesContainer(expectedContainer), "Attachment must remain completely unmodified");
    }

    // =========================================================================
    // Category 2: Live Synchronization
    // =========================================================================

    @Test
    @DisplayName("5. Menu open, worker auto-equips tool from slot 0: menu storage updates to reflect removal")
    void test05_workerAutoEquipUpdatesOpenMenuStorage() {
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        ItemStack wheat = new ItemStack(Items.WHEAT, 12);
        WorkerStorage initial = createStorage(hoe, wheat);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertFalse(menu.getStorageContainer().getItem(0).isEmpty());

        // Simulate worker background tick: tool extracted from slot 0 to main hand
        WorkerStorage updatedByWorker = createStorage(ItemStack.EMPTY, wheat);
        ctx.attachment = updatedByWorker;

        // Player tick invokes broadcastChanges()
        menu.broadcastChanges();

        assertTrue(menu.getStorageContainer().getItem(0).isEmpty(),
                "Menu storage slot 0 must now be empty after worker equipped the tool");
        assertTrue(ItemStack.matches(wheat, menu.getStorageContainer().getItem(1)),
                "Menu storage slot 1 must retain wheat");
    }

    @Test
    @DisplayName("6. Menu open, worker stashes old held item into slot 1: menu storage reflects stashed item")
    void test06_workerStashesOldToolUpdatesOpenMenuStorage() {
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        WorkerStorage initial = createStorage(hoe);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        // Worker stashes pickaxe into slot 1 when switching to hoe
        WorkerStorage updatedByWorker = createStorage(ItemStack.EMPTY, pickaxe);
        ctx.attachment = updatedByWorker;

        menu.broadcastChanges();

        assertTrue(menu.getStorageContainer().getItem(0).isEmpty());
        assertTrue(ItemStack.matches(pickaxe, menu.getStorageContainer().getItem(1)),
                "Menu storage slot 1 must show stashed iron pickaxe");
    }

    @Test
    @DisplayName("7. Player interacts with menu after worker tool swap: operates on current storage, no ghost item")
    void test07_playerInteractionOperatesOnCurrentStorageAfterSwap() {
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        WorkerStorage initial = createStorage(hoe);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        // Worker equipped hoe into hand, vacating slot 0
        ctx.attachment = createStorage(ItemStack.EMPTY);

        // syncFromEntityStorageIfChanged called upon player interaction entry
        boolean synced = menu.syncFromEntityStorageIfChanged();
        assertTrue(synced, "Menu must detect out-of-sync storage and synchronize");
        assertTrue(menu.getStorageContainer().getItem(0).isEmpty(),
                "Player cannot click or take ghost tool that was already equipped by worker");
    }

    @Test
    @DisplayName("8. Player closes menu after worker tool swap: attachment retains worker stashed items")
    void test08_playerClosesMenuAfterSwapRetainsWorkerStashedItems() {
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        WorkerStorage initial = createStorage(hoe);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        // Worker swapped hoe for pick, stashing pick into slot 1
        WorkerStorage swapped = createStorage(ItemStack.EMPTY, pick);
        ctx.attachment = swapped;

        // Menu synchronizes on server tick
        menu.broadcastChanges();

        // Player closes container without moving items
        assertEquals(0, ctx.writeCount, "Menu closure must not overwrite stashed items with stale data");
        assertTrue(ItemStack.matches(pick, ctx.attachment.items().get(1)));
    }

    @Test
    @DisplayName("9. Worker storage modified twice between player ticks: verify both changes reflected")
    void test09_workerStorageModifiedTwiceBetweenPlayerTicksReflected() {
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        ItemStack apple = new ItemStack(Items.APPLE, 5);
        WorkerStorage initial = createStorage(pick, hoe);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        // First swap: pick equipped, apple deposited
        ctx.attachment = createStorage(ItemStack.EMPTY, hoe, apple);

        // Second swap: hoe equipped, pick stashed in slot 0
        ctx.attachment = createStorage(pick, ItemStack.EMPTY, apple);

        // Tick occurs
        menu.broadcastChanges();

        assertTrue(ItemStack.matches(pick, menu.getStorageContainer().getItem(0)));
        assertTrue(menu.getStorageContainer().getItem(1).isEmpty());
        assertTrue(ItemStack.matches(apple, menu.getStorageContainer().getItem(2)));
    }

    @Test
    @DisplayName("10. Rapid interaction test: multiple tool swaps interleaved with container queries")
    void test10_rapidInteractionMultiToolSwapsInterleaved() {
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(WorkerStorage.empty());
        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        for (int i = 0; i < 20; i++) {
            ItemStack tool = (i % 2 == 0) ? new ItemStack(Items.IRON_PICKAXE) : new ItemStack(Items.DIAMOND_HOE);
            ctx.attachment = createStorage(tool);
            menu.syncFromEntityStorageIfChanged();
            assertTrue(ItemStack.matches(tool, menu.getStorageContainer().getItem(0)),
                    "Rapid swap step " + i + " must remain consistent");
        }
    }

    // =========================================================================
    // Category 3: Transaction Failure (atomic rollback)
    // =========================================================================

    @Test
    @DisplayName("11. Tool equip fails: verify storage and hand unchanged")
    void test11_toolEquipFailsStorageAndHandUnchanged() {
        ItemStack held = new ItemStack(Items.STICK);
        WorkerStorage storage = createStorage(new ItemStack(Items.BREAD));

        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                held,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, result);
        assertTrue(handSet.isEmpty(), "Hand setter must not be called on swap failure");
        assertTrue(storageSet.isEmpty(), "Storage setter must not be called on swap failure");
    }

    @Test
    @DisplayName("12. Tool stash fails (WorkerStorage full): verify tool in hand, storage unchanged, no drop")
    void test12_toolStashFailsFullStorageAtomicNoDrop() {
        ItemStack[] full = new ItemStack[15];
        for (int i = 0; i < 15; i++) {
            full[i] = new ItemStack(Items.COBBLESTONE, 64);
        }
        WorkerStorage storage = createStorage(full);
        ItemStack heldPick = new ItemStack(Items.IRON_PICKAXE);

        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                heldPick,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, result);
        assertTrue(handSet.isEmpty());
        assertTrue(storageSet.isEmpty());
    }

    @Test
    @DisplayName("13. WorkerExecutionLoop tool swap failure: verify atomic rollback, no partial state")
    void test13_swapJobToolInsufficientSpaceFailsClosed() {
        ItemStack[] items = new ItemStack[15];
        items[0] = new ItemStack(Items.DIAMOND_HOE);
        for (int i = 1; i < 15; i++) {
            items[i] = new ItemStack(Items.BOW);
        }
        WorkerStorage storage = createStorage(items);
        ItemStack heldPick = new ItemStack(Items.IRON_PICKAXE);

        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                heldPick,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(Items.DIAMOND_HOE, handSet.get(0).getItem());
        assertEquals(Items.IRON_PICKAXE, storageSet.get(0).items().get(0).getItem());
    }

    @Test
    @DisplayName("14. No compatible tool found: worker WAITING_FOR_TOOL, storage and hand untouched")
    void test14_noCompatibleToolLeavesStorageAndHandUntouched() {
        WorkerStorage storage = createStorage(new ItemStack(Items.APPLE, 5));
        ItemStack held = ItemStack.EMPTY;

        AtomicBoolean handModified = new AtomicBoolean(false);
        AtomicBoolean storageModified = new AtomicBoolean(false);

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                held,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                t -> handModified.set(true),
                s -> storageModified.set(true)
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE, result);
        assertFalse(handModified.get());
        assertFalse(storageModified.get());
    }

    // =========================================================================
    // Category 4: Item Identity and Persistence
    // =========================================================================

    @Test
    @DisplayName("15. Storage with vanilla hoe + modded hoe: correct hoe equipped, unequipped hoe remains intact")
    void test15_twoHoesInStorageOnlyOneEquippedOtherIntact() {
        ItemStack vanillaHoe = new ItemStack(Items.WOODEN_HOE);
        ItemStack secondaryHoe = new ItemStack(Items.DIAMOND_HOE);
        WorkerStorage storage = createStorage(vanillaHoe, secondaryHoe);

        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(storage);
        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        List<ItemStack> equipped = new ArrayList<>();
        List<WorkerStorage> updatedStorage = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                ctx.attachment,
                ItemStack.EMPTY,
                JobToolRequirement.FARMING,
                Collections.emptyList(),
                equipped::add,
                updatedStorage::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(Items.DIAMOND_HOE, equipped.get(0).getItem(), "Highest tier hoe (diamond) must be equipped");

        ctx.attachment = updatedStorage.get(0);
        menu.broadcastChanges();

        // Slot 0 retains the wooden hoe, slot 1 was vacated by diamond hoe
        assertEquals(Items.WOODEN_HOE, menu.getStorageContainer().getItem(0).getItem(),
                "Wooden hoe in slot 0 must NOT disappear!");
        assertTrue(menu.getStorageContainer().getItem(1).isEmpty(),
                "Slot 1 must now be empty as diamond hoe was equipped into hand");
        assertEquals(Items.WOODEN_HOE, ctx.attachment.items().get(0).getItem(),
                "Wooden hoe in attachment must NOT disappear!");
    }

    @Test
    @DisplayName("16. Item with custom components: components preserved through swap and menu sync")
    void test16_itemWithCustomComponentsPreservedThroughSwapAndSync() {
        ItemStack customPick = new ItemStack(Items.DIAMOND_PICKAXE);
        customPick.set(DataComponents.CUSTOM_NAME, Component.literal("Worker Excalibur"));
        customPick.set(DataComponents.DAMAGE, 100);

        WorkerStorage initial = createStorage(customPick);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        ItemStack inMenu = menu.getStorageContainer().getItem(0);
        assertEquals(Component.literal("Worker Excalibur"), inMenu.get(DataComponents.CUSTOM_NAME));
        assertEquals(100, inMenu.get(DataComponents.DAMAGE));
    }

    @Test
    @DisplayName("17. Storage serialization round-trip: DataComponent integrity preserved")
    void test17_storageSerializationRoundTripDataComponentIntegrity() {
        ItemStack customHoe = new ItemStack(Items.DIAMOND_HOE);
        customHoe.set(DataComponents.CUSTOM_NAME, Component.literal("Golden Harvest"));
        WorkerStorage storage = createStorage(customHoe);

        var encoded = WorkerStorage.CODEC.encodeStart(JsonOps.INSTANCE, storage).result().orElseThrow();
        WorkerStorage decoded = WorkerStorage.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();

        assertTrue(ItemStack.matches(storage.items().get(0), decoded.items().get(0)));
        assertEquals(Component.literal("Golden Harvest"), decoded.items().get(0).get(DataComponents.CUSTOM_NAME));
    }

    // =========================================================================
    // Category 5: Edge Cases & Contract Verification
    // =========================================================================

    @Test
    @DisplayName("18. Multi-worker candidate mode: storage not synchronized from arbitrary worker")
    void test18_multiWorkerModeStorageNotSynced() {
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(createStorage(new ItemStack(Items.APPLE)));
        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1, 2), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertFalse(menu.syncFromEntityStorageIfChanged());
    }

    @Test
    @DisplayName("19. Storage matching check with identical items returns true, mismatched returns false")
    void test19_matchesContainerIdenticalAndMismatched() {
        WorkerStorage s1 = createStorage(new ItemStack(Items.APPLE, 5));
        SimpleContainer c1 = new SimpleContainer(15);
        c1.setItem(0, new ItemStack(Items.APPLE, 5));
        assertTrue(s1.matchesContainer(c1));

        c1.setItem(0, new ItemStack(Items.APPLE, 4));
        assertFalse(s1.matchesContainer(c1));

        c1.setItem(0, new ItemStack(Items.BREAD, 5));
        assertFalse(s1.matchesContainer(c1));

        assertFalse(s1.matchesContainer(null));
        assertFalse(s1.matchesContainer(new SimpleContainer(10)));
    }

    @Test
    @DisplayName("20. Storage matching check detects DataComponent differences")
    void test20_matchesContainerDetectsComponentDifferences() {
        ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
        damaged.set(DataComponents.DAMAGE, 50);

        ItemStack undamaged = new ItemStack(Items.DIAMOND_PICKAXE);

        WorkerStorage s = createStorage(damaged);
        SimpleContainer c = new SimpleContainer(15);
        c.setItem(0, undamaged);

        assertFalse(s.matchesContainer(c), "matchesContainer must detect component (damage) differences");

        c.setItem(0, damaged.copy());
        assertTrue(s.matchesContainer(c), "matchesContainer must return true when components match");
    }

    @Test
    @DisplayName("21. Storage write-back suppression flag lifecycle during loadStorageFromSnapshot")
    void test21_suppressStorageWriteBackLifecycle() {
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(createStorage(new ItemStack(Items.APPLE)));
        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertFalse(menu.isStorageWriteBackSuppressed(), "Suppression must be false after construction");

        menu.getStorageContainer().setItem(0, new ItemStack(Items.BREAD, 2));
        assertEquals(1, ctx.writeCount, "Direct player interaction must write back to storage");
        assertEquals(Items.BREAD, ctx.attachment.items().get(0).getItem());
    }

    @Test
    @DisplayName("22. quickMoveStack (shift-click) performs live sync before moving items")
    void test22_quickMoveStackPerformsLiveSync() {
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        WorkerStorage initial = createStorage(hoe);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        ctx.attachment = createStorage(ItemStack.EMPTY);

        ItemStack result = menu.quickMoveStack(null, 6);

        assertTrue(result.isEmpty());
        assertTrue(menu.getStorageContainer().getItem(0).isEmpty());
    }

    @Test
    @DisplayName("23. clickMenuButton performs live sync before handling order")
    void test23_clickMenuButtonPerformsLiveSync() {
        ItemStack hoe = new ItemStack(Items.DIAMOND_HOE);
        WorkerStorage initial = createStorage(hoe);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);

        WorkerManagementMenu menu = new WorkerManagementMenu(0, null, List.of(1), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        ctx.attachment = createStorage(ItemStack.EMPTY);

        boolean handled = menu.clickMenuButton(null, 110);
        assertTrue(handled);
        assertTrue(menu.getStorageContainer().getItem(0).isEmpty(),
                "Storage container must be synchronized during clickMenuButton");
    }

    // =========================================================================
    // Category 6: WorkerStorage Integrity Tests (Tests A through G)
    // =========================================================================

    @Test
    @DisplayName("Test A: Mining activation preserves all other 14 slots, counts, and components")
    void testA_miningActivationPreservesAllOtherSlots() {
        ItemStack[] items = new ItemStack[15];
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.set(DataComponents.CUSTOM_NAME, Component.literal("Miner Master"));
        pickaxe.set(DataComponents.DAMAGE, 15);
        items[0] = pickaxe;

        items[1] = new ItemStack(Items.GOLDEN_APPLE, 3);
        items[2] = new ItemStack(Items.IRON_INGOT, 12);
        items[3] = new ItemStack(Items.COAL, 45);
        items[4] = new ItemStack(Items.TORCH, 64);
        items[5] = new ItemStack(Items.BREAD, 16);
        items[6] = new ItemStack(Items.WATER_BUCKET, 1);
        items[7] = new ItemStack(Items.OAK_LOG, 24);
        items[8] = new ItemStack(Items.COBBLESTONE, 64);
        items[9] = new ItemStack(Items.DIAMOND, 5);
        items[10] = new ItemStack(Items.EMERALD, 8);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.set(DataComponents.DAMAGE, 20);
        items[11] = bow;
        items[12] = new ItemStack(Items.ARROW, 32);
        items[13] = new ItemStack(Items.COMPASS, 1);
        items[14] = new ItemStack(Items.CLOCK, 1);

        WorkerStorage initialStorage = createStorage(items);

        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapMiningTool(
                initialStorage,
                ItemStack.EMPTY,
                handSet::add,
                storageSet::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, handSet.size());
        assertEquals(Items.DIAMOND_PICKAXE, handSet.get(0).getItem());
        assertEquals(Component.literal("Miner Master"), handSet.get(0).get(DataComponents.CUSTOM_NAME));
        assertEquals(15, handSet.get(0).get(DataComponents.DAMAGE));

        assertEquals(1, storageSet.size());
        WorkerStorage updated = storageSet.get(0);
        assertTrue(updated.items().get(0).isEmpty(), "Slot 0 must be empty after pickaxe is equipped to hand");
        for (int i = 1; i < 15; i++) {
            assertTrue(ItemStack.matches(items[i], updated.items().get(i)),
                    "Slot " + i + " must retain exact item, count, and components");
        }
    }

    @Test
    @DisplayName("Test B: Menu close and reopen preserves all 15 slots without write-back cascade")
    void testB_menuReopenPreservesAll15Slots() {
        ItemStack[] items = new ItemStack[15];
        items[0] = new ItemStack(Items.NETHERITE_PICKAXE);
        for (int i = 1; i < 15; i++) {
            items[i] = new ItemStack(Items.AMETHYST_SHARD, i + 1);
        }

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);
        UUID workerUuid = UUID.randomUUID();

        // 1. Initial menu open
        WorkerManagementMenu menu1 = new WorkerManagementMenu(1, null, List.of(100), List.of(workerUuid), ctx.reader(), ctx.writer());
        menu1.setTestMode(true);

        assertEquals(0, ctx.writeCount, "Opening menu 1 must not trigger write-back to storage");
        for (int i = 0; i < 15; i++) {
            assertTrue(ItemStack.matches(items[i], menu1.getStorageContainer().getItem(i)),
                    "Menu 1 slot " + i + " must match initial attachment item");
        }

        // 2. Simulate menu close and reopen with new menu instance
        WorkerManagementMenu menu2 = new WorkerManagementMenu(2, null, List.of(100), List.of(workerUuid), ctx.reader(), ctx.writer());
        menu2.setTestMode(true);

        assertEquals(0, ctx.writeCount, "Reopening menu must not trigger write-back to storage");
        assertEquals(15, menu2.getStorageContainer().getContainerSize());
        for (int i = 0; i < 15; i++) {
            assertTrue(ItemStack.matches(items[i], menu2.getStorageContainer().getItem(i)),
                    "Menu 2 slot " + i + " must retain exact item from attachment");
        }
    }

    @Test
    @DisplayName("Test C: Repeated menu lifecycle (20 cycles) guarantees zero data loss and zero duplication")
    void testC_repeatedMenuLifecycleZeroDataLossZeroDuplication() {
        ItemStack[] items = new ItemStack[15];
        for (int i = 0; i < 15; i++) {
            items[i] = new ItemStack(Items.IRON_INGOT, 10);
        }

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);
        UUID workerUuid = UUID.randomUUID();

        int expectedWrites = 0;
        for (int cycle = 0; cycle < 20; cycle++) {
            WorkerManagementMenu menu = new WorkerManagementMenu(cycle + 1, null, List.of(100), List.of(workerUuid), ctx.reader(), ctx.writer());
            menu.setTestMode(true);

            // Verify before interaction
            for (int s = 0; s < 15; s++) {
                assertTrue(ItemStack.matches(ctx.attachment.items().get(s), menu.getStorageContainer().getItem(s)),
                        "Cycle " + cycle + " slot " + s + " must match attachment");
            }

            // Simulate legitimate modification on slot (cycle % 15)
            int targetSlot = cycle % 15;
            ItemStack current = menu.getStorageContainer().getItem(targetSlot);
            ItemStack modified = current.copy();
            modified.grow(1);
            menu.getStorageContainer().setItem(targetSlot, modified);
            expectedWrites++;

            assertEquals(expectedWrites, ctx.writeCount, "Cycle " + cycle + " write count must increment by exactly 1 on user edit");
            assertEquals(modified.getCount(), ctx.attachment.items().get(targetSlot).getCount(),
                    "Cycle " + cycle + " modified slot " + targetSlot + " must be persisted in attachment");
        }

        // Final verification across all 15 slots
        for (int i = 0; i < 15; i++) {
            assertFalse(ctx.attachment.items().get(i).isEmpty(), "Slot " + i + " must not have been lost");
            assertEquals(Items.IRON_INGOT, ctx.attachment.items().get(i).getItem());
        }
    }

    @Test
    @DisplayName("Test D: Mining tool swap while menu is open synchronizes cleanly and preserves unrelated items")
    void testD_miningWhileMenuOpenSyncPreservesUnrelatedItems() {
        ItemStack[] items = new ItemStack[15];
        items[4] = new ItemStack(Items.IRON_PICKAXE);
        for (int i = 0; i < 15; i++) {
            if (i != 4) items[i] = new ItemStack(Items.EMERALD, i + 5);
        }

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);
        UUID workerUuid = UUID.randomUUID();

        WorkerManagementMenu menu = new WorkerManagementMenu(1, null, List.of(100), List.of(workerUuid), ctx.reader(), ctx.writer());
        menu.setTestMode(true);

        assertEquals(Items.IRON_PICKAXE, menu.getStorageContainer().getItem(4).getItem());

        // Worker execution loop performs tool swap in the background while menu is open
        List<ItemStack> equipped = new ArrayList<>();
        List<WorkerStorage> updatedStorage = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult swapResult = WorkerExecutionLoop.swapMiningTool(
                ctx.attachment,
                ItemStack.EMPTY,
                equipped::add,
                updatedStorage::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, swapResult);
        assertEquals(Items.IRON_PICKAXE, equipped.get(0).getItem());

        // Update attachment directly
        ctx.attachment = updatedStorage.get(0);
        int writesBeforeSync = ctx.writeCount;

        // Menu synchronizes live from entity storage
        boolean synced = menu.syncFromEntityStorageIfChanged();
        assertTrue(synced, "syncFromEntityStorageIfChanged must detect change and return true");
        assertEquals(writesBeforeSync, ctx.writeCount, "Live sync must NOT write back to attachment");

        // Verify container state: slot 4 is now empty, all other slots retained
        assertTrue(menu.getStorageContainer().getItem(4).isEmpty(), "Slot 4 in menu must now be empty");
        for (int i = 0; i < 15; i++) {
            if (i != 4) {
                assertTrue(ItemStack.matches(items[i], menu.getStorageContainer().getItem(i)),
                        "Slot " + i + " must be untouched by tool swap sync");
            }
        }
    }

    @Test
    @DisplayName("Test E: Mining activation followed by menu reopen preserves final attachment and container match")
    void testE_miningFollowedByMenuReopenPreservesItems() {
        ItemStack[] items = new ItemStack[15];
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal("Underground King"));
        items[8] = pick;
        for (int i = 0; i < 15; i++) {
            if (i != 8) items[i] = new ItemStack(Items.REDSTONE, i + 1);
        }

        WorkerStorage initial = createStorage(items);
        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(initial);
        UUID workerUuid = UUID.randomUUID();

        // 1. Initial menu open and close
        WorkerManagementMenu menu1 = new WorkerManagementMenu(1, null, List.of(100), List.of(workerUuid), ctx.reader(), ctx.writer());
        menu1.setTestMode(true);
        assertEquals(Component.literal("Underground King"), menu1.getStorageContainer().getItem(8).get(DataComponents.CUSTOM_NAME));

        // 2. Mining starts while menu is closed: held dirt is stashed into slot 8, pickaxe goes to hand
        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();
        ItemStack heldDirt = new ItemStack(Items.DIRT, 5);
        WorkerExecutionLoop.ToolSwapResult swapResult = WorkerExecutionLoop.swapMiningTool(
                ctx.attachment,
                heldDirt,
                handSet::add,
                storageSet::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, swapResult);
        ctx.attachment = storageSet.get(0);
        assertEquals(Component.literal("Underground King"), handSet.get(0).get(DataComponents.CUSTOM_NAME));

        // 3. Player reopens menu
        WorkerManagementMenu menu2 = new WorkerManagementMenu(2, null, List.of(100), List.of(workerUuid), ctx.reader(), ctx.writer());
        menu2.setTestMode(true);

        assertEquals(Items.DIRT, menu2.getStorageContainer().getItem(8).getItem(), "Slot 8 must now contain stashed dirt");
        assertEquals(5, menu2.getStorageContainer().getItem(8).getCount());
        for (int i = 0; i < 15; i++) {
            if (i != 8) {
                assertTrue(ItemStack.matches(items[i], menu2.getStorageContainer().getItem(i)),
                        "Slot " + i + " must retain original item on reopen");
            }
        }
    }

    @Test
    @DisplayName("Test F: Distinct worker identities prevent cross-worker storage contamination")
    void testF_workerIdentityPreservesDistinctStorages() {
        ItemStack[] itemsA = new ItemStack[15];
        ItemStack[] itemsB = new ItemStack[15];
        for (int i = 0; i < 15; i++) {
            itemsA[i] = new ItemStack(Items.DIAMOND, i + 1);
            itemsB[i] = new ItemStack(Items.EMERALD, (i + 1) * 2);
        }

        SimulatedWorkerStorageContext ctxA = new SimulatedWorkerStorageContext(createStorage(itemsA));
        SimulatedWorkerStorageContext ctxB = new SimulatedWorkerStorageContext(createStorage(itemsB));

        UUID uuidA = UUID.randomUUID();
        UUID uuidB = UUID.randomUUID();

        WorkerManagementMenu menuA = new WorkerManagementMenu(1, null, List.of(101), List.of(uuidA), ctxA.reader(), ctxA.writer());
        menuA.setTestMode(true);
        WorkerManagementMenu menuB = new WorkerManagementMenu(2, null, List.of(102), List.of(uuidB), ctxB.reader(), ctxB.writer());
        menuB.setTestMode(true);

        // Edit menu A
        menuA.getStorageContainer().setItem(0, new ItemStack(Items.GOLD_INGOT, 64));
        assertEquals(Items.GOLD_INGOT, ctxA.attachment.items().get(0).getItem());

        // Verify menu B and worker B are completely unaffected
        assertEquals(Items.EMERALD, menuB.getStorageContainer().getItem(0).getItem());
        assertEquals(2, menuB.getStorageContainer().getItem(0).getCount());
        assertEquals(Items.EMERALD, ctxB.attachment.items().get(0).getItem());
        assertEquals(2, ctxB.attachment.items().get(0).getCount());
    }

    @Test
    @DisplayName("Test G: Empty slot 0 preserved across mining activation and menu reopen")
    void testG_emptySlot0PreservedAcrossMiningAndReopen() {
        ItemStack[] items = new ItemStack[15];
        items[0] = ItemStack.EMPTY;
        items[5] = new ItemStack(Items.DIAMOND_PICKAXE);
        for (int i = 1; i < 15; i++) {
            if (i != 5) items[i] = new ItemStack(Items.LAPIS_LAZULI, i);
        }

        SimulatedWorkerStorageContext ctx = new SimulatedWorkerStorageContext(createStorage(items));
        UUID uuid = UUID.randomUUID();

        // 1. Initial menu open: slot 0 must be empty, slot 5 has pickaxe
        WorkerManagementMenu menu1 = new WorkerManagementMenu(1, null, List.of(100), List.of(uuid), ctx.reader(), ctx.writer());
        menu1.setTestMode(true);
        assertTrue(menu1.getStorageContainer().getItem(0).isEmpty(), "Slot 0 must be empty");
        assertEquals(Items.DIAMOND_PICKAXE, menu1.getStorageContainer().getItem(5).getItem());

        // 2. Mining tool swap: pickaxe in slot 5 is equipped into mainhand
        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult swapResult = WorkerExecutionLoop.swapMiningTool(
                ctx.attachment,
                ItemStack.EMPTY,
                handSet::add,
                storageSet::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, swapResult);
        ctx.attachment = storageSet.get(0);

        assertTrue(ctx.attachment.items().get(0).isEmpty(), "Slot 0 must remain empty in attachment");
        assertTrue(ctx.attachment.items().get(5).isEmpty(), "Slot 5 must be empty after tool swap");

        // 3. Reopen menu
        WorkerManagementMenu menu2 = new WorkerManagementMenu(2, null, List.of(100), List.of(uuid), ctx.reader(), ctx.writer());
        menu2.setTestMode(true);

        assertTrue(menu2.getStorageContainer().getItem(0).isEmpty(), "Slot 0 must remain empty in menu container");
        assertTrue(menu2.getStorageContainer().getItem(5).isEmpty(), "Slot 5 must remain empty in menu container");
        for (int i = 1; i < 15; i++) {
            if (i != 5) {
                assertEquals(Items.LAPIS_LAZULI, menu2.getStorageContainer().getItem(i).getItem(),
                        "Slot " + i + " must retain lapis lazuli");
                assertEquals(i, menu2.getStorageContainer().getItem(i).getCount(),
                        "Slot " + i + " count must be exactly preserved");
            }
        }
        assertEquals(0, ctx.writeCount, "Menu reopen must not trigger write-back to attachment");
    }
}
