package com.negger.chronos.history;

import net.minecraft.nbt.NbtCompound;

/** Snapshot joueur + horloge/meteo du monde pour un rewind deterministe. */
public record TimeSnapshot(
        long tick,
        double x, double y, double z,
        float yaw, float pitch,
        float health,
        int foodLevel,
        float saturation,
        int experienceLevel,
        int totalExperience,
        float experienceProgress,
        long worldTime,
        boolean raining,
        boolean thundering,
        int clearWeatherTime,
        int rainTime,
        int thunderTime,
        NbtCompound inventoryNbt
) {
    public TimeSnapshot(long tick, double x, double y, double z, float yaw, float pitch,
                        float health, int foodLevel, float saturation, long worldTime) {
        this(tick, x, y, z, yaw, pitch, health, foodLevel, saturation,
                0, 0, 0.0f, worldTime, false, false, 0, 0, 0, null);
    }
}
