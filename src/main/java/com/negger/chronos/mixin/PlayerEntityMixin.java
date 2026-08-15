package com.negger.chronos.mixin;

import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.ItemDropRecord;
import com.negger.chronos.rewind.RewindManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("RETURN"))
    private void chronos$recordDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        ItemEntity item = cir.getReturnValue();
        if (!(self instanceof ServerPlayerEntity player) || item == null || stack.isEmpty() || RewindManager.isRestoring() || RewindManager.isRewinding()) return;
        HistoryManager.recordItemDrop(new ItemDropRecord(
                HistoryManager.getCurrentTick(),
                player.getUuid(),
                item.getUuid(),
                player.getServerWorld().getRegistryKey(),
                stack.copy(),
                new Vec3d(item.getX(), item.getY(), item.getZ())
        ));
    }
}
