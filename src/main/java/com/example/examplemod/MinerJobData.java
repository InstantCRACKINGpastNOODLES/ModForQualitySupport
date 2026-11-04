package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Persistent data model for the Miner job.
 * Uses 64-bit signed integers (long) and overflow-safe arithmetic to track very large jobs
 * (e.g. 100,000,000 blocks) without numeric truncation or overflow.
 *
 * Supports three distinct mining modes:
 * - TUNNEL: Horizontal 1x2 tunnel excavation.
 * - STAIR: Ascending or descending traversable staircase excavation.
 * - SPECIFIC_BLOCK: Search and mine configured block types inside a 3D spherical region.
 */
public record MinerJobData(
        long requestedBlocks,
        long blocksMined,
        long itemsPendingDelivery,
        long itemsDelivered,
        List<ItemStack> pendingStacks,
        WorkerTarget storage,
        Optional<InFlightMutation> inFlightMutation,
        Direction direction,
        ObjectiveMode objectiveMode,
        MiningMode miningMode,
        int maxTravelDistance,
        boolean stairAscending,
        int targetY,
        int searchRadius,
        List<ResourceLocation> targetBlockIds,
        List<ResourceLocation> targetBlockTags,
        int scanCursor,
        Optional<BlockPos> activeTarget,
        int retryCount,
        int retryDelay
) implements JobItemReservation {

    public enum ObjectiveMode {
        BLOCK_COUNT,
        ITEM_COUNT
    }

    public enum MiningMode {
        TUNNEL,
        STAIR,
        SPECIFIC_BLOCK
    }

    public record InFlightMutation(
            BlockPos pos,
            ResourceLocation blockId,
            List<ItemStack> expectedDrops
    ) {
        public static final Codec<InFlightMutation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(InFlightMutation::pos),
                ResourceLocation.CODEC.fieldOf("block_id").forGetter(InFlightMutation::blockId),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("expected_drops", List.of()).forGetter(InFlightMutation::expectedDrops)
        ).apply(instance, (pos, blockId, drops) -> new InFlightMutation(pos, blockId, (drops == null) ? List.of() :
                drops.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList())));

        public InFlightMutation {
            Objects.requireNonNull(pos, "Position must not be null");
            Objects.requireNonNull(blockId, "Block ID must not be null");
            expectedDrops = (expectedDrops == null) ? List.of() :
                    List.copyOf(expectedDrops.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList());
        }
    }

    public record SpecificBlockConfig(
            int searchRadius,
            List<ResourceLocation> targetBlockIds,
            List<ResourceLocation> targetBlockTags,
            int scanCursor,
            Optional<BlockPos> activeTarget,
            int retryCount,
            int retryDelay
    ) {
        public static final MapCodec<SpecificBlockConfig> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.optionalFieldOf("search_radius", 8).forGetter(SpecificBlockConfig::searchRadius),
                ResourceLocation.CODEC.listOf().optionalFieldOf("target_block_ids", List.of()).forGetter(SpecificBlockConfig::targetBlockIds),
                ResourceLocation.CODEC.listOf().optionalFieldOf("target_block_tags", List.of()).forGetter(SpecificBlockConfig::targetBlockTags),
                Codec.INT.optionalFieldOf("scan_cursor", 0).forGetter(SpecificBlockConfig::scanCursor),
                BlockPos.CODEC.optionalFieldOf("active_target").forGetter(SpecificBlockConfig::activeTarget),
                Codec.INT.optionalFieldOf("retry_count", 0).forGetter(SpecificBlockConfig::retryCount),
                Codec.INT.optionalFieldOf("retry_delay", 0).forGetter(SpecificBlockConfig::retryDelay)
        ).apply(instance, (radius, ids, tags, cursor, activeTargetOpt, retries, delay) -> new SpecificBlockConfig(
                radius, ids, tags, cursor,
                activeTargetOpt != null ? activeTargetOpt : Optional.empty(),
                retries != null ? retries : 0,
                delay != null ? delay : 0
        )));

        public SpecificBlockConfig(int searchRadius, List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags, int scanCursor, Optional<BlockPos> activeTarget, int retryCount) {
            this(searchRadius, targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, 0);
        }

        public SpecificBlockConfig(int searchRadius, List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags, int scanCursor) {
            this(searchRadius, targetBlockIds, targetBlockTags, scanCursor, Optional.empty(), 0, 0);
        }
    }

    public SpecificBlockConfig specificBlockConfig() {
        return new SpecificBlockConfig(searchRadius, targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, retryDelay);
    }

    public static final Codec<MinerJobData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("requested_blocks").forGetter(MinerJobData::requestedBlocks),
            Codec.LONG.fieldOf("blocks_mined").forGetter(MinerJobData::blocksMined),
            Codec.LONG.optionalFieldOf("items_pending_delivery", 0L).forGetter(MinerJobData::itemsPendingDelivery),
            Codec.LONG.fieldOf("items_delivered").forGetter(MinerJobData::itemsDelivered),
            ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("pending_stacks", List.of()).forGetter(MinerJobData::pendingStacks),
            WorkerTarget.CODEC.fieldOf("storage").forGetter(MinerJobData::storage),
            InFlightMutation.CODEC.optionalFieldOf("in_flight_mutation").forGetter(MinerJobData::inFlightMutation),
            Direction.CODEC.optionalFieldOf("direction", Direction.NORTH).forGetter(MinerJobData::direction),
            Codec.STRING.optionalFieldOf("objective_mode", "BLOCK_COUNT").xmap(
                    s -> { try { return ObjectiveMode.valueOf(s); } catch (Exception e) { return ObjectiveMode.BLOCK_COUNT; } },
                    ObjectiveMode::name
            ).forGetter(MinerJobData::objectiveMode),
            Codec.STRING.optionalFieldOf("mining_mode", "TUNNEL").xmap(
                    s -> { try { return MiningMode.valueOf(s); } catch (Exception e) { return MiningMode.TUNNEL; } },
                    MiningMode::name
            ).forGetter(MinerJobData::miningMode),
            Codec.INT.optionalFieldOf("max_travel_distance", 64).forGetter(MinerJobData::maxTravelDistance),
            Codec.BOOL.optionalFieldOf("stair_ascending", false).forGetter(MinerJobData::stairAscending),
            Codec.INT.optionalFieldOf("target_y", 64).forGetter(MinerJobData::targetY),
            SpecificBlockConfig.MAP_CODEC.forGetter(MinerJobData::specificBlockConfig)
    ).apply(instance, (req, mined, pendingCount, delivered, stacks, storage, inFlight, dir, mode,
                       miningMode, maxTravelDist, stairAsc, targetY, spec) -> {
        List<ItemStack> nonNullStacks = (stacks == null) ? List.of() :
                stacks.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList();
        long actualPending = !nonNullStacks.isEmpty() ?
                nonNullStacks.stream().mapToLong(ItemStack::getCount).sum() : pendingCount;
        return new MinerJobData(
                req, mined, actualPending, delivered, nonNullStacks, storage, inFlight,
                dir != null ? dir : Direction.NORTH,
                mode != null ? mode : ObjectiveMode.BLOCK_COUNT,
                miningMode != null ? miningMode : MiningMode.TUNNEL,
                maxTravelDist > 0 ? maxTravelDist : 64,
                stairAsc,
                targetY,
                spec.searchRadius() > 0 ? spec.searchRadius() : 8,
                spec.targetBlockIds() != null ? List.copyOf(spec.targetBlockIds()) : List.of(),
                spec.targetBlockTags() != null ? List.copyOf(spec.targetBlockTags()) : List.of(),
                Math.max(0, spec.scanCursor()),
                spec.activeTarget() != null ? spec.activeTarget() : Optional.empty(),
                Math.max(0, spec.retryCount()),
                Math.max(0, spec.retryDelay())
        );
    }));

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered,
                        List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightMutation> inFlightMutation,
                        Direction direction, ObjectiveMode objectiveMode, MiningMode miningMode,
                        int maxTravelDistance, boolean stairAscending, int targetY, int searchRadius,
                        List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags, int scanCursor,
                        Optional<BlockPos> activeTarget, int retryCount) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, inFlightMutation,
                direction, objectiveMode, miningMode, maxTravelDistance, stairAscending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, 0);
    }

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered,
                        List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightMutation> inFlightMutation,
                        Direction direction, ObjectiveMode objectiveMode, MiningMode miningMode,
                        int maxTravelDistance, boolean stairAscending, int targetY, int searchRadius,
                        List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags, int scanCursor) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, inFlightMutation,
                direction, objectiveMode, miningMode, maxTravelDistance, stairAscending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, scanCursor, Optional.empty(), 0, 0);
    }

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered,
                        List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightMutation> inFlightMutation,
                        Direction direction, ObjectiveMode objectiveMode, MiningMode miningMode,
                        int maxTravelDistance, boolean stairAscending, int targetY, int searchRadius,
                        List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, inFlightMutation,
                direction, objectiveMode, miningMode, maxTravelDistance, stairAscending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, 0, Optional.empty(), 0, 0);
    }

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered, WorkerTarget storage) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, List.of(), storage, Optional.empty(),
                Direction.NORTH, ObjectiveMode.BLOCK_COUNT, MiningMode.TUNNEL, 64, false, 64, 8, List.of(), List.of());
    }

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered, List<ItemStack> pendingStacks, WorkerTarget storage) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, Optional.empty(),
                Direction.NORTH, ObjectiveMode.BLOCK_COUNT, MiningMode.TUNNEL, 64, false, 64, 8, List.of(), List.of());
    }

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered, List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightMutation> inFlightMutation) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, inFlightMutation,
                Direction.NORTH, ObjectiveMode.BLOCK_COUNT, MiningMode.TUNNEL, 64, false, 64, 8, List.of(), List.of());
    }

    public MinerJobData(long requestedBlocks, long blocksMined, long itemsPendingDelivery, long itemsDelivered,
                        List<ItemStack> pendingStacks, WorkerTarget storage, Optional<InFlightMutation> inFlightMutation,
                        Direction direction, ObjectiveMode objectiveMode) {
        this(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage, inFlightMutation,
                direction, objectiveMode, MiningMode.TUNNEL, 64, false, 64, 8, List.of(), List.of());
    }

    public MinerJobData {
        Objects.requireNonNull(storage, "Storage target must not be null");
        if (requestedBlocks <= 0) {
            throw new IllegalArgumentException("Requested blocks must be positive: " + requestedBlocks);
        }
        objectiveMode = (objectiveMode == null) ? ObjectiveMode.BLOCK_COUNT : objectiveMode;
        miningMode = (miningMode == null) ? MiningMode.TUNNEL : miningMode;
        if (blocksMined < 0 || (objectiveMode == ObjectiveMode.BLOCK_COUNT && blocksMined > requestedBlocks)) {
            throw new IllegalArgumentException("Blocks mined cannot be negative or exceed requested blocks: " + blocksMined);
        }
        if (itemsPendingDelivery < 0) {
            throw new IllegalArgumentException("Items pending delivery cannot be negative: " + itemsPendingDelivery);
        }
        if (itemsDelivered < 0) {
            throw new IllegalArgumentException("Items delivered cannot be negative: " + itemsDelivered);
        }
        pendingStacks = (pendingStacks == null) ? List.of() :
                List.copyOf(pendingStacks.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList());
        inFlightMutation = (inFlightMutation == null) ? Optional.empty() : inFlightMutation;
        direction = (direction == null || !direction.getAxis().isHorizontal()) ? Direction.NORTH : direction;
        maxTravelDistance = maxTravelDistance > 0 ? maxTravelDistance : 64;
        searchRadius = searchRadius > 0 ? searchRadius : 8;
        targetBlockIds = (targetBlockIds == null) ? List.of() : List.copyOf(targetBlockIds);
        targetBlockTags = (targetBlockTags == null) ? List.of() : List.copyOf(targetBlockTags);
        scanCursor = Math.max(0, scanCursor);
        activeTarget = (activeTarget == null) ? Optional.empty() : activeTarget;
        retryCount = Math.max(0, retryCount);
        retryDelay = Math.max(0, retryDelay);
    }

    public static MinerJobData initial(long requestedBlocks, WorkerTarget storage) {
        return initialTunnel(requestedBlocks, storage, Direction.NORTH, 64);
    }

    public static MinerJobData initial(long requestedBlocks, WorkerTarget storage, Direction direction, ObjectiveMode mode) {
        return new MinerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                direction != null ? direction : Direction.NORTH, mode != null ? mode : ObjectiveMode.BLOCK_COUNT,
                MiningMode.TUNNEL, 64, false, 64, 8, List.of(), List.of());
    }

    public static MinerJobData initialTunnel(long requestedBlocks, WorkerTarget storage, Direction direction, int maxTravelDistance) {
        return new MinerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                direction != null ? direction : Direction.NORTH, ObjectiveMode.BLOCK_COUNT,
                MiningMode.TUNNEL, maxTravelDistance > 0 ? maxTravelDistance : 64, false, 64, 8, List.of(), List.of());
    }

    public static MinerJobData initialStair(long requestedBlocks, WorkerTarget storage, Direction direction, boolean ascending, int targetY, int maxTravelDistance) {
        return new MinerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                direction != null ? direction : Direction.NORTH, ObjectiveMode.BLOCK_COUNT,
                MiningMode.STAIR, maxTravelDistance > 0 ? maxTravelDistance : 64, ascending, targetY, 8, List.of(), List.of());
    }

    public static MinerJobData initialSpecificBlock(long requestedBlocks, WorkerTarget storage, int searchRadius,
                                                    List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags) {
        return new MinerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                Direction.NORTH, ObjectiveMode.BLOCK_COUNT,
                MiningMode.SPECIFIC_BLOCK, 64, false, 64, searchRadius > 0 ? searchRadius : 8,
                targetBlockIds != null ? List.copyOf(targetBlockIds) : List.of(),
                targetBlockTags != null ? List.copyOf(targetBlockTags) : List.of());
    }

    public static MinerJobData initialSpecificBlock(long requestedBlocks, WorkerTarget storage, Direction direction, int searchRadius,
                                                    List<String> targetBlockIds, List<String> targetBlockTags) {
        List<ResourceLocation> ids = new ArrayList<>();
        if (targetBlockIds != null) {
            for (String s : targetBlockIds) {
                try {
                    ids.add(ResourceLocation.parse(s));
                } catch (Exception ignored) {}
            }
        }
        List<ResourceLocation> tags = new ArrayList<>();
        if (targetBlockTags != null) {
            for (String s : targetBlockTags) {
                try {
                    String clean = s.startsWith("#") ? s.substring(1) : s;
                    tags.add(ResourceLocation.parse(clean));
                } catch (Exception ignored) {}
            }
        }
        return new MinerJobData(requestedBlocks, 0L, 0L, 0L, List.of(), storage, Optional.empty(),
                direction != null ? direction : Direction.NORTH, ObjectiveMode.BLOCK_COUNT,
                MiningMode.SPECIFIC_BLOCK, 64, false, 64, searchRadius > 0 ? searchRadius : 8,
                ids, tags);
    }

    public boolean isMiningFinished() {
        if (objectiveMode == ObjectiveMode.ITEM_COUNT) {
            return (itemsDelivered + itemsPendingDelivery) >= requestedBlocks;
        }
        return blocksMined >= requestedBlocks;
    }

    public boolean isDeliveryComplete() {
        return itemsPendingDelivery == 0L && pendingStacks.isEmpty();
    }

    public boolean isFullyCompleted() {
        return isMiningFinished() && isDeliveryComplete();
    }

    public long blocksRemaining() {
        if (objectiveMode == ObjectiveMode.ITEM_COUNT) {
            return Math.max(0L, requestedBlocks - (itemsDelivered + itemsPendingDelivery));
        }
        return Math.max(0L, requestedBlocks - blocksMined);
    }

    public MinerJobData withDirection(Direction newDirection) {
        Direction validDir = (newDirection != null && newDirection.getAxis().isHorizontal()) ? newDirection : Direction.NORTH;
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, validDir, objectiveMode, miningMode, maxTravelDistance, stairAscending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withObjectiveMode(ObjectiveMode newMode) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, newMode != null ? newMode : ObjectiveMode.BLOCK_COUNT,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withMiningMode(MiningMode newMode) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, newMode != null ? newMode : MiningMode.TUNNEL,
                maxTravelDistance, stairAscending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withStairConfig(boolean ascending, int targetY, int maxTravelDist) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, MiningMode.STAIR,
                maxTravelDist > 0 ? maxTravelDist : maxTravelDistance, ascending, targetY, searchRadius,
                targetBlockIds, targetBlockTags, scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withSpecificBlockConfig(int searchRadius, List<ResourceLocation> targetBlockIds, List<ResourceLocation> targetBlockTags) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, MiningMode.SPECIFIC_BLOCK,
                maxTravelDistance, stairAscending, targetY, searchRadius > 0 ? searchRadius : 8,
                targetBlockIds != null ? List.copyOf(targetBlockIds) : List.of(),
                targetBlockTags != null ? List.copyOf(targetBlockTags) : List.of(), 0, Optional.empty(), 0, 0);
    }

    public MinerJobData withScanCursor(int cursor) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, miningMode,
                maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags, Math.max(0, cursor),
                activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withActiveTarget(BlockPos pos) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, miningMode,
                maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags, scanCursor,
                Optional.ofNullable(pos), 0, 0);
    }

    public MinerJobData withoutActiveTarget() {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, miningMode,
                maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags, scanCursor,
                Optional.empty(), 0, 0);
    }

    public MinerJobData withRetryCount(int count) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, miningMode,
                maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags, scanCursor,
                activeTarget, Math.max(0, count), retryDelay);
    }

    public MinerJobData withRetryDelay(int delay) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                inFlightMutation, direction, objectiveMode, miningMode,
                maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags, scanCursor,
                activeTarget, retryCount, Math.max(0, delay));
    }

    public MinerJobData withInFlightMutation(BlockPos pos, ResourceLocation blockId, List<ItemStack> drops) {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                Optional.of(new InFlightMutation(pos, blockId, drops)), direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withoutInFlightMutation() {
        return new MinerJobData(requestedBlocks, blocksMined, itemsPendingDelivery, itemsDelivered, pendingStacks, storage,
                Optional.empty(), direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData commitInFlightMutation() {
        if (inFlightMutation.isEmpty()) {
            return this;
        }
        InFlightMutation inFlight = inFlightMutation.get();
        return withBlockMined(1L, inFlight.expectedDrops()).withoutInFlightMutation().withoutActiveTarget();
    }

    public MinerJobData commitRecoveryInFlightMutation() {
        if (inFlightMutation.isEmpty()) {
            return this;
        }
        InFlightMutation inFlight = inFlightMutation.get();
        return withRecoveryDrops(inFlight.expectedDrops()).withoutInFlightMutation().withoutActiveTarget();
    }

    public MinerJobData withRecoveryDrops(List<ItemStack> drops) {
        List<ItemStack> combined = new ArrayList<>(this.pendingStacks);
        if (drops != null) {
            for (ItemStack drop : drops) {
                if (drop != null && !drop.isEmpty()) {
                    combined.add(drop.copy());
                }
            }
        }
        long updatedPending = combined.stream().mapToLong(ItemStack::getCount).sum();
        return new MinerJobData(requestedBlocks, blocksMined, updatedPending, itemsDelivered, combined, storage, inFlightMutation, direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withBlockMined(long count, List<ItemStack> drops) {
        if (count < 0) {
            throw new IllegalArgumentException("Block count must be non-negative");
        }
        long updatedBlocks = (objectiveMode == ObjectiveMode.BLOCK_COUNT) ?
                Math.min(requestedBlocks, Math.addExact(blocksMined, count)) :
                Math.addExact(blocksMined, count);
        List<ItemStack> combined = new ArrayList<>(this.pendingStacks);
        if (drops != null) {
            for (ItemStack drop : drops) {
                if (drop != null && !drop.isEmpty()) {
                    combined.add(drop.copy());
                }
            }
        }
        long updatedPending = combined.stream().mapToLong(ItemStack::getCount).sum();
        return new MinerJobData(requestedBlocks, updatedBlocks, updatedPending, itemsDelivered, combined, storage, inFlightMutation, direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withBlockMined(long count, long newDrops) {
        if (count < 0 || newDrops < 0) {
            throw new IllegalArgumentException("Block count and drops must be non-negative");
        }
        long updatedBlocks = (objectiveMode == ObjectiveMode.BLOCK_COUNT) ?
                Math.min(requestedBlocks, Math.addExact(blocksMined, count)) :
                Math.addExact(blocksMined, count);
        long updatedPending = Math.addExact(itemsPendingDelivery, newDrops);
        return new MinerJobData(requestedBlocks, updatedBlocks, updatedPending, itemsDelivered, this.pendingStacks, storage, inFlightMutation, direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withItemsDelivered(long deliveredAmount, List<ItemStack> remainingPendingStacks) {
        if (deliveredAmount < 0) {
            throw new IllegalArgumentException("Delivered amount must be non-negative");
        }
        List<ItemStack> nonNullRemaining = (remainingPendingStacks == null) ? List.of() :
                remainingPendingStacks.stream().filter(s -> s != null && !s.isEmpty()).map(ItemStack::copy).toList();
        long updatedPending = nonNullRemaining.stream().mapToLong(ItemStack::getCount).sum();
        long updatedDelivered = Math.addExact(itemsDelivered, deliveredAmount);
        return new MinerJobData(requestedBlocks, blocksMined, updatedPending, updatedDelivered, nonNullRemaining, storage, inFlightMutation, direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
    }

    public MinerJobData withItemsDelivered(long deliveredAmount) {
        if (deliveredAmount < 0) {
            throw new IllegalArgumentException("Delivered amount must be non-negative");
        }
        long actualDelivered = Math.min(itemsPendingDelivery, deliveredAmount);
        long remainingPending = itemsPendingDelivery - actualDelivered;
        long updatedDelivered = Math.addExact(itemsDelivered, actualDelivered);
        List<ItemStack> updatedStacks = remainingPending == 0 ? List.of() : this.pendingStacks;
        return new MinerJobData(requestedBlocks, blocksMined, remainingPending, updatedDelivered, updatedStacks, storage, inFlightMutation, direction, objectiveMode,
                miningMode, maxTravelDistance, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags,
                scanCursor, activeTarget, retryCount, retryDelay);
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
        if (inFlightMutation != null && inFlightMutation.isPresent()) {
            List<ItemStack> drops = inFlightMutation.get().expectedDrops();
            if (drops != null) {
                for (ItemStack s : drops) {
                    if (s != null && !s.isEmpty()) {
                        reserved.add(s.copy());
                    }
                }
            }
        }
        return java.util.Collections.unmodifiableList(reserved);
    }
}
