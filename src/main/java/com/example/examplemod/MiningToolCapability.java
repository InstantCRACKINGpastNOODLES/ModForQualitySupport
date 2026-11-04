package com.example.examplemod;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.component.Tool;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Capability contract for verifying whether a tool is authorized for autonomous block-breaking jobs.
 * Enforces a strict DEFAULT-DENY security model to prevent world destruction from nonstandard,
 * area-effect, or explosive modded tools (such as Avaritia World Breakers).
 */
public interface MiningToolCapability {

    ResourceLocation id();

    /**
     * Determines whether the tool is explicitly verified as a safe, controlled tool.
     * Must return false for unverified tools or tools with uncontrolled AOE, explosive, or nonstandard side effects.
     */
    boolean isVerifiedSafe(ItemStack tool);

    /**
     * Returns the maximum number of blocks this tool can affect in a single discrete action.
     * Must return <= 0 if the effect cannot be strictly bounded.
     */
    int maxBlocksPerAction(ItemStack tool);

    /**
     * Central registry for mining tool capabilities.
     * Implements strict default-deny: tools are forbidden unless an explicit provider verifies them.
     */
    final class Registry {
        private static final Map<ResourceLocation, MiningToolCapability> CAPABILITIES = new ConcurrentHashMap<>();

        public static final ResourceLocation VANILLA_PROVIDER_ID =
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "vanilla_tools");

        static {
            register(new VanillaMiningToolCapability());
        }

        private Registry() {
        }

        public static void register(MiningToolCapability capability) {
            Objects.requireNonNull(capability);
            CAPABILITIES.put(capability.id(), capability);
        }

        public static Optional<MiningToolCapability> get(ResourceLocation id) {
            if (id == null) return Optional.empty();
            return Optional.ofNullable(CAPABILITIES.get(id));
        }

        public static void clear() {
            CAPABILITIES.clear();
            register(new VanillaMiningToolCapability());
        }

        /**
         * Checks whether any registered provider explicitly verifies the tool as safe.
         * Enforces default-deny for unknown, null, or empty items.
         */
        public static boolean isVerifiedSafe(ItemStack tool) {
            if (tool == null || tool.isEmpty()) {
                return false;
            }
            for (MiningToolCapability capability : CAPABILITIES.values()) {
                if (capability.isVerifiedSafe(tool)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the maximum blocks per action authorized for this tool.
         * Returns 0 if the tool is unverified, null, empty, or unbounded.
         */
        public static int getMaxBlocksPerAction(ItemStack tool) {
            if (tool == null || tool.isEmpty()) {
                return 0;
            }
            for (MiningToolCapability capability : CAPABILITIES.values()) {
                if (capability.isVerifiedSafe(tool)) {
                    int max = capability.maxBlocksPerAction(tool);
                    return Math.max(0, max);
                }
            }
            return 0;
        }
    }

    /**
     * Built-in capability provider for standard single-block mining tools.
     * Recognizes standard vanilla tools, standard NeoForge tool abilities (PICKAXE_DIG, etc.),
     * modern 1.21.1 tags (ItemTags.PICKAXES, etc.), standard tool class hierarchy (PickaxeItem, etc.),
     * and 1.21.1 Tool data components with mineable rules.
     * Enforces bounded single-block actions (maxBlocksPerAction = 1).
     */
    final class VanillaMiningToolCapability implements MiningToolCapability {
        private static final Set<Item> SAFE_VANILLA_TOOLS = Set.of(
                Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
                Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
                Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL,
                Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL,
                Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
                Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE,
                Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
                Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE,
                Items.SHEARS
        );

        @Override
        public ResourceLocation id() {
            return Registry.VANILLA_PROVIDER_ID;
        }

        @Override
        public boolean isVerifiedSafe(ItemStack tool) {
            if (tool == null || tool.isEmpty()) {
                return false;
            }

            // 1. Explicit identity check against known safe vanilla tools
            if (SAFE_VANILLA_TOOLS.contains(tool.getItem())) {
                return true;
            }

            // 2. Modded or vanilla pickaxes/digging tools via NeoForge ItemAbilities
            try {
                if (tool.canPerformAction(ItemAbilities.PICKAXE_DIG) ||
                    tool.canPerformAction(ItemAbilities.SHOVEL_DIG) ||
                    tool.canPerformAction(ItemAbilities.AXE_DIG) ||
                    tool.canPerformAction(ItemAbilities.HOE_DIG) ||
                    tool.canPerformAction(ItemAbilities.SHEARS_DIG)) {
                    return true;
                }
            } catch (Throwable ignored) {}

            // 3. Modern Minecraft tags (ItemTags.PICKAXES, SHOVELS, AXES, HOES)
            try {
                if (tool.is(ItemTags.PICKAXES) ||
                    tool.is(ItemTags.SHOVELS) ||
                    tool.is(ItemTags.AXES) ||
                    tool.is(ItemTags.HOES)) {
                    return true;
                }
            } catch (Throwable ignored) {}

            // 4. Standard tool class hierarchy (PickaxeItem, ShovelItem, AxeItem, HoeItem, DiggerItem, ShearsItem)
            Item item = tool.getItem();
            if (item instanceof PickaxeItem ||
                item instanceof ShovelItem ||
                item instanceof AxeItem ||
                item instanceof HoeItem ||
                item instanceof DiggerItem ||
                item instanceof ShearsItem) {
                return true;
            }

            // 5. Tool Data Component in 1.21.1 (DataComponents.TOOL)
            try {
                if (tool.has(DataComponents.TOOL)) {
                    Tool toolComp = tool.get(DataComponents.TOOL);
                    if (toolComp != null && !toolComp.rules().isEmpty()) {
                        for (Tool.Rule rule : toolComp.rules()) {
                            if (rule.blocks() instanceof net.minecraft.core.HolderSet.Named<net.minecraft.world.level.block.Block> named) {
                                var key = named.key();
                                if (key.equals(BlockTags.MINEABLE_WITH_PICKAXE) ||
                                    key.equals(BlockTags.MINEABLE_WITH_SHOVEL) ||
                                    key.equals(BlockTags.MINEABLE_WITH_AXE) ||
                                    key.equals(BlockTags.MINEABLE_WITH_HOE)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            return false;
        }

        @Override
        public int maxBlocksPerAction(ItemStack tool) {
            return isVerifiedSafe(tool) ? 1 : 0;
        }
    }
}
