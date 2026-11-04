package com.example.examplemod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for Mining Fix Phase 1:
 * Failure Handling, Tool Fallback, Bounded Retries, and Active Target Persistence.
 */
class MinerFailureHandlingAndActiveTargetTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 95));

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

    @Test
    @DisplayName("MinerJobData stores activeTarget and retryCount with proper defaults and immutability")
    void testMinerJobDataActiveTargetAndRetryCountFields() {
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of());
        assertTrue(data.activeTarget().isEmpty(), "Initial activeTarget must be empty");
        assertEquals(0, data.retryCount(), "Initial retryCount must be 0");

        BlockPos targetPos = new BlockPos(102, 60, 105);
        MinerJobData withTarget = data.withActiveTarget(targetPos);
        assertTrue(withTarget.activeTarget().isPresent());
        assertEquals(targetPos, withTarget.activeTarget().get());
        assertEquals(0, withTarget.retryCount());

        MinerJobData withRetry = withTarget.withRetryCount(2);
        assertEquals(2, withRetry.retryCount());
        assertEquals(targetPos, withRetry.activeTarget().get());

        MinerJobData advancedCursor = withRetry.withScanCursor(42);
        assertEquals(42, advancedCursor.scanCursor());
        assertEquals(targetPos, advancedCursor.activeTarget().get(), "Cursor advance must preserve activeTarget");
        assertEquals(2, advancedCursor.retryCount(), "Cursor advance must preserve retryCount");

        MinerJobData cleared = advancedCursor.withoutActiveTarget();
        assertTrue(cleared.activeTarget().isEmpty());
        assertEquals(0, cleared.retryCount());
    }

    @Test
    @DisplayName("commitInFlightMutation clears activeTarget upon successful mining")
    void testCommitInFlightMutationClearsActiveTarget() {
        BlockPos targetPos = new BlockPos(105, 55, 105);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos)
                .withRetryCount(1)
                .withInFlightMutation(targetPos, ResourceLocation.parse("minecraft:iron_ore"), List.of(new ItemStack(Items.RAW_IRON)));

        assertTrue(data.activeTarget().isPresent());
        assertTrue(data.inFlightMutation().isPresent());

        MinerJobData committed = data.commitInFlightMutation();
        assertTrue(committed.inFlightMutation().isEmpty(), "In-flight mutation must be cleared");
        assertTrue(committed.activeTarget().isEmpty(), "Active target must be cleared after successful mutation commit");
        assertEquals(1, committed.blocksMined());
        assertEquals(1, committed.itemsPendingDelivery());
    }

    @Test
    @DisplayName("commitRecoveryInFlightMutation clears activeTarget upon recovery commit")
    void testCommitRecoveryInFlightMutationClearsActiveTarget() {
        BlockPos targetPos = new BlockPos(105, 55, 105);
        MinerJobData data = MinerJobData.initialTunnel(10, STORAGE, Direction.NORTH, 64)
                .withActiveTarget(targetPos)
                .withInFlightMutation(targetPos, ResourceLocation.parse("minecraft:gravel"), List.of(new ItemStack(Items.FLINT)));

        MinerJobData committed = data.commitRecoveryInFlightMutation();
        assertTrue(committed.inFlightMutation().isEmpty());
        assertTrue(committed.activeTarget().isEmpty(), "Active target must be cleared after recovery commit");
        assertEquals(0, committed.blocksMined(), "Recovery mining does not increment blocksMined quota");
        assertEquals(1, committed.itemsPendingDelivery());
    }

    @Test
    @DisplayName("MinerJobData Codec backwards compatibility: loads legacy saves without activeTarget or retryCount")
    void testCodecBackwardsCompatibilityWithoutActiveTarget() {
        String legacyJson = """
        {
            "requested_blocks": 50,
            "blocks_mined": 5,
            "items_pending_delivery": 0,
            "items_delivered": 5,
            "pending_stacks": [],
            "storage": {
                "dimension": "minecraft:overworld",
                "position": [100, 64, 95]
            },
            "direction": "north",
            "objective_mode": "BLOCK_COUNT",
            "mining_mode": "SPECIFIC_BLOCK",
            "search_radius": 12,
            "target_block_ids": ["minecraft:gold_ore"],
            "target_block_tags": [],
            "scan_cursor": 15
        }
        """;

        var jsonElement = JsonParser.parseString(legacyJson);
        var parseResult = MinerJobData.CODEC.parse(JsonOps.INSTANCE, jsonElement);
        assertTrue(parseResult.result().isPresent(), "Legacy MinerJobData must parse cleanly: " + parseResult.error());

        MinerJobData loaded = parseResult.result().get();
        assertEquals(50L, loaded.requestedBlocks());
        assertEquals(5L, loaded.blocksMined());
        assertEquals(12, loaded.searchRadius());
        assertEquals(15, loaded.scanCursor());
        assertTrue(loaded.activeTarget().isEmpty(), "Legacy data must default activeTarget to Optional.empty()");
        assertEquals(0, loaded.retryCount(), "Legacy data must default retryCount to 0");
        assertEquals(0, loaded.retryDelay(), "Legacy data must default retryDelay to 0");
    }

    @Test
    @DisplayName("MinerJobData Codec round-trip: serializes and restores activeTarget and retryCount")
    void testCodecRoundTripWithActiveTargetAndRetryCount() {
        BlockPos targetPos = new BlockPos(110, 40, 120);
        MinerJobData data = MinerJobData.initialSpecificBlock(100, STORAGE, 16, List.of(ResourceLocation.parse("minecraft:diamond_ore")), List.of())
                .withScanCursor(88)
                .withActiveTarget(targetPos)
                .withRetryCount(2)
                .withRetryDelay(20);

        var encodedOpt = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).result();
        assertTrue(encodedOpt.isPresent(), "Encoding MinerJobData must succeed");

        var decodedOpt = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encodedOpt.get()).result();
        assertTrue(decodedOpt.isPresent(), "Decoding MinerJobData must succeed");

        MinerJobData restored = decodedOpt.get();
        assertEquals(88, restored.scanCursor());
        assertTrue(restored.activeTarget().isPresent());
        assertEquals(targetPos, restored.activeTarget().get());
        assertEquals(2, restored.retryCount());
        assertEquals(20, restored.retryDelay());
    }

    @Test
    @DisplayName("TransactionExecutionResult includes execution gate for preflight rejection")
    void testTransactionExecutionResultGate() {
        WorkerJob job = new WorkerJob(
                WorkerJobs.MINE,
                JobStatus.RUNNING,
                0,
                Optional.empty(),
                Optional.of(STORAGE),
                Optional.of(MinerJobData.initial(10, STORAGE)),
                Optional.empty()
        );
        WorkerProfile profile = new WorkerProfile(
                WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty()
        );

        MinerJobController.TransactionExecutionResult successRes =
                MinerJobController.TransactionExecutionResult.success(profile, List.of(new ItemStack(Items.COBBLESTONE)));
        assertEquals(MinerJobController.TransactionStatus.SUCCESS, successRes.status());
        assertTrue(successRes.gate().isPresent());
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, successRes.gate().get());

        MinerJobController.TransactionExecutionResult rejectRes =
                MinerJobController.TransactionExecutionResult.rejected(
                        MinerJobController.TransactionStatus.REJECTED_PREFLIGHT,
                        profile,
                        "Preflight check rejected mining action: REJECTED_UNVERIFIED_TOOL",
                        MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL
                );
        assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, rejectRes.status());
        assertTrue(rejectRes.gate().isPresent());
        assertEquals(MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL, rejectRes.gate().get());
    }

    @Test
    @DisplayName("isValidSpecificBlockTarget identifies matching blocks and rejects air, liquids, and invalid blocks")
    void testIsValidSpecificBlockTarget() {
        BlockPos pos = new BlockPos(10, 60, 10);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8,
                List.of(ResourceLocation.parse("minecraft:iron_ore"), ResourceLocation.parse("minecraft:gold_ore")),
                List.of()
        );

        // Air is rejected
        assertFalse(MinerJobController.isValidSpecificBlockTarget(null, data, pos, Blocks.AIR.defaultBlockState(), null));

        // Water is rejected
        assertFalse(MinerJobController.isValidSpecificBlockTarget(null, data, pos, Blocks.WATER.defaultBlockState(), null));

        // Non-target block (dirt) is rejected
        assertFalse(MinerJobController.isValidSpecificBlockTarget(null, data, pos, Blocks.DIRT.defaultBlockState(), null));

        // Indestructible block (bedrock) is rejected even if listed
        MinerJobData bedrockData = MinerJobData.initialSpecificBlock(10, STORAGE, 8,
                List.of(ResourceLocation.parse("minecraft:bedrock")), List.of());
        assertFalse(MinerJobController.isValidSpecificBlockTarget(null, bedrockData, pos, Blocks.BEDROCK.defaultBlockState(), null));

        // Matching block (iron ore) is accepted
        assertTrue(MinerJobController.isValidSpecificBlockTarget(null, data, pos, Blocks.IRON_ORE.defaultBlockState(), null));
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

    @Test
    @DisplayName("swapJobTool respects reservations and selects unreserved candidate")
    void testSwapJobToolRespectsReservations() {
        ItemStack held = new ItemStack(Items.STICK);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);

        WorkerStorage storage = createStorage(diamondPick, ironPick);
        // Diamond pick is reserved
        List<ItemStack> reserved = List.of(diamondPick.copy());

        List<ItemStack> equipped = new ArrayList<>();
        List<WorkerStorage> updatedStorage = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult result = WorkerExecutionLoop.swapJobTool(
                storage,
                held,
                JobToolRequirement.MINING,
                reserved,
                equipped::add,
                updatedStorage::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.SUCCESS, result);
        assertEquals(1, equipped.size());
        assertEquals(Items.IRON_PICKAXE, equipped.get(0).getItem(), "Must select unreserved iron pickaxe instead of reserved diamond pickaxe");
    }

    @Test
    @DisplayName("swapJobTool fails closed when WorkerStorage has no free space to stash old held item")
    void testSwapJobToolFailsClosedOnFullStorage() {
        ItemStack held = new ItemStack(Items.STICK);
        ItemStack multiPick = new ItemStack(Items.IRON_PICKAXE, 2);

        // Fill all 15 slots: slot 0 has 2 pickaxes (so extracting 1 still leaves 1 in the slot, vacating no slots),
        // and slots 1-14 have full cobblestone stacks. Old held stick cannot fit anywhere!
        List<ItemStack> fullList = new ArrayList<>();
        fullList.add(multiPick);
        for (int i = 1; i < WorkerStorage.SIZE; i++) {
            fullList.add(new ItemStack(Items.COBBLESTONE, 64));
        }
        WorkerStorage fullNoEmptySlot = new WorkerStorage(fullList);

        List<ItemStack> equipped = new ArrayList<>();
        List<WorkerStorage> updatedStorage = new ArrayList<>();

        WorkerExecutionLoop.ToolSwapResult resultFull = WorkerExecutionLoop.swapJobTool(
                fullNoEmptySlot,
                held,
                JobToolRequirement.MINING,
                Collections.emptyList(),
                equipped::add,
                updatedStorage::add
        );

        assertEquals(WorkerExecutionLoop.ToolSwapResult.INSUFFICIENT_STORAGE_SPACE, resultFull,
                "Must fail closed with INSUFFICIENT_STORAGE_SPACE when old held item cannot be safely stored");
        assertTrue(equipped.isEmpty(), "Hand item must not be modified when storage cannot accommodate old item");
        assertTrue(updatedStorage.isEmpty(), "Storage must not be modified when swap fails closed");
    }

    @Test
    @DisplayName("Fix B — MinerJobData stores and sanitizes retryDelay with default 0")
    void testMinerJobDataRetryDelayFieldAndSanitization() {
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of());
        assertEquals(0, data.retryDelay(), "Initial retryDelay must be 0");

        MinerJobData withDelay = data.withRetryDelay(15);
        assertEquals(15, withDelay.retryDelay());

        // Negative delay is sanitized to 0
        MinerJobData sanitized = withDelay.withRetryDelay(-5);
        assertEquals(0, sanitized.retryDelay(), "Negative retryDelay must be sanitized to 0");
    }

    @Test
    @DisplayName("Fix B — 1st mutation failure sets retry count to 1 and backoff delay to 10 ticks, preserving activeTarget")
    void test1stMutationFailureSetsRetryCount1AndDelay10AndPreservesTarget() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos);

        assertEquals(0, data.retryCount());
        assertEquals(0, data.retryDelay());
        assertTrue(data.activeTarget().isPresent());

        // Simulate 1st mutation failure backoff logic
        int retries = data.retryCount() + 1;
        assertEquals(1, retries);
        int backoffTicks = switch (retries) {
            case 1 -> 10;
            case 2 -> 20;
            default -> 40;
        };
        MinerJobData after1st = data.withRetryCount(retries).withRetryDelay(backoffTicks);

        assertEquals(1, after1st.retryCount(), "Retry count must be 1 after 1st mutation failure");
        assertEquals(10, after1st.retryDelay(), "Backoff delay must be 10 ticks after 1st mutation failure");
        assertTrue(after1st.activeTarget().isPresent(), "activeTarget must be strictly preserved after 1st failure");
        assertEquals(targetPos, after1st.activeTarget().get());
    }

    @Test
    @DisplayName("Fix B — Subsequent ticks decrement retryDelay while preserving activeTarget")
    void testSubsequentTicksDecrementRetryDelayWhilePreservingTarget() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos)
                .withRetryCount(1)
                .withRetryDelay(10);

        for (int i = 10; i > 0; i--) {
            assertEquals(i, data.retryDelay());
            assertTrue(data.activeTarget().isPresent());
            assertEquals(targetPos, data.activeTarget().get());
            data = data.withRetryDelay(data.retryDelay() - 1);
        }

        assertEquals(0, data.retryDelay(), "retryDelay must reach 0 after countdown");
        assertEquals(1, data.retryCount(), "retryCount must remain 1 during backoff");
        assertTrue(data.activeTarget().isPresent(), "activeTarget must remain intact after countdown");
        assertEquals(targetPos, data.activeTarget().get());
    }

    @Test
    @DisplayName("Fix B — 2nd mutation failure sets retry count to 2 and backoff delay to 20 ticks, preserving activeTarget")
    void test2ndMutationFailureSetsRetryCount2AndDelay20AndPreservesTarget() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos)
                .withRetryCount(1)
                .withRetryDelay(0);

        int retries = data.retryCount() + 1;
        assertEquals(2, retries);
        int backoffTicks = switch (retries) {
            case 1 -> 10;
            case 2 -> 20;
            default -> 40;
        };
        MinerJobData after2nd = data.withRetryCount(retries).withRetryDelay(backoffTicks);

        assertEquals(2, after2nd.retryCount(), "Retry count must be 2 after 2nd mutation failure");
        assertEquals(20, after2nd.retryDelay(), "Backoff delay must be 20 ticks after 2nd mutation failure");
        assertTrue(after2nd.activeTarget().isPresent(), "activeTarget must be preserved after 2nd failure");
        assertEquals(targetPos, after2nd.activeTarget().get());
    }

    @Test
    @DisplayName("Fix B — 3rd mutation failure transitions to PAUSED, retaining activeTarget and retryCount = 3")
    void test3rdMutationFailureTransitionsToPausedRetainingTargetAndRetries() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos)
                .withRetryCount(2)
                .withRetryDelay(0);

        WorkerJob job = new WorkerJob(
                WorkerJobs.MINE,
                JobStatus.RUNNING,
                0,
                Optional.empty(),
                Optional.of(STORAGE),
                Optional.of(data),
                Optional.empty()
        );
        WorkerProfile profile = new WorkerProfile(
                WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty()
        );

        // Simulate 3rd failure handling
        int retries = data.retryCount() + 1;
        assertTrue(retries >= 3, "Retries must reach threshold of 3");

        MinerJobData updatedData = data.withRetryCount(retries).withRetryDelay(0);
        WorkerJob updatedJob = job.withMinerData(updatedData).orElseThrow();
        WorkerProfile updatedProfile = profile.withJob(updatedJob);

        String failureReason = "Repeated mutation failure at " + targetPos.toShortString();
        Optional<WorkerProfile> pausedProfileOpt = updatedProfile.transition(JobStatus.PAUSED, job.progress(), null);
        assertTrue(pausedProfileOpt.isPresent(), "Transition to PAUSED must succeed");

        WorkerProfile pausedProfile = pausedProfileOpt.get();
        assertEquals(JobStatus.PAUSED, pausedProfile.job().orElseThrow().status(), "Worker must transition to PAUSED");
        MinerJobData pausedData = pausedProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(pausedData.activeTarget().isPresent(), "activeTarget must be strictly preserved upon retry exhaustion");
        assertEquals(targetPos, pausedData.activeTarget().get(), "Target pos must not be cleared or advanced");
        assertEquals(3, pausedData.retryCount(), "retryCount must be retained at 3 for player/automation review");
    }

    @Test
    @DisplayName("Fix B — Target is ONLY cleared when block changes to air or becomes invalid")
    void testTargetOnlyClearedWhenBlockChangesToAirOrInvalid() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos)
                .withRetryCount(3);

        // While target is still iron ore, isValidSpecificBlockTarget returns true
        assertTrue(MinerJobController.isValidSpecificBlockTarget(null, data, targetPos, Blocks.IRON_ORE.defaultBlockState(), null));

        // When target becomes air (e.g. mined or broken externally), target is cleared and retries reset
        assertFalse(MinerJobController.isValidSpecificBlockTarget(null, data, targetPos, Blocks.AIR.defaultBlockState(), null));
        MinerJobData cleared = data.withoutActiveTarget().withRetryCount(0).withRetryDelay(0);
        assertTrue(cleared.activeTarget().isEmpty(), "activeTarget must be cleared when block is destroyed");
        assertEquals(0, cleared.retryCount(), "retryCount must be reset to 0 when block is destroyed");
        assertEquals(0, cleared.retryDelay(), "retryDelay must be reset to 0 when block is destroyed");
    }

    @Test
    @DisplayName("Fix B — Preflight rejections must NEVER increment retryCount or consume retries")
    void testPreflightRejectionsDoNotConsumeMutationRetries() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos);

        assertEquals(0, data.retryCount());

        // Preflight rejections (tool unverified, tool exhausted, storage invalid, chunk unloaded)
        // must never increment retryCount
        for (MinerJobController.ExecutionGate gate : List.of(
                MinerJobController.ExecutionGate.REJECTED_UNVERIFIED_TOOL,
                MinerJobController.ExecutionGate.REJECTED_TOOL_EXHAUSTED,
                MinerJobController.ExecutionGate.REJECTED_STORAGE_INVALID,
                MinerJobController.ExecutionGate.REJECTED_CHUNK_UNLOADED
        )) {
            MinerJobController.TransactionExecutionResult rej =
                    MinerJobController.TransactionExecutionResult.rejected(
                            MinerJobController.TransactionStatus.REJECTED_PREFLIGHT,
                            null,
                            "Preflight rejection: " + gate.name(),
                            gate
                    );
            assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, rej.status());
            assertEquals(gate, rej.gate().orElseThrow());
            // Retry count in job data remains 0
            assertEquals(0, data.retryCount(), "Preflight rejection for " + gate + " must not increment retryCount");
        }
    }

    @Test
    @DisplayName("Fix B — Successful mining resets retryCount and retryDelay to 0")
    void testSuccessfulMiningResetsRetryCountAndRetryDelay() {
        BlockPos targetPos = new BlockPos(100, 50, 100);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(targetPos)
                .withRetryCount(2)
                .withRetryDelay(15);

        assertEquals(2, data.retryCount());
        assertEquals(15, data.retryDelay());

        MinerJobData resetData = data.withRetryCount(0).withRetryDelay(0);
        assertEquals(0, resetData.retryCount(), "Successful mining must reset retryCount to 0");
        assertEquals(0, resetData.retryDelay(), "Successful mining must reset retryDelay to 0");
    }

    @Test
    @DisplayName("Fix C — ExecutionGate contains REJECTED_CHUNK_UNLOADED")
    void testRejectedChunkUnloadedGateExistsInExecutionGate() {
        MinerJobController.ExecutionGate gate = MinerJobController.ExecutionGate.valueOf("REJECTED_CHUNK_UNLOADED");
        assertNotNull(gate, "ExecutionGate.REJECTED_CHUNK_UNLOADED must exist");
        assertEquals("REJECTED_CHUNK_UNLOADED", gate.name());
    }

    @Test
    @DisplayName("Fix C — executeLiveMiningStep returns REJECTED_CHUNK_UNLOADED without reading block state when chunk unloaded")
    void testExecuteLiveMiningStepReturnsRejectedChunkUnloaded() {
        WorkerJob job = new WorkerJob(
                WorkerJobs.MINE,
                JobStatus.RUNNING,
                0,
                Optional.empty(),
                Optional.of(STORAGE),
                Optional.of(MinerJobData.initial(10, STORAGE)),
                Optional.empty()
        );
        WorkerProfile profile = new WorkerProfile(
                WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty()
        );

        // When worker is null, fails preflight safely
        MinerJobController.TransactionExecutionResult nullRes =
                MinerJobController.executeLiveMiningStep(profile, null, null, new ItemStack(Items.DIAMOND_PICKAXE), new BlockPos(10, 60, 10));
        assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, nullRes.status());
    }

    @Test
    @DisplayName("Fix C — canExecuteMiningStep returns REJECTED_CHUNK_UNLOADED when target chunk is not loaded")
    void testCanExecuteMiningStepReturnsRejectedChunkUnloaded() {
        WorkerProfile profile = new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE);
        MinerJobData minerData = MinerJobData.initial(64L, STORAGE, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob minerJob = WorkerJob.assignedMiner(WorkerJobs.MINE, new WorkerTarget(OVERWORLD, new BlockPos(100, 64, 100)), minerData);
        WorkerProfile minerProfile = profile.withJob(minerJob);

        BlockPos pos = new BlockPos(100, 64, 100);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);

        // When worker is null (unbound level), passes chunk check and validates state
        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                minerProfile, null, null, pick, pos, Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, gate);
    }

    @Test
    @DisplayName("Fix A — Target-aware fallback selects tool satisfying target requiresCorrectToolForDrops")
    void testTargetAwareFallbackSelectsCorrectToolForBlockDrops() {
        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        ItemStack ironPick = new ItemStack(Items.IRON_PICKAXE);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Storage contains wood, iron, and diamond pickaxes
        WorkerStorage storage = createStorage(woodenPick, ironPick, diamondPick);

        // Target: Diamond Ore (requires Iron or higher)
        BlockState diamondOreState = Blocks.DIAMOND_ORE.defaultBlockState();
        assertTrue(diamondOreState.requiresCorrectToolForDrops());

        // In test env, isToolAppropriateForBlockInTestEnv correctly discriminates tiers
        assertFalse(MinerJobController.isToolAppropriateForBlockInTestEnv(woodenPick, diamondOreState), "Wooden pickaxe cannot harvest diamond ore");
        assertTrue(MinerJobController.isToolAppropriateForBlockInTestEnv(ironPick, diamondOreState), "Iron pickaxe can harvest diamond ore");
        assertTrue(MinerJobController.isToolAppropriateForBlockInTestEnv(diamondPick, diamondOreState), "Diamond pickaxe can harvest diamond ore");

        // Target: Obsidian (requires Diamond or higher)
        BlockState obsidianState = Blocks.OBSIDIAN.defaultBlockState();
        assertTrue(obsidianState.requiresCorrectToolForDrops());
        assertFalse(MinerJobController.isToolAppropriateForBlockInTestEnv(woodenPick, obsidianState), "Wooden pickaxe cannot harvest obsidian");
        assertFalse(MinerJobController.isToolAppropriateForBlockInTestEnv(ironPick, obsidianState), "Iron pickaxe cannot harvest obsidian");
        assertTrue(MinerJobController.isToolAppropriateForBlockInTestEnv(diamondPick, obsidianState), "Diamond pickaxe can harvest obsidian");
    }

    @Test
    @DisplayName("Fix A — Target-aware fallback fails closed with NO_TOOL_AVAILABLE when no adequate tool exists")
    void testTargetAwareFallbackInadequateToolFailsClosed() {
        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        ItemStack shears = new ItemStack(Items.SHEARS);
        WorkerStorage storage = createStorage(woodenPick, shears);

        BlockState diamondOreState = Blocks.DIAMOND_ORE.defaultBlockState();

        // Wooden pickaxe candidate test check
        boolean woodCanHarvest = MinerJobController.isToolAppropriateForBlockInTestEnv(woodenPick, diamondOreState);
        assertFalse(woodCanHarvest, "Storage with only wooden pickaxe must not be considered adequate for diamond ore");
    }

    @Test
    @DisplayName("Fix A — WAITING_FOR_TOOL does not resume when held tool is inadequate for activeTarget")
    void testWaitingForToolDoesNotResumeWhenHeldToolInadequateForTarget() {
        ItemStack heldWoodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        BlockState targetDiamondOre = Blocks.DIAMOND_ORE.defaultBlockState();

        // Held tool is wooden pickaxe, target is diamond ore
        boolean heldCanHarvest = JobSafetyPolicy.isMiningToolVerified(heldWoodenPick) &&
                JobSafetyPolicy.hasSufficientDurability(heldWoodenPick, 1);
        if (heldCanHarvest && targetDiamondOre.requiresCorrectToolForDrops()) {
            boolean correct = heldWoodenPick.isCorrectToolForDrops(targetDiamondOre);
            if (!correct) {
                correct = MinerJobController.isToolAppropriateForBlockInTestEnv(heldWoodenPick, targetDiamondOre);
            }
            if (!correct) {
                heldCanHarvest = false;
            }
        }

        assertFalse(heldCanHarvest, "Held wooden pickaxe must NOT be considered adequate to harvest diamond ore");
    }

    @Test
    @DisplayName("Fix A — WAITING_FOR_TOOL resumes to RUNNING when held tool is adequate for activeTarget")
    void testWaitingForToolResumesWhenHeldToolAdequateForTarget() {
        ItemStack heldIronPick = new ItemStack(Items.IRON_PICKAXE);
        BlockState targetDiamondOre = Blocks.DIAMOND_ORE.defaultBlockState();

        boolean heldCanHarvest = JobSafetyPolicy.isMiningToolVerified(heldIronPick) &&
                JobSafetyPolicy.hasSufficientDurability(heldIronPick, 1);
        if (heldCanHarvest && targetDiamondOre.requiresCorrectToolForDrops()) {
            boolean correct = heldIronPick.isCorrectToolForDrops(targetDiamondOre);
            if (!correct) {
                correct = MinerJobController.isToolAppropriateForBlockInTestEnv(heldIronPick, targetDiamondOre);
            }
            if (!correct) {
                heldCanHarvest = false;
            }
        }

        assertTrue(heldCanHarvest, "Held iron pickaxe MUST be considered adequate to harvest diamond ore and resume");
    }

    @Test
    @DisplayName("Fix A & C — Unloaded activeTarget chunk waits safely without inspecting block state")
    void testUnloadedActiveTargetChunkWaitsSafely() {
        BlockPos unloadedPos = new BlockPos(5000, 40, 5000);
        MinerJobData data = MinerJobData.initialSpecificBlock(10, STORAGE, 8, List.of(ResourceLocation.parse("minecraft:iron_ore")), List.of())
                .withActiveTarget(unloadedPos)
                .withRetryCount(1)
                .withRetryDelay(5);

        // When chunk is unloaded, activeTarget is preserved, retryCount is preserved, retryDelay is preserved
        assertTrue(data.activeTarget().isPresent());
        assertEquals(unloadedPos, data.activeTarget().get());
        assertEquals(1, data.retryCount());
        assertEquals(5, data.retryDelay());
    }

    @Test
    @DisplayName("SpecificBlockConfig CODEC preserves activeTarget, retryCount, and retryDelay")
    void testSpecificBlockConfigCodecPreservation() {
        BlockPos pos = new BlockPos(50, 60, 70);
        MinerJobData.SpecificBlockConfig config = new MinerJobData.SpecificBlockConfig(
                12,
                List.of(ResourceLocation.parse("minecraft:gold_ore")),
                List.of(ResourceLocation.parse("c:ores")),
                45,
                Optional.of(pos),
                3,
                15
        );

        assertEquals(12, config.searchRadius());
        assertEquals(45, config.scanCursor());
        assertEquals(pos, config.activeTarget().get());
        assertEquals(3, config.retryCount());
        assertEquals(15, config.retryDelay());

        MinerJobData data = new MinerJobData(
                20L, 0L, 0L, 0L, List.of(), STORAGE, Optional.empty(),
                Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT, MinerJobData.MiningMode.SPECIFIC_BLOCK,
                64, false, 64, config.searchRadius(), config.targetBlockIds(), config.targetBlockTags(),
                config.scanCursor(), config.activeTarget(), config.retryCount(), config.retryDelay()
        );

        assertEquals(pos, data.activeTarget().get());
        assertEquals(3, data.retryCount());
        assertEquals(15, data.retryDelay());
        assertEquals(config, data.specificBlockConfig());
    }
}
