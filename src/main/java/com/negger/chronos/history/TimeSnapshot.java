package com.negger.chronos.history;

/**
 * Un instantané de l'état d'un joueur à un tick précis.
 * Immuable, léger : c'est ce qui est stocké des milliers de fois en mémoire.
 */
public record TimeSnapshot(
        long tick,
        double x, double y, double z,
        float yaw, float pitch,
        float health,
        int foodLevel,
        float saturation
) {
}
