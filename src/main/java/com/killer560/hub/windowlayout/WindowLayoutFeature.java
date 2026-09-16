package com.killer560.hub.windowlayout;

import com.killer560.hub.window.WindowModeConfig;
import com.killer560.hub.window.WindowModeFeature;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Moves + resizes this Minecraft window into one cell of an N-window grid on a chosen monitor, so several
 * instances can be tiled on one screen. Nothing runs unless the user picks a cell (or turns on Restore on
 * Launch after having picked one).
 * <p>
 * Placement is a small per-tick state machine because leaving exclusive fullscreen / the mod's borderless
 * mode, restoring a maximized window, and crossing onto a monitor with a different DPI all change the
 * window's frame asynchronously:
 * <ol>
 *   <li>PREPARE - leave fullscreen ({@code Window.setWindowed}; vanilla re-applies its stale windowed bounds
 *       from {@code updateFullscreenIfChanged} next frame, so wait a few ticks), turn borderless off through
 *       {@link WindowModeFeature#toggle()}, un-maximize/un-minimize.</li>
 *   <li>MOVE - put the window's content origin on the target monitor first, so Windows applies that
 *       monitor's DPI to the title bar/borders before they are measured.</li>
 *   <li>SIZE - measure {@code glfwGetWindowFrameSize} (+ the invisible Win10/11 resize borders via DWM) and
 *       {@code glfwSetWindowMonitor(handle, 0, x, y, w, h, DONT_CARE)} with content coordinates chosen so the
 *       visible outer frame fills the cell exactly.</li>
 *   <li>VERIFY - re-measure a few ticks later and re-apply once or twice if the frame changed.</li>
 * </ol>
 * Minecraft's own GLFW size/framebuffer callbacks pick up the new size exactly like a manual drag-resize.
 */
public final class WindowLayoutFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-windowlayout");

    private static final int RESTORE_DELAY_TICKS = 10;
    private static final int MAX_PREPARE_ROUNDS = 6;
    private static final int MAX_VERIFY_ROUNDS = 2;
    private static final int MIN_CONTENT_SIZE = 64;

    private enum Stage { PREPARE, MOVE, SIZE, VERIFY }

    private static final class Request {
        final int monitorIndex;
        final String monitorDevice;
        final String monitorName;
        final int count;
        final int cellIndex;
        Stage stage = Stage.PREPARE;
        int waitTicks;
        int prepareRounds;
        int verifyRounds;

        /** Set in PREPARE when borderless had to be switched off for a Full Monitor (count == 1) placement, so
         *  it can be switched back on once the window is sitting on the right monitor (killer560, 2026-09-15:
         *  "if i make it do full monitor can you make it reingauge the borderless fullscreen"). */
        boolean restoreBorderless;

        /** Same idea for vanilla (F11) fullscreen: a Full Monitor placement that started in real fullscreen
         *  goes back into real fullscreen once the window is on the requested monitor. */
        boolean restoreFullscreen;

        Request(int monitorIndex, String monitorDevice, String monitorName, int count, int cellIndex, int waitTicks) {
            this.monitorIndex = monitorIndex;
            this.monitorDevice = monitorDevice;
            this.monitorName = monitorName;
            this.count = count;
            this.cellIndex = cellIndex;
            this.waitTicks = waitTicks;
        }
    }

    private static Request pending;
    private static boolean startupHandled;

    private WindowLayoutFeature() {
    }

    public static void register() {
        WindowLayoutConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(WindowLayoutFeature::tick);
    }

    /** Places this window into {@code cellIndex} of a {@code count}-window layout on {@code monitor} and
     *  remembers it for Restore on Launch. {@code count == 1} is Full Monitor. */
    public static void place(WindowMonitors.MonitorInfo monitor, int count, int cellIndex) {
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        cfg.setLastPlacement(monitor, count, cellIndex);
        cfg.save();
        pending = new Request(monitor.index(), monitor.device(), monitor.name(), count, cellIndex, 1);
        Minecraft mc = Minecraft.getInstance();
        LOGGER.info("[Layout] place monitor={} count={} cell={} | mcFullscreen={} borderless={}",
                monitor.index() + 1, count, cellIndex,
                mc.getWindow() != null && mc.getWindow().isFullscreen(),
                WindowModeConfig.getInstance().isBorderlessFullscreenEnabled());
    }

    public static void openPicker() {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new WindowLayoutScreen(client.screen));
    }

    private static void tick(Minecraft client) {
        if (!startupHandled) {
            startupHandled = true;
            WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
            if (cfg.isRestoreOnLaunch() && cfg.hasLastPlacement()) {
                // Delayed so WindowModeFeature.tickApplyOnce (first tick) has already applied its own saved state.
                pending = new Request(cfg.getLastMonitorIndex(), cfg.getLastMonitorDevice(), cfg.getLastMonitorName(),
                        cfg.getLastCount(), cfg.getLastCellIndex(), RESTORE_DELAY_TICKS);
            }
        }


        if (pending != null) {
            try {
                step(client, pending);
            } catch (Throwable t) {
                LOGGER.warn("Window layout placement failed", t);
                pending = null;
            }
        }
    }


    private static void step(Minecraft client, Request req) {
        if (req.waitTicks > 0) {
            req.waitTicks--;
            return;
        }
        Window window = client.getWindow();
        long handle = window.handle();

        List<WindowMonitors.MonitorInfo> monitors = WindowMonitors.list();
        WindowMonitors.MonitorInfo monitor = WindowMonitors.find(monitors, req.monitorIndex, req.monitorDevice, req.monitorName);
        if (monitor == null) {
            LOGGER.info("Window layout: saved monitor {} not connected, skipping", req.monitorIndex + 1);
            pending = null;
            return;
        }
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        int[][] cells = WindowMonitors.cells(monitor.area(cfg.isRespectTaskbar()), req.count, cfg.getGap());
        int[] cell = cells[Math.max(0, Math.min(cells.length - 1, req.cellIndex))];

        switch (req.stage) {
            case PREPARE -> {
                if (req.prepareRounds++ >= MAX_PREPARE_ROUNDS) {
                    req.stage = Stage.MOVE;
                    return;
                }
                if (window.isFullscreen()) {
                    // Full Monitor asked for the whole screen, so end up back in the same kind of fullscreen the
                    // user was already in instead of a plain window (killer560, 2026-09-15: "it still doesnt
                    // reingauge fullscreen... I have to press f11 to refullscreen it").
                    if (req.count == 1) {
                        req.restoreBorderless = wantsBorderlessFullMonitor();
                        req.restoreFullscreen = !req.restoreBorderless;
                    }
                    LOGGER.info("[Layout] PREPARE leaving real fullscreen | restoreFullscreen={} restoreBorderless={}",
                            req.restoreFullscreen, req.restoreBorderless);
                    window.setWindowed(Math.max(MIN_CONTENT_SIZE, cell[2]), Math.max(MIN_CONTENT_SIZE, cell[3]));
                    // Callback is a no-op now that isFullscreen() already matches; keeps options.txt from
                    // re-entering fullscreen next launch.
                    client.options.fullscreen().set(false);
                    client.options.save();
                    req.waitTicks = 3;
                    return;
                }
                if (WindowModeConfig.getInstance().isBorderlessFullscreenEnabled()) {
                    // Full Monitor is exactly what borderless already does, so put it back at the end instead of
                    // leaving the window merely windowed-at-monitor-size (which keeps the title bar/borders).
                    req.restoreBorderless = req.count == 1 && wantsBorderlessFullMonitor();
                    LOGGER.info("[Layout] PREPARE turning borderless off | restoreBorderless={}", req.restoreBorderless);
                    WindowModeFeature.toggle();
                    req.waitTicks = 3;
                    return;
                }
                if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE
                        || GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
                    GLFW.glfwRestoreWindow(handle);
                    req.waitTicks = 2;
                    return;
                }
                req.stage = Stage.MOVE;
                step(client, req);
            }
            case MOVE -> {
                int[] frame = frameSize(handle);
                GLFW.glfwSetWindowPos(handle, cell[0] + frame[0], cell[1] + frame[1]);
                req.stage = Stage.SIZE;
                req.waitTicks = 1;
            }
            case SIZE -> {
                int[] target = contentRect(handle, cell);
                GLFW.glfwSetWindowMonitor(handle, 0L, target[0], target[1], target[2], target[3], GLFW.GLFW_DONT_CARE);
                req.stage = Stage.VERIFY;
                req.waitTicks = 4;
            }
            case VERIFY -> {
                int[] target = contentRect(handle, cell);
                int[] px = new int[1];
                int[] py = new int[1];
                int[] pw = new int[1];
                int[] ph = new int[1];
                GLFW.glfwGetWindowPos(handle, px, py);
                GLFW.glfwGetWindowSize(handle, pw, ph);
                boolean off = Math.abs(px[0] - target[0]) > 1 || Math.abs(py[0] - target[1]) > 1
                        || Math.abs(pw[0] - target[2]) > 1 || Math.abs(ph[0] - target[3]) > 1;
                if (off && req.verifyRounds++ < MAX_VERIFY_ROUNDS) {
                    GLFW.glfwSetWindowMonitor(handle, 0L, target[0], target[1], target[2], target[3], GLFW.GLFW_DONT_CARE);
                    req.waitTicks = 4;
                    return;
                }
                if (req.count == 1 && !req.restoreBorderless && !req.restoreFullscreen && wantsBorderlessFullMonitor()) {
                    // Borderless may already have been off when this placement started (e.g. a tiled layout was
                    // picked first, which turns it off) - Full Monitor still means "cover the screen".
                    req.restoreBorderless = true;
                }
                LOGGER.info("[Layout] VERIFY done | restoreBorderless={} restoreFullscreen={} mcFullscreen={}",
                        req.restoreBorderless, req.restoreFullscreen, window.isFullscreen());
                if (req.restoreBorderless) {
                    req.restoreBorderless = false;
                    // The window is on the requested monitor now, so borderless re-applies to that one.
                    if (!WindowModeConfig.getInstance().isBorderlessFullscreenEnabled()) {
                        WindowModeFeature.toggle();
                    }
                } else if (req.restoreFullscreen) {
                    req.restoreFullscreen = false;
                    if (!window.isFullscreen()) {
                        window.toggleFullScreen();
                        client.options.fullscreen().set(window.isFullscreen());
                        client.options.save();
                        // 26.1.2 defers the real switch to updateFullscreenIfChanged (called once per frame from
                        // Minecraft.runTick), and it no-ops when its actuallyFullscreen flag already matches - so
                        // apply it here rather than trusting the next frame to do it.
                        window.updateFullscreenIfChanged();
                        LOGGER.info("[Layout] re-entered real fullscreen | mcFullscreen={}", window.isFullscreen());
                    }
                }
                pending = null;
            }
        }
    }

    /** Full Monitor should end up borderless-fullscreen unless the user turned that behaviour off. */
    private static boolean wantsBorderlessFullMonitor() {
        return WindowLayoutConfig.getInstance().isFullMonitorBorderless();
    }

    /** @return {left, top, right, bottom} decoration sizes (title bar/borders), zeros when undecorated. */
    private static int[] frameSize(long handle) {
        int[] l = new int[1];
        int[] t = new int[1];
        int[] r = new int[1];
        int[] b = new int[1];
        GLFW.glfwGetWindowFrameSize(handle, l, t, r, b);
        return new int[]{Math.max(0, l[0]), Math.max(0, t[0]), Math.max(0, r[0]), Math.max(0, b[0])};
    }

    /** Content-area {x, y, w, h} (what GLFW positions/sizes) so the window's VISIBLE outer frame covers
     *  {@code cell}: the GLFW frame includes Windows' invisible resize borders, so those are pushed outside the
     *  cell rather than showing up as a transparent gap between tiled windows. */
    private static int[] contentRect(long handle, int[] cell) {
        int[] frame = frameSize(handle);
        int[] invisible = {0, 0, 0, 0};
        if (WindowLayoutWin32.isWindows()) {
            try {
                invisible = WindowLayoutWin32.invisibleBorders(GLFWNativeWin32.glfwGetWin32Window(handle));
            } catch (Throwable ignored) {
            }
        }
        int outerX = cell[0] - invisible[0];
        int outerY = cell[1] - invisible[1];
        int outerW = cell[2] + invisible[0] + invisible[2];
        int outerH = cell[3] + invisible[1] + invisible[3];
        int w = Math.max(MIN_CONTENT_SIZE, outerW - frame[0] - frame[2]);
        int h = Math.max(MIN_CONTENT_SIZE, outerH - frame[1] - frame[3]);
        return new int[]{outerX + frame[0], outerY + frame[1], w, h};
    }
}
