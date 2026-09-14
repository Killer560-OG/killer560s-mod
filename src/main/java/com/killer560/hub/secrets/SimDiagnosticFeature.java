package com.killer560.hub.secrets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-data capture for p3sim.net testing - killer560 confirmed {@code /killer560 sim} unblocks the
 * shared floor/boss-phase gate (the overlay message shows), but Simon Says/Tick Timers/Split
 * Timers/Mask Invincibility still don't do anything there. Since every one of those besides Simon Says
 * is triggered PURELY by matching real Hypixel's exact boss chat wording, and Simon Says also depends
 * on real Hypixel's exact F7 boss-room block coordinates, the next most likely explanation is that
 * p3sim's chat text and/or world layout genuinely differs from real Hypixel's - not something to guess
 * at a second time.
 * <p>
 * While {@link DungeonState#isSimOverrideActive()} is on, this logs every chat line verbatim (no
 * keyword filter - the whole point is seeing p3sim's REAL wording, which by definition isn't known in
 * advance) and your own position every 2 seconds, both tagged {@code [SimDiag]}. Run a real p3sim
 * session with sim mode on, then send back {@code logs/latest.log} (or just the {@code [SimDiag]}
 * lines from it) - that's what's needed to fix the actual regexes/coordinates rather than guessing
 * again.
 */
public final class SimDiagnosticFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-simdiag");
    private static int tickCounter = 0;

    private SimDiagnosticFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void onChatMessage(Component message) {
        if (!DungeonState.isSimOverrideActive()) {
            return;
        }
        LOGGER.info("[SimDiag] Chat: \"{}\"", message.getString());
    }

    private static void tick() {
        if (!DungeonState.isSimOverrideActive()) {
            tickCounter = 0;
            return;
        }
        tickCounter++;
        if (tickCounter < 40) {
            return;
        }
        tickCounter = 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        var pos = client.player.position();
        LOGGER.info("[SimDiag] Position: x={} y={} z={}", pos.x, pos.y, pos.z);
    }
}
