package com.killer560.hub.roomsim;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.hud.GuiRects;
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
 * <p>killer560 (2026-10-07), with a mockup: a panel titled "Filters", and one row per category - a label on the left,
 * chips on the right. Rows: Size, Kind, Rare room, Secrets and Your routes from the mockup, then Puzzles and Crypts,
 * which the designer's filters already had.
 *
 * <p>Drawn in the mod's own style, not the mockup's (killer560, 2026-10-07: "make sure that the filter section for
 * creating the map is in our mods style and not a direct copy"): the panel, header bar, title and "N of M rooms" are
 * Design a Map's ({@link SimMapEditorScreen}), in the menu theme ({@link ScreenChrome}, screen sweep 2026-10-13); a chip
 * is the menu's value pill, lit in the accent when chosen; Clear all and Done are the menu's raised buttons.
 *
 * <p>The chips flow: they wrap onto as many lines as the width needs, and on a narrow window the labels go on a line
 * of their own above their chips. When that is taller than the window the rows scroll (mouse wheel) between the
 * header and the footer, so nothing is ever drawn over anything else - testkit 75 and 97-sim-roomcycle check every
 * chip's box against every other at several window sizes.
 */
public class SimRoomFilterScreen extends Screen implements MenuThemedScreen {

    /** The Theme setting this screen follows (ScreenThemes; "Theme Default" = the menu's theme). */
    public static final String THEME_ID = "sim";

    @Override
    public String themeId() {
        return THEME_ID;
    }

    /** {x, y, w, h} of the themed panel as last drawn (the testkit samples it). */
    private int[] themedBounds = new int[4];

    @Override
    public int[] themedPanelBounds() {
        return themedBounds.clone();
    }

    static final int CHIP_H = 16;
    private static final int CHIP_GAP = 4;
    private static final int LINE = CHIP_H + 4;
    private static final int ROW_GAP = 5;
    private static final int PAD = 12;
    /** Design a Map's 30-pixel header bar, and a gap under it. */
    private static final int BAR = 30;
    private static final int HEADER = BAR + 6;
    /** The footer's buttons are the mod's 20-pixel height, as on Design a Map. */
    private static final int BUTTON_H = 20;
    private static final int FOOTER = BUTTON_H + 12;
    /** Below this many pixels for chips beside the labels, each label takes a line of its own. */
    private static final int MIN_CHIP_AREA = 150;

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
                "Narrows the list and Generate. Required rooms always generate.");
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

        // Footer: Clear all and Done bottom-right, the mod's own buttons, outside the scrolling band so they are
        // always reachable. The header bar keeps "N of M rooms" on the right (Design a Map dropped its own, 2026-10-15).
        int buttonW = footerButtonW();
        int by = panelY + panelH - BUTTON_H - 6;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + panelW - PAD - buttonW, by, buttonW, BUTTON_H).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Clear all"), b -> {
            filter.clear();
            rebuildWidgets();
        }).bounds(panelX + panelW - PAD - buttonW * 2 - 6, by, buttonW, BUTTON_H).build());

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

    /**
     * The note at this width: whole, else its leading sentences that fit, else trimmed with an ellipsis - never cut
     * mid-word, which is what a plain trim did beside the footer's two buttons.
     */
    private String fitNote(String text, int maxW) {
        if (this.font.width(text) <= maxW) {
            return text;
        }
        for (int cut = text.lastIndexOf(". "); cut > 0; cut = text.lastIndexOf(". ", cut - 1)) {
            String head = text.substring(0, cut + 1);
            if (this.font.width(head) <= maxW) {
                return head;
            }
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxW - this.font.width("..."))) + "...";
    }

    /** Clear all and Done share one width, so the footer reads as a row of buttons like Design a Map's. */
    private int footerButtonW() {
        return Math.max(this.font.width("Clear all"), this.font.width("Done")) + 20;
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
        // Design a Map's frame (SimMapEditorScreen.extractRenderState): the menu theme's panel and header bar, the title,
        // and the room count right-aligned in the bar.
        ScreenChrome.backdrop(g, this.width, this.height);
        themedBounds = new int[]{panelX, panelY, panelW, panelH};
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX, panelY, panelW);
        ScreenChrome.title(g, "FILTERS", panelX + 10, panelY + 11, false);
        String count = shown + " of " + total + " rooms" + (filter.isDefault() ? "" : " (filtered)");
        int maxW = panelW - 20 - this.font.width("FILTERS") - 10;
        String shownCount = this.font.width(count) <= maxW ? count : shown + "/" + total;
        MenuStyle.text(g, shownCount, panelX + panelW - 10 - this.font.width(shownCount), panelY + 11,
                filter.isDefault() ? MenuStyle.DIM : MenuStyle.OR);

        // The footer's rule, then the note beside Clear all and Done.
        GuiRects rule = GuiRects.begin(g);
        MenuStyle.separator(rule, panelX + 3, panelX + panelW - 3, contentBottom + 1);
        rule.submit();
        int noteW = panelW - PAD * 2 - footerButtonW() * 2 - 6 - 8;
        String foot = RoomDatabase.isReady() ? note : "room database loading...";
        ScreenChrome.text(g, fitNote(foot, Math.max(0, noteW)), panelX + PAD, panelY + panelH - BUTTON_H / 2 - 6 - 4, MenuStyle.DIM);

        g.enableScissor(panelX + 1, contentTop, panelX + panelW - 1, contentBottom);
        for (Object[] l : labels) {
            int ly = (Integer) l[2] - scroll;
            // Same rule as the chips (applyScroll): a label shows only when it fits whole in the band.
            if (ly >= contentTop && ly + 9 <= contentBottom) {
                MenuStyle.text(g, (String) l[0], (Integer) l[1], ly, MenuStyle.TXT_SOFT);
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            // The main menu's scrollbar (ModScreen): a dark track and an amber thumb.
            int band = contentBottom - contentTop;
            int bar = Math.max(12, band * band / contentHeight);
            int by = contentTop + (band - bar) * scroll / maxScroll();
            int sx = panelX + panelW - PAD + 4;
            GuiRects sb = GuiRects.begin(g);
            MenuStyle.scrollbar(sb, sx, contentTop, contentBottom - contentTop, scroll, maxScroll(), band);
            sb.submit();
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * One chip: a toggle in a row, drawn as a {@link SettingsButtonWidget} box. Its message is the bare label, so a
     * test can find it by name; {@link #row()} says which row it is in. The color is added only when drawing: §6 when
     * chosen, §8 when the row is switched off, as the mod's tabs color a chosen or disabled value.
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
            // The menu's value pill: recessed when not chosen, lit in the accent when chosen (screen sweep, 2026-10-13).
            boolean hover = isHovered && active;
            GuiRects r = GuiRects.begin(g);
            MenuStyle.pill(r, getX(), getY(), getWidth(), getHeight(), selected && active, hover);
            r.submit();
            var font = Minecraft.getInstance().font;
            String shown = font.width(label) <= getWidth() - 8 ? label : font.plainSubstrByWidth(label, getWidth() - 8);
            int colour = !active ? MenuStyle.DIM2 : selected
                    ? (MenuStyle.luminance(MenuStyle.OR) > 0.55f ? 0xFF1A120A : 0xFFFFFFFF)
                    : (hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
            g.text(font, shown, getX() + (getWidth() - font.width(shown)) / 2, getY() + (getHeight() - 8) / 2, colour,
                    MenuStyle.TEXT_SHADOW && !(selected && active));
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
