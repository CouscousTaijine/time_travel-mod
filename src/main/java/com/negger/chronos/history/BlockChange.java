package com.negger.chronos.history;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Enregistre un changement de bloc (pose ou cassage) causé par un joueur,
 * avec l'état AVANT le changement pour pouvoir le restaurer pendant un rewind.
 *
 * type permet de savoir, en plus de remettre le bloc, s'il faut aussi
 * ajouter/retirer l'item correspondant de l'inventaire du joueur pour que
 * tout redevienne cohérent (pas de duplication ni de perte d'item).
 */
public record BlockChange(
        long tick,
        BlockPos pos,
        BlockState oldState,
        BlockState newState,
        UUID playerUuid,
        ChangeType type
) {
    public enum ChangeType {
        BREAK, // le joueur a cassé un bloc (oldState -> air), il a reçu l'item
        PLACE  // le joueur a posé un bloc (air/replaceable -> newState), il a dépensé l'item
    }
}
