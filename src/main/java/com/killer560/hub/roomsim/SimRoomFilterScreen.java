package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;
import com.killer560.hub.roomdatabase.RoomDatabase;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The map designer's room filters, as a popup over the designer.
 *
 * <p>killer560 (2026-10-06): "in the generate a map thing have a filter section where i can filter based on things
 * like puzzles, room size, secrets in a room, etc." The rules themselves live in {@link SimRoomFilters}; this only
 * edits them. A screen of its own rather than a panel inside the designer, because the designer has no free space
 * at a small window - its grid, list and two button rows already fill it - and a popup cannot overlap any of them.
 *
 * <p>The rows flow: toggles wrap onto as many lines as the width needs, and when that is taller than the window the
 * rows scroll (mouse wheel) between the header and the footer, so nothing is ever drawn over anything else.
 */
public class SimRoomFilterScreen extends Screen {

    private static final int BTN_H = 16;
    private static final int PITCH = 18;
    private static final int PAD = 12;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private int scroll;

    /** Where each scrolling widget sits with no scroll applied. */
    private final Map<AbstractWidget, Integer> baseY = new IdentityHashMap<>();
    /** Row labels: {text, x, base y}. */
    private final List<Object[]> labels = new ArrayList<>();

    private int shown;
    private int total;

    public SimRoomFilterScreen(Screen parent) {
        super(Component.literal("Room filters"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        baseY.clear();
        labels.clear();
        panelW = Math.min(this.width - 20, 520);
        panelH = Math.min(this.height - 20, 310);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        contentTop = panelY + 56;
        contentBottom = panelY + panelH - 30;

        int labelW = 0;
        for (String l : new String[]{"Type", "Puzzles", "Size", "Secrets", "Crypts", "Routes"}) {
            labelW = Math.max(labelW, this.font.width(l));
        }
        int x0 = panelX + PAD + labelW + 8;
        int x1 = panelX + panelW - PAD - 4;   // 4 for the scrollbar
        int y = contentTop + 2;

        List<Toggle> types = new ArrayList<>();
        for (String t : SimRoomFilters.presentTypes()) {
            types.add(new Toggle(SimRoomFilters.typeLabel(t), SimRoomFilters.isTypeShown(t), true,
                    () -> SimRoomFilters.setTypeShown(t, !SimRoomFilters.isTypeShown(t))));
        }
        y = flowRow("Type", types, x0, x1, y);

        boolean puzzlesOn = SimRoomFilters.isTypeShown("PUZZLE");
        List<String> puzzles = SimRoomFilters.puzzleNames();
        List<Toggle> pz = new ArrayList<>();
        pz.add(new Toggle("All", null, puzzlesOn, () -> puzzles.forEach(p -> SimRoomFilters.setPuzzleShown(p, true))));
        pz.add(new Toggle("None", null, puzzlesOn, () -> puzzles.forEach(p -> SimRoomFilters.setPuzzleShown(p, false))));
        for (String p : puzzles) {
            pz.add(new Toggle(p, SimRoomFilters.isPuzzleShown(p), puzzlesOn,
                    () -> SimRoomFilters.setPuzzleShown(p, !SimRoomFilters.isPuzzleShown(p))));
        }
        y = flowRow("Puzzles", pz, x0, x1, y);

        List<Toggle> shapes = new ArrayList<>();
        for (String s : SimRoomFilters.SHAPES) {
            shapes.add(new Toggle(s, SimRoomFilters.isShapeShown(s), true,
                    () -> SimRoomFilters.setShapeShown(s, !SimRoomFilters.isShapeShown(s))));
        }
        y = flowRow("Size", shapes, x0, x1, y);

        int[] max = SimRoomFilters.maxima();
        y = rangeRow("Secrets", max[0], SimRoomFilters.getMinSecrets(), SimRoomFilters.getMaxSecrets(),
                v -> SimRoomFilters.setSecrets(v, SimRoomFilters.getMaxSecrets()),
                v -> SimRoomFilters.setSecrets(SimRoomFilters.getMinSecrets(), v), x0, x1, y);
        y = rangeRow("Crypts", max[1], SimRoomFilters.getMinCrypts(), SimRoomFilters.getMaxCrypts(),
                v -> SimRoomFilters.setCrypts(v, SimRoomFilters.getMaxCrypts()),
                v -> SimRoomFilters.setCrypts(SimRoomFilters.getMinCrypts(), v), x0, x1, y);

        SimRoomRoutes.Filter routes = SimRoomFilters.getRoutes();
        List<Toggle> rt = new ArrayList<>();
        for (SimRoomRoutes.Filter f : SimRoomRoutes.Filter.values()) {
            String label = switch (f) {
                case ALL -> "Any";
                case HAS -> "Has Auto Routes";
                case NONE -> "No Auto Routes";
            };
            rt.add(new Toggle(label, routes == f, true, () -> SimRoomFilters.setRoutes(f)));
        }
        y = flowRow("Routes", rt, x0, x1, y);
        contentHeight = y - contentTop;

        // Footer: outside the scrolling band, so always reachable.
        int fy = panelY + panelH - 24;
        int fw = Math.min(110, (panelW - PAD * 2 - 6) / 2);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset filters"), b -> {
            SimRoomFilters.reset();
            rebuildWidgets();
        }).bounds(panelX + PAD, fy, fw, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aDone"), b -> onClose())
                .bounds(panelX + panelW - PAD - fw, fy, fw, 18).build());

        applyScroll();
        recount();
    }

    /** A toggle button: {@code on} null for a plain action button ("All", "None"). */
    private record Toggle(String label, Boolean on, boolean active, Runnable press) {
    }

    /** Lays toggles left to right from x0, wrapping before x1; returns the y below the row. */
    private int flowRow(String label, List<Toggle> toggles, int x0, int x1, int y) {
        labels.add(new Object[]{label, panelX + PAD, y + 4});
        int x = x0;
        for (Toggle t : toggles) {
            int w = Math.min(x1 - x0, this.font.width(t.label()) + 12);
            if (x + w > x1 && x > x0) {
                x = x0;
                y += PITCH;
            }
            String text = t.on() == null ? "§7" + t.label()
                    : t.on() ? "§6" + t.label() : "§8§m" + t.label();
            SettingsButtonWidget b = SettingsButtonWidget.builder(Component.literal(text), btn -> {
                t.press().run();
                rebuildWidgets();
            }).bounds(x, y, w, BTN_H).build();
            b.active = t.active();
            addScrolled(b, y);
            x += w + 4;
        }
        return y + PITCH + 4;
    }

    /** A "min" and a "max" slider side by side; the max slider's last stop is "any". */
    private int rangeRow(String label, int top, int min, int max, IntConsumer onMin, IntConsumer onMax,
                         int x0, int x1, int y) {
        labels.add(new Object[]{label, panelX + PAD, y + 4});
        int w = (x1 - x0 - 6) / 2;
        addScrolled(new RangeSlider(x0, y, w, "Min", 0, top, false, Math.min(min, top), v -> {
            onMin.accept(v);
            recount();
        }), y);
        addScrolled(new RangeSlider(x0 + w + 6, y, w, "Max", 0, top, true, max < 0 ? -1 : Math.min(max, top), v -> {
            onMax.accept(v);
            recount();
        }), y);
        return y + PITCH + 4;
    }

    private void addScrolled(AbstractWidget w, int y) {
        baseY.put(w, y);
        addRenderableWidget(w);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom - contentTop));
    }

    private void applyScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        for (Map.Entry<AbstractWidget, Integer> e : baseY.entrySet()) {
            AbstractWidget w = e.getKey();
            w.setY(e.getValue() - scroll);
            // Only a widget wholly inside the band is shown, and so clickable - never one under the header/footer.
            w.visible = w.getY() >= contentTop && w.getY() + w.getHeight() <= contentBottom;
        }
    }

    private void recount() {
        List<String> all = SimRoomFilters.usableNames();
        total = all.size();
        int n = 0;
        for (String name : all) {
            n += SimRoomFilters.listMatches(name) ? 1 : 0;
        }
        shown = n;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (maxScroll() > 0) {
            scroll -= (int) (dy * PITCH);
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        g.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        g.text(this.font, "ROOM FILTERS", panelX + 10, panelY + 11, ProfitPanels.ACCENT, false);
        String count = shown + " of " + total + " rooms";
        g.text(this.font, count, panelX + panelW - 10 - this.font.width(count), panelY + 11,
                shown == total ? ProfitPanels.DIM : ProfitPanels.ACCENT, false);
        int lineW = panelW - 20;
        g.text(this.font, fit("Filters the room list, Fill and Generate.", "Filters the list, Fill and Generate",
                lineW), panelX + 10, panelY + 34, ProfitPanels.TEXT, false);
        g.text(this.font, fit("Generate always keeps Entrance, Blood, Fairy and Trap. Puzzles follow only their own toggles.",
                "Entrance/Blood/Fairy/Trap always generate", lineW), panelX + 10, panelY + 44, ProfitPanels.DIM, false);
        if (!RoomDatabase.isReady()) {
            g.text(this.font, fit("room database loading...", null, lineW), panelX + 10, contentBottom + 2,
                    ProfitPanels.DIM, false);
        }

        g.enableScissor(panelX + 1, contentTop, panelX + panelW - 1, contentBottom);
        for (Object[] l : labels) {
            int ly = (Integer) l[2] - scroll;
            // Same rule as the widgets (applyScroll): a label shows only when its row's first line fits whole, so a
            // label is never left standing beside controls that are scrolled out of sight.
            int rowY = ly - 4;
            if (rowY >= contentTop && rowY + BTN_H <= contentBottom) {
                g.text(this.font, (String) l[0], (Integer) l[1], ly, ProfitPanels.TEXT, false);
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int band = contentBottom - contentTop;
            int bar = Math.max(12, band * band / (contentHeight));
            int by = contentTop + (band - bar) * scroll / maxScroll();
            int sx = panelX + panelW - PAD + 2;
            g.fill(sx, contentTop, sx + 3, contentBottom, 0xFF1A1A1A);
            g.fill(sx, by, sx + 3, by + bar, ProfitPanels.ACCENT);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** The full text if it fits, else the shorter one, else the shorter one trimmed with an ellipsis. */
    private String fit(String full, String shorter, int maxWidth) {
        if (this.font.width(full) <= maxWidth) {
            return full;
        }
        if (shorter != null && this.font.width(shorter) <= maxWidth) {
            return shorter;
        }
        String c = shorter != null ? shorter : full;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            if (this.font.width(sb.toString() + c.charAt(i) + "...") > maxWidth) {
                break;
            }
            sb.append(c.charAt(i));
        }
        return sb + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A whole-number slider for a bound. With {@code anyAtTop} the track has one stop past {@code max}, which
     * reads "any" and reports -1 - the same "one extra stop" idea as {@link SimSlider}'s Random.
     */
    static final class RangeSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final boolean anyAtTop;
        private final IntConsumer onChange;

        RangeSlider(int x, int y, int width, String label, int min, int max, boolean anyAtTop, int value,
                    IntConsumer onChange) {
            super(x, y, width, BTN_H, Component.empty(), position(value, min, max, anyAtTop));
            this.label = label;
            this.min = min;
            this.max = max;
            this.anyAtTop = anyAtTop;
            this.onChange = onChange;
            updateMessage();
        }

        private static double position(int value, int min, int max, boolean anyAtTop) {
            int top = anyAtTop ? max + 1 : max;
            if (top <= min) {
                return 0.0;
            }
            int v = anyAtTop && value < 0 ? top : Math.max(min, Math.min(max, value));
            return (double) (v - min) / (top - min);
        }

        /** @return the bound, or -1 for "any" */
        int intValue() {
            int top = anyAtTop ? max + 1 : max;
            int raw = (int) Math.round(min + this.value * (top - min));
            return anyAtTop && raw > max ? -1 : raw;
        }

        @Override
        protected void updateMessage() {
            int v = intValue();
            setMessage(Component.literal(label + ": " + (v < 0 ? "§7any" : "§6" + v)));
        }

        @Override
        protected void applyValue() {
            int v = intValue();
            this.value = position(v, min, max, anyAtTop);
            onChange.accept(v);
        }
    }
}
