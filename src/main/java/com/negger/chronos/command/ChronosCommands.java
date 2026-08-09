package com.negger.chronos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * /chronos back <minutes>  -> saut instantané précis dans le passé
 * /chronos status          -> combien de temps d'historique il te reste
 *
 * Contrairement à l'item (qui rembobine en temps réel, tick par tick, pour
 * VOIR le rewind se dérouler), cette commande saute directement au point
 * demandé en un seul coup. Les deux partagent le même historique : si tu
 * fais /chronos back 5 puis que tu utilises l'Éclat Chronos, il continuera
 * depuis là où la commande t'a laissé.
 */
public class ChronosCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("chronos")
                .then(CommandManager.literal("back")
                        .then(CommandManager.argument("minutes", FloatArgumentType.floatArg(0.01f))
                                .executes(ChronosCommands::executeBack)))
                .then(CommandManager.literal("status")
                        .executes(ChronosCommands::executeStatus))
        );
    }

    private static int executeBack(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer(); // lève CommandSyntaxException si pas un joueur (ex: console)

        float minutes = FloatArgumentType.getFloat(ctx, "minutes");
        int ticksRequested = Math.round(minutes * 60 * 20);

        int availableTicks = HistoryManager.getPlayerHistorySize(player.getUuid());
        if (availableTicks == 0) {
            player.sendMessage(Text.literal("§cAucun historique disponible pour l'instant. Bouge un peu d'abord."), false);
            return 0;
        }

        int actualTicks = Math.min(ticksRequested, availableTicks);

        TimeSnapshot target = HistoryManager.jumpBack(player.getUuid(), actualTicks);
        if (target == null) {
            player.sendMessage(Text.literal("§cÉchec du saut temporel."), false);
            return 0;
        }

        // Restaure tous les blocs changés par ce joueur dans la fenêtre traversée
        List<BlockChange> reverted = HistoryManager.popAllBlockChangesSince(
                player.getUuid(), target.tick(), ChronosConfig.onlyRevertOwnBlocks);
        for (BlockChange change : reverted) {
            player.getServerWorld().setBlockState(change.pos(), change.oldState());
        }

        player.teleport(player.getServerWorld(), target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        if (ChronosConfig.restoreHealthAndHunger) {
            player.setHealth(target.health());
            player.getHungerManager().setFoodLevel(target.foodLevel());
            player.getHungerManager().setSaturationLevel(target.saturation());
        }

        double actualMinutes = actualTicks / 20.0 / 60.0;
        if (actualTicks < ticksRequested) {
            player.sendMessage(Text.literal(String.format(
                    "§6Ton historique ne remontait pas si loin. Saut de %.1f min au lieu de %.1f min demandées (%d blocs restaurés).",
                    actualMinutes, (double) minutes, reverted.size())), false);
        } else {
            player.sendMessage(Text.literal(String.format(
                    "§bSaut de %.1f min dans le passé effectué (%d blocs restaurés).",
                    actualMinutes, reverted.size())), false);
        }

        return 1;
    }

    private static int executeStatus(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();

        int ticks = HistoryManager.getPlayerHistorySize(player.getUuid());
        double seconds = ticks / 20.0;
        int minutes = (int) (seconds / 60);
        double remSeconds = seconds - (minutes * 60);

        player.sendMessage(Text.literal(String.format(
                "§7Historique disponible : §b%d min %.0f s §7(capacité max configurée : %d s)",
                minutes, remSeconds, ChronosConfig.bufferSeconds)), false);
        return 1;
    }
}
