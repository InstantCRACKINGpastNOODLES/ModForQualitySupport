package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Extensible Equipment Capability, Autonomous Usability, and Emergency Evaluation Policy.
 *
 * Invariants & Core Architecture:
 * 1. Physical EquipmentCondition is strictly decoupled from AutonomousUseCapability.
 * 2. An item without vanilla durability is NEVER classified as BROKEN.
 * 3. getDamageValue() == 0 is NEVER interpreted as proof of indestructible or unusable.
 * 4. Absence of a durability component is NEVER interpreted as durability depletion.
 * 5. Indeterminate physical condition is classified as UNKNOWN, never BROKEN.
 * 6. AutonomousUseCapability assesses whether an item is verified, unsupported, unverified,
 *    unsafe (e.g. destructive AoE failing closed), or resource-exhausted.
 * 7. Emergency rescue is triggered ONLY when equipment is genuinely insufficient and unrecoverable.
 * 8. Comprehensive shelter geometry supporting FULL_SHELTER (7x7, 121 blocks),
 *    PARTIAL_SHELTER (3x3 with safe entrance doorway, 31 blocks), and
 *    DIRECTIONAL_BARRIER (3-wide threat-facing wall, 9 blocks).
 * 9. Worker standing/head collision space (0,0, dy=0..2) is NEVER placed or suffocated.
 * 10. Material planning verifies WorkerStorage, never generates items, never consumes pending output
 *     or reserved stacks, safely halts when depleted, and resumes without duplication.
 */
public final class EquipmentCapabilityPolicy {

    public enum EquipmentCondition {
        USABLE,
        BROKEN,
        EXHAUSTED_RESOURCE, // e.g. out of energy, charge, or ammunition
        UNKNOWN // condition indeterminate; must NOT be assumed broken
    }

    public enum AutonomousUseCapability {
        VERIFIED_AUTONOMOUS_USE,
        NOT_SUPPORTED,
        UNVERIFIED,
        UNSAFE,
        RESOURCE_EXHAUSTED
    }

    public enum DurabilityProfile {
        VANILLA_DAMAGEABLE,
        NON_DAMAGEABLE,
        CUSTOM_ENERGY_OR_CHARGE,
        INDESTRUCTIBLE,
        UNKNOWN
    }

    public enum EquipmentCategory {
        MINING_TOOL,
        WEAPON,
        ARMOR_HELMET,
        ARMOR_CHESTPLATE,
        ARMOR_LEGGINGS,
        ARMOR_BOOTS,
        MISC
    }

    public enum RescueTrigger {
        NONE,
        UNRECOVERABLE_TOOL_EXHAUSTION,
        UNRECOVERABLE_WEAPON_EXHAUSTION,
        SURVIVAL_CRITICAL_EQUIPMENT_LOSS,
        IMPASSABLE_CORRIDOR_HAZARD,
        MANUAL_OPERATOR_REQUEST
    }

    public record EquipmentAssessment(
            ItemStack stack,
            EquipmentCategory category,
            DurabilityProfile profile,
            EquipmentCondition condition,
            AutonomousUseCapability capability,
            String detail
    ) {
        public boolean isPhysicallyUsable() {
            return condition == EquipmentCondition.USABLE || condition == EquipmentCondition.UNKNOWN;
        }

        public boolean isAutonomouslyUsable() {
            return condition == EquipmentCondition.USABLE && capability == AutonomousUseCapability.VERIFIED_AUTONOMOUS_USE;
        }

        public boolean isAutonomousUnsafe() {
            return capability == AutonomousUseCapability.UNSAFE;
        }
    }

    public record RescueEvaluation(
            boolean rescueRequired,
            RescueTrigger trigger,
            Optional<String> reason
    ) {
        public static RescueEvaluation none() {
            return new RescueEvaluation(false, RescueTrigger.NONE, Optional.empty());
        }

        public static RescueEvaluation required(RescueTrigger trigger, String reason) {
            return new RescueEvaluation(true, trigger, Optional.ofNullable(reason));
        }

        public boolean requiresRescue() {
            return rescueRequired;
        }
    }

    @FunctionalInterface
    public interface CustomEquipmentAdapter {
        EquipmentCondition evaluate(ItemStack stack);
    }

    @FunctionalInterface
    public interface AutonomousCapabilityAdapter {
        AutonomousUseCapability assessCapability(ItemStack stack, EquipmentCategory category, EquipmentCondition condition);
    }

    private static final Map<ResourceLocation, CustomEquipmentAdapter> CUSTOM_ADAPTERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, AutonomousCapabilityAdapter> CAPABILITY_ADAPTERS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> KNOWN_INDESTRUCTIBLE_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> KNOWN_UNSAFE_ITEMS = ConcurrentHashMap.newKeySet();

    private EquipmentCapabilityPolicy() {
    }

    public static void registerCustomAdapter(ResourceLocation id, CustomEquipmentAdapter adapter) {
        Objects.requireNonNull(id, "Adapter ID must not be null");
        Objects.requireNonNull(adapter, "Adapter must not be null");
        CUSTOM_ADAPTERS.put(id, adapter);
    }

    public static void registerCapabilityAdapter(ResourceLocation id, AutonomousCapabilityAdapter adapter) {
        Objects.requireNonNull(id, "Adapter ID must not be null");
        Objects.requireNonNull(adapter, "Capability adapter must not be null");
        CAPABILITY_ADAPTERS.put(id, adapter);
    }

    public static void registerIndestructibleItem(ResourceLocation itemId) {
        if (itemId != null) {
            KNOWN_INDESTRUCTIBLE_ITEMS.add(itemId);
        }
    }

    public static void registerUnsafeItem(ResourceLocation itemId) {
        if (itemId != null) {
            KNOWN_UNSAFE_ITEMS.add(itemId);
        }
    }

    public static void clearRegistries() {
        CUSTOM_ADAPTERS.clear();
        CAPABILITY_ADAPTERS.clear();
        KNOWN_INDESTRUCTIBLE_ITEMS.clear();
        KNOWN_UNSAFE_ITEMS.clear();
    }

    /**
     * Determines the durability profile of an item stack.
     */
    public static DurabilityProfile getDurabilityProfile(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return DurabilityProfile.UNKNOWN;
        }

        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (KNOWN_INDESTRUCTIBLE_ITEMS.contains(itemId)) {
            return DurabilityProfile.INDESTRUCTIBLE;
        }

        // Check if item has a registered custom adapter
        if (CUSTOM_ADAPTERS.containsKey(itemId)) {
            return DurabilityProfile.CUSTOM_ENERGY_OR_CHARGE;
        }

        // Vanilla durability component (DataComponents.MAX_DAMAGE)
        if (stack.isDamageableItem()) {
            return DurabilityProfile.VANILLA_DAMAGEABLE;
        }

        return DurabilityProfile.NON_DAMAGEABLE;
    }

    /**
     * Evaluates the operational condition of an item without making invalid durability assumptions.
     *
     * Invariants:
     * - Item with vanilla durability component and remaining > 0: USABLE (subject to safety threshold).
     * - Item with vanilla durability component and remaining <= 1: BROKEN.
     * - Item with NO vanilla durability component: NEVER classified as BROKEN.
     * - Item with custom energy/charge: evaluated through registered adapter when available.
     * - Unknown condition: classified as UNKNOWN, never BROKEN.
     * - getDamageValue() == 0 is never treated as proof of indestructible or unusable.
     * - Absence of durability component is never treated as durability depletion.
     */
    public static EquipmentCondition evaluateCondition(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EquipmentCondition.UNKNOWN;
        }

        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());

        // 1. Known indestructible item (e.g. Avaritia tools/armor)
        if (KNOWN_INDESTRUCTIBLE_ITEMS.contains(itemId)) {
            return EquipmentCondition.USABLE;
        }

        // 2. Custom capability or modded adapter (e.g. Mekanism energy, FE/RF charge, custom ammo)
        CustomEquipmentAdapter adapter = CUSTOM_ADAPTERS.get(itemId);
        if (adapter != null) {
            try {
                EquipmentCondition result = adapter.evaluate(stack);
                return result != null ? result : EquipmentCondition.UNKNOWN;
            } catch (Throwable t) {
                return EquipmentCondition.UNKNOWN;
            }
        }

        // 3. Vanilla damageable item
        if (stack.isDamageableItem()) {
            int maxDamage = stack.getMaxDamage();
            int currentDamage = stack.getDamageValue();
            int remaining = maxDamage - currentDamage;

            // When remaining durability drops to or below threshold, item is broken
            if (remaining <= JobSafetyPolicy.MIN_TOOL_DURABILITY) {
                return EquipmentCondition.BROKEN;
            }
            return EquipmentCondition.USABLE;
        }

        // 4. Non-damageable item (Scythes, Dreadsteel armor, modded indestructible equipment)
        // Absence of vanilla durability component MUST NOT be classified as broken!
        return EquipmentCondition.USABLE;
    }

    /**
     * Categorizes an item stack into its functional equipment category.
     */
    public static EquipmentCategory categorizeEquipment(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EquipmentCategory.MISC;
        }
        Item item = stack.getItem();
        if (item instanceof ArmorItem armor) {
            return switch (armor.getEquipmentSlot()) {
                case HEAD -> EquipmentCategory.ARMOR_HELMET;
                case CHEST -> EquipmentCategory.ARMOR_CHESTPLATE;
                case LEGS -> EquipmentCategory.ARMOR_LEGGINGS;
                case FEET -> EquipmentCategory.ARMOR_BOOTS;
                default -> EquipmentCategory.MISC;
            };
        }
        if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem) {
            return EquipmentCategory.WEAPON;
        }
        if (item instanceof DiggerItem) {
            return EquipmentCategory.MINING_TOOL;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase(java.util.Locale.ROOT);
        if (path.contains("scythe") || path.contains("sword") || path.contains("blade") || path.contains("spear") || path.contains("weapon")) {
            return EquipmentCategory.WEAPON;
        }
        if (path.contains("pickaxe") || path.contains("shovel") || path.contains("axe") || path.contains("hoe") || path.contains("drill")) {
            return EquipmentCategory.MINING_TOOL;
        }
        if (path.contains("helmet") || path.contains("hood") || path.contains("crown")) {
            return EquipmentCategory.ARMOR_HELMET;
        }
        if (path.contains("chestplate") || path.contains("robe") || path.contains("tunic")) {
            return EquipmentCategory.ARMOR_CHESTPLATE;
        }
        if (path.contains("leggings") || path.contains("pants") || path.contains("greaves")) {
            return EquipmentCategory.ARMOR_LEGGINGS;
        }
        if (path.contains("boots") || path.contains("shoes") || path.contains("greaves")) {
            return EquipmentCategory.ARMOR_BOOTS;
        }
        return EquipmentCategory.MISC;
    }

    /**
     * Assesses an item stack comprehensively, strictly separating physical condition from autonomous-use capability.
     */
    public static EquipmentAssessment assessEquipment(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new EquipmentAssessment(
                    ItemStack.EMPTY,
                    EquipmentCategory.MISC,
                    DurabilityProfile.UNKNOWN,
                    EquipmentCondition.UNKNOWN,
                    AutonomousUseCapability.NOT_SUPPORTED,
                    "Empty or null item stack"
            );
        }

        EquipmentCategory category = categorizeEquipment(stack);
        DurabilityProfile profile = getDurabilityProfile(stack);
        EquipmentCondition condition = evaluateCondition(stack);
        ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());

        // 1. Explicitly marked unsafe items (e.g. destructive AoE items like Avaritia tools)
        if (KNOWN_UNSAFE_ITEMS.contains(itemId)) {
            return new EquipmentAssessment(
                    stack, category, profile, condition,
                    AutonomousUseCapability.UNSAFE,
                    "Item has destructive or uncontrolled AoE effects that fail closed for autonomous use"
            );
        }

        // 2. Custom capability adapter
        AutonomousCapabilityAdapter adapter = CAPABILITY_ADAPTERS.get(itemId);
        if (adapter != null) {
            try {
                AutonomousUseCapability cap = adapter.assessCapability(stack, category, condition);
                return new EquipmentAssessment(
                        stack, category, profile, condition,
                        cap != null ? cap : AutonomousUseCapability.UNVERIFIED,
                        "Evaluated via registered capability adapter"
                );
            } catch (Throwable t) {
                return new EquipmentAssessment(
                        stack, category, profile, condition,
                        AutonomousUseCapability.UNVERIFIED,
                        "Capability adapter error: " + t.getMessage()
                );
            }
        }

        // 3. Resource exhaustion
        if (condition == EquipmentCondition.EXHAUSTED_RESOURCE) {
            return new EquipmentAssessment(
                    stack, category, profile, condition,
                    AutonomousUseCapability.RESOURCE_EXHAUSTED,
                    "Equipment resource (charge, energy, or ammo) is depleted"
            );
        }

        // 4. Broken durability
        if (condition == EquipmentCondition.BROKEN) {
            return new EquipmentAssessment(
                    stack, category, profile, condition,
                    AutonomousUseCapability.RESOURCE_EXHAUSTED,
                    "Durability depleted"
            );
        }

        // 5. Category-specific autonomous usability
        switch (category) {
            case ARMOR_HELMET, ARMOR_CHESTPLATE, ARMOR_LEGGINGS, ARMOR_BOOTS -> {
                // Armor provides passive defense, NOT autonomous tool activation
                return new EquipmentAssessment(
                        stack, category, profile, condition,
                        AutonomousUseCapability.NOT_SUPPORTED,
                        "Armor provides passive protection, not autonomously activated"
                );
            }
            case MINING_TOOL -> {
                if (stack.getItem() instanceof DiggerItem) {
                    return new EquipmentAssessment(
                            stack, category, profile, condition,
                            condition == EquipmentCondition.USABLE ? AutonomousUseCapability.VERIFIED_AUTONOMOUS_USE : AutonomousUseCapability.UNVERIFIED,
                            "Standard digger tool verified for autonomous excavation"
                    );
                }
                return new EquipmentAssessment(
                        stack, category, profile, condition,
                        AutonomousUseCapability.UNVERIFIED,
                        "Modded mining tool requires explicit capability verification"
                );
            }
            case WEAPON -> {
                Item item = stack.getItem();
                if (item instanceof SwordItem || item instanceof BowItem || item instanceof CrossbowItem) {
                    return new EquipmentAssessment(
                            stack, category, profile, condition,
                            condition == EquipmentCondition.USABLE ? AutonomousUseCapability.VERIFIED_AUTONOMOUS_USE : AutonomousUseCapability.UNVERIFIED,
                            "Standard weapon verified for autonomous combat"
                    );
                }
                return new EquipmentAssessment(
                        stack, category, profile, condition,
                        AutonomousUseCapability.UNVERIFIED,
                        "Modded weapon unverified for autonomous combat activation"
                );
            }
            default -> {
                return new EquipmentAssessment(
                        stack, category, profile, condition,
                        AutonomousUseCapability.NOT_SUPPORTED,
                        "Item category does not support autonomous action"
                );
            }
        }
    }

    /**
     * Evaluates weapon usability independently from vanilla durability.
     * Scythes without durability, Avaritia Infinity Swords, and modded weapons without durability are USABLE.
     */
    public static boolean isWeaponUsable(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return false;
        }
        EquipmentCondition condition = evaluateCondition(weapon);
        return condition == EquipmentCondition.USABLE || condition == EquipmentCondition.UNKNOWN;
    }

    public static boolean isWeaponAutonomouslyUsable(ItemStack weapon) {
        return assessEquipment(weapon).isAutonomouslyUsable();
    }

    /**
     * Evaluates armor piece usability independently from vanilla durability.
     * Dreadsteel armor pieces and Avaritia Infinity armor without durability are USABLE.
     */
    public static boolean isArmorUsable(ItemStack armor) {
        if (armor == null || armor.isEmpty()) {
            return false;
        }
        EquipmentCondition condition = evaluateCondition(armor);
        return condition == EquipmentCondition.USABLE || condition == EquipmentCondition.UNKNOWN;
    }

    /**
     * Evaluates mining tool usability independently from vanilla durability.
     * Valid indestructible modded pickaxes without durability remain USABLE.
     */
    public static boolean isMiningToolUsable(ItemStack tool) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        EquipmentCondition condition = evaluateCondition(tool);
        return condition == EquipmentCondition.USABLE || condition == EquipmentCondition.UNKNOWN;
    }

    public static boolean isMiningToolAutonomouslyUsable(ItemStack tool) {
        return assessEquipment(tool).isAutonomouslyUsable();
    }

    /**
     * Evaluates whether an emergency rescue condition exists for a worker entity.
     *
     * Invariants:
     * - An adequately equipped worker must NEVER request rescue solely because its equipment lacks vanilla durability.
     * - Missing ammunition or energy is distinguished from broken durability.
     * - Unknown equipment condition does NOT trigger emergency rescue.
     * - Unverified autonomous capability does NOT trigger durability emergency rescue.
     */
    public static RescueEvaluation evaluateWorkerRescueNeeds(
            Mob worker,
            WorkerStorage storage,
            boolean returnRouteBlockedAndUnmineable,
            boolean underHostileThreat
    ) {
        return evaluateWorkerRescueNeeds(worker, storage, returnRouteBlockedAndUnmineable, underHostileThreat, false);
    }

    public static RescueEvaluation evaluateWorkerRescueNeeds(
            Mob worker,
            WorkerStorage storage,
            boolean returnRouteBlockedAndUnmineable,
            boolean underHostileThreat,
            boolean isMiningJob
    ) {
        if (worker == null) {
            return RescueEvaluation.none();
        }

        // 1. Environmental / unrecoverable corridor hazard
        if (returnRouteBlockedAndUnmineable) {
            return RescueEvaluation.required(
                    RescueTrigger.IMPASSABLE_CORRIDOR_HAZARD,
                    "Return route is blocked by an unmineable or impassable hazard"
            );
        }

        // 2. Weapon & Combat Evaluation under hostile threat
        if (underHostileThreat) {
            ItemStack heldWeapon = worker.getMainHandItem();
            EquipmentAssessment assessment = assessEquipment(heldWeapon);

            if (heldWeapon.isEmpty() || assessment.condition() == EquipmentCondition.BROKEN) {
                // Search WorkerStorage for any usable weapon
                boolean hasBackupWeapon = false;
                if (storage != null) {
                    for (ItemStack item : storage.items()) {
                        if (categorizeEquipment(item) == EquipmentCategory.WEAPON && isWeaponUsable(item)) {
                            hasBackupWeapon = true;
                            break;
                        }
                    }
                }
                if (!hasBackupWeapon) {
                    return RescueEvaluation.required(
                            RescueTrigger.UNRECOVERABLE_WEAPON_EXHAUSTION,
                            "Under hostile threat without usable weapon or weapon replacement"
                    );
                }
            } else if (assessment.condition() == EquipmentCondition.EXHAUSTED_RESOURCE) {
                return RescueEvaluation.required(
                        RescueTrigger.UNRECOVERABLE_WEAPON_EXHAUSTION,
                        "Under hostile threat with exhausted weapon resource (energy, charge, or ammunition)"
                );
            }
        }

        // 3. Mining Tool Evaluation
        boolean isMiner = isMiningJob;
        if (!isMiner && worker.hasData(ExampleMod.WORKER_PROFILE)) {
            WorkerProfile prof = worker.getData(ExampleMod.WORKER_PROFILE);
            if (prof.job().isPresent() && prof.job().get().minerData().isPresent()) {
                isMiner = true;
            }
        }

        ItemStack heldTool = worker.getMainHandItem();
        EquipmentAssessment toolAssessment = assessEquipment(heldTool);

        boolean toolExhausted = (isMiner && !isMiningToolUsable(heldTool)) || toolAssessment.condition() == EquipmentCondition.BROKEN;
        if (toolExhausted) {
            // Check WorkerStorage for replacement tool
            boolean hasBackupTool = false;
            if (storage != null) {
                for (ItemStack item : storage.items()) {
                    if (isMiningToolUsable(item)) {
                        hasBackupTool = true;
                        break;
                    }
                }
            }
            if (!hasBackupTool) {
                return RescueEvaluation.required(
                        RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION,
                        "Mining tool durability depleted and no suitable replacement tool available in WorkerStorage"
                );
            }
        }

        return RescueEvaluation.none();
    }

    // =========================================================================
    // Emergency Shelter Blueprint & Geometry Model
    // =========================================================================

    public enum ShelterMode {
        FULL_SHELTER(121),
        PARTIAL_SHELTER(31),
        DIRECTIONAL_BARRIER(9);

        private final int standardBlockCount;

        ShelterMode(int standardBlockCount) {
            this.standardBlockCount = standardBlockCount;
        }

        public int getStandardBlockCount() {
            return standardBlockCount;
        }
    }

    public record ShelterBlockPlacement(
            BlockPos pos,
            boolean isRoof,
            boolean isWall
    ) {}

    public record EmergencyShelterPlan(
            BlockPos center,
            ShelterMode mode,
            int radius,
            int wallHeight,
            List<ShelterBlockPlacement> placements,
            int requiredBlockCount
    ) {
        public EmergencyShelterPlan(BlockPos center, int radius, int wallHeight, List<ShelterBlockPlacement> placements, int requiredBlockCount) {
            this(center, radius >= 3 ? ShelterMode.FULL_SHELTER : ShelterMode.PARTIAL_SHELTER, radius, wallHeight, placements, requiredBlockCount);
        }
    }

    /**
     * Generates an emergency defensive shelter plan centered on the worker.
     *
     * Modes:
     * 1. FULL_SHELTER:
     *    - 7x7 horizontal footprint (radius 3).
     *    - Perimeter walls 3 blocks high (dy in [0, 2]): 24 * 3 = 72 blocks.
     *    - Roof full 7x7 slab at dy = 3: 49 blocks.
     *    - Total: 121 blocks.
     *    - Center column (0,0, dy=0..2) remains empty safe space.
     *
     * 2. PARTIAL_SHELTER:
     *    - 3x3 horizontal footprint (radius 1).
     *    - Walls 3 blocks high (dy in [0, 2]) on 7 outer columns: 7 * 3 = 21 blocks.
     *    - Entrance doorway at facing: dy=0,1 OPEN for safe egress/ingress; dy=2 lintel: 1 block.
     *    - Roof full 3x3 slab at dy = 3: 9 blocks.
     *    - Total: 22 + 9 = 31 blocks.
     *    - Center column (0,0, dy=0..2) remains empty safe space. Worker is never trapped.
     *
     * 3. DIRECTIONAL_BARRIER:
     *    - 3-wide protective wall 1 block in front of worker facing threatDirection.
     *    - Height 3 (dy in [0, 2]): 3 * 3 = 9 blocks.
     *    - Roof is open, sides and rear remain completely open for immediate escape.
     */
    public static EmergencyShelterPlan generateShelterPlan(BlockPos center, ShelterMode mode, Direction facing, int wallHeight) {
        Objects.requireNonNull(center, "Center position must not be null");
        Objects.requireNonNull(mode, "ShelterMode must not be null");
        Direction face = (facing != null) ? facing : Direction.NORTH;
        int h = Math.max(2, wallHeight);

        List<ShelterBlockPlacement> placements = new ArrayList<>();

        switch (mode) {
            case FULL_SHELTER -> {
                int r = 3;
                // Perimeter walls: 24 blocks per level * h (3) = 72 blocks
                for (int dy = 0; dy < h; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            boolean isPerimeter = (dx == -r || dx == r || dz == -r || dz == r);
                            if (isPerimeter) {
                                placements.add(new ShelterBlockPlacement(center.offset(dx, dy, dz), false, true));
                            }
                        }
                    }
                }
                // Roof: full 7x7 slab at dy = h: 49 blocks
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        placements.add(new ShelterBlockPlacement(center.offset(dx, h, dz), true, false));
                    }
                }
                return new EmergencyShelterPlan(center, mode, r, h, Collections.unmodifiableList(placements), placements.size());
            }

            case PARTIAL_SHELTER -> {
                int r = 1; // 3x3 footprint
                int doorDx = face.getStepX();
                int doorDz = face.getStepZ();

                // Walls: dy in [0, h - 1]
                for (int dy = 0; dy < h; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (dx == 0 && dz == 0) continue; // center safe space

                            // Doorway opening: dy=0 and dy=1 remain OPEN for safe egress/ingress
                            if (dx == doorDx && dz == doorDz) {
                                if (dy < 2) {
                                    continue;
                                }
                            }
                            placements.add(new ShelterBlockPlacement(center.offset(dx, dy, dz), false, true));
                        }
                    }
                }

                // Roof: full 3x3 slab at dy = h: 9 blocks
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        placements.add(new ShelterBlockPlacement(center.offset(dx, h, dz), true, false));
                    }
                }
                return new EmergencyShelterPlan(center, mode, r, h, Collections.unmodifiableList(placements), placements.size());
            }

            case DIRECTIONAL_BARRIER -> {
                int frontDx = face.getStepX();
                int frontDz = face.getStepZ();
                Direction left = face.getClockWise();
                int leftDx = left.getStepX();
                int leftDz = left.getStepZ();

                for (int dy = 0; dy < h; dy++) {
                    placements.add(new ShelterBlockPlacement(center.offset(frontDx, dy, frontDz), false, true));
                    placements.add(new ShelterBlockPlacement(center.offset(frontDx + leftDx, dy, frontDz + leftDz), false, true));
                    placements.add(new ShelterBlockPlacement(center.offset(frontDx - leftDx, dy, frontDz - leftDz), false, true));
                }
                // No roof, open sides and rear
                return new EmergencyShelterPlan(center, mode, 1, h, Collections.unmodifiableList(placements), placements.size());
            }
        }
        throw new IllegalArgumentException("Unknown ShelterMode: " + mode);
    }

    public static EmergencyShelterPlan generateShelterPlan(BlockPos center, int radius, int wallHeight) {
        return generateShelterPlan(center, radius >= 3 ? ShelterMode.FULL_SHELTER : ShelterMode.PARTIAL_SHELTER, Direction.NORTH, wallHeight);
    }

    /**
     * Determines whether a block state is suitable for emergency shelter construction.
     * Excludes air, fluids, falling blocks (sand/gravel), and hazardous blocks.
     */
    public static boolean isSuitableBuildingBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getBlock() instanceof FallingBlock) {
            return false; // gravity blocks cannot be used for shelter walls or roof
        }
        return state.blocksMotion();
    }

    /**
     * Collision space safety: verifies targetPos never intersects worker standing or head space.
     */
    public static boolean isInsideWorkerCollision(BlockPos targetPos, Mob worker, BlockPos center) {
        if (targetPos == null) return false;
        if (center != null) {
            // Standing column dx=0, dz=0, dy in [0, 2] is strictly protected
            if (targetPos.getX() == center.getX() && targetPos.getZ() == center.getZ()) {
                int dy = targetPos.getY() - center.getY();
                if (dy >= 0 && dy <= 2) {
                    return true;
                }
            }
        }
        if (worker != null) {
            BlockPos workerPos = worker.blockPosition();
            if (targetPos.getX() == workerPos.getX() && targetPos.getZ() == workerPos.getZ()) {
                int dy = targetPos.getY() - workerPos.getY();
                if (dy >= 0 && dy <= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Helper to compute total quantity of reserved items matching candidate stack's item and DataComponents.
     */
    private static int getTotalReservedQuantity(ItemStack candidate, Collection<ItemStack> reservedStacks) {
        if (candidate == null || candidate.isEmpty() || reservedStacks == null || reservedStacks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ItemStack res : reservedStacks) {
            if (res != null && !res.isEmpty() && ItemStack.isSameItemSameComponents(candidate, res)) {
                total += res.getCount();
            }
        }
        return total;
    }

    /**
     * Counts suitable solid building blocks in WorkerStorage, respecting quantity-aware reservations.
     * Differentiates DataComponents and avoids mutating or double-subtracting reserved stacks.
     */
    public static int countSuitableBuildingBlocks(WorkerStorage storage, Collection<ItemStack> reservedStacks) {
        if (storage == null || storage.items() == null) {
            return 0;
        }

        // Group total suitable counts by distinct item+components in storage
        List<ItemStack> distinctTypes = new ArrayList<>();
        List<Integer> totalCounts = new ArrayList<>();

        for (ItemStack stack : storage.items()) {
            if (stack == null || stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (isSuitableBuildingBlock(blockItem.getBlock().defaultBlockState())) {
                    int matchIdx = -1;
                    for (int i = 0; i < distinctTypes.size(); i++) {
                        if (ItemStack.isSameItemSameComponents(distinctTypes.get(i), stack)) {
                            matchIdx = i;
                            break;
                        }
                    }
                    if (matchIdx >= 0) {
                        totalCounts.set(matchIdx, totalCounts.get(matchIdx) + stack.getCount());
                    } else {
                        distinctTypes.add(stack.copy());
                        totalCounts.add(stack.getCount());
                    }
                }
            }
        }

        // For each distinct suitable block type, subtract total reserved quantity
        int availableTotal = 0;
        for (int i = 0; i < distinctTypes.size(); i++) {
            ItemStack type = distinctTypes.get(i);
            int inStorage = totalCounts.get(i);
            int reserved = getTotalReservedQuantity(type, reservedStacks);
            int available = Math.max(0, inStorage - reserved);
            availableTotal += available;
        }
        return availableTotal;
    }

    public record MaterialExtractionResult(
            WorkerStorage updatedStorage,
            ItemStack extractedBlock
    ) {}

    /**
     * Safely extracts 1 suitable building block from WorkerStorage.
     * Enforces quantity-aware reservation: never extracts an item needed to satisfy reservations.
     * Never generates blocks, preserves DataComponents, and respects stack identity.
     */
    public static Optional<MaterialExtractionResult> extractBuildingBlock(WorkerStorage storage, Collection<ItemStack> reservedStacks) {
        if (storage == null || storage.items() == null) {
            return Optional.empty();
        }

        // 1. Build a working list of remaining reservation quantities per distinct item+component
        List<ItemStack> resTypes = new ArrayList<>();
        List<Integer> resRemaining = new ArrayList<>();
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
                    resRemaining.set(matchIdx, resRemaining.get(matchIdx) + res.getCount());
                } else {
                    resTypes.add(res.copy());
                    resRemaining.add(res.getCount());
                }
            }
        }

        // 2. Scan WorkerStorage slots in order
        List<ItemStack> list = new ArrayList<>(storage.items());
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = list.get(i);
            if (stack == null || stack.isEmpty()) continue;
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (isSuitableBuildingBlock(blockItem.getBlock().defaultBlockState())) {
                    // Find if there is a pending reservation for this type
                    int matchIdx = -1;
                    for (int r = 0; r < resTypes.size(); r++) {
                        if (ItemStack.isSameItemSameComponents(resTypes.get(r), stack)) {
                            matchIdx = r;
                            break;
                        }
                    }

                    int neededReservation = (matchIdx >= 0) ? resRemaining.get(matchIdx) : 0;

                    if (stack.getCount() > neededReservation) {
                        // This slot has unreserved blocks available! Extract 1
                        ItemStack singleBlock = stack.copyWithCount(1);
                        if (stack.getCount() > 1) {
                            list.set(i, stack.copyWithCount(stack.getCount() - 1));
                        } else {
                            list.set(i, ItemStack.EMPTY);
                        }
                        WorkerStorage newStorage = new WorkerStorage(list);
                        return Optional.of(new MaterialExtractionResult(newStorage, singleBlock));
                    } else {
                        // All blocks in this slot are consumed by the reservation
                        if (matchIdx >= 0) {
                            resRemaining.set(matchIdx, neededReservation - stack.getCount());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Tracks incremental, resumable, and durably persistent shelter construction.
     */
    public static final class ShelterConstructionState {
        public static final Codec<ShelterConstructionState> CODEC = CompoundTag.CODEC.xmap(
                ShelterConstructionState::fromTag,
                ShelterConstructionState::toTag
        );

        private final UUID workerId;
        private final UUID eventId;
        private final EmergencyShelterPlan plan;
        private final Direction facing;
        private final Set<BlockPos> placedPositions = new HashSet<>();
        private int nextIndex = 0;
        private boolean isHalted = false;
        private boolean isComplete = false;
        private Optional<String> haltReason = Optional.empty();

        public ShelterConstructionState(UUID workerId, UUID eventId, EmergencyShelterPlan plan, Direction facing) {
            this.workerId = workerId;
            this.eventId = eventId;
            this.plan = plan;
            this.facing = (facing != null) ? facing : Direction.NORTH;
        }

        public ShelterConstructionState(EmergencyShelterPlan plan) {
            this(null, null, plan, Direction.NORTH);
        }

        public static ShelterConstructionState empty() {
            return new ShelterConstructionState(null, null, null, Direction.NORTH);
        }

        public boolean isEmpty() {
            return plan == null;
        }

        public UUID getWorkerId() {
            return workerId;
        }

        public UUID getEventId() {
            return eventId;
        }

        public Direction getFacing() {
            return facing;
        }

        public EmergencyShelterPlan getPlan() {
            return plan;
        }

        public boolean isComplete() {
            return isComplete;
        }

        public boolean isHalted() {
            return isHalted;
        }

        public Optional<String> getHaltReason() {
            return haltReason;
        }

        public int getPlacedCount() {
            return placedPositions.size();
        }

        public int getNextIndex() {
            return nextIndex;
        }

        public int getRemainingCount() {
            if (plan == null) return 0;
            return Math.max(0, plan.requiredBlockCount() - placedPositions.size());
        }

        public Set<BlockPos> getPlacedPositions() {
            return Collections.unmodifiableSet(placedPositions);
        }

        public void recordPlacement(BlockPos pos) {
            if (pos != null) {
                placedPositions.add(pos);
            }
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            if (plan == null) {
                tag.putBoolean("Empty", true);
                return tag;
            }
            if (workerId != null) {
                tag.putUUID("WorkerId", workerId);
            }
            if (eventId != null) {
                tag.putUUID("EventId", eventId);
            }
            tag.putString("Mode", plan.mode().name());
            tag.putInt("CenterX", plan.center().getX());
            tag.putInt("CenterY", plan.center().getY());
            tag.putInt("CenterZ", plan.center().getZ());
            tag.putInt("Radius", plan.radius());
            tag.putInt("WallHeight", plan.wallHeight());
            tag.putString("Facing", facing.getName());
            tag.putInt("NextIndex", nextIndex);
            tag.putBoolean("IsHalted", isHalted);
            tag.putBoolean("IsComplete", isComplete);
            haltReason.ifPresent(reason -> tag.putString("HaltReason", reason));

            ListTag placedList = new ListTag();
            for (BlockPos pos : placedPositions) {
                CompoundTag pTag = new CompoundTag();
                pTag.putInt("X", pos.getX());
                pTag.putInt("Y", pos.getY());
                pTag.putInt("Z", pos.getZ());
                placedList.add(pTag);
            }
            tag.put("PlacedPositions", placedList);
            return tag;
        }

        public static ShelterConstructionState fromTag(CompoundTag tag) {
            if (tag.getBoolean("Empty") || !tag.contains("Mode")) {
                return empty();
            }
            UUID workerId = tag.hasUUID("WorkerId") ? tag.getUUID("WorkerId") : null;
            UUID eventId = tag.hasUUID("EventId") ? tag.getUUID("EventId") : null;
            ShelterMode mode = ShelterMode.valueOf(tag.getString("Mode"));
            BlockPos center = new BlockPos(tag.getInt("CenterX"), tag.getInt("CenterY"), tag.getInt("CenterZ"));
            int wallHeight = tag.getInt("WallHeight");
            Direction facing = Direction.byName(tag.getString("Facing"));
            if (facing == null) facing = Direction.NORTH;

            EmergencyShelterPlan plan = generateShelterPlan(center, mode, facing, wallHeight);
            ShelterConstructionState state = new ShelterConstructionState(workerId, eventId, plan, facing);
            state.nextIndex = tag.getInt("NextIndex");
            state.isHalted = tag.getBoolean("IsHalted");
            state.isComplete = tag.getBoolean("IsComplete");
            if (tag.contains("HaltReason")) {
                state.haltReason = Optional.of(tag.getString("HaltReason"));
            }

            if (tag.contains("PlacedPositions", Tag.TAG_LIST)) {
                ListTag list = tag.getList("PlacedPositions", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag pTag = list.getCompound(i);
                    state.placedPositions.add(new BlockPos(pTag.getInt("X"), pTag.getInt("Y"), pTag.getInt("Z")));
                }
            }
            return state;
        }

        public record StepResult(
                WorkerStorage updatedStorage,
                boolean placedBlock,
                boolean complete,
                boolean halted,
                String message
        ) {}

        public StepResult stepPlacement(Level level, WorkerStorage storage, Collection<ItemStack> reservedStacks, Mob worker) {
            return stepPlacement(level, storage, reservedStacks, worker, null, null);
        }

        public StepResult stepPlacement(Level level, WorkerStorage storage, Collection<ItemStack> reservedStacks, Mob worker,
                                        java.util.function.BiPredicate<BlockPos, BlockState> customPlacer) {
            return stepPlacement(level, storage, reservedStacks, worker, customPlacer, null);
        }

        /**
         * Steps construction by placing the next required shelter block.
         * Enforces collision safety, chunk-safe boundaries, material extraction, and safe halting if materials run out.
         */
        public StepResult stepPlacement(Level level, WorkerStorage storage, Collection<ItemStack> reservedStacks, Mob worker,
                                        java.util.function.BiPredicate<BlockPos, BlockState> customPlacer,
                                        java.util.function.Predicate<BlockPos> chunkLoadedPredicate) {
            if (plan == null) {
                return new StepResult(storage, false, false, true, "Empty shelter plan");
            }
            if (isComplete) {
                return new StepResult(storage, false, true, false, "Construction already complete");
            }
            if (isHalted) {
                if ("WAITING_FOR_CHUNK".equals(haltReason.orElse(""))) {
                    // Re-check chunk status at nextIndex before remaining halted
                    if (plan != null && nextIndex < plan.placements().size()) {
                        BlockPos nextTarget = plan.placements().get(nextIndex).pos();
                        boolean nowLoaded = (chunkLoadedPredicate != null) ? chunkLoadedPredicate.test(nextTarget)
                                : (level != null && level.hasChunkAt(nextTarget));
                        if (nowLoaded) {
                            isHalted = false;
                            haltReason = Optional.empty();
                        } else {
                            return new StepResult(storage, false, false, true, "Target position " + nextTarget + " is in an unloaded chunk; waiting for chunk");
                        }
                    } else {
                        isHalted = false;
                        haltReason = Optional.empty();
                    }
                } else {
                    return new StepResult(storage, false, false, true, haltReason.orElse("Construction halted"));
                }
            }

            while (nextIndex < plan.placements().size()) {
                ShelterBlockPlacement placement = plan.placements().get(nextIndex);
                BlockPos targetPos = placement.pos();

                // Safety 1: Collision space preservation
                if (isInsideWorkerCollision(targetPos, worker, plan.center())) {
                    nextIndex++;
                    continue;
                }

                // Safety 2: Check world bounds
                if (level != null && !level.isInWorldBounds(targetPos)) {
                    nextIndex++;
                    continue;
                }

                // Safety 3: Chunk-safe check (verify chunk is loaded without force-loading)
                boolean isChunkLoaded = true;
                if (chunkLoadedPredicate != null) {
                    isChunkLoaded = chunkLoadedPredicate.test(targetPos);
                } else if (level != null) {
                    isChunkLoaded = level.hasChunkAt(targetPos);
                }

                if (!isChunkLoaded) {
                    isHalted = true;
                    haltReason = Optional.of("WAITING_FOR_CHUNK");
                    return new StepResult(storage, false, false, true, "Target position " + targetPos + " is in an unloaded chunk; waiting for chunk");
                }

                // Safety 4: Check if world block is already present and solid
                if (level != null) {
                    BlockState existingState = level.getBlockState(targetPos);
                    if (existingState.blocksMotion() && !existingState.canBeReplaced()) {
                        placedPositions.add(targetPos);
                        nextIndex++;
                        if (nextIndex >= plan.placements().size()) {
                            isComplete = true;
                        }
                        continue;
                    }
                }

                // Try to extract 1 building block from WorkerStorage
                Optional<MaterialExtractionResult> extraction = extractBuildingBlock(storage, reservedStacks);
                if (extraction.isEmpty()) {
                    isHalted = true;
                    haltReason = Optional.of("Insufficient building materials in WorkerStorage");
                    return new StepResult(storage, false, false, true, haltReason.get());
                }

                MaterialExtractionResult result = extraction.get();
                WorkerStorage updatedStorage = result.updatedStorage();
                ItemStack blockStack = result.extractedBlock();

                boolean placed = false;
                try {
                    if (customPlacer != null) {
                        if (blockStack.getItem() instanceof BlockItem blockItem) {
                            placed = customPlacer.test(targetPos, blockItem.getBlock().defaultBlockState());
                        } else {
                            placed = false;
                        }
                    } else if (level != null) {
                        if (blockStack.getItem() instanceof BlockItem blockItem) {
                            placed = level.setBlock(targetPos, blockItem.getBlock().defaultBlockState(), 3);
                        } else {
                            placed = false;
                        }
                    } else {
                        placed = true;
                    }
                } catch (Exception e) {
                    ExampleMod.LOGGER.warn("Recoverable exception during shelter block placement at {}: {}", targetPos, e.getMessage());
                    placed = false;
                }

                if (!placed) {
                    // Do not advance progress or consume materials on failed placement
                    return new StepResult(storage, false, false, false, "Failed to place block at " + targetPos);
                }

                placedPositions.add(targetPos);
                nextIndex++;

                if (nextIndex >= plan.placements().size()) {
                    isComplete = true;
                }

                return new StepResult(updatedStorage, true, isComplete, false, "Placed block at " + targetPos);
            }

            isComplete = true;
            return new StepResult(storage, false, true, false, "Construction complete");
        }

        /**
         * Safely resumes construction after new materials have been supplied.
         * Preserves existing placed positions without tearing down or duplicating blocks.
         */
        public void resumeWithMaterials() {
            isHalted = false;
            haltReason = Optional.empty();
        }
    }
}
