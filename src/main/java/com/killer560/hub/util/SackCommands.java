package com.killer560.hub.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one place an automatic {@code /gfs} (get from sacks) is sent from: Auto GFS and Architect's First Draft's Auto Get.
 * <p>
 * killer560 (2026-10-15) sent a screenshot of an F7 run on p3sim.net with chat full of "Unknown or incomplete command.
 * See below for error / gfs ender_pearl 16&lt;--[HERE]", several a second. That spam was another mod's Auto GFS (his
 * killer560smod Auto GFS was off and the log has no "Auto GFS:" line), but ours had the same hole: it gated on
 * "Hypixel or p3sim", never read the reply, and asked again every check interval for as long as the pearls failed to
 * arrive. So every sender here: sends only on Hypixel Skyblock (p3sim has no sacks), one command at a time, waits for
 * the answer ({@link #awaiting}) before anything else may be sent, and on an unknown-command reply stops for the rest
 * of the connection and says so once.
 */
public final class SackCommands {

    private static final Logger LOGGER = ModLog.get("killer560smod-sackcommands");

    /** How long a sent {@code /gfs} counts as unanswered before a sender may try again. */
    public static final long REPLY_TIMEOUT_MS = 5000;

    /**
     * Vanilla's unknown-command reply ({@code command.unknown.command}), with the "&lt;--[HERE]" context either in the same
     * message (p3sim, 2026-10-15 log: one line joined by a newline) or as its own message (vanilla sends two). A system
     * line with no prefix, so another player's chat ("[VIP] Name: Unknown ...") can never match it.
     */
    private static final Pattern UNKNOWN_COMMAND =
            Pattern.compile("^Unknown or incomplete command\\. See below for error(?:\\n[^\\n]{0,256})?$");

    /** The same reply as a translatable component (vanilla servers), whatever language the client renders it in. */
    private static final String UNKNOWN_KEY = "command.unknown.command";

    private static Object session;
    private static boolean dead;
    private static long sentAtMs;
    private static boolean awaiting;

    private SackCommands() {
    }

    public static void register() {
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message),
                message.getContents() instanceof TranslatableContents tc && UNKNOWN_KEY.equals(tc.getKey())));
    }

    /** True only on Hypixel Skyblock, on a connection where {@code /gfs} has not been answered "unknown command". */
    public static boolean available(Minecraft client) {
        rollSession(client);
        return !dead && isHypixel(client) && SkyblockGate.isOnSkyblock();
    }

    /** A {@code /gfs} was sent and neither answered nor timed out yet; nothing else may be sent meanwhile. */
    public static boolean awaiting() {
        if (awaiting && System.currentTimeMillis() - sentAtMs >= REPLY_TIMEOUT_MS) {
            awaiting = false;
        }
        return awaiting;
    }

    /**
     * Send {@code /gfs <args>} if {@link #available} and nothing is {@link #awaiting}.
     * @return whether it was sent
     */
    public static boolean send(Minecraft client, String args) {
        if (client.player == null || !available(client) || awaiting()) {
            return false;
        }
        client.player.connection.sendCommand("gfs " + args);
        sentAtMs = System.currentTimeMillis();
        awaiting = true;
        return true;
    }

    /** The sender saw its answer (the items arrived, or Hypixel said the sack is empty). */
    public static void answered() {
        awaiting = false;
    }

    static void onChat(String plain, boolean unknownKey) {
        if (plain == null || !awaiting || !(unknownKey || UNKNOWN_COMMAND.matcher(plain).matches())) {
            return;
        }
        awaiting = false;
        if (!dead) {
            dead = true;
            LOGGER.warn("[SackCommands] /gfs is an unknown command on this server - no more automatic /gfs until you join another server");
            ModChat.send("Sacks", ModChat.text("This server has no "), ModChat.value("/gfs"),
                    ModChat.text(" - automatic /gfs stopped until you join another server."));
        }
    }

    private static void rollSession(Minecraft client) {
        Object now = client.getConnection();
        if (now != session) {
            session = now;
            dead = false;
            awaiting = false;
        }
    }

    private static boolean isHypixel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) {
            return false;
        }
        String ip = server.ip.toLowerCase(Locale.ROOT);
        if (ip.contains("hypixel.net")) {
            return true;
        }
        // Dev and testkit builds only: a local server stands in for Hypixel (same rule as CheatUtils.isOnDungeonServer).
        return com.killer560.hub.BuildVariant.DEV_TOOLS
                && (ip.startsWith("localhost") || ip.startsWith("127.0.0.1") || ip.startsWith("[::1]"));
    }
}
