package com.example.examplemod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(ExampleMod.MODID)
public class ExampleMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "elstarticket";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "examplemod" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "examplemod" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MODID);
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> WORKER_OWNER = ATTACHMENTS.register("worker_owner",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<WorkerProfile>> WORKER_PROFILE = ATTACHMENTS.register("worker_profile",
            () -> AttachmentType.builder(WorkerProfile::unbound).serialize(WorkerProfile.CODEC).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<WorkerStorage>> WORKER_STORAGE = ATTACHMENTS.register("worker_storage",
            () -> AttachmentType.builder(WorkerStorage::empty).serialize(WorkerStorage.CODEC).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<EquipmentCapabilityPolicy.ShelterConstructionState>> SHELTER_STATE = ATTACHMENTS.register("shelter_state",
            () -> AttachmentType.builder(EquipmentCapabilityPolicy.ShelterConstructionState::empty)
                    .serialize(EquipmentCapabilityPolicy.ShelterConstructionState.CODEC).build());
    public static final DeferredItem<BindingTicketItem> BINDING_TICKET = ITEMS.register("binding_ticket",
            () -> new BindingTicketItem(new Item.Properties().stacksTo(1)));
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "examplemod" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "examplemod:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "examplemod:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // Creates a new food item with the id "examplemod:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "examplemod:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.elstarticket")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get());// Add the example item to the tab. For your own tabs, this method is preferred over the event
                output.accept(BINDING_TICKET.get());
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        ATTACHMENTS.register(modEventBus);
        WorkerManagementMenu.TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(this::registerPayloadHandlers);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (ExampleMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(WorkerExecutionLoop::onEntityTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BindingTicketItem::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BindingTicketItem::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, BindingTicketItem::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(RescueServerManager::onPlayerLoggedIn);
        TouhouLittleMaidCompat.init();

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    private void registerPayloadHandlers(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        net.neoforged.neoforge.network.registration.PayloadRegistrar registrar = event.registrar("1.0.0");
        registrar.playToClient(
                OrderSessionSyncPayload.TYPE,
                OrderSessionSyncPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                            ExampleModClient.handleOrderSessionSync(payload);
                        }
                    });
                }
        );
        registrar.playToClient(
                RescueAlertPayload.TYPE,
                RescueAlertPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                            RescueWaypointManager.handleRescueAlert(payload);
                        }
                    });
                }
        );
        registrar.playToServer(
                RescueResolutionRequestPayload.TYPE,
                RescueResolutionRequestPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                            net.minecraft.server.level.ServerLevel level = player.serverLevel();
                            net.minecraft.world.entity.Entity entity = null;
                            if (level != null) {
                                entity = level.getEntity(payload.workerId());
                                if (entity == null && level.getServer() != null) {
                                    for (net.minecraft.server.level.ServerLevel otherLevel : level.getServer().getAllLevels()) {
                                        entity = otherLevel.getEntity(payload.workerId());
                                        if (entity != null) break;
                                    }
                                }
                            }
                            net.minecraft.world.entity.Mob workerMob = (entity instanceof net.minecraft.world.entity.Mob mob) ? mob : null;
                            WorkerStorage storage = (workerMob != null && workerMob.hasData(ExampleMod.WORKER_STORAGE))
                                    ? workerMob.getData(ExampleMod.WORKER_STORAGE) : null;

                            RescueServerManager.handleResolutionRequest(player, payload, workerMob, storage);
                        }
                    });
                }
        );
        registrar.playToClient(
                OpenMinerConfigPayload.TYPE,
                OpenMinerConfigPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                            ExampleModClient.handleOpenMinerConfig(payload);
                        }
                    });
                }
        );
        registrar.playToServer(
                MinerConfigSubmitPayload.TYPE,
                MinerConfigSubmitPayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                            WorkerOrderSession.handleMinerConfigSubmit(player, payload);
                        }
                    });
                }
        );
        registrar.playToClient(
                MinerConfigResponsePayload.TYPE,
                MinerConfigResponsePayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
                            ExampleModClient.handleMinerConfigResponse(payload);
                        }
                    });
                }
        );
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
