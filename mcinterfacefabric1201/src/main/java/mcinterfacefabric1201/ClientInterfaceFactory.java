package mcinterfacefabric1201;

import minecrafttransportsimulator.mcinterface.InterfaceManager;

/**
 * Isolates the construction of the client-side interfaces so their classes are never
 * class-loaded on a dedicated server.  {@link InterfaceLoader#onInitialize()} only touches
 * this class inside its {@code isClient} branch, so on a server the client interface classes
 * (InterfaceClient/Input/Sound/Render) are never referenced and therefore never loaded.
 *
 * @author don_bruce
 */
public final class ClientInterfaceFactory {

    private ClientInterfaceFactory() {
    }

    public static void createInterfaceManager(String modID, String gameDirectory) {
        new InterfaceManager(modID, gameDirectory, new InterfaceCore(), new InterfacePacket(), new InterfaceClient(), new InterfaceInput(), new InterfaceSound(), new InterfaceRender());
    }
}
