package com.negger.chronos;

import com.negger.chronos.command.ChronosCommands;
import com.negger.chronos.history.BlockChange;
import com.negger.chronos.history.DeathRecord;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.PersistenceIO;
import com.negger.chronos.item.ChronosShardItem;
import com.negger.chronos.listener.PlacementTracker;
import com.negger.chronos.rewind.RewindManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RewindManager.setServer(server);

            var anchor = PersistenceIO.loadMeta(getChronosDir(server));
            if (anchor != null) {
                HistoryManager.initializeClockFromAnchor(anchor.tick(), anchor.epochMillis());
                LOGGER.info("Chronos : horloge resynchronisée depuis la dernière session");
            }
        });

        // Tick serveur : avance l'horloge, enregistre joueurs + entités, fait avancer les rewinds actifs
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            HistoryManager.tick();
            PlacementTracker.resolvePending();

            server.getPlayerManager().getPlayerList().forEach(player -> {
                HistoryManager.recordSnapshot(player);
                HistoryManager.recordLongTermIfDue(player);
            });

            for (var onlinePlayer : server.getPlayerManager().getPlayerList()) {
                var nearby = onlinePlayer.getServerWorld().getEntitiesByClass(
                        LivingEntity.class,
                        onlinePlayer.getBoundingBox().expand(96),
                        e -> !(e instanceof PlayerEntity)
                );
                for (LivingEntity living : nearby) {
                    HistoryManager.recordEntitySnapshot(living);
                }
            }

            RewindManager.tickAll();

            // Sauvegarde automatique toutes les 5 minutes (filet de sécurité en cas de crash)
            if (HistoryManager.getCurrentTick() % 6000 == 0) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    PersistenceIO.save(getChronosDir(server), player.getUuid(), HistoryManager.getLongTermHistory(player.getUuid()));
                }
                PersistenceIO.saveMeta(getChronosDir(server), HistoryManager.getCurrentTick());
            }
        });

        // Un joueur se connecte -> on recharge son historique longue durée depuis le disque
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var loaded = PersistenceIO.load(getChronosDir(server), handler.player.getUuid());
            if (!loaded.isEmpty()) {
                HistoryManager.setLongTermHistory(handler.player.getUuid(), loaded);
                LOGGER.info("Chronos : historique longue durée rechargé pour {} ({} points)",
                        handler.player.getName().getString(), loaded.size());
            }
        });

        // Le serveur s'arrête -> on sauvegarde tout le monde avant de fermer
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                PersistenceIO.save(getChronosDir(server), player.getUuid(), HistoryManager.getLongTermHistory(player.getUuid()));
            }
            PersistenceIO.saveMeta(getChronosDir(server), HistoryManager.getCurrentTick());
        });

        // Un animal/monstre meurt -> on garde son NBT complet pour pouvoir le ressusciter
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof PlayerEntity) return;
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            HistoryManager.recordDeath(new DeathRecord(
                    HistoryManager.getCurrentTick(),
                    net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                    nbt,
                    entity.getX(), entity.getY(), entity.getZ()
            ));
        });

        // Cassage de bloc par un joueur -> on enregistre l'ancien état pour pouvoir le restaurer
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                HistoryManager.recordBlockChange(new BlockChange(
                        HistoryManager.getCurrentTick(),
                        pos.toImmutable(),
                        state,
                        world.getBlockState(pos),
                        serverPlayer.getUuid(),
                        BlockChange.ChangeType.BREAK
                ));
            }
        });

        // Interaction avec un bloc (pose potentielle) -> vérifié un tick plus tard
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient() && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                var targetPos = hitResult.getBlockPos().offset(hitResult.getSide());
                PlacementTracker.queueCheck(serverPlayer, world, targetPos);
                PlacementTracker.queueCheck(serverPlayer, world, hitResult.getBlockPos());
            }
            return ActionResult.PASS;
        });

        // Un joueur se déconnecte -> on sauvegarde son historique et on libère la mémoire
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PersistenceIO.save(getChronosDir(server), handler.player.getUuid(), HistoryManager.getLongTermHistory(handler.player.getUuid()));
            HistoryManager.unloadLongTermFromMemory(handler.player.getUuid());
            RewindManager.clear(handler.player.getUuid());
            HistoryManager.clearPlayerHistory(handler.player.getUuid());
        });
    }

    private static Path getChronosDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("chronos");
    }
}
