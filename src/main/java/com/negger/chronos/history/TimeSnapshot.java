package com.negger.chronos.history;

/**
 * Instantané d'un joueur et de l'horloge du monde à un tick précis.
 */
public record TimeSnapshot(
        long tick,
        double x, double y, double z,
        float yaw, float pitch,
        float health,
        int foodLevel,
        float saturation,
        long worldTime
) {
}
