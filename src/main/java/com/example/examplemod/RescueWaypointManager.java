package com.example.examplemod;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Client-Side Rescue Waypoint & Notification Manager.
 *
 * Invariants & Architecture:
 * 1. Stable Identity: Waypoint key is "elstarticket_rescue_" + workerId (UUID).
 *    Never relies solely on display name; two workers named "Reimu" maintain separate waypoints.
 * 2. Monotonicity & Stale Packet Rejection: Stale out-of-order packets with sequence < current sequence
 *    are discarded. Stale resolution packets from older eventIds cannot dismiss newer active alerts.
 * 3. Lifecycle Transitions:
 *    - Created: Creates waypoint in JourneyMap.
 *    - Worker Moves: Updates coordinates on existing waypoint.
 *    - Worker Changes Dimension: Removes waypoint from old dimension, creates in new dimension.
 *    - Rescue Resolved: Removes ONLY that worker's waypoint, leaving others untouched.
 * 4. Fallback Notification: If JourneyMap is absent or uninstalled, formatted chat notification
 *    with clickable coordinates is reliably delivered to the player.
 */
public final class RescueWaypointManager {

    public record TrackedRescueWaypoint(
            UUID workerId,
            String waypointId,
            String workerName,
            UUID ownerUUID,
            String ownerName,
            UUID eventId,
            ResourceLocation dimension,
            BlockPos workerPos,
            EquipmentCapabilityPolicy.RescueTrigger trigger,
            String reason,
            long sequence
    ) {}

    private static final Map<UUID, TrackedRescueWaypoint> TRACKED_WAYPOINTS = new ConcurrentHashMap<>();

    private RescueWaypointManager() {
    }

    public static void clearTrackedWaypoints() {
        TRACKED_WAYPOINTS.clear();
    }

    public static Optional<TrackedRescueWaypoint> getTrackedWaypoint(UUID workerId) {
        if (workerId == null) return Optional.empty();
        return Optional.ofNullable(TRACKED_WAYPOINTS.get(workerId));
    }

    public static Map<UUID, TrackedRescueWaypoint> getAllTrackedWaypoints() {
        return Collections.unmodifiableMap(TRACKED_WAYPOINTS);
    }

    public static String buildWaypointId(UUID workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        return "elstarticket_rescue_" + workerId;
    }

    /**
     * Handles incoming rescue alert or resolution from the server with strict monotonicity checks.
     */
    public static void handleRescueAlert(RescueAlertPayload payload) {
        if (payload == null) return;

        UUID workerId = payload.workerId();
        String waypointId = buildWaypointId(workerId);
        TrackedRescueWaypoint existing = TRACKED_WAYPOINTS.get(workerId);

        // Sequence Monotonicity: Reject stale out-of-order alerts
        if (existing != null) {
            if (payload.sequence() < existing.sequence()) {
                ExampleMod.LOGGER.debug("Discarding stale out-of-order rescue alert for worker {}: received seq {}, existing seq {}",
                        workerId, payload.sequence(), existing.sequence());
                return;
            }
        }

        // Case 1: Rescue Resolved
        if (payload.isResolved()) {
            if (existing != null && existing.eventId() != null && payload.eventId() != null) {
                if (!existing.eventId().equals(payload.eventId())) {
                    ExampleMod.LOGGER.debug("Discarding stale resolution request from older eventId {} (current is {})",
                            payload.eventId(), existing.eventId());
                    return;
                }
            }

            TrackedRescueWaypoint removed = TRACKED_WAYPOINTS.remove(workerId);
            if (removed != null) {
                JourneyMapBridge.removeWaypoint(removed.waypointId(), removed.dimension());
            } else {
                JourneyMapBridge.removeWaypoint(waypointId, payload.dimension());
            }

            // Display resolution notification to player
            sendClientNotification(Component.literal("§a[Elstar Ticket] Worker " + payload.workerName() + " rescue resolved.§r"));
            return;
        }

        // Case 2: Active Alert or Position/Dimension Update
        if (existing != null) {
            // Check for dimension migration
            if (!existing.dimension().equals(payload.dimension())) {
                JourneyMapBridge.removeWaypoint(existing.waypointId(), existing.dimension());
            }
        }

        TrackedRescueWaypoint updated = new TrackedRescueWaypoint(
                workerId,
                waypointId,
                payload.workerName(),
                payload.ownerUUID(),
                payload.ownerName(),
                payload.eventId(),
                payload.dimension(),
                payload.workerPos(),
                payload.trigger(),
                payload.reason(),
                payload.sequence()
        );

        TRACKED_WAYPOINTS.put(workerId, updated);

        // Attempt JourneyMap registration/update
        JourneyMapBridge.createOrUpdateWaypoint(
                waypointId,
                payload.dimension(),
                payload.workerPos(),
                payload.workerName() + " (Rescue)",
                payload.reason()
        );

        // Always display clickable chat notification (works with or without JourneyMap)
        sendClientNotification(buildFallbackNotificationComponent(payload));
    }

    /**
     * Builds a rich, clickable chat component directing the player to the rescue location.
     */
    public static MutableComponent buildFallbackNotificationComponent(RescueAlertPayload payload) {
        BlockPos pos = payload.workerPos();
        String tpCommand = String.format("/execute in %s run tp @s %d %d %d",
                payload.dimension(), pos.getX(), pos.getY(), pos.getZ());

        MutableComponent coordLink = Component.literal(String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ()))
                .setStyle(Style.EMPTY
                        .withColor(0x55FFFF)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpCommand))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to suggest teleport command: " + tpCommand))));

        return Component.literal("§c[Elstar Ticket Emergency] Worker §e" + payload.workerName() + "§c needs rescue at ")
                .append(coordLink)
                .append(Component.literal("§c in " + payload.dimension().getPath() + " (" + payload.reason() + ")§r"));
    }

    private static void sendClientNotification(Component component) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.sendSystemMessage(component);
                }
            } catch (Throwable t) {
                ExampleMod.LOGGER.debug("Client notification dispatch notice: {}", t.getMessage());
            }
        }
    }
}
