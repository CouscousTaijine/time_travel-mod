package com.negger.chronos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Config très simple en .properties, générée dans /config/chronos.properties
 * au premier lancement. C'est ici que tu règles "autant de temps que tu veux" :
 * augmente buffer_seconds pour agrandir la fenêtre de rewind disponible.
 *
 * Attention: plus buffer_seconds est grand, plus ça consomme de RAM
 * (environ 60-80 octets par joueur par tick enregistré).
 * 300s (5 min) = ~6000 ticks = quelques centaines de Ko par joueur. Sans souci.
 * 3600s (1h) = correct sur un petit serveur.
 */
public class ChronosConfig {
    public static int bufferSeconds = 300;   // fenêtre d'historique max
    public static double rewindSpeed = 1.0;  // 1.0 = temps réel, 2.0 = 2x plus vite en arrière
    public static boolean restoreHealthAndHunger = true;
    public static boolean onlyRevertOwnBlocks = true; // évite les conflits en multijoueur

    public static int getBufferTicks() {
        return bufferSeconds * 20;
    }

    public static void load(Path configDir) {
        Path file = configDir.resolve("chronos.properties");
        Properties props = new Properties();

        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                bufferSeconds = Integer.parseInt(props.getProperty("buffer_seconds", "300"));
                rewindSpeed = Double.parseDouble(props.getProperty("rewind_speed", "1.0"));
                restoreHealthAndHunger = Boolean.parseBoolean(props.getProperty("restore_health_and_hunger", "true"));
                onlyRevertOwnBlocks = Boolean.parseBoolean(props.getProperty("only_revert_own_blocks", "true"));
            } catch (IOException | NumberFormatException e) {
                ChronosMod.LOGGER.warn("Impossible de lire chronos.properties, valeurs par défaut utilisées", e);
            }
        } else {
            save(configDir);
        }
    }

    public static void save(Path configDir) {
        Path file = configDir.resolve("chronos.properties");
        Properties props = new Properties();
        props.setProperty("buffer_seconds", String.valueOf(bufferSeconds));
        props.setProperty("rewind_speed", String.valueOf(rewindSpeed));
        props.setProperty("restore_health_and_hunger", String.valueOf(restoreHealthAndHunger));
        props.setProperty("only_revert_own_blocks", String.valueOf(onlyRevertOwnBlocks));

        try {
            Files.createDirectories(configDir);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Config du mod Chronos - remonter le temps");
            }
        } catch (IOException e) {
            ChronosMod.LOGGER.warn("Impossible d'écrire chronos.properties", e);
        }
    }
}
