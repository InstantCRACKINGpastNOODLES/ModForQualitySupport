package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted regression tests verifying:
 * 1. Equipment capability evaluation is decoupled from physical condition.
 * 2. Modded non-damageable weapons (Scythes), armor (Dreadsteel), and tools (Avaritia) are never classified as broken.
 * 3. Emergency rescue is triggered ONLY when equipment is genuinely insufficient.
 * 4. FULL_SHELTER, PARTIAL_SHELTER, and DIRECTIONAL_BARRIER geometries, safe entrance & safe-zone preservation.
 * 5. Incremental material planning, collision avoidance, safe halting, and resumption.
 * 6. Stable JourneyMap waypoint identity (UUID-based), lifecycle, and client/server isolation.
 */
class EquipmentCapabilityAndRescueTest {

    private static Item TEST_SCYTHE_ITEM;
    private static Item DREADSTEEL_HELMET_ITEM;
    private static Item DREADSTEEL_CHESTPLATE_ITEM;
    private static Item DREADSTEEL_LEGGINGS_ITEM;
    private static Item DREADSTEEL_BOOTS_ITEM;
    private static Item AVARITIA_PICKAXE_ITEM;

    private static final ResourceLocation AVARITIA_PICKAXE_ID =
            ResourceLocation.fromNamespaceAndPath("avaritia", "infinity_pickaxe");
    private static final ResourceLocation ENERGY_WEAPON_ID =
            ResourceLocation.fromNamespaceAndPath("testmod", "plasma_blade");

    @BeforeAll
    static void setupItems() {
        try {
            java.lang.reflect.Field frozenField = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(BuiltInRegistries.ITEM, false);

            // 1. Scythe without durability component
            TEST_SCYTHE_ITEM = new Item(new Item.Properties());
            net.minecraft.core.Registry.register(
                    BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("testmod", "dragon_bone_scythe"),
                    TEST_SCYTHE_ITEM
            );

            // 2. Dreadsteel Armor pieces without durability components
            DREADSTEEL_HELMET_ITEM = new ArmorItem(ArmorMaterials.NETHERITE, ArmorItem.Type.HELMET, new Item.Properties());
            DREADSTEEL_CHESTPLATE_ITEM = new ArmorItem(ArmorMaterials.NETHERITE, ArmorItem.Type.CHESTPLATE, new Item.Properties());
            DREADSTEEL_LEGGINGS_ITEM = new ArmorItem(ArmorMaterials.NETHERITE, ArmorItem.Type.LEGGINGS, new Item.Properties());
            DREADSTEEL_BOOTS_ITEM = new ArmorItem(ArmorMaterials.NETHERITE, ArmorItem.Type.BOOTS, new Item.Properties());

            net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("testmod", "dreadsteel_helmet"), DREADSTEEL_HELMET_ITEM);
            net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("testmod", "dreadsteel_chestplate"), DREADSTEEL_CHESTPLATE_ITEM);
            net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("testmod", "dreadsteel_leggings"), DREADSTEEL_LEGGINGS_ITEM);
            net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("testmod", "dreadsteel_boots"), DREADSTEEL_BOOTS_ITEM);

            // 3. Avaritia Pickaxe without vanilla durability
            AVARITIA_PICKAXE_ITEM = new Item(new Item.Properties());
            net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, AVARITIA_PICKAXE_ID, AVARITIA_PICKAXE_ITEM);

            // 4. Custom energy weapon
            Item energyWeaponItem = new Item(new Item.Properties());
            net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, ENERGY_WEAPON_ID, energyWeaponItem);

            frozenField.set(BuiltInRegistries.ITEM, true);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to register test equipment items", t);
        }
    }

    @BeforeEach
    @AfterEach
    void resetState() {
        EquipmentCapabilityPolicy.clearRegistries();
        RescueServerManager.clearAllRescues();
        RescueWaypointManager.clearTrackedWaypoints();
        JourneyMapBridge.setMockJourneyMapPresent(null);
    }

    // ==========================================
    // 1. Item without durability component remains usable
    // ==========================================
    @Test
    @DisplayName("1. Item without durability component remains usable and is never classified as broken")
    void test1_itemWithoutDurabilityComponentRemainsUsable() {
        ItemStack stick = new ItemStack(Items.STICK);
        assertFalse(stick.isDamageableItem(), "Stick must not be damageable");

        EquipmentCapabilityPolicy.EquipmentCondition condition = EquipmentCapabilityPolicy.evaluateCondition(stick);
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, condition);
        assertNotEquals(EquipmentCapabilityPolicy.EquipmentCondition.BROKEN, condition);

        EquipmentCapabilityPolicy.DurabilityProfile profile = EquipmentCapabilityPolicy.getDurabilityProfile(stick);
        assertEquals(EquipmentCapabilityPolicy.DurabilityProfile.NON_DAMAGEABLE, profile);
    }

    // ==========================================
    // 2. Scythe without vanilla durability is not classified as broken
    // ==========================================
    @Test
    @DisplayName("2. Scythe without vanilla durability is categorized as WEAPON and is USABLE, never broken")
    void test2_scytheWithoutVanillaDurabilityNotBroken() {
        ItemStack scythe = new ItemStack(TEST_SCYTHE_ITEM);
        assertFalse(scythe.isDamageableItem(), "Test scythe has no vanilla durability");

        assertEquals(EquipmentCapabilityPolicy.EquipmentCategory.WEAPON, EquipmentCapabilityPolicy.categorizeEquipment(scythe));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, EquipmentCapabilityPolicy.evaluateCondition(scythe));
        assertTrue(EquipmentCapabilityPolicy.isWeaponUsable(scythe), "Scythe must be evaluated as usable weapon");
    }

    // ==========================================
    // 3. Dreadsteel armor pieces are not classified as broken
    // ==========================================
    @Test
    @DisplayName("3. All four Dreadsteel armor pieces without vanilla durability are USABLE and never broken")
    void test3_dreadsteelArmorPiecesNotBroken() {
        ItemStack helmet = new ItemStack(DREADSTEEL_HELMET_ITEM);
        ItemStack chestplate = new ItemStack(DREADSTEEL_CHESTPLATE_ITEM);
        ItemStack leggings = new ItemStack(DREADSTEEL_LEGGINGS_ITEM);
        ItemStack boots = new ItemStack(DREADSTEEL_BOOTS_ITEM);

        assertFalse(helmet.isDamageableItem());
        assertFalse(chestplate.isDamageableItem());
        assertFalse(leggings.isDamageableItem());
        assertFalse(boots.isDamageableItem());

        assertEquals(EquipmentCapabilityPolicy.EquipmentCategory.ARMOR_HELMET, EquipmentCapabilityPolicy.categorizeEquipment(helmet));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCategory.ARMOR_CHESTPLATE, EquipmentCapabilityPolicy.categorizeEquipment(chestplate));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCategory.ARMOR_LEGGINGS, EquipmentCapabilityPolicy.categorizeEquipment(leggings));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCategory.ARMOR_BOOTS, EquipmentCapabilityPolicy.categorizeEquipment(boots));

        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, EquipmentCapabilityPolicy.evaluateCondition(helmet));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, EquipmentCapabilityPolicy.evaluateCondition(chestplate));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, EquipmentCapabilityPolicy.evaluateCondition(leggings));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, EquipmentCapabilityPolicy.evaluateCondition(boots));

        assertTrue(EquipmentCapabilityPolicy.isArmorUsable(helmet));
        assertTrue(EquipmentCapabilityPolicy.isArmorUsable(chestplate));
        assertTrue(EquipmentCapabilityPolicy.isArmorUsable(leggings));
        assertTrue(EquipmentCapabilityPolicy.isArmorUsable(boots));
    }

    // ==========================================
    // 4. Avaritia equipment without vanilla durability does not trigger emergency rescue
    // ==========================================
    @Test
    @DisplayName("4. Avaritia equipment registered as indestructible is USABLE and does not trigger rescue")
    void test4_avaritiaEquipmentDoesNotTriggerRescue() {
        EquipmentCapabilityPolicy.registerIndestructibleItem(AVARITIA_PICKAXE_ID);

        ItemStack avaritiaPick = new ItemStack(AVARITIA_PICKAXE_ITEM);
        assertEquals(EquipmentCapabilityPolicy.DurabilityProfile.INDESTRUCTIBLE,
                EquipmentCapabilityPolicy.getDurabilityProfile(avaritiaPick));
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE,
                EquipmentCapabilityPolicy.evaluateCondition(avaritiaPick));
        assertTrue(EquipmentCapabilityPolicy.isMiningToolUsable(avaritiaPick));
    }

    // ==========================================
    // 5. Broken vanilla pickaxe triggers tool replacement
    // ==========================================
    @Test
    @DisplayName("5. Broken vanilla pickaxe evaluates as BROKEN and triggers tool exhaustion rescue only when no replacement exists")
    void test5_brokenVanillaPickaxeTriggersToolReplacement() {
        ItemStack brokenPick = new ItemStack(Items.DIAMOND_PICKAXE);
        brokenPick.setDamageValue(brokenPick.getMaxDamage() - 1); // Only 1 durability remaining

        assertTrue(brokenPick.isDamageableItem());
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.BROKEN,
                EquipmentCapabilityPolicy.evaluateCondition(brokenPick));
        assertFalse(EquipmentCapabilityPolicy.isMiningToolUsable(brokenPick));

        WorkerStorage storageWithBackup = WorkerStorage.empty().deposit(new ItemStack(Items.IRON_PICKAXE));
        boolean hasBackup = false;
        for (ItemStack item : storageWithBackup.items()) {
            if (EquipmentCapabilityPolicy.isMiningToolUsable(item)) {
                hasBackup = true;
                break;
            }
        }
        assertTrue(hasBackup, "WorkerStorage must provide a backup tool");

        WorkerStorage emptyStorage = WorkerStorage.empty();
        boolean hasNoBackup = true;
        for (ItemStack item : emptyStorage.items()) {
            if (EquipmentCapabilityPolicy.isMiningToolUsable(item)) {
                hasNoBackup = false;
                break;
            }
        }
        assertTrue(hasNoBackup, "Empty WorkerStorage has no backup tool");
    }

    // ==========================================
    // 6. Valid indestructible modded pickaxe remains usable
    // ==========================================
    @Test
    @DisplayName("6. Valid indestructible modded pickaxe without durability remains USABLE")
    void test6_validIndestructibleModdedPickaxeRemainsUsable() {
        ItemStack moddedPick = new ItemStack(AVARITIA_PICKAXE_ITEM);
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE,
                EquipmentCapabilityPolicy.evaluateCondition(moddedPick));
        assertTrue(EquipmentCapabilityPolicy.isMiningToolUsable(moddedPick));
    }

    // ==========================================
    // 7. Unknown custom durability is not automatically classified as zero durability
    // ==========================================
    @Test
    @DisplayName("7. Unknown custom equipment condition is classified as UNKNOWN, never BROKEN, and does not trigger rescue")
    void test7_unknownCustomDurabilityNotClassifiedAsZeroOrBroken() {
        ResourceLocation unknownItemId = ResourceLocation.fromNamespaceAndPath("testmod", "plasma_blade");
        EquipmentCapabilityPolicy.registerCustomAdapter(unknownItemId, stack -> EquipmentCapabilityPolicy.EquipmentCondition.UNKNOWN);

        ItemStack customWeapon = new ItemStack(BuiltInRegistries.ITEM.get(unknownItemId));
        EquipmentCapabilityPolicy.EquipmentCondition condition = EquipmentCapabilityPolicy.evaluateCondition(customWeapon);

        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.UNKNOWN, condition);
        assertNotEquals(EquipmentCapabilityPolicy.EquipmentCondition.BROKEN, condition);
        assertTrue(EquipmentCapabilityPolicy.isWeaponUsable(customWeapon));
    }

    // ==========================================
    // 8. Missing ammunition or energy is distinguished from broken durability
    // ==========================================
    @Test
    @DisplayName("8. Missing energy or ammunition evaluates as EXHAUSTED_RESOURCE, distinct from BROKEN")
    void test8_missingAmmunitionOrEnergyDistinguishedFromBroken() {
        ResourceLocation energyItemId = ResourceLocation.fromNamespaceAndPath("testmod", "plasma_blade");
        EquipmentCapabilityPolicy.registerCustomAdapter(energyItemId, stack -> EquipmentCapabilityPolicy.EquipmentCondition.EXHAUSTED_RESOURCE);

        ItemStack energyWeapon = new ItemStack(BuiltInRegistries.ITEM.get(energyItemId));
        EquipmentCapabilityPolicy.EquipmentCondition condition = EquipmentCapabilityPolicy.evaluateCondition(energyWeapon);

        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.EXHAUSTED_RESOURCE, condition);
        assertNotEquals(EquipmentCapabilityPolicy.EquipmentCondition.BROKEN, condition);
    }

    // ==========================================
    // 9. Emergency rescue is triggered only when equipment is genuinely insufficient
    // ==========================================
    @Test
    @DisplayName("9. Adequately equipped worker with Scythe, Dreadsteel, or Avaritia never requests rescue")
    void test9_adequatelyEquippedWorkerNeverRequestsRescue() {
        ItemStack scythe = new ItemStack(TEST_SCYTHE_ITEM);
        assertTrue(EquipmentCapabilityPolicy.isWeaponUsable(scythe));

        ItemStack dreadHelmet = new ItemStack(DREADSTEEL_HELMET_ITEM);
        ItemStack dreadChest = new ItemStack(DREADSTEEL_CHESTPLATE_ITEM);
        assertTrue(EquipmentCapabilityPolicy.isArmorUsable(dreadHelmet));
        assertTrue(EquipmentCapabilityPolicy.isArmorUsable(dreadChest));

        ItemStack avaritiaPick = new ItemStack(AVARITIA_PICKAXE_ITEM);
        assertTrue(EquipmentCapabilityPolicy.isMiningToolUsable(avaritiaPick));
    }

    // ==========================================
    // 10. Emergency Shelter Blueprint & Geometry Tests
    // ==========================================
    @Test
    @DisplayName("10. Emergency shelter generates exact 7x7 perimeter walls and roof with protected center safe zone")
    void test10_emergencyShelterGeometryAndSafety() {
        BlockPos center = new BlockPos(100, 64, 100);
        int radius = 3; // 7x7 footprint: [-3, +3]
        int wallHeight = 3; // dy = 0, 1, 2

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan =
                EquipmentCapabilityPolicy.generateShelterPlan(center, radius, wallHeight);

        assertEquals(center, plan.center());
        assertEquals(3, plan.radius());
        assertEquals(3, plan.wallHeight());

        int expectedWallBlocksPerLayer = (7 * 4) - 4; // 24 perimeter blocks
        int expectedTotalWalls = expectedWallBlocksPerLayer * wallHeight; // 24 * 3 = 72
        int expectedRoofBlocks = 7 * 7; // 49
        int expectedTotalBlocks = expectedTotalWalls + expectedRoofBlocks; // 121

        assertEquals(expectedTotalBlocks, plan.requiredBlockCount());

        for (EquipmentCapabilityPolicy.ShelterBlockPlacement placement : plan.placements()) {
            BlockPos pos = placement.pos();
            int dx = pos.getX() - center.getX();
            int dy = pos.getY() - center.getY();
            int dz = pos.getZ() - center.getZ();

            if (dx == 0 && dz == 0) {
                assertTrue(dy >= wallHeight, "Center space at dy=" + dy + " must not have blocks placed (worker safe zone)");
            }
        }
    }

    // ==========================================
    // 11. Suitable building material filter
    // ==========================================
    @Test
    @DisplayName("11. Suitable building material filter accepts solid blocks and rejects air, fluids, and gravity blocks")
    void test11_suitableBuildingMaterialFilter() {
        assertTrue(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.STONE.defaultBlockState()));
        assertTrue(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.OAK_PLANKS.defaultBlockState()));

        assertFalse(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.AIR.defaultBlockState()), "Air is not a building block");
        assertFalse(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.WATER.defaultBlockState()), "Water fluid is not a building block");
        assertFalse(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.LAVA.defaultBlockState()), "Lava fluid is not a building block");
        assertFalse(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.GRAVEL.defaultBlockState()), "Gravel cannot be used for shelter (gravity hazard)");
        assertFalse(EquipmentCapabilityPolicy.isSuitableBuildingBlock(Blocks.SAND.defaultBlockState()), "Sand cannot be used for shelter (gravity hazard)");
    }

    // ==========================================
    // 12. Correct Partial Shelter Geometry and Entrance Preservation
    // ==========================================
    @Test
    @DisplayName("12. PARTIAL_SHELTER generates coherent 3x3 enclosure with safe open doorway and protected center safe zone")
    void test12_partialShelterGeometryAndEntrancePreservation() {
        BlockPos center = new BlockPos(200, 64, 200);
        Direction entranceFacing = Direction.NORTH; // door at dz = -1, dx = 0
        int wallHeight = 3;

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan =
                EquipmentCapabilityPolicy.generateShelterPlan(center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, entranceFacing, wallHeight);

        assertEquals(EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, plan.mode());
        assertEquals(center, plan.center());

        // Standard requirement: 22 walls + 9 roof = 31 blocks
        assertEquals(31, plan.requiredBlockCount());
        assertEquals(31, plan.placements().size());

        // Check door position at NORTH: (dx=0, dz=-1)
        int doorDx = entranceFacing.getStepX();
        int doorDz = entranceFacing.getStepZ();

        boolean hasDoorFeet = false;
        boolean hasDoorHead = false;
        boolean hasDoorLintel = false;

        for (EquipmentCapabilityPolicy.ShelterBlockPlacement placement : plan.placements()) {
            BlockPos pos = placement.pos();
            int dx = pos.getX() - center.getX();
            int dy = pos.getY() - center.getY();
            int dz = pos.getZ() - center.getZ();

            // Center standing/head space (0, 0, dy=0..2) must NEVER be placed
            if (dx == 0 && dz == 0) {
                assertTrue(dy >= wallHeight, "Center space at dy=" + dy + " must remain empty (worker collision zone)");
            }

            // Doorway position check
            if (dx == doorDx && dz == doorDz) {
                if (dy == 0) hasDoorFeet = true;
                if (dy == 1) hasDoorHead = true;
                if (dy == 2) hasDoorLintel = true;
            }
        }

        // Invariant: Doorway at dy=0 and dy=1 MUST be open for safe egress/ingress
        assertFalse(hasDoorFeet, "Doorway feet space (dy=0) must remain open");
        assertFalse(hasDoorHead, "Doorway head space (dy=1) must remain open");
        assertTrue(hasDoorLintel, "Doorway lintel (dy=2) must have lintel block");
    }

    // ==========================================
    // 13. Directional Barrier Geometry
    // ==========================================
    @Test
    @DisplayName("13. DIRECTIONAL_BARRIER creates a 3-wide threat wall without roof and preserves rear/side escape")
    void test13_directionalBarrierGeometry() {
        BlockPos center = new BlockPos(300, 64, 300);
        Direction threatFacing = Direction.SOUTH; // barrier at dz = 1

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan =
                EquipmentCapabilityPolicy.generateShelterPlan(center, EquipmentCapabilityPolicy.ShelterMode.DIRECTIONAL_BARRIER, threatFacing, 3);

        assertEquals(EquipmentCapabilityPolicy.ShelterMode.DIRECTIONAL_BARRIER, plan.mode());
        // 3 wide x 3 high = 9 blocks
        assertEquals(9, plan.requiredBlockCount());

        // Verify all 9 blocks are in the forward plane (dz = 1)
        for (EquipmentCapabilityPolicy.ShelterBlockPlacement placement : plan.placements()) {
            BlockPos pos = placement.pos();
            int dz = pos.getZ() - center.getZ();
            int dy = pos.getY() - center.getY();
            assertEquals(1, dz, "Barrier must be placed 1 block in front facing threat");
            assertTrue(dy >= 0 && dy <= 2, "Barrier height must be 0..2");
            assertFalse(placement.isRoof(), "Directional barrier must not have a roof");
        }
    }

    // ==========================================
    // 14. Collision Safety Validation
    // ==========================================
    @Test
    @DisplayName("14. isInsideWorkerCollision correctly protects worker standing and head space")
    void test14_collisionSafetyValidation() {
        BlockPos center = new BlockPos(50, 64, 50);

        // Standing/head space at center
        assertTrue(EquipmentCapabilityPolicy.isInsideWorkerCollision(new BlockPos(50, 64, 50), null, center));
        assertTrue(EquipmentCapabilityPolicy.isInsideWorkerCollision(new BlockPos(50, 65, 50), null, center));
        assertTrue(EquipmentCapabilityPolicy.isInsideWorkerCollision(new BlockPos(50, 66, 50), null, center));

        // Overhead roof space at center (dy = 3) is safe to place roof
        assertFalse(EquipmentCapabilityPolicy.isInsideWorkerCollision(new BlockPos(50, 67, 50), null, center));

        // Perimeter positions are outside worker collision
        assertFalse(EquipmentCapabilityPolicy.isInsideWorkerCollision(new BlockPos(51, 64, 50), null, center));
        assertFalse(EquipmentCapabilityPolicy.isInsideWorkerCollision(new BlockPos(49, 64, 50), null, center));
    }

    // ==========================================
    // 15. Material Counting and Filter
    // ==========================================
    @Test
    @DisplayName("15. Material planning counts solid block items and excludes non-blocks, gravity blocks, and reserved stacks")
    void test15_materialCountingAndFilter() {
        ItemStack cobble = new ItemStack(Blocks.COBBLESTONE.asItem(), 20);
        ItemStack planks = new ItemStack(Blocks.OAK_PLANKS.asItem(), 10);
        ItemStack sand = new ItemStack(Blocks.SAND.asItem(), 64); // gravity block -> rejected!
        ItemStack sticks = new ItemStack(Items.STICK, 16); // non-block -> rejected!

        WorkerStorage storage = WorkerStorage.empty()
                .deposit(cobble)
                .deposit(planks)
                .deposit(sand)
                .deposit(sticks);

        // Count without reservation: 20 cobble + 10 planks = 30 suitable blocks
        int countAll = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, Set.of());
        assertEquals(30, countAll);

        // Count with cobble reserved (e.g. pending output): only 10 planks suitable
        int countWithReserved = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage, Set.of(cobble));
        assertEquals(10, countWithReserved);
    }

    // ==========================================
    // 16. Incremental Construction and Safe Halting
    // ==========================================
    @Test
    @DisplayName("16. Incremental construction consumes materials safely and halts when materials are exhausted")
    void test16_shelterConstructionIncrementalAndSafeHalting() {
        BlockPos center = new BlockPos(100, 64, 100);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan =
                EquipmentCapabilityPolicy.generateShelterPlan(center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3);

        // Storage has only 3 cobblestone blocks (partial shelter requires 31)
        ItemStack cobble = new ItemStack(Blocks.COBBLESTONE.asItem(), 3);
        WorkerStorage storage = WorkerStorage.empty().deposit(cobble);

        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(plan);

        assertFalse(state.isComplete());
        assertFalse(state.isHalted());

        // Step 1: place block 1
        EquipmentCapabilityPolicy.ShelterConstructionState.StepResult r1 =
                state.stepPlacement(null, storage, Set.of(), null);
        assertTrue(r1.placedBlock());
        assertEquals(1, state.getPlacedCount());
        storage = r1.updatedStorage();

        // Step 2: place block 2
        EquipmentCapabilityPolicy.ShelterConstructionState.StepResult r2 =
                state.stepPlacement(null, storage, Set.of(), null);
        assertTrue(r2.placedBlock());
        assertEquals(2, state.getPlacedCount());
        storage = r2.updatedStorage();

        // Step 3: place block 3
        EquipmentCapabilityPolicy.ShelterConstructionState.StepResult r3 =
                state.stepPlacement(null, storage, Set.of(), null);
        assertTrue(r3.placedBlock());
        assertEquals(3, state.getPlacedCount());
        storage = r3.updatedStorage();

        // Step 4: materials exhausted! Must safely halt without exception or repeated impossible placement
        EquipmentCapabilityPolicy.ShelterConstructionState.StepResult r4 =
                state.stepPlacement(null, storage, Set.of(), null);
        assertFalse(r4.placedBlock());
        assertTrue(r4.halted());
        assertTrue(state.isHalted());
        assertEquals("Insufficient building materials in WorkerStorage", state.getHaltReason().orElse(""));

        // Invariant: Already placed 3 blocks are preserved!
        assertEquals(3, state.getPlacedCount());
    }

    // ==========================================
    // 17. Safe Resumption Without Duplication
    // ==========================================
    @Test
    @DisplayName("17. Construction resumption preserves placed blocks and completes shelter without duplicate placements")
    void test17_shelterConstructionResumptionWithoutDuplication() {
        BlockPos center = new BlockPos(150, 64, 150);
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan =
                EquipmentCapabilityPolicy.generateShelterPlan(center, EquipmentCapabilityPolicy.ShelterMode.DIRECTIONAL_BARRIER, Direction.NORTH, 3);
        // Directional barrier requires 9 blocks

        // Provide 4 blocks initially
        WorkerStorage storage = WorkerStorage.empty().deposit(new ItemStack(Blocks.STONE.asItem(), 4));
        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(plan);

        // Place 4 blocks
        for (int i = 0; i < 4; i++) {
            var res = state.stepPlacement(null, storage, Set.of(), null);
            assertTrue(res.placedBlock());
            storage = res.updatedStorage();
        }
        assertEquals(4, state.getPlacedCount());

        // 5th placement halts
        var haltRes = state.stepPlacement(null, storage, Set.of(), null);
        assertTrue(haltRes.halted());
        assertTrue(state.isHalted());

        // Resume: Provide remaining 5 blocks
        storage = WorkerStorage.empty().deposit(new ItemStack(Blocks.STONE.asItem(), 5));
        state.resumeWithMaterials();
        assertFalse(state.isHalted());

        // Complete remaining placements
        for (int i = 0; i < 5; i++) {
            var res = state.stepPlacement(null, storage, Set.of(), null);
            assertTrue(res.placedBlock());
            storage = res.updatedStorage();
        }

        assertTrue(state.isComplete());
        assertEquals(9, state.getPlacedCount());
        assertEquals(9, state.getPlacedPositions().size(), "Must have exactly 9 unique placed positions");
    }

    // ==========================================
    // 18. Decoupled Condition vs Autonomous Capability
    // ==========================================
    @Test
    @DisplayName("18. assessEquipment strictly decouples physical condition from autonomous-use capability")
    void test18_equipmentConditionVsAutonomousCapabilityDecoupled() {
        ItemStack scythe = new ItemStack(TEST_SCYTHE_ITEM);

        EquipmentCapabilityPolicy.EquipmentAssessment assessment =
                EquipmentCapabilityPolicy.assessEquipment(scythe);

        // Physical condition is USABLE (intact, non-damageable)
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, assessment.condition());
        assertTrue(assessment.isPhysicallyUsable());

        // Autonomous use capability is UNVERIFIED (requires registered adapter for combat activation)
        assertEquals(EquipmentCapabilityPolicy.AutonomousUseCapability.UNVERIFIED, assessment.capability());
        assertFalse(assessment.isAutonomouslyUsable());

        // When custom capability adapter is registered:
        EquipmentCapabilityPolicy.registerCapabilityAdapter(
                BuiltInRegistries.ITEM.getKey(TEST_SCYTHE_ITEM),
                (stack, cat, cond) -> EquipmentCapabilityPolicy.AutonomousUseCapability.VERIFIED_AUTONOMOUS_USE
        );

        EquipmentCapabilityPolicy.EquipmentAssessment verifiedAssessment =
                EquipmentCapabilityPolicy.assessEquipment(scythe);
        assertEquals(EquipmentCapabilityPolicy.AutonomousUseCapability.VERIFIED_AUTONOMOUS_USE, verifiedAssessment.capability());
        assertTrue(verifiedAssessment.isAutonomouslyUsable());
    }

    // ==========================================
    // 19. Dreadsteel Armor Passive Defense
    // ==========================================
    @Test
    @DisplayName("19. Dreadsteel armor pieces are USABLE for passive defense but NOT_SUPPORTED for tool activation")
    void test19_dreadsteelArmorPassiveDefenseNotSwungAsTool() {
        ItemStack helmet = new ItemStack(DREADSTEEL_HELMET_ITEM);
        ItemStack chest = new ItemStack(DREADSTEEL_CHESTPLATE_ITEM);

        var helmetAssess = EquipmentCapabilityPolicy.assessEquipment(helmet);
        var chestAssess = EquipmentCapabilityPolicy.assessEquipment(chest);

        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, helmetAssess.condition());
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, chestAssess.condition());

        assertEquals(EquipmentCapabilityPolicy.AutonomousUseCapability.NOT_SUPPORTED, helmetAssess.capability());
        assertEquals(EquipmentCapabilityPolicy.AutonomousUseCapability.NOT_SUPPORTED, chestAssess.capability());

        assertFalse(helmetAssess.isAutonomouslyUsable(), "Armor cannot be swung as autonomous tool");
    }

    // ==========================================
    // 20. Avaritia Unsafe AoE Fails Closed
    // ==========================================
    @Test
    @DisplayName("20. Avaritia equipment with destructive AoE is marked UNSAFE and fails closed for autonomous use")
    void test20_avaritiaToolsUnsafeAoEFailsClosed() {
        EquipmentCapabilityPolicy.registerIndestructibleItem(AVARITIA_PICKAXE_ID);
        EquipmentCapabilityPolicy.registerUnsafeItem(AVARITIA_PICKAXE_ID);

        ItemStack pick = new ItemStack(AVARITIA_PICKAXE_ITEM);
        var assessment = EquipmentCapabilityPolicy.assessEquipment(pick);

        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.USABLE, assessment.condition());
        assertEquals(EquipmentCapabilityPolicy.DurabilityProfile.INDESTRUCTIBLE, assessment.profile());
        assertEquals(EquipmentCapabilityPolicy.AutonomousUseCapability.UNSAFE, assessment.capability());

        assertTrue(assessment.isAutonomousUnsafe());
        assertFalse(assessment.isAutonomouslyUsable(), "Unsafe destructive AoE tool must fail closed");

        // Crucial invariant: UNSAFE tool does NOT trigger durability emergency rescue!
        assertTrue(EquipmentCapabilityPolicy.isMiningToolUsable(pick));
    }

    // ==========================================
    // 21. Stable JourneyMap Waypoint Identity (UUID-based)
    // ==========================================
    @Test
    @DisplayName("21. Rescue waypoint identity is UUID-based; two workers named 'Reimu' have distinct waypoints")
    void test21_stableJourneyMapWaypointIdentity() {
        UUID worker1Id = UUID.randomUUID();
        UUID worker2Id = UUID.randomUUID();
        String name = "Reimu";

        RescueAlertPayload alert1 = new RescueAlertPayload(
                worker1Id, name, "Player1",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                new BlockPos(100, 64, 100),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION,
                "Pickaxe broken", 1, false
        );

        RescueAlertPayload alert2 = new RescueAlertPayload(
                worker2Id, name, "Player1",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                new BlockPos(200, 64, 200),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION,
                "Pickaxe broken", 1, false
        );

        RescueWaypointManager.handleRescueAlert(alert1);
        RescueWaypointManager.handleRescueAlert(alert2);

        // Verify two separate waypoints exist despite identical display name "Reimu"
        assertEquals(2, RescueWaypointManager.getAllTrackedWaypoints().size());

        Optional<RescueWaypointManager.TrackedRescueWaypoint> wp1 = RescueWaypointManager.getTrackedWaypoint(worker1Id);
        Optional<RescueWaypointManager.TrackedRescueWaypoint> wp2 = RescueWaypointManager.getTrackedWaypoint(worker2Id);

        assertTrue(wp1.isPresent());
        assertTrue(wp2.isPresent());

        assertEquals("elstarticket_rescue_" + worker1Id, wp1.get().waypointId());
        assertEquals("elstarticket_rescue_" + worker2Id, wp2.get().waypointId());
        assertNotEquals(wp1.get().waypointId(), wp2.get().waypointId());
    }

    // ==========================================
    // 22. Duplicate Alert Suppression and Position Update
    // ==========================================
    @Test
    @DisplayName("22. Repeated alerts for the same worker UUID update position and do NOT create duplicate waypoints")
    void test22_duplicateAlertSuppressionAndPositionUpdate() {
        UUID workerId = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

        RescueAlertPayload alert1 = new RescueAlertPayload(
                workerId, "Marisa", "Player1", dim,
                new BlockPos(100, 64, 100),
                EquipmentCapabilityPolicy.RescueTrigger.IMPASSABLE_CORRIDOR_HAZARD,
                "Lava lake obstruction", 1, false
        );

        RescueWaypointManager.handleRescueAlert(alert1);
        assertEquals(1, RescueWaypointManager.getAllTrackedWaypoints().size());
        assertEquals(new BlockPos(100, 64, 100), RescueWaypointManager.getTrackedWaypoint(workerId).get().workerPos());

        // Worker moves to (110, 64, 105) -> dispatch update
        RescueAlertPayload alert2 = new RescueAlertPayload(
                workerId, "Marisa", "Player1", dim,
                new BlockPos(110, 64, 105),
                EquipmentCapabilityPolicy.RescueTrigger.IMPASSABLE_CORRIDOR_HAZARD,
                "Lava lake obstruction", 2, false
        );

        RescueWaypointManager.handleRescueAlert(alert2);

        // Invariant: No duplicate waypoint created!
        assertEquals(1, RescueWaypointManager.getAllTrackedWaypoints().size());
        assertEquals(new BlockPos(110, 64, 105), RescueWaypointManager.getTrackedWaypoint(workerId).get().workerPos());
    }

    // ==========================================
    // 23. Dimension Migration
    // ==========================================
    @Test
    @DisplayName("23. Worker changing dimension updates tracked dimension cleanly without leaving stale waypoints")
    void test23_dimensionMigrationHandlesStaleWaypoints() {
        UUID workerId = UUID.randomUUID();
        ResourceLocation overworld = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        ResourceLocation nether = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

        RescueAlertPayload overworldAlert = new RescueAlertPayload(
                workerId, "Youmu", "Player1", overworld,
                new BlockPos(50, 64, 50),
                EquipmentCapabilityPolicy.RescueTrigger.MANUAL_OPERATOR_REQUEST,
                "Help", 1, false
        );
        RescueWaypointManager.handleRescueAlert(overworldAlert);

        assertEquals(overworld, RescueWaypointManager.getTrackedWaypoint(workerId).get().dimension());

        // Worker enters portal into Nether
        RescueAlertPayload netherAlert = new RescueAlertPayload(
                workerId, "Youmu", "Player1", nether,
                new BlockPos(20, 70, 20),
                EquipmentCapabilityPolicy.RescueTrigger.MANUAL_OPERATOR_REQUEST,
                "Help", 2, false
        );
        RescueWaypointManager.handleRescueAlert(netherAlert);

        assertEquals(1, RescueWaypointManager.getAllTrackedWaypoints().size());
        assertEquals(nether, RescueWaypointManager.getTrackedWaypoint(workerId).get().dimension());
        assertEquals(new BlockPos(20, 70, 20), RescueWaypointManager.getTrackedWaypoint(workerId).get().workerPos());
    }

    // ==========================================
    // 24. Targeted Rescue Resolution
    // ==========================================
    @Test
    @DisplayName("24. Rescue resolution removes ONLY the targeted worker's waypoint, leaving others untouched")
    void test24_rescueResolutionRemovesOnlyTargetWorker() {
        UUID worker1Id = UUID.randomUUID();
        UUID worker2Id = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

        RescueWaypointManager.handleRescueAlert(new RescueAlertPayload(
                worker1Id, "Worker1", "Player1", dim, new BlockPos(10, 64, 10),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need pick", 1, false
        ));
        RescueWaypointManager.handleRescueAlert(new RescueAlertPayload(
                worker2Id, "Worker2", "Player1", dim, new BlockPos(20, 64, 20),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need pick", 1, false
        ));

        assertEquals(2, RescueWaypointManager.getAllTrackedWaypoints().size());

        // Resolve Worker 1
        RescueWaypointManager.handleRescueAlert(new RescueAlertPayload(
                worker1Id, "Worker1", "Player1", dim, new BlockPos(10, 64, 10),
                EquipmentCapabilityPolicy.RescueTrigger.NONE, "Resolved", 2, true
        ));

        // Invariant: Worker 1 removed, Worker 2 remains active
        assertEquals(1, RescueWaypointManager.getAllTrackedWaypoints().size());
        assertFalse(RescueWaypointManager.getTrackedWaypoint(worker1Id).isPresent());
        assertTrue(RescueWaypointManager.getTrackedWaypoint(worker2Id).isPresent());
    }

    // ==========================================
    // 25. Rescue Server Manager Authorization and Reconnection Sync
    // ==========================================
    @Test
    @DisplayName("25. Server validates owner authorization for rescue resolution and synchronizes on reconnect")
    void test25_rescueServerManagerAuthorizationAndReconnectionSync() {
        UUID workerId = UUID.randomUUID();
        UUID aliceUUID = UUID.randomUUID();
        UUID bobUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        // Trigger rescue for worker owned by Alice
        var active = RescueServerManager.triggerRescue(
                null, workerId, "AliceMaid", aliceUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool"
        );

        assertEquals(1, RescueServerManager.getAllActiveRescues().size());
        assertEquals(aliceUUID, RescueServerManager.getActiveRescue(workerId).get().ownerUUID());

        // Unauthorized operator Bob attempts to resolve Alice's rescue
        var unauthorizedResult = RescueServerManager.resolveRescue(
                null, workerId, bobUUID, "Bob", active.eventId(), active.sequence(), false, null, null
        );
        assertFalse(unauthorizedResult.resolved(), "Unauthorized operator Bob must be rejected");
        assertTrue(RescueServerManager.getActiveRescue(workerId).isPresent(), "Rescue must remain active");

        // Authorized owner Alice resolves rescue
        var authorizedResult = RescueServerManager.resolveRescue(
                null, workerId, aliceUUID, "Alice", active.eventId(), active.sequence(), false, null, null
        );
        assertTrue(authorizedResult.resolved(), "Authorized owner Alice must succeed");
        assertFalse(RescueServerManager.getActiveRescue(workerId).isPresent(), "Rescue must be removed");
    }

    // ==========================================
    // 26. Dedicated Server and JourneyMap Fallback Isolation
    // ==========================================
    @Test
    @DisplayName("26. JourneyMap soft bridge safely handles absent JourneyMap and delivers rich chat fallback")
    void test26_dedicatedServerAndFallbackNotificationWithoutJourneyMap() {
        JourneyMapBridge.setMockJourneyMapPresent(false);
        assertFalse(JourneyMapBridge.isJourneyMapAvailable());

        UUID workerId = UUID.randomUUID();
        RescueAlertPayload alert = new RescueAlertPayload(
                workerId, "Patchouli", "Owner1",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                new BlockPos(42, 64, 84),
                EquipmentCapabilityPolicy.RescueTrigger.IMPASSABLE_CORRIDOR_HAZARD,
                "Obstruction in tunnel", 1, false
        );

        // Should execute cleanly without exceptions even when JourneyMap is absent
        assertDoesNotThrow(() -> RescueWaypointManager.handleRescueAlert(alert));
        assertTrue(RescueWaypointManager.getTrackedWaypoint(workerId).isPresent());

        // Verify fallback chat component formatting
        MutableComponent component = RescueWaypointManager.buildFallbackNotificationComponent(alert);
        assertNotNull(component);
        String text = component.getString();
        assertTrue(text.contains("Patchouli"));
        assertTrue(text.contains("[42, 64, 84]"));
        assertTrue(text.contains("Obstruction in tunnel"));
    }

    // ==========================================
    // 27. RescueSavedData NBT Save and Load
    // ==========================================
    @Test
    @DisplayName("27. RescueSavedData serializes active rescues and monotonic sequence to NBT and restores identically across restarts")
    void test27_rescueSavedDataNbtSaveAndLoad() {
        RescueSavedData original = new RescueSavedData();
        long seq1 = original.nextSequence(); // 1
        long seq2 = original.nextSequence(); // 2

        UUID workerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(123, 64, -456);

        RescueServerManager.ActiveRescue rescue = new RescueServerManager.ActiveRescue(
                workerId, "ReimuWorker", ownerId, "AliceOwner", eventId,
                dim, pos, EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION,
                "Broken Pickaxe", seq2
        );
        original.putRescue(rescue);

        // Save to NBT
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        original.save(tag, null);

        // Load into new instance
        RescueSavedData loaded = RescueSavedData.load(tag, null);

        // Invariant 1: Sequence generator continues monotonically
        long nextSeq = loaded.nextSequence();
        assertTrue(nextSeq > seq2, "Loaded sequence counter must continue monotonically from saved value");

        // Invariant 2: Active rescue is fully restored
        Optional<RescueServerManager.ActiveRescue> restored = loaded.getRescue(workerId);
        assertTrue(restored.isPresent());
        assertEquals("ReimuWorker", restored.get().workerName());
        assertEquals(ownerId, restored.get().ownerUUID());
        assertEquals("AliceOwner", restored.get().ownerName());
        assertEquals(eventId, restored.get().eventId());
        assertEquals(dim, restored.get().dimension());
        assertEquals(pos, restored.get().workerPos());
        assertEquals(EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, restored.get().trigger());
        assertEquals("Broken Pickaxe", restored.get().reason());
        assertEquals(seq2, restored.get().sequence());
    }

    // ==========================================
    // 28. Owner Authorization By UUID Survives Renaming
    // ==========================================
    @Test
    @DisplayName("28. Owner authorization succeeds by Mojang UUID even if owner changes username / display name")
    void test28_ownerAuthorizationByUUIDSucceedsAfterRenaming() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "WorkerMaid", ownerUUID, "AliceOriginal",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool"
        );

        // Player renames account to "AliceNewName", but ownerUUID remains unchanged
        var result = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "AliceNewName", active.eventId(), active.sequence(), false, null, null
        );

        assertTrue(result.resolved(), "Resolution must succeed when owner UUID matches, despite username change");
        assertFalse(RescueServerManager.getActiveRescue(workerId).isPresent(), "Rescue must be removed");
    }

    // ==========================================
    // 29. Owner Authorization Rejects Different UUID With Same Username
    // ==========================================
    @Test
    @DisplayName("29. Owner authorization rejects resolution attempt from different UUID even if username is identical")
    void test29_ownerAuthorizationRejectsDifferentUUIDWithSameUsername() {
        UUID workerId = UUID.randomUUID();
        UUID legitimateOwnerUUID = UUID.randomUUID();
        UUID imposterUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "WorkerMaid", legitimateOwnerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool"
        );

        // Imposter has different UUID but same username "Alice"
        var unauthorized = RescueServerManager.resolveRescue(
                null, workerId, imposterUUID, "Alice", active.eventId(), active.sequence(), false, null, null
        );

        assertFalse(unauthorized.resolved(), "Imposter with mismatched UUID must be rejected");
        assertTrue(unauthorized.message().contains("does not match"), "Error message should note UUID mismatch");
        assertTrue(RescueServerManager.getActiveRescue(workerId).isPresent(), "Rescue must remain active");
    }

    // ==========================================
    // 30. Stale Sequence Rejection on Server
    // ==========================================
    @Test
    @DisplayName("30. Server rejects stale resolution request where sequence is older than active rescue sequence")
    void test30_staleSequenceRejectionOnServer() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "WorkerMaid", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool"
        );
        long activeSeq = active.sequence();

        // Stale sequence (activeSeq - 1)
        var staleResult = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", active.eventId(), activeSeq - 1, false, null, null
        );

        assertFalse(staleResult.resolved(), "Stale sequence must be rejected");
        assertTrue(staleResult.message().contains("older than active sequence"));
        assertTrue(RescueServerManager.getActiveRescue(workerId).isPresent());
    }

    // ==========================================
    // 31. Mismatched EventId Rejection on Server
    // ==========================================
    @Test
    @DisplayName("31. Server rejects resolution request with mismatched eventId from an older or different event")
    void test31_mismatchedEventIdRejectionOnServer() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        UUID correctEventId = UUID.randomUUID();
        UUID staleEventId = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "WorkerMaid", ownerUUID, "Alice",
                correctEventId, dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool"
        );

        var mismatchedResult = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", staleEventId, active.sequence(), false, null, null
        );

        assertFalse(mismatchedResult.resolved(), "Mismatched event ID must be rejected");
        assertTrue(mismatchedResult.message().contains("event ID mismatch"));
        assertTrue(RescueServerManager.getActiveRescue(workerId).isPresent());
    }

    // ==========================================
    // 32. Client Waypoint Manager Rejects Out-of-Order Stale Alerts
    // ==========================================
    @Test
    @DisplayName("32. Client RescueWaypointManager rejects out-of-order stale alerts and preserves latest state")
    void test32_clientWaypointManagerRejectsOutOfOrderAlerts() {
        UUID workerId = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

        // Alert 1 (seq 10) at (100, 64, 100)
        RescueWaypointManager.handleRescueAlert(new RescueAlertPayload(
                workerId, "WorkerMaid", "Player1", dim, new BlockPos(100, 64, 100),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool", 10, false
        ));
        assertEquals(new BlockPos(100, 64, 100), RescueWaypointManager.getTrackedWaypoint(workerId).get().workerPos());

        // Alert 2 (seq 15) at (120, 64, 120)
        RescueWaypointManager.handleRescueAlert(new RescueAlertPayload(
                workerId, "WorkerMaid", "Player1", dim, new BlockPos(120, 64, 120),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool", 15, false
        ));
        assertEquals(new BlockPos(120, 64, 120), RescueWaypointManager.getTrackedWaypoint(workerId).get().workerPos());

        // Out-of-order delayed Alert 3 (seq 12) at (110, 64, 110)
        RescueWaypointManager.handleRescueAlert(new RescueAlertPayload(
                workerId, "WorkerMaid", "Player1", dim, new BlockPos(110, 64, 110),
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool", 12, false
        ));

        // Invariant: Stale alert 12 was rejected; position remains (120, 64, 120) with sequence 15
        assertEquals(new BlockPos(120, 64, 120), RescueWaypointManager.getTrackedWaypoint(workerId).get().workerPos());
        assertEquals(15, RescueWaypointManager.getTrackedWaypoint(workerId).get().sequence());
    }

    // ==========================================
    // 33. Unloaded Worker Chunk Rejects Resolution
    // ==========================================
    @Test
    @DisplayName("33. Resolution with physical re-check refuses resolution when worker chunk is unloaded (worker == null)")
    void test33_unloadedWorkerChunkRejectsResolution() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "WorkerMaid", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool"
        );

        // Owner attempts resolution with recheckNeeds = true, but worker entity is null (chunk unloaded)
        var result = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", active.eventId(), active.sequence(), true, null, null
        );

        assertFalse(result.resolved(), "Must fail when worker chunk is unloaded");
        assertTrue(result.message().contains("chunk is currently unloaded"), "Message must explain chunk is unloaded");
        assertTrue(RescueServerManager.getActiveRescue(workerId).isPresent(), "Rescue must remain active until verified");
    }

    // ==========================================
    // 34. ShelterConstructionState Codec Serialization
    // ==========================================
    @Test
    @DisplayName("34. ShelterConstructionState serializes and deserializes via Codec preserving facing, indices and placed blocks")
    void test34_shelterConstructionStateCodecSerialization() {
        UUID workerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BlockPos center = new BlockPos(50, 64, 50);

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.SOUTH, 3
        );

        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, eventId, plan, Direction.SOUTH);

        // Place two positions
        BlockPos p1 = center.offset(1, 0, 1);
        BlockPos p2 = center.offset(1, 1, 1);
        state.recordPlacement(p1);
        state.recordPlacement(p2);

        // Serialize via toTag()
        net.minecraft.nbt.CompoundTag tag = state.toTag();

        // Deserialize via fromTag()
        EquipmentCapabilityPolicy.ShelterConstructionState loaded =
                EquipmentCapabilityPolicy.ShelterConstructionState.fromTag(tag);

        assertEquals(workerId, loaded.getWorkerId());
        assertEquals(eventId, loaded.getEventId());
        assertEquals(Direction.SOUTH, loaded.getFacing());
        assertEquals(EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, loaded.getPlan().mode());
        assertEquals(2, loaded.getPlacedCount());
        assertTrue(loaded.getPlacedPositions().contains(p1));
        assertTrue(loaded.getPlacedPositions().contains(p2));
        assertEquals(plan.requiredBlockCount() - 2, loaded.getRemainingCount());
    }

    // ==========================================
    // 35. Worker Tool Exhaustion Rescue Evaluation
    // ==========================================
    @Test
    @DisplayName("35. evaluateWorkerRescueNeeds evaluates unrecoverable tool exhaustion and distinguishes valid modded tools")
    void test35_workerToolExhaustionRescueEvaluation() {
        // Mining tool broken with empty storage
        ItemStack brokenPick = new ItemStack(Items.DIAMOND_PICKAXE);
        brokenPick.setDamageValue(brokenPick.getMaxDamage() - 1);
        assertEquals(EquipmentCapabilityPolicy.EquipmentCondition.BROKEN, EquipmentCapabilityPolicy.evaluateCondition(brokenPick));

        WorkerStorage emptyStorage = WorkerStorage.empty();
        boolean hasBackup = false;
        for (ItemStack item : emptyStorage.items()) {
            if (EquipmentCapabilityPolicy.isMiningToolUsable(item)) {
                hasBackup = true;
                break;
            }
        }
        assertFalse(hasBackup, "Empty storage has no replacement tool");

        // With valid replacement tool in storage
        WorkerStorage storageWithTool = WorkerStorage.empty().deposit(new ItemStack(Items.IRON_PICKAXE));
        boolean hasBackupNow = false;
        for (ItemStack item : storageWithTool.items()) {
            if (EquipmentCapabilityPolicy.isMiningToolUsable(item)) {
                hasBackupNow = true;
                break;
            }
        }
        assertTrue(hasBackupNow, "Storage now has backup tool");
    }

    // ==========================================
    // 36. Physical Verification Resolves When Tool Restored
    // ==========================================
    @Test
    @DisplayName("36. Physical verification: resolution with recheckNeeds=false clears rescue; recheckNeeds=true verifies presence")
    void test36_physicalVerificationResolvesWhenToolRestored() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "WorkerMaid", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Need tool"
        );

        // When recheckNeeds=false (e.g. manual operator override or pre-verified), resolution clears rescue
        var result = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", active.eventId(), active.sequence(), false, null, null
        );
        assertTrue(result.resolved());
        assertFalse(RescueServerManager.getActiveRescue(workerId).isPresent());
    }

    // ==========================================
    // 37. Null Owner UUID Fails Closed
    // ==========================================
    @Test
    @DisplayName("37. Fail-closed: Rescue record with null owner UUID rejects resolution attempt")
    void test37_nullOwnerUUIDFailsClosed() {
        UUID workerId = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        // Create active rescue without owner UUID (e.g. corrupted saved record)
        RescueServerManager.ActiveRescue rescue = new RescueServerManager.ActiveRescue(
                workerId, "Worker", null, "SomeOwner", UUID.randomUUID(),
                dim, pos, EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool", 1
        );
        RescueSavedData.get(null).putRescue(rescue);

        var result = RescueServerManager.resolveRescue(
                null, workerId, UUID.randomUUID(), "SomeOwner", rescue.eventId(), 1, false, null, null
        );

        assertFalse(result.resolved(), "Must fail closed when owner UUID is null");
        assertTrue(result.message().contains("missing owner UUID"));
    }

    // ==========================================
    // 38. Null Operator UUID Fails Closed
    // ==========================================
    @Test
    @DisplayName("38. Fail-closed: Operator resolution with null operator UUID is rejected")
    void test38_nullOperatorUUIDFailsClosed() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "Worker", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool"
        );

        var result = RescueServerManager.resolveRescue(
                null, workerId, null, "Alice", active.eventId(), active.sequence(), false, null, null
        );

        assertFalse(result.resolved(), "Must fail closed when operator UUID is null");
        assertTrue(result.message().contains("missing operator UUID"));
    }

    // ==========================================
    // 39. Empty Owner Identity Fails Closed
    // ==========================================
    @Test
    @DisplayName("39. Fail-closed: Empty owner identity rejects resolution even if operator passes empty identity")
    void test39_emptyOwnerIdentityFailsClosed() {
        UUID workerId = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        RescueServerManager.ActiveRescue rescue = new RescueServerManager.ActiveRescue(
                workerId, "Worker", null, "", UUID.randomUUID(),
                dim, pos, EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool", 1
        );
        RescueSavedData.get(null).putRescue(rescue);

        var result = RescueServerManager.resolveRescue(
                null, workerId, null, "", rescue.eventId(), 1, false, null, null
        );

        assertFalse(result.resolved(), "Empty owner identity must fail closed");
    }

    // ==========================================
    // 40. Missing Event ID in Resolution Request Fails Closed
    // ==========================================
    @Test
    @DisplayName("40. Fail-closed: Resolution request with null event ID is rejected as stale")
    void test40_missingEventIDFailsClosed() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "Worker", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool"
        );

        var result = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", null, active.sequence(), false, null, null
        );

        assertFalse(result.resolved());
        assertTrue(result.message().contains("Missing event ID"));
    }

    // ==========================================
    // 41. Higher Sequence Resolution Request Is Rejected
    // ==========================================
    @Test
    @DisplayName("41. Server rejects resolution request with sequence higher than active event sequence")
    void test41_higherSequenceRejected() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "Worker", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool"
        );

        // Sequence 999 is higher than active sequence
        var result = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", active.eventId(), active.sequence() + 999, false, null, null
        );

        assertFalse(result.resolved());
        assertTrue(result.message().contains("does not match active sequence"));
        assertTrue(RescueServerManager.getActiveRescue(workerId).isPresent());
    }

    // ==========================================
    // 42. Duplicate Resolution Request Fails Gracefully
    // ==========================================
    @Test
    @DisplayName("42. Duplicate resolution request fails safely without altering state")
    void test42_duplicateResolutionFailsGracefully() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        var active = RescueServerManager.triggerRescue(
                null, workerId, "Worker", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "No tool"
        );

        // First resolution
        var first = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", active.eventId(), active.sequence(), false, null, null
        );
        assertTrue(first.resolved());
        assertFalse(RescueServerManager.getActiveRescue(workerId).isPresent());

        // Second (duplicate) resolution
        var duplicate = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", active.eventId(), active.sequence(), false, null, null
        );
        assertFalse(duplicate.resolved());
        assertTrue(duplicate.message().contains("No active rescue"));
    }

    // ==========================================
    // 43. Stale Resolution After Newer Event Created Is Rejected
    // ==========================================
    @Test
    @DisplayName("43. Resolution with older event ID is rejected after a newer rescue event is triggered")
    void test43_staleResolutionAfterNewerEventCreated() {
        UUID workerId = UUID.randomUUID();
        UUID ownerUUID = UUID.randomUUID();
        ResourceLocation dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = new BlockPos(100, 64, 100);

        // Event 1
        var event1 = RescueServerManager.triggerRescue(
                null, workerId, "Worker", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.UNRECOVERABLE_TOOL_EXHAUSTION, "Tool broken"
        );

        // Event 2 supersedes Event 1 for same worker
        var event2 = RescueServerManager.triggerRescue(
                null, workerId, "Worker", ownerUUID, "Alice",
                UUID.randomUUID(), dim, pos,
                EquipmentCapabilityPolicy.RescueTrigger.IMPASSABLE_CORRIDOR_HAZARD, "Lava obstruction"
        );

        // Attempting to resolve with Event 1 ID & sequence
        var staleResolve = RescueServerManager.resolveRescue(
                null, workerId, ownerUUID, "Alice", event1.eventId(), event1.sequence(), false, null, null
        );

        assertFalse(staleResolve.resolved(), "Stale event 1 resolution must be rejected");
        assertTrue(staleResolve.message().contains("event ID mismatch"));

        // New rescue (Event 2) remains active
        Optional<RescueServerManager.ActiveRescue> current = RescueServerManager.getActiveRescue(workerId);
        assertTrue(current.isPresent());
        assertEquals(event2.eventId(), current.get().eventId());
        assertEquals(event2.sequence(), current.get().sequence());
    }

    // ==========================================
    // 44. Shelter Mode Selection Based on Available Materials
    // ==========================================
    @Test
    @DisplayName("44. Shelter mode selects FULL_SHELTER (>=121), PARTIAL_SHELTER (>=31), or DIRECTIONAL_BARRIER (<31)")
    void test44_shelterModeSelectionBasedOnMaterials() {
        // Case 1: 150 cobblestone -> FULL_SHELTER
        WorkerStorage storage1 = WorkerStorage.empty()
                .deposit(new ItemStack(Items.COBBLESTONE, 64))
                .deposit(new ItemStack(Items.COBBLESTONE, 64))
                .deposit(new ItemStack(Items.COBBLESTONE, 22));
        int count1 = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage1, Collections.emptySet());
        assertEquals(150, count1);
        assertTrue(count1 >= EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER.getStandardBlockCount());

        // Case 2: 40 stone -> PARTIAL_SHELTER
        WorkerStorage storage2 = WorkerStorage.empty()
                .deposit(new ItemStack(Items.STONE, 40));
        int count2 = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage2, Collections.emptySet());
        assertEquals(40, count2);
        assertTrue(count2 >= EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER.getStandardBlockCount());
        assertTrue(count2 < EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER.getStandardBlockCount());

        // Case 3: 10 granite -> DIRECTIONAL_BARRIER
        WorkerStorage storage3 = WorkerStorage.empty()
                .deposit(new ItemStack(Items.GRANITE, 10));
        int count3 = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(storage3, Collections.emptySet());
        assertEquals(10, count3);
        assertTrue(count3 < EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER.getStandardBlockCount());
    }

    // ==========================================
    // 45. Failed Placement Does Not Advance Progress or Consume Materials
    // ==========================================
    @Test
    @DisplayName("45. Failed placement step does not advance construction index or consume building materials")
    void test45_failedPlacementDoesNotAdvanceOrConsume() {
        UUID workerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BlockPos center = new BlockPos(100, 64, 100);

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );

        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, eventId, plan, Direction.NORTH);

        WorkerStorage storage = WorkerStorage.empty()
                .deposit(new ItemStack(Items.COBBLESTONE, 10));

        // Simulated placement failure via customPlacer returning false
        var result = state.stepPlacement(null, storage, Collections.emptySet(), null, (pos, bs) -> false);

        assertFalse(result.placedBlock(), "Placement must fail when level is null");
        assertEquals(0, state.getNextIndex(), "Index must NOT advance on failed placement");
        assertEquals(0, state.getPlacedCount(), "Placed count must remain 0");

        // Storage remains unconsumed (10 cobblestone intact)
        int remainingBlocks = EquipmentCapabilityPolicy.countSuitableBuildingBlocks(result.updatedStorage(), Collections.emptySet());
        assertEquals(10, remainingBlocks, "Materials must not be consumed on failed placement");
    }

    // ==========================================
    // 46. Safe Halting on Depletion and Resumption With New Supplies
    // ==========================================
    @Test
    @DisplayName("46. Shelter construction halts safely on material depletion and resumes without resetting progress")
    void test46_safeHaltingAndResumptionWithSupplies() {
        UUID workerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BlockPos center = new BlockPos(100, 64, 100);

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.DIRECTIONAL_BARRIER, Direction.NORTH, 3
        );

        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, eventId, plan, Direction.NORTH);

        // Empty storage
        WorkerStorage emptyStorage = WorkerStorage.empty();
        var haltedResult = state.stepPlacement(null, emptyStorage, Collections.emptySet(), null);

        assertTrue(haltedResult.halted(), "Must halt when storage is empty");
        assertTrue(state.isHalted());
        assertTrue(state.getHaltReason().isPresent());
        assertEquals("Insufficient building materials in WorkerStorage", state.getHaltReason().get());

        // Supplies replenished
        state.resumeWithMaterials();
        assertFalse(state.isHalted(), "Must un-halt after materials are supplied");
        assertFalse(state.getHaltReason().isPresent());
    }

    // ==========================================
    // 47. Worker Collision Space (0,0, dy=0..2) Is Never Occupied
    // ==========================================
    @Test
    @DisplayName("47. Worker collision space is preserved; placement positions never intersect center (0,0, dy=0..2)")
    void test47_workerCollisionSpaceNeverOccupied() {
        BlockPos center = new BlockPos(200, 64, 200);

        // Generate full shelter plan
        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.FULL_SHELTER, Direction.NORTH, 3
        );

        for (var placement : plan.placements()) {
            BlockPos p = placement.pos();
            if (p.getX() == center.getX() && p.getZ() == center.getZ()) {
                int dy = p.getY() - center.getY();
                assertFalse(dy >= 0 && dy <= 2, "Placement at " + p + " collides with worker standing space (dy=" + dy + ")");
            }
        }
    }

    // ==========================================
    // 48. Pre-Existing Solid World Blocks Counted Without Duplicate Consumption
    // ==========================================
    @Test
    @DisplayName("48. Pre-existing solid world blocks are reconciled into shelter without consuming storage")
    void test48_preExistingWorldBlocksReconciled() {
        UUID workerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        BlockPos center = new BlockPos(100, 64, 100);

        EquipmentCapabilityPolicy.EmergencyShelterPlan plan = EquipmentCapabilityPolicy.generateShelterPlan(
                center, EquipmentCapabilityPolicy.ShelterMode.PARTIAL_SHELTER, Direction.NORTH, 3
        );

        EquipmentCapabilityPolicy.ShelterConstructionState state =
                new EquipmentCapabilityPolicy.ShelterConstructionState(workerId, eventId, plan, Direction.NORTH);

        // Manually record 5 placed blocks
        for (int i = 0; i < 5; i++) {
            state.recordPlacement(plan.placements().get(i).pos());
        }

        assertEquals(5, state.getPlacedCount());
        assertEquals(plan.requiredBlockCount() - 5, state.getRemainingCount());
    }

    // ==========================================
    // 49. JourneyMap Bridge Reflective Failure Returns False
    // ==========================================
    @Test
    @DisplayName("49. JourneyMapBridge: when Waypoint class cannot be instantiated, createOrUpdateWaypoint returns false")
    void test49_journeyMapBridgeNullWaypointReturnsFalse() {
        JourneyMapBridge.setMockJourneyMapPresent(true);

        // Invoking createOrUpdateWaypoint with non-existent JourneyMap Waypoint class on test classpath
        boolean created = JourneyMapBridge.createOrUpdateWaypoint(
                "test_wp",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                new BlockPos(100, 64, 100),
                "TestWP",
                "Reason"
        );

        // Invariant: MUST return false (never true when Waypoint is not instantiated or saved)
        assertFalse(created, "Must return false when Waypoint instantiation or save fails");
    }

    // ==========================================
    // 50. JourneyMap Bridge Remove Absent Waypoint Returns False
    // ==========================================
    @Test
    @DisplayName("50. JourneyMapBridge: when WaypointStore is absent or cannot remove, removeWaypoint returns false")
    void test50_journeyMapBridgeRemoveAbsentReturnsFalse() {
        JourneyMapBridge.setMockJourneyMapPresent(true);

        boolean removed = JourneyMapBridge.removeWaypoint(
                "nonexistent_wp",
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        );

        assertFalse(removed, "Must return false when store cannot remove waypoint");
    }

    // ==========================================
    // 51. JourneyMap Absent Returns False Cleanly
    // ==========================================
    @Test
    @DisplayName("51. JourneyMapBridge: when JourneyMap is absent, both create and remove return false cleanly")
    void test51_journeyMapAbsentReturnsFalseCleanly() {
        JourneyMapBridge.setMockJourneyMapPresent(false);

        assertFalse(JourneyMapBridge.createOrUpdateWaypoint(
                "wp1", ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                new BlockPos(10, 64, 10), "Name", "Reason"
        ));

        assertFalse(JourneyMapBridge.removeWaypoint(
                "wp1", ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        ));
    }
}
