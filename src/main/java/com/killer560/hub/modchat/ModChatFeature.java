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
 * client channel, so messages now go over the mod's own relay ({@code killer560s-mod-relay}, our own server) and never touch Hypixel at all.
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
        if (++tickCounter < TICKS_BETWEEN_CHECKS) return;
        tickCounter = 0;
        ModChatConfig cfg = ModChatConfig.getInstance();
        // The relay also carries party dungeon data (hub/partydata, Team Melody), which is ON by default and must
        // not depend on Mod Chat being on. Without Mod Chat, data always uses the party room - never a lobby room.
        boolean chatOn = cfg.isEnabled();
        // Team Melody needs the party room whatever the other data switches say (killer560, 2026-11-01).
        boolean dataOn = (com.killer560.hub.interop.InteropConfig.getInstance().isEnabled()
                && com.killer560.hub.interop.InteropConfig.getInstance().isRelayData()
                && com.killer560.hub.partydata.PartyDataConfig.getInstance().isShareEnabled())
                || com.killer560.hub.melody.MelodyHudConfig.getInstance().isShareProgress();
        // Crystal Hollows structure sharing ("ch.v1.find", the Crystal Hollows Map's sharing) needs a LOBBY room: a find
        // only means anything to someone on the same server, since Hypixel lays that world out differently on each.
        boolean chNeedsLobby = com.killer560.hub.mining.chmap.ChShare.wantsLobbyRoom();
        // Song Tag (killer560, 2026-11-01: "they arent being shared currently. I tested with another one of my accounts
        // and another persons"): its packets ride the relay, but nothing here ever connected for it, so with Mod Chat
        // off and no party the socket stayed shut and every song went nowhere; even partied, the two players had to be
        // in one Hypixel party. The line is drawn over players within 16 blocks, which is the LOBBY: so Share My Song or
        // Show Others' Songs on now keeps the relay up, in the lobby room when Mod Chat does not choose one.
        boolean songsOn = songTagWantsRelay();
        boolean on = (chatOn || dataOn || songsOn || chNeedsLobby)
                && client.getConnection() != null && client.player != null;
        // Party dungeon data only travels in a party room. In a dungeon the lobby IS the party (the instance only holds
        // your team), so while sharing is on, a dungeon always uses the party room even if Mod Chat is set to Lobby -
        // otherwise choosing Lobby would silently stop data sharing. Mod Chat keeps its chosen mode everywhere else.
        boolean dataNeedsParty = dataOn && com.killer560.hub.secrets.DungeonState.isInDungeon();
        RelayRoom.Mode mode;
        boolean held = false;
        if (dataNeedsParty) {
            mode = RelayRoom.Mode.PARTY;
        } else if (chNeedsLobby) {
            // Crystal Hollows sharing only means anything in the lobby room (nobody is in a dungeon and the Hollows).
            mode = RelayRoom.Mode.LOBBY;
        } else if (chatOn) {
            mode = cfg.getRoomMode();
            // Mod Chat is ON by default in Party mode, so with no party it chose no room and Song Tag never connected
            // (killer560, 2026-11-04: "i still cant see other peoples song"): Song Tag then takes the lobby room, and
            // Mod Chat neither sends nor shows a line there (chatHeld), so it still only reaches the party he chose.
            held = songsOn && mode == RelayRoom.Mode.PARTY && RelayRoom.current(mode) == null;
            if (held) {
                mode = RelayRoom.Mode.LOBBY;
            }
        } else {
            mode = songsOn ? RelayRoom.Mode.LOBBY : RelayRoom.Mode.PARTY;
        }
        chatHeld = held;
        lastMode = mode;
        // Only asks Hypixel for its instance id when Lobby mode could actually use the answer - Party mode
        // never sends /locraw at all.
        HypixelLocation.tick(client, on && mode == RelayRoom.Mode.LOBBY);
        // null (never a wider fallback room) when the chosen mode's room can't be computed yet - RelayClient
        // goes NO_ROOM and stays disconnected instead of guessing. See RelayRoom's class doc.
        String room = on ? RelayRoom.current(mode) : null;
        // Cheap no-op unless one of these three actually changed - all the work happens on the relay's thread.
        RelayClient.update(on, cfg.getRelayUrl(), room);
    }

    /** Test hook: the room mode the last check chose. */
    private static volatile RelayRoom.Mode lastMode;
    /** The relay is in the lobby room only for Song Tag while Mod Chat is set to Party: no chat in or out. */
    private static volatile boolean chatHeld;

    /** Song Tag needs the relay: you share your song, or you want to see other players'. */
    static boolean songTagWantsRelay() {
        com.killer560.hub.spotify.SongShareConfig tag = com.killer560.hub.spotify.SongShareConfig.getInstance();
        return tag.isShareSong() || tag.isShowOthers();
    }

    /** For {@code /kc <message>} and plain chat while the channel is Mod Chat ({@link ModChatChannel}).
     *  @return the line to show the player: a "[ModChat] ..." confirmation, or a red one when nothing was sent. */
    public static String send(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return "§c[ModChat] You need to be in a world to send this.";
        ModChatConfig cfg = ModChatConfig.getInstance();
        if (!cfg.isEnabled()) return "§c[ModChat] Mod Chat is off - turn it on in its settings tab.";
        if (!RelayEndpoint.isUsable(cfg.getRelayUrl())) {
            // Should never actually happen - the relay is live and its address is hardcoded in
            // RelayEndpoint.DEFAULT_BASE_URL, not something the player sets. Defensive only (e.g. a
            // corrupted build), so this doesn't send them looking for a settings field that doesn't exist.
            return "§c[ModChat] Relay address invalid - nothing was sent. This is a bug, not a setting to fix.";
        }
        if (chatHeld) {
            return "§c[ModChat] Mod Chat is set to Party and you are not in a party - nothing was sent.";
        }
        if (!RelayClient.sendChat(message)) {
            // Never "well, send it over party chat instead" - that is the leak he reported.
            return "§c[ModChat] Not connected to the relay (" + RelayClient.statusText() + ") - nothing was sent.";
        }
        int others = Math.max(0, RelayClient.online().size() - 1);
        return "[ModChat] Sent to " + others + " other mod user" + (others == 1 ? "" : "s") + ".";
    }

    /** Marks a whisper inside a Mod Chat line: a control character no typed chat contains, then the target, then the text. */
    static final String WHISPER_PREFIX = "\u001EWHISPER\u001F";
    private static final String WHISPER_SEP = "\u001F";
    private static final java.util.regex.Pattern WHISPER_TARGET = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** {@code /kc msg <name> <text>}: a Mod Chat line only that player shows. Every mod user's relay gets it; only the named
     *  player (and the sender) display it, so it is hidden in the game but not encrypted. */
    public static String whisper(String target, String text) {
        if (target == null || !WHISPER_TARGET.matcher(target).matches()) return "§c[ModChat] That is not a player name.";
        if (text == null || text.isBlank()) return "§c[ModChat] Nothing to whisper.";
        String line = WHISPER_PREFIX + target + WHISPER_SEP + text.trim();
        return line.length() > 512 ? "§c[ModChat] Too long - 512 characters at most." : send(line);
    }

    /** The relay stamps the sender from a verified login, but a name is still checked before it is shown or acted on. */
    private static final java.util.regex.Pattern SENDER = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** Longest received line kept; the sending side caps at 512 too (RelayClient.MAX_CHAT_CHARS). */
    private static final int MAX_RECEIVED_CHARS = 512;

    /** Received text made safe to print: capped, and without section signs or control characters (formatting injection,
     *  fake line breaks). */
    static String clean(String s, int max) {
        if (s == null) return "";
        int n = Math.min(s.length(), max);
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F && c != '§') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Shows a received whisper if it is to or from this player; any other whisper is dropped on the floor. */
    private static void handleWhisper(String from, String message) {
        String body = message.substring(WHISPER_PREFIX.length());
        int sep = body.indexOf(WHISPER_SEP);
        if (sep <= 0 || from == null) return;
        String target = body.substring(0, sep);
        String text = clean(body.substring(sep + 1), MAX_RECEIVED_CHARS);
        if (!WHISPER_TARGET.matcher(target).matches() || text.isBlank()) return;
        if (isSelf(from)) {
            ModChat.send("ModChat", ModChat.dim("To " + target + ": "), ModChat.text(text));
        } else if (isSelf(target)) {
            ModChat.send("ModChat", ModChat.dim("Whisper from "), ModChat.value(from), ModChat.text(": " + text));
            com.killer560.hub.notify.ModOverlayMessage.show("[ModChat] whisper from " + from + ": " + text, 4000);
        }
    }

    /** @return who else is in your relay room right now, for the settings tab. Never null. */
    public static List<String> online() { return RelayClient.online(); }

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
        public void onChat(String from, String rawMessage) {
            if (rawMessage == null || rawMessage.isBlank() || from == null || !SENDER.matcher(from).matches()) return;
            // Hostile input: bounded, and stripped of section signs / control characters so a sender cannot color or
            // fake chat lines. The whisper marker (a control character) is tested on the raw text first.
            boolean whisper = rawMessage.startsWith(WHISPER_PREFIX);
            Minecraft.getInstance().execute(() -> {
                ModChatConfig cfg = ModChatConfig.getInstance();
                if (!cfg.isEnabled() || chatHeld) return;
                if (whisper) {
                    handleWhisper(from, rawMessage);
                    return; // a whisper is never shown to anyone else, and never reaches the party or meow listeners
                }
                String message = clean(rawMessage, MAX_RECEIVED_CHARS);
                if (message.isBlank()) return;
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
                // Auto Meow answers a Mod Chat meow in Mod Chat, from the same relay-verified sender name.
                com.killer560.hub.automeow.AutoMeowFeature.onModChat(from, message);
            });
        }

        @Override
        public void onDisconnected(String reason) {
            Minecraft.getInstance().execute(() -> {
                if (!ModChatConfig.getInstance().isEnabled()) return;
                // Exactly one line per outage (RelayClient only calls this once), then silence while it retries.
                ModChat.send("ModChat", ModChat.bad("Relay unavailable"),
                        ModChat.dim(" (" + clean(reason, 120) + "). Messages will not send until it is back."));
            });
        }
    }
}
