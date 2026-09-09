package com.killer560.hub.autojoinskyblock;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/** When enabled, sends {@code /skyblock} shortly after the client first connects to Hypixel - so
 *  killer560 doesn't have to type it every time he logs on. Modeled on SkyHanni's own
 *  {@code AutoJoinSkyblock} (which fires off the same true rising-edge "just connected to Hypixel"
 *  signal a real login handler gives, not anything scoreboard/chat based, so it can't misfire on
 *  {@code /lobby}, which never re-triggers a login) and quoi's equivalent feature.
 *  <p>
 *  One deliberate improvement over both of those references, per killer560's explicit requirement:
 *  neither of them cancels if the player manually navigates away during the delay window - SkyHanni
 *  just blindly fires {@code /skyblock} after a flat 30s no matter what the player did in the
 *  meantime. Here, any command the player sends themselves during {@link #DELAY_MS} (typing
 *  {@code /lobby} included) cancels the pending auto-join outright - see
 *  {@link ClientSendMessageEvents#COMMAND}. */
public final class AutoJoinSkyblockFeature {

    // Per killer560's explicit request (2026-09-08): fire almost immediately after loading in
    // rather than waiting several seconds.
    private static final long DELAY_MS = 500;

    private static boolean pending = false;
    private static long fireAtMs = -1;

    private AutoJoinSkyblockFeature() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> cancelPending());
        // Fires for ANY outgoing command regardless of source, including our own sendCommand() call
        // below - cancelPending() is called there FIRST, before sendCommand(), so by the time this
        // listener runs for our own command, pending is already false and this is a no-op for it.
        ClientSendMessageEvents.COMMAND.register(command -> cancelPending());
        ClientTickEvents.END_CLIENT_TICK.register(AutoJoinSkyblockFeature::tick);
    }

    private static void onJoin(Minecraft client) {
        if (!AutoJoinSkyblockConfig.getInstance().isEnabled()) {
            return;
        }
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null || !server.ip.toLowerCase(Locale.US).contains("hypixel.net")) {
            return;
        }
        pending = true;
        fireAtMs = System.currentTimeMillis() + DELAY_MS;
    }

    private static void cancelPending() {
        pending = false;
    }

    private static void tick(Minecraft client) {
        if (!pending || System.currentTimeMillis() < fireAtMs) {
            return;
        }
        pending = false;
        if (client.player != null) {
            client.player.connection.sendCommand("skyblock");
        }
    }
}
