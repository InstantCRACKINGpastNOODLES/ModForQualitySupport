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
    private static final int HEARTS_PER_ROW = 7;
    private static final int VISIBLE_HEARTS = 21;
    private static final int PANEL_WIDTH = 316;
    private static final String[] SLOT_ICONS = {"H", "C", "L", "B", "M", "O"};
    private int page;
    private final List<Button> choices = new ArrayList<>();
    private final List<Button> unavailable = new ArrayList<>();
    private Button cancel;
    private Button previous;
    private Button next;
    public WorkerManagementScreen(WorkerManagementMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_WIDTH;
        this.imageHeight = 270;
    }

    @Override
    protected void init() {
        super.init();
        choices.clear();
        unavailable.clear();
        for (int i = 0; i < 8; i++) {
            int index = i;
            choices.add(addRenderableWidget(Button.builder(Component.empty(), button ->
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, page * 8 + index))
                    .bounds(leftPos + 8 + i % 2 * 108, topPos + 22 + i / 2 * 21, 105, 20).build()));
        }
        previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; updateChoices(); })
                .bounds(leftPos + 8, topPos + 108, 20, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; updateChoices(); })
                .bounds(leftPos + 34, topPos + 108, 20, 20).build());
        cancel = addRenderableWidget(Button.builder(Component.translatable("gui.elstarticket.cancel"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 100))
                .bounds(leftPos + 225, topPos + 91, 82, 20).build());
        addUnavailable("gui.elstarticket.follow", 10, 140);
        addUnavailable("gui.elstarticket.stand", 63, 140);
        addUnavailable("gui.elstarticket.home", 10, 162);
        addUnavailable("gui.elstarticket.wander", 63, 162);
        addUnavailable("gui.elstarticket.select_target", 225, 115);
        addUnavailable("gui.elstarticket.lock", 225, 139);
        updateChoices();
    }

    private void addUnavailable(String key, int x, int y) {
        Button button = addRenderableWidget(Button.builder(Component.translatable(key), unused -> {})
                .bounds(leftPos + x, topPos + y, key.contains("target") || key.contains("lock") ? 82 : 51, 20).build());
        button.active = false;
        unavailable.add(button);
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
        panel(graphics, 7, 17, 210, 56, 0xffababab);
        panel(graphics, 10, 19, 90, 52, 0xff242424);
        panel(graphics, 171, 17, 42, 59, 0xffb5b5b5);
        panel(graphics, 26, 80, 98, 61, 0xffb5b5b5);
        panel(graphics, 218, 17, 91, 170, 0xffb5b5b5);
        panel(graphics, 26, 186, 170, 80, 0xffb5b5b5);
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            panel(graphics, slot.x - 1, slot.y - 1, 18, 18, 0xff8b8b8b);
            if (i < 6 && !slot.hasItem()) graphics.drawCenteredString(font, SLOT_ICONS[i],
                    leftPos + slot.x + 8, topPos + slot.y + 4, 0xff606060);
        }
        Mob worker = menu.worker();
        if (worker != null) InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                leftPos + 12, topPos + 21, leftPos + 98, topPos + 68, 23, 0.0625f, mouseX, mouseY, worker);
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
        return type == 1 ? "wait" : type == 2 ? "move" : "none";
    }

    static String statusKey(int type, int status) {
        return type == 0 ? "idle" : status >= 0 && status < JobStatus.values().length ?
                JobStatus.values()[status].name().toLowerCase(java.util.Locale.ROOT) : "unknown";
    }

    private void renderVitals(GuiGraphics graphics, Mob worker) {
        int max = visibleHearts(worker.getMaxHealth());
        for (int i = 0; i < max; i++) {
            int x = 105 + i % HEARTS_PER_ROW * 9;
            int y = 23 + i / HEARTS_PER_ROW * 10;
            graphics.blitSprite(ResourceLocation.withDefaultNamespace("hud/heart/container"), x, y, 9, 9);
            String sprite = heartFill(worker.getHealth(), i) == 2 ? "full" : heartFill(worker.getHealth(), i) == 1 ? "half" : null;
            if (sprite != null) graphics.blitSprite(ResourceLocation.withDefaultNamespace("hud/heart/" + sprite), x, y, 9, 9);
        }
        graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.elstarticket.health_values",
                (int) Math.ceil(worker.getHealth()), (int) Math.ceil(worker.getMaxHealth())).getString(), 63),
                105, 54, 0x202020, false);
        graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.elstarticket.armor_value",
                (int) worker.getAttributeValue(Attributes.ARMOR)).getString(), 63), 105, 64, 0x202020, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 7, 0x202020, false);
        if (menu.candidateCount() != 1) {
            graphics.drawString(font, Component.translatable("gui.elstarticket.select"), 8, 134, 0x202020);
            return;
        }
        Mob worker = menu.worker();
        if (worker != null) {
            graphics.drawString(font, font.plainSubstrByWidth(worker.getDisplayName().getString(), 140), 8, 7, 0x202020, false);
            renderVitals(graphics, worker);
            String job = jobKey(menu.jobType());
            String status = statusKey(menu.jobType(), menu.jobStatus());
            Component jobText = Component.translatable("gui.elstarticket.job_type." + job);
            if (menu.hasTarget()) jobText = Component.translatable("gui.elstarticket.target", jobText);
            graphics.drawString(font, Component.translatable("gui.elstarticket.tasks"), 225, 23, 0x202020, false);
            graphics.drawString(font, font.plainSubstrByWidth(jobText.getString(), 80), 225, 41, 0x202020, false);
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.elstarticket.job_status." + status).getString(), 80), 225, 55, 0x404040, false);
            graphics.drawString(font, Component.translatable("gui.elstarticket.owned"), 105, 7, 0x505050, false);
            graphics.drawString(font, Component.translatable("gui.elstarticket.storage"), 34, 73, 0x202020, false);
            graphics.drawString(font, Component.translatable("gui.elstarticket.player_inventory"), 34, 179, 0x202020, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        cancel.visible = menu.candidateCount() == 1;
        cancel.active = cancel.visible && menu.jobType() != 0;
        unavailable.forEach(button -> button.visible = menu.candidateCount() == 1);
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (menu.candidateCount() == 1 && hoveredSlot != null && hoveredSlot.index < 6 && !hoveredSlot.hasItem()) {
            String[] slots = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
            graphics.renderTooltip(font, Component.translatable("gui.elstarticket.slot." + slots[hoveredSlot.index]), mouseX, mouseY);
        }
        if (unavailable.stream().anyMatch(button -> button.visible && button.isMouseOver(mouseX, mouseY)))
            graphics.renderTooltip(font, Component.translatable("gui.elstarticket.unavailable"), mouseX, mouseY);
    }
}