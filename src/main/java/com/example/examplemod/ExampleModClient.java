package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = ExampleMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT)
public class ExampleModClient {
    public ExampleModClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        ExampleMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        ExampleMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        NeoForge.EVENT_BUS.register(BindingTicketTetherRenderer.class);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(WorkerManagementMenu.TYPE.get(), WorkerManagementScreen::new);
    }

    @SubscribeEvent
    static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "ticket_selection_hud"),
                BindingTicketHUD::render
        );
    }

    public static void handleOrderSessionSync(OrderSessionSyncPayload payload) {
        if (payload != null) {
            BindingTicketHUD.updateSession(
                    payload.stageOrdinal(),
                    payload.workerId(),
                    payload.workerName(),
                    payload.jobType(),
                    payload.requestedQuantity(),
                    payload.worksitePos(),
                    payload.message()
            );
        }
    }

    public static void handleOpenMinerConfig(OpenMinerConfigPayload payload) {
        if (payload != null && Minecraft.getInstance() != null) {
            Minecraft.getInstance().setScreen(new MinerJobConfigScreen(payload));
        }
    }

    public static void handleMinerConfigResponse(MinerConfigResponsePayload payload) {
        if (payload != null && Minecraft.getInstance().player != null) {
            if (!payload.accepted()) {
                Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§c[Miner Config Error] §f" + payload.message()), false);
            }
        }
    }
}
