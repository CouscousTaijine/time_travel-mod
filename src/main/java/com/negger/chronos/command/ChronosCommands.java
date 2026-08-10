package com.negger.chronos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * /chronos back <minutes>  -> saut instantané précis dans le passé (pas en timelapse,
 *                              contrairement à l'item — c'est fait exprès, pour un usage
 *                              rapide en admin/debug)
 * /chronos status          -> combien de temps d'historique il te reste
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
        ServerPlayerEntity player = source.getPlayer();

        float minutes = FloatArgumentType.getFloat(ctx, "minutes");
        int ticksRequested = Math.round(minutes * 60 * 20);

        List<TimeSnapshot> history = HistoryManager.combinedHistory(player.getUuid());
        if (history.isEmpty()) {
            player.sendMessage(Text.literal("§cAucun historique disponible pour l'instant. Bouge un peu d'abord."), false);
            return 0;
        }

        int targetIndex = Math.max(0, history.size() - ticksRequested);
        TimeSnapshot target = history.get(targetIndex);
        int actualTicks = history.size() - targetIndex;

        int reverted = RewindManager.revertInstantTo(player, target.tick());

        // La troncature du "futur" ne s'applique qu'à la partie récente en mémoire (20x/sec) ;
        // si le saut va plus loin, dans la partie longue durée, on vide juste la partie récente.
        List<TimeSnapshot> shortTerm = HistoryManager.snapshotHistory(player.getUuid());
        int longTermCount = history.size() - shortTerm.size();
        if (targetIndex < longTermCount) {
            HistoryManager.truncateHistoryTo(player.getUuid(), 0);
        } else {
            HistoryManager.truncateHistoryTo(player.getUuid(), (targetIndex - longTermCount) + 1);
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
                    actualMinutes, (double) minutes, reverted)), false);
        } else {
            player.sendMessage(Text.literal(String.format(
                    "§bSaut de %.1f min dans le passé effectué (%d blocs restaurés).",
                    actualMinutes, reverted)), false);
        }

        return 1;
    }

    private static int executeStatus(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();

        int fluidTicks = HistoryManager.getPlayerHistorySize(player.getUuid());
        int longTermPoints = HistoryManager.getLongTermHistory(player.getUuid()).size();

        double fluidSeconds = fluidTicks / 20.0;
        double totalSeconds = fluidSeconds + longTermPoints; // longTerm = 1 point/seconde

        player.sendMessage(Text.literal(String.format(
                "§7Historique fluide (20x/sec) : §b%.0f s §7| longue durée (1x/sec, survit aux redémarrages) : §b%s",
                fluidSeconds, formatDuration(longTermPoints))), false);
        player.sendMessage(Text.literal(String.format(
                "§7Total remontable : §b%s", formatDuration((long) totalSeconds))), false);
        return 1;
    }

    private static String formatDuration(long totalSeconds) {
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (days > 0) return String.format("%dj %dh %dmin", days, hours, minutes);
        if (hours > 0) return String.format("%dh %dmin", hours, minutes);
        return String.format("%dmin", minutes);
    }
}
