package com.negger.chronos.mixin;

import com.negger.chronos.history.HistoryManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldBlockHistoryMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chronos$recordBlockChange(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (self instanceof ServerWorld serverWorld && !HistoryManager.isRestoring()) {
            HistoryManager.recordWorldBlockChange(serverWorld, pos, self.getBlockState(pos), newState);
        }
    }
}
