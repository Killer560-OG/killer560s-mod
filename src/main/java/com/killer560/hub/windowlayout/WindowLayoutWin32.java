package com.killer560.hub.windowlayout;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.util.Util;

/** Hand-declared user32/dwmapi bindings for Window Layout, same style as {@code shorts.Win32}: JNA core only
 *  (no jna-platform), HWND/HMONITOR as {@code long}, RECT as {@code int[4]}, MONITORINFO as {@code int[10]}.
 *  Every call is optional - callers fall back to plain GLFW values when a binding is unavailable. */
final class WindowLayoutWin32 {

    private static final int MONITOR_DEFAULTTONULL = 0;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    /** DPI_AWARENESS_PER_MONITOR_AWARE from GetAwarenessFromDpiAwarenessContext. */
    private static final int DPI_AWARENESS_PER_MONITOR_AWARE = 2;

    interface User32 extends StdCallLibrary {
        long MonitorFromRect(int[] rect, int flags);

        boolean GetMonitorInfoW(long hMonitor, int[] monitorInfo);

        boolean GetWindowRect(long hwnd, int[] rect);

        long GetThreadDpiAwarenessContext();

        int GetAwarenessFromDpiAwarenessContext(long context);
    }

    interface Dwmapi extends StdCallLibrary {
        int DwmGetWindowAttribute(long hwnd, int attribute, int[] value, int size);
    }

    private static User32 user32;
    private static Dwmapi dwmapi;
    private static boolean loadAttempted;
    private static Boolean perMonitorAware;

    private WindowLayoutWin32() {
    }

    static boolean isWindows() {
        return Util.getPlatform() == Util.OS.WINDOWS;
    }

    private static synchronized boolean ensureLoaded() {
        if (!loadAttempted) {
            loadAttempted = true;
            if (isWindows()) {
                try {
                    user32 = Native.load("user32", User32.class);
                } catch (Throwable t) {
                    user32 = null;
                }
                try {
                    dwmapi = Native.load("dwmapi", Dwmapi.class);
                } catch (Throwable t) {
                    dwmapi = null;
                }
            }
        }
        return user32 != null;
    }

    /** @return {x, y, w, h} of the work area (rcWork) of the monitor covering the given full bounds, or null. */
    static int[] workArea(int x, int y, int w, int h) {
        if (!ensureLoaded()) {
            return null;
        }
        try {
            long hMonitor = user32.MonitorFromRect(new int[]{x, y, x + w, y + h}, MONITOR_DEFAULTTONULL);
            if (hMonitor == 0L) {
                return null;
            }
            int[] info = new int[10];
            info[0] = 40;
            if (!user32.GetMonitorInfoW(hMonitor, info)) {
                return null;
            }
            // rcMonitor = [1..4] must match the GLFW monitor, otherwise MonitorFromRect picked a neighbour.
            if (info[1] != x || info[2] != y || info[3] - info[1] != w || info[4] - info[2] != h) {
                return null;
            }
            int ww = info[7] - info[5];
            int wh = info[8] - info[6];
            return ww > 0 && wh > 0 ? new int[]{info[5], info[6], ww, wh} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether this thread's window coordinates are real per-monitor physical pixels (not DPI-virtualized),
     *  which is what DWM's extended frame bounds are always reported in. */
    private static boolean isPerMonitorAware() {
        if (perMonitorAware == null) {
            boolean aware = false;
            try {
                long ctx = user32.GetThreadDpiAwarenessContext();
                aware = user32.GetAwarenessFromDpiAwarenessContext(ctx) == DPI_AWARENESS_PER_MONITOR_AWARE;
            } catch (Throwable ignored) {
            }
            perMonitorAware = aware;
        }
        return perMonitorAware;
    }

    /** Windows 10/11 decorated windows have invisible resize borders that are part of the window rect but not
     *  drawn. @return {left, top, right, bottom} of those invisible borders, or all zeros when unknown. */
    static int[] invisibleBorders(long hwnd) {
        int[] zero = {0, 0, 0, 0};
        if (hwnd == 0L || !ensureLoaded() || dwmapi == null || !isPerMonitorAware()) {
            return zero;
        }
        try {
            int[] window = new int[4];
            int[] visible = new int[4];
            if (!user32.GetWindowRect(hwnd, window)
                    || dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, visible, 16) != 0) {
                return zero;
            }
            int[] out = {visible[0] - window[0], visible[1] - window[1], window[2] - visible[2], window[3] - visible[3]};
            for (int v : out) {
                if (v < 0 || v > 48) {
                    return zero;
                }
            }
            return out;
        } catch (Throwable t) {
            return zero;
        }
    }
}
