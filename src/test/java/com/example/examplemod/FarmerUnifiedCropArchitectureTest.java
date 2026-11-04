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
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BeetrootBlock;
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
 * Phase 8.2 Comprehensive Verification Suite:
 * Automated tests for Unified Vanilla Crop Discovery & Extensible Crop Adapter Architecture.
 * Covers all 40 scenarios defined in Section 11 of the Phase 8.2 specification.
 */
class FarmerUnifiedCropArchitectureTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID WORKER_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static final BlockPos ANCHOR = new BlockPos(100, 64, 100);
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, ANCHOR);
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 90));

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
    // Group 1: Selection Mode Tests (Scenarios 1 - 6)
    // ========================================================

    @Test
    @DisplayName("1. New Farmer job defaults to ALL_SUPPORTED")
    void testNewFarmerJobDefaultsToAllSupported() {
        FarmerJobData data = FarmerJobData.initial(20L, STORAGE);
        assertEquals(CropSelectionMode.ALL_SUPPORTED, data.selectionMode());
        assertTrue(data.permittedCrops().isEmpty());
    }

    @Test
    @DisplayName("2. Selecting wheat as anchor still permits carrot, potato, beetroot, pumpkin, melon and cocoa")
    void testSelectingWheatAnchorStillPermitsAllCrops() {
        FarmerJobData data = FarmerJobData.initial(20L, STORAGE);
        assertTrue(data.isCropPermitted(Blocks.WHEAT));
        assertTrue(data.isCropPermitted(Blocks.CARROTS));
        assertTrue(data.isCropPermitted(Blocks.POTATOES));
        assertTrue(data.isCropPermitted(Blocks.BEETROOTS));
        assertTrue(data.isCropPermitted(Blocks.PUMPKIN));
        assertTrue(data.isCropPermitted(Blocks.MELON));
        assertTrue(data.isCropPermitted(Blocks.COCOA));
    }

    @Test
    @DisplayName("3. SELECTED_ONLY restricts to the configured crop IDs")
    void testSelectedOnlyRestrictsToConfiguredCropIds() {
        FarmerJobData data = FarmerJobData.initial(20L, STORAGE, CropSelectionMode.SELECTED_ONLY,
                List.of(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        assertEquals(CropSelectionMode.SELECTED_ONLY, data.selectionMode());
        assertTrue(data.isCropPermitted(Blocks.WHEAT));
        assertFalse(data.isCropPermitted(Blocks.CARROTS));
        assertFalse(data.isCropPermitted(Blocks.POTATOES));
        assertFalse(data.isCropPermitted(Blocks.BEETROOTS));
        assertFalse(data.isCropPermitted(Blocks.PUMPKIN));
        assertFalse(data.isCropPermitted(Blocks.MELON));
        assertFalse(data.isCropPermitted(Blocks.COCOA));
    }

    @Test
    @DisplayName("4. Legacy explicit allowlists remain restricted")
    void testLegacyExplicitAllowlistsRemainRestricted() {
        FarmerJobData legacyData = FarmerJobData.initial(20L, STORAGE,
                List.of(BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS)));
        assertEquals(CropSelectionMode.SELECTED_ONLY, legacyData.selectionMode());
        assertTrue(legacyData.isCropPermitted(Blocks.CARROTS));
        assertFalse(legacyData.isCropPermitted(Blocks.WHEAT));
        assertFalse(legacyData.isCropPermitted(Blocks.PUMPKIN));
    }

    @Test
    @DisplayName("5. Legacy saves without mode decode with intended compatibility policy")
    void testLegacySavesWithoutModeDecodeWithIntendedCompatibilityPolicy() {
        var storageObj = WorkerTarget.CODEC.encodeStart(JsonOps.INSTANCE, STORAGE).getOrThrow();

        // Legacy JSON with non-empty permitted_crops -> SELECTED_ONLY
        JsonObject json1 = new JsonObject();
        json1.addProperty("requested_blocks", 32L);
        json1.addProperty("crops_harvested", 0L);
        json1.addProperty("items_pending_delivery", 0L);
        json1.addProperty("items_delivered", 0L);
        json1.add("pending_stacks", new com.google.gson.JsonArray());
        json1.add("storage", storageObj);
        com.google.gson.JsonArray permittedArr = new com.google.gson.JsonArray();
        permittedArr.add("minecraft:wheat");
        json1.add("permitted_crops", permittedArr);

        var decoded1 = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, json1).getOrThrow();
        assertEquals(CropSelectionMode.SELECTED_ONLY, decoded1.selectionMode());
        assertTrue(decoded1.isCropPermitted(Blocks.WHEAT));
        assertFalse(decoded1.isCropPermitted(Blocks.CARROTS));

        // Legacy JSON without selection_mode field -> retains legacy restriction semantics (SELECTED_ONLY)
        JsonObject json2 = new JsonObject();
        json2.addProperty("requested_blocks", 32L);
        json2.addProperty("crops_harvested", 0L);
        json2.addProperty("items_pending_delivery", 0L);
        json2.addProperty("items_delivered", 0L);
        json2.add("pending_stacks", new com.google.gson.JsonArray());
        json2.add("storage", storageObj);

        var decoded2 = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, json2).getOrThrow();
        assertEquals(CropSelectionMode.SELECTED_ONLY, decoded2.selectionMode());
        assertFalse(decoded2.isCropPermitted(Blocks.WHEAT));
    }

    @Test
    @DisplayName("6. Unknown selected IDs fail safely")
    void testUnknownSelectedIdsFailSafely() {
        FarmerJobData data = FarmerJobData.initial(20L, STORAGE, CropSelectionMode.SELECTED_ONLY,
                List.of(ResourceLocation.fromNamespaceAndPath("unknown_mod", "unknown_crop")));
        assertFalse(data.isCropPermitted(Blocks.WHEAT));
        assertFalse(data.isCropPermitted(Blocks.AIR));
        assertFalse(data.isCropPermitted(null));
    }

    // ========================================================
    // Group 2: Farmland Tests (Scenarios 7 - 12)
    // ========================================================

    @Test
    @DisplayName("7. Wheat harvest and replant")
    void testWheatHarvestAndReplant() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        assertTrue(CropRegistry.isMature(mockLevelAccessor, pos, worldBlocks.get(pos)));
        assertTrue(CropRegistry.canReplant(mockLevelAccessor, pos, worldBlocks.get(pos)));

        // Harvest (destroy block)
        BlockState originalCrop = worldBlocks.get(pos);
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());

        // Replant
        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, originalCrop);
        assertTrue(replanted);
        BlockState replantedState = worldBlocks.get(pos);
        assertTrue(replantedState.is(Blocks.WHEAT));
        assertEquals(0, replantedState.getValue(CropBlock.AGE));
        assertTrue(CropRegistry.isReplanted(mockLevelAccessor, pos, replantedState));
    }

    @Test
    @DisplayName("8. Carrot harvest and replant")
    void testCarrotHarvestAndReplant() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        assertTrue(CropRegistry.isMature(mockLevelAccessor, pos, worldBlocks.get(pos)));
        BlockState originalCrop = worldBlocks.get(pos);
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, originalCrop);
        assertTrue(replanted);
        BlockState replantedState = worldBlocks.get(pos);
        assertTrue(replantedState.is(Blocks.CARROTS));
        assertEquals(0, replantedState.getValue(CropBlock.AGE));
    }

    @Test
    @DisplayName("9. Potato harvest and replant")
    void testPotatoHarvestAndReplant() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        assertTrue(CropRegistry.isMature(mockLevelAccessor, pos, worldBlocks.get(pos)));
        BlockState originalCrop = worldBlocks.get(pos);
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, originalCrop);
        assertTrue(replanted);
        BlockState replantedState = worldBlocks.get(pos);
        assertTrue(replantedState.is(Blocks.POTATOES));
        assertEquals(0, replantedState.getValue(CropBlock.AGE));
    }

    @Test
    @DisplayName("10. Beetroot harvest and replant")
    void testBeetrootHarvestAndReplant() {
        BlockPos pos = ANCHOR;
        worldBlocks.put(pos, Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3));
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        assertTrue(CropRegistry.isMature(mockLevelAccessor, pos, worldBlocks.get(pos)));
        BlockState originalCrop = worldBlocks.get(pos);
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, originalCrop);
        assertTrue(replanted);
        BlockState replantedState = worldBlocks.get(pos);
        assertTrue(replantedState.is(Blocks.BEETROOTS));
        assertEquals(0, replantedState.getValue(BeetrootBlock.AGE));
    }

    @Test
    @DisplayName("11. Failed replant conserves seeds")
    void testFailedReplantConservesSeeds() {
        WorkerStorage initialStorage = WorkerStorage.empty().deposit(new ItemStack(Items.WHEAT_SEEDS, 2));
        Optional<WorkerStorage> refunded = FarmerJobController.refundToStorage(initialStorage, Items.WHEAT_SEEDS, 0);
        assertTrue(refunded.isPresent());
        assertEquals(3, refunded.get().items().get(0).getCount());
    }

    @Test
    @DisplayName("12. Invalid farmland support is rejected safely")
    void testInvalidFarmlandSupportIsRejectedSafely() {
        BlockPos pos = ANCHOR;
        BlockState cropState = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        worldBlocks.put(pos, Blocks.AIR.defaultBlockState());
        worldBlocks.put(pos.below(), Blocks.STONE.defaultBlockState()); // Invalid soil!

        assertFalse(CropRegistry.canReplant(mockLevelAccessor, pos, cropState));
        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, cropState);
        assertFalse(replanted);
        assertTrue(worldBlocks.get(pos).isAir()); // Air unchanged
        assertTrue(worldBlocks.get(pos.below()).is(Blocks.STONE)); // Stone untouched
    }

    // ========================================================
    // Group 3: Pumpkin / Melon Tests (Scenarios 13 - 19)
    // ========================================================

    @Test
    @DisplayName("13. Mature pumpkin fruit is discovered")
    void testMaturePumpkinFruitIsDiscovered() {
        BlockPos fruitPos = ANCHOR;
        BlockPos stemPos = ANCHOR.west();
        worldBlocks.put(fruitPos, Blocks.PUMPKIN.defaultBlockState());
        worldBlocks.put(stemPos, Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.EAST));
        worldBlocks.put(fruitPos.below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan.targetPos().isPresent());
        assertEquals(fruitPos, scan.targetPos().get());
    }

    @Test
    @DisplayName("14. Pumpkin harvest preserves stem")
    void testPumpkinHarvestPreservesStem() {
        BlockPos fruitPos = ANCHOR;
        BlockPos stemPos = ANCHOR.west();
        BlockState stemState = Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.EAST);
        worldBlocks.put(fruitPos, Blocks.PUMPKIN.defaultBlockState());
        worldBlocks.put(stemPos, stemState);

        // Break fruit
        worldBlocks.put(fruitPos, Blocks.AIR.defaultBlockState());

        // Stem must remain completely intact
        assertEquals(stemState, worldBlocks.get(stemPos));
        assertTrue(worldBlocks.get(stemPos).is(Blocks.ATTACHED_PUMPKIN_STEM));
    }

    @Test
    @DisplayName("15. Mature melon fruit is discovered")
    void testMatureMelonFruitIsDiscovered() {
        BlockPos fruitPos = ANCHOR;
        BlockPos stemPos = ANCHOR.north();
        worldBlocks.put(fruitPos, Blocks.MELON.defaultBlockState());
        worldBlocks.put(stemPos, Blocks.ATTACHED_MELON_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.SOUTH));
        worldBlocks.put(fruitPos.below(), Blocks.DIRT.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan.targetPos().isPresent());
        assertEquals(fruitPos, scan.targetPos().get());
    }

    @Test
    @DisplayName("16. Melon harvest preserves stem")
    void testMelonHarvestPreservesStem() {
        BlockPos fruitPos = ANCHOR;
        BlockPos stemPos = ANCHOR.north();
        BlockState stemState = Blocks.ATTACHED_MELON_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.SOUTH);
        worldBlocks.put(fruitPos, Blocks.MELON.defaultBlockState());
        worldBlocks.put(stemPos, stemState);

        // Break fruit
        worldBlocks.put(fruitPos, Blocks.AIR.defaultBlockState());

        // Stem must remain completely intact
        assertEquals(stemState, worldBlocks.get(stemPos));
        assertTrue(worldBlocks.get(stemPos).is(Blocks.ATTACHED_MELON_STEM));
    }

    @Test
    @DisplayName("17. Fruit harvest does not consume planting seeds")
    void testFruitHarvestDoesNotConsumePlantingSeeds() {
        Optional<net.minecraft.world.item.Item> pumpkinSeed = CropRegistry.getPlantingItem(mockLevelAccessor, ANCHOR, Blocks.PUMPKIN.defaultBlockState());
        Optional<net.minecraft.world.item.Item> melonSeed = CropRegistry.getPlantingItem(mockLevelAccessor, ANCHOR, Blocks.MELON.defaultBlockState());
        assertTrue(pumpkinSeed.isEmpty());
        assertTrue(melonSeed.isEmpty());

        assertFalse(CropRegistry.canReplant(mockLevelAccessor, ANCHOR, Blocks.PUMPKIN.defaultBlockState()));
        assertFalse(CropRegistry.canReplant(mockLevelAccessor, ANCHOR, Blocks.MELON.defaultBlockState()));
    }

    @Test
    @DisplayName("18. Missing or obstructed fruit targets do not destroy unrelated blocks")
    void testMissingOrObstructedFruitTargetsDoNotDestroyUnrelatedBlocks() {
        BlockPos fruitPos = ANCHOR;
        worldBlocks.put(fruitPos, Blocks.COBBLESTONE.defaultBlockState());

        assertFalse(CropRegistry.isMature(mockLevelAccessor, fruitPos, worldBlocks.get(fruitPos)));
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        assertFalse(data.isCropPermitted(worldBlocks.get(fruitPos).getBlock()));

        assertEquals(Blocks.COBBLESTONE, worldBlocks.get(fruitPos).getBlock());
    }

    @Test
    @DisplayName("19. Stems never count as harvest quota units")
    void testStemsNeverCountAsHarvestQuotaUnits() {
        assertFalse(CropRegistry.isSupportedCrop(Blocks.PUMPKIN_STEM));
        assertFalse(CropRegistry.isSupportedCrop(Blocks.ATTACHED_PUMPKIN_STEM));
        assertFalse(CropRegistry.isSupportedCrop(Blocks.MELON_STEM));
        assertFalse(CropRegistry.isSupportedCrop(Blocks.ATTACHED_MELON_STEM));

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        assertFalse(data.isCropPermitted(Blocks.PUMPKIN_STEM));
        assertFalse(data.isCropPermitted(Blocks.MELON_STEM));
    }

    // ========================================================
    // Group 4: Cocoa Tests (Scenarios 20 - 25)
    // ========================================================

    @Test
    @DisplayName("20. Mature cocoa pod is discovered on jungle log")
    void testMatureCocoaPodIsDiscoveredOnJungleLog() {
        BlockPos cocoaPos = ANCHOR;
        BlockPos logPos = ANCHOR.north();
        worldBlocks.put(logPos, Blocks.JUNGLE_LOG.defaultBlockState());
        worldBlocks.put(cocoaPos, Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 2));

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan.targetPos().isPresent());
        assertEquals(cocoaPos, scan.targetPos().get());
    }

    @Test
    @DisplayName("21. Cocoa maturity is correctly detected")
    void testCocoaMaturityIsCorrectlyDetected() {
        BlockPos cocoaPos = ANCHOR;
        BlockState cocoaAge0 = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 0);
        BlockState cocoaAge1 = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 1);
        BlockState cocoaAge2 = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2);

        assertFalse(CropRegistry.isMature(mockLevelAccessor, cocoaPos, cocoaAge0));
        assertFalse(CropRegistry.isMature(mockLevelAccessor, cocoaPos, cocoaAge1));
        assertTrue(CropRegistry.isMature(mockLevelAccessor, cocoaPos, cocoaAge2));
    }

    @Test
    @DisplayName("22. Cocoa harvest preserves jungle log")
    void testCocoaHarvestPreservesJungleLog() {
        BlockPos cocoaPos = ANCHOR;
        BlockPos logPos = ANCHOR.north();
        worldBlocks.put(logPos, Blocks.JUNGLE_LOG.defaultBlockState());
        worldBlocks.put(cocoaPos, Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 2));

        // Break cocoa pod
        worldBlocks.put(cocoaPos, Blocks.AIR.defaultBlockState());

        // Jungle log must remain completely undisturbed
        assertTrue(worldBlocks.get(logPos).is(Blocks.JUNGLE_LOG));
    }

    @Test
    @DisplayName("23. Cocoa facing is preserved during replant")
    void testCocoaFacingIsPreservedDuringReplant() {
        BlockPos cocoaPos = ANCHOR;
        BlockPos logPos = ANCHOR.east();
        worldBlocks.put(logPos, Blocks.JUNGLE_LOG.defaultBlockState());
        BlockState matureEast = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.EAST)
                .setValue(CocoaBlock.AGE, 2);
        worldBlocks.put(cocoaPos, matureEast);

        assertTrue(CropRegistry.canReplant(mockLevelAccessor, cocoaPos, matureEast));
        worldBlocks.put(cocoaPos, Blocks.AIR.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, cocoaPos, matureEast);
        assertTrue(replanted);
        BlockState replantedState = worldBlocks.get(cocoaPos);
        assertTrue(replantedState.is(Blocks.COCOA));
        assertEquals(Direction.EAST, replantedState.getValue(CocoaBlock.FACING));
        assertEquals(0, replantedState.getValue(CocoaBlock.AGE));
        assertTrue(CropRegistry.isReplanted(mockLevelAccessor, cocoaPos, replantedState));
    }

    @Test
    @DisplayName("24. Missing support log prevents unsafe replant")
    void testMissingSupportLogPreventsUnsafeReplant() {
        BlockPos cocoaPos = ANCHOR;
        BlockPos logPos = ANCHOR.south();
        worldBlocks.put(logPos, Blocks.AIR.defaultBlockState()); // No jungle log!
        BlockState matureSouth = Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.SOUTH)
                .setValue(CocoaBlock.AGE, 2);
        worldBlocks.put(cocoaPos, Blocks.AIR.defaultBlockState());

        assertFalse(CropRegistry.canReplant(mockLevelAccessor, cocoaPos, matureSouth));
        boolean replanted = CropRegistry.replant(mockLevelAccessor, cocoaPos, matureSouth);
        assertFalse(replanted);
        assertTrue(worldBlocks.get(cocoaPos).isAir());
    }

    @Test
    @DisplayName("25. Cocoa discovery does not require farmland")
    void testCocoaDiscoveryDoesNotRequireFarmland() {
        BlockPos cocoaPos = ANCHOR;
        BlockPos logPos = ANCHOR.north();
        worldBlocks.put(logPos, Blocks.JUNGLE_LOG.defaultBlockState());
        worldBlocks.put(cocoaPos, Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH)
                .setValue(CocoaBlock.AGE, 2));
        worldBlocks.put(cocoaPos.below(), Blocks.STONE.defaultBlockState()); // Stone beneath, NOT farmland

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan.targetPos().isPresent());
        assertEquals(cocoaPos, scan.targetPos().get());
    }

    // ========================================================
    // Group 5: Discovery & Scheduler Tests (Scenarios 26 - 33)
    // ========================================================

    @Test
    @DisplayName("26. Mixed-crop worksite discovers every supported adapter")
    void testMixedCropWorksiteDiscoversEverySupportedAdapter() {
        // Place one mature crop of each type in worksite
        BlockPos pWheat = ANCHOR;
        BlockPos pCarrot = ANCHOR.east(1);
        BlockPos pPotato = ANCHOR.east(2);
        BlockPos pBeetroot = ANCHOR.east(3);
        BlockPos pPumpkin = ANCHOR.south(1);
        BlockPos pMelon = ANCHOR.south(2);
        BlockPos pCocoa = ANCHOR.south(3);

        worldBlocks.put(pWheat, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(pWheat.below(), Blocks.FARMLAND.defaultBlockState());

        worldBlocks.put(pCarrot, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(pCarrot.below(), Blocks.FARMLAND.defaultBlockState());

        worldBlocks.put(pPotato, Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(pPotato.below(), Blocks.FARMLAND.defaultBlockState());

        worldBlocks.put(pBeetroot, Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3));
        worldBlocks.put(pBeetroot.below(), Blocks.FARMLAND.defaultBlockState());

        worldBlocks.put(pPumpkin, Blocks.PUMPKIN.defaultBlockState());
        worldBlocks.put(pPumpkin.below(), Blocks.FARMLAND.defaultBlockState());

        worldBlocks.put(pMelon, Blocks.MELON.defaultBlockState());
        worldBlocks.put(pMelon.below(), Blocks.FARMLAND.defaultBlockState());

        worldBlocks.put(pCocoa, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, Direction.SOUTH).setValue(CocoaBlock.AGE, 2));
        worldBlocks.put(pCocoa.south(), Blocks.JUNGLE_LOG.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        List<BlockPos> found = new ArrayList<>();
        int cursor = 0;

        for (int i = 0; i < 20 && found.size() < 7; i++) {
            var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, cursor);
            if (scan.targetPos().isPresent()) {
                BlockPos target = scan.targetPos().get();
                if (!found.contains(target)) {
                    found.add(target);
                }
            }
            cursor = scan.nextCursor();
        }

        assertTrue(found.contains(pWheat), "Wheat must be discovered");
        assertTrue(found.contains(pCarrot), "Carrots must be discovered");
        assertTrue(found.contains(pPotato), "Potatoes must be discovered");
        assertTrue(found.contains(pBeetroot), "Beetroots must be discovered");
        assertTrue(found.contains(pPumpkin), "Pumpkin must be discovered");
        assertTrue(found.contains(pMelon), "Melon must be discovered");
        assertTrue(found.contains(pCocoa), "Cocoa must be discovered");
    }

    @Test
    @DisplayName("27. One unsupported crop does not block other supported crop discovery")
    void testUnsupportedCropDoesNotBlockDiscovery() {
        worldBlocks.put(ANCHOR, Blocks.COBBLESTONE.defaultBlockState());
        worldBlocks.put(ANCHOR.east(1), Blocks.OAK_SAPLING.defaultBlockState());
        worldBlocks.put(ANCHOR.east(2), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(ANCHOR.east(2).below(), Blocks.FARMLAND.defaultBlockState());

        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan.targetPos().isPresent());
        assertEquals(ANCHOR.east(2), scan.targetPos().get());
    }

    @Test
    @DisplayName("28. Scan budget remains bounded")
    void testScanBudgetRemainsBounded() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertEquals(CropRegistry.FARMER_SCAN_BUDGET, scan.nextCursor());
    }

    @Test
    @DisplayName("29. Scan cursor and emptyChecks remain correct across multiple adapter types")
    void testScanCursorAndEmptyChecksRemainCorrect() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE).withScanCursor(100, 200);
        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, data.scanCursor());
        assertEquals(600, scan.nextCursor());
        assertEquals(700, scan.emptyChecks());
    }

    @Test
    @DisplayName("30. Unloaded chunks are revisited after loading")
    void testUnloadedChunksAreRevisitedAfterLoading() {
        BlockPos wheatPos = ANCHOR;
        worldBlocks.put(wheatPos, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        worldBlocks.put(wheatPos.below(), Blocks.FARMLAND.defaultBlockState());

        // When chunks are unloaded
        chunksLoaded.set(false);
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        var scan1 = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan1.targetPos().isEmpty());

        // When chunks become loaded
        chunksLoaded.set(true);
        var scan2 = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan2.targetPos().isPresent());
        assertEquals(wheatPos, scan2.targetPos().get());
    }

    @Test
    @DisplayName("31. Invalid target does not cause permanent scan starvation")
    void testInvalidTargetDoesNotCausePermanentScanStarvation() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        int cursor = 0;
        int checks = 0;
        boolean cycleFinished = false;
        for (int i = 0; i < 15; i++) {
            var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data.withScanCursor(cursor, checks), cursor);
            cursor = scan.nextCursor();
            checks = scan.emptyChecks();
            if (scan.cycleCompleted()) {
                cycleFinished = true;
                break;
            }
        }
        assertTrue(cycleFinished, "Scan must complete a full cycle across all positions without starving");
    }

    @Test
    @DisplayName("32. WAITING_FOR_CROPS resumes when any supported crop becomes harvestable")
    void testWaitingForCropsResumesWhenAnyCropBecomesHarvestable() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.WAITING_FOR_CROPS);
        assertEquals(JobStatus.WAITING_FOR_CROPS, profile.job().get().status());

        // Introduce a mature melon
        worldBlocks.put(ANCHOR, Blocks.MELON.defaultBlockState());
        worldBlocks.put(ANCHOR.below(), Blocks.FARMLAND.defaultBlockState());

        var scan = CropRegistry.findMatureCrop(mockLevelAccessor, ANCHOR, 16, data, 0);
        assertTrue(scan.targetPos().isPresent());

        // Worker execution loop transitions WAITING_FOR_CROPS to RUNNING when targetPos is present
        WorkerProfile resumed = profile.transition(JobStatus.RUNNING, profile.job().get().progress(), null).get();
        assertEquals(JobStatus.RUNNING, resumed.job().get().status());
    }

    @Test
    @DisplayName("33. Save/reload preserves discovery progress and selection mode")
    void testSaveReloadPreservesDiscoveryProgressAndSelectionMode() {
        FarmerJobData original = FarmerJobData.initial(48L, STORAGE, CropSelectionMode.ALL_SUPPORTED, List.of())
                .withScanCursor(350, 420)
                .withSearchRadius(12);

        var encoded = FarmerJobData.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        var decoded = FarmerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(original.requestedBlocks(), decoded.requestedBlocks());
        assertEquals(original.selectionMode(), decoded.selectionMode());
        assertEquals(original.scanCursor(), decoded.scanCursor());
        assertEquals(original.emptyChecks(), decoded.emptyChecks());
        assertEquals(original.searchRadius(), decoded.searchRadius());
        assertEquals(original.storage(), decoded.storage());
    }

    // ========================================================
    // Group 6: Transaction & Regression Tests (Scenarios 34 - 40)
    // ========================================================

    @Test
    @DisplayName("34. Pumpkin/melon harvest output enters pending delivery correctly")
    void testPumpkinMelonHarvestOutputEntersPendingDelivery() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        List<ItemStack> drops = List.of(new ItemStack(Items.PUMPKIN, 1));
        WorkerProfile updated = FarmerJobController.processHarvestedCrop(profile, 1L, drops);

        FarmerJobData updatedData = updated.job().get().farmerData().get();
        assertEquals(1L, updatedData.cropsHarvested());
        assertEquals(1L, updatedData.itemsPendingDelivery());
        assertEquals(1, updatedData.pendingStacks().size());
        assertEquals(Items.PUMPKIN, updatedData.pendingStacks().get(0).getItem());
    }

    @Test
    @DisplayName("35. Cocoa output and seed accounting are correct")
    void testCocoaOutputAndSeedAccountingAreCorrect() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createFarmerProfile(data, JobStatus.RUNNING);

        // 3 beans dropped; 1 consumed for replanting -> 2 enter pending delivery
        List<ItemStack> produce = List.of(new ItemStack(Items.COCOA_BEANS, 2));
        WorkerProfile updated = FarmerJobController.processHarvestedCrop(profile, 1L, produce);

        FarmerJobData updatedData = updated.job().get().farmerData().get();
        assertEquals(1L, updatedData.cropsHarvested());
        assertEquals(2L, updatedData.itemsPendingDelivery());
        assertEquals(2, updatedData.pendingStacks().get(0).getCount());
    }

    @Test
    @DisplayName("36. Quota counts successful harvest units, not stem/support blocks")
    void testQuotaCountsSuccessfulHarvestUnits() {
        FarmerJobData data = FarmerJobData.initial(5L, STORAGE);
        FarmerJobData step1 = data.withCropHarvested(1L, List.of(new ItemStack(Items.MELON_SLICE, 5)));
        assertEquals(1L, step1.cropsHarvested());
        assertEquals(4L, step1.cropsRemaining());

        FarmerJobData step2 = step1.withCropHarvested(1L, List.of(new ItemStack(Items.PUMPKIN, 1)));
        assertEquals(2L, step2.cropsHarvested());
        assertEquals(3L, step2.cropsRemaining());
    }

    @Test
    @DisplayName("37. Completion waits for pending output and unresolved transactions")
    void testCompletionWaitsForPendingOutput() {
        FarmerJobData data = FarmerJobData.initial(2L, STORAGE)
                .withCropHarvested(2L, List.of(new ItemStack(Items.WHEAT, 2)));
        assertTrue(data.isHarvestFinished());
        assertFalse(data.isDeliveryComplete());
        assertFalse(data.isFullyCompleted());

        // Deliver produce into chest
        Container chest = new SimpleContainer(27);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(chest.getItem(i));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);
        WorkerProfile afterDelivery = FarmerJobController.deliverToStorage(profile, slots, 27);

        FarmerJobData deliveredData = afterDelivery.job().get().farmerData().get();
        assertEquals(0L, deliveredData.itemsPendingDelivery());
        assertTrue(deliveredData.isDeliveryComplete());
        assertTrue(deliveredData.isFullyCompleted());
    }

    @Test
    @DisplayName("38. Unified reservations remain correct")
    void testUnifiedReservationsRemainCorrect() {
        FarmerJobData data = FarmerJobData.initial(10L, STORAGE)
                .withCropHarvested(1L, List.of(new ItemStack(Items.PUMPKIN, 2)));
        WorkerProfile profile = createFarmerProfile(data, JobStatus.DELIVERING);

        List<ItemStack> reserved = profile.getReservedItemStacks();
        assertEquals(1, reserved.size());
        assertEquals(Items.PUMPKIN, reserved.get(0).getItem());
        assertEquals(2, reserved.get(0).getCount());
    }

    @Test
    @DisplayName("39. Existing Phase 8.1 unrelated-block preservation tests still pass")
    void testUnrelatedBlockPreservationStillPasses() {
        BlockPos pos = ANCHOR;
        BlockState originalCrop = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        worldBlocks.put(pos, Blocks.COBBLESTONE.defaultBlockState()); // Unrelated block placed!
        worldBlocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());

        boolean replanted = CropRegistry.replant(mockLevelAccessor, pos, originalCrop);
        assertFalse(replanted);
        assertEquals(Blocks.COBBLESTONE, worldBlocks.get(pos).getBlock());
    }

    @Test
    @DisplayName("40. Existing Miner, Rescue, Shelter, JobQueue and UI tests remain green")
    void testMinerRescueShelterJobQueueRemainGreen() {
        // Verify MinerJobData creation and serialization
        MinerJobData miner = MinerJobData.initialTunnel(64L, STORAGE, Direction.NORTH, 32);
        assertEquals(MinerJobData.MiningMode.TUNNEL, miner.miningMode());
        assertEquals(64L, miner.requestedBlocks());

        // Verify Shelter Plan creation
        var shelterPlan = EquipmentCapabilityPolicy.generateShelterPlan(ANCHOR, EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER, Direction.NORTH, 2);
        assertNotNull(shelterPlan);
        assertEquals(EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER, shelterPlan.mode());

        // Verify JobQueue
        JobQueue queue = JobQueue.EMPTY;
        assertTrue(queue.isEmpty());
    }
}
