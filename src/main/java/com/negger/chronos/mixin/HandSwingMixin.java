package com.negger.chronos.mixin;

import com.negger.chronos.ChronosMod;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft n'envoie pas d'event exploitable côté serveur pour "le joueur a
 * cliqué gauche" (ça part d'un simple paquet "swing de main", pas lié à un
 * bloc ou une entité visée). On intercepte donc ce paquet directement.
 */
@Mixin(ServerPlayNetworkHandler.class)
public class HandSwingMixin {

    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void chronos$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (player == null) return;
        if (player.getMainHandStack().getItem() != ChronosMod.CHRONOS_SHARD) return;

        if (player.isSneaking()) {
            RewindManager.onRestoreSavepoint(player);
        } else {
            RewindManager.onReturnToPresent(player);
        }
    }
}
