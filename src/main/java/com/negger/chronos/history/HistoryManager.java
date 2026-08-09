package com.negger.chronos.history;

import com.negger.chronos.ChronosConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enregistre en continu :
 *  - un buffer de positions/vie/faim par joueur (un ArrayDeque par joueur)
 *  - un buffer global des blocs cassés/posés par des joueurs
 *
 * Les deux buffers sont bornés par ChronosConfig.getBufferTicks() pour éviter
 * une fuite mémoire si personne n'utilise jamais l'item de rewind.
 */
public class HistoryManager {

    private static final Map<UUID, Deque<TimeSnapshot>> PLAYER_HISTORY = new ConcurrentHashMap<>();
    private static final Deque<BlockChange> BLOCK_HISTORY = new ArrayDeque<>();

    private static long currentTick = 0;

    public static void tick() {
        currentTick++;
    }

    public static long getCurrentTick() {
        return currentTick;
    }

    // ----- Historique joueur -----

    public static void recordSnapshot(ServerPlayerEntity player) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());

        history.addLast(new TimeSnapshot(
                currentTick,
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel()
        ));

        int maxSize = ChronosConfig.getBufferTicks();
        while (history.size() > maxSize) {
            history.pollFirst();
        }
    }

    /** Retire et renvoie le snapshot le plus récent d'un joueur, ou null si l'historique est vide. */
    public static TimeSnapshot popLatestSnapshot(UUID playerUuid) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid);
        if (history == null || history.isEmpty()) return null;
        return history.pollLast();
    }

    /**
     * Saut direct : dépile jusqu'à "ticksBack" snapshots d'un coup et renvoie
     * uniquement le dernier (le point d'arrivée), sans téléporter à chaque étape.
     * Utilisé par la commande /chronos back pour un saut instantané précis.
     * Renvoie null si l'historique est vide dès le départ.
     */
    public static TimeSnapshot jumpBack(UUID playerUuid, int ticksBack) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid);
        if (history == null || history.isEmpty()) return null;

        TimeSnapshot last = null;
        for (int i = 0; i < ticksBack && !history.isEmpty(); i++) {
            last = history.pollLast();
        }
        return last;
    }

    public static int getPlayerHistorySize(UUID playerUuid) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid);
        return history == null ? 0 : history.size();
    }

    public static void clearPlayerHistory(UUID playerUuid) {
        PLAYER_HISTORY.remove(playerUuid);
    }

    // ----- Historique des blocs -----

    public static void recordBlockChange(BlockChange change) {
        synchronized (BLOCK_HISTORY) {
            BLOCK_HISTORY.addLast(change);
            int maxSize = ChronosConfig.getBufferTicks();
            while (BLOCK_HISTORY.size() > maxSize) {
                BLOCK_HISTORY.pollFirst();
            }
        }
    }

    /**
     * Retire et renvoie le dernier changement de bloc fait par ce joueur
     * dont le tick est >= minTick, ou null s'il n'y en a pas.
     * Utilisé pour restaurer les blocs pendant le rewind, en synchro avec
     * la position du joueur qu'on est en train de rembobiner.
     */
    public static BlockChange popMatchingBlockChange(UUID playerUuid, long minTick, boolean restrictToPlayer) {
        synchronized (BLOCK_HISTORY) {
            BlockChange last = BLOCK_HISTORY.peekLast();
            if (last == null || last.tick() < minTick) return null;
            if (restrictToPlayer && !last.playerUuid().equals(playerUuid)) return null;
            return BLOCK_HISTORY.pollLast();
        }
    }

    /**
     * Dépile et renvoie TOUS les changements de blocs dont le tick >= minTick,
     * dans l'ordre du plus récent au plus ancien (donc prêt à être réappliqué
     * tel quel pour un revert en masse). Utilisé par /chronos back pour un
     * saut instantané qui remet tous les blocs cassés/posés dans la fenêtre.
     */
    public static java.util.List<BlockChange> popAllBlockChangesSince(UUID playerUuid, long minTick, boolean restrictToPlayer) {
        java.util.List<BlockChange> result = new java.util.ArrayList<>();
        synchronized (BLOCK_HISTORY) {
            while (true) {
                BlockChange last = BLOCK_HISTORY.peekLast();
                if (last == null || last.tick() < minTick) break;
                if (restrictToPlayer && !last.playerUuid().equals(playerUuid)) {
                    // Un changement d'un autre joueur bloque la pile : on l'ignore mais
                    // on ne peut pas "sauter" au-dessus proprement avec un Deque simple,
                    // donc on le laisse en place et on s'arrête pour cette itération.
                    break;
                }
                result.add(BLOCK_HISTORY.pollLast());
            }
        }
        return result;
    }
}
