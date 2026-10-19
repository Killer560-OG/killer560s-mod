package com.killer560.hub.streamer;

import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

/** {@code /streamer} and {@code /streamermode} (cheat build only - the caller registers this only there). */
public final class StreamerCommands {

    private static final String FEATURE = "Streamer Mode";

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

    private static void toggle() {
        boolean on = StreamerMode.toggle();
        if (on) {
            ModChat.send(FEATURE, ModChat.text("On - "), ModChat.value(StreamerMode.option().label),
                    ModChat.dim(". Run the command again to turn it off and restore everything."));
        } else {
            ModChat.send(FEATURE, ModChat.good("Off"), ModChat.text(" - everything is back as it was."));
        }
    }
}
