package com.killer560.hub.modchat;

import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.relay.HypixelLocation;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.relay.RelayListener;
import com.killer560.hub.relay.RelayRoom;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;

/**
 * "Mod Chat" - killer560's "custom chat where only mod users in the same lobby can see it" request.
 * <p>
 * <b>Rebuilt 2026-09-20.</b> killer560: <i>"For the mod chat I meant for that to only be client side. So other
 * people not using my mod cannot see my chat."</i> The old version tagged a line and sent it over real Hypixel
 * Party/Guild chat, which is exactly the bug - everyone in that party saw it, mod or not. Hypixel has no private
 * client channel, so messages now go over the mod's own relay ({@code killer560s-mod-relay}, the same approach
 * NoammAddons uses for {@code ws.noamm.org}) and never touch Hypixel at all.
 * <p>
 * <b>There is deliberately no fallback to party chat.</b> If the relay is off, unset or unreachable, sending
 * fails and says so. Falling back would silently reintroduce the exact leak he reported.
 * <p>
 * Still registered as {@code /killer560 chat <message>} rather than the literally-requested {@code /chat
 * killer560} - {@code /chat} is Hypixel's OWN real command (switches your default chat channel), and a Fabric
 * client command with that same root would intercept every {@code /chat ...} call before Hypixel saw it.
 */
public final class ModChatFeature {

    /** Two seconds. The room name only changes when the party does, and reconnecting is not free. */
    private static final int TICKS_BETWEEN_CHECKS = 40;

    private static int tickCounter;

    private ModChatFeature() {
    }

    public static void register() {
        RelayClient.setListener(new Listener());
        HypixelLocation.register();
        ClientTickEvents.END_CLIENT_TICK.register(ModChatFeature::onTick);
    }

    private static void onTick(Minecraft client) {
        if (++tickCounter < TICKS_BETWEEN_CHECKS) {
            return;
        }
        tickCounter = 0;
        ModChatConfig cfg = ModChatConfig.getInstance();
        // The relay also carries party dungeon data (hub/partydata, Team Melody), which is ON by default and must
        // not depend on Mod Chat being on. Without Mod Chat, data always uses the party room - never a lobby room.
        boolean chatOn = cfg.isEnabled();
        boolean dataOn = com.killer560.hub.interop.InteropConfig.getInstance().isEnabled()
                && com.killer560.hub.interop.InteropConfig.getInstance().isRelayData()
                && (com.killer560.hub.partydata.PartyDataConfig.getInstance().isShareEnabled()
                    || com.killer560.hub.melody.MelodyHudConfig.getInstance().isShareProgress());
        boolean on = (chatOn || dataOn) && client.getConnection() != null && client.player != null;
        // Party dungeon data only travels in a party room. In a dungeon the lobby IS the party (the instance only holds
        // your team), so while sharing is on, a dungeon always uses the party room even if Mod Chat is set to Lobby -
        // otherwise choosing Lobby would silently stop data sharing. Mod Chat keeps its chosen mode everywhere else.
        boolean dataNeedsParty = dataOn && com.killer560.hub.secrets.DungeonState.isInDungeon();
        RelayRoom.Mode mode = (!chatOn || dataNeedsParty) ? RelayRoom.Mode.PARTY : cfg.getRoomMode();
        // Only asks Hypixel for its instance id when Lobby mode could actually use the answer - Party mode
        // never sends /locraw at all.
        HypixelLocation.tick(client, on && mode == RelayRoom.Mode.LOBBY);
        // null (never a wider fallback room) when the chosen mode's room can't be computed yet - RelayClient
        // goes NO_ROOM and stays disconnected instead of guessing. See RelayRoom's class doc.
        String room = on ? RelayRoom.current(mode) : null;
        // Cheap no-op unless one of these three actually changed - all the work happens on the relay's thread.
        RelayClient.update(on, cfg.getRelayUrl(), room);
    }

    /** For {@code /killer560 chat <message>}. @return the line to show the player. */
    public static String send(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return "§c[ModChat] You need to be in a world to send this.";
        }
        ModChatConfig cfg = ModChatConfig.getInstance();
        if (!cfg.isEnabled()) {
            return "§c[ModChat] Mod Chat is off - turn it on in the New tab.";
        }
        if (!RelayEndpoint.isUsable(cfg.getRelayUrl())) {
            return "§c[ModChat] No relay address set - nothing was sent. Set one in the New tab.";
        }
        if (!RelayClient.sendChat(message)) {
            // Never "well, send it over party chat instead" - that is the leak he reported.
            return "§c[ModChat] Not connected to the relay (" + RelayClient.statusText() + ") - nothing was sent.";
        }
        int others = Math.max(0, RelayClient.online().size() - 1);
        return "[ModChat] Sent to " + others + " other mod user" + (others == 1 ? "" : "s") + ".";
    }

    /** @return who else is in your relay room right now, for the settings tab. Never null. */
    public static List<String> online() {
        return RelayClient.online();
    }

    private static boolean isSelf(String name) {
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        return self != null && self.equalsIgnoreCase(name);
    }

    /**
     * Relay callbacks. These arrive on the relay's own threads, so every one of them hops onto the client
     * thread before it touches anything in the game.
     */
    private static final class Listener implements RelayListener {

        @Override
        public void onChat(String from, String message) {
            if (message == null || message.isBlank()) {
                return;
            }
            Minecraft.getInstance().execute(() -> {
                ModChatConfig cfg = ModChatConfig.getInstance();
                if (!cfg.isEnabled()) {
                    return;
                }
                if (cfg.isLogToChat()) {
                    ModChat.send("ModChat", ModChat.value(from), ModChat.text(": " + message));
                }
                // The relay echoes your own line back to you; showing it in the overlay would just wipe the
                // "Sent to N" confirmation a fraction of a second after you read it.
                if (!isSelf(from)) {
                    ModOverlayMessage.show("[ModChat] " + from + ": " + message, 4000);
                }
            });
        }

        @Override
        public void onPresence(String event, String name, List<String> online) {
            if (name == null || name.isBlank()) {
                return;
            }
            Minecraft.getInstance().execute(() -> {
                ModChatConfig cfg = ModChatConfig.getInstance();
                if (!cfg.isEnabled() || !cfg.isPresenceAlerts() || isSelf(name)) {
                    return;
                }
                boolean joined = "join".equals(event == null ? "" : event.toLowerCase(Locale.ROOT));
                ModOverlayMessage.show("[ModChat] " + name + (joined ? " joined" : " left") + " mod chat.", 2500);
            });
        }

        @Override
        public void onConnected(String room, List<String> online) {
            Minecraft.getInstance().execute(() -> {
                ModChatConfig cfg = ModChatConfig.getInstance();
                if (!cfg.isEnabled() || !cfg.isPresenceAlerts()) {
                    return;
                }
                int others = Math.max(0, online.size() - 1);
                ModOverlayMessage.show("[ModChat] Connected - " + others + " other mod user"
                        + (others == 1 ? "" : "s") + " here.", 2500);
            });
        }

        @Override
        public void onDisconnected(String reason) {
            Minecraft.getInstance().execute(() -> {
                if (!ModChatConfig.getInstance().isEnabled()) {
                    return;
                }
                // Exactly one line per outage (RelayClient only calls this once), then silence while it retries.
                ModChat.send("ModChat", ModChat.bad("Relay unavailable"),
                        ModChat.dim(" (" + reason + "). Messages will not send until it is back."));
            });
        }
    }
}
