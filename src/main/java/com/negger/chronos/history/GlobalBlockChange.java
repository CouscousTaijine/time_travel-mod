package com.negger.chronos.history;

import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A world-wide block change. Unlike BlockChange, it is not tied to the player who caused it. */
public record GlobalBlockChange(
        long tick,
        RegistryKey<World> worldKey,
        BlockPos pos,
        BlockState oldState,
        BlockState newState
) {}
