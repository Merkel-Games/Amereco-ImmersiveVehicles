package mcinterfacefabric1201;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packloading.PackParser;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Interface for handling events pertaining to loading models into MC.  This used to handle mainly item models, but
 * now it just re-directs texture calls for the main core mod to allow them to work in development with the referenced
 * core library files that MC doesn't see normally.
 *
 * @author don_bruce
 */
public class InterfaceEventsModelLoader {
    public static PackResourcePack packPack = new PackResourcePack();

    /**
     * Called to init the custom model loader.  Should be done before any other things.
     * This allows injecting our custom resource manager into MC's systems to have it use it.
     * We do this by registering it as a reload listener, as on a resource reload (and boot) MC will purge the list
     * of packs and will re-query from disk.  But we aren't on disk, and so we will need to be
     * ready when that call comes and will re-add ourselves.
     */
    public static void init() {
        packPack.domains.addAll(PackParser.getAllPackIDs());
    }

    /**
     * Custom ResourcePack class for auto-generating item JSONs.
     */
    public static class PackResourcePack implements PackResources {
        private final Set<String> domains;
        private final Set<String> fakeDomains;

        private PackResourcePack() {
            super();
            domains = new HashSet<>();
            fakeDomains = new HashSet<>();
            fakeDomains.add(InterfaceLoader.MODID);
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            //Check if this resource belongs to us: either the namespace is a known pack/the core
            //mod, or the filename is prefixed with a known pack ID (item models are shipped under
            //the mts namespace as mts:models/item/<packid>.<name>.json).  On Forge the pack jars
            //were auto-loaded resource packs, so MC found models/textures directly; on Fabric they
            //are not, so we must serve every asset type here (not just .png).
            if (domains.contains(location.getNamespace()) || domains.contains(getPackID(location.getPath()))) {
                //Preserve the request's own namespace in the asset path: pack item models live at
                //assets/mts/models/item/... even though the file physically sits in the pack jar.
                String streamLocation = "/assets/" + location.getNamespace() + "/" + location.getPath();
                final InputStream stream = InterfaceManager.coreInterface.getPackResource(streamLocation);
                if (stream == null) {
                    if (!streamLocation.contains("/assets/mts/textures/mcfont") && ConfigSystem.settings.general.devMode.value) {
                        InterfaceManager.coreInterface.logError("Couldn't find requested pack resource: " + streamLocation);
                    }
                    return null;
                }
                //Return whichever stream we found.
                return () -> stream;
            } else {
                return null;
            }
        }

        @Override
        public Set<String> getNamespaces(PackType pType) {
            //Expose the core mod namespace plus every content-pack namespace, so MC's atlas
            //stitcher (which enumerates textures per-namespace via listResources) knows to ask us
            //for content-pack item/block textures.  On Forge the pack jars were real resource packs
            //and provided their own namespaces; on Fabric we stand in for all of them.
            Set<String> namespaces = new HashSet<>(fakeDomains);
            namespaces.addAll(domains);
            return namespaces;
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> pDeserializer) {
            return null;
        }

        @Override
        public String packId() {
            return InterfaceLoader.MODID + "_packs";
        }

        @Override
        public void close() {
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... pElements) {
            String pFileName = String.join("/", pElements);
            if (!pFileName.contains("/") && !pFileName.contains("\\")) {
                return this.getResource(PackType.CLIENT_RESOURCES, new ResourceLocation(pFileName));
            } else {
                throw new IllegalArgumentException("Root resources can only be filenames, not paths (no / allowed!)");
            }
        }

        @Override
        public void listResources(PackType pType, String pNamespace, String pPath, PackResources.ResourceOutput pResourceOutput) {
            //MC calls this to enumerate resources under assets/<namespace>/<path> — used mainly by
            //the item/block atlas stitcher.  On Fabric the content-pack jars are not resource packs,
            //so we walk their jar entries ourselves and emit every match.  Without this, item-atlas
            //textures for pack items never stitch and the icons render as the missing texture.
            if (pType != PackType.CLIENT_RESOURCES) {
                return;
            }
            String prefix = "assets/" + pNamespace + "/" + pPath + "/";
            Set<File> scanned = new HashSet<>();
            for (String packID : PackParser.getAllPackIDs()) {
                File packJar = PackParser.getPackJar(packID);
                if (packJar == null || !scanned.add(packJar)) {
                    continue;
                }
                try (ZipFile zip = new ZipFile(packJar)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!entry.isDirectory() && name.startsWith(prefix)) {
                            String relativePath = name.substring(("assets/" + pNamespace + "/").length());
                            ResourceLocation location = ResourceLocation.tryBuild(pNamespace, relativePath);
                            if (location != null) {
                                //Re-fetch through getPackResource so the returned stream comes from
                                //the session-cached ZipFile rather than this short-lived one.
                                final String streamLocation = "/" + name;
                                pResourceOutput.accept(location, () -> {
                                    InputStream stream = InterfaceManager.coreInterface.getPackResource(streamLocation);
                                    if (stream == null) {
                                        throw new IOException("Missing pack resource: " + streamLocation);
                                    }
                                    return stream;
                                });
                            }
                        }
                    }
                } catch (IOException e) {
                    //Skip an unreadable pack jar.
                }
            }
        }

        private static String getPackID(String path) {
            int distanceToFirstDot = path.indexOf(".");
            int distanceToSlashBefore = path.lastIndexOf("/", distanceToFirstDot);
            if (distanceToSlashBefore != -1) {
                String packID = path.substring(distanceToSlashBefore + 1, distanceToFirstDot);
                if (PackParser.getAllPackIDs().contains(packID)) {
                    return packID;
                }
            }
            //Not an actual pack resource, must be from core.
            return InterfaceLoader.MODID;
        }
    }
}
