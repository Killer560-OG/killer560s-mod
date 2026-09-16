package com.killer560.hub.window;

import com.killer560.hub.windowlayout.WindowMonitors;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Borderless fullscreen: an undecorated window resized/positioned to cover the whole monitor,
 *  as opposed to Minecraft's own real-fullscreen (a dedicated exclusive video mode). Implemented
 *  with raw GLFW calls since {@link Window} has no borderless concept of its own - verified via
 *  javap against the real 26.1.2 jar (see project notes) rather than assumed.
 *  <p>
 *  <b>The one-pixel overhang.</b> A borderless window whose client area is <i>exactly</i> the monitor's
 *  video mode is what Windows' DWM looks for before it promotes the window to <i>direct flip /
 *  independent flip</i>: the app's swap-chain is scanned out straight to the display and DWM stops
 *  compositing that region. Two user-visible consequences, both reported by killer560 on 2026-09-15:
 *  the desktop-duplication screen recorder froze on a stale frame ("it doesn't show my mod menu open on
 *  the recording side... the entire game freezes on the video side"), and the mouse pointer stopped being
 *  drawn over the game at all - which only shows up as a bug when a screen is open, because that is the
 *  only time Minecraft asks for a visible cursor. Direct flip additionally requires the swap-chain to
 *  match the display mode exactly, so making the window one pixel taller (or wider) than the monitor -
 *  with the extra row hanging off the edge of the desktop where nothing can see it - disqualifies the
 *  promotion and keeps the window a normal composited DWM window. Nothing about the visible coverage
 *  changes: the monitor is still covered edge to edge with no border. */
public final class WindowModeFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-window");

    /** Extra pixels pushed outside the monitor so DWM keeps compositing (see the class javadoc). */
    private static final int OVERHANG = 1;

    private static boolean appliedStartupState = false;
    private static Class<?> lastLoggedScreen = Void.class;

    private WindowModeFeature() {
    }

    /** Re-applies the saved borderless state once, on the first client tick after boot - the GLFW
     *  window isn't guaranteed to exist yet at {@code onInitializeClient()} time, but it always does
     *  by the first tick. Also carries the per-screen-change diagnostic (one log line per screen
     *  change, never per frame). */
    public static void tickApplyOnce(Minecraft client) {
        if (!appliedStartupState) {
            appliedStartupState = true;
            if (WindowModeConfig.getInstance().isBorderlessFullscreenEnabled()) {
                enable(client, true);
            }
        }
        logScreenChange(client);
    }

    /** Profile switch ({@code ProfileManager#applyProfile}): after {@link WindowModeConfig#load()} picked up
     *  a different borderless setting, puts the real window into that state so the next toggle doesn't go
     *  the wrong way. Before the first tick has applied the startup state this does nothing - that tick
     *  reads the freshly loaded config itself. */
    public static void applyConfigAfterReload(boolean wasEnabled) {
        if (!appliedStartupState) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        boolean nowEnabled = WindowModeConfig.getInstance().isBorderlessFullscreenEnabled();
        if (nowEnabled == wasEnabled) {
            return;
        }
        if (nowEnabled) {
            enable(client, false);
        } else {
            disable(client);
        }
    }

    public static void toggle() {
        Minecraft client = Minecraft.getInstance();
        WindowModeConfig cfg = WindowModeConfig.getInstance();
        if (cfg.isBorderlessFullscreenEnabled()) {
            cfg.setBorderlessFullscreenEnabled(false);
            cfg.save();
            disable(client);
        } else {
            cfg.setBorderlessFullscreenEnabled(true);
            cfg.save();
            enable(client, false);
        }
    }

    /** @param fromStartup true when re-applying the saved state on the first tick after boot. The window
     *  at that point is just Minecraft's default startup window, not a size the user chose, so it must not
     *  replace already-saved windowed bounds (2026-09-15 persistence audit: it did, so leaving borderless
     *  after every restart snapped back to the startup size instead of the user's real windowed bounds). */
    private static void enable(Minecraft client, boolean fromStartup) {
        Window window = client.getWindow();
        WindowModeConfig cfg = WindowModeConfig.getInstance();

        if (window.isFullscreen()) {
            window.setWindowed(cfg.getSavedWindowedWidth(), cfg.getSavedWindowedHeight());
            // setWindowed clears Window.fullscreen and calls setMode() itself, but leaves the separate
            // actuallyFullscreen flag stale - so Minecraft.runTick's next Window.updateFullscreenIfChanged()
            // would notice the mismatch and run setMode() a SECOND time, throwing the window back to its
            // stale windowed bounds and undoing the borderless geometry applied below. Flushing that
            // deferred sync here (it is a no-op once the two flags agree) keeps it from clobbering us.
            window.updateFullscreenIfChanged();
        } else if (!fromStartup || !cfg.hasSavedWindowedBounds()) {
            cfg.saveWindowedBounds(window.getX(), window.getY(), window.getWidth(), window.getHeight());
            cfg.save();
        }

        Monitor monitor = window.findBestMonitor();
        if (monitor == null) {
            return;
        }
        VideoMode mode = monitor.getCurrentMode();
        int[] rect = borderlessRect(monitor.getX(), monitor.getY(), mode.getWidth(), mode.getHeight());

        GLFW.glfwSetWindowAttrib(window.handle(), GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, rect[0], rect[1], rect[2], rect[3], GLFW.GLFW_DONT_CARE);
        reapplyCursorState(client);
        LOGGER.info("Borderless ON: monitor {}x{} at {},{} -> window {}x{} at {},{} ({} overhang, composited)",
                mode.getWidth(), mode.getHeight(), monitor.getX(), monitor.getY(),
                rect[2], rect[3], rect[0], rect[1], OVERHANG > 0 ? OVERHANG + "px" : "no");
    }

    private static void disable(Minecraft client) {
        Window window = client.getWindow();
        WindowModeConfig cfg = WindowModeConfig.getInstance();

        int x = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedX() : 100;
        int y = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedY() : 100;
        int width = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedWidth() : 854;
        int height = cfg.hasSavedWindowedBounds() ? cfg.getSavedWindowedHeight() : 480;

        GLFW.glfwSetWindowAttrib(window.handle(), GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, x, y, width, height, GLFW.GLFW_DONT_CARE);
        reapplyCursorState(client);
        LOGGER.info("Borderless OFF: window {}x{} at {},{}", width, height, x, y);
    }

    /** The window rect to use for borderless on the monitor {@code (mx, my, mw, mh)}: the monitor itself
     *  plus {@link #OVERHANG} pixels pushed off one edge of that monitor, chosen on the side where no other
     *  monitor is sitting so the sliver lands on empty desktop rather than over a neighbouring screen.
     *  @return {x, y, width, height} */
    private static int[] borderlessRect(int mx, int my, int mw, int mh) {
        if (OVERHANG <= 0) {
            return new int[]{mx, my, mw, mh};
        }
        int o = OVERHANG;
        // bottom, right, top, left - first side with nothing next to it wins; bottom is the fallback.
        int[][] candidates = {
                {mx, my, mw, mh + o, mx, my + mh, mw, o},
                {mx, my, mw + o, mh, mx + mw, my, o, mh},
                {mx, my - o, mw, mh + o, mx, my - o, mw, o},
                {mx - o, my, mw + o, mh, mx - o, my, o, mh},
        };
        List<WindowMonitors.MonitorInfo> monitors;
        try {
            monitors = WindowMonitors.list();
        } catch (Throwable t) {
            monitors = List.of();
        }
        for (int[] c : candidates) {
            if (!occupied(monitors, mx, my, mw, mh, c[4], c[5], c[6], c[7])) {
                return new int[]{c[0], c[1], c[2], c[3]};
            }
        }
        int[] fallback = candidates[0];
        return new int[]{fallback[0], fallback[1], fallback[2], fallback[3]};
    }

    /** Whether any monitor other than {@code (mx, my, mw, mh)} overlaps the strip {@code (sx, sy, sw, sh)}. */
    private static boolean occupied(List<WindowMonitors.MonitorInfo> monitors, int mx, int my, int mw, int mh,
                                    int sx, int sy, int sw, int sh) {
        for (WindowMonitors.MonitorInfo m : monitors) {
            if (m.x() == mx && m.y() == my && m.width() == mw && m.height() == mh) {
                continue;
            }
            if (sx < m.x() + m.width() && m.x() < sx + sw && sy < m.y() + m.height() && m.y() < sy + sh) {
                return true;
            }
        }
        return false;
    }

    /** Re-applies the cursor mode after the window geometry changes. Minecraft only issues a GLFW cursor mode
     *  when its own {@code mouseGrabbed} flag flips ({@code MouseHandler.grabMouse}/{@code releaseMouse} both
     *  return early when the flag already matches), so a geometry change that happened to lose the mode would
     *  never be corrected on its own. Cheap insurance; note that it is NOT what fixed the invisible cursor -
     *  see the class javadoc, that was DWM dropping the window out of composition. */
    private static void reapplyCursorState(Minecraft client) {
        try {
            Window window = client.getWindow();
            boolean grabbed = client.mouseHandler.isMouseGrabbed() && client.screen == null;
            InputConstants.grabOrReleaseMouse(window,
                    grabbed ? InputConstants.CURSOR_DISABLED : InputConstants.CURSOR_NORMAL,
                    client.mouseHandler.xpos(), client.mouseHandler.ypos());
        } catch (Throwable ignored) {
            // Never let a cursor fix-up break the window toggle itself.
        }
    }

    /** One log line per screen open/close (never per frame) recording everything needed to tell a cursor-mode
     *  problem from a compositing problem in a real test run: what the game thinks the grab state is, what GLFW
     *  actually has set, whether the window is still undecorated, and the exact window rect versus the monitor. */
    private static void logScreenChange(Minecraft client) {
        Screen screen = client.screen;
        Class<?> now = screen == null ? null : screen.getClass();
        if (now == lastLoggedScreen) {
            return;
        }
        lastLoggedScreen = now;
        if (!WindowModeConfig.getInstance().isBorderlessFullscreenEnabled()) {
            return;
        }
        try {
            Window window = client.getWindow();
            long handle = window.handle();
            int[] wx = new int[1];
            int[] wy = new int[1];
            int[] ww = new int[1];
            int[] wh = new int[1];
            GLFW.glfwGetWindowPos(handle, wx, wy);
            GLFW.glfwGetWindowSize(handle, ww, wh);
            Monitor monitor = window.findBestMonitor();
            VideoMode mode = monitor == null ? null : monitor.getCurrentMode();
            int cursorMode = GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR);
            LOGGER.info("Screen {} | grabbed={} glfwCursor={} decorated={} window={}x{}@{},{} monitor={} mcFullscreen={}",
                    now == null ? "<none>" : now.getSimpleName(),
                    client.mouseHandler.isMouseGrabbed(),
                    cursorMode == GLFW.GLFW_CURSOR_DISABLED ? "DISABLED"
                            : cursorMode == GLFW.GLFW_CURSOR_HIDDEN ? "HIDDEN" : "NORMAL",
                    GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE,
                    ww[0], wh[0], wx[0], wy[0],
                    mode == null ? "?" : mode.getWidth() + "x" + mode.getHeight() + "@"
                            + monitor.getX() + "," + monitor.getY(),
                    window.isFullscreen());
        } catch (Throwable ignored) {
            // Diagnostics must never break a screen change.
        }
    }
}
