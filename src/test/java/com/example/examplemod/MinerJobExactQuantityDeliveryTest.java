package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.example.examplemod.MinerJobData.ObjectiveMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinerJobExactQuantityDeliveryTest {

    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final WorkerTarget WORKSITE = new WorkerTarget(OVERWORLD, new BlockPos(10, 64, 10));
    private static final WorkerTarget STORAGE = new WorkerTarget(OVERWORLD, new BlockPos(12, 64, 12));

    private static final ResourceLocation TEST_AVARITIA_PROVIDER =
            ResourceLocation.fromNamespaceAndPath("test", "avaritia_provider");

    @BeforeEach
    @AfterEach
    void resetRegistries() {
        MiningToolCapability.Registry.clear();
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

    @Test
    void hundredMillionBlockJobRetainsExactQuantityWithoutArbitraryCap() {
        long hugeQuantity = 100_000_000L;
        MinerJobData data = MinerJobData.initial(hugeQuantity, STORAGE);

        assertEquals(hugeQuantity, data.requestedBlocks());
        assertEquals(0L, data.blocksMined());
        assertEquals(hugeQuantity, data.blocksRemaining());

        // Preflight approves full 100M block request without arbitrary total caps
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        MinerResourcePreflight.PreflightPlan plan = MinerResourcePreflight.evaluate(pick, hugeQuantity, 1);
        assertTrue(plan.isApproved());
        assertEquals(hugeQuantity, plan.playerRequestedQuantity());
        assertEquals(hugeQuantity, plan.estimatedTicksToComplete());
        assertEquals(hugeQuantity, data.requestedBlocks(), "100M block request must not be capped or altered");
    }

    @Test
    void workerNeverCreditsMoreBlocksThanRequestedQuantity() {
        long requested = 5L;
        MinerJobData data = MinerJobData.initial(requested, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Mine 3 blocks
        profile = MinerJobController.processMinedBlocks(profile, 3L, 3L);
        assertEquals(3L, profile.job().orElseThrow().minerData().orElseThrow().blocksMined());

        // Mine 4 more blocks -> total would be 7, but capped at 5
        profile = MinerJobController.processMinedBlocks(profile, 4L, 4L);
        MinerJobData finalData = profile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(5L, finalData.blocksMined(), "Blocks mined must strictly be capped at requested blocks");
        assertTrue(finalData.isMiningFinished());
    }

    @Test
    void woodenPickaxeDurabilityDepletionEntersWaitingForTool() {
        ItemStack woodenPick = new ItemStack(Items.WOODEN_PICKAXE);
        woodenPick.setDamageValue(woodenPick.getMaxDamage() - 1); // Only 1 durability remaining (< MIN_TOOL_DURABILITY = 2)

        MinerJobData data = MinerJobData.initial(100L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Durability exhaustion prevents mining action
        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                profile, null, null, woodenPick, WORKSITE.position(), null
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_TOOL_EXHAUSTED, gate);

        // Handling tool exhaustion transitions to WAITING_FOR_TOOL
        profile = MinerJobController.handleToolExhaustion(profile);
        assertEquals(JobStatus.WAITING_FOR_TOOL, profile.job().orElseThrow().status());
        assertTrue(profile.job().orElseThrow().failure().orElse("").contains("Tool durability exhausted"));
    }

    @Test
    void infiniteDurabilityToolDoesNotStopDueToFabricatedDurability() {
        // Register verified infinite durability tool provider
        MiningToolCapability infiniteToolAdapter = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return TEST_AVARITIA_PROVIDER; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.NETHER_STAR); }
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 1; }
        };
        MiningToolCapability.Registry.register(infiniteToolAdapter);

        ItemStack infiniteTool = new ItemStack(Items.NETHER_STAR);
        MinerJobData data = MinerJobData.initial(1000L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                profile, null, null, infiniteTool, WORKSITE.position(), null
        );
        assertEquals(MinerJobController.ExecutionGate.PERMITTED, gate);

        // Tool wear handling on non-damageable items does not stop execution
        assertTrue(JobSafetyPolicy.hasSufficientDurability(infiniteTool));
    }

    @Test
    void reachingRequestedQuantityTransitionsToDeliveringNotImmediateCompletion() {
        MinerJobData data = new MinerJobData(10L, 9L, 0L, 0L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Mine the final 10th block, which yields 1 drop
        profile = MinerJobController.processMinedBlocks(profile, 1L, 1L);
        WorkerJob job = profile.job().orElseThrow();
        MinerJobData updatedData = job.minerData().orElseThrow();

        assertEquals(10L, updatedData.blocksMined());
        assertEquals(1L, updatedData.itemsPendingDelivery());
        assertTrue(updatedData.isMiningFinished());
        assertFalse(updatedData.isDeliveryComplete());

        // Reaching quota with pending drops MUST transition to DELIVERING, not COMPLETED
        assertEquals(JobStatus.DELIVERING, job.status());

        // WorkerJob codec strictly rejects COMPLETED state while itemsPendingDelivery > 0
        WorkerJob invalidCompleted = new WorkerJob(
                WorkerJobs.MINE, JobStatus.COMPLETED, 100, Optional.empty(), Optional.of(WORKSITE), Optional.of(updatedData)
        );
        var encodeResult = WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, invalidCompleted);
        assertTrue(encodeResult.result().isEmpty(), "WorkerJob must fail validation if COMPLETED with itemsPendingDelivery > 0");
    }

    @Test
    void fullOrUnavailableChestPreventsUnsafeContinuedMining() {
        MinerJobData data = new MinerJobData(100L, 10L, 10L, 0L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Storage is unavailable
        WorkerProfile waitingProfile = MinerJobController.processDelivery(profile, 0L, false, false);
        assertEquals(JobStatus.WAITING_FOR_STORAGE, waitingProfile.job().orElseThrow().status());
        assertEquals(10L, waitingProfile.job().orElseThrow().minerData().orElseThrow().itemsPendingDelivery());

        // Storage is full
        WorkerProfile fullProfile = MinerJobController.processDelivery(profile, 0L, true, true);
        assertEquals(JobStatus.WAITING_FOR_STORAGE, fullProfile.job().orElseThrow().status());
        assertEquals(10L, fullProfile.job().orElseThrow().minerData().orElseThrow().itemsPendingDelivery());

        // While in WAITING_FOR_STORAGE, further mining is rejected
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                fullProfile, null, null, pick, WORKSITE.position(), null
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_INVALID_STATE, gate);
    }

    @Test
    void itemsAreNeverDroppedOrDiscardedToClearInventory() {
        // Worker has 64 items pending delivery
        MinerJobData data = new MinerJobData(100L, 20L, 64L, 0L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Chest has space for only 20 items (partial delivery, then chest is full)
        profile = MinerJobController.processDelivery(profile, 20L, true, true);
        MinerJobData updated = profile.job().orElseThrow().minerData().orElseThrow();

        assertEquals(20L, updated.itemsDelivered());
        assertEquals(44L, updated.itemsPendingDelivery());
        assertEquals(64L, updated.itemsDelivered() + updated.itemsPendingDelivery(), "Conservation of items must be exact");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, profile.job().orElseThrow().status());
    }

    @Test
    void jobCompletesOnlyAfterAllOutputDepositedInAuthorizedStorage() {
        MinerJobData data = new MinerJobData(50L, 50L, 15L, 35L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Chest accepts all 15 remaining items
        profile = MinerJobController.processDelivery(profile, 15L, true, false);
        WorkerJob job = profile.job().orElseThrow();
        MinerJobData updated = job.minerData().orElseThrow();

        assertEquals(50L, updated.itemsDelivered());
        assertEquals(0L, updated.itemsPendingDelivery());
        assertTrue(updated.isFullyCompleted());

        assertEquals(JobStatus.COMPLETED, job.status());
        assertEquals(100, job.progress());
    }

    @Test
    void savingAndReloadingPreservesProgressWithoutDuplicatingMiningOrDelivery() {
        long requested = 100_000_000L;
        MinerJobData data = new MinerJobData(requested, 45_000L, 100L, 44_900L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        WorkerJob job = profile.job().orElseThrow();
        var encoded = WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, job).result().orElseThrow();
        WorkerJob decoded = WorkerJob.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();

        assertEquals(job.type(), decoded.type());
        assertEquals(job.status(), decoded.status());
        assertEquals(job.progress(), decoded.progress());

        assertTrue(decoded.minerData().isPresent());
        MinerJobData reloadedData = decoded.minerData().get();
        assertEquals(requested, reloadedData.requestedBlocks());
        assertEquals(45_000L, reloadedData.blocksMined());
        assertEquals(100L, reloadedData.itemsPendingDelivery());
        assertEquals(44_900L, reloadedData.itemsDelivered());
        assertEquals(STORAGE, reloadedData.storage());

        // Progress calculation matches exactly
        assertEquals(job.progress(), decoded.progress());
    }

    @Test
    void areaEffectToolCannotExceedRequestedQuantityOrBypassPerTickLimit() {
        // Register an AOE tool that affects 9 blocks per action
        MiningToolCapability aoeToolAdapter = new MiningToolCapability() {
            @Override
            public ResourceLocation id() { return TEST_AVARITIA_PROVIDER; }
            @Override
            public boolean isVerifiedSafe(ItemStack tool) { return tool.is(Items.NETHER_STAR); }
            @Override
            public int maxBlocksPerAction(ItemStack tool) { return 9; }
        };
        MiningToolCapability.Registry.register(aoeToolAdapter);

        ItemStack aoeTool = new ItemStack(Items.NETHER_STAR);

        // Case 1: Remaining requested blocks = 2. Tool affects 9 blocks -> REJECTED
        MinerJobData data = new MinerJobData(10L, 8L, 0L, 0L, STORAGE); // Remaining = 2
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                profile, null, null, aoeTool, WORKSITE.position(), null
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_COLLATERAL_EXCEEDS_REMAINING, gate);

        // Case 2: Preflight rejects AOE tool if per-tick execution limit is 1
        MinerResourcePreflight.PreflightPlan plan = MinerResourcePreflight.evaluate(aoeTool, 100, 1);
        assertFalse(plan.isApproved());
        assertEquals(MinerResourcePreflight.PreflightStatus.REJECTED_RATE_LIMIT_EXCEEDED, plan.status());
    }

    @Test
    void partialDeliveryPersistsCompleteItemStacksSurvivesRestartAndResumesWithoutLossOrDuplication() {
        // Step 1: Worker obtains multiple different item types with custom DataComponents
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 5);
        diamonds.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Lucky Gem"));
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        cobble.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Pure Stone"));
        MinerJobData data = new MinerJobData(69L, 69L, 69L, 0L, java.util.List.of(diamonds, cobble), STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Step 2: Storage chest has limited space:
        // Slot 0: empty (can hold 5 diamonds)
        // Slot 1: already has 44 cobblestone with matching custom name (can only accept 20 more)
        java.util.List<ItemStack> chestSlots = new java.util.ArrayList<>();
        chestSlots.add(ItemStack.EMPTY);
        ItemStack partialCobble = new ItemStack(Items.COBBLESTONE, 44);
        partialCobble.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Pure Stone"));
        chestSlots.add(partialCobble);

        // Step 3: Worker delivers to chest
        profile = MinerJobController.deliverToStorage(profile, chestSlots, 2);
        WorkerJob jobAfterPartial = profile.job().orElseThrow();
        MinerJobData dataAfterPartial = jobAfterPartial.minerData().orElseThrow();

        // Verification of partial delivery:
        // 5 diamonds placed in slot 0, 20 cobblestone added to slot 1 (now 64 cobble)
        assertEquals(5, chestSlots.get(0).getCount());
        assertEquals(Items.DIAMOND, chestSlots.get(0).getItem());
        assertEquals(net.minecraft.network.chat.Component.literal("Lucky Gem"), chestSlots.get(0).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
        assertEquals(64, chestSlots.get(1).getCount());
        assertEquals(Items.COBBLESTONE, chestSlots.get(1).getItem());
        assertEquals(net.minecraft.network.chat.Component.literal("Pure Stone"), chestSlots.get(1).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));

        // Exactly 25 items delivered, 44 cobblestone remain in pendingStacks with DataComponents preserved
        assertEquals(25L, dataAfterPartial.itemsDelivered());
        assertEquals(44L, dataAfterPartial.itemsPendingDelivery());
        assertEquals(1, dataAfterPartial.pendingStacks().size());
        assertEquals(Items.COBBLESTONE, dataAfterPartial.pendingStacks().get(0).getItem());
        assertEquals(44, dataAfterPartial.pendingStacks().get(0).getCount());
        assertEquals(net.minecraft.network.chat.Component.literal("Pure Stone"), dataAfterPartial.pendingStacks().get(0).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
        assertEquals(JobStatus.WAITING_FOR_STORAGE, jobAfterPartial.status());

        // Failed insertion check: Delivering again to the full chest cannot consume source or duplicate destination
        WorkerProfile repeatedDelivery = MinerJobController.deliverToStorage(profile, chestSlots, 2);
        MinerJobData repeatedData = repeatedDelivery.job().orElseThrow().minerData().orElseThrow();
        assertEquals(25L, repeatedData.itemsDelivered(), "Delivered count must not change on full chest");
        assertEquals(44L, repeatedData.itemsPendingDelivery(), "Pending count must not be consumed on full chest");
        assertEquals(1, repeatedData.pendingStacks().size());
        assertEquals(44, repeatedData.pendingStacks().get(0).getCount());
        assertEquals(64, chestSlots.get(1).getCount(), "Destination stack must not be duplicated or grown beyond capacity");

        // Step 4: Game/server restarts -> encode to JSON, decode back
        var encoded = WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, jobAfterPartial).result().orElseThrow();
        WorkerJob decodedJob = WorkerJob.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
        MinerJobData reloadedData = decodedJob.minerData().orElseThrow();

        assertEquals(JobStatus.WAITING_FOR_STORAGE, decodedJob.status());
        assertEquals(25L, reloadedData.itemsDelivered());
        assertEquals(44L, reloadedData.itemsPendingDelivery());
        assertEquals(1, reloadedData.pendingStacks().size(), "Exact ItemStacks must be reconstructed across restart");
        assertEquals(Items.COBBLESTONE, reloadedData.pendingStacks().get(0).getItem());
        assertEquals(44, reloadedData.pendingStacks().get(0).getCount());
        // Verify DataComponents survive restart
        assertEquals(net.minecraft.network.chat.Component.literal("Pure Stone"), reloadedData.pendingStacks().get(0).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));

        // Step 5: Space is freed in the container (player adds an empty slot 2)
        chestSlots.add(ItemStack.EMPTY);
        WorkerProfile reloadedProfile = profile.withJob(decodedJob);
        WorkerProfile finalProfile = MinerJobController.deliverToStorage(reloadedProfile, chestSlots, 3);
        WorkerJob finalJob = finalProfile.job().orElseThrow();
        MinerJobData finalData = finalJob.minerData().orElseThrow();

        // Verification of resumed delivery:
        assertEquals(69L, finalData.itemsDelivered());
        assertEquals(0L, finalData.itemsPendingDelivery());
        assertTrue(finalData.pendingStacks().isEmpty());
        assertTrue(finalData.isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, finalJob.status());

        // Total items in chest: 5 diamonds + 64 cobblestone + 44 cobblestone (including initial 44) = 113 total
        // Mined items added: exactly 5 diamonds + 64 cobble = 69 items. Zero lost, zero duplicated.
        assertEquals(5, chestSlots.get(0).getCount());
        assertEquals(64, chestSlots.get(1).getCount());
        assertEquals(44, chestSlots.get(2).getCount());
        assertEquals(net.minecraft.network.chat.Component.literal("Pure Stone"), chestSlots.get(2).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
    }

    @Test
    void toolDurabilityCheckBeforeMutationPreventsToolBreaking() {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);

        // Case 1: Tool remaining durability = 2 (MIN_TOOL_DURABILITY = 2)
        pick.setDamageValue(pick.getMaxDamage() - 2);
        // Operation cost 1 -> remaining after action would be 1 (< MIN_TOOL_DURABILITY) -> REJECTED
        assertFalse(JobSafetyPolicy.hasSufficientDurability(pick, 1));
        // Operation cost 2 -> remaining after action would be 0 (breaks tool) -> REJECTED
        assertFalse(JobSafetyPolicy.hasSufficientDurability(pick, 2));

        // In MinerJobController: pick with remaining = 2 is rejected before mutation
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                profile, null, null, pick, WORKSITE.position(), null
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_TOOL_EXHAUSTED, gate);

        // Case 2: Tool remaining durability = 3 (remaining - 1 = 2 == MIN_TOOL_DURABILITY) -> PERMITTED for cost 1
        pick.setDamageValue(pick.getMaxDamage() - 3);
        assertTrue(JobSafetyPolicy.hasSufficientDurability(pick, 1));
        // But for cost 2 -> remaining - 2 = 1 (< MIN_TOOL_DURABILITY) -> REJECTED
        assertFalse(JobSafetyPolicy.hasSufficientDurability(pick, 2));

        // Case 3: Unknown or non-positive durability cost on damageable tool is strictly rejected
        assertFalse(JobSafetyPolicy.hasSufficientDurability(pick, 0));
        assertFalse(JobSafetyPolicy.hasSufficientDurability(pick, -1));

        // Case 4: Non-damageable tool has no durability consumption and is not assigned fabricated costs
        ItemStack infiniteTool = new ItemStack(Items.NETHER_STAR);
        assertTrue(JobSafetyPolicy.hasSufficientDurability(infiniteTool, 1));
        assertTrue(JobSafetyPolicy.hasSufficientDurability(infiniteTool, 100));
    }

    @Test
    void boundary1_exceptionAfterSuccessfulBlockDestruction_reconcilesCommittedDropsAndProgress() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> expectedDrops = List.of(new ItemStack(Items.COBBLESTONE, 1));

        java.util.concurrent.atomic.AtomicReference<WorkerProfile> stagedRef = new java.util.concurrent.atomic.AtomicReference<>();

        // Simulate crash/exception immediately following world block destruction
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, targetPos, null, stoneId,
                () -> expectedDrops,
                () -> {
                    // Block destruction succeeds in chunk, but an exception occurs before normal completion
                    throw new RuntimeException("Simulated crash right after chunk mutation");
                },
                () -> {},
                stagedRef::set
        );

        assertEquals(MinerJobController.TransactionStatus.FAILED_POST_MUTATION, result.status());
        assertNotNull(stagedRef.get(), "Profile must have staged in-flight mutation before destruction");
        WorkerProfile stagedProfile = stagedRef.get();
        MinerJobData stagedData = stagedProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(stagedData.inFlightMutation().isPresent());
        assertEquals(0L, stagedData.blocksMined(), "Prior to reconciliation, progress is not yet committed");

        // Reconciliation discovers the block was indeed destroyed in the world
        WorkerProfile recovered = MinerJobController.reconcileInFlightMutation(stagedProfile, true);
        MinerJobData recoveredData = recovered.job().orElseThrow().minerData().orElseThrow();

        // Verification: progress is safely credited and drops preserved without loss
        assertTrue(recoveredData.inFlightMutation().isEmpty());
        assertEquals(1L, recoveredData.blocksMined());
        assertEquals(1L, recoveredData.itemsPendingDelivery());
        assertEquals(1, recoveredData.pendingStacks().size());
        assertEquals(Items.COBBLESTONE, recoveredData.pendingStacks().get(0).getItem());
        assertEquals(1, recoveredData.pendingStacks().get(0).getCount());
    }

    @Test
    void boundary2_exceptionAfterDropGenerationBeforeMutation_cancelsWithoutPhantomProgress() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> expectedDrops = List.of(new ItemStack(Items.COBBLESTONE, 1));

        java.util.concurrent.atomic.AtomicReference<WorkerProfile> stagedRef = new java.util.concurrent.atomic.AtomicReference<>();

        // World block destruction fails or returns false (e.g. block was protected or cancel event fired)
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, targetPos, null, stoneId,
                () -> expectedDrops,
                () -> false, // Block destruction returned false - block remains intact
                () -> {},
                stagedRef::set
        );

        assertEquals(MinerJobController.TransactionStatus.MUTATION_FAILED, result.status());
        WorkerProfile finalProfile = result.profile();
        MinerJobData finalData = finalProfile.job().orElseThrow().minerData().orElseThrow();

        // Verification: in-flight cancelled cleanly, 0 blocks mined, 0 drops credited (no phantom progress)
        assertTrue(finalData.inFlightMutation().isEmpty());
        assertEquals(0L, finalData.blocksMined());
        assertEquals(0L, finalData.itemsPendingDelivery());
        assertTrue(finalData.pendingStacks().isEmpty());
    }

    @Test
    void boundary3_toolDamageExceptionDoesNotAbortProgressOrDropPersistence() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation ironOreId = ResourceLocation.fromNamespaceAndPath("test", "iron_ore");
        List<ItemStack> expectedDrops = List.of(new ItemStack(Items.RAW_IRON, 2));

        // Tool damage throws unexpected exception post-mutation
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, targetPos, null, ironOreId,
                () -> expectedDrops,
                () -> true, // Block destruction succeeded
                () -> { throw new RuntimeException("Unexpected tool capability failure during damage application"); },
                staged -> {}
        );

        // Result is successful because world was already mutated
        assertEquals(MinerJobController.TransactionStatus.SUCCESS, result.status());
        MinerJobData finalData = result.profile().job().orElseThrow().minerData().orElseThrow();

        assertEquals(1L, finalData.blocksMined(), "Destroyed block must be credited despite tool damage exception");
        assertEquals(2L, finalData.itemsPendingDelivery());
        assertEquals(1, finalData.pendingStacks().size());
        assertEquals(Items.RAW_IRON, finalData.pendingStacks().get(0).getItem());
        assertEquals(2, finalData.pendingStacks().get(0).getCount());
    }

    @Test
    void boundary4_exceptionDuringProfilePersistenceSurvivesRestartAndReconciles() {
        MinerJobData data = MinerJobData.initial(50L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation diamondOreId = ResourceLocation.fromNamespaceAndPath("test", "diamond_ore");
        List<ItemStack> expectedDrops = List.of(new ItemStack(Items.DIAMOND, 1));

        java.util.concurrent.atomic.AtomicReference<WorkerProfile> stagedRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger commitCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Transaction stages intent, mutates chunk, but server crashes during final commit persistence
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, targetPos, null, diamondOreId,
                () -> expectedDrops,
                () -> true,
                () -> {},
                p -> {
                    if (commitCount.incrementAndGet() == 1) {
                        stagedRef.set(p);
                    } else {
                        throw new RuntimeException("Simulated crash during final profile persistence");
                    }
                }
        );

        assertEquals(MinerJobController.TransactionStatus.FAILED_POST_MUTATION, result.status());
        WorkerProfile stagedProfile = stagedRef.get();
        assertNotNull(stagedProfile);

        // Server restarts: Staged profile is serialized to JSON and deserialized back
        var encoded = WorkerProfile.CODEC.encodeStart(JsonOps.INSTANCE, stagedProfile).result().orElseThrow();
        WorkerProfile reloadedProfile = WorkerProfile.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();

        // Staged in-flight mutation survived serialization
        MinerJobData reloadedData = reloadedProfile.job().orElseThrow().minerData().orElseThrow();
        assertTrue(reloadedData.inFlightMutation().isPresent());
        assertEquals(targetPos, reloadedData.inFlightMutation().get().pos());
        assertEquals(diamondOreId, reloadedData.inFlightMutation().get().blockId());
        assertEquals(1, reloadedData.inFlightMutation().get().expectedDrops().size());
        assertEquals(Items.DIAMOND, reloadedData.inFlightMutation().get().expectedDrops().get(0).getItem());

        // Worker execution loop runs reconciliation upon world startup
        WorkerProfile reconciled = MinerJobController.reconcileInFlightMutation(reloadedProfile, true);
        MinerJobData finalData = reconciled.job().orElseThrow().minerData().orElseThrow();

        assertTrue(finalData.inFlightMutation().isEmpty());
        assertEquals(1L, finalData.blocksMined());
        assertEquals(1L, finalData.itemsPendingDelivery());
        assertEquals(1, finalData.pendingStacks().size());
        assertEquals(Items.DIAMOND, finalData.pendingStacks().get(0).getItem());
    }

    @Test
    void boundary5_deliveryFailureOrPartialContainerConservesItemsWithoutLossOrDuplication() {
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 30);
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 50);
        MinerJobData data = new MinerJobData(80L, 80L, 80L, 0L, List.of(diamonds, iron), STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Storage container can only accept 20 diamonds (slot capacity limit)
        java.util.List<ItemStack> chestSlots = new java.util.ArrayList<>();
        chestSlots.add(new ItemStack(Items.DIAMOND, 44)); // Can take 20 more diamonds (max 64)

        WorkerProfile partialProfile = MinerJobController.deliverToStorage(profile, chestSlots, 1);
        MinerJobData partialData = partialProfile.job().orElseThrow().minerData().orElseThrow();

        // Exactly 20 diamonds delivered; 10 diamonds and 50 iron remain pending
        assertEquals(64, chestSlots.get(0).getCount());
        assertEquals(20L, partialData.itemsDelivered());
        assertEquals(60L, partialData.itemsPendingDelivery());
        assertEquals(80L, partialData.itemsDelivered() + partialData.itemsPendingDelivery(), "Conservation of items must be exact");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, partialProfile.job().orElseThrow().status());

        // Repeated delivery to full container does not alter state or duplicate items
        WorkerProfile retryProfile = MinerJobController.deliverToStorage(partialProfile, chestSlots, 1);
        MinerJobData retryData = retryProfile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(20L, retryData.itemsDelivered());
        assertEquals(60L, retryData.itemsPendingDelivery());
        assertEquals(64, chestSlots.get(0).getCount());
    }

    @Test
    void recoveryCannotMineBeyondRequestedQuantity() {
        // Job is already at 5/5 blocks mined
        MinerJobData data = new MinerJobData(5L, 5L, 0L, 0L, STORAGE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));

        // Attempting to stage and commit beyond requested quantity
        MinerJobData staged = data.withInFlightMutation(targetPos, stoneId, drops);
        WorkerProfile profile = createMinerProfile(staged, JobStatus.RUNNING);

        WorkerProfile recovered = MinerJobController.reconcileInFlightMutation(profile, true);
        MinerJobData finalData = recovered.job().orElseThrow().minerData().orElseThrow();

        assertEquals(5L, finalData.blocksMined(), "Blocks mined must strictly be capped at requested blocks (5)");
        assertTrue(finalData.isMiningFinished());
    }

    @Test
    void reconciliationFailsClosedWhenChunkIsUnloadedOrUnavailable() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));
        MinerJobData staged = data.withInFlightMutation(targetPos, stoneId, drops);
        WorkerProfile profile = createMinerProfile(staged, JobStatus.RUNNING);

        // Chunk is unloaded (chunkLoaded = false, currentState = null)
        MinerJobController.ReconciliationOutcome outcome = MinerJobController.evaluateReconciliation(profile, false, null);

        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, outcome.status());
        WorkerProfile resultProfile = outcome.profile();
        MinerJobData resultData = resultProfile.job().orElseThrow().minerData().orElseThrow();

        // In-flight mutation must be preserved unresolved (fail closed)
        assertTrue(resultData.inFlightMutation().isPresent());
        assertEquals(0L, resultData.blocksMined(), "Must not credit blocks when chunk is unloaded");
        assertEquals(0L, resultData.itemsPendingDelivery(), "Must not credit drops when chunk is unloaded");
    }

    @Test
    void reconciliationFailsClosedWhenTargetReplacedByForeignBlock() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));
        MinerJobData staged = data.withInFlightMutation(targetPos, stoneId, drops);
        WorkerProfile profile = createMinerProfile(staged, JobStatus.RUNNING);

        // Target position in loaded chunk contains DIRT instead of expected STONE (foreign block replacement)
        BlockState dirtState = Blocks.DIRT.defaultBlockState();
        MinerJobController.ReconciliationOutcome outcome = MinerJobController.evaluateReconciliation(profile, true, dirtState);

        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, outcome.status());
        WorkerProfile resultProfile = outcome.profile();
        MinerJobData resultData = resultProfile.job().orElseThrow().minerData().orElseThrow();

        // In-flight mutation must be preserved unresolved (fail closed, no automatic credit)
        assertTrue(resultData.inFlightMutation().isPresent());
        assertEquals(0L, resultData.blocksMined(), "Must not credit blocks when replaced by foreign block");
        assertEquals(0L, resultData.itemsPendingDelivery(), "Must not credit drops when replaced by foreign block");
    }

    @Test
    void reconciliationCancelsWhenTargetStillContainsOriginalBlock() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.withDefaultNamespace("stone");
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));
        MinerJobData staged = data.withInFlightMutation(targetPos, stoneId, drops);
        WorkerProfile profile = createMinerProfile(staged, JobStatus.RUNNING);

        // Target position still contains original STONE (e.g. server crashed before destruction, or replaced with identical block)
        BlockState stoneState = Blocks.STONE.defaultBlockState();
        MinerJobController.ReconciliationOutcome outcome = MinerJobController.evaluateReconciliation(profile, true, stoneState);

        assertEquals(MinerJobController.ReconciliationStatus.CANCELLED, outcome.status());
        WorkerProfile resultProfile = outcome.profile();
        MinerJobData resultData = resultProfile.job().orElseThrow().minerData().orElseThrow();

        // Transaction cancelled cleanly so intact block can be mined normally
        assertTrue(resultData.inFlightMutation().isEmpty());
        assertEquals(0L, resultData.blocksMined());
        assertEquals(0L, resultData.itemsPendingDelivery());
        assertTrue(resultData.pendingStacks().isEmpty());
    }

    @Test
    void reconciliationFailsClosedOnAmbiguousAirState() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.withDefaultNamespace("stone");
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));
        MinerJobData staged = data.withInFlightMutation(targetPos, stoneId, drops);
        WorkerProfile profile = createMinerProfile(staged, JobStatus.RUNNING);

        // Target position is AIR: observing AIR does not prove worker attribution
        // Fails closed to prevent duplicate loot exploitation
        BlockState airState = Blocks.AIR.defaultBlockState();
        MinerJobController.ReconciliationOutcome outcome = MinerJobController.evaluateReconciliation(profile, true, airState);

        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_AMBIGUOUS, outcome.status());
        WorkerProfile resultProfile = outcome.profile();
        MinerJobData resultData = resultProfile.job().orElseThrow().minerData().orElseThrow();

        // In-flight mutation must remain unresolved; must NOT credit blocksMined or pendingStacks
        assertTrue(resultData.inFlightMutation().isPresent());
        assertEquals(0L, resultData.blocksMined(), "Must not credit blocksMined on ambiguous air observation");
        assertEquals(0L, resultData.itemsPendingDelivery(), "Must not credit loot on ambiguous air observation");
        assertTrue(resultData.pendingStacks().isEmpty(), "Pending stacks must remain empty on ambiguous air observation");
    }

    @Test
    void reconciliationFailsClosedOnTerminatedJob() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> drops = List.of(new ItemStack(Items.COBBLESTONE, 1));
        MinerJobData staged = data.withInFlightMutation(targetPos, stoneId, drops);

        // Job is in FAILED state (terminal)
        WorkerJob failedJob = new WorkerJob(WorkerJobs.MINE, JobStatus.FAILED, 50, Optional.of("Manual cancel"), Optional.of(WORKSITE), Optional.of(staged));
        WorkerProfile failedProfile = new WorkerProfile(WORKER_ID, OWNER_ID, OVERWORLD, WorkerStatus.IDLE,
                Optional.of(failedJob), WorkerBehavior.STAND, Optional.empty());

        MinerJobController.ReconciliationOutcome outcome = MinerJobController.evaluateReconciliation(failedProfile, true, Blocks.AIR.defaultBlockState());
        assertEquals(MinerJobController.ReconciliationStatus.UNRESOLVED_TERMINATED_JOB, outcome.status());
        assertTrue(outcome.profile().job().orElseThrow().minerData().orElseThrow().inFlightMutation().isPresent());
    }

    @Test
    void calculateExpectedDropsRejectsUnverifiedTool() {
        ItemStack unverifiedTool = new ItemStack(Items.NETHER_STAR);
        BlockState stoneState = Blocks.STONE.defaultBlockState();

        assertThrows(IllegalArgumentException.class, () ->
                MinerJobController.calculateExpectedDrops(null, WORKSITE.position(), stoneState, null, unverifiedTool)
        );
    }

    @Test
    void calculateExpectedDropsRejectsBlockEntities() {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockState chestState = Blocks.CHEST.defaultBlockState();
        assertTrue(chestState.hasBlockEntity());

        assertThrows(IllegalStateException.class, () ->
                MinerJobController.calculateExpectedDrops(null, WORKSITE.position(), chestState, null, pick)
        );
    }

    @Test
    void runtimeExternalActorRemovalBeforeDestroyAbortsWithoutProgressOrDrops() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos targetPos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.fromNamespaceAndPath("test", "stone");
        List<ItemStack> expectedDrops = List.of(new ItemStack(Items.COBBLESTONE, 1));

        // Another actor removed the block to air before blockDestroyer.destroy() is executed:
        // In Minecraft Level.destroyBlock, attempting to destroy air returns false.
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, targetPos, null, stoneId,
                () -> expectedDrops,
                () -> false, // Block was already air when destroy was attempted
                () -> {},
                p -> {}
        );

        assertEquals(MinerJobController.TransactionStatus.MUTATION_FAILED, result.status());
        MinerJobData finalData = result.profile().job().orElseThrow().minerData().orElseThrow();
        assertEquals(0L, finalData.blocksMined(), "Must not credit blocks when target was removed by another actor");
        assertEquals(0L, finalData.itemsPendingDelivery(), "Must not credit drops when target was removed by another actor");
        assertTrue(finalData.inFlightMutation().isEmpty());
    }

    @Test
    void canExecuteMiningStepRejectsAirAndBlacklistedAndBlockEntities() {
        MinerJobData data = MinerJobData.initial(10L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos pos = WORKSITE.position();

        // 1. Air is rejected
        assertEquals(MinerJobController.ExecutionGate.REJECTED_SAFETY_POLICY,
                MinerJobController.canExecuteMiningStep(profile, null, null, pick, pos, Blocks.AIR.defaultBlockState()));

        // 2. Block Entity (Chest) is rejected
        assertEquals(MinerJobController.ExecutionGate.REJECTED_SAFETY_POLICY,
                MinerJobController.canExecuteMiningStep(profile, null, null, pick, pos, Blocks.CHEST.defaultBlockState()));

        // 3. Blacklisted block (Bedrock) is rejected
        assertEquals(MinerJobController.ExecutionGate.REJECTED_SAFETY_POLICY,
                MinerJobController.canExecuteMiningStep(profile, null, null, pick, pos, Blocks.BEDROCK.defaultBlockState()));
    }

    @Test
    void calculateExpectedDropsRejectsBlacklistedBlocks() {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

        assertThrows(IllegalStateException.class, () ->
                MinerJobController.calculateExpectedDrops(null, WORKSITE.position(), bedrock, null, pick)
        );
    }

    @Test
    void stoneToCobblestoneObjectiveTracksBlocksNotOutputItemType() {
        // Order specifies mining 3 Stone blocks. Pickaxe without Silk Touch produces Cobblestone drops.
        long requestedBlocks = 3L;
        MinerJobData data = MinerJobData.initial(requestedBlocks, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos pos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.withDefaultNamespace("stone");

        // Mine 3 Stone blocks sequentially, each yielding 1 Cobblestone
        for (int i = 0; i < 3; i++) {
            List<ItemStack> cobblestoneDrop = List.of(new ItemStack(Items.COBBLESTONE, 1));
            MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                    profile, null, null, pick, pos, null, stoneId,
                    () -> cobblestoneDrop,
                    () -> true,
                    () -> {},
                    staged -> {}
            );
            assertEquals(MinerJobController.TransactionStatus.SUCCESS, result.status());
            profile = result.profile();
        }

        MinerJobData minedData = profile.job().orElseThrow().minerData().orElseThrow();
        // Exact target block quota reached
        assertEquals(3L, minedData.blocksMined());
        assertEquals(3L, minedData.itemsPendingDelivery());
        assertEquals(3, minedData.pendingStacks().size());
        // All drops are Cobblestone (NOT Stone items)
        for (ItemStack pending : minedData.pendingStacks()) {
            assertEquals(Items.COBBLESTONE, pending.getItem());
            assertEquals(1, pending.getCount());
        }
        // Mining is finished, but delivery is not complete yet -> DELIVERING
        assertEquals(JobStatus.DELIVERING, profile.job().orElseThrow().status());
        assertFalse(minedData.isFullyCompleted());

        // Worker delivers Cobblestone drops to designated storage
        java.util.List<ItemStack> chestSlots = new java.util.ArrayList<>();
        chestSlots.add(ItemStack.EMPTY);
        profile = MinerJobController.deliverToStorage(profile, chestSlots, 1);

        MinerJobData deliveredData = profile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(3L, deliveredData.itemsDelivered());
        assertEquals(0L, deliveredData.itemsPendingDelivery());
        assertTrue(deliveredData.pendingStacks().isEmpty());
        // Objective satisfied: 3 stone blocks broken, 3 cobblestone delivered, job COMPLETED
        assertTrue(deliveredData.isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, profile.job().orElseThrow().status());
        assertEquals(Items.COBBLESTONE, chestSlots.get(0).getItem());
        assertEquals(3, chestSlots.get(0).getCount());
    }

    @Test
    void zeroDropsFromMinedBlockIncrementsBlocksMinedAndCompletesIfNoPending() {
        // Order specifies mining 1 block (e.g. glass broken with regular tool yields 0 drops)
        MinerJobData data = MinerJobData.initial(1L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos pos = WORKSITE.position();
        ResourceLocation glassId = ResourceLocation.withDefaultNamespace("glass");

        // Destroy block with 0 drops
        MinerJobController.TransactionExecutionResult result = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, pos, null, glassId,
                List::of, // Zero drops
                () -> true, // Successfully destroyed
                () -> {},
                staged -> {}
        );

        assertEquals(MinerJobController.TransactionStatus.SUCCESS, result.status());
        MinerJobData resultData = result.profile().job().orElseThrow().minerData().orElseThrow();

        // Invariant: zero drops still count as 1 successfully mined block
        assertEquals(1L, resultData.blocksMined());
        assertEquals(0L, resultData.itemsPendingDelivery());
        assertTrue(resultData.pendingStacks().isEmpty());
        assertTrue(resultData.isMiningFinished());
        assertTrue(resultData.isDeliveryComplete());
        assertTrue(resultData.isFullyCompleted());

        // Transitions directly to COMPLETED (no pending items to deliver)
        assertEquals(JobStatus.COMPLETED, result.profile().job().orElseThrow().status());
        assertEquals(100, result.profile().job().orElseThrow().progress());
    }

    @Test
    void multipleDropsFromSingleBlockIncrementsBlockCountByOneOnly() {
        // Order specifies 2 blocks. Target is Redstone Ore which yields multiple items per block.
        MinerJobData data = MinerJobData.initial(2L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos pos = WORKSITE.position();
        ResourceLocation redstoneOreId = ResourceLocation.withDefaultNamespace("redstone_ore");

        // Block 1 yields 5 redstone dust
        MinerJobController.TransactionExecutionResult result1 = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, pos, null, redstoneOreId,
                () -> List.of(new ItemStack(Items.REDSTONE, 5)),
                () -> true,
                () -> {},
                staged -> {}
        );
        assertEquals(MinerJobController.TransactionStatus.SUCCESS, result1.status());
        MinerJobData data1 = result1.profile().job().orElseThrow().minerData().orElseThrow();
        assertEquals(1L, data1.blocksMined(), "Blocks mined must increment by exactly 1, not drop count (5)");
        assertEquals(5L, data1.itemsPendingDelivery());

        // Block 2 yields 4 redstone dust
        MinerJobController.TransactionExecutionResult result2 = MinerJobController.executeMiningTransaction(
                result1.profile(), null, null, pick, pos, null, redstoneOreId,
                () -> List.of(new ItemStack(Items.REDSTONE, 4)),
                () -> true,
                () -> {},
                staged -> {}
        );
        assertEquals(MinerJobController.TransactionStatus.SUCCESS, result2.status());
        MinerJobData data2 = result2.profile().job().orElseThrow().minerData().orElseThrow();
        assertEquals(2L, data2.blocksMined(), "Blocks mined must be exactly 2, matching requested quota");
        assertEquals(9L, data2.itemsPendingDelivery(), "Items pending delivery must accurately sum all drops (5 + 4 = 9)");
        assertTrue(data2.isMiningFinished());
        assertFalse(data2.isDeliveryComplete());
        assertEquals(JobStatus.DELIVERING, result2.profile().job().orElseThrow().status());
    }

    @Test
    void partialDeliveryRemovesOnlyAcceptedAmountAndRetainsRemainderAcrossRestart() {
        // Worker has 50 diamonds pending delivery
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 50);
        MinerJobData data = new MinerJobData(1L, 1L, 50L, 0L, List.of(diamonds), STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Container only has room for 18 diamonds (already has 46 diamonds in a 64-capacity slot)
        java.util.List<ItemStack> chestSlots = new java.util.ArrayList<>();
        chestSlots.add(new ItemStack(Items.DIAMOND, 46));

        // Execute partial delivery
        WorkerProfile partialProfile = MinerJobController.deliverToStorage(profile, chestSlots, 1);
        MinerJobData partialData = partialProfile.job().orElseThrow().minerData().orElseThrow();

        // Exactly 18 items delivered, 32 remain pending
        assertEquals(64, chestSlots.get(0).getCount());
        assertEquals(18L, partialData.itemsDelivered());
        assertEquals(32L, partialData.itemsPendingDelivery());
        assertEquals(1, partialData.pendingStacks().size());
        assertEquals(32, partialData.pendingStacks().get(0).getCount());
        assertEquals(JobStatus.WAITING_FOR_STORAGE, partialProfile.job().orElseThrow().status());

        // Save & reload across restart
        var encoded = WorkerJob.CODEC.encodeStart(JsonOps.INSTANCE, partialProfile.job().orElseThrow()).result().orElseThrow();
        WorkerJob decodedJob = WorkerJob.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
        MinerJobData reloadedData = decodedJob.minerData().orElseThrow();

        assertEquals(18L, reloadedData.itemsDelivered());
        assertEquals(32L, reloadedData.itemsPendingDelivery());
        assertEquals(32, reloadedData.pendingStacks().get(0).getCount());

        // Container space becomes available (empty slot added)
        chestSlots.add(ItemStack.EMPTY);
        WorkerProfile reloadedProfile = profile.withJob(decodedJob);
        WorkerProfile resumedProfile = MinerJobController.deliverToStorage(reloadedProfile, chestSlots, 2);
        MinerJobData finalData = resumedProfile.job().orElseThrow().minerData().orElseThrow();

        assertEquals(50L, finalData.itemsDelivered());
        assertEquals(0L, finalData.itemsPendingDelivery());
        assertTrue(finalData.pendingStacks().isEmpty());
        assertTrue(finalData.isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, resumedProfile.job().orElseThrow().status());
        assertEquals(32, chestSlots.get(1).getCount());
    }

    @Test
    void fullStoragePausesDeliveryAndBlocksAnyFurtherMining() {
        MinerJobData data = new MinerJobData(10L, 5L, 10L, 0L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Storage is full (0 items accepted, storageFull = true)
        WorkerProfile fullProfile = MinerJobController.processDelivery(profile, 0L, true, true);
        MinerJobData fullData = fullProfile.job().orElseThrow().minerData().orElseThrow();

        assertEquals(JobStatus.WAITING_FOR_STORAGE, fullProfile.job().orElseThrow().status());
        assertEquals(10L, fullData.itemsPendingDelivery());
        assertEquals(0L, fullData.itemsDelivered());

        // Invariant: worker in WAITING_FOR_STORAGE cannot mine additional blocks
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        MinerJobController.ExecutionGate gate = MinerJobController.canExecuteMiningStep(
                fullProfile, null, null, pick, WORKSITE.position(), Blocks.STONE.defaultBlockState()
        );
        assertEquals(MinerJobController.ExecutionGate.REJECTED_INVALID_STATE, gate,
                "Worker with full/unavailable storage must be rejected from mining additional blocks");
    }

    @Test
    void retryAfterTransientFailureDoesNotDuplicateProgressOrDrops() {
        MinerJobData data = MinerJobData.initial(5L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos pos = WORKSITE.position();
        ResourceLocation stoneId = ResourceLocation.withDefaultNamespace("stone");
        List<ItemStack> drop = List.of(new ItemStack(Items.COBBLESTONE, 1));

        // Attempt 1: Transient failure (destroy returns false, e.g. cancelled by protection event)
        MinerJobController.TransactionExecutionResult failedResult = MinerJobController.executeMiningTransaction(
                profile, null, null, pick, pos, null, stoneId,
                () -> drop,
                () -> false, // Failure
                () -> {},
                staged -> {}
        );
        assertEquals(MinerJobController.TransactionStatus.MUTATION_FAILED, failedResult.status());
        MinerJobData failedData = failedResult.profile().job().orElseThrow().minerData().orElseThrow();
        assertEquals(0L, failedData.blocksMined());
        assertEquals(0L, failedData.itemsPendingDelivery());
        assertTrue(failedData.pendingStacks().isEmpty());
        assertTrue(failedData.inFlightMutation().isEmpty());

        // Attempt 2 (Retry): Next tick, condition resolved, destroy succeeds
        MinerJobController.TransactionExecutionResult retryResult = MinerJobController.executeMiningTransaction(
                failedResult.profile(), null, null, pick, pos, null, stoneId,
                () -> drop,
                () -> true, // Success
                () -> {},
                staged -> {}
        );
        assertEquals(MinerJobController.TransactionStatus.SUCCESS, retryResult.status());
        MinerJobData successData = retryResult.profile().job().orElseThrow().minerData().orElseThrow();
        assertEquals(1L, successData.blocksMined(), "Exactly 1 block mined on successful retry");
        assertEquals(1L, successData.itemsPendingDelivery(), "Exactly 1 drop queued on successful retry");
        assertEquals(1, successData.pendingStacks().size());
    }

    @Test
    void minerTunnelDirectionAndObjectiveModeSerializationAndPreservation() {
        // Test custom direction and ITEM_COUNT mode
        MinerJobData data = MinerJobData.initial(50L, STORAGE, Direction.EAST, ObjectiveMode.ITEM_COUNT);
        assertEquals(Direction.EAST, data.direction());
        assertEquals(ObjectiveMode.ITEM_COUNT, data.objectiveMode());

        // Test withDirection and withObjectiveMode
        MinerJobData changed = data.withDirection(Direction.SOUTH).withObjectiveMode(ObjectiveMode.BLOCK_COUNT);
        assertEquals(Direction.SOUTH, changed.direction());
        assertEquals(ObjectiveMode.BLOCK_COUNT, changed.objectiveMode());

        // Test Codec roundtrip
        var encoded = MinerJobData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var decoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(Direction.EAST, decoded.direction());
        assertEquals(ObjectiveMode.ITEM_COUNT, decoded.objectiveMode());
        assertEquals(50L, decoded.requestedBlocks());

        // Backwards-compatibility: decoding JSON missing direction and objectiveMode
        JsonObject legacyJson = new JsonObject();
        legacyJson.add("requested_blocks", new JsonPrimitive(25L));
        legacyJson.add("blocks_mined", new JsonPrimitive(0L));
        legacyJson.add("items_pending_delivery", new JsonPrimitive(0L));
        legacyJson.add("items_delivered", new JsonPrimitive(0L));
        legacyJson.add("pending_stacks", new com.google.gson.JsonArray());
        var storageObj = WorkerTarget.CODEC.encodeStart(JsonOps.INSTANCE, STORAGE).getOrThrow();
        legacyJson.add("storage", storageObj);

        var legacyDecoded = MinerJobData.CODEC.parse(JsonOps.INSTANCE, legacyJson).getOrThrow();
        assertEquals(Direction.NORTH, legacyDecoded.direction(), "Missing direction must default to NORTH");
        assertEquals(ObjectiveMode.BLOCK_COUNT, legacyDecoded.objectiveMode(), "Missing objectiveMode must default to BLOCK_COUNT");
    }

    @Test
    void minerObjectiveModeItemCountVsBlockCount() {
        // Mode 1: BLOCK_COUNT
        // Objective: mine 5 blocks. Drop counts do not dictate completion.
        MinerJobData blockModeData = MinerJobData.initial(5L, STORAGE, Direction.NORTH, ObjectiveMode.BLOCK_COUNT);
        WorkerProfile blockProfile = createMinerProfile(blockModeData, JobStatus.RUNNING);

        // Mine 1 block yielding 10 cobblestone drops
        blockProfile = MinerJobController.processMinedBlocks(blockProfile, 1L, List.of(new ItemStack(Items.COBBLESTONE, 10)));
        MinerJobData after1Block = blockProfile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(1L, after1Block.blocksMined());
        assertEquals(10L, after1Block.itemsPendingDelivery());
        assertEquals(4L, after1Block.blocksRemaining());
        assertFalse(after1Block.isMiningFinished());

        // Mine 4 more blocks yielding 0 drops
        blockProfile = MinerJobController.processMinedBlocks(blockProfile, 4L, List.of());
        MinerJobData after5Blocks = blockProfile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(5L, after5Blocks.blocksMined());
        assertEquals(0L, after5Blocks.blocksRemaining());
        assertTrue(after5Blocks.isMiningFinished(), "5 blocks mined finishes BLOCK_COUNT objective");

        // Mode 2: ITEM_COUNT
        // Objective: gather 10 items.
        MinerJobData itemModeData = MinerJobData.initial(10L, STORAGE, Direction.NORTH, ObjectiveMode.ITEM_COUNT);
        WorkerProfile itemProfile = createMinerProfile(itemModeData, JobStatus.RUNNING);

        // Mine 1 block yielding 4 items
        itemProfile = MinerJobController.processMinedBlocks(itemProfile, 1L, List.of(new ItemStack(Items.COBBLESTONE, 4)));
        MinerJobData itemAfter1 = itemProfile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(1L, itemAfter1.blocksMined());
        assertEquals(4L, itemAfter1.itemsPendingDelivery());
        assertEquals(6L, itemAfter1.blocksRemaining());
        assertFalse(itemAfter1.isMiningFinished());

        // Mine 1 block yielding 6 items -> total 10 items reached!
        itemProfile = MinerJobController.processMinedBlocks(itemProfile, 1L, List.of(new ItemStack(Items.COBBLESTONE, 6)));
        MinerJobData itemAfter2 = itemProfile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(2L, itemAfter2.blocksMined());
        assertEquals(10L, itemAfter2.itemsPendingDelivery());
        assertEquals(0L, itemAfter2.blocksRemaining());
        assertTrue(itemAfter2.isMiningFinished(), "10 items gathered finishes ITEM_COUNT objective");
    }

    @Test
    void tunnelTargetResultObstructionAndSafetyGuards() {
        BlockPos testPos = new BlockPos(10, 65, 10);

        // Target found
        MinerJobController.TunnelTargetResult targetResult = MinerJobController.TunnelTargetResult.ofTarget(testPos);
        assertTrue(targetResult.targetPos().isPresent());
        assertEquals(testPos, targetResult.targetPos().get());
        assertFalse(targetResult.obstructed());
        assertTrue(targetResult.obstructionReason().isEmpty());

        // Obstruction encountered
        MinerJobController.TunnelTargetResult obstructedResult =
                MinerJobController.TunnelTargetResult.obstructed(testPos, "Tunnel obstructed by bedrock");
        assertTrue(obstructedResult.targetPos().isEmpty());
        assertTrue(obstructedResult.obstructed());
        assertEquals("Tunnel obstructed by bedrock", obstructedResult.obstructionReason().orElse(""));

        // Unobstructed empty (tunnel fully cleared)
        MinerJobController.TunnelTargetResult emptyResult = MinerJobController.TunnelTargetResult.unobstructedEmpty();
        assertTrue(emptyResult.targetPos().isEmpty());
        assertFalse(emptyResult.obstructed());
        assertTrue(emptyResult.obstructionReason().isEmpty());
    }

    @Test
    void multiTripDeliveryBufferingAndContainerSlotHandling() {
        // Start with 100 blocks requested, 64 items pending delivery (buffering threshold met)
        MinerJobData data = new MinerJobData(100L, 20L, 64L, 0L, STORAGE);
        WorkerProfile profile = createMinerProfile(data, JobStatus.DELIVERING);

        // Container with only 1 slot that currently has 40 cobblestone (can accept 24 more)
        List<ItemStack> containerSlots = new ArrayList<>();
        containerSlots.add(new ItemStack(Items.COBBLESTONE, 40));

        // Attempt delivery of 64 cobblestone
        profile = MinerJobController.processMinedBlocks(profile, 0L, List.of(new ItemStack(Items.COBBLESTONE, 64)));
        profile = MinerJobController.deliverToStorage(profile, containerSlots, 1);
        MinerJobData trip1Data = profile.job().orElseThrow().minerData().orElseThrow();

        // 24 inserted into slot 0 (now 64), 40 remainder stays pending
        assertEquals(64, containerSlots.get(0).getCount());
        assertEquals(40L, trip1Data.itemsPendingDelivery());
        assertEquals(24L, trip1Data.itemsDelivered());
        assertEquals(JobStatus.WAITING_FOR_STORAGE, profile.job().orElseThrow().status(),
                "Must enter WAITING_FOR_STORAGE when container is full");

        // Player provides an additional empty chest slot
        containerSlots.add(ItemStack.EMPTY);

        // Next delivery attempt: remaining 40 items are deposited into empty slot 1
        profile = MinerJobController.deliverToStorage(profile, containerSlots, 2);
        MinerJobData trip2Data = profile.job().orElseThrow().minerData().orElseThrow();

        assertEquals(40, containerSlots.get(1).getCount());
        assertEquals(0L, trip2Data.itemsPendingDelivery());
        assertEquals(64L, trip2Data.itemsDelivered());
        assertTrue(trip2Data.pendingStacks().isEmpty());
        // Since blocksMined (20) < requestedBlocks (100), worker transitions back to RUNNING to continue mining!
        assertEquals(JobStatus.RUNNING, profile.job().orElseThrow().status(),
                "Must resume RUNNING when delivery finishes and more blocks remain to mine");
    }

    @Test
    void verticalDirectionNormalizedToHorizontalInMinerJobData() {
        // Attempting to initialize MinerJobData with UP or DOWN normalizes to NORTH
        MinerJobData upData = MinerJobData.initial(10L, STORAGE, Direction.UP, ObjectiveMode.BLOCK_COUNT);
        assertEquals(Direction.NORTH, upData.direction(), "Vertical direction UP must normalize to horizontal NORTH");

        MinerJobData downData = MinerJobData.initial(10L, STORAGE, Direction.DOWN, ObjectiveMode.BLOCK_COUNT);
        assertEquals(Direction.NORTH, downData.direction(), "Vertical direction DOWN must normalize to horizontal NORTH");

        // withDirection also normalizes vertical directions
        MinerJobData changed = upData.withDirection(Direction.UP);
        assertEquals(Direction.NORTH, changed.direction());

        MinerJobData changedDown = upData.withDirection(Direction.DOWN);
        assertEquals(Direction.NORTH, changedDown.direction());

        // Horizontal directions are preserved unchanged
        MinerJobData westData = upData.withDirection(Direction.WEST);
        assertEquals(Direction.WEST, westData.direction());
    }

    @Test
    void itemCountModeAllowsBlocksMinedToExceedRequestedCountWithoutClampingOrError() {
        // Request: 2 items (ITEM_COUNT mode).
        // It takes 3 blocks broken to get the items (first 2 blocks yield 0 drops, 3rd block yields 2 drops).
        MinerJobData data = MinerJobData.initial(2L, STORAGE, Direction.NORTH, ObjectiveMode.ITEM_COUNT);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Block 1 broken -> 0 drops
        profile = MinerJobController.processMinedBlocks(profile, 1L, List.of());
        MinerJobData after1 = profile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(1L, after1.blocksMined());
        assertEquals(0L, after1.itemsPendingDelivery());
        assertFalse(after1.isMiningFinished());

        // Block 2 broken -> 0 drops
        profile = MinerJobController.processMinedBlocks(profile, 1L, List.of());
        MinerJobData after2 = profile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(2L, after2.blocksMined());
        assertEquals(0L, after2.itemsPendingDelivery());
        assertFalse(after2.isMiningFinished());

        // Block 3 broken -> 2 drops
        // In ITEM_COUNT mode, blocksMined (3) exceeds requestedBlocks (2 items), which MUST be allowed and not clamped!
        profile = MinerJobController.processMinedBlocks(profile, 1L, List.of(new ItemStack(Items.COBBLESTONE, 2)));
        MinerJobData after3 = profile.job().orElseThrow().minerData().orElseThrow();
        assertEquals(3L, after3.blocksMined(), "blocksMined must not be clamped to requested item count in ITEM_COUNT mode");
        assertEquals(2L, after3.itemsPendingDelivery());
        assertTrue(after3.isMiningFinished(), "Item quota is now satisfied");
        assertEquals(0L, after3.blocksRemaining());
    }

    @Test
    void surplusLootInItemCountModeRetainedAndDeliveredWithoutVoiding() {
        // Request: 1 diamond (ITEM_COUNT mode).
        // The last block broken drops 4 diamonds (Fortune effect).
        MinerJobData data = MinerJobData.initial(1L, STORAGE, Direction.NORTH, ObjectiveMode.ITEM_COUNT);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Mine 1 block yielding 4 diamonds
        profile = MinerJobController.processMinedBlocks(profile, 1L, List.of(new ItemStack(Items.DIAMOND, 4)));
        MinerJobData afterMine = profile.job().orElseThrow().minerData().orElseThrow();

        // All 4 diamonds must be retained, not truncated to 1!
        assertEquals(1L, afterMine.blocksMined());
        assertEquals(4L, afterMine.itemsPendingDelivery(), "All 4 diamonds must be queued in pendingStacks");
        assertEquals(1, afterMine.pendingStacks().size());
        assertEquals(4, afterMine.pendingStacks().get(0).getCount());
        assertTrue(afterMine.isMiningFinished(), "1 diamond quota is met and exceeded");

        // Delivery into container
        List<ItemStack> chestSlots = new ArrayList<>(List.of(ItemStack.EMPTY));
        profile = MinerJobController.deliverToStorage(profile, chestSlots, 1);
        MinerJobData afterDelivery = profile.job().orElseThrow().minerData().orElseThrow();

        // All 4 diamonds deposited into chest, itemsDelivered is 4, pending is 0, status is COMPLETED
        assertEquals(4, chestSlots.get(0).getCount());
        assertEquals(4L, afterDelivery.itemsDelivered(), "All 4 surplus diamonds must be delivered");
        assertEquals(0L, afterDelivery.itemsPendingDelivery());
        assertTrue(afterDelivery.pendingStacks().isEmpty());
        assertTrue(afterDelivery.isFullyCompleted());
        assertEquals(JobStatus.COMPLETED, profile.job().orElseThrow().status());
    }

    @Test
    void minerItemCountModeQuotaSatisfiedWithPendingStacksCannotCompleteUntilDelivered() {
        // Request: 10 redstone dust (ITEM_COUNT mode).
        MinerJobData data = MinerJobData.initial(10L, STORAGE, Direction.NORTH, ObjectiveMode.ITEM_COUNT);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Break 2 blocks yielding 12 redstone dust total (quota 10 satisfied with surplus)
        profile = MinerJobController.processMinedBlocks(profile, 2L, List.of(new ItemStack(Items.REDSTONE, 12)));
        WorkerJob job = profile.job().orElseThrow();
        MinerJobData minerData = job.minerData().orElseThrow();

        // Verify independent concepts:
        assertEquals(10L, minerData.requestedBlocks(), "Requested item quota is 10");
        assertEquals(2L, minerData.blocksMined(), "Actual blocks broken is 2 (not clamped)");
        assertEquals(12L, minerData.itemsPendingDelivery(), "12 items pending delivery");
        assertEquals(0L, minerData.itemsDelivered(), "Items delivered remains 0 prior to destination insertion");
        assertTrue(minerData.isMiningFinished(), "Item quota is met/exceeded");
        assertFalse(minerData.isDeliveryComplete(), "Delivery is not complete while 12 items remain pending");
        assertFalse(minerData.isFullyCompleted(), "Job is not fully completed while items remain pending");
        assertEquals(JobStatus.DELIVERING, job.status(), "Status must transition to DELIVERING, never prematurely COMPLETED");

        // Attempting an explicit transition to COMPLETED while items are pending must fail closed
        Optional<WorkerJob> prematureComplete = job.transition(JobStatus.COMPLETED, 100, null);
        assertTrue(prematureComplete.isEmpty(), "WorkerJob must reject COMPLETED transition while pending items remain");

        // Storage with space for only 8 items (partial delivery)
        ItemStack partialStack = new ItemStack(Items.REDSTONE, 56); // max 64, room for 8
        List<ItemStack> slots = new ArrayList<>(List.of(partialStack));
        profile = MinerJobController.deliverToStorage(profile, slots, 1);
        WorkerJob partialJob = profile.job().orElseThrow();
        MinerJobData partialData = partialJob.minerData().orElseThrow();

        assertEquals(8L, partialData.itemsDelivered(), "8 items deposited into storage");
        assertEquals(4L, partialData.itemsPendingDelivery(), "4 items remain pending delivery");
        assertFalse(partialData.isFullyCompleted(), "Still not fully completed with 4 items pending");
        assertEquals(JobStatus.WAITING_FOR_STORAGE, partialJob.status(), "Must transition to WAITING_FOR_STORAGE when destination is full");

        // Storage expanded: second container accepts remaining 4 items
        List<ItemStack> emptySlots = new ArrayList<>(List.of(ItemStack.EMPTY));
        profile = MinerJobController.deliverToStorage(profile, emptySlots, 1);
        WorkerJob finalJob = profile.job().orElseThrow();
        MinerJobData finalData = finalJob.minerData().orElseThrow();

        assertEquals(12L, finalData.itemsDelivered(), "All 12 items delivered (including 2 surplus)");
        assertEquals(0L, finalData.itemsPendingDelivery(), "0 items pending");
        assertTrue(finalData.isFullyCompleted(), "Fully completed now that all pending items are deposited");
        assertEquals(JobStatus.COMPLETED, finalJob.status(), "Job transitions to COMPLETED only after full delivery");
        assertEquals(100, finalJob.progress());
    }

    @Test
    void minerItemCountModeProgressReflectsItemsGatheredNotBlocksMined() {
        // Request: 100 lapis lazuli items (ITEM_COUNT mode)
        MinerJobData data = MinerJobData.initial(100L, STORAGE, Direction.NORTH, ObjectiveMode.ITEM_COUNT);
        WorkerProfile profile = createMinerProfile(data, JobStatus.RUNNING);

        // Mine 5 ore blocks, yielding 45 lapis lazuli items total
        profile = MinerJobController.processMinedBlocks(profile, 5L, List.of(new ItemStack(Items.LAPIS_LAZULI, 45)));
        WorkerJob job = profile.job().orElseThrow();

        // In ITEM_COUNT mode, progress must be 45% (45 items gathered / 100 requested), NOT 5% (5 blocks / 100)!
        assertEquals(5L, job.minerData().orElseThrow().blocksMined());
        assertEquals(45L, job.minerData().orElseThrow().itemsPendingDelivery());
        assertEquals(45, job.progress(), "Progress in ITEM_COUNT mode must measure items gathered toward quota");

        // Mine 5 more blocks yielding 40 more items (total 85 items)
        profile = MinerJobController.processMinedBlocks(profile, 5L, List.of(new ItemStack(Items.LAPIS_LAZULI, 40)));
        WorkerJob job2 = profile.job().orElseThrow();
        assertEquals(10L, job2.minerData().orElseThrow().blocksMined());
        assertEquals(85L, job2.minerData().orElseThrow().itemsPendingDelivery());
        assertEquals(85, job2.progress(), "Progress must update to 85% based on item count");
    }
}
