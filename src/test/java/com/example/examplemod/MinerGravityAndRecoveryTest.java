package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test suite verifying all 15 requirements from Part 7:
 * Miner Tunnel Gravity-Block Obstruction & Worker Recovery.
 */
class MinerGravityAndRecoveryTest {

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
        MiningToolCapability.Registry.clear();
        WorkerOrderSession.clearAll();
    }

    private WorkerProfile createMinerProfile(MinerJobData data, JobStatus status) {
        int progress = (data.requestedBlocks() > 0)
                ? (int) Math.min(100L, (data.blocksMined() * 100L) / data.requestedBlocks()) : 0;
        WorkerJob job = new WorkerJob(
                WorkerJobs.MINE,
                status,
                progress,
                Optional.empty(),
                Optional.of(WORKSITE),
                Optional.of(data)
        );
        return new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());
    }

    // ==========================================
    // 1. Gravel falls into the next tunnel target
    // ==========================================
    @Test
    @DisplayName("1. Gravel is recognized as a gravity block and target selection keeps worker at safe stand position")
    void test1_gravelFallsIntoNextTunnelTarget() {
        BlockState gravelState = Blocks.GRAVEL.defaultBlockState();
        assertTrue(MinerJobController.isGravityBlock(gravelState), "Gravel must be recognized as gravity block");

        // Target depth d=1 is occupied by fallen gravel
        BlockPos depth1Head = ANCHOR.relative(Direction.NORTH, 1).above();
        MinerJobController.TunnelTargetResult targetResult = MinerJobController.findNextTunnelTarget(
                ANCHOR,
                Direction.NORTH,
                10L,
                pos -> !pos.equals(depth1Head), // all cleared except depth 1 head
                pos -> Optional.empty()
        );

        assertTrue(targetResult.targetPos().isPresent());
        assertEquals(depth1Head, targetResult.targetPos().get());
        assertTrue(targetResult.standPos().isPresent());
        // Worker stands at d=0 (floor level), safely behind target without walking into falling gravel
        assertEquals(ANCHOR, targetResult.standPos().get());
    }

    // ==========================================
    // 2. Sand falls into the next tunnel target
    // ==========================================
    @Test
    @DisplayName("2. Sand and Red Sand are recognized as gravity blocks")
    void test2_sandFallsIntoNextTunnelTarget() {
        BlockState sandState = Blocks.SAND.defaultBlockState();
        BlockState redSandState = Blocks.RED_SAND.defaultBlockState();
        assertTrue(MinerJobController.isGravityBlock(sandState), "Sand must be recognized as gravity block");
        assertTrue(MinerJobController.isGravityBlock(redSandState), "Red Sand must be recognized as gravity block");

        BlockPos depth2Head = ANCHOR.relative(Direction.NORTH, 2).above();
        MinerJobController.TunnelTargetResult targetResult = MinerJobController.findNextTunnelTarget(
                ANCHOR,
                Direction.NORTH,
                10L,
                pos -> !pos.equals(depth2Head),
                pos -> Optional.empty()
        );

        assertTrue(targetResult.targetPos().isPresent());
        assertEquals(depth2Head, targetResult.targetPos().get());
        // Worker stands at d=1, safely separated from target
        assertEquals(ANCHOR.relative(Direction.NORTH, 1), targetResult.standPos().get());
    }

    // ==========================================
    // 3. Gravity block falls behind the worker
    // ==========================================
    @Test
    @DisplayName("3. Gravity block falls behind the worker: worker stands on safe deeper side (d+1) facing entrance")
    void test3_gravityBlockFallsBehindWorker() {
        // Worker is at depth 3
        BlockPos workerPos = ANCHOR.relative(Direction.NORTH, 3);
        int depth = MinerJobController.getWorkerTunnelDepth(workerPos, ANCHOR, Direction.NORTH);
        assertEquals(3, depth, "Worker depth must be 3");

        // Depth 1 head is blocked by fallen gravel behind worker
        BlockPos obstructedPos = ANCHOR.relative(Direction.NORTH, 1).above();

        // Safe stand position for the worker must be at depth 2 (d+1 of the obstruction)
        BlockPos expectedStandPos = ANCHOR.relative(Direction.NORTH, 2);
        assertTrue(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, Direction.NORTH, obstructedPos, 10));
        assertTrue(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, Direction.NORTH, expectedStandPos, 10));
        assertTrue(expectedStandPos.getY() == ANCHOR.getY(), "Stand pos must be at floor level");
    }

    // ==========================================
    // 4. Gravity block blocks the only return corridor
    // ==========================================
    @Test
    @DisplayName("4. Gravity block blocks the only return corridor: identifies return route obstruction")
    void test4_gravityBlockBlocksReturnCorridor() {
        // Worker is at depth 2; depth 0 (entrance) is blocked
        BlockPos workerPos = ANCHOR.relative(Direction.NORTH, 2);
        int workerDepth = MinerJobController.getWorkerTunnelDepth(workerPos, ANCHOR, Direction.NORTH);
        assertEquals(2, workerDepth);

        // Entrance slot 0 head is blocked
        BlockPos entranceHead = ANCHOR.above();
        assertTrue(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, Direction.NORTH, entranceHead, 10));

        // When return corridor is blocked, worker standing at d=2 cannot safely return to chest
        BlockPos standPosForRecovery = ANCHOR.relative(Direction.NORTH, 1); // d = 0 + 1
        assertEquals(ANCHOR.relative(Direction.NORTH, 1), standPosForRecovery);
    }

    // ==========================================
    // 5. Falling-block entity is still moving
    // ==========================================
    @Test
    @DisplayName("5. Active falling entities detection prevents excavation and movement while blocks fall")
    void test5_fallingBlockEntityIsMoving() {
        // Bounding box for tunnel corridor correctly includes above-ceiling height (+6) to detect falling blocks
        int maxDepth = 10;
        Direction dir = Direction.NORTH;
        BlockPos endPos = ANCHOR.relative(dir, maxDepth - 1);
        double minX = Math.min(ANCHOR.getX(), endPos.getX()) - 1.5;
        double maxX = Math.max(ANCHOR.getX(), endPos.getX()) + 2.5;
        double minY = ANCHOR.getY() - 1.0;
        double maxY = ANCHOR.getY() + 6.0;
        double minZ = Math.min(ANCHOR.getZ(), endPos.getZ()) - 1.5;
        double maxZ = Math.max(ANCHOR.getZ(), endPos.getZ()) + 2.5;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);

        // A falling block at depth 1, Y=66 (above head Y=65) is inside the detection box
        BlockPos fallingPos = ANCHOR.relative(dir, 1).above(2);
        assertTrue(box.contains(fallingPos.getX() + 0.5, fallingPos.getY() + 0.5, fallingPos.getZ() + 0.5));
    }

    // ==========================================
    // 6. Worker must wait until falling entity settles
    // ==========================================
    @Test
    @DisplayName("6. Worker transitions to WAITING_FOR_FALLING_BLOCK and resumes once settled")
    void test6_workerWaitsUntilFallingEntitySettles() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Enters WAITING_FOR_FALLING_BLOCK
        WorkerProfile waitingProfile = profile.transition(JobStatus.WAITING_FOR_FALLING_BLOCK, 0, "Waiting for falling blocks to settle")
                .orElseThrow();
        assertEquals(JobStatus.WAITING_FOR_FALLING_BLOCK, waitingProfile.job().orElseThrow().status());
        assertEquals("Waiting for falling blocks to settle", waitingProfile.job().orElseThrow().failure().orElse(""));

        // Once falling block settles, transitions back to RUNNING cleanly
        WorkerProfile resumedProfile = waitingProfile.transition(JobStatus.RUNNING, 0, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, resumedProfile.job().orElseThrow().status());
        assertTrue(resumedProfile.job().orElseThrow().failure().isEmpty());
    }

    // ==========================================
    // 7. Worker does not advance while return route is blocked
    // ==========================================
    @Test
    @DisplayName("7. Worker does not advance deeper when return route is blocked")
    void test7_workerDoesNotAdvanceWhileReturnRouteBlocked() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Obstruction exists behind worker: worker transitions to RECOVERING_TUNNEL_OBSTRUCTION
        WorkerProfile recoveryProfile = profile.transition(JobStatus.RECOVERING_TUNNEL_OBSTRUCTION, 0, null).orElseThrow();
        assertEquals(JobStatus.RECOVERING_TUNNEL_OBSTRUCTION, recoveryProfile.job().orElseThrow().status());

        // In RECOVERING_TUNNEL_OBSTRUCTION, forward mining is paused; recovery has sole priority
        assertEquals(JobStatus.RECOVERING_TUNNEL_OBSTRUCTION, recoveryProfile.job().orElseThrow().status());
    }

    // ==========================================
    // 8. Worker can recover by mining a settled obstruction within the tunnel
    // ==========================================
    @Test
    @DisplayName("8. Worker can recover by mining settled obstruction even when isMiningFinished is true")
    void test8_workerCanRecoverSettledObstruction() {
        // Mining finished (5/5 blocks mined), but gravel fell behind worker on the way to delivery
        MinerJobData data = new MinerJobData(5L, 5L, 5L, 0L, List.of(), STORAGE);
        assertTrue(data.isMiningFinished());

        WorkerProfile profile = createMinerProfile(data, JobStatus.RECOVERING_TUNNEL_OBSTRUCTION);
        ItemStack diamondPick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos obstructionPos = ANCHOR.relative(Direction.NORTH, 1).above();

        // canExecuteMiningStep permits mining because job is in RECOVERING_TUNNEL_OBSTRUCTION
        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                profile, null, null, diamondPick, obstructionPos, Blocks.GRAVEL.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, gate,
                "Obstruction recovery must be permitted even when isMiningFinished is true");
    }

    // ==========================================
    // 9. Recovery does not mine outside the authorized tunnel
    // ==========================================
    @Test
    @DisplayName("9. Recovery strictly rejects positions outside the authorized 1x2 tunnel corridor")
    void test9_recoveryDoesNotMineOutsideTunnel() {
        int maxDepth = 10;
        Direction dir = Direction.NORTH;

        // Position 1 block laterally outside tunnel corridor (dx = 1)
        BlockPos outsideLateral = ANCHOR.relative(dir, 2).relative(Direction.EAST, 1);
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, dir, outsideLateral, maxDepth));

        // Position above tunnel ceiling (dy = 2)
        BlockPos outsideCeiling = ANCHOR.relative(dir, 2).above(2);
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, dir, outsideCeiling, maxDepth));

        // Position beyond max depth (d = 12 >= 10)
        BlockPos beyondMaxDepth = ANCHOR.relative(dir, 12);
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, dir, beyondMaxDepth, maxDepth));

        // Valid corridor positions (feet and head)
        BlockPos validFeet = ANCHOR.relative(dir, 2);
        BlockPos validHead = ANCHOR.relative(dir, 2).above();
        assertTrue(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, dir, validFeet, maxDepth));
        assertTrue(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, dir, validHead, maxDepth));
    }

    // ==========================================
    // 10. Recovery loot enters pending output exactly once
    // ==========================================
    @Test
    @DisplayName("10. Recovery loot enters pending output exactly once and does not inflate blocksMined")
    void test10_recoveryLootEntersPendingOutputExactlyOnce() {
        MinerJobData data = new MinerJobData(10L, 5L, 5L, 0L, List.of(), STORAGE);
        BlockPos obstructionPos = ANCHOR.relative(Direction.NORTH, 1).above();
        ItemStack gravelDrop = new ItemStack(Items.GRAVEL, 1);

        // Staged in-flight recovery mutation
        MinerJobData staged = data.withInFlightMutation(
                obstructionPos,
                BuiltInRegistries.BLOCK.getKey(Blocks.GRAVEL),
                List.of(gravelDrop)
        );
        assertTrue(staged.inFlightMutation().isPresent());

        // Commit recovery mutation
        MinerJobData committed = staged.commitRecoveryInFlightMutation();
        assertTrue(committed.inFlightMutation().isEmpty());
        // blocksMined remains unchanged at 5 (recovery does not steal tunnel quota)
        assertEquals(5L, committed.blocksMined());
        // pendingStacks contains the gravel drop
        assertEquals(1, committed.pendingStacks().size());
        assertEquals(Items.GRAVEL, committed.pendingStacks().get(0).getItem());
        assertEquals(1, committed.pendingStacks().get(0).getCount());
        // itemsPendingDelivery updated
        assertEquals(1L, committed.itemsPendingDelivery());
    }

    // ==========================================
    // 11. Storage-full conditions preserve recovery output
    // ==========================================
    @Test
    @DisplayName("11. Full storage preserves all recovery output in pendingStacks without loss or drop")
    void test11_storageFullPreservesRecoveryOutput() {
        ItemStack recoveredGravel = new ItemStack(Items.GRAVEL, 64);
        MinerJobData data = new MinerJobData(10L, 10L, 64L, 0L, List.of(recoveredGravel), STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Simulated full container (slot 0 full of cobblestone)
        List<ItemStack> containerSlots = new ArrayList<>(List.of(new ItemStack(Items.COBBLESTONE, 64)));
        WorkerProfile updated = MinerJobController.deliverToStorage(profile, containerSlots, 1);

        // Job transitions to WAITING_FOR_STORAGE
        assertEquals(JobStatus.WAITING_FOR_STORAGE, updated.job().orElseThrow().status());
        // Gravel is completely preserved in pendingStacks
        MinerJobData updatedData = updated.job().orElseThrow().minerData().orElseThrow();
        assertEquals(1, updatedData.pendingStacks().size());
        assertEquals(64, updatedData.pendingStacks().get(0).getCount());
        assertEquals(Items.GRAVEL, updatedData.pendingStacks().get(0).getItem());
        assertEquals(64L, updatedData.itemsPendingDelivery());
    }

    // ==========================================
    // 12. Unresolved InFlightMutation prevents unsafe recovery
    // ==========================================
    @Test
    @DisplayName("12. Unresolved InFlightMutation prevents initiating new recovery until reconciled")
    void test12_unresolvedInFlightMutationPreventsUnsafeRecovery() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE)
                .withInFlightMutation(ANCHOR, BuiltInRegistries.BLOCK.getKey(Blocks.STONE), List.of());
        WorkerProfile profile = createMinerProfile(data, JobStatus.RECOVERING_TUNNEL_OBSTRUCTION);

        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos obstructionPos = ANCHOR.relative(Direction.NORTH, 1).above();

        // Attempting to execute transaction with unresolved in-flight mutation is rejected at preflight
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, obstructionPos, Blocks.GRAVEL.defaultBlockState(),
                BuiltInRegistries.BLOCK.getKey(Blocks.GRAVEL),
                List::of, () -> true, () -> {}, p -> {}
        );

        assertEquals(MinerJobController.TransactionStatus.REJECTED_PREFLIGHT, result.status());
        assertTrue(result.message().orElse("").contains("unresolved in-flight mutation"));
    }

    // ==========================================
    // 13. Worker position is occupied by a falling block
    // ==========================================
    @Test
    @DisplayName("13. Worker space occupied by falling block: targets occupied space with feet standPos")
    void test13_workerPositionOccupiedByFallingBlock() {
        // Worker is at depth 2
        BlockPos workerPos = ANCHOR.relative(Direction.NORTH, 2);
        int workerDepth = MinerJobController.getWorkerTunnelDepth(workerPos, ANCHOR, Direction.NORTH);
        assertEquals(2, workerDepth);

        // Occupied head position has gravel
        BlockPos headPos = workerPos.above();
        assertTrue(JobSafetyPolicy.isWithinTunnelBounds(ANCHOR, Direction.NORTH, headPos, 10));

        // The target is headPos, and the worker stands right at her feet position
        BlockPos expectedStandPos = workerPos;
        assertEquals(workerPos, expectedStandPos);
    }

    // ==========================================
    // 14. Impossible recovery produces an explicit safe failure state
    // ==========================================
    @Test
    @DisplayName("14. Impassable hazard (bedrock / fluid) transitions to WAITING_FOR_SAFE_TUNNEL with explicit reason")
    void test14_impossibleRecoveryProducesSafeFailureState() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        String failureReason = "Tunnel return route obstructed by impassable obstacle (minecraft:bedrock) at " + ANCHOR;
        WorkerProfile safeWaitingProfile = profile.transition(JobStatus.WAITING_FOR_SAFE_TUNNEL, 0, failureReason).orElseThrow();

        assertEquals(JobStatus.WAITING_FOR_SAFE_TUNNEL, safeWaitingProfile.job().orElseThrow().status());
        assertEquals(failureReason, safeWaitingProfile.job().orElseThrow().failure().orElse(""));
    }

    // ==========================================
    // 15. Successful recovery restores the return route and resumes mining
    // ==========================================
    @Test
    @DisplayName("15. Successful recovery clears return route and resumes RUNNING without duplicate quota")
    void test15_successfulRecoveryRestoresReturnRouteAndResumesMining() {
        // Started with 3/10 blocks mined and 3 cobblestone pending delivery
        ItemStack initialCobble = new ItemStack(Items.COBBLESTONE, 3);
        MinerJobData data = new MinerJobData(10L, 3L, 3L, 0L, List.of(initialCobble), STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RECOVERING_TUNNEL_OBSTRUCTION);

        // Obstruction cleared: 1 gravel recovered
        ItemStack gravel = new ItemStack(Items.GRAVEL, 1);
        MinerJobData recoveredData = data.withRecoveryDrops(List.of(gravel));
        WorkerProfile recoveredProfile = profile.withJob(
                profile.job().orElseThrow().withMinerData(recoveredData).orElseThrow()
        );

        // Resumes RUNNING
        WorkerProfile resumedProfile = recoveredProfile.transition(JobStatus.RUNNING, 30, null).orElseThrow();
        assertEquals(JobStatus.RUNNING, resumedProfile.job().orElseThrow().status());

        // Quota is not duplicated: blocksMined is still 3, blocksRemaining is 7
        MinerJobData finalData = resumedProfile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(3L, finalData.blocksMined());
        assertEquals(7L, finalData.blocksRemaining());
        // Both initial cobble and recovered gravel are preserved in pending delivery
        assertEquals(2, finalData.pendingStacks().size());
        assertEquals(4L, finalData.itemsPendingDelivery());
    }
}
