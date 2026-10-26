package com.killer560.hub.blink;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import com.killer560.hub.util.ModChat;

/**
 * {@code /blink} starts a hold (or releases the one running), {@code /blink <ticks>} starts one with that many Hold Ticks,
 * {@code /blink release} releases, {@code /blink status} says what the last hold was. Cheat build only (registered from
 * {@link BlinkFeature#register}).
 */
final class BlinkCommands {

    private BlinkCommands() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("blink")
                        .executes(c -> toggle())
                        .then(ClientCommands.literal("release").executes(c -> {
                            BlinkFeature.end("command");
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(c -> status()))
                        .then(ClientCommands.argument("ticks", IntegerArgumentType.integer(BlinkConfig.MIN_TICKS,
                                BlinkConfig.MAX_TICKS)).executes(c -> {
                            BlinkConfig cfg = BlinkConfig.getInstance();
                            cfg.setHoldTicks(IntegerArgumentType.getInteger(c, "ticks"));
                            cfg.save();
                            return start();
                        }))));
    }

    private static int toggle() {
        if (BlinkFeature.isHolding()) {
            BlinkFeature.end("command");
            return 1;
        }
        return start();
    }

    private static int start() {
        if (!BlinkConfig.getInstance().isEnabled()) {
            ModChat.send("Blink", ModChat.bad("Blink is switched off in its settings."));
            return 0;
        }
        BlinkFeature.begin("command");
        return 1;
    }

    private static int status() {
        BlinkConfig cfg = BlinkConfig.getInstance();
        ModChat.send("Blink", ModChat.text((BlinkFeature.isHolding() ? "holding " + BlinkFeature.heldTicks() + " tick(s), "
                + BlinkFeature.heldPackets() + " packet(s)" : "not holding") + "; Hold Ticks " + cfg.getHoldTicks()
                + "; last hold " + BlinkFeature.lastHoldTicks() + " tick(s) / " + BlinkFeature.lastHoldPackets()
                + " packet(s); longest " + BlinkFeature.longestHoldTicks() + " tick(s)"));
        return 1;
    }
}
