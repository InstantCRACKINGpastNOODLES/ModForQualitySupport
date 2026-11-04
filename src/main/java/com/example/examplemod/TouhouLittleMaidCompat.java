package com.example.examplemod;

import java.lang.reflect.Method;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Clean runtime bridge for Touhou Little Maid (TLM) integration.
 * Prevents TLM's SwitchSittingEvent from toggling maid sit pose
 * when the player uses a Binding Ticket.
 * Also provides control over maid follow behavior and Brain memories during job execution.
 */
public final class TouhouLittleMaidCompat {

    private static boolean registered = false;

    private TouhouLittleMaidCompat() {}

    public static void init() {
        if (registered) return;
        try {
            Class<?> eventClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent");
            if (Event.class.isAssignableFrom(eventClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Event> eventType = (Class<? extends Event>) eventClass;
                Method getPlayerMethod = eventClass.getMethod("getPlayer");
                Method setCanceledMethod = eventClass.getMethod("setCanceled", boolean.class);

                NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, eventType, event -> {
                    try {
                        Player player = (Player) getPlayerMethod.invoke(event);
                        if (player != null) {
                            if (player.getMainHandItem().is(ExampleMod.BINDING_TICKET.get()) ||
                                    player.getOffhandItem().is(ExampleMod.BINDING_TICKET.get())) {
                                setCanceledMethod.invoke(event, true);
                                ExampleMod.LOGGER.debug("Intercepted TLM InteractMaidEvent at HIGHEST priority - cancelled TLM sit toggle");
                            }
                        }
                    } catch (Throwable t) {
                        ExampleMod.LOGGER.error("Error in TLM InteractMaidEvent handler", t);
                    }
                });
                registered = true;
                ExampleMod.LOGGER.info("Touhou Little Maid detected: Registered HIGHEST priority InteractMaidEvent interceptor.");
            }
        } catch (ClassNotFoundException e) {
            ExampleMod.LOGGER.info("Touhou Little Maid not present: Skipping TLM event listener registration.");
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("Failed to register Touhou Little Maid interceptor", t);
        }
    }

    /**
     * Suppresses Touhou Little Maid's autonomous follow-owner brain behavior during active job execution.
     * In TLM, MaidFollowOwnerTask runs in CORE goals every tick when isHomeModeEnable() is false.
     * Enabling home mode suppresses MaidFollowOwnerTask without restricting movement.
     * Also clears conflicting WALK_TARGET from the worker's Brain.
     */
    public static void suppressMaidFollow(Mob worker) {
        if (worker == null) return;
        try {
            Method m = worker.getClass().getMethod("setHomeModeEnable", boolean.class);
            m.invoke(worker, true);
        } catch (Throwable ignored) {}
        try {
            worker.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } catch (Throwable ignored) {}
    }

    /**
     * Restores Touhou Little Maid's autonomous follow-owner brain behavior when jobs are finished
     * and the worker is configured to follow its owner.
     */
    public static void restoreMaidFollow(Mob worker) {
        if (worker == null) return;
        try {
            Method m = worker.getClass().getMethod("setHomeModeEnable", boolean.class);
            m.invoke(worker, false);
        } catch (Throwable ignored) {}
    }
}
