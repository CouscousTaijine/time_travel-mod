package com.negger.chronos;

import com.negger.chronos.command.ChronosCommands;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.item.ChronosShardItem;
import com.negger.chronos.listener.PlacementTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChronosMod implements ModInitializer {

    public static final String MOD_ID = "chronos";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Item CHRONOS_SHARD;

    @Override
    public void onInitialize() {
        LOGGER.info("Chronos : initialisation du mod de rewind temporel");

        ChronosConfig.load(FabricLoader.getInstance().getConfigDir());

        registerItems();
        registerEvents();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ChronosCommands.register(dispatcher));
    }

    private void registerItems() {
        CHRONOS_SHARD = Registry.register(
                Registries.ITEM,
                new Identifier(MOD_ID, "chronos_shard"),
                new ChronosShardItem(new FabricItemSettings().maxCount(1))
        );

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(CHRONOS_SHARD));
    }

    private void registerEvents() {
        // Tick serveur : avance l'horloge interne, enregistre chaque joueur,
        // résout les vérifications de pose de bloc en attente.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            HistoryManager.tick();
            PlacementTracker.resolvePending();
            server.getPlayerManager().getPlayerList().forEach(HistoryManager::recordSnapshot);
        });

        // Cassage de bloc par un joueur -> on enregistre l'ancien état pour pouvoir le restaurer
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                HistoryManager.recordBlockChange(new BlockChange(
                        HistoryManager.getCurrentTick(),
                        pos.toImmutable(),
                        state,
                        world.getBlockState(pos),
                        serverPlayer.getUuid()
                ));
            }
        });

        // Interaction avec un bloc (pose potentielle) -> vérifié un tick plus tard
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient() && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                var targetPos = hitResult.getBlockPos().offset(hitResult.getSide());
                PlacementTracker.queueCheck(serverPlayer, world, targetPos);
                // On vérifie aussi la position visée elle-même (cas replace, ex: torches sur mur)
                PlacementTracker.queueCheck(serverPlayer, world, hitResult.getBlockPos());
            }
            return ActionResult.PASS;
        });
    }
}
