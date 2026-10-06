package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.roomdatabase.RoomDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sim's Filters panel: one widget for every room filter the sim has (the Map Designer's, Load a Room's and All
 * Rooms'), so the three cannot look or behave differently. The rules live in {@link SimRoomFilter}; this only edits
 * one.
 *
 * <p>killer560 (2026-10-07), with a mockup: a dark panel titled "Filters" with a "Clear all" pill top-right, and one
 * row per category - a grey label on the left, rounded chips on the right, a chosen chip drawn with a lighter fill
 * and border. Rows: Size, Kind, Rare room, Secrets and Your routes from the mockup, then Puzzles and Crypts, which the
 * designer's filters already had and are kept in the same style.
 *
 * <p>The chips flow: they wrap onto as many lines as the width needs, and on a narrow window the labels go on a line
 * of their own above their chips. When that is taller than the window the rows scroll (mouse wheel) between the
 * header and the footer, so nothing is ever drawn over anything else - testkit 75 and 97-sim-roomcycle check every
 * chip's box against every other at several window sizes.
 */
public class SimRoomFilterScreen extends Screen {

    static final int CHIP_H = 16;
    private static final int CHIP_GAP = 4;
    private static final int LINE = CHIP_H + 4;
    private static final int ROW_GAP = 5;
    private static final int PAD = 12;
    private static final int HEADER = 28;
    private static final int FOOTER = 26;
    /** Below this many pixels for chips beside the labels, each label takes a line of its own. */
    private static final int MIN_CHIP_AREA = 150;

    // The mockup's greys.
    private static final int SHADE = 0xAA000000;
    private static final int PANEL_BG = 0xFF17191C;
    private static final int PANEL_BORDER = 0xFF2E3238;
    private static final int TITLE = 0xFFF2F2F2;
    private static final int LABEL = 0xFF9BA0A6;
    private static final int DIM = 0xFF6E737A;
    static final int CHIP_BG = 0xFF1C1F23;
    static final int CHIP_BORDER = 0xFF3A3F46;
    static final int CHIP_BORDER_HOVER = 0xFF5C626B;
    static final int CHIP_TEXT = 0xFFDADDE1;
    static final int CHIP_ON_BG = 0xFF4B5058;
    static final int CHIP_ON_BORDER = 0xFFBFC4CB;
    static final int CHIP_ON_TEXT = 0xFFFFFFFF;
    static final int CHIP_OFF_TEXT = 0xFF555A61;

    private final Screen parent;
    private final SimRoomFilter filter;
    private final String note;

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

    /** The Map Designer's filters - what its Filters button opens. */
    public SimRoomFilterScreen(Screen parent) {
        this(parent, SimRoomFilters.DESIGNER,
                "Narrows the list, Fill and Generate. Required rooms always generate.");
    }

    public SimRoomFilterScreen(Screen parent, SimRoomFilter filter, String note) {
        super(Component.literal("Filters"));
        this.parent = parent;
        this.filter = filter;
        this.note = note;
    }

    /** The filter this panel edits. For the testkit. */
    public SimRoomFilter filter() {
        return filter;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 16, 480);
        layout(Math.min(this.height - 16, 340));
        // Shrink the panel to its rows when they need less than the window gives, as the mockup's panel does; the
        // second pass lays everything out again at the new height, so nothing is moved after the fact.
        int fitted = HEADER + contentHeight + FOOTER + 2;
        if (fitted < panelH) {
            clearWidgets();
            layout(fitted);
        }
        applyScroll();
        recount();
    }

    private void layout(int height) {
        baseY.clear();
        labels.clear();
        panelH = height;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        contentTop = panelY + HEADER;
        contentBottom = panelY + panelH - FOOTER;

        // Header: "Clear all" top-right.
        int clearW = this.font.width("Clear all") + 16;
        addRenderableWidget(new Chip("Clear all", "", false, true, () -> {
            filter.clear();
            rebuildWidgets();
        }, panelX + panelW - PAD - clearW, panelY + 6, clearW));
        // Footer: Done bottom-right, outside the scrolling band so it is always reachable.
        int doneW = this.font.width("Done") + 24;
        addRenderableWidget(new Chip("Done", "", false, true, this::onClose,
                panelX + panelW - PAD - doneW, panelY + panelH - FOOTER + 5, doneW));

        String[] names = {"Size", "Kind", "Rare room", "Secrets", "Your routes", "Puzzles", "Crypts"};
        int labelW = 0;
        for (String l : names) {
            labelW = Math.max(labelW, this.font.width(l));
        }
        int left = panelX + PAD;
        int x1 = panelX + panelW - PAD - 4;   // 4 for the scrollbar
        boolean stacked = x1 - (left + labelW + 10) < MIN_CHIP_AREA;
        int x0 = stacked ? left : left + labelW + 10;
        int y = contentTop + 4;

        List<Chip> size = new ArrayList<>();
        for (String s : SimRoomFilter.SIZES) {
            size.add(chip("Size", "L".equals(s) ? "L shape" : s, filter.hasSize(s), true,
                    () -> filter.toggleSize(s)));
        }
        y = row("Size", size, left, x0, x1, y, stacked);

        List<Chip> kind = new ArrayList<>();
        for (String k : SimRoomFilter.KINDS) {
            kind.add(chip("Kind", SimRoomFilter.kindLabel(k), filter.hasKind(k), true, () -> filter.toggleKind(k)));
        }
        y = row("Kind", kind, left, x0, x1, y, stacked);

        List<Chip> rare = new ArrayList<>();
        for (SimRoomFilter.Rare r : SimRoomFilter.Rare.values()) {
            rare.add(chip("Rare room", r.label, filter.rare() == r, true, () -> filter.setRare(r)));
        }
        y = row("Rare room", rare, left, x0, x1, y, stacked);

        List<Chip> secrets = new ArrayList<>();
        for (SimRoomFilter.Count c : SimRoomFilter.Count.values()) {
            secrets.add(chip("Secrets", c.label, filter.secrets() == c, true, () -> filter.setSecrets(c)));
        }
        y = row("Secrets", secrets, left, x0, x1, y, stacked);

        List<Chip> routes = new ArrayList<>();
        for (SimRoomRoutes.Filter f : new SimRoomRoutes.Filter[]{
                SimRoomRoutes.Filter.ALL, SimRoomRoutes.Filter.HAS, SimRoomRoutes.Filter.NONE}) {
            String label = switch (f) {
                case ALL -> "Any";
                case HAS -> "Has routes";
                case NONE -> "No routes";
            };
            routes.add(chip("Your routes", label, filter.routes() == f, true, () -> filter.setRoutes(f)));
        }
        y = row("Your routes", routes, left, x0, x1, y, stacked);

        boolean puzzlesOn = filter.puzzlesInPlay();
        List<Chip> puzzles = new ArrayList<>();
        for (String p : SimRoomFilters.puzzleNames()) {
            puzzles.add(chip("Puzzles", p, filter.hasPuzzle(p), puzzlesOn, () -> filter.togglePuzzle(p)));
        }
        if (!puzzles.isEmpty()) {
            y = row("Puzzles", puzzles, left, x0, x1, y, stacked);
        }

        List<Chip> crypts = new ArrayList<>();
        for (SimRoomFilter.Count c : SimRoomFilter.Count.values()) {
            crypts.add(chip("Crypts", c.label, filter.crypts() == c, true, () -> filter.setCrypts(c)));
        }
        y = row("Crypts", crypts, left, x0, x1, y, stacked);
        contentHeight = y - contentTop;
    }

    private Chip chip(String row, String label, boolean on, boolean active, Runnable change) {
        Chip c = new Chip(label, row, on, active, () -> {
            change.run();
            rebuildWidgets();
        }, 0, 0, 0);
        return c;
    }

    /** Lays a row's chips left to right from x0, wrapping before x1; returns the y below the row. */
    private int row(String label, List<Chip> chips, int left, int x0, int x1, int y, boolean stacked) {
        if (stacked) {
            labels.add(new Object[]{label, left, y});
            y += 11;
        } else {
            labels.add(new Object[]{label, left, y + (CHIP_H - 8) / 2});
        }
        int x = x0;
        for (Chip c : chips) {
            int w = Math.min(x1 - x0, this.font.width(c.label) + 16);
            if (x + w > x1 && x > x0) {
                x = x0;
                y += LINE;
            }
            c.setX(x);
            c.setWidth(w);
            baseY.put(c, y);
            addRenderableWidget(c);
            x += w + CHIP_GAP;
        }
        return y + CHIP_H + ROW_GAP + 4;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom - contentTop));
    }

    private void applyScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        for (Map.Entry<AbstractWidget, Integer> e : baseY.entrySet()) {
            AbstractWidget w = e.getKey();
            w.setY(e.getValue() - scroll);
            // Only a chip wholly inside the band is shown, and so clickable - never one under the header/footer.
            w.visible = w.getY() >= contentTop && w.getY() + w.getHeight() <= contentBottom;
        }
    }

    private void recount() {
        List<String> all = SimRoomFilters.usableNames();
        total = all.size();
        int n = 0;
        for (String name : all) {
            n += filter.matches(name) ? 1 : 0;
        }
        shown = n;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (maxScroll() > 0) {
            scroll -= (int) (dy * LINE);
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
        g.fill(0, 0, this.width, this.height, SHADE);
        pill(g, panelX, panelY, panelW, panelH, 6, PANEL_BG, PANEL_BORDER);
        g.text(this.font, "Filters", panelX + PAD, panelY + 10, TITLE, false);
        String count = shown + " of " + total + " rooms";
        int countX = panelX + PAD + this.font.width("Filters") + 10;
        int clearX = panelX + panelW - PAD - this.font.width("Clear all") - 16;
        if (countX + this.font.width(count) < clearX - 6) {
            g.text(this.font, count, countX, panelY + 10, filter.isDefault() ? DIM : LABEL, false);
        }
        g.fill(panelX + 1, contentTop - 2, panelX + panelW - 1, contentTop - 1, PANEL_BORDER);
        g.fill(panelX + 1, contentBottom + 1, panelX + panelW - 1, contentBottom + 2, PANEL_BORDER);
        int noteW = panelW - PAD * 2 - this.font.width("Done") - 24 - 8;
        String foot = RoomDatabase.isReady() ? note : "room database loading...";
        g.text(this.font, this.font.plainSubstrByWidth(foot, Math.max(0, noteW)), panelX + PAD,
                panelY + panelH - FOOTER + 9, DIM, false);

        g.enableScissor(panelX + 1, contentTop, panelX + panelW - 1, contentBottom);
        for (Object[] l : labels) {
            int ly = (Integer) l[2] - scroll;
            // Same rule as the chips (applyScroll): a label shows only when it fits whole in the band.
            if (ly >= contentTop && ly + 9 <= contentBottom) {
                g.text(this.font, (String) l[0], (Integer) l[1], ly, LABEL, false);
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int band = contentBottom - contentTop;
            int bar = Math.max(12, band * band / contentHeight);
            int by = contentTop + (band - bar) * scroll / maxScroll();
            int sx = panelX + panelW - PAD + 4;
            g.fill(sx, contentTop, sx + 3, contentBottom, 0xFF22252A);
            g.fill(sx, by, sx + 3, by + bar, CHIP_ON_BORDER);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A rounded box: the border colour as a rounded fill, then the fill colour one pixel in. Corners are cut row by
     * row from a circle of radius {@code r}.
     */
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int border) {
        rounded(g, x, y, w, h, r, border);
        rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    private static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int colour) {
        if (w <= 0 || h <= 0) {
            return;
        }
        r = Math.min(r, Math.min(w, h) / 2);
        for (int d = 0; d < r; d++) {
            double dy = r - d - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + d, x + w - inset, y + d + 1, colour);
            g.fill(x + inset, y + h - d - 1, x + w - inset, y + h - d, colour);
        }
        if (h - 2 * r > 0) {
            g.fill(x, y + r, x + w, y + h - r, colour);
        }
    }

    /**
     * One rounded chip: a toggle in a row, or a plain action ("Clear all", "Done") when {@code row} is empty. Its
     * message is the bare label, so a test can find it by name; {@link #row()} says which row it is in.
     */
    public static final class Chip extends AbstractWidget {
        private final String label;
        private final String row;
        private final boolean selected;
        private final Runnable press;

        Chip(String label, String row, boolean selected, boolean active, Runnable press, int x, int y, int width) {
            super(x, y, width, CHIP_H, Component.literal(label));
            this.label = label;
            this.row = row;
            this.selected = selected;
            this.press = press;
            this.active = active;
        }

        /** The row's label ("Size", "Kind", ...), or "" for an action chip. */
        public String row() {
            return row;
        }

        public boolean selected() {
            return selected;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int bg = selected ? CHIP_ON_BG : CHIP_BG;
            int border = selected ? CHIP_ON_BORDER : (isHovered && active ? CHIP_BORDER_HOVER : CHIP_BORDER);
            int text = !active ? CHIP_OFF_TEXT : selected ? CHIP_ON_TEXT : CHIP_TEXT;
            pill(g, getX(), getY(), getWidth(), getHeight(), CHIP_H / 2, bg, border);
            var font = Minecraft.getInstance().font;
            String shown = font.width(label) <= getWidth() - 8 ? label : font.plainSubstrByWidth(label, getWidth() - 8);
            g.text(font, shown, getX() + (getWidth() - font.width(shown)) / 2, getY() + (getHeight() - 8) / 2,
                    text, false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            press.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal(row.isEmpty() ? label
                    : row + ": " + label + (selected ? " (selected)" : "")));
        }
    }
}
