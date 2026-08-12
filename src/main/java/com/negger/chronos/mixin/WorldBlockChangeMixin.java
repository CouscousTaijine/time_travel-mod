package com.negger.chronos.mixin;

import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.block.BlockState;
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

        BlockChange.ChangeType type;
        if (!oldState.isAir() && newState.isAir()) type = BlockChange.ChangeType.BREAK;
        else type = BlockChange.ChangeType.PLACE;

        HistoryManager.recordWorldBlockChange(serverWorld, new BlockChange(
                HistoryManager.getCurrentTick(),
                pos.toImmutable(),
                oldState,
                newState,
                null,
                type
        ));
    }
}
