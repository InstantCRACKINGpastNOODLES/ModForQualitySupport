package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-authoritative controller for autonomous Farmer jobs.
 * Enforces the complete lifecycle:
 * Travel to worksite -> find eligible mature crop -> harvest -> collect drops ->
 * replant if planting item available -> transport output to storage -> complete objective.
 *
 * Invariants:
 * 1. Progress increments once per successfully broken mature crop block; replanting never increments progress.
 * 2. Crops harvested never exceed the player's requested quota.
 * 3. Harvested produce is delivered to designated storage without dropping on the ground.
 * 4. Replanting strictly requires a matching planting item and valid supporting block.
 * 5. Replanting failure refunds the planting item without loss or duplication.
 */
public final class FarmerJobController {

    public enum ExecutionGate {
        PERMITTED,
        REJECTED_JOB_FINISHED,
        REJECTED_UNVERIFIED_TOOL,
        REJECTED_STORAGE_INVALID,
        REJECTED_SAFETY_POLICY,
        REJECTED_INVALID_STATE,
        REJECTED_UNSUPPORTED_CROP,
        REJECTED_UNPERMITTED_CROP,
        REJECTED_IMMATURE_CROP
    }

    public enum TransactionStatus {
        SUCCESS,
        REJECTED_PREFLIGHT,
        MUTATION_FAILED,
        RECOVERED_COMMITTED,
        RECOVERED_CANCELLED,
        FAILED_POST_MUTATION
    }

    public record TransactionExecutionResult(
            TransactionStatus status,
            WorkerProfile profile,
            List<ItemStack> drops,
            boolean replanted,
            Optional<String> message
    ) {
        public static TransactionExecutionResult success(WorkerProfile profile, List<ItemStack> drops, boolean replanted) {
            return new TransactionExecutionResult(TransactionStatus.SUCCESS, profile, drops, replanted, Optional.empty());
        }

        public static TransactionExecutionResult rejected(TransactionStatus status, WorkerProfile profile, String reason) {
            return new TransactionExecutionResult(status, profile, List.of(), false, Optional.ofNullable(reason));
        }
    }

    public enum ReconciliationStatus {
        COMMITTED,
        CANCELLED,
        UNRESOLVED_AMBIGUOUS,
        UNRESOLVED_CHUNK_UNLOADED,
        UNRESOLVED_TERMINATED_JOB,
        NO_OP
    }

    public record ReconciliationOutcome(
            ReconciliationStatus status,
            WorkerProfile profile,
            Optional<String> reason
    ) {
        public static ReconciliationOutcome of(ReconciliationStatus status, WorkerProfile profile, String reason) {
            return new ReconciliationOutcome(status, profile, Optional.ofNullable(reason));
        }
    }

    @FunctionalInterface
    public interface DropSupplier {
        List<ItemStack> getDrops() throws Exception;
    }

    @FunctionalInterface
    public interface BlockDestroyer {
        boolean destroy() throws Exception;
    }

    @FunctionalInterface
    public interface Replanter {
        boolean replant() throws Exception;
    }

    @FunctionalInterface
    public interface ProfileCommitter {
        void commit(WorkerProfile profile);
    }

    private FarmerJobController() {
    }

    /**
     * Pre-action validation gate before a worker harvests a crop.
     */
    public static ExecutionGate canExecuteHarvestStep(
            WorkerProfile profile,
            Mob worker,
            ServerPlayer owner,
            ItemStack tool,
            BlockPos targetPos,
            BlockState state
    ) {
        if (profile == null || profile.job().isEmpty() ||
                !WorkerJobs.FARM.equals(profile.job().get().type()) || profile.job().get().farmerData().isEmpty()) {
            return ExecutionGate.REJECTED_INVALID_STATE;
        }

        if (worker != null && owner != null) {
            if (!profile.entityId().equals(worker.getUUID()) || !profile.ownerId().equals(owner.getUUID())) {
                return ExecutionGate.REJECTED_INVALID_STATE;
            }
        }

        WorkerJob job = profile.job().get();
        if (job.status() != JobStatus.RUNNING && job.status() != JobStatus.ASSIGNED) {
            return ExecutionGate.REJECTED_INVALID_STATE;
        }

        FarmerJobData farmerData = job.farmerData().get();
        if (farmerData.isHarvestFinished()) {
            return ExecutionGate.REJECTED_JOB_FINISHED;
        }

        // 1. Tool / Hand Capability
        if (!WorkerJobCapability.Registry.canPerformFarming(worker, tool)) {
            return ExecutionGate.REJECTED_UNVERIFIED_TOOL;
        }

        // 2. Storage proximity
        WorkerTarget storageTarget = farmerData.storage();
        if (job.target().isPresent()) {
            BlockPos worksitePos = job.target().get().position();
            if (!JobSafetyPolicy.isStorageDistanceSafe(worksitePos, storageTarget.position(), Config.getMaxStorageDistance())) {
                return ExecutionGate.REJECTED_STORAGE_INVALID;
            }
        }

        // 3. Target Block Validation
        if (state == null || state.isAir() || state.hasBlockEntity() || JobSafetyPolicy.isBlockBlacklisted(state.getBlock())) {
            return ExecutionGate.REJECTED_SAFETY_POLICY;
        }

        if (!CropRegistry.isSupportedCrop(state.getBlock())) {
            return ExecutionGate.REJECTED_UNSUPPORTED_CROP;
        }

        if (!farmerData.isCropPermitted(state.getBlock())) {
            return ExecutionGate.REJECTED_UNPERMITTED_CROP;
        }

        Level level = worker != null ? worker.level() : null;
        if (!CropRegistry.isMature(level, targetPos, state)) {
            return ExecutionGate.REJECTED_IMMATURE_CROP;
        }

        return ExecutionGate.PERMITTED;
    }

    /**
     * Credits successfully harvested crop blocks and queues collected produce for delivery.
     */
    public static WorkerProfile processHarvestedCrop(WorkerProfile profile, long actualCropsHarvested, List<ItemStack> drops) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return profile;
        }
        WorkerJob currentJob = profile.job().get();
        FarmerJobData currentData = currentJob.farmerData().get();

        FarmerJobData updatedData = currentData.withCropHarvested(actualCropsHarvested, drops);
        WorkerJob updatedJob = currentJob.withFarmerData(updatedData).orElse(currentJob);

        if (updatedData.isHarvestFinished()) {
            if (updatedData.itemsPendingDelivery() > 0) {
                updatedJob = updatedJob.transition(JobStatus.DELIVERING, updatedJob.progress(), null).orElse(updatedJob);
            } else {
                updatedJob = updatedJob.transition(JobStatus.COMPLETED, 100, null).orElse(updatedJob);
            }
        }

        return profile.withJob(updatedJob);
    }

    /**
     * Executes durable item delivery into container slots.
     */
    public static WorkerProfile deliverToStorage(WorkerProfile profile, List<ItemStack> containerSlots, int maxSlots) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return profile;
        }
        WorkerJob currentJob = profile.job().get();
        FarmerJobData currentData = currentJob.farmerData().get();

        if (containerSlots == null) {
            WorkerJob waiting = currentJob.transition(JobStatus.WAITING_FOR_STORAGE, currentJob.progress(),
                    "Designated storage container is missing or unavailable").orElse(currentJob);
            return profile.withJob(waiting);
        }

        List<ItemStack> remainingPending = new ArrayList<>();
        long totalDeliveredInStep = 0L;

        for (ItemStack pending : currentData.pendingStacks()) {
            if (pending == null || pending.isEmpty()) continue;
            ItemStack toInsert = pending.copy();

            // 1. Merge into matching existing stacks
            for (int i = 0; i < containerSlots.size() && i < maxSlots; i++) {
                ItemStack slotStack = containerSlots.get(i);
                if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(slotStack, toInsert)) {
                    int space = slotStack.getMaxStackSize() - slotStack.getCount();
                    if (space > 0) {
                        int inserted = Math.min(space, toInsert.getCount());
                        slotStack.grow(inserted);
                        toInsert.shrink(inserted);
                        totalDeliveredInStep += inserted;
                        if (toInsert.isEmpty()) break;
                    }
                }
            }

            // 2. Insert remainder into empty container slots
            if (!toInsert.isEmpty()) {
                for (int i = 0; i < containerSlots.size() && i < maxSlots; i++) {
                    ItemStack slotStack = containerSlots.get(i);
                    if (slotStack.isEmpty()) {
                        int inserted = Math.min(toInsert.getMaxStackSize(), toInsert.getCount());
                        ItemStack placed = toInsert.copy();
                        placed.setCount(inserted);
                        containerSlots.set(i, placed);
                        toInsert.shrink(inserted);
                        totalDeliveredInStep += inserted;
                        if (toInsert.isEmpty()) break;
                    }
                }
            }

            // 3. Uninserted remainder stays in pending
            if (!toInsert.isEmpty()) {
                remainingPending.add(toInsert);
            }
        }

        FarmerJobData updatedData = currentData.withItemsDelivered(totalDeliveredInStep, remainingPending);
        WorkerJob updatedJob = currentJob.withFarmerData(updatedData).orElse(currentJob);

        boolean storageFull = !remainingPending.isEmpty();
        if (storageFull || updatedData.itemsPendingDelivery() > 0) {
            updatedJob = updatedJob.transition(JobStatus.WAITING_FOR_STORAGE, updatedJob.progress(),
                    "Designated storage container is full").orElse(updatedJob);
        } else if (updatedData.isFullyCompleted()) {
            updatedJob = updatedJob.transition(JobStatus.COMPLETED, 100, null).orElse(updatedJob);
        } else if (updatedData.isHarvestFinished()) {
            updatedJob = updatedJob.transition(JobStatus.DELIVERING, updatedJob.progress(), null).orElse(updatedJob);
        } else {
            updatedJob = updatedJob.transition(JobStatus.RUNNING, updatedJob.progress(), null).orElse(updatedJob);
        }

        return profile.withJob(updatedJob);
    }

    /**
     * Executes durable item delivery into a live Minecraft Container.
     */
    public static WorkerProfile deliverToContainer(WorkerProfile profile, Container container) {
        if (container == null) {
            return profile;
        }
        List<ItemStack> slots = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            slots.add(container.getItem(i));
        }
        WorkerProfile updated = deliverToStorage(profile, slots, container.getContainerSize());
        for (int i = 0; i < container.getContainerSize() && i < slots.size(); i++) {
            container.setItem(i, slots.get(i));
        }
        container.setChanged();
        return updated;
    }

    /**
     * Evaluates an uncommitted InFlightHarvest against the world state for idempotent crash recovery.
     */
    public static ReconciliationOutcome evaluateReconciliation(
            WorkerProfile profile,
            boolean chunkLoaded,
            BlockState currentState
    ) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return ReconciliationOutcome.of(ReconciliationStatus.NO_OP, profile, "No farmer job present");
        }
        WorkerJob job = profile.job().get();
        FarmerJobData data = job.farmerData().get();
        if (data.inFlightHarvest().isEmpty()) {
            return ReconciliationOutcome.of(ReconciliationStatus.NO_OP, profile, "No in-flight harvest to reconcile");
        }

        FarmerJobData.InFlightHarvest inFlight = data.inFlightHarvest().get();

        if (job.status() == JobStatus.COMPLETED || job.status() == JobStatus.FAILED) {
            return ReconciliationOutcome.of(ReconciliationStatus.UNRESOLVED_TERMINATED_JOB, profile,
                    "Job is in terminal status (" + job.status().name() + "); preserving unresolved transaction");
        }

        if (!chunkLoaded || currentState == null) {
            return ReconciliationOutcome.of(ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, profile,
                    "Target chunk at " + inFlight.pos() + " is unloaded or unavailable; failing closed to preserve transaction");
        }

        ResourceLocation currentBlockId = BuiltInRegistries.BLOCK.getKey(currentState.getBlock());

        // Case A: Target block is air -> harvested without replant
        if (currentState.isAir()) {
            WorkerProfile committedProfile = reconcileInFlightHarvest(profile, true);
            return ReconciliationOutcome.of(ReconciliationStatus.COMMITTED, committedProfile,
                    "Target crop verified broken (air); transaction committed");
        }

        // Case B: Target position contains a young replanted crop (e.g. age 0)
        if (inFlight.blockId().equals(currentBlockId)) {
            if (CropRegistry.isReplanted(null, inFlight.pos(), currentState)) {
                WorkerProfile committedProfile = reconcileInFlightHarvest(profile, true);
                return ReconciliationOutcome.of(ReconciliationStatus.COMMITTED, committedProfile,
                        "Target crop verified replanted (age 0); transaction committed");
            }
            // If still mature, mutation never happened
            if (CropRegistry.isMature(null, inFlight.pos(), currentState)) {
                WorkerProfile cancelledProfile = reconcileInFlightHarvest(profile, false);
                return ReconciliationOutcome.of(ReconciliationStatus.CANCELLED, cancelledProfile,
                        "Target crop still intact; transaction cancelled without crediting progress");
            }
        }

        return ReconciliationOutcome.of(ReconciliationStatus.UNRESOLVED_AMBIGUOUS, profile,
                "Target crop position in unexpected state (" + currentBlockId + "); failing closed to preserve transaction");
    }

    /**
     * Evaluates an uncommitted InFlightHarvest against the world state for a given level.
     * Guaranteed to fail closed if chunk is unloaded.
     */
    public static ReconciliationOutcome evaluateReconciliation(
            WorkerProfile profile,
            Level level
    ) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return ReconciliationOutcome.of(ReconciliationStatus.NO_OP, profile, "No farmer job present");
        }
        FarmerJobData data = profile.job().get().farmerData().get();
        if (data.inFlightHarvest().isEmpty()) {
            return ReconciliationOutcome.of(ReconciliationStatus.NO_OP, profile, "No in-flight harvest to reconcile");
        }

        BlockPos targetPos = data.inFlightHarvest().get().pos();
        if (level == null) {
            return ReconciliationOutcome.of(ReconciliationStatus.UNRESOLVED_CHUNK_UNLOADED, profile,
                    "Level is null; cannot inspect target chunk at " + targetPos);
        }

        boolean chunkLoaded = level.hasChunkAt(targetPos);
        BlockState currentState = chunkLoaded ? level.getBlockState(targetPos) : null;
        return evaluateReconciliation(profile, chunkLoaded, currentState);
    }

    public static WorkerProfile reconcileInFlightHarvest(WorkerProfile profile, Level level) {
        return evaluateReconciliation(profile, level).profile();
    }

    public static Optional<WorkerProfile> reconcileInFlightHarvest(Mob worker) {
        if (worker == null || !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            return Optional.empty();
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        ReconciliationOutcome outcome = evaluateReconciliation(profile, worker.level());
        WorkerProfile reconciled = outcome.profile();

        // If cancelled on a live worker, refund consumed replant seed if applicable
        if (outcome.status() == ReconciliationStatus.CANCELLED) {
            profile.job().flatMap(WorkerJob::farmerData).flatMap(FarmerJobData::inFlightHarvest).flatMap(FarmerJobData.InFlightHarvest::replantItemConsumed)
                    .ifPresent(itemId -> {
                        Item item = BuiltInRegistries.ITEM.get(itemId);
                        if (item != null && item != Items.AIR) {
                            refundToWorkerStorage(worker, item, -1);
                        }
                    });
        }

        if (!Objects.equals(profile, reconciled)) {
            worker.setData(ExampleMod.WORKER_PROFILE, reconciled);
        }
        return Optional.of(reconciled);
    }

    /**
     * Calculates drop ItemStacks for a crop block using Minecraft loot tables,
     * accounting for tool enchantments (Fortune), worker entity context, and block state.
     */
    public static List<ItemStack> calculateCropDrops(ServerLevel level, BlockPos pos, BlockState state, Mob worker, ItemStack tool) {
        if (level == null || pos == null || state == null) {
            return List.of();
        }
        try {
            return Block.getDrops(state, level, pos, null, worker, tool != null ? tool : ItemStack.EMPTY);
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static WorkerProfile reconcileInFlightHarvest(WorkerProfile profile, boolean cropHarvested) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return profile;
        }
        WorkerJob job = profile.job().get();
        FarmerJobData data = job.farmerData().get();
        if (data.inFlightHarvest().isEmpty()) {
            return profile;
        }

        if (cropHarvested) {
            FarmerJobData committedData = data.commitInFlightHarvest();
            WorkerJob updatedJob = job.withFarmerData(committedData).orElse(job);
            if (committedData.isHarvestFinished()) {
                if (committedData.itemsPendingDelivery() > 0) {
                    if (updatedJob.status() != JobStatus.DELIVERING) {
                        updatedJob = updatedJob.transition(JobStatus.DELIVERING, updatedJob.progress(), null).orElse(updatedJob);
                    }
                } else {
                    if (updatedJob.status() != JobStatus.COMPLETED) {
                        updatedJob = updatedJob.transition(JobStatus.COMPLETED, 100, null).orElse(updatedJob);
                    }
                }
            }
            return profile.withJob(updatedJob);
        } else {
            FarmerJobData cancelledData = data.withoutInFlightHarvest();
            WorkerJob updatedJob = job.withFarmerData(cancelledData).orElse(job);
            return profile.withJob(updatedJob);
        }
    }

    /**
     * Executes an end-to-end crash-safe harvest and replant transaction.
     * Enforces:
     * 1. Preflight validation.
     * 2. Calculation of crop drops.
     * 3. Seed / planting item deduction from WorkerStorage or drops.
     * 4. Intent staging in profile (InFlightHarvest).
     * 5. Block destruction.
     * 6. Replanting at harvested position if planting item was consumed.
     * 7. Progress and output accounting commit.
     */
    public static TransactionExecutionResult executeHarvestTransaction(
            WorkerProfile profile,
            Mob worker,
            ServerPlayer owner,
            ItemStack tool,
            BlockPos targetPos,
            BlockState state,
            ResourceLocation blockId,
            DropSupplier dropSupplier,
            BlockDestroyer blockDestroyer,
            Replanter replanter,
            ProfileCommitter profileCommitter
    ) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return TransactionExecutionResult.rejected(TransactionStatus.REJECTED_PREFLIGHT, profile, "Invalid worker profile or farmer job");
        }

        WorkerProfile currentProfile = profile;
        FarmerJobData currentData = currentProfile.job().get().farmerData().get();

        // 1. Reconcile any existing in-flight harvest before proceeding
        if (currentData.inFlightHarvest().isPresent()) {
            if (worker != null && worker.level() != null) {
                currentProfile = reconcileInFlightHarvest(currentProfile, worker.level());
                currentData = currentProfile.job().get().farmerData().get();
            }
            if (currentData.inFlightHarvest().isPresent()) {
                return TransactionExecutionResult.rejected(TransactionStatus.REJECTED_PREFLIGHT, currentProfile,
                        "Worker has an unresolved in-flight harvest from a previous operation; reconcile before proceeding");
            }
        }

        // 2. Preflight validation & live block state revalidation
        BlockState actualState = state;
        if (worker != null && worker.level() instanceof ServerLevel serverLevel) {
            actualState = serverLevel.getBlockState(targetPos);
        }
        ExecutionGate gate = canExecuteHarvestStep(currentProfile, worker, owner, tool, targetPos, actualState);
        if (gate != ExecutionGate.PERMITTED) {
            return TransactionExecutionResult.rejected(TransactionStatus.REJECTED_PREFLIGHT, currentProfile,
                    "Preflight check rejected harvest: " + gate.name());
        }

        // 3. Drop calculation
        List<ItemStack> rawDrops;
        try {
            rawDrops = (dropSupplier != null) ? dropSupplier.getDrops() : List.of();
        } catch (Exception ex) {
            return TransactionExecutionResult.rejected(TransactionStatus.REJECTED_PREFLIGHT, currentProfile,
                    "Drop calculation threw exception: " + ex.getMessage());
        }

        List<ItemStack> mutableDrops = new ArrayList<>();
        if (rawDrops != null) {
            for (ItemStack stack : rawDrops) {
                if (stack != null && !stack.isEmpty()) {
                    mutableDrops.add(stack.copy());
                }
            }
        }

        // 4. Determine replanting eligibility and item availability
        Level level = worker != null ? worker.level() : null;
        Optional<Item> plantingItemOpt = CropRegistry.getPlantingItem(level, targetPos, actualState);
        boolean canReplant = CropRegistry.canReplant(level, targetPos, actualState);

        Item consumedItem = null;
        boolean consumedFromStorage = false;
        int storageSlotConsumed = -1;

        if (canReplant && plantingItemOpt.isPresent()) {
            Item requiredItem = plantingItemOpt.get();

            // Check WorkerStorage first
            if (worker != null && worker.hasData(ExampleMod.WORKER_STORAGE)) {
                WorkerStorage storage = worker.getData(ExampleMod.WORKER_STORAGE);
                for (int i = 0; i < storage.items().size(); i++) {
                    ItemStack slot = storage.items().get(i);
                    if (!slot.isEmpty() && slot.is(requiredItem)) {
                        consumedItem = requiredItem;
                        consumedFromStorage = true;
                        storageSlotConsumed = i;
                        break;
                    }
                }
            }

            // If not in WorkerStorage, check if available in fresh drops
            if (consumedItem == null) {
                for (ItemStack drop : mutableDrops) {
                    if (drop.is(requiredItem) && drop.getCount() >= 1) {
                        drop.shrink(1);
                        consumedItem = requiredItem;
                        consumedFromStorage = false;
                        break;
                    }
                }
                mutableDrops.removeIf(ItemStack::isEmpty);
            }
        }

        Optional<ResourceLocation> replantItemConsumed = consumedItem != null ?
                Optional.of(BuiltInRegistries.ITEM.getKey(consumedItem)) : Optional.empty();

        // Deduct from WorkerStorage if consumed from there
        if (consumedFromStorage && worker != null && storageSlotConsumed >= 0) {
            WorkerStorage storage = worker.getData(ExampleMod.WORKER_STORAGE);
            List<ItemStack> updatedStorage = new ArrayList<>(storage.items());
            ItemStack slotStack = updatedStorage.get(storageSlotConsumed).copy();
            slotStack.shrink(1);
            updatedStorage.set(storageSlotConsumed, slotStack.isEmpty() ? ItemStack.EMPTY : slotStack);
            worker.setData(ExampleMod.WORKER_STORAGE, new WorkerStorage(updatedStorage));
        }

        // 5. Stage InFlightHarvest intent
        FarmerJobData stagedData = currentData.withInFlightHarvest(targetPos, blockId, mutableDrops, replantItemConsumed);
        WorkerJob stagedJob = currentProfile.job().get().withFarmerData(stagedData).orElse(currentProfile.job().get());
        WorkerProfile stagedProfile = currentProfile.withJob(stagedJob);

        if (profileCommitter != null) {
            try {
                profileCommitter.commit(stagedProfile);
            } catch (Exception ex) {
                // Refund seed if staging threw
                if (consumedFromStorage && worker != null && storageSlotConsumed >= 0 && consumedItem != null) {
                    refundToWorkerStorage(worker, consumedItem, storageSlotConsumed);
                }
                return TransactionExecutionResult.rejected(TransactionStatus.REJECTED_PREFLIGHT, currentProfile,
                        "Staging in-flight profile failed: " + ex.getMessage());
            }
        }

        // 6. Block destruction
        boolean destroyed;
        try {
            destroyed = blockDestroyer != null && blockDestroyer.destroy();
        } catch (Exception ex) {
            return new TransactionExecutionResult(TransactionStatus.FAILED_POST_MUTATION, stagedProfile, mutableDrops, false,
                    Optional.of("Block mutation threw exception: " + ex.getMessage()));
        }

        if (!destroyed) {
            // Unstage cleanly without crediting progress and refund consumed seed
            if (consumedFromStorage && worker != null && consumedItem != null) {
                refundToWorkerStorage(worker, consumedItem, storageSlotConsumed);
            }
            WorkerProfile cancelledProfile = reconcileInFlightHarvest(stagedProfile, false);
            if (profileCommitter != null) {
                profileCommitter.commit(cancelledProfile);
            }
            return TransactionExecutionResult.rejected(TransactionStatus.MUTATION_FAILED, cancelledProfile, "Block destruction returned false");
        }

        // 7. Conditional Replanting
        boolean replanted = false;
        if (consumedItem != null) {
            try {
                replanted = replanter != null && replanter.replant();
            } catch (Exception ex) {
                replanted = false;
            }

            if (!replanted) {
                // Replant failed: refund seed back so it is NEVER lost or deleted into void!
                if (consumedFromStorage) {
                    boolean refunded = refundToWorkerStorage(worker, consumedItem, storageSlotConsumed);
                    if (!refunded) {
                        // WorkerStorage has no room! Must not void the seed; add to mutableDrops so it is queued for delivery!
                        mutableDrops.add(new ItemStack(consumedItem, 1));
                    }
                } else {
                    // Seed was taken from drops, so put it back into drops!
                    mutableDrops.add(new ItemStack(consumedItem, 1));
                }

                // If drops or replant status changed, update staged intent before committing!
                stagedData = stagedData.withInFlightHarvest(targetPos, blockId, mutableDrops, Optional.empty());
                stagedJob = stagedJob.withFarmerData(stagedData).orElse(stagedJob);
                stagedProfile = stagedProfile.withJob(stagedJob);
            }
        }

        // 8. Commit transaction
        WorkerProfile committedProfile = reconcileInFlightHarvest(stagedProfile, true);
        if (profileCommitter != null) {
            profileCommitter.commit(committedProfile);
        }

        return TransactionExecutionResult.success(committedProfile, mutableDrops, replanted);
    }

    public static Optional<WorkerStorage> refundToStorage(WorkerStorage storage, Item item, int preferredSlot) {
        if (storage == null || item == null) {
            return Optional.empty();
        }
        List<ItemStack> items = new ArrayList<>(storage.items());

        // 1. Try preferred slot if valid
        if (preferredSlot >= 0 && preferredSlot < items.size()) {
            ItemStack preferred = items.get(preferredSlot);
            if (preferred.isEmpty()) {
                items.set(preferredSlot, new ItemStack(item, 1));
                return Optional.of(new WorkerStorage(items));
            } else if (preferred.is(item) && preferred.getCount() < preferred.getMaxStackSize()) {
                ItemStack grown = preferred.copy();
                grown.grow(1);
                items.set(preferredSlot, grown);
                return Optional.of(new WorkerStorage(items));
            }
        }

        // 2. Try merge into any matching slot
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                ItemStack grown = stack.copy();
                grown.grow(1);
                items.set(i, grown);
                return Optional.of(new WorkerStorage(items));
            }
        }

        // 3. Try any empty slot
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, new ItemStack(item, 1));
                return Optional.of(new WorkerStorage(items));
            }
        }

        return Optional.empty();
    }

    public static boolean refundToWorkerStorage(Mob worker, Item item, int preferredSlot) {
        if (worker == null || item == null || !worker.hasData(ExampleMod.WORKER_STORAGE)) {
            return false;
        }
        WorkerStorage storage = worker.getData(ExampleMod.WORKER_STORAGE);
        Optional<WorkerStorage> updated = refundToStorage(storage, item, preferredSlot);
        if (updated.isPresent()) {
            worker.setData(ExampleMod.WORKER_STORAGE, updated.get());
            return true;
        }
        return false;
    }

    public static WorkerProfile handleToolExhaustion(WorkerProfile profile) {
        if (profile == null || profile.job().isEmpty() || profile.job().get().farmerData().isEmpty()) {
            return profile;
        }
        WorkerJob currentJob = profile.job().get();
        FarmerJobData data = currentJob.farmerData().get();

        if (data.itemsPendingDelivery() > 0) {
            WorkerJob delivering = currentJob.transition(JobStatus.DELIVERING, currentJob.progress(), null).orElse(currentJob);
            return profile.withJob(delivering);
        } else {
            WorkerJob waiting = currentJob.transition(JobStatus.WAITING_FOR_TOOL, currentJob.progress(),
                    "Farming tool missing or durability exhausted; waiting for replacement tool").orElse(currentJob);
            return profile.withJob(waiting);
        }
    }
}
