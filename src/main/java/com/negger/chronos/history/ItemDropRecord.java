package com.negger.chronos.history;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

public record ItemDropRecord(
        long tick,
        UUID playerUuid,
        UUID entityUuid,
        RegistryKey<World> worldKey,
        ItemStack stack,
        Vec3d position
) {}
