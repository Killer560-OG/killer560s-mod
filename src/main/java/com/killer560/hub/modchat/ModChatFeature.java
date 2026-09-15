package com.killer560.hub.modchat;

import com.killer560.hub.notify.ModOverlayMessage;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * "Mod Chat" - killer560's "custom chat where only mod users in the same lobby can see it" request.
 * <p>
 * <b>Real, disclosed limitation:</b> this mod has no hosted server or private network channel of its
 * own - the only transport actually available is Hypixel's own Party/Guild chat, which every member of
 * that party/guild sees regardless of whether they run this mod. So this does NOT hide the message from
 * non-mod users - a message sent with {@code /killer560 chat} still shows up as an ordinary (if odd,
 * tagged) line in everyone's normal chat. What this actually provides: any OTHER player running this
 * mod gets their copy specially detected, stripped of its tag, and shown as a distinct highlighted
 * overlay - closer to "a chat channel mod users can filter for" than a truly private channel. Building
 * a genuinely private channel would need real infrastructure (a relay server killer560 would have to
 * host) - flagged rather than silently overpromised.
 * <p>
 * Registered as {@code /killer560 chat <message>} rather than the literally-requested {@code /chat
 * killer560} - {@code /chat} is Hypixel's OWN real command (switches your default chat channel), and a
 * Fabric client command with that same root would intercept every {@code /chat ...} call, including
 * legitimate ones, before Hypixel ever saw it. Keeping this under this mod's own {@code /killer560}
 * namespace avoids breaking that real command entirely.
 */
public final class ModChatFeature {

    private static final String TAG = "[K560C]";

    private ModChatFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
    }

    private static void onChatMessage(Component message) {
        if (!ModChatConfig.getInstance().isEnabled()) {
            return;
        }
        String raw = message.getString();
        int index = raw.indexOf(TAG);
        if (index < 0) {
            return;
        }
        String body = raw.substring(index + TAG.length()).trim();
        if (body.isEmpty()) {
            return;
        }
        ModOverlayMessage.show("[ModChat] " + body, 4000);
    }

    /** For {@code /killer560 chat <message>}. */
    public static String send(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return "§c[ModChat] You need to be in a world to send this.";
        }
        ModChatConfig cfg = ModChatConfig.getInstance();
        client.player.connection.sendCommand(cfg.getChannel().commandPrefix + " " + TAG + " " + message);
        return "[ModChat] Sent via " + cfg.getChannel().name() + " chat (still visible to non-mod-users there).";
    }
}
