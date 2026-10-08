package com.killer560.hub.modchat;

import com.killer560.hub.util.FeatureGuard;
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
 * <b>Sending, 2026-10-08.</b> killer560: <i>"make stuff like /chat k or /kc to send the message in my chat or to
 * enter my chat's channel (just like /chat p or /gc). Remove the /killer560 chat command."</i> {@code /kc <message>}
 * sends one line and {@code /chat k} makes plain typed chat go here - both live in {@link ModChatChannel}, which
 * intercepts only those {@code /chat} arguments and lets every other {@code /chat ...} through to Hypixel untouched
 * ({@code /chat} itself stays Hypixel's command; it is never registered client-side).
 * <p>
 * Presence notices ("X joined / left mod chat", "Connected - N others here") were removed the same day, with their
 * setting. Received lines print into chat by default ({@link ModChatConfig#isLogToChat}).
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
        ModChatChannel.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ModChatFeature.onTick", ModChatFeature::onTick));
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
        // Crystal Hollows structure sharing (which forced a LOBBY room here) is shelved until after 2.0 - see
        // shelved/mining/README.md. A "ch.v1.find" data packet from an older client still in our room reaches
        // only listeners that switch on their own keys, so it is ignored.
        boolean on = (chatOn || dataOn)
                && client.getConnection() != null && client.player != null;
        // Party dungeon data only travels in a party room. In a dungeon the lobby IS the party (the instance only holds
        // your team), so while sharing is on, a dungeon always uses the party room even if Mod Chat is set to Lobby -
        // otherwise choosing Lobby would silently stop data sharing. Mod Chat keeps its chosen mode everywhere else.
        boolean dataNeedsParty = dataOn && com.killer560.hub.secrets.DungeonState.isInDungeon();
        RelayRoom.Mode mode;
        if (dataNeedsParty) {
            mode = RelayRoom.Mode.PARTY;
        } else {
            mode = chatOn ? cfg.getRoomMode() : RelayRoom.Mode.PARTY;
        }
        // Only asks Hypixel for its instance id when Lobby mode could actually use the answer - Party mode
        // never sends /locraw at all.
        HypixelLocation.tick(client, on && mode == RelayRoom.Mode.LOBBY);
        // null (never a wider fallback room) when the chosen mode's room can't be computed yet - RelayClient
        // goes NO_ROOM and stays disconnected instead of guessing. See RelayRoom's class doc.
        String room = on ? RelayRoom.current(mode) : null;
        // Cheap no-op unless one of these three actually changed - all the work happens on the relay's thread.
        RelayClient.update(on, cfg.getRelayUrl(), room);
    }

    /** For {@code /kc <message>} and plain chat while the channel is Mod Chat ({@link ModChatChannel}).
     *  @return the line to show the player: a "[ModChat] ..." confirmation, or a red one when nothing was sent. */
    public static String send(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return "§c[ModChat] You need to be in a world to send this.";
        }
        ModChatConfig cfg = ModChatConfig.getInstance();
        if (!cfg.isEnabled()) {
            return "§c[ModChat] Mod Chat is off - turn it on in its settings tab.";
        }
        if (!RelayEndpoint.isUsable(cfg.getRelayUrl())) {
            // Should never actually happen - the relay is live and its address is hardcoded in
            // RelayEndpoint.DEFAULT_BASE_URL, not something the player sets. Defensive only (e.g. a
            // corrupted build), so this doesn't send them looking for a settings field that doesn't exist.
            return "§c[ModChat] Relay address invalid - nothing was sent. This is a bug, not a setting to fix.";
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
                    // The relay echoes your own line back; in chat that echo IS the record of what you said, the
                    // same way Hypixel shows your own party chat line.
                    ModChat.send("ModChat", ModChat.value(from), ModChat.text(": " + message));
                } else if (!isSelf(from)) {
                    // Overlay only: showing your own echo there would wipe the "Sent to N" line you just got.
                    ModOverlayMessage.show("[ModChat] " + from + ": " + message, 4000);
                }
                // Mod Chat is one of Party Commands' channels ("kc"). The sender name was stamped by the relay from
                // a Mojang-verified login, never taken from the message text.
                com.killer560.hub.partycommands.PartyCommandsFeature.onModChat(from, message);
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
