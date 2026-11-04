package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Persistent data model for the Farmer job.
 * Uses 64-bit signed integers (long) and overflow-safe arithmetic to track large farming orders.
 *
 * Invariants:
 * - requestedBlocks: The exact total number of crop blocks to harvest.
 * - cropsHarvested: Actual number of successfully broken mature crop blocks (replanting does not increment this).
 * - itemsPendingDelivery: Harvested produce awaiting delivery to designated storage.
 * - itemsDelivered: Output verified and deposited into storage.
 * - pendingStacks: Preserved ItemStacks with full component fidelity across restarts.
 * - storage: Designated storage container.
 * - inFlightHarvest: Staged mutation intent to ensure crash-safe idempotent recovery.
 * - selectionMode: ALL_SUPPORTED (default) or SELECTED_ONLY.
 * - permittedCrops: Explicit allowlist of crop block IDs permitted when in SELECTED_ONLY mode.
 */
public record FarmerJobData(
        long requestedBlocks,
        long cropsHarvested,
        long itemsPendingDelivery,
        long itemsDelivered,
        List<ItemStack> pendingStacks,
        WorkerTarget storage,
        Optional<InFlightHarvest> inFlightHarvest,
        CropSelectionMode selectionMode,
        List<ResourceLocation> permittedCrops,
        int searchRadius,
        int scanCursor,
        int emptyChecks,
        Optional<BlockPos> activeTarget,
        int retryTimer
) implements JobItemReservation {
    public record InFlightHarvest(
            BlockPos pos,
            ResourceLocation blockId,
            List<ItemStack> expectedDrops,
            Optional<ResourceLocation> replantItemConsumed
    ) {
        public static final Codec<InFlightHarvest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(InFlightHarvest::pos),
                ResourceLocation.CODEC.fieldOf("block_id").forGetter(InFlightHarvest::blockId),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("expected_drops", List.of()).forGetter(InFlightHarvest::expectedDrops),
                ResourceLocation.CODEC.optionalFieldOf("replant_item_consumed").forGetter(InFlightHarvest::replantItemConsumed)
        ).apply(instance, (pos, blockId, drops, replantItem) -> new InFlightHarvest(pos, blockId,
                (drops == null) ? List.of() : drops.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList(),
                replantItem)));

        public InFlightHarvest {
            Objects.requireNonNull(pos, "Position must not be null");
            Objects.requireNonNull(blockId, "Block ID must not be null");
            expectedDrops = (expectedDrops == null) ? List.of() :
                    List.copyOf(expectedDrops.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList());
            replantItemConsumed = (replantItemConsumed == null) ? Optional.empty() : replantItemConsumed;
        }
    }

    public static final Codec<FarmerJobData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("requested_blocks").forGetter(FarmerJobData::requestedBlocks),
            Codec.LONG.fieldOf("crops_harvested").forGetter(FarmerJobData::cropsHarvested),
            Codec.LONG.optionalFieldOf("items_pending_delivery", 0L).forGetter(FarmerJobData::itemsPendingDelivery),
            Codec.LONG.fieldOf("items_delivered").forGetter(FarmerJobData::itemsDelivered),
            ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("pending_stacks", List.of()).forGetter(FarmerJobData::pendingStacks),
            WorkerTarget.CODEC.fieldOf("storage").forGetter(FarmerJobData::storage),
            InFlightHarvest.CODEC.optionalFieldOf("in_flight_harvest").forGetter(FarmerJobData::inFlightHarvest),
            CropSelectionMode.CODEC.optionalFieldOf("selection_mode").forGetter(d -> Optional.of(d.selectionMode())),
            ResourceLocation.CODEC.listOf().optionalFieldOf("permitted_crops", List.of()).forGetter(FarmerJobData::permittedCrops),
            Codec.INT.optionalFieldOf("search_radius", 16).forGetter(FarmerJobData::searchRadius),
            Codec.INT.optionalFieldOf("scan_cursor", 0).forGetter(FarmerJobData::scanCursor),
            Codec.INT.optionalFieldOf("empty_checks", 0).forGetter(FarmerJobData::emptyChecks),
            BlockPos.CODEC.optionalFieldOf("active_target").forGetter(FarmerJobData::activeTarget),
            Codec.INT.optionalFieldOf("retry_timer", 0).forGetter(FarmerJobData::retryTimer)
    ).apply(instance, (req, harvested, pendingCount, delivered, stacks, storage, inFlight, modeOpt, permitted, radius, cursor, emptyChecks, activeTargetOpt, retryTimer) -> {
        List<ItemStack> nonNullStacks = (stacks == null) ? List.of() :
                stacks.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList();
        long actualPending = !nonNullStacks.isEmpty() ?
                nonNullStacks.stream().mapToLong(ItemStack::getCount).sum() : pendingCount;
        List<ResourceLocation> safePermitted = (permitted != null) ? List.copyOf(permitted) : List.of();

        CropSelectionMode effectiveMode = modeOpt.orElse(CropSelectionMode.SELECTED_ONLY);

        return new FarmerJobData(req, harvested, actualPending, delivered, nonNullStacks, storage, inFlight,
                effectiveMode, safePermitted,
                radius > 0 ? radius : 16,
                Math.max(0, cursor),
                Math.max(0, emptyChecks),
                activeTargetOpt != null ? activeTargetOpt : Optional.empty(),
                Math.max(0, retryTimer));
    }));

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered,
                         List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightHarvest> inFlightHarvest,
                         CropSelectionMode selectionMode, List<ResourceLocation> permittedCrops,
                         int searchRadius, int scanCursor, int emptyChecks) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightHarvest, selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks, Optional.empty(), 0);
    }

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered,
                         List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightHarvest> inFlightHarvest,
                         List<ResourceLocation> permittedCrops, int searchRadius, int scanCursor, int emptyChecks) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightHarvest, (permittedCrops != null && !permittedCrops.isEmpty()) ? CropSelectionMode.SELECTED_ONLY : CropSelectionMode.ALL_SUPPORTED,
                permittedCrops, searchRadius, scanCursor, emptyChecks, Optional.empty(), 0);
    }

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered,
                         List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightHarvest> inFlightHarvest,
                         List<ResourceLocation> permittedCrops, int searchRadius, int scanCursor) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightHarvest, permittedCrops, searchRadius, scanCursor, 0);
    }

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered,
                         List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightHarvest> inFlightHarvest,
                         List<ResourceLocation> permittedCrops) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightHarvest, permittedCrops, 16, 0, 0);
    }

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered, WorkerTarget storage) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, List.of(), storage, Optional.empty(), List.of(), 16, 0, 0);
    }

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered, List<ItemStack> pendingStacks, WorkerTarget storage) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, Optional.empty(), List.of(), 16, 0, 0);
    }

    public FarmerJobData(long requestedBlocks, long cropsHarvested, long itemsPendingDelivery, long itemsDelivered, List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightHarvest> inFlightHarvest) {
        this(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, inFlightHarvest, List.of(), 16, 0, 0);
    }

    public FarmerJobData {
        Objects.requireNonNull(storage, "Storage target must not be null");
        selectionMode = (selectionMode != null) ? selectionMode : CropSelectionMode.ALL_SUPPORTED;
        if (requestedBlocks <= 0) {
            throw new IllegalArgumentException("Requested blocks must be positive: " + requestedBlocks);
        }
        if (cropsHarvested < 0 || cropsHarvested > requestedBlocks) {
            throw new IllegalArgumentException("Crops harvested cannot be negative or exceed requested blocks: " + cropsHarvested);
        }
        if (itemsPendingDelivery < 0) {
            throw new IllegalArgumentException("Items pending delivery cannot be negative: " + itemsPendingDelivery);
        }
        if (itemsDelivered < 0) {
            throw new IllegalArgumentException("Items delivered cannot be negative: " + itemsDelivered);
        }
        pendingStacks = (pendingStacks == null) ? List.of() :
                List.copyOf(pendingStacks.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList());
        inFlightHarvest = (inFlightHarvest == null) ? Optional.empty() : inFlightHarvest;
        permittedCrops = (permittedCrops == null) ? List.of() : List.copyOf(permittedCrops);
        searchRadius = searchRadius > 0 ? searchRadius : 16;
        scanCursor = Math.max(0, scanCursor);
        emptyChecks = Math.max(0, emptyChecks);
        activeTarget = (activeTarget == null) ? Optional.empty() : activeTarget;
        retryTimer = Math.max(0, retryTimer);
    }

    public static FarmerJobData initial(long requestedBlocks, WorkerTarget storage) {
        return new FarmerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                CropSelectionMode.ALL_SUPPORTED, List.of(), 16, 0, 0);
    }

    public static FarmerJobData initial(long requestedBlocks, WorkerTarget storage, List<ResourceLocation> permittedCrops) {
        CropSelectionMode mode = (permittedCrops != null)
                ? CropSelectionMode.SELECTED_ONLY : CropSelectionMode.ALL_SUPPORTED;
        return new FarmerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                mode, permittedCrops != null ? List.copyOf(permittedCrops) : List.of(), 16, 0, 0);
    }

    public static FarmerJobData initial(long requestedBlocks, WorkerTarget storage, CropSelectionMode mode, List<ResourceLocation> permittedCrops) {
        return new FarmerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                mode != null ? mode : CropSelectionMode.ALL_SUPPORTED,
                permittedCrops != null ? List.copyOf(permittedCrops) : List.of(), 16, 0, 0);
    }

    public static FarmerJobData initialWithBlocks(long requestedBlocks, WorkerTarget storage, List<Block> permittedCropBlocks) {
        List<ResourceLocation> ids = permittedCropBlocks != null ?
                permittedCropBlocks.stream().map(BuiltInRegistries.BLOCK::getKey).toList() : List.of();
        return initial(requestedBlocks, storage, ids);
    }

    public boolean isHarvestFinished() {
        return cropsHarvested >= requestedBlocks;
    }

    public boolean isDeliveryComplete() {
        return itemsPendingDelivery == 0L && pendingStacks.isEmpty();
    }

    public boolean isFullyCompleted() {
        return isHarvestFinished() && isDeliveryComplete();
    }

    public long cropsRemaining() {
        return Math.max(0L, requestedBlocks - cropsHarvested);
    }

    public boolean isCropPermitted(Block block) {
        if (block == null) {
            return false;
        }
        if (selectionMode == CropSelectionMode.ALL_SUPPORTED) {
            return CropRegistry.isSupportedCrop(block);
        }
        if (permittedCrops == null || permittedCrops.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return permittedCrops.contains(id);
    }

    public FarmerJobData withActiveTarget(BlockPos pos) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks,
                Optional.ofNullable(pos), retryTimer);
    }

    public FarmerJobData withoutActiveTarget() {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks,
                Optional.empty(), retryTimer);
    }

    public FarmerJobData withRetryTimer(int timer) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks,
                activeTarget, Math.max(0, timer));
    }

    public FarmerJobData withSelectionMode(CropSelectionMode mode) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, mode != null ? mode : CropSelectionMode.ALL_SUPPORTED, permittedCrops, searchRadius, 0, 0, Optional.empty(), 0);
    }

    public FarmerJobData withScanCursor(int cursor) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, selectionMode, permittedCrops, searchRadius, Math.max(0, cursor), emptyChecks, activeTarget, retryTimer);
    }

    public FarmerJobData withScanCursor(int cursor, int emptyChecks) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, selectionMode, permittedCrops, searchRadius, Math.max(0, cursor), Math.max(0, emptyChecks), activeTarget, retryTimer);
    }

    public FarmerJobData withSearchRadius(int radius) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks,
                storage, inFlightHarvest, selectionMode, permittedCrops, radius > 0 ? radius : 16, 0, 0, Optional.empty(), 0);
    }

    public FarmerJobData withPermittedCrops(List<ResourceLocation> crops) {
        CropSelectionMode mode = (crops != null && !crops.isEmpty()) ? CropSelectionMode.SELECTED_ONLY : selectionMode;
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightHarvest, mode, crops != null ? List.copyOf(crops) : List.of(), searchRadius, 0, 0, Optional.empty(), 0);
    }

    public FarmerJobData withInFlightHarvest(BlockPos pos, ResourceLocation blockId, List<ItemStack> drops, Optional<ResourceLocation> replantItem) {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                Optional.of(new InFlightHarvest(pos, blockId, drops, replantItem)), selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks, activeTarget, retryTimer);
    }

    public FarmerJobData withoutInFlightHarvest() {
        return new FarmerJobData(requestedBlocks, cropsHarvested, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                Optional.empty(), selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks, activeTarget, retryTimer);
    }

    public FarmerJobData commitInFlightHarvest() {
        if (inFlightHarvest.isEmpty()) {
            return this;
        }
        InFlightHarvest inFlight = inFlightHarvest.get();
        return withCropHarvested(1L, inFlight.expectedDrops()).withoutInFlightHarvest();
    }

    /**
     * Credits successfully harvested crop blocks and queues resulting output for delivery.
     * Guaranteed never to exceed requestedBlocks.
     */
    public FarmerJobData withCropHarvested(long count, List<ItemStack> drops) {
        if (count < 0) {
            throw new IllegalArgumentException("Harvest count must be non-negative");
        }
        long updatedHarvested = Math.min(requestedBlocks, Math.addExact(cropsHarvested, count));
        List<ItemStack> combined = new ArrayList<>(this.pendingStacks);
        if (drops != null) {
            for (ItemStack drop : drops) {
                if (drop != null && !drop.isEmpty()) {
                    combined.add(drop.copy());
                }
            }
        }
        long updatedPending = combined.stream().mapToLong(ItemStack::getCount).sum();
        return new FarmerJobData(requestedBlocks, updatedHarvested, updatedPending, itemsDelivered, combined, storage, inFlightHarvest,
                selectionMode, permittedCrops, searchRadius, scanCursor, 0, Optional.empty(), 0);
    }

    public FarmerJobData withCropHarvested(long count, long newDrops) {
        if (count < 0 || newDrops < 0) {
            throw new IllegalArgumentException("Count and drops must be non-negative");
        }
        long updatedHarvested = Math.min(requestedBlocks, Math.addExact(cropsHarvested, count));
        long updatedPending = Math.addExact(itemsPendingDelivery, newDrops);
        return new FarmerJobData(requestedBlocks, updatedHarvested, updatedPending, itemsDelivered, this.pendingStacks, storage, inFlightHarvest,
                selectionMode, permittedCrops, searchRadius, scanCursor, 0, Optional.empty(), 0);
    }

    public FarmerJobData withItemsDelivered(long deliveredAmount, List<ItemStack> remainingPendingStacks) {
        if (deliveredAmount < 0) {
            throw new IllegalArgumentException("Delivered amount must be non-negative");
        }
        List<ItemStack> nonNullRemaining = (remainingPendingStacks == null) ? List.of() :
                remainingPendingStacks.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList();
        long updatedPending = nonNullRemaining.stream().mapToLong(ItemStack::getCount).sum();
        long updatedDelivered = Math.addExact(itemsDelivered, deliveredAmount);
        return new FarmerJobData(requestedBlocks, cropsHarvested, updatedPending, updatedDelivered, nonNullRemaining, storage, inFlightHarvest,
                selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks, activeTarget, retryTimer);
    }

    public FarmerJobData withItemsDelivered(long deliveredAmount) {
        if (deliveredAmount < 0) {
            throw new IllegalArgumentException("Delivered amount must be non-negative");
        }
        long actualDelivered = Math.min(itemsPendingDelivery, deliveredAmount);
        long remainingPending = itemsPendingDelivery - actualDelivered;
        long updatedDelivered = Math.addExact(itemsDelivered, actualDelivered);
        List<ItemStack> updatedStacks = remainingPending == 0 ? List.of() : this.pendingStacks;
        return new FarmerJobData(requestedBlocks, cropsHarvested, remainingPending, updatedDelivered, updatedStacks, storage, inFlightHarvest,
                selectionMode, permittedCrops, searchRadius, scanCursor, emptyChecks, activeTarget, retryTimer);
    }

    @Override
    public List<ItemStack> getReservedItemStacks() {
        List<ItemStack> reserved = new ArrayList<>();
        if (pendingStacks != null) {
            for (ItemStack s : pendingStacks) {
                if (s != null && !s.isEmpty()) {
                    reserved.add(s.copy());
                }
            }
        }
        if (inFlightHarvest != null && inFlightHarvest.isPresent()) {
            List<ItemStack> drops = inFlightHarvest.get().expectedDrops();
            if (drops != null) {
                for (ItemStack drop : drops) {
                    if (drop != null && !drop.isEmpty()) {
                        reserved.add(drop.copy());
                    }
                }
            }
        }
        return reserved;
    }
}
