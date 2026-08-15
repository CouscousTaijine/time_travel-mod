package com.negger.chronos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import com.negger.chronos.rewind.RewindManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

public class ChronosCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("chronos")
                .then(CommandManager.literal("back")
                        .then(CommandManager.argument("secondes", FloatArgumentType.floatArg(0.05f))
                                .executes(ChronosCommands::executeBack)))
                .then(CommandManager.literal("speed")
                        .then(CommandManager.argument("vitesse", FloatArgumentType.floatArg(0.25f, 100f))
                                .executes(ChronosCommands::executeSpeed)))
                .then(CommandManager.literal("speed").executes(ChronosCommands::executeSpeedStatus))
                .then(CommandManager.literal("status").executes(ChronosCommands::executeStatus)));
    }

    private static int executeSpeed(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        float speed = FloatArgumentType.getFloat(ctx, "vitesse");
        ChronosConfig.rewindSpeed = speed;
        ChronosConfig.save(FabricLoader.getInstance().getConfigDir());
        player.sendMessage(Text.literal(String.format("§bVitesse du rewind : §f%.2fx §7(sauvegardée)", speed)), false);
        return 1;
    }

    private static int executeSpeedStatus(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        player.sendMessage(Text.literal(String.format("§bVitesse actuelle : §f%.2fx", ChronosConfig.rewindSpeed)), false);
        player.sendMessage(Text.literal("§7Change-la avec §f/chronos speed <vitesse>§7, ex: 0.5, 1, 2, 5, 10."), false);
        return 1;
    }

    private static int executeBack(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        float seconds = FloatArgumentType.getFloat(ctx, "secondes");
        int ticksRequested = Math.round(seconds * 20);
        List<TimeSnapshot> history = HistoryManager.combinedHistory(player.getUuid());
        if (history.isEmpty()) {
            player.sendMessage(Text.literal("§cAucun historique disponible pour l'instant. Bouge un peu d'abord."), false);
            return 0;
        }
        int targetIndex = Math.max(0, history.size() - ticksRequested);
        TimeSnapshot target = history.get(targetIndex);
        int actualTicks = history.size() - targetIndex;
        int reverted = RewindManager.revertInstantTo(player, target.tick());
        List<TimeSnapshot> shortTerm = HistoryManager.snapshotHistory(player.getUuid());
        int longTermCount = history.size() - shortTerm.size();
        if (targetIndex < longTermCount) HistoryManager.truncateHistoryTo(player.getUuid(), 0);
        else HistoryManager.truncateHistoryTo(player.getUuid(), (targetIndex - longTermCount) + 1);
        player.teleport(player.getServerWorld(), target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        if (ChronosConfig.restoreHealthAndHunger) {
            player.setHealth(Math.max(0.1f, target.health()));
            player.getHungerManager().setFoodLevel(target.foodLevel());
            player.getHungerManager().setSaturationLevel(target.saturation());
        }
        player.sendMessage(Text.literal(String.format("§bSaut de %.1f s dans le passé (%d blocs restaurés).", actualTicks / 20.0, reverted)), false);
        return 1;
    }

    private static int executeStatus(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        int fluidTicks = HistoryManager.getPlayerHistorySize(player.getUuid());
        int longTermPoints = HistoryManager.getLongTermHistory(player.getUuid()).size();
        player.sendMessage(Text.literal(String.format("§7Historique fluide : §b%.0f s §7| longue durée : §b%s", fluidTicks / 20.0, formatDuration(longTermPoints))), false);
        player.sendMessage(Text.literal(String.format("§7Vitesse : §b%.2fx", ChronosConfig.rewindSpeed)), false);
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
