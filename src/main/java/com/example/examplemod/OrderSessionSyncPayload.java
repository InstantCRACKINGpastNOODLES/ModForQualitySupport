package com.example.examplemod;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OrderSessionSyncPayload(
        UUID workerId,
        String workerName,
        String jobType,
        int stageOrdinal,
        long requestedQuantity,
        BlockPos worksitePos,
        String message
) implements CustomPacketPayload {

    public static final Type<OrderSessionSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "order_session_sync")
    );

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(workerId);
        buf.writeUtf(workerName);
        buf.writeUtf(jobType);
        buf.writeVarInt(stageOrdinal);
        buf.writeVarLong(requestedQuantity);
        buf.writeBoolean(worksitePos != null);
        if (worksitePos != null) {
            buf.writeBlockPos(worksitePos);
        }
        buf.writeUtf(message);
    }

    public static OrderSessionSyncPayload read(FriendlyByteBuf buf) {
        UUID workerId = buf.readUUID();
        String workerName = buf.readUtf();
        String jobType = buf.readUtf();
        int stageOrdinal = buf.readVarInt();
        long requestedQuantity = buf.readVarLong();
        BlockPos worksitePos = buf.readBoolean() ? buf.readBlockPos() : null;
        String message = buf.readUtf();
        return new OrderSessionSyncPayload(workerId, workerName, jobType, stageOrdinal, requestedQuantity, worksitePos, message);
    }

    public static final StreamCodec<FriendlyByteBuf, OrderSessionSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            OrderSessionSyncPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
