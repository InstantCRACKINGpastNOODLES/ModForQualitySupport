package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Interactive client-side configuration screen for Touhou Little Maid miner jobs.
 * Supports TUNNEL, STAIR, and SPECIFIC_BLOCK excavation modes.
 * Implements multi-selection for block IDs and block tags in SPECIFIC_BLOCK mode.
 */
public class MinerJobConfigScreen extends Screen {

    private final OpenMinerConfigPayload initialData;
    private MinerJobData.MiningMode currentMode = MinerJobData.MiningMode.TUNNEL;

    // Common widgets
    private EditBox quotaEdit;

    // Mode A: TUNNEL widgets
    private Direction tunnelDirection;
    private Button btnTunnelDirection;
    private EditBox tunnelMaxTravelEdit;

    // Mode B: STAIR widgets
    private Direction stairDirection;
    private Button btnStairDirection;
    private boolean stairAscending = false;
    private Button btnStairAscending;
    private EditBox stairTargetYEdit;
    private EditBox stairMaxTravelEdit;

    // Mode C: SPECIFIC_BLOCK widgets
    private EditBox specificRadiusEdit;
    private EditBox inputTargetEdit;
    private Button btnAddTarget;
    private Button btnCandidateTag;
    private int candidateTagIndex = 0;
    private final List<String> selectedTargets = new ArrayList<>();
    private final List<Button> removeButtons = new ArrayList<>();
    private Button btnClearTargets;
    private int targetListPage = 0;
    private static final int TARGETS_PER_PAGE = 3;
    private Button btnPrevPage;
    private Button btnNextPage;

    // Navigation and Action buttons
    private Button btnTabTunnel;
    private Button btnTabStair;
    private Button btnTabSpecific;
    private Button btnConfirm;
    private Button btnCancel;

    private String validationError = null;

    public MinerJobConfigScreen(OpenMinerConfigPayload initialData) {
        super(Component.translatable("gui.elstarticket.miner_config.title"));
        this.initialData = initialData;
        this.tunnelDirection = (initialData.initialDirection() != null && initialData.initialDirection().getAxis().isHorizontal())
                ? initialData.initialDirection() : Direction.NORTH;
        this.stairDirection = this.tunnelDirection;

        // Initialize with worksite block ID if available
        if (initialData.initialBlockId() != null && !initialData.initialBlockId().isEmpty()) {
            selectedTargets.add(initialData.initialBlockId());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        removeButtons.clear();

        int panelW = 300;
        int panelH = 224;
        int left = (this.width - panelW) / 2;
        int top = (this.height - panelH) / 2;

        int tabW = 90;
        int tabH = 20;

        // Mode Selection Tabs
        btnTabTunnel = addRenderableWidget(Button.builder(Component.literal("Tunnel"), b -> switchTab(MinerJobData.MiningMode.TUNNEL))
                .bounds(left + 12, top + 26, tabW, tabH).build());

        btnTabStair = addRenderableWidget(Button.builder(Component.literal("Stair"), b -> switchTab(MinerJobData.MiningMode.STAIR))
                .bounds(left + 12 + tabW + 4, top + 26, tabW, tabH).build());

        btnTabSpecific = addRenderableWidget(Button.builder(Component.literal("Specific Block"), b -> switchTab(MinerJobData.MiningMode.SPECIFIC_BLOCK))
                .bounds(left + 12 + (tabW + 4) * 2, top + 26, tabW, tabH).build());

        // Common Quota input
        quotaEdit = addRenderableWidget(new EditBox(this.font, left + 140, top + 52, 145, 18, Component.literal("Quota")));
        quotaEdit.setValue(String.valueOf(initialData.defaultQuota() > 0 ? initialData.defaultQuota() : 64));

        // Mode A: TUNNEL
        btnTunnelDirection = addRenderableWidget(Button.builder(Component.literal("Direction: " + tunnelDirection.getName().toUpperCase()), b -> {
            tunnelDirection = cycleHorizontalDirection(tunnelDirection);
            btnTunnelDirection.setMessage(Component.literal("Direction: " + tunnelDirection.getName().toUpperCase()));
        }).bounds(left + 140, top + 74, 145, 20).build());

        tunnelMaxTravelEdit = addRenderableWidget(new EditBox(this.font, left + 140, top + 98, 145, 18, Component.literal("Max Travel")));
        tunnelMaxTravelEdit.setValue("64");

        // Mode B: STAIR
        btnStairDirection = addRenderableWidget(Button.builder(Component.literal("Direction: " + stairDirection.getName().toUpperCase()), b -> {
            stairDirection = cycleHorizontalDirection(stairDirection);
            btnStairDirection.setMessage(Component.literal("Direction: " + stairDirection.getName().toUpperCase()));
        }).bounds(left + 140, top + 74, 145, 20).build());

        btnStairAscending = addRenderableWidget(Button.builder(Component.literal(stairAscending ? "Type: Ascending (+Y)" : "Type: Descending (-Y)"), b -> {
            stairAscending = !stairAscending;
            btnStairAscending.setMessage(Component.literal(stairAscending ? "Type: Ascending (+Y)" : "Type: Descending (-Y)"));
            int defaultY = stairAscending ? (initialData.anchorPos().getY() + 10) : (initialData.anchorPos().getY() - 10);
            stairTargetYEdit.setValue(String.valueOf(defaultY));
        }).bounds(left + 140, top + 96, 145, 20).build());

        stairTargetYEdit = addRenderableWidget(new EditBox(this.font, left + 140, top + 118, 145, 18, Component.literal("Target Y")));
        stairTargetYEdit.setValue(String.valueOf(initialData.anchorPos().getY() - 10));

        stairMaxTravelEdit = addRenderableWidget(new EditBox(this.font, left + 140, top + 138, 145, 18, Component.literal("Max Depth")));
        stairMaxTravelEdit.setValue("64");

        // Mode C: SPECIFIC_BLOCK
        specificRadiusEdit = addRenderableWidget(new EditBox(this.font, left + 140, top + 52, 145, 18, Component.literal("Radius")));
        specificRadiusEdit.setValue("8");

        inputTargetEdit = addRenderableWidget(new EditBox(this.font, left + 15, top + 88, 145, 18, Component.literal("Target Block / Tag")));
        inputTargetEdit.setHint(Component.literal("e.g. #c:ores or iron_ore"));

        btnAddTarget = addRenderableWidget(Button.builder(Component.literal("+ Add"), b -> addTargetFromInput())
                .bounds(left + 165, top + 88, 48, 18).build());

        String tagLabel = (!initialData.candidateTags().isEmpty())
                ? "+ " + initialData.candidateTags().get(0) : "+ Tag";
        btnCandidateTag = addRenderableWidget(Button.builder(Component.literal(tagLabel), b -> addCandidateTag())
                .bounds(left + 217, top + 88, 68, 18).build());

        btnClearTargets = addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            selectedTargets.clear();
            targetListPage = 0;
            rebuildTargetListWidgets();
        }).bounds(left + 242, top + 110, 43, 16).build());

        btnPrevPage = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (targetListPage > 0) {
                targetListPage--;
                rebuildTargetListWidgets();
            }
        }).bounds(left + 195, top + 110, 20, 16).build());

        btnNextPage = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if ((targetListPage + 1) * TARGETS_PER_PAGE < selectedTargets.size()) {
                targetListPage++;
                rebuildTargetListWidgets();
            }
        }).bounds(left + 218, top + 110, 20, 16).build());

        // Action buttons
        btnConfirm = addRenderableWidget(Button.builder(Component.literal("Confirm & Apply"), b -> onConfirm())
                .bounds(left + 20, top + 192, 125, 20).build());

        btnCancel = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + 155, top + 192, 125, 20).build());

        rebuildTargetListWidgets();
        updateTabVisibility();
    }

    private void addTargetFromInput() {
        if (inputTargetEdit == null) return;
        String text = inputTargetEdit.getValue().trim();
        if (text.isEmpty()) {
            validationError = "Enter a block ID or tag";
            return;
        }

        if (text.startsWith("#")) {
            String tagPath = text.substring(1);
            if (ResourceLocation.tryParse(tagPath) == null) {
                validationError = "Invalid tag resource location format";
                return;
            }
        } else {
            ResourceLocation parsed = ResourceLocation.tryParse(text);
            if (parsed == null) {
                validationError = "Invalid block resource location format";
                return;
            }
            if (!BuiltInRegistries.BLOCK.containsKey(parsed)) {
                // If it contains a colon, allow modded blocks with a warning
                if (!text.contains(":")) {
                    validationError = "Block not found in registry";
                    return;
                }
            }
        }

        if (selectedTargets.contains(text)) {
            validationError = "Target already added!";
            return;
        }

        selectedTargets.add(text);
        inputTargetEdit.setValue("");
        validationError = null;
        targetListPage = (selectedTargets.size() - 1) / TARGETS_PER_PAGE;
        rebuildTargetListWidgets();
    }

    private void addCandidateTag() {
        if (initialData.candidateTags().isEmpty()) return;
        String tag = initialData.candidateTags().get(candidateTagIndex);
        candidateTagIndex = (candidateTagIndex + 1) % initialData.candidateTags().size();
        String nextTag = initialData.candidateTags().get(candidateTagIndex);
        btnCandidateTag.setMessage(Component.literal("+ " + nextTag));

        if (!selectedTargets.contains(tag)) {
            selectedTargets.add(tag);
            validationError = null;
            targetListPage = (selectedTargets.size() - 1) / TARGETS_PER_PAGE;
            rebuildTargetListWidgets();
        } else {
            validationError = "Tag already in list";
        }
    }

    private void rebuildTargetListWidgets() {
        // Remove existing remove buttons
        for (Button btn : removeButtons) {
            removeWidget(btn);
        }
        removeButtons.clear();

        if (currentMode != MinerJobData.MiningMode.SPECIFIC_BLOCK) {
            return;
        }

        int panelW = 300;
        int left = (this.width - panelW) / 2;
        int top = (this.height - 224) / 2;

        int startIndex = targetListPage * TARGETS_PER_PAGE;
        int endIndex = Math.min(startIndex + TARGETS_PER_PAGE, selectedTargets.size());

        for (int i = startIndex; i < endIndex; i++) {
            final int indexToRemove = i;
            int row = i - startIndex;
            Button removeBtn = addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                if (indexToRemove >= 0 && indexToRemove < selectedTargets.size()) {
                    selectedTargets.remove(indexToRemove);
                    if (targetListPage > 0 && targetListPage * TARGETS_PER_PAGE >= selectedTargets.size()) {
                        targetListPage--;
                    }
                    rebuildTargetListWidgets();
                }
            }).bounds(left + 265, top + 128 + (row * 18), 18, 16).build());
            removeButtons.add(removeBtn);
        }

        if (btnPrevPage != null) btnPrevPage.active = targetListPage > 0;
        if (btnNextPage != null) btnNextPage.active = (targetListPage + 1) * TARGETS_PER_PAGE < selectedTargets.size();
    }

    private Direction cycleHorizontalDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private void switchTab(MinerJobData.MiningMode mode) {
        this.currentMode = mode;
        this.validationError = null;
        rebuildTargetListWidgets();
        updateTabVisibility();
    }

    private void updateTabVisibility() {
        btnTabTunnel.active = (currentMode != MinerJobData.MiningMode.TUNNEL);
        btnTabStair.active = (currentMode != MinerJobData.MiningMode.STAIR);
        btnTabSpecific.active = (currentMode != MinerJobData.MiningMode.SPECIFIC_BLOCK);

        boolean isTunnel = (currentMode == MinerJobData.MiningMode.TUNNEL);
        btnTunnelDirection.visible = isTunnel;
        tunnelMaxTravelEdit.visible = isTunnel;
        quotaEdit.visible = !isTunnel ? (currentMode == MinerJobData.MiningMode.STAIR) : true;

        boolean isStair = (currentMode == MinerJobData.MiningMode.STAIR);
        btnStairDirection.visible = isStair;
        btnStairAscending.visible = isStair;
        stairTargetYEdit.visible = isStair;
        stairMaxTravelEdit.visible = isStair;

        boolean isSpecific = (currentMode == MinerJobData.MiningMode.SPECIFIC_BLOCK);
        specificRadiusEdit.visible = isSpecific;
        inputTargetEdit.visible = isSpecific;
        btnAddTarget.visible = isSpecific;
        btnCandidateTag.visible = isSpecific;
        btnClearTargets.visible = isSpecific;
        btnPrevPage.visible = isSpecific;
        btnNextPage.visible = isSpecific;
        for (Button b : removeButtons) {
            b.visible = isSpecific;
        }
    }

    private void onConfirm() {
        long quota = 64L;
        if (currentMode != MinerJobData.MiningMode.SPECIFIC_BLOCK) {
            try {
                quota = Long.parseLong(quotaEdit.getValue().trim());
                if (quota < 1 || quota > 64000) {
                    validationError = "Quota must be 1 - 64,000";
                    return;
                }
            } catch (NumberFormatException e) {
                validationError = "Invalid quota number";
                return;
            }
        }

        MinerConfigSubmitPayload payload;
        switch (currentMode) {
            case TUNNEL -> {
                int maxTravel;
                try {
                    maxTravel = Integer.parseInt(tunnelMaxTravelEdit.getValue().trim());
                    if (maxTravel < 1 || maxTravel > 256) {
                        validationError = "Max travel must be 1 - 256";
                        return;
                    }
                } catch (NumberFormatException e) {
                    validationError = "Invalid max travel distance";
                    return;
                }
                payload = new MinerConfigSubmitPayload(
                        initialData.workerId(),
                        initialData.anchorPos(),
                        "TUNNEL",
                        quota,
                        maxTravel,
                        tunnelDirection,
                        false,
                        64,
                        8,
                        List.of(),
                        List.of()
                );
            }
            case STAIR -> {
                int maxTravel;
                try {
                    maxTravel = Integer.parseInt(stairMaxTravelEdit.getValue().trim());
                    if (maxTravel < 1 || maxTravel > 256) {
                        validationError = "Max travel must be 1 - 256";
                        return;
                    }
                } catch (NumberFormatException e) {
                    validationError = "Invalid max travel distance";
                    return;
                }
                int targetY;
                try {
                    targetY = Integer.parseInt(stairTargetYEdit.getValue().trim());
                    if (targetY < -64 || targetY > 320) {
                        validationError = "Target Y outside valid height";
                        return;
                    }
                } catch (NumberFormatException e) {
                    validationError = "Invalid target Y coordinate";
                    return;
                }

                int anchorY = initialData.anchorPos().getY();
                if (stairAscending && targetY < anchorY) {
                    validationError = "Ascending requires Target Y >= anchor Y (" + anchorY + ")";
                    return;
                }
                if (!stairAscending && targetY > anchorY) {
                    validationError = "Descending requires Target Y <= anchor Y (" + anchorY + ")";
                    return;
                }

                payload = new MinerConfigSubmitPayload(
                        initialData.workerId(),
                        initialData.anchorPos(),
                        "STAIR",
                        quota,
                        maxTravel,
                        stairDirection,
                        stairAscending,
                        targetY,
                        8,
                        List.of(),
                        List.of()
                );
            }
            case SPECIFIC_BLOCK -> {
                int radius;
                try {
                    radius = Integer.parseInt(specificRadiusEdit.getValue().trim());
                    if (radius < 1 || radius > 32) {
                        validationError = "Radius must be 1 - 32";
                        return;
                    }
                } catch (NumberFormatException e) {
                    validationError = "Invalid search radius";
                    return;
                }

                if (selectedTargets.isEmpty()) {
                    validationError = "Select at least one block ID or tag";
                    return;
                }

                List<String> targetIds = new ArrayList<>();
                List<String> targetTags = new ArrayList<>();
                for (String entry : selectedTargets) {
                    if (entry.startsWith("#")) {
                        targetTags.add(entry);
                    } else {
                        targetIds.add(entry);
                    }
                }

                payload = new MinerConfigSubmitPayload(
                        initialData.workerId(),
                        initialData.anchorPos(),
                        "SPECIFIC_BLOCK",
                        64L,
                        64,
                        tunnelDirection,
                        false,
                        64,
                        radius,
                        targetIds,
                        targetTags
                );
            }
            default -> {
                return;
            }
        }

        PacketDistributor.sendToServer(payload);
        this.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int panelW = 300;
        int panelH = 224;
        int left = (this.width - panelW) / 2;
        int top = (this.height - panelH) / 2;

        // Background panel
        graphics.fill(left, top, left + panelW, top + panelH, 0xEE1E222B);
        // Border
        graphics.fill(left, top, left + panelW, top + 1, 0xFF555555);
        graphics.fill(left, top + panelH - 1, left + panelW, top + panelH, 0xFF555555);
        graphics.fill(left, top, left + 1, top + panelH, 0xFF555555);
        graphics.fill(left + panelW - 1, top, left + panelW, top + panelH, 0xFF555555);

        Font font = this.font;

        // Title and Anchor Pos info
        graphics.drawString(font, "§6§lMiner Job Configuration", left + 12, top + 8, 0xFFFFFF, false);
        String anchorStr = String.format("§7Worksite: §f[%d, %d, %d]",
                initialData.anchorPos().getX(), initialData.anchorPos().getY(), initialData.anchorPos().getZ());
        graphics.drawString(font, anchorStr, left + 12, top + 17, 0xFFFFFF, false);

        // Mode-specific labels
        if (currentMode == MinerJobData.MiningMode.TUNNEL) {
            graphics.drawString(font, "Block Quota:", left + 20, top + 56, 0xDDDDDD, false);
            graphics.drawString(font, "Tunnel Direction:", left + 20, top + 79, 0xDDDDDD, false);
            graphics.drawString(font, "Max Tunnel Length:", left + 20, top + 102, 0xDDDDDD, false);
            graphics.drawString(font, "§8Excavates 1x2 horizontal tunnel.", left + 20, top + 130, 0xAAAAAA, false);
        } else if (currentMode == MinerJobData.MiningMode.STAIR) {
            graphics.drawString(font, "Block Quota:", left + 20, top + 56, 0xDDDDDD, false);
            graphics.drawString(font, "Stair Direction:", left + 20, top + 79, 0xDDDDDD, false);
            graphics.drawString(font, "Stair Slope Type:", left + 20, top + 101, 0xDDDDDD, false);
            graphics.drawString(font, "Target Y Height:", left + 20, top + 122, 0xDDDDDD, false);
            graphics.drawString(font, "Max Travel Dist:", left + 20, top + 142, 0xDDDDDD, false);
            graphics.drawString(font, "§83D clearance & floor support.", left + 20, top + 164, 0xAAAAAA, false);
        } else if (currentMode == MinerJobData.MiningMode.SPECIFIC_BLOCK) {
            graphics.drawString(font, "Search Radius (R):", left + 20, top + 56, 0xDDDDDD, false);
            graphics.drawString(font, "Target ID / Tag:", left + 20, top + 76, 0xDDDDDD, false);

            int totalPages = Math.max(1, (int) Math.ceil((double) selectedTargets.size() / TARGETS_PER_PAGE));
            String listHeader = String.format("§eSelected Targets (%d) - Page %d/%d:", selectedTargets.size(), targetListPage + 1, totalPages);
            graphics.drawString(font, listHeader, left + 20, top + 114, 0xEEEEEE, false);

            // Render current page targets
            int startIndex = targetListPage * TARGETS_PER_PAGE;
            int endIndex = Math.min(startIndex + TARGETS_PER_PAGE, selectedTargets.size());
            for (int i = startIndex; i < endIndex; i++) {
                int row = i - startIndex;
                String target = selectedTargets.get(i);
                String prefix = target.startsWith("#") ? "§a[TAG] §f" : "§b[ID] §f";
                // Shorten string if too long for display
                String display = target.length() > 34 ? target.substring(0, 31) + "..." : target;
                graphics.drawString(font, prefix + display, left + 24, top + 132 + (row * 18), 0xFFFFFF, false);
            }

            if (selectedTargets.isEmpty()) {
                graphics.drawString(font, "§7(No targets added yet)", left + 24, top + 132, 0x888888, false);
            }
        }

        // Error message if validation failed
        if (validationError != null) {
            graphics.drawString(font, "§c" + validationError, left + 20, top + 180, 0xFF5555, false);
        }
    }
}
