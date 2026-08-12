package com.negger.chronos.rewind;

import com.negger.chronos.ChronosConfig;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.EntitySnapshot;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.TimeSnapshot;
import com.negger.chronos.network.ChronosNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Hand;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RewindManager {
    private static final int TAP_THRESHOLD_TICKS = 6;
    private static final int ENTITY_MATCH_WINDOW = 1;

    private enum Mode { HELD_BACKWARD, AUTO_TO_TARGET }
    private static class Session {
        List<TimeSnapshot> buffer;
        int cursor;
        Mode mode;
        Integer targetCursor;
    }
    private static class PendingPress {
        long startTick;
        boolean sneaking;
        boolean promoted;
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, TimeSnapshot> SAVEPOINTS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingPress> PENDING = new ConcurrentHashMap<>();
    private static net.minecraft.server.MinecraftServer CURRENT_SERVER;

    public static void setServer(net.minecraft.server.MinecraftServer server) { CURRENT_SERVER = server; }

    public static boolean onRightClickPress(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        boolean sneaking = player.isSneaking();
        if (!sneaking && HistoryManager.getPlayerHistorySize(id) == 0 && !isRewinding(id)) return false;
        PendingPress p = new PendingPress();
        p.startTick = HistoryManager.getCurrentTick();
        p.sneaking = sneaking;
        PENDING.put(id, p);
        return true;
    }

    public static void onRightClickRelease(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        PendingPress p = PENDING.remove(id);
        if (p == null) return;
        if (!p.promoted) {
            if (p.sneaking) setSavepoint(player);
            else onReturnToPresent(player);
            return;
        }
        Session session = SESSIONS.get(id);
        if (session != null) finishAtPast(player, session);
        else {
            HistoryManager.setPaused(id, false);
            sendSmoothPacket(player, false);
        }
    }

    public static void tickAll() {
        for (Map.Entry<UUID, PendingPress> e : PENDING.entrySet()) {
            PendingPress p = e.getValue();
            if (p.promoted || HistoryManager.getCurrentTick() - p.startTick < TAP_THRESHOLD_TICKS) continue;
            ServerPlayerEntity player = findPlayer(e.getKey());
            if (player == null) continue;
            p.promoted = true;
            if (p.sneaking) beginScrubToSavepoint(player); else beginHeldBackward(player);
        }

        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            Session s = e.getValue();
            if (s.mode == null) continue;
            ServerPlayerEntity player = findPlayer(e.getKey());
            if (player == null) continue;
            for (int i = 0; i < Math.min(2, Math.max(1, (int) Math.round(ChronosConfig.rewindSpeed))); i++) {
                if (!advanceOneStep(player, s)) break;
            }
        }
    }

    private static ServerPlayerEntity findPlayer(UUID id) {
        return CURRENT_SERVER == null ? null : CURRENT_SERVER.getPlayerManager().getPlayer(id);
    }

    private static Session getOrCreateSession(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Session s = SESSIONS.get(id);
        if (s != null) return s;
        List<TimeSnapshot> buffer = HistoryManager.combinedHistory(id);
        if (buffer.isEmpty()) return null;
        s = new Session();
        s.buffer = buffer;
        s.cursor = buffer.size() - 1;
        SESSIONS.put(id, s);
        HistoryManager.setPaused(id, true);
        return s;
    }

    private static void beginHeldBackward(ServerPlayerEntity player) {
        Session s = getOrCreateSession(player);
        if (s != null) s.mode = Mode.HELD_BACKWARD;
        HistoryManager.setPaused(player.getUuid(), true);
    }

    private static void beginScrubToSavepoint(ServerPlayerEntity player) {
        TimeSnapshot savepoint = SAVEPOINTS.get(player.getUuid());
        if (savepoint == null) {
            player.sendMessage(Text.literal("§cAucun point de sauvegarde posé."), true);
            return;
        }
        Session s = getOrCreateSession(player);
        if (s == null) return;
        s.mode = Mode.AUTO_TO_TARGET;
        s.targetCursor = closestIndexForTick(s.buffer, savepoint.tick());
    }

    public static void onReturnToPresent(ServerPlayerEntity player) {
        Session s = SESSIONS.get(player.getUuid());
        if (s == null) return;

        BlockChange change;
        while ((change = HistoryManager.redoMatchingBlockChange(player.getUuid(), Long.MAX_VALUE)) != null) {
            applyBlockChange(player, change, false, false);
        }

        TimeSnapshot present = s.buffer.get(s.buffer.size() - 1);
        restoreEntities(player, present.tick());
        applySnapshot(player, present, false);
        SESSIONS.remove(player.getUuid());
        HistoryManager.setPaused(player.getUuid(), false);
        sendSmoothPacket(player, false);
        player.networkHandler.syncWithPlayerPosition();
    }

    private static void finishAtPast(ServerPlayerEntity player, Session session) {
        // Le snapshot "present" reste dans la session afin qu'un clic droit court
        // puisse ramener instantanément le monde à l'état d'origine.
        HistoryManager.truncateHistoryTo(player.getUuid(), session.cursor + 1);
        session.mode = null;
        HistoryManager.setPaused(player.getUuid(), false);
        sendSmoothPacket(player, false);
    }

    public static void setSavepoint(ServerPlayerEntity player) {
        List<TimeSnapshot> h = HistoryManager.snapshotHistory(player.getUuid());
        if (h.isEmpty()) return;
        SAVEPOINTS.put(player.getUuid(), h.get(h.size() - 1));
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.5f, 1.8f);
        player.sendMessage(Text.literal("§dPoint de sauvegarde posé."), true);
    }

    private static int closestIndexForTick(List<TimeSnapshot> buffer, long tick) {
        int best = 0; long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < buffer.size(); i++) {
            long diff = Math.abs(buffer.get(i).tick() - tick);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }

    private static boolean advanceOneStep(ServerPlayerEntity player, Session session) {
        boolean backward = session.mode == Mode.HELD_BACKWARD ||
                (session.mode == Mode.AUTO_TO_TARGET && session.targetCursor < session.cursor);
        int next = backward ? session.cursor - 1 : session.cursor + 1;
        if (next < 0 || next >= session.buffer.size()) {
            finishAtPast(player, session);
            return false;
        }

        long newTick = session.buffer.get(next).tick();
        if (backward) {
            BlockChange c;
            while ((c = HistoryManager.popMatchingBlockChange(player.getUuid(), newTick + 1)) != null) {
                applyBlockChange(player, c, true, true);
            }
        } else {
            BlockChange c;
            while ((c = HistoryManager.redoMatchingBlockChange(player.getUuid(), newTick)) != null) {
                applyBlockChange(player, c, false, true);
            }
        }

        session.cursor = next;
        TimeSnapshot snapshot = session.buffer.get(next);
        restoreEntities(player, snapshot.tick());
        applySnapshot(player, snapshot, true);

        if (session.mode == Mode.AUTO_TO_TARGET && session.cursor == session.targetCursor) {
            finishAtPast(player, session);
            return false;
        }
        if (session.mode == Mode.HELD_BACKWARD && session.cursor == 0) {
            finishAtPast(player, session);
            player.sendMessage(Text.literal("§6Limite de l'historique atteinte."), true);
            return false;
        }
        return true;
    }

    private static void applySnapshot(ServerPlayerEntity player, TimeSnapshot s, boolean smooth) {
        player.requestTeleport(s.x(), s.y(), s.z());
        player.setYaw(s.yaw());
        player.setPitch(s.pitch());
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0;

        var props = player.getServerWorld().getLevelProperties();
        props.setTimeOfDay(s.worldTime());
        props.setRaining(s.raining());
        props.setThundering(s.thundering());
        props.setClearWeatherTime(s.clearWeatherTime());
        props.setRainTime(s.rainTime());
        props.setThunderTime(s.thunderTime());

        if (ChronosConfig.restoreHealthAndHunger) {
            player.setHealth(Math.max(0.1f, Math.min(player.getMaxHealth(), s.health())));
            player.getHungerManager().setFoodLevel(s.foodLevel());
            player.getHungerManager().setSaturationLevel(s.saturation());
        }
        player.experienceLevel = s.experienceLevel();
        player.totalExperience = s.totalExperience();
        player.experienceProgress = s.experienceProgress();
        player.getInventory().selectedSlot = Math.max(0, Math.min(8, s.selectedSlot()));

        if (s.inventoryNbt() != null && s.inventoryNbt().contains("Inventory")) {
            player.getInventory().readNbt(s.inventoryNbt().getList("Inventory", NbtElement.COMPOUND_TYPE));
            player.getInventory().selectedSlot = Math.max(0, Math.min(8, s.selectedSlot()));
            player.playerScreenHandler.sendContentUpdates();
        }
        if (smooth) sendSmoothPacket(player, true);
    }

    private static void restoreEntities(ServerPlayerEntity player, long targetTick) {
        ServerWorld world = player.getServerWorld();
        List<EntitySnapshot> raw = HistoryManager.getEntitySnapshotsNear(targetTick, targetTick - ENTITY_MATCH_WINDOW, targetTick + ENTITY_MATCH_WINDOW);
        Map<UUID, EntitySnapshot> snapshots = new HashMap<>();
        String worldKey = world.getRegistryKey().getValue().toString();
        for (EntitySnapshot s : raw) if (worldKey.equals(s.worldKey())) snapshots.put(s.entityUuid(), s);

        Set<UUID> touched = new HashSet<>();
        var nearby = world.getEntitiesByClass(Entity.class, player.getBoundingBox().expand(128), e -> !(e instanceof PlayerEntity));
        for (Entity entity : nearby) {
            EntitySnapshot snapshot = snapshots.get(entity.getUuid());
            if (snapshot == null) {
                entity.discard();
                continue;
            }
            restoreEntity(entity, snapshot);
            touched.add(entity.getUuid());
        }

        for (EntitySnapshot snapshot : snapshots.values()) {
            if (touched.contains(snapshot.entityUuid())) continue;
            EntityType<?> type = Registries.ENTITY_TYPE.get(new Identifier(snapshot.entityTypeId()));
            if (type == null) continue;
            Entity entity = type.create(world);
            if (entity == null) continue;
            try {
                entity.readNbt(snapshot.nbt().copy());
                world.spawnEntity(entity);
            } catch (Exception ignored) {
                entity.discard();
            }
        }
    }

    private static void restoreEntity(Entity entity, EntitySnapshot snapshot) {
        try {
            entity.readNbt(snapshot.nbt().copy());
            entity.setVelocity(0, 0, 0);
        } catch (Exception ignored) {
            entity.refreshPositionAndAngles(snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        }
    }

    private static void applyBlockChange(ServerPlayerEntity player, BlockChange change, boolean reverse, boolean animate) {
        BlockPos pos = change.pos();
        ServerWorld world = player.getServerWorld();
        var state = reverse ? change.oldState() : change.newState();
        world.setBlockState(pos, state, Block.NOTIFY_ALL);
        if (!animate) return;
        player.swingHand(Hand.MAIN_HAND);
        if (reverse) {
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.55f, 1.0f);
        } else {
            world.syncWorldEvent(2001, pos, Block.getRawIdFromState(state));
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.45f, 1.0f);
        }
    }

    private static void sendSmoothPacket(ServerPlayerEntity player, boolean active) {
        var buf = PacketByteBufs.create();
        buf.writeDouble(player.getX()); buf.writeDouble(player.getY()); buf.writeDouble(player.getZ());
        buf.writeFloat(player.getYaw()); buf.writeFloat(player.getPitch()); buf.writeBoolean(active);
        ServerPlayNetworking.send(player, ChronosNetworking.REWIND_MOTION, buf);
    }

    public static boolean isRewinding(UUID id) { return SESSIONS.containsKey(id); }

    public static void clear(UUID id) {
        SESSIONS.remove(id); SAVEPOINTS.remove(id); PENDING.remove(id); HistoryManager.setPaused(id, false);
    }
}
