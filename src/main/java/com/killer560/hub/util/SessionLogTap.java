package com.killer560.hub.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/**
 * The session's mod log kept in memory: the INFO, WARN and ERROR lines of the mod's own loggers since the game started, the
 * newest {@value #MAX_LINES} of them and at most {@value #MAX_CHARS} characters. {@link ModLog} tees every logger here, always
 * (bugreportmenu-1122: a release jar drops INFO and WARN from latest.log, so a bug report had almost nothing from the mod).
 * Nothing is written to disk or sent anywhere from here: only a bug report the player makes ({@code bugreport/}, its
 * mod-log.txt) or a ban report while Auto Report Bans is ON ({@code banreport/BanReportFeature}) reads it.
 *
 * <p>Pure Java, no Minecraft class, because ModLog is loaded long before the game.
 *
 * <p>Loggers that carry other players' words (chat, Mod Chat, the bridges, party and friend tracking) are never kept:
 * a report must never hold another player's chat. Names in the lines that are kept are scrubbed when a report is built.
 */
public final class SessionLogTap {

    public static final int MAX_LINES = 4000;
    public static final int MAX_LINE_CHARS = 600;
    /** About 1.5 MB of text at worst, whatever the line lengths. */
    public static final int MAX_CHARS = 1_500_000;

    /** Never kept: their lines quote other players' chat, names or messages. */
    static final Set<String> SKIP = Set.of(
            "killer560smod-modchat", "killer560smod-relay", "killer560smod-bridge", "killer560smod-chatobserver",
            "killer560smod-copychat", "killer560smod-chattidy", "killer560smod-translate", "killer560smod-clicktranslate",
            "killer560smod-partycommands", "killer560smod-partydata", "killer560smod-partyfinder", "killer560smod-lfg",
            "killer560smod-players", "killer560smod-bestfriends", "killer560smod-friendslist-sync", "killer560smod-modusers",
            "killer560smod-supporters", "killer560smod-imagepreview", "killer560smod-voicetotext", "killer560smod-automeow",
            "killer560smod-quizlog", "killer560smod-spotify", "killer560smod-leapmessage", "killer560smod-sharedrecords",
            "killer560smod-profileviewer", "killer560smod-playerstats", "killer560smod-autokick", "killer560smod-fiestaflask");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static int chars;

    private SessionLogTap() {
    }

    /** True when a line from {@code logger} is kept. */
    public static boolean wants(String logger) { return logger != null && !SKIP.contains(logger); }

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
            LINES.addLast(line);
            chars += line.length();
            while (LINES.size() > MAX_LINES || chars > MAX_CHARS) {
                chars -= LINES.removeFirst().length();
            }
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
            chars = 0;
        }
    }
}
