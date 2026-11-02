package com.killer560.hub.streamer;

import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

/** {@code /streamer} and {@code /streamermode} (cheat build only - the caller registers this only there). */
public final class StreamerCommands {

    private static final String FEATURE = "Streamer Mode";
    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-streamer");

    private StreamerCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("streamer").executes(context -> {
                toggle();
                return 1;
            }));
            dispatcher.register(ClientCommands.literal("streamermode").executes(context -> {
                toggle();
                return 1;
            }));
        });
    }

    /** Flips it without a word in chat (killer560, 2026-11-01: "do not have the chat line for turning it on or off show
     *  up when i toggle it"): a chat line saying Streamer Mode is on is exactly what a stream must not show. The new
     *  state goes to the log only. */
    private static void toggle() {
        boolean on = StreamerMode.toggle();
        LOGGER.info("[{}] {} by command", FEATURE, on ? "on" : "off");
    }
}
