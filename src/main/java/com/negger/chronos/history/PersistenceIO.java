package com.negger.chronos.history;

import com.negger.chronos.ChronosMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PersistenceIO {
    public record ClockAnchor(long tick, long epochMillis) {}

    public static void saveMeta(Path dir, long lastTick) {
        try {
            Files.createDirectories(dir);
            NbtCompound root = new NbtCompound();
            root.putLong("lastTick", lastTick);
            root.putLong("lastEpochMillis", System.currentTimeMillis());
            NbtIo.writeCompressed(root, dir.resolve("meta.dat").toFile());
        } catch (IOException e) { ChronosMod.LOGGER.warn("Chronos : impossible de sauvegarder les métadonnées", e); }
    }

    public static ClockAnchor loadMeta(Path dir) {
        java.io.File file = dir.resolve("meta.dat").toFile();
        if (!file.exists()) return null;
        try {
            NbtCompound root = NbtIo.readCompressed(file);
            return new ClockAnchor(root.getLong("lastTick"), root.getLong("lastEpochMillis"));
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Chronos : impossible de charger les métadonnées", e);
            return null;
        }
    }

    public static void save(Path dir, UUID uuid, List<TimeSnapshot> history) {
        if (history.isEmpty()) return;
        try {
            Files.createDirectories(dir);
            NbtCompound root = new NbtCompound();
            root.putInt("version", 4);
            NbtList list = new NbtList();
            for (TimeSnapshot s : history) {
                NbtCompound n = new NbtCompound();
                n.putLong("tick", s.tick());
                n.putDouble("x", s.x()); n.putDouble("y", s.y()); n.putDouble("z", s.z());
                n.putFloat("yaw", s.yaw()); n.putFloat("pitch", s.pitch());
                n.putFloat("health", s.health()); n.putInt("food", s.foodLevel()); n.putFloat("sat", s.saturation());
                n.putInt("xpLevel", s.experienceLevel()); n.putInt("xpTotal", s.totalExperience()); n.putFloat("xpProgress", s.experienceProgress());
                n.putInt("selectedSlot", s.selectedSlot());
                n.putLong("worldTime", s.worldTime());
                n.putBoolean("raining", s.raining()); n.putBoolean("thundering", s.thundering());
                n.putInt("clearWeatherTime", s.clearWeatherTime()); n.putInt("rainTime", s.rainTime()); n.putInt("thunderTime", s.thunderTime());
                if (s.inventoryNbt() != null) n.put("inventory", s.inventoryNbt().copy());
                list.add(n);
            }
            root.put("snapshots", list);
            NbtIo.writeCompressed(root, dir.resolve(uuid + ".dat").toFile());
        } catch (IOException e) { ChronosMod.LOGGER.warn("Chronos : impossible de sauvegarder l'historique de " + uuid, e); }
    }

    public static List<TimeSnapshot> load(Path dir, UUID uuid) {
        List<TimeSnapshot> result = new ArrayList<>();
        java.io.File file = dir.resolve(uuid + ".dat").toFile();
        if (!file.exists()) return result;
        try {
            NbtCompound root = NbtIo.readCompressed(file);
            if (root.contains("snapshots")) {
                NbtList list = root.getList("snapshots", 10);
                for (int i = 0; i < list.size(); i++) {
                    NbtCompound n = list.getCompound(i);
                    NbtCompound inventory = n.contains("inventory") ? n.getCompound("inventory").copy() : null;
                    result.add(new TimeSnapshot(
                            n.getLong("tick"), n.getDouble("x"), n.getDouble("y"), n.getDouble("z"),
                            n.getFloat("yaw"), n.getFloat("pitch"), n.getFloat("health"), n.getInt("food"), n.getFloat("sat"),
                            n.getInt("xpLevel"), n.getInt("xpTotal"), n.getFloat("xpProgress"), n.getInt("selectedSlot"),
                            n.getLong("worldTime"), n.getBoolean("raining"), n.getBoolean("thundering"),
                            n.getInt("clearWeatherTime"), n.getInt("rainTime"), n.getInt("thunderTime"), inventory
                    ));
                }
                return result;
            }

            int count = root.getInt("count");
            byte[] data = root.getByteArray("data");
            int version = root.contains("version") ? root.getInt("version") : 1;
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))) {
                for (int i = 0; i < count; i++) {
                    long tick = in.readLong();
                    double x = version >= 2 ? in.readDouble() : in.readFloat();
                    double y = version >= 2 ? in.readDouble() : in.readFloat();
                    double z = version >= 2 ? in.readDouble() : in.readFloat();
                    float yaw = in.readFloat(), pitch = in.readFloat(), health = in.readFloat();
                    int food = in.readInt(); float sat = in.readFloat();
                    long worldTime = version >= 2 ? in.readLong() : 0L;
                    result.add(new TimeSnapshot(tick, x, y, z, yaw, pitch, health, food, sat, worldTime));
                }
            }
        } catch (IOException e) { ChronosMod.LOGGER.warn("Chronos : impossible de charger l'historique de " + uuid, e); }
        return result;
    }
}
