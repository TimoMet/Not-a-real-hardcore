package com.timo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static net.minecraft.server.command.CommandManager.*;

public class NotARealHardcore implements ModInitializer {

    private final int SPECTATOR_DURATION = 3600 * 1000; // 1 hour in milliseconds
    private final int DEATH_MALUS = 2; // 1 = 1/2 heart

    private final int HEALTH_INCREASE = 2; // 1 = 1/2 heart


    private final ConcurrentHashMap<UUID, Long> playerSpectatorEndTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> pendingHealthIncreases = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("resethardcore")
                .requires(source -> source.hasPermissionLevel(4))
                .executes(context -> {
                    // Reset all players' max health to 20
                    context.getSource().getServer().getPlayerManager().getPlayerList().forEach(player -> {
                        player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(20);
                    });

                    return 1;
                })));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("showRespawnTimer")
                .executes(context -> {
                    var player = context.getSource().getPlayer();
                    if (player != null) {
                        commandShowTime(player);
                    }
                    return 1;
                })));


        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity serverPlayer) {
                applyMalus(serverPlayer);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity serverPlayer = handler.player;
            UUID playerUUID = serverPlayer.getUuid();
            Long endTime = playerSpectatorEndTimes.get(playerUUID);
            if (endTime != null && System.currentTimeMillis() >= endTime) {
                respawnPlayer(serverPlayer, playerUUID);
            }

            Integer pendingIncrease = pendingHealthIncreases.remove(playerUUID);
            if (pendingIncrease != null) {
                increaseMaxHealth(serverPlayer, pendingIncrease);
            }
        });

        ServerLifecycleEvents.SERVER_STARTING.register(this::scheduleHealthIncrease);
    }

    private void applyMalus(ServerPlayerEntity serverPlayer) {
        double newMaxHealth = serverPlayer.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).getBaseValue() - DEATH_MALUS;
        serverPlayer.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(newMaxHealth);
        serverPlayer.changeGameMode(GameMode.SPECTATOR);

        UUID playerUUID = serverPlayer.getUuid();
        long endTime = System.currentTimeMillis() + SPECTATOR_DURATION;
        playerSpectatorEndTimes.put(playerUUID, endTime);

        String playerName = serverPlayer.getName().getLiteralString();
        // Schedule task to revert to survival mode after 1 hour (3600 seconds)
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                ServerPlayerEntity player = serverPlayer.getServerWorld().getServer().getPlayerManager().getPlayer(playerUUID);
                if (player != null) {
                    respawnPlayer(player, playerUUID);
                }
                //message everyone
                serverPlayer.getServer().getPlayerManager().getPlayerList().forEach(
                        p -> p.sendMessage(Text.of(playerName + " is now back from the dead!"), false)
                );
            }
        }, SPECTATOR_DURATION);

        //say it in chat
        serverPlayer.sendMessage(Text.of("You have died! You will be in spectator mode for 1 hour."), false);
    }

    private void respawnPlayer(ServerPlayerEntity player, UUID playerUUID) {
        player.changeGameMode(GameMode.SURVIVAL);
        playerSpectatorEndTimes.remove(playerUUID);

        // Teleport the player to their spawn point
        ServerWorld world = player.getServerWorld();
        BlockPos spawnPos = player.getSpawnPointPosition();
        if (spawnPos == null) {
            spawnPos = world.getSpawnPos();
        }
        player.teleport(world, spawnPos.getX(), spawnPos.getY(), spawnPos.getZ(), player.getYaw(), player.getPitch());
        // play bell sound
        player.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.AMBIENT, 2.0F, 1.0F);
    }


    private void commandShowTime(ServerPlayerEntity player) {
        player.sendMessage(Text.of(player.getName().getLiteralString() + " has " + getFormattedTime(playerSpectatorEndTimes.get(player.getUuid()) - System.currentTimeMillis()) + " left in spectator mode."), false);
    }

    private String getFormattedTime(long time) {
        long seconds = time / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return hours + "h " + minutes % 60 + "m " + seconds % 60 + "s";
    }

    public void scheduleHealthIncrease(MinecraftServer server) {
        long delay = calculateDelayUntilNextFriday10PM();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                increaseMaxHealthForAllPlayers(server);
                scheduleHealthIncrease(server); // Reschedule for next week
            }
        }, delay);
    }

    private long calculateDelayUntilNextFriday10PM() {
        Calendar nextFriday = Calendar.getInstance();
        nextFriday.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY);
        nextFriday.set(Calendar.HOUR_OF_DAY, 0);
        nextFriday.set(Calendar.MINUTE, 0);
        nextFriday.set(Calendar.SECOND, 0);
        nextFriday.set(Calendar.MILLISECOND, 0);

        // If today is Friday and it's past 10 PM, move to next week
        if (nextFriday.getTimeInMillis() <= System.currentTimeMillis()) {
            nextFriday.add(Calendar.WEEK_OF_YEAR, 1);
        }

        Logger.getGlobal().info("Next Friday in : " + (nextFriday.getTimeInMillis() - System.currentTimeMillis()) / 1000 / 60 + "m");

        return nextFriday.getTimeInMillis() - System.currentTimeMillis();
    }


    private void increaseMaxHealthForAllPlayers(MinecraftServer server) {

        // Increase health for online players
        server.getPlayerManager().getPlayerList().forEach(this::increaseMaxHealth);

        // Handle offline players
        Set<UUID> allPlayerUUIDs = getAllPlayerUUIDs(server);
        for (UUID uuid : allPlayerUUIDs) {
            if (server.getPlayerManager().getPlayer(uuid) == null) {
                pendingHealthIncreases.merge(uuid, 2, Integer::sum);
            }
        }
    }

    private void increaseMaxHealth(ServerPlayerEntity player) {
        increaseMaxHealth(player, HEALTH_INCREASE);
    }

    private void increaseMaxHealth(ServerPlayerEntity player, int amount) {
        double maxHealth = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).getBaseValue();

        if (maxHealth < 2) {
            maxHealth = 2;
        }

        maxHealth += amount;

        if (maxHealth > 20) {
            maxHealth = 20;
        }

        player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);


        player.sendMessage(Text.of("Your max health has increased by " + amount / 2f + " heart!"), false);
    }

    private Set<UUID> getAllPlayerUUIDs(MinecraftServer server) {
        Set<UUID> uuidSet = new HashSet<>();
        try {
            Path playerDataFolder = server.getSavePath(WorldSavePath.PLAYERDATA);
            DirectoryStream<Path> stream = Files.newDirectoryStream(playerDataFolder);
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".dat")) {
                    String uuidString = fileName.substring(0, fileName.length() - 4);
                    try {
                        UUID uuid = UUID.fromString(uuidString);
                        uuidSet.add(uuid);
                    } catch (IllegalArgumentException e) {
                        // Skip invalid UUIDs
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return uuidSet;
    }
}