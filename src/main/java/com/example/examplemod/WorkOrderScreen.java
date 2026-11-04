package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Dedicated UI for configuring and issuing worker work orders (Move, Mine, Farm).
 * Extends AbstractContainerScreen to maintain the active container menu connection
 * without prematurely triggering container close packets.
 */
public class WorkOrderScreen extends AbstractContainerScreen<WorkerManagementMenu> {

    private final Screen parentScreen;

    private int selectedJob = 2; // 1 = MOVE, 2 = MINE, 3 = FARM
    private long selectedQuota = 32L; // 16, 32, 64
    private CropSelectionMode cropSelectionMode = CropSelectionMode.ALL_SUPPORTED;

    private Button btnMove;
    private Button btnMine;
    private Button btnFarm;

    private Button btnQuota16;
    private Button btnQuota32;
    private Button btnQuota64;

    private Button btnCropMode;

    private Button btnConfirm;
    private Button btnBack;

    public WorkOrderScreen(Screen parentScreen, WorkerManagementMenu menu, Inventory inventory, int initialJob) {
        super(menu, inventory != null ? inventory : (menu != null ? menu.getInventory() : null), Component.translatable("gui.elstarticket.work_order_title"));
        this.parentScreen = parentScreen;
        this.selectedJob = (initialJob >= 1 && initialJob <= 3) ? initialJob : 2;
        this.imageWidth = 260;
        this.imageHeight = 224;
    }

    public WorkOrderScreen(Screen parentScreen, WorkerManagementMenu menu, int initialJob) {
        this(parentScreen, menu, menu != null ? menu.getInventory() : null, initialJob);
    }

    public WorkOrderScreen(Screen parentScreen, WorkerManagementMenu menu) {
        this(parentScreen, menu, 2);
    }

    @Override
    protected void init() {
        super.init();
        if (menu != null) {
            menu.setActiveTab(-2); // deactivate and hide menu slots during order configuration
        }
        int panelW = 260;
        int panelH = 224;
        this.imageWidth = panelW;
        this.imageHeight = panelH;
        this.leftPos = (this.width - panelW) / 2;
        this.topPos = (this.height - panelH) / 2;
        int left = this.leftPos;
        int top = this.topPos;

        int btnW = 76;
        int btnH = 20;

        // Job type buttons
        btnMove = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.task_move"), b -> {
            selectedJob = 1;
            updateButtonStates();
        }).bounds(left + 12, top + 34, btnW, btnH).build());

        btnMine = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.task_mine"), b -> {
            selectedJob = 2;
            updateButtonStates();
        }).bounds(left + 12 + btnW + 4, top + 34, btnW, btnH).build());

        btnFarm = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.task_farm"), b -> {
            selectedJob = 3;
            updateButtonStates();
        }).bounds(left + 12 + (btnW + 4) * 2, top + 34, btnW, btnH).build());

        // Quota buttons
        int qBtnW = 76;
        btnQuota16 = addRenderableWidget(Button.builder(Component.literal("16"), b -> {
            selectedQuota = 16L;
            updateButtonStates();
        }).bounds(left + 12, top + 98, qBtnW, btnH).build());

        btnQuota32 = addRenderableWidget(Button.builder(Component.literal("32"), b -> {
            selectedQuota = 32L;
            updateButtonStates();
        }).bounds(left + 12 + qBtnW + 4, top + 98, qBtnW, btnH).build());

        btnQuota64 = addRenderableWidget(Button.builder(Component.literal("64"), b -> {
            selectedQuota = 64L;
            updateButtonStates();
        }).bounds(left + 12 + (qBtnW + 4) * 2, top + 98, qBtnW, btnH).build());

        // Crop Selection Mode button (Farm only)
        btnCropMode = addRenderableWidget(Button.builder(Component.literal("Crops: All Supported"), b -> {
            cropSelectionMode = (cropSelectionMode == CropSelectionMode.ALL_SUPPORTED) ?
                    CropSelectionMode.SELECTED_ONLY : CropSelectionMode.ALL_SUPPORTED;
            updateButtonStates();
        }).bounds(left + 12, top + 122, panelW - 24, btnH).build());

        // Bottom action buttons
        btnBack = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.cancel"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parentScreen);
            }
        }).bounds(left + 12, top + panelH - 26, 70, 20).build());

        btnConfirm = addRenderableWidget(Button.builder(Component.literal("▶ Start Selection"), b -> onConfirm())
                .bounds(left + 88, top + panelH - 26, 160, 20).build());

        updateButtonStates();
    }

    private void updateButtonStates() {
        boolean isMove = selectedJob == 1;
        boolean isMine = selectedJob == 2;
        boolean isFarm = selectedJob == 3;

        btnMove.setMessage(Component.literal(isMove ? "§6§l[Move]" : "Move"));
        btnMine.setMessage(Component.literal(isMine ? "§6§l[Mine]" : "Mine"));
        btnFarm.setMessage(Component.literal(isFarm ? "§6§l[Farm]" : "Farm"));

        btnQuota16.visible = !isMove;
        btnQuota32.visible = !isMove;
        btnQuota64.visible = !isMove;

        btnQuota16.setMessage(Component.literal(selectedQuota == 16L ? "§a§l[16]" : "16"));
        btnQuota32.setMessage(Component.literal(selectedQuota == 32L ? "§a§l[32]" : "32"));
        btnQuota64.setMessage(Component.literal(selectedQuota == 64L ? "§a§l[64]" : "64"));

        btnCropMode.visible = isFarm;
        if (cropSelectionMode == CropSelectionMode.ALL_SUPPORTED) {
            btnCropMode.setMessage(Component.literal("§aCrops: All Supported (Mixed Farms)"));
        } else {
            btnCropMode.setMessage(Component.literal("§eCrops: Selected Only (Specific Crop)"));
        }

        // Enable confirmation button (worker will enter WAITING_FOR_TOOL if tool is pending)
        btnConfirm.active = true;
    }

    private boolean hasMiningTool(Mob worker) {
        if (worker != null) {
            ItemStack held = worker.getMainHandItem();
            if (WorkerJobCapability.Registry.canPerformMining(worker, held)) return true;
        }
        if (menu != null) {
            for (int i = 0; i < 24 && i < menu.slots.size(); i++) {
                ItemStack item = menu.slots.get(i).getItem();
                if (WorkerJobCapability.Registry.canPerformMining(worker, item)) return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && parentScreen != null) {
            this.minecraft.setScreen(parentScreen);
        } else {
            super.onClose();
        }
    }

    private void onConfirm() {
        if (minecraft == null || minecraft.gameMode == null) return;
        int buttonId;
        if (selectedJob == 1) {
            buttonId = 105; // Move
        } else if (selectedJob == 2) {
            buttonId = selectedQuota == 16L ? 201 : selectedQuota == 32L ? 202 : 203; // Mine
        } else {
            if (cropSelectionMode == CropSelectionMode.ALL_SUPPORTED) {
                buttonId = selectedQuota == 16L ? 211 : selectedQuota == 32L ? 212 : 213; // Farm All Supported
            } else {
                buttonId = selectedQuota == 16L ? 221 : selectedQuota == 32L ? 222 : 223; // Farm Selected Only
            }
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int panelW = 260;
        int panelH = 224;
        int left = this.leftPos;
        int top = this.topPos;

        // Background panel
        graphics.fill(left, top, left + panelW, top + panelH, 0xEE1E222B);
        // Border
        graphics.fill(left, top, left + panelW, top + 1, 0xFF555555);
        graphics.fill(left, top + panelH - 1, left + panelW, top + panelH, 0xFF555555);
        graphics.fill(left, top, left + 1, top + panelH, 0xFF555555);
        graphics.fill(left + panelW - 1, top, left + panelW, top + panelH, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Rendered via render() for full coordinate control
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int panelW = 260;
        int left = this.leftPos;
        int top = this.topPos;

        Font font = this.font;

        // Title
        graphics.drawString(font, "§6§lWork Order Configuration", left + 12, top + 10, 0xFFFFFF, false);
        Mob worker = menu.worker();
        String wName = worker != null ? worker.getDisplayName().getString() : "Worker";
        graphics.drawString(font, "§7Worker: §f" + wName, left + 12, top + 22, 0xFFFFFF, false);

        // Job details & description
        int descY = top + 56;
        if (selectedJob == 1) {
            graphics.drawString(font, "§eMove Job:", left + 12, descY, 0xFFFFFF, false);
            graphics.drawString(font, "§7Directs the worker to walk to a designated block.", left + 12, descY + 11, 0xFFFFFF, false);
            graphics.drawString(font, "§fTarget: §b1 Destination Block §7(No storage needed)", left + 12, descY + 22, 0xFFFFFF, false);
        } else if (selectedJob == 2) {
            graphics.drawString(font, "§eMine Job:", left + 12, descY, 0xFFFFFF, false);
            graphics.drawString(font, "§7Mines a 1×2 straight tunnel into storage.", left + 12, descY + 11, 0xFFFFFF, false);
            boolean hasTool = hasMiningTool(worker);
            String toolMsg = hasTool ? "§a✔ Mining pickaxe ready" : "§e⚠ Pickaxe needed (WorkerStorage or Hand)";
            graphics.drawString(font, toolMsg, left + 12, descY + 22, 0xFFFFFF, false);
            graphics.drawString(font, "§7Select Target Quota (blocks to mine):", left + 12, top + 86, 0xFFFFFF, false);
        } else if (selectedJob == 3) {
            graphics.drawString(font, "§eFarm Job:", left + 12, descY, 0xFFFFFF, false);
            graphics.drawString(font, "§7Harvests and replants mature crops into storage.", left + 12, descY + 11, 0xFFFFFF, false);
            graphics.drawString(font, "§a✔ Tool: Bare hands or any hoe", left + 12, descY + 20, 0xFFFFFF, false);
            graphics.drawString(font, "§7Select Target Quota (crops to harvest):", left + 12, top + 86, 0xFFFFFF, false);
        }

        // Selection workflow preview
        int flowY = top + (selectedJob == 3 ? 146 : 130);
        int flowH = (selectedJob == 3) ? 44 : 32;
        graphics.fill(left + 10, flowY - 2, left + panelW - 10, flowY + flowH, 0x55000000);
        graphics.drawString(font, "§6Workflow Steps:", left + 14, flowY + 1, 0xFFFFFF, false);
        if (selectedJob == 1) {
            graphics.drawString(font, "§fStep 1: §7Right-click ground in world with Binding Ticket", left + 14, flowY + 14, 0xFFFFFF, false);
        } else if (selectedJob == 2) {
            graphics.drawString(font, "§fStep 1: §7Right-click worksite block in world", left + 14, flowY + 12, 0xFFFFFF, false);
            graphics.drawString(font, "§fStep 2: §7Right-click storage container (Chest/Barrel)", left + 14, flowY + 22, 0xFFFFFF, false);
        } else {
            if (cropSelectionMode == CropSelectionMode.ALL_SUPPORTED) {
                graphics.drawString(font, "§fStep 1: §7Right-click ANY crop/farmland as anchor", left + 14, flowY + 12, 0xFFFFFF, false);
                graphics.drawString(font, "§7       (Discovers all: wheat, carrot, melon, cocoa...)", left + 14, flowY + 22, 0xAAAAAA, false);
                graphics.drawString(font, "§fStep 2: §7Right-click storage container (Chest/Barrel)", left + 14, flowY + 32, 0xFFFFFF, false);
            } else {
                graphics.drawString(font, "§fStep 1: §7Right-click SPECIFIC crop to restrict harvest", left + 14, flowY + 12, 0xFFFFFF, false);
                graphics.drawString(font, "§fStep 2: §7Right-click storage container (Chest/Barrel)", left + 14, flowY + 24, 0xFFFFFF, false);
            }
        }
    }
}
