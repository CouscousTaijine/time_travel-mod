package com.negger.chronos.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.negger.chronos.ChronosMod;

public class DimensionPortalItem extends Item {
    public DimensionPortalItem(Settings settings) {
        super(settings.maxCount(1));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) {
            return TypedActionResult.success(stack);
        }
        if (!(user instanceof ServerPlayerEntity player)) {
            return TypedActionResult.fail(stack);
        }

        ServerWorld overworld = player.getServer().getOverworld();
        ServerWorld pocket = player.getServer().getWorld(ChronosMod.DIMENSION_PORTAL_WORLD);

        if (pocket == null) {
            player.sendMessage(Text.literal("§cLe Dimension Portal n'a pas pu être chargé."), true);
            return TypedActionResult.fail(stack);
        }

        player.swingHand(hand, true);
        player.playSound(SoundEvents.BLOCK_PORTAL_TRAVEL, 0.8f, 1.2f);

        if (player.getServerWorld().getRegistryKey().equals(ChronosMod.DIMENSION_PORTAL_WORLD)) {
            player.teleport(overworld, ChronosMod.getReturnX(player), ChronosMod.getReturnY(player), ChronosMod.getReturnZ(player), player.getYaw(), player.getPitch());
            player.sendMessage(Text.literal("§dRetour au monde normal"), true);
            return TypedActionResult.success(stack);
        }

        ChronosMod.savePortalReturnPosition(player);
        ChronosMod.preparePortalWorld(pocket);
        player.teleport(pocket, 0.5, 66.0, 0.5, player.getYaw(), player.getPitch());
        player.sendMessage(Text.literal("§dDimension Portal"), true);
        ChronosMod.spawnPortalParticles(pocket, new BlockPos(0, 66, 0));
        return TypedActionResult.success(stack);
    }
}
