package com.negger.chronos;

import com.negger.chronos.command.ChronosCommands;
import com.negger.chronos.history.DeathRecord;
import com.negger.chronos.history.HistoryManager;
import com.negger.chronos.history.PersistenceIO;
import com.negger.chronos.item.ChronosShardItem;
import com.negger.chronos.item.DimensionPortalItem;
import com.negger.chronos.rewind.RewindManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChronosMod implements ModInitializer {

    public static final String MOD_ID = "chronos";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Item CHRONOS_SHARD;
    public static Item DIMENSION_PORTAL;

    public static final RegistryKey<World> DIMENSION_PORTAL_WORLD = RegistryKey.of(
            RegistryKeys.WORLD,
            new Identifier(MOD_ID, "dimension_portal")
    );

    private static final Map<UUID, ReturnPosition> PORTAL_RETURN_POSITIONS = new HashMap<>();

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

        DIMENSION_PORTAL = Registry.register(
                Registries.ITEM,
                new Identifier(MOD_ID, "dimension_portal"),
                new DimensionPortalItem(new FabricItemSettings().maxCount(1))
        );

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(CHRONOS_SHARD);
            entries.add(DIMENSION_PORTAL);
        });
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

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            HistoryManager.tick();

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                HistoryManager.recordSnapshot(player);
                HistoryManager.recordLongTermIfDue(player);

                if (player.getServerWorld().getRegistryKey().equals(DIMENSION_PORTAL_WORLD)
                        && player.getY() < 55.0) {
                    returnFromPortalVoid(player);
                }
            }

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

            if (HistoryManager.getCurrentTick() % 6000 == 0) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    PersistenceIO.save(getChronosDir(server), player.getUuid(), HistoryManager.getLongTermHistory(player.getUuid()));
                }
                PersistenceIO.saveMeta(getChronosDir(server), HistoryManager.getCurrentTick());
            }
        });

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var loaded = PersistenceIO.load(getChronosDir(server), handler.player.getUuid());
            if (!loaded.isEmpty()) {
                HistoryManager.setLongTermHistory(handler.player.getUuid(), loaded);
                LOGGER.info("Chronos : historique longue durée rechargé pour {} ({} points)",
                        handler.player.getName().getString(), loaded.size());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                PersistenceIO.save(getChronosDir(server), player.getUuid(), HistoryManager.getLongTermHistory(player.getUuid()));
            }
            PersistenceIO.saveMeta(getChronosDir(server), HistoryManager.getCurrentTick());
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof PlayerEntity) return;
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            HistoryManager.recordDeath(new DeathRecord(
                    HistoryManager.getCurrentTick(),
                    Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                    nbt,
                    entity.getX(), entity.getY(), entity.getZ()
            ));
        });

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PersistenceIO.save(getChronosDir(server), handler.player.getUuid(), HistoryManager.getLongTermHistory(handler.player.getUuid()));
            HistoryManager.unloadLongTermFromMemory(handler.player.getUuid());
            RewindManager.clear(handler.player.getUuid());
            HistoryManager.clearPlayerHistory(handler.player.getUuid());
            PORTAL_RETURN_POSITIONS.remove(handler.player.getUuid());
        });
    }

    public static void savePortalReturnPosition(ServerPlayerEntity player) {
        PORTAL_RETURN_POSITIONS.put(player.getUuid(), new ReturnPosition(
                player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch()
        ));
    }

    public static double getReturnX(ServerPlayerEntity player) {
        ReturnPosition pos = PORTAL_RETURN_POSITIONS.get(player.getUuid());
        return pos == null ? player.getX() : pos.x;
    }

    public static double getReturnY(ServerPlayerEntity player) {
        ReturnPosition pos = PORTAL_RETURN_POSITIONS.get(player.getUuid());
        return pos == null ? player.getY() : pos.y;
    }

    public static double getReturnZ(ServerPlayerEntity player) {
        ReturnPosition pos = PORTAL_RETURN_POSITIONS.get(player.getUuid());
        return pos == null ? player.getZ() : pos.z;
    }

    /** Short visual/audio transition used by both entry and exit. */
    public static void playPortalTransition(ServerPlayerEntity player, ServerWorld destination, BlockPos destinationPos, boolean entering) {
        player.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 8, 0, false, false, false));
        player.playSound(SoundEvents.BLOCK_PORTAL_TRAVEL, 1.0f, entering ? 1.15f : 0.9f);
        destination.spawnParticles(
                ParticleTypes.PORTAL,
                destinationPos.getX() + 0.5,
                destinationPos.getY() + 1.0,
                destinationPos.getZ() + 0.5,
                120,
                1.0, 1.0, 1.0,
                0.18
        );
        destination.playSound(
                null,
                destinationPos,
                SoundEvents.BLOCK_PORTAL_TRAVEL,
                SoundCategory.PLAYERS,
                0.9f,
                entering ? 1.15f : 0.9f
        );
    }

    public static void returnFromPortalVoid(ServerPlayerEntity player) {
        ServerWorld overworld = player.getServer().getOverworld();
        ReturnPosition pos = PORTAL_RETURN_POSITIONS.get(player.getUuid());
        double x = pos == null ? overworld.getSpawnPos().getX() + 0.5 : pos.x;
        double y = pos == null ? overworld.getSpawnPos().getY() + 1.0 : pos.y;
        double z = pos == null ? overworld.getSpawnPos().getZ() + 0.5 : pos.z;
        float yaw = pos == null ? player.getYaw() : pos.yaw;
        float pitch = pos == null ? player.getPitch() : pos.pitch;

        playPortalTransition(player, overworld, new BlockPos((int) x, (int) y, (int) z), false);
        player.teleport(overworld, x, y, z, yaw, pitch);
    }

    /**
     * Creates the starter island only once. The initialized flag is stored in
     * the pocket world's PersistentState, so the world itself remains persistent:
     * blocks, containers, mobs, dropped items and other normal world data survive
     * leaving the dimension and restarting the server.
     */
    public static void preparePortalWorld(ServerWorld world) {
        PortalWorldState state = world.getPersistentStateManager().getOrCreate(
                PortalWorldState::fromNbt,
                PortalWorldState::new,
                MOD_ID + "_dimension_portal"
        );

        if (state.isInitialized()) {
            return;
        }

        BlockPos center = new BlockPos(0, 64, 0);

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.setBlockState(center.add(x, 0, z), Blocks.DIRT.getDefaultState(), 3);
                world.setBlockState(center.add(x, 1, z), Blocks.GRASS_BLOCK.getDefaultState(), 3);
            }
        }

        for (int y = 2; y <= 5; y++) {
            world.setBlockState(center.add(0, y, 0), Blocks.OAK_LOG.getDefaultState(), 3);
        }

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 3) {
                    world.setBlockState(center.add(x, 5, z), Blocks.OAK_LEAVES.getDefaultState(), 3);
                }
            }
        }

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    world.setBlockState(center.add(x, 6, z), Blocks.OAK_LEAVES.getDefaultState(), 3);
                }
            }
        }

        state.setInitialized();
    }

    public static void spawnPortalParticles(ServerWorld world, BlockPos pos) {
        world.spawnParticles(ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                80, 1.2, 1.0, 1.2, 0.15);
        world.playSound(null, pos, SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.PLAYERS, 0.7f, 1.3f);
    }

    private static Path getChronosDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("chronos");
    }

    private record ReturnPosition(double x, double y, double z, float yaw, float pitch) {}
}
