package com.negger.chronos.item;

import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/**
 * Éclat Chronos : clic droit MAINTENU pour remonter le temps.
 * Tant que le bouton est maintenu, on "dépile" l'historique du joueur
 * tick par tick : il est téléporté en arrière, sa vie/faim est restaurée,
 * et les blocs qu'il a cassés/posés dans cette fenêtre sont remis à leur
 * état d'origine.
 *
 * Relâcher le clic arrête le rewind. Une fois arrêté, le jeu recommence
 * à enregistrer normalement à partir du nouveau point dans le temps.
 */
public class ChronosShardItem extends Item {

    // Combien de ticks d'historique on consomme par tick de jeu réel.
    // ChronosConfig.rewindSpeed = 1.0 -> rewind en temps réel (1 tick d'historique par tick).
    private static final int MAX_USE_TICKS = 72000; // 1h de maintien max, largement suffisant

    public ChronosShardItem(Settings settings) {
        super(settings);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.BOW; // réutilise l'animation "tenir en visée" existante
    }

    @Override
    public int getMaxUseTime(ItemStack stack) {
        return MAX_USE_TICKS;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
        if (!world.isClient) {
            if (HistoryManager.getPlayerHistorySize(user.getUuid()) == 0) {
                user.sendMessage(net.minecraft.text.Text.literal("§7Aucun historique à remonter pour l'instant."), true);
                return TypedActionResult.fail(user.getStackInHand(hand));
            }
        }
        user.setCurrentHand(hand);
        return TypedActionResult.consume(user.getStackInHand(hand));
    }

    @Override
    public void usageTick(World world, net.minecraft.entity.LivingEntity user, ItemStack stack, int remainingUseTicks) {
        if (world.isClient) return;
        if (!(user instanceof ServerPlayerEntity player)) return;

        // rewindSpeed permet de dépiler plusieurs ticks d'historique par tick réel
        int stepsThisTick = Math.max(1, (int) Math.round(ChronosConfig.rewindSpeed));

        for (int i = 0; i < stepsThisTick; i++) {
            TimeSnapshot snapshot = HistoryManager.popLatestSnapshot(player.getUuid());
            if (snapshot == null) {
                // Historique épuisé : on arrête proprement le rewind
                player.stopUsingItem();
                player.sendMessage(net.minecraft.text.Text.literal("§6Tu as atteint la limite de ton historique."), true);
                return;
            }

            applySnapshot(player, snapshot);
            revertBlocksAt(player, snapshot.tick());
        }

        // Petit effet visuel/sonore pour que ce soit clair qu'on remonte le temps
        if (player.age % 4 == 0) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.4f, 1.6f);
        }
        if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.REVERSE_PORTAL,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    3, 0.3, 0.5, 0.3, 0.02);
        }

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 10, 0, false, false));
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, net.minecraft.entity.LivingEntity user, int remainingUseTicks) {
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            player.sendMessage(net.minecraft.text.Text.literal("§bRetour au présent."), true);
        }
    }

    private void applySnapshot(ServerPlayerEntity player, TimeSnapshot snapshot) {
        player.teleport(player.getServerWorld(), snapshot.x(), snapshot.y(), snapshot.z(),
                snapshot.yaw(), snapshot.pitch());

        if (ChronosConfig.restoreHealthAndHunger) {
            player.setHealth(snapshot.health());
            player.getHungerManager().setFoodLevel(snapshot.foodLevel());
            player.getHungerManager().setSaturationLevel(snapshot.saturation());
        }
    }

    private void revertBlocksAt(ServerPlayerEntity player, long targetTick) {
        BlockChange change;
        while ((change = HistoryManager.popMatchingBlockChange(
                player.getUuid(), targetTick, ChronosConfig.onlyRevertOwnBlocks)) != null) {
            player.getServerWorld().setBlockState(change.pos(), change.oldState());
        }
    }
}
