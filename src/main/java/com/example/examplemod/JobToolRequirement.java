package com.example.examplemod;

import java.util.Comparator;
import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Common extensible specification defining tool requirements, candidate validation,
 * satisfaction preflight, and deterministic tool selection across different autonomous job types.
 */
public interface JobToolRequirement {

    /**
     * Unique identifier for this requirement.
     */
    ResourceLocation id();

    /**
     * Determines whether the currently held tool satisfies the requirement for this job.
     * Prevents pointless swapping every tick when an already compatible tool is held.
     */
    boolean isSatisfiedBy(ItemStack held);

    /**
     * Determines whether a candidate item from WorkerStorage is authorized and capable
     * of satisfying this requirement.
     */
    boolean isCandidate(ItemStack candidate);

    /**
     * Computes a deterministic integer score for ranking candidate tools.
     * Higher score indicates a better tool (e.g. higher tier, faster mining speed, higher durability).
     */
    int scoreCandidate(ItemStack candidate);

    /**
     * Comparator ordering candidate ItemStacks by suitability (descending: best candidate first).
     */
    default Comparator<ItemStack> candidateComparator() {
        return (a, b) -> {
            int scoreA = scoreCandidate(a);
            int scoreB = scoreCandidate(b);
            if (scoreA != scoreB) {
                return Integer.compare(scoreB, scoreA); // Higher score first
            }
            int durA = a.isDamageableItem() ? (a.getMaxDamage() - a.getDamageValue()) : Integer.MAX_VALUE;
            int durB = b.isDamageableItem() ? (b.getMaxDamage() - b.getDamageValue()) : Integer.MAX_VALUE;
            if (durA != durB) {
                return Integer.compare(durB, durA); // Higher remaining durability first
            }
            return 0;
        };
    }

    /**
     * Requirement for autonomous Mining jobs (requires verified Pickaxe with durability).
     */
    JobToolRequirement MINING = new MiningToolRequirement();

    /**
     * Requirement for autonomous Farming jobs (requires verified Hoe with durability).
     */
    JobToolRequirement FARMING = new FarmingToolRequirement();

    /**
     * Requirement for jobs that do not require specialized tools (e.g. Move, Wait).
     * Any held tool or empty hand satisfies this requirement, and currently held tools
     * are retained safely without blocking movement or operations.
     */
    JobToolRequirement NONE = new NoneToolRequirement();

    /**
     * Resolves the appropriate tool requirement for a given job type.
     */
    static JobToolRequirement forJobType(ResourceLocation jobType) {
        if (WorkerJobs.MINE.equals(jobType)) {
            return MINING;
        }
        if (WorkerJobs.FARM.equals(jobType)) {
            return FARMING;
        }
        return NONE;
    }

    /**
     * Standard implementation for Mining tool requirements.
     */
    final class MiningToolRequirement implements JobToolRequirement {
        private static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "mining_tool_requirement");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        private static boolean isPickaxe(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            Item item = stack.getItem();
            if (item instanceof PickaxeItem) return true;
            try {
                if (stack.is(ItemTags.PICKAXES)) return true;
            } catch (Throwable ignored) {}
            try {
                if (stack.canPerformAction(ItemAbilities.PICKAXE_DIG)) return true;
            } catch (Throwable ignored) {}
            return false;
        }

        @Override
        public boolean isSatisfiedBy(ItemStack held) {
            if (held == null || held.isEmpty()) {
                return false;
            }
            return isCandidate(held);
        }

        @Override
        public boolean isCandidate(ItemStack candidate) {
            if (candidate == null || candidate.isEmpty()) {
                return false;
            }
            if (!isPickaxe(candidate)) {
                return false;
            }
            return JobSafetyPolicy.isMiningToolVerified(candidate) &&
                    JobSafetyPolicy.hasSufficientDurability(candidate, 1);
        }

        @Override
        public int scoreCandidate(ItemStack candidate) {
            if (!isCandidate(candidate)) {
                return -1;
            }
            int tierScore = calculateTierScore(candidate);
            int durScore = candidate.isDamageableItem() ?
                    Math.min(99999, candidate.getMaxDamage() - candidate.getDamageValue()) : 99999;
            return tierScore * 100000 + durScore;
        }

        private static int calculateTierScore(ItemStack stack) {
            Item item = stack.getItem();
            if (item == Items.NETHERITE_PICKAXE) return 5000;
            if (item == Items.DIAMOND_PICKAXE) return 4000;
            if (item == Items.IRON_PICKAXE) return 3000;
            if (item == Items.STONE_PICKAXE) return 2000;
            if (item == Items.GOLDEN_PICKAXE) return 1500;
            if (item == Items.WOODEN_PICKAXE) return 1000;

            if (item instanceof TieredItem tiered) {
                var tier = tiered.getTier();
                if (tier == Tiers.NETHERITE) return 5000;
                if (tier == Tiers.DIAMOND) return 4000;
                if (tier == Tiers.IRON) return 3000;
                if (tier == Tiers.STONE) return 2000;
                if (tier == Tiers.GOLD) return 1500;
                if (tier == Tiers.WOOD) return 1000;
                return (int) (tier.getSpeed() * 200) + Math.min(1000, tier.getUses());
            }
            return 500;
        }
    }

    /**
     * Standard implementation for Farming tool requirements.
     */
    final class FarmingToolRequirement implements JobToolRequirement {
        private static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "farming_tool_requirement");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        private static boolean isHoe(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            Item item = stack.getItem();
            if (item instanceof HoeItem) return true;
            try {
                if (stack.is(ItemTags.HOES)) return true;
            } catch (Throwable ignored) {}
            try {
                if (stack.canPerformAction(ItemAbilities.HOE_DIG)) return true;
            } catch (Throwable ignored) {}
            return false;
        }

        @Override
        public boolean isSatisfiedBy(ItemStack held) {
            if (held == null || held.isEmpty()) {
                return false;
            }
            return isCandidate(held);
        }

        @Override
        public boolean isCandidate(ItemStack candidate) {
            if (candidate == null || candidate.isEmpty()) {
                return false;
            }
            if (!isHoe(candidate)) {
                return false;
            }
            return JobSafetyPolicy.hasSufficientDurability(candidate, 1);
        }

        @Override
        public int scoreCandidate(ItemStack candidate) {
            if (!isCandidate(candidate)) {
                return -1;
            }
            int tierScore = calculateTierScore(candidate);
            int durScore = candidate.isDamageableItem() ?
                    Math.min(99999, candidate.getMaxDamage() - candidate.getDamageValue()) : 99999;
            return tierScore * 100000 + durScore;
        }

        private static int calculateTierScore(ItemStack stack) {
            Item item = stack.getItem();
            if (item == Items.NETHERITE_HOE) return 5000;
            if (item == Items.DIAMOND_HOE) return 4000;
            if (item == Items.IRON_HOE) return 3000;
            if (item == Items.STONE_HOE) return 2000;
            if (item == Items.GOLDEN_HOE) return 1500;
            if (item == Items.WOODEN_HOE) return 1000;

            if (item instanceof TieredItem tiered) {
                var tier = tiered.getTier();
                if (tier == Tiers.NETHERITE) return 5000;
                if (tier == Tiers.DIAMOND) return 4000;
                if (tier == Tiers.IRON) return 3000;
                if (tier == Tiers.STONE) return 2000;
                if (tier == Tiers.GOLD) return 1500;
                if (tier == Tiers.WOOD) return 1000;
                return (int) (tier.getSpeed() * 200) + Math.min(1000, tier.getUses());
            }
            return 500;
        }
    }

    /**
     * Requirement implementation for non-specialized jobs (Move, Wait).
     */
    final class NoneToolRequirement implements JobToolRequirement {
        private static final ResourceLocation ID =
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "none_tool_requirement");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public boolean isSatisfiedBy(ItemStack held) {
            return true;
        }

        @Override
        public boolean isCandidate(ItemStack candidate) {
            return false;
        }

        @Override
        public int scoreCandidate(ItemStack candidate) {
            return 0;
        }
    }
}
