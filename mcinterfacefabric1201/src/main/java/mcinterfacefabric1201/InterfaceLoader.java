package mcinterfacefabric1201;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import minecrafttransportsimulator.blocks.components.ABlockBase;
import minecrafttransportsimulator.blocks.components.ABlockBaseTileEntity;
import minecrafttransportsimulator.blocks.instances.BlockCollision;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityEnergyCharger;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityFluidTankProvider;
import minecrafttransportsimulator.blocks.tileentities.components.ITileEntityInventoryProvider;
import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.items.components.AItemSubTyped;
import minecrafttransportsimulator.items.components.IItemBlock;
import minecrafttransportsimulator.items.components.IItemEntityProvider;
import minecrafttransportsimulator.items.components.IItemFood;
import minecrafttransportsimulator.items.instances.ItemItem;
import minecrafttransportsimulator.jsondefs.JSONPack;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packloading.PackParser;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.systems.LanguageSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTab.DisplayItemsGenerator;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Loader interface for the mod.  This class is not actually an interface, unlike everything else.
 * Instead, it keeps references to all interfaces, which are passed-in during construction.
 * It also handles initialization calls when the game is first booted.  There will only
 * be ONE loader per running instance of Minecraft.
 *
 * @author don_bruce
 */
public class InterfaceLoader implements ModInitializer {
    public static final String MODID = "mts";
    public static final String MODNAME = "Immersive Vehicles (MTS)";
    public static final String MODVER = "25.0.0";

    public static final Logger LOGGER = LogManager.getLogger(InterfaceLoader.MODID);
    public static Set<String> packIDs = new HashSet<>();

    private static final List<BuilderBlock> normalBlocks = new ArrayList<>();
    private static final List<BuilderBlock> fluidBlocks = new ArrayList<>();
    private static final List<BuilderBlock> inventoryBlocks = new ArrayList<>();
    private static final List<BuilderBlock> chargerBlocks = new ArrayList<>();

    @Override
    public void onInitialize() {
        final String gameDirectory = FabricLoader.getInstance().getGameDir().toFile().getAbsolutePath();
        final boolean isClient = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

        //Init interfaces and send to the main game system.  Client interfaces are built in a
        //separate class so their classes never load on a dedicated server.
        if (isClient) {
            ClientInterfaceFactory.createInterfaceManager(MODID, gameDirectory);
        } else {
            new InterfaceManager(MODID, gameDirectory, new InterfaceCore(), new InterfacePacket(), null, null, null, null);
        }

        InterfaceManager.coreInterface.logError("Welcome to MTS VERSION: " + MODVER);

        //Init config.
        ConfigSystem.loadFromDisk(isClient);

        //Parse packs.  Look through the mods directory, the directory the mod jar lives in, and
        //a dedicated "packs" directory (a fallback for setups where non-mod pack jars can't sit
        //in mods/).  IV's PackParser scans these directories for jars carrying a packdefinition.
        List<File> packDirectories = new ArrayList<>();
        addPackDirectory(packDirectories, new File(gameDirectory, "mods"));
        try {
            for (Path modPath : FabricLoader.getInstance().getModContainer(MODID).get().getOrigin().getPaths()) {
                addPackDirectory(packDirectories, modPath.getParent().toFile().getCanonicalFile());
            }
        } catch (Exception e) {
            //The mod origin may not be a normal jar path in dev; ignore.
        }
        addPackDirectory(packDirectories, new File(gameDirectory, "packs"));
        if (!packDirectories.isEmpty()) {
            PackParser.addDefaultItems();
            PackParser.parsePacks(packDirectories);
        } else {
            InterfaceManager.coreInterface.logError("Could not find any pack directories!  Checked game directory: " + gameDirectory);
        }

        //Set pack IDs.
        packIDs.addAll(PackParser.getAllPackIDs());

        //Create all pack items.  We need to do this before anything else.
        //Item registration comes first, and we use the items registered to determine
        //which blocks we need to register.  Fabric registries are open during mod init,
        //so we register each object immediately rather than deferring.
        Set<ABlockBase> blocksRegistred = new HashSet<>();
        Map<String, List<AItemPack<?>>> creativeTabsRequired = new HashMap<>();
        for (String packID : PackParser.getAllPackIDs()) {
            for (AItemPack<?> item : PackParser.getAllItemsForPack(packID, true)) {
                if (item.autoGenerate()) {
                    //Create and register the item.
                    Item.Properties itemProperties = new Item.Properties();
                    itemProperties.stacksTo(item.getStackSize());
                    if (item instanceof ItemItem && ((ItemItem) item).definition.food != null) {
                        IItemFood food = (IItemFood) item;
                        itemProperties.food(new FoodProperties.Builder().nutrition(food.getHungerAmount()).saturationMod(food.getSaturationAmount()).build());
                    }
                    Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(MODID, item.getRegistrationName()), new BuilderItem(itemProperties, item));

                    //Check if the creative tab is set/created.
                    //The only exception is for "invisible" parts of the core mod, these are internal.
                    boolean hideOnCreativeTab = item.definition.general.hideOnCreativeTab || (item instanceof AItemSubTyped && ((AItemSubTyped<?>) item).subDefinition.hideOnCreativeTab);
                    if (!hideOnCreativeTab && (!item.definition.packID.equals(InterfaceLoader.MODID) || !item.definition.systemName.contains("invisible"))) {
                        creativeTabsRequired.computeIfAbsent(item.getCreativeTabID(), k -> new ArrayList<>()).add(item);
                    }
                }

                //If this item is an IItemBlock, generate a block in the registry for it.
                if (item instanceof IItemBlock) {
                    IItemBlock itemBlock = (IItemBlock) item;
                    ABlockBase itemBlockBlock = itemBlock.getBlock();
                    if (!blocksRegistred.contains(itemBlockBlock)) {
                        //New block class detected.  Register it and its instance.
                        String name = itemBlock.getBlockClass() != null ? itemBlock.getBlockClass().getSimpleName().substring("Block".length()).toLowerCase(Locale.ROOT) : (item.getRegistrationName() + "_Block").toLowerCase(Locale.ROOT);
                        blocksRegistred.add(itemBlockBlock);
                        registerBlock(name, itemBlockBlock);
                    }
                }
            }
        }

        //Create creative tabs, as required.
        creativeTabsRequired.forEach((tabID, tabItems) -> {
            JSONPack packConfiguration = PackParser.getPackConfiguration(tabID);
            AItemPack<?> tabIconItem = packConfiguration.packItem != null ? PackParser.getItem(packConfiguration.packID, packConfiguration.packItem) : null;
            ItemStack tabIconStack = tabIconItem != null ? new ItemStack(BuilderItem.itemMap.get(tabIconItem)) : null;
            DisplayItemsGenerator validItemsGenerator = (pParameters, pOutput) -> tabItems.forEach(tabItem -> pOutput.accept(BuilderItem.itemMap.get(tabItem)));
            Supplier<ItemStack> iconSupplier = tabIconStack != null ? () -> tabIconStack : () -> new ItemStack(BuilderItem.itemMap.get(tabItems.get((int) (System.currentTimeMillis() / 1000 % tabItems.size()))));
            CreativeModeTab tab = FabricItemGroup.builder().title(Component.literal(packConfiguration.packName)).icon(iconSupplier).displayItems(validItemsGenerator).build();
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, new ResourceLocation(MODID, tabID), tab);
        });

        //Register the collision blocks.
        for (int i = 0; i < BlockCollision.blockInstances.size(); ++i) {
            BlockCollision collisionBlock = BlockCollision.blockInstances.get(i);
            String name = collisionBlock.getClass().getSimpleName().substring("Block".length()).toLowerCase(Locale.ROOT) + i;
            BuilderBlock wrapper = new BuilderBlock(collisionBlock);
            BuilderBlock.blockMap.put(collisionBlock, wrapper);
            Registry.register(BuiltInRegistries.BLOCK, new ResourceLocation(MODID, name), wrapper);
        }

        //Init the language system for the created items and blocks.
        LanguageSystem.init(isClient);

        //Init tile entities.  These will run after blocks, so the tile entity lists will be populated by this time.
        BuilderTileEntity.TE_TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, new ResourceLocation(MODID, "builder_base"), BlockEntityType.Builder.of(BuilderTileEntity::new, normalBlocks.toArray(new BuilderBlock[0])).build(null));
        BuilderTileEntityFluidTank.TE_TYPE2 = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, new ResourceLocation(MODID, "builder_fluidtank"), BlockEntityType.Builder.of(BuilderTileEntityFluidTank::new, fluidBlocks.toArray(new BuilderBlock[0])).build(null));
        BuilderTileEntityInventoryContainer.TE_TYPE2 = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, new ResourceLocation(MODID, "builder_inventory"), BlockEntityType.Builder.of(BuilderTileEntityInventoryContainer::new, inventoryBlocks.toArray(new BuilderBlock[0])).build(null));
        BuilderTileEntityEnergyCharger.TE_TYPE2 = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, new ResourceLocation(MODID, "builder_charger"), BlockEntityType.Builder.of(BuilderTileEntityEnergyCharger::new, chargerBlocks.toArray(new BuilderBlock[0])).build(null));

        //Register the Transfer/Energy API lookups now that the BE types exist.
        BuilderTileEntityFluidTank.registerFluidLookup();
        BuilderTileEntityEnergyCharger.registerEnergyLookup();

        //Init entities.
        BuilderEntityExisting.E_TYPE2 = Registry.register(BuiltInRegistries.ENTITY_TYPE, new ResourceLocation(MODID, "builder_existing"), EntityType.Builder.<BuilderEntityExisting>of(BuilderEntityExisting::new, MobCategory.MISC).sized(0.05F, 0.05F).clientTrackingRange(32 * 16).updateInterval(5).build("builder_existing"));
        BuilderEntityLinkedSeat.E_TYPE3 = Registry.register(BuiltInRegistries.ENTITY_TYPE, new ResourceLocation(MODID, "builder_seat"), EntityType.Builder.<BuilderEntityLinkedSeat>of(BuilderEntityLinkedSeat::new, MobCategory.MISC).sized(0.05F, 0.05F).clientTrackingRange(32 * 16).updateInterval(5).build("builder_seat"));
        BuilderEntityRenderForwarder.E_TYPE4 = Registry.register(BuiltInRegistries.ENTITY_TYPE, new ResourceLocation(MODID, "builder_rendering"), EntityType.Builder.<BuilderEntityRenderForwarder>of(BuilderEntityRenderForwarder::new, MobCategory.MISC).sized(0.05F, 0.05F).clientTrackingRange(32 * 16).updateInterval(5).build("builder_rendering"));

        //Iterate over all pack items and find those that spawn entities.
        //Register these with the IV internal system.
        for (AItemPack<?> packItem : PackParser.getAllPackItems()) {
            if (packItem instanceof IItemEntityProvider) {
                ((IItemEntityProvider) packItem).registerEntities(BuilderEntityExisting.entityMap);
            }
        }

        //Init networking interface.  This will register packets as well.
        InterfacePacket.init();

        //Wire the world tick + unload dispatchers (Forge fired these off the event bus).
        ServerTickEvents.START_WORLD_TICK.register(level -> WrapperWorld.onServerTick(level, true));
        ServerTickEvents.END_WORLD_TICK.register(level -> WrapperWorld.onServerTick(level, false));
        ServerWorldEvents.UNLOAD.register((server, level) -> {
            WrapperWorld.onServerWorldUnload(level);
            WrapperEntity.onWorldUnload(level);
            WrapperPlayer.onWorldUnload(level);
            InterfaceSound.onWorldUnload(level);
        });
        //Invalidate the internal MTS entity when its builder unloads (Forge did this via Entity#onRemovedFromWorld).
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof BuilderEntityExisting) {
                ((BuilderEntityExisting) entity).onRemovedFromWorld();
            }
        });
        //Invalidate IV block-entities when their chunk unloads (Forge did this via BlockEntity#onChunkUnloaded).
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> BuilderTileEntity.onChunkUnload(chunk));
    }

    private void registerBlock(String name, ABlockBase itemBlockBlock) {
        BuilderBlock wrapper;
        if (itemBlockBlock instanceof ABlockBaseTileEntity) {
            wrapper = new BuilderBlockTileEntity(itemBlockBlock);
            Class<?> teClass = ((ABlockBaseTileEntity) itemBlockBlock).getTileEntityClass();
            if (ITileEntityFluidTankProvider.class.isAssignableFrom(teClass)) {
                fluidBlocks.add(wrapper);
            } else if (ITileEntityInventoryProvider.class.isAssignableFrom(teClass)) {
                inventoryBlocks.add(wrapper);
            } else if (ITileEntityEnergyCharger.class.isAssignableFrom(teClass)) {
                chargerBlocks.add(wrapper);
            } else {
                normalBlocks.add(wrapper);
            }
        } else {
            wrapper = new BuilderBlock(itemBlockBlock);
        }
        BuilderBlock.blockMap.put(itemBlockBlock, wrapper);
        Registry.register(BuiltInRegistries.BLOCK, new ResourceLocation(MODID, name), wrapper);
    }

    private static void addPackDirectory(List<File> packDirectories, File directory) {
        if (directory != null && directory.exists() && !packDirectories.contains(directory)) {
            packDirectories.add(directory);
        }
    }
}
