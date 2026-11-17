package com.killer560.hub.secrets;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Catacombs RUN this is, so a rejoin of the same run (a disconnect, /lobby, a crash, then back in) keeps the
 * run's memory instead of starting over (killer560, 2026-11-11: "stuff not working if i leave and rejoin mid
 * dungeon").
 *
 * <p>A run is live once its sidebar shows the server id ({@code 11/11/26 m12AB}) and {@code Time Elapsed}. When the
 * world changes away from a live run that has not ended ({@code > EXTRA STATS <} or the Team Score line), the run is
 * HELD: {@link #holding()} is true and features keep their per-run state instead of dropping it on the world change.
 * The next dungeon world decides: the same server id and floor with a Time Elapsed that fits the time away is the
 * same run ({@link #resumed()}); anything else, or no dungeon within {@link #HOLD_MS}, drops the held run and bumps
 * {@link #generation()}, which is when the holding features reset. It never guesses: a run without a server id or a
 * Time Elapsed line is never held, and a world that cannot show both within {@link #DECIDE_TICKS} is a new run.
 * Nothing here runs in the sim. Testkit 1111-hx-rejoin-*.
 */
public final class DungeonRun {

    private static final Logger LOGGER = ModLog.get("killer560smod-secrets");
    private static final Pattern SERVER = Pattern.compile("\\d{2}/\\d{2}/\\d{2} +([mM]\\d{1,5}[A-Za-z]{0,4})");
    private static final Pattern ELAPSED = Pattern.compile("Time Elapsed: *(?:(\\d{1,2})h *)?(?:(\\d{1,2})m *)?(\\d{1,2})s");
    private static final Pattern EXTRA_STATS = Pattern.compile("^\\s*> EXTRA STATS <\\s*$");
    private static final Pattern TEAM_SCORE = Pattern.compile("^\\s*Team Score: [\\d,]{1,15} \\([A-DS+]{1,2}\\)\\s*$");
    /** A held run is dropped after this long without its dungeon coming back. */
    static final long HOLD_MS = 10 * 60_000L;
    /** Ticks a dungeon world may take to show the server id and Time Elapsed before it counts as a new run. */
    static final int DECIDE_TICKS = 100;
    /** Seconds the rejoined Time Elapsed may run past the time away (the sidebar updates once a second). */
    private static final int ELAPSED_SLACK_S = 20;

    /** The live (or held) run: where and how far along it was when last seen. */
    private static String server;
    private static String floor;
    private static int elapsedS = -1;
    private static long seenAtMs;
    private static boolean ended;
    /** The world the live run is in; a different world means it is held (or gone). */
    private static Object liveLevel;
    private static boolean resumed;
    private static int generation;
    private static Object undecidedLevel;
    private static int undecidedTicks;
    private static String parsedText;

    private DungeonRun() {
    }

    static void register() {
        ChatObserver.subscribe(message -> {
            String plain = ChatObserver.strip(message);
            if (plain != null && liveLevel != null && (EXTRA_STATS.matcher(plain).matches() || TEAM_SCORE.matcher(plain).matches())) {
                ended = true;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DungeonRun", DungeonRun::tick));
    }

    /** A live, unfinished run was left and this world has not yet been judged as it or another: keep per-run state. */
    public static boolean holding() {
        return liveLevel != null && Minecraft.getInstance().level != liveLevel && canResume();
    }

    /** This world is a rejoin of the run that was left. */
    public static boolean resumed() {
        return resumed && liveLevel != null && Minecraft.getInstance().level == liveLevel;
    }

    /** Bumped when a held run is dropped (another run, or it never came back): per-run state kept for it goes then. */
    public static int generation() {
        return generation;
    }

    private static boolean canResume() {
        return !ended && server != null && elapsedS >= 0 && System.currentTimeMillis() - seenAtMs < HOLD_MS;
    }

    private static void tick(Minecraft client) {
        Object level = client.level;
        if (liveLevel != null && level != liveLevel && !canResume()) {
            drop("it did not come back in time");
        }
        if (level == null || DungeonState.isRoomSim() || DungeonState.isSimOverrideActive() || !DungeonState.isInDungeon()) {
            return;
        }
        String text = DungeonState.readSidebarText();
        if (level == liveLevel) {
            if (!ended && text != parsedText) {
                read(text);
            }
            return;
        }
        if (level != undecidedLevel) {
            undecidedLevel = level;
            undecidedTicks = 0;
        }
        String nowServer = serverOf(text);
        int nowElapsed = elapsedOf(text);
        String nowFloor = DungeonState.getFloor();
        if (liveLevel == null) {
            start(level, text);
            return;
        }
        if (text.equals(parsedText)) {
            return; // the left world's sidebar, not yet replaced (a server switch keeps it a moment); a rejoin's has a later time
        }
        long awayS = (System.currentTimeMillis() - seenAtMs) / 1000L;
        if (nowServer != null && nowElapsed >= 0) {
            if (nowServer.equals(server) && nowFloor != null && nowFloor.equals(floor)
                    && nowElapsed >= elapsedS - 2 && nowElapsed <= elapsedS + awayS + ELAPSED_SLACK_S) {
                LOGGER.info("[DungeonRun] Rejoined the same run: {} {} at {} s (left at {} s, away {} s) - keeping its state",
                        server, floor, nowElapsed, elapsedS, awayS);
                liveLevel = level;
                resumed = true;
                read(text);
                return;
            }
        } else if ((nowServer == null || nowServer.equals(server)) && ++undecidedTicks < DECIDE_TICKS) {
            return; // the sidebar is still filling in
        }
        drop(String.format("another run (%s %s %ds, left %s %s %ds)", nowServer, nowFloor, nowElapsed, server, floor, elapsedS));
        start(level, text);
    }

    private static void start(Object level, String text) {
        liveLevel = level;
        resumed = false;
        ended = false;
        server = null;
        elapsedS = -1;
        read(text);
    }

    private static void read(String text) {
        parsedText = text;
        String s = serverOf(text);
        int e = elapsedOf(text);
        if (s != null) server = s;
        if (e >= 0) elapsedS = e;
        floor = DungeonState.getFloor();
        seenAtMs = System.currentTimeMillis();
    }

    /** Ends the live run; if features were holding its state past a world change, they drop it now. */
    private static void drop(String why) {
        if (liveLevel != null && Minecraft.getInstance().level != liveLevel && !ended && server != null && elapsedS >= 0) {
            generation++;
            LOGGER.info("[DungeonRun] Dropped the held run {} {} ({})", server, floor, why);
        }
        liveLevel = null;
        resumed = false;
        server = null;
        elapsedS = -1;
        ended = false;
    }

    /**
     * A feature's "new run, drop the per-run state" check, in place of its own {@code level != lastLevel}: true on a
     * world change unless that may be (or is) a rejoin of the run left, and when a held run is dropped.
     */
    public static final class Tracker {
        private Object level = Tracker.class;
        private int gen = generation;

        public boolean newRun(Object now) {
            boolean reset = false;
            if (now != level) {
                level = now;
                reset = !holding() && !resumed();
            }
            if (gen != generation) {
                gen = generation;
                reset = true;
            }
            return reset;
        }
    }

    static String serverOf(String text) {
        Matcher m = SERVER.matcher(ChatObserver.stripCodes(text));
        return m.find() ? m.group(1) : null;
    }

    static int elapsedOf(String text) {
        Matcher m = ELAPSED.matcher(ChatObserver.stripCodes(text));
        if (!m.find()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return h * 3600 + min * 60 + Integer.parseInt(m.group(3));
    }

    /** Testkit: forget everything. */
    public static void resetForTest() {
        liveLevel = null;
        resumed = false;
        server = null;
        floor = null;
        elapsedS = -1;
        ended = false;
        undecidedLevel = null;
        parsedText = null;
    }

    /** Testkit readout. */
    public static String describe() {
        return "server=" + server + " floor=" + floor + " elapsed=" + elapsedS + " ended=" + ended + " live=" + (liveLevel != null)
                + " holding=" + holding() + " resumed=" + resumed() + " generation=" + generation;
    }
}
