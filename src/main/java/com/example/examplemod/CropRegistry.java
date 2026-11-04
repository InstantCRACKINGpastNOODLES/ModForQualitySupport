package com.example.examplemod;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Authoritative registry of crop adapters for autonomous farming.
 * Provides default-safe handling: unknown or unsupported blocks are safely ignored.
 * Built-in vanilla support covers Wheat, Carrots, Potatoes, Beetroot, Cocoa, Pumpkin, and Melon.
 */
public final class CropRegistry {

    private static final Map<ResourceLocation, CropAdapter> ADAPTERS = new ConcurrentHashMap<>();

    public static final ResourceLocation FARMLAND_CROPS_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "farmland_crops");
    public static final ResourceLocation COCOA_CROP_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "cocoa_crops");
    public static final ResourceLocation PUMPKIN_CROP_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "pumpkin_crops");
    public static final ResourceLocation MELON_CROP_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "melon_crops");

    public static final List<ResourceLocation> DEFAULT_FARMLAND_CROP_IDS = List.of(
            BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
            BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS),
            BuiltInRegistries.BLOCK.getKey(Blocks.POTATOES),
            BuiltInRegistries.BLOCK.getKey(Blocks.BEETROOTS)
    );

    public static final List<ResourceLocation> DEFAULT_SUPPORTED_CROP_IDS = List.of(
            BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT),
            BuiltInRegistries.BLOCK.getKey(Blocks.CARROTS),
            BuiltInRegistries.BLOCK.getKey(Blocks.POTATOES),
            BuiltInRegistries.BLOCK.getKey(Blocks.BEETROOTS),
            BuiltInRegistries.BLOCK.getKey(Blocks.PUMPKIN),
            BuiltInRegistries.BLOCK.getKey(Blocks.MELON),
            BuiltInRegistries.BLOCK.getKey(Blocks.COCOA)
    );

    static {
        registerDefaults();
    }

    private CropRegistry() {
    }

    public static void registerDefaults() {
        ADAPTERS.clear();
        register(new FarmlandCropAdapter());
        register(new CocoaCropAdapter());
        register(new PumpkinCropAdapter());
        register(new MelonCropAdapter());
    }

    public static void register(CropAdapter adapter) {
        Objects.requireNonNull(adapter, "Crop adapter must not be null");
        ADAPTERS.put(adapter.id(), adapter);
    }

    public static Optional<CropAdapter> getAdapter(ResourceLocation id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(ADAPTERS.get(id));
    }

    public static Optional<CropAdapter> getAdapter(Block block) {
        if (block == null) return Optional.empty();
        for (CropAdapter adapter : ADAPTERS.values()) {
            if (adapter.matches(block)) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
    }

    public static boolean isSupportedCrop(Block block) {
        return getAdapter(block).isPresent();
    }

    public static boolean isMature(LevelReader level, BlockPos pos, BlockState state) {
        if (state == null) return false;
        return getAdapter(state.getBlock())
                .map(adapter -> adapter.isMature(level, pos, state))
                .orElse(false);
    }

    public static boolean isReplanted(LevelReader level, BlockPos pos, BlockState state) {
        if (state == null) return false;
        return getAdapter(state.getBlock())
                .map(adapter -> adapter.isReplantedState(level, pos, state))
                .orElse(false);
    }

    public static Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state) {
        if (state == null) return Optional.empty();
        return getAdapter(state.getBlock())
                .flatMap(adapter -> adapter.getPlantingItem(level, pos, state));
    }

    public static boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState) {
        if (previousState == null) return false;
        return getAdapter(previousState.getBlock())
                .map(adapter -> adapter.canReplant(level, pos, previousState))
                .orElse(false);
    }

    public static Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState) {
        if (previousState == null) return Optional.empty();
        return getAdapter(previousState.getBlock())
                .flatMap(adapter -> adapter.getReplantState(level, pos, previousState));
    }

    public static boolean replant(LevelAccessor level, BlockPos pos, BlockState previousState) {
        if (level == null || pos == null || previousState == null) return false;
        Optional<CropAdapter> adapterOpt = getAdapter(previousState.getBlock());
        if (adapterOpt.isEmpty()) return false;
        return adapterOpt.get().replant(level, pos, previousState);
    }

    public static boolean replant(ServerLevel level, BlockPos pos, BlockState previousState) {
        return replant((LevelAccessor) level, pos, previousState);
    }

    public record Offset2D(int dx, int dz) {}

    public static final int[] Y_OFFSETS = {0, 1, -1, 2, -2};
    public static final int MAX_SCAN_RADIUS = 16;
    public static final Offset2D[] HORIZONTAL_OFFSETS = computeHorizontalOffsets(MAX_SCAN_RADIUS);
    public static final int TOTAL_SEARCH_POSITIONS = Y_OFFSETS.length * HORIZONTAL_OFFSETS.length; // 5 * 1089 = 5445
    public static final int FARMER_SCAN_BUDGET = 500;

    public record CropScanResult(
            Optional<BlockPos> targetPos,
            int nextCursor,
            boolean cycleCompleted,
            int emptyChecks
    ) {
        public CropScanResult(Optional<BlockPos> targetPos, int nextCursor, boolean cycleCompleted) {
            this(targetPos, nextCursor, cycleCompleted, 0);
        }

        public static CropScanResult found(BlockPos pos, int nextCursor) {
            return new CropScanResult(Optional.of(pos), nextCursor, false, 0);
        }

        public static CropScanResult inProgress(int nextCursor) {
            return new CropScanResult(Optional.empty(), nextCursor, false, 0);
        }

        public static CropScanResult inProgress(int nextCursor, int emptyChecks) {
            return new CropScanResult(Optional.empty(), nextCursor, false, emptyChecks);
        }

        public static CropScanResult cycleCompleted(int nextCursor) {
            return new CropScanResult(Optional.empty(), nextCursor, true, 0);
        }
    }

    private static Offset2D[] computeHorizontalOffsets(int maxRadius) {
        int side = 2 * maxRadius + 1;
        Offset2D[] offsets = new Offset2D[side * side];
        int idx = 0;
        offsets[idx++] = new Offset2D(0, 0);
        for (int r = 1; r <= maxRadius; r++) {
            // Top and bottom edges of square ring r
            for (int dx = -r; dx <= r; dx++) {
                offsets[idx++] = new Offset2D(dx, -r);
                offsets[idx++] = new Offset2D(dx, r);
            }
            // Left and right edges (excluding corners already added)
            for (int dz = -r + 1; dz < r; dz++) {
                offsets[idx++] = new Offset2D(-r, dz);
                offsets[idx++] = new Offset2D(r, dz);
            }
        }
        return offsets;
    }

    /**
     * Bounded, deterministic search for an eligible mature crop around an anchor position within given radius.
     * Enforces strict per-tick bounding (maximum 500 positions checked per tick) to maintain server TPS.
     * Searches from crop-level outwards (Y=0, then Y=1, then nearby Y levels) in concentric rings.
     * Honors explicit crop allowlist in FarmerJobData and ignores unpermitted crop types.
     */
    public static CropScanResult findMatureCrop(LevelReader level, BlockPos center, int radius, FarmerJobData farmerData, int startCursor) {
        if (level == null || center == null || radius <= 0) {
            return CropScanResult.inProgress(0);
        }
        int effectiveRadius = Math.min(radius, MAX_SCAN_RADIUS);
        int cursor = Math.floorMod(startCursor, TOTAL_SEARCH_POSITIONS);
        int budget = FARMER_SCAN_BUDGET;
        int emptyChecks = (farmerData != null) ? Math.max(0, farmerData.emptyChecks()) : 0;

        int inspected = 0;
        BlockPos.MutableBlockPos candidatePos = new BlockPos.MutableBlockPos();

        while (inspected < budget) {
            int currentIndex = (cursor + inspected) % TOTAL_SEARCH_POSITIONS;
            int yIndex = currentIndex / HORIZONTAL_OFFSETS.length;
            int hIndex = currentIndex % HORIZONTAL_OFFSETS.length;
            int y = Y_OFFSETS[yIndex];
            Offset2D offset = HORIZONTAL_OFFSETS[hIndex];

            if (Math.max(Math.abs(offset.dx()), Math.abs(offset.dz())) <= effectiveRadius) {
                candidatePos.set(center.getX() + offset.dx(), center.getY() + y, center.getZ() + offset.dz());
                if (level.hasChunkAt(candidatePos)) {
                    BlockState state = level.getBlockState(candidatePos);
                    if (farmerData == null || farmerData.isCropPermitted(state.getBlock())) {
                        if (isMature(level, candidatePos, state)) {
                            int nextCursor = (currentIndex + 1) % TOTAL_SEARCH_POSITIONS;
                            return CropScanResult.found(candidatePos.immutable(), nextCursor);
                        }
                    }
                }
            }
            inspected++;
        }

        int nextCursor = (cursor + inspected) % TOTAL_SEARCH_POSITIONS;
        int updatedEmptyChecks = emptyChecks + inspected;
        boolean cycleCompleted = (updatedEmptyChecks >= TOTAL_SEARCH_POSITIONS);
        if (cycleCompleted) {
            return CropScanResult.cycleCompleted(0);
        } else {
            return CropScanResult.inProgress(nextCursor, updatedEmptyChecks);
        }
    }

    public static Optional<BlockPos> findMatureCrop(LevelReader level, BlockPos center, int radius, FarmerJobData farmerData) {
        return findMatureCrop(level, center, radius, farmerData, 0).targetPos();
    }

    public static Optional<BlockPos> findMatureCrop(LevelReader level, BlockPos center, int radius) {
        return findMatureCrop(level, center, radius, null);
    }

    /**
     * Adapter for vanilla farmland crops: Wheat, Carrots, Potatoes, Beetroot.
     */
    public static final class FarmlandCropAdapter implements CropAdapter {
        @Override
        public ResourceLocation id() {
            return FARMLAND_CROPS_ID;
        }

        @Override
        public boolean matches(Block block) {
            return block instanceof CropBlock || block == Blocks.WHEAT || block == Blocks.CARROTS ||
                    block == Blocks.POTATOES || block == Blocks.BEETROOTS;
        }

        @Override
        public boolean isMature(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null) return false;
            if (state.getBlock() instanceof CropBlock crop) {
                return crop.isMaxAge(state);
            }
            return false;
        }

        @Override
        public boolean isReplantedState(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null || !(state.getBlock() instanceof CropBlock crop)) return false;
            return crop.getAge(state) == 0;
        }

        @Override
        public Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null) return Optional.empty();
            Block block = state.getBlock();
            if (block == Blocks.WHEAT) {
                return Optional.of(Items.WHEAT_SEEDS);
            } else if (block == Blocks.CARROTS) {
                return Optional.of(Items.CARROT);
            } else if (block == Blocks.POTATOES) {
                return Optional.of(Items.POTATO);
            } else if (block == Blocks.BEETROOTS) {
                return Optional.of(Items.BEETROOT_SEEDS);
            }
            return Optional.empty();
        }

        @Override
        public boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState) {
            if (pos == null || previousState == null) return false;
            if (level == null) {
                return true;
            }
            BlockPos soilPos = pos.below();
            if (!level.hasChunkAt(soilPos)) return false;
            BlockState soilState = level.getBlockState(soilPos);
            // Farmland support requirement
            boolean validSoil = soilState.is(Blocks.FARMLAND);
            BlockState currentTargetState = level.getBlockState(pos);
            boolean validSpace = currentTargetState.isAir() || currentTargetState.canBeReplaced() ||
                    currentTargetState.getBlock() == previousState.getBlock();
            return validSoil && validSpace;
        }

        @Override
        public Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState) {
            if (previousState == null) return Optional.empty();
            Block block = previousState.getBlock();
            if (block instanceof CropBlock crop) {
                return Optional.of(crop.getStateForAge(0));
            }
            return Optional.empty();
        }

        public boolean replant(LevelAccessor level, BlockPos pos, BlockState previousState) {
            if (level == null || pos == null || previousState == null) return false;
            if (!level.hasChunkAt(pos)) return false;

            // Revalidate soil support immediately before placement
            BlockPos soilPos = pos.below();
            if (!level.hasChunkAt(soilPos)) return false;
            BlockState soilState = level.getBlockState(soilPos);
            if (!soilState.is(Blocks.FARMLAND)) return false;

            // Revalidate target position immediately before placement:
            // Physical replant must only proceed when the position is air or safely replaceable!
            BlockState current = level.getBlockState(pos);
            if (!current.isAir() && !current.canBeReplaced()) {
                // Another non-replaceable block occupies the position; abort replanting without removing or modifying it.
                return false;
            }

            Optional<BlockState> replantOpt = getReplantState(level, pos, previousState);
            if (replantOpt.isEmpty()) return false;
            BlockState replantState = replantOpt.get();
            boolean placed = level.setBlock(pos, replantState, 3);
            if (placed) {
                BlockState verified = level.getBlockState(pos);
                return verified.is(replantState.getBlock()) &&
                        (verified.getBlock() instanceof CropBlock crop && crop.getAge(verified) == 0);
            }
            return false;
        }

        @Override
        public boolean replant(ServerLevel level, BlockPos pos, BlockState previousState) {
            return replant((LevelAccessor) level, pos, previousState);
        }
    }

    /**
     * Adapter for vanilla Cocoa crops growing on the sides of jungle logs.
     */
    public static final class CocoaCropAdapter implements CropAdapter {
        @Override
        public ResourceLocation id() {
            return COCOA_CROP_ID;
        }

        @Override
        public boolean matches(Block block) {
            return block instanceof CocoaBlock || block == Blocks.COCOA;
        }

        @Override
        public boolean isMature(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null || !(state.getBlock() instanceof CocoaBlock)) return false;
            return state.hasProperty(CocoaBlock.AGE) && state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        }

        @Override
        public boolean isReplantedState(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null || !(state.getBlock() instanceof CocoaBlock)) return false;
            return state.hasProperty(CocoaBlock.AGE) && state.getValue(CocoaBlock.AGE) == 0;
        }

        @Override
        public Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state) {
            return Optional.of(Items.COCOA_BEANS);
        }

        @Override
        public boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState) {
            if (pos == null || previousState == null || !(previousState.getBlock() instanceof CocoaBlock)) {
                return false;
            }
            if (level == null) {
                return true;
            }
            if (!previousState.hasProperty(CocoaBlock.FACING)) return false;
            Direction facing = previousState.getValue(CocoaBlock.FACING);
            BlockPos logPos = pos.relative(facing);
            if (!level.hasChunkAt(logPos)) return false;
            BlockState logState = level.getBlockState(logPos);
            boolean validLog = logState.is(BlockTags.JUNGLE_LOGS) || logState.is(Blocks.JUNGLE_LOG) ||
                    logState.is(Blocks.STRIPPED_JUNGLE_LOG) || logState.is(Blocks.JUNGLE_WOOD) ||
                    logState.is(Blocks.STRIPPED_JUNGLE_WOOD);
            BlockState currentTargetState = level.getBlockState(pos);
            boolean validSpace = currentTargetState.isAir() || currentTargetState.canBeReplaced() ||
                    currentTargetState.getBlock() == previousState.getBlock();
            return validLog && validSpace;
        }

        @Override
        public Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState) {
            if (previousState == null || !(previousState.getBlock() instanceof CocoaBlock)) return Optional.empty();
            if (!previousState.hasProperty(CocoaBlock.FACING)) return Optional.empty();
            Direction facing = previousState.getValue(CocoaBlock.FACING);
            return Optional.of(Blocks.COCOA.defaultBlockState()
                    .setValue(CocoaBlock.FACING, facing)
                    .setValue(CocoaBlock.AGE, 0));
        }

        public boolean replant(LevelAccessor level, BlockPos pos, BlockState previousState) {
            if (level == null || pos == null || previousState == null || !(previousState.getBlock() instanceof CocoaBlock)) {
                return false;
            }
            if (!level.hasChunkAt(pos)) return false;
            if (!previousState.hasProperty(CocoaBlock.FACING)) return false;
            Direction facing = previousState.getValue(CocoaBlock.FACING);
            BlockPos logPos = pos.relative(facing);
            if (!level.hasChunkAt(logPos)) return false;
            BlockState logState = level.getBlockState(logPos);
            boolean validLog = logState.is(BlockTags.JUNGLE_LOGS) || logState.is(Blocks.JUNGLE_LOG) ||
                    logState.is(Blocks.STRIPPED_JUNGLE_LOG) || logState.is(Blocks.JUNGLE_WOOD) ||
                    logState.is(Blocks.STRIPPED_JUNGLE_WOOD);
            if (!validLog) return false;

            // Revalidate target position immediately before placement:
            // Physical replant must only proceed when the position is air or safely replaceable!
            BlockState current = level.getBlockState(pos);
            if (!current.isAir() && !current.canBeReplaced()) {
                // Another non-replaceable block occupies the position; abort replanting without removing or modifying it.
                return false;
            }

            Optional<BlockState> replantOpt = getReplantState(level, pos, previousState);
            if (replantOpt.isEmpty()) return false;
            BlockState replantState = replantOpt.get();
            boolean placed = level.setBlock(pos, replantState, 3);
            if (placed) {
                BlockState verified = level.getBlockState(pos);
                return verified.is(Blocks.COCOA) && verified.getValue(CocoaBlock.AGE) == 0;
            }
            return false;
        }

        @Override
        public boolean replant(ServerLevel level, BlockPos pos, BlockState previousState) {
            return replant((LevelAccessor) level, pos, previousState);
        }
    }

    /**
     * Adapter for vanilla Pumpkin fruit crops growing adjacent to pumpkin stems.
     */
    public static final class PumpkinCropAdapter implements CropAdapter {
        @Override
        public ResourceLocation id() {
            return PUMPKIN_CROP_ID;
        }

        @Override
        public boolean matches(Block block) {
            return block == Blocks.PUMPKIN;
        }

        @Override
        public boolean isMature(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null || !state.is(Blocks.PUMPKIN)) return false;
            if (level == null) return true;
            // Validate fruit target and stem relationship where present
            Optional<BlockPos> stem = findAssociatedStem(level, pos);
            if (stem.isPresent()) {
                return true;
            }
            // If no stem is directly adjacent, check if the fruit is resting on farmable soil (farmland/dirt/grass)
            BlockPos below = pos.below();
            if (level.hasChunkAt(below)) {
                BlockState soil = level.getBlockState(below);
                return soil.is(Blocks.FARMLAND) || soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK);
            }
            return false;
        }

        @Override
        public Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state) {
            return Optional.empty(); // Stems persist; fruit harvest does not consume seeds
        }

        @Override
        public boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState) {
            return false;
        }

        @Override
        public Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState) {
            return Optional.empty();
        }

        @Override
        public boolean replant(ServerLevel level, BlockPos pos, BlockState previousState) {
            return true; // Fruit harvest complete; no replant required
        }

        @Override
        public boolean replant(LevelAccessor level, BlockPos pos, BlockState previousState) {
            return true;
        }

        @Override
        public boolean requiresReplant() {
            return false;
        }

        public static Optional<BlockPos> findAssociatedStem(LevelReader level, BlockPos fruitPos) {
            if (level == null || fruitPos == null) return Optional.empty();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = fruitPos.relative(dir);
                if (level.hasChunkAt(neighbor)) {
                    BlockState neighborState = level.getBlockState(neighbor);
                    if (neighborState.is(Blocks.ATTACHED_PUMPKIN_STEM)) {
                        if (neighborState.hasProperty(AttachedStemBlock.FACING) &&
                                neighborState.getValue(AttachedStemBlock.FACING) == dir.getOpposite()) {
                            return Optional.of(neighbor);
                        }
                    } else if (neighborState.is(Blocks.PUMPKIN_STEM)) {
                        return Optional.of(neighbor);
                    }
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Adapter for vanilla Melon fruit crops growing adjacent to melon stems.
     */
    public static final class MelonCropAdapter implements CropAdapter {
        @Override
        public ResourceLocation id() {
            return MELON_CROP_ID;
        }

        @Override
        public boolean matches(Block block) {
            return block == Blocks.MELON;
        }

        @Override
        public boolean isMature(LevelReader level, BlockPos pos, BlockState state) {
            if (state == null || !state.is(Blocks.MELON)) return false;
            if (level == null) return true;
            // Validate fruit target and stem relationship where present
            Optional<BlockPos> stem = findAssociatedStem(level, pos);
            if (stem.isPresent()) {
                return true;
            }
            // If no stem is directly adjacent, check if the fruit is resting on farmable soil (farmland/dirt/grass)
            BlockPos below = pos.below();
            if (level.hasChunkAt(below)) {
                BlockState soil = level.getBlockState(below);
                return soil.is(Blocks.FARMLAND) || soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK);
            }
            return false;
        }

        @Override
        public Optional<Item> getPlantingItem(LevelReader level, BlockPos pos, BlockState state) {
            return Optional.empty(); // Stems persist; fruit harvest does not consume seeds
        }

        @Override
        public boolean canReplant(LevelReader level, BlockPos pos, BlockState previousState) {
            return false;
        }

        @Override
        public Optional<BlockState> getReplantState(LevelReader level, BlockPos pos, BlockState previousState) {
            return Optional.empty();
        }

        @Override
        public boolean replant(ServerLevel level, BlockPos pos, BlockState previousState) {
            return true; // Fruit harvest complete; no replant required
        }

        @Override
        public boolean replant(LevelAccessor level, BlockPos pos, BlockState previousState) {
            return true;
        }

        @Override
        public boolean requiresReplant() {
            return false;
        }

        public static Optional<BlockPos> findAssociatedStem(LevelReader level, BlockPos fruitPos) {
            if (level == null || fruitPos == null) return Optional.empty();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = fruitPos.relative(dir);
                if (level.hasChunkAt(neighbor)) {
                    BlockState neighborState = level.getBlockState(neighbor);
                    if (neighborState.is(Blocks.ATTACHED_MELON_STEM)) {
                        if (neighborState.hasProperty(AttachedStemBlock.FACING) &&
                                neighborState.getValue(AttachedStemBlock.FACING) == dir.getOpposite()) {
                            return Optional.of(neighbor);
                        }
                    } else if (neighborState.is(Blocks.MELON_STEM)) {
                        return Optional.of(neighbor);
                    }
                }
            }
            return Optional.empty();
        }
    }
}
