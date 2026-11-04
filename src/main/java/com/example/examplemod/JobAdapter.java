package com.example.examplemod;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Extensible adapter interface for block/machine operations executed by a worker JobStep.
 * Core systems interact with this interface without hardcoding modded machine names.
 */
public interface JobAdapter {

    record StepResult(
            Status status,
            int progress,
            String message
    ) {
        public enum Status {
            SUCCESS,
            IN_PROGRESS,
            WAITING,
            FAILED
        }

        public static StepResult success() {
            return new StepResult(Status.SUCCESS, 1, "");
        }

        public static StepResult inProgress(int completed) {
            return new StepResult(Status.IN_PROGRESS, completed, "");
        }

        public static StepResult waiting(String reason) {
            return new StepResult(Status.WAITING, 0, reason != null ? reason : "Waiting for machine/resource");
        }

        public static StepResult failed(String reason) {
            return new StepResult(Status.FAILED, 0, reason != null ? reason : "Operation failed");
        }
    }

    ResourceLocation id();

    boolean canHandle(ServerLevel level, BlockPos pos, BlockState state, JobStep step);

    StepResult execute(ServerLevel level, BlockPos pos, Mob worker, ServerPlayer owner, JobStep step, WorkerProfile profile);

    /**
     * Built-in registry for extensible job adapters.
     */
    final class Registry {
        private static final Map<ResourceLocation, JobAdapter> ADAPTERS = new ConcurrentHashMap<>();

        static {
            register(new ContainerItemDepositAdapter());
            register(new ContainerItemExtractAdapter());
            register(new GenericMachineInteractAdapter());
        }

        private Registry() {
        }

        public static void register(JobAdapter adapter) {
            Objects.requireNonNull(adapter);
            ADAPTERS.put(adapter.id(), adapter);
        }

        public static Optional<JobAdapter> findAdapter(ServerLevel level, BlockPos pos, BlockState state, JobStep step) {
            for (JobAdapter adapter : ADAPTERS.values()) {
                if (adapter.canHandle(level, pos, state, step)) {
                    return Optional.of(adapter);
                }
            }
            return Optional.empty();
        }

        public static Optional<JobAdapter> get(ResourceLocation id) {
            if (id == null) return Optional.empty();
            return Optional.ofNullable(ADAPTERS.get(id));
        }
    }

    /**
     * Adapter for depositing items into vanilla Containers or NeoForge IItemHandler machine inventories.
     */
    final class ContainerItemDepositAdapter implements JobAdapter {
        public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "container_deposit");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean canHandle(ServerLevel level, BlockPos pos, BlockState state, JobStep step) {
            return "DEPOSIT_ITEMS".equalsIgnoreCase(step.stepType());
        }

        @Override
        public StepResult execute(ServerLevel level, BlockPos pos, Mob worker, ServerPlayer owner, JobStep step, WorkerProfile profile) {
            if (level == null || pos == null || worker == null) return StepResult.failed("Invalid context");
            if (!worker.hasData(ExampleMod.WORKER_STORAGE)) return StepResult.success();

            Direction face = step.direction().orElse(Direction.UP);
            IItemHandler itemHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face);
            WorkerStorage ws = worker.getData(ExampleMod.WORKER_STORAGE);
            java.util.List<ItemStack> remaining = new java.util.ArrayList<>();
            boolean depositedAny = false;
            boolean anyRefused = false;

            for (ItemStack stack : ws.items()) {
                if (stack.isEmpty()) {
                    remaining.add(ItemStack.EMPTY);
                    continue;
                }
                if (itemHandler != null) {
                    ItemStack leftover = ItemHandlerHelper.insertItemStacked(itemHandler, stack, false);
                    remaining.add(leftover);
                    if (leftover.getCount() < stack.getCount()) depositedAny = true;
                    if (!leftover.isEmpty()) anyRefused = true;
                } else {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof Container container) {
                        ItemStack leftover = insertIntoContainer(container, stack);
                        remaining.add(leftover);
                        if (leftover.getCount() < stack.getCount()) depositedAny = true;
                        if (!leftover.isEmpty()) anyRefused = true;
                    } else {
                        return StepResult.waiting("Destination is not a valid container or machine");
                    }
                }
            }

            worker.setData(ExampleMod.WORKER_STORAGE, new WorkerStorage(remaining));
            if (anyRefused) {
                return StepResult.waiting("Storage destination is full");
            }
            return StepResult.success();
        }

        private ItemStack insertIntoContainer(Container container, ItemStack stack) {
            ItemStack toInsert = stack.copy();
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!container.canPlaceItem(i, toInsert)) continue;
                ItemStack existing = container.getItem(i);
                if (existing.isEmpty()) {
                    container.setItem(i, toInsert);
                    container.setChanged();
                    return ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(existing, toInsert)) {
                    int space = existing.getMaxStackSize() - existing.getCount();
                    int transfer = Math.min(space, toInsert.getCount());
                    if (transfer > 0) {
                        existing.grow(transfer);
                        toInsert.shrink(transfer);
                        container.setChanged();
                        if (toInsert.isEmpty()) return ItemStack.EMPTY;
                    }
                }
            }
            return toInsert;
        }
    }

    /**
     * Adapter for extracting required ingredients from containers or machine output slots.
     */
    final class ContainerItemExtractAdapter implements JobAdapter {
        public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "container_extract");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean canHandle(ServerLevel level, BlockPos pos, BlockState state, JobStep step) {
            return "EXTRACT_ITEMS".equalsIgnoreCase(step.stepType());
        }

        @Override
        public StepResult execute(ServerLevel level, BlockPos pos, Mob worker, ServerPlayer owner, JobStep step, WorkerProfile profile) {
            if (level == null || pos == null || worker == null) return StepResult.failed("Invalid context");
            ItemStack req = step.itemRequirement();
            if (req.isEmpty()) return StepResult.success();

            Direction face = step.direction().orElse(Direction.DOWN);
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face);
            int needed = step.repetitions() - step.completedRepetitions();
            if (needed <= 0) return StepResult.success();

            int extracted = 0;
            if (handler != null) {
                for (int slot = 0; slot < handler.getSlots() && needed > 0; slot++) {
                    ItemStack inSlot = handler.getStackInSlot(slot);
                    if (ItemStack.isSameItemSameComponents(inSlot, req)) {
                        ItemStack taken = handler.extractItem(slot, needed, false);
                        extracted += taken.getCount();
                        needed -= taken.getCount();
                        addWorkerItem(worker, taken);
                    }
                }
            } else {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof Container container) {
                    for (int slot = 0; slot < container.getContainerSize() && needed > 0; slot++) {
                        ItemStack inSlot = container.getItem(slot);
                        if (ItemStack.isSameItemSameComponents(inSlot, req)) {
                            ItemStack taken = inSlot.split(needed);
                            extracted += taken.getCount();
                            needed -= taken.getCount();
                            container.setChanged();
                            addWorkerItem(worker, taken);
                        }
                    }
                }
            }

            int totalProgress = step.completedRepetitions() + extracted;
            if (totalProgress >= step.repetitions()) {
                return StepResult.success();
            } else if (extracted > 0) {
                return StepResult.inProgress(totalProgress);
            } else {
                return StepResult.waiting("Waiting for item in container: " + req.getHoverName().getString());
            }
        }

        private void addWorkerItem(Mob worker, ItemStack stack) {
            if (worker.hasData(ExampleMod.WORKER_STORAGE)) {
                WorkerStorage ws = worker.getData(ExampleMod.WORKER_STORAGE);
                java.util.List<ItemStack> items = new java.util.ArrayList<>(ws.items());
                for (int i = 0; i < items.size(); i++) {
                    ItemStack existing = items.get(i);
                    if (existing.isEmpty()) {
                        items.set(i, stack);
                        worker.setData(ExampleMod.WORKER_STORAGE, new WorkerStorage(items));
                        return;
                    } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                        int space = existing.getMaxStackSize() - existing.getCount();
                        int transfer = Math.min(space, stack.getCount());
                        existing.grow(transfer);
                        stack.shrink(transfer);
                        if (stack.isEmpty()) {
                            worker.setData(ExampleMod.WORKER_STORAGE, new WorkerStorage(items));
                            return;
                        }
                    }
                }
                worker.setData(ExampleMod.WORKER_STORAGE, new WorkerStorage(items));
            }
        }
    }

    /**
     * Generic adapter for inserting items into machine input ports or triggering processing.
     */
    final class GenericMachineInteractAdapter implements JobAdapter {
        public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "machine_interact");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean canHandle(ServerLevel level, BlockPos pos, BlockState state, JobStep step) {
            return "INTERACT_MACHINE".equalsIgnoreCase(step.stepType());
        }

        @Override
        public StepResult execute(ServerLevel level, BlockPos pos, Mob worker, ServerPlayer owner, JobStep step, WorkerProfile profile) {
            if (level == null || pos == null || worker == null) return StepResult.failed("Invalid context");
            ItemStack req = step.itemRequirement();
            Direction face = step.direction().orElse(Direction.UP);
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face);

            if (handler != null && !req.isEmpty()) {
                // Insert requirement into machine
                ItemStack leftover = ItemHandlerHelper.insertItemStacked(handler, req.copy(), false);
                if (leftover.getCount() < req.getCount()) {
                    return StepResult.success();
                } else {
                    return StepResult.waiting("Machine input full or rejecting item");
                }
            }
            return StepResult.success();
        }
    }
}
