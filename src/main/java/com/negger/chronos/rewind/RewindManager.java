package com.negger.chronos.rewind;

import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.DeathRecord;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les sessions de rewind par joueur (tenu en main OU en lecture auto)
 * et les points de sauvegarde. Une session ne détruit jamais l'historique de
 * position : elle se contente de "naviguer" dedans avec un curseur, donc on
 * peut toujours revenir en avant. Les blocs, eux, sont dépilés/rempilés
 * (undo/redo) pour rester cohérents avec ce qui a été rejoué.
 */
public class RewindManager {

    private enum Mode { HELD_BACKWARD, AUTO_FORWARD, AUTO_TO_TARGET }

    private static class Session {
        List<TimeSnapshot> buffer; // du plus vieux au plus récent
        int cursor;                // index actuellement affiché dans buffer
        Mode mode;
        Integer targetCursor;      // utilisé pour AUTO_TO_TARGET
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, TimeSnapshot> SAVEPOINTS = new ConcurrentHashMap<>();

    // ----- Point d'entrée : clic droit (maintenu) sans être accroupi -----

    public static boolean startOrResumeHeldBackward(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Session session = SESSIONS.get(id);

        if (session == null) {
            List<TimeSnapshot> buffer = HistoryManager.snapshotHistory(id);
            if (buffer.isEmpty()) return false;
            session = new Session();
            session.buffer = buffer;
            session.cursor = buffer.size() - 1;
            SESSIONS.put(id, session);
            HistoryManager.setPaused(id, true);
        }

        if (session.cursor <= 0) return false; // déjà au bout de l'historique connu
        session.mode = Mode.HELD_BACKWARD;
        session.targetCursor = null;
        return true;
    }

    public static void pauseHeld(ServerPlayerEntity player) {
        Session session = SESSIONS.get(player.getUuid());
        if (session != null) session.mode = null;
    }

    // ----- Clic gauche : retour au présent (si pas accroupi) -----

    public static void onReturnToPresent(ServerPlayerEntity player) {
        Session session = SESSIONS.get(player.getUuid());
        if (session == null) {
            player.sendMessage(Text.literal("§7Tu es déjà au présent."), true);
            return;
        }
        session.mode = Mode.AUTO_FORWARD;
        session.targetCursor = session.buffer.size() - 1;
    }

    // ----- Accroupi + clic droit : poser un point de sauvegarde -----

    public static void setSavepoint(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Session session = SESSIONS.get(id);
        TimeSnapshot current;

        if (session != null) {
            current = session.buffer.get(session.cursor);
        } else {
            current = new TimeSnapshot(
                    HistoryManager.getCurrentTick(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(),
                    player.getHealth(),
                    player.getHungerManager().getFoodLevel(),
                    player.getHungerManager().getSaturationLevel()
            );
        }

        SAVEPOINTS.put(id, current);
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.5f, 1.8f);
        player.sendMessage(Text.literal("§dPoint de sauvegarde posé."), true);
    }

    // ----- Accroupi + clic gauche : revenir au point de sauvegarde (timelapse) -----

    public static void onRestoreSavepoint(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        TimeSnapshot savepoint = SAVEPOINTS.get(id);
        if (savepoint == null) {
            player.sendMessage(Text.literal("§cAucun point de sauvegarde posé. Accroupis-toi + clic droit d'abord."), true);
            return;
        }

        Session session = SESSIONS.get(id);
        if (session == null) {
            List<TimeSnapshot> buffer = HistoryManager.snapshotHistory(id);
            if (buffer.isEmpty()) return;
            session = new Session();
            session.buffer = buffer;
            session.cursor = buffer.size() - 1;
            SESSIONS.put(id, session);
            HistoryManager.setPaused(id, true);
        }

        int target = closestIndexForTick(session.buffer, savepoint.tick());
        session.mode = Mode.AUTO_TO_TARGET;
        session.targetCursor = target;
    }

    private static int closestIndexForTick(List<TimeSnapshot> buffer, long tick) {
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < buffer.size(); i++) {
            long diff = Math.abs(buffer.get(i).tick() - tick);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    // ----- Boucle principale : appelée chaque tick serveur pour chaque joueur -----

    public static void tickAll() {
        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.mode == null) continue; // en pause, rien à faire (figé dans le passé)

            ServerPlayerEntity player = findPlayer(entry.getKey());
            if (player == null) continue;

            int steps = Math.max(1, (int) Math.round(ChronosConfig.rewindSpeed));
            for (int i = 0; i < steps; i++) {
                if (!advanceOneStep(player, session)) break;
            }
        }
    }

    private static ServerPlayerEntity findPlayer(UUID id) {
        return CURRENT_SERVER == null ? null : CURRENT_SERVER.getPlayerManager().getPlayer(id);
    }

    private static net.minecraft.server.MinecraftServer CURRENT_SERVER;
    public static void setServer(net.minecraft.server.MinecraftServer server) {
        CURRENT_SERVER = server;
    }

    /** Renvoie false si la session est terminée (arrivée à destination ou plus rien à jouer). */
    private static boolean advanceOneStep(ServerPlayerEntity player, Session session) {
        boolean backward = switch (session.mode) {
            case HELD_BACKWARD -> true;
            case AUTO_FORWARD -> false;
            case AUTO_TO_TARGET -> session.targetCursor < session.cursor;
        };

        int newCursor = backward ? session.cursor - 1 : session.cursor + 1;
        if (newCursor < 0 || newCursor >= session.buffer.size()) {
            finishSession(player, session);
            return false;
        }

        long oldTick = session.buffer.get(session.cursor).tick();
        long newTick = session.buffer.get(newCursor).tick();

        if (backward) {
            revertBlocksAndEntities(player, newTick, oldTick);
        } else {
            replayBlocks(player, newTick);
        }

        session.cursor = newCursor;
        applySnapshot(player, session.buffer.get(newCursor));
        playFeedback(player, backward);

        boolean reachedTarget = session.mode == Mode.AUTO_TO_TARGET && session.cursor == session.targetCursor;
        boolean reachedPresent = session.mode == Mode.AUTO_FORWARD && session.cursor == session.buffer.size() - 1;
        boolean reachedStart = session.mode == Mode.HELD_BACKWARD && session.cursor == 0;

        if (reachedTarget || reachedPresent) {
            finishSession(player, session);
            return false;
        }
        if (reachedStart) {
            session.mode = null; // reste figé, en pause, prêt à reprendre plus tard
            player.sendMessage(Text.literal("§6Tu as atteint la limite de ton historique."), true);
            player.stopUsingItem();
            return false;
        }
        return true;
    }

    private static void finishSession(ServerPlayerEntity player, Session session) {
        if (session.mode == Mode.AUTO_FORWARD || (session.mode == Mode.AUTO_TO_TARGET && session.cursor >= session.buffer.size() - 1)) {
            SESSIONS.remove(player.getUuid());
            HistoryManager.setPaused(player.getUuid(), false);
            player.sendMessage(Text.literal("§bRetour au présent."), true);
        } else {
            session.mode = null; // point de sauvegarde atteint : on reste figé là, en pause
            player.sendMessage(Text.literal("§dPoint de sauvegarde atteint."), true);
        }
        player.stopUsingItem();
    }

    private static void applySnapshot(ServerPlayerEntity player, TimeSnapshot snapshot) {
        player.teleport(player.getServerWorld(), snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        if (ChronosConfig.restoreHealthAndHunger) {
            player.setHealth(snapshot.health());
            player.getHungerManager().setFoodLevel(snapshot.foodLevel());
            player.getHungerManager().setSaturationLevel(snapshot.saturation());
        }
    }

    // ----- Blocs : annuler en reculant, rejouer en avançant -----

    private static void revertBlocksAndEntities(ServerPlayerEntity player, long newTick, long oldTick) {
        BlockChange change;
        while ((change = HistoryManager.popMatchingBlockChange(player.getUuid(), newTick + 1)) != null) {
            player.getServerWorld().setBlockState(change.pos(), change.oldState());
            giveOrTakeItemForRevert(player, change);
        }

        for (DeathRecord death : HistoryManager.popDeathsBetween(newTick + 1, oldTick)) {
            reviveEntity(player, death);
        }
    }

    private static void replayBlocks(ServerPlayerEntity player, long newTick) {
        BlockChange change;
        while ((change = HistoryManager.redoMatchingBlockChange(player.getUuid(), newTick)) != null) {
            player.getServerWorld().setBlockState(change.pos(), change.newState());
            giveOrTakeItemForReplay(player, change);
        }
    }

    /** Undo d'un BREAK = le bloc réapparaît -> on retire l'item de l'inventaire. Undo d'un PLACE = le bloc disparaît -> on rend l'item. */
    private static void giveOrTakeItemForRevert(ServerPlayerEntity player, BlockChange change) {
        if (change.type() == BlockChange.ChangeType.BREAK) {
            removeOneMatchingItem(player, change.oldState().getBlock().asItem());
        } else if (change.type() == BlockChange.ChangeType.PLACE) {
            giveItem(player, change.newState().getBlock().asItem());
        }
    }

    /** Redo d'un BREAK = le bloc disparaît -> on rend l'item. Redo d'un PLACE = le bloc réapparaît -> on retire l'item. */
    private static void giveOrTakeItemForReplay(ServerPlayerEntity player, BlockChange change) {
        if (change.type() == BlockChange.ChangeType.BREAK) {
            giveItem(player, change.oldState().getBlock().asItem());
        } else if (change.type() == BlockChange.ChangeType.PLACE) {
            removeOneMatchingItem(player, change.newState().getBlock().asItem());
        }
    }

    private static void giveItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (item == net.minecraft.item.Items.AIR) return;
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
    }

    private static void removeOneMatchingItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (item == net.minecraft.item.Items.AIR) return;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.getItem() == item && !stack.isEmpty()) {
                stack.decrement(1);
                return;
            }
        }
    }

    private static void reviveEntity(ServerPlayerEntity player, DeathRecord death) {
        Optional<EntityType<?>> type = EntityType.get(death.entityTypeId());
        if (type.isEmpty()) return;

        Entity entity = type.get().create(player.getServerWorld());
        if (entity == null) return;

        NbtCompound nbt = death.nbt().copy();
        nbt.remove("UUID"); // évite un conflit si un fantôme de l'ancienne entité traîne encore
        entity.readNbt(nbt);
        entity.refreshPositionAndAngles(death.x(), death.y(), death.z(), entity.getYaw(), entity.getPitch());
        player.getServerWorld().spawnEntity(entity);
    }

    private static void playFeedback(ServerPlayerEntity player, boolean backward) {
        if (player.age % 4 != 0) return;
        var world = player.getServerWorld();
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.4f, backward ? 1.6f : 0.9f);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL,
                player.getX(), player.getY() + 1.0, player.getZ(), 3, 0.3, 0.5, 0.3, 0.02);
    }

    public static boolean isRewinding(UUID playerUuid) {
        Session s = SESSIONS.get(playerUuid);
        return s != null;
    }

    /**
     * Revert instantané (pas en timelapse) utilisé par /chronos back : annule
     * tous les changements de blocs de ce joueur et ressuscite les entités
     * mortes entre targetTick et maintenant. Renvoie le nombre de blocs restaurés.
     */
    public static int revertInstantTo(ServerPlayerEntity player, long targetTick) {
        int count = 0;
        BlockChange change;
        while ((change = HistoryManager.popMatchingBlockChange(player.getUuid(), targetTick + 1)) != null) {
            player.getServerWorld().setBlockState(change.pos(), change.oldState());
            giveOrTakeItemForRevert(player, change);
            count++;
        }
        for (DeathRecord death : HistoryManager.popDeathsBetween(targetTick + 1, HistoryManager.getCurrentTick())) {
            reviveEntity(player, death);
        }
        return count;
    }

    public static void clear(UUID playerUuid) {
        SESSIONS.remove(playerUuid);
        SAVEPOINTS.remove(playerUuid);
        HistoryManager.setPaused(playerUuid, false);
    }
}
