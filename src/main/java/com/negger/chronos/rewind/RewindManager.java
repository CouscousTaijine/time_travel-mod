package com.negger.chronos.rewind;

import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.DeathRecord;
import com.negger.chronos.history.EntitySnapshot;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewind based on an ordered event history.  World mutations are recorded by
 * HistoryManager, including mutations not caused directly by a player.
 */
public final class RewindManager {
    private static final int TAP_THRESHOLD_TICKS = 6;
    private static final int ENTITY_MATCH_WINDOW = 6;

    private enum Mode { HELD_BACKWARD, AUTO_FORWARD, AUTO_TO_TARGET }
    private static final class Session {
        List<TimeSnapshot> buffer;
        int cursor;
        Mode mode;
        Integer targetCursor;
    }
    private static final class PendingPress {
        long startTick;
        boolean sneaking;
        boolean promoted;
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, TimeSnapshot> SAVEPOINTS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingPress> PENDING = new ConcurrentHashMap<>();
    private static net.minecraft.server.MinecraftServer CURRENT_SERVER;

    private RewindManager() {}

    public static void setServer(net.minecraft.server.MinecraftServer server) { CURRENT_SERVER = server; }

    public static boolean onRightClickPress(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        boolean sneaking = player.isSneaking();
        if (!sneaking && HistoryManager.getPlayerHistorySize(id) == 0 && !isRewinding(id)) return false;
        PendingPress press = new PendingPress();
        press.startTick = HistoryManager.getCurrentTick();
        press.sneaking = sneaking;
        PENDING.put(id, press);
        return true;
    }

    public static void onRightClickRelease(ServerPlayerEntity player) {
        PendingPress press = PENDING.remove(player.getUuid());
        if (press == null) return;
        if (!press.promoted) {
            if (press.sneaking) setSavepoint(player);
            else onReturnToPresent(player);
        } else if (!press.sneaking) {
            Session session = SESSIONS.get(player.getUuid());
            if (session != null) session.mode = null;
        }
    }

    public static void tickAll() {
        for (Map.Entry<UUID, PendingPress> entry : PENDING.entrySet()) {
            PendingPress press = entry.getValue();
            if (press.promoted || HistoryManager.getCurrentTick() - press.startTick < TAP_THRESHOLD_TICKS) continue;
            ServerPlayerEntity player = findPlayer(entry.getKey());
            if (player == null) continue;
            press.promoted = true;
            if (press.sneaking) beginScrubToSavepoint(player); else beginHeldBackward(player);
        }

        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.mode == null) continue;
            ServerPlayerEntity player = findPlayer(entry.getKey());
            if (player == null) continue;
            int steps = Math.max(1, (int) Math.round(ChronosConfig.rewindSpeed));
            for (int i = 0; i < steps; i++) if (!advanceOneStep(player, session, false)) break;
        }
    }

    private static ServerPlayerEntity findPlayer(UUID id) {
        return CURRENT_SERVER == null ? null : CURRENT_SERVER.getPlayerManager().getPlayer(id);
    }

    private static Session getOrCreateSession(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Session session = SESSIONS.get(id);
        if (session != null) return session;
        List<TimeSnapshot> buffer = HistoryManager.combinedHistory(id);
        if (buffer.isEmpty()) return null;
        session = new Session();
        session.buffer = buffer;
        session.cursor = buffer.size() - 1;
        SESSIONS.put(id, session);
        HistoryManager.setPaused(id, true);
        return session;
    }

    private static void beginHeldBackward(ServerPlayerEntity player) {
        Session session = getOrCreateSession(player);
        if (session == null) return;
        if (session.cursor <= 0) {
            player.sendMessage(Text.literal("§6Tu as atteint la limite de ton historique."), true);
            return;
        }
        session.mode = Mode.HELD_BACKWARD;
        session.targetCursor = null;
    }

    private static void beginScrubToSavepoint(ServerPlayerEntity player) {
        TimeSnapshot savepoint = SAVEPOINTS.get(player.getUuid());
        if (savepoint == null) {
            player.sendMessage(Text.literal("§cAucun point de sauvegarde posé."), true);
            return;
        }
        Session session = getOrCreateSession(player);
        if (session == null) return;
        session.mode = Mode.AUTO_TO_TARGET;
        session.targetCursor = closestIndexForTick(session.buffer, savepoint.tick());
    }

    /** Un tap sans accroupissement restaure le présent immédiatement. */
    public static void onReturnToPresent(ServerPlayerEntity player) {
        Session session = SESSIONS.get(player.getUuid());
        if (session == null) return;
        session.mode = Mode.AUTO_FORWARD;
        session.targetCursor = session.buffer.size() - 1;
        while (SESSIONS.containsKey(player.getUuid()) && session.mode == Mode.AUTO_FORWARD) {
            if (!advanceOneStep(player, session, true)) break;
        }
    }

    public static void setSavepoint(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Session session = SESSIONS.get(id);
        TimeSnapshot current;
        if (session != null) current = session.buffer.get(session.cursor);
        else current = latestSnapshot(player);
        SAVEPOINTS.put(id, current);
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.5f, 1.8f);
        player.sendMessage(Text.literal("§dPoint de sauvegarde posé."), true);
    }

    private static TimeSnapshot latestSnapshot(ServerPlayerEntity player) {
        List<TimeSnapshot> history = HistoryManager.snapshotHistory(player.getUuid());
        if (!history.isEmpty()) return history.get(history.size() - 1);
        return new TimeSnapshot(HistoryManager.getCurrentTick(), player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), player.getHealth(), player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel(), player.getServerWorld().getTimeOfDay());
    }

    private static int closestIndexForTick(List<TimeSnapshot> buffer, long tick) {
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < buffer.size(); i++) {
            long diff = Math.abs(buffer.get(i).tick() - tick);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }

    private static boolean advanceOneStep(ServerPlayerEntity player, Session session, boolean instant) {
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

        HistoryManager.setRestoring(true);
        try {
            if (backward) revertWorld(player, newTick, oldTick);
            else replayWorld(player, newTick);
            restoreEntityPositions(newTick);
            session.cursor = newCursor;
            applySnapshot(player, session.buffer.get(newCursor));
        } finally {
            HistoryManager.setRestoring(false);
        }

        if (!instant) playFeedback(player, backward);

        boolean target = session.mode == Mode.AUTO_TO_TARGET && session.cursor == session.targetCursor;
        boolean present = session.mode == Mode.AUTO_FORWARD && session.cursor == session.buffer.size() - 1;
        boolean start = session.mode == Mode.HELD_BACKWARD && session.cursor == 0;
        if (target || present) { finishSession(player, session); return false; }
        if (start) {
            session.mode = null;
            player.sendMessage(Text.literal("§6Tu as atteint la limite de ton historique."), true);
            return false;
        }
        return true;
    }

    private static void finishSession(ServerPlayerEntity player, Session session) {
        boolean present = session.mode == Mode.AUTO_FORWARD || (session.mode == Mode.AUTO_TO_TARGET && session.cursor >= session.buffer.size() - 1);
        if (present) {
            SESSIONS.remove(player.getUuid());
            HistoryManager.setPaused(player.getUuid(), false);
            player.sendMessage(Text.literal("§bRetour au présent."), true);
        } else {
            session.mode = null;
            player.sendMessage(Text.literal("§dRetour temporel arrêté."), true);
        }
    }

    private static void applySnapshot(ServerPlayerEntity player, TimeSnapshot snapshot) {
        player.teleport(player.getServerWorld(), snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        var world = player.getServerWorld();
        world.setTimeOfDay(snapshot.worldTime());
        world.setWeather(snapshot.clearWeatherTime(), snapshot.rainTime(), snapshot.raining(), snapshot.thundering());

        if (snapshot.inventoryNbt() != null) {
            player.getInventory().readNbt(snapshot.inventoryNbt().copy());
            player.getInventory().markDirty();
        }
        if (ChronosConfig.restoreHealthAndHunger) {
            player.setHealth(Math.max(0.1f, snapshot.health()));
            player.getHungerManager().setFoodLevel(snapshot.foodLevel());
            player.getHungerManager().setSaturationLevel(snapshot.saturation());
        }
    }

    private static void restoreEntityPositions(long targetTick) {
        if (CURRENT_SERVER == null) return;
        List<EntitySnapshot> snaps = HistoryManager.getEntitySnapshotsNear(targetTick, targetTick - ENTITY_MATCH_WINDOW, targetTick + ENTITY_MATCH_WINDOW);
        for (EntitySnapshot snap : snaps) {
            for (var world : CURRENT_SERVER.getWorlds()) {
                Entity entity = world.getEntity(snap.entityUuid());
                if (entity instanceof LivingEntity living) {
                    living.refreshPositionAndAngles(snap.x(), snap.y(), snap.z(), snap.yaw(), snap.pitch());
                    living.setHealth(Math.max(0.1f, Math.min(living.getMaxHealth(), snap.health())));
                    break;
                }
            }
        }
    }

    private static void reviveEntity(ServerPlayerEntity player, DeathRecord death) {
        Optional<EntityType<?>> type = EntityType.get(death.entityTypeId());
        if (type.isEmpty()) return;
        Entity entity = type.get().create(player.getServerWorld());
        if (entity == null) return;
        NbtCompound nbt = death.nbt().copy();
        nbt.remove("UUID");
        nbt.remove("Health");
        nbt.remove("DeathTime");
        nbt.remove("HurtTime");
        nbt.remove("HurtByTimestamp");
        entity.readNbt(nbt);
        entity.refreshPositionAndAngles(death.x(), death.y(), death.z(), entity.getYaw(), entity.getPitch());
        if (entity instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
        player.getServerWorld().spawnEntity(entity);
    }

    private static void revertWorld(ServerPlayerEntity player, long newTick, long oldTick) {
        BlockChange change;
        while ((change = HistoryManager.popMatchingBlockChange(player.getUuid(), newTick + 1)) != null) {
            player.getServerWorld().setBlockState(change.pos(), change.oldState());
        }
        for (DeathRecord death : HistoryManager.popDeathsBetween(newTick + 1, oldTick)) reviveEntity(player, death);
    }

    private static void replayWorld(ServerPlayerEntity player, long newTick) {
        BlockChange change;
        while ((change = HistoryManager.redoMatchingBlockChange(player.getUuid(), newTick)) != null) {
            player.getServerWorld().setBlockState(change.pos(), change.newState());
        }
    }

    private static void playFeedback(ServerPlayerEntity player, boolean backward) {
        if (player.age % 4 != 0) return;
        var world = player.getServerWorld();
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.4f, backward ? 1.6f : 0.9f);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 3, 0.3, 0.5, 0.3, 0.02);
    }

    public static boolean isRewinding(UUID playerUuid) { return SESSIONS.containsKey(playerUuid); }

    public static int revertInstantTo(ServerPlayerEntity player, long targetTick) {
        int count = 0;
        HistoryManager.setRestoring(true);
        try {
            BlockChange change;
            while ((change = HistoryManager.popMatchingBlockChange(player.getUuid(), targetTick + 1)) != null) {
                player.getServerWorld().setBlockState(change.pos(), change.oldState());
                count++;
            }
            for (DeathRecord death : HistoryManager.popDeathsBetween(targetTick + 1, HistoryManager.getCurrentTick())) reviveEntity(player, death);
        } finally {
            HistoryManager.setRestoring(false);
        }
        return count;
    }

    public static void clear(UUID playerUuid) {
        SESSIONS.remove(playerUuid);
        SAVEPOINTS.remove(playerUuid);
        PENDING.remove(playerUuid);
        HistoryManager.setPaused(playerUuid, false);
    }
}
