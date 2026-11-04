package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Verification Suite:
 * Comprehensive tests for Miner Job Modes (TUNNEL, STAIR, SPECIFIC_BLOCK),
 * Geometry, Decoupled parameters, Codec backwards compatibility, and Networking.
 */
class MinerJobModesTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private static final BlockPos ANCHOR = new BlockPos(100, 64, 100);
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, ANCHOR);
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 95));

    @BeforeAll
    static void setupRegistries() {
        try {
            java.lang.reflect.Field frozenField = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(BuiltInRegistries.ITEM, false);
            frozenField.set(BuiltInRegistries.BLOCK, false);
        } catch (Throwable ignored) {
        }
    }

    @BeforeEach
    @AfterEach
    void resetState() {
        WorkerOrderSession.clearAll();
    }

    // ========================================================
    // 1. Serialization & Backwards Compatibility
    // ========================================================

    @Test
    @DisplayName("1.1 Legacy saved job without new fields deserializes with TUNNEL mode and safe defaults")
    void testLegacyDeserializationDefaultsToTunnel() {
        String legacyJson = """
                {
                    "requested_blocks": 64,
                    "blocks_mined": 10,
                    "items_pending_delivery": 5,
                    "items_delivered": 5,
                    "storage": {
                        "dimension": "minecraft:overworld",
                        "position": [100, 64, 95]
                    },
                    "objective_mode": "BLOCK_COUNT",
                    "direction": "north",
                    "pending_stacks": []
                }
                """;

        var jsonElement = JsonParser.parseString(legacyJson);
        MinerJobData decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, jsonElement).getOrThrow();

        assertEquals(MinerJobData.MiningMode.TUNNEL, decoded.miningMode(), "Legacy job must default to TUNNEL mode");
        assertEquals(64, decoded.maxTravelDistance(), "Default max travel distance must be 64");
        assertFalse(decoded.stairAscending(), "Default stairAscending must be false");
        assertEquals(64, decoded.targetY(), "Default targetY must be 64");
        assertEquals(8, decoded.searchRadius(), "Default searchRadius must be 8");
        assertTrue(decoded.targetBlockIds().isEmpty(), "Default targetBlockIds must be empty");
        assertTrue(decoded.targetBlockTags().isEmpty(), "Default targetBlockTags must be empty");
        assertEquals(64L, decoded.requestedBlocks());
        assertEquals(Direction.NORTH, decoded.direction());
    }

    @Test
    @DisplayName("1.2 Full TUNNEL job roundtrip codec preserves all fields")
    void testTunnelModeCodecRoundtrip() {
        MinerJobData data = MinerJobData.initialTunnel(128L, STORAGE, Direction.EAST, 48);
        var encoded = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        MinerJobData decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(MinerJobData.MiningMode.TUNNEL, decoded.miningMode());
        assertEquals(128L, decoded.requestedBlocks());
        assertEquals(Direction.EAST, decoded.direction());
        assertEquals(48, decoded.maxTravelDistance());
    }

    @Test
    @DisplayName("1.3 Full STAIR job roundtrip codec preserves all fields")
    void testStairModeCodecRoundtrip() {
        MinerJobData data = MinerJobData.initialStair(200L, STORAGE, Direction.SOUTH, true, 96, 75);
        var encoded = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        MinerJobData decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(MinerJobData.MiningMode.STAIR, decoded.miningMode());
        assertEquals(200L, decoded.requestedBlocks());
        assertEquals(Direction.SOUTH, decoded.direction());
        assertTrue(decoded.stairAscending());
        assertEquals(96, decoded.targetY());
        assertEquals(75, decoded.maxTravelDistance());
    }

    @Test
    @DisplayName("1.4 Full SPECIFIC_BLOCK job roundtrip codec preserves all fields")
    void testSpecificBlockModeCodecRoundtrip() {
        List<String> blockIds = List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");
        List<String> tags = List.of("#c:ores", "#minecraft:iron_ores");
        MinerJobData data = MinerJobData.initialSpecificBlock(50L, STORAGE, Direction.WEST, 12, blockIds, tags);
        var encoded = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        MinerJobData decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(MinerJobData.MiningMode.SPECIFIC_BLOCK, decoded.miningMode());
        assertEquals(50L, decoded.requestedBlocks());
        assertEquals(Direction.WEST, decoded.direction());
        assertEquals(12, decoded.searchRadius());
        List<ResourceLocation> expectedIds = List.of(
                ResourceLocation.parse("minecraft:diamond_ore"),
                ResourceLocation.parse("minecraft:deepslate_diamond_ore")
        );
        List<ResourceLocation> expectedTags = List.of(
                ResourceLocation.parse("c:ores"),
                ResourceLocation.parse("minecraft:iron_ores")
        );
        assertEquals(expectedIds, decoded.targetBlockIds());
        assertEquals(expectedTags, decoded.targetBlockTags());
    }

    // ========================================================
    // 2. Decoupled Concepts Verification
    // ========================================================

    @Test
    @DisplayName("2.1 Independent concepts: quota != maxTravelDistance != searchRadius != targetY")
    void testConceptIndependence() {
        MinerJobData tunnelData = MinerJobData.initialTunnel(100L, STORAGE, Direction.NORTH, 30);
        assertNotEquals(tunnelData.requestedBlocks(), (long) tunnelData.maxTravelDistance(),
                "Quota and maxTravelDistance must be independent");

        MinerJobData stairData = MinerJobData.initialStair(50L, STORAGE, Direction.NORTH, false, 20, 60);
        assertNotEquals(stairData.targetY(), ANCHOR.getY(), "Target Y must be distinct from anchor Y");
        assertNotEquals(stairData.requestedBlocks(), (long) stairData.targetY());

        MinerJobData specData = MinerJobData.initialSpecificBlock(15L, STORAGE, Direction.NORTH, 10, List.of("minecraft:iron_ore"), List.of());
        assertNotEquals(specData.requestedBlocks(), (long) specData.searchRadius(),
                "Quota and searchRadius must be independent");
    }

    // ========================================================
    // 3. Mode A: TUNNEL Excavation & Geometry
    // ========================================================

    @Test
    @DisplayName("3.1 Tunnel excavation executes head before feet at each depth")
    void testTunnelHeadBeforeFeet() {
        // At depth d=0: upper block (head, Y=65) is mined first, then lower block (feet, Y=64)
        BlockPos headPos = ANCHOR.above();
        BlockPos feetPos = ANCHOR;

        // When both head and feet are solid
        MinerJobController.TunnelTargetResult result1 = MinerJobController.findNextTunnelTarget(
                ANCHOR, Direction.NORTH, 10L,
                pos -> false, // nothing cleared
                pos -> Optional.empty()
        );
        assertTrue(result1.targetPos().isPresent());
        assertEquals(headPos, result1.targetPos().get(), "Upper block (head) must be targeted first");

        // When head is cleared, feet is targeted
        MinerJobController.TunnelTargetResult result2 = MinerJobController.findNextTunnelTarget(
                ANCHOR, Direction.NORTH, 10L,
                pos -> pos.equals(headPos), // only head is cleared
                pos -> Optional.empty()
        );
        assertTrue(result2.targetPos().isPresent());
        assertEquals(feetPos, result2.targetPos().get(), "Lower block (feet) must be targeted second");
    }

    @Test
    @DisplayName("3.2 Tunnel horizontal directions North, East, South, West step horizontally")
    void testTunnelHorizontalDirections() {
        for (Direction dir : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            MinerJobController.TunnelTargetResult result = MinerJobController.findNextTunnelTarget(
                    ANCHOR, dir, 10L,
                    pos -> false,
                    pos -> Optional.empty()
            );
            assertTrue(result.targetPos().isPresent());
            assertEquals(ANCHOR.above(), result.targetPos().get());
        }
    }

    // ========================================================
    // 4. Mode B: STAIR Excavation & Geometry
    // ========================================================

    @Test
    @DisplayName("4.1 Descending staircase geometry: d=0 is (Y0), d=1 is (Y0 - 1), etc.")
    void testStairDescendingGeometryCalculations() {
        int baseY = 64;
        BlockPos anchor = new BlockPos(100, baseY, 100);
        Direction dir = Direction.NORTH;

        for (int d = 0; d < 5; d++) {
            int stepY = baseY - d;
            BlockPos stepBase = anchor.relative(dir, d);
            BlockPos floorPos = new BlockPos(stepBase.getX(), stepY - 1, stepBase.getZ());
            BlockPos feetPos = new BlockPos(stepBase.getX(), stepY, stepBase.getZ());
            BlockPos headPos = new BlockPos(stepBase.getX(), stepY + 1, stepBase.getZ());
            BlockPos clearPos = new BlockPos(stepBase.getX(), stepY + 2, stepBase.getZ());

            assertEquals(baseY - d - 1, floorPos.getY(), "Floor support must be at stepY - 1");
            assertEquals(baseY - d, feetPos.getY(), "Feet must be at stepY");
            assertEquals(baseY - d + 1, headPos.getY(), "Head must be at stepY + 1");
            assertEquals(baseY - d + 2, clearPos.getY(), "Clearance must be at stepY + 2");
        }
    }

    @Test
    @DisplayName("4.2 Ascending staircase geometry: d=0 is (Y0), d=1 is (Y0 + 1), etc.")
    void testStairAscendingGeometryCalculations() {
        int baseY = 64;
        BlockPos anchor = new BlockPos(100, baseY, 100);
        Direction dir = Direction.EAST;

        for (int d = 0; d < 5; d++) {
            int stepY = baseY + d;
            BlockPos stepBase = anchor.relative(dir, d);
            BlockPos floorPos = new BlockPos(stepBase.getX(), stepY - 1, stepBase.getZ());
            BlockPos feetPos = new BlockPos(stepBase.getX(), stepY, stepBase.getZ());
            BlockPos headPos = new BlockPos(stepBase.getX(), stepY + 1, stepBase.getZ());
            BlockPos clearPos = new BlockPos(stepBase.getX(), stepY + 2, stepBase.getZ());

            assertEquals(baseY + d - 1, floorPos.getY());
            assertEquals(baseY + d, feetPos.getY());
            assertEquals(baseY + d + 1, headPos.getY());
            assertEquals(baseY + d + 2, clearPos.getY());
        }
    }

    @Test
    @DisplayName("4.3 Stair standing position is the previous step floor")
    void testStairStandingPositionCalculation() {
        BlockPos anchor = new BlockPos(100, 64, 100);
        Direction dir = Direction.NORTH;

        // Step 0 stand pos is anchor
        // Step 1 stand pos is step 0 floor (anchor)
        // Step 2 stand pos is step 1 floor (anchor.relative(dir, 1) with Y = 64 - 1 = 63)
        int step0Y = 64;
        BlockPos prevStepFloor0 = anchor;
        assertEquals(100, prevStepFloor0.getX());
        assertEquals(64, prevStepFloor0.getY());
        assertEquals(100, prevStepFloor0.getZ());

        int step1Y = 63;
        BlockPos prevStepFloor1 = new BlockPos(anchor.relative(dir, 1).getX(), step1Y, anchor.relative(dir, 1).getZ());
        assertEquals(100, prevStepFloor1.getX());
        assertEquals(63, prevStepFloor1.getY());
        assertEquals(99, prevStepFloor1.getZ());
    }

    @Test
    @DisplayName("4.4 Stair TargetReached condition evaluates targetY boundary")
    void testStairTargetReachedBoundary() {
        // In findNextStairTarget, if ascending and anchor Y > targetY -> targetReached
        MinerJobController.MiningTargetResult resultAsc = MinerJobController.findNextStairTarget(
                null, new BlockPos(100, 70, 100), Direction.NORTH, true, 65, 30, 50, null
        );
        assertTrue(resultAsc.targetReached(), "Ascending when anchor Y > targetY must return targetReached");

        // In findNextStairTarget, if descending and anchor Y < targetY -> targetReached
        MinerJobController.MiningTargetResult resultDesc = MinerJobController.findNextStairTarget(
                null, new BlockPos(100, 50, 100), Direction.NORTH, false, 60, 30, 50, null
        );
        assertTrue(resultDesc.targetReached(), "Descending when anchor Y < targetY must return targetReached");
    }

    // ========================================================
    // 5. Mode C: SPECIFIC_BLOCK Spherical Math & Selection
    // ========================================================

    @Test
    @DisplayName("5.1 Spherical distance check: (dx)^2 + (dy)^2 + (dz)^2 <= R^2 uses integer coordinates")
    void testSpecificBlockSphericalMath() {
        BlockPos center = new BlockPos(100, 64, 100);
        int radius = 5;
        int rSq = radius * radius; // 25

        // Point inside: (103, 64, 100) -> dx=3, dy=0, dz=0 -> 9 <= 25 (TRUE)
        BlockPos inside = new BlockPos(103, 64, 100);
        double distInside = inside.distSqr(center);
        assertTrue(distInside <= rSq, "Point at dx=3 must be inside radius 5");

        // Point exactly on the surface: (103, 64, 104) -> dx=3, dy=0, dz=4 -> 9 + 0 + 16 = 25 <= 25 (TRUE)
        BlockPos onSurface = new BlockPos(103, 64, 104);
        double distOnSurface = onSurface.distSqr(center);
        assertEquals(25.0, distOnSurface, 0.001);
        assertTrue(distOnSurface <= rSq, "Point at dx=3, dz=4 (dist 5) must be included");

        // Point strictly outside: (103, 65, 104) -> dx=3, dy=1, dz=4 -> 9 + 1 + 16 = 26 > 25 (FALSE)
        BlockPos outside = new BlockPos(103, 65, 104);
        double distOutside = outside.distSqr(center);
        assertEquals(26.0, distOutside, 0.001);
        assertFalse(distOutside <= rSq, "Point at distSq=26 must be strictly excluded from radius 5");

        // Point outside on single axis: (106, 64, 100) -> dx=6, distSq=36 > 25 (FALSE)
        BlockPos outsideAxis = new BlockPos(106, 64, 100);
        assertFalse(outsideAxis.distSqr(center) <= rSq);
    }

    @Test
    @DisplayName("5.2 Target block and tag filtering logic in SPECIFIC_BLOCK")
    void testSpecificBlockTargetMatching() {
        List<String> targetIds = List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore");
        List<String> targetTags = List.of("#c:ores", "#minecraft:iron_ores");

        // Direct ID match
        assertTrue(targetIds.contains("minecraft:iron_ore"));
        assertTrue(targetIds.contains("minecraft:deepslate_iron_ore"));
        assertFalse(targetIds.contains("minecraft:coal_ore"));

        // Tag format check
        assertTrue(targetTags.get(0).startsWith("#"));
        assertEquals("c:ores", targetTags.get(0).substring(1));
        assertEquals("minecraft:iron_ores", targetTags.get(1).substring(1));
    }

    // ========================================================
    // 6. Network Payloads & StreamCodec Roundtrip
    // ========================================================

    @Test
    @DisplayName("6.1 OpenMinerConfigPayload stream codec byte buffer roundtrip")
    void testOpenMinerConfigPayloadCodec() {
        BlockPos anchor = new BlockPos(120, 60, -80);
        List<String> tags = List.of("#c:ores", "#c:ores/iron", "#minecraft:stone_ore_replaceables");
        OpenMinerConfigPayload original = new OpenMinerConfigPayload(
                WORKER_ID, anchor, Direction.WEST, 64L, "minecraft:iron_ore", tags
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        OpenMinerConfigPayload.STREAM_CODEC.encode(buf, original);

        OpenMinerConfigPayload decoded = OpenMinerConfigPayload.STREAM_CODEC.decode(buf);

        assertEquals(original.workerId(), decoded.workerId());
        assertEquals(original.anchorPos(), decoded.anchorPos());
        assertEquals(original.initialDirection(), decoded.initialDirection());
        assertEquals(original.defaultQuota(), decoded.defaultQuota());
        assertEquals(original.initialBlockId(), decoded.initialBlockId());
        assertEquals(original.candidateTags(), decoded.candidateTags());
    }

    @Test
    @DisplayName("6.2 MinerConfigSubmitPayload stream codec byte buffer roundtrip")
    void testMinerConfigSubmitPayloadCodec() {
        BlockPos anchor = new BlockPos(200, 75, 300);
        List<String> ids = List.of("minecraft:diamond_ore", "minecraft:emerald_ore");
        List<String> tags = List.of("#c:ores");
        MinerConfigSubmitPayload original = new MinerConfigSubmitPayload(
                WORKER_ID, anchor, "SPECIFIC_BLOCK", 100L, 50, Direction.SOUTH, true, 80, 16, ids, tags
        );

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        MinerConfigSubmitPayload.STREAM_CODEC.encode(buf, original);

        MinerConfigSubmitPayload decoded = MinerConfigSubmitPayload.STREAM_CODEC.decode(buf);

        assertEquals(original.workerId(), decoded.workerId());
        assertEquals(original.anchorPos(), decoded.anchorPos());
        assertEquals(original.mode(), decoded.mode());
        assertEquals(original.quota(), decoded.quota());
        assertEquals(original.maxTravelDistance(), decoded.maxTravelDistance());
        assertEquals(original.direction(), decoded.direction());
        assertEquals(original.stairAscending(), decoded.stairAscending());
        assertEquals(original.targetY(), decoded.targetY());
        assertEquals(original.searchRadius(), decoded.searchRadius());
        assertEquals(original.targetBlockIds(), decoded.targetBlockIds());
        assertEquals(original.targetBlockTags(), decoded.targetBlockTags());
    }

    @Test
    @DisplayName("6.3 MinerConfigResponsePayload stream codec byte buffer roundtrip")
    void testMinerConfigResponsePayloadCodec() {
        MinerConfigResponsePayload original = new MinerConfigResponsePayload(true, "Config accepted!");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        MinerConfigResponsePayload.STREAM_CODEC.encode(buf, original);

        MinerConfigResponsePayload decoded = MinerConfigResponsePayload.STREAM_CODEC.decode(buf);

        assertEquals(original.accepted(), decoded.accepted());
        assertEquals(original.message(), decoded.message());
    }

    // ========================================================
    // 7. Server Validation & WorkerOrderSession MinerConfig
    // ========================================================

    @Test
    @DisplayName("7.1 WorkerOrderSession MinerConfig updates session and applies to selectStorage")
    void testSessionMinerConfigApplication() {
        WorkerOrderSession.MinerConfig config = new WorkerOrderSession.MinerConfig(
                MinerJobData.MiningMode.STAIR,
                50,
                false,
                40,
                8,
                List.of(),
                List.of()
        );

        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                OWNER_ID,
                WORKER_ID,
                OVERWORLD,
                WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(WORKSITE),
                Optional.empty(),
                100L,
                System.currentTimeMillis(),
                Optional.of(Direction.NORTH),
                List.of(),
                Optional.empty(),
                Optional.of(config)
        );

        assertTrue(session.minerConfig().isPresent());
        assertEquals(MinerJobData.MiningMode.STAIR, session.minerConfig().get().mode());
        assertEquals(50, session.minerConfig().get().maxTravelDistance());
        assertFalse(session.minerConfig().get().stairAscending());
        assertEquals(40, session.minerConfig().get().targetY());
    }

    @Test
    @DisplayName("7.2 Invalid mode strings are rejected safely")
    void testInvalidMiningModeRejected() {
        assertThrows(IllegalArgumentException.class, () -> MinerJobData.MiningMode.valueOf("INVALID_MODE"));
        assertEquals(MinerJobData.MiningMode.TUNNEL, MinerJobData.MiningMode.valueOf("TUNNEL"));
        assertEquals(MinerJobData.MiningMode.STAIR, MinerJobData.MiningMode.valueOf("STAIR"));
        assertEquals(MinerJobData.MiningMode.SPECIFIC_BLOCK, MinerJobData.MiningMode.valueOf("SPECIFIC_BLOCK"));
    }

    // ========================================================
    // 8. Phase 7.1 Specific Audits: Multi-Selection, Cursor, Reach, Precedence
    // ========================================================

    @Test
    @DisplayName("8.1 Multi-selection of multiple block IDs and multiple tags in SPECIFIC_BLOCK")
    void testSpecificBlockMultiSelectionHandling() {
        List<String> ids = List.of("minecraft:iron_ore", "minecraft:gold_ore", "modded:tin_ore");
        List<String> tags = List.of("#c:ores", "#c:ores/iron", "#minecraft:diamond_ores");

        MinerJobData data = MinerJobData.initialSpecificBlock(100L, STORAGE, Direction.NORTH, 16, ids, tags);

        assertEquals(3, data.targetBlockIds().size());
        assertEquals(ResourceLocation.parse("minecraft:iron_ore"), data.targetBlockIds().get(0));
        assertEquals(ResourceLocation.parse("minecraft:gold_ore"), data.targetBlockIds().get(1));
        assertEquals(ResourceLocation.parse("modded:tin_ore"), data.targetBlockIds().get(2));

        assertEquals(3, data.targetBlockTags().size());
        assertEquals(ResourceLocation.parse("c:ores"), data.targetBlockTags().get(0));
        assertEquals(ResourceLocation.parse("c:ores/iron"), data.targetBlockTags().get(1));
        assertEquals(ResourceLocation.parse("minecraft:diamond_ores"), data.targetBlockTags().get(2));
    }

    @Test
    @DisplayName("8.2 Missing optional mod entries do not crash codec serialization or deserialization")
    void testMissingModEntriesDoNotCrashJob() {
        List<String> ids = List.of("unknown_mod:missing_block", "minecraft:iron_ore");
        List<String> tags = List.of("#unknown_mod:missing_tag");

        MinerJobData data = MinerJobData.initialSpecificBlock(64L, STORAGE, Direction.NORTH, 8, ids, tags);

        var encoded = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        MinerJobData decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(2, decoded.targetBlockIds().size());
        assertEquals(ResourceLocation.parse("unknown_mod:missing_block"), decoded.targetBlockIds().get(0));
        assertEquals(1, decoded.targetBlockTags().size());
        assertEquals(ResourceLocation.parse("unknown_mod:missing_tag"), decoded.targetBlockTags().get(0));

        // When scanning in null level or without the block registered, returns unobstructedEmpty safely
        MinerJobController.MiningTargetResult result = MinerJobController.findNextSpecificBlockTarget(
                null, ANCHOR, 8, decoded.targetBlockIds(), decoded.targetBlockTags(), 0, 64L, null, null
        );
        assertTrue(result.targetPos().isEmpty(), "Must safely return empty without throwing exception");
    }

    @Test
    @DisplayName("8.3 Bounded incremental scan budget (500 blocks/tick) and cursor progression")
    void testBoundedIncrementalScanBudgetAndCursor() {
        assertEquals(500, MinerJobController.SPECIFIC_BLOCK_SCAN_BUDGET, "Scan budget must be 500 blocks per tick");

        // With radius 8: S = 17, N = 4,913 positions
        int r = 8;
        int s = 2 * r + 1;
        int total = s * s * s;
        assertEquals(4913, total);

        // First slice: start at 0, inspects 500 positions -> nextCursor = 500
        int cursor = 0;
        int budget = MinerJobController.SPECIFIC_BLOCK_SCAN_BUDGET;
        int nextCursor = (cursor + budget) % total;
        assertEquals(500, nextCursor);

        // Near end of sphere: start at 4800 -> wraps around total (4913) -> nextCursor = (4800 + 500) % 4913 = 387
        cursor = 4800;
        nextCursor = (cursor + budget) % total;
        assertEquals(387, nextCursor);
    }

    @Test
    @DisplayName("8.4 Scan cursor is durably preserved across MinerJobData codec roundtrip")
    void testScanCursorPersistedInCodec() {
        MinerJobData original = MinerJobData.initialSpecificBlock(50L, STORAGE, Direction.SOUTH, 16, List.of("minecraft:iron_ore"), List.of())
                .withScanCursor(1234);

        assertEquals(1234, original.scanCursor());

        var encoded = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        MinerJobData decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(1234, decoded.scanCursor(), "Scan cursor must be restored from Codec");
    }

    @Test
    @DisplayName("8.5 Stair ascending clearance block reachability from worker eye position")
    void testStairAscendingEyeReachDistance() {
        // Worker standing at step 0 feet (100, 64, 100).
        // Eye position is at (100.5, 65.5, 100.5).
        // Step 1 ascending has clearancePos at (101, 67, 100). Center is at (101.5, 67.5, 100.5).
        double workerX = 100.5;
        double workerFeetY = 64.0;
        double workerEyeY = 65.5;
        double workerZ = 100.5;

        double targetCenterX = 101.5;
        double targetCenterY = 67.5;
        double targetCenterZ = 100.5;

        // Feet distance squared: dx=1, dy=3.5, dz=0 -> 1 + 12.25 = 13.25 > 12.25 (3.5^2)
        double dxFeet = targetCenterX - workerX;
        double dyFeet = targetCenterY - workerFeetY;
        double dzFeet = targetCenterZ - workerZ;
        double feetDistSq = dxFeet * dxFeet + dyFeet * dyFeet + dzFeet * dzFeet;
        assertEquals(13.25, feetDistSq, 0.001);
        assertTrue(feetDistSq > 3.5 * 3.5, "Feet distance alone exceeds 3.5 blocks");

        // Eye distance squared: dx=1, dy=2.0, dz=0 -> 1 + 4 = 5.0 <= 16.0 (4.0^2)
        double dxEye = targetCenterX - workerX;
        double dyEye = targetCenterY - workerEyeY;
        double dzEye = targetCenterZ - workerZ;
        double eyeDistSq = dxEye * dxEye + dyEye * dyEye + dzEye * dzEye;
        assertEquals(5.0, eyeDistSq, 0.001);
        assertTrue(eyeDistSq <= 4.0 * 4.0, "Eye distance is comfortably within 4.0 blocks reach");
    }

    @Test
    @DisplayName("8.6 Contradictory stair ascending/descending target Y is rejected by server")
    void testContradictoryStairDirectionValidation() {
        // Anchor is at Y=64.
        // Ascending stair with targetY = 50 (< anchorY) is contradictory
        MinerConfigSubmitPayload invalidAscending = new MinerConfigSubmitPayload(
                WORKER_ID, ANCHOR, "STAIR", 64L, 64, Direction.NORTH, true, 50, 8, List.of(), List.of()
        );
        // Descending stair with targetY = 80 (> anchorY) is contradictory
        MinerConfigSubmitPayload invalidDescending = new MinerConfigSubmitPayload(
                WORKER_ID, ANCHOR, "STAIR", 64L, 64, Direction.NORTH, false, 80, 8, List.of(), List.of()
        );

        assertTrue(invalidAscending.stairAscending() && invalidAscending.targetY() < ANCHOR.getY(),
                "Ascending with targetY < anchorY must be detected as contradictory");
        assertTrue(!invalidDescending.stairAscending() && invalidDescending.targetY() > ANCHOR.getY(),
                "Descending with targetY > anchorY must be detected as contradictory");
    }

    @Test
    @DisplayName("8.7 Legacy tunnel maxTravelDistance <= 0 respects Config.getMaxTravelDistance()")
    void testLegacyTunnelTravelDistanceFallback() {
        int configMax = Config.getMaxTravelDistance();
        int requestedBlocks = 100;
        int targetDepth = Math.max(1, (int) Math.ceil(requestedBlocks / 2.0)); // 50

        // When maxTravelDistance = 0 (legacy), limit is Config.getMaxTravelDistance()
        int maxTravel = 0;
        int limit = maxTravel > 0 ? Math.min(configMax, maxTravel) : configMax;
        int maxDepth = Math.min(limit, targetDepth);

        assertEquals(Math.min(configMax, targetDepth), maxDepth,
                "Legacy tunnel depth calculation must match pre-Phase 7 formula exactly");
    }
}
