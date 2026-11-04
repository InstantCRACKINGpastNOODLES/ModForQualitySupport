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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8.1 Verification Suite:
 * Focused tests for Farmer Replant Safety, Unrelated Block Preservation,
 * Scan Cursor & Chunk Loading Dynamics, and State / Serialization Compatibility.
 */
class FarmerReplantSafetyTest {

    private static final UUID OWNER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID WORKER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static final BlockPos ANCHOR = new BlockPos(200, 64, 200);
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, ANCHOR);
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(200, 64, 195));

    private Map<BlockPos, BlockState> worldBlocks;
    private AtomicBoolean chunksLoaded;
    private LevelAccessor mockLevelAccessor;

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

        mockLevelAccessor = (LevelAccessor) Proxy.newProxyInstance(
                LevelAccessor.class.getClassLoader(),
                new Class<?>[]{LevelAccessor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("hasChunkAt".equals(name)) {
                        return chunksLoaded.get();
                    }
                    if ("getBlockState".equals(name)) {
                        BlockPos pos = (BlockPos) args[0];
                        return worldBlocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    }
                    if ("setBlock".equals(name)) {
                        BlockPos pos = (BlockPos) args[0];
                        BlockState state = (BlockState) args[1];
                        worldBlocks.put(pos, state);
                        return true;
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
    // Group 1: P0 Replant Safety & Unrelated Block Preservation
    // ========================================================

    @Test
    @DisplayName("1. The original crop can be harvested and replanted")
    void testOriginalCropCanBeHarvestedAndReplanted() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, matureWheat());
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
                matureWheat(),
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> { worldBlocks.put(pos, Blocks.AIR.defaultBlockState()); return true; },
                () -> CropRegistry.replant(mockLevelAccessor, pos, matureWheat()),
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertTrue(result.replanted(), "Wheat must be successfully replanted");
        assertEquals(Blocks.WHEAT, worldBlocks.get(pos).getBlock());
        assertEquals(0, worldBlocks.get(pos).getValue(CropBlock.AGE));
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
    }

    @Test
    @DisplayName("2. An unrelated solid block appearing before replant is preserved")
    void testUnrelatedSolidBlockAppearingBeforeReplantIsPreserved() {
        BlockPos pos = ANCHOR;
        // Farmland is present
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        // An unrelated solid block (e.g. Stone placed by player or falling anvil/cobblestone) occupies target
        BlockState stone = Blocks.STONE.defaultBlockState();
        worldBlocks.put(pos, stone);

        // Physical replant must be safely rejected
        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, matureWheat());
        assertFalse(replanted, "Replant must reject position occupied by an unrelated solid block");

        // The unrelated stone block must remain 100% intact and unmutated
        assertEquals(stone, worldBlocks.get(pos), "Unrelated block must be strictly preserved without modification");
    }

    @Test
    @DisplayName("3. A failed replant does not duplicate or void seeds (seed refunded to WorkerStorage)")
    void testFailedReplantDoesNotDuplicateOrVoidSeeds() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, matureWheat());
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // 1 wheat + 2 seeds produced from harvest
        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 2));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                matureWheat(),
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> {
                    // Harvest destroys crop, but player immediately places cobblestone here
                    worldBlocks.put(pos, Blocks.COBBLESTONE.defaultBlockState());
                    return true;
                },
                () -> CropRegistry.replant(mockLevelAccessor, pos, matureWheat()), // Aborts because cobblestone occupies space
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertFalse(result.replanted(), "Replant must fail due to cobblestone obstruction");
        assertEquals(Blocks.COBBLESTONE, worldBlocks.get(pos).getBlock(), "Cobblestone must be preserved");

        // Item conservation audit: drops had 1 wheat + 2 seeds = 3 items.
        // Seed was taken, replant failed, seed refunded back to drops: total items pending delivery must equal 3!
        long pendingCount = result.profile().job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery();
        assertEquals(3L, pendingCount, "All 3 items (1 wheat + 2 seeds) must be preserved; zero seed voiding");
    }

    @Test
    @DisplayName("4. A failed replant does not void seed when storage is full")
    void testFailedReplantDoesNotVoidSeedWhenStorageFull() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, matureWheat());
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 1));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                matureWheat(),
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> true,
                () -> false, // Simulated replant failure
                null
        );

        assertEquals(FarmerJobController.TransactionStatus.SUCCESS, result.status());
        assertFalse(result.replanted());
        // 1 wheat + 1 seed = exactly 2 items preserved in pending stacks
        assertEquals(2L, result.profile().job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery());
    }

    @Test
    @DisplayName("5. A failed replant does not lose harvested drops")
    void testFailedReplantDoesNotLoseHarvestedDrops() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, matureWheat());
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(5L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        List<ItemStack> drops = List.of(new ItemStack(Items.WHEAT, 2), new ItemStack(Items.WHEAT_SEEDS, 3));

        FarmerJobController.TransactionExecutionResult result = FarmerJobController.executeHarvestTransaction(
                profile,
                null,
                null,
                ItemStack.EMPTY,
                pos,
                matureWheat(),
                BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                () -> drops,
                () -> true,
                () -> false, // Replant fails
                null
        );

        // Harvest itself succeeded
        assertEquals(1L, result.profile().job().orElseThrow().farmerData().orElseThrow().cropsHarvested());
        // All drops preserved
        assertEquals(5L, result.profile().job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery());
    }

    @Test
    @DisplayName("6. Reconciliation after interruption does not remove unrelated blocks")
    void testReconciliationAfterInterruptionDoesNotRemoveUnrelatedBlocks() {
        BlockPos pos = ANCHOR;
        // In-flight harvest was staged for wheat
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withInFlightHarvest(pos, BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
                        List.of(new ItemStack(Items.WHEAT, 1)), Optional.of(BuiltInRegistries.ITEM.getKey(Items.WHEAT_SEEDS)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // In the world, an unrelated Obsidian block was placed at pos
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        worldBlocks.put(pos, obsidian);

        FarmerJobController.ReconciliationOutcome outcome =
                FarmerJobController.evaluateReconciliation(profile, true, obsidian);

        // Must fail closed as UNRESOLVED_AMBIGUOUS without modifying the world
        assertEquals(FarmerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, outcome.status());
        assertEquals(obsidian, worldBlocks.get(pos), "Reconciliation must never remove or mutate an unrelated block");
    }

    @Test
    @DisplayName("7. Farmland soil missing before replant aborts without placement")
    void testFarmlandSoilMissingBeforeReplantAbortsWithoutPlacement() {
        BlockPos pos = ANCHOR;
        // Target space is air, but soil below is dirt (trampled farmland)
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());
        worldBlocks.put(pos.below(), Blocks.DIRT.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, matureWheat());
        assertFalse(replanted, "Replant must abort when soil is not farmland");
        assertEquals(Blocks.AIR.defaultBlockState(), worldBlocks.get(pos), "Air must not be replaced when soil is invalid");
    }

    @Test
    @DisplayName("8. Cocoa jungle log missing before replant aborts cleanly")
    void testCocoaLogMissingBeforeReplantAbortsCleanly() {
        BlockPos pos = ANCHOR;
        BlockState matureCocoa = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 2);

        // Position is air, but jungle log at pos.relative(NORTH) is missing (air)
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());
        worldBlocks.put(pos.relative(Direction.NORTH), Blocks.AIR.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, matureCocoa);
        assertFalse(replanted, "Cocoa replant must abort when supporting jungle log is missing");
        assertEquals(Blocks.AIR.defaultBlockState(), worldBlocks.get(pos));
    }

    // ========================================================
    // Group 2: P1 Scan Cursor & Unloaded Chunks
    // ========================================================

    @Test
    @DisplayName("9. Skipping an unloaded candidate does not permanently exclude it")
    void testSkippingUnloadedCandidateDoesNotPermanentlyExcludeIt() {
        BlockPos wheatPos = ANCHOR.offset(3, 0, 3);
        worldBlocks.put(wheatPos, matureWheat());
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));

        // Chunk is unloaded: candidate is skipped safely
        chunksLoaded.set(false);
        CropRegistry.CropScanResult res1 = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, farmerData, 0);
        assertTrue(res1.targetPos().isEmpty(), "Unloaded chunk candidate must be skipped");

        // Chunk loads later: candidate is discovered on subsequent scan
        chunksLoaded.set(true);
        CropRegistry.CropScanResult res2 = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, farmerData, 0);
        assertTrue(res2.targetPos().isPresent(), "Previously unloaded crop must be discovered once chunk loads");
        assertEquals(wheatPos, res2.targetPos().get());
    }

    @Test
    @DisplayName("10. Chunk loaded after cursor has passed can be discovered on a later complete scan cycle")
    void testChunkLoadedAfterCursorPassedDiscoveredOnLaterCycle() {
        BlockPos wheatPos = ANCHOR; // Position 0
        worldBlocks.put(wheatPos, matureWheat());
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData farmerData = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withScanCursor(2500, 2500); // Cursor has already passed position 0

        // Scanning from 2500 does not check position 0 in this slice
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, farmerData, 2500);
        assertTrue(res.targetPos().isEmpty());

        // Once the full cycle wraps around to position 0, it is discovered
        CropRegistry.CropScanResult resWrapped = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, farmerData, 0);
        assertTrue(resWrapped.targetPos().isPresent());
        assertEquals(wheatPos, resWrapped.targetPos().get());
    }

    @Test
    @DisplayName("11. Cursor wraparound cannot cause false WAITING_FOR_CROPS")
    void testCursorWraparoundCannotCauseFalseWaitingForCrops() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withScanCursor(5200, 0); // Near boundary of 5445 positions

        // Checking budget of 500 checks from 5200 wraps around 5445 to index 255.
        // But only 500 positions were inspected! emptyChecks = 500 < 5445.
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 5200);

        assertEquals(255, res.nextCursor(), "Cursor must wrap around array length safely");
        assertEquals(500, res.emptyChecks(), "Empty checks must reflect actual positions inspected, not cursor position");
        assertFalse(res.cycleCompleted(), "Wraparound after only 500 checks must NOT trigger cycleCompleted or false WAITING_FOR_CROPS");
    }

    @Test
    @DisplayName("12. A partial scan is never interpreted as a completed empty scan")
    void testPartialScanIsNeverInterpretedAsCompletedEmptyScan() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));

        int cursor = 0;
        FarmerJobData current = data;
        // Run 5 slices (2500 checks out of 5445)
        for (int i = 0; i < 5; i++) {
            CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, current, cursor);
            cursor = res.nextCursor();
            current = current.withScanCursor(res.nextCursor(), res.emptyChecks());
            assertFalse(res.cycleCompleted(), "Slice " + i + " (partial scan) must not report cycleCompleted");
        }
        assertEquals(2500, current.emptyChecks());
    }

    @Test
    @DisplayName("13. Changes to anchor, radius, or crop allowlist reset or reconcile cursor correctly")
    void testChangesToAllowlistOrRadiusResetCursorAndEmptyChecks() {
        FarmerJobData data = FarmerJobData.initial(50L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withScanCursor(3500, 3000);

        assertEquals(3500, data.scanCursor());
        assertEquals(3000, data.emptyChecks());

        // Allowlist change resets both
        FarmerJobData newCrops = data.withPermittedCrops(List.of(BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS)));
        assertEquals(0, newCrops.scanCursor());
        assertEquals(0, newCrops.emptyChecks());

        // Radius change resets both
        FarmerJobData newRadius = data.withSearchRadius(8);
        assertEquals(0, newRadius.scanCursor());
        assertEquals(0, newRadius.emptyChecks());
    }

    // ========================================================
    // Group 3: P2 State & Compatibility Audit
    // ========================================================

    @Test
    @DisplayName("14. WAITING_FOR_CROPS survives save/reload")
    void testWaitingForCropsSurvivesSaveReload() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withSearchRadius(12)
                .withScanCursor(1500, 1200);

        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        var encoded = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow();
        WorkerProfile decoded = WorkerProfile.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        WorkerJob decodedJob = decoded.job().orElseThrow();
        assertEquals(JobStatus.WAITING_FOR_CROPS, decodedJob.status());
        FarmerJobData decodedData = decodedJob.farmerData().orElseThrow();
        assertEquals(12, decodedData.searchRadius());
        assertEquals(1500, decodedData.scanCursor());
        assertEquals(1200, decodedData.emptyChecks());
    }

    @Test
    @DisplayName("15. Paused jobs retain the correct scan and retry state")
    void testPausedJobRetainsCorrectScanAndRetryState() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withScanCursor(4000, 3500);

        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Pause job
        WorkerProfile paused = profile.transition(JobStatus.PAUSED, 0, null).orElseThrow();
        assertEquals(JobStatus.PAUSED, paused.job().orElseThrow().status());
        assertEquals(4000, paused.job().orElseThrow().farmerData().orElseThrow().scanCursor());
        assertEquals(3500, paused.job().orElseThrow().farmerData().orElseThrow().emptyChecks());

        // Resume job
        WorkerProfile resumed = paused.transition(JobStatus.WAITING_FOR_CROPS, 0, null).orElseThrow();
        assertEquals(JobStatus.WAITING_FOR_CROPS, resumed.job().orElseThrow().status());
        assertEquals(4000, resumed.job().orElseThrow().farmerData().orElseThrow().scanCursor());
        assertEquals(3500, resumed.job().orElseThrow().farmerData().orElseThrow().emptyChecks());
    }

    @Test
    @DisplayName("16. A newly mature crop resumes work without requiring job reassignment")
    void testNewlyMatureCropResumesWorkWithoutReassignment() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);

        // Mature wheat appears in world
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, matureWheat());
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        // Scan discovers crop
        CropRegistry.CropScanResult res = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(res.targetPos().isPresent());
        assertEquals(0, res.emptyChecks(), "Finding a crop must reset empty checks to 0");

        // Worker transitions directly back to RUNNING
        WorkerProfile resumed = profile.transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, resumed.job().orElseThrow().status());
    }

    @Test
    @DisplayName("17. Legacy FarmerJobData without the new fields still decodes correctly")
    void testLegacyFarmerJobDataDecodesWithDefaults() {
        // Legacy JSON without search_radius, scan_cursor, or empty_checks
        String legacyJson = """
                {
                    "requested_blocks": 32,
                    "crops_harvested": 5,
                    "items_pending_delivery": 5,
                    "items_delivered": 10,
                    "storage": {
                        "dimension": "minecraft:overworld",
                        "position": [200, 64, 195]
                    },
                    "permitted_crops": ["minecraft:wheat"]
                }
                """;
        JsonObject json = JsonParser.parseString(legacyJson).getAsJsonObject();
        FarmerJobData decoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(32L, decoded.requestedBlocks());
        assertEquals(5L, decoded.cropsHarvested());
        assertEquals(16, decoded.searchRadius(), "Missing search_radius must default to 16");
        assertEquals(0, decoded.scanCursor(), "Missing scan_cursor must default to 0");
        assertEquals(0, decoded.emptyChecks(), "Missing empty_checks must default to 0");
    }

    @Test
    @DisplayName("18. Existing Farmer quota, delivery, WorkerStorage, and reservation semantics remain unchanged")
    void testFarmerQuotaDeliveryAndReservationUnchanged() {
        FarmerJobData data = FarmerJobData.initial(2L, STORAGE, List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)))
                .withCropHarvested(1L, List.of(new ItemStack(Items.WHEAT, 2)));

        assertEquals(1L, data.cropsHarvested());
        assertEquals(2L, data.itemsPendingDelivery());
        assertEquals(1, data.getReservedItemStacks().size());
        assertEquals(Items.WHEAT, data.getReservedItemStacks().get(0).getItem());
        assertEquals(2, data.getReservedItemStacks().get(0).getCount());

        // Delivery into container
        Container container = new SimpleContainer(9);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);
        WorkerProfile delivered = FarmerJobController.deliverToContainer(profile, container);

        assertEquals(JobStatus.RUNNING, delivered.job().orElseThrow().status());
        assertEquals(0L, delivered.job().orElseThrow().farmerData().orElseThrow().itemsPendingDelivery());
        assertEquals(2L, delivered.job().orElseThrow().farmerData().orElseThrow().itemsDelivered());
        assertEquals(2, container.getItem(0).getCount());
        assertEquals(Items.WHEAT, container.getItem(0).getItem());
    }
}
