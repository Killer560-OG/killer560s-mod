package com.killer560.hub.chatcommands;

import com.killer560.hub.chatcommands.ChatCommandsConfig.InfoCommand;
import com.killer560.hub.partycommands.PartyCommandsConfig;
import com.killer560.hub.partycommands.PartyCommandsFeature;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real "!command" chat-reply system, ported from Odin's own {@code ChatCommands.kt} - the informational
 * half of it. Odin's same module also lets any party/guild/private message trigger real party-management
 * commands (kick/promote/demote/transfer/warp); that half was refused here, since on Odin another player's
 * chat text alone can make your client attempt a real party action with no check on who they are. Since
 * 2026-09-16 it exists as {@code com.killer560.hub.partycommands.PartyCommandsFeature} instead, gated so
 * only real party/dungeon teammates can trigger a party-mutating one - see that class for the rules. This
 * file stays the ONE chat listener for both halves: it does the channel matching and sender extraction
 * once, hands every "!" line to Party Commands first, and answers the informational ones itself.
 * <p>
 * Real mechanic: when a real teammate/friend types {@code !coords} (etc.) in real party/guild/private/
 * co-op chat, this replies in that same real channel via the exact real
 * {@code ClientPacketListener#sendCommand} calls this mod's own {@code PosmsgFeature} already uses
 * ("pc "/"gc "/"cc "/"msg name " prefixes, no leading slash).
 * <p>
 * Since 2026-09-21 (Odin parity gap review) this is also where {@link Channel} lives - Party Commands
 * needs it too now that {@code !boop}/{@code !racism} (Odin: "all" channels) and a DM'd {@code !invite}
 * (Odin: invites the sender) can fire from Guild or a DM, not just party chat. Co-op chat is deliberately
 * never passed to Party Commands - Odin has no co-op channel at all, and none of its party-management
 * commands make sense outside a real Hypixel party.
 */
public final class ChatCommandsFeature {

    public enum Channel {
        PARTY, GUILD, PRIVATE, COOP
    }

    private static final Pattern PARTY_REGEX =
            Pattern.compile("^Party > (?:\\[[^]]*?] )?(\\w{1,16})(?: [ቾ⚒])?: ?(.+)$");
    private static final Pattern GUILD_REGEX =
            Pattern.compile("^Guild > (?:\\[[^]]*?] )?(\\w{1,16})(?: \\[[^]]*?])?: ?(.+)$");
    private static final Pattern PRIVATE_REGEX =
            Pattern.compile("^From (?:\\[[^]]*?] )?(\\w{1,16}): ?(.+)$");
    private static final Pattern COOP_REGEX =
            Pattern.compile("^Co-op > (?:\\[[^]]*?] )?(\\w{1,16})(?: [ቾ⚒])?: ?(.+)$");

    /** Same tab-list "Area:"/"Dungeon:" line as {@code routes.SkyblockArea} - see {@code InfoCommand.LOCATION}'s
     *  doc for why this reads it directly instead of calling that class. */
    private static final Pattern AREA_PATTERN = Pattern.compile("^(?:Area|Dungeon):\\s*(.+)$");

    // Duplicated from gui.tab.HomeMainTab.DISCORD_INVITE_URL (private there, and that tab isn't ours to
    // touch) - this mod's own Discord invite, for "!odin"/"!od"/"!killer560"/"!k560".
    private static final String DISCORD_INVITE_URL = "https://discord.gg/hkQMF5fE84";

    private static final String[] EIGHT_BALL_RESPONSES = {
            "It is certain", "It is decidedly so", "Without a doubt",
            "Yes definitely", "You may rely on it", "As I see it, yes",
            "Most likely", "Outlook good", "Yes", "Signs point to yes",
            "Reply hazy try again", "Ask again later", "Better not tell you now",
            "Cannot predict now", "Concentrate and ask again", "Don't count on it",
            "My reply is no", "My sources say no", "Outlook not so good", "Very doubtful"
    };

    // Real safety net beyond Odin's own version: a flat cooldown between any two auto-replies, so a
    // teammate spamming "!coords" can't make this mod flood your own outgoing chat and risk a real
    // Hypixel chat-rate-limit kick.
    private static final long COOLDOWN_MS = 2000;
    private static long lastReplyAtMs = 0;
    // Per-sender floor and a per-minute ceiling on top of the flat gap (2026-09-16 audit): DMs are on by
    // default, so without these ANY player on Hypixel could drive ~30 outgoing lines/min from this account
    // indefinitely with "!ping" spam - enough for Hypixel's own spam auto-mute.
    private static final long PER_SENDER_COOLDOWN_MS = 8_000;
    private static final int MAX_REPLIES_PER_MINUTE = 10;
    private static final java.util.Map<String, Long> LAST_REPLY_BY_SENDER = new java.util.HashMap<>();
    private static final java.util.ArrayDeque<Long> REPLY_TIMES = new java.util.ArrayDeque<>();

    /** Server-tick timestamps for the last {@link #TPS_WINDOW_MS} - "!tps" (Odin parity, 2026-09-21). Fed by
     *  {@link ServerTickClock}, which is already running for Wither Dragons / Tick Timers regardless of
     *  whether either of those is turned on. */
    private static final Deque<Long> TICK_TIMES = new ArrayDeque<>();
    private static final long TPS_WINDOW_MS = 5_000L;

    private ChatCommandsFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Action-bar text is not chat - never feed it to the command / leader parsers.
            if (!overlay) {
                onMessage(message);
            }
        });
        ServerTickClock.register();
        ServerTickClock.subscribe(ChatCommandsFeature::onServerTick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> pruneTicks());
    }

    private static void onMessage(Component message) {
        boolean chatCommands = ChatCommandsConfig.getInstance().isEnabled();
        boolean partyCommands = PartyCommandsConfig.getInstance().isEnabled();
        if (!chatCommands && !partyCommands) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();

        // Only ever reached from the two Fabric events above, i.e. only for lines that really arrived in a
        // chat packet from the server - Party Commands relies on that (see its class doc) and gets every such
        // line, not just the "!" ones, for party-leader tracking and its end-of-run downtime reminder.
        if (partyCommands) {
            PartyCommandsFeature.onServerLine(raw.trim());
        }

        Matcher matcher;
        Channel channel;
        if ((matcher = PARTY_REGEX.matcher(raw)).matches()) {
            channel = Channel.PARTY;
        } else if ((matcher = GUILD_REGEX.matcher(raw)).matches()) {
            channel = Channel.GUILD;
        } else if ((matcher = PRIVATE_REGEX.matcher(raw)).matches()) {
            channel = Channel.PRIVATE;
        } else if ((matcher = COOP_REGEX.matcher(raw)).matches()) {
            channel = Channel.COOP;
        } else {
            return;
        }

        String senderName = matcher.group(1);
        String text = matcher.group(2);
        if (!text.startsWith("!")) {
            return;
        }
        String body = text.substring(1).trim();
        // Party chat, guild chat and DMs all reach Party Commands now (Odin parity: !boop/!racism answer in
        // all three, a DM'd !invite invites the sender) - it decides per-command which channels are actually
        // "meant" to trigger it and still gates every party-mutating one on party chat + real teammate. Co-op
        // never reaches it: Odin has no co-op channel and no party command belongs there.
        if (partyCommands && channel != Channel.COOP && PartyCommandsFeature.handle(senderName, body, channel)) {
            return;
        }
        if (!chatCommands || !channelEnabled(channel)) {
            return;
        }
        handleCommand(body.toLowerCase(Locale.US), senderName, channel);
    }

    private static boolean channelEnabled(Channel channel) {
        ChatCommandsConfig cfg = ChatCommandsConfig.getInstance();
        return switch (channel) {
            case PARTY -> cfg.isPartyEnabled();
            case GUILD -> cfg.isGuildEnabled();
            case PRIVATE -> cfg.isPrivateEnabled();
            case COOP -> cfg.isCoopEnabled();
        };
    }

    private static void handleCommand(String command, String senderName, Channel channel) {
        InfoCommand info = InfoCommand.forTrigger(command);
        if (info == null || !ChatCommandsConfig.getInstance().isOn(info)) {
            return;
        }
        String reply = switch (info) {
            case COORDS -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null) {
                    yield null;
                }
                yield String.format(Locale.US, "%.0f, %.0f, %.0f",
                        client.player.getX(), client.player.getY(), client.player.getZ());
            }
            case PING -> {
                Minecraft client = Minecraft.getInstance();
                if (client.getConnection() == null || client.player == null) {
                    yield null;
                }
                var info2 = client.getConnection().getPlayerInfo(client.player.getUUID());
                yield info2 != null ? "Current Ping: " + info2.getLatency() + "ms" : null;
            }
            case FPS -> "Current FPS: " + Minecraft.getInstance().getFps();
            case TIME -> "Current Time: " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            case HOLDING -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null) {
                    yield null;
                }
                String name = client.player.getMainHandItem().getHoverName().getString();
                String stripped = ChatFormatting.stripFormatting(name);
                yield "Holding: " + (stripped != null ? stripped : name);
            }
            case COINFLIP -> Math.random() < 0.5 ? "heads" : "tails";
            case EIGHT_BALL -> EIGHT_BALL_RESPONSES[(int) (Math.random() * EIGHT_BALL_RESPONSES.length)];
            case DICE -> String.valueOf(1 + (int) (Math.random() * 6));
            case TPS -> tpsReply();
            case LOCATION -> locationReply();
            case DISCORD -> "killer560's Mod Discord: " + DISCORD_INVITE_URL;
        };
        if (reply == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyAtMs < COOLDOWN_MS) {
            return;
        }
        String senderKey = senderName.toLowerCase(Locale.ROOT);
        Long lastForSender = LAST_REPLY_BY_SENDER.get(senderKey);
        if (lastForSender != null && now - lastForSender < PER_SENDER_COOLDOWN_MS) {
            return;
        }
        while (!REPLY_TIMES.isEmpty() && now - REPLY_TIMES.peekFirst() > 60_000L) {
            REPLY_TIMES.pollFirst();
        }
        if (REPLY_TIMES.size() >= MAX_REPLIES_PER_MINUTE) {
            return;
        }
        REPLY_TIMES.addLast(now);
        LAST_REPLY_BY_SENDER.put(senderKey, now);
        if (LAST_REPLY_BY_SENDER.size() > 256) {
            LAST_REPLY_BY_SENDER.values().removeIf(t -> now - t > PER_SENDER_COOLDOWN_MS);
        }
        lastReplyAtMs = now;
        sendToChannel(reply, senderName, channel);
    }

    // ------------------------------------------------------------------ !tps (Odin parity, 2026-09-21)

    private static void onServerTick() {
        TICK_TIMES.addLast(System.currentTimeMillis());
        // Bounded independently of pruneTicks() below - a sudden ping burst after a stall must never grow
        // this without limit while nothing is polling the client tick (there always is, but belt and braces).
        while (TICK_TIMES.size() > 200) {
            TICK_TIMES.pollFirst();
        }
    }

    private static void pruneTicks() {
        long now = System.currentTimeMillis();
        while (!TICK_TIMES.isEmpty() && now - TICK_TIMES.peekFirst() > TPS_WINDOW_MS) {
            TICK_TIMES.pollFirst();
        }
    }

    /** Same idea as {@code witherdragons.ServerTickClock}'s own ping-vs-client-tick split: only ever answer
     *  with a real number while the server is actually driving the clock via its own pings, otherwise say so
     *  instead of printing a client-tick-derived number that isn't really the server's TPS. */
    private static String tpsReply() {
        if (!ServerTickClock.isPingDriven() || TICK_TIMES.size() < 2) {
            return "TPS: Unknown (server isn't ping-driven right now)";
        }
        long spanMs = TICK_TIMES.peekLast() - TICK_TIMES.peekFirst();
        if (spanMs <= 0) {
            return "TPS: Unknown";
        }
        double tps = Math.min(20.0, (TICK_TIMES.size() - 1) * 1000.0 / spanMs);
        return String.format(Locale.US, "TPS: %.1f", tps);
    }

    // ------------------------------------------------------------------ !location (Odin parity, 2026-09-21)

    private static String locationReply() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return null;
        }
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = AREA_PATTERN.matcher(plain.trim());
            if (m.matches()) {
                return "Location: " + m.group(1).trim();
            }
        }
        return null;
    }

    private static void sendToChannel(String message, String senderName, Channel channel) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        String command = switch (channel) {
            case PARTY -> "pc " + message;
            case GUILD -> "gc " + message;
            case COOP -> "cc " + message;
            case PRIVATE -> "msg " + senderName + " " + message;
        };
        client.player.connection.sendCommand(command);
    }
}
