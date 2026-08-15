package com.negger.chronos.network;

import com.negger.chronos.history.TimeSnapshot;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Paquet envoyé au joueur qui rembobine, pour que SON PROPRE client interpole
 * visuellement entre deux snapshots au lieu d'un saut sec à chaque tick
 * (voir ChronosClient pour la réception).
 *
 * Avant ce fix, ce paquet était défini et écouté côté client mais jamais
 * envoyé côté serveur : la transition fluide documentée n'avait donc aucun
 * effet, le rewind se faisait tout de même par sauts.
 */
public final class ChronosNetworking {
    public static final Identifier REWIND_MOTION = new Identifier("chronos", "rewind_motion");

    private ChronosNetworking() {}

    public static void sendRewindMotion(ServerPlayerEntity player, TimeSnapshot snapshot) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(snapshot.x());
        buf.writeDouble(snapshot.y());
        buf.writeDouble(snapshot.z());
        buf.writeFloat(snapshot.yaw());
        buf.writeFloat(snapshot.pitch());
        buf.writeBoolean(true);
        ServerPlayNetworking.send(player, REWIND_MOTION, buf);
    }

    public static void sendRewindEnded(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(0.0).writeDouble(0.0).writeDouble(0.0).writeFloat(0.0f).writeFloat(0.0f);
        buf.writeBoolean(false);
        ServerPlayNetworking.send(player, REWIND_MOTION, buf);
    }
}
