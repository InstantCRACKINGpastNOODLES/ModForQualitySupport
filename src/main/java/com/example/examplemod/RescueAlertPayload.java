package com.example.examplemod;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> Client payload communicating worker rescue alerts and lifecycle updates.
 *
 * Enforces stable identity:
 * - Tied directly to workerId (UUID) and dimension.
 * - ownerUUID: Stable Mojang player UUID (immune to username changes).
 * - eventId: Unique UUID identifying this specific rescue event.
 * - sequence: Monotonic identifier ensuring out-of-order or stale packets cannot resolve newer alerts.
 * - isResolved: When true, instructs client to dismiss only this worker's waypoint.
 */
public record RescueAlertPayload(
        UUID workerId,
        String workerName,
        UUID ownerUUID,
        String ownerName,
        UUID eventId,
        ResourceLocation dimension,
        BlockPos workerPos,
        EquipmentCapabilityPolicy.RescueTrigger trigger,
        String reason,
        long sequence,
        boolean isResolved
) implements CustomPacketPayload {

    public RescueAlertPayload(
            UUID workerId,
            String workerName,
            String ownerName,
            ResourceLocation dimension,
            BlockPos workerPos,
            EquipmentCapabilityPolicy.RescueTrigger trigger,
            String reason,
            long sequence,
            boolean isResolved
    ) {
        this(workerId, workerName, null, ownerName, null, dimension, workerPos, trigger, reason, sequence, isResolved);
    }

    public static final Type<RescueAlertPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rescue_alert")
    );

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(workerId);
        buf.writeUtf(workerName != null ? workerName : "Worker");
        buf.writeBoolean(ownerUUID != null);
        if (ownerUUID != null) {
            buf.writeUUID(ownerUUID);
        }
        buf.writeUtf(ownerName != null ? ownerName : "");
        buf.writeBoolean(eventId != null);
        if (eventId != null) {
            buf.writeUUID(eventId);
        }
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(workerPos);
        buf.writeEnum(trigger != null ? trigger : EquipmentCapabilityPolicy.RescueTrigger.NONE);
        buf.writeUtf(reason != null ? reason : "");
        buf.writeVarLong(sequence);
        buf.writeBoolean(isResolved);
    }

    public static RescueAlertPayload read(FriendlyByteBuf buf) {
        UUID workerId = buf.readUUID();
        String workerName = buf.readUtf();
        UUID ownerUUID = buf.readBoolean() ? buf.readUUID() : null;
        String ownerName = buf.readUtf();
        UUID eventId = buf.readBoolean() ? buf.readUUID() : null;
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos workerPos = buf.readBlockPos();
        EquipmentCapabilityPolicy.RescueTrigger trigger = buf.readEnum(EquipmentCapabilityPolicy.RescueTrigger.class);
        String reason = buf.readUtf();
        long sequence = buf.readVarLong();
        boolean isResolved = buf.readBoolean();
        return new RescueAlertPayload(workerId, workerName, ownerUUID, ownerName, eventId, dimension, workerPos, trigger, reason, sequence, isResolved);
    }

    public static final StreamCodec<FriendlyByteBuf, RescueAlertPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            RescueAlertPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
