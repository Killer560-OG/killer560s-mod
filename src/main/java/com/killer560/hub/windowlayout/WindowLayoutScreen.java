package com.killer560.hub.windowlayout;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import com.killer560.hub.compat.McCompat;

/** Scaled preview of one monitor with the N layout cells outlined; clicking a cell moves + resizes this
 *  Minecraft window into it (see {@link WindowLayoutFeature}). */
public class WindowLayoutScreen extends Screen implements MenuThemedScreen {

    /** Room above the monitor preview (the frame's header bar) and below it (the button row), and beside it. */
    private static final int TOP = 36;
    private static final int BOTTOM = 40;
    private static final int SIDE = 20;
    /** The frame around everything, inset from the window edge. */
    private static final int MARGIN = 6;

    private final Screen parent;
    private List<WindowMonitors.MonitorInfo> monitors = List.of();
    private WindowMonitors.MonitorInfo monitor;

    public WindowLayoutScreen(Screen parent) {
        super(Component.literal("Window Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        monitors = WindowMonitors.list();
        monitor = WindowMonitors.selected(monitors);
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        int bw = 96;
        int gap = 6;
        int total = bw * 4 + gap * 3;
        int x = width / 2 - total / 2;
        int y = height - BOTTOM + 8;

        addRenderableWidget(SettingsButtonWidget.cycling(monitorText(), (btn, dir) -> {
            if (monitors.isEmpty()) {
                return;
            }
            int next = monitor == null ? (dir > 0 ? 0 : monitors.size() - 1)
                    : SettingsButtonWidget.step(monitor.index(), dir, monitors.size());
            monitor = monitors.get(next);
            cfg.setMonitor(monitor);
            cfg.save();
            btn.setMessage(monitorText());
        }).bounds(x, y, bw, 20).build());
        x += bw + gap;

        addRenderableWidget(SettingsButtonWidget.cycling(countText(), (btn, dir) -> {
            int next = SettingsButtonWidget.step(cfg.getWindowsPerMonitor() - 1, dir, WindowLayoutConfig.MAX_WINDOWS) + 1;
            cfg.setWindowsPerMonitor(next);
            cfg.save();
            btn.setMessage(countText());
        }).bounds(x, y, bw, 20).build());
        x += bw + gap;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Full Monitor"), btn -> choose(1, 0))
                .bounds(x, y, bw, 20).build());
        x += bw + gap;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Cancel"), btn -> onClose())
                .bounds(x, y, bw, 20).build());
    }

    private Component monitorText() {
        return Component.literal("Monitor: " + (monitor == null ? "-" : monitor.index() + 1));
    }

    private static Component countText() {
        return Component.literal("Windows: " + WindowLayoutConfig.getInstance().getWindowsPerMonitor());
    }

    private void choose(int count, int cellIndex) {
        if (monitor == null) {
            return;
        }
        WindowMonitors.MonitorInfo target = monitor;
        onClose();
        WindowLayoutFeature.place(target, count, cellIndex);
    }

    /** @return {x, y, w, h, scale} of the monitor preview in GUI coordinates (scale = GUI units per pixel). */
    private double[] preview() {
        if (monitor == null) {
            return null;
        }
        double availW = width - SIDE * 2;
        double availH = height - TOP - BOTTOM;
        double scale = Math.min(availW / monitor.width(), availH / monitor.height());
        double w = monitor.width() * scale;
        double h = monitor.height() * scale;
        return new double[]{(width - w) / 2.0, TOP + (availH - h) / 2.0, w, h, scale};
    }

    /** Maps a real-pixel rect to preview GUI coordinates {x0, y0, x1, y1}. */
    private int[] toGui(double[] p, int rx, int ry, int rw, int rh) {
        double s = p[4];
        int x0 = (int) Math.round(p[0] + (rx - monitor.x()) * s);
        int y0 = (int) Math.round(p[1] + (ry - monitor.y()) * s);
        int x1 = (int) Math.round(p[0] + (rx + rw - monitor.x()) * s);
        int y1 = (int) Math.round(p[1] + (ry + rh - monitor.y()) * s);
        return new int[]{x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1)};
    }

    private int[][] guiCells(double[] p) {
        WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
        int[][] cells = WindowMonitors.cells(monitor.area(cfg.isRespectTaskbar()), cfg.getWindowsPerMonitor(), cfg.getGap());
        int[][] out = new int[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            // At least a 1px visible separation between neighboring cells in the preview even at gap 0.
            int[] g = toGui(p, cells[i][0], cells[i][1], cells[i][2], cells[i][3]);
            out[i] = new int[]{g[0] + 1, g[1] + 1, g[2] - 1, g[3] - 1};
        }
        return out;
    }

    private int hoveredCell(int[][] cells, double mx, double my) {
        for (int i = 0; i < cells.length; i++) {
            int[] c = cells[i];
            if (mx >= c[0] && mx < c[2] && my >= c[1] && my < c[3]) {
                return i;
            }
        }
        return -1;
    }

    /** This screen's own Theme setting (Theme Default follows the menu). */
    public static final String THEME_ID = "windowlayout";

    @Override
    public String themeId() {
        return THEME_ID;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{MARGIN, MARGIN, width - 2 * MARGIN, height - 2 * MARGIN};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, width, height);
        ScreenChrome.frame(graphics, MARGIN, MARGIN, width - 2 * MARGIN, height - 2 * MARGIN);
        ScreenChrome.header(graphics, MARGIN, MARGIN, width - 2 * MARGIN, "Window Layout");
        ScreenChrome.headerRight(graphics, MARGIN, MARGIN, width - 2 * MARGIN, "Click a cell to move this window there",
                MenuStyle.DIM);

        double[] p = preview();
        if (p != null) {
            WindowLayoutConfig cfg = WindowLayoutConfig.getInstance();
            int[] mon = toGui(p, monitor.x(), monitor.y(), monitor.width(), monitor.height());
            GuiRects r = GuiRects.begin(graphics);
            // The monitor is a recessed well; the taskbar strip outside the work area is a deeper shade of it.
            MenuStyle.well(r, mon[0] - 1, mon[1] - 1, mon[2] - mon[0] + 2, mon[3] - mon[1] + 2,
                    cfg.isRespectTaskbar() ? MenuStyle.WELL_DEEP : MenuStyle.WELL);
            if (cfg.isRespectTaskbar()) {
                int[] work = toGui(p, monitor.workX(), monitor.workY(), monitor.workWidth(), monitor.workHeight());
                r.fill(work[0], work[1], work[2], work[3], MenuStyle.WELL);
            }

            int[][] cells = guiCells(p);
            int hovered = hoveredCell(cells, mouseX, mouseY);
            boolean lastHere = cfg.hasLastPlacement() && cfg.getLastCount() == cells.length
                    && WindowMonitors.find(monitors, cfg.getLastMonitorIndex(), cfg.getLastMonitorDevice(),
                    cfg.getLastMonitorName()) == monitor;
            for (int i = 0; i < cells.length; i++) {
                int[] c = cells[i];
                boolean hover = i == hovered;
                boolean last = lastHere && i == cfg.getLastCellIndex();
                if (hover) {
                    MenuStyle.glow(r, c[0], c[1], c[2] - c[0], c[3] - c[1], MenuStyle.OR, 0x40);
                }
                ScreenChrome.card(r, c[0], c[1], c[2] - c[0], c[3] - c[1], hover);
                if (hover || last) {
                    r.outline(c[0], c[1], c[2] - c[0], c[3] - c[1], hover ? MenuStyle.OR_HOVER : MenuStyle.OR);
                    r.outline(c[0] + 1, c[1] + 1, c[2] - c[0] - 2, c[3] - c[1] - 2, hover ? MenuStyle.OR_HOVER : MenuStyle.OR);
                } else {
                    r.outline(c[0], c[1], c[2] - c[0], c[3] - c[1], MenuStyle.ACCENT_EDGE);
                }
            }
            r.submit();
            for (int i = 0; i < cells.length; i++) {
                int[] c = cells[i];
                drawNumber(graphics, String.valueOf(i + 1), (c[0] + c[2]) / 2, (c[1] + c[3]) / 2,
                        Math.min(c[2] - c[0], c[3] - c[1]), i == hovered ? MenuStyle.OR_HOVER : MenuStyle.TXT);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawNumber(GuiGraphicsExtractor graphics, String text, int cx, int cy, int cellSize, int color) {
        float scale = Math.max(1f, Math.min(4f, cellSize / 40f));
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(scale, scale);
        ScreenChrome.centered(graphics, text, 0, -4, color);
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double[] p = preview();
        if (p == null || event.button() != 0) {
            return false;
        }
        int hit = hoveredCell(guiCells(p), event.x(), event.y());
        if (hit < 0) {
            return false;
        }
        choose(WindowLayoutConfig.getInstance().getWindowsPerMonitor(), hit);
        return true;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
