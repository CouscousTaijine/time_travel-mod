package com.negger.chronos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Config très simple en .properties, générée dans /config/chronos.properties
 * au premier lancement.
 *
 * - buffer_seconds : fenêtre d'historique FLUIDE (20x/sec) gardée en mémoire.
 *   Quelques minutes suffisent, c'est celle-ci que l'item utilise pour un
 *   rewind bien lisse.
 * - persist_days : historique gardé sur le DISQUE à 1x/sec, qui survit aux
 *   redémarrages du serveur. C'est celui-ci qui permet de remonter des jours
 *   en arrière. Environ 2-4 Mo par jour par joueur (compressé), donc même
 *   30 jours reste raisonnable (~100 Mo/joueur).
 */
public class ChronosConfig {
    public static int bufferSeconds = 300;
    public static double rewindSpeed = 1.0;
    public static boolean restoreHealthAndHunger = true;
    public static boolean persistenceEnabled = true;
    public static int persistDays = 3;

    public static int getBufferTicks() {
        return bufferSeconds * 20;
    }

    public static long getPersistCapacity() {
        return (long) persistDays * 86400L; // 1 entrée/seconde
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
                persistenceEnabled = Boolean.parseBoolean(props.getProperty("persistence_enabled", "true"));
                persistDays = Integer.parseInt(props.getProperty("persist_days", "3"));
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
        props.setProperty("persistence_enabled", String.valueOf(persistenceEnabled));
        props.setProperty("persist_days", String.valueOf(persistDays));

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
