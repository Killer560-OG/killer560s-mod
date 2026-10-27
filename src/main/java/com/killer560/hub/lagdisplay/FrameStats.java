package com.killer560.hub.lagdisplay;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Individual frame times and stutter attribution for the Performance HUD (stutter-1026).
 *
 * <p>killer560: "I feel like I keep having large frame stutters but the fps thing isn't picking it up." The FPS line is
 * {@code Minecraft.getFps()}, a count of frames in the last second, so one 300 ms hitch moves it by a frame or two. This
 * class times every frame on the render thread instead.
 *
 * <p><b>A frame</b> is one {@code Minecraft.runTick}: from its HEAD to the next one's ({@code FrameStatsMinecraftMixin}).
 * Inside it the mixins time the phases that can hold a hitch: the client ticks run in that frame ({@code Minecraft.tick},
 * with how many), queued packets and tasks, the world and the GUI/HUD, each in both halves of 26.x's frame (extract:
 * {@code LevelRenderer.extractLevel} / 26.2 {@code LevelExtractor.extract} and {@code extractGui} / 26.2
 * {@code Gui.extractRenderState}; render: {@code GameRenderer.renderLevel} and {@code GuiRenderer.render}), the FPS
 * limiter's sleep ({@code FramerateLimiter.limitDisplayFPS}), and chunk work done on the render thread (section scheduling,
 * synchronous section rebuilds, terrain buffer uploads). GC pauses come from the GarbageCollectorMXBeans' counters, read
 * once a frame. The mod's own tick handlers and HUD elements are timed per callback by {@link ModCallbackTimes}.
 *
 * <p><b>Frame time</b> for the Frame line and the stutter test is the frame's length minus the FPS limiter's sleep: the
 * limiter waiting on purpose (a frame cap, the unfocused-window cap) is not lag. A frame of at least the Stutter Threshold
 * is a stutter: one INFO line goes to the log and to {@code killer560smod-lagdisplay-stutters.log} (rolled at 1 MB, kept
 * out of profiles as a log), and {@code /lag stutters} lists the last ones. 1% Low is from the raw frame lengths (what
 * the screen showed): 1000 / the 99th-percentile frame time over the last five seconds.
 *
 * <p>Off (the Performance HUD off, or Frame, 1% Low and Log Stutters all off) every hook is one static boolean read.
 * On, about two dozen {@code System.nanoTime()} calls and three GC counter reads a frame, no allocation except on a
 * stutter frame (testkit 95-perf-stutter). Render thread only.
 */
public final class FrameStats {

    private static final Logger LOGGER = ModLog.get("killer560smod-lagdisplay");

    // ---- phases ----------------------------------------------------------------------------------------------------
    public static final int TICK = 0;
    public static final int TASKS = 1;
    public static final int EXTRACT = 2;
    public static final int EXTRACT_LEVEL = 3;
    public static final int EXTRACT_GUI = 4;
    public static final int RENDER = 5;
    public static final int RENDER_LEVEL = 6;
    public static final int RENDER_GUI = 7;
    public static final int LIMITER = 8;
    public static final int CHUNK_SCHEDULE = 9;
    public static final int CHUNK_SYNC = 10;
    public static final int CHUNK_UPLOAD = 11;
    public static final int SWAP = 12;
    private static final int PHASES = 13;
    private static final String[] PHASE_NAMES = {"tick", "tasks", "extract", "extractLevel", "extractGui", "render",
            "renderLevel", "renderGui", "limiter", "chunkSchedule", "chunkSync", "chunkUpload", "swap"};

    /** Read first by every hook. True only while measuring. */
    public static boolean active;

    private static final long[] START = new long[PHASES];
    private static final long[] NS = new long[PHASES];
    private static final int[] COUNT = new int[PHASES];
    /** Every phase's hits since start-up while active (the testkit proves each hook fires). */
    private static final long[] HITS = new long[PHASES];

    private static Thread renderThread;
    private static long frameStartNs;
    /** Render thread CPU time at the frame start (ns; Windows counts it in ~15.6 ms steps), or -1 when unsupported. */
    private static long frameStartCpuNs = -1L;
    private static final java.lang.management.ThreadMXBean THREADS = threadBean();
    private static long frames;

    // ---- GC --------------------------------------------------------------------------------------------------------
    private static final GarbageCollectorMXBean[] GC = pauseCollectors();
    private static final long[] GC_COUNT = new long[GC.length];
    private static final long[] GC_TIME = new long[GC.length];

    // ---- rolling frame window (render thread) -----------------------------------------------------------------------
    private static final int RING = 8192;
    private static final long[] RING_END = new long[RING];
    private static final int[] RING_RAW_US = new int[RING];
    private static final int[] RING_WORK_US = new int[RING];
    private static int ringHead;
    private static int ringSize;
    private static final int[] SCRATCH = new int[RING];
    private static final long WINDOW_NS = 5_000_000_000L;
    private static final long STUTTER_WINDOW_MS = 60_000L;
    private static long lastStatsNs;
    /** Shown values, refreshed four times a second: worst frame (0.1 ms) in the last 5 s, 1% low FPS, stutters a minute. */
    private static volatile int worstTenthsMs = -1;
    private static volatile int lowFps = -1;
    private static volatile int stuttersLastMinute;

    // ---- stutters ---------------------------------------------------------------------------------------------------
    /** One recorded stutter, for {@code /lag stutters} and the testkit. */
    public record Stutter(long wallMs, String clock, double frameMs, String text) {
    }

    private static final int KEEP = 100;
    private static final ArrayDeque<Stutter> STUTTERS = new ArrayDeque<>();
    private static final ArrayDeque<Long> STUTTER_TIMES = new ArrayDeque<>();
    private static long lastStutterNs;
    private static long stutterTotal;
    private static boolean chunkHooksSeen;

    private static final int LOG_PER_MINUTE = 30;
    private static long logWindowStartMs;
    private static int loggedInWindow;
    private static int suppressed;

    private static final Path FILE = ModPaths.config("killer560smod-lagdisplay-stutters.log");
    private static final long FILE_ROLL_BYTES = 1024L * 1024L;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-stutter-log");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private FrameStats() {
    }

    // ---- hooks (render thread) --------------------------------------------------------------------------------------

    /** HEAD of {@code Minecraft.runTick}: closes the previous frame and opens this one. */
    public static void frameStart() {
        long now = System.nanoTime();
        LagDisplayConfig cfg = LagDisplayConfig.getInstance();
        boolean want = cfg.wantsFrameTimes();
        if (active && frameStartNs != 0L) {
            try {
                finishFrame(now, cfg);
            } catch (RuntimeException e) {
                LOGGER.error("[Stutter] frame bookkeeping failed", e);
            }
        }
        renderThread = Thread.currentThread();
        if (!want) {
            if (active) {
                active = false;
                frameStartNs = 0L;
                ModCallbackTimes.beginFrame(false);
                ringSize = 0;
                worstTenthsMs = -1;
                lowFps = -1;
            }
            return;
        }
        if (!active) {
            readGc(); // the baseline, so the first frame does not count every collection since start-up
        }
        for (int i = 0; i < PHASES; i++) {
            NS[i] = 0L;
            COUNT[i] = 0;
        }
        frameStartNs = now;
        frameStartCpuNs = cpuNow();
        Heartbeat.ensureStarted();
        active = true;
        ModCallbackTimes.beginFrame(true);
    }

    public static void begin(int phase) {
        if (Thread.currentThread() == renderThread) {
            START[phase] = System.nanoTime();
        }
    }

    public static void end(int phase) {
        if (Thread.currentThread() != renderThread || START[phase] == 0L) {
            return;
        }
        NS[phase] += System.nanoTime() - START[phase];
        START[phase] = 0L;
        COUNT[phase]++;
        HITS[phase]++;
        if (phase >= CHUNK_SCHEDULE && phase <= CHUNK_UPLOAD) {
            chunkHooksSeen = true;
        }
    }

    // ---- frame end --------------------------------------------------------------------------------------------------

    private static void finishFrame(long now, LagDisplayConfig cfg) {
        long raw = now - frameStartNs;
        long work = Math.max(0L, raw - NS[LIMITER]);
        frames++;
        long gcNs = 0L;
        int gcCount = 0;
        for (int i = 0; i < GC.length; i++) {
            long c = GC[i].getCollectionCount();
            long t = GC[i].getCollectionTime();
            if (c > GC_COUNT[i] && GC_COUNT[i] >= 0) {
                gcCount += (int) (c - GC_COUNT[i]);
                gcNs += Math.max(0L, t - GC_TIME[i]) * 1_000_000L;
            }
            GC_COUNT[i] = c;
            GC_TIME[i] = t;
        }
        // ring
        int idx = ringHead;
        RING_END[idx] = now;
        RING_RAW_US[idx] = (int) Math.min(Integer.MAX_VALUE, raw / 1000L);
        RING_WORK_US[idx] = (int) Math.min(Integer.MAX_VALUE, work / 1000L);
        ringHead = (ringHead + 1) & (RING - 1);
        if (ringSize < RING) {
            ringSize++;
        }
        long thresholdNs = cfg.getStutterThresholdMs() * 1_000_000L;
        if (work >= thresholdNs) {
            onStutter(now, raw, work, gcNs, gcCount, cfg);
        }
        if (now - lastStatsNs >= 250_000_000L) {
            lastStatsNs = now;
            refreshShown(now);
        }
    }

    private static void refreshShown(long now) {
        int n = 0;
        int worst = -1;
        for (int k = 0, i = (ringHead - 1) & (RING - 1); k < ringSize; k++, i = (i - 1) & (RING - 1)) {
            if (now - RING_END[i] > WINDOW_NS) {
                break;
            }
            worst = Math.max(worst, RING_WORK_US[i]);
            SCRATCH[n++] = RING_RAW_US[i];
        }
        worstTenthsMs = worst < 0 ? -1 : (worst + 50) / 100;
        if (n == 0) {
            lowFps = -1;
        } else {
            // 99th percentile frame length: the (n/100 + 1)-th longest.
            int k = n - 1 - n / 100;
            int p99 = select(SCRATCH, n, k);
            lowFps = p99 <= 0 ? -1 : (int) Math.min(9999, Math.round(1_000_000.0 / p99));
        }
        long nowMs = System.currentTimeMillis();
        synchronized (STUTTERS) {
            while (!STUTTER_TIMES.isEmpty() && nowMs - STUTTER_TIMES.peekFirst() > STUTTER_WINDOW_MS) {
                STUTTER_TIMES.pollFirst();
            }
            stuttersLastMinute = STUTTER_TIMES.size();
        }
    }

    /** Quickselect: the value that would be at index {@code k} of {@code a[0..n)} sorted ascending. Reorders {@code a}. */
    private static int select(int[] a, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            int pivot = a[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) {
                    i++;
                }
                while (a[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    int t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                break;
            }
        }
        return a[k];
    }

    private static void onStutter(long now, long raw, long work, long gcNs, int gcCount, LagDisplayConfig cfg) {
        long sinceNs = lastStutterNs == 0L ? -1L : now - lastStutterNs;
        lastStutterNs = now;
        stutterTotal++;
        // The game's own phases (what the render thread spent inside the game's code) against the rest of the frame:
        // wall time outside every phase, where the thread was not running the game's work at all (the OS did not run it,
        // or a driver, or another program held the PC). The swap is its own share: a GPU/driver wait, vsync included.
        long game = NS[TICK] + NS[TASKS] + NS[EXTRACT] + NS[RENDER];
        long outside = Math.max(0L, raw - game - NS[LIMITER] - NS[SWAP]);
        long world = NS[EXTRACT_LEVEL] + NS[RENDER_LEVEL];
        long gui = NS[EXTRACT_GUI] + NS[RENDER_GUI];
        long renderRest = Math.max(0L, NS[EXTRACT] + NS[RENDER] - world - gui);
        long cpu = frameStartCpuNs < 0 ? -1L : cpuNow() - frameStartCpuNs;
        long stall = Heartbeat.maxGapBetween(frameStartNs, now);

        StringBuilder sb = new StringBuilder(400);
        sb.append("frame ").append(ms(work)).append(": game ").append(ms(game))
                .append(", GPU/driver swap ").append(ms(NS[SWAP]))
                .append(", outside the game ").append(ms(outside));
        sb.append(" (").append(verdict(work, game, outside, NS[SWAP], gcNs, cpu, stall)).append(')');
        if (NS[LIMITER] > 500_000L) {
            sb.append(" [+").append(ms(NS[LIMITER])).append(" FPS limiter wait, not counted]");
        }
        sb.append(" | render thread CPU ").append(cpu < 0 ? "n/a" : ms(cpu)).append(" of ").append(ms(raw)).append(" wall");
        sb.append(", background thread stalled ").append(stall < 0 ? "n/a" : ms(stall));
        if (sinceNs < 0) {
            sb.append(" | first this session");
        } else {
            sb.append(" | ").append(seconds(sinceNs)).append(" since the last");
        }
        sb.append(" | ticks ").append(COUNT[TICK]).append("x ").append(ms(NS[TICK]));
        sb.append(", packets/tasks ").append(ms(NS[TASKS]));
        sb.append(", world ").append(ms(world)).append(" (extract ").append(ms(NS[EXTRACT_LEVEL]))
                .append(", draw ").append(ms(NS[RENDER_LEVEL])).append(')');
        sb.append(", GUI/HUD ").append(ms(gui)).append(" (extract ").append(ms(NS[EXTRACT_GUI]))
                .append(", draw ").append(ms(NS[RENDER_GUI])).append(')');
        sb.append(", other render ").append(ms(renderRest));
        sb.append(" | GC ").append(gcCount).append("x ").append(ms(gcNs));
        if (chunkHooksSeen) {
            sb.append(" | chunks: scheduling ").append(ms(NS[CHUNK_SCHEDULE])).append(", ")
                    .append(COUNT[CHUNK_SYNC]).append(" sync rebuild(s) ").append(ms(NS[CHUNK_SYNC]))
                    .append(", upload ").append(ms(NS[CHUNK_UPLOAD]));
            int queue = compileQueue();
            if (queue >= 0) {
                sb.append(", queue ").append(queue);
            }
        } else {
            sb.append(" | chunks: n/a (another renderer, e.g. Sodium, does them)");
        }
        sb.append(" | mod: ").append(ModCallbackTimes.top(3));
        Minecraft mc = Minecraft.getInstance();
        Object screen = McCompat.screen(mc);
        sb.append(" | screen ").append(screen == null ? "none" : screen.getClass().getSimpleName());
        sb.append(", world ").append(mc.level == null ? "none" : mc.level.dimension().identifier().toString());
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            sb.append(" | Blink ").append(onOff(blinkRunning()))
                    .append(", Auto Routes ").append(onOff(com.killer560.hub.autoroutes.RouteExecutor.isRunning()))
                    .append(", AP3 ").append(onOff(com.killer560.hub.ap3.Ap3Executor.isRunning()));
        }
        String text = sb.toString();
        LocalDateTime t = LocalDateTime.now();
        long wall = System.currentTimeMillis();
        Stutter s = new Stutter(wall, t.format(CLOCK), work / 1e6, text);
        synchronized (STUTTERS) {
            STUTTERS.addLast(s);
            while (STUTTERS.size() > KEEP) {
                STUTTERS.pollFirst();
            }
            STUTTER_TIMES.addLast(wall);
            while (STUTTER_TIMES.size() > 10_000) {
                STUTTER_TIMES.pollFirst();
            }
        }
        if (!cfg.isLogStutters()) {
            return;
        }
        if (wall - logWindowStartMs >= 60_000L) {
            logWindowStartMs = wall;
            loggedInWindow = 0;
        }
        if (loggedInWindow >= LOG_PER_MINUTE) {
            suppressed++;
            return;
        }
        loggedInWindow++;
        String line = "[Stutter] " + text + (suppressed > 0 ? " (" + suppressed + " more not logged, over "
                + LOG_PER_MINUTE + " a minute)" : "");
        suppressed = 0;
        LOGGER.info(line);
        String stamped = t.format(STAMP) + " " + line + System.lineSeparator();
        try {
            WRITER.execute(() -> append(stamped));
        } catch (RuntimeException ignored) {
            // executor gone at shutdown
        }
    }

    /**
     * The plain-words reason (killer560, 2026-10-26: "I think the fps freeze thing is my PC, not the game"). Outside time
     * is a hitch of the PC when the render thread barely ran (its CPU time is far under the wall time) or a background
     * thread that does nothing but sleep 10 ms at a time stalled too; GC pauses stop every Java thread, so a stall that
     * GC explains is Java's, not the PC's.
     */
    static String verdict(long work, long game, long outside, long swap, long gcNs, long cpu, long stall) {
        if (gcNs >= work / 2) {
            return "mostly Java garbage collection";
        }
        // A thread that only sleeps stalled for most of the frame: the whole PC (or JVM) stopped, wherever the render
        // thread happened to be - even inside one of the game's phases.
        if (stall >= 0 && stall - gcNs >= work / 2) {
            return "likely your PC: everything stalled, not only the game";
        }
        if (outside >= work / 2) {
            boolean descheduled = cpu >= 0 && cpu < outside / 2;
            boolean allStalled = stall >= 0 && stall - gcNs >= outside / 2;
            if (descheduled || allStalled) {
                return "likely your PC: the game was not running";
            }
            return "outside the game's own work";
        }
        if (swap >= work / 2) {
            return "mostly the GPU/driver swap";
        }
        if (game >= work / 2) {
            return "mostly the game";
        }
        return "mixed";
    }

    private static java.lang.management.ThreadMXBean threadBean() {
        try {
            java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
            return b.isCurrentThreadCpuTimeSupported() ? b : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static long cpuNow() {
        if (THREADS == null) {
            return -1L;
        }
        try {
            long t = THREADS.getCurrentThreadCpuTime();
            return t < 0 ? -1L : t;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * A daemon thread that only sleeps 10 ms at a time and records how late each wake-up was. If the whole PC (or the
     * whole JVM) stalls, it stalls too; if only the render thread was busy, it does not. Runs only while frames are
     * measured; about 100 wake-ups a second, each a few microseconds.
     */
    static final class Heartbeat {
        private static final int N = 512;
        private static final long[] WAKE = new long[N];
        private static final long[] GAP = new long[N];
        private static volatile int head;
        private static volatile boolean started;

        static void ensureStarted() {
            if (started) {
                return;
            }
            synchronized (Heartbeat.class) {
                if (started) {
                    return;
                }
                started = true;
                Thread t = new Thread(Heartbeat::run, "killer560smod-stutter-heartbeat");
                t.setDaemon(true);
                t.start();
            }
        }

        private static void run() {
            long last = System.nanoTime();
            while (true) {
                try {
                    Thread.sleep(active ? 10L : 250L);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.nanoTime();
                if (active) {
                    int h = head;
                    WAKE[h] = now;
                    GAP[h] = now - last;
                    head = (h + 1) & (N - 1);
                }
                last = now;
            }
        }

        /** The longest a sleep overran (gap minus the 10 ms asked for) for wake-ups in (from, to], or -1 if none. */
        static long maxGapBetween(long from, long to) {
            if (!started) {
                return -1L;
            }
            long max = -1L;
            int h = head;
            for (int k = 0; k < N; k++) {
                int i = (h - 1 - k) & (N - 1);
                long w = WAKE[i];
                if (w == 0L || w <= from) {
                    break;
                }
                if (w <= to + 50_000_000L) { // a wake-up just after the frame still ends a gap that lay inside it
                    max = Math.max(max, Math.max(0L, GAP[i] - 10_000_000L));
                }
            }
            return max;
        }
    }

    private static boolean blinkRunning() {
        return com.killer560.hub.blink.BlinkFeature.isHolding() || com.killer560.hub.blink.BlinkCharge.isActive();
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private static int compileQueue() {
        try {
            Object lr = Minecraft.getInstance().levelRenderer;
            if (lr instanceof com.killer560.hub.lagdisplay.mixin.FrameStatsLevelRendererAccessor a
                    && a.killer560smod$sectionRenderDispatcher() != null) {
                return a.killer560smod$sectionRenderDispatcher().getCompileQueueSize();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // not worth failing a log line over
        }
        return -1;
    }

    private static void append(String line) {
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE) && Files.size(FILE) > FILE_ROLL_BYTES) {
                Path old = FILE.resolveSibling("killer560smod-lagdisplay-stutters.1.log");
                Files.move(FILE, old, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("[Stutter] couldn't write {}: {}", FILE.getFileName(), e.toString());
        }
    }

    // ---- GC ---------------------------------------------------------------------------------------------------------

    /** Every collector whose time is pause time. ZGC and Shenandoah also register "... Cycles" beans that count
     *  concurrent work; those are left out. G1's "G1 Concurrent GC" counts its Remark and Cleanup pauses (JDK 20+). */
    private static GarbageCollectorMXBean[] pauseCollectors() {
        List<GarbageCollectorMXBean> out = new ArrayList<>();
        try {
            for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!b.getName().contains("Cycles")) {
                    out.add(b);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no management beans: GC reads 0
        }
        return out.toArray(new GarbageCollectorMXBean[0]);
    }

    private static void readGc() {
        for (int i = 0; i < GC.length; i++) {
            GC_COUNT[i] = GC[i].getCollectionCount();
            GC_TIME[i] = GC[i].getCollectionTime();
        }
    }

    // ---- reads ------------------------------------------------------------------------------------------------------

    /** Worst frame time (minus the limiter's sleep) in the last five seconds, in tenths of a millisecond, or -1. */
    public static int worstTenthsMs() {
        return worstTenthsMs;
    }

    /** 1% low FPS over the last five seconds, or -1. */
    public static int lowFps() {
        return lowFps;
    }

    /** Stutters in the last minute. */
    public static int stuttersLastMinute() {
        return stuttersLastMinute;
    }

    /** Stutters since start-up. */
    public static long stutterTotal() {
        return stutterTotal;
    }

    /** Frames measured since start-up. */
    public static long frames() {
        return frames;
    }

    /** The last {@code n} stutters, oldest first. */
    public static List<Stutter> recent(int n) {
        synchronized (STUTTERS) {
            List<Stutter> all = new ArrayList<>(STUTTERS);
            return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }

    /** Forgets the recorded stutters (the shown numbers follow within a quarter second). */
    public static void clear() {
        synchronized (STUTTERS) {
            STUTTERS.clear();
            STUTTER_TIMES.clear();
        }
    }

    /** "tick=123 tasks=..." - every hook's hits since start-up, for the testkit. */
    public static String hits() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PHASES; i++) {
            sb.append(PHASE_NAMES[i]).append('=').append(HITS[i]).append(' ');
        }
        return sb.toString().trim();
    }

    /** The log file the stutters go to. */
    public static Path file() {
        return FILE;
    }

    static String ms(long ns) {
        long tenths = (ns + 50_000L) / 100_000L;
        return tenths / 10 + "." + tenths % 10 + " ms";
    }

    private static String seconds(long ns) {
        long tenths = ns / 100_000_000L;
        return tenths / 10 + "." + tenths % 10 + " s";
    }
}
