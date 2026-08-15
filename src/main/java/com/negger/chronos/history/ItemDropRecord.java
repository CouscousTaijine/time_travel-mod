package com.negger.chronos.history;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

public record ItemDropRecord(
        long tick,
        UUID playerUuid,
        UUID entityUuid,
        ItemStack stack,
        Vec3d position
) {}
