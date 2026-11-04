package com.example.examplemod;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server payload requesting resolution of an active worker rescue.
 *
 * Security & Authorization Invariants:
 * - Server MUST validate sender player UUID matches the recorded ownerUUID of the worker.
 * - Server MUST verify that missing tools, equipment, or route conditions are genuinely satisfied.
 * - Client waypoint acknowledgement alone NEVER resumes worker execution.
 * - eventId and sequence protect against stale, replay, or out-of-order resolution requests.
 */
public record RescueResolutionRequestPayload(
        UUID workerId,
        UUID eventId,
        long sequence
) implements CustomPacketPayload {

    public RescueResolutionRequestPayload(UUID workerId) {
        this(workerId, null, 0L);
    }

    public static final Type<RescueResolutionRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "rescue_resolution_req")
    );

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(workerId);
        buf.writeBoolean(eventId != null);
        if (eventId != null) {
            buf.writeUUID(eventId);
        }
        buf.writeVarLong(sequence);
    }

    public static RescueResolutionRequestPayload read(FriendlyByteBuf buf) {
        UUID workerId = buf.readUUID();
        UUID eventId = buf.readBoolean() ? buf.readUUID() : null;
        long sequence = buf.readVarLong();
        return new RescueResolutionRequestPayload(workerId, eventId, sequence);
    }

    public static final StreamCodec<FriendlyByteBuf, RescueResolutionRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            RescueResolutionRequestPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
