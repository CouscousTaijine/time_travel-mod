package com.negger.chronos.history;

import net.minecraft.nbt.NbtCompound;

/**
 * Capturé au moment où une entité (animal/monstre) meurt : son type et son
 * NBT complet, pour pouvoir la faire réapparaître telle qu'elle était si un
 * joueur remonte le temps jusqu'avant sa mort.
 */
public record DeathRecord(
        long tick,
        String entityTypeId,
        NbtCompound nbt,
        double x, double y, double z
) {
}
