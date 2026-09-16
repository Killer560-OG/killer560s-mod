package com.killer560.hub.chatcommands;

import com.killer560.hub.partycommands.PartyCommandsConfig;
import com.killer560.hub.partycommands.PartyCommandsFeature;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real "!command" chat-reply system, ported from Odin's own {@code ChatCommands.kt} - the informational
 * half of it. Odin's same module also lets any party/guild/private message trigger real party-management
 * commands (kick/promote/demote/transfer/warp); that half was refused here, since on Odin another player's
 * chat text alone can make your client attempt a real party action with no check on who they are. Since
 * 2026-09-16 it exists as {@code com.killer560.hub.partycommands.PartyCommandsFeature} instead, gated so
 * only real party/dungeon teammates can trigger anything - see that class for the rules. This file stays
 * the ONE chat listener for both halves: it does the channel matching and sender extraction once, hands
 * real party-chat "!" lines to Party Commands first, and answers the informational ones itself.
 * Also dropped "tps"/"location" (this codebase has no already-verified real way to read either) rather
 * than guess.
 * <p>
 * Real mechanic: when a real teammate/friend types {@code !coords} (etc.) in real party/guild/private/
 * co-op chat, this replies in that same real channel via the exact real
 * {@code ClientPacketListener#sendCommand} calls this mod's own {@code PosmsgFeature} already uses
 * ("pc "/"gc "/"cc "/"msg name " prefixes, no leading slash).
 */
public final class ChatCommandsFeature {

    private enum Channel {
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

    private ChatCommandsFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message));
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
        // Party channel only: a guild member or a DM is not a teammate, so party commands never see them.
        if (partyCommands && channel == Channel.PARTY && PartyCommandsFeature.handle(senderName, body)) {
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
        String reply = switch (command) {
            case "coords", "co" -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null) {
                    yield null;
                }
                yield String.format(Locale.US, "%.0f, %.0f, %.0f",
                        client.player.getX(), client.player.getY(), client.player.getZ());
            }
            case "ping" -> {
                Minecraft client = Minecraft.getInstance();
                if (client.getConnection() == null || client.player == null) {
                    yield null;
                }
                var info = client.getConnection().getPlayerInfo(client.player.getUUID());
                yield info != null ? "Current Ping: " + info.getLatency() + "ms" : null;
            }
            case "fps" -> "Current FPS: " + Minecraft.getInstance().getFps();
            case "time" -> "Current Time: " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            case "holding" -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player == null) {
                    yield null;
                }
                String name = client.player.getMainHandItem().getHoverName().getString();
                String stripped = ChatFormatting.stripFormatting(name);
                yield "Holding: " + (stripped != null ? stripped : name);
            }
            case "cf", "coinflip" -> Math.random() < 0.5 ? "heads" : "tails";
            case "8ball" -> EIGHT_BALL_RESPONSES[(int) (Math.random() * EIGHT_BALL_RESPONSES.length)];
            case "dice" -> String.valueOf(1 + (int) (Math.random() * 6));
            default -> null;
        };
        if (reply == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyAtMs < COOLDOWN_MS) {
            return;
        }
        lastReplyAtMs = now;
        sendToChannel(reply, senderName, channel);
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
