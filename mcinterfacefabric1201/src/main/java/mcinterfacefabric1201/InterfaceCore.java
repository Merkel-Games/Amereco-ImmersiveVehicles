package mcinterfacefabric1201;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import minecrafttransportsimulator.items.components.AItemBase;
import minecrafttransportsimulator.mcinterface.IInterfaceCore;
import minecrafttransportsimulator.mcinterface.IWrapperItemStack;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packloading.PackParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

class InterfaceCore implements IInterfaceCore {
    protected static final Map<String, List<BuilderItem>> taggedItems = new HashMap<>();

    @Override
    public boolean isGameFlattened() {
        return true;
    }

    @Override
    public boolean isModPresent(String modID) {
        return FabricLoader.getInstance().isModLoaded(modID);
    }

    @Override
    public boolean isFluidValid(String fluidID) {
        for (ResourceLocation location : BuiltInRegistries.FLUID.keySet()) {
            if (location.getPath().equals(fluidID)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getModName(String modID) {
        return FabricLoader.getInstance().getModContainer(modID).map(container -> container.getMetadata().getName()).orElse(modID);
    }
    
    @Override
    public InputStream getPackResource(String resource) {
        int assetsIndexEnd = resource.indexOf("assets/") + "assets/".length();
        int modIDEnd = resource.indexOf("/", assetsIndexEnd + 1);
        String modID = resource.substring(assetsIndexEnd, modIDEnd);

        //First try the classloader.  This resolves the core mts assets (which are inside our own
        //mod jar and are classloaded normally) plus any content pack that happens to be a real
        //Fabric mod.  The dev-build fallback checks the separately-compiled core jar.
        InputStream stream = InterfaceCore.class.getResourceAsStream(resource);
        if (stream == null && modID.equals(InterfaceLoader.MODID)) {
            stream = InterfaceManager.class.getResourceAsStream(resource);
        }
        if (stream != null) {
            return stream;
        }

        //Content pack jars are NOT on the Fabric classloader (unlike Forge, which classloads every
        //jar in the mods folder) nor registered as resource packs.  Search every content-pack jar
        //for the exact asset entry.  This covers both textures (assets/<pack>/textures/...) and the
        //item-model JSONs the packs ship under the mts namespace (assets/mts/models/item/<pack>.*).
        InputStream packStream = getPackJarResource(resource);
        if (packStream != null) {
            return packStream;
        }

        //Try to get a Minecraft texture, using the block class's classloader (common to servers and clients).
        return Blocks.AIR.getClass().getResourceAsStream(resource);
    }

    /**
     * Reads a resource straight out of whichever content-pack jar contains it.  Jars are opened
     * once and kept open for the process lifetime (matching how Forge kept pack jars open via the
     * classloader), as pack assets are read heavily during model/texture loading.
     */
    private static InputStream getPackJarResource(String resource) {
        String entryName = resource.startsWith("/") ? resource.substring(1) : resource;
        for (String packID : PackParser.getAllPackIDs()) {
            File packJar = PackParser.getPackJar(packID);
            if (packJar == null) {
                continue;
            }
            ZipFile zip = packZipCache.computeIfAbsent(packID, id -> {
                try {
                    return new ZipFile(packJar);
                } catch (IOException e) {
                    return null;
                }
            });
            if (zip == null) {
                continue;
            }
            ZipEntry entry = zip.getEntry(entryName);
            if (entry != null) {
                try {
                    //ZipFile is thread-safe for concurrent getInputStream and stays open for the
                    //session, so the returned stream can be closed independently by the caller.
                    return zip.getInputStream(entry);
                } catch (IOException e) {
                    //Try the next pack jar.
                }
            }
        }
        return null;
    }

    private static final Map<String, ZipFile> packZipCache = new ConcurrentHashMap<>();

    @Override
    public void logError(String message) {
        InterfaceLoader.LOGGER.error("MTSERROR: " + message);
    }

    @Override
    public IWrapperNBT getNewNBTWrapper() {
        return new WrapperNBT();
    }

    @Override
    public IWrapperItemStack getAutoGeneratedStack(AItemBase item, IWrapperNBT data) {
        WrapperItemStack newStack = new WrapperItemStack(new ItemStack(BuilderItem.itemMap.get(item)));
        newStack.setData(data);
        return newStack;
    }

    @Override
    public IWrapperItemStack getStackForProperties(String name, int meta, int qty) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(name));
        if (item != null) {
            return new WrapperItemStack(new ItemStack(item, qty));
        } else {
            return new WrapperItemStack(ItemStack.EMPTY.copy());
        }
    }

    @Override
    public String getStackItemName(IWrapperItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(((WrapperItemStack) stack).stack.getItem()).toString();
    }

    @Override
    public boolean isOredictMatch(IWrapperItemStack stackA, IWrapperItemStack stackB) {
        return !((WrapperItemStack) stackA).stack.isEmpty() && ((WrapperItemStack) stackA).stack.is(((WrapperItemStack) stackB).stack.getItem());
    }

    @SuppressWarnings("deprecation")
    @Override
    public List<IWrapperItemStack> getOredictMaterials(String oreName, int stackSize) {
        //Convert to lowercase in case we are camelCase from oreDict systems.
        //Also do a bunch of stupid stream crap, cause hashmaps are clearly not made to lookup things...
        String lowerCaseOre = oreName.toLowerCase(Locale.ROOT);
        List<IWrapperItemStack> stacks = new ArrayList<>();
        Stream<TagKey<Item>> tagStream = BuiltInRegistries.ITEM.getTagNames().filter(tagKey -> tagKey.location().getPath().equals(lowerCaseOre));
        tagStream.forEach(tagKey -> {
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tagKey)) {
                stacks.add(new WrapperItemStack(new ItemStack(holder.value(), stackSize)));
            }
        });
        //Couldn't find normal OreDict, check our internal stuff.
        if (stacks.isEmpty()) {
            List<BuilderItem> items = taggedItems.get(lowerCaseOre);
            if (items != null) {
                items.forEach(item -> stacks.add(new WrapperItemStack(new ItemStack(item, stackSize))));
            }
        }

        return stacks;
    }
}
