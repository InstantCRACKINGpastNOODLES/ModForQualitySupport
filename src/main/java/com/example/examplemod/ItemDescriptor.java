package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/**
 * Immutable descriptor of an item identifier and expected hand.
 * Never references or stores a mutable ItemStack.
 */
public record ItemDescriptor(
        ResourceLocation itemId,
        InteractionHand hand,
        Optional<String> componentFingerprint
) {
    public ItemDescriptor {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(hand, "hand must not be null");
        Objects.requireNonNull(componentFingerprint, "componentFingerprint must not be null");
    }

    public ItemDescriptor(ResourceLocation itemId, InteractionHand hand) {
        this(itemId, hand, Optional.empty());
    }

    public ItemDescriptor(ResourceLocation itemId) {
        this(itemId, InteractionHand.MAIN_HAND, Optional.empty());
    }
}
