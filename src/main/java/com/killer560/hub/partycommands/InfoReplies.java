package com.killer560.hub.partycommands;

import com.killer560.hub.partycommands.PartyCommandsConfig.Command;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text answers of the informational commands (!coords, !ping, !fps, !time, !holding, !cf, !8ball, !dice, !tps,
 * !location, !odin) - Odin's {@code ChatCommands.kt} replies, formerly the separate Chat Commands feature
 * ({@code chatcommands/}, folded into Party Commands' one list on 2026-10-08). Only builds the line; where and when it
 * is sent is {@link PartyCommandsFeature}'s job.
 */
final class InfoReplies {

    /** Same tab-list "Area:"/"Dungeon:" line as {@code routes.SkyblockArea}. */
    private static final Pattern AREA_PATTERN = Pattern.compile("^(?:Area|Dungeon):\\s*(.{1,64})$");

    /** This mod's own Discord invite, for "!odin"/"!od"/"!killer560"/"!k560" (same one README.md and HomeMainTab carry). */
    private static final String DISCORD_INVITE_URL = "https://discord.gg/hkQMF5fE84";

    private static final String[] EIGHT_BALL_RESPONSES = {
            "It is certain", "It is decidedly so", "Without a doubt",
            "Yes definitely", "You may rely on it", "As I see it, yes",
            "Most likely", "Outlook good", "Yes", "Signs point to yes",
            "Reply hazy try again", "Ask again later", "Better not tell you now",
            "Cannot predict now", "Concentrate and ask again", "Don't count on it",
            "My reply is no", "My sources say no", "Outlook not so good", "Very doubtful"
    };

    /** Server-tick timestamps for the last {@link #TPS_WINDOW_MS}, fed by {@link ServerTickClock}. */
    private static final Deque<Long> TICK_TIMES = new ArrayDeque<>();
    static final long TPS_WINDOW_MS = 5_000L;

    private InfoReplies() {
    }

    static void register() {
        ServerTickClock.register();
        ServerTickClock.subscribe(InfoReplies::onServerTick);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PartyCommands.InfoReplies", client -> pruneTicks()));
    }

    /** The line to post for an informational command, or null when there is nothing honest to say. */
    static String reply(Command command) {
        Minecraft client = Minecraft.getInstance();
        return switch (command) {
            case COORDS -> client.player == null ? null : String.format(Locale.US, "%.0f, %.0f, %.0f",
                    client.player.getX(), client.player.getY(), client.player.getZ());
            case PING -> {
                if (client.getConnection() == null || client.player == null) {
                    yield null;
                }
                PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
                yield info != null ? "Current Ping: " + info.getLatency() + "ms" : null;
            }
            case FPS -> "Current FPS: " + client.getFps();
            case TIME -> "Current Time: " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            case HOLDING -> {
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
            case LOCATION -> locationReply(client);
            case DISCORD -> "killer560's Mod Discord: " + DISCORD_INVITE_URL;
            default -> null;
        };
    }

    private static void onServerTick() {
        TICK_TIMES.addLast(System.currentTimeMillis());
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

    /** Only a real number while the server drives the clock with its own pings; otherwise says so. */
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

    private static String locationReply(Minecraft client) {
        if (client.getConnection() == null) {
            return null;
        }
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = com.killer560.hub.util.TabText.plain(info);
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
}
