package com.negger.chronos.history;

import com.negger.chronos.ChronosConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
        currentTick = anchorTick + Math.max(0, Math.round(elapsedMillis / 50.0));
    }

    public static void setPaused(UUID id, boolean paused) {
        if (paused) PAUSED_PLAYERS.add(id); else PAUSED_PLAYERS.remove(id);
    }
    public static boolean isPaused(UUID id) { return PAUSED_PLAYERS.contains(id); }

    public static void recordSnapshot(ServerPlayerEntity player) {
        if (isPaused(player.getUuid())) return;
        ServerWorld world = player.getServerWorld();
        NbtCompound full = new NbtCompound();
        player.writeNbt(full);
        NbtCompound inventory = new NbtCompound();
        if (full.contains("Inventory")) inventory.put("Inventory", full.getList("Inventory", 10).copy());

        var props = world.getLevelProperties();
        Deque<TimeSnapshot> history = PLAYER_HISTORY.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        history.addLast(new TimeSnapshot(
                currentTick, player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                player.getHealth(), player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel(),
                player.experienceLevel, player.totalExperience, player.experienceProgress,
                player.getInventory().selectedSlot,
                props.getTimeOfDay(), props.isRaining(), props.isThundering(),
                props.getClearWeatherTime(), props.getRainTime(), props.getThunderTime(), inventory
        ));
        int max = ChronosConfig.getBufferTicks();
        while (history.size() > max) history.pollFirst();
    }

    public static List<TimeSnapshot> snapshotHistory(UUID id) {
        Deque<TimeSnapshot> h = PLAYER_HISTORY.get(id);
        return h == null ? new ArrayList<>() : new ArrayList<>(h);
    }

    public static void recordLongTermIfDue(ServerPlayerEntity player) {
        if (!ChronosConfig.persistenceEnabled || isPaused(player.getUuid()) || currentTick % 20 != 0) return;
        Deque<TimeSnapshot> h = LONG_TERM_HISTORY.computeIfAbsent(player.getUuid(), id -> new ArrayDeque<>());
        List<TimeSnapshot> shortHistory = snapshotHistory(player.getUuid());
        if (shortHistory.isEmpty()) return;
        h.addLast(shortHistory.get(shortHistory.size() - 1));
        while (h.size() > ChronosConfig.getPersistCapacity()) h.pollFirst();
    }

    public static List<TimeSnapshot> getLongTermHistory(UUID id) {
        Deque<TimeSnapshot> h = LONG_TERM_HISTORY.get(id);
        return h == null ? new ArrayList<>() : new ArrayList<>(h);
    }
    public static void setLongTermHistory(UUID id, List<TimeSnapshot> loaded) { LONG_TERM_HISTORY.put(id, new ArrayDeque<>(loaded)); }

    public static List<TimeSnapshot> combinedHistory(UUID id) {
        List<TimeSnapshot> shortTerm = snapshotHistory(id);
        List<TimeSnapshot> longTerm = getLongTermHistory(id);
        if (shortTerm.isEmpty()) return longTerm;
        long cutoff = shortTerm.get(0).tick();
        List<TimeSnapshot> result = new ArrayList<>();
        for (TimeSnapshot s : longTerm) if (s.tick() < cutoff) result.add(s);
        result.addAll(shortTerm);
        return result;
    }

    public static int getPlayerHistorySize(UUID id) {
        Deque<TimeSnapshot> h = PLAYER_HISTORY.get(id);
        return h == null ? 0 : h.size();
    }

    public static void clearPlayerHistory(UUID id) {
        PLAYER_HISTORY.remove(id); BLOCK_HISTORY.remove(id); BLOCK_UNDO_STACK.remove(id); PAUSED_PLAYERS.remove(id);
    }
    public static void unloadLongTermFromMemory(UUID id) { LONG_TERM_HISTORY.remove(id); }

    public static void recordBlockChange(BlockChange change) {
        Deque<BlockChange> h = BLOCK_HISTORY.computeIfAbsent(change.playerUuid(), id -> new ArrayDeque<>());
        synchronized (h) {
            h.addLast(change);
            while (h.size() > ChronosConfig.getBufferTicks()) h.pollFirst();
        }
        Deque<BlockChange> undo = BLOCK_UNDO_STACK.get(change.playerUuid());
        if (undo != null) undo.clear();
    }

    public static BlockChange popMatchingBlockChange(UUID id, long minTick) {
        Deque<BlockChange> h = BLOCK_HISTORY.get(id);
        if (h == null) return null;
        synchronized (h) {
            BlockChange c = h.peekLast();
            if (c == null || c.tick() < minTick) return null;
            h.pollLast();
            BLOCK_UNDO_STACK.computeIfAbsent(id, x -> new ArrayDeque<>()).addLast(c);
            return c;
        }
    }

    public static BlockChange redoMatchingBlockChange(UUID id, long maxTick) {
        Deque<BlockChange> undo = BLOCK_UNDO_STACK.get(id);
        if (undo == null) return null;
        synchronized (undo) {
            BlockChange c = undo.peekLast();
            if (c == null || c.tick() > maxTick) return null;
            undo.pollLast();
            BLOCK_HISTORY.computeIfAbsent(id, x -> new ArrayDeque<>()).addLast(c);
            return c;
        }
    }

    public static void recordEntitySnapshot(Entity entity) {
        if (entity instanceof PlayerEntity || entity.isRemoved() || !PAUSED_PLAYERS.isEmpty()) return;
        NbtCompound nbt = new NbtCompound();
        entity.writeNbt(nbt);
        EntitySnapshot snapshot = new EntitySnapshot(
                currentTick, entity.getUuid(),
                Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                entity.getWorld().getRegistryKey().getValue().toString(), nbt.copy());
        synchronized (ENTITY_HISTORY) {
            ENTITY_HISTORY.addLast(snapshot);
            int max = Math.max(20000, ChronosConfig.getBufferTicks() * 64);
            while (ENTITY_HISTORY.size() > max) ENTITY_HISTORY.pollFirst();
        }
    }

    public static void recordEntitySnapshot(LivingEntity entity) { recordEntitySnapshot((Entity) entity); }

    public static List<EntitySnapshot> getEntitySnapshotsNear(long targetTick, long minTick, long maxTick) {
        synchronized (ENTITY_HISTORY) {
            Map<UUID, EntitySnapshot> best = new HashMap<>();
            Map<UUID, Long> bestDiff = new HashMap<>();
            for (EntitySnapshot s : ENTITY_HISTORY) {
                if (s.tick() < minTick || s.tick() > maxTick) continue;
                long diff = Math.abs(s.tick() - targetTick);
                if (!bestDiff.containsKey(s.entityUuid()) || diff < bestDiff.get(s.entityUuid())) {
                    bestDiff.put(s.entityUuid(), diff); best.put(s.entityUuid(), s);
                }
            }
            return new ArrayList<>(best.values());
        }
    }

    public static void recordDeath(DeathRecord record) {
        synchronized (DEATH_RECORDS) {
            DEATH_RECORDS.addLast(record);
            while (DEATH_RECORDS.size() > 2000) DEATH_RECORDS.pollFirst();
        }
    }

    public static List<DeathRecord> popDeathsBetween(long minTick, long maxTick) {
        synchronized (DEATH_RECORDS) {
            List<DeathRecord> result = new ArrayList<>();
            DEATH_RECORDS.removeIf(d -> {
                if (d.tick() >= minTick && d.tick() <= maxTick) { result.add(d); return true; }
                return false;
            });
            return result;
        }
    }
}
