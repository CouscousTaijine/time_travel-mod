package com.negger.chronos;

import com.negger.chronos.network.ChronosNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

public final class ChronosClient implements ClientModInitializer {
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastYaw;
    private static float lastPitch;
    private static boolean havePrevious;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ChronosNetworking.REWIND_MOTION, (client, handler, buf, responseSender) -> {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            boolean active = buf.readBoolean();

            client.execute(() -> {
                if (!active) {
                    havePrevious = false;
                    return;
                }

                if (client.player == null) return;

                // Le serveur a déjà envoyé le nouvel état de position. On remplace
                // uniquement le point de départ de l'interpolation du rendu : le
                // client affiche donc une transition entre deux snapshots au lieu
                // d'un saut visuel à chaque tick.
                if (havePrevious) {
                    client.player.prevX = lastX;
                    client.player.prevY = lastY;
                    client.player.prevZ = lastZ;
                    client.player.prevYaw = lastYaw;
                    client.player.prevPitch = lastPitch;
                }

                lastX = x;
                lastY = y;
                lastZ = z;
                lastYaw = yaw;
                lastPitch = pitch;
                havePrevious = true;
            });
        });
    }
}
