package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * {@code /speed <n>} - sets movement speed on the Hypixel scale, inside the sim.
 *
 * <p>killer560 (2026-09-28): "Make a command for /speed ___ and let me set it to whatever I want." It matters
 * more than it sounds: he plays at 550 to 600, every route he practises is timed at that speed, and a sim stuck
 * at a normal walk would teach spacing that does not transfer.
 *
 * <p>100 is a normal walk, which is vanilla's 0.1 - so the attribute is simply the number over a thousand, the
 * same conversion the anticheat harness uses.
 *
 * <p>Sim only. This writes an attribute on the player, and doing that anywhere near a real server is the kind
 * of thing that ends an account - so it refuses outside the sim rather than trusting the command not to be
 * typed there.
 */
public final class SimSpeed {

    /** Hypixel's own scale: 100 speed is one normal walk. */
    private static final int MIN = 1;

    /** Far above anything Hypixel grants. A cap exists so a typo cannot make the player unplayable. */
    private static final int MAX = 10_000;

    /** What the sim opens at before he has ever set one - a normal walk. */
    private static final int DEFAULT_SPEED = 100;

    /**
     * The last speed he set, kept across restarts.
     *
     * <p>killer560 (2026-09-28): "Make sure it saves my speed on reboot." Every route he practises is timed at
     * 550 to 600, so a sim that comes back at 100 is not merely inconvenient - the first run after every
     * restart is at the wrong speed, and a route rehearsed at walking pace is a different route.
     */
    private static final java.nio.file.Path FILE = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("killer560smod-sim-speed.txt");

    private static int savedSpeed = -1;

    private SimSpeed() {
    }

    /** The remembered speed, or the default when none has been set. */
    public static synchronized int saved() {
        if (savedSpeed < 0) {
            savedSpeed = DEFAULT_SPEED;
            try {
                if (java.nio.file.Files.exists(FILE)) {
                    savedSpeed = Integer.parseInt(java.nio.file.Files
                            .readString(FILE, java.nio.charset.StandardCharsets.UTF_8).trim());
                }
            } catch (Exception ignored) {
                // Unreadable means unknown, and the default is a safe unknown.
            }
        }
        return savedSpeed;
    }

    private static synchronized void remember(int hypixelSpeed) {
        savedSpeed = hypixelSpeed;
        try {
            java.nio.file.Files.createDirectories(FILE.getParent());
            java.nio.file.Files.writeString(FILE, String.valueOf(hypixelSpeed),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Losing the preference is not worth failing the command he just ran.
        }
    }

    /** Re-applies the remembered speed. Called when a sim world opens. */
    public static void applySaved(Minecraft client) {
        set(client, saved());
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("speed")
                        // Sim-only, like /map, /fly and /start. Without this the client command claims the
                        // name globally and swallows the server's own /speed - Hypixel Housing has one - to
                        // answer "only works inside the dungeon sim".
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .then(ClientCommands.argument("amount", IntegerArgumentType.integer(MIN, MAX))
                                .executes(ctx -> {
                                    set(Minecraft.getInstance(),
                                            IntegerArgumentType.getInteger(ctx, "amount"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            show(Minecraft.getInstance());
                            return 1;
                        })));
    }

    private static void show(Minecraft client) {
        if (client.player == null) {
            return;
        }
        double attr = client.player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        ModChat.send("Sim", ModChat.text("Speed is "),
                ModChat.value(String.valueOf(Math.round(attr * 1000))),
                ModChat.dim("  (/speed <number>, 100 is a normal walk)"));
    }

    private static void set(Minecraft client, int hypixelSpeed) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("/speed only works inside the dungeon sim."));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        var uuid = client.player.getUUID();
        double value = hypixelSpeed / 1000.0;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            var attr = sp.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) {
                attr.setBaseValue(value);
            }
        });
        remember(hypixelSpeed);
        ModChat.send("Sim", ModChat.text("Speed set to "), ModChat.value(String.valueOf(hypixelSpeed)));
    }
}
