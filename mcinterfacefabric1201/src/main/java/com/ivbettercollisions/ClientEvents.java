package com.ivbettercollisions;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client-side hookup for Better Collisions (mirrors the original addon's inner {@code ClientEvents}).
 * <p>
 * Running the same correction pass on the client - which ticks vehicles with its own prediction - keeps
 * the predicted position in agreement with the server authority, so vehicles don't rubber-band into walls
 * on contact.  The pass never mutates blocks or the world, so it is safe to run client-side.
 */
@Environment(EnvType.CLIENT)
public class ClientEvents implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!mc.isPaused() && mc.level != null) {
                VehicleCollisionHandler.onWorldTickEnd(mc.level, true);
            }
        });
    }
}
