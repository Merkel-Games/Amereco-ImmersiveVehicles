package mcinterfacefabric1201;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import io.netty.buffer.ByteBuf;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IInterfacePacket;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.components.APacketBase;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

class InterfacePacket implements IInterfacePacket {
    public static final ResourceLocation CHANNEL = new ResourceLocation("mts", "main");
    private static final BiMap<Byte, Class<? extends APacketBase>> packetMappings = HashBiMap.create();
    private static MinecraftServer currentServer;

    /**
     * Called to init this network.  Needs to be done after networking is ready.
     * Packets should be registered at this point in this constructor.
     */
    public static void init() {
        //Register the server-side receiver for the main channel.
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, handler, buf, responseSender) -> {
            //Parse the packet now, as the buffer is invalid once this method returns.
            APacketBase packet = parsePacket(buf);
            if (packet.runOnMainThread()) {
                //Need to put this in a runnable to not run it on the network thread and get a CME.
                server.execute(() -> {
                    AWrapperWorld world = WrapperWorld.getWrapperFor(player.level());
                    if (world != null) {
                        packet.handle(world);
                    }
                });
            } else {
                packet.handle(WrapperWorld.getWrapperFor(player.level()));
            }
        });

        //Track the running server so we can send packets to all its players.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> currentServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> currentServer = null);

        //Register internal packets, then external.
        byte packetIndex = 0;
        InterfaceManager.packetInterface.registerPacket(packetIndex++, PacketEntityCSHandshakeClient.class);
        InterfaceManager.packetInterface.registerPacket(packetIndex++, PacketEntityCSHandshakeServer.class);
        APacketBase.initPackets(packetIndex);
    }

    /**
     * Called to init the client-side network receiver.  Needs to be done after networking is ready.
     * Only call this from the client entrypoint: it must never run on a dedicated server.
     */
    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buf, responseSender) -> {
            //Parse the packet now, as the buffer is invalid once this method returns.
            APacketBase packet = parsePacket(buf);
            if (packet.runOnMainThread()) {
                //Need to put this in a runnable to not run it on the network thread and get a CME.
                client.execute(() -> {
                    AWrapperWorld world = InterfaceManager.clientInterface.getClientWorld();
                    if (world != null) {
                        packet.handle(world);
                    }
                });
            } else {
                packet.handle(InterfaceManager.clientInterface.getClientWorld());
            }
        });
    }

    /**
     * Parses the packet in the buffer.  MC won't know what class to construct,
     * that's up to us to handle via the packet's first byte.
     */
    private static APacketBase parsePacket(FriendlyByteBuf buf) {
        byte packetIndex = buf.readByte();
        Class<? extends APacketBase> packetClass = packetMappings.get(packetIndex);
        if (packetClass == null) {
            throw new IndexOutOfBoundsException("Was asked to create packet of index " + packetIndex + " but we haven't registered that one yet!");
        }
        try {
            return packetClass.getConstructor(ByteBuf.class).newInstance(buf);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException("Was asked to create packet of " + packetClass.getSimpleName() + " but couldn't due to an error.  Something went VERY wrong here.  Check the log for more information.");
        }
    }

    @Override
    public void registerPacket(byte packetIndex, Class<? extends APacketBase> packetClass) {
        packetMappings.put(packetIndex, packetClass);
    }

    @Override
    public byte getPacketIndex(APacketBase packet) {
        return packetMappings.inverse().get(packet.getClass());
    }

    @Override
    public void sendToServer(APacketBase packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        packet.writeToBuffer(buf);
        //The actual send call lives in a client-only holder class, as this class is
        //also loaded on dedicated servers where the client networking classes don't exist.
        ClientPacketSender.sendToServer(buf);
    }

    @Override
    public void sendToAllClients(APacketBase packet) {
        if (currentServer != null) {
            for (ServerPlayer player : PlayerLookup.all(currentServer)) {
                //A fresh buffer per player: Netty consumes the reader index on send, so a
                //shared buffer would arrive truncated for every player after the first.
                FriendlyByteBuf buf = PacketByteBufs.create();
                packet.writeToBuffer(buf);
                ServerPlayNetworking.send(player, CHANNEL, buf);
            }
        }
    }

    @Override
    public void sendToPlayer(APacketBase packet, IWrapperPlayer player) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        packet.writeToBuffer(buf);
        ServerPlayNetworking.send((ServerPlayer) ((WrapperPlayer) player).player, CHANNEL, buf);
    }

    @Override
    public void writeDataToBuffer(IWrapperNBT data, ByteBuf buf) {
        //We know this will be a PacketBuffer, so we can cast rather than wrap.
        ((FriendlyByteBuf) buf).writeNbt(((WrapperNBT) data).tag);
    }

    @Override
    public WrapperNBT readDataFromBuffer(ByteBuf buf) {
        return new WrapperNBT(((FriendlyByteBuf) buf).readNbt());
    }
}
