package com.example.examplemod;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.item.ItemStack;

/**
 * Server-authoritative resource preflight evaluator for autonomous mining jobs.
 * Enforces:
 * 1. Independent requested mining quantity: quantity is player-controlled and NOT capped by tool durability.
 * 2. Infinite-durability handling: tools with no durability (e.g. Avaritia tools) do not require replacement tools,
 *    and tool wear is never used as a stopping condition.
 * 3. Collateral & per-tick world change enforcement: every additional block affected by a tool's special mining
 *    behavior is counted against the configured per-tick execution limit.
 * 4. Safety verification: infinite durability does not imply safe execution; unverified tools or tools with
 *    unmeasurable/unconstrained collateral effects are strictly rejected.
 */
public final class MinerResourcePreflight {

    public enum PreflightStatus {
        APPROVED,
        REJECTED_UNVERIFIED_TOOL,
        REJECTED_UNBOUNDED_COLLATERAL,
        REJECTED_RATE_LIMIT_EXCEEDED,
        REJECTED_INSUFFICIENT_DURABILITY,
        REJECTED_INVALID_QUANTITY,
        REJECTED_MISSING_TOOL
    }

    public record PreflightPlan(
            PreflightStatus status,
            ItemStack tool,
            long playerRequestedQuantity,
            boolean hasInfiniteDurability,
            long usableDurability,
            int collateralBlocksPerAction,
            int maxBlocksPerTick,
            long estimatedTicksToComplete,
            long replacementToolsNeeded,
            Optional<String> detail
    ) {
        public boolean isApproved() {
            return status == PreflightStatus.APPROVED;
        }
    }

    private MinerResourcePreflight() {
    }

    /**
     * Evaluates resource requirements, tool safety, and per-tick execution boundaries for a requested mining job.
     *
     * @param tool The proposed tool
     * @param playerRequestedQuantity The total block quantity requested by the player (long)
     * @param maxBlocksPerTick The configured maximum allowed block changes per tick
     */
    public static PreflightPlan evaluate(ItemStack tool, long playerRequestedQuantity, int maxBlocksPerTick) {
        if (playerRequestedQuantity <= 0) {
            return new PreflightPlan(
                    PreflightStatus.REJECTED_INVALID_QUANTITY,
                    tool, playerRequestedQuantity, false, 0L, 0, maxBlocksPerTick, 0L, 0L,
                    Optional.of("Player requested quantity must be strictly positive")
            );
        }

        if (tool == null || tool.isEmpty()) {
            return new PreflightPlan(
                    PreflightStatus.REJECTED_MISSING_TOOL,
                    ItemStack.EMPTY, playerRequestedQuantity, false, 0L, 0, maxBlocksPerTick, 0L, 0L,
                    Optional.of("Mining tool is missing")
            );
        }

        // 1. Tool Verification: Infinite durability does NOT imply safety; tool must be explicitly verified
        if (!JobSafetyPolicy.isMiningToolVerified(tool)) {
            return new PreflightPlan(
                    PreflightStatus.REJECTED_UNVERIFIED_TOOL,
                    tool, playerRequestedQuantity, !tool.isDamageableItem(), 0L, 0, maxBlocksPerTick, 0L, 0L,
                    Optional.of("Tool is not verified for autonomous mining by any registered capability provider")
            );
        }

        // 2. Collateral Effect Accounting: Tool's special mining behavior must be measurable and bounded
        int collateralBlocks = MiningToolCapability.Registry.getMaxBlocksPerAction(tool);
        if (collateralBlocks <= 0) {
            return new PreflightPlan(
                    PreflightStatus.REJECTED_UNBOUNDED_COLLATERAL,
                    tool, playerRequestedQuantity, !tool.isDamageableItem(), 0L, 0, maxBlocksPerTick, 0L, 0L,
                    Optional.of("Tool collateral effects cannot be reliably measured, bounded, or constrained")
            );
        }

        // 3. Per-Tick Rate Limit Enforcement: All affected blocks (including collateral) count against per-tick limit
        if (collateralBlocks > maxBlocksPerTick) {
            return new PreflightPlan(
                    PreflightStatus.REJECTED_RATE_LIMIT_EXCEEDED,
                    tool, playerRequestedQuantity, !tool.isDamageableItem(), 0L, collateralBlocks, maxBlocksPerTick, 0L, 0L,
                    Optional.of("Tool collateral block alteration (" + collateralBlocks + ") exceeds configured per-tick limit (" + maxBlocksPerTick + ")")
            );
        }

        boolean infiniteDurability = !tool.isDamageableItem();
        long usableDurability = -1L;
        long replacementToolsNeeded = 0L;

        if (infiniteDurability) {
            // For tools with no durability:
            // - Do not require replacement tools merely to compensate for durability loss.
            // - Do not estimate stopping points based on tool wear.
            // - Calculate requested mining quantity independently of tool durability.
            usableDurability = Long.MAX_VALUE;
            replacementToolsNeeded = 0L;
        } else {
            // For damageable tools: verify next operation cannot break the tool or drop below MIN_TOOL_DURABILITY
            int remaining = tool.getMaxDamage() - tool.getDamageValue();
            if (!JobSafetyPolicy.hasSufficientDurability(tool, collateralBlocks)) {
                return new PreflightPlan(
                        PreflightStatus.REJECTED_INSUFFICIENT_DURABILITY,
                        tool, playerRequestedQuantity, false, 0L, collateralBlocks, maxBlocksPerTick, 0L, 0L,
                        Optional.of("Tool remaining durability (" + remaining + ") cannot safely perform next operation cost (" + collateralBlocks + ")")
                );
            }
            usableDurability = (long) (remaining - JobSafetyPolicy.MIN_TOOL_DURABILITY);
            // Estimate whether additional tools are required if durability is less than requested quantity
            if (usableDurability < playerRequestedQuantity) {
                long defDamage = (long) (tool.getMaxDamage() - JobSafetyPolicy.MIN_TOOL_DURABILITY);
                long remainingBlocks = playerRequestedQuantity - usableDurability;
                replacementToolsNeeded = (defDamage > 0) ? Math.ceilDiv(remainingBlocks, defDamage) : 1L;
            }
        }

        // Rate-limited progress calculation: ticks to complete based on per-tick throughput, independent of durability
        long estimatedTicks = Math.ceilDiv(playerRequestedQuantity, (long) collateralBlocks);

        return new PreflightPlan(
                PreflightStatus.APPROVED,
                tool,
                playerRequestedQuantity,
                infiniteDurability,
                usableDurability,
                collateralBlocks,
                maxBlocksPerTick,
                estimatedTicks,
                replacementToolsNeeded,
                Optional.empty()
        );
    }
}
