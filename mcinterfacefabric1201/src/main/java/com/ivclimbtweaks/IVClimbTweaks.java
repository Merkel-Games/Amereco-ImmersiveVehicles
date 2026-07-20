package com.ivclimbtweaks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point for the climb-height tweaks, bundled inside the Immersive Vehicles Fabric port.
 * <p>
 * The whole {@code com.ivclimbtweaks} package is self-contained (it depends only on loader-agnostic
 * {@code mccore} APIs + the Fabric ModInitializer), so it can later be lifted out into a standalone Fabric
 * addon - move the package into a new jar and move the {@code fabric.mod.json} entrypoint with it.
 * <p>
 * This entrypoint is listed AFTER {@code mcinterfacefabric1201.InterfaceLoader} in {@code fabric.mod.json};
 * Fabric runs a mod's entrypoints in declared order and InterfaceLoader.onInitialize() parses all content
 * packs synchronously, so by the time we run the pack definitions already exist and can be patched. It is a
 * "main" entrypoint, so it runs (and therefore patches the definitions) on both the server and the client.
 *
 * @see ClimbHeightOverrider
 */
public class IVClimbTweaks implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("IVClimbTweaks");

    @Override
    public void onInitialize() {
        ClimbConfig.load(FabricLoader.getInstance().getConfigDir());
        ClimbHeightOverrider.apply();
    }
}
