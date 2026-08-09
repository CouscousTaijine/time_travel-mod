package com.negger.chronos.item;

import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/**
 * Éclat Chronos.
 *
 * - Clic droit maintenu (pas accroupi)  -> rembobine en arrière, en direct (timelapse)
 * - Clic gauche (pas accroupi)          -> repart en avant jusqu'au présent (timelapse)
 * - Accroupi + clic droit               -> pose un point de sauvegarde à l'instant présent
 * - Accroupi + clic gauche              -> revient à ce point de sauvegarde (timelapse)
 *
 * Le clic gauche n'existe pas nativement comme "action d'objet" en Minecraft
 * (c'est une attaque), donc il est intercepté côté serveur par un mixin
 * (voir mixin/HandSwingMixin.java) qui appelle RewindManager directement.
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

        if (player.isSneaking()) {
            // Accroupi + clic droit = poser un point de sauvegarde (action instantanée)
            RewindManager.setSavepoint(player);
            return TypedActionResult.success(user.getStackInHand(hand));
        }

        if (HistoryManager.getPlayerHistorySize(player.getUuid()) == 0 && !RewindManager.isRewinding(player.getUuid())) {
            player.sendMessage(Text.literal("§7Aucun historique à remonter pour l'instant. Bouge un peu d'abord."), true);
            return TypedActionResult.fail(user.getStackInHand(hand));
        }

        boolean started = RewindManager.startOrResumeHeldBackward(player);
        if (!started) {
            player.sendMessage(Text.literal("§6Tu as atteint la limite de ton historique."), true);
            return TypedActionResult.fail(user.getStackInHand(hand));
        }

        user.setCurrentHand(hand);
        return TypedActionResult.consume(user.getStackInHand(hand));
    }

    @Override
    public void usageTick(World world, net.minecraft.entity.LivingEntity user, ItemStack stack, int remainingUseTicks) {
        // Le vrai travail est fait une fois par tick serveur pour tout le monde
        // par RewindManager.tickAll() (voir ChronosMod). Ici on ne fait que
        // garder l'item "en cours d'utilisation" tant que le clic est maintenu.
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, net.minecraft.entity.LivingEntity user, int remainingUseTicks) {
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            RewindManager.pauseHeld(player);
        }
    }
}
