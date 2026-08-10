package com.negger.chronos.history;

import com.negger.chronos.ChronosConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Enregistre en continu tout ce qui peut être rembobiné :
 *  - la position/vie/faim de chaque joueur (par joueur)
 *  - les blocs cassés/posés par chaque joueur, avec une pile "annulé" séparée
 *    pour pouvoir les REJOUER si le joueur revient vers le présent
 *  - la position/vie de toutes les entités vivantes du monde (animaux, mobs)
 *  - les morts d'entités, pour pouvoir les ressusciter
 *
 * Tout est borné par ChronosConfig.getBufferTicks() pour éviter une fuite
 * mémoire. Pendant qu'un joueur est en train de "voir le passé" (curseur de
 * rewind actif), on NE réenregistre PAS sa position — sinon on écraserait
 * l'historique avec des positions rembobinées, ce qui corrompait tout dans
 * la version précédente du mod.
 */
public class HistoryManager {

    private static final Map<UUID, Deque<TimeSnapshot>> PLAYER_HISTORY = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<TimeSnapshot>> LONG_TERM_HISTORY = new ConcurrentHashMap<>();
    private static final Set<UUID> PAUSED_PLAYERS = new CopyOnWriteArraySet<>();

    // Historique des blocs PAR joueur : plus simple et évite les conflits entre joueurs
    private static final Map<UUID, Deque<BlockChange>> BLOCK_HISTORY = new ConcurrentHashMap<>();
    // Piles des changements déjà annulés par ce joueur, prêtes à être rejouées
    // s'il revient vers le présent (clic gauche)
    private static final Map<UUID, Deque<BlockChange>> BLOCK_UNDO_STACK = new ConcurrentHashMap<>();

    private static final Deque<EntitySnapshot> ENTITY_HISTORY = new ArrayDeque<>();
    private static final Deque<DeathRecord> DEATH_RECORDS = new ArrayDeque<>();

    private static long currentTick = 0;

    public static void tick() {
        currentTick++;
    }

    public static long getCurrentTick() {
        return currentTick;
    }

    /**
     * À appeler UNE FOIS au démarrage du serveur, avant le premier tick, si
     * une ancre d'horloge a été trouvée sur le disque. Fait "rattraper" le
     * compteur de tick pour qu'il reste cohérent avec l'historique sauvegardé,
     * en tenant compte du temps réel écoulé pendant que le serveur était éteint.
     */
    public static void initializeClockFromAnchor(long anchorTick, long anchorEpochMillis) {
        long elapsedMillis = System.currentTimeMillis() - anchorEpochMillis;
        long elapsedTicks = Math.max(0, Math.round(elapsedMillis / 50.0)); // 50ms/tick = 20 ticks/sec
        currentTick = anchorTick + elapsedTicks;
    }

    // ----- Pause de l'enregistrement pendant un rewind actif -----

    public static void setPaused(UUID playerUuid, boolean paused) {
        if (paused) PAUSED_PLAYERS.add(playerUuid);
        else PAUSED_PLAYERS.remove(playerUuid);
    }

    public static boolean isPaused(UUID playerUuid) {
        return PAUSED_PLAYERS.contains(playerUuid);
    }

    // ----- Historique joueur -----

    public static void recordSnapshot(ServerPlayerEntity player) {
        if (isPaused(player.getUuid())) return;

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

    /** Copie l'historique complet d'un joueur (du plus vieux au plus récent), sans le vider. */
    public static List<TimeSnapshot> snapshotHistory(UUID playerUuid) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid);
        if (history == null) return new ArrayList<>();
        return new ArrayList<>(history);
    }

    // ----- Historique longue durée (1 point/seconde, persiste sur le disque) -----

    public static void recordLongTermIfDue(ServerPlayerEntity player) {
        if (!ChronosConfig.persistenceEnabled) return;
        if (isPaused(player.getUuid())) return;
        if (currentTick % 20 != 0) return; // une fois par seconde

        Deque<TimeSnapshot> history = LONG_TERM_HISTORY.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        history.addLast(new TimeSnapshot(
                currentTick,
                player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(),
                player.getHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getHungerManager().getSaturationLevel()
        ));

        long maxSize = ChronosConfig.getPersistCapacity();
        while (history.size() > maxSize) {
            history.pollFirst();
        }
    }

    public static List<TimeSnapshot> getLongTermHistory(UUID playerUuid) {
        Deque<TimeSnapshot> history = LONG_TERM_HISTORY.get(playerUuid);
        return history == null ? new ArrayList<>() : new ArrayList<>(history);
    }

    public static void setLongTermHistory(UUID playerUuid, List<TimeSnapshot> loaded) {
        LONG_TERM_HISTORY.put(playerUuid, new ArrayDeque<>(loaded));
    }

    /**
     * Historique combiné utilisé pour le rewind : les dernières minutes en
     * fluide (20x/sec), tout ce qui est plus vieux en 1x/sec (chargé depuis
     * le disque si le joueur vient de se reconnecter). C'est ce qui permet
     * de remonter des jours en arrière, avec juste un timelapse un peu moins
     * fluide une fois passé la fenêtre récente.
     */
    public static List<TimeSnapshot> combinedHistory(UUID playerUuid) {
        List<TimeSnapshot> shortTerm = snapshotHistory(playerUuid);
        List<TimeSnapshot> longTerm = getLongTermHistory(playerUuid);

        if (shortTerm.isEmpty()) return longTerm;

        long cutoff = shortTerm.get(0).tick();
        List<TimeSnapshot> combined = new ArrayList<>();
        for (TimeSnapshot s : longTerm) {
            if (s.tick() < cutoff) combined.add(s);
        }
        combined.addAll(shortTerm);
        return combined;
    }

    public static int getPlayerHistorySize(UUID playerUuid) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid);
        return history == null ? 0 : history.size();
    }

    /**
     * Remplace la fin de l'historique d'un joueur par le contenu fourni.
     * Utilisé quand une session de rewind se termine : tout ce qui a été
     * "visité" en arrière est retiré du vrai historique (comme une branche
     * temporelle qui disparaît), sauf si le joueur est revenu pile au présent.
     */
    public static void truncateHistoryTo(UUID playerUuid, int keepFromStart) {
        Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid);
        if (history == null) return;
        List<TimeSnapshot> asList = new ArrayList<>(history);
        history.clear();
        for (int i = 0; i < Math.min(keepFromStart, asList.size()); i++) {
            history.addLast(asList.get(i));
        }
    }

    public static void clearPlayerHistory(UUID playerUuid) {
        PLAYER_HISTORY.remove(playerUuid);
        BLOCK_HISTORY.remove(playerUuid);
        BLOCK_UNDO_STACK.remove(playerUuid);
        PAUSED_PLAYERS.remove(playerUuid);
    }

    /** À appeler après avoir sauvegardé l'historique longue durée sur le disque, pour libérer la RAM. */
    public static void unloadLongTermFromMemory(UUID playerUuid) {
        LONG_TERM_HISTORY.remove(playerUuid);
    }

    // ----- Historique des blocs (par joueur) -----

    public static void recordBlockChange(BlockChange change) {
        Deque<BlockChange> history = BLOCK_HISTORY.computeIfAbsent(change.playerUuid(), id -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(change);
            int maxSize = ChronosConfig.getBufferTicks();
            while (history.size() > maxSize) {
                history.pollFirst();
            }
        }
        // Une nouvelle action "efface" ce qui aurait pu être rejoué (comme une vraie branche temporelle)
        Deque<BlockChange> undo = BLOCK_UNDO_STACK.get(change.playerUuid());
        if (undo != null) undo.clear();
    }

    /** Retire et renvoie le dernier changement de CE joueur dont le tick est >= minTick, ou null. */
    public static BlockChange popMatchingBlockChange(UUID playerUuid, long minTick) {
        Deque<BlockChange> history = BLOCK_HISTORY.get(playerUuid);
        if (history == null) return null;
        synchronized (history) {
            BlockChange last = history.peekLast();
            if (last == null || last.tick() < minTick) return null;
            history.pollLast();
            BLOCK_UNDO_STACK.computeIfAbsent(playerUuid, id -> new ArrayDeque<>()).addLast(last);
            return last;
        }
    }

    /** Rejoue (redo) le dernier changement annulé de ce joueur, si son tick est <= maxTick. */
    public static BlockChange redoMatchingBlockChange(UUID playerUuid, long maxTick) {
        Deque<BlockChange> undo = BLOCK_UNDO_STACK.get(playerUuid);
        if (undo == null) return null;
        synchronized (undo) {
            BlockChange last = undo.peekLast();
            if (last == null || last.tick() > maxTick) return null;
            undo.pollLast();
            BLOCK_HISTORY.computeIfAbsent(playerUuid, id -> new ArrayDeque<>()).addLast(last);
            return last;
        }
    }

    // ----- Historique des entités (animaux/mobs, global) -----

    public static void recordEntitySnapshot(LivingEntity entity) {
        synchronized (ENTITY_HISTORY) {
            ENTITY_HISTORY.addLast(new EntitySnapshot(
                    currentTick, entity.getUuid(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYaw(), entity.getPitch(),
                    entity.getHealth()
            ));
            int maxSize = ChronosConfig.getBufferTicks() * 8; // plusieurs entités par tick
            while (ENTITY_HISTORY.size() > maxSize) {
                ENTITY_HISTORY.pollFirst();
            }
        }
    }

    /** Renvoie le dernier snapshot connu de chaque entité dont le tick est >= minTick (pour un pas de rewind). */
    public static List<EntitySnapshot> getEntitySnapshotsSince(long minTick) {
        synchronized (ENTITY_HISTORY) {
            List<EntitySnapshot> result = new ArrayList<>();
            for (EntitySnapshot s : ENTITY_HISTORY) {
                if (s.tick() >= minTick) result.add(s);
            }
            return result;
        }
    }

    /** Renvoie le snapshot le plus proche du tick demandé pour chaque entité (une entrée par UUID). */
    public static List<EntitySnapshot> getEntitySnapshotsNear(long targetTick, long minTick, long maxTick) {
        synchronized (ENTITY_HISTORY) {
            Map<UUID, EntitySnapshot> best = new java.util.HashMap<>();
            Map<UUID, Long> bestDiff = new java.util.HashMap<>();
            for (EntitySnapshot s : ENTITY_HISTORY) {
                if (s.tick() < minTick || s.tick() > maxTick) continue;
                long diff = Math.abs(s.tick() - targetTick);
                Long current = bestDiff.get(s.entityUuid());
                if (current == null || diff < current) {
                    bestDiff.put(s.entityUuid(), diff);
                    best.put(s.entityUuid(), s);
                }
            }
            return new ArrayList<>(best.values());
        }
    }

    public static void recordDeath(DeathRecord record) {
        synchronized (DEATH_RECORDS) {
            DEATH_RECORDS.addLast(record);
            int maxSize = 2000;
            while (DEATH_RECORDS.size() > maxSize) {
                DEATH_RECORDS.pollFirst();
            }
        }
    }

    /** Renvoie et retire les morts survenues entre minTick et maxTick (pour les ressusciter en rembobinant). */
    public static List<DeathRecord> popDeathsBetween(long minTick, long maxTick) {
        synchronized (DEATH_RECORDS) {
            List<DeathRecord> result = new ArrayList<>();
            DEATH_RECORDS.removeIf(d -> {
                if (d.tick() >= minTick && d.tick() <= maxTick) {
                    result.add(d);
                    return true;
                }
                return false;
            });
            return result;
        }
    }
}
