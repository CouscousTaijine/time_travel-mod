package com.negger.chronos.mixin;

import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {
    @Unique private final ThreadLocal<BlockState> chronos$oldState = new ThreadLocal<>();

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"))
    private void chronos$captureOld(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (self instanceof ServerWorld && !RewindManager.isRestoring() && !RewindManager.isRewinding()) {
            chronos$oldState.set(self.getBlockState(pos));
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("RETURN"))
    private void chronos$recordChange(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        BlockState oldState = chronos$oldState.get();
        chronos$oldState.remove();
        if (!(self instanceof ServerWorld serverWorld) || RewindManager.isRestoring() || RewindManager.isRewinding() || !cir.getReturnValueZ() || oldState == null || oldState.equals(newState)) return;
        HistoryManager.recordGlobalBlockChange(
                new com.negger.chronos.history.GlobalBlockChange(
                        HistoryManager.getCurrentTick(),
                        serverWorld.getRegistryKey(),
                        pos.toImmutable(),
                        oldState,
                        newState
                )
        );
    }
}
