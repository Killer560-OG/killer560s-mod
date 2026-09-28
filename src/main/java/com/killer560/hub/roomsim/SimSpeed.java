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

    private SimSpeed() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("speed")
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
        ModChat.send("Sim", ModChat.text("Speed set to "), ModChat.value(String.valueOf(hypixelSpeed)));
    }
}
