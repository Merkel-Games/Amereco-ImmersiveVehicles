package mcinterfacefabric1201;

import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.systems.ConfigSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Client-side loader.  Runs after all main entrypoints have completed, so all pack items and
 * registries are populated by the time this is called.  Handles client-only registrations:
 * dynamic model loading, keybinds, shaders, entity renderers, client tick hooks, screen events
 * and packet receivers.  Mirrors the {@code isClient} tail of the Forge {@code init()} plus the
 * old {@code FMLLoadCompleteEvent} fluid-config sync.
 *
 * @author don_bruce
 */
public class InterfaceClientLoader implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        //Create dynamic models/resource pack hooks.
        InterfaceEventsModelLoader.init();

        //Create the keybind instances, THEN register them (registration reads the instances).
        InterfaceManager.inputInterface.initConfigKey();
        InterfaceInput.registerKeyMappings();
        InterfaceInput.registerScreenEvents();

        //Register shaders + entity renderers.
        InterfaceRender.initClient();

        //Register the client packet receiver.
        InterfacePacket.initClient();

        //Client tick: START then END phases, matching the Forge ClientTickEvent phases.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> InterfaceClient.onClientTick(true));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            InterfaceClient.onClientTick(false);
            InterfaceSound.onClientTick();
        });

        //Client world unload (Forge fired LevelEvent.Unload client-side).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> fireClientWorldUnload(client.level));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof BuilderEntityExisting) {
                ((BuilderEntityExisting) entity).onRemovedFromWorld();
            }
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> BuilderTileEntity.onChunkUnload(chunk));

        //Equivalent of the Forge FMLLoadCompleteEvent: record all fluids for modpack makers.
        ConfigSystem.settings.fuel.lastLoadedFluids = InterfaceManager.clientInterface.getAllFluidNames();
        ConfigSystem.saveToDisk();
    }

    /**
     * Fires the client-side world-unload hooks that Forge dispatched via LevelEvent.Unload.
     * Called on disconnect and (via MinecraftMixin) when the client level is swapped or cleared.
     */
    public static void fireClientWorldUnload(Level level) {
        if (level != null) {
            WrapperEntity.onWorldUnload(level);
            WrapperPlayer.onWorldUnload(level);
            InterfaceSound.onWorldUnload(level);
        }
    }
}
