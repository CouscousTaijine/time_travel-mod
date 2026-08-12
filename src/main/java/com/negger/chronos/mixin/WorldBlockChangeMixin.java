package com.negger.chronos.mixin;

import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class WorldBlockChangeMixin {
    @Inject(method = "onBlockChanged", at = @At("HEAD"))
    private void chronos$recordWorldChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld serverWorld)) return;
        if (RewindManager.isRestoring()) return;
        if (oldState.equals(newState)) return;

        // Le moteur actuel est organisé par historique de joueur. On associe
        // les changements automatiques au joueur le plus proche afin que TNT,
        // pistons, redstone et mobs soient inclus dans le même rewind.
        ServerPlayerEntity owner = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            double distance = player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distance < bestDistance) {
                bestDistance = distance;
                owner = player;
            }
        }
        if (owner == null) return;

        BlockChange.ChangeType type = (!oldState.isAir() && newState.isAir())
                ? BlockChange.ChangeType.BREAK
                : BlockChange.ChangeType.PLACE;

        HistoryManager.recordBlockChange(new BlockChange(
                HistoryManager.getCurrentTick(),
                pos.toImmutable(),
                oldState,
                newState,
                owner.getUuid(),
                type
        ));
    }
}
