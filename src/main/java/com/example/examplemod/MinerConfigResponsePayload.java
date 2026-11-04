package com.example.examplemod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MinerConfigResponsePayload(
        boolean accepted,
        String message
) implements CustomPacketPayload {

    public static final Type<MinerConfigResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "miner_config_response")
    );

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(accepted);
        buf.writeUtf(message != null ? message : "");
    }

    public static MinerConfigResponsePayload read(FriendlyByteBuf buf) {
        boolean accepted = buf.readBoolean();
        String message = buf.readUtf();
        return new MinerConfigResponsePayload(accepted, message);
    }

    public static final StreamCodec<FriendlyByteBuf, MinerConfigResponsePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            MinerConfigResponsePayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
