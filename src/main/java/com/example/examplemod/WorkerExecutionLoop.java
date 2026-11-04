package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Enforces worker execution and behavior priority:
 * 1. Owner & entity validity/permissions
 * 2. Stand mode stops autonomous movement and pauses running Move jobs
 * 3. Authorized active jobs override Follow
 * 4. Follow operates when no higher-priority behavior or job is active
 * 5. Home defines an allowed roaming boundary
 */
public final class WorkerExecutionLoop {
    enum Result { INELIGIBLE, UNAVAILABLE, SKIPPED, FAILED, REQUESTED }
    private static final Map<PathfinderMob, WorkerTarget> REQUESTED_PATHS = new WeakHashMap<>();

    private WorkerExecutionLoop() {
    }

    static Result step(WorkerProfile profile, UUID entityId, UUID ownerId, boolean server,
                       boolean available, boolean permitted, Supplier<Result> action) {
        if (!server || profile == null || entityId == null || ownerId == null ||
                !profile.entityId().equals(entityId) || !profile.ownerId().equals(ownerId) ||
                profile.job().isEmpty() || !WorkerJobs.isKnown(profile.job().get().type()) ||
                (profile.job().get().status() != JobStatus.ASSIGNED &&
                        profile.job().get().status() != JobStatus.RUNNING)) {
            return Result.INELIGIBLE;
        }
        if (!available) {
            return Result.UNAVAILABLE;
        }
        if (!permitted || action == null) {
            return Result.INELIGIBLE;
        }
        try {
            Result result = action.get();
            return result == null ? Result.FAILED : result;
        } catch (RuntimeException exception) {
            return Result.FAILED;
        }
    }

    static Result navigate(WorkerProfile profile, net.minecraft.resources.ResourceLocation dimension,
                           boolean loaded, boolean valid, boolean permitted, boolean navigating,
                           long tick, Supplier<Boolean> request) {
        if (profile == null || profile.job().isEmpty() ||
                !WorkerJobs.MOVE.equals(profile.job().get().type()) ||
                (profile.job().get().status() != JobStatus.ASSIGNED &&
                        profile.job().get().status() != JobStatus.RUNNING) ||
                profile.job().get().target().isEmpty() ||
                !profile.dimension().equals(dimension) ||
                !profile.job().get().target().get().dimension().equals(dimension) ||
                !permitted || !valid) {
            return Result.INELIGIBLE;
        }
        if (!loaded) return Result.UNAVAILABLE;
        if (navigating || tick % 40 != 0) return Result.SKIPPED;
        try {
            return request != null && Boolean.TRUE.equals(request.get()) ? Result.REQUESTED : Result.FAILED;
        } catch (RuntimeException exception) {
            return Result.FAILED;
        }
    }

    static Result follow(WorkerProfile profile, double distSq, boolean navigating, long tick, Supplier<Boolean> request) {
        if (profile == null || profile.behavior() != WorkerBehavior.FOLLOW) {
            return Result.INELIGIBLE;
        }
        // Active running job overrides follow
        if (profile.job().isPresent() && profile.job().get().status() == JobStatus.RUNNING) {
            return Result.INELIGIBLE;
        }
        if (distSq <= 9.0) {
            return Result.SKIPPED; // Close enough (within 3 blocks)
        }
        if (distSq > 1024.0) {
            return Result.UNAVAILABLE; // Too far (beyond 32 blocks)
        }
        if (navigating && tick % 20 != 0) {
            return Result.SKIPPED; // Throttled path requests
        }
        try {
            return request != null && Boolean.TRUE.equals(request.get()) ? Result.REQUESTED : Result.FAILED;
        } catch (RuntimeException exception) {
            return Result.FAILED;
        }
    }

    static Result stand(WorkerProfile profile, boolean navigating, Runnable stopNavigation) {
        if (profile == null || profile.behavior() != WorkerBehavior.STAND) {
            return Result.INELIGIBLE;
        }
        if (navigating) {
            if (stopNavigation != null) stopNavigation.run();
            return Result.REQUESTED;
        }
        return Result.SKIPPED;
    }

    /**
     * Executes a validated world-modifying block action under JobSafetyPolicy.
     * Enforces pre-mutation validation: unverified tools, tool replacement, out-of-bounds targets,
     * blacklisted blocks, block entities, and exhausted budgets/quotas immediately abort execution.
     */
    static Result executeBlockAction(
            WorkerProfile profile,
            Mob worker,
            ServerPlayer owner,
            BlockPos worksite,
            BlockPos targetPos,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.item.ItemStack expectedTool,
            int plannedBlocksInTick,
            long performedQuantity,
            long playerRequestedQuantity,
            Supplier<Result> action
    ) {
        if (profile == null || worker == null || owner == null || !(worker.level() instanceof ServerLevel level)) {
            return Result.INELIGIBLE;
        }

        net.minecraft.world.item.ItemStack currentTool = worker.getMainHandItem();
        JobSafetyPolicy.SafetyValidationResult validation = JobSafetyPolicy.validateBlockMutation(
                level, worksite, targetPos, state, owner, currentTool, expectedTool,
                plannedBlocksInTick, performedQuantity, playerRequestedQuantity
        );

        if (!validation.isPermitted()) {
            return Result.FAILED;
        }

        if (action == null) {
            return Result.FAILED;
        }

        try {
            Result result = action.get();
            return result == null ? Result.FAILED : result;
        } catch (RuntimeException exception) {
            return Result.FAILED;
        }
    }

    public static void ensureStanding(Mob worker) {
        if (worker instanceof net.minecraft.world.entity.TamableAnimal tamable) {
            if (tamable.isOrderedToSit()) {
                tamable.setOrderedToSit(false);
            }
            if (tamable.isInSittingPose()) {
                tamable.setInSittingPose(false);
            }
        }
        try {
            java.lang.reflect.Method m = worker.getClass().getMethod("setMaidSitting", boolean.class);
            m.invoke(worker, false);
        } catch (Throwable ignored) {}
    }

    public static boolean isInsideHome(WorkerProfile profile, BlockPos pos) {
        return profile != null && profile.isWithinHomeArea(pos);
    }

    public enum ToolSwapResult {
        SUCCESS,
        NO_TOOL_AVAILABLE,
        INSUFFICIENT_STORAGE_SPACE
    }

    /**
     * Resolves all item stacks currently reserved by the worker's active job and queue.
     */
    public static java.util.Collection<ItemStack> getWorkerReservedStacks(Mob worker) {
        if (worker != null && worker.hasData(ExampleMod.WORKER_PROFILE)) {
            WorkerProfile prof = worker.getData(ExampleMod.WORKER_PROFILE);
            return prof.getReservedItemStacks();
        }
        return java.util.Collections.emptyList();
    }

    /**
     * Algorithmic core of unified, job-driven tool swapping.
     * Enforces strict lossless tool transfer:
     * - Respects JobItemReservation: never extracts or steals reserved or pending-delivery items.
     * - Evaluates candidates against JobToolRequirement with deterministic candidate ranking.
     * - Takes exactly 1 item from the storage slot (preserving remaining stacked count).
     * - Never overwrites, discards, or world-drops the existing Main Hand item.
     * - If WorkerStorage has insufficient capacity to safely accept the old held item,
     *   the swap is aborted immediately: the hand and storage remain completely unmodified.
     * - Preserves all enchantments, custom components, and durability values.
     * Returns ToolSwapResult indicating outcome.
     */
    public static ToolSwapResult swapJobTool(
            WorkerStorage storage,
            ItemStack held,
            JobToolRequirement requirement,
            java.util.Collection<ItemStack> reservedStacks,
            java.util.function.Consumer<ItemStack> handSetter,
            java.util.function.Consumer<WorkerStorage> storageSetter
    ) {
        if (storage == null || requirement == null) return ToolSwapResult.NO_TOOL_AVAILABLE;
        List<ItemStack> items = storage.items();
        if (items == null || items.isEmpty()) return ToolSwapResult.NO_TOOL_AVAILABLE;

        // 1. Group reserved counts by item + component identity
        List<ItemStack> resTypes = new ArrayList<>();
        List<Integer> resCounts = new ArrayList<>();
        if (reservedStacks != null) {
            for (ItemStack res : reservedStacks) {
                if (res == null || res.isEmpty()) continue;
                int matchIdx = -1;
                for (int i = 0; i < resTypes.size(); i++) {
                    if (ItemStack.isSameItemSameComponents(resTypes.get(i), res)) {
                        matchIdx = i;
                        break;
                    }
                }
                if (matchIdx >= 0) {
                    resCounts.set(matchIdx, resCounts.get(matchIdx) + res.getCount());
                } else {
                    resTypes.add(res.copy());
                    resCounts.add(res.getCount());
                }
            }
        }

        // 2. Count total storage occurrences by item + component identity
        List<ItemStack> storageTypes = new ArrayList<>();
        List<Integer> storageCounts = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;
            int matchIdx = -1;
            for (int i = 0; i < storageTypes.size(); i++) {
                if (ItemStack.isSameItemSameComponents(storageTypes.get(i), stack)) {
                    matchIdx = i;
                    break;
                }
            }
            if (matchIdx >= 0) {
                storageCounts.set(matchIdx, storageCounts.get(matchIdx) + stack.getCount());
            } else {
                storageTypes.add(stack.copy());
                storageCounts.add(stack.getCount());
            }
        }

        // 3. Find the best unreserved candidate slot
        int bestSlotIdx = -1;
        int bestScore = Integer.MIN_VALUE;
        int bestDurability = -1;

        for (int i = 0; i < items.size(); i++) {
            ItemStack candidate = items.get(i);
            if (candidate == null || candidate.isEmpty()) continue;
            if (!requirement.isCandidate(candidate)) continue;

            // Check if this candidate type has unreserved units available
            int totalInStorage = 0;
            for (int s = 0; s < storageTypes.size(); s++) {
                if (ItemStack.isSameItemSameComponents(storageTypes.get(s), candidate)) {
                    totalInStorage = storageCounts.get(s);
                    break;
                }
            }
            int totalReserved = 0;
            for (int r = 0; r < resTypes.size(); r++) {
                if (ItemStack.isSameItemSameComponents(resTypes.get(r), candidate)) {
                    totalReserved = resCounts.get(r);
                    break;
                }
            }

            int unreserved = totalInStorage - totalReserved;
            if (unreserved <= 0) {
                continue; // All copies of this item type in storage are reserved!
            }

            int score = requirement.scoreCandidate(candidate);
            int dur = candidate.isDamageableItem() ?
                    (candidate.getMaxDamage() - candidate.getDamageValue()) : Integer.MAX_VALUE;

            if (bestSlotIdx == -1 || score > bestScore || (score == bestScore && dur > bestDurability)) {
                bestSlotIdx = i;
                bestScore = score;
                bestDurability = dur;
            }
        }

        if (bestSlotIdx == -1) {
            return ToolSwapResult.NO_TOOL_AVAILABLE;
        }

        // 4. Safely extract 1 count from the chosen candidate slot
        ItemStack chosenCandidate = items.get(bestSlotIdx);
        ItemStack toolToEquip = chosenCandidate.copyWithCount(1);
        ItemStack remainingInSlot = chosenCandidate.copy();
        remainingInSlot.shrink(1);

        ItemStack oldHeld = (held != null) ? held.copy() : ItemStack.EMPTY;
        List<ItemStack> simulated = new ArrayList<>(items.stream().map(ItemStack::copy).toList());
        simulated.set(bestSlotIdx, remainingInSlot.isEmpty() ? ItemStack.EMPTY : remainingInSlot);

        if (!oldHeld.isEmpty()) {
            ItemStack toStash = oldHeld.copy();
            // 4a. Try to merge into matching existing stacks in simulated storage
            for (int j = 0; j < simulated.size(); j++) {
                ItemStack slot = simulated.get(j);
                if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, toStash)) {
                    int space = slot.getMaxStackSize() - slot.getCount();
                    if (space > 0) {
                        int toAdd = Math.min(space, toStash.getCount());
                        slot.grow(toAdd);
                        toStash.shrink(toAdd);
                        if (toStash.isEmpty()) break;
                    }
                }
            }
            // 4b. Insert remainder into empty slots (including vacated bestSlotIdx if applicable)
            if (!toStash.isEmpty()) {
                for (int j = 0; j < simulated.size(); j++) {
                    if (simulated.get(j).isEmpty()) {
                        int toPlace = Math.min(toStash.getMaxStackSize(), toStash.getCount());
                        ItemStack placed = toStash.copy();
                        placed.setCount(toPlace);
                        simulated.set(j, placed);
                        toStash.shrink(toPlace);
                        if (toStash.isEmpty()) break;
                    }
                }
            }

            // 4c. If oldHeld could not be completely accommodated, FAIL CLOSED:
            // Do not drop to world, do not modify hand, do not modify storage!
            if (!toStash.isEmpty()) {
                return ToolSwapResult.INSUFFICIENT_STORAGE_SPACE;
            }
        }

        // Both sides guaranteed safe: apply changes
        if (handSetter != null) {
            handSetter.accept(toolToEquip);
        }
        if (storageSetter != null) {
            storageSetter.accept(new WorkerStorage(simulated));
        }
        return ToolSwapResult.SUCCESS;
    }

    public static ToolSwapResult swapJobTool(
            WorkerStorage storage,
            ItemStack held,
            JobToolRequirement requirement,
            java.util.function.Consumer<ItemStack> handSetter,
            java.util.function.Consumer<WorkerStorage> storageSetter
    ) {
        return swapJobTool(storage, held, requirement, java.util.Collections.emptyList(), handSetter, storageSetter);
    }

    /**
     * Backward-compatible overload for mining tool swapping.
     */
    public static ToolSwapResult swapMiningTool(
            WorkerStorage storage,
            ItemStack held,
            java.util.function.Consumer<ItemStack> handSetter,
            java.util.function.Consumer<WorkerStorage> storageSetter
    ) {
        return swapJobTool(storage, held, JobToolRequirement.MINING, java.util.Collections.emptyList(), handSetter, storageSetter);
    }

    /**
     * Backward-compatible overload for tool swap testing.
     * Ignores overflowDropper as world drops are strictly prohibited for item preservation.
     */
    public static boolean swapMiningTool(
            WorkerStorage storage,
            ItemStack held,
            java.util.function.Consumer<ItemStack> handSetter,
            java.util.function.Consumer<WorkerStorage> storageSetter,
            java.util.function.Consumer<ItemStack> overflowDropper
    ) {
        return swapMiningTool(storage, held, handSetter, storageSetter) == ToolSwapResult.SUCCESS;
    }

    /**
     * Inspects the worker's equipped main hand and WorkerStorage for an authorized tool required by the job type.
     * Returns ToolSwapResult indicating whether the tool is equipped or why it failed.
     */
    public static ToolSwapResult tryEnsureToolEquippedForJob(Mob worker, ResourceLocation jobType) {
        if (worker == null) return ToolSwapResult.NO_TOOL_AVAILABLE;
        JobToolRequirement req = JobToolRequirement.forJobType(jobType);
        ItemStack held = worker.getMainHandItem();
        if (req.isSatisfiedBy(held)) {
            return ToolSwapResult.SUCCESS;
        }

        if (worker.hasData(ExampleMod.WORKER_STORAGE)) {
            WorkerStorage ws = worker.getData(ExampleMod.WORKER_STORAGE);
            java.util.Collection<ItemStack> reserved = getWorkerReservedStacks(worker);
            return swapJobTool(
                    ws,
                    held,
                    req,
                    reserved,
                    tool -> worker.setItemSlot(EquipmentSlot.MAINHAND, tool),
                    updatedStorage -> worker.setData(ExampleMod.WORKER_STORAGE, updatedStorage)
            );
        }
        return ToolSwapResult.NO_TOOL_AVAILABLE;
    }

    public static boolean ensureToolEquippedForJob(Mob worker, ResourceLocation jobType) {
        return tryEnsureToolEquippedForJob(worker, jobType) == ToolSwapResult.SUCCESS;
    }

    /**
     * Inspects the worker's equipped main hand and WorkerStorage for an authorized mining tool.
     * Returns ToolSwapResult indicating whether the tool is equipped or why it failed.
     */
    public static ToolSwapResult tryEnsureMiningToolEquipped(Mob worker) {
        return tryEnsureToolEquippedForJob(worker, WorkerJobs.MINE);
    }

    /**
     * Inspects the worker's equipped main hand and WorkerStorage for an authorized mining tool.
     * If the main hand does not have a verified, durable tool, searches WorkerStorage and equips one.
     * Enforces lossless tool transfer: never drops items into the world.
     * Returns true if the worker is equipped with a verified mining tool ready for action.
     */
    public static boolean ensureMiningToolEquipped(Mob worker) {
        return tryEnsureMiningToolEquipped(worker) == ToolSwapResult.SUCCESS;
    }

    /**
     * Attempts to find and swap to a fallback mining tool capable of harvesting the specified target block state.
     * Searches WorkerStorage for pickaxes that satisfy JobToolRequirement.MINING and isCorrectToolForDrops(targetState).
     */
    public static ToolSwapResult tryFallbackMiningTool(Mob worker, BlockState targetState) {
        if (worker == null) return ToolSwapResult.NO_TOOL_AVAILABLE;
        ItemStack held = worker.getMainHandItem();
        if (worker.hasData(ExampleMod.WORKER_STORAGE)) {
            WorkerStorage ws = worker.getData(ExampleMod.WORKER_STORAGE);
            java.util.Collection<ItemStack> reserved = getWorkerReservedStacks(worker);
            JobToolRequirement req = new JobToolRequirement() {
                private static final ResourceLocation ID =
                        ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "fallback_mining_tool_requirement");

                @Override
                public ResourceLocation id() {
                    return ID;
                }

                @Override
                public boolean isSatisfiedBy(ItemStack stack) {
                    return isCandidate(stack);
                }

                @Override
                public boolean isCandidate(ItemStack candidate) {
                    if (!JobToolRequirement.MINING.isCandidate(candidate)) {
                        return false;
                    }
                    if (targetState != null && targetState.requiresCorrectToolForDrops()) {
                        boolean correct = candidate.isCorrectToolForDrops(targetState);
                        if (!correct && MinerJobController.isExplicitUnbootstrappedTestEnvironment(worker)) {
                            correct = MinerJobController.isToolAppropriateForBlockInTestEnv(candidate, targetState);
                        }
                        return correct;
                    }
                    return true;
                }

                @Override
                public int scoreCandidate(ItemStack candidate) {
                    int base = JobToolRequirement.MINING.scoreCandidate(candidate);
                    boolean correct = targetState != null && targetState.requiresCorrectToolForDrops() &&
                            (candidate.isCorrectToolForDrops(targetState) ||
                             (MinerJobController.isExplicitUnbootstrappedTestEnvironment(worker) &&
                              MinerJobController.isToolAppropriateForBlockInTestEnv(candidate, targetState)));
                    if (correct) {
                        base += 1000000;
                    }
                    return base;
                }
            };
            return swapJobTool(
                    ws,
                    held,
                    req,
                    reserved,
                    tool -> worker.setItemSlot(EquipmentSlot.MAINHAND, tool),
                    updatedStorage -> worker.setData(ExampleMod.WORKER_STORAGE, updatedStorage)
            );
        }
        return ToolSwapResult.NO_TOOL_AVAILABLE;
    }

    /**
     * Handles the outcome of a mining transaction, resolving preflight failures, fallback tool swaps,
     * tool exhaustion, quota completion, invalid storage, and bounded retries.
     */
    public static void handleMiningTransactionResult(
            ServerLevel level,
            Mob worker,
            ServerPlayer owner,
            MinerJobController.TransactionExecutionResult result,
            BlockPos targetPos,
            BlockState targetState,
            ItemStack tool
    ) {
        if (result == null || worker == null) {
            return;
        }

        WorkerProfile currentProfile = worker.getData(ExampleMod.WORKER_PROFILE);
        if (currentProfile == null || currentProfile.job().isEmpty() || currentProfile.job().get().minerData().isEmpty()) {
            return;
        }
        WorkerJob job = currentProfile.job().get();
        MinerJobData minerData = job.minerData().get();

        switch (result.status()) {
            case SUCCESS -> {
                if (minerData.retryCount() > 0 || minerData.retryDelay() > 0) {
                    MinerJobData updated = minerData.withRetryCount(0).withRetryDelay(0);
                    job.withMinerData(updated).ifPresent(j -> worker.setData(ExampleMod.WORKER_PROFILE, currentProfile.withJob(j)));
                }
            }
            case REJECTED_PREFLIGHT -> {
                Optional<MinerJobController.ExecutionGate> gateOpt = result.gate();
                if (gateOpt.isPresent()) {
                    switch (gateOpt.get()) {
                        case REJECTED_UNVERIFIED_TOOL -> {
                            ToolSwapResult swapRes = tryFallbackMiningTool(worker, targetState);
                            if (swapRes != ToolSwapResult.SUCCESS) {
                                WorkerProfile updated = MinerJobController.handleToolExhaustion(currentProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, updated);
                                if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                                    String blockName = (targetState != null) ? targetState.getBlock().getName().getString() : "target block";
                                    owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker waiting for tool: No tool in inventory can harvest " + blockName), true);
                                }
                            }
                        }
                        case REJECTED_TOOL_EXHAUSTED -> {
                            ToolSwapResult swapRes = tryEnsureMiningToolEquipped(worker);
                            if (swapRes != ToolSwapResult.SUCCESS) {
                                WorkerProfile updated = MinerJobController.handleToolExhaustion(currentProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, updated);
                                if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                                    owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_waiting_for_tool"), true);
                                }
                            }
                        }
                        case REJECTED_JOB_FINISHED -> {
                            JobStatus nextStatus = minerData.itemsPendingDelivery() > 0 ? JobStatus.DELIVERING : JobStatus.COMPLETED;
                            currentProfile.transition(nextStatus, job.progress(), null)
                                    .ifPresent(p -> worker.setData(ExampleMod.WORKER_PROFILE, p));
                        }
                        case REJECTED_STORAGE_INVALID -> {
                            currentProfile.transition(JobStatus.WAITING_FOR_STORAGE, job.progress(), "Designated storage container invalid")
                                    .ifPresent(p -> worker.setData(ExampleMod.WORKER_PROFILE, p));
                            if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker waiting: Storage container is invalid or missing"), true);
                            }
                        }
                        case REJECTED_CHUNK_UNLOADED -> {
                            // Target chunk is unloaded: safe wait without consuming retries or clearing target
                            return;
                        }
                        default -> {
                            if (minerData.activeTarget().isPresent()) {
                                MinerJobData cleared = minerData.withoutActiveTarget().withRetryCount(0).withRetryDelay(0);
                                job.withMinerData(cleared).ifPresent(j -> worker.setData(ExampleMod.WORKER_PROFILE, currentProfile.withJob(j)));
                            }
                            if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                                String reason = result.message().orElse("Preflight check rejected mining action: " + gateOpt.get().name());
                                owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker paused: " + reason), true);
                            }
                        }
                    }
                } else {
                    if (targetState != null && targetState.requiresCorrectToolForDrops() && (tool == null || !tool.isCorrectToolForDrops(targetState))) {
                        ToolSwapResult swapRes = tryFallbackMiningTool(worker, targetState);
                        if (swapRes != ToolSwapResult.SUCCESS) {
                            WorkerProfile updated = MinerJobController.handleToolExhaustion(currentProfile);
                            worker.setData(ExampleMod.WORKER_PROFILE, updated);
                            if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker waiting for tool: No suitable tool to harvest " + targetState.getBlock().getName().getString()), true);
                            }
                            return;
                        }
                    }
                    if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                        String reason = result.message().orElse("Preflight check rejected mining action");
                        owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker preflight rejected: " + reason), true);
                    }
                }
            }
            case MUTATION_FAILED -> {
                int retries = minerData.retryCount() + 1;
                if (retries >= 3) {
                    MinerJobData updated = minerData.withRetryCount(retries).withRetryDelay(0);
                    WorkerJob updatedJob = job.withMinerData(updated).orElse(job);
                    WorkerProfile updatedProf = currentProfile.withJob(updatedJob);
                    String reason = "Repeated mutation failure at " + targetPos.toShortString();
                    updatedProf.transition(JobStatus.PAUSED, job.progress(), null)
                            .ifPresent(p -> worker.setData(ExampleMod.WORKER_PROFILE, p));
                    if (owner != null) {
                        owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker paused: " + reason), true);
                    }
                } else {
                    int backoffTicks = switch (retries) {
                        case 1 -> 10;
                        case 2 -> 20;
                        default -> 40;
                    };
                    MinerJobData updated = minerData.withRetryCount(retries).withRetryDelay(backoffTicks);
                    job.withMinerData(updated).ifPresent(j -> worker.setData(ExampleMod.WORKER_PROFILE, currentProfile.withJob(j)));
                }
            }
            case FAILED_POST_MUTATION -> {
                if (owner != null && level != null && level.getGameTime() % 40 == 0) {
                    owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker encountered error during mining step at " + targetPos.toShortString()), true);
                }
            }
            default -> {}
        }
    }

    /**
     * Inspects the worker's equipped main hand and WorkerStorage for an authorized farming tool.
     * Returns ToolSwapResult indicating whether the tool is equipped or why it failed.
     */
    public static ToolSwapResult tryEnsureFarmingToolEquipped(Mob worker) {
        return tryEnsureToolEquippedForJob(worker, WorkerJobs.FARM);
    }

    /**
     * Inspects the worker's equipped main hand and WorkerStorage for an authorized farming tool.
     * If the main hand does not have a verified, durable tool, searches WorkerStorage and equips one.
     * Enforces lossless tool transfer: never drops items into the world.
     * Returns true if the worker is equipped with an authorized farming tool ready for action.
     */
    public static boolean ensureFarmingToolEquipped(Mob worker) {
        return tryEnsureFarmingToolEquipped(worker) == ToolSwapResult.SUCCESS;
    }

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob worker) || !(worker.level() instanceof ServerLevel level) ||
                !level.getServer().isSameThread() || !worker.isAlive() || worker.isRemoved() ||
                !worker.hasData(ExampleMod.WORKER_PROFILE)) {
            return;
        }
        WorkerProfile profile = worker.getData(ExampleMod.WORKER_PROFILE);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(profile.ownerId());
        boolean available = owner != null && owner.level() == level && !owner.isRemoved();
        boolean permitted = available && owner.mayBuild() && profile.dimension().equals(level.dimension().location());

        if (!(worker instanceof PathfinderMob pathfinder)) {
            return;
        }

        // Priority 1 & 2: Stand mode stops autonomous movement and pauses active MOVE jobs
        if (profile.behavior() == WorkerBehavior.STAND) {
            TouhouLittleMaidCompat.suppressMaidFollow(worker);
            if (REQUESTED_PATHS.containsKey(pathfinder)) {
                pathfinder.getNavigation().stop();
                REQUESTED_PATHS.remove(pathfinder);
            }
            if (!pathfinder.getNavigation().isDone()) {
                pathfinder.getNavigation().stop();
            }
            // If MOVE job was running, pause it
            if (profile.job().isPresent() && profile.job().get().status() == JobStatus.RUNNING) {
                profile.transition(JobStatus.PAUSED, profile.job().get().progress(), null)
                        .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
            }
            return;
        }

        // Clean up completed or invalid requested MOVE paths
        if (REQUESTED_PATHS.containsKey(pathfinder)) {
            WorkerTarget requested = REQUESTED_PATHS.get(pathfinder);
            if (!permitted || profile.job().isEmpty() ||
                    !WorkerJobs.MOVE.equals(profile.job().get().type()) ||
                    (profile.job().get().status() != JobStatus.ASSIGNED &&
                            profile.job().get().status() != JobStatus.RUNNING) ||
                    !profile.job().get().target().equals(java.util.Optional.of(requested)) ||
                    !requested.dimension().equals(level.dimension().location()) ||
                    !level.hasChunkAt(requested.position())) {
                pathfinder.getNavigation().stop();
                REQUESTED_PATHS.remove(pathfinder);
            } else if (pathfinder.getNavigation().isDone()) {
                REQUESTED_PATHS.remove(pathfinder);
            }
        }

        // Priority 3: An authorized active MOVE job has precedence
        boolean hasActiveMoveJob = profile.job().isPresent() &&
                WorkerJobs.MOVE.equals(profile.job().get().type()) &&
                (profile.job().get().status() == JobStatus.ASSIGNED || profile.job().get().status() == JobStatus.RUNNING);

        // Priority 3.5: An authorized active FARM job has precedence
        boolean hasActiveFarmJob = profile.job().isPresent() &&
                WorkerJobs.FARM.equals(profile.job().get().type()) &&
                profile.job().get().farmerData().isPresent() &&
                (profile.job().get().status() == JobStatus.ASSIGNED ||
                 profile.job().get().status() == JobStatus.RUNNING ||
                 profile.job().get().status() == JobStatus.DELIVERING ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_STORAGE ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_TOOL ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_CROPS);

        // Priority 3.6: An authorized active MINE job has precedence
        boolean hasActiveMineJob = profile.job().isPresent() &&
                WorkerJobs.MINE.equals(profile.job().get().type()) &&
                profile.job().get().minerData().isPresent() &&
                (profile.job().get().status() == JobStatus.ASSIGNED ||
                 profile.job().get().status() == JobStatus.RUNNING ||
                 profile.job().get().status() == JobStatus.DELIVERING ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_STORAGE ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_TOOL ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_FALLING_BLOCK ||
                 profile.job().get().status() == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION ||
                 profile.job().get().status() == JobStatus.WAITING_FOR_SAFE_TUNNEL);

        // Priority 3.7: An extensible JobQueue has precedence
        boolean hasActiveQueueJob = profile.queue() != null && !profile.queue().isEmpty() && !profile.queue().isAllComplete();

        // Suppress external follow AI when executing any active autonomous job
        if (hasActiveMoveJob || hasActiveFarmJob || hasActiveMineJob || hasActiveQueueJob) {
            TouhouLittleMaidCompat.suppressMaidFollow(worker);
        }

        if (hasActiveMoveJob) {
            ensureStanding(worker);
            step(profile, worker.getUUID(), profile.ownerId(), true, available, permitted,
                    () -> {
                        WorkerTarget target = profile.job().get().target().get();
                        var pos = target.position();
                        boolean loaded = level.hasChunkAt(pos);
                        double maxTravelDist = Config.getMaxTravelDistance();
                        return navigate(profile, level.dimension().location(), loaded,
                                level.isInWorldBounds(pos) && worker.distanceToSqr(pos.getX() + 0.5,
                                        pos.getY() + 0.5, pos.getZ() + 0.5) <= maxTravelDist * maxTravelDist,
                                owner.mayBuild(), !pathfinder.getNavigation().isDone(), level.getGameTime(),
                                () -> {
                                    boolean started = pathfinder.getNavigation().moveTo(pos.getX() + 0.5,
                                            pos.getY(), pos.getZ() + 0.5, 1.0);
                                    if (started) {
                                        REQUESTED_PATHS.put(pathfinder, target);
                                        if (profile.job().get().status() == JobStatus.ASSIGNED) {
                                            profile.transition(JobStatus.RUNNING, 0, null)
                                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                                        }
                                    }
                                    return started;
                                });
                    });
            return;
        }

        if (hasActiveFarmJob) {
            ensureStanding(worker);
            FarmerJobController.reconcileInFlightHarvest(worker);
            WorkerProfile farmProfile = worker.getData(ExampleMod.WORKER_PROFILE);
            if (farmProfile.job().isEmpty() || farmProfile.job().get().farmerData().isEmpty()) {
                return;
            }
            WorkerJob job = farmProfile.job().get();
            FarmerJobData farmerData = job.farmerData().get();
            WorkerTarget worksite = job.target().orElse(null);
            WorkerTarget storage = farmerData.storage();

            if (worksite != null && permitted) {
                boolean needsDelivery = farmerData.isHarvestFinished() ||
                        job.status() == JobStatus.DELIVERING ||
                        (job.status() == JobStatus.WAITING_FOR_STORAGE && level.getGameTime() % 40 == 0) ||
                        farmerData.itemsPendingDelivery() >= 64 ||
                        farmerData.pendingStacks().size() >= 10;

                if (needsDelivery) {
                    BlockPos storagePos = storage.position();
                    if (level.hasChunkAt(storagePos)) {
                        double distToStorageSq = worker.distanceToSqr(storagePos.getX() + 0.5, storagePos.getY() + 0.5, storagePos.getZ() + 0.5);
                        if (distToStorageSq <= 3.5 * 3.5) {
                            if (!pathfinder.getNavigation().isDone()) {
                                pathfinder.getNavigation().stop();
                            }
                            worker.getLookControl().setLookAt(storagePos.getX() + 0.5, storagePos.getY() + 0.5, storagePos.getZ() + 0.5);
                            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                            WorkerProfile updated = StorageDestinationAdapter.deliverToDesignatedStorage(
                                    farmProfile, level, storagePos, owner
                            );
                            worker.setData(ExampleMod.WORKER_PROFILE, updated);
                        } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                            BlockPos standPos = MinerJobController.findWalkableStandPosition(level, storagePos, null);
                            pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                        }
                    } else if (job.status() != JobStatus.WAITING_FOR_STORAGE) {
                        farmProfile.transition(JobStatus.WAITING_FOR_STORAGE, job.progress(), "Storage destination chunk is currently unloaded")
                                .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                    }
                } else if (job.status() != JobStatus.WAITING_FOR_STORAGE) {
                    ToolSwapResult toolResult = tryEnsureFarmingToolEquipped(worker);
                    if (toolResult != ToolSwapResult.SUCCESS) {
                        if (toolResult == ToolSwapResult.INSUFFICIENT_STORAGE_SPACE) {
                            if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                farmProfile.transition(JobStatus.WAITING_FOR_TOOL, job.progress(),
                                        "Worker inventory is full; clear space to equip farming tool")
                                        .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                            }
                            if (owner != null && level.getGameTime() % 40 == 0) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_waiting_for_storage_space"), true);
                            }
                        } else {
                            if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                farmProfile.transition(JobStatus.WAITING_FOR_TOOL, job.progress(), "Farming tool missing or exhausted")
                                        .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                            }
                            if (owner != null && level.getGameTime() % 40 == 0) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.farmer_waiting_for_tool"), true);
                            }
                        }
                        return;
                    } else if (job.status() == JobStatus.WAITING_FOR_TOOL) {
                        farmProfile = farmProfile.transition(JobStatus.RUNNING, job.progress(), null).orElse(farmProfile);
                        worker.setData(ExampleMod.WORKER_PROFILE, farmProfile);
                        job = farmProfile.job().get();
                    }

                    if (job.status() == JobStatus.ASSIGNED) {
                        farmProfile = farmProfile.transition(JobStatus.RUNNING, 0, null).orElse(farmProfile);
                        worker.setData(ExampleMod.WORKER_PROFILE, farmProfile);
                        job = farmProfile.job().get();
                    }

                    // 1. Active target harvesting & navigation
                    if (farmerData.activeTarget().isPresent()) {
                        BlockPos targetCrop = farmerData.activeTarget().get();
                        if (!level.hasChunkAt(targetCrop)) {
                            return;
                        }
                        BlockState targetState = level.getBlockState(targetCrop);
                        boolean stillValid = CropRegistry.isSupportedCrop(targetState.getBlock()) &&
                                farmerData.isCropPermitted(targetState.getBlock()) &&
                                CropRegistry.isMature(level, targetCrop, targetState);

                        if (!stillValid) {
                            farmerData = farmerData.withoutActiveTarget();
                            final FarmerJobData fd = farmerData;
                            final WorkerProfile profBefore = farmProfile;
                            job.withFarmerData(fd).ifPresent(updatedJob -> {
                                WorkerProfile updatedProf = profBefore.withJob(updatedJob);
                                worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                            });
                            farmProfile = worker.getData(ExampleMod.WORKER_PROFILE);
                            job = farmProfile.job().get();
                        } else {
                            if (job.status() != JobStatus.RUNNING) {
                                farmProfile = farmProfile.transition(JobStatus.RUNNING, job.progress(), null).orElse(farmProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, farmProfile);
                                job = farmProfile.job().get();
                            }

                            double eyeDistSq = worker.getEyePosition().distanceToSqr(targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);
                            double distSq = worker.distanceToSqr(targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);
                            if (eyeDistSq <= 4.0 * 4.0 || distSq <= 3.5 * 3.5) {
                                if (!pathfinder.getNavigation().isDone()) {
                                    pathfinder.getNavigation().stop();
                                }
                                worker.getLookControl().setLookAt(targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);
                                worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                                ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
                                FarmerJobController.executeHarvestTransaction(
                                        farmProfile, worker, owner, worker.getMainHandItem(),
                                        targetCrop, targetState, blockId,
                                        () -> FarmerJobController.calculateCropDrops(level, targetCrop, targetState, worker, worker.getMainHandItem()),
                                        () -> level.destroyBlock(targetCrop, false, worker),
                                        () -> CropRegistry.replant(level, targetCrop, targetState),
                                        p -> worker.setData(ExampleMod.WORKER_PROFILE, p)
                                );
                            } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                                BlockPos standPos = MinerJobController.findWalkableStandPosition(level, targetCrop, null);
                                pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                            }
                            return;
                        }
                    }

                    // 2. Throttled retry when waiting for crops
                    if (job.status() == JobStatus.WAITING_FOR_CROPS) {
                        if (farmerData.retryTimer() > 0) {
                            farmerData = farmerData.withRetryTimer(farmerData.retryTimer() - 1);
                            final FarmerJobData fd = farmerData;
                            final WorkerProfile profBefore = farmProfile;
                            job.withFarmerData(fd).ifPresent(updatedJob -> {
                                WorkerProfile updatedProf = profBefore.withJob(updatedJob);
                                worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                            });
                            return;
                        }
                    }

                    BlockPos worksitePos = worksite.position();
                    CropRegistry.CropScanResult scanResult = CropRegistry.findMatureCrop(
                            level, worksitePos, farmerData.searchRadius(), farmerData, farmerData.scanCursor()
                    );

                    if (scanResult.nextCursor() != farmerData.scanCursor() || scanResult.emptyChecks() != farmerData.emptyChecks()) {
                        farmerData = farmerData.withScanCursor(scanResult.nextCursor(), scanResult.emptyChecks());
                        final FarmerJobData fd = farmerData;
                        final WorkerProfile profBefore = farmProfile;
                        job.withFarmerData(fd).ifPresent(updatedJob -> {
                            WorkerProfile updatedProf = profBefore.withJob(updatedJob);
                            worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                        });
                        farmProfile = worker.getData(ExampleMod.WORKER_PROFILE);
                        job = farmProfile.job().get();
                    }

                    if (scanResult.targetPos().isPresent()) {
                        BlockPos cropPos = scanResult.targetPos().get();
                        farmerData = farmerData.withActiveTarget(cropPos).withRetryTimer(0);
                        final FarmerJobData fd = farmerData;
                        final WorkerProfile profBefore = farmProfile;
                        job.withFarmerData(fd).ifPresent(updatedJob -> {
                            WorkerProfile updatedProf = profBefore.withJob(updatedJob);
                            worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                        });
                        farmProfile = worker.getData(ExampleMod.WORKER_PROFILE);
                        job = farmProfile.job().get();

                        if (job.status() == JobStatus.WAITING_FOR_CROPS) {
                            farmProfile = farmProfile.transition(JobStatus.RUNNING, job.progress(), null).orElse(farmProfile);
                            worker.setData(ExampleMod.WORKER_PROFILE, farmProfile);
                            job = farmProfile.job().get();
                        }

                        BlockState cropState = level.getBlockState(cropPos);
                        double eyeDistSq = worker.getEyePosition().distanceToSqr(cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5);
                        double distSq = worker.distanceToSqr(cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5);
                        if (eyeDistSq <= 4.0 * 4.0 || distSq <= 3.5 * 3.5) {
                            if (!pathfinder.getNavigation().isDone()) {
                                pathfinder.getNavigation().stop();
                            }
                            worker.getLookControl().setLookAt(cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5);
                            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                            ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(cropState.getBlock());
                            FarmerJobController.executeHarvestTransaction(
                                    farmProfile, worker, owner, worker.getMainHandItem(),
                                    cropPos, cropState, blockId,
                                    () -> FarmerJobController.calculateCropDrops(level, cropPos, cropState, worker, worker.getMainHandItem()),
                                    () -> level.destroyBlock(cropPos, false, worker),
                                    () -> CropRegistry.replant(level, cropPos, cropState),
                                    p -> worker.setData(ExampleMod.WORKER_PROFILE, p)
                            );
                        } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                            BlockPos standPos = MinerJobController.findWalkableStandPosition(level, cropPos, null);
                            pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                        }
                    } else if (farmerData.itemsPendingDelivery() > 0) {
                        farmProfile.transition(JobStatus.DELIVERING, job.progress(), null)
                                .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                    } else if (scanResult.cycleCompleted()) {
                        farmerData = farmerData.withScanCursor(0, 0).withRetryTimer(40);
                        final FarmerJobData fd = farmerData;
                        final WorkerProfile profBefore = farmProfile;
                        job.withFarmerData(fd).ifPresent(updatedJob -> {
                            WorkerProfile updatedProf = profBefore.withJob(updatedJob);
                            worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                        });
                        farmProfile = worker.getData(ExampleMod.WORKER_PROFILE);
                        job = farmProfile.job().get();

                        if (job.status() != JobStatus.WAITING_FOR_CROPS) {
                            farmProfile.transition(JobStatus.WAITING_FOR_CROPS, job.progress(), "No mature crops found in worksite")
                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                            if (owner != null) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                        "message.elstarticket.farmer_waiting_for_crops"), true);
                            }
                        }
                    }
                }
            }
            return;
        }

        // Priority 3.6: An authorized active MINE job has precedence
        if (hasActiveMineJob) {
            ensureStanding(worker);
            MinerJobController.reconcileInFlightMutation(worker);
            WorkerProfile minerProfile = worker.getData(ExampleMod.WORKER_PROFILE);
            if (minerProfile.job().isEmpty() || minerProfile.job().get().minerData().isEmpty()) {
                return;
            }
            WorkerJob job = minerProfile.job().get();
            MinerJobData minerData = job.minerData().get();
            WorkerTarget worksite = job.target().orElse(null);
            WorkerTarget storage = minerData.storage();

            if (worksite != null && permitted) {
                BlockPos worksitePos = worksite.position();
                int travelLimit = minerData.maxTravelDistance() > 0 ? minerData.maxTravelDistance() : Config.getMaxTravelDistance();
                int targetDepth = Math.max(1, (int) Math.ceil(minerData.requestedBlocks() / 2.0));
                int maxDepth = Math.min(travelLimit, targetDepth);

                // 1. Active Falling Block Entity detection (tunnel corridors)
                if (minerData.miningMode() == MinerJobData.MiningMode.TUNNEL &&
                        MinerJobController.hasActiveFallingEntities(level, worksitePos, minerData.direction(), maxDepth)) {
                    if (!pathfinder.getNavigation().isDone()) {
                        pathfinder.getNavigation().stop();
                    }
                    if (job.status() != JobStatus.WAITING_FOR_FALLING_BLOCK) {
                        minerProfile.transition(JobStatus.WAITING_FOR_FALLING_BLOCK, job.progress(), "Waiting for falling blocks to settle")
                                .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                    }
                    if (owner != null && level.getGameTime() % 40 == 0) {
                        owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_waiting_for_falling_block"), true);
                    }
                    return;
                } else if (job.status() == JobStatus.WAITING_FOR_FALLING_BLOCK) {
                    JobStatus resumeStatus = minerData.isMiningFinished() ? JobStatus.DELIVERING : JobStatus.RUNNING;
                    minerProfile = minerProfile.transition(resumeStatus, job.progress(), null).orElse(minerProfile);
                    worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                    job = minerProfile.job().get();
                }

                // 2. Tunnel Corridor & Return Route Obstruction Check (tunnel corridors)
                MinerJobController.CorridorObstruction obstruction = minerData.miningMode() == MinerJobData.MiningMode.TUNNEL ?
                        MinerJobController.findCorridorObstruction(
                                level, worksitePos, minerData.direction(), worker.blockPosition(), maxDepth, owner
                        ) : null;

                if (obstruction != null) {
                    if (!obstruction.mineable()) {
                        // Impassable hazard (bedrock, fluid, blacklisted)
                        if (!pathfinder.getNavigation().isDone()) {
                            pathfinder.getNavigation().stop();
                        }
                        String reason = obstruction.reason().orElse("Tunnel corridor obstructed by impassable hazard");
                        if (job.status() != JobStatus.WAITING_FOR_SAFE_TUNNEL) {
                            minerProfile.transition(JobStatus.WAITING_FOR_SAFE_TUNNEL, job.progress(), reason)
                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                        }
                        if (owner != null && level.getGameTime() % 40 == 0) {
                            owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker halted: " + reason), true);
                        }
                        return;
                    }

                    // Mineable obstruction! Verify tool first
                    ToolSwapResult toolResult = tryEnsureMiningToolEquipped(worker);
                    if (toolResult != ToolSwapResult.SUCCESS) {
                        if (toolResult == ToolSwapResult.INSUFFICIENT_STORAGE_SPACE) {
                            if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                minerProfile.transition(JobStatus.WAITING_FOR_TOOL, job.progress(),
                                        "Worker inventory is full; clear space to equip mining tool")
                                        .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                            }
                        } else {
                            if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                WorkerProfile updated = MinerJobController.handleToolExhaustion(minerProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, updated);
                                if (updated.job().isPresent() && updated.job().get().status() == JobStatus.WAITING_FOR_TOOL) {
                                    WorkerStorage storageData = worker.getExistingData(ExampleMod.WORKER_STORAGE).orElse(WorkerStorage.empty());
                                    EquipmentCapabilityPolicy.RescueEvaluation rescueEval =
                                            EquipmentCapabilityPolicy.evaluateWorkerRescueNeeds(worker, storageData, false, false, true);
                                    if (rescueEval.requiresRescue()) {
                                        RescueServerManager.triggerRescue(
                                                level,
                                                worker,
                                                minerProfile,
                                                rescueEval.trigger(),
                                                rescueEval.reason().orElse("Tool exhausted")
                                        );
                                    }
                                }
                            }
                        }
                        if (owner != null && level.getGameTime() % 40 == 0) {
                            owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_waiting_for_tool"), true);
                        }
                        executeEmergencyShelterStep(level, worker, minerProfile);
                        return;
                    }

                    if (job.status() != JobStatus.RECOVERING_TUNNEL_OBSTRUCTION) {
                        minerProfile = minerProfile.transition(JobStatus.RECOVERING_TUNNEL_OBSTRUCTION, job.progress(), null).orElse(minerProfile);
                        worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                        job = minerProfile.job().get();
                    }

                    ItemStack tool = worker.getMainHandItem();
                    BlockPos targetPos = obstruction.targetPos();
                    if (!level.hasChunkAt(targetPos)) {
                        return;
                    }
                    BlockPos standPos = obstruction.standPos();
                    double distSq = worker.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

                    if (distSq <= 3.5 * 3.5) {
                        if (!pathfinder.getNavigation().isDone()) {
                            pathfinder.getNavigation().stop();
                        }
                        worker.getLookControl().setLookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                        worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        MinerJobController.TransactionExecutionResult result =
                                MinerJobController.executeLiveMiningStep(minerProfile, worker, owner, tool, targetPos);
                        handleMiningTransactionResult(level, worker, owner, result, targetPos, level.getBlockState(targetPos), tool);
                        ensureMiningToolEquipped(worker);
                    } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                        pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                    }
                    return;
                } else if (job.status() == JobStatus.RECOVERING_TUNNEL_OBSTRUCTION || job.status() == JobStatus.WAITING_FOR_SAFE_TUNNEL) {
                    // Obstruction resolved!
                    JobStatus resumeStatus = minerData.isMiningFinished() ? JobStatus.DELIVERING : JobStatus.RUNNING;
                    minerProfile = minerProfile.transition(resumeStatus, job.progress(), null).orElse(minerProfile);
                    worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                    job = minerProfile.job().get();
                }

                // 3. Normal Delivery or Forward Mining
                boolean needsDelivery = minerData.isMiningFinished() ||
                        job.status() == JobStatus.DELIVERING ||
                        (job.status() == JobStatus.WAITING_FOR_STORAGE && level.getGameTime() % 40 == 0) ||
                        minerData.itemsPendingDelivery() >= 64 ||
                        minerData.pendingStacks().size() >= 10;

                if (needsDelivery) {
                    BlockPos storagePos = storage.position();
                    if (level.hasChunkAt(storagePos)) {
                        double distToStorageSq = worker.distanceToSqr(storagePos.getX() + 0.5, storagePos.getY() + 0.5, storagePos.getZ() + 0.5);
                        if (distToStorageSq <= 3.5 * 3.5) {
                            if (!pathfinder.getNavigation().isDone()) {
                                pathfinder.getNavigation().stop();
                            }
                            worker.getLookControl().setLookAt(storagePos.getX() + 0.5, storagePos.getY() + 0.5, storagePos.getZ() + 0.5);
                            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                            WorkerProfile updated = StorageDestinationAdapter.deliverToDesignatedStorage(
                                    minerProfile, level, storagePos, owner
                            );
                            worker.setData(ExampleMod.WORKER_PROFILE, updated);
                        } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                            BlockPos standPos = MinerJobController.findWalkableStandPosition(level, storagePos, null);
                            pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                        }
                    } else if (job.status() != JobStatus.WAITING_FOR_STORAGE) {
                        minerProfile.transition(JobStatus.WAITING_FOR_STORAGE, job.progress(), "Storage destination chunk is currently unloaded")
                                .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                    }
                } else if (job.status() != JobStatus.WAITING_FOR_STORAGE) {
                    if (minerData.retryDelay() > 0) {
                        MinerJobData updated = minerData.withRetryDelay(minerData.retryDelay() - 1);
                        final WorkerProfile prof = minerProfile;
                        job.withMinerData(updated).ifPresent(j -> worker.setData(ExampleMod.WORKER_PROFILE, prof.withJob(j)));
                        return;
                    }

                    if (minerData.miningMode() == MinerJobData.MiningMode.SPECIFIC_BLOCK && minerData.activeTarget().isPresent()) {
                        BlockPos activePos = minerData.activeTarget().get();
                        if (!level.hasChunkAt(activePos)) {
                            // Target chunk unloaded; wait safely without clearing activeTarget
                            return;
                        }
                        BlockState activeState = level.getBlockState(activePos);
                        if (!MinerJobController.isValidSpecificBlockTarget(level, minerData, activePos, activeState, owner)) {
                            // Target is no longer valid (e.g. broken or changed); clear activeTarget
                            minerData = minerData.withoutActiveTarget().withRetryCount(0).withRetryDelay(0);
                            final MinerJobData md = minerData;
                            final WorkerProfile profBeforeUpdate = minerProfile;
                            job.withMinerData(md).ifPresent(updatedJob -> {
                                WorkerProfile updatedProf = profBeforeUpdate.withJob(updatedJob);
                                worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                            });
                            minerProfile = worker.getData(ExampleMod.WORKER_PROFILE);
                            job = minerProfile.job().get();
                        } else {
                            // Active target is valid; check target-specific tool compatibility
                            ItemStack heldTool = worker.getMainHandItem();
                            boolean heldCanHarvest = JobSafetyPolicy.isMiningToolVerified(heldTool) &&
                                    JobSafetyPolicy.hasSufficientDurability(heldTool, 1);
                            if (heldCanHarvest && activeState.requiresCorrectToolForDrops()) {
                                boolean correct = heldTool.isCorrectToolForDrops(activeState);
                                if (!correct && MinerJobController.isExplicitUnbootstrappedTestEnvironment(worker)) {
                                    correct = MinerJobController.isToolAppropriateForBlockInTestEnv(heldTool, activeState);
                                }
                                if (!correct) {
                                    heldCanHarvest = false;
                                }
                            }

                            if (!heldCanHarvest) {
                                // Attempt target-aware tool swap from WorkerStorage
                                ToolSwapResult swapRes = tryFallbackMiningTool(worker, activeState);
                                if (swapRes != ToolSwapResult.SUCCESS) {
                                    if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                        WorkerProfile updated = MinerJobController.handleToolExhaustion(minerProfile);
                                        worker.setData(ExampleMod.WORKER_PROFILE, updated);
                                    }
                                    if (owner != null && level.getGameTime() % 40 == 0) {
                                        String blockName = activeState.getBlock().getName().getString();
                                        owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker waiting for tool: No tool in inventory can harvest " + blockName), true);
                                    }
                                    executeEmergencyShelterStep(level, worker, minerProfile);
                                    return;
                                }
                                heldTool = worker.getMainHandItem();
                            }

                            if (job.status() == JobStatus.WAITING_FOR_TOOL) {
                                minerProfile = minerProfile.transition(JobStatus.RUNNING, job.progress(), null).orElse(minerProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                                job = minerProfile.job().get();
                                if (owner != null) {
                                    owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_tool_resumed"), true);
                                }
                            }

                            if (job.status() == JobStatus.ASSIGNED) {
                                minerProfile = minerProfile.transition(JobStatus.RUNNING, 0, null).orElse(minerProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                                job = minerProfile.job().get();
                            }

                            // Active target is valid; move towards it or execute mining
                            double eyeDistSq = worker.getEyePosition().distanceToSqr(activePos.getX() + 0.5, activePos.getY() + 0.5, activePos.getZ() + 0.5);
                            double feetDistSq = worker.distanceToSqr(activePos.getX() + 0.5, activePos.getY() + 0.5, activePos.getZ() + 0.5);
                            if (eyeDistSq <= 4.0 * 4.0 || feetDistSq <= 3.5 * 3.5) {
                                if (!pathfinder.getNavigation().isDone()) {
                                    pathfinder.getNavigation().stop();
                                }
                                worker.getLookControl().setLookAt(activePos.getX() + 0.5, activePos.getY() + 0.5, activePos.getZ() + 0.5);
                                worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                                MinerJobController.TransactionExecutionResult result =
                                        MinerJobController.executeLiveMiningStep(minerProfile, worker, owner, heldTool, activePos);
                                handleMiningTransactionResult(level, worker, owner, result, activePos, activeState, heldTool);
                                ensureMiningToolEquipped(worker);
                            } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                                BlockPos standPos = MinerJobController.findWalkableStandPosition(level, activePos, null);
                                if (standPos != null) {
                                    pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                                }
                            }
                            return;
                        }
                    }

                    ToolSwapResult toolResult = tryEnsureMiningToolEquipped(worker);
                    if (toolResult != ToolSwapResult.SUCCESS) {
                        if (toolResult == ToolSwapResult.INSUFFICIENT_STORAGE_SPACE) {
                            if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                minerProfile.transition(JobStatus.WAITING_FOR_TOOL, job.progress(),
                                        "Worker inventory is full; clear space to equip mining tool")
                                        .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                            }
                            if (owner != null && level.getGameTime() % 40 == 0) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_waiting_for_storage_space"), true);
                            }
                        } else {
                            if (job.status() != JobStatus.WAITING_FOR_TOOL) {
                                WorkerProfile updated = MinerJobController.handleToolExhaustion(minerProfile);
                                worker.setData(ExampleMod.WORKER_PROFILE, updated);
                                if (updated.job().isPresent() && updated.job().get().status() == JobStatus.WAITING_FOR_TOOL) {
                                    WorkerStorage storageData = worker.getExistingData(ExampleMod.WORKER_STORAGE).orElse(WorkerStorage.empty());
                                    EquipmentCapabilityPolicy.RescueEvaluation rescueEval =
                                            EquipmentCapabilityPolicy.evaluateWorkerRescueNeeds(worker, storageData, false, false, true);
                                    if (rescueEval.requiresRescue()) {
                                        RescueServerManager.triggerRescue(
                                                level,
                                                worker,
                                                minerProfile,
                                                rescueEval.trigger(),
                                                rescueEval.reason().orElse("Tool exhausted")
                                        );
                                    }
                                }
                            }
                            if (owner != null && level.getGameTime() % 40 == 0) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_waiting_for_tool"), true);
                            }
                        }
                        executeEmergencyShelterStep(level, worker, minerProfile);
                        return;
                    } else if (job.status() == JobStatus.WAITING_FOR_TOOL) {
                        minerProfile = minerProfile.transition(JobStatus.RUNNING, job.progress(), null).orElse(minerProfile);
                        worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                        job = minerProfile.job().get();
                        if (owner != null) {
                            owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.elstarticket.miner_tool_resumed"), true);
                        }
                    }

                    ItemStack tool = worker.getMainHandItem();

                    if (job.status() == JobStatus.ASSIGNED) {
                        minerProfile = minerProfile.transition(JobStatus.RUNNING, 0, null).orElse(minerProfile);
                        worker.setData(ExampleMod.WORKER_PROFILE, minerProfile);
                        job = minerProfile.job().get();
                    }

                    MinerJobController.MiningTargetResult targetResult = MinerJobController.findNextMiningTarget(
                            level, minerData, worksitePos, owner, worker
                    );

                    boolean cursorChanged = targetResult.nextCursor() != minerData.scanCursor();
                    boolean shouldSetActiveTarget = minerData.miningMode() == MinerJobData.MiningMode.SPECIFIC_BLOCK && targetResult.targetPos().isPresent();

                    if (cursorChanged || shouldSetActiveTarget) {
                        if (cursorChanged) {
                            minerData = minerData.withScanCursor(targetResult.nextCursor());
                        }
                        if (shouldSetActiveTarget) {
                            minerData = minerData.withActiveTarget(targetResult.targetPos().get());
                        }
                        final MinerJobData md = minerData;
                        final WorkerProfile profBeforeUpdate = minerProfile;
                        job.withMinerData(md).ifPresent(updatedJob -> {
                            WorkerProfile updatedProf = profBeforeUpdate.withJob(updatedJob);
                            worker.setData(ExampleMod.WORKER_PROFILE, updatedProf);
                        });
                        minerProfile = worker.getData(ExampleMod.WORKER_PROFILE);
                        job = minerProfile.job().get();
                    }

                    if (targetResult.targetReached()) {
                        if (minerData.itemsPendingDelivery() > 0) {
                            minerProfile.transition(JobStatus.DELIVERING, job.progress(), null)
                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                        } else {
                            if (owner != null) {
                                String msg = minerData.miningMode() == MinerJobData.MiningMode.STAIR
                                        ? "Worker completed: Staircase target Y reached"
                                        : "Worker completed: Mining mode objective reached";
                                owner.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
                            }
                            minerProfile.transition(JobStatus.COMPLETED, job.progress(), null)
                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                        }
                        return;
                    }

                    if (targetResult.obstructed()) {
                        String reason = targetResult.obstructionReason().orElse("Mining path obstructed");
                        if (owner != null) {
                            owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker paused: " + reason), true);
                        }
                        minerProfile.transition(JobStatus.PAUSED, job.progress(), null)
                                .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                        return;
                    }

                    if (targetResult.targetPos().isPresent()) {
                        BlockPos targetPos = targetResult.targetPos().get();
                        if (!level.hasChunkAt(targetPos)) {
                            return;
                        }
                        double eyeDistSq = worker.getEyePosition().distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                        double feetDistSq = worker.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                        if (eyeDistSq <= 4.0 * 4.0 || feetDistSq <= 3.5 * 3.5) {
                            if (!pathfinder.getNavigation().isDone()) {
                                pathfinder.getNavigation().stop();
                            }
                            worker.getLookControl().setLookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                            MinerJobController.TransactionExecutionResult result =
                                    MinerJobController.executeLiveMiningStep(minerProfile, worker, owner, tool, targetPos);
                            handleMiningTransactionResult(level, worker, owner, result, targetPos, level.getBlockState(targetPos), tool);
                            ensureMiningToolEquipped(worker);
                        } else if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                            final Direction miningDirection = minerData.direction();
                            BlockPos standPos = targetResult.standPos().orElseGet(() ->
                                    MinerJobController.findWalkableStandPosition(level, targetPos, miningDirection));
                            pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                        }
                    } else {
                        if (minerData.itemsPendingDelivery() > 0) {
                            minerProfile.transition(JobStatus.DELIVERING, job.progress(), null)
                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                        } else if (!minerData.isMiningFinished()) {
                            // In SPECIFIC_BLOCK mode, do not pause while scan is still in progress across ticks
                            if (minerData.miningMode() == MinerJobData.MiningMode.SPECIFIC_BLOCK && !targetResult.scanCompleted()) {
                                return;
                            }
                            String reason = switch (minerData.miningMode()) {
                                case STAIR -> "Staircase excavation completed up to maximum boundary";
                                case SPECIFIC_BLOCK -> "No matching blocks found in search radius";
                                default -> "Tunnel excavation completed up to maximum boundary";
                            };
                            if (owner != null) {
                                owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Worker paused: " + reason), true);
                            }
                            minerProfile.transition(JobStatus.PAUSED, job.progress(), null)
                                    .ifPresent(updated -> worker.setData(ExampleMod.WORKER_PROFILE, updated));
                        }
                    }
                }
            }
            return;
        }

        // Priority 3.7: An extensible JobQueue has precedence
        if (profile.queue() != null && !profile.queue().isEmpty() && !profile.queue().isAllComplete() && permitted) {
            ensureStanding(worker);
            processJobQueue(worker, profile, level, owner, pathfinder);
            return;
        }

        // Priority 4: Follow owner when behavior == FOLLOW and no active job overrides it
        if (profile.behavior() == WorkerBehavior.FOLLOW && available && permitted) {
            TouhouLittleMaidCompat.restoreMaidFollow(worker);
            double distSq = worker.distanceToSqr(owner);
            follow(profile, distSq, !pathfinder.getNavigation().isDone(), level.getGameTime(),
                    () -> pathfinder.getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 1.0));
        }
    }

    public static void processJobQueue(Mob worker, WorkerProfile profile, ServerLevel level, ServerPlayer owner, PathfinderMob pathfinder) {
        JobQueue queue = profile.queue();
        if (queue == null || queue.isEmpty() || queue.isAllComplete()) {
            return;
        }
        Job currentJob = queue.currentJob().orElse(null);
        if (currentJob == null) {
            return;
        }

        if (currentJob.isComplete()) {
            JobQueue advanced = queue.advance();
            worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(advanced));
            return;
        }

        if (currentJob.state() == ExecutionState.PENDING) {
            currentJob = currentJob.withState(ExecutionState.RUNNING, null);
            queue = queue.updateCurrentJob(currentJob);
            worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue));
        }

        // Evaluate job-level dependencies
        for (JobDependency dep : currentJob.dependencies()) {
            if (!dep.isSatisfied(level, worker, profile, null)) {
                if (currentJob.state() != ExecutionState.WAITING) {
                    Job waitingJob = currentJob.withState(ExecutionState.WAITING, dep.getWaitDescription());
                    worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(waitingJob)));
                }
                return;
            }
        }
        if (currentJob.state() == ExecutionState.WAITING) {
            currentJob = currentJob.withState(ExecutionState.RUNNING, null);
            queue = queue.updateCurrentJob(currentJob);
            worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue));
        }

        JobStep step = currentJob.currentStep().orElse(null);
        if (step == null || step.isComplete()) {
            Job nextJob = currentJob.advanceStep();
            worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(nextJob)));
            return;
        }

        // Evaluate step dependencies
        BlockPos targetPos = step.targetPos().orElse(null);
        for (JobDependency dep : step.dependencies()) {
            if (!dep.isSatisfied(level, worker, profile, targetPos)) {
                if (step.state() != ExecutionState.WAITING) {
                    JobStep waitingStep = step.withState(ExecutionState.WAITING, dep.getWaitDescription());
                    Job waitingJob = currentJob.updateCurrentStep(waitingStep).withState(ExecutionState.WAITING, dep.getWaitDescription());
                    worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(waitingJob)));
                }
                return;
            }
        }

        if (step.state() == ExecutionState.WAITING) {
            step = step.withState(ExecutionState.RUNNING, null);
            currentJob = currentJob.updateCurrentStep(step).withState(ExecutionState.RUNNING, null);
            queue = queue.updateCurrentJob(currentJob);
            worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue));
        }

        // Handle navigation to target position
        if (targetPos != null && pathfinder != null) {
            double distSq = worker.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
            if (distSq > 3.5 * 3.5) {
                if (pathfinder.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
                    BlockPos standPos = MinerJobController.findWalkableStandPosition(level, targetPos, null);
                    pathfinder.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0);
                }
                return;
            } else {
                if (!pathfinder.getNavigation().isDone()) {
                    pathfinder.getNavigation().stop();
                }
                worker.getLookControl().setLookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
            }
        }

        // Execute step via JobAdapter
        BlockState state = targetPos != null ? level.getBlockState(targetPos) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var adapterOpt = JobAdapter.Registry.findAdapter(level, targetPos, state, step);
        if (adapterOpt.isPresent()) {
            JobAdapter.StepResult result = adapterOpt.get().execute(level, targetPos, worker, owner, step, profile);
            if (result.status() == JobAdapter.StepResult.Status.SUCCESS) {
                int nextCount = step.completedRepetitions() + 1;
                JobStep updatedStep = step.withProgress(nextCount);
                Job updatedJob = (updatedStep.isComplete()) ? currentJob.advanceStep() : currentJob.updateCurrentStep(updatedStep);
                worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(updatedJob)));
            } else if (result.status() == JobAdapter.StepResult.Status.IN_PROGRESS) {
                JobStep updatedStep = step.withProgress(result.progress());
                worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(currentJob.updateCurrentStep(updatedStep))));
            } else if (result.status() == JobAdapter.StepResult.Status.WAITING) {
                JobStep waitingStep = step.withState(ExecutionState.WAITING, result.message());
                Job waitingJob = currentJob.updateCurrentStep(waitingStep).withState(ExecutionState.WAITING, result.message());
                worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(waitingJob)));
            } else if (result.status() == JobAdapter.StepResult.Status.FAILED) {
                JobStep failedStep = step.withState(ExecutionState.FAILED, result.message());
                Job failedJob = currentJob.updateCurrentStep(failedStep).withState(ExecutionState.FAILED, result.message());
                worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(failedJob)));
            }
        } else if ("MOVE_TO_POSITION".equalsIgnoreCase(step.stepType())) {
            // Reached target position
            JobStep completedStep = step.withProgress(1);
            Job updatedJob = completedStep.isComplete() ? currentJob.advanceStep() : currentJob.updateCurrentStep(completedStep);
            worker.setData(ExampleMod.WORKER_PROFILE, profile.withQueue(queue.updateCurrentJob(updatedJob)));
        }
    }

    /**
     * Executes server-side emergency shelter construction when an active rescue is pending.
     * Respects rate-limits, bounds placement per tick, avoids worker collision space,
     * safely halts on material depletion, and resumes from persistent SHELTER_STATE.
     */
    public static void executeEmergencyShelterStep(ServerLevel level, Mob worker, WorkerProfile profile) {
        if (level == null || worker == null || !worker.isAlive() || worker.isRemoved()) {
            return;
        }

        // 1. Check whether an active rescue exists for this worker
        Optional<RescueServerManager.ActiveRescue> activeRescueOpt = RescueServerManager.getActiveRescue(level, worker.getUUID());
        if (activeRescueOpt.isEmpty()) {
            return;
        }
        RescueServerManager.ActiveRescue rescue = activeRescueOpt.get();

        // 2. Halt pathfinder navigation to remain inside defensive shelter
        if (worker instanceof PathfinderMob pathfinder && !pathfinder.getNavigation().isDone()) {
            pathfinder.getNavigation().stop();
        }

        // 3. Retrieve or initialize persistent SHELTER_STATE
        WorkerStorage storage = worker.getExistingData(ExampleMod.WORKER_STORAGE).orElse(WorkerStorage.empty());
        List<ItemStack> reservedStacks = (profile != null) ? profile.getReservedItemStacks() : Collections.emptyList();

        if (profile != null && profile.hasUncertainReservation()) {
            // Fail safely: conservatively avoid extracting from an active job with uncertain reservations
            return;
        }

        EquipmentCapabilityPolicy.ShelterConstructionState shelterState =
                worker.getExistingData(ExampleMod.SHELTER_STATE).orElse(null);

        if (shelterState == null || shelterState.isEmpty() ||
                (shelterState.getEventId() != null && !shelterState.getEventId().equals(rescue.eventId()))) {
            int availableBlocks = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reservedStacks);
            EquipmentCapabilityPolicy.ShelterMode mode;
            if (availableBlocks >= EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER.getStandardBlockCount()) {
                mode = EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER;
            } else if (availableBlocks >= EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER.getStandardBlockCount()) {
                mode = EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER;
            } else {
                mode = EquipmentCapabilityPolicy.ShelterMode.DIRECTIONAL_BARRIER;
            }

            Direction facing = worker.getDirection();
            EquipmentCapabilityPolicy.EmergencyShelterPlan plan =
                    EquipmentCapabilityPolicy.generateShelterPlan(worker.blockPosition(), mode, facing, 3);
            shelterState = new EquipmentCapabilityPolicy.ShelterConstructionState(worker.getUUID(), rescue.eventId(), plan, facing);
            worker.setData(ExampleMod.SHELTER_STATE, shelterState);
        }

        if (shelterState.isComplete()) {
            return;
        }

        if (shelterState.isHalted()) {
            if ("WAITING_FOR_CHUNK".equals(shelterState.getHaltReason().orElse(""))) {
                // If waiting for chunk, check if the current target position's chunk is now naturally loaded
                if (shelterState.getPlan() != null && shelterState.getNextIndex() < shelterState.getPlan().placements().size()) {
                    BlockPos targetPos = shelterState.getPlan().placements().get(shelterState.getNextIndex()).pos();
                    if (level.hasChunkAt(targetPos)) {
                        shelterState.resumeWithMaterials();
                        worker.setData(ExampleMod.SHELTER_STATE, shelterState);
                    } else {
                        return; // Still waiting for chunk to naturally load without force loading
                    }
                }
            } else {
                int availableBlocks = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, reservedStacks);
                if (availableBlocks > 0) {
                    shelterState.resumeWithMaterials();
                    worker.setData(ExampleMod.SHELTER_STATE, shelterState);
                } else {
                    return;
                }
            }
        }

        // 4. Rate-limit placement: at most 1 block placement every 10 ticks (0.5s)
        if (level.getGameTime() % 10 != 0) {
            return;
        }

        // 5. Execute 1 step of placement
        EquipmentCapabilityPolicy.ShelterConstructionState.StepResult result =
                shelterState.stepPlacement(level, storage, reservedStacks, worker);

        if (result.placedBlock()) {
            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }

        // 6. Update persistent attachments
        worker.setData(ExampleMod.WORKER_STORAGE, result.updatedStorage());
        worker.setData(ExampleMod.SHELTER_STATE, shelterState);
    }
}
