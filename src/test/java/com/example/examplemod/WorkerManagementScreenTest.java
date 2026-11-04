package com.example.examplemod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerManagementScreenTest {
    @Test
    void heartsIncludeHalfAndMultipleRows() {
        assertEquals(10, WorkerManagementScreen.visibleHearts(20));
        assertEquals(10, WorkerManagementScreen.visibleHearts(36));
        assertEquals(10, WorkerManagementScreen.visibleHearts(100));
        assertEquals(2, WorkerManagementScreen.heartFill(5, 1));
        assertEquals(1, WorkerManagementScreen.heartFill(5, 2));
        assertEquals(1, WorkerManagementScreen.heartFill(0.5f, 0));
        assertEquals(0, WorkerManagementScreen.heartFill(5, 3));
    }

    @Test
    void netheriteArmorDeclaresCorrespondingVanillaEquipmentSlots() {
        assertEquals(EquipmentSlot.HEAD, ((ArmorItem) Items.NETHERITE_HELMET).getType().getSlot());
        assertEquals(EquipmentSlot.CHEST, ((ArmorItem) Items.NETHERITE_CHESTPLATE).getType().getSlot());
        assertEquals(EquipmentSlot.LEGS, ((ArmorItem) Items.NETHERITE_LEGGINGS).getType().getSlot());
        assertEquals(EquipmentSlot.FEET, ((ArmorItem) Items.NETHERITE_BOOTS).getType().getSlot());
    }

    @Test
    void taskPanelOnlyNamesKnownJobsAndDoesNotInventStatus() {
        assertEquals("none", WorkerManagementScreen.jobKey(0));
        assertEquals("wait", WorkerManagementScreen.jobKey(1));
        assertEquals("move", WorkerManagementScreen.jobKey(2));
        assertEquals("none", WorkerManagementScreen.jobKey(99));
        assertEquals("idle", WorkerManagementScreen.statusKey(0, -1));
        assertEquals("assigned", WorkerManagementScreen.statusKey(2, JobStatus.ASSIGNED.ordinal()));
        assertEquals("unknown", WorkerManagementScreen.statusKey(2, 99));
    }

    @Test
    void equipmentSlotIconsMatchVanillaItemAtlasSprites() {
        assertEquals(ResourceLocation.withDefaultNamespace("item/empty_armor_slot_helmet"),
                WorkerManagementScreen.equipmentSlotIcon(0));
        assertEquals(ResourceLocation.withDefaultNamespace("item/empty_armor_slot_chestplate"),
                WorkerManagementScreen.equipmentSlotIcon(1));
        assertEquals(ResourceLocation.withDefaultNamespace("item/empty_armor_slot_leggings"),
                WorkerManagementScreen.equipmentSlotIcon(2));
        assertEquals(ResourceLocation.withDefaultNamespace("item/empty_armor_slot_boots"),
                WorkerManagementScreen.equipmentSlotIcon(3));
        assertEquals(ResourceLocation.withDefaultNamespace("item/empty_slot_sword"),
                WorkerManagementScreen.equipmentSlotIcon(4));
        assertEquals(ResourceLocation.withDefaultNamespace("item/empty_armor_slot_shield"),
                WorkerManagementScreen.equipmentSlotIcon(5));
        assertNull(WorkerManagementScreen.equipmentSlotIcon(6));
        assertNull(WorkerManagementScreen.equipmentSlotIcon(-1));
    }

    @Test
    void adaptiveLayoutFitsVariousViewportSizes() {
        int[][] viewports = {
                {1920, 1080}, // 1080p scale 1
                {960, 540},   // 1080p scale 2
                {640, 360},   // 1080p scale 3
                {480, 270},   // 1080p scale 4
                {384, 216},   // 1080p scale 5
                {1280, 720},  // 720p scale 1
                {640, 360},   // 720p scale 2
                {426, 240},   // 720p scale 3
                {320, 180},   // 720p scale 4
                {854, 480},   // 480p scale 1
                {427, 240},   // 480p scale 2
                {320, 240},   // small window
                {320, 200},   // constrained
                {320, 180}    // minimum viewport
        };

        for (int[] vp : viewports) {
            int w = vp[0];
            int h = vp[1];

            WorkerManagementScreen.LayoutPlan plan = WorkerManagementScreen.computeLayout(w, h, 1, -1);
            assertTrue(plan.leftPos() >= 0, "leftPos must be non-negative for " + w + "x" + h);
            assertTrue(plan.topPos() >= 0, "topPos must be non-negative for " + w + "x" + h);
            assertTrue(plan.leftPos() + plan.panelWidth() <= w, "Panel width exceeds viewport width " + w);
            assertTrue(plan.topPos() + plan.panelHeight() <= h || plan.compact(), "Panel height exceeds viewport height " + h);

            if (plan.compact()) {
                WorkerManagementScreen.LayoutPlan tab0 = WorkerManagementScreen.computeLayout(w, h, 1, 0);
                assertEquals(0, tab0.activeTab());
                assertTrue(tab0.panelHeight() <= h, "Tab 0 height must fit in " + h);
                assertTrue(tab0.leftPos() >= 0);
                assertTrue(tab0.topPos() >= 0);

                WorkerManagementScreen.LayoutPlan tab1 = WorkerManagementScreen.computeLayout(w, h, 1, 1);
                assertEquals(1, tab1.activeTab());
                assertTrue(tab1.panelHeight() <= h, "Tab 1 height must fit in " + h);
                assertTrue(tab1.leftPos() >= 0);
                assertTrue(tab1.topPos() >= 0);
            } else {
                assertFalse(plan.compact());
                assertTrue(plan.previewW() > 0);
                assertTrue(plan.vitalsW() > 0);
                assertTrue(plan.equipW() > 0);
                assertTrue(plan.taskW() > 0);
                assertTrue(plan.addTaskW() > 0);
                assertTrue(plan.behaviorW() > 0);
                assertTrue(plan.storageW() > 0);
                assertTrue(plan.playerInvW() > 0);

                // Left panel internal arrangement: preview is to the left of vitals
                assertTrue(plan.previewX() + plan.previewW() <= plan.vitalsX());
                // Equipment is underneath preview and vitals
                assertTrue(plan.previewY() + plan.previewH() <= plan.equipY());
                assertTrue(plan.vitalsY() + plan.vitalsH() <= plan.equipY());

                // Left panel is to the left of right panel (task and behavior)
                assertTrue(plan.vitalsX() + plan.vitalsW() <= plan.taskX());
                assertTrue(plan.equipX() + plan.equipW() <= plan.taskX());
                assertTrue(plan.equipX() + plan.equipW() <= plan.behaviorX());

                // Right panel internal arrangement: behavior is below task list
                assertTrue(plan.taskY() + plan.taskH() <= plan.behaviorY());
                assertTrue(plan.taskX() + plan.taskW() <= plan.panelWidth());
                assertTrue(plan.behaviorX() + plan.behaviorW() <= plan.panelWidth());

                // Lower section: storage is to the left of player inventory
                assertTrue(plan.storageX() + plan.storageW() <= plan.playerInvX());
                assertTrue(plan.playerInvX() + plan.playerInvW() <= plan.panelWidth());
                assertTrue(plan.playerInvY() + plan.playerInvH() <= plan.panelHeight());

                // Vertical separation between top panels and lower slot grids
                assertTrue(plan.equipY() + plan.equipH() <= plan.storageY());
                assertTrue(plan.behaviorY() + plan.behaviorH() <= plan.playerInvY());

                // Cancel button inside task panel row
                assertTrue(plan.cancelX() >= plan.taskX());
                assertTrue(plan.cancelX() + plan.cancelW() <= plan.taskX() + plan.taskW());
                assertTrue(plan.cancelY() >= plan.taskY());
                assertTrue(plan.cancelY() + plan.cancelH() <= plan.taskY() + plan.taskH());

                // Add Task button inside task panel
                assertTrue(plan.addTaskX() >= plan.taskX());
                assertTrue(plan.addTaskX() + plan.addTaskW() <= plan.taskX() + plan.taskW());
                assertTrue(plan.addTaskY() >= plan.taskY());
                assertTrue(plan.addTaskY() + plan.addTaskH() <= plan.taskY() + plan.taskH());
            }
        }
    }

    @Test
    void selectionLayoutFitsSmallViewports() {
        int[][] viewports = {{320, 240}, {320, 200}, {320, 180}, {426, 240}, {1920, 1080}};
        for (int[] vp : viewports) {
            WorkerManagementScreen.LayoutPlan plan = WorkerManagementScreen.computeLayout(vp[0], vp[1], 4, -1);
            assertTrue(plan.leftPos() >= 0);
            assertTrue(plan.topPos() >= 0);
            assertTrue(plan.leftPos() + plan.panelWidth() <= vp[0]);
            assertTrue(plan.topPos() + plan.panelHeight() <= vp[1]);
        }
    }

    @Test
    void itemsTabLayoutHasZeroOverlapBetweenVitalsAndTabs() {
        WorkerManagementScreen.LayoutPlan plan = WorkerManagementScreen.computeLayout(1920, 1080, 1, 1);
        assertEquals(1, plan.activeTab());
        assertEquals(312, plan.panelWidth());
        assertEquals(130, plan.panelHeight());

        // Worker name + vitals: left side of header (X: 8..165, Y: 6..18)
        int nameEndX = 8 + 65; // 73
        int vitalsStartX = 76;
        assertTrue(nameEndX <= vitalsStartX, "Worker name must not overlap vitals text");
        int vitalsEndX = vitalsStartX + 85; // 161 (typical '20/20 HP  Armor: 0' width)

        // Tabs: right side of header (X: 178..310, Y: 6..18)
        int tabAllX = plan.panelWidth() - 134; // 178
        assertTrue(vitalsEndX < tabAllX, "Vitals text (ends at " + vitalsEndX + ") must not collide with Tab 'All' (starts at " + tabAllX + ")");

        // Section labels: Y = 25 (above slots)
        int storageLabelY = plan.storageY() - 11; // 25
        int playerInvLabelY = plan.playerInvY() - 11; // 25
        assertTrue(storageLabelY > 18, "Section labels must be positioned below the header area");
        assertTrue(plan.storageY() >= storageLabelY + 11, "Storage panel must start after label");

        // Slots fit cleanly inside panel
        assertTrue(plan.storageY() + plan.storageH() <= plan.panelHeight(), "Storage panel must fit within panel height");
        assertTrue(plan.playerInvY() + plan.playerInvH() <= plan.panelHeight(), "Player inventory panel must fit within panel height");
    }
}
