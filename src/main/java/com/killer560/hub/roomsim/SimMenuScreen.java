package com.killer560.hub.roomsim;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * What the Dungeon Sim button opens: pick a previous run, generate a map, or load a single room.
 *
 * <p>killer560 (2026-09-28): "have it open a new menu that says load previous run create new map or load a
 * room [...] For generate new have an option for how many puzzles how many rooms to blood and if I want
 * specific rooms. For the room generator let me choose which room I want. Let there be a search bar to search
 * for rooms and filters for things like puzzles I choose and amount and 2x2 and 1x1 or whatnot."
 *
 * <p>killer560 (2026-10-15): "remove the pick what to practice and if there is a better way of using this space then
 * please use it." There is no in-between page any more: the four choices are a column down the left of one wide
 * panel and the page they pick fills the rest, so the room list or the runs are on screen the moment the menu
 * opens. It opens on the page it was last on ({@link SimMenuPrefs}). "Create a New Map" still opens the designer.
 */
public class SimMenuScreen extends Screen implements MenuThemedScreen {

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

    private enum Mode {
        PREVIOUS("Load a Previous Run", "Previous Runs"),
        ROOM("Load a Room", "Load a Room"),
        CYCLE("All Rooms (route practice)", "Route Practice");

        final String title;
        /** The label of its button in the left column. */
        final String nav;

        Mode(String title, String nav) {
            this.title = title;
            this.nav = nav;
        }
    }

    /** The left column's "Create a New Map" button, which opens the designer rather than a page here. */
    public static final String NEW_MAP_LABEL = "Create a New Map";

    private final Screen parent;
    private final SimMenuPrefs prefs = SimMenuPrefs.load();
    private Mode mode;

    private EditBox search;
    /** Kept across a visit to the Filters panel, which rebuilds this screen. */
    private String searchText = "";
    private int scroll;

    private List<String> listed = List.of();

    /**
     * Whether the room database had finished loading when {@link #listed} was last built.
     *
     * <p>The type filter reads the database, which loads on a background thread and answers NORMAL for every
     * room until it is done. A list filtered before that point would show no puzzles and never change, so the
     * render loop rebuilds it the moment the database arrives.
     */
    private boolean listedWithDatabase;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    /** The left column. */
    private int navX;
    private int navW;
    /** The page area right of it. */
    private int contentX;
    private int contentY;
    private int contentW;

    public SimMenuScreen(Screen parent) {
        super(Component.literal("Dungeon Sim"));
        this.parent = parent;
        Mode remembered = Mode.ROOM;
        for (Mode m : Mode.values()) {
            if (m.name().equalsIgnoreCase(prefs.page)) {
                remembered = m;
            }
        }
        this.mode = remembered;
    }

    @Override
    protected void init() {
        // Wide: the old 560-unit cap left most of a real monitor empty around a list of names.
        panelW = Math.min(this.width - 16, Math.max(420, Math.min((int) (this.width * 0.92), 820)));
        panelH = Math.min(this.height - 16, Math.max(240, Math.min((int) (this.height * 0.9), 500)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        navX = panelX + 8;
        navW = Math.max(112, Math.min(150, panelW / 5));
        contentX = navX + navW + 10;
        contentY = panelY + ScreenChrome.HEADER_H + 10;
        contentW = panelX + panelW - 8 - contentX;
        RoomLibrary.loadAsync();
        // See SimMapEditorScreen.init - the sim runs from the main menu, where nothing else starts this.
        com.killer560.hub.roomdatabase.RoomDatabase.ensureLoading();

        buildNav();
        switch (mode) {
            case PREVIOUS -> buildPrevious();
            case ROOM -> buildRoomPicker();
            case CYCLE -> buildCycle();
        }
    }

    /** The left column: the three pages, the designer, and Back. */
    private void buildNav() {
        int y = contentY;
        for (Mode m : new Mode[]{Mode.PREVIOUS, null, Mode.ROOM, Mode.CYCLE}) {
            if (m == null) {
                // killer560 (2026-09-29): Create a New Map goes straight to the designer.
                addRenderableWidget(SettingsButtonWidget.builder(Component.literal(NEW_MAP_LABEL), b ->
                        McCompat.setScreen(this.minecraft, new SimMapEditorScreen(this)))
                        .bounds(navX, y, navW, 20).build());
            } else {
                addRenderableWidget(SettingsButtonWidget.builder(Component.literal(m.nav), b -> showPage(m))
                        .bounds(navX, y, navW, 20).build());
            }
            y += 24;
        }
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(navX, panelY + panelH - 28, navW, 20).build());
    }

    /** Switches page and remembers it. */
    private void showPage(Mode m) {
        mode = m;
        scroll = 0;
        capturing = 0;
        prefs.page = m.name();
        prefs.save();
        rebuildWidgets();
    }

    /** Which route-practice key is waiting for a press: 0 none, 1 Next Room, 2 Previous Room. */
    private int capturing;

    /** Whether the database had loaded when the route-practice buttons were built - their counts need it. */
    private boolean cycleBuiltWithDatabase;

    /**
     * killer560 (2026-10-06): "when I select it then it goes to a new menu that says rooms without routes or all
     * rooms or rooms with auto routes", plus a keybinds section for Next Room / Previous Room.
     */
    private void buildCycle() {
        int w = contentW;
        int x = contentX;
        int y = contentY + 24;
        boolean ready = com.killer560.hub.roomdatabase.RoomDatabase.isReady();
        cycleBuiltWithDatabase = ready;
        // All Rooms' own filters (killer560, 2026-10-07): the same Filters panel as the designer and Load a Room, on
        // SimRoomFilters.CYCLE. The counts below are after them.
        filtersButton(SimRoomFilters.CYCLE, "Narrows the rooms /next and /back step through.",
                contentX + contentW - filtersWidth(), contentY);
        for (SimRoomCycle.Choice c : SimRoomCycle.Choice.values()) {
            String count = ready ? " (" + SimRoomCycle.roomsFor(c).size() + ")" : " §7(loading...)";
            var button = SettingsButtonWidget.builder(Component.literal(c.label + count), b -> {
                if (SimRoomCycle.start(this.minecraft, c)) {
                    McCompat.setScreen(this.minecraft, null);
                }
            }).bounds(x, y, w, 20).build();
            button.active = ready;
            addRenderableWidget(button);
            y += 24;
        }
        // Keybinds: Next Room and Previous Room, the same as /next and /back, only inside the sim. Unbound by
        // default. Esc clears a bind; a mouse button can be one.
        // Room under the buttons for the KEYBINDS heading, drawn in render.
        y += 20;
        int half = (w - 6) / 2;
        // Previous Room on the left, Next Room on the right (killer560, 2026-10-13).
        addRenderableWidget(SettingsButtonWidget.builder(keyLabel("Next Room", 1, SimRoomCycle.getNextKey()), b -> {
            capturing = 1;
            b.setMessage(Component.literal("Next Room: §ePress any key..."));
        }).bounds(x + half + 6, y, w - half - 6, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(keyLabel("Previous Room", 2, SimRoomCycle.getBackKey()),
                b -> {
                    capturing = 2;
                    b.setMessage(Component.literal("Previous Room: §ePress any key..."));
                }).bounds(x, y, half, 20).build());
    }

    /** The y of the route practice page's KEYBINDS heading. */
    private int cycleKeybindsY() {
        return contentY + 24 + 3 * 24 + 6;
    }

    private Component keyLabel(String label, int which, int code) {
        if (capturing == which) {
            return Component.literal(label + ": §ePress any key...");
        }
        String name = code == com.killer560.hub.util.KeyUtil.NONE
                ? "§7Not Set" : "§e" + com.killer560.hub.util.KeyUtil.bindDisplayName(code);
        return Component.literal(label + ": " + name);
    }

    /** Stores a captured bind (a key code, or a mouse code) for whichever key row is listening. */
    private void applyCapture(int code) {
        if (capturing == 1) {
            SimRoomCycle.setNextKey(code);
        } else if (capturing == 2) {
            SimRoomCycle.setBackKey(code);
        }
        capturing = 0;
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (mode == Mode.CYCLE && capturing != 0) {
            applyCapture(event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                    ? com.killer560.hub.util.KeyUtil.NONE : event.key());
            return true;
        }
        return super.keyPressed(event);
    }

    private void buildPrevious() {
        listed = SimRunHistory.savedRuns();
    }

    private void buildRoomPicker() {
        int x = contentX;
        int y = contentY;
        int right = contentX + contentW;
        // killer560 (2026-10-07): "the single room should have the same filter option." The Routes, shape and
        // Puzzles buttons that sat here (2026-09-30, 2026-10-05) are rows of the shared Filters panel now, on
        // Load a Room's own SimRoomFilters.PICKER; the button sits beside the search box.
        int fw = filtersWidth();
        search = new ThemedEditBox(this.font, x, y, right - x - fw - 4, 18, Component.literal("Search"));
        ((ThemedEditBox) search).hint("Search rooms...");
        search.setValue(searchText);
        search.setResponder(v -> {
            searchText = v;
            scroll = 0;
            refreshRoomList();
        });
        addRenderableWidget(search);
        filtersButton(SimRoomFilters.PICKER, "Narrows the Load a Room list.", right - fw, y);
        refreshRoomList();
    }

    private int filtersWidth() {
        return this.font.width("Filters (99)") + 12;
    }

    /** A "Filters" button opening the shared panel on {@code filter}. */
    private void filtersButton(SimRoomFilter filter, String note, int x, int y) {
        int active = filter.activeCount();
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(
                active == 0 ? "Filters" : "§6Filters (" + active + ")"), b -> McCompat.setScreen(this.minecraft,
                        new SimRoomFilterScreen(this, filter, note)))
                .bounds(x, y, filtersWidth(), 18).build());
    }

    /**
     * Applies the search box and the filters.
     *
     * <p>Filtered from the captured library rather than from a hardcoded room list: the sim can only load a room
     * it has, so offering one it does not would be offering a button that cannot work.
     */
    private void refreshRoomList() {
        String query = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        for (String name : RoomLibrary.names()) {
            if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            // The shared filter (SimRoomFilter): types come from the room database, never from the name.
            if (!SimRoomFilters.PICKER.matches(name)) {
                continue;
            }
            out.add(name);
        }
        listed = out;
        listedWithDatabase = com.killer560.hub.roomdatabase.RoomDatabase.isReady();
    }

    /** The text at the right of a room's row, or null for a room the routes view leaves out and has no route. */
    static String routeMarker(String name) {
        int nodes = SimRoomRoutes.routeNodes(name);
        if (nodes > 0) {
            return nodes + (nodes == 1 ? " node" : " nodes");
        }
        return SimRoomRoutes.isEligible(name) ? "no routes" : null;
    }

    /** What the room list shows right now, in order. For the testkit; the screen itself reads the field. */
    public List<String> listedRooms() {
        return List.copyOf(listed);
    }

    /** Opens straight on "Load a Room", for the testkit. */
    public static SimMenuScreen roomPicker(Screen parent) {
        SimMenuScreen screen = new SimMenuScreen(parent);
        screen.mode = Mode.ROOM;
        return screen;
    }

    private int listTop() {
        return mode == Mode.ROOM ? contentY + 24 : contentY;
    }

    private int listHeight() {
        return panelY + panelH - 8 - listTop();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, listed.size() * 14 - listHeight());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(scrollY * 28)));
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // A key row waiting for a bind takes the next mouse press as the bind, as the settings tabs do.
        if (mode == Mode.CYCLE && capturing != 0) {
            applyCapture(com.killer560.hub.util.KeyUtil.codeForMouseButton(event.button()));
            return true;
        }
        if ((mode == Mode.ROOM || mode == Mode.PREVIOUS) && !listed.isEmpty()) {
            double my = event.y();
            int row = (int) ((my - listTop() + scroll) / 14);
            if (my >= listTop() && my <= listTop() + listHeight() && row >= 0 && row < listed.size()) {
                String picked = listed.get(row);
                if (mode == Mode.ROOM) {
                    SimBuilder.buildSingleRoom(this.minecraft, picked);
                } else {
                    SimRunHistory.load(this.minecraft, picked);
                }
                McCompat.setScreen(this.minecraft, null);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The menu theme (screen sweep, 2026-10-13): the menu's panel and header bar, lists in a recessed well with
        // hover / selected rows, every colour from the active theme.
        ScreenChrome.backdrop(g, this.width, this.height);
        themedBounds = new int[]{panelX, panelY, panelW, panelH};
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(g, panelX, panelY, panelW);
        ScreenChrome.title(g, "DUNGEON SIM", panelX + 10, panelY + 11, false);
        String page = mode.title.toUpperCase(Locale.ROOT);
        MenuStyle.text(g, page, panelX + panelW - 12 - this.font.width(page), panelY + 11, MenuStyle.DIM);
        drawNav(g);
        if (mode == Mode.CYCLE) {
            if (com.killer560.hub.roomdatabase.RoomDatabase.isReady() != cycleBuiltWithDatabase) {
                rebuildWidgets();
            }
            ScreenChrome.section(g, "ROUTE PRACTICE", contentX, contentY + 5, contentX + contentW - filtersWidth() - 8,
                    false);
            // killer560 (2026-10-15): "remove the top text and the no puzzles blood text stuff."
            ScreenChrome.section(g, "KEYBINDS (sim only)", contentX, cycleKeybindsY(), contentX + contentW, false);
        } else {
            boolean needsDatabase = !SimRoomFilters.PICKER.isDefault();
            if (mode == Mode.ROOM && needsDatabase
                    && com.killer560.hub.roomdatabase.RoomDatabase.isReady() != listedWithDatabase) {
                refreshRoomList();
            }
            int top = listTop();
            int h = listHeight();
            ScreenChrome.well(g, contentX - 1, top - 1, contentW + 2, h + 2);
            g.enableScissor(contentX, top, contentX + contentW, top + h);
            try {
                if (listed.isEmpty()) {
                    String none = mode != Mode.ROOM ? "No saved runs yet"
                            : needsDatabase && !listedWithDatabase ? "Loading the room database..."
                            : "No rooms match";
                    ScreenChrome.text(g, none, contentX + 8, top + 6, MenuStyle.DIM);
                }
                for (int i = 0; i < listed.size(); i++) {
                    int rowY = top + i * 14 - scroll + 3;
                    if (rowY > top + h) {
                        break;
                    }
                    if (rowY + 14 < top) {
                        continue;
                    }
                    String name = listed.get(i);
                    boolean hover = mouseX >= contentX && mouseX < contentX + contentW && mouseY >= top
                            && mouseY < top + h && mouseY >= rowY - 3 && mouseY < rowY + 11;
                    if (hover) {
                        GuiRects rr = GuiRects.begin(g);
                        ScreenChrome.row(rr, contentX, rowY - 3, contentW, 14, true, false, false);
                        rr.submit();
                    }
                    int nameRoom = contentW - 16;
                    if (mode == Mode.ROOM) {
                        String marker = routeMarker(name);
                        if (marker != null) {
                            int mw = this.font.width(marker);
                            int colour = SimRoomRoutes.routeNodes(name) > 0 ? ScreenChrome.readable(0xFF55FF55) : MenuStyle.DIM;
                            MenuStyle.text(g, marker, contentX + contentW - 8 - mw, rowY, colour);
                            nameRoom -= mw + 8;
                        }
                    }
                    MenuStyle.text(g, this.font.plainSubstrByWidth(name, nameRoom), contentX + 8, rowY,
                            hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
                }
            } finally {
                g.disableScissor();
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** The left column's frame, the open page's marker, and the library line under the buttons. */
    private void drawNav(GuiGraphicsExtractor g) {
        GuiRects r = GuiRects.begin(g);
        // A separator between the column and the page.
        r.fill(contentX - 6, contentY, contentX - 5, panelY + panelH - 8, MenuStyle.alpha(MenuStyle.BLACK, 0x80));
        int index = switch (mode) {
            case PREVIOUS -> 0;
            case ROOM -> 2;
            case CYCLE -> 3;
        };
        int y = contentY + index * 24;
        // The open page: an accent bar beside its button.
        r.fill(navX - 4, y, navX - 2, y + 20, MenuStyle.OR);
        r.submit();
        String library = RoomLibrary.roomCount() == 0
                ? "No rooms loaded"
                : RoomLibrary.roomCount() + " rooms captured";
        MenuStyle.text(g, this.font.plainSubstrByWidth(library, navW), navX, contentY + 4 * 24 + 4, MenuStyle.DIM);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
