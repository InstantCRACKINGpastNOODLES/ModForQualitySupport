package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Client-side in-game HUD overlay for the Binding Ticket.
 * Displays real-time binding status, active job authoring steps,
 * worksite/storage selection guidance, and server feedback.
 */
public final class BindingTicketHUD {

    public enum ClientStage {
        NONE,
        JOB_AUTHORING,
        SELECTING_WORKSITE,
        SELECTING_STORAGE,
        ORDER_SUBMITTED,
        ORDER_COMPLETED,
        ORDER_FAILED,
        ORDER_CANCELLED
    }

    private static ClientStage stage = ClientStage.NONE;
    private static UUID workerId = null;
    private static String workerName = "";
    private static String jobType = "";
    private static long requestedQuantity = 0;
    private static BlockPos worksitePos = null;
    private static String statusMessage = "";
    private static long messageSetTimeMs = 0;

    private static final long FEEDBACK_DISPLAY_DURATION_MS = 4000L;

    private BindingTicketHUD() {
    }

    public static synchronized void updateSession(
            int stageOrdinal,
            UUID id,
            String name,
            String job,
            long quantity,
            BlockPos worksite,
            String message
    ) {
        if (stageOrdinal < 0 || stageOrdinal >= ClientStage.values().length) {
            stage = ClientStage.NONE;
        } else {
            stage = ClientStage.values()[stageOrdinal];
        }
        workerId = id;
        workerName = name != null ? name : "";
        jobType = job != null ? job : "";
        requestedQuantity = quantity;
        worksitePos = worksite;
        statusMessage = message != null ? message : "";
        messageSetTimeMs = System.currentTimeMillis();
    }

    public static synchronized void clear() {
        stage = ClientStage.NONE;
        workerId = null;
        workerName = "";
        jobType = "";
        requestedQuantity = 0;
        worksitePos = null;
        statusMessage = "";
        messageSetTimeMs = 0;
    }

    public static synchronized ClientStage getStage() {
        return stage;
    }

    public static synchronized String getJobType() {
        return jobType;
    }

    public static synchronized long getRequestedQuantity() {
        return requestedQuantity;
    }

    public static synchronized BlockPos getWorksitePos() {
        return worksitePos;
    }

    public static synchronized UUID getActiveWorkerId() {
        return workerId;
    }

    public static synchronized boolean isConfirmedAck() {
        return (stage == ClientStage.ORDER_COMPLETED || stage == ClientStage.ORDER_SUBMITTED) &&
                (System.currentTimeMillis() - messageSetTimeMs < FEEDBACK_DISPLAY_DURATION_MS);
    }

    public static synchronized boolean isAuthoringActive() {
        return stage == ClientStage.JOB_AUTHORING ||
                stage == ClientStage.SELECTING_WORKSITE ||
                stage == ClientStage.SELECTING_STORAGE;
    }

    public static synchronized boolean isInSelection() {
        return stage == ClientStage.SELECTING_WORKSITE || stage == ClientStage.SELECTING_STORAGE;
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        ItemStack ticket = (mainHand.getItem() instanceof BindingTicketItem) ? mainHand :
                ((offHand.getItem() instanceof BindingTicketItem) ? offHand : ItemStack.EMPTY);
        boolean hasTicket = !ticket.isEmpty();

        boolean inAuthoring = isAuthoringActive();
        boolean hasRecentFeedback = (System.currentTimeMillis() - messageSetTimeMs < FEEDBACK_DISPLAY_DURATION_MS) &&
                (stage == ClientStage.ORDER_SUBMITTED || stage == ClientStage.ORDER_COMPLETED ||
                 stage == ClientStage.ORDER_FAILED || stage == ClientStage.ORDER_CANCELLED);

        if (!hasTicket && !inAuthoring && !hasRecentFeedback) {
            return;
        }

        Font font = mc.font;
        int screenW = graphics.guiWidth();
        int panelW = 280;
        int panelH = 38;
        int x = (screenW - panelW) / 2;
        int y = 10;

        // Draw HUD container panel
        int bgColor = 0xDD12151A;
        int borderColor = inAuthoring ? 0xFFFFAA00 : 0xFF4A5568;
        if (stage == ClientStage.ORDER_COMPLETED) borderColor = 0xFF22C55E;
        if (stage == ClientStage.ORDER_FAILED || stage == ClientStage.ORDER_CANCELLED) borderColor = 0xFFEF4444;

        graphics.fill(x, y, x + panelW, y + panelH, bgColor);
        // Border
        graphics.fill(x, y, x + panelW, y + 1, borderColor);
        graphics.fill(x, y + panelH - 1, x + panelW, y + panelH, borderColor);
        graphics.fill(x, y, x + 1, y + panelH, borderColor);
        graphics.fill(x + panelW - 1, y, x + panelW, y + panelH, borderColor);

        int textY = y + 4;

        if (stage == ClientStage.JOB_AUTHORING) {
            boolean isReassign = statusMessage.contains("Reassignment");
            String title = (isReassign ? "§6§lJOB REASSIGNMENT: §f" : "§6§lJOB AUTHORING: §f") + (!workerName.isEmpty() ? workerName : "Worker");
            String prompt = "§e▶ Click block: set worksite | Sneak-click air: switch type";
            String sub = statusMessage.isEmpty() ? "§8[Hotbar switch allowed | Sneak-click ticket to cancel]" : "§e" + statusMessage;

            graphics.drawString(font, title, x + 8, textY, 0xFFFFFF, false);
            graphics.drawString(font, prompt, x + 8, textY + 11, 0xFFFFFF, false);
            graphics.drawString(font, sub, x + 8, textY + 22, 0xFFFFFF, false);
        } else if (stage == ClientStage.SELECTING_WORKSITE) {
            String title = "§6§lORDER: " + jobType.toUpperCase() + " §7(Quota: " + requestedQuantity + ")";
            String prompt = "§e▶ Step 1: Right-click a block to select worksite";
            String sub = statusMessage.isEmpty() ? "§8[Sneak + Right-Click ticket to cancel]" : "§c" + statusMessage;

            graphics.drawString(font, title, x + 8, textY, 0xFFFFFF, false);
            graphics.drawString(font, prompt, x + 8, textY + 11, 0xFFFFFF, false);
            graphics.drawString(font, sub, x + 8, textY + 22, 0xFFFFFF, false);
        } else if (stage == ClientStage.SELECTING_STORAGE) {
            String siteStr = worksitePos != null ? worksitePos.toShortString() : "Saved";
            String title = "§6§lORDER: " + jobType.toUpperCase() + " §7| Worksite: §f" + siteStr;
            String prompt = "§a▶ Step 2: Right-click storage container (Chest/Barrel)";
            String sub = statusMessage.isEmpty() ? "§8[Within 32 blocks | Hotbar switch allowed to place chest]" : "§c" + statusMessage;

            graphics.drawString(font, title, x + 8, textY, 0xFFFFFF, false);
            graphics.drawString(font, prompt, x + 8, textY + 11, 0xFFFFFF, false);
            graphics.drawString(font, sub, x + 8, textY + 22, 0xFFFFFF, false);
        } else if (hasRecentFeedback) {
            if (stage == ClientStage.ORDER_COMPLETED || stage == ClientStage.ORDER_SUBMITTED) {
                String title = "§a§l✔ Order Activated!";
                String msg = statusMessage.isEmpty() ? "Worker is now executing " + jobType.toUpperCase() : statusMessage;
                graphics.drawString(font, title, x + 8, textY + 4, 0xFFFFFF, false);
                graphics.drawString(font, "§f" + msg, x + 8, textY + 18, 0xFFFFFF, false);
            } else {
                String title = "§c§l✖ Order Cancelled";
                String msg = statusMessage.isEmpty() ? "The current order session was cancelled." : statusMessage;
                graphics.drawString(font, title, x + 8, textY + 4, 0xFFFFFF, false);
                graphics.drawString(font, "§f" + msg, x + 8, textY + 18, 0xFFFFFF, false);
            }
        } else if (hasTicket) {
            Optional<UUID> boundId = BindingTicketItem.getBoundWorkerId(ticket);
            if (boundId.isEmpty()) {
                // Unbound ticket
                String title = "§e§l[Binding Ticket — Unbound]";
                String prompt = "§fRight-click a maid or tamed worker to bind";
                String targetInfo = "§7Aim at worker within 8 blocks";

                HitResult hit = mc.hitResult;
                if (hit instanceof EntityHitResult entityHit) {
                    Entity target = entityHit.getEntity();
                    if (target instanceof Mob mob) {
                        targetInfo = "§aTarget: " + mob.getDisplayName().getString() + " (Click to bind)";
                    }
                }
                graphics.drawString(font, title, x + 8, textY, 0xFFFFFF, false);
                graphics.drawString(font, prompt, x + 8, textY + 11, 0xFFFFFF, false);
                graphics.drawString(font, targetInfo, x + 8, textY + 22, 0xFFFFFF, false);
            } else {
                // Bound ticket in idle state (NO tether rendered)
                UUID uuid = boundId.get();
                String shortId = uuid.toString().substring(0, 8) + "...";
                String title = "§b§l[Binding Ticket — Bound: " + shortId + "]";
                String prompt = "§fRight-click: Manage UI | Shift+Right-Click: Job Authoring";
                String shortcut = "§7Tether activates in Authoring mode";

                HitResult hit = mc.hitResult;
                if (hit instanceof EntityHitResult entityHit && entityHit.getEntity().getUUID().equals(uuid)) {
                    prompt = "§aTarget: Bound Worker (Shift+Right-Click: Author Job)";
                }
                graphics.drawString(font, title, x + 8, textY, 0xFFFFFF, false);
                graphics.drawString(font, prompt, x + 8, textY + 11, 0xFFFFFF, false);
                graphics.drawString(font, shortcut, x + 8, textY + 22, 0xFFFFFF, false);
            }
        }
    }
}
