package com.negger.chronos.item;

import com.negger.chronos.rewind.RewindManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/**
 * Éclat Chronos. Tout se joue au clic droit (tap = rapide, maintien = long) :
 *
 * - Pas accroupi, TAP        -> retour au présent (timelapse)
 * - Pas accroupi, MAINTIEN   -> rembobine en arrière tant que maintenu (timelapse)
 * - Accroupi, TAP            -> pose un point de sauvegarde
 * - Accroupi, MAINTIEN       -> file vers le point de sauvegarde (timelapse)
 *
 * La distinction tap/maintien est faite via la durée réelle d'utilisation
 * (voir RewindManager.onRightClickPress / onRightClickRelease). Le mouvement
 * ne commence qu'une fois le seuil de maintien dépassé, donc un simple tap
 * ne provoque jamais de micro-saut visuel.
 */
public class ChronosShardItem extends Item {

    private static final int MAX_USE_TICKS = 72000; // 1h de maintien max

    public ChronosShardItem(Settings settings) {
        super(settings);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.BOW;
    }

    @Override
    public int getMaxUseTime(ItemStack stack) {
        return MAX_USE_TICKS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
        if (world.isClient || !(user instanceof ServerPlayerEntity player)) {
            return TypedActionResult.consume(user.getStackInHand(hand));
        }

        boolean accepted = RewindManager.onRightClickPress(player);
        if (!accepted) {
            player.sendMessage(Text.literal("§7Aucun historique à remonter pour l'instant. Bouge un peu d'abord."), true);
            return TypedActionResult.fail(user.getStackInHand(hand));
        }

        user.setCurrentHand(hand);
        return TypedActionResult.consume(user.getStackInHand(hand));
    }

    @Override
    public void usageTick(World world, net.minecraft.entity.LivingEntity user, ItemStack stack, int remainingUseTicks) {
        // Le vrai travail est fait chaque tick serveur par RewindManager.tickAll() (voir ChronosMod)
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, net.minecraft.entity.LivingEntity user, int remainingUseTicks) {
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            RewindManager.onRightClickRelease(player);
        }
    }
}
