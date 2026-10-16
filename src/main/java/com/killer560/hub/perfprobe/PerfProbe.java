package com.killer560.hub.perfprobe;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug-only profiler for the mod's own callbacks (2026-10-15, the testkit's {@code 95-perf-*} benches).
 *
 * <p>Off unless the JVM is started with {@code -Dkiller560.perf.probe=true} AND the jar is a dev build
 * ({@code BuildVariant.DEV_TOOLS}). When off, nothing is wrapped: the two probe mixins are not even applied
 * ({@link PerfProbeMixinPlugin}), and the one call site in the mod ({@code HudElementRegistry.drawAt}) tests a
 * static final false. A release jar pays nothing.
 *
 * <p>When on, every listener a {@code com.killer560.hub} class registers on a Fabric event (any {@code ArrayBackedEvent}:
 * ticks, level render, chat, screen events...) and every Fabric HUD layer it adds is wrapped in a proxy that times the
 * call; each mod HUD element drawn by {@code HudElementRegistry.drawAt} is timed too. Time is SELF time on the render
 * thread (a nested probed call is subtracted from its caller), and allocation is the render thread's allocated bytes
 * over the same window. Calls on other threads (the integrated server's ticks in the sim) are kept apart, inclusive (time and that
 * thread's allocation).
 *
 * <p>Not covered: the mod's mixins into vanilla code (nametags, item rendering, slot drawing...) and anything a feature
 * ticks from another feature's listener (CheatUtils ticks several). JFR (testkit {@code tools/fps-jfr.py}) covers those.
 *
 * <p>The testkit reads it by reflection: {@link #snapshot()}, {@link #reset()}, {@link #calibrate(int)}.
 */
public final class PerfProbe {

    public static final boolean ENABLED = com.killer560.hub.BuildVariant.DEV_TOOLS
            && Boolean.getBoolean("killer560.perf.probe");

    private static final String MOD_PREFIX = "com.killer560.hub.";
    private static final String SELF_PREFIX = "com.killer560.hub.perfprobe.";
    private static final int MAX_DEPTH = 256;

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static final com.sun.management.ThreadMXBean THREADS = threads();

    // Render-thread nesting stacks (self time = elapsed - time spent in probed calls nested inside).
    private static final long[] START_NS = new long[MAX_DEPTH];
    private static final long[] START_B = new long[MAX_DEPTH];
    private static final long[] CHILD_NS = new long[MAX_DEPTH];
    private static final long[] CHILD_B = new long[MAX_DEPTH];
    private static int depth;
    private static volatile Thread renderThread;

    private PerfProbe() {
    }

    /** One timed thing. Render-thread fields are written from the render thread only; {@link #off} is synchronized. */
    public static final class Stat {
        final String key;
        final String kind;
        long calls;
        long ns;
        long bytes;
        long maxNs;
        volatile Stat off;

        Stat(String key, String kind) {
            this.key = key;
            this.kind = kind;
        }
    }

    // ---- wrapping -----------------------------------------------------------------------------------------------

    /** The listener itself, or a timing proxy for it when the probe is on and it is one of this mod's classes. */
    @SuppressWarnings("unchecked")
    public static <T> T wrapListener(Class<?> iface, T listener) {
        if (!ENABLED || listener == null || iface == null || !iface.isInterface()
                || listener instanceof Proxy && Proxy.getInvocationHandler(listener) instanceof Handler) {
            return listener;
        }
        String cn = listener.getClass().getName();
        if (!cn.startsWith(MOD_PREFIX) || cn.startsWith(SELF_PREFIX)) {
            return listener;
        }
        try {
            String event = eventName(iface);
            Stat stat = stat(owner(cn) + " " + event, kindOf(event));
            return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, new Handler(listener, stat));
        } catch (RuntimeException | LinkageError e) {
            return listener; // a probe must never stop a listener being registered
        }
    }

    private static final class Handler implements InvocationHandler {
        private final Object delegate;
        private final Stat stat;

        Handler(Object delegate, Stat stat) {
            this.delegate = delegate;
            this.stat = stat;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "PerfProbe[" + delegate + "]";
                    default -> method.invoke(delegate, args);
                };
            }
            boolean onRender = onRenderThread();
            if (onRender) {
                enter();
            }
            long t0 = onRender ? 0L : System.nanoTime();
            long b0 = onRender ? 0L : allocated();
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                if (onRender) {
                    exit(stat);
                } else {
                    recordOff(stat, System.nanoTime() - t0, allocated() - b0);
                }
            }
        }
    }

    // ---- HUD elements (HudElementRegistry.drawAt) ---------------------------------------------------------------

    private static final Map<Object, Stat> HUD = new java.util.IdentityHashMap<>();

    /** Start timing one HUD element's draw. Pair with {@link #exitHud(Object, String)} in a finally. */
    public static void enterHud() {
        if (onRenderThread()) {
            enter();
        }
    }

    public static void exitHud(Object element, String id) {
        if (!onRenderThread()) {
            return;
        }
        Stat stat = HUD.get(element);
        if (stat == null) {
            stat = stat(owner(element.getClass().getName()) + " HUD[" + id + "]", "frame");
            HUD.put(element, stat);
        }
        exit(stat);
    }

    // ---- screens (Screen.extractRenderStateWithTooltipAndSubtitles) ---------------------------------------------

    private static final Map<Class<?>, Stat> SCREENS = new java.util.IdentityHashMap<>();

    /** Start timing the open screen's draw (fps-gui-1015). Pair with {@link #exitScreen(Object)} in a finally. */
    public static void enterScreen() {
        if (onRenderThread()) {
            enter();
        }
    }

    /** Ends {@link #enterScreen()}: key {@code screen.<SimpleName> extract}, kind frame. Self time is the screen's own
     *  draw (vanilla's and every mod mixin inside it) minus probed listeners nested inside. */
    public static void exitScreen(Object screen) {
        if (!onRenderThread()) {
            return;
        }
        Class<?> c = screen.getClass();
        Stat stat = SCREENS.get(c);
        if (stat == null) {
            stat = stat("screen." + c.getSimpleName() + " extract", "frame");
            SCREENS.put(c, stat);
        }
        exit(stat);
    }

    // ---- timing -------------------------------------------------------------------------------------------------

    private static boolean onRenderThread() {
        Thread t = Thread.currentThread();
        Thread r = renderThread;
        if (t == r) {
            return true;
        }
        if (r == null && "Render thread".equals(t.getName())) {
            renderThread = t;
            return true;
        }
        return false;
    }

    private static void enter() {
        int d = depth++;
        if (d < MAX_DEPTH) {
            CHILD_NS[d] = 0L;
            CHILD_B[d] = 0L;
            START_B[d] = allocated();
            START_NS[d] = System.nanoTime();
        }
    }

    private static void exit(Stat stat) {
        long t1 = System.nanoTime();
        long b1 = allocated();
        int d = --depth;
        if (d < 0) {
            depth = 0;
            return;
        }
        if (d >= MAX_DEPTH) {
            return;
        }
        long el = t1 - START_NS[d];
        long eb = b1 - START_B[d];
        long self = el - CHILD_NS[d];
        stat.calls++;
        stat.ns += self;
        stat.bytes += eb - CHILD_B[d];
        if (self > stat.maxNs) {
            stat.maxNs = self;
        }
        if (d > 0) {
            CHILD_NS[d - 1] += el;
            CHILD_B[d - 1] += eb;
        }
    }

    private static void recordOff(Stat stat, long ns, long bytes) {
        Stat off = stat.off;
        if (off == null) {
            synchronized (stat) {
                off = stat.off;
                if (off == null) {
                    off = stat(stat.key + " @" + Thread.currentThread().getName(), "server");
                    stat.off = off;
                }
            }
        }
        synchronized (off) {
            off.calls++;
            off.ns += ns;
            off.bytes += bytes;
            if (ns > off.maxNs) {
                off.maxNs = ns;
            }
        }
    }

    private static long allocated() {
        return THREADS == null ? 0L : THREADS.getCurrentThreadAllocatedBytes();
    }

    private static com.sun.management.ThreadMXBean threads() {
        try {
            com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            t.setThreadAllocatedMemoryEnabled(true);
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Stat stat(String key, String kind) {
        return STATS.computeIfAbsent(key, k -> new Stat(k, kind));
    }

    // ---- naming -------------------------------------------------------------------------------------------------

    /** {@code com.killer560.hub.trail.TrailFeature$$Lambda/0x..} -> {@code trail.TrailFeature}; an anonymous
     *  {@code Foo$1} -> {@code Foo}; a named nested class keeps its name. */
    static String owner(String className) {
        String s = className;
        int lambda = s.indexOf("$$Lambda");
        if (lambda >= 0) {
            s = s.substring(0, lambda);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        while (true) {
            int d = s.lastIndexOf('$');
            if (d < 0 || d == s.length() - 1 || !Character.isDigit(s.charAt(d + 1))) {
                break;
            }
            s = s.substring(0, d);
        }
        return s.startsWith(MOD_PREFIX) ? s.substring(MOD_PREFIX.length()) : s;
    }

    /** {@code ...ClientTickEvents$EndTick} -> {@code ClientTickEvents.EndTick}. */
    static String eventName(Class<?> iface) {
        String n = iface.getName();
        return n.substring(n.lastIndexOf('.') + 1).replace('$', '.');
    }

    static String kindOf(String event) {
        if (event.contains("Tick")) {
            return "tick";
        }
        if (event.contains("Render") || event.contains("Extract") || event.contains("Hud") || event.contains("Submit")) {
            return "frame";
        }
        return "event";
    }

    // ---- read-out for the testkit (reflection; plain types only) -------------------------------------------------

    public static boolean enabled() {
        return ENABLED;
    }

    /** One line per key: {@code key \t kind \t calls \t selfNs \t bytes \t maxNs}. Call on the render thread. */
    public static String snapshot() {
        List<String> lines = new ArrayList<>();
        for (Stat s : STATS.values()) {
            long calls;
            long ns;
            long bytes;
            long max;
            synchronized (s) {
                calls = s.calls;
                ns = s.ns;
                bytes = s.bytes;
                max = s.maxNs;
            }
            if (calls == 0) {
                continue;
            }
            lines.add(s.key + "\t" + s.kind + "\t" + calls + "\t" + ns + "\t" + bytes + "\t" + max);
        }
        lines.sort(null);
        return String.join("\n", lines);
    }

    /** Zero every counter (keys stay registered). Call on the render thread. */
    public static void reset() {
        for (Stat s : STATS.values()) {
            synchronized (s) {
                s.calls = 0;
                s.ns = 0;
                s.bytes = 0;
                s.maxNs = 0;
            }
        }
    }

    /** Number of keys registered so far: proof the wrapping ran. */
    public static int keyCount() {
        return STATS.size();
    }

    /**
     * The probe's own cost per call, from {@code n} calls of an empty listener through the same proxy path:
     * {@code outerNsPerCall \t recordedNsPerCall \t recordedBytesPerCall}. "Recorded" is what each real listener's
     * self time carries on top of its own work, to subtract; "outer" is what the probe adds to the frame. Render thread.
     */
    public static String calibrate(int n) {
        if (!ENABLED || !onRenderThread()) {
            return "0\t0\t0";
        }
        Stat stat = new Stat("perfprobe.calibration", "event");
        Runnable empty = () -> { };
        Runnable wrapped = (Runnable) Proxy.newProxyInstance(Runnable.class.getClassLoader(),
                new Class<?>[]{Runnable.class}, new Handler(empty, stat));
        for (int i = 0; i < Math.min(n, 20_000); i++) {
            wrapped.run(); // warm-up
        }
        stat.calls = 0;
        stat.ns = 0;
        stat.bytes = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            wrapped.run();
        }
        long outer = System.nanoTime() - t0;
        return String.format(Locale.ROOT, "%.1f\t%.1f\t%.1f", outer / (double) n, stat.ns / (double) stat.calls,
                stat.bytes / (double) stat.calls);
    }
}
