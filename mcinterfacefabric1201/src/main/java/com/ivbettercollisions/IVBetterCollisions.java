package com.ivbettercollisions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point for the Better Collisions system, bundled inside the Immersive Vehicles Fabric port.
 * <p>
 * The whole {@code com.ivbettercollisions} package is deliberately self-contained (its only tie to the
 * port is {@link MtsAccess}) so it can later be lifted out into a standalone Fabric addon.  Extraction is:
 * move this package into a new jar and move the two {@code fabric.mod.json} entrypoints
 * ({@code com.ivbettercollisions.IVBetterCollisions} / {@code com.ivbettercollisions.ClientEvents}) into
 * that jar's own {@code fabric.mod.json}.
 *
 * @see ClientEvents
 * @see VehicleCollisionHandler
 */
public class IVBetterCollisions implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("IVBetterCollisions");

    @Override
    public void onInitialize() {
        CollisionConfig.load(FabricLoader.getInstance().getConfigDir());
        // Server authority: run the pass at the END of every server world tick, after MTS has moved
        // vehicles this tick.  Registered after the port's own tick handler, so it runs after it.
        ServerTickEvents.END_WORLD_TICK.register(level -> VehicleCollisionHandler.onWorldTickEnd(level, false));
        LOGGER.info("[IVBC] Better Collisions initialised (enabled={}, v2v={})", CollisionConfig.enabled, CollisionConfig.v2vEnabled);
    }
}
