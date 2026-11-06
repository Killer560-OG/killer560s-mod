package com.killer560.hub.util;

import com.killer560.hub.shared.SharedWriter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tick Timer Audit (ticklog-1105, killer560 2026-11-05: "add a logger for all tick related timers for just my instance ...
 * go off of stuff like mask procs to tell when one actually goes off"). On only in a game folder holding the marker file
 * {@link #MARKER} (read once per world join; no menu row, so it is off everywhere else and costs one boolean read when off).
 * Every server-tick timer calls {@link #predict} when it starts (the moment it predicts) and when it reaches 0, and
 * {@link #truth} when the real event it predicts arrives; each call is one line with the server tick, the wall clock, the
 * client tick and the level's game time (the server's set-time clock, a second clock), the offset of a true event from its
 * prediction in all four, the gap since the timer's previous true event (his mask procs fell on exact 60-tick multiples of
 * WALL time) and {@link PerTickServerClock#auditState()}. One summary line per world: mean and max offset per timer. Lines
 * go to logger {@code killer560smod-ticklog} (kept in a release) and to {@link #FOLDER} in the game folder (one file per
 * launch, the newest {@link #KEEP_FILES} kept), written on {@link SharedWriter}'s thread.
 */
public final class TickAudit {

    public static final String MARKER = "killer560smod-ticklog";
    public static final String FOLDER = "killer560-ticklog";
    static final int KEEP_FILES = 10;
    private static final int MAX_TIMERS = 64;

    private static volatile boolean on;
    private static final Logger LOGGER = ModLog.keptWhen("killer560smod-ticklog", TickAudit::on);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static boolean registered;
    private static long clientTicks;
    private static Object lastLevel;
    private static volatile Path file;
    /** Per timer: its latest prediction and its last true event. */
    private static final Map<String, Timer> TIMERS = new HashMap<>();
    /** Per timer family (the name before " @"): this world's counts and offsets, for the summary. */
    private static final Map<String, Stats> STATS = new LinkedHashMap<>();

    /** One instant on every clock: server tick, wall ms, client tick, game time (-1 with no level). */
    private record Moment(long tick, long wallMs, long client, long game) {
        static Moment now() {
            Minecraft mc = Minecraft.getInstance();
            return new Moment(PerTickServerClock.now(), System.currentTimeMillis(), clientTicks,
                    mc == null || mc.level == null ? -1L : mc.level.getGameTime());
        }

        Moment plus(long ticks) {
            return new Moment(tick + ticks, wallMs + ticks * 50L, client + ticks, game < 0 ? -1L : game + ticks);
        }
    }

    private static final class Timer {
        Moment predicted;
        int period;
        Moment lastTrue;
    }

    private static final class Stats {
        int predictions;
        int truths;
        long sumTicks;
        long maxTicks;
        long sumMs;
        long maxMs;
    }

    private TickAudit() {
    }

    public static boolean on() { return on; }

    /** From {@link PerTickServerClock#register()}: the world-join marker check, the client tick count and the summary. */
    static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!on) return;
            clientTicks++;
            if (client.level != lastLevel) {
                lastLevel = client.level;
                endWorld();
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> refresh());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> endWorld());
    }

    /** Reads the marker (at each world join; the testkit calls it after writing or deleting the file). */
    public static synchronized void refresh() {
        boolean was = on;
        on = Files.exists(FabricLoader.getInstance().getGameDir().resolve(MARKER));
        if (on && !was) {
            Minecraft mc = Minecraft.getInstance();
            lastLevel = mc == null ? null : mc.level; // this world's lines are not a world change
            write("Tick Timer Audit on (marker " + MARKER + " in the game folder)");
        }
    }

    /** {@code timer} is predicted to fire {@code ahead} server ticks from now (0: it reached 0 now). */
    public static void predict(String timer, long ahead) { predict(timer, ahead, 0); }

    /** As {@link #predict(String, long)} for a timer that repeats every {@code period} ticks: a true event is measured
     *  against the nearest repeat of the prediction. */
    public static synchronized void predict(String timer, long ahead, int period) {
        if (!on) return;
        Moment now = Moment.now();
        Moment at = now.plus(ahead);
        Timer t = timer(timer);
        String drift = "";
        if (ahead == 0 && t != null && t.predicted != null && t.predicted.tick == now.tick) {
            drift = String.format(Locale.ROOT, ", %+d ms of wall time off the moment predicted", now.wallMs - t.predicted.wallMs);
        }
        if (t != null) {
            t.predicted = at;
            t.period = period;
        }
        stats(timer).predictions++;
        write(String.format(Locale.ROOT, "%s %s: server tick %d, wall %s, client tick %d, game time %d%s%s; clock %s", timer,
                ahead == 0 ? "reached 0" : "predicted in " + ahead + " ticks", at.tick, clock(at.wallMs), at.client, at.game,
                drift, period > 0 ? ", repeats every " + period : "", PerTickServerClock.auditState()));
    }

    /** The real event {@code timer} predicts has happened ({@code what}: how it was seen). */
    public static synchronized void truth(String timer, String what) {
        if (!on) return;
        Moment now = Moment.now();
        Timer t = timer(timer);
        StringBuilder line = new StringBuilder(timer).append(" TRUE (").append(what).append(')');
        if (t == null || t.predicted == null) {
            line.append(" with no prediction");
        } else {
            Moment p = t.predicted;
            long dt = now.tick - p.tick;
            long k = t.period > 0 ? Math.floorDiv(dt + t.period / 2, t.period) : 0L;
            Moment q = p.plus(k * t.period);
            dt = now.tick - q.tick;
            long ms = now.wallMs - q.wallMs;
            line.append(String.format(Locale.ROOT, ": %+d server ticks, %+d ms (%+.1f wall ticks), %+d client ticks, %s game"
                    + " ticks off the prediction (server tick %d)", dt, ms, ms / 50.0, now.client - q.client,
                    now.game < 0 || q.game < 0 ? "?" : String.format(Locale.ROOT, "%+d", now.game - q.game), q.tick));
            Stats s = stats(timer);
            s.truths++;
            s.sumTicks += dt;
            s.maxTicks = Math.max(s.maxTicks, Math.abs(dt));
            s.sumMs += ms;
            s.maxMs = Math.max(s.maxMs, Math.abs(ms));
        }
        if (t != null && t.lastTrue != null) {
            Moment l = t.lastTrue;
            line.append(String.format(Locale.ROOT, "; previous true one %.1f wall ticks, %d server ticks, %s game ticks ago",
                    (now.wallMs - l.wallMs) / 50.0, now.tick - l.tick, now.game < 0 || l.game < 0 ? "?"
                            : String.valueOf(now.game - l.game)));
        }
        if (t != null) {
            t.lastTrue = now;
        }
        line.append(String.format(Locale.ROOT, "; at server tick %d, wall %s, game time %d; clock %s", now.tick,
                clock(now.wallMs), now.game, PerTickServerClock.auditState()));
        write(line.toString());
    }

    /** The world ended (a new level, a disconnect): one summary line, then this world's numbers are cleared. */
    private static synchronized void endWorld() {
        if (!STATS.isEmpty()) {
            StringBuilder line = new StringBuilder("world summary:");
            for (Map.Entry<String, Stats> e : STATS.entrySet()) {
                Stats s = e.getValue();
                line.append(' ').append(e.getKey()).append(" (").append(s.predictions).append(" predicted, ")
                        .append(s.truths).append(" true");
                if (s.truths > 0) {
                    line.append(String.format(Locale.ROOT, ": mean %+.1f / max %d ticks, mean %+.0f / max %d ms",
                            s.sumTicks / (double) s.truths, s.maxTicks, s.sumMs / (double) s.truths, s.maxMs));
                }
                line.append(");");
            }
            write(line.toString());
        }
        STATS.clear();
        TIMERS.clear();
    }

    private static Timer timer(String name) {
        Timer t = TIMERS.get(name);
        if (t == null && TIMERS.size() < MAX_TIMERS) {
            TIMERS.put(name, t = new Timer());
        }
        return t;
    }

    private static Stats stats(String timer) {
        int at = timer.indexOf(" @");
        String family = at < 0 ? timer : timer.substring(0, at);
        Stats s = STATS.get(family);
        if (s == null) {
            s = new Stats();
            if (STATS.size() < MAX_TIMERS) {
                STATS.put(family, s);
            }
        }
        return s;
    }

    private static String clock(long wallMs) {
        return java.time.Instant.ofEpochMilli(wallMs).atZone(java.time.ZoneId.systemDefault()).format(TIME) + " (" + wallMs + ")";
    }

    private static void write(String text) {
        LOGGER.info("[TickAudit] {}", text);
        String line = LocalDateTime.now().format(TIME) + " " + text + System.lineSeparator();
        SharedWriter.submit(() -> append(line));
    }

    /** On the writer thread: the launch's file (made on its first line, the oldest past {@link #KEEP_FILES} deleted). */
    private static void append(String line) {
        try {
            if (file == null) {
                Path dir = FabricLoader.getInstance().getGameDir().resolve(FOLDER);
                Files.createDirectories(dir);
                file = dir.resolve("ticklog-" + LocalDateTime.now().format(STAMP) + ".log");
                try (var files = Files.list(dir)) {
                    List<Path> old = files.filter(p -> p.getFileName().toString().startsWith("ticklog-")).sorted().toList();
                    for (int i = 0; i < old.size() - (KEEP_FILES - 1); i++) {
                        Files.deleteIfExists(old.get(i));
                    }
                }
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[TickAudit] could not write {}: {}", file, e.toString());
        }
    }

    /** Testkit: this launch's file (null before its first line), after every queued line is written. */
    public static Path fileForTest() {
        SharedWriter.drain(3000);
        return file;
    }

    /** Testkit: end the world now (the summary line). */
    public static void endWorldForTest() { endWorld(); }
}
