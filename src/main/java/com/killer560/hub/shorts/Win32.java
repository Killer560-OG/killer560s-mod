package com.killer560.hub.shorts;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal hand-declared user32/gdi32 bindings for the YT Shorts companion window. The mod only bundles JNA
 * core (net.java.dev.jna:jna 5.14.0, no jna-platform), so HWND/HRGN/LONG_PTR are passed as Java {@code long}
 * (64-bit only - {@link ShortsFeature#isSupported()} refuses 32-bit JVMs), RECT as {@code int[4]} and POINT as
 * {@code int[2]} (both are arrays of 32-bit LONGs), so no JNA Structure classes are needed. Every function
 * uses its explicit "W" name because no W32APIOptions function mapper is available without jna-platform.
 */
final class Win32 {

    static final int GWL_STYLE = -16;
    static final int GWL_EXSTYLE = -20;
    static final int GWLP_HWNDPARENT = -8;

    static final long WS_CAPTION = 0x00C00000L;
    static final long WS_THICKFRAME = 0x00040000L;
    static final long WS_SYSMENU = 0x00080000L;
    static final long WS_MINIMIZEBOX = 0x00020000L;
    static final long WS_MAXIMIZEBOX = 0x00010000L;
    static final long FRAME_STYLES = WS_CAPTION | WS_THICKFRAME | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX;

    static final long WS_EX_TOOLWINDOW = 0x00000080L;
    static final long WS_EX_APPWINDOW = 0x00040000L;
    static final long WS_EX_LAYERED = 0x00080000L;

    static final int SW_HIDE = 0;
    static final int SW_SHOWNOACTIVATE = 4;

    static final int SWP_NOSIZE = 0x0001;
    static final int SWP_NOMOVE = 0x0002;
    static final int SWP_NOZORDER = 0x0004;
    static final int SWP_NOACTIVATE = 0x0010;
    static final int SWP_FRAMECHANGED = 0x0020;
    static final int SWP_NOOWNERZORDER = 0x0200;

    static final int GW_OWNER = 4;
    static final int LWA_ALPHA = 0x00000002;

    /** DWM's own per-window accent border (Windows 11), separate from any WS_* frame style - this is what was
     *  still drawing a thin white/light border around the cropped, caption-less Shorts window (killer560:
     *  "it still has that white border around the outside of it"). DWMWA_COLOR_NONE turns it off entirely. */
    static final int DWMWA_BORDER_COLOR = 34;
    static final int DWMWA_COLOR_NONE = 0xFFFFFFFE;

    static final String CHROME_WINDOW_CLASS = "Chrome_WidgetWin_1";

    interface WndEnumProc extends StdCallLibrary.StdCallCallback {
        boolean callback(long hwnd, long lParam);
    }

    interface User32 extends StdCallLibrary {
        boolean EnumWindows(WndEnumProc proc, long lParam);

        int GetClassNameW(long hwnd, char[] buffer, int maxCount);

        int GetWindowTextW(long hwnd, char[] buffer, int maxCount);

        int GetWindowThreadProcessId(long hwnd, int[] processId);

        boolean IsWindow(long hwnd);

        boolean IsWindowVisible(long hwnd);

        boolean IsIconic(long hwnd);

        long GetWindowLongPtrW(long hwnd, int index);

        long SetWindowLongPtrW(long hwnd, int index, long newLong);

        boolean ShowWindow(long hwnd, int cmdShow);

        boolean SetWindowPos(long hwnd, long hwndInsertAfter, int x, int y, int cx, int cy, int flags);

        boolean GetWindowRect(long hwnd, int[] rect);

        boolean GetClientRect(long hwnd, int[] rect);

        boolean ClientToScreen(long hwnd, int[] point);

        long GetWindow(long hwnd, int cmd);

        long GetForegroundWindow();

        boolean SetLayeredWindowAttributes(long hwnd, int colorKey, byte alpha, int flags);

        int SetWindowRgn(long hwnd, long hrgn, boolean redraw);
    }

    interface Gdi32 extends StdCallLibrary {
        long CreateRectRgn(int left, int top, int right, int bottom);

        boolean DeleteObject(long handle);
    }

    interface Dwmapi extends StdCallLibrary {
        int DwmSetWindowAttribute(long hwnd, int dwAttribute, int[] pvAttribute, int cbAttribute);
    }

    private static volatile User32 user32;
    private static volatile Gdi32 gdi32;
    private static volatile Dwmapi dwmapi;
    private static volatile Throwable loadError;
    private static boolean loadAttempted;

    private Win32() {
    }

    /** Loads the bindings once. @return null on success, or the load failure. Dwmapi is best-effort (only
     *  used for the cosmetic border fix) and never fails this method - {@link #dwmapi()} is null if it's
     *  unavailable and callers just skip that step, same as WindowLayoutWin32 does it. */
    static synchronized Throwable ensureLoaded() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                user32 = Native.load("user32", User32.class);
                gdi32 = Native.load("gdi32", Gdi32.class);
            } catch (Throwable t) {
                loadError = t;
                user32 = null;
                gdi32 = null;
            }
            try {
                dwmapi = Native.load("dwmapi", Dwmapi.class);
            } catch (Throwable t) {
                dwmapi = null;
            }
        }
        return loadError;
    }

    static User32 user32() {
        return user32;
    }

    static Gdi32 gdi32() {
        return gdi32;
    }

    static Dwmapi dwmapi() {
        return dwmapi;
    }

    static String className(long hwnd) {
        char[] buf = new char[128];
        int n = user32.GetClassNameW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    static String title(long hwnd) {
        char[] buf = new char[512];
        int n = user32.GetWindowTextW(hwnd, buf, buf.length);
        return n <= 0 ? "" : new String(buf, 0, n);
    }

    static int processId(long hwnd) {
        int[] pid = new int[1];
        user32.GetWindowThreadProcessId(hwnd, pid);
        return pid[0];
    }

    /** All visible top-level Chrome/Edge frame windows ({@value #CHROME_WINDOW_CLASS}). */
    static List<Long> visibleChromeWindows() {
        List<Long> out = new ArrayList<>();
        WndEnumProc proc = (hwnd, lParam) -> {
            try {
                if (user32.IsWindowVisible(hwnd) && CHROME_WINDOW_CLASS.equals(className(hwnd))) {
                    out.add(hwnd);
                }
            } catch (Throwable ignored) {
            }
            return true;
        };
        user32.EnumWindows(proc, 0L);
        return out;
    }

    /** @return {left, top, width, height} of the window's client area in screen pixels, or null. */
    static int[] clientRectOnScreen(long hwnd) {
        int[] rect = new int[4];
        int[] pt = new int[2];
        if (!user32.GetClientRect(hwnd, rect) || !user32.ClientToScreen(hwnd, pt)) {
            return null;
        }
        return new int[]{pt[0], pt[1], rect[2] - rect[0], rect[3] - rect[1]};
    }

    /** @return {left, top, width, height} of the window rect, or null. */
    static int[] windowRect(long hwnd) {
        int[] rect = new int[4];
        if (!user32.GetWindowRect(hwnd, rect)) {
            return null;
        }
        return new int[]{rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]};
    }
}
