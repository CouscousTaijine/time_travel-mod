package com.negger.chronos.history;

import com.negger.chronos.ChronosConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.level.ServerWorldProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class HistoryManager {
    private static final Map<UUID, Deque<TimeSnapshot>> PLAYER_HISTORY = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<TimeSnapshot>> LONG_TERM_HISTORY = new ConcurrentHashMap<>();
    private static final Set<UUID> PAUSED_PLAYERS = new CopyOnWriteArraySet<>();
    private static final Map<UUID, Deque<BlockChange>> BLOCK_HISTORY = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<BlockChange>> BLOCK_UNDO_STACK = new ConcurrentHashMap<>();
    private static final Deque<EntitySnapshot> ENTITY_HISTORY = new ArrayDeque<>();
    private static final Deque<DeathRecord> DEATH_RECORDS = new ArrayDeque<>();
    private static long currentTick = 0;

    public static void tick() { currentTick++; }
    public static long getCurrentTick() { return currentTick; }
    public static void initializeClockFromAnchor(long anchorTick, long anchorEpochMillis) {
        long elapsedMillis = System.currentTimeMillis() - anchorEpochMillis;
        long elapsedTicks = Math.max(0, Math.round(elapsedMillis / 50.0));
        currentTick = anchorTick + elapsedTicks;
    }
    public static void setPaused(UUID playerUuid, boolean paused) { if (paused) PAUSED_PLAYERS.add(playerUuid); else PAUSED_PLAYERS.remove(playerUuid); }
    public static boolean isPaused(UUID playerUuid) { return PAUSED_PLAYERS.contains(playerUuid); }

    private static TimeSnapshot snapshot(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        ServerWorldProperties properties = (ServerWorldProperties) world.getLevelProperties();
        NbtList items = new NbtList();
        player.getInventory().writeNbt(items);
        NbtCompound inventory = new NbtCompound();
        inventory.put("Items", items);
        return new TimeSnapshot(currentTick, player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), player.getHealth(), player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel(), world.getTime(), world.isRaining(), world.isThundering(), properties.getClearWeatherTime(), properties.getRainTime(), properties.getThunderTime(), inventory);
    }

    public static void recordSnapshot(ServerPlayerEntity player) {
        if (isPaused(player.getUuid())) return;
        Deque<TimeSnapshot> history = PLAYER_HISTORY.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        history.addLast(snapshot(player));
        int maxSize = ChronosConfig.getBufferTicks();
        while (history.size() > maxSize) history.pollFirst();
    }
    public static List<TimeSnapshot> snapshotHistory(UUID playerUuid) { Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid); return history == null ? new ArrayList<>() : new ArrayList<>(history); }
    public static void recordLongTermIfDue(ServerPlayerEntity player) { if (!ChronosConfig.persistenceEnabled || isPaused(player.getUuid()) || currentTick % 20 != 0) return; Deque<TimeSnapshot> history = LONG_TERM_HISTORY.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>()); history.addLast(snapshot(player)); long maxSize = ChronosConfig.getPersistCapacity(); while (history.size() > maxSize) history.pollFirst(); }
    public static List<TimeSnapshot> getLongTermHistory(UUID playerUuid) { Deque<TimeSnapshot> history = LONG_TERM_HISTORY.get(playerUuid); return history == null ? new ArrayList<>() : new ArrayList<>(history); }
    public static void setLongTermHistory(UUID playerUuid, List<TimeSnapshot> loaded) { LONG_TERM_HISTORY.put(playerUuid, new ArrayDeque<>(loaded)); }
    public static List<TimeSnapshot> combinedHistory(UUID playerUuid) { List<TimeSnapshot> shortTerm = snapshotHistory(playerUuid); List<TimeSnapshot> longTerm = getLongTermHistory(playerUuid); if (shortTerm.isEmpty()) return longTerm; long cutoff = shortTerm.get(0).tick(); List<TimeSnapshot> combined = new ArrayList<>(); for (TimeSnapshot s : longTerm) if (s.tick() < cutoff) combined.add(s); combined.addAll(shortTerm); return combined; }
    public static int getPlayerHistorySize(UUID playerUuid) { Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid); return history == null ? 0 : history.size(); }
    public static void truncateHistoryTo(UUID playerUuid, int keepFromStart) { Deque<TimeSnapshot> history = PLAYER_HISTORY.get(playerUuid); if (history == null) return; List<TimeSnapshot> asList = new ArrayList<>(history); history.clear(); for (int i = 0; i < Math.min(keepFromStart, asList.size()); i++) history.addLast(asList.get(i)); }
    public static void clearPlayerHistory(UUID playerUuid) { PLAYER_HISTORY.remove(playerUuid); BLOCK_HISTORY.remove(playerUuid); BLOCK_UNDO_STACK.remove(playerUuid); PAUSED_PLAYERS.remove(playerUuid); }
    public static void unloadLongTermFromMemory(UUID playerUuid) { LONG_TERM_HISTORY.remove(playerUuid); }

    public static void recordBlockChange(BlockChange change) { Deque<BlockChange> history = BLOCK_HISTORY.computeIfAbsent(change.playerUuid(), id -> new ArrayDeque<>()); synchronized (history) { history.addLast(change); int maxSize = ChronosConfig.getBufferTicks(); while (history.size() > maxSize) history.pollFirst(); } Deque<BlockChange> undo = BLOCK_UNDO_STACK.get(change.playerUuid()); if (undo != null) undo.clear(); }
    public static BlockChange popMatchingBlockChange(UUID playerUuid, long minTick) { Deque<BlockChange> history = BLOCK_HISTORY.get(playerUuid); if (history == null) return null; synchronized (history) { BlockChange last = history.peekLast(); if (last == null || last.tick() < minTick) return null; history.pollLast(); BLOCK_UNDO_STACK.computeIfAbsent(playerUuid, id -> new ArrayDeque<>()).addLast(last); return last; } }
    public static BlockChange redoMatchingBlockChange(UUID playerUuid, long maxTick) { Deque<BlockChange> undo = BLOCK_UNDO_STACK.get(playerUuid); if (undo == null) return null; synchronized (undo) { BlockChange last = undo.peekLast(); if (last == null || last.tick() > maxTick) return null; undo.pollLast(); BLOCK_HISTORY.computeIfAbsent(playerUuid, id -> new ArrayDeque<>()).addLast(last); return last; } }
    public static void recordEntitySnapshot(LivingEntity entity) { synchronized (ENTITY_HISTORY) { ENTITY_HISTORY.addLast(new EntitySnapshot(currentTick, entity.getUuid(), entity.getX(), entity.getY(), entity.getZ(), entity.getYaw(), entity.getPitch(), entity.getHealth())); int maxSize = ChronosConfig.getBufferTicks() * 8; while (ENTITY_HISTORY.size() > maxSize) ENTITY_HISTORY.pollFirst(); } }
    public static List<EntitySnapshot> getEntitySnapshotsSince(long minTick) { synchronized (ENTITY_HISTORY) { List<EntitySnapshot> result = new ArrayList<>(); for (EntitySnapshot s : ENTITY_HISTORY) if (s.tick() >= minTick) result.add(s); return result; } }
    public static List<EntitySnapshot> getEntitySnapshotsNear(long targetTick, long minTick, long maxTick) { synchronized (ENTITY_HISTORY) { Map<UUID, EntitySnapshot> best = new java.util.HashMap<>(); Map<UUID, Long> bestDiff = new java.util.HashMap<>(); for (EntitySnapshot s : ENTITY_HISTORY) { if (s.tick() < minTick || s.tick() > maxTick) continue; long diff = Math.abs(s.tick() - targetTick); Long current = bestDiff.get(s.entityUuid()); if (current == null || diff < current) { bestDiff.put(s.entityUuid(), diff); best.put(s.entityUuid(), s); } } return new ArrayList<>(best.values()); } }
    public static void recordDeath(DeathRecord record) { synchronized (DEATH_RECORDS) { DEATH_RECORDS.addLast(record); while (DEATH_RECORDS.size() > 2000) DEATH_RECORDS.pollFirst(); } }
    public static List<DeathRecord> popDeathsBetween(long minTick, long maxTick) { synchronized (DEATH_RECORDS) { List<DeathRecord> result = new ArrayList<>(); DEATH_RECORDS.removeIf(d -> { if (d.tick() >= minTick && d.tick() <= maxTick) { result.add(d); return true; } return false; }); return result; } }
}
