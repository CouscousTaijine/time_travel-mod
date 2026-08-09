package com.negger.chronos.history;

/**
 * Instantané de l'état d'une entité vivante (pas un joueur) à un tick donné.
 * Utilisé pour remettre les animaux/monstres à leur position et vie d'origine
 * quand un joueur remonte le temps.
 */
public record EntitySnapshot(
        long tick,
        java.util.UUID entityUuid,
        double x, double y, double z,
        float yaw, float pitch,
        float health
) {
}
