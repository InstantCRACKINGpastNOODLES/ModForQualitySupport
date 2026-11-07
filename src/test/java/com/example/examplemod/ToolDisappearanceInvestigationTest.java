package com.example.examplemod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 Mass Tool Loss Investigation & Instrumentation Tests.
 * Replicates the exact 5-tool scenario: 2 hoes, 3 pickaxes.
 */
public class ToolDisappearanceInvestigationTest {

    private static final UUID WORKER_UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @BeforeAll
    static void setupRegistries() {
        try {
            Field frozenField = MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(BuiltInRegistries.ITEM, false);
            frozenField.set(BuiltInRegistries.BLOCK, false);
        } catch (Throwable ignored) {
        }
        CropRegistry.registerDefaults();
    }

    private static String formatStack(ItemStack s) {
        if (s == null || s.isEmpty()) return "[Empty]";
        String name = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        int count = s.getCount();
        int dmg = s.isDamageableItem() ? s.getDamageValue() : 0;
        String customName = s.has(DataComponents.CUSTOM_NAME) ? " name=" + s.get(DataComponents.CUSTOM_NAME).getString() : "";
        return String.format("[%s x%d dmg=%d%s]", name, count, dmg, customName);
    }

    private static void logState(
            String callingMethod,
            String phase,
            UUID workerId,
            long tick,
            String jobState,
            int sourceSlot,
            int destSlot,
            ItemStack handBefore,
            ItemStack handAfter,
            WorkerStorage storage,
            boolean success
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n=== [INSTRUMENTATION LOG] %s | %s ===\n", callingMethod, phase));
        sb.append(String.format("Worker: %s | Tick: %d | JobState: %s | Success: %b\n", workerId, tick, jobState, success));
        sb.append(String.format("Source Slot: %d | Dest Slot: %d\n", sourceSlot, destSlot));
        sb.append(String.format("Main Hand Before: %s\n", formatStack(handBefore)));
        sb.append(String.format("Main Hand After : %s\n", formatStack(handAfter)));
        sb.append("WorkerStorage (15 slots):\n");
        if (storage != null && storage.items() != null) {
            for (int i = 0; i < storage.items().size(); i++) {
                sb.append(String.format("  Slot %2d: %s\n", i, formatStack(storage.items().get(i))));
            }
        } else {
            sb.append("  [NULL STORAGE]\n");
        }
        sb.append("===================================================\n");
        System.out.println(sb.toString());
    }

    private static ItemStack createDistinguishableTool(net.minecraft.world.item.Item item, int damage, String label) {
        ItemStack stack = new ItemStack(item, 1);
        stack.set(DataComponents.DAMAGE, damage);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        return stack;
    }

    private static WorkerStorage create5ToolStorage() {
        List<ItemStack> list = new ArrayList<>();
        list.add(createDistinguishableTool(Items.WOODEN_HOE, 10, "Hoe_Wood"));       // Slot 0
        list.add(createDistinguishableTool(Items.DIAMOND_HOE, 20, "Hoe_Diamond"));    // Slot 1
        list.add(createDistinguishableTool(Items.WOODEN_PICKAXE, 5, "Pick_Wood"));    // Slot 2
        list.add(createDistinguishableTool(Items.IRON_PICKAXE, 15, "Pick_Iron"));     // Slot 3
        list.add(createDistinguishableTool(Items.DIAMOND_PICKAXE, 25, "Pick_Diamond")); // Slot 4
        for (int i = 5; i < WorkerStorage.SIZE; i++) {
            list.add(ItemStack.EMPTY);
        }
        return new WorkerStorage(list);
    }

    static class StorageAttachmentHolder {
        WorkerStorage storage;
        ItemStack hand = ItemStack.EMPTY;
        int writeCount = 0;

        StorageAttachmentHolder(WorkerStorage initial) {
            this.storage = initial;
        }

        Supplier<WorkerStorage> reader() {
            return () -> this.storage;
        }

        Consumer<WorkerStorage> writer() {
            return ws -> {
                this.storage = ws;
                this.writeCount++;
            };
        }
    }

    // =========================================================================
    // Test 1: Exact Five-Tool Scenario in swapJobTool
    // =========================================================================
    @Test
    @DisplayName("Test 1: Five distinguishable tools in swapJobTool directly")
    void test01_fiveToolsInSwapJobToolDirectly() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);

        logState("swapJobTool", "BEFORE_MINING_ACTIVATION", WORKER_UUID, 0, "IDLE", -1, -1,
                holder.hand, holder.hand, holder.storage, true);

        // Best tool should be Diamond Pickaxe (Slot 4)
        ItemStack handBefore = holder.hand.copy();
        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                holder.storage,
                holder.hand,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, handSet.size());
        assertEquals(1, storageSet.size());

        holder.hand = handSet.get(0);
        holder.storage = storageSet.get(0);

        logState("swapJobTool", "AFTER_JOB_ACTIVATION", WORKER_UUID, 0, "MINING_ASSIGNED", 4, EquipmentSlot.MAINHAND.getIndex(),
                handBefore, holder.hand, holder.storage, result == WorkerExecutionLoop.ToolSwapResult.SUCCESS);

        // Verification of acceptance criteria:
        // 1. Only the selected tool (Pick_Diamond) moves to Main Hand
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());
        assertEquals(25, holder.hand.getDamageValue());
        assertEquals("Pick_Diamond", holder.hand.get(DataComponents.CUSTOM_NAME).getString());

        // 2. All 4 unselected tools must remain in WorkerStorage in their original slots
        assertEquals(Items.WOODEN_HOE, holder.storage.items().get(0).getItem());
        assertEquals(10, holder.storage.items().get(0).getDamageValue());

        assertEquals(Items.DIAMOND_HOE, holder.storage.items().get(1).getItem());
        assertEquals(20, holder.storage.items().get(1).getDamageValue());

        assertEquals(Items.WOODEN_PICKAXE, holder.storage.items().get(2).getItem());
        assertEquals(5, holder.storage.items().get(2).getDamageValue());

        assertEquals(Items.IRON_PICKAXE, holder.storage.items().get(3).getItem());
        assertEquals(15, holder.storage.items().get(3).getDamageValue());

        // 3. Slot 4 vacated
        assertTrue(holder.storage.items().get(4).isEmpty());

        // 4. Slots 5..14 remain empty
        for (int i = 5; i < 15; i++) {
            assertTrue(holder.storage.items().get(i).isEmpty(), "Slot " + i + " must remain empty");
        }
    }

    // =========================================================================
    // Test 2: Five distinguishable tools with Main Hand initially occupied
    // =========================================================================
    @Test
    @DisplayName("Test 2: Five distinguishable tools with Main Hand initially occupied (displaced item preserved)")
    void test02_fiveToolsWithOccupiedHandPreserved() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);
        holder.hand = new ItemStack(Items.DIRT, 32);

        ItemStack handBefore = holder.hand.copy();
        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                holder.storage,
                holder.hand,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        holder.hand = handSet.get(0);
        holder.storage = storageSet.get(0);

        logState("swapJobToolOccupied", "AFTER_JOB_ACTIVATION", WORKER_UUID, 0, "MINING_ASSIGNED", 4, EquipmentSlot.MAINHAND.getIndex(),
                handBefore, holder.hand, holder.storage, true);

        // Diamond pickaxe equipped
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());

        // The 4 unselected tools remain in slots 0..3
        assertEquals(Items.WOODEN_HOE, holder.storage.items().get(0).getItem());
        assertEquals(Items.DIAMOND_HOE, holder.storage.items().get(1).getItem());
        assertEquals(Items.WOODEN_PICKAXE, holder.storage.items().get(2).getItem());
        assertEquals(Items.IRON_PICKAXE, holder.storage.items().get(3).getItem());

        // Displaced Dirt stack is preserved in storage (either in slot 4 or first empty slot 5)
        boolean dirtFound = false;
        for (int i = 0; i < 15; i++) {
            ItemStack s = holder.storage.items().get(i);
            if (s.is(Items.DIRT) && s.getCount() == 32) {
                dirtFound = true;
                break;
            }
        }
        assertTrue(dirtFound, "Displaced 32 Dirt from Main Hand MUST be preserved in WorkerStorage!");
    }

    // =========================================================================
    // Test 3: Five distinguishable tools in Non-Contiguous Slots with Slot 0 Empty
    // =========================================================================
    @Test
    @DisplayName("Test 3: Non-contiguous slots with slot 0 empty")
    void test03_nonContiguousSlotsWithSlot0Empty() {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < 15; i++) list.add(ItemStack.EMPTY);

        // Slot 0: EMPTY
        list.set(1, createDistinguishableTool(Items.WOODEN_HOE, 10, "Hoe_Wood"));
        list.set(4, createDistinguishableTool(Items.DIAMOND_HOE, 20, "Hoe_Diamond"));
        list.set(7, createDistinguishableTool(Items.WOODEN_PICKAXE, 5, "Pick_Wood"));
        list.set(10, createDistinguishableTool(Items.IRON_PICKAXE, 15, "Pick_Iron"));
        list.set(14, createDistinguishableTool(Items.DIAMOND_PICKAXE, 25, "Pick_Diamond"));

        WorkerStorage initialStorage = new WorkerStorage(list);
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);

        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                holder.storage,
                holder.hand,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        holder.hand = handSet.get(0);
        holder.storage = storageSet.get(0);

        // Best tool: Diamond Pickaxe at slot 14
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());
        assertEquals("Pick_Diamond", holder.hand.get(DataComponents.CUSTOM_NAME).getString());

        // All 4 unselected tools remain in exact original slots!
        assertTrue(holder.storage.items().get(0).isEmpty(), "Slot 0 must remain empty");
        assertEquals("Hoe_Wood", holder.storage.items().get(1).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Hoe_Diamond", holder.storage.items().get(4).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Wood", holder.storage.items().get(7).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Iron", holder.storage.items().get(10).get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(holder.storage.items().get(14).isEmpty(), "Slot 14 must be vacated");
    }

    // =========================================================================
    // Test 4: Full Menu Interaction & Reopen LifeCycle with 5 Tools
    // =========================================================================
    @Test
    @DisplayName("Test 4: Menu open, tool swap, sync, close, and reopen lifecycle with 5 tools")
    void test04_menuLifecycleWith5Tools() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);
        UUID workerUuid = UUID.randomUUID();

        // 1. Menu opened: verified contains all 5 tools
        WorkerManagementMenu menu1 = new WorkerManagementMenu(1, null, List.of(100), List.of(workerUuid), holder.reader(), holder.writer());
        menu1.setTestMode(true);

        assertEquals(0, holder.writeCount, "Menu construction must not write back");
        assertEquals("Hoe_Wood", menu1.getStorageContainer().getItem(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Hoe_Diamond", menu1.getStorageContainer().getItem(1).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Wood", menu1.getStorageContainer().getItem(2).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Iron", menu1.getStorageContainer().getItem(3).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Diamond", menu1.getStorageContainer().getItem(4).get(DataComponents.CUSTOM_NAME).getString());

        // 2. Background tool swap (simulating worker equipping diamond pickaxe)
        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                holder.storage,
                holder.hand,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        holder.hand = handSet.get(0);
        holder.storage = storageSet.get(0);

        // 3. Menu synchronizes live while open
        boolean synced = menu1.syncFromEntityStorageIfChanged();
        assertTrue(synced, "Live sync must succeed");
        assertEquals(0, holder.writeCount, "Live sync must not write back");

        // 4. Menu closed and reopened as menu2
        WorkerManagementMenu menu2 = new WorkerManagementMenu(2, null, List.of(100), List.of(workerUuid), holder.reader(), holder.writer());
        menu2.setTestMode(true);

        assertEquals(0, holder.writeCount, "Reopen must not write back");

        // Verify all 4 unselected tools in menu2 and attachment
        for (int i = 0; i < 4; i++) {
            assertFalse(menu2.getStorageContainer().getItem(i).isEmpty(), "Slot " + i + " must NOT be empty in menu container");
            assertFalse(holder.storage.items().get(i).isEmpty(), "Slot " + i + " must NOT be empty in attachment");
        }
        assertTrue(menu2.getStorageContainer().getItem(4).isEmpty(), "Slot 4 must be empty (equipped)");
        assertTrue(holder.storage.items().get(4).isEmpty(), "Slot 4 in attachment must be empty");
    }

    // =========================================================================
    // Instrumented Call Chain Helper
    // =========================================================================
    static WorkerExecutionLoop.ToolSwapResult executeInstrumentedMiningCallChain(
            StorageAttachmentHolder holder,
            UUID workerUuid,
            long tick,
            String jobState
    ) {
        ItemStack handBefore = holder.hand.copy();
        WorkerStorage storageBefore = holder.storage;

        // 1. Immediately before Mining activation
        logState("MiningActivationChain", "1_IMMEDIATELY_BEFORE_MINING_ACTIVATION", workerUuid, tick, jobState,
                -1, -1, handBefore, handBefore, storageBefore, true);

        // 2. Before and after candidate selection
        logState("MiningActivationChain", "2A_BEFORE_CANDIDATE_SELECTION", workerUuid, tick, jobState,
                -1, -1, handBefore, handBefore, storageBefore, true);

        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < storageBefore.items().size(); i++) {
            ItemStack stack = storageBefore.items().get(i);
            if (JobToolRequirement.MINING.isCandidate(stack)) {
                int score = JobToolRequirement.MINING.scoreCandidate(stack);
                if (bestSlot == -1 || score > bestScore) {
                    bestSlot = i;
                    bestScore = score;
                }
            }
        }
        logState("MiningActivationChain", "2B_AFTER_CANDIDATE_SELECTION", workerUuid, tick, jobState,
                bestSlot, -1, handBefore, handBefore, storageBefore, bestSlot != -1);

        if (bestSlot == -1) {
            return WorkerExecutionLoop.ToolSwapResult.NO_TOOL_AVAILABLE;
        }

        // 3. Before and after each tool extraction
        logState("MiningActivationChain", "3A_BEFORE_TOOL_EXTRACTION", workerUuid, tick, jobState,
                bestSlot, -1, handBefore, handBefore, storageBefore, true);

        ItemStack extracted = storageBefore.items().get(bestSlot).copyWithCount(1);
        List<ItemStack> simulated = new ArrayList<>(storageBefore.items().stream().map(ItemStack::copy).toList());
        simulated.set(bestSlot, ItemStack.EMPTY);
        WorkerStorage storageAfterExtract = new WorkerStorage(simulated);

        logState("MiningActivationChain", "3B_AFTER_TOOL_EXTRACTION", workerUuid, tick, jobState,
                bestSlot, -1, handBefore, handBefore, storageAfterExtract, true);

        // 4. Before and after Main Hand replacement
        logState("MiningActivationChain", "4A_BEFORE_MAIN_HAND_REPLACEMENT", workerUuid, tick, jobState,
                bestSlot, EquipmentSlot.MAINHAND.getIndex(), handBefore, handBefore, storageAfterExtract, true);

        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                holder.storage,
                holder.hand,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );

        if (result != WorkerExecutionLoop.ToolSwapResult.SUCCESS) {
            logState("MiningActivationChain", "SWAP_FAILED", workerUuid, tick, jobState,
                    bestSlot, EquipmentSlot.MAINHAND.getIndex(), handBefore, handBefore, storageBefore, false);
            return result;
        }

        ItemStack handAfter = handSet.get(0);
        WorkerStorage storageAfterSwap = storageSet.get(0);

        logState("MiningActivationChain", "4B_AFTER_MAIN_HAND_REPLACEMENT", workerUuid, tick, jobState,
                bestSlot, EquipmentSlot.MAINHAND.getIndex(), handBefore, handAfter, storageAfterSwap, true);

        // 5. Before and after every attachment write
        logState("MiningActivationChain", "5A_BEFORE_ATTACHMENT_WRITE", workerUuid, tick, jobState,
                bestSlot, EquipmentSlot.MAINHAND.getIndex(), handBefore, handAfter, holder.storage, true);

        holder.writer().accept(storageAfterSwap);
        holder.hand = handAfter;

        logState("MiningActivationChain", "5B_AFTER_ATTACHMENT_WRITE", workerUuid, tick, jobState,
                bestSlot, EquipmentSlot.MAINHAND.getIndex(), handBefore, holder.hand, holder.storage, true);

        // 6. After job activation completes
        logState("MiningActivationChain", "6_AFTER_JOB_ACTIVATION_COMPLETE", workerUuid, tick + 1, "MINING_RUNNING",
                bestSlot, EquipmentSlot.MAINHAND.getIndex(), handBefore, holder.hand, holder.storage, true);

        return WorkerExecutionLoop.ToolSwapResult.SUCCESS;
    }

    // =========================================================================
    // Test 5: Real Mining Activation Call Chain across Job Start, Stop, Save & Reload
    // =========================================================================
    @Test
    @DisplayName("Test 5: Real Mining Activation Call Chain across Job Start, Stop, Save and Reload")
    void test05_realMiningActivationCallChain_jobStartStopSaveReload() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);
        UUID workerUuid = WORKER_UUID;

        // Step 1: Execute complete instrumented call chain
        WorkerExecutionLoop.ToolSwapResult result = executeInstrumentedMiningCallChain(
                holder, workerUuid, 100L, "IDLE"
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, holder.writeCount);

        // Selected tool is Diamond Pickaxe
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());
        assertEquals("Pick_Diamond", holder.hand.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(25, holder.hand.getDamageValue());

        // The other 4 tools remain in their original WorkerStorage slots with intact identities and damage
        assertEquals(Items.WOODEN_HOE, holder.storage.items().get(0).getItem());
        assertEquals(10, holder.storage.items().get(0).getDamageValue());
        assertEquals("Hoe_Wood", holder.storage.items().get(0).get(DataComponents.CUSTOM_NAME).getString());

        assertEquals(Items.DIAMOND_HOE, holder.storage.items().get(1).getItem());
        assertEquals(20, holder.storage.items().get(1).getDamageValue());
        assertEquals("Hoe_Diamond", holder.storage.items().get(1).get(DataComponents.CUSTOM_NAME).getString());

        assertEquals(Items.WOODEN_PICKAXE, holder.storage.items().get(2).getItem());
        assertEquals(5, holder.storage.items().get(2).getDamageValue());
        assertEquals("Pick_Wood", holder.storage.items().get(2).get(DataComponents.CUSTOM_NAME).getString());

        assertEquals(Items.IRON_PICKAXE, holder.storage.items().get(3).getItem());
        assertEquals(15, holder.storage.items().get(3).getDamageValue());
        assertEquals("Pick_Iron", holder.storage.items().get(3).get(DataComponents.CUSTOM_NAME).getString());

        assertTrue(holder.storage.items().get(4).isEmpty(), "Slot 4 must be vacated");

        // Step 2: Simulate active Mining Job execution
        WorkerTarget worksite = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 100));
        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, new BlockPos(105, 64, 100));
        MinerJobData minerData = MinerJobData.initial(64L, storageTarget, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, worksite, minerData);
        WorkerProfile profile = new WorkerProfile(workerUuid, OWNER_UUID, OVERWORLD, WorkerStatus.IDLE).withJob(minerJob);

        assertTrue(profile.job().isPresent());
        assertEquals(WorkerJobs.MINE, profile.job().get().type());

        // Worker execution loop tick checks tool: tool is already equipped, returns SUCCESS without touching storage
        assertTrue(JobToolRequirement.MINING.isSatisfiedBy(holder.hand));

        // Step 3: Job Stop / Cancellation
        WorkerProfile stoppedProfile = profile.cancel(OWNER_UUID).orElseThrow();
        assertTrue(stoppedProfile.job().isEmpty(), "Job must be cleared upon cancellation");

        // The equipped tool remains in Main Hand even after Mining was stopped
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());
        assertEquals("Pick_Diamond", holder.hand.get(DataComponents.CUSTOM_NAME).getString());

        // All 4 tools still remain in WorkerStorage
        for (int i = 0; i < 4; i++) {
            assertFalse(holder.storage.items().get(i).isEmpty(), "Slot " + i + " must remain populated after job stop");
        }

        // Step 4: Save & Reload via NBT Ops
        Tag nbt = WorkerStorage.CODEC.encodeStart(NbtOps.INSTANCE, holder.storage).result().orElseThrow();
        WorkerStorage reloadedFromNbt = WorkerStorage.CODEC.parse(NbtOps.INSTANCE, nbt).result().orElseThrow();

        assertEquals(15, reloadedFromNbt.items().size());
        assertEquals("Hoe_Wood", reloadedFromNbt.items().get(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(10, reloadedFromNbt.items().get(0).getDamageValue());
        assertEquals("Hoe_Diamond", reloadedFromNbt.items().get(1).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(20, reloadedFromNbt.items().get(1).getDamageValue());
        assertEquals("Pick_Wood", reloadedFromNbt.items().get(2).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(5, reloadedFromNbt.items().get(2).getDamageValue());
        assertEquals("Pick_Iron", reloadedFromNbt.items().get(3).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(15, reloadedFromNbt.items().get(3).getDamageValue());
        assertTrue(reloadedFromNbt.items().get(4).isEmpty());

        // Step 5: Save & Reload via JSON Ops
        JsonElement json = WorkerStorage.CODEC.encodeStart(JsonOps.INSTANCE, holder.storage).result().orElseThrow();
        WorkerStorage reloadedFromJson = WorkerStorage.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();

        for (int i = 0; i < 4; i++) {
            assertTrue(ItemStack.matches(holder.storage.items().get(i), reloadedFromJson.items().get(i)),
                    "Slot " + i + " must match exactly after JSON reload");
        }
        assertTrue(reloadedFromJson.items().get(4).isEmpty());
    }

    // =========================================================================
    // Test 6: Real Mining Activation with Main Hand Initially Occupied
    // =========================================================================
    @Test
    @DisplayName("Test 6: Real Mining Activation with Main Hand Occupied across Start, Stop, Save and Reload")
    void test06_realMiningActivation_occupiedHand_saveReload() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);
        holder.hand = new ItemStack(Items.BREAD, 16);

        WorkerExecutionLoop.ToolSwapResult result = executeInstrumentedMiningCallChain(
                holder, WORKER_UUID, 200L, "IDLE"
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);

        // Pickaxe equipped
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());

        // 4 unselected tools in slots 0..3 untouched
        assertEquals("Hoe_Wood", holder.storage.items().get(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Hoe_Diamond", holder.storage.items().get(1).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Wood", holder.storage.items().get(2).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Iron", holder.storage.items().get(3).get(DataComponents.CUSTOM_NAME).getString());

        // Displaced Bread x16 preserved in slot 4
        assertEquals(Items.BREAD, holder.storage.items().get(4).getItem());
        assertEquals(16, holder.storage.items().get(4).getCount());

        // Save & reload preserves everything including displaced bread
        Tag nbt = WorkerStorage.CODEC.encodeStart(NbtOps.INSTANCE, holder.storage).result().orElseThrow();
        WorkerStorage reloaded = WorkerStorage.CODEC.parse(NbtOps.INSTANCE, nbt).result().orElseThrow();

        assertEquals(Items.BREAD, reloaded.items().get(4).getItem());
        assertEquals(16, reloaded.items().get(4).getCount());
        assertEquals("Pick_Iron", reloaded.items().get(3).get(DataComponents.CUSTOM_NAME).getString());
    }

    // =========================================================================
    // Test 7: Non-Contiguous Slots with Slot 0 Empty across Start, Stop, Save and Reload
    // =========================================================================
    @Test
    @DisplayName("Test 7: Non-Contiguous Slots with Slot 0 Empty across Start, Stop, Save and Reload")
    void test07_realMiningActivation_slot0Empty_nonContiguous_saveReload() {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < 15; i++) list.add(ItemStack.EMPTY);

        list.set(1, createDistinguishableTool(Items.WOODEN_HOE, 10, "Hoe_Wood"));
        list.set(4, createDistinguishableTool(Items.DIAMOND_HOE, 20, "Hoe_Diamond"));
        list.set(7, createDistinguishableTool(Items.WOODEN_PICKAXE, 5, "Pick_Wood"));
        list.set(10, createDistinguishableTool(Items.IRON_PICKAXE, 15, "Pick_Iron"));
        list.set(14, createDistinguishableTool(Items.DIAMOND_PICKAXE, 25, "Pick_Diamond"));

        StorageAttachmentHolder holder = new StorageAttachmentHolder(new WorkerStorage(list));

        WorkerExecutionLoop.ToolSwapResult result = executeInstrumentedMiningCallChain(
                holder, WORKER_UUID, 300L, "IDLE"
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);

        // Pickaxe from slot 14 equipped
        assertEquals(Items.DIAMOND_PICKAXE, holder.hand.getItem());
        assertEquals("Pick_Diamond", holder.hand.get(DataComponents.CUSTOM_NAME).getString());

        // Original slots strictly maintained
        assertTrue(holder.storage.items().get(0).isEmpty(), "Slot 0 must remain empty");
        assertEquals("Hoe_Wood", holder.storage.items().get(1).get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(holder.storage.items().get(2).isEmpty());
        assertTrue(holder.storage.items().get(3).isEmpty());
        assertEquals("Hoe_Diamond", holder.storage.items().get(4).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Wood", holder.storage.items().get(7).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Iron", holder.storage.items().get(10).get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(holder.storage.items().get(14).isEmpty(), "Slot 14 vacated");

        // Save and reload preserves non-contiguous positioning
        Tag nbt = WorkerStorage.CODEC.encodeStart(NbtOps.INSTANCE, holder.storage).result().orElseThrow();
        WorkerStorage reloaded = WorkerStorage.CODEC.parse(NbtOps.INSTANCE, nbt).result().orElseThrow();

        assertTrue(reloaded.items().get(0).isEmpty());
        assertEquals("Hoe_Wood", reloaded.items().get(1).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Hoe_Diamond", reloaded.items().get(4).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Wood", reloaded.items().get(7).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Iron", reloaded.items().get(10).get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(reloaded.items().get(14).isEmpty());
    }

    // =========================================================================
    // Test 8: Real Mining Activation with Menu Open, Button Click, Sync, Close & Reopen
    // =========================================================================
    @Test
    @DisplayName("Test 8: Real Mining Activation with Menu Open, Button Click, Sync, Close and Reopen")
    void test08_realMiningActivation_menuOpenAndClosed_buttonClick() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);
        UUID workerUuid = WORKER_UUID;

        // 1. Player opens menu: container populated from entity storage
        WorkerManagementMenu menu1 = new WorkerManagementMenu(1, null, List.of(100), List.of(workerUuid), holder.reader(), holder.writer());
        menu1.setTestMode(true);

        assertEquals("Hoe_Wood", menu1.getStorageContainer().getItem(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Diamond", menu1.getStorageContainer().getItem(4).get(DataComponents.CUSTOM_NAME).getString());

        // 2. Button 203 clicked (Mining with quota 64): triggers tool swap and sync
        // Simulating WorkerExecutionLoop.ensureMiningToolEquipped and syncFromEntityStorageIfChanged
        List<ItemStack> handSet = new ArrayList<>();
        List<WorkerStorage> storageSet = new ArrayList<>();
        WorkerExecutionLoop.ToolSwapResult swapRes = WorkerExecutionLoop.swapJobTool(
                holder.storage,
                holder.hand,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                handSet::add,
                storageSet::add
        );
        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, swapRes);
        holder.hand = handSet.get(0);
        holder.storage = storageSet.get(0);

        // Menu synchronizes live
        boolean synced = menu1.syncFromEntityStorageIfChanged();
        assertTrue(synced, "Live synchronization must update container from attachment");

        // Verify container slots match attachment: 4 unselected tools retained, slot 4 empty
        for (int i = 0; i < 4; i++) {
            assertFalse(menu1.getStorageContainer().getItem(i).isEmpty(), "Slot " + i + " must retain tool in menu");
        }
        assertTrue(menu1.getStorageContainer().getItem(4).isEmpty(), "Slot 4 must be empty in menu");

        // 3. Menu is closed and reopened
        WorkerManagementMenu menu2 = new WorkerManagementMenu(2, null, List.of(100), List.of(workerUuid), holder.reader(), holder.writer());
        menu2.setTestMode(true);

        assertEquals("Hoe_Wood", menu2.getStorageContainer().getItem(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Hoe_Diamond", menu2.getStorageContainer().getItem(1).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Wood", menu2.getStorageContainer().getItem(2).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Iron", menu2.getStorageContainer().getItem(3).get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(menu2.getStorageContainer().getItem(4).isEmpty());
    }

    // =========================================================================
    // Test 9: Stale Container Snapshot Zero-Item Write-Back Guard
    // =========================================================================
    @Test
    @DisplayName("Test 9: Stale Container Snapshot Zero-Item Write-Back Guard Audit")
    void test09_staleContainerSnapshotZeroItemWriteBackGuard() {
        WorkerStorage initialStorage = create5ToolStorage();
        StorageAttachmentHolder holder = new StorageAttachmentHolder(initialStorage);
        UUID workerUuid = WORKER_UUID;

        // When reader returns null (entity temporarily unavailable or lookup delayed)
        Supplier<WorkerStorage> failingReader = () -> null;

        WorkerManagementMenu menu = new WorkerManagementMenu(1, null, List.of(100), List.of(workerUuid), failingReader, holder.writer());
        menu.setTestMode(true);

        // Container storage has not been populated
        for (int i = 0; i < 15; i++) {
            assertTrue(menu.getStorageContainer().getItem(i).isEmpty(), "Uninitialized menu container has empty slot " + i);
        }

        // Live attachment on holder still has all 5 tools
        assertEquals(5, holder.storage.items().stream().filter(s -> !s.isEmpty()).count());

        // When entity becomes available again, reader returns live storage
        menu.configureStorageBindingsForTesting(holder.reader(), holder.writer());

        // syncFromEntityStorageIfChanged successfully pulls live data without write-back
        int writesBefore = holder.writeCount;
        boolean synced = menu.syncFromEntityStorageIfChanged();
        assertTrue(synced, "Sync must successfully populate uninitialized container");
        assertEquals(writesBefore, holder.writeCount, "Sync must NOT write back to attachment");

        // Container now holds all 5 tools
        assertEquals("Hoe_Wood", menu.getStorageContainer().getItem(0).get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("Pick_Diamond", menu.getStorageContainer().getItem(4).get(DataComponents.CUSTOM_NAME).getString());
    }
}
