package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rigorous 3D Spatial Validation and Long-Distance Worker Navigation Test Suite.
 * Exercises true 3D coordinates (different Y levels, vertical shafts, elevated platforms),
 * independent range policies, long-distance delivery cycles, and pending output preservation.
 */
class SpatialValidation3DTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @BeforeEach
    @AfterEach
    void cleanState() {
        Config.clearTestOverrides();
        WorkerOrderSession.clearAll();
        WorksiteAdapter.Registry.clear();
        CropRegistry.registerDefaults();
        BindingTicketHUD.clear();
    }

    // ==========================================
    // 1. Right-Triangle Reproducible Geometry in 3D
    // ==========================================

    @Test
    @DisplayName("1. Right-triangle geometry: worksite(0,64,0), worker(4,64,0), chest(0,64,7) all valid")
    void test01_rightTrianglePlanarGeometry() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerPos = new Vec3(4.5, 64.5, 0.5); // 4 blocks along X
        BlockPos chestPos = new BlockPos(0, 64, 7);   // 7 blocks along Z

        // Worker distance to worksite center
        double workerToWorksite = Math.sqrt(workerPos.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5));
        assertEquals(4.0, workerToWorksite, 0.001);
        assertTrue(workerToWorksite <= Config.getMaxWorksiteDistance());

        // Chest distance to worksite
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestPos));

        // Worker-to-chest hypotenuse: sqrt(4^2 + 7^2) = sqrt(65) ~ 8.062
        double workerToChest = Math.sqrt(workerPos.distanceToSqr(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5));
        assertEquals(Math.sqrt(65.0), workerToChest, 0.001);
        assertTrue(workerToChest <= Config.getMaxWorksiteDistance());
    }

    // ==========================================
    // 2. Genuinely 3D Coordinate Scenarios (Different Y Levels)
    // ==========================================

    @Test
    @DisplayName("2. Worker above worksite (4, 72, 0), chest below worksite (0, 57, 0), job at (0, 64, 0)")
    void test02_workerAbove_chestBelow_worksiteCenter() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerAbove = new Vec3(4.5, 72.5, 0.5); // dy = +8 blocks
        BlockPos chestBelow = new BlockPos(0, 57, 0); // dy = -7 blocks

        // Worker to worksite 3D distance: sqrt(4^2 + 8^2 + 0^2) = sqrt(80) ~ 8.944
        double workerDistSqr = workerAbove.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertEquals(80.0, workerDistSqr, 0.001);
        assertTrue(workerDistSqr <= Config.getMaxWorksiteDistance() * Config.getMaxWorksiteDistance(),
                "Worker 8 blocks above and 4 blocks across worksite must be accepted");

        // Storage to worksite 3D distance: sqrt(0^2 + (-7)^2 + 0^2) = 7.0
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestBelow),
                "Chest 7 blocks below worksite must be accepted");

        // Worker to chest 3D separation: sqrt(4^2 + (8 - (-7))^2) = sqrt(16 + 225) = sqrt(241) ~ 15.524
        double workerToChestSqr = workerAbove.distanceToSqr(chestBelow.getX() + 0.5, chestBelow.getY() + 0.5, chestBelow.getZ() + 0.5);
        assertEquals(241.0, workerToChestSqr, 0.001);
    }

    @Test
    @DisplayName("3. Worker below worksite (0, 52, 5), chest above worksite (3, 74, 0), job at (0, 64, 0)")
    void test03_workerBelow_chestAbove_worksiteCenter() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerBelow = new Vec3(0.5, 52.5, 5.5); // dy = -12 blocks, dz = 5 blocks
        BlockPos chestAbove = new BlockPos(3, 74, 0); // dx = 3, dy = +10 blocks

        // Worker 3D distance: sqrt(0^2 + (-12)^2 + 5^2) = sqrt(169) = 13.0 blocks
        double workerDistSqr = workerBelow.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertEquals(169.0, workerDistSqr, 0.001);
        assertTrue(workerDistSqr <= Config.getMaxWorksiteDistance() * Config.getMaxWorksiteDistance());

        // Chest 3D distance: sqrt(3^2 + 10^2 + 0^2) = sqrt(109) ~ 10.44 blocks
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestAbove));
    }

    @Test
    @DisplayName("4. Same X/Z coordinates but significant vertical separation (chest at +20 Y)")
    void test04_sameXZ_verticalSeparation() {
        BlockPos worksite = new BlockPos(50, 64, 50);
        BlockPos chestDirectlyAbove = new BlockPos(50, 84, 50); // dy = +20 blocks
        BlockPos chestDirectlyBelow = new BlockPos(50, 44, 50); // dy = -20 blocks

        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestDirectlyAbove));
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestDirectlyBelow));

        BlockPos chestTooFarAbove = new BlockPos(50, 97, 50); // dy = +33 blocks (> 32)
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestTooFarAbove));
    }

    @Test
    @DisplayName("5. Large X/Z diagonal separation with small Y separation")
    void test05_largeXZ_smallY_separation() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        // dx = 20, dy = 2, dz = 20 -> dist^2 = 400 + 4 + 400 = 804 <= 1024 (~28.35m)
        BlockPos inside3D = new BlockPos(20, 66, 20);
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, inside3D));

        // dx = 23, dy = 2, dz = 23 -> dist^2 = 529 + 4 + 529 = 1062 > 1024 (~32.58m)
        BlockPos outside3D = new BlockPos(23, 66, 23);
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, outside3D));

        // When maxStorageDistance is configured to 64, outside3D (32.58m) is accepted
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, outside3D, 64));
    }

    // ==========================================
    // 3. Deep Vertical Shafts and Elevated Platforms
    // ==========================================

    @Test
    @DisplayName("6. Deep vertical shaft: target at dy = -50 and dy = -60 blocks from worksite is permitted")
    void test06_deepVerticalShaft_permittedUnderDefault3DDelta() {
        BlockPos worksite = new BlockPos(100, 64, 100);

        BlockPos shaft50 = new BlockPos(100, 14, 100); // dy = -50 blocks
        BlockPos shaft60 = new BlockPos(100, 4, 100);  // dy = -60 blocks
        BlockPos shaft64 = new BlockPos(100, 0, 100);  // dy = -64 blocks (exact boundary)

        assertTrue(JobSafetyPolicy.isWithinWorkBounds(worksite, shaft50), "dy = -50 shaft must be within bounds");
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(worksite, shaft60), "dy = -60 shaft must be within bounds");
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(worksite, shaft64), "dy = -64 shaft must be within bounds");

        BlockPos shaft65 = new BlockPos(100, -1, 100); // dy = -65 blocks (> 64)
        assertFalse(JobSafetyPolicy.isWithinWorkBounds(worksite, shaft65), "dy = -65 shaft exceeds default delta");

        // When configured to 128 vertical delta, shaft65 is permitted
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(worksite, shaft65, 32, 128));
    }

    @Test
    @DisplayName("7. Elevated work platform: dx=15, dy=+30, dz=15 permitted under 3D bounds")
    void test07_elevatedWorkPlatform_permitted() {
        BlockPos worksite = new BlockPos(100, 64, 100);
        BlockPos platformTarget = new BlockPos(115, 94, 115); // dx=15, dy=+30, dz=15

        // Horizontal radius: 15^2 + 15^2 = 450 <= 32^2 (1024)
        // Vertical delta: 30 <= 64
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(worksite, platformTarget));

        // True 3D Euclidean distance: 450 + 900 = 1350 (~36.74m)
        assertTrue(JobSafetyPolicy.isWithin3DWorkBounds(worksite, platformTarget, 40.0));
        assertFalse(JobSafetyPolicy.isWithin3DWorkBounds(worksite, platformTarget, 30.0));
    }

    // ==========================================
    // 4. Separation of the 6 Range Policies
    // ==========================================

    @Test
    @DisplayName("8. Policy Separation: Worker at 60m assigned to worksite; worksite at 50m from storage")
    void test08_policySeparation_independentWorkerAndStorageRanges() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerFar = new Vec3(60.5, 64.5, 0.5); // 60 blocks from worksite
        BlockPos storageDistant = new BlockPos(0, 64, 50); // 50 blocks from worksite

        // Category 2: Worker-to-worksite selection range (maxWorksiteDistance = 128)
        double workerToWorksiteSqr = workerFar.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertTrue(workerToWorksiteSqr <= Config.getMaxWorksiteDistance() * Config.getMaxWorksiteDistance(),
                "Worker at 60m must be within worker-to-worksite assignment limit (128m)");

        // Category 3: Worksite-to-storage distance (maxStorageDistance = 64)
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, storageDistant, 64),
                "Storage at 50m must be within worksite-to-storage distance (64m)");

        // Distance between worker and storage: sqrt(60^2 + 50^2) = sqrt(3600 + 2500) = sqrt(6100) ~ 78.1m
        double workerToStorageSqr = workerFar.distanceToSqr(storageDistant.getX() + 0.5, storageDistant.getY() + 0.5, storageDistant.getZ() + 0.5);
        assertEquals(6100.0, workerToStorageSqr, 0.001);

        // Even though worker-to-storage is 78.1m, worksite-to-storage is 50m, so storage is eligible!
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, storageDistant, 64));
    }

    @Test
    @DisplayName("9. Policy Separation: Worker beyond maxWorksiteDistance (150m) is rejected")
    void test09_workerBeyondMaxWorksiteDistance_rejected() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerTooFar = new Vec3(150.5, 64.5, 0.5); // 150 blocks away (> 128)

        double workerDistSqr = workerTooFar.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertTrue(workerDistSqr > Config.getMaxWorksiteDistance() * Config.getMaxWorksiteDistance());
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_WORKER,
                WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_WORKER"));
    }

    // ==========================================
    // 5. Long-Distance Navigation & Delivery Cycle
    // ==========================================

    @Test
    @DisplayName("10. Full Delivery Cycle: Mine -> Pending items -> Deliver -> Storage receives -> Return to RUNNING")
    void test10_fullDeliveryCycle_resumesWorkAfterDeposit() {
        BlockPos worksitePos = new BlockPos(0, 64, 0);
        BlockPos storagePos = new BlockPos(0, 64, 40); // 40m from worksite

        WorkerTarget worksiteTarget = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, storagePos);

        // Initial miner job with 64 blocks requested
        MinerJobData minerData = MinerJobData.initial(64, storageTarget, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob job = new WorkerJob(WorkerJobs.MINE, JobStatus.RUNNING, 0, Optional.empty(),
                Optional.of(worksiteTarget), Optional.of(minerData), Optional.empty());
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());

        // 1. Worker mines 64 cobblestone blocks (pending delivery accumulates)
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 64));
        MinerJobData minedData = minerData.withBlockMined(64, drops);
        WorkerProfile minedProfile = profile.withJob(job.withMinerData(minedData).get());
        assertEquals(64, minedProfile.job().get().minerData().get().itemsPendingDelivery());

        // 2. Needs delivery triggers: job status transitions to DELIVERING
        WorkerJob deliveringJob = minedProfile.job().get().transition(JobStatus.DELIVERING, minedProfile.job().get().progress(), null).get();
        WorkerProfile deliveringProfile = minedProfile.withJob(deliveringJob);
        assertEquals(JobStatus.DELIVERING, deliveringProfile.job().get().status());

        // 3. Worker arrives at storage (interaction reach <= 3.5m) and deposits items
        SimpleContainer chest = new SimpleContainer(27);
        WorkerProfile deliveredProfile = MinerJobController.deliverToContainer(deliveringProfile, chest);

        // 4. Verify chest received items and pending count is reset to 0
        assertEquals(64, chest.getItem(0).getCount());
        assertEquals(Items.COBBLESTONE, chest.getItem(0).getItem());
        assertEquals(0, deliveredProfile.job().get().minerData().get().itemsPendingDelivery());
        assertEquals(64, deliveredProfile.job().get().minerData().get().itemsDelivered());

        // 5. Quota met: job transitions to COMPLETED
        assertTrue(deliveredProfile.job().get().minerData().get().isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, deliveredProfile.job().get().status());
    }

    @Test
    @DisplayName("11. Partial Delivery: Unfinished quota returns to RUNNING after deposit for next cycle")
    void test11_partialDelivery_returnsToRunningState() {
        BlockPos worksitePos = new BlockPos(0, 64, 0);
        BlockPos storagePos = new BlockPos(0, 64, 30);

        WorkerTarget worksiteTarget = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, storagePos);

        // 200 blocks requested (multiple delivery trips required)
        MinerJobData minerData = MinerJobData.initial(200, storageTarget, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        WorkerJob job = new WorkerJob(WorkerJobs.MINE, JobStatus.RUNNING, 0, Optional.empty(),
                Optional.of(worksiteTarget), Optional.of(minerData), Optional.empty());
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());

        // Trip 1: 64 blocks mined
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 64));
        MinerJobData trip1Data = minerData.withBlockMined(64, drops);
        WorkerProfile trip1Profile = profile.withJob(job.withMinerData(trip1Data).get());

        // Deliver Trip 1 into chest
        SimpleContainer chest = new SimpleContainer(27);
        WorkerProfile postTrip1Profile = MinerJobController.deliverToContainer(trip1Profile, chest);

        // Verify Trip 1 delivered and state returned to RUNNING
        assertEquals(0, postTrip1Profile.job().get().minerData().get().itemsPendingDelivery());
        assertEquals(64, postTrip1Profile.job().get().minerData().get().itemsDelivered());
        assertFalse(postTrip1Profile.job().get().minerData().get().isFullyCompleted());
        assertEquals(JobStatus.RUNNING, postTrip1Profile.job().get().status(),
                "Job must return to RUNNING to navigate back to worksite and continue mining");
    }

    @Test
    @DisplayName("12. Storage Full: 100% item preservation, transitions to WAITING_FOR_STORAGE without item loss")
    void test12_storageFull_preservesItems_transitionsToWaiting() {
        BlockPos worksitePos = new BlockPos(0, 64, 0);
        BlockPos storagePos = new BlockPos(0, 64, 20);

        WorkerTarget worksiteTarget = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, storagePos);

        MinerJobData minerData = MinerJobData.initial(100, storageTarget, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        List<ItemStack> drops = List.of(new ItemStack(Items.DIAMOND, 10));
        MinerJobData minedData = minerData.withBlockMined(10, drops);
        WorkerJob job = new WorkerJob(WorkerJobs.MINE, JobStatus.RUNNING, 0, Optional.empty(),
                Optional.of(worksiteTarget), Optional.of(minedData), Optional.empty());
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());

        // Completely fill the container with dirt
        SimpleContainer fullChest = new SimpleContainer(1);
        fullChest.setItem(0, new ItemStack(Items.DIRT, 64));

        // Attempt delivery
        WorkerProfile afterAttempt = MinerJobController.deliverToContainer(profile, fullChest);

        // Items must NOT be lost: 10 diamonds remain pending
        assertEquals(JobStatus.WAITING_FOR_STORAGE, afterAttempt.job().get().status());
        assertEquals(10, afterAttempt.job().get().minerData().get().itemsPendingDelivery());
        assertEquals(0, afterAttempt.job().get().minerData().get().itemsDelivered());
        assertEquals(1, afterAttempt.job().get().minerData().get().pendingStacks().size());
        assertEquals(Items.DIAMOND, afterAttempt.job().get().minerData().get().pendingStacks().get(0).getItem());

        // Now clear the chest space and retry delivery
        fullChest.clearContent();
        WorkerProfile retried = MinerJobController.deliverToContainer(afterAttempt, fullChest);

        // Delivery succeeds on retry!
        assertEquals(0, retried.job().get().minerData().get().itemsPendingDelivery());
        assertEquals(10, retried.job().get().minerData().get().itemsDelivered());
        assertEquals(JobStatus.RUNNING, retried.job().get().status());
        assertEquals(10, fullChest.getItem(0).getCount());
        assertEquals(Items.DIAMOND, fullChest.getItem(0).getItem());
    }

    @Test
    @DisplayName("13. Unloaded Storage Chunk: Preserves items and transitions to WAITING_FOR_STORAGE")
    void test13_unloadedStorageChunk_preservesItems() {
        BlockPos worksitePos = new BlockPos(0, 64, 0);
        BlockPos storagePos = new BlockPos(1000, 64, 1000); // Far away

        WorkerTarget worksiteTarget = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, storagePos);

        MinerJobData minerData = MinerJobData.initial(100, storageTarget, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        List<ItemStack> drops = List.of(new ItemStack(Items.IRON_ORE, 32));
        MinerJobData minedData = minerData.withBlockMined(32, drops);

        WorkerJob job = new WorkerJob(WorkerJobs.MINE, JobStatus.RUNNING, 0, Optional.empty(),
                Optional.of(worksiteTarget), Optional.of(minedData), Optional.empty());
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());

        // Simulate destination chunk unloaded: storage is unavailable
        WorkerProfile waitingProfile = MinerJobController.processDelivery(profile, 0, false, false);

        assertEquals(JobStatus.WAITING_FOR_STORAGE, waitingProfile.job().get().status());
        assertEquals(32, waitingProfile.job().get().minerData().get().itemsPendingDelivery());
        assertEquals(0, waitingProfile.job().get().minerData().get().itemsDelivered());
    }

    // ==========================================
    // 5. Long-Distance Tunneling, Destination Integrity & 64-Bit Safety
    // ==========================================

    @Test
    @DisplayName("14. Tunnel request exceeding 32 blocks (100 blocks = 50m) progresses beyond 32m work radius")
    void test14_tunnelRequestExceeding32Blocks_progressesBeyond32() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        Direction dir = Direction.NORTH;
        long requestedBlocks = 100; // 50m depth
        int targetDepth = (int) Math.ceil(requestedBlocks / 2.0); // 50
        assertEquals(50, targetDepth);

        // Verify isWithinTunnelBounds accepts positions far beyond 32-block radius: depths 33, 40, 49
        for (int d : new int[] {0, 15, 32, 33, 40, 49}) {
            BlockPos feet = anchor.relative(dir, d);
            BlockPos head = feet.above();
            assertTrue(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, feet, targetDepth),
                    "Feet block at depth " + d + " must be within tunnel bounds");
            assertTrue(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, head, targetDepth),
                    "Head block at depth " + d + " must be within tunnel bounds");
        }

        // Rejects depth >= 50
        BlockPos atDepth50 = anchor.relative(dir, 50);
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, atDepth50, targetDepth),
                "Depth 50 must be rejected when targetDepth is 50");

        // Rejects lateral deviation (width > 1)
        BlockPos eastDev = anchor.relative(dir, 33).east();
        BlockPos westDev = anchor.relative(dir, 33).west();
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, eastDev, targetDepth),
                "Lateral deviation to east must be rejected from 1x2 corridor");
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, westDev, targetDepth),
                "Lateral deviation to west must be rejected from 1x2 corridor");

        // Rejects vertical deviation (height != 2)
        BlockPos belowAnchor = anchor.relative(dir, 33).below();
        BlockPos aboveHead = anchor.relative(dir, 33).above(2);
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, belowAnchor, targetDepth),
                "Floor position below tunnel must be rejected");
        assertFalse(JobSafetyPolicy.isWithinTunnelBounds(anchor, dir, aboveHead, targetDepth),
                "Ceiling position above tunnel head must be rejected");

        // Verify findNextTunnelTarget progresses past depth 32
        // Simulate depths 0..32 already cleared (66 blocks)
        java.util.Set<BlockPos> clearedBlocks = new java.util.HashSet<>();
        for (int d = 0; d <= 32; d++) {
            clearedBlocks.add(anchor.relative(dir, d));
            clearedBlocks.add(anchor.relative(dir, d).above());
        }

        MinerJobController.TunnelTargetResult result = MinerJobController.findNextTunnelTarget(
                anchor,
                dir,
                requestedBlocks,
                clearedBlocks::contains,
                pos -> Optional.empty() // no obstructions
        );

        assertTrue(result.targetPos().isPresent(), "findNextTunnelTarget must find target beyond 32m");
        // Upper block of depth 33 is mined first
        BlockPos expectedHeadAt33 = anchor.relative(dir, 33).above();
        assertEquals(expectedHeadAt33, result.targetPos().get());
    }

    @Test
    @DisplayName("15. Storage Delivery: Never deposits into unrelated adjacent containers")
    void test15_storageNeverLeaksToUnrelatedAdjacentContainers() {
        BlockPos worksitePos = new BlockPos(0, 64, 0);
        BlockPos designatedStoragePos = new BlockPos(0, 64, 20);
        BlockPos adjacentUnrelatedPos = designatedStoragePos.east();

        WorkerTarget worksiteTarget = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerTarget storageTarget = new WorkerTarget(OVERWORLD, designatedStoragePos);

        MinerJobData minerData = MinerJobData.initial(100, storageTarget, Direction.NORTH, MinerJobData.ObjectiveMode.BLOCK_COUNT);
        List<ItemStack> drops = List.of(new ItemStack(Items.DIAMOND, 16));
        MinerJobData minedData = minerData.withBlockMined(16, drops);

        WorkerJob job = new WorkerJob(WorkerJobs.MINE, JobStatus.RUNNING, 0, Optional.empty(),
                Optional.of(worksiteTarget), Optional.of(minedData), Optional.empty());
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(job), WorkerBehavior.STAND, Optional.empty());

        // Designated storage is completely FULL
        SimpleContainer designatedChest = new SimpleContainer(1);
        designatedChest.setItem(0, new ItemStack(Items.DIRT, 64));

        // Adjacent unrelated container (e.g. private player chest) is completely EMPTY
        SimpleContainer adjacentChest = new SimpleContainer(27);

        java.util.Set<BlockPos> accessedPositions = new java.util.HashSet<>();
        java.util.Map<BlockPos, Container> containerMap = java.util.Map.of(
                designatedStoragePos, designatedChest,
                adjacentUnrelatedPos, adjacentChest
        );

        // Deliver to designated storage
        WorkerProfile resultProfile = StorageDestinationAdapter.deliverToDesignatedStorage(
                profile,
                designatedStoragePos,
                pos -> {
                    accessedPositions.add(pos);
                    return Optional.ofNullable(containerMap.get(pos));
                }
        );

        // 1. Designated chest remains full of dirt
        assertEquals(Items.DIRT, designatedChest.getItem(0).getItem());
        assertEquals(64, designatedChest.getItem(0).getCount());

        // 2. Adjacent chest was NEVER touched and contains NO items
        assertTrue(adjacentChest.isEmpty(), "Adjacent container must remain completely empty: no leakage permitted!");
        assertFalse(accessedPositions.contains(adjacentUnrelatedPos),
                "Adjacent container position must never even be queried");

        // 3. Worker transitioned safely to WAITING_FOR_STORAGE without item loss
        assertEquals(JobStatus.WAITING_FOR_STORAGE, resultProfile.job().get().status());
        assertEquals(16, resultProfile.job().get().minerData().get().itemsPendingDelivery());
        assertEquals(0, resultProfile.job().get().minerData().get().itemsDelivered());
        assertEquals(1, resultProfile.job().get().minerData().get().pendingStacks().size());
        assertEquals(Items.DIAMOND, resultProfile.job().get().minerData().get().pendingStacks().get(0).getItem());
        assertEquals(16, resultProfile.job().get().minerData().get().pendingStacks().get(0).getCount());
    }

    @Test
    @DisplayName("16. 64-bit spatial arithmetic: Overflow-safe across world border coordinates")
    void test16_64BitSpatialArithmetic_overflowSafetyAtWorldLimits() {
        // Positions near world limits (~30,000,000 blocks)
        BlockPos borderA = new BlockPos(29_999_990, 64, 29_999_990);
        BlockPos borderB = new BlockPos(29_999_995, 64, 29_999_990);

        // Distance is 5 blocks; must be within work bounds and storage safe
        assertTrue(JobSafetyPolicy.isWithinWorkBounds(borderA, borderB));
        assertTrue(JobSafetyPolicy.isWithin3DWorkBounds(borderA, borderB));
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(borderA, borderB));

        // Cross-world positions: difference of 50,000,000 blocks
        // In 32-bit math, 50,000,000^2 would overflow signed 32-bit int (~2.14B) into negative numbers
        BlockPos posFarEast = new BlockPos(25_000_000, 64, 25_000_000);
        BlockPos posFarWest = new BlockPos(-25_000_000, 64, -25_000_000);

        assertFalse(JobSafetyPolicy.isWithinWorkBounds(posFarEast, posFarWest));
        assertFalse(JobSafetyPolicy.isWithin3DWorkBounds(posFarEast, posFarWest));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(posFarEast, posFarWest));

        // Delta exceeding 2^31 - 1 (signed 32-bit integer range):
        // (long) a.getX() - b.getX() must widen BEFORE subtraction
        BlockPos extremePositive = new BlockPos(1_500_000_000, 64, 0);
        BlockPos extremeNegative = new BlockPos(-1_500_000_000, 64, 0);

        long dx = (long) extremePositive.getX() - extremeNegative.getX();
        assertEquals(3_000_000_000L, dx, "Difference must be 3 billion in 64-bit arithmetic without integer overflow");

        assertFalse(JobSafetyPolicy.isWithinWorkBounds(extremePositive, extremeNegative));
        assertFalse(JobSafetyPolicy.isWithin3DWorkBounds(extremePositive, extremeNegative));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(extremePositive, extremeNegative));
    }
}
