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

/**
 * Sauvegarde l'historique "longue durée" (1 point par seconde) d'un joueur
 * sur le disque, dans le dossier de sauvegarde du monde : world/chronos/<uuid>.dat
 *
 * Format : un NbtCompound compressé en Gzip (comme n'importe quelle sauvegarde
 * Minecraft) contenant un unique tableau d'octets où chaque entrée est encodée
 * en binaire compact à taille fixe (40 octets/entrée). Beaucoup plus léger
 * qu'une liste NBT classique (qui répète les noms de champs à chaque entrée).
 */
public class PersistenceIO {

    private static final int RECORD_SIZE = 40; // 8+4+4+4+4+4+4+4+4 octets

    /**
     * Le compteur de tick interne repart de zéro à chaque redémarrage du
     * serveur. Sans ça, un vieux tick (ex: 5 000 000, d'il y a 3 jours) et un
     * nouveau tick (qui recommence à 0) rentreraient en collision et tout
     * l'ordre chronologique du rewind casserait. On sauvegarde donc une
     * petite "ancre" (dernier tick connu + horodatage réel) pour que le
     * compteur reparte au bon endroit après un redémarrage, en tenant compte
     * du temps réel écoulé (même serveur éteint).
     */
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
                    out.writeFloat((float) s.x());
                    out.writeFloat((float) s.y());
                    out.writeFloat((float) s.z());
                    out.writeFloat(s.yaw());
                    out.writeFloat(s.pitch());
                    out.writeFloat(s.health());
                    out.writeInt(s.foodLevel());
                    out.writeFloat(s.saturation());
                }
            }

            NbtCompound root = new NbtCompound();
            root.putInt("version", 1);
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

            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
                for (int i = 0; i < count; i++) {
                    long tick = in.readLong();
                    double x = in.readFloat();
                    double y = in.readFloat();
                    double z = in.readFloat();
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    float health = in.readFloat();
                    int food = in.readInt();
                    float sat = in.readFloat();
                    result.add(new TimeSnapshot(tick, x, y, z, yaw, pitch, health, food, sat));
                }
            }
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Chronos : impossible de charger l'historique de " + uuid, e);
        }
        return result;
    }
}
