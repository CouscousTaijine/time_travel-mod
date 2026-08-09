package com.negger.chronos.history;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Enregistre un changement de bloc (pose ou cassage) causé par un joueur,
 * avec l'état AVANT le changement pour pouvoir le restaurer pendant un rewind.
 */
public record BlockChange(
        long tick,
        BlockPos pos,
        BlockState oldState,
        BlockState newState,
        UUID playerUuid
) {
}
