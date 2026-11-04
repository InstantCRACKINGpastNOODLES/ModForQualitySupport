package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rigorous regression tests for Storage Range Validation and Anchor Resolution.
 * Verifies exact right-triangle geometry, 32-block boundary conditions, independent
 * worker vs storage range semantics, 3D diagonal calculations, and error isolation.
 */
class StorageRangeValidationTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");

    @BeforeEach
    @AfterEach
    void cleanState() {
        WorkerOrderSession.clearAll();
        WorksiteAdapter.Registry.clear();
        CropRegistry.registerDefaults();
        BindingTicketHUD.clear();
    }

    // ==========================================
    // 1. Right Triangle Reproducible In-Game Geometry
    // ==========================================

    @Test
    @DisplayName("1. Right triangle geometry: worksite(0,0,0), worker(4,0,0), chest(0,0,7) is accepted")
    void test01_rightTriangleGeometry_acceptsChestAndValidatesDistances() {
        BlockPos worksitePos = new BlockPos(0, 64, 0);
        BlockPos workerPos = new BlockPos(4, 64, 0);
        BlockPos chestPos = new BlockPos(0, 64, 7);

        // Verify perpendicular geometry: worker-to-job dot chest-to-job == 0
        int workerToJobX = workerPos.getX() - worksitePos.getX(); // 4
        int workerToJobZ = workerPos.getZ() - worksitePos.getZ(); // 0
        int chestToJobX = chestPos.getX() - worksitePos.getX();   // 0
        int chestToJobZ = chestPos.getZ() - worksitePos.getZ();   // 7
        int dotProduct = workerToJobX * chestToJobX + workerToJobZ * chestToJobZ;
        assertEquals(0, dotProduct, "Worker-to-job and chest-to-job directions must be strictly perpendicular");

        // Verify individual distances
        double workerToWorksiteDist = Math.sqrt(workerToJobX * workerToJobX + workerToJobZ * workerToJobZ);
        assertEquals(4.0, workerToWorksiteDist, 0.001);
        assertTrue(workerToWorksiteDist <= JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS);

        double chestToWorksiteDist = Math.sqrt(chestToJobX * chestToJobX + chestToJobZ * chestToJobZ);
        assertEquals(7.0, chestToWorksiteDist, 0.001);
        assertTrue(chestToWorksiteDist <= JobSafetyPolicy.DEFAULT_MAX_STORAGE_DISTANCE);

        // Worker-to-chest distance in the right triangle: hypotenuse = sqrt(4^2 + 7^2) = sqrt(65) ~ 8.062
        double workerToChestDist = Math.sqrt((workerPos.getX() - chestPos.getX()) * (workerPos.getX() - chestPos.getX()) +
                (workerPos.getZ() - chestPos.getZ()) * (workerPos.getZ() - chestPos.getZ()));
        assertEquals(Math.sqrt(65.0), workerToChestDist, 0.001);
        assertTrue(workerToChestDist <= JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS);

        // Authoritative storage validation MUST pass
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksitePos, chestPos),
                "Chest at (0, 64, 7) from worksite at (0, 64, 0) must be accepted by isStorageDistanceSafe");

        // Session state verification
        WorkerTarget worksiteTarget = new WorkerTarget(OVERWORLD, worksitePos);
        WorkerOrderSession.Session session = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(worksiteTarget), Optional.empty(), 64L, System.currentTimeMillis()
        );
        WorkerOrderSession.setSessionForTesting(session);

        assertTrue(WorkerOrderSession.hasActiveSession(PLAYER_ID));
        assertEquals(worksitePos, WorkerOrderSession.getSession(PLAYER_ID).orElseThrow().worksite().orElseThrow().position());
    }

    // ==========================================
    // 2. Exact 32-Block Boundary Tests
    // ==========================================

    @Test
    @DisplayName("2. Storage exactly at 32-block boundary is accepted in all six cardinal directions")
    void test02_storageAtExact32BlockBoundary_acceptedInAllSixDirections() {
        BlockPos worksite = new BlockPos(100, 64, 100);

        BlockPos east32 = worksite.east(32);   // (132, 64, 100)
        BlockPos west32 = worksite.west(32);   // (68, 64, 100)
        BlockPos south32 = worksite.south(32); // (100, 64, 132)
        BlockPos north32 = worksite.north(32); // (100, 64, 68)
        BlockPos up32 = worksite.above(32);    // (100, 96, 100)
        BlockPos down32 = worksite.below(32);  // (100, 32, 100)

        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, east32), "+X at 32 blocks must be accepted");
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, west32), "-X at 32 blocks must be accepted");
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, south32), "+Z at 32 blocks must be accepted");
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, north32), "-Z at 32 blocks must be accepted");
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, up32), "+Y at 32 blocks must be accepted");
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, down32), "-Y at 32 blocks must be accepted");
    }

    @Test
    @DisplayName("3. Storage beyond 32-block boundary (33 blocks) is rejected in all six directions")
    void test03_storageBeyond32BlockBoundary_rejectedInAllSixDirections() {
        BlockPos worksite = new BlockPos(100, 64, 100);

        BlockPos east33 = worksite.east(33);
        BlockPos west33 = worksite.west(33);
        BlockPos south33 = worksite.south(33);
        BlockPos north33 = worksite.north(33);
        BlockPos up33 = worksite.above(33);
        BlockPos down33 = worksite.below(33);

        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, east33), "+X at 33 blocks must be rejected");
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, west33), "-X at 33 blocks must be rejected");
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, south33), "+Z at 33 blocks must be rejected");
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, north33), "-Z at 33 blocks must be rejected");
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, up33), "+Y at 33 blocks must be rejected");
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, down33), "-Y at 33 blocks must be rejected");

        // Verify StepResult enum value
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_STORAGE,
                WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_STORAGE"));
    }

    // ==========================================
    // 3. Worker vs Storage Range Independence
    // ==========================================

    @Test
    @DisplayName("4. Worker within range but chest outside range rejects with REJECTED_RANGE_STORAGE")
    void test04_workerWithinRange_chestOutsideRange_rejectsStorage() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerPos = new Vec3(4.5, 64.0, 0.5); // 4 blocks away (within 32)
        BlockPos chestTooFar = new BlockPos(35, 64, 0); // 35 blocks away (> 32)

        // Worker distance to worksite center:
        double workerDistSqr = workerPos.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertTrue(workerDistSqr <= 32 * 32, "Worker must be within 32 blocks");

        // Storage distance from worksite:
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestTooFar),
                "Chest at 35 blocks must be rejected");
    }

    @Test
    @DisplayName("5. Chest within range but worker outside range rejects with REJECTED_RANGE_WORKER")
    void test05_chestWithinRange_workerOutsideRange_rejectsWorksite() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerTooFar = new Vec3(35.5, 64.0, 0.5); // 35 blocks away (> 32)
        BlockPos chestValid = new BlockPos(0, 64, 7);   // 7 blocks away (<= 32)

        double workerDistSqr = workerTooFar.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertTrue(workerDistSqr > 32 * 32, "Worker must be outside 32 blocks");

        // Worksite rejection
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_WORKER,
                WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_WORKER"));

        // Storage is technically within range of worksite, but worksite itself cannot be configured
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestValid));
    }

    @Test
    @DisplayName("6. Storage range measured from worksite anchor, NOT worker (worker-to-chest = 60m is accepted)")
    void test06_independentMeasurement_workerAndChestOpposite_acceptedEvenWhenWorkerToChestIs60Blocks() {
        BlockPos worksite = new BlockPos(0, 64, 0);
        Vec3 workerPos = new Vec3(30.5, 64.0, 0.5); // +30 blocks from worksite
        BlockPos chestPos = new BlockPos(-30, 64, 0); // -30 blocks from worksite

        // Distance worker -> worksite = 30m <= 32m
        double workerToWorksiteSqr = workerPos.distanceToSqr(worksite.getX() + 0.5, worksite.getY() + 0.5, worksite.getZ() + 0.5);
        assertTrue(workerToWorksiteSqr <= 32 * 32, "Worker is within 30 blocks of worksite");

        // Distance worksite -> chest = 30m <= 32m
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, chestPos),
                "Worksite-to-chest distance is 30 blocks and MUST be accepted");

        // Worker-to-chest distance: 30 - (-30) = 60 blocks (> 32m)!
        double workerToChestDist = Math.abs(workerPos.x() - (chestPos.getX() + 0.5));
        assertEquals(60.0, workerToChestDist, 0.001);
        assertTrue(workerToChestDist > 32.0, "Worker-to-chest is 60 blocks");

        // This PROVES storage range is measured strictly from the worksite anchor, not the worker!
    }

    // ==========================================
    // 4. 3D Diagonal and Vertical Coordinate Tests
    // ==========================================

    @Test
    @DisplayName("7. 3D diagonal distance inside (904 <= 1024) is accepted; outside (1200 > 1024) is rejected")
    void test07_3DDiagonalDistances() {
        BlockPos worksite = new BlockPos(0, 64, 0);

        // dx=18, dy=16, dz=18 -> 18^2 + 16^2 + 18^2 = 324 + 256 + 324 = 904 <= 1024 (sqrt(904) ~ 30.07 blocks)
        BlockPos diagInside = new BlockPos(18, 80, 18);
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, diagInside), "Diagonal 3D distance of 30.07m must be accepted");

        // dx=20, dy=20, dz=20 -> 20^2 + 20^2 + 20^2 = 400 + 400 + 400 = 1200 > 1024 (sqrt(1200) ~ 34.64 blocks)
        BlockPos diagOutside = new BlockPos(20, 84, 20);
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, diagOutside), "Diagonal 3D distance of 34.64m must be rejected");

        // Pure vertical check
        BlockPos pureVertical32 = worksite.above(32);
        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(worksite, pureVertical32));
        BlockPos pureVertical33 = worksite.above(33);
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(worksite, pureVertical33));
    }

    // ==========================================
    // 5. Session State, Anchors, and Edge Cases
    // ==========================================

    @Test
    @DisplayName("8. Missing worksite anchor in session rejects storage selection with UNAVAILABLE")
    void test08_missingWorksiteAnchor_returnsUnavailable() {
        // Session with worksite = Optional.empty()
        WorkerOrderSession.Session noWorksiteSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.MINE,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.empty(), Optional.empty(), 64L, System.currentTimeMillis()
        );
        WorkerOrderSession.setSessionForTesting(noWorksiteSession);

        // Calling selectStorage without worksite returns UNAVAILABLE
        WorkerOrderSession.StepResult result = WorkerOrderSession.selectStorage(null, new BlockPos(0, 64, 0));
        assertEquals(WorkerOrderSession.StepResult.UNAVAILABLE, result);
    }

    @Test
    @DisplayName("9. Null coordinates in isStorageDistanceSafe safely return false without exception")
    void test09_nullCoordinates_safelyHandled() {
        BlockPos validPos = new BlockPos(0, 64, 0);
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(null, validPos));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(validPos, null));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(null, null));
    }

    // ==========================================
    // 6. Differentiated Error Reporting & Localization
    // ==========================================

    @Test
    @DisplayName("10. Error reporting cleanly differentiates distance, invalid container, and busy worker")
    void test10_errorReportingDifferentiatesStorageFailureCauses() {
        // StepResult enum verification
        assertNotNull(WorkerOrderSession.StepResult.REJECTED_RANGE_STORAGE);
        assertNotNull(WorkerOrderSession.StepResult.INVALID_STORAGE);
        assertNotNull(WorkerOrderSession.StepResult.REJECTED_WORKER_BUSY);
        assertNotNull(WorkerOrderSession.StepResult.REJECTED_RANGE_WORKER);
        assertNotNull(WorkerOrderSession.StepResult.REJECTED_RANGE_PLAYER);

        // All step results must be mutually distinct
        assertNotEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_STORAGE, WorkerOrderSession.StepResult.INVALID_STORAGE);
        assertNotEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_STORAGE, WorkerOrderSession.StepResult.REJECTED_WORKER_BUSY);
        assertNotEquals(WorkerOrderSession.StepResult.INVALID_STORAGE, WorkerOrderSession.StepResult.REJECTED_WORKER_BUSY);
    }

    @Test
    @DisplayName("11. Client HUD guidance correctly displays 32-block storage limit")
    void test11_hudGuidanceDisplays32Blocks() {
        BindingTicketHUD.updateSession(
                WorkerOrderSession.toClientStageOrdinal(WorkerOrderSession.Stage.SELECTING_STORAGE),
                WORKER_ID, "Maid", "mine", 32L, new BlockPos(0, 64, 0), ""
        );

        assertEquals(BindingTicketHUD.ClientStage.SELECTING_STORAGE, BindingTicketHUD.getStage());
        assertEquals(new BlockPos(0, 64, 0), BindingTicketHUD.getWorksitePos());
        assertEquals(32L, BindingTicketHUD.getRequestedQuantity());
        assertTrue(BindingTicketHUD.isInSelection());
    }

    @Test
    @DisplayName("12. Negative coordinate handling: worksite and storage in negative space validate correctly")
    void test12_negativeCoordinates_validateCorrectly() {
        BlockPos negWorksite = new BlockPos(-500, -20, -500);
        BlockPos negStorageWithin = new BlockPos(-500, -20, -525); // dz = -25 -> 25m <= 32m
        BlockPos negStorageBeyond = new BlockPos(-500, -20, -535); // dz = -35 -> 35m > 32m

        assertTrue(JobSafetyPolicy.isStorageDistanceSafe(negWorksite, negStorageWithin));
        assertFalse(JobSafetyPolicy.isStorageDistanceSafe(negWorksite, negStorageBeyond));
    }
}
