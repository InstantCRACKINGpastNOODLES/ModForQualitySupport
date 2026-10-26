package com.example.examplemod;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerManagementScreenTest {
    @Test
    void heartsIncludeHalfAndMultipleRows() {
        assertEquals(10, WorkerManagementScreen.visibleHearts(20));
        assertEquals(18, WorkerManagementScreen.visibleHearts(36));
        assertEquals(21, WorkerManagementScreen.visibleHearts(100));
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
}