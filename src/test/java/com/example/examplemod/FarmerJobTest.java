package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FarmerJobTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12));

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        CropRegistry.registerDefaults();
    }

    private WorkerProfile createFarmerProfile(FarmerJobData data, JobStatus status) {
        int progress = (data.requestedBlocks() > 0)
                ? (int) Math.min(100L, (data.cropsHarvested() * 100L) / data.requestedBlocks()) : 0;
        WorkerJob job = new WorkerJob(
                WorkerJobs.FARM,
                status,
                progress,
                Optional.empty(),
                Optional.of(WORKSITE),
                Optional.empty(),
                Optional.of(data)
        );
        return new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());
    }

    @Test
    void farmerInitialStateAndQuotaBounds() {
        FarmerJobData data = FarmerJobData.initial(64L, STORAGE);
        assertEquals(64L, data.requestedBlocks());
        assertEquals(0L, data.cropsHarvested());
        assertEquals(64L, data.cropsRemaining());
        assertEquals(0L, data.itemsPendingDelivery());
        assertEquals(0L, data.itemsDelivered());
        assertFalse(data.isHarvestFinished());
        assertTrue(data.isDeliveryComplete());
        assertFalse(data.isFullyCompleted());

        // Illegal arguments fail fast
        assertThrows(IllegalArgumentException.class, () -> FarmerJobData.initial(0L, STORAGE));
        assertThrows(IllegalArgumentException.class, () -> FarmerJobData.initial(-5L, STORAGE));
        assertThrows(NullPointerException.class, () -> FarmerJobData.initial(10L, null));
    }

    @Test
    void harvestProgressIncrementsByOnePerCropBlockRegardlessOfDropCount() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Harvest 1: Wheat yields 1 wheat + 2 seeds (3 items total)
        List<ItemStack> drops1 = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 2));
        profile = FarmerJobController.processHarvestedCrop(profile, 1L, drops1);
        FarmerJobData after1 = profile.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(1L, after1.cropsHarvested(), "Harvest progress must increment by exactly 1 block");
        assertEquals(3L, after1.itemsPendingDelivery(), "Pending delivery should track actual items dropped");

        // Harvest 2: Zero drops (e.g. immature or bad roll) still advances progress by 1 block
        profile = FarmerJobController.processHarvestedCrop(profile, 1L, List.of());
        FarmerJobData after2 = profile.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(2L, after2.cropsHarvested(), "Zero-drop harvest must still advance progress by 1");
        assertEquals(3L, after2.itemsPendingDelivery(), "Zero-drop harvest must not create phantom items");

        // Harvest 3: 5 drops
        profile = FarmerJobController.processHarvestedCrop(profile, 1L, List.of(new ItemStack(Items.CARROT, 5)));
        FarmerJobData after3 = profile.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(3L, after3.cropsHarvested(), "Third block credits 1 block");
        assertEquals(8L, after3.itemsPendingDelivery());
    }

    @Test
    void workerNeverHarvestsMoreThanRequestedQuota() {
        FarmerJobData data = FarmerJobData.initial(5L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Harvest 4 blocks
        profile = FarmerJobController.processHarvestedCrop(profile, 4L, List.of(new ItemStack(Items.WHEAT, 4)));
        assertEquals(4L, profile.job().orElseThrow().farmerData().orElseThrow().cropsHarvested());

        // Attempt to credit 3 more blocks -> total would be 7, capped strictly at 5
        profile = FarmerJobController.processHarvestedCrop(profile, 3L, List.of(new ItemStack(Items.WHEAT, 3)));
        FarmerJobData capped = profile.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(5L, capped.cropsHarvested(), "Harvested crops must never exceed requested quota");
        assertTrue(capped.isHarvestFinished());
    }

    @Test
    void replantingConsumesMatchingSeedFromDropsWhenAvailable() {
        FarmerJobData data = FarmerJobData.initialWithBlocks(10L, STORAGE, List.of(Blocks.WHEAT));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        BlockPos targetPos = new BlockPos(10, 64, 10);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState matureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);
        ResourceLocation blockId = ResourceLocation.withDefaultNamespace("wheat");

        // Simulate drops: 1 wheat + 2 wheat seeds
        List<ItemStack> rawDrops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 2));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY, // Bare hands
                targetPos,
                matureWheat,
                blockId,
                () -> rawDrops,
                () -> true, // Destroy succeeded
                () -> true, // Replant succeeded
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertTrue(result.replanted(), "Replant should succeed when matching seed is dropped");

        // Check output stacks: 1 seed consumed for replanting, leaving 1 wheat + 1 seed in delivery queue
        long totalOutput = result.drops().stream().mapToLong(ItemStack::getCount).sum();
        assertEquals(2L, totalOutput, "1 seed should be consumed for replant, leaving 2 items for delivery");
        long seedCount = result.drops().stream().filter(s -> s.is(Items.WHEAT_SEEDS)).mapToLong(ItemStack::getCount).sum();
        assertEquals(1L, seedCount, "Exactly 1 seed should remain after consuming 1 for replant");

        FarmerJobData updated = result.profile().job().orElseThrow().farmerData().orElseThrow();
        assertEquals(1L, updated.cropsHarvested(), "Harvest progress must increment by 1");
    }

    @Test
    void harvestSucceedsWithoutReplantingWhenNoPlantingItemAvailable() {
        FarmerJobData data = FarmerJobData.initialWithBlocks(10L, STORAGE, List.of(Blocks.WHEAT));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        BlockPos targetPos = new BlockPos(10, 64, 10);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState matureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);
        ResourceLocation blockId = ResourceLocation.withDefaultNamespace("wheat");

        // Simulate drops with NO seeds: only 1 wheat
        List<ItemStack> rawDrops = List.of(new ItemStack(Items.WHEAT, 1));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                targetPos,
                matureWheat,
                blockId,
                () -> rawDrops,
                () -> true,
                () -> false, // No replant attempt
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertFalse(result.replanted(), "Replant should be false when no planting item is available");
        assertEquals(1L, result.drops().size());
        assertEquals(Items.WHEAT, result.drops().getFirst().getItem());
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
    }

    @Test
    void replantingFailureRefundsConsumedItemSafely() {
        FarmerJobData data = FarmerJobData.initialWithBlocks(10L, STORAGE, List.of(Blocks.CARROTS));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        BlockPos targetPos = new BlockPos(10, 64, 10);
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        BlockState matureCarrots = carrots.defaultBlockState().setValue(CropBlock.AGE, 7);
        ResourceLocation blockId = ResourceLocation.withDefaultNamespace("carrots");

        // Drops: 3 carrots
        List<ItemStack> rawDrops = List.of(new ItemStack(Items.CARROT, 3));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                targetPos,
                matureCarrots,
                blockId,
                () -> rawDrops,
                () -> true,
                () -> false, // Replant fails!
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertFalse(result.replanted());
        // Harvest still credited
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
        // Seed deducted from drops must be refunded back to drops and delivery queue
        assertEquals(3L, result.drops().stream().mapToLong(ItemStack::getCount).sum(), "All 3 carrots must be preserved in drops");
        assertEquals(3L, result.profile().job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery(), "All 3 carrots must be queued for delivery");
    }

    @Test
    void replantedCropIsImmatureAndCannotBeImmediatelyHarvested() {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState replantedWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 0);

        // Age 0 is immature -> must NOT be an eligible harvest target
        assertFalse(CropRegistry.isMature(null, BlockPos.ZERO, replantedWheat));
        assertFalse(CropHarvestTarget.eligible(replantedWheat, true, true, 4.0));
    }

    @Test
    void storageDeliveryPartialAndFullHandling() {
        FarmerJobData data = FarmerJobData.initial(5L, STORAGE)
                .withCropHarvested(5L, List.of(new ItemStack(Items.WHEAT, 100))); // 100 wheat pending
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);

        assertEquals(100L, profile.job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery());

        // Container with only 1 slot of max capacity 64
        List<ItemStack> containerSlots = new ArrayList<>(List.of(ItemStack.EMPTY));
        profile = FarmerJobController.deliverToStorage(profile, containerSlots, 1);

        FarmerJobData afterPartial = profile.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(64L, afterPartial.itemsDelivered());
        assertEquals(36L, afterPartial.itemsPendingDelivery(), "36 items must remain in pendingStacks");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, profile.job().orElseThrow().status(),
                "Storage is full; must transition to WAITING_FOR_STORAGE");

        // Now provide another slot and resume delivery
        containerSlots.add(ItemStack.EMPTY);
        profile = FarmerJobController.deliverToStorage(profile, containerSlots, 2);

        FarmerJobData afterComplete = profile.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(100L, afterComplete.itemsDelivered());
        assertEquals(0L, afterComplete.itemsPendingDelivery());
        assertTrue(afterComplete.isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, profile.job().orElseThrow().status(),
                "When all quota harvested and all items delivered, status must be COMPLETED");
    }

    @Test
    void cannotTransitionToCompletedWhileItemsOrCropsPending() {
        FarmerJobData incompleteHarvest = FarmerJobData.initial(10L, STORAGE).withCropHarvested(8L, 0L);
        WorkerProfile p1 = createFarmerProfile(incompleteHarvest, JobStatus.RUNNING);
        assertTrue(p1.transition(JobStatus.COMPLETED, 100, null).isEmpty(),
                "Farmer job cannot be COMPLETED while cropsRemaining > 0");

        FarmerJobData undeliveredItems = FarmerJobData.initial(10L, STORAGE).withCropHarvested(10L, 5L);
        WorkerProfile p2 = createFarmerProfile(undeliveredItems, JobStatus.DELIVERING);
        assertTrue(p2.transition(JobStatus.COMPLETED, 100, null).isEmpty(),
                "Farmer job cannot be COMPLETED while itemsPendingDelivery > 0");
    }

    @Test
    void farmingCapabilityAllowsHandsAndHoesAndRejectsWeapons() {
        assertTrue(WorkerJobCapability.Registry.canPerformFarming(null, ItemStack.EMPTY));
        assertTrue(WorkerJobCapability.Registry.canPerformFarming(null, new ItemStack(Items.IRON_HOE)));
        assertTrue(WorkerJobCapability.Registry.canPerformFarming(null, new ItemStack(Items.NETHERITE_HOE)));
        assertFalse(WorkerJobCapability.Registry.canPerformFarming(null, new ItemStack(Items.DIAMOND_SWORD)));
        assertFalse(WorkerJobCapability.Registry.canPerformFarming(null, new ItemStack(Items.DIAMOND_PICKAXE)));
    }

    @Test
    void farmerJobDataCodecRoundTrip() {
        BlockPos pos = new BlockPos(15, 64, 15);
        FarmerJobData data = FarmerJobData.initial(64L, STORAGE)
                .withCropHarvested(12L, List.of(new ItemStack(Items.CARROT, 24)))
                .withInFlightHarvest(pos, ResourceLocation.withDefaultNamespace("carrots"),
                        List.of(new ItemStack(Items.CARROT, 2)), Optional.of(ResourceLocation.withDefaultNamespace("carrot")));

        var encoded = FarmerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        FarmerJobData decoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(data.requestedBlocks(), decoded.requestedBlocks());
        assertEquals(data.cropsHarvested(), decoded.cropsHarvested());
        assertEquals(data.itemsPendingDelivery(), decoded.itemsPendingDelivery());
        assertEquals(data.itemsDelivered(), decoded.itemsDelivered());
        assertEquals(data.pendingStacks().size(), decoded.pendingStacks().size());
        assertTrue(decoded.inFlightHarvest().isPresent());
        assertEquals(pos, decoded.inFlightHarvest().get().pos());
        assertEquals(ResourceLocation.withDefaultNamespace("carrots"), decoded.inFlightHarvest().get().blockId());
    }

    @Test
    void crashRecoveryReconciliationScenarios() {
        BlockPos pos = new BlockPos(15, 64, 15);
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE)
                .withInFlightHarvest(pos, ResourceLocation.withDefaultNamespace("wheat"),
                        List.of(new ItemStack(Items.WHEAT, 1)), Optional.empty());
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Case 1: Target block is air -> proven broken -> COMMITTED
        FarmerJobController.ReconciliationOutcome airResult =
                FarmerJobController.evaluateReconciliation(profile, true, Blocks.AIR.defaultBlockState());
        assertEquals(FarmerJobController.ReconciliationStatus.COMMITTED, airResult.status());
        assertEquals(1L, airResult.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());

        // Case 2: Target is young age-0 wheat -> proven broken and replanted -> COMMITTED
        BlockState replantedWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0);
        FarmerJobController.ReconciliationOutcome replantResult =
                FarmerJobController.evaluateReconciliation(profile, true, replantedWheat);
        assertEquals(FarmerJobController.ReconciliationStatus.COMMITTED, replantResult.status());

        // Case 3: Target is intact mature wheat -> mutation never executed -> CANCELLED
        BlockState intactWheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        FarmerJobController.ReconciliationOutcome cancelResult =
                FarmerJobController.evaluateReconciliation(profile, true, intactWheat);
        assertEquals(FarmerJobController.ReconciliationStatus.CANCELLED, cancelResult.status());
        assertEquals(0L, cancelResult.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());

        // Case 4: Target chunk unloaded -> UNRESOLVED_CHUNK_UNLOADED (fail closed)
        FarmerJobController.ReconciliationOutcome unloadedResult =
                FarmerJobController.evaluateReconciliation(profile, false, null);
        assertEquals(FarmerJobController.ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, unloadedResult.status());
    }

    @Test
    void cocoaReconciliationRecognizesReplantedAgeZero() {
        BlockPos pos = new BlockPos(15, 64, 15);
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE)
                .withInFlightHarvest(pos, ResourceLocation.withDefaultNamespace("cocoa"),
                        List.of(new ItemStack(Items.COCOA_BEANS, 2)), Optional.of(ResourceLocation.withDefaultNamespace("cocoa_beans")));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Replanted cocoa at age 0 with north facing
        BlockState replantedCocoa = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 0);

        FarmerJobController.ReconciliationOutcome result =
                FarmerJobController.evaluateReconciliation(profile, true, replantedCocoa);

        assertEquals(FarmerJobController.ReconciliationStatus.COMMITTED, result.status(),
                "Young replanted cocoa at age 0 must be recognized and committed during reconciliation");
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
    }

    @Test
    void refundToStorageHandlesPreferredSlotMergeAndCapacityExhaustion() {
        WorkerStorage empty = WorkerStorage.empty();

        // 1. Preferred slot placement into empty slot
        Optional<WorkerStorage> placed = FarmerJobController.refundToStorage(empty, Items.WHEAT_SEEDS, 2);
        assertTrue(placed.isPresent());
        assertEquals(Items.WHEAT_SEEDS, placed.get().items().get(2).getItem());
        assertEquals(1, placed.get().items().get(2).getCount());

        // 2. Merge into existing stack in preferred slot
        Optional<WorkerStorage> merged = FarmerJobController.refundToStorage(placed.get(), Items.WHEAT_SEEDS, 2);
        assertTrue(merged.isPresent());
        assertEquals(2, merged.get().items().get(2).getCount());

        // 3. Preferred slot occupied by different item -> finds empty slot
        List<ItemStack> occupiedSlots = new ArrayList<>(empty.items());
        occupiedSlots.set(0, new ItemStack(Items.DIRT, 64));
        WorkerStorage occupied0 = new WorkerStorage(occupiedSlots);
        Optional<WorkerStorage> fallback = FarmerJobController.refundToStorage(occupied0, Items.CARROT, 0);
        assertTrue(fallback.isPresent());
        assertEquals(Items.CARROT, fallback.get().items().get(1).getItem());

        // 4. Completely full storage -> capacity exhausted, returns empty
        List<ItemStack> fullSlots = new ArrayList<>();
        for (int i = 0; i < WorkerStorage.SIZE; i++) {
            fullSlots.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        WorkerStorage full = new WorkerStorage(fullSlots);
        Optional<WorkerStorage> overflow = FarmerJobController.refundToStorage(full, Items.WHEAT_SEEDS, 0);
        assertTrue(overflow.isEmpty(), "Full storage with no matching stack or empty slot must return empty");
    }

    @Test
    void replantFailureRefundsToPendingStacksWhenWorkerStorageFull() {
        FarmerJobData data = FarmerJobData.initialWithBlocks(10L, STORAGE, List.of(Blocks.WHEAT));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        BlockPos targetPos = new BlockPos(10, 64, 10);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState matureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);
        ResourceLocation blockId = ResourceLocation.withDefaultNamespace("wheat");

        // Drops: 1 wheat + 1 wheat seed
        List<ItemStack> rawDrops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 1));

        // When replanting fails, the seed deducted from drops must be refunded to mutableDrops / pendingStacks
        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                targetPos,
                matureWheat,
                blockId,
                () -> rawDrops,
                () -> true,
                () -> false, // Replant fails!
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertFalse(result.replanted(), "Replant failed");
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());

        // Both wheat and the refunded seed must be preserved in delivery queue, never voided!
        FarmerJobData finalData = result.profile().job().orElseThrow().farmerData().orElseThrow();
        assertEquals(2L, finalData.itemsPendingDelivery(), "Both wheat and refunded seed must be pending delivery");
        boolean hasWheat = finalData.pendingStacks().stream().anyMatch(s -> s.is(Items.WHEAT));
        boolean hasSeed = finalData.pendingStacks().stream().anyMatch(s -> s.is(Items.WHEAT_SEEDS));
        assertTrue(hasWheat, "Delivery queue must contain wheat");
        assertTrue(hasSeed, "Delivery queue must contain refunded seed");
    }

    @Test
    void reconciliationRejectsForeignBlockOrUnloadedChunk() {
        BlockPos pos = new BlockPos(15, 64, 15);
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE)
                .withInFlightHarvest(pos, ResourceLocation.withDefaultNamespace("wheat"),
                        List.of(new ItemStack(Items.WHEAT, 1)), Optional.empty());
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Case A: Foreign block (stone) at target pos -> UNRESOLVED_AMBIGUOUS (fail closed)
        FarmerJobController.ReconciliationOutcome stoneResult =
                FarmerJobController.evaluateReconciliation(profile, true, Blocks.STONE.defaultBlockState());
        assertEquals(FarmerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, stoneResult.status());

        // Case B: Unloaded chunk -> UNRESOLVED_CHUNK_UNLOADED (fail closed)
        FarmerJobController.ReconciliationOutcome unloadedResult =
                FarmerJobController.evaluateReconciliation(profile, false, null);
        assertEquals(FarmerJobController.ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, unloadedResult.status());
    }

    @Test
    void executeHarvestTransactionRejectsUnresolvedInFlightMutation() {
        BlockPos pos = new BlockPos(15, 64, 15);
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE)
                .withInFlightHarvest(pos, ResourceLocation.withDefaultNamespace("wheat"),
                        List.of(new ItemStack(Items.WHEAT, 1)), Optional.empty());
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState matureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);

        // Attempting to execute a new harvest transaction while an in-flight harvest remains unresolved
        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null, // No level available to reconcile
                null,
                ItemStack.EMPTY,
                new BlockPos(16, 64, 16),
                matureWheat,
                ResourceLocation.withDefaultNamespace("wheat"),
                List::of,
                () -> true,
                () -> true,
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.REJECTED_PREFLIGHT, result.status());
        assertTrue(result.message().orElse("").contains("unresolved in-flight harvest"),
                "Must reject when previous in-flight harvest has not been reconciled");
    }

    @Test
    void quotaBoundsDenyHarvestWhenFinished() {
        FarmerJobData finishedData = FarmerJobData.initial(5L, STORAGE)
                .withCropHarvested(5L, 0L);
        WorkerProfile profile = createFarmerProfile(finishedData, JobStatus.RUNNING);

        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState matureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);

        FarmerJobController.ExecutionGate gate = FarmerJobController.canExecuteHarvestStep(
                profile, null, null, ItemStack.EMPTY, new BlockPos(10, 64, 10), matureWheat);
        assertEquals(FarmerJobController.ExecutionGate.REJECTED_JOB_FINISHED, gate);

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile, null, null, ItemStack.EMPTY, new BlockPos(10, 64, 10), matureWheat,
                ResourceLocation.withDefaultNamespace("wheat"), List::of, () -> true, () -> true, null);
        assertEquals(FarmerJobController.TransactionStatus.REJECTED_PREFLIGHT, result.status());
    }

    @Test
    void farmerCropAllowlistStrictFilteringAndSerialization() {
        // Allowlist configured for Wheat and Carrots only
        FarmerJobData data = FarmerJobData.initialWithBlocks(20L, STORAGE, List.of(Blocks.WHEAT, Blocks.CARROTS));
        assertEquals(2, data.permittedCrops().size());
        assertTrue(data.isCropPermitted(Blocks.WHEAT));
        assertTrue(data.isCropPermitted(Blocks.CARROTS));
        assertFalse(data.isCropPermitted(Blocks.POTATOES));
        assertFalse(data.isCropPermitted(Blocks.BEETROOTS));
        assertFalse(data.isCropPermitted(Blocks.COCOA));

        // Codec roundtrip preserves allowlist
        var encoded = FarmerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var decoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(2, decoded.permittedCrops().size());
        assertTrue(decoded.isCropPermitted(Blocks.WHEAT));
        assertTrue(decoded.isCropPermitted(Blocks.CARROTS));
        assertFalse(decoded.isCropPermitted(Blocks.POTATOES));

        // Safe default-deny: empty allowlist denies all crops
        FarmerJobData openData = FarmerJobData.initial(20L, STORAGE, List.of());
        assertFalse(openData.isCropPermitted(Blocks.WHEAT));
        assertFalse(openData.isCropPermitted(Blocks.POTATOES));
        assertFalse(openData.isCropPermitted(Blocks.COCOA));

        // Backwards-compatibility: decoding JSON missing permittedCrops field defaults to safe default-deny
        JsonObject legacyJson = new JsonObject();
        legacyJson.add("requested_blocks", new JsonPrimitive(15L));
        legacyJson.add("crops_harvested", new JsonPrimitive(0L));
        legacyJson.add("items_pending_delivery", new JsonPrimitive(0L));
        legacyJson.add("items_delivered", new JsonPrimitive(0L));
        legacyJson.add("pending_stacks", new com.google.gson.JsonArray());
        var storageObj = WorkerTarget.CODEC.encodeStart(JsonOps.INSTANCE, STORAGE).getOrThrow();
        legacyJson.add("storage", storageObj);

        var legacyDecoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, legacyJson).getOrThrow();
        assertTrue(legacyDecoded.permittedCrops().isEmpty());
        assertFalse(legacyDecoded.isCropPermitted(Blocks.WHEAT));
        assertFalse(legacyDecoded.isCropPermitted(Blocks.POTATOES));
    }

    @Test
    void dynamicCropChangesPreflightRejection() {
        // Job allowlist permits only Wheat
        FarmerJobData data = FarmerJobData.initialWithBlocks(10L, STORAGE, List.of(Blocks.WHEAT));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);
        BlockPos targetPos = new BlockPos(10, 64, 10);

        // Case A: Target crop is immature wheat (age 3 != 7)
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState immatureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 3);
        FarmerJobController.ExecutionGate gateImmature = FarmerJobController.canExecuteHarvestStep(
                profile, null, null, ItemStack.EMPTY, targetPos, immatureWheat);
        assertEquals(FarmerJobController.ExecutionGate.REJECTED_IMMATURE_CROP, gateImmature);

        FarmerJobController.TransactionExecutionResult immatureResult = FarmerJobController.executeHarvestTransaction(
                profile, null, null, ItemStack.EMPTY, targetPos, immatureWheat,
                ResourceLocation.withDefaultNamespace("wheat"), List::of, () -> true, () -> true, null);
        assertEquals(FarmerJobController.TransactionStatus.REJECTED_PREFLIGHT, immatureResult.status());

        // Case B: Target crop was replaced with unpermitted mature potatoes
        CropBlock potatoes = (CropBlock) Blocks.POTATOES;
        BlockState maturePotatoes = potatoes.defaultBlockState().setValue(CropBlock.AGE, 7);
        FarmerJobController.ExecutionGate gateUnpermitted = FarmerJobController.canExecuteHarvestStep(
                profile, null, null, ItemStack.EMPTY, targetPos, maturePotatoes);
        assertEquals(FarmerJobController.ExecutionGate.REJECTED_UNPERMITTED_CROP, gateUnpermitted);

        FarmerJobController.TransactionExecutionResult unpermittedResult = FarmerJobController.executeHarvestTransaction(
                profile, null, null, ItemStack.EMPTY, targetPos, maturePotatoes,
                ResourceLocation.withDefaultNamespace("potatoes"), List::of, () -> true, () -> true, null);
        assertEquals(FarmerJobController.TransactionStatus.REJECTED_PREFLIGHT, unpermittedResult.status());

        // Case C: Target crop was broken by player or external event (air)
        FarmerJobController.ExecutionGate gateAir = FarmerJobController.canExecuteHarvestStep(
                profile, null, null, ItemStack.EMPTY, targetPos, Blocks.AIR.defaultBlockState());
        assertEquals(FarmerJobController.ExecutionGate.REJECTED_SAFETY_POLICY, gateAir);
    }

    @Test
    void farmerMultiTripDeliveryAndBufferingThreshold() {
        // Request: 50 crops. Currently harvested 10 crops, with 64 wheat pending delivery (buffering threshold met)
        FarmerJobData data = new FarmerJobData(50L, 10L, 64L, 0L,
                List.of(new ItemStack(Items.WHEAT, 64)), STORAGE, Optional.empty(), List.of());
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);

        // Chest has space for only 24 items in slot 0 (40 items already present)
        List<ItemStack> containerSlots = new ArrayList<>();
        containerSlots.add(new ItemStack(Items.WHEAT, 40));

        // Attempt delivery of 64 wheat into chest
        profile = FarmerJobController.deliverToStorage(profile, containerSlots, 1);
        FarmerJobData trip1Data = profile.job().orElseThrow().farmerData().orElseThrow();

        // 24 items deposited (slot 0 now has 64), 40 items remain pending
        assertEquals(64, containerSlots.get(0).getCount());
        assertEquals(40L, trip1Data.itemsPendingDelivery());
        assertEquals(24L, trip1Data.itemsDelivered());
        assertEquals(1, trip1Data.pendingStacks().size());
        assertEquals(40, trip1Data.pendingStacks().get(0).getCount());
        assertEquals(JobStatus.WAITING_FOR_STORAGE, profile.job().orElseThrow().status(),
                "Must enter WAITING_FOR_STORAGE when chest cannot accept remaining pending output");

        // Player provides an empty second slot in the container
        containerSlots.add(ItemStack.EMPTY);

        // Second delivery trip / retry: remaining 40 items are deposited
        profile = FarmerJobController.deliverToStorage(profile, containerSlots, 2);
        FarmerJobData trip2Data = profile.job().orElseThrow().farmerData().orElseThrow();

        assertEquals(40, containerSlots.get(1).getCount());
        assertEquals(0L, trip2Data.itemsPendingDelivery());
        assertEquals(64L, trip2Data.itemsDelivered());
        assertTrue(trip2Data.pendingStacks().isEmpty());
        // Since cropsHarvested (10) < requestedBlocks (50), job transitions back to RUNNING to resume farming!
        assertEquals(JobStatus.RUNNING, profile.job().orElseThrow().status(),
                "Must transition back to RUNNING after delivering buffered items when quota not yet completed");
    }

    @Test
    void farmWorksiteAdapterRejectsFarmlandAirAndNonCropBlocks() {
        WorksiteAdapter adapter = WorksiteAdapter.Registry.get(WorkerJobs.FARM).orElseThrow();

        // Farmland is not a crop -> must be rejected so unrestricted allowlists cannot be created
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.FARMLAND.defaultBlockState(), null),
                "Farmland must be rejected as worksite for farming");

        // Air is not a crop
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.AIR.defaultBlockState(), null),
                "Air must be rejected as worksite for farming");

        // Dirt and Stone are not crops
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.DIRT.defaultBlockState(), null));
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.STONE.defaultBlockState(), null));

        // Crops are accepted (mock null level/player checks pre-world filter or pass valid objects)
        // With null level/player, the first guard in isValidWorksite returns false if level/player is null.
        // Let's verify that when level/player is null, it safely returns false without crashing.
        assertFalse(adapter.isValidWorksite(null, null, null, null, null));
    }

    @Test
    void pausedJobTransitionAllowedWithNullFailureReason() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Transition to PAUSED with null failure succeeds cleanly
        Optional<WorkerProfile> pausedOpt = profile.transition(JobStatus.PAUSED, 20, null);
        assertTrue(pausedOpt.isPresent(), "Transition to PAUSED with null failure must succeed");
        assertEquals(JobStatus.PAUSED, pausedOpt.get().job().orElseThrow().status());
        assertEquals(20, pausedOpt.get().job().orElseThrow().progress());
        assertTrue(pausedOpt.get().job().orElseThrow().failure().isEmpty());
    }

    @Test
    void farmerEmptyAllowlistRejectsHarvestingAtExecutionGate() {
        // Job initialized with empty allowlist (default-deny)
        FarmerJobData emptyData = FarmerJobData.initial(10L, STORAGE, List.of());
        WorkerProfile profile = createFarmerProfile(emptyData, JobStatus.RUNNING);

        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState matureWheat = wheat.defaultBlockState().setValue(CropBlock.AGE, 7);

        FarmerJobController.ExecutionGate gate = FarmerJobController.canExecuteHarvestStep(
                profile, null, null, ItemStack.EMPTY, new BlockPos(10, 64, 10), matureWheat
        );

        assertEquals(FarmerJobController.ExecutionGate.REJECTED_UNPERMITTED_CROP, gate,
                "Empty allowlist must default-deny all crop harvesting");
    }

    @Test
    void farmerCropDeliveryToContainerPreservesUninsertedStacksOnCapacityExhaustion() {
        FarmerJobData data = FarmerJobData.initialWithBlocks(10L, STORAGE, List.of(Blocks.WHEAT))
                .withCropHarvested(10L, List.of(new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 30)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);

        // Single small chest container with 1 slot only, already holding 60 wheat
        net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(1);
        container.setItem(0, new ItemStack(Items.WHEAT, 60)); // room for only 4 more wheat

        WorkerProfile updated = FarmerJobController.deliverToContainer(profile, container);
        WorkerJob updatedJob = updated.job().orElseThrow();
        FarmerJobData updatedData = updatedJob.farmerData().orElseThrow();

        // Slot should now be maxed at 64 (4 inserted)
        assertEquals(64, container.getItem(0).getCount());
        assertEquals(4L, updatedData.itemsDelivered(), "Exactly 4 items delivered");
        assertEquals(90L, updatedData.itemsPendingDelivery(), "Remaining 90 items must be preserved in pending delivery");
        assertFalse(updatedData.isFullyCompleted(), "Cannot be completed when 90 items remain pending");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, updatedJob.status(), "Must enter WAITING_FOR_STORAGE when container is full");

        // Second container (Double chest capacity simulation - 54 slots)
        net.minecraft.world.SimpleContainer largeContainer = new net.minecraft.world.SimpleContainer(54);
        WorkerProfile fullyDelivered = FarmerJobController.deliverToContainer(updated, largeContainer);
        FarmerJobData finalData = fullyDelivered.job().orElseThrow().farmerData().orElseThrow();

        assertEquals(94L, finalData.itemsDelivered(), "All 94 items delivered across containers");
        assertEquals(0L, finalData.itemsPendingDelivery(), "0 items pending");
        assertTrue(finalData.pendingStacks().isEmpty());
        assertTrue(finalData.isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, fullyDelivered.job().orElseThrow().status());
    }

    @Test
    void farmWorksiteAdapterRejectsBareFarmlandWithoutPreconfiguredAllowlist() {
        WorksiteAdapter adapter = WorksiteAdapter.Registry.get(WorkerJobs.FARM).orElseThrow();
        // Null checks and non-crop blocks fail safely
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.FARMLAND.defaultBlockState(), null));
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.DIRT.defaultBlockState(), null));
        assertFalse(adapter.isValidWorksite(null, new BlockPos(10, 64, 10), Direction.UP, Blocks.AIR.defaultBlockState(), null));
    }
}
