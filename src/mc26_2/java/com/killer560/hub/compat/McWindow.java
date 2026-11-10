package com.killer560.hub.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * Window, display and cursor management across Minecraft versions (26.2 copy: GLFW).
 *
 * <p>Coordinates are the windowing system's screen coordinates; a window's position and size are its CONTENT
 * (client) area, which is what both GLFW and SDL3 position and size. A display is identified by an opaque long
 * (a GLFW monitor pointer here, an SDL display id on 26.3) that is only valid until the displays change. Public
 * signatures must match the 26.3 copy exactly.
 */
public final class McWindow {

    private McWindow() {
    }

    // ------------------------------------------------------------------------------------------------ window

    /** Whether vanilla (F11 / Video Settings) fullscreen is on. */
    public static boolean isFullscreen(Window window) {
        return window.isFullscreen();
    }

    /** Turns vanilla fullscreen on and records it in options (the caller saves options). */
    public static void enterFullscreen(Minecraft client, Window window) {
        if (!window.isFullscreen()) {
            window.toggleFullScreen();
        }
        client.options.fullscreen().set(window.isFullscreen());
    }

    /** Shows or hides the title bar and borders. */
    public static void setDecorated(Window window, boolean decorated) {
        GLFW.glfwSetWindowAttrib(window.handle(), GLFW.GLFW_DECORATED, decorated ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
    }

    /** Leaves any fullscreen monitor and places the content area at {@code (x, y)} with size {@code w x h}. */
    public static void setWindowedBounds(Window window, int x, int y, int width, int height) {
        GLFW.glfwSetWindowMonitor(window.handle(), 0L, x, y, width, height, GLFW.GLFW_DONT_CARE);
    }

    /** Moves the content area's top-left corner to {@code (x, y)}. */
    public static void setPosition(Window window, int x, int y) {
        GLFW.glfwSetWindowPos(window.handle(), x, y);
    }

    /** @return {x, y} of the content area. */
    public static int[] position(Window window) {
        int[] x = new int[1];
        int[] y = new int[1];
        GLFW.glfwGetWindowPos(window.handle(), x, y);
        return new int[]{x[0], y[0]};
    }

    /** @return {w, h} of the content area in screen coordinates. */
    public static int[] size(Window window) {
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetWindowSize(window.handle(), w, h);
        return new int[]{w[0], h[0]};
    }

    /** @return {left, top, right, bottom} decoration sizes (title bar and borders), zeros when undecorated. */
    public static int[] frameSize(Window window) {
        int[] l = new int[1];
        int[] t = new int[1];
        int[] r = new int[1];
        int[] b = new int[1];
        GLFW.glfwGetWindowFrameSize(window.handle(), l, t, r, b);
        return new int[]{Math.max(0, l[0]), Math.max(0, t[0]), Math.max(0, r[0]), Math.max(0, b[0])};
    }

    /** Whether the window is minimized or maximized (both block an exact placement until restored). */
    public static boolean isMinimizedOrMaximized(Window window) {
        long h = window.handle();
        return GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE
                || GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
    }

    /** Restores a minimized or maximized window. */
    public static void restore(Window window) {
        GLFW.glfwRestoreWindow(window.handle());
    }

    /** Brings the window to the front and gives it input focus. */
    public static void focus(Window window) {
        GLFW.glfwFocusWindow(window.handle());
    }

    /** The display's content (DPI) scale for this window, 1 when unknown. */
    public static float contentScale(Window window) {
        float[] x = new float[1];
        float[] y = new float[1];
        GLFW.glfwGetWindowContentScale(window.handle(), x, y);
        return x[0];
    }

    /** The Win32 HWND of the window, or 0 when not on Windows or unavailable. */
    public static long win32Handle(Window window) {
        try {
            long h = window.handle();
            return h == 0L ? 0L : GLFWNativeWin32.glfwGetWin32Window(h);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------------------------------------ cursor

    /** Hides the real pointer, or puts back what the game wants: captured (in-world) or a normal arrow. */
    public static void setCursorHidden(Window window, boolean hidden, boolean grabbed) {
        GLFW.glfwSetInputMode(window.handle(), GLFW.GLFW_CURSOR,
                hidden ? GLFW.GLFW_CURSOR_HIDDEN : grabbed ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
    }

    /** Re-issues the game's grab or release of the mouse at {@code (x, y)}. */
    public static void reapplyMouseGrab(Window window, boolean grabbed, double x, double y) {
        InputConstants.grabOrReleaseMouse(window, grabbed ? InputConstants.CURSOR_DISABLED : InputConstants.CURSOR_NORMAL, x, y);
    }

    /** Standard-cursor id for {@code CursorType.createStandardCursor}: the NW-SE diagonal resize arrow. */
    public static int resizeNwseCursorId() {
        return GLFW.GLFW_RESIZE_NWSE_CURSOR;
    }

    /** Standard-cursor id for {@code CursorType.createStandardCursor}: the NE-SW diagonal resize arrow. */
    public static int resizeNeswCursorId() {
        return GLFW.GLFW_RESIZE_NESW_CURSOR;
    }

    // ------------------------------------------------------------------------------------------------ displays

    /** Every connected display, in the windowing system's order. */
    public static long[] displays() {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) {
            return new long[0];
        }
        long[] out = new long[monitors.limit()];
        for (int i = 0; i < out.length; i++) {
            out[i] = monitors.get(i);
        }
        return out;
    }

    /** The primary display, or 0. */
    public static long primaryDisplay() {
        return GLFW.glfwGetPrimaryMonitor();
    }

    /** The display id of one of Minecraft's {@link Monitor}s (comparable with {@link #displays()}). */
    public static long displayOf(Monitor monitor) {
        return monitor.monitor();
    }

    /** @return {x, y, w, h} of the display's current mode in screen coordinates, or null. */
    public static int[] displayBounds(long display) {
        GLFWVidMode mode = GLFW.glfwGetVideoMode(display);
        if (mode == null) {
            return null;
        }
        int[] x = new int[1];
        int[] y = new int[1];
        GLFW.glfwGetMonitorPos(display, x, y);
        return new int[]{x[0], y[0], mode.width(), mode.height()};
    }

    /** @return {x, y, w, h} of the display's work area (without the taskbar), or null. */
    public static int[] displayWorkArea(long display) {
        int[] x = new int[1];
        int[] y = new int[1];
        int[] w = new int[1];
        int[] h = new int[1];
        GLFW.glfwGetMonitorWorkarea(display, x, y, w, h);
        return w[0] > 0 && h[0] > 0 ? new int[]{x[0], y[0], w[0], h[0]} : null;
    }

    /** The display's human-readable name, or null. */
    public static String displayName(long display) {
        return GLFW.glfwGetMonitorName(display);
    }

    /** The Win32 display device name (e.g. {@code \\.\DISPLAY1}), or "" when unavailable. */
    public static String displayWin32Device(long display) {
        try {
            String d = GLFWNativeWin32.glfwGetWin32Monitor(display);
            return d == null ? "" : d;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------------------------------------ OS opens

    /** Opens a link in the default browser (26.2: {@code Util.getPlatform().openUri}; 26.3: {@code Blaze3D.openUri}). */
    public static void openUri(String uri) {
        net.minecraft.util.Util.getPlatform().openUri(uri);
    }

    /** Opens a file or folder with the OS (26.2: {@code Util.getPlatform().openPath}; 26.3: {@code Blaze3D.openPath}). */
    public static void openPath(java.nio.file.Path path) {
        net.minecraft.util.Util.getPlatform().openPath(path);
    }

}
