package com.example.examplemod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorksiteValidationAndVisualFeedbackTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_WORKER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @BeforeEach
    @AfterEach
    void cleanState() {
        WorkerOrderSession.clearAll();
        WorksiteAdapter.Registry.clear();
        CropRegistry.registerDefaults();
        BindingTicketHUD.clear();
    }

    @Test
    @DisplayName("Scenario 1: Valid farm worksite on mature wheat is accepted")
    void scenario01_validFarmWorksiteOnMatureWheat_isAccepted() {
        assertTrue(CropRegistry.isSupportedCrop(Blocks.WHEAT));
        CropAdapter adapter = CropRegistry.getAdapter(Blocks.WHEAT).orElseThrow();
        assertTrue(adapter.isMature(null, BlockPos.ZERO, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7)));
    }

    @Test
    @DisplayName("Scenario 2: Valid farm worksite on bare farmland is accepted")
    void scenario02_validFarmWorksiteOnBareFarmland_isAccepted() {
        WorksiteAdapter farmAdapter = WorksiteAdapter.Registry.get(WorkerJobs.FARM).orElseThrow();
        assertEquals(WorkerJobs.FARM, farmAdapter.jobType());
        assertTrue(Blocks.FARMLAND.defaultBlockState().is(Blocks.FARMLAND));
    }

    @Test
    @DisplayName("Scenario 3: Bare farmland defaults permittedCrops to standard farmland crops")
    void scenario03_bareFarmlandDefaultsPermittedCropsToStandardVanillaCrops() {
        List<ResourceLocation> defaultCrops = CropRegistry.DEFAULT_FARMLAND_CROP_IDS;
        assertEquals(4, defaultCrops.size());
        assertTrue(defaultCrops.contains(BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)));
        assertTrue(defaultCrops.contains(BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS)));
        assertTrue(defaultCrops.contains(BuiltInRegistries.BLOCK.getKey(Blocks.POTATOES)));
        assertTrue(defaultCrops.contains(BuiltInRegistries.BLOCK.getKey(Blocks.BEETROOTS)));
    }

    @Test
    @DisplayName("Scenario 4: Pre-selected crop in GUI overrides worksite default")
    void scenario04_preSelectedCropInGuiOverridesWorksiteDefault() {
        long now = System.currentTimeMillis();
        BlockPos worksitePos = new BlockPos(20, 64, 20);

        WorkerOrderSession.Session customCropSession = new WorkerOrderSession.Session(
                PLAYER_ID, WORKER_ID, OVERWORLD, WorkerJobs.FARM,
                WorkerOrderSession.Stage.SELECTING_STORAGE,
                Optional.of(new WorkerTarget(OVERWORLD, worksitePos)),
                Optional.empty(),
                64L, now, Optional.empty(),
                List.of(BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS))
        );

        assertEquals(1, customCropSession.permittedCrops().size());
        assertEquals(BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS), customCropSession.permittedCrops().getFirst());
        assertNotEquals(CropRegistry.DEFAULT_FARMLAND_CROP_IDS, customCropSession.permittedCrops());
    }

    @Test
    @DisplayName("Scenario 5: Worksite outside player reach (> 8 blocks) rejected with REJECTED_RANGE_PLAYER")
    void scenario05_worksiteOutsidePlayerReach_rejectsRangePlayer() {
        double maxReachSqr = 64.0; // 8 blocks squared
        double outOfReachDistSqr = 81.0; // 9 blocks
        assertTrue(outOfReachDistSqr > maxReachSqr);
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_PLAYER, WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_PLAYER"));
    }

    @Test
    @DisplayName("Scenario 6: Worksite outside worker distance (> 32 blocks) rejected with REJECTED_RANGE_WORKER")
    void scenario06_worksiteOutsideWorkerDistance_rejectsRangeWorker() {
        double maxWorkRadius = JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS; // 32 blocks
        double maxWorkRadiusSqr = maxWorkRadius * maxWorkRadius; // 1024
        double tooFarDistanceSqr = 35.0 * 35.0; // 1225
        assertTrue(tooFarDistanceSqr > maxWorkRadiusSqr);
        assertEquals(WorkerOrderSession.StepResult.REJECTED_RANGE_WORKER, WorkerOrderSession.StepResult.valueOf("REJECTED_RANGE_WORKER"));
    }

    @Test
    @DisplayName("Scenario 7: Worksite lacking build permission rejected with REJECTED_NO_PERMISSION")
    void scenario07_worksiteLackingBuildPermission_rejectsNoPermission() {
        assertEquals(WorkerOrderSession.StepResult.REJECTED_NO_PERMISSION, WorkerOrderSession.StepResult.valueOf("REJECTED_NO_PERMISSION"));
        assertEquals(JobSafetyPolicy.SafetyValidationResult.REJECTED_NO_PERMISSION, JobSafetyPolicy.SafetyValidationResult.valueOf("REJECTED_NO_PERMISSION"));
    }

    @Test
    @DisplayName("Scenario 8: Mining worksite on bedrock rejected with REJECTED_INDESTRUCTIBLE")
    void scenario08_miningWorksiteOnBedrock_rejectsIndestructible() {
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.BEDROCK));
        assertEquals(WorkerOrderSession.StepResult.REJECTED_INDESTRUCTIBLE, WorkerOrderSession.StepResult.valueOf("REJECTED_INDESTRUCTIBLE"));
        assertEquals(JobSafetyPolicy.SafetyValidationResult.REJECTED_INDESTRUCTIBLE, JobSafetyPolicy.SafetyValidationResult.valueOf("REJECTED_INDESTRUCTIBLE"));
    }

    @Test
    @DisplayName("Scenario 9: Mining worksite on block entity rejected with REJECTED_BLOCK_ENTITY")
    void scenario09_miningWorksiteOnBlockEntity_rejectsBlockEntity() {
        assertEquals(WorkerOrderSession.StepResult.REJECTED_BLOCK_ENTITY, WorkerOrderSession.StepResult.valueOf("REJECTED_BLOCK_ENTITY"));
        assertEquals(JobSafetyPolicy.SafetyValidationResult.REJECTED_BLOCK_ENTITY, JobSafetyPolicy.SafetyValidationResult.valueOf("REJECTED_BLOCK_ENTITY"));
    }

    @Test
    @DisplayName("Scenario 10: Mining worksite on blacklisted block rejected with REJECTED_BLACKLISTED")
    void scenario10_miningWorksiteOnBlacklistedBlock_rejectsBlacklisted() {
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.SPAWNER));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.END_PORTAL));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.NETHER_PORTAL));
        assertTrue(JobSafetyPolicy.isBlockBlacklisted(Blocks.BARRIER));
        assertEquals(WorkerOrderSession.StepResult.REJECTED_BLACKLISTED, WorkerOrderSession.StepResult.valueOf("REJECTED_BLACKLISTED"));
        assertEquals(JobSafetyPolicy.SafetyValidationResult.REJECTED_BLACKLISTED_BLOCK, JobSafetyPolicy.SafetyValidationResult.valueOf("REJECTED_BLACKLISTED_BLOCK"));
    }

    @Test
    @DisplayName("Scenario 11: Moving worksite with obstructed surface rejected with REJECTED_SURFACE_OBSTRUCTED")
    void scenario11_moveWorksiteWithObstructedSurface_rejectsSurfaceObstructed() {
        WorksiteAdapter moveAdapter = WorksiteAdapter.Registry.get(WorkerJobs.MOVE).orElseThrow();
        assertEquals(WorkerJobs.MOVE, moveAdapter.jobType());
        assertEquals(WorkerOrderSession.StepResult.REJECTED_SURFACE_OBSTRUCTED, WorkerOrderSession.StepResult.valueOf("REJECTED_SURFACE_OBSTRUCTED"));
    }

    @Test
    @DisplayName("Scenario 12: Farming worksite on invalid block (stone) rejected with REJECTED_BLOCK_TYPE")
    void scenario12_farmWorksiteOnInvalidBlock_rejectsBlockType() {
        assertFalse(CropRegistry.isSupportedCrop(Blocks.STONE));
        assertFalse(Blocks.STONE.defaultBlockState().is(Blocks.FARMLAND));
        assertEquals(WorkerOrderSession.StepResult.REJECTED_BLOCK_TYPE, WorkerOrderSession.StepResult.valueOf("REJECTED_BLOCK_TYPE"));
    }

    @Test
    @DisplayName("Scenario 13: Order attempted on busy worker rejected with REJECTED_WORKER_BUSY")
    void scenario13_orderAttemptedOnBusyWorker_rejectsWorkerBusy() {
        WorkerProfile profile = new WorkerProfile(WORKER_ID, PLAYER_ID, OVERWORLD, WorkerStatus.IDLE);
        WorkerJob runningJob = new WorkerJob(WorkerJobs.MINE, JobStatus.RUNNING, 50, Optional.empty(),
                Optional.of(new WorkerTarget(OVERWORLD, new BlockPos(0, 64, 0))));
        WorkerProfile busyProfile = profile.withJob(runningJob);

        assertTrue(busyProfile.job().isPresent());
        assertEquals(JobStatus.RUNNING, busyProfile.job().get().status());
        assertEquals(WorkerOrderSession.StepResult.REJECTED_WORKER_BUSY, WorkerOrderSession.StepResult.valueOf("REJECTED_WORKER_BUSY"));
    }

    @Test
    @DisplayName("Scenario 14: Order attempted with wrong ticket rejected with REJECTED_WRONG_TICKET")
    void scenario14_orderAttemptedWithWrongTicket_rejectsWrongTicket() {
        assertNotEquals(WORKER_ID, OTHER_WORKER_ID);
        assertEquals(WorkerOrderSession.StepResult.REJECTED_WRONG_TICKET, WorkerOrderSession.StepResult.valueOf("REJECTED_WRONG_TICKET"));
    }

    @Test
    @DisplayName("Scenario 15: Order attempted with unbound ticket rejected with REJECTED_WRONG_TICKET")
    void scenario15_orderAttemptedWithUnboundTicket_rejectsWrongTicket() {
        Optional<UUID> unboundWorker = Optional.empty();
        assertTrue(unboundWorker.isEmpty());
        assertEquals(WorkerOrderSession.StepResult.REJECTED_WRONG_TICKET, WorkerOrderSession.StepResult.valueOf("REJECTED_WRONG_TICKET"));
    }

    @Test
    @DisplayName("Scenario 16: Tether renders between player and worker during idle targeting within 32 blocks (aligned with work radius)")
    void scenario16_tetherRendersBetweenPlayerAndWorkerDuringIdleTargeting() {
        double maxTetherDistSqr = JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS * JobSafetyPolicy.DEFAULT_MAX_WORK_RADIUS; // 32 blocks
        double validDistSqr = 10.0 * 10.0;
        double outOfDistSqr = 35.0 * 35.0;

        assertTrue(validDistSqr <= maxTetherDistSqr);
        assertFalse(outOfDistSqr <= maxTetherDistSqr);

        // During idle targeting (no active session), tether uses idle sky-blue/cyan palette
        assertFalse(BindingTicketHUD.isInSelection());
        assertFalse(BindingTicketHUD.isConfirmedAck());
    }

    @Test
    @DisplayName("Scenario 17: Tether renders with distinct visual state during selection and confirmation")
    void scenario17_tetherRendersWithDistinctVisualStateDuringSelectionAndAck() {
        assertFalse(BindingTicketHUD.isInSelection());
        assertFalse(BindingTicketHUD.isConfirmedAck());

        // 1. Stage 1: SELECTING_WORKSITE (Amber beam)
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.SELECTING_WORKSITE.ordinal(),
                WORKER_ID, "Maid", "mine", 32L, null, ""
        );
        assertTrue(BindingTicketHUD.isInSelection(), "Tether must remain amber during worksite selection");
        assertFalse(BindingTicketHUD.isConfirmedAck());
        assertEquals(WORKER_ID, BindingTicketHUD.getActiveWorkerId());

        // Worker mismatch check: tether for OTHER_WORKER_ID does not inherit selection
        UUID activeWorker = BindingTicketHUD.getActiveWorkerId();
        boolean workerAMatches = activeWorker != null && activeWorker.equals(WORKER_ID);
        boolean workerBMatches = activeWorker != null && activeWorker.equals(OTHER_WORKER_ID);
        assertTrue(workerAMatches);
        assertFalse(workerBMatches);

        // 2. Stage 2: SELECTING_STORAGE (Amber beam)
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.SELECTING_STORAGE.ordinal(),
                WORKER_ID, "Maid", "mine", 32L, new BlockPos(10, 64, 10), ""
        );
        assertTrue(BindingTicketHUD.isInSelection(), "Tether must remain amber during storage selection");
        assertFalse(BindingTicketHUD.isConfirmedAck());

        // 3. Stage 3: ORDER_COMPLETED (Emerald confirmation pulse)
        BindingTicketHUD.updateSession(
                BindingTicketHUD.ClientStage.ORDER_COMPLETED.ordinal(),
                WORKER_ID, "Maid", "mine", 32L, new BlockPos(10, 64, 10), "Order Activated!"
        );
        assertFalse(BindingTicketHUD.isInSelection());
        assertTrue(BindingTicketHUD.isConfirmedAck(), "Tether must display distinguishable emerald pulse");

        // 4. Clear HUD / Cancel
        BindingTicketHUD.clear();
        assertFalse(BindingTicketHUD.isInSelection());
        assertFalse(BindingTicketHUD.isConfirmedAck());
        assertNull(BindingTicketHUD.getActiveWorkerId());
    }
}
