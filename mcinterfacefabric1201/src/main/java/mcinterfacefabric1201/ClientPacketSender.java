package mcinterfacefabric1201;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Static holder for the client->server send call.  This lives in its own class, as
 * {@link InterfacePacket} is also loaded on dedicated servers, and the client networking
 * classes referenced here don't exist there.  This class is only classloaded when
 * {@link #sendToServer(FriendlyByteBuf)} is first called, which only happens on clients.
 */
public class ClientPacketSender {

    public static void sendToServer(FriendlyByteBuf buf) {
        ClientPlayNetworking.send(InterfacePacket.CHANNEL, buf);
    }
}
