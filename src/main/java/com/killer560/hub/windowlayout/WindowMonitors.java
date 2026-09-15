package com.killer560.hub.windowlayout;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;

import java.util.ArrayList;
import java.util.List;

/** Connected monitors straight from GLFW (Mojang's {@code ScreenManager} keeps its map private), with the
 *  Windows work area from user32 {@code GetMonitorInfoW} and GLFW's own work area as the fallback. Must be
 *  called on the render thread. All values are in GLFW screen coordinates. */
public final class WindowMonitors {

    /** @param device Win32 display device name (e.g. {@code \\.\DISPLAY1}), empty elsewhere. */
    public record MonitorInfo(int index, long handle, String name, String device,
                              int x, int y, int width, int height,
                              int workX, int workY, int workWidth, int workHeight) {

        public int[] area(boolean respectTaskbar) {
            return respectTaskbar ? new int[]{workX, workY, workWidth, workHeight} : new int[]{x, y, width, height};
        }

        public String label() {
            return (index + 1) + " (" + width + "x" + height + ")";
        }
    }

    private WindowMonitors() {
    }

    public static List<MonitorInfo> list() {
        List<MonitorInfo> out = new ArrayList<>();
        try {
            PointerBuffer monitors = GLFW.glfwGetMonitors();
            if (monitors == null) {
                return out;
            }
            for (int i = 0; i < monitors.limit(); i++) {
                long handle = monitors.get(i);
                GLFWVidMode mode = GLFW.glfwGetVideoMode(handle);
                if (mode == null) {
                    continue;
                }
                int[] mx = new int[1];
                int[] my = new int[1];
                GLFW.glfwGetMonitorPos(handle, mx, my);
                int w = mode.width();
                int h = mode.height();
                String name = GLFW.glfwGetMonitorName(handle);
                String device = "";
                if (WindowLayoutWin32.isWindows()) {
                    try {
                        String d = GLFWNativeWin32.glfwGetWin32Monitor(handle);
                        device = d == null ? "" : d;
                    } catch (Throwable ignored) {
                    }
                }
                int[] work = WindowLayoutWin32.isWindows() ? WindowLayoutWin32.workArea(mx[0], my[0], w, h) : null;
                if (work == null) {
                    work = glfwWorkArea(handle);
                }
                if (work == null) {
                    work = new int[]{mx[0], my[0], w, h};
                }
                out.add(new MonitorInfo(out.size(), handle, name == null ? "Monitor" : name, device,
                        mx[0], my[0], w, h, work[0], work[1], work[2], work[3]));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int[] glfwWorkArea(long handle) {
        try {
            int[] x = new int[1];
            int[] y = new int[1];
            int[] w = new int[1];
            int[] h = new int[1];
            GLFW.glfwGetMonitorWorkarea(handle, x, y, w, h);
            return w[0] > 0 && h[0] > 0 ? new int[]{x[0], y[0], w[0], h[0]} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The monitor this Minecraft window is currently on. */
    public static MonitorInfo current(List<MonitorInfo> monitors) {
        if (monitors.isEmpty()) {
            return null;
        }
        try {
            Window window = Minecraft.getInstance().getWindow();
            Monitor best = window.findBestMonitor();
            if (best != null) {
                for (MonitorInfo m : monitors) {
                    if (m.handle() == best.getMonitor()) {
                        return m;
                    }
                }
            }
            int cx = window.getX() + window.getWidth() / 2;
            int cy = window.getY() + window.getHeight() / 2;
            for (MonitorInfo m : monitors) {
                if (cx >= m.x() && cx < m.x() + m.width() && cy >= m.y() && cy < m.y() + m.height()) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        long primary = GLFW.glfwGetPrimaryMonitor();
        for (MonitorInfo m : monitors) {
            if (m.handle() == primary) {
                return m;
            }
        }
        return monitors.get(0);
    }

    /** Finds a saved monitor: Win32 device name first, then name at the saved index, then any name match,
     *  then the bare index. @return null when nothing plausible is connected. */
    public static MonitorInfo find(List<MonitorInfo> monitors, int index, String device, String name) {
        if (device != null && !device.isEmpty()) {
            for (MonitorInfo m : monitors) {
                if (device.equals(m.device())) {
                    return m;
                }
            }
        }
        if (index >= 0 && index < monitors.size() && (name == null || name.isEmpty() || name.equals(monitors.get(index).name()))) {
            return monitors.get(index);
        }
        if (name != null && !name.isEmpty()) {
            for (MonitorInfo m : monitors) {
                if (name.equals(m.name())) {
                    return m;
                }
            }
        }
        return index >= 0 && index < monitors.size() ? monitors.get(index) : null;
    }

    /** The monitor chosen in settings (Auto = the current one). */
    public static MonitorInfo selected(List<MonitorInfo> monitors) {
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        if (!cfg.isMonitorAuto()) {
            MonitorInfo m = find(monitors, cfg.getMonitorIndex(), cfg.getMonitorDevice(), cfg.getMonitorName());
            if (m != null) {
                return m;
            }
        }
        return current(monitors);
    }

    /** Cells per row for {@code count} windows on a landscape area (each row stretches to full width).
     *  @return null for 7-9, which fill a fixed 3x3 grid row-major instead. */
    private static int[] rowsFor(int count, boolean ultrawide) {
        return switch (count) {
            case 1 -> new int[]{1};
            case 2 -> new int[]{2};
            case 3 -> ultrawide ? new int[]{3} : new int[]{2, 1};
            case 4 -> new int[]{2, 2};
            case 5 -> new int[]{3, 2};
            case 6 -> new int[]{3, 3};
            default -> null;
        };
    }

    /** @return one {x, y, w, h} per cell, row-major, inside {@code area} with {@code gap} around every cell. */
    public static int[][] cells(int[] area, int count, int gap) {
        count = Math.max(1, Math.min(9, count));
        boolean portrait = area[3] > area[2];
        // Work in a "landscape" frame and transpose back for portrait monitors.
        int ax = portrait ? area[1] : area[0];
        int ay = portrait ? area[0] : area[1];
        int aw = portrait ? area[3] : area[2];
        int ah = portrait ? area[2] : area[3];
        boolean ultrawide = aw >= ah * 2;

        int[][] out = new int[count][];
        int[] rows = rowsFor(count, ultrawide);
        if (rows == null) {
            int[] xs = split(ax, aw, 3, gap);
            int[] ys = split(ay, ah, 3, gap);
            for (int i = 0; i < count; i++) {
                int c = i % 3;
                int r = i / 3;
                out[i] = new int[]{xs[c * 2], ys[r * 2], xs[c * 2 + 1], ys[r * 2 + 1]};
            }
        } else {
            int[] ys = split(ay, ah, rows.length, gap);
            int i = 0;
            for (int r = 0; r < rows.length; r++) {
                int[] xs = split(ax, aw, rows[r], gap);
                for (int c = 0; c < rows[r]; c++) {
                    out[i++] = new int[]{xs[c * 2], ys[r * 2], xs[c * 2 + 1], ys[r * 2 + 1]};
                }
            }
        }
        if (portrait) {
            for (int[] cell : out) {
                int t = cell[0];
                cell[0] = cell[1];
                cell[1] = t;
                t = cell[2];
                cell[2] = cell[3];
                cell[3] = t;
            }
            // Keep numbering in reading order (top-to-bottom, then left-to-right).
            java.util.Arrays.sort(out, (a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        }
        return out;
    }

    /** Splits [start, start+length) into n parts with {@code gap} before, between and after them.
     *  @return {pos0, size0, pos1, size1, ...}; the last part absorbs rounding. */
    private static int[] split(int start, int length, int n, int gap) {
        int usable = Math.max(n, length - gap * (n + 1));
        int each = usable / n;
        int[] out = new int[n * 2];
        for (int i = 0; i < n; i++) {
            int pos = start + gap + i * (each + gap);
            int size = i == n - 1 ? (start + length - gap) - pos : each;
            out[i * 2] = pos;
            out[i * 2 + 1] = Math.max(1, size);
        }
        return out;
    }
}
