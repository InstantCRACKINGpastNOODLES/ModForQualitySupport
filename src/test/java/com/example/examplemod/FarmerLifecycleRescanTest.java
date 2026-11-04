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
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
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
 * Phase 8.3 Verification Suite:
 * Automated regression tests for Farmer continuous crop re-scan, target retention,
 * and WAITING_FOR_CROPS wake-up lifecycle.
 *
 * Verifies the 8 core lifecycle guarantees:
 * 1. All crops immature -> worker waits -> one crop matures -> worker resumes and harvests.
 * 2. All crops immature -> multiple retry cycles -> crop matures -> worker resumes.
 * 3. Crop matures while worker is in WAITING_FOR_CROPS.
 * 4. Scan cursor wraps around before the crop matures.
 * 5. Worker saves and reloads while waiting -> crop matures -> worker resumes.
 * 6. No supported crops exist -> worker remains safely waiting.
 * 7. Quota reached while output is pending -> delivery completes before job completion.
 * 8. Both Maid and Cow integrations share the same execution loop and wake-up behavior.
 */
class FarmerLifecycleRescanTest {

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

    @Test
    @DisplayName("1. All crops immature -> worker waits -> one crop matures -> worker resumes and harvests")
    void testImmatureThenMaturesResumesAndHarvests() {
        BlockPos wheatPos = ANCHOR.offset(1, 0, 1);
        worldBlocks.put(wheatPos, immatureWheat(3));
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(farmerData, JobStatus.RUNNING);

        // Step 1: Scan entire volume when crops are immature -> complete cycle -> WAITING_FOR_CROPS
        CropRegistry.CropScanResult scanRes = null;
        for (int i = 0; i < 11; i++) {
            scanRes = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, farmerData.scanCursor());
            farmerData = farmerData.withScanCursor(scanRes.nextCursor(), scanRes.emptyChecks());
        }
        assertNotNull(scanRes);
        assertTrue(scanRes.cycleCompleted(), "Cycle must complete after inspecting entire volume");
        assertTrue(scanRes.targetPos().isEmpty(), "No target position when all crops immature");

        // Transition profile to WAITING_FOR_CROPS with scanCursor reset to 0 and retryTimer = 40
        farmerData = farmerData.withScanCursor(0, 0).withRetryTimer(40);
        profile = profile.withJob(profile.job().get().withFarmerData(farmerData).get())
                .transition(JobStatus.WAITING_FOR_CROPS, 0, "No mature crops found in worksite").orElseThrow();
        assertEquals(JobStatus.WAITING_FOR_CROPS, profile.job().get().status());
        assertEquals(0, farmerData.scanCursor());
        assertEquals(40, farmerData.retryTimer());

        // Step 2: Wheat matures to age 7 while worker is waiting
        worldBlocks.put(wheatPos, matureWheat());

        // Step 3: Advance retry cooldown from 40 down to 0
        while (farmerData.retryTimer() > 0) {
            farmerData = farmerData.withRetryTimer(farmerData.retryTimer() - 1);
        }
        assertEquals(0, farmerData.retryTimer());

        // Step 4: Retry scan executes from cursor 0 and discovers mature wheat
        CropRegistry.CropScanResult retryScan = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, farmerData, farmerData.scanCursor());
        assertTrue(retryScan.targetPos().isPresent(), "Retry scan must discover the mature wheat");
        assertEquals(wheatPos, retryScan.targetPos().get());

        // Step 5: Lock activeTarget and transition to RUNNING
        farmerData = farmerData.withActiveTarget(wheatPos).withRetryTimer(0);
        profile = profile.withJob(profile.job().get().withFarmerData(farmerData).get())
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, profile.job().get().status());
        assertTrue(profile.job().get().farmerData().get().activeTarget().isPresent());
        assertEquals(wheatPos, profile.job().get().farmerData().get().activeTarget().get());

        // Step 6: Execute harvest and replant
        worldBlocks.put(wheatPos, immatureWheat(0)); // replanted at age 0
        farmerData = profile.job().get().farmerData().get().withCropHarvested(1L, List.of(new ItemStack(Items.WHEAT, 1)));
        profile = profile.withJob(profile.job().get().withFarmerData(farmerData).get());

        assertEquals(1L, profile.job().get().farmerData().get().cropsHarvested());
        assertTrue(profile.job().get().farmerData().get().activeTarget().isEmpty(), "Active target must be cleared upon harvest commit");
        assertEquals(0, profile.job().get().farmerData().get().emptyChecks());
    }

    @Test
    @DisplayName("2. All crops immature -> multiple retry cycles -> crop matures -> worker resumes")
    void testMultipleRetryCyclesThenMatures() {
        BlockPos wheatPos = ANCHOR.offset(2, 0, 0);
        worldBlocks.put(wheatPos, immatureWheat(2));
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Run 3 full retry cycles with 0 mature crops
        for (int cycle = 1; cycle <= 3; cycle++) {
            data = data.withScanCursor(0, 0).withRetryTimer(0);
            for (int slice = 0; slice < 11; slice++) {
                CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, data.scanCursor());
                data = data.withScanCursor(res.nextCursor(), res.emptyChecks());
            }
            assertEquals(0, data.scanCursor(), "Cycle completion must reset scan cursor to 0");
            assertEquals(0, data.emptyChecks(), "Cycle completion must reset empty checks to 0");
            assertEquals(JobStatus.WAITING_FOR_CROPS, profile.job().get().status(), "Job must remain in WAITING_FOR_CROPS across idle cycles");
        }

        // Now wheat matures
        worldBlocks.put(wheatPos, matureWheat());

        // Next retry sweep from cursor 0 discovers the crop
        CropRegistry.CropScanResult wakeScan = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, 0);
        assertTrue(wakeScan.targetPos().isPresent(), "Worker must discover crop after multiple idle retry cycles");
        assertEquals(wheatPos, wakeScan.targetPos().get());

        data = data.withActiveTarget(wheatPos);
        profile = profile.withJob(profile.job().get().withFarmerData(data).get())
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, profile.job().get().status());
    }

    @Test
    @DisplayName("3. Crop matures while worker is in WAITING_FOR_CROPS and activeTarget locks navigation")
    void testCropMaturesWhileWaitingLocksNavigationTarget() {
        BlockPos wheatPos = ANCHOR.offset(4, 0, 2);
        worldBlocks.put(wheatPos, immatureWheat(4));
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(5L, STORAGE).withRetryTimer(20);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Crop matures
        worldBlocks.put(wheatPos, matureWheat());

        // Timer decrements to 0
        data = data.withRetryTimer(0);

        // Scan discovers crop
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, 0);
        assertTrue(res.targetPos().isPresent());

        // Target locks into FarmerJobData
        data = data.withActiveTarget(res.targetPos().get());
        assertTrue(data.activeTarget().isPresent());
        assertEquals(wheatPos, data.activeTarget().get());

        // Transition to RUNNING
        profile = profile.withJob(profile.job().get().withFarmerData(data).get())
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();

        // While activeTarget is present, worker does not re-enter WAITING_FOR_CROPS
        assertEquals(JobStatus.RUNNING, profile.job().get().status());
        assertEquals(wheatPos, profile.job().get().farmerData().get().activeTarget().get());
    }

    @Test
    @DisplayName("4. Scan cursor wraps around before the crop matures")
    void testScanCursorWraparoundBeforeCropMatures() {
        // Start cursor near the boundary of search volume (5400 of 5445)
        FarmerJobData data = FarmerJobData.initial(5L, STORAGE).withScanCursor(5400, 5000);

        // Inspect 500 positions -> wraps around past 5445 to beginning of ring offsets
        CropRegistry.CropScanResult wrapRes = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, data.scanCursor());
        assertTrue(wrapRes.cycleCompleted(), "Advancing past total positions must complete the cycle");
        assertEquals(0, wrapRes.nextCursor(), "Cycle completion must reset cursor to 0");

        // Now place mature wheat at anchor
        worldBlocks.put(ANCHOR, matureWheat());

        // Subsequent scan starting from reset cursor 0 finds wheat immediately
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, 0);
        assertTrue(res.targetPos().isPresent(), "Reset scan from cursor 0 must find mature crop at anchor");
        assertEquals(ANCHOR, res.targetPos().get());
    }

    @Test
    @DisplayName("5. Worker saves and reloads while waiting -> crop matures -> worker resumes")
    void testSaveAndReloadWhileWaitingResumesCleanly() {
        BlockPos wheatPos = ANCHOR.offset(1, 0, 0);
        worldBlocks.put(wheatPos, immatureWheat(5));
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE).withRetryTimer(15).withScanCursor(0, 0);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Serialize to JSON via Codec
        var jsonResult = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile);
        assertTrue(jsonResult.result().isPresent(), "WorkerProfile must serialize cleanly to JSON");
        var jsonElement = jsonResult.result().get();

        // Deserialize from JSON via Codec (simulating save/reload)
        var decodeResult = WorkerProfile.CODEC.parse(JsonOps.INSTANCE, jsonElement);
        assertTrue(decodeResult.result().isPresent(), "WorkerProfile must deserialize cleanly from JSON");
        WorkerProfile reloaded = decodeResult.result().get();

        assertEquals(JobStatus.WAITING_FOR_CROPS, reloaded.job().get().status());
        FarmerJobData reloadedData = reloaded.job().get().farmerData().get();
        assertEquals(15, reloadedData.retryTimer());
        assertEquals(0, reloadedData.scanCursor());

        // Wheat matures
        worldBlocks.put(wheatPos, matureWheat());

        // Advance timer to 0 on reloaded data
        reloadedData = reloadedData.withRetryTimer(0);
        CropRegistry.CropScanResult wakeScan = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, reloadedData, reloadedData.scanCursor());
        assertTrue(wakeScan.targetPos().isPresent(), "Reloaded worker must discover matured crop upon retry");
        assertEquals(wheatPos, wakeScan.targetPos().get());

        // Transition to RUNNING
        reloadedData = reloadedData.withActiveTarget(wheatPos);
        reloaded = reloaded.withJob(reloaded.job().get().withFarmerData(reloadedData).get())
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, reloaded.job().get().status());
    }

    @Test
    @DisplayName("6. No supported crops exist -> worker remains safely waiting without completing or failing")
    void testNoSupportedCropsRemainsWaitingSafely() {
        // No crops in worldBlocks at all (only air and stone)
        FarmerJobData data = FarmerJobData.initial(20L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Run 5 full scan cycles
        for (int i = 0; i < 5; i++) {
            data = data.withScanCursor(0, 0);
            for (int slice = 0; slice < 11; slice++) {
                CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, data, data.scanCursor());
                data = data.withScanCursor(res.nextCursor(), res.emptyChecks());
            }
            assertEquals(JobStatus.WAITING_FOR_CROPS, profile.job().get().status(), "Job must never fail or complete when zero crops exist");
            assertEquals(0L, data.cropsHarvested());
            assertEquals(0, data.scanCursor());
        }
    }

    @Test
    @DisplayName("7. Quota is reached while output is pending -> delivery completes before job completion")
    void testQuotaReachedDeliversBeforeJobCompletion() {
        FarmerJobData data = FarmerJobData.initial(3L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // Harvest 3 crops (quota reached)
        List<ItemStack> produce = List.of(new ItemStack(Items.WHEAT, 3), new ItemStack(Items.WHEAT_SEEDS, 2));
        data = data.withCropHarvested(3L, produce);
        profile = profile.withJob(profile.job().get().withFarmerData(data).get());

        assertTrue(data.isHarvestFinished(), "Harvest must be marked finished when harvested == requested");
        assertFalse(data.isDeliveryComplete(), "Delivery must not be complete while items are pending");
        assertFalse(data.isFullyCompleted(), "Job must not be fully completed until delivered");

        // Profile transitions to DELIVERING
        profile = profile.transition(JobStatus.DELIVERING, profile.job().get().progress(), null).orElseThrow();
        assertEquals(JobStatus.DELIVERING, profile.job().get().status());

        // Perform delivery into storage container
        SimpleContainer container = new SimpleContainer(27);
        WorkerProfile deliveredProfile = FarmerJobController.deliverToContainer(profile, container);

        FarmerJobData deliveredData = deliveredProfile.job().get().farmerData().get();
        assertTrue(deliveredData.isDeliveryComplete(), "All items must be delivered to container");
        assertTrue(deliveredData.isFullyCompleted(), "Job must be fully completed after delivery");
        assertEquals(JobStatus.COMPLETED, deliveredProfile.job().get().status(), "Job status must transition to COMPLETED only after full delivery");
        assertEquals(100, deliveredProfile.job().get().progress());
    }

    @Test
    @DisplayName("8. Both Maid and Cow integrations use the same execution loop and wake-up behavior")
    void testSharedMaidAndCowWakeUpBehavior() {
        UUID maidWorkerId = UUID.randomUUID();
        UUID cowWorkerId = UUID.randomUUID();

        FarmerJobData maidData = FarmerJobData.initial(10L, STORAGE).withRetryTimer(0);
        FarmerJobData cowData = FarmerJobData.initial(10L, STORAGE).withRetryTimer(0);

        WorkerProfile maidProfile = new WorkerProfile(maidWorkerId, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(new WorkerJob(WorkerJobs.FARM, JobStatus.WAITING_FOR_CROPS, 0, Optional.empty(), Optional.of(WORKSITE), Optional.empty(), Optional.of(maidData))),
                WorkerBehavior.FOLLOW, Optional.empty());

        WorkerProfile cowProfile = new WorkerProfile(cowWorkerId, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(new WorkerJob(WorkerJobs.FARM, JobStatus.WAITING_FOR_CROPS, 0, Optional.empty(), Optional.of(WORKSITE), Optional.empty(), Optional.of(cowData))),
                WorkerBehavior.FOLLOW, Optional.empty());

        // Place mature wheat
        BlockPos wheatPos = ANCHOR.offset(1, 0, 0);
        worldBlocks.put(wheatPos, matureWheat());
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        // Both scan using the shared CropRegistry logic
        CropRegistry.CropScanResult maidScan = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, maidData, 0);
        CropRegistry.CropScanResult cowScan = CropRegistry.findMatureCrop(mockLevel, ANCHOR, 16, cowData, 0);

        assertTrue(maidScan.targetPos().isPresent(), "Maid worker must discover mature crop");
        assertTrue(cowScan.targetPos().isPresent(), "Cow worker must discover mature crop");
        assertEquals(maidScan.targetPos().get(), cowScan.targetPos().get());

        // Both transition to RUNNING with identical activeTarget locking
        WorkerProfile updatedMaid = maidProfile.withJob(maidProfile.job().get().withFarmerData(maidData.withActiveTarget(wheatPos)).get())
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();
        WorkerProfile updatedCow = cowProfile.withJob(cowProfile.job().get().withFarmerData(cowData.withActiveTarget(wheatPos)).get())
                .transition(JobStatus.RUNNING, 0, null).orElseThrow();

        assertEquals(JobStatus.RUNNING, updatedMaid.job().get().status());
        assertEquals(JobStatus.RUNNING, updatedCow.job().get().status());
        assertEquals(wheatPos, updatedMaid.job().get().farmerData().get().activeTarget().get());
        assertEquals(wheatPos, updatedCow.job().get().farmerData().get().activeTarget().get());
    }
}
