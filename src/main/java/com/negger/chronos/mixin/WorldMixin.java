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
    // setBlockState() est ré-entrant : poser un bloc peut déclencher, dans la même
    // pile d'appels, d'autres setBlockState (mise à jour de voisins, piston qui pousse
    // un bloc, redstone, etc). Un simple ThreadLocal<BlockState> se faisait donc
    // écraser par l'appel imbriqué, et l'appel englobant relisait "null" au retour :
    // son changement n'était alors jamais enregistré dans l'historique. Une pile
    // (une par thread) restaure le bon comportement pour les appels imbriqués.
    // LinkedList (pas ArrayDeque : celui-ci interdit les éléments null et lève une
    // NullPointerException dès qu'on empile un "pas suivi" -> crash au premier
    // setBlockState, donc dès qu'on pose/casse un bloc ou qu'on restaure un rewind).
    @Unique private final ThreadLocal<java.util.LinkedList<BlockState>> chronos$oldStateStack =
            ThreadLocal.withInitial(java.util.LinkedList::new);

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"))
    private void chronos$captureOld(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        boolean track = self instanceof ServerWorld && !RewindManager.isRestoring() && !RewindManager.isRewinding();
        // On empile systématiquement (au besoin un "null" sentinelle) pour que
        // chaque HEAD ait toujours un pop correspondant au RETURN du même appel,
        // même imbriqué.
        chronos$oldStateStack.get().push(track ? self.getBlockState(pos) : null);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("RETURN"))
    private void chronos$recordChange(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        java.util.LinkedList<BlockState> stack = chronos$oldStateStack.get();
        BlockState oldState = stack.isEmpty() ? null : stack.pop();
        World self = (World) (Object) this;
        if (!(self instanceof ServerWorld serverWorld) || oldState == null || RewindManager.isRestoring() || RewindManager.isRewinding() || !cir.getReturnValueZ() || oldState.equals(newState)) return;
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
