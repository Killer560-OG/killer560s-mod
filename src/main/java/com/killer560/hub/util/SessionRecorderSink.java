package com.killer560.hub.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * The file side of the Session Recorder ({@code sessionrecorder/SessionRecorder}): one open session file, the lines
 * written to it, and the 20 MB cap. Pure Java with no Minecraft class, because {@link ModLog} calls it from every
 * recorded logger and ModLog is loaded long before the game is.
 *
 * <p>killer560 (2026-10-19) wanted a way to record what the features do while he plays, so he can send the data back.
 * Nothing here acts on the game: lines come from the INFO/WARN/ERROR calls the features already make (ModLog tees
 * the loggers in {@link #SOURCES}), so a recorded line is word for word the log line, plus the time, the client tick
 * and the gap in ticks since that feature's previous line.
 *
 * <p>Line shape (space separated, one event per line, grep-able):
 * <pre>14:03:22.418 t=48211 +3 autoroutes AutoRoutes Landed in node #4 (WARP) from #3 - firing it</pre>
 * wall-clock time, client tick, ticks since this feature's last line ({@code +-} for its first), feature, event tag
 * (the message's leading {@code [Tag]}, or {@code -}), then the message with any WARN/ERROR level in front of it.
 *
 * <p>Thread-safe: features log from the render thread, the Netty thread and the integrated server thread alike.
 */
public final class SessionRecorderSink {

    /** killer560's rule: a session file never grows past 20 MB; one last line says it was cut. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    /** Room kept under {@link #MAX_BYTES} for the cut line itself. */
    private static final long CUT_RESERVE = 512;

    /**
     * Logger name -> feature label. Only these loggers are recorded: the automations and helpers he asked about (Auto
     * Routes, Auto Puzzles, Breaker and Breaker Aura, the auras and Arrow Align, Blood Camp, the map) plus the few
     * they report through (corrections, ModChat.log's progress lines, the warp helpers).
     */
    static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("killer560smod-autoroutes", "autoroutes"),
            Map.entry("killer560smod/autoroutes", "autoroutes"),
            Map.entry("killer560smod-etherwarp", "etherwarp"),
            Map.entry("killer560smod-autopuzzles", "autopuzzles"),
            Map.entry("killer560smod-puzzles", "puzzles"),
            Map.entry("killer560smod-puzzlesolvers", "puzzles"),
            Map.entry("killer560smod-dungeonbreaker", "breaker"),
            Map.entry("killer560smod-dungeonextras", "breakeraura"),
            Map.entry("killer560smod-breakeraura", "breakeraura"),
            Map.entry("killer560smod/ArrowAlign", "arrowalign"),
            Map.entry("killer560smod-autosecret", "secretaura"),
            Map.entry("killer560smod-leveraura", "leveraura"),
            Map.entry("killer560smod-terminalaura", "terminalaura"),
            Map.entry("killer560smod-bloodcamp", "bloodcamp"),
            Map.entry("killer560smod-livemap", "map"),
            Map.entry("killer560smod-mapping", "map"),
            Map.entry("killer560smod-interactivemap", "autoclear"),
            Map.entry("killer560smod-autoclear", "autoclear"),
            Map.entry("killer560smod-corrections", "corrections"),
            Map.entry("killer560smod-chat", "chat")
    );

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static volatile boolean active;
    private static volatile long tick;

    private static Writer out;
    private static Path file;
    private static long bytes;
    private static long lines;
    private static boolean cut;
    private static boolean dirty;
    private static final Map<String, Long> LAST_TICK = new HashMap<>();

    private SessionRecorderSink() {
    }

    /** The feature label for a logger name, or null when that logger is not recorded. */
    public static String featureFor(String loggerName) {
        return loggerName == null ? null : SOURCES.get(loggerName);
    }

    /** True while a session file is open and under its cap. Read on every recorded log call, so it is a volatile. */
    public static boolean active() {
        return active;
    }

    /** The client tick counter the lines carry; advanced once per client tick by SessionRecorder. */
    public static void advanceTick() {
        tick++;
    }

    public static long tick() {
        return tick;
    }

    /** The open (or last) session file, or null. */
    public static Path file() {
        synchronized (LOCK) {
            return file;
        }
    }

    public static long bytesWritten() {
        synchronized (LOCK) {
            return bytes;
        }
    }

    public static long linesWritten() {
        synchronized (LOCK) {
            return lines;
        }
    }

    /** True once the current/last session hit {@link #MAX_BYTES}. */
    public static boolean wasCut() {
        synchronized (LOCK) {
            return cut;
        }
    }

    /** Opens {@code path} as the session file, closing any open one first. False (and nothing open) on an IO error. */
    public static boolean open(Path path, String header) {
        synchronized (LOCK) {
            closeLocked(null);
            try {
                Files.createDirectories(path.getParent());
                out = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(path,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                        StandardCharsets.UTF_8), 1 << 16);
            } catch (IOException e) {
                out = null;
                return false;
            }
            file = path;
            bytes = 0;
            lines = 0;
            cut = false;
            dirty = false;
            LAST_TICK.clear();
            active = true;
            writeLocked(stamp() + " t=" + tick + " +- recorder start " + clean(header));
            return out != null;
        }
    }

    /** Closes the session file with a last "stop" line naming why. Safe to call twice and from any thread. */
    public static void close(String why) {
        synchronized (LOCK) {
            closeLocked(why);
        }
    }

    /** Writes any buffered lines out. Called about once a second by SessionRecorder. */
    public static void flush() {
        synchronized (LOCK) {
            if (out != null && dirty) {
                try {
                    out.flush();
                } catch (IOException ignored) {
                    // Next write reports it by failing the same way; nothing to do from a flush.
                }
                dirty = false;
            }
        }
    }

    /** One recorded line. {@code level} is "INFO", "WARN" or "ERROR". No-op unless a session is open. */
    public static void record(String feature, String level, String message, Throwable throwable) {
        if (!active) {
            return;
        }
        String msg = message == null ? "" : message;
        String event = "-";
        if (msg.startsWith("[")) {
            int end = msg.indexOf(']');
            if (end > 1 && end <= 40) {
                event = msg.substring(1, end).replace(' ', '_');
                msg = msg.substring(end + 1).stripLeading();
            }
        }
        if (throwable != null) {
            msg = msg + " ! " + throwable;
        }
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            long now = tick;
            Long last = LAST_TICK.put(feature, now);
            StringBuilder line = new StringBuilder(64 + msg.length());
            line.append(stamp()).append(" t=").append(now).append(' ')
                    .append(last == null ? "+-" : "+" + (now - last)).append(' ')
                    .append(feature).append(' ').append(event).append(' ');
            if (!"INFO".equals(level)) {
                line.append(level).append(' ');
            }
            line.append(clean(msg));
            writeLocked(line.toString());
        }
    }

    /** A line from the recorder itself (start, stop, world changes). */
    public static void note(String event, String message) {
        record("recorder", "INFO", "[" + event + "] " + message, null);
    }

    private static void writeLocked(String line) {
        if (out == null) {
            return;
        }
        byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes + encoded.length > MAX_BYTES - CUT_RESERVE) {
                String last = stamp() + " t=" + tick + " +- recorder cut file reached the 20 MB limit after " + lines
                        + " lines - nothing more is recorded this session\n";
                out.write(last);
                bytes += last.getBytes(StandardCharsets.UTF_8).length;
                lines++;
                cut = true;
                active = false;
                out.close();
                out = null;
                return;
            }
            out.write(line);
            out.write('\n');
            bytes += encoded.length;
            lines++;
            dirty = true;
        } catch (IOException e) {
            active = false;
            try {
                out.close();
            } catch (IOException ignored) {
                // Already failing; the file keeps what reached it.
            }
            out = null;
        }
    }

    private static void closeLocked(String why) {
        if (out == null) {
            active = false;
            return;
        }
        if (why != null) {
            writeLocked(stamp() + " t=" + tick + " +- recorder stop " + clean(why) + " (" + lines + " lines)");
        }
        active = false;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // The file keeps whatever was flushed.
            }
            out = null;
        }
    }

    private static String stamp() {
        return LocalTime.now().format(TIME);
    }

    /** One event per line: newlines and tabs inside a message become " | " and a space. */
    private static String clean(String s) {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0 && s.indexOf('\t') < 0) {
            return s;
        }
        return s.replace("\r\n", " | ").replace('\r', ' ').replace("\n", " | ").replace('\t', ' ');
    }
}
