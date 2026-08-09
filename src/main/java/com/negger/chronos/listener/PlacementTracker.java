package com.negger.chronos.listener;

import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.HistoryManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fabric API n'expose pas d'event "après pose de bloc" directement utilisable
 * pour tous les cas (seaux, blocs multi-tick, etc). Solution simple et fiable :
 * on note "ce joueur a interagi avec ce bloc à ce tick", et on compare l'état
 * AVANT/APRÈS un tick plus tard. Si ça a changé, c'est une pose (ou une modif).
 */
public class PlacementTracker {

    private record PendingCheck(World world, BlockPos pos, UUID playerUuid, BlockState oldState, long checkAtTick) {}

    private static final List<PendingCheck> PENDING = new ArrayList<>();

    public static void queueCheck(ServerPlayerEntity player, World world, BlockPos pos) {
        BlockState before = world.getBlockState(pos);
        PENDING.add(new PendingCheck(world, pos, player.getUuid(), before, HistoryManager.getCurrentTick() + 1));
    }

    public static void resolvePending() {
        if (PENDING.isEmpty()) return;
        long now = HistoryManager.getCurrentTick();

        List<PendingCheck> ready = new ArrayList<>();
        for (PendingCheck check : PENDING) {
            if (check.checkAtTick <= now) ready.add(check);
        }
        PENDING.removeAll(ready);

        for (PendingCheck check : ready) {
            BlockState after = check.world.getBlockState(check.pos);
            if (!after.equals(check.oldState)) {
                HistoryManager.recordBlockChange(new BlockChange(
                        now, check.pos.toImmutable(), check.oldState, after, check.playerUuid,
                        BlockChange.ChangeType.PLACE
                ));
            }
        }
    }
}
