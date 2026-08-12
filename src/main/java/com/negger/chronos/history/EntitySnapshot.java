package com.negger.chronos.history;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/** Snapshot complet d'une entite non-joueur, y compris les ItemEntity et projectiles. */
public record EntitySnapshot(
        long tick,
        UUID entityUuid,
        String entityTypeId,
        String worldKey,
        NbtCompound nbt
) {
    public double x() {
        return nbt.contains("Pos") ? nbt.getList("Pos", 6).getDouble(0) : 0.0;
    }
    public double y() {
        return nbt.contains("Pos") ? nbt.getList("Pos", 6).getDouble(1) : 0.0;
    }
    public double z() {
        return nbt.contains("Pos") ? nbt.getList("Pos", 6).getDouble(2) : 0.0;
    }
    public float yaw() {
        return nbt.contains("Rotation") ? nbt.getList("Rotation", 5).getFloat(0) : 0.0f;
    }
    public float pitch() {
        return nbt.contains("Rotation") ? nbt.getList("Rotation", 5).getFloat(1) : 0.0f;
    }
    public float health() {
        return nbt.getFloat("Health");
    }
}
