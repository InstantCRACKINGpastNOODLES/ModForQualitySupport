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

public record MinerConfigSubmitPayload(
        UUID workerId,
        BlockPos anchorPos,
        String mode,
        long quota,
        int maxTravelDistance,
        Direction direction,
        boolean stairAscending,
        int targetY,
        int searchRadius,
        List<String> targetBlockIds,
        List<String> targetBlockTags
) implements CustomPacketPayload {

    public static final Type<MinerConfigSubmitPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "miner_config_submit")
    );

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(workerId);
        buf.writeBlockPos(anchorPos);
        buf.writeUtf(mode != null ? mode : "TUNNEL");
        buf.writeVarLong(quota);
        buf.writeVarInt(maxTravelDistance);
        buf.writeEnum(direction != null ? direction : Direction.NORTH);
        buf.writeBoolean(stairAscending);
        buf.writeVarInt(targetY);
        buf.writeVarInt(searchRadius);

        buf.writeVarInt(targetBlockIds != null ? targetBlockIds.size() : 0);
        if (targetBlockIds != null) {
            for (String id : targetBlockIds) {
                buf.writeUtf(id);
            }
        }

        buf.writeVarInt(targetBlockTags != null ? targetBlockTags.size() : 0);
        if (targetBlockTags != null) {
            for (String tag : targetBlockTags) {
                buf.writeUtf(tag);
            }
        }
    }

    public static MinerConfigSubmitPayload read(FriendlyByteBuf buf) {
        UUID workerId = buf.readUUID();
        BlockPos anchorPos = buf.readBlockPos();
        String mode = buf.readUtf();
        long quota = buf.readVarLong();
        int maxTravelDistance = buf.readVarInt();
        Direction direction = buf.readEnum(Direction.class);
        boolean stairAscending = buf.readBoolean();
        int targetY = buf.readVarInt();
        int searchRadius = buf.readVarInt();

        int idCount = buf.readVarInt();
        List<String> targetBlockIds = new ArrayList<>(idCount);
        for (int i = 0; i < idCount; i++) {
            targetBlockIds.add(buf.readUtf());
        }

        int tagCount = buf.readVarInt();
        List<String> targetBlockTags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) {
            targetBlockTags.add(buf.readUtf());
        }

        return new MinerConfigSubmitPayload(workerId, anchorPos, mode, quota, maxTravelDistance,
                direction, stairAscending, targetY, searchRadius, targetBlockIds, targetBlockTags);
    }

    public static final StreamCodec<FriendlyByteBuf, MinerConfigSubmitPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            MinerConfigSubmitPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
