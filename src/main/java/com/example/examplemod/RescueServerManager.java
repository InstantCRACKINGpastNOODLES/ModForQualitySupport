package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-Side Rescue State & Authorization Manager.
 *
 * Invariants & Architecture:
 * 1. Durable Persistence: All active rescues and monotonic sequence counters are saved
 *    to disk via RescueSavedData (`data/elstarticket_rescues.dat`). Rescues survive chunk unloads,
 *    worker unloads, and server/world restarts.
 * 2. UUID-Based Authorization: Owner identity is anchored to stable Mojang player UUID (`ownerUUID`),
 *    completely immune to account renaming, username spoofing, or custom display names.
 * 3. Physical Verification: Waypoint acknowledgement alone NEVER resumes worker execution.
 *    If worker chunk is unloaded, resolution is refused until chunk is approached.
 *    If worker is present, equipment sufficiency is strictly re-evaluated before resolution.
 * 4. Stale Packet & Monotonicity Protection: Sequence numbers and event UUIDs (`eventId`)
 *    prevent stale, replayed, or out-of-order resolution requests from closing newer rescue events.
 * 5. Reconnection Synchronization: When the owner connects, active rescues stored in RescueSavedData
 *    are automatically re-synchronized to the client.
 */
public final class RescueServerManager {

    public record ActiveRescue(
            UUID workerId,
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

    private RescueServerManager() {
    }

    public static void clearAllRescues(ServerLevel level) {
        RescueSavedData.get(level).clearAll();
    }

    public static void clearAllRescues() {
        clearAllRescues(null);
    }

    public static Optional<ActiveRescue> getActiveRescue(ServerLevel level, UUID workerId) {
        if (workerId == null) return Optional.empty();
        return RescueSavedData.get(level).getRescue(workerId);
    }

    public static Optional<ActiveRescue> getActiveRescue(UUID workerId) {
        return getActiveRescue(null, workerId);
    }

    public static Map<UUID, ActiveRescue> getAllActiveRescues(ServerLevel level) {
        return RescueSavedData.get(level).getAllRescues();
    }

    public static Map<UUID, ActiveRescue> getAllActiveRescues() {
        return getAllActiveRescues(null);
    }

    public static List<ActiveRescue> getActiveRescuesForOwner(ServerLevel level, UUID ownerUUID, String ownerName) {
        RescueSavedData data = RescueSavedData.get(level);
        List<ActiveRescue> result = new ArrayList<>();
        if (ownerUUID != null) {
            result.addAll(data.getRescuesForOwner(ownerUUID));
        }
        if (result.isEmpty() && ownerName != null && !ownerName.isEmpty()) {
            result.addAll(data.getRescuesForOwnerName(ownerName));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<ActiveRescue> getActiveRescuesForOwner(UUID ownerUUID, String ownerName) {
        return getActiveRescuesForOwner(null, ownerUUID, ownerName);
    }

    /**
     * Triggers an emergency rescue state for a worker with full UUID security and persistent storage.
     */
    public static ActiveRescue triggerRescue(
            ServerLevel level,
            UUID workerId,
            String workerName,
            UUID ownerUUID,
            String ownerName,
            UUID eventId,
            ResourceLocation dimension,
            BlockPos workerPos,
            EquipmentCapabilityPolicy.RescueTrigger trigger,
            String reason
    ) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(workerPos, "workerPos must not be null");

        RescueSavedData data = RescueSavedData.get(level);
        long seq = data.nextSequence();
        UUID evId = (eventId != null) ? eventId : UUID.randomUUID();

        ActiveRescue rescue = new ActiveRescue(
                workerId,
                workerName != null ? workerName : "Worker",
                ownerUUID,
                ownerName != null ? ownerName : "",
                evId,
                dimension,
                workerPos,
                trigger != null ? trigger : EquipmentCapabilityPolicy.RescueTrigger.MANUAL_OPERATOR_REQUEST,
                reason != null ? reason : "Assistance required",
                seq
        );

        data.putRescue(rescue);

        // Dispatch alert to owner if online
        if (level != null) {
            ServerPlayer owner = findOwnerPlayer(level, rescue.ownerUUID(), rescue.ownerName());
            if (owner != null) {
                dispatchAlertToPlayer(owner, rescue, false);
            }
        }

        return rescue;
    }

    /**
     * Overload for convenience.
     */
    public static ActiveRescue triggerRescue(
            ServerLevel level,
            UUID workerId,
            String workerName,
            UUID ownerUUID,
            String ownerName,
            ResourceLocation dimension,
            BlockPos workerPos,
            EquipmentCapabilityPolicy.RescueTrigger trigger,
            String reason
    ) {
        return triggerRescue(level, workerId, workerName, ownerUUID, ownerName, UUID.randomUUID(), dimension, workerPos, trigger, reason);
    }

    /**
     * Legacy/Testing overload with String ownerName only.
     */
    public static ActiveRescue triggerRescue(
            ServerLevel level,
            UUID workerId,
            String workerName,
            String ownerName,
            ResourceLocation dimension,
            BlockPos workerPos,
            EquipmentCapabilityPolicy.RescueTrigger trigger,
            String reason
    ) {
        return triggerRescue(level, workerId, workerName, null, ownerName, UUID.randomUUID(), dimension, workerPos, trigger, reason);
    }

    /**
     * Convenience overload extracting identity and owner UUID from Mob worker and WorkerProfile.
     */
    public static ActiveRescue triggerRescue(
            ServerLevel level,
            Mob worker,
            WorkerProfile profile,
            EquipmentCapabilityPolicy.RescueTrigger trigger,
            String reason
    ) {
        if (worker == null) {
            throw new IllegalArgumentException("Worker entity must not be null");
        }
        UUID workerId = worker.getUUID();
        String workerName = worker.getDisplayName().getString();
        UUID ownerUUID = (profile != null) ? profile.ownerId() : null;
        String ownerName = worker.hasData(ExampleMod.WORKER_OWNER) ? worker.getData(ExampleMod.WORKER_OWNER) : "";
        if (ownerName.isEmpty() && ownerUUID != null && level != null && level.getServer() != null) {
            ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(ownerUUID);
            if (ownerPlayer != null) {
                ownerName = ownerPlayer.getScoreboardName();
            }
        }
        ResourceLocation dim = (level != null) ? level.dimension().location() : ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        BlockPos pos = worker.blockPosition();

        return triggerRescue(level, workerId, workerName, ownerUUID, ownerName, UUID.randomUUID(), dim, pos, trigger, reason);
    }

    /**
     * Updates worker position and dimension while in rescue mode.
     */
    public static Optional<ActiveRescue> updateWorkerPosition(
            ServerLevel level,
            UUID workerId,
            ResourceLocation dimension,
            BlockPos newPos
    ) {
        if (workerId == null || newPos == null) return Optional.empty();
        RescueSavedData data = RescueSavedData.get(level);
        ActiveRescue existing = data.getRescue(workerId).orElse(null);
        if (existing == null) return Optional.empty();

        long newSeq = data.nextSequence();
        ActiveRescue updated = new ActiveRescue(
                existing.workerId(),
                existing.workerName(),
                existing.ownerUUID(),
                existing.ownerName(),
                existing.eventId(),
                dimension != null ? dimension : existing.dimension(),
                newPos,
                existing.trigger(),
                existing.reason(),
                newSeq
        );

        data.putRescue(updated);

        if (level != null) {
            ServerPlayer owner = findOwnerPlayer(level, updated.ownerUUID(), updated.ownerName());
            if (owner != null) {
                dispatchAlertToPlayer(owner, updated, false);
            }
        }

        return Optional.of(updated);
    }

    public record ResolutionResult(
            boolean resolved,
            String message
    ) {
        public static ResolutionResult success(String msg) {
            return new ResolutionResult(true, msg);
        }

        public static ResolutionResult failed(String msg) {
            return new ResolutionResult(false, msg);
        }

        public static ResolutionResult unauthorized(String msg) {
            return new ResolutionResult(false, msg);
        }

        public static ResolutionResult stale(String msg) {
            return new ResolutionResult(false, msg);
        }
    }

    /**
     * Resolves an active rescue with full UUID security, event identity validation, sequence checking,
     * and server-side equipment verification.
     */
    public static ResolutionResult resolveRescue(
            ServerLevel level,
            UUID workerId,
            UUID operatorUUID,
            String operatorName,
            UUID eventId,
            long sequence,
            boolean recheckNeeds,
            Mob worker,
            WorkerStorage storage
    ) {
        if (workerId == null) {
            return ResolutionResult.failed("Invalid worker UUID");
        }
        RescueSavedData data = RescueSavedData.get(level);
        ActiveRescue existing = data.getRescue(workerId).orElse(null);
        if (existing == null) {
            return ResolutionResult.failed("No active rescue for worker " + workerId);
        }

        // 1. Security Check: Strict UUID-based owner verification (Fail-closed)
        if (existing.ownerUUID() == null) {
            return ResolutionResult.unauthorized("Invalid rescue record: missing owner UUID");
        }
        if (operatorUUID == null) {
            return ResolutionResult.unauthorized("Unauthorized operator: missing operator UUID");
        }
        if (!existing.ownerUUID().equals(operatorUUID)) {
            return ResolutionResult.unauthorized("Unauthorized operator: UUID " + operatorUUID + " does not match worker owner UUID " + existing.ownerUUID());
        }

        // 2. Event Identity & Monotonic Sequence Check (Exact Match)
        if (eventId == null) {
            return ResolutionResult.stale("Missing event ID in resolution request");
        }
        if (existing.eventId() == null || !existing.eventId().equals(eventId)) {
            return ResolutionResult.stale("Stale rescue resolution: event ID mismatch (received " + eventId + ", active " + existing.eventId() + ")");
        }
        if (sequence != existing.sequence()) {
            if (sequence < existing.sequence()) {
                return ResolutionResult.stale("Stale rescue resolution: sequence " + sequence + " is older than active sequence " + existing.sequence());
            } else {
                return ResolutionResult.stale("Stale rescue resolution: sequence " + sequence + " does not match active sequence " + existing.sequence());
            }
        }

        // 3. Physical Verification: Waypoint acknowledgement alone NEVER resumes worker
        if (recheckNeeds) {
            if (worker == null) {
                return ResolutionResult.failed("Worker chunk is currently unloaded; please approach worker worksite to verify equipment before resolving");
            }
            EquipmentCapabilityPolicy.RescueEvaluation eval =
                    EquipmentCapabilityPolicy.evaluateWorkerRescueNeeds(worker, storage, false, false, true);
            if (eval.rescueRequired()) {
                return ResolutionResult.failed("Worker still requires rescue: " + eval.reason().orElse("Missing equipment"));
            }
        }

        // 4. Remove active rescue from persistent world storage
        data.removeRescue(workerId);

        // 5. If worker is present, clear SHELTER_STATE and transition back to RUNNING
        if (worker != null) {
            if (worker.hasData(ExampleMod.SHELTER_STATE)) {
                worker.removeData(ExampleMod.SHELTER_STATE);
            }
            if (worker.hasData(ExampleMod.WORKER_PROFILE)) {
                WorkerProfile prof = worker.getData(ExampleMod.WORKER_PROFILE);
                if (prof.job().isPresent() && prof.job().get().status() == JobStatus.WAITING_FOR_TOOL) {
                    WorkerProfile resumed = prof.transition(JobStatus.RUNNING, prof.job().get().progress(), null).orElse(prof);
                    worker.setData(ExampleMod.WORKER_PROFILE, resumed);
                }
            }
        }

        // 6. Dispatch resolved alert payload to owner
        if (level != null) {
            ServerPlayer owner = findOwnerPlayer(level, existing.ownerUUID(), existing.ownerName());
            if (owner != null) {
                dispatchAlertToPlayer(owner, existing, true);
            }
        }

        return ResolutionResult.success("Rescue resolved for worker " + existing.workerName());
    }

    public static ResolutionResult resolveRescue(
            ServerLevel level,
            UUID workerId,
            UUID operatorUUID,
            boolean recheckNeeds,
            Mob worker,
            WorkerStorage storage
    ) {
        RescueSavedData data = RescueSavedData.get(level);
        ActiveRescue existing = data.getRescue(workerId).orElse(null);
        UUID eventId = existing != null ? existing.eventId() : null;
        long seq = existing != null ? existing.sequence() : 0L;
        String name = existing != null ? existing.ownerName() : "";
        return resolveRescue(level, workerId, operatorUUID, name, eventId, seq, recheckNeeds, worker, storage);
    }

    /**
     * Backward-compatible overload for legacy test call sites.
     */
    public static ResolutionResult resolveRescue(
            ServerLevel level,
            UUID workerId,
            String operatorName,
            boolean recheckNeeds,
            Mob worker,
            WorkerStorage storage
    ) {
        return resolveRescue(level, workerId, null, operatorName, null, 0, recheckNeeds, worker, storage);
    }

    /**
     * Handles client resolution request packet with server-side authenticated player.
     */
    public static ResolutionResult handleResolutionRequest(
            ServerPlayer player,
            RescueResolutionRequestPayload payload,
            Mob worker,
            WorkerStorage storage
    ) {
        if (player == null || payload == null) {
            return ResolutionResult.failed("Null player or payload");
        }
        return resolveRescue(
                player.serverLevel(),
                payload.workerId(),
                player.getUUID(),
                player.getScoreboardName(),
                payload.eventId(),
                payload.sequence(),
                true,
                worker,
                storage
        );
    }

    /**
     * Re-synchronizes active rescues to reconnecting owner players upon login.
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncRescuesToPlayer(player);
        }
    }

    public static void syncRescuesToPlayer(ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        List<ActiveRescue> list = getActiveRescuesForOwner(level, player.getUUID(), player.getScoreboardName());
        for (ActiveRescue rescue : list) {
            dispatchAlertToPlayer(player, rescue, false);
        }
    }

    private static ServerPlayer findOwnerPlayer(ServerLevel level, UUID ownerUUID, String ownerName) {
        if (level == null || level.getServer() == null) return null;
        if (ownerUUID != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(ownerUUID);
            if (player != null) return player;
        }
        if (ownerName != null && !ownerName.isEmpty()) {
            return level.getServer().getPlayerList().getPlayerByName(ownerName);
        }
        return null;
    }

    private static void dispatchAlertToPlayer(ServerPlayer player, ActiveRescue rescue, boolean isResolved) {
        try {
            RescueAlertPayload payload = new RescueAlertPayload(
                    rescue.workerId(),
                    rescue.workerName(),
                    rescue.ownerUUID(),
                    rescue.ownerName(),
                    rescue.eventId(),
                    rescue.dimension(),
                    rescue.workerPos(),
                    rescue.trigger(),
                    rescue.reason(),
                    rescue.sequence(),
                    isResolved
            );
            PacketDistributor.sendToPlayer(player, payload);
        } catch (Throwable t) {
            ExampleMod.LOGGER.debug("RescueAlertPayload packet dispatch notice: {}", t.getMessage());
        }
    }
}
