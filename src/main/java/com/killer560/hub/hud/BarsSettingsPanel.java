package com.killer560.hub.hud;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.HealthAndManaBarsTab;
import com.killer560.hub.gui.tab.MenuCategories;
import com.killer560.hub.playerstats.PlayerStatsConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit Bars' settings panel (barsedit-1104; killer560, 2026-11-04: "make the edit bars button have the edit options for
 * the bars as well so you can see how the bars are changing as you change the settings"). Its rows are the menu's own
 * Health and Mana Bars rows ({@link HealthAndManaBarsTab#editorWidgets}), the same widgets and setters, so there is no
 * second copy of any setting; the bars draw from the config every frame, so a change shows at once. The wheel scrolls
 * it; only whole rows in view are the screen's children. It stops above the bars in the lower half of the screen that
 * share its columns and starts below those in the upper half, and sits Right, Left or Hidden ({@code PlayerStatsConfig.editorPanel}).
 */
final class BarsSettingsPanel {

    private static final String[] SIDES = {"Right", "Left", "Hidden"};
    private static final int PAD = 5;
    private static final int SIDE_W = 118;
    private static final int ROW = 22;

    private final HealthAndManaBarsTab bars;
    private final String category;
    /** The panel's box as last laid out; {@code w == 0} while Hidden (only its side button shows). */
    int x;
    int y;
    int w;
    int h;
    private int scroll;
    private int contentH;
    private int viewH;
    private AbstractWidget tipWidget;
    private long tipSinceMs;
    private List<FormattedCharSequence> tipLines;

    BarsSettingsPanel() {
        HealthAndManaBarsTab found = null;
        for (BaseTab leaf : ModScreen.allLeaves()) {
            if (leaf instanceof HealthAndManaBarsTab t) {
                found = t;
                break;
            }
        }
        bars = found != null ? found : new HealthAndManaBarsTab();
        category = found != null ? MenuCategories.legacyCategory(found) : null;
    }

    private static Component sideText(int side) {
        return Component.literal("Settings Panel: §6" + SIDES[side]);
    }

    /**
     * Lays the panel out on a {@code sw x sh} screen and returns its widgets: the side button, then every row wholly in
     * view. {@code avoid} are the bars' boxes {x0, y0, x1, y1}: one under the panel's columns in the lower half ends
     * the panel above it, one in the upper half starts it below, so it covers a bar only when no room is left.
     */
    List<AbstractWidget> layout(int sw, int sh, List<int[]> avoid, Runnable rebuild) {
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        int side = ps.getEditorPanel();
        w = Math.max(SIDE_W + 2 * PAD, Math.min(260, sw / 2 - 8));
        x = side == 1 ? 4 : sw - w - 4;
        y = 4;
        int bottom = sh - 4;
        for (int[] b : avoid) {
            if (b[0] < x + w && b[2] > x) {
                if (b[1] > sh / 2) {
                    bottom = Math.min(bottom, b[1] - 14);
                } else {
                    y = Math.max(y, b[3] + 14);
                }
            }
        }
        h = Math.max(ROW * 3, bottom - y);
        List<AbstractWidget> out = new ArrayList<>();
        out.add(SettingsButtonWidget.cycling(sideText(side), (btn, dir) -> {
                    ps.setEditorPanel(SettingsButtonWidget.step(ps.getEditorPanel(), dir, SIDES.length));
                    ps.save();
                    rebuild.run();
                }).bounds(side == 1 ? x + PAD : x + w - PAD - SIDE_W, y + PAD, SIDE_W, 18).build());
        if (side == 2) {
            w = 0;
            return out;
        }
        int top = y + PAD + ROW;
        viewH = y + h - PAD - top;
        List<AbstractWidget> rows = bars.editorWidgets(x + PAD, top - scroll, w - 2 * PAD - 4, rebuild);
        int end = top - scroll;
        for (AbstractWidget a : rows) {
            end = Math.max(end, a.getY() + a.getHeight());
        }
        contentH = end - (top - scroll);
        int max = Math.max(0, contentH - viewH);
        if (scroll > max) {
            scroll = max;
            return layout(sw, sh, avoid, rebuild);
        }
        for (AbstractWidget a : rows) {
            if (a.getY() >= top && a.getY() + a.getHeight() <= top + viewH) {
                out.add(a);
            }
        }
        return out;
    }

    boolean contains(double mx, double my) {
        return w > 0 && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** One wheel notch moves a row; true when the panel took it (the caller lays it out again). */
    boolean scroll(double mx, double my, double dy) {
        if (!contains(mx, my) || dy == 0) {
            return false;
        }
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - (int) Math.signum(dy) * ROW));
        return true;
    }

    /** The panel and its scroll bar, under its widgets. */
    void drawBackground(GuiGraphicsExtractor g) {
        if (w == 0) {
            return;
        }
        ScreenChrome.frame(g, x, y, w, h);
        if (contentH > viewH && viewH > 0) {
            int top = y + PAD + ROW;
            int knob = Math.max(10, viewH * viewH / contentH);
            int ky = top + (viewH - knob) * scroll / Math.max(1, contentH - viewH);
            g.fill(x + w - PAD - 2, ky, x + w - PAD, ky + knob, MenuStyle.OR);
        }
    }

    /** The hovered row's menu description after the menu's delay; its lines are split once per row. */
    void drawTooltip(GuiGraphicsExtractor g, List<? extends AbstractWidget> widgets, int mx, int my, int sw, int sh) {
        AbstractWidget hovered = null;
        for (AbstractWidget a : widgets) {
            if (a.visible && mx >= a.getX() && mx < a.getX() + a.getWidth() && my >= a.getY()
                    && my < a.getY() + a.getHeight()) {
                hovered = a;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (hovered != tipWidget) {
            tipWidget = hovered;
            tipSinceMs = now;
            tipLines = null;
        }
        if (hovered == null || now - tipSinceMs < 350L) {
            return;
        }
        if (tipLines == null) {
            String text = SettingTooltips.describeScoped(bars.name, category, hovered.getMessage().getString());
            tipLines = text == null || text.isBlank() ? List.of()
                    : MenuStyle.font().split(Component.literal(text), 220);
        }
        if (!tipLines.isEmpty()) {
            ScreenChrome.tooltipNear(g, tipLines, mx, my, sw, sh);
        }
    }
}
