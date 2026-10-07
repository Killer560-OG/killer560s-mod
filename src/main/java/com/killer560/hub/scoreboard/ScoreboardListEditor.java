package com.killer560.hub.scoreboard;

import com.killer560.hub.gui.DragListWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The Custom Scoreboard's list editor, SkyHanni style (killer560, 2026-10-07: "redo the custom scoreboard such that
 * things are draggable to be in order and it has the add and trashcan system SkyHanni uses instead of an individual
 * toggle system"): an Add button that drops down every entry not on the board, a Reset Order button, and a
 * {@link DragListWidget} of the entries that are - drag to reorder, trash can to take one off, click to select it.
 * <p>
 * Used by the settings tab ({@code CustomScoreboardTab}) and by {@link ScoreboardEditorScreen}, so both edit the same
 * {@link EntryOrder}s the same way. The open dropdown, the selected entry and the list's scroll are remembered per
 * list for the session (both screens rebuild every widget after each change).
 * <p>
 * Decisions: Add puts the entry at the BOTTOM of the board, as SkyHanni's does (MoulConfig's draggable list appends;
 * javap of SkyHanni 7.48.0), and then selects it and scrolls to it so it can be dragged straight up. The dropdown
 * opens in place, pushing the list down, rather than floating over it: the mod menu draws its rows in order but gives
 * a press to the FIRST row under the cursor, so a popup over other rows would look on top and act underneath
 * (docs/LESSONS.md, "Two mod-menu rows on one rectangle"). The sixteen Separators share one name, so Add offers
 * "Separator" once and takes the first one not on the board.
 */
public final class ScoreboardListEditor {

    /** The three ordered lists of the board. */
    public enum Section {
        LINES("Line", "Lines"), EVENTS("Event", "Events"), STATS("Stat", "Chunked Stats");

        public final String one;
        public final String title;

        Section(String one, String title) {
            this.one = one;
            this.title = title;
        }
    }

    /** Session state of one list's editor. */
    private static final class State {
        final DragListWidget.ScrollState scroll = new DragListWidget.ScrollState();
        Enum<?> selected;
        boolean addOpen;
    }

    private static final Map<Section, State> STATES = new EnumMap<>(Section.class);

    static {
        for (Section s : Section.values()) {
            STATES.put(s, new State());
        }
    }

    public static final int ROW = 22;
    private static final int GAP = 4;
    private static final int PICK_H = 16;
    private static final int PICK_ROW = 18;

    private ScoreboardListEditor() {
    }

    /** The selected entry of {@code section}'s list (one of its enum's constants), or null. */
    public static Enum<?> selected(Section section) {
        return STATES.get(section).selected;
    }

    /** Closes every dropdown and clears every selection (tests, and a fresh menu open does not need it). */
    public static void resetSession() {
        for (State st : STATES.values()) {
            st.selected = null;
            st.addOpen = false;
            st.scroll.scroll = 0;
        }
    }

    /**
     * The Add and Reset Order row, the Add dropdown when open, both {@code width} wide from {@code x}.
     * @return the y below them, where the list goes
     */
    public static int buildControls(List<AbstractWidget> out, Section section, int x, int y, int width,
                                    Runnable rebuild) {
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        return switch (section) {
            case LINES -> controls(out, section, cfg.entries(), e -> e.label, cfg::resetEntries, x, y, width, rebuild);
            case EVENTS -> controls(out, section, cfg.events(), e -> e.label, cfg::resetEvents, x, y, width, rebuild);
            case STATS -> controls(out, section, cfg.chunkedStats(), s -> s.label, cfg::resetChunkedStats, x, y, width,
                    rebuild);
        };
    }

    /**
     * The list itself, {@code rows} rows tall at most (it scrolls past that, and shrinks to fit a short list).
     * @return the y below it
     */
    public static int buildList(List<AbstractWidget> out, Section section, int x, int y, int width, int rows,
                                Runnable rebuild) {
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        return switch (section) {
            case LINES -> list(out, section, cfg.entries(), e -> e.label, x, y, width, rows, rebuild);
            case EVENTS -> list(out, section, cfg.events(), e -> e.label, x, y, width, rows, rebuild);
            case STATS -> list(out, section, cfg.chunkedStats(), s -> s.label, x, y, width, rows, rebuild);
        };
    }

    private static <E extends Enum<E>> int controls(List<AbstractWidget> out, Section section, EntryOrder<E> order,
                                                    Function<E, String> label, Runnable reset, int x, int y, int width,
                                                    Runnable rebuild) {
        State st = STATES.get(section);
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        List<E> picks = picks(order, label);
        int half = (width - GAP) / 2;
        String addText = "Add " + section.one + ": " + (picks.isEmpty() ? "§7none left"
                : (st.addOpen ? "§6" : "§a") + picks.size() + " available " + (st.addOpen ? "▲" : "▼"));
        SettingsButtonWidget.Builder add = SettingsButtonWidget.builder(Component.literal(addText), btn -> {
            st.addOpen = !st.addOpen;
            rebuild.run();
        }).bounds(x, y, half, 18);
        if (st.addOpen) {
            add.primary();
        }
        out.add(add.build());
        out.add(SettingsButtonWidget.builder(Component.literal("Reset Order"), btn -> {
                    reset.run();
                    cfg.save();
                    st.selected = null;
                    st.scroll.scroll = 0;
                    rebuild.run();
                }).bounds(x + half + GAP, y, width - half - GAP, 18).build());
        y += ROW;
        if (!st.addOpen) {
            return y;
        }
        if (picks.isEmpty()) {
            out.add(new StringWidget(x, y, width, 12, Component.literal("§7Every " + section.one.toLowerCase()
                    + " is already on the board."), Minecraft.getInstance().font));
            return y + 16;
        }
        int cols = width >= 330 ? 3 : 2;
        int colW = (width - GAP * (cols - 1)) / cols;
        for (int i = 0; i < picks.size(); i++) {
            E e = picks.get(i);
            int px = x + (i % cols) * (colW + GAP);
            int py = y + (i / cols) * PICK_ROW;
            out.add(SettingsButtonWidget.builder(Component.literal("+ " + label.apply(e)), btn -> {
                        order.add(e);
                        cfg.save();
                        st.addOpen = false;
                        st.selected = e;
                        st.scroll.scroll = Integer.MAX_VALUE / 2; // clamped to the bottom, where it went
                        rebuild.run();
                    }).bounds(px, py, colW, PICK_H).build());
        }
        return y + ((picks.size() + cols - 1) / cols) * PICK_ROW + 4;
    }

    /** What Add offers: the pool in default order, one entry per name (the Separators are interchangeable). */
    static <E extends Enum<E>> List<E> picks(EntryOrder<E> order, Function<E, String> label) {
        List<E> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (E e : order.available()) {
            if (names.add(label.apply(e))) {
                out.add(e);
            }
        }
        return out;
    }

    private static <E extends Enum<E>> int list(List<AbstractWidget> out, Section section, EntryOrder<E> order,
                                                Function<E, String> label, int x, int y, int width, int maxRows,
                                                Runnable rebuild) {
        State st = STATES.get(section);
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();
        if (st.selected != null && !order.active().contains(st.selected)) {
            st.selected = null;
        }
        int rows = Math.max(3, Math.min(maxRows, order.size()));
        DragListWidget.Model model = new DragListWidget.Model() {
            @Override
            public int size() {
                return order.size();
            }

            @Override
            public String label(int index) {
                return label.apply(order.get(index));
            }

            @Override
            public boolean selected(int index) {
                return order.get(index) == st.selected;
            }

            @Override
            public void click(int index) {
                E e = order.get(index);
                st.selected = st.selected == e ? null : e;
                rebuild.run();
            }

            @Override
            public void move(int from, int to) {
                order.move(from, to);
                cfg.save();
                rebuild.run();
            }

            @Override
            public void trash(int index) {
                E e = order.removeAt(index);
                if (e != null && e == st.selected) {
                    st.selected = null;
                }
                cfg.save();
                rebuild.run();
            }
        };
        out.add(new DragListWidget(x, y, width, rows, model, st.scroll,
                Component.literal("Scoreboard " + section.title), "Nothing here - use Add " + section.one + "."));
        return y + DragListWidget.heightFor(rows);
    }
}
