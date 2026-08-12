package com.negger.chronos.history;

import net.minecraft.nbt.NbtCompound;

/** État du joueur et de l'horloge/météo du monde à un tick précis. */
public record TimeSnapshot(
        long tick,
        double x, double y, double z,
        float yaw, float pitch,
        float health,
        int foodLevel,
        float saturation,
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
                worldTime, false, false, 0, 0, 0, null);
    }
}
