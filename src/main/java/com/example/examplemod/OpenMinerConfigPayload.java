package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenMinerConfigPayload(
        UUID workerId,
        BlockPos anchorPos,
        Direction initialDirection,
        long defaultQuota,
        String initialBlockId,
        List<String> candidateTags
) implements CustomPacketPayload {

    public static final Type<OpenMinerConfigPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "open_miner_config")
    );

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(workerId);
        buf.writeBlockPos(anchorPos);
        buf.writeEnum(initialDirection != null ? initialDirection : Direction.NORTH);
        buf.writeVarLong(defaultQuota);
        buf.writeUtf(initialBlockId != null ? initialBlockId : "");
        buf.writeVarInt(candidateTags != null ? candidateTags.size() : 0);
        if (candidateTags != null) {
            for (String tag : candidateTags) {
                buf.writeUtf(tag);
            }
        }
    }

    public static OpenMinerConfigPayload read(FriendlyByteBuf buf) {
        UUID workerId = buf.readUUID();
        BlockPos anchorPos = buf.readBlockPos();
        Direction initialDirection = buf.readEnum(Direction.class);
        long defaultQuota = buf.readVarLong();
        String initialBlockId = buf.readUtf();
        int tagCount = buf.readVarInt();
        List<String> candidateTags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            candidateTags.add(buf.readUtf());
        }
        return new OpenMinerConfigPayload(workerId, anchorPos, initialDirection, defaultQuota, initialBlockId, candidateTags);
    }

    public static final StreamCodec<FriendlyByteBuf, OpenMinerConfigPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            OpenMinerConfigPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
