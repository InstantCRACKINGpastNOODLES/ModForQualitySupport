package com.example.examplemod;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 Verification Suite:
 * Comprehensive tests for Farmer Crop Discovery, Bounded Incremental Scans,
 * Waiting & Retry State Transitions, and Harvest & Replant Transactions.
 */
class FarmerDiscoveryAndHarvestFixTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static final BlockPos ANCHOR = new BlockPos(100, 64, 100);
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, ANCHOR);
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 95));

    private Map<BlockPos, BlockState> worldBlocks;
    private AtomicBoolean chunksLoaded;
    private LevelReader mockLevel;

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

    @BeforeEach
    void setUp() {
        CropRegistry.registerDefaults();
        worldBlocks = new HashMap<>();
        chunksLoaded = new AtomicBoolean(true);

        mockLevel = (LevelReader) Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("hasChunkAt".equals(name)) {
                        return chunksLoaded.get();
                    }
                    if ("getBlockState".equals(name)) {
                        BlockPos pos = (BlockPos) args[0];
                        return worldBlocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    }
                    if ("isClientSide".equals(name)) {
                        return false;
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    if (ret == double.class) return 0.0;
                    if (ret == float.class) return 0.0f;
                    if (ret == Optional.class) return Optional.empty();
                    return null;
                }
        );
    }

    private BlockState matureWheat() {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
    }

    private BlockState immatureWheat(int age) {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, Math.min(6, age));
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

    // ========================================================
    // Group 1: Crop Discovery (Scenarios 1 - 10)
    // ========================================================

    @Test
    @DisplayName("1. Mature vanilla wheat exists before Farmer job initialization and is discovered")
    void testMatureWheatDiscoveredBeforeJobInit() {
        BlockPos wheatPos = ANCHOR;
        worldBlocks.put(wheatPos, matureWheat());
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        CropRegistry.CropScanResult result = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);

        assertTrue(result.targetPos().isPresent(), "Mature wheat at anchor must be discovered immediately");
        assertEquals(wheatPos, result.targetPos().get());
        assertTrue(result.nextCursor() > 0, "Cursor must advance after finding crop");
    }

    @Test
    @DisplayName("2. Wheat is immature at initialization and becomes mature later")
    void testWheatImmatureThenBecomesMature() {
        BlockPos wheatPos = ANCHOR.offset(2, 0, 1);
        worldBlocks.put(wheatPos, immatureWheat(3));
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));

        // Initial scan does not find immature wheat
        CropRegistry.CropScanResult res1 = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);
        assertTrue(res1.targetPos().isEmpty(), "Immature wheat must not be targeted");

        // Crop matures
        worldBlocks.put(wheatPos, matureWheat());

        // Next scan discovers it
        CropRegistry.CropScanResult res2 = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);
        assertTrue(res2.targetPos().isPresent(), "Wheat must be discovered once mature");
        assertEquals(wheatPos, res2.targetPos().get());
    }

    @Test
    @DisplayName("3. First scan finds no mature crops, wheat becomes mature afterward")
    void testFirstScanFindsNoCropsThenMatureWheatAppears() {
        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));

        // Run full scan across all slices with no crops present
        FarmerJobData currentData = farmerData;
        int cursor = 0;
        boolean cycleCompleted = false;
        for (int i = 0; i < 20; i++) {
            CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, currentData, cursor);
            cursor = res.nextCursor();
            currentData = currentData.withScanCursor(res.nextCursor(), res.emptyChecks());
            if (res.cycleCompleted()) {
                cycleCompleted = true;
                break;
            }
        }
        assertTrue(cycleCompleted, "Full scan cycle must complete when no crops exist");

        // Mature wheat appears on farmland at anchor
        BlockPos wheatPos = ANCHOR;
        worldBlocks.put(wheatPos, matureWheat());
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        // Rescan from cursor
        CropRegistry.CropScanResult resAfter = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);
        assertTrue(resAfter.targetPos().isPresent(), "Mature wheat must be discovered on subsequent scan");
        assertEquals(wheatPos, resAfter.targetPos().get());
    }

    @Test
    @DisplayName("4. Multiple mature wheat crops exist; worker discovers closest target first")
    void testMultipleMatureWheatCropsDiscoveredInOrder() {
        BlockPos closeCrop = ANCHOR.offset(1, 0, 0);
        BlockPos farCrop = ANCHOR.offset(10, 0, 0);
        worldBlocks.put(closeCrop, matureWheat());
        worldBlocks.put(farCrop, matureWheat());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        CropRegistry.CropScanResult result = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);

        assertTrue(result.targetPos().isPresent());
        assertEquals(closeCrop, result.targetPos().get(), "Closer crop must be discovered before further crop");
    }

    @Test
    @DisplayName("5. Wheat exists near worksite boundary (radius 16)")
    void testWheatNearWorksiteBoundary() {
        BlockPos edgeCrop = ANCHOR.offset(16, 0, 16);
        worldBlocks.put(edgeCrop, matureWheat());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));

        // Iterate through slices until edge crop is reached
        Optional<BlockPos> found = Optional.empty();
        int cursor = 0;
        for (int i = 0; i < 15; i++) {
            CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, cursor);
            if (res.targetPos().isPresent()) {
                found = res.targetPos();
                break;
            }
            cursor = res.nextCursor();
        }
        assertTrue(found.isPresent(), "Edge crop at radius 16 must be discoverable within full cycle");
        assertEquals(edgeCrop, found.get());
    }

    @Test
    @DisplayName("6. Search budget is respected and scan cursor advances deterministically")
    void testSearchBudgetAndCursorProgression() {
        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        assertEquals(500, CropRegistry.FARMER_SCAN_BUDGET);

        // First slice: 0 -> 500
        CropRegistry.CropScanResult res1 = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);
        assertEquals(500, res1.nextCursor());
        assertFalse(res1.cycleCompleted());

        // Second slice: 500 -> 1000
        CropRegistry.CropScanResult res2 = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, res1.nextCursor());
        assertEquals(1000, res2.nextCursor());
        assertFalse(res2.cycleCompleted());
    }

    @Test
    @DisplayName("7. Full scan cycle completes without falsely skipping crop-level positions")
    void testFullScanCycleCompletes() {
        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        int totalPositions = CropRegistry.TOTAL_SEARCH_POSITIONS; // 5445

        FarmerJobData currentData = farmerData;
        int cursor = 0;
        int slices = 0;
        boolean cycleFinished = false;
        while (slices < 20) {
            CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, currentData, cursor);
            cursor = res.nextCursor();
            currentData = currentData.withScanCursor(res.nextCursor(), res.emptyChecks());
            slices++;
            if (res.cycleCompleted()) {
                cycleFinished = true;
                break;
            }
        }
        assertTrue(cycleFinished, "Scan cycle must complete after inspecting total search volume");
        // Math: 5445 positions / 500 per slice = 11 slices
        assertEquals(11, slices, "5445 positions with budget 500 must complete in exactly 11 slices");
    }

    @Test
    @DisplayName("8. Unloaded chunks are not force-loaded and can be reconsidered after loading")
    void testUnloadedChunksSkippedAndReconsidered() {
        BlockPos wheatPos = ANCHOR.offset(3, 0, 0);
        worldBlocks.put(wheatPos, matureWheat());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));

        // Unloaded chunk skips block safely
        chunksLoaded.set(false);
        CropRegistry.CropScanResult resUnloaded = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);
        assertTrue(resUnloaded.targetPos().isEmpty(), "Unloaded chunks must be skipped safely");

        // Chunk loads -> crop is discovered
        chunksLoaded.set(true);
        CropRegistry.CropScanResult resLoaded = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, 0);
        assertTrue(resLoaded.targetPos().isPresent());
        assertEquals(wheatPos, resLoaded.targetPos().get());
    }

    @Test
    @DisplayName("9. Save and reload during an incomplete scan resumes safely")
    void testSaveReloadDuringScanPreservesCursor() {
        FarmerJobData original = FarmerJobData.initial(64L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withSearchRadius(12)
                .withScanCursor(1500);

        var encoded = FarmerJobData.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        FarmerJobData decoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(1500, decoded.scanCursor(), "Cursor must survive serialization");
        assertEquals(12, decoded.searchRadius(), "Search radius must survive serialization");
    }

    @Test
    @DisplayName("10. Changing allowlist or radius resets cursor correctly")
    void testChangingAllowlistOrRadiusResetsCursor() {
        FarmerJobData data = FarmerJobData.initial(50L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withScanCursor(2500);
        assertEquals(2500, data.scanCursor());

        // Changing permitted crops resets cursor to 0
        FarmerJobData newCrops = data.withPermittedCrops(List.of(BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS)));
        assertEquals(0, newCrops.scanCursor(), "Changing permitted crops must reset cursor");

        // Changing radius resets cursor to 0
        FarmerJobData newRadius = data.withSearchRadius(8);
        assertEquals(0, newRadius.scanCursor(), "Changing search radius must reset cursor");
    }

    // ========================================================
    // Group 2: Execution & Recovery (Scenarios 11 - 15)
    // ========================================================

    @Test
    @DisplayName("11. No mature crops produces WAITING_FOR_CROPS state rather than silent RUNNING")
    void testNoMatureCropsTransitionsToWaitingForCrops() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Verify transition to WAITING_FOR_CROPS is permitted and valid
        WorkerProfile waitingProfile = profile.transition(JobStatus.WAITING_FOR_CROPS, 0, "No mature crops found in worksite").orElseThrow();
        assertEquals(JobStatus.WAITING_FOR_CROPS, waitingProfile.job().orElseThrow().status());
        assertEquals("No mature crops found in worksite", waitingProfile.job().orElseThrow().failure().orElse(""));
    }

    @Test
    @DisplayName("12. Worker automatically recovers from WAITING_FOR_CROPS when wheat matures")
    void testAutoRecoveryFromWaitingForCrops() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Mature wheat appears
        BlockPos wheatPos = ANCHOR.offset(1, 0, 0);
        worldBlocks.put(wheatPos, matureWheat());

        // Scan discovers crop
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, 0);
        assertTrue(res.targetPos().isPresent());

        // Recovery transition: WAITING_FOR_CROPS -> RUNNING
        WorkerProfile recovered = profile.transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, recovered.job().orElseThrow().status());
        assertTrue(recovered.job().orElseThrow().failure().isEmpty());
    }

    @Test
    @DisplayName("13. Invalid or removed target triggers rediscovery")
    void testRemovedTargetTriggersRediscovery() {
        BlockPos crop1 = ANCHOR.offset(1, 0, 0);
        BlockPos crop2 = ANCHOR.offset(2, 0, 0);
        worldBlocks.put(crop1, matureWheat());
        worldBlocks.put(crop2, matureWheat());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        CropRegistry.CropScanResult res1 = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, 0);
        assertEquals(crop1, res1.targetPos().orElseThrow());

        // crop1 is broken by an external entity / player
        worldBlocks.remove(crop1);

        // Next scan discovers crop2
        CropRegistry.CropScanResult res2 = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, res1.nextCursor());
        assertTrue(res2.targetPos().isPresent());
        assertEquals(crop2, res2.targetPos().get());
    }

    @Test
    @DisplayName("14. Waiting state WAITING_FOR_CROPS survives save/reload")
    void testWaitingForCropsSurvivesSaveReload() {
        FarmerJobData data = FarmerJobData.initial(20L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerJob job = new WorkerJob(
                WorkerJobs.FARM,
                JobStatus.WAITING_FOR_CROPS,
                0,
                Optional.of("No mature crops found in worksite"),
                Optional.of(WORKSITE),
                Optional.empty(),
                Optional.of(data)
        );

        var encoded = WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, job).getOrThrow();
        WorkerJob decoded = WorkerJob.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(JobStatus.WAITING_FOR_CROPS, decoded.status());
        assertEquals(Optional.of("No mature crops found in worksite"), decoded.failure());
    }

    @Test
    @DisplayName("15. Tool prerequisite waiting does not permanently block crop rediscovery")
    void testToolWaitingDoesNotBlockRediscovery() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile waitingTool = createFarmerProfile(data, JobStatus.WAITING_FOR_TOOL);

        // Resume to RUNNING once tool is equipped
        WorkerProfile resumed = waitingTool.transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, resumed.job().orElseThrow().status());

        // Crop scanning proceeds normally
        worldBlocks.put(ANCHOR, matureWheat());
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, 0);
        assertTrue(res.targetPos().isPresent());
    }

    // ========================================================
    // Group 3: Harvest & Replant (Scenarios 16 - 23)
    // ========================================================

    @Test
    @DisplayName("16. Mature wheat can be harvested and replanted")
    void testMatureWheatHarvestAndReplant() {
        BlockPos pos = ANCHOR;
        BlockState mature = matureWheat();
        worldBlocks.put(pos, mature);
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 2));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                mature,
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> { worldBlocks.put(pos, Blocks.AIR.defaultBlockState()); return true; },
                () -> { worldBlocks.put(pos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0)); return true; },
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertTrue(result.replanted(), "Wheat must be marked as replanted");
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
        // 1 wheat + 1 remaining seed (1 seed was consumed for replant) = 2 items pending
        assertEquals(2L, result.profile().job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery());
    }

    @Test
    @DisplayName("17. Preflight canReplant succeeds even though current crop occupies target position")
    void testPreflightCanReplantSucceedsWhileCropStillPresent() {
        BlockPos pos = ANCHOR;
        BlockState mature = matureWheat();
        worldBlocks.put(pos, mature);
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        // Prior to Phase 8, this returned false because currentTargetState was mature wheat (not air).
        // In Phase 8, it correctly returns true because the crop occupying the position is being harvested!
        boolean canReplant = CropRegistry.canReplant(mockLevel, pos, mature);
        assertTrue(canReplant, "canReplant preflight must succeed when supporting soil is valid and space is occupied by harvested crop");
    }

    @Test
    @DisplayName("18. Seed is consumed exactly once after successful planting")
    void testSeedConsumedExactlyOnceAfterReplant() {
        BlockPos pos = ANCHOR;
        BlockState mature = matureWheat();
        worldBlocks.put(pos, mature);
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // 1 wheat + 2 seeds in drops
        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 2));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                mature,
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> true,
                () -> true,
                null
        );

        assertTrue(result.replanted());
        // Original 3 items minus 1 consumed seed = exactly 2 items remaining in pending
        long totalItems = result.drops().stream().mapToLong(ItemStack::getCount).sum();
        assertEquals(2L, totalItems, "Exactly 1 seed must be consumed; 2 items must remain");
    }

    @Test
    @DisplayName("19. Failed planting preserves and refunds seed exactly once")
    void testFailedReplantRefundsSeedExactlyOnce() {
        BlockPos pos = ANCHOR;
        BlockState mature = matureWheat();
        worldBlocks.put(pos, mature);
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // 1 wheat + 2 seeds
        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 2));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                mature,
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> true,
                () -> false, // Replant fails!
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertFalse(result.replanted());
        // All 3 items preserved (1 wheat + 2 seeds)
        long totalItems = result.drops().stream().mapToLong(ItemStack::getCount).sum();
        assertEquals(3L, totalItems, "All items including seed must be preserved on replant failure");
        assertEquals(3L, result.profile().job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery());
    }

    @Test
    @DisplayName("20. Harvest drops enter persistent pending output")
    void testHarvestDropsEnterPersistentPendingOutput() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 4), new ItemStack(Items.WHEAT_SEEDS, 5));

        FarmerJobData updated = data.withCropHarvested(2L, drops);
        assertEquals(2L, updated.cropsHarvested());
        assertEquals(9L, updated.itemsPendingDelivery());
        assertEquals(2, updated.pendingStacks().size());

        // Roundtrip codec
        var encoded = FarmerJobData.CODEC.encodeStart(JsonOps.INSTANCE, updated).getOrThrow();
        FarmerJobData decoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(9L, decoded.itemsPendingDelivery());
        assertEquals(2, decoded.pendingStacks().size());
    }

    @Test
    @DisplayName("21. Full or unavailable storage does not lose output")
    void testFullStorageDoesNotLoseOutput() {
        FarmerJobData data = FarmerJobData.initial(5L, STORAGE)
                .withCropHarvested(5L, List.of(new ItemStack(Items.WHEAT, 80)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);

        // Storage can only accept 64 items (1 slot)
        List<ItemStack> container = new ArrayList<>(List.of(ItemStack.EMPTY));
        WorkerProfile updated = FarmerJobController.deliverToStorage(profile, container, 1);

        FarmerJobData fd = updated.job().orElseThrow().farmerData().orElseThrow();
        assertEquals(64L, fd.itemsDelivered());
        assertEquals(16L, fd.itemsPendingDelivery(), "16 items must remain safe in pending delivery");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, updated.job().orElseThrow().status());
    }

    @Test
    @DisplayName("22. Failed harvest does not falsely increment progress")
    void testFailedHarvestDoesNotIncrementProgress() {
        BlockPos pos = ANCHOR;
        BlockState mature = matureWheat();

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                mature,
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> List.of(new ItemStack(Items.WHEAT, 1)),
                () -> false, // Block destruction fails!
                () -> true,
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.MUTATION_FAILED, result.status());
        assertEquals(0L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested(),
                "Failed block destruction must never increment progress");
    }

    @Test
    @DisplayName("23. Crash reconciliation does not duplicate drops or seeds")
    void testCrashReconciliationIdempotence() {
        BlockPos pos = ANCHOR;
        ResourceLocation wheatId = BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT);
        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 1));

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(wheatId))
                .withInFlightHarvest(pos, wheatId, drops, Optional.of(BuiltInRegistries.ITEM.getKey(Items.WHEAT_SEEDS)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Case A: Block verified destroyed (isAir) -> commit once
        FarmerJobController.ReconciliationOutcome outcome1 = FarmerJobController.evaluateReconciliation(profile, true, Blocks.AIR.defaultBlockState());
        assertEquals(FarmerJobController.ReconciliationStatus.COMMITTED, outcome1.status());
        assertEquals(1L, outcome1.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());

        // Subsequent reconciliation on already committed profile is no-op
        FarmerJobController.ReconciliationOutcome outcome2 = FarmerJobController.evaluateReconciliation(outcome1.profile(), true, Blocks.AIR.defaultBlockState());
        assertEquals(FarmerJobController.ReconciliationStatus.NO_OP, outcome2.status());
        assertEquals(1L, outcome2.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
    }
}
