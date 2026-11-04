package com.example.examplemod;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;

/**
 * Capability contract determining whether a worker is capable of performing a given job type.
 * Ensures that holding a tool alone does not grant arbitrary capabilities.
 */
public interface WorkerJobCapability {
    boolean canPerform(ResourceLocation jobType, Mob worker, ItemStack heldTool);

    final class Registry {
        private static final Map<ResourceLocation, WorkerJobCapability> CAPABILITIES = new ConcurrentHashMap<>();

        static {
            // Built-in Move capability: requires PathfinderMob
            register(WorkerJobs.MOVE, (jobType, worker, heldTool) ->
                    worker instanceof PathfinderMob && worker.isAlive() && !worker.isRemoved());

            // Built-in Wait capability: requires PathfinderMob
            register(WorkerJobs.WAIT, (jobType, worker, heldTool) ->
                    worker instanceof PathfinderMob && worker.isAlive() && !worker.isRemoved());

            // Built-in Mine capability: requires PathfinderMob
            register(WorkerJobs.MINE, (jobType, worker, heldTool) ->
                    worker instanceof PathfinderMob && worker.isAlive() && !worker.isRemoved());

            // Built-in Farm capability: requires PathfinderMob
            register(WorkerJobs.FARM, (jobType, worker, heldTool) ->
                    worker instanceof PathfinderMob && worker.isAlive() && !worker.isRemoved());
        }

        private Registry() {
        }

        public static void register(ResourceLocation jobType, WorkerJobCapability capability) {
            Objects.requireNonNull(jobType);
            Objects.requireNonNull(capability);
            CAPABILITIES.put(jobType, capability);
        }

        public static boolean canPerform(ResourceLocation jobType, Mob worker, ItemStack heldTool) {
            if (jobType == null || worker == null) {
                return false;
            }
            WorkerJobCapability capability = CAPABILITIES.get(jobType);
            return capability != null && capability.canPerform(jobType, worker, heldTool);
        }

        /**
         * Validates whether a worker and equipped tool are authorized for autonomous mining operations.
         * Enforces strict default-deny: unverified, damaged, or empty tools return false.
         */
        public static boolean canPerformMining(Mob worker, ItemStack heldTool) {
            if (worker == null || !worker.isAlive() || worker.isRemoved() || heldTool == null || heldTool.isEmpty()) {
                return false;
            }
            return JobSafetyPolicy.isMiningToolAuthorized(heldTool, 1);
        }

        /**
         * Validates whether a worker is authorized for autonomous farming operations.
         * Allows empty hands or hoes; rejects weapons and destructive tools.
         */
        public static boolean canPerformFarming(Mob worker, ItemStack heldTool) {
            if (worker != null && (!worker.isAlive() || worker.isRemoved())) {
                return false;
            }
            if (heldTool == null || heldTool.isEmpty()) {
                return true;
            }
            if (heldTool.getItem() instanceof net.minecraft.world.item.HoeItem) {
                return true;
            }
            try {
                if (heldTool.is(net.minecraft.tags.ItemTags.HOES) ||
                    heldTool.canPerformAction(net.neoforged.neoforge.common.ItemAbilities.HOE_DIG)) {
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }
}
