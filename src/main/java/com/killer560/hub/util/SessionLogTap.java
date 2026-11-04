package com.killer560.hub.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/**
 * The session's mod log kept in memory for Auto Report Bans ({@code banreport/BanReportFeature}): the INFO, WARN and ERROR
 * lines of the mod's own loggers since the game started, the newest {@value #MAX_LINES} of them. {@link ModLog} tees every
 * logger here while {@link #active()}, which is only while the player has Auto Report Bans ON; off, nothing is kept.
 *
 * <p>It is a copy, not the game log: a release jar drops INFO and WARN from latest.log ({@link ModLog}), and those are the
 * lines a ban report needs (server corrections, what each automation did). Pure Java, no Minecraft class, because ModLog
 * is loaded long before the game.
 *
 * <p>Loggers that carry other players' words (chat, Mod Chat, the bridges, party and friend tracking) are never kept:
 * a report must never hold another player's chat. Names in the lines that are kept are scrubbed when the report is built.
 */
public final class SessionLogTap {

    public static final int MAX_LINES = 5000;
    public static final int MAX_LINE_CHARS = 600;

    /** Never kept: their lines quote other players' chat, names or messages. */
    static final Set<String> SKIP = Set.of(
            "killer560smod-modchat", "killer560smod-relay", "killer560smod-bridge", "killer560smod-chatobserver",
            "killer560smod-copychat", "killer560smod-chattidy", "killer560smod-translate", "killer560smod-clicktranslate",
            "killer560smod-partycommands", "killer560smod-partydata", "killer560smod-partyfinder", "killer560smod-lfg",
            "killer560smod-players", "killer560smod-bestfriends", "killer560smod-friendslist-sync", "killer560smod-modusers",
            "killer560smod-supporters", "killer560smod-imagepreview", "killer560smod-voicetotext", "killer560smod-automeow",
            "killer560smod-quizlog", "killer560smod-spotify", "killer560smod-leapmessage", "killer560smod-sharedrecords",
            "killer560smod-profileviewer", "killer560smod-playerstats", "killer560smod-autokick");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static volatile boolean active;

    private SessionLogTap() {
    }

    public static boolean active() { return active; }

    public static void setActive(boolean on) { active = on; }

    /** True when a line from {@code logger} would be kept right now. */
    public static boolean wants(String logger) { return active && logger != null && !SKIP.contains(logger); }

    public static void record(String logger, String level, String message, Throwable thrown) {
        if (!wants(logger)) return;
        String text = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        if (thrown != null) {
            text += " | " + thrown.getClass().getName() + ": " + thrown.getMessage();
        }
        if (text.length() > MAX_LINE_CHARS) {
            text = text.substring(0, MAX_LINE_CHARS) + "...";
        }
        String line = "[" + LocalTime.now().format(TIME) + "] [" + level + "] [" + logger + "] " + text;
        synchronized (LINES) {
            if (LINES.size() >= MAX_LINES) {
                LINES.removeFirst();
            }
            LINES.addLast(line);
        }
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return List.copyOf(LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
