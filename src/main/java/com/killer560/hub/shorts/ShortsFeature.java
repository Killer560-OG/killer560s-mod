package com.killer560.hub.shorts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "YT Shorts" - YouTube Shorts in a companion browser window glued over Minecraft (no embedded browser: MCEF
 * has no 26.1.2 port).
 * <p>
 * How it works:
 * <ol>
 *   <li>{@link #launch()} starts Edge (or Chrome) in app mode on youtube.com/shorts with a dedicated profile in
 *       the Fabric config dir (log into YouTube there once; it persists) and {@code --remote-debugging-port=0}.
 *       The browser writes its chosen DevTools port to {@code <profile>/DevToolsActivePort}.</li>
 *   <li>A background thread connects to the page over the Chrome DevTools Protocol and finds the browser's
 *       top-level {@code Chrome_WidgetWin_1} window (by process id, or by temporarily setting a unique
 *       {@code document.title} when that's ambiguous).</li>
 *   <li>On the client thread the window gets Minecraft as its OWNER (GWLP_HWNDPARENT: stays above Minecraft,
 *       minimizes with it, no taskbar button), its frame styles stripped, and every tick it's repositioned
 *       over Minecraft's client area with SWP_NOACTIVATE (never steals focus). App mode's own title bar is
 *       cropped away with a window region, using insets measured from the page's
 *       innerWidth/innerHeight/devicePixelRatio vs the real window rect.</li>
 *   <li>Keybinds send CDP {@code Input.dispatchKeyEvent}/{@code Runtime.evaluate} to the page - no focus
 *       change, so you keep playing.</li>
 * </ol>
 * Windows (64-bit) only; everything that can block runs on the single "killer560smod-shorts" thread.
 */
public final class ShortsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-shorts");
    private static final String CHAT = "YT Shorts";

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-shorts");
        t.setDaemon(true);
        return t;
    });

    private static final Object LOCK = new Object();

    // ---- shared between the background thread and the client thread ----
    private static final AtomicInteger generation = new AtomicInteger();
    private static final AtomicBoolean shutdownDone = new AtomicBoolean();
    private static final AtomicBoolean measureScheduled = new AtomicBoolean();
    private static volatile boolean launching;
    private static volatile ProcessHandle browserProcess;
    private static volatile int devToolsPort = -1;
    private static volatile CdpClient cdp;
    private static volatile long browserHwnd;
    private static volatile long mcHwnd;
    /** Window-rect pixels to crop off the browser window: left, top, right, bottom. */
    private static volatile int[] insets = {0, 0, 0, 0};

    // ---- client thread only ----
    private static long attachedHwnd;
    private static boolean shown;
    private static int[] lastWindowRect;
    private static int[] lastRegion;
    private static int appliedOpacity = -1;
    private static int ticks;
    private static boolean refocusMcOnAttach;
    private static boolean pausedByHide;
    private static int driftLogs;
    private static final boolean[] keyWasDown = new boolean[5];

    // ---- 2026-09-21 expansion: Edit Window + DVD placement (client thread only, same as the fields above) ----
    private static boolean editMode;
    private static boolean dvdInitialized;
    private static float dvdX, dvdY, dvdVx, dvdVy;
    private static int dvdW, dvdH;
    private static long dvdLastUpdateMs;
    private static final float DVD_SPEED_PX_PER_SEC = 85f;

    private static Boolean supported;
    private static String unsupportedReason = "";

    private ShortsFeature() {
    }

    public static void register() {
        ShortsConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(ShortsFeature::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdownNow("client stopping"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownNow("JVM shutdown"), "killer560smod-shorts-exit"));
    }

    // ------------------------------------------------------------------------------------------------
    // Support / status
    // ------------------------------------------------------------------------------------------------

    /** Windows 64-bit with JNA's user32/gdi32 loadable. Cached after the first call. */
    public static synchronized boolean isSupported() {
        if (supported == null) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!os.startsWith("windows")) {
                supported = false;
                unsupportedReason = "YT Shorts only works on Windows (it drives an Edge/Chrome window).";
            } else if (!arch.contains("64")) {
                supported = false;
                unsupportedReason = "YT Shorts needs 64-bit Java.";
            } else {
                Throwable err = Win32.ensureLoaded();
                supported = err == null;
                if (err != null) {
                    unsupportedReason = "YT Shorts couldn't load Windows APIs (" + err.getClass().getSimpleName() + ").";
                    LOGGER.error("[Shorts] Failed to load user32/gdi32 via JNA - feature disabled.", err);
                }
            }
            if (!supported) {
                LOGGER.info("[Shorts] Feature unavailable: {}", unsupportedReason);
            }
        }
        return supported;
    }

    public static String getUnsupportedReason() {
        return unsupportedReason;
    }

    public static boolean isLaunching() {
        return launching;
    }

    public static boolean isRunning() {
        ProcessHandle ph = browserProcess;
        return browserHwnd != 0 || (ph != null && ph.isAlive());
    }

    /** One-line status for the settings tab. */
    public static String statusText() {
        if (launching) {
            return "Launching...";
        }
        if (browserHwnd != 0) {
            CdpClient c = cdp;
            return c != null && c.isOpen() ? "Running" : "Running (controls disconnected)";
        }
        return isRunning() ? "Running (window not attached - try Relaunch)" : "Not running";
    }

    // ------------------------------------------------------------------------------------------------
    // Launch / close
    // ------------------------------------------------------------------------------------------------

    /** Launches (or relaunches) the Shorts browser. Client thread. */
    public static void launch() {
        if (!isSupported()) {
            ModChat.send(CHAT, ModChat.bad(unsupportedReason));
            return;
        }
        if (launching) {
            ModChat.send(CHAT, ModChat.text("Already launching..."));
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ShortsConfig cfg = ShortsConfig.getInstance();
        long mc = currentMcHwnd(client);
        int[] mcRect = mc != 0 ? Win32.clientRectOnScreen(mc) : null;
        int[] geom = mcRect != null && mcRect[2] > 0 && mcRect[3] > 0
                ? computeContentRect(cfg, mcRect) : new int[]{100, 100, 360, 640};

        boolean relaunch = isRunning();
        if (relaunch) {
            closeBrowser("relaunch", false);
        }
        refocusMcOnAttach = client.isWindowActive();
        insets = new int[]{0, 0, 0, 0};
        launching = true;
        int gen = generation.incrementAndGet();
        ModChat.send(CHAT, ModChat.text(relaunch ? "Relaunching browser..." : "Launching browser..."));
        EXEC.execute(() -> doLaunch(gen, geom));
    }

    /** Closes the browser if running. Any thread; the blocking part runs in the background. */
    public static void closeBrowser(String reason, boolean announce) {
        CdpClient c;
        ProcessHandle ph;
        int port;
        long h;
        synchronized (LOCK) {
            generation.incrementAndGet();
            launching = false;
            c = cdp;
            ph = browserProcess;
            port = devToolsPort;
            h = browserHwnd;
            cdp = null;
            browserProcess = null;
            devToolsPort = -1;
            browserHwnd = 0;
        }
        if (c == null && ph == null && h == 0) {
            return;
        }
        LOGGER.info("[Shorts] Closing browser ({}).", reason);
        if (announce) {
            ModChat.send(CHAT, ModChat.text("Browser closed."));
        }
        EXEC.execute(() -> shutdownBrowser(c, ph, port, 1500, 2500));
    }

    private static void shutdownNow(String reason) {
        if (!shutdownDone.compareAndSet(false, true)) {
            return;
        }
        CdpClient c;
        ProcessHandle ph;
        int port;
        synchronized (LOCK) {
            generation.incrementAndGet();
            launching = false;
            c = cdp;
            ph = browserProcess;
            port = devToolsPort;
            cdp = null;
            browserProcess = null;
            devToolsPort = -1;
            browserHwnd = 0;
        }
        if (c == null && ph == null) {
            return;
        }
        LOGGER.info("[Shorts] Closing browser on exit ({}).", reason);
        try {
            shutdownBrowser(c, ph, port, 800, 1200);
        } catch (Throwable t) {
            LOGGER.warn("[Shorts] Error closing browser on exit: {}", t.toString());
        }
    }

    /** Blocking: asks the browser to close via CDP Browser.close, then force-kills whatever is left. */
    private static void shutdownBrowser(CdpClient pageClient, ProcessHandle ph, int port, long closeWaitMs, long exitWaitMs) {
        boolean asked = false;
        // Only ask over CDP while the browser we launched is still alive - if it already died, that port could now
        // belong to something else (review pass 2026-09-15).
        if (port > 0 && ph != null && ph.isAlive()) {
            asked = requestBrowserClose(port, null, closeWaitMs);
        }
        if (pageClient != null) {
            pageClient.close();
        }
        if (ph == null) {
            return;
        }
        if (asked) {
            try {
                ph.onExit().get(exitWaitMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        if (ph.isAlive()) {
            List<ProcessHandle> kids = ph.descendants().toList();
            ph.destroyForcibly();
            kids.forEach(ProcessHandle::destroyForcibly);
            LOGGER.info("[Shorts] Browser process {} force-killed ({} child processes).", ph.pid(), kids.size());
        } else {
            LOGGER.info("[Shorts] Browser process {} exited cleanly.", ph.pid());
        }
    }

    /** Connects to the browser-level DevTools endpoint and sends Browser.close. @return whether it was sent. */
    private static boolean requestBrowserClose(int port, String expectedBrowserPath, long waitMs) {
        try {
            String ws = CdpClient.findBrowserWebSocketUrl(port, 700);
            if (ws == null || (expectedBrowserPath != null && !ws.endsWith(expectedBrowserPath))) {
                return false;
            }
            CdpClient bc = CdpClient.connect(ws, () -> {
            });
            try {
                bc.send("Browser.close", null).get(waitMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // The browser usually drops the socket before replying - the command was still delivered.
            }
            bc.close();
            LOGGER.info("[Shorts] Sent Browser.close on DevTools port {}.", port);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void doLaunch(int gen, int[] geom) {
        Process process = null;
        try {
            Path exe = BrowserLauncher.findBrowser(LOGGER);
            if (exe == null) {
                LOGGER.warn("[Shorts] Neither Microsoft Edge nor Google Chrome was found.");
                chatLater(ModChat.bad("Couldn't find Microsoft Edge or Google Chrome - install one and try again."));
                return;
            }
            Path profile = BrowserLauncher.profileDir();
            Files.createDirectories(profile);
            closeStaleInstance(profile);
            if (gen != generation.get()) {
                return;
            }
            BrowserLauncher.markProfileExitedCleanly(profile, LOGGER);
            BrowserLauncher.deleteDevToolsPortFile(profile);

            ShortsConfig launchCfg = ShortsConfig.getInstance();
            List<String> cmd = BrowserLauncher.buildCommand(exe, profile, geom[0], geom[1], geom[2], geom[3],
                    launchCfg.getTheme(), launchCfg.getZoomPercent(), resolveUrl(launchCfg));
            LOGGER.info("[Shorts] Launching browser: {}", String.join(" ", cmd));
            process = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long startedAt = System.currentTimeMillis();
            synchronized (LOCK) {
                if (gen != generation.get()) {
                    process.destroyForcibly();
                    return;
                }
                browserProcess = process.toHandle();
            }
            LOGGER.info("[Shorts] Browser process started (pid {}).", process.pid());

            // 1) DevTools port (the browser writes it once its debugging server is up).
            int port = -1;
            while (System.currentTimeMillis() - startedAt < 20000 && gen == generation.get()) {
                port = BrowserLauncher.readDevToolsPort(profile);
                if (port > 0) {
                    break;
                }
                if (!process.isAlive() && System.currentTimeMillis() - startedAt > 4000) {
                    break;
                }
                Thread.sleep(100);
            }
            if (aborted(gen, process)) {
                return;
            }
            if (port > 0) {
                devToolsPort = port;
                LOGGER.info("[Shorts] DevTools port {} ({}ms after launch).", port, System.currentTimeMillis() - startedAt);
                connectCdp(gen, port, 10000);
            } else {
                LOGGER.warn("[Shorts] No DevTools port after launch (process alive: {}) - playback keybinds won't work.",
                        process.isAlive());
            }
            if (aborted(gen, process)) {
                return;
            }

            // 2) The browser's top-level window.
            long hwnd = findBrowserWindow(gen, process, 15000);
            if (aborted(gen, process)) {
                return;
            }
            if (hwnd == 0) {
                LOGGER.warn("[Shorts] Browser window not found within 15s (process alive: {}).", process.isAlive());
                chatLater(ModChat.bad("The browser started but its window wasn't found - try Relaunch. "
                        + "If a Shorts window is already open from before, close it first."));
                return;
            }
            int pid = Win32.processId(hwnd);
            synchronized (LOCK) {
                if (gen != generation.get()) {
                    return;
                }
                if (pid != process.pid()) {
                    ProcessHandle.of(pid).ifPresent(h -> browserProcess = h);
                    LOGGER.info("[Shorts] Window belongs to pid {} (launched pid {}), tracking that process.", pid, process.pid());
                }
                browserHwnd = hwnd;
            }
            LOGGER.info("[Shorts] Browser window found: hwnd 0x{} pid {} ({}ms after launch).",
                    Long.toHexString(hwnd), pid, System.currentTimeMillis() - startedAt);
            CdpClient c = cdp;
            chatLater(ModChat.good("Browser ready. "),
                    ModChat.dim(c != null && c.isOpen() ? "First time? Click the Shorts window and log into YouTube once."
                            : "Controls not connected - keybinds may not work."));
            scheduleInsetMeasure(800);
            // Volume is applied from connectCdp (installVolumeHookNow), not here - see that method's comment
            // for why a fixed post-launch delay isn't good enough.
            // Same 3s settle as before: YouTube's app shell has to exist before the html[dark] nudge means
            // anything (the media emulation itself was already sent the moment CDP connected).
            if (ShortsConfig.getInstance().getTheme() != ShortsConfig.Theme.SYSTEM) {
                EXEC.schedule(() -> runCommand("theme (launch)", cc -> applyThemeNow(cc, "launch")), 3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("[Shorts] Launch failed.", e);
            chatLater(ModChat.bad("Launch failed: " + e.getMessage()));
        } finally {
            if (gen == generation.get()) {
                launching = false;
            }
        }
    }

    private static boolean aborted(int gen, Process process) {
        if (gen == generation.get()) {
            return false;
        }
        LOGGER.info("[Shorts] Launch aborted (closed or relaunched meanwhile).");
        if (process != null && process.isAlive() && browserProcess == null) {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        return true;
    }

    /** If a previous game session left the Shorts browser running (crash / taskkill), close it first -
     *  a second launch on the same profile would just hand off to it and exit. */
    private static void closeStaleInstance(Path profile) throws InterruptedException {
        int port = BrowserLauncher.readDevToolsPort(profile);
        String path = BrowserLauncher.readDevToolsBrowserPath(profile);
        if (port <= 0 || path == null) {
            return;
        }
        if (!requestBrowserClose(port, path, 1500)) {
            return;
        }
        LOGGER.info("[Shorts] Closed a leftover Shorts browser from a previous session (port {}).", port);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                CdpClient.httpGet(port, "/json/version", 300);
            } catch (Exception gone) {
                break;
            }
            Thread.sleep(200);
        }
        Thread.sleep(500);
    }

    /** Blocking: connects the page-level CDP socket, retrying until timeout. */
    private static void connectCdp(int gen, int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String lastError = null;
        while (System.currentTimeMillis() < deadline && gen == generation.get()) {
            try {
                String ws = CdpClient.findPageWebSocketUrl(port);
                if (ws != null) {
                    AtomicReference<CdpClient> ref = new AtomicReference<>();
                    CdpClient client = CdpClient.connect(ws, () -> onCdpClosed(ref.get()));
                    ref.set(client);
                    synchronized (LOCK) {
                        if (gen != generation.get()) {
                            client.close();
                            return;
                        }
                        cdp = client;
                    }
                    LOGGER.info("[Shorts] CDP connected (port {}).", port);
                    // Emulation overrides die with the CDP session, so the theme has to be re-sent on every
                    // (re)connect, not just at launch. Own try/catch: a theme failure must not fall into this
                    // loop's retry path and open a second client on top of the one just stored.
                    try {
                        LOGGER.info("[Shorts] Theme: {}.", applyThemeNow(client, "connect"));
                    } catch (Exception e) {
                        LOGGER.warn("[Shorts] Theme apply on connect failed: {}", e.toString());
                    }
                    // Own try/catch for the same reason as the theme above: install the volume hook (page-side
                    // JS, dies with the CDP session same as the emulation) fresh on every (re)connect.
                    try {
                        LOGGER.info("[Shorts] Volume hook: {}.", installVolumeHookNow(client));
                    } catch (Exception e) {
                        LOGGER.warn("[Shorts] Volume hook install on connect failed: {}", e.toString());
                    }
                    return;
                }
                lastError = "no page target yet";
            } catch (Exception e) {
                lastError = e.toString();
            }
            Thread.sleep(250);
        }
        if (gen == generation.get()) {
            LOGGER.warn("[Shorts] CDP connection failed on port {}: {}", port, lastError);
        }
    }

    private static void onCdpClosed(CdpClient client) {
        synchronized (LOCK) {
            if (client == null || cdp != client) {
                return;
            }
            cdp = null;
        }
        LOGGER.info("[Shorts] CDP disconnected.");
        int gen = generation.get();
        int port = devToolsPort;
        if (port > 0) {
            EXEC.schedule(() -> {
                if (gen == generation.get() && cdp == null && browserHwnd != 0) {
                    try {
                        connectCdp(gen, port, 5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, 2, TimeUnit.SECONDS);
        }
    }

    /** Blocking: finds the launched browser's app window. Prefers a unique visible Chrome_WidgetWin_1 window
     *  owned by the launched process tree; otherwise tags the page title with a nonce over CDP and matches it. */
    private static long findBrowserWindow(int gen, Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String nonce = "K560SHORTS-" + Long.toHexString(System.nanoTime());
        long lastNonceAt = 0;
        boolean nonceLogged = false;
        while (System.currentTimeMillis() < deadline && gen == generation.get()) {
            Set<Long> pids = new HashSet<>();
            if (process.isAlive()) {
                pids.add(process.pid());
                process.toHandle().descendants().forEach(p -> pids.add(p.pid()));
            }
            List<Long> windows = Win32.visibleChromeWindows();
            long unique = 0;
            int count = 0;
            for (long h : windows) {
                if (!pids.contains((long) Win32.processId(h)) || Win32.title(h).isEmpty()) {
                    continue;
                }
                int[] r = Win32.windowRect(h);
                if (r == null || r[2] < 100 || r[3] < 100) {
                    continue;
                }
                unique = h;
                count++;
            }
            if (count == 1) {
                return unique;
            }

            CdpClient c = cdp;
            if (c != null && c.isOpen()) {
                long now = System.currentTimeMillis();
                if (now - lastNonceAt > 1500) {
                    lastNonceAt = now;
                    if (!nonceLogged) {
                        LOGGER.info("[Shorts] {} candidate windows by pid - matching by page title instead.", count);
                        nonceLogged = true;
                    }
                    try {
                        c.evaluate("(()=>{const n='" + nonce + "';if(document.title!==n){window.__k560t=document.title;"
                                + "document.title=n;setTimeout(()=>{if(document.title===n)document.title=window.__k560t||'YouTube'},4000)}"
                                + "return true})()");
                    } catch (Exception ignored) {
                    }
                }
                for (long h : windows) {
                    if (Win32.title(h).contains(nonce)) {
                        return h;
                    }
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Client tick: attach, place, show/hide, keybinds
    // ------------------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        ShortsConfig cfg = ShortsConfig.getInstance();
        ticks++;
        if (cfg.isEnabled()) {
            pollKeys(client, cfg);
        }

        long h = browserHwnd;
        if (attachedHwnd != 0 && h != attachedHwnd) {
            detachLocal();
        }
        if (h == 0) {
            ProcessHandle ph = browserProcess;
            if (!launching && ph != null && ticks % 20 == 0 && !ph.isAlive()) {
                LOGGER.info("[Shorts] Browser process exited before its window was attached.");
                closeBrowser("process exited", false);
            }
            return;
        }
        if (!isSupported()) {
            return;
        }
        if (!cfg.isEnabled()) {
            closeBrowser("feature disabled", false);
            return;
        }
        Win32.User32 u = Win32.user32();
        try {
            if (!u.IsWindow(h)) {
                LOGGER.info("[Shorts] Browser window 0x{} is gone (closed by the user?).", Long.toHexString(h));
                closeBrowser("window closed", true);
                return;
            }
            if (ticks % 10 == 0) {
                ProcessHandle ph = browserProcess;
                if (ph != null && !ph.isAlive()) {
                    LOGGER.info("[Shorts] Browser process {} exited.", ph.pid());
                    closeBrowser("process exited", true);
                    return;
                }
            }
            if (attachedHwnd == 0 && !attach(client, h)) {
                return;
            }
            updatePlacement(client, cfg, u, h);
        } catch (Throwable t) {
            LOGGER.error("[Shorts] Window management failed - closing the browser.", t);
            closeBrowser("window management error", true);
        }
    }

    private static long currentMcHwnd(Minecraft client) {
        try {
            if (client.getWindow() == null) {
                return 0;
            }
            long glfw = client.getWindow().handle();
            return glfw == 0 ? 0 : GLFWNativeWin32.glfwGetWin32Window(glfw);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean attach(Minecraft client, long h) {
        long mc = currentMcHwnd(client);
        if (mc == 0) {
            return false;
        }
        mcHwnd = mc;
        Win32.User32 u = Win32.user32();
        u.ShowWindow(h, Win32.SW_HIDE);

        long style = u.GetWindowLongPtrW(h, Win32.GWL_STYLE);
        long newStyle = style & ~Win32.FRAME_STYLES;
        if (newStyle != style) {
            u.SetWindowLongPtrW(h, Win32.GWL_STYLE, newStyle);
        }
        long ex = u.GetWindowLongPtrW(h, Win32.GWL_EXSTYLE);
        long newEx = (ex & ~Win32.WS_EX_APPWINDOW) | Win32.WS_EX_TOOLWINDOW;
        if (newEx != ex) {
            u.SetWindowLongPtrW(h, Win32.GWL_EXSTYLE, newEx);
        }
        u.SetWindowLongPtrW(h, Win32.GWLP_HWNDPARENT, mc);
        u.SetWindowPos(h, 0, 0, 0, 0, 0, Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOZORDER
                | Win32.SWP_NOACTIVATE | Win32.SWP_NOOWNERZORDER | Win32.SWP_FRAMECHANGED);
        boolean ownerOk = u.GetWindow(h, Win32.GW_OWNER) == mc;
        removeDwmBorder(h);

        attachedHwnd = h;
        shown = false;
        lastWindowRect = null;
        lastRegion = null;
        appliedOpacity = -1;
        pausedByHide = false;
        driftLogs = 0;
        LOGGER.info("[Shorts] Attached window 0x{}: owner={} (Minecraft 0x{}), style 0x{} -> 0x{}, exstyle 0x{} -> 0x{}.",
                Long.toHexString(h), ownerOk ? "set" : "FAILED", Long.toHexString(mc),
                Long.toHexString(style), Long.toHexString(newStyle), Long.toHexString(ex), Long.toHexString(newEx));
        if (refocusMcOnAttach) {
            refocusMcOnAttach = false;
            GLFW.glfwFocusWindow(client.getWindow().handle());
            LOGGER.info("[Shorts] Returned focus to Minecraft.");
        }
        return true;
    }

    /** Windows 11 draws a thin accent-colour border around every top-level window via DWM, independent of the
     *  WS_* frame styles already stripped above - that's what was still visible as a white border around the
     *  cropped, caption-less Shorts window (killer560 screenshot 2026-09-20_15.18.59.png). Best-effort: silently
     *  does nothing on Windows 10 (no DWMWA_BORDER_COLOR support) or if dwmapi failed to load. */
    private static void removeDwmBorder(long h) {
        Win32.Dwmapi dwm = Win32.dwmapi();
        if (dwm == null) {
            return;
        }
        try {
            dwm.DwmSetWindowAttribute(h, Win32.DWMWA_BORDER_COLOR, new int[]{Win32.DWMWA_COLOR_NONE}, 4);
        } catch (Throwable ignored) {
        }
    }

    private static void detachLocal() {
        LOGGER.info("[Shorts] Detached from window 0x{}.", Long.toHexString(attachedHwnd));
        attachedHwnd = 0;
        shown = false;
        lastWindowRect = null;
        lastRegion = null;
        appliedOpacity = -1;
        pausedByHide = false;
        editMode = false;
        dvdInitialized = false;
    }

    private static void updatePlacement(Minecraft client, ShortsConfig cfg, Win32.User32 u, long h) {
        if (editMode) {
            // Fully hands-off while he's dragging/resizing it himself - real OS window chrome does the work,
            // and fighting it with our own SetWindowPos every tick would just make it fight back.
            return;
        }
        long mc = mcHwnd;
        int[] mcRect = null;
        if (u.IsWindow(mc) && !u.IsIconic(mc)) {
            mcRect = Win32.clientRectOnScreen(mc);
            if (mcRect != null && (mcRect[2] <= 0 || mcRect[3] <= 0)) {
                mcRect = null;
            }
        }

        String hideReason = null;
        if (cfg.isHidden()) {
            hideReason = "toggled off";
        } else if (mcRect == null) {
            hideReason = "Minecraft minimized";
        } else if (cfg.isHideWhenUnfocused() && !isMcOrBrowserForeground(u, mc, h)) {
            hideReason = "Minecraft unfocused";
        }
        boolean wantShown = hideReason == null;

        if (wantShown) {
            int[] content = cfg.getPlacementMode() == ShortsConfig.PlacementMode.DVD
                    ? advanceDvdRect(cfg, mcRect) : computeContentRect(cfg, mcRect);
            int[] in = insets;
            int[] win = {content[0] - in[0], content[1] - in[1], content[2] + in[0] + in[2], content[3] + in[1] + in[3]};
            if (!Arrays.equals(win, lastWindowRect)) {
                u.SetWindowPos(h, 0, win[0], win[1], win[2], win[3],
                        Win32.SWP_NOACTIVATE | Win32.SWP_NOZORDER | Win32.SWP_NOOWNERZORDER);
                boolean resized = lastWindowRect == null || lastWindowRect[2] != win[2] || lastWindowRect[3] != win[3];
                lastWindowRect = win;
                if (resized) {
                    scheduleInsetMeasure(700);
                }
            }
            int[] region = {in[0], in[1], in[0] + content[2], in[1] + content[3]};
            if (!Arrays.equals(region, lastRegion)) {
                boolean crop = (in[0] | in[1] | in[2] | in[3]) != 0;
                long rgn = crop ? Win32.gdi32().CreateRectRgn(region[0], region[1], region[2], region[3]) : 0L;
                if (u.SetWindowRgn(h, rgn, true) == 0 && rgn != 0) {
                    Win32.gdi32().DeleteObject(rgn);
                }
                lastRegion = region;
            }
            applyOpacity(cfg, u, h);
        }

        if (wantShown != shown) {
            u.ShowWindow(h, wantShown ? Win32.SW_SHOWNOACTIVATE : Win32.SW_HIDE);
            shown = wantShown;
            LOGGER.info("[Shorts] Window {}.", wantShown ? "shown" : "hidden (" + hideReason + ")");
            if (!wantShown && cfg.isPauseWhenHidden()) {
                runCommand("pause (hidden)", c -> {
                    boolean wasPlaying = "paused".equals(str(c.evaluate(pauseJs())));
                    client.execute(() -> pausedByHide = wasPlaying);
                    return wasPlaying ? "paused" : "was not playing";
                });
            } else if (wantShown && pausedByHide) {
                pausedByHide = false;
                runCommand("resume (shown)", c -> str(c.evaluate(playJs())));
            }
        }

        if (shown && ticks % 100 == 0) {
            scheduleInsetMeasure(0);
        }
        if (ticks % 40 == 0) {
            checkDrift(u, h, mc);
        }
    }

    private static boolean isMcOrBrowserForeground(Win32.User32 u, long mc, long h) {
        long fg = u.GetForegroundWindow();
        if (fg == 0) {
            return false;
        }
        if (fg == mc || fg == h) {
            return true;
        }
        ProcessHandle ph = browserProcess;
        return ph != null && Win32.processId(fg) == ph.pid();
    }

    private static void applyOpacity(ShortsConfig cfg, Win32.User32 u, long h) {
        int op = cfg.getOpacity();
        if (op == appliedOpacity) {
            return;
        }
        long ex = u.GetWindowLongPtrW(h, Win32.GWL_EXSTYLE);
        if (op >= 100) {
            if ((ex & Win32.WS_EX_LAYERED) != 0) {
                u.SetWindowLongPtrW(h, Win32.GWL_EXSTYLE, ex & ~Win32.WS_EX_LAYERED);
                LOGGER.info("[Shorts] Opacity back to 100% (layered style removed).");
            }
        } else {
            if ((ex & Win32.WS_EX_LAYERED) == 0) {
                u.SetWindowLongPtrW(h, Win32.GWL_EXSTYLE, ex | Win32.WS_EX_LAYERED);
                LOGGER.info("[Shorts] Opacity enabled (layered style added).");
            }
            u.SetLayeredWindowAttributes(h, 0, (byte) Math.round(op * 255 / 100.0), Win32.LWA_ALPHA);
        }
        appliedOpacity = op;
    }

    /** The browser can re-apply its own frame styles/owner (e.g. after page fullscreen) - re-strip. */
    private static void checkDrift(Win32.User32 u, long h, long mc) {
        long style = u.GetWindowLongPtrW(h, Win32.GWL_STYLE);
        boolean styleDrift = (style & Win32.FRAME_STYLES) != 0;
        boolean ownerDrift = u.GetWindow(h, Win32.GW_OWNER) != mc;
        if (!styleDrift && !ownerDrift) {
            return;
        }
        if (styleDrift) {
            u.SetWindowLongPtrW(h, Win32.GWL_STYLE, style & ~Win32.FRAME_STYLES);
        }
        if (ownerDrift) {
            u.SetWindowLongPtrW(h, Win32.GWLP_HWNDPARENT, mc);
        }
        u.SetWindowPos(h, 0, 0, 0, 0, 0, Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOZORDER
                | Win32.SWP_NOACTIVATE | Win32.SWP_NOOWNERZORDER | Win32.SWP_FRAMECHANGED);
        lastWindowRect = null;
        lastRegion = null;
        appliedOpacity = -1;
        if (driftLogs < 5) {
            driftLogs++;
            LOGGER.info("[Shorts] Browser reset its window ({}{}) - re-applied.", styleDrift ? "frame styles" : "",
                    ownerDrift ? (styleDrift ? ", owner" : "owner") : "");
        }
    }

    /** @return {x, y, w, h} of the video area in screen pixels for a Minecraft client rect {x, y, w, h},
     *  per the configured {@link ShortsConfig.PlacementMode}. ANCHORED is the original, sole behaviour. */
    static int[] computeContentRect(ShortsConfig cfg, int[] mcRect) {
        return cfg.getPlacementMode() == ShortsConfig.PlacementMode.CUSTOM
                ? customRect(cfg, mcRect) : anchoredRect(cfg, mcRect);
    }

    /** Width/height only (the 9:16 sizing math), shared by ANCHORED and DVD placement - DVD bounces the
     *  window around, but its size still follows the same Size/Margin sliders. */
    private static int[] sizeFor(ShortsConfig cfg, int[] mcRect) {
        int margin = cfg.getMargin();
        int availW = Math.max(9, mcRect[2] - 2 * margin);
        int availH = Math.max(16, mcRect[3] - 2 * margin);
        int height = Math.min(availH, (int) Math.round(mcRect[3] * cfg.getSizePercent() / 100.0));
        int width = (int) Math.round(height * 9 / 16.0);
        if (width > availW) {
            width = availW;
            height = (int) Math.round(width * 16 / 9.0);
        }
        return new int[]{width, height};
    }

    private static int[] anchoredRect(ShortsConfig cfg, int[] mcRect) {
        int[] wh = sizeFor(cfg, mcRect);
        int width = wh[0], height = wh[1];
        int margin = cfg.getMargin();
        ShortsConfig.Anchor a = cfg.getAnchor();
        boolean left = a == ShortsConfig.Anchor.LEFT_CENTER || a == ShortsConfig.Anchor.LEFT_TOP || a == ShortsConfig.Anchor.LEFT_BOTTOM;
        int x = left ? mcRect[0] + margin : mcRect[0] + mcRect[2] - margin - width;
        int y = switch (a) {
            case LEFT_TOP, RIGHT_TOP -> mcRect[1] + margin;
            case LEFT_BOTTOM, RIGHT_BOTTOM -> mcRect[1] + mcRect[3] - margin - height;
            default -> mcRect[1] + (mcRect[3] - height) / 2;
        };
        return new int[]{x, y, width, height};
    }

    /** PlacementMode.CUSTOM: the free rect {@link #toggleEditMode} saved, relative to Minecraft's own
     *  client-area top-left (so it still follows the game window if that moves, same as ANCHORED does). */
    private static int[] customRect(ShortsConfig cfg, int[] mcRect) {
        return new int[]{mcRect[0] + cfg.getCustomX(), mcRect[1] + cfg.getCustomY(), cfg.getCustomW(), cfg.getCustomH()};
    }

    /**
     * PlacementMode.DVD (2026-09-21 expansion, killer560 item 8.8's "DVD compatibility"). Chosen reading:
     * the hub/dvd feature bounces gifs/text drawn INSIDE Minecraft's own GUI - it has no concept of an
     * external OS window at all, so "compatible" can't mean literally interoperating with it in code
     * without either (a) teaching hub/dvd about a foreign HWND, or (b) giving the Shorts window the same
     * bounce behaviour itself. (b) is the one that's actually buildable from here without touching
     * hub/dvd (out of scope for this package) or fighting a second system for ownership of the same
     * screen space: it reuses the exact same reflect-off-the-edges physics as {@code DvdFeature}, applied
     * to the real window via the same {@code SetWindowPos} call {@link #updatePlacement} already makes
     * every tick for ANCHORED placement. Bounces within Minecraft's own client area, at the Size/Margin
     * sliders' size - Anchor is ignored in this mode (there's no anchor to bounce around).
     */
    private static int[] advanceDvdRect(ShortsConfig cfg, int[] mcRect) {
        int[] wh = sizeFor(cfg, mcRect);
        int w = wh[0], h = wh[1];
        long now = System.currentTimeMillis();
        if (!dvdInitialized || dvdW != w || dvdH != h) {
            // (Re)seed on first use and whenever the configured size changes, so a resized box can't end up
            // parked out of bounds - same trigger DvdFeature itself doesn't need (its boxes don't resize
            // live), but this one's size tracks a slider so it has to handle it.
            dvdW = w;
            dvdH = h;
            dvdX = Math.min(Math.max(0, dvdX), Math.max(0, mcRect[2] - w));
            dvdY = Math.min(Math.max(0, dvdY), Math.max(0, mcRect[3] - h));
            if (!dvdInitialized) {
                // Start from wherever ANCHORED placement would have put it (clamped into bounds) rather than
                // a random spot, so switching Placement to DVD Bounce doesn't visibly teleport the window
                // before it starts moving.
                int[] anchored = anchoredRect(cfg, mcRect);
                dvdX = Math.min(Math.max(0, anchored[0] - mcRect[0]), Math.max(0, mcRect[2] - w));
                dvdY = Math.min(Math.max(0, anchored[1] - mcRect[1]), Math.max(0, mcRect[3] - h));
                double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
                dvdVx = (float) Math.cos(angle);
                dvdVy = (float) Math.sin(angle);
            }
            dvdInitialized = true;
            dvdLastUpdateMs = now;
        }
        float dt = Math.min(0.25f, (now - dvdLastUpdateMs) / 1000f);
        dvdLastUpdateMs = now;
        float mag = (float) Math.sqrt(dvdVx * dvdVx + dvdVy * dvdVy);
        if (mag > 0.0001f) {
            dvdVx = dvdVx / mag * DVD_SPEED_PX_PER_SEC;
            dvdVy = dvdVy / mag * DVD_SPEED_PX_PER_SEC;
        }
        float maxX = Math.max(0, mcRect[2] - w);
        float maxY = Math.max(0, mcRect[3] - h);
        float newX = dvdX + dvdVx * dt;
        float newY = dvdY + dvdVy * dt;
        if (newX < 0) {
            newX = 0;
            dvdVx = -dvdVx;
        } else if (newX > maxX) {
            newX = maxX;
            dvdVx = -dvdVx;
        }
        if (newY < 0) {
            newY = 0;
            dvdVy = -dvdVy;
        } else if (newY > maxY) {
            newY = maxY;
            dvdVy = -dvdVy;
        }
        dvdX = newX;
        dvdY = newY;
        return new int[]{mcRect[0] + Math.round(dvdX), mcRect[1] + Math.round(dvdY), w, h};
    }

    // ------------------------------------------------------------------------------------------------
    // Edit Window (2026-09-21 expansion, killer560 item 8.8: "its own edit window (drag to resize,
    // scroll to zoom, drag to move)")
    // ------------------------------------------------------------------------------------------------

    /**
     * Drag-to-move and drag-to-resize are genuinely deliverable: this just gives the already-real Chrome
     * window back its normal title bar and resize border (the same {@code WS_*} styles {@link #attach}
     * strips for the pinned overlay look) so Windows itself handles the drag - no mod code moves the
     * window while editing. Turning it back off reads the window's final rect and saves it as
     * {@link ShortsConfig.PlacementMode#CUSTOM}, then re-strips the frame and re-crops it.
     * <p>
     * "Scroll to zoom" is NOT something this can add as a live gesture - Chrome's own Ctrl+scroll (or
     * Ctrl+=/-) already zooms a real window like this one once it can take input (which happens the
     * moment it's clicked, e.g. to start dragging it), so nothing further is needed for that to work; the
     * mod's own contribution is the separate Zoom slider in the tab (a launch-time
     * {@code --force-device-scale-factor}, see {@link BrowserLauncher#buildCommand}), which is NOT live -
     * it needs Relaunch Browser to take effect, and plain mouse-wheel-without-Ctrl over the window just
     * does whatever the page under the cursor normally does with a wheel event (page scroll on most sites).
     */
    public static void toggleEditMode() {
        if (!isSupported()) {
            ModChat.send(CHAT, ModChat.bad(unsupportedReason));
            return;
        }
        long h = attachedHwnd;
        if (h == 0) {
            ModChat.send(CHAT, ModChat.text("Launch the browser first."));
            return;
        }
        Win32.User32 u = Win32.user32();
        editMode = !editMode;
        if (editMode) {
            long style = u.GetWindowLongPtrW(h, Win32.GWL_STYLE);
            u.SetWindowLongPtrW(h, Win32.GWL_STYLE, style | Win32.EDIT_FRAME_STYLES);
            u.SetWindowRgn(h, 0, true); // drop the title-bar crop - a real caption is showing now, nothing to hide
            u.SetWindowPos(h, 0, 0, 0, 0, 0, Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOZORDER
                    | Win32.SWP_NOACTIVATE | Win32.SWP_NOOWNERZORDER | Win32.SWP_FRAMECHANGED);
            LOGGER.info("[Shorts] Edit Window on: real title bar/border restored, auto-placement paused.");
            ModChat.send(CHAT, ModChat.text("Edit Window: drag the title bar to move it, drag an edge/corner to "
                    + "resize it. Turn Edit Window off again to lock the new position/size in."));
        } else {
            int[] wr = Win32.windowRect(h);
            long mc = mcHwnd;
            int[] mcRect = mc != 0 && u.IsWindow(mc) ? Win32.clientRectOnScreen(mc) : null;
            if (wr != null && mcRect != null && mcRect[2] > 0 && mcRect[3] > 0) {
                ShortsConfig cfg = ShortsConfig.getInstance();
                cfg.setPlacementMode(ShortsConfig.PlacementMode.CUSTOM);
                cfg.setCustomX(wr[0] - mcRect[0]);
                cfg.setCustomY(wr[1] - mcRect[1]);
                cfg.setCustomW(wr[2]);
                cfg.setCustomH(wr[3]);
                cfg.save();
                ModChat.send(CHAT, ModChat.good("Position saved (Placement: Custom)."));
            } else {
                ModChat.send(CHAT, ModChat.bad("Couldn't read the window's new position - not saved."));
            }
            long style = u.GetWindowLongPtrW(h, Win32.GWL_STYLE);
            u.SetWindowLongPtrW(h, Win32.GWL_STYLE, style & ~Win32.FRAME_STYLES);
            u.SetWindowPos(h, 0, 0, 0, 0, 0, Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOZORDER
                    | Win32.SWP_NOACTIVATE | Win32.SWP_NOOWNERZORDER | Win32.SWP_FRAMECHANGED);
            lastWindowRect = null;
            lastRegion = null;
            appliedOpacity = -1;
            LOGGER.info("[Shorts] Edit Window off: frame re-stripped, auto-placement resumed.");
            scheduleInsetMeasure(300);
        }
    }

    public static boolean isEditMode() {
        return editMode;
    }

    // ------------------------------------------------------------------------------------------------
    // Title-bar crop measurement (background)
    // ------------------------------------------------------------------------------------------------

    private static void scheduleInsetMeasure(long delayMs) {
        if (measureScheduled.compareAndSet(false, true)) {
            EXEC.schedule(() -> {
                measureScheduled.set(false);
                measureInsets();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void measureInsets() {
        try {
            int[] a = measureOnce();
            if (a == null) {
                return;
            }
            Thread.sleep(250);
            int[] b = measureOnce();
            if (b == null || !Arrays.equals(a, b)) {
                scheduleInsetMeasure(800); // mid-resize - try again once it settles
                return;
            }
            if (!Arrays.equals(b, insets)) {
                insets = b;
                LOGGER.info("[Shorts] Browser frame insets measured: left {} top {} right {} bottom {} px (cropped away).",
                        b[0], b[1], b[2], b[3]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private static int[] measureOnce() throws Exception {
        CdpClient c = cdp;
        long h = browserHwnd;
        if (c == null || !c.isOpen() || h == 0) {
            return null;
        }
        JsonElement v = c.evaluate("JSON.stringify([window.innerWidth,window.innerHeight,window.devicePixelRatio])");
        if (v == null || !v.isJsonPrimitive()) {
            return null;
        }
        JsonArray arr = JsonParser.parseString(v.getAsString()).getAsJsonArray();
        double dpr = arr.get(2).getAsDouble();
        int contentW = (int) Math.round(arr.get(0).getAsDouble() * dpr);
        int contentH = (int) Math.round(arr.get(1).getAsDouble() * dpr);
        int[] wr = Win32.windowRect(h);
        if (wr == null || contentW <= 0 || contentH <= 0) {
            return null;
        }
        int side = Math.max(0, (wr[2] - contentW) / 2);
        int right = Math.max(0, wr[2] - contentW - side);
        int bottom = Math.min(side, Math.max(0, wr[3] - contentH));
        int top = Math.max(0, wr[3] - contentH - bottom);
        if (top > wr[3] / 3 || side > wr[2] / 4) {
            return null; // implausible (page mid-layout) - ignore
        }
        return new int[]{side, top, right, bottom};
    }

    // ------------------------------------------------------------------------------------------------
    // Playback commands
    // ------------------------------------------------------------------------------------------------

    private interface CdpAction {
        String run(CdpClient client) throws Exception;
    }

    private static final String ACTIVE_VIDEO_JS =
            "(document.querySelector('ytd-reel-video-renderer[is-active] video')"
                    + "||document.querySelector('#shorts-player video')||document.querySelector('video'))";

    private static String pauseJs() {
        return "(()=>{const v=" + ACTIVE_VIDEO_JS + ";if(v&&!v.paused){v.pause();return 'paused'}return 'idle'})()";
    }

    private static String playJs() {
        return "(()=>{const v=" + ACTIVE_VIDEO_JS + ";if(v&&v.paused){v.play();return 'playing'}return 'idle'})()";
    }

    /**
     * Background thread. Applies the saved volume once the active video/player actually exists, and keeps it
     * applied as Shorts' feed swaps in each new video.
     * <p>
     * killer560: "if i have it set to 1 percent and open yt shorts, then it defaults to the default volume
     * until I reset it back to 1 percent then it updates". The old code pushed the volume once, 3 seconds
     * after launch - a guess: if the player wasn't ready yet by then the call landed on nothing, and the very
     * next Short (a new {@code <video>} element - this is a feed, not one page) reset it right back.
     * <p>
     * This installs a small MutationObserver plus a cheap safety-poll INSIDE the page instead (survives exactly
     * as long as the page does - CDP reconnect re-sends it, same as the theme emulation above). Every check
     * reads the live {@code window.__k560Vol} and the current active video/player, and only calls
     * {@code setVolume}/writes {@code .volume} when that element hasn't already been set to that value - so once
     * it sticks for a given video, nothing keeps re-touching it (and it won't fight a live YouTube volume
     * change) until either a new video appears or {@link #applyVolume()} bumps {@code __k560Vol} itself.
     */
    private static String installVolumeHookNow(CdpClient c) throws Exception {
        int vol = ShortsConfig.getInstance().getVolume();
        return str(c.evaluate(volumeHookJs(vol)));
    }

    private static String volumeHookJs(int volume) {
        return "(()=>{window.__k560Vol=" + volume + ";"
                + "const apply=()=>{const n=window.__k560Vol;if(n<0)return;"
                + "const v=" + ACTIVE_VIDEO_JS + ";if(!v||v.__k560AppliedVol===n)return;"
                + "const p=document.querySelector('#shorts-player');"
                + "if(p&&typeof p.setVolume==='function'){p.setVolume(n);if(n>0&&typeof p.isMuted==='function'&&p.isMuted())p.unMute();}"
                + "else{v.volume=n/100;if(n>0)v.muted=false;}"
                + "v.__k560AppliedVol=n;};"
                + "if(window.__k560VolHook){apply();return 'updated'}"
                + "window.__k560VolHook=new MutationObserver(apply);"
                + "window.__k560VolHook.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['is-active']});"
                // Fallback for the case the fixed delay used to miss: the player element can exist before its
                // setVolume API is ready, with no DOM mutation marking the moment it becomes usable.
                + "setInterval(apply,400);"
                + "apply();return 'installed'})()";
    }

    // ------------------------------------------------------------------------------------------------
    // Site selection (2026-09-21 expansion, killer560 item 8.8: "TikTok, Reels, any site... normal
    // videos, Twitch")
    // ------------------------------------------------------------------------------------------------

    /** @return the app-mode launch URL for the currently configured site, falling back to YouTube Shorts
     *  if CUSTOM has no usable URL typed in yet. */
    private static String resolveUrl(ShortsConfig cfg) {
        if (cfg.getSite() == ShortsConfig.Site.CUSTOM) {
            String u = BrowserLauncher.normalizeUrl(cfg.getCustomUrl());
            if (u != null) {
                return u;
            }
            LOGGER.warn("[Shorts] Custom URL is blank - falling back to YouTube Shorts.");
        }
        return cfg.getSite() == ShortsConfig.Site.CUSTOM ? ShortsConfig.Site.YOUTUBE_SHORTS.defaultUrl : cfg.getSite().defaultUrl;
    }

    // ------------------------------------------------------------------------------------------------
    // Sign-in (2026-09-20, killer560: "please implement the login through my mod by having me click a button
    // that says sign in to youtube shorts or sign into tictoc or reels once you finish implementing those" -
    // 2026-09-21: TikTok/Reels/Twitch/custom all now exist, so this follows whatever site is selected)
    // ------------------------------------------------------------------------------------------------

    /**
     * Opens a real, decorated sign-in/site page in an ordinary browser window on the Shorts profile, for
     * killer560 to log into by hand. The mod never sees, asks for, or stores a password - it only starts a
     * normal browser process pointed at a normal URL; everything past that is between him and the site, same
     * as if he'd typed the address himself. See {@link BrowserLauncher#buildSignInCommand} for why this can't
     * collide with an overlay that's already running, and {@link ShortsConfig.Site} for why the same profile
     * being used for every site is exactly what keeps him signed into all of them at once.
     */
    public static void signInToCurrentSite() {
        ShortsConfig cfg = ShortsConfig.getInstance();
        ShortsConfig.Site site = cfg.getSite();
        // CUSTOM has no known login page - the closest real thing is just opening the site itself, decorated,
        // so he can find and use its own sign-in link himself.
        String url = site == ShortsConfig.Site.CUSTOM ? resolveUrl(cfg) : site.signInUrl;
        openSignInWindow(url, site.label);
    }

    /** Kept for anything still calling the pre-expansion name; now just YouTube Shorts specifically. */
    public static void signInToYouTube() {
        openSignInWindow(ShortsConfig.Site.YOUTUBE_SHORTS.signInUrl, ShortsConfig.Site.YOUTUBE_SHORTS.label);
    }

    private static void openSignInWindow(String url, String platform) {
        if (!isSupported()) {
            ModChat.send(CHAT, ModChat.bad(unsupportedReason));
            return;
        }
        EXEC.execute(() -> {
            try {
                Path exe = BrowserLauncher.findBrowser(LOGGER);
                if (exe == null) {
                    chatLater(ModChat.bad("Couldn't find Microsoft Edge or Google Chrome - install one and try again."));
                    return;
                }
                Path profile = BrowserLauncher.profileDir();
                Files.createDirectories(profile);
                List<String> cmd = BrowserLauncher.buildSignInCommand(exe, profile, url);
                LOGGER.info("[Shorts] Opening {} sign-in: {}", platform, String.join(" ", cmd));
                new ProcessBuilder(cmd)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                chatLater(ModChat.text("Opened a "), ModChat.value(platform + " sign-in"),
                        ModChat.text(" window - log in there, then close it."));
            } catch (Exception e) {
                LOGGER.error("[Shorts] Sign-in launch failed.", e);
                chatLater(ModChat.bad("Couldn't open the sign-in window: " + e.getMessage()));
            }
        });
    }

    public static void toggleShown() {
        ShortsConfig cfg = ShortsConfig.getInstance();
        if (!isRunning() && !launching) {
            cfg.setHidden(false);
            cfg.save();
            launch();
            return;
        }
        cfg.setHidden(!cfg.isHidden());
        cfg.save();
        LOGGER.info("[Shorts] Overlay toggled {}.", cfg.isHidden() ? "off" : "on");
    }

    /**
     * Best-effort "is a Shorts comments panel open" check (2026-09-21 expansion, killer560 item 8.8:
     * "a comments panel that captures scrolling"), YouTube-only - see the long note above {@link #next()}.
     */
    private static final String COMMENTS_OPEN_JS =
            "(()=>{const p=document.querySelector("
                    + "'ytd-engagement-panel-section-list-renderer[visibility=\"ENGAGEMENT_PANEL_VISIBILITY_EXPANDED\"]');"
                    + "return !!(p&&p.offsetParent)})()";

    /**
     * Whether Next/Previous's key fallback should be skipped right now because a comments panel looks open.
     * Honest scope note (killer560 item 8.8's "comments panel that captures scrolling"): a normal mouse
     * scroll never reaches the browser at all while Minecraft has the mouse captured for gameplay (it's raw
     * input straight to the game, not routed by Windows to whatever's visually under the cursor) - that's
     * exactly why this feature drives Next/Previous with keybinds -> a single simulated ArrowDown/Up key,
     * never a scroll event, so there has never been a "scroll advances the video" behaviour for this mod to
     * redirect. When the mouse ISN'T captured (a menu's open, or the window's unfocused), physical scroll
     * over the browser goes straight to the page and already scrolls whatever's under the cursor - including
     * a comments panel's own scrollable container - as ordinary site behaviour, outside the mod entirely.
     * The one real thing achievable from here: don't let a Next/Previous KEYBIND press yank the feed out
     * from under him while he's reading/typing in comments. Best-effort and YouTube-Shorts-specific (the
     * engagement-panel selector above); not attempted for TikTok/Reels/Twitch, whose comment UIs are
     * different and unverified without a live run - see the tab's tooltip and the staging notes.
     */
    private static boolean commentsOpenGuardBlocks(CdpClient c) {
        if (!ShortsConfig.getInstance().isCommentsScrollGuard()) {
            return false;
        }
        try {
            return "true".equals(str(c.evaluate(COMMENTS_OPEN_JS)));
        } catch (Exception e) {
            return false;
        }
    }

    public static void next() {
        command("next", c -> {
            String r = str(c.evaluate("(()=>{const b=document.querySelector('#navigation-button-down button');"
                    + "if(b&&!b.disabled){b.click();return 'button'}return 'none'})()"));
            if ("button".equals(r)) {
                return "clicked next button";
            }
            if (commentsOpenGuardBlocks(c)) {
                return "skipped (comments panel open)";
            }
            c.pressKey("ArrowDown", "ArrowDown", 40, null);
            return "ArrowDown key";
        });
    }

    public static void previous() {
        command("previous", c -> {
            String r = str(c.evaluate("(()=>{const b=document.querySelector('#navigation-button-up button');"
                    + "if(b&&!b.disabled){b.click();return 'button'}return 'none'})()"));
            if ("button".equals(r)) {
                return "clicked previous button";
            }
            if (commentsOpenGuardBlocks(c)) {
                return "skipped (comments panel open)";
            }
            c.pressKey("ArrowUp", "ArrowUp", 38, null);
            return "ArrowUp key";
        });
    }

    public static void togglePlayPause() {
        command("play/pause", c -> {
            String r = str(c.evaluate("(()=>{const v=" + ACTIVE_VIDEO_JS + ";if(!v)return 'none';"
                    + "if(v.paused){v.play();return 'playing'}v.pause();return 'paused'})()"));
            if ("none".equals(r) || r == null) {
                c.pressKey("k", "KeyK", 75, "k");
                return "k key (no video element found)";
            }
            return r;
        });
    }

    public static void toggleMute() {
        command("mute", c -> {
            String r = str(c.evaluate("(()=>{const p=document.querySelector('#shorts-player');"
                    + "if(p&&typeof p.isMuted==='function'){if(p.isMuted()){p.unMute();return 'unmuted'}p.mute();return 'muted'}"
                    + "const v=" + ACTIVE_VIDEO_JS + ";if(!v)return 'none';v.muted=!v.muted;return v.muted?'muted':'unmuted'})()"));
            if ("muted".equals(r) || "unmuted".equals(r)) {
                chatLater(ModChat.text("Shorts "), ModChat.value(r), ModChat.text("."));
            } else {
                c.pressKey("m", "KeyM", 77, "m");
                r = "m key (no player found)";
            }
            return r;
        });
    }

    /** Applies the saved volume (debounced - safe to call on every slider drag step). Client thread. */
    public static void applyVolume() {
        if (!isRunning() || cdp == null) {
            return;
        }
        if (volumePending.compareAndSet(false, true)) {
            EXEC.schedule(() -> {
                volumePending.set(false);
                int vol = ShortsConfig.getInstance().getVolume();
                if (vol >= 0) {
                    runCommand("volume " + vol, ShortsFeature::installVolumeHookNow);
                }
            }, 150, TimeUnit.MILLISECONDS);
        }
    }

    private static final AtomicBoolean volumePending = new AtomicBoolean();

    /** Applies the saved dark/light theme to the running browser (debounced like the volume). Client thread. */
    public static void applyTheme() {
        if (!isRunning() || cdp == null) {
            return;
        }
        if (themePending.compareAndSet(false, true)) {
            EXEC.schedule(() -> {
                themePending.set(false);
                runCommand("theme", c -> applyThemeNow(c, "settings"));
            }, 150, TimeUnit.MILLISECONDS);
        }
    }

    private static final AtomicBoolean themePending = new AtomicBoolean();

    /**
     * Background thread. Dark/light mode (2026-09-16, killer560: "make an option for dark or light mode") in
     * two layers, both over the page-level CDP session this feature already holds:
     * <ol>
     *   <li>{@code Emulation.setEmulatedMedia} overrides {@code prefers-color-scheme} for the page. YouTube's
     *       default "Device theme" appearance follows that media query live, so this alone flips the theme
     *       (and survives Shorts' own in-page navigation - it only dies with the CDP session, which is why
     *       {@code connectCdp} re-sends it). SYSTEM sends an empty feature list, i.e. clears the override.</li>
     *   <li>A JS nudge on YouTube's own {@code html[dark]} attribute, for a profile whose YouTube Appearance
     *       was set to an explicit Dark/Light instead of Device theme - that setting ignores the media query,
     *       so without this the option would silently do nothing there. Only touched for DARK/LIGHT; SYSTEM
     *       leaves whatever YouTube itself decided, exactly like before the option existed.</li>
     * </ol>
     * Never throws past the caller - {@code runCommand} logs a failure and moves on.
     */
    private static String applyThemeNow(CdpClient c, String why) throws Exception {
        ShortsConfig.Theme theme = ShortsConfig.getInstance().getTheme();
        JsonObject params = new JsonObject();
        JsonArray features = new JsonArray();
        if (theme != ShortsConfig.Theme.SYSTEM) {
            JsonObject scheme = new JsonObject();
            scheme.addProperty("name", "prefers-color-scheme");
            // Amber is a dark theme with the mod's accent painted over it, so it emulates dark too.
            scheme.addProperty("value", theme == ShortsConfig.Theme.LIGHT ? "light" : "dark");
            features.add(scheme);
        }
        params.add("features", features);
        c.send("Emulation.setEmulatedMedia", params).get(5500, TimeUnit.MILLISECONDS);
        applyAmberStyleNow(c, theme == ShortsConfig.Theme.AMBER);
        if (theme == ShortsConfig.Theme.SYSTEM) {
            return "emulation cleared (" + why + ")";
        }
        boolean dark = theme != ShortsConfig.Theme.LIGHT;
        String r = str(c.evaluate("(()=>{const h=document.documentElement;if(!h)return 'no document';"
                + "const want=" + dark + ";if(h.hasAttribute('dark')===want)return 'already';"
                + "if(want)h.setAttribute('dark','');else h.removeAttribute('dark');return 'nudged'})()"));
        return theme.label.toLowerCase(Locale.ROOT) + " emulated, html[dark] " + r + " (" + why + ")";
    }

    /**
     * Amber: this mod's own theme, applied to the Shorts page as a single injected stylesheet.
     * <p>
     * Deliberately narrow. It recolours the progress bar, the "chrome" accents and link/hover colour to the
     * menu's own accent and nothing else - YouTube's class names change constantly, so a stylesheet that
     * tried to restyle the whole player would quietly rot into a broken-looking page. Anything it fails to
     * match simply stays the dark theme underneath it, which is a fine fallback rather than a broken one.
     * The element is removed again when the theme is not Amber, so switching away is clean.
     */
    private static void applyAmberStyleNow(CdpClient c, boolean on) throws Exception {
        // The menu's accent, kept in sync with SectionHeaders/MainMenuTheme's orange by eye rather than by
        // import: this string is CSS, and the GUI constants are ARGB ints for a different renderer.
        final String accent = "#cc6600";
        final String accentBright = "#ff8c1a";
        String js = on
                ? "(()=>{const id='k560-amber';let e=document.getElementById(id);"
                + "if(!e){e=document.createElement('style');e.id=id;(document.head||document.documentElement).appendChild(e);}"
                + "e.textContent=`"
                + ":root{--yt-spec-static-brand-red:" + accent + ";--yt-spec-call-to-action:" + accent + ";"
                + "--yt-spec-text-primary-inverse:#000;--yt-spec-brand-button-background:" + accent + ";}"
                + ".ytp-play-progress,.ytp-swatch-background-color{background:" + accent + " !important;}"
                + ".ytp-scrubber-button{background:" + accentBright + " !important;}"
                + "a{color:" + accentBright + ";}"
                + "`;return 'applied'})()"
                : "(()=>{const e=document.getElementById('k560-amber');if(e){e.remove();return 'removed';}return 'absent'})()";
        try {
            c.evaluate(js);
        } catch (Exception ignored) {
            // A cosmetic overlay must never be the reason the Shorts window stops working.
        }
    }

    /** Client thread entry point for user-triggered commands. */
    private static void command(String name, CdpAction action) {
        if (!isSupported()) {
            ModChat.send(CHAT, ModChat.bad(unsupportedReason));
            return;
        }
        if (launching) {
            ModChat.send(CHAT, ModChat.text("Still launching..."));
            return;
        }
        if (!isRunning()) {
            ModChat.send(CHAT, ModChat.text("Browser isn't running - press your toggle key or use "),
                    ModChat.value("Launch Browser"), ModChat.text(" in the YT Shorts tab."));
            return;
        }
        EXEC.execute(() -> runCommand(name, action));
    }

    /** Background thread: runs a CDP action, reconnecting first if the socket dropped. */
    private static void runCommand(String name, CdpAction action) {
        CdpClient c = cdp;
        if (c == null || !c.isOpen()) {
            int port = devToolsPort;
            if (port > 0 && browserHwnd != 0) {
                try {
                    connectCdp(generation.get(), port, 3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                c = cdp;
            }
        }
        if (c == null || !c.isOpen()) {
            LOGGER.warn("[Shorts] Command '{}' skipped - CDP not connected.", name);
            chatLater(ModChat.bad("Not connected to the browser's controls - try Relaunch Browser."));
            return;
        }
        try {
            String result = action.run(c);
            LOGGER.info("[Shorts] Command '{}' sent ({}).", name, result);
        } catch (Exception e) {
            LOGGER.warn("[Shorts] Command '{}' failed: {}", name, e.toString());
        }
    }

    private static String str(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static void chatLater(Component... parts) {
        Minecraft.getInstance().execute(() -> ModChat.send(CHAT, parts));
    }

    // ------------------------------------------------------------------------------------------------
    // Keybinds
    // ------------------------------------------------------------------------------------------------

    private static void pollKeys(Minecraft client, ShortsConfig cfg) {
        int[] keys = {cfg.getToggleKey(), cfg.getNextKey(), cfg.getPreviousKey(), cfg.getPlayPauseKey(), cfg.getMuteKey()};
        for (int i = 0; i < keys.length; i++) {
            boolean down = keys[i] >= 0 && client.getWindow() != null && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), keys[i]);
            if (down && !keyWasDown[i] && client.screen == null) {
                switch (i) {
                    case 0 -> toggleShown();
                    case 1 -> next();
                    case 2 -> previous();
                    case 3 -> togglePlayPause();
                    default -> toggleMute();
                }
            }
            keyWasDown[i] = down;
        }
    }
}
