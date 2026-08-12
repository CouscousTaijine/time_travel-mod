package com.negger.chronos.history;

import com.negger.chronos.ChronosMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PersistenceIO {

    private static final int RECORD_SIZE = 48;

    public record ClockAnchor(long tick, long epochMillis) {}

    public static void saveMeta(Path chronosDir, long lastTick) {
        try {
            Files.createDirectories(chronosDir);
            NbtCompound root = new NbtCompound();
            root.putLong("lastTick", lastTick);
            root.putLong("lastEpochMillis", System.currentTimeMillis());
            NbtIo.writeCompressed(root, chronosDir.resolve("meta.dat").toFile());
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Chronos : impossible de sauvegarder les métadonnées d'horloge", e);
        }
    }

    public static ClockAnchor loadMeta(Path chronosDir) {
        java.io.File file = chronosDir.resolve("meta.dat").toFile();
        if (!file.exists()) return null;
        try {
            NbtCompound root = NbtIo.readCompressed(file);
            return new ClockAnchor(root.getLong("lastTick"), root.getLong("lastEpochMillis"));
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Chronos : impossible de charger les métadonnées d'horloge", e);
            return null;
        }
    }

    public static void save(Path chronosDir, UUID uuid, List<TimeSnapshot> longTerm) {
        if (longTerm.isEmpty()) return;
        try {
            Files.createDirectories(chronosDir);
            java.io.File file = chronosDir.resolve(uuid + ".dat").toFile();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(longTerm.size() * RECORD_SIZE);
            try (DataOutputStream out = new DataOutputStream(baos)) {
                for (TimeSnapshot s : longTerm) {
                    out.writeLong(s.tick());
                    out.writeDouble(s.x());
                    out.writeDouble(s.y());
                    out.writeDouble(s.z());
                    out.writeFloat(s.yaw());
                    out.writeFloat(s.pitch());
                    out.writeFloat(s.health());
                    out.writeInt(s.foodLevel());
                    out.writeFloat(s.saturation());
                    out.writeLong(s.worldTime());
                }
            }
            NbtCompound root = new NbtCompound();
            root.putInt("version", 2);
            root.putInt("count", longTerm.size());
            root.putByteArray("data", baos.toByteArray());
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Chronos : impossible de sauvegarder l'historique de " + uuid, e);
        }
    }

    public static List<TimeSnapshot> load(Path chronosDir, UUID uuid) {
        List<TimeSnapshot> result = new ArrayList<>();
        java.io.File file = chronosDir.resolve(uuid + ".dat").toFile();
        if (!file.exists()) return result;
        try {
            NbtCompound root = NbtIo.readCompressed(file);
            int count = root.getInt("count");
            byte[] data = root.getByteArray("data");
            int version = root.contains("version") ? root.getInt("version") : 1;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
                for (int i = 0; i < count; i++) {
                    long tick = in.readLong();
                    double x = version >= 2 ? in.readDouble() : in.readFloat();
                    double y = version >= 2 ? in.readDouble() : in.readFloat();
                    double z = version >= 2 ? in.readDouble() : in.readFloat();
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    float health = in.readFloat();
                    int food = in.readInt();
                    float sat = in.readFloat();
                    long worldTime = version >= 2 ? in.readLong() : 0L;
                    result.add(new TimeSnapshot(tick, x, y, z, yaw, pitch, health, food, sat, worldTime));
                }
            }
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Chronos : impossible de charger l'historique de " + uuid, e);
        }
        return result;
    }
}
