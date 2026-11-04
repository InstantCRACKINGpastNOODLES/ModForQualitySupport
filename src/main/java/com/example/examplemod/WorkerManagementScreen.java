package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;

public class WorkerManagementScreen extends AbstractContainerScreen<WorkerManagementMenu> {
    private static final int HEARTS_PER_ROW = 5;
    private static final int VISIBLE_HEARTS = 10;
    public static final int PANEL_WIDTH = 312;
    public static final int PANEL_HEIGHT = 212;

    public static final ResourceLocation[] EQUIPMENT_SLOT_ICONS = {
            ResourceLocation.withDefaultNamespace("item/empty_armor_slot_helmet"),
            ResourceLocation.withDefaultNamespace("item/empty_armor_slot_chestplate"),
            ResourceLocation.withDefaultNamespace("item/empty_armor_slot_leggings"),
            ResourceLocation.withDefaultNamespace("item/empty_armor_slot_boots"),
            ResourceLocation.withDefaultNamespace("item/empty_slot_sword"),
            ResourceLocation.withDefaultNamespace("item/empty_armor_slot_shield")
    };

    public static ResourceLocation equipmentSlotIcon(int index) {
        return index >= 0 && index < EQUIPMENT_SLOT_ICONS.length ? EQUIPMENT_SLOT_ICONS[index] : null;
    }

    public record LayoutPlan(
            int panelWidth,
            int panelHeight,
            int leftPos,
            int topPos,
            boolean compact,
            int activeTab,
            int previewX, int previewY, int previewW, int previewH,
            int vitalsX, int vitalsY, int vitalsW, int vitalsH,
            int equipX, int equipY, int equipW, int equipH,
            int taskX, int taskY, int taskW, int taskH,
            int cancelX, int cancelY, int cancelW, int cancelH,
            int addTaskX, int addTaskY, int addTaskW, int addTaskH,
            int behaviorX, int behaviorY, int behaviorW, int behaviorH,
            int storageX, int storageY, int storageW, int storageH,
            int playerInvX, int playerInvY, int playerInvW, int playerInvH
    ) {}

    public static LayoutPlan computeLayout(int screenWidth, int screenHeight, int candidateCount, int activeTab) {
        if (candidateCount != 1) {
            int width = Math.min(240, Math.max(160, screenWidth - 16));
            int height = Math.min(140, Math.max(100, screenHeight - 16));
            int left = Math.max(0, (screenWidth - width) / 2);
            int top = Math.max(0, (screenHeight - height) / 2);
            return new LayoutPlan(width, height, left, top, false, activeTab,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int fullWidth = PANEL_WIDTH;
        int fullHeight = PANEL_HEIGHT;
        boolean compact = screenHeight < fullHeight || screenWidth < fullWidth;
        int currentTab = compact && activeTab == -1 ? 0 : activeTab;

        if (!compact && currentTab == -1) {
            int left = Math.max(0, (screenWidth - fullWidth) / 2);
            int top = Math.max(0, (screenHeight - fullHeight) / 2);
            return new LayoutPlan(
                    fullWidth, fullHeight, left, top, false, -1,
                    8, 18, 46, 48,
                    58, 18, 60, 48,
                    8, 70, 110, 42,
                    124, 18, 182, 54,
                    244, 22, 58, 16,
                    128, 50, 174, 16,
                    124, 74, 182, 36,
                    14, 126, 90, 54,
                    126, 126, 162, 76
            );
        } else if (currentTab == 0) {
            int tabHeight = Math.min(146, screenHeight);
            int width = Math.min(fullWidth, screenWidth);
            int left = Math.max(0, (screenWidth - width) / 2);
            int top = Math.max(0, (screenHeight - tabHeight) / 2);
            return new LayoutPlan(
                    width, tabHeight, left, top, compact, 0,
                    8, 18, 46, 48,
                    58, 18, 60, 48,
                    8, 70, 110, 42,
                    124, 18, 182, 54,
                    244, 22, 58, 16,
                    128, 50, 174, 16,
                    124, 74, 182, 36,
                    0, 0, 0, 0,
                    0, 0, 0, 0
            );
        } else {
            int tabHeight = Math.min(130, screenHeight);
            int width = Math.min(fullWidth, screenWidth);
            int left = Math.max(0, (screenWidth - width) / 2);
            int top = Math.max(0, (screenHeight - tabHeight) / 2);
            return new LayoutPlan(
                    width, tabHeight, left, top, compact, 1,
                    0, 0, 0, 0,
                    8, 6, 160, 12,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    14, 36, 90, 54,
                    126, 36, 162, 76
            );
        }
    }

    private int page;
    private int activeTab = -1;
    private LayoutPlan layoutPlan;
    private final List<Button> choices = new ArrayList<>();
    private Button cancel;
    private Button addTask;
    private Button addFarmBtn;
    private Button addMoveBtn;
    private Button followBtn;
    private Button standBtn;
    private Button homeBtn;
    private Button previous;
    private Button next;
    private Button tabAll;
    private Button tabWorker;
    private Button tabItems;

    public WorkerManagementScreen(WorkerManagementMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        choices.clear();
        layoutPlan = computeLayout(this.width, this.height, menu.candidateCount(), activeTab);
        this.imageWidth = layoutPlan.panelWidth();
        this.imageHeight = layoutPlan.panelHeight();
        this.leftPos = layoutPlan.leftPos();
        this.topPos = layoutPlan.topPos();

        for (int i = 0; i < 8; i++) {
            int index = i;
            choices.add(addRenderableWidget(Button.builder(Component.empty(), button ->
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, page * 8 + index))
                    .bounds(leftPos + 8 + i % 2 * 110, topPos + 22 + i / 2 * 21, 106, 20).build()));
        }
        previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; updateChoices(); })
                .bounds(leftPos + 8, topPos + 110, 20, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; updateChoices(); })
                .bounds(leftPos + 34, topPos + 110, 20, 20).build());

        cancel = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.cancel"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 100))
                .bounds(leftPos + layoutPlan.cancelX(), topPos + layoutPlan.cancelY(), layoutPlan.cancelW(), layoutPlan.cancelH()).build());

        int taskBtnW = Math.max(14, (layoutPlan.addTaskW() - 4) / 3);
        addTask = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.task_mine"), button -> {
            if (minecraft != null) {
                minecraft.setScreen(new WorkOrderScreen(this, menu, menu.getInventory(), 2));
            }
        }).bounds(leftPos + layoutPlan.addTaskX(), topPos + layoutPlan.addTaskY(), taskBtnW, layoutPlan.addTaskH()).build());

        addFarmBtn = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.task_farm"), button -> {
            if (minecraft != null) {
                minecraft.setScreen(new WorkOrderScreen(this, menu, menu.getInventory(), 3));
            }
        }).bounds(leftPos + layoutPlan.addTaskX() + taskBtnW + 2, topPos + layoutPlan.addTaskY(), taskBtnW, layoutPlan.addTaskH()).build());

        addMoveBtn = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.task_move"), button -> {
            if (minecraft != null) {
                minecraft.setScreen(new WorkOrderScreen(this, menu, menu.getInventory(), 1));
            }
        }).bounds(leftPos + layoutPlan.addTaskX() + (taskBtnW + 2) * 2, topPos + layoutPlan.addTaskY(), taskBtnW, layoutPlan.addTaskH()).build());

        followBtn = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.follow"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 101))
                .bounds(leftPos + layoutPlan.behaviorX(), topPos + layoutPlan.behaviorY() + 4, 58, 16).build());

        standBtn = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.stand"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 102))
                .bounds(leftPos + layoutPlan.behaviorX() + 61, topPos + layoutPlan.behaviorY() + 4, 58, 16).build());

        homeBtn = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.home"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 103))
                .bounds(leftPos + layoutPlan.behaviorX() + 122, topPos + layoutPlan.behaviorY() + 4, 58, 16).build());

        int tabY = layoutPlan.activeTab() == 1 ? 6 : 2;
        tabAll = addRenderableWidget(Button.builder(Component.literal("All"), button -> switchTab(-1))
                .bounds(leftPos + layoutPlan.panelWidth() - 134, topPos + tabY, 38, 12).build());
        tabWorker = addRenderableWidget(Button.builder(Component.literal("Worker"), button -> switchTab(0))
                .bounds(leftPos + layoutPlan.panelWidth() - 92, topPos + tabY, 44, 12).build());
        tabItems = addRenderableWidget(Button.builder(Component.literal("Items"), button -> switchTab(1))
                .bounds(leftPos + layoutPlan.panelWidth() - 46, topPos + tabY, 44, 12).build());

        updateChoices();
    }

    private void switchTab(int tab) {
        this.activeTab = tab;
        this.menu.setActiveTab(tab);
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 110 + tab + 1);
        }
        this.init(this.minecraft, this.width, this.height);
    }

    private void updateChoices() {
        page = Math.max(0, Math.min(page, (menu.candidateCount() - 1) / 8));
        previous.visible = menu.candidateCount() > 8;
        next.visible = previous.visible;
        previous.active = page > 0;
        next.active = (page + 1) * 8 < menu.candidateCount();
        for (int i = 0; i < choices.size(); i++) {
            int number = page * 8 + i;
            choices.get(i).visible = menu.candidateCount() > 1 && number < menu.candidateCount();
            Mob mob = menu.candidate(number);
            choices.get(i).setMessage(mob == null ? Component.literal(Integer.toString(number + 1)) :
                    Component.literal((number + 1) + ": ").append(mob.getDisplayName()));
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        panel(graphics, 0, 0, imageWidth, imageHeight, 0xffc6c6c6);
        if (menu.candidateCount() != 1) return;

        if (layoutPlan.previewW() > 0) {
            panel(graphics, layoutPlan.previewX() - 1, layoutPlan.previewY() - 1, layoutPlan.previewW() + 2, layoutPlan.previewH() + 2, 0xffababab);
            panel(graphics, layoutPlan.previewX(), layoutPlan.previewY(), layoutPlan.previewW(), layoutPlan.previewH(), 0xff242424);
            Mob worker = menu.worker();
            if (worker != null) {
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                        leftPos + layoutPlan.previewX() + 2, topPos + layoutPlan.previewY() + 2,
                        leftPos + layoutPlan.previewX() + layoutPlan.previewW() - 2,
                        topPos + layoutPlan.previewY() + layoutPlan.previewH() - 2,
                        23, 0.0625f, mouseX, mouseY, worker);
            }
        }

        if (layoutPlan.vitalsW() > 0 && layoutPlan.previewW() > 0) {
            panel(graphics, layoutPlan.vitalsX(), layoutPlan.vitalsY(), layoutPlan.vitalsW(), layoutPlan.vitalsH(), 0xffb5b5b5);
        }
        if (layoutPlan.taskW() > 0) {
            panel(graphics, layoutPlan.taskX(), layoutPlan.taskY(), layoutPlan.taskW(), layoutPlan.taskH(), 0xffb5b5b5);
            panel(graphics, layoutPlan.taskX() + 2, layoutPlan.taskY() + 2, layoutPlan.taskW() - 4, 30, 0xffd0d0d0);
        }
        if (layoutPlan.behaviorW() > 0) {
            panel(graphics, layoutPlan.behaviorX(), layoutPlan.behaviorY(), layoutPlan.behaviorW(), layoutPlan.behaviorH(), 0xffb5b5b5);
        }
        if (layoutPlan.storageW() > 0) {
            panel(graphics, layoutPlan.storageX() - 2, layoutPlan.storageY() - 2, layoutPlan.storageW() + 4, layoutPlan.storageH() + 4, 0xffb5b5b5);
        }
        if (layoutPlan.playerInvW() > 0) {
            panel(graphics, layoutPlan.playerInvX() - 2, layoutPlan.playerInvY() - 2, layoutPlan.playerInvW() + 4, layoutPlan.playerInvH() + 4, 0xffb5b5b5);
        }

        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.isActive()) {
                panel(graphics, slot.x - 1, slot.y - 1, 18, 18, 0xff8b8b8b);
            }
        }
    }

    private void panel(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(leftPos + x, topPos + y, leftPos + x + width, topPos + y + height, 0xff353535);
        graphics.fill(leftPos + x + 1, topPos + y + 1, leftPos + x + width - 1, topPos + y + height - 1, 0xffffffff);
        graphics.fill(leftPos + x + 2, topPos + y + 2, leftPos + x + width - 2, topPos + y + height - 2, color);
    }

    static int visibleHearts(float maxHealth) {
        return Math.min(VISIBLE_HEARTS, (int) Math.ceil(Math.max(0, maxHealth) / 2.0));
    }

    static int heartFill(float health, int index) {
        float remainder = Math.max(0, health) - index * 2;
        return remainder >= 2 ? 2 : remainder > 0 ? 1 : 0;
    }

    static String jobKey(int type) {
        return type == 1 ? "wait" : type == 2 ? "move" : type == 3 ? "mine" : type == 4 ? "farm" : "none";
    }

    static String statusKey(int type, int status) {
        return type == 0 ? "idle" : status >= 0 && status < JobStatus.values().length ?
                JobStatus.values()[status].name().toLowerCase(java.util.Locale.ROOT) : "unknown";
    }

    private void renderVitals(GuiGraphics graphics, Mob worker, int vx, int vy) {
        int max = visibleHearts(worker.getMaxHealth());
        for (int i = 0; i < max; i++) {
            int x = vx + 4 + (i % HEARTS_PER_ROW) * 10;
            int y = vy + 4 + (i / HEARTS_PER_ROW) * 11;
            graphics.blitSprite(ResourceLocation.withDefaultNamespace("hud/heart/container"), x, y, 9, 9);
            String sprite = heartFill(worker.getHealth(), i) == 2 ? "full" : heartFill(worker.getHealth(), i) == 1 ? "half" : null;
            if (sprite != null) graphics.blitSprite(ResourceLocation.withDefaultNamespace("hud/heart/" + sprite), x, y, 9, 9);
        }
        graphics.blitSprite(ResourceLocation.withDefaultNamespace("hud/armor_full"), vx + 4, vy + 28, 9, 9);
        graphics.drawString(font, String.valueOf((int) worker.getAttributeValue(Attributes.ARMOR)), vx + 16, vy + 29, 0x202020, false);
        graphics.drawString(font, (int) Math.ceil(worker.getHealth()) + "/" + (int) Math.ceil(worker.getMaxHealth()), vx + 4, vy + 39, 0x404040, false);
    }

    private void renderVitalsCompact(GuiGraphics graphics, Mob worker, int x, int y) {
        Component vitals = Component.literal((int) Math.ceil(worker.getHealth()) + "/" + (int) Math.ceil(worker.getMaxHealth()) + " HP  ")
                .append(Component.translatable("gui.elstarticket.armor_value", (int) worker.getAttributeValue(Attributes.ARMOR)));
        graphics.drawString(font, vitals, x, y, 0x404040, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (menu.candidateCount() != 1) {
            graphics.drawString(font, title, 8, 7, 0x202020, false);
            graphics.drawString(font, Component.translatable("gui.elstarticket.select"), 8, 114, 0x202020, false);
            return;
        }

        Mob worker = menu.worker();
        if (worker == null) return;

        if (layoutPlan.previewW() > 0) {
            graphics.drawString(font, font.plainSubstrByWidth(worker.getDisplayName().getString(), 110), 8, 6, 0x202020, false);
            renderVitals(graphics, worker, layoutPlan.vitalsX(), layoutPlan.vitalsY());

            graphics.drawString(font, Component.translatable("gui.elstarticket.task_list"), layoutPlan.taskX() + 4, layoutPlan.taskY() - 10, 0x202020, false);
            if (menu.jobType() != 0) {
                String job = jobKey(menu.jobType());
                String status = statusKey(menu.jobType(), menu.jobStatus());
                graphics.drawString(font, Component.translatable("gui.elstarticket.job_type." + job), layoutPlan.taskX() + 6, layoutPlan.taskY() + 6, 0x101010, false);
                Component targetText = menu.hasTarget() ? Component.translatable("gui.elstarticket.target_saved") : Component.translatable("gui.elstarticket.target_none");
                graphics.drawString(font, targetText, layoutPlan.taskX() + 6, layoutPlan.taskY() + 18, 0x555555, false);
                Component statusComp = Component.literal("[").append(Component.translatable("gui.elstarticket.job_status." + status)).append("]");
                int statusColor = menu.jobStatus() == JobStatus.RUNNING.ordinal() ? 0x106010 :
                                  menu.jobStatus() == JobStatus.PAUSED.ordinal() ? 0x805010 :
                                  menu.jobStatus() == JobStatus.ASSIGNED.ordinal() ? 0x104080 : 0x404040;
                graphics.drawString(font, statusComp, layoutPlan.taskX() + 64, layoutPlan.taskY() + 6, statusColor, false);
            } else {
                graphics.drawString(font, Component.translatable("gui.elstarticket.no_active_tasks"), layoutPlan.taskX() + 6, layoutPlan.taskY() + 8, 0x606060, false);
                graphics.drawString(font, Component.translatable("gui.elstarticket.job_status.idle"), layoutPlan.taskX() + 6, layoutPlan.taskY() + 18, 0x707070, false);
            }

            Component homeDesc = menu.hasHome() ? Component.translatable("gui.elstarticket.home_zone_desc") : Component.translatable("gui.elstarticket.home_not_set");
            graphics.drawString(font, homeDesc, layoutPlan.behaviorX() + 4, layoutPlan.behaviorY() + 24, 0x404040, false);
        } else {
            graphics.drawString(font, font.plainSubstrByWidth(worker.getDisplayName().getString(), 65), 8, 6, 0x202020, false);
            renderVitalsCompact(graphics, worker, 76, 6);
        }

        if (layoutPlan.storageW() > 0) {
            graphics.drawString(font, Component.translatable("gui.elstarticket.storage"), layoutPlan.storageX(), layoutPlan.storageY() - 11, 0x202020, false);
        }
        if (layoutPlan.playerInvW() > 0) {
            graphics.drawString(font, Component.translatable("gui.elstarticket.player_inventory"), layoutPlan.playerInvX(), layoutPlan.playerInvY() - 11, 0x202020, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean singleWorker = menu.candidateCount() == 1;
        tabAll.visible = singleWorker && !layoutPlan.compact();
        tabWorker.visible = singleWorker;
        tabItems.visible = singleWorker;
        tabAll.active = activeTab != -1;
        tabWorker.active = activeTab != 0;
        tabItems.active = activeTab != 1;

        cancel.visible = singleWorker && layoutPlan.activeTab() != 1;
        cancel.active = cancel.visible && menu.jobType() != 0;
        addTask.visible = singleWorker && layoutPlan.activeTab() != 1;
        if (addFarmBtn != null) addFarmBtn.visible = singleWorker && layoutPlan.activeTab() != 1;
        if (addMoveBtn != null) addMoveBtn.visible = singleWorker && layoutPlan.activeTab() != 1;
        followBtn.visible = singleWorker && layoutPlan.activeTab() != 1;
        standBtn.visible = singleWorker && layoutPlan.activeTab() != 1;
        homeBtn.visible = singleWorker && layoutPlan.activeTab() != 1;

        if (followBtn.visible) {
            followBtn.setMessage(menu.behavior() == 0 ? Component.literal("● ").append(Component.translatable("gui.elstarticket.follow")) : Component.translatable("gui.elstarticket.follow"));
            followBtn.active = menu.behavior() != 0;
            standBtn.setMessage(menu.behavior() == 1 ? Component.literal("● ").append(Component.translatable("gui.elstarticket.stand")) : Component.translatable("gui.elstarticket.stand"));
            standBtn.active = menu.behavior() != 1;
            homeBtn.setMessage(menu.hasHome() ? Component.translatable("gui.elstarticket.home_active") : Component.translatable("gui.elstarticket.home"));
        }

        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);

        if (singleWorker && hoveredSlot != null && hoveredSlot.index < 6 && !hoveredSlot.hasItem() && hoveredSlot.isActive()) {
            String[] slots = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
            graphics.renderTooltip(font, Component.translatable("gui.elstarticket.slot." + slots[hoveredSlot.index]), mouseX, mouseY);
        }
        if (homeBtn.visible && homeBtn.isMouseOver(mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.translatable(menu.hasHome() ? "gui.elstarticket.home_zone_desc" : "gui.elstarticket.home_not_set"), mouseX, mouseY);
        }
    }
}
