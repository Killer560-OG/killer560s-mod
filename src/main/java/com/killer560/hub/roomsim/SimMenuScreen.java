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
        detail = null;
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

    private void buildRoomPicker() {
        int x = contentX;
        int y = contentY;
        int right = contentX + contentW;
        // killer560 (2026-10-07): "the single room should have the same filter option." The Routes, shape and
        // Puzzles buttons that sat here (2026-09-30, 2026-10-05) are rows of the shared Filters panel now, on
        // Load a Room's own SimRoomFilters.PICKER; the button sits beside the search box.
        // killer560 (2026-10-15): "have a sort button as well as filter for things like secret count room size, ect."
        // Sort beside the search, and the rows used most (Size, Secrets, Kind) out on the page as well as in the
        // panel - the same SimRoomFilters.PICKER, so the two always agree.
        int fw = filtersWidth();
        int sw = sortWidth();
        search = new ThemedEditBox(this.font, x, y, right - x - fw - sw - 8, 18, Component.literal("Search"));
        ((ThemedEditBox) search).hint("Search rooms...");
        search.setValue(searchText);
        search.setResponder(v -> {
            searchText = v;
            scroll = 0;
            refreshRoomList();
        });
        addRenderableWidget(search);
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal(sortLabel()), (b, dir) -> {
            setRoomSort(SettingsButtonWidget.step(prefs.roomSort, dir), null);
        }).bounds(right - fw - sw - 4, y, sw, 18).build());
        filtersButton(SimRoomFilters.PICKER, "Narrows the Load a Room list.", right - fw, y);
        quickFilters(x, y + 22, right);
        refreshRoomList();
    }

    /**
     * Sorts the room list by {@code sort}; {@code descending} null takes that key's natural direction (counts biggest
     * first). Saved, so the list comes back sorted the same way.
     */
    private void setRoomSort(SimMenuPrefs.RoomSort sort, Boolean descending) {
        prefs.roomSort = sort;
        prefs.roomDescending = descending != null ? descending : defaultDescending(sort);
        prefs.save();
        scroll = 0;
        rebuildWidgets();
    }

    /** The second row: the Size chips, then Secrets and Kind, wrapping onto another line if the page is narrow. */
    private void quickFilters(int x, int y, int right) {
        SimRoomFilter f = SimRoomFilters.PICKER;
        List<SettingsButtonWidget> chips = new ArrayList<>();
        for (String size : SimRoomFilter.SIZES) {
            boolean on = f.hasSize(size);
            chips.add(SettingsButtonWidget.builder(Component.literal((on ? "§6" : "") + size), b -> {
                f.toggleSize(size);
                scroll = 0;
                rebuildWidgets();
            }).bounds(0, 0, Math.max(26, this.font.width(size) + 10), 18).build());
        }
        int lastSize = chips.size() - 1;
        chips.add(SettingsButtonWidget.cycling(Component.literal("Secrets: " + f.secrets().label), (b, dir) -> {
            f.setSecrets(SettingsButtonWidget.step(f.secrets(), dir));
            scroll = 0;
            rebuildWidgets();
        }).bounds(0, 0, this.font.width("Secrets: 4 or more") + 12, 18).build());
        chips.add(SettingsButtonWidget.cycling(Component.literal("Kind: " + kindValue()), (b, dir) -> {
            stepKind(dir);
            scroll = 0;
            rebuildWidgets();
        }).bounds(0, 0, this.font.width("Kind: Champion") + 12, 18).build());
        int cx = x;
        int cy = y;
        quickRows = 1;
        for (int i = 0; i < chips.size(); i++) {
            SettingsButtonWidget chip = chips.get(i);
            if (cx > x && cx + chip.getWidth() > right) {
                cx = x;
                cy += 22;
                quickRows++;
            }
            chip.setX(cx);
            chip.setY(cy);
            addRenderableWidget(chip);
            cx += chip.getWidth() + (i == lastSize ? 10 : 3);
        }
    }

    /** How many lines the quick filters took, for where the list starts. */
    private int quickRows = 1;

    /** The Kind chip's value: Any, the one kind chosen, or how many when the panel chose several. */
    private String kindValue() {
        List<String> chosen = new ArrayList<>();
        for (String k : SimRoomFilter.KINDS) {
            if (SimRoomFilters.PICKER.hasKind(k)) {
                chosen.add(k);
            }
        }
        if (chosen.isEmpty()) {
            return "Any";
        }
        return chosen.size() == 1 ? SimRoomFilter.kindLabel(chosen.get(0)) : chosen.size() + " kinds";
    }

    /** Any -> each kind on its own -> Any. Several kinds chosen in the panel step on from Any. */
    private void stepKind(int dir) {
        SimRoomFilter f = SimRoomFilters.PICKER;
        int current = -1;
        int chosen = 0;
        for (int i = 0; i < SimRoomFilter.KINDS.size(); i++) {
            if (f.hasKind(SimRoomFilter.KINDS.get(i))) {
                current = i;
                chosen++;
            }
        }
        if (chosen > 1) {
            current = -1;
        }
        int next = SettingsButtonWidget.step(current + 1, dir, SimRoomFilter.KINDS.size() + 1) - 1;
        for (String k : SimRoomFilter.KINDS) {
            if (f.hasKind(k)) {
                f.toggleKind(k);
            }
        }
        if (next >= 0) {
            f.toggleKind(SimRoomFilter.KINDS.get(next));
        }
    }

    private int sortWidth() {
        return this.font.width("Sort: Secrets ▼") + 14;
    }

    private String sortLabel() {
        return "Sort: " + prefs.roomSort.label + (prefs.roomDescending ? " ▼" : " ▲");
    }

    /** Counts read best biggest first; names, types and sizes smallest first. */
    private static boolean defaultDescending(SimMenuPrefs.RoomSort sort) {
        return sort == SimMenuPrefs.RoomSort.SECRETS || sort == SimMenuPrefs.RoomSort.CRYPTS
                || sort == SimMenuPrefs.RoomSort.ROUTES;
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
        out.sort(roomOrder(prefs.roomSort, prefs.roomDescending));
        listed = out;
        listedWithDatabase = com.killer560.hub.roomdatabase.RoomDatabase.isReady();
    }

    /** Tiles a shape covers, so Size sorts 1x1 < 1x2 < 1x3 = L < 1x4 = 2x2. */
    private static int shapeTiles(String shape) {
        return switch (shape) {
            case "1x2" -> 2;
            case "1x3", "L" -> 3;
            case "1x4", "2x2" -> 4;
            default -> 1;
        };
    }

    /**
     * The order the room list is in. Every key falls back to the name, so rooms that tie (most share a size) stay
     * alphabetical whichever way the key runs.
     */
    static java.util.Comparator<String> roomOrder(SimMenuPrefs.RoomSort sort, boolean descending) {
        java.util.Comparator<String> byName = String.CASE_INSENSITIVE_ORDER;
        java.util.Comparator<String> key = switch (sort) {
            case NAME -> byName;
            case TYPE -> java.util.Comparator.comparing(SimRoomFilter::kindKey);
            case SIZE -> java.util.Comparator.<String>comparingInt(n -> shapeTiles(SimRoomFilter.shapeKey(n)))
                    .thenComparing(SimRoomFilter::shapeKey);
            case SECRETS -> java.util.Comparator.comparingInt(SimRoomFilter::secretsOf);
            case CRYPTS -> java.util.Comparator.comparingInt(SimRoomFilter::cryptsOf);
            case ROUTES -> java.util.Comparator.comparingInt(SimRoomRoutes::routeNodes);
        };
        if (descending) {
            key = key.reversed();
        }
        return sort == SimMenuPrefs.RoomSort.NAME ? key : key.thenComparing(byName);
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

    // ------------------------------------------------------------------------------------------- the room list

    private static final int ROW_H = 14;
    private static final int HEAD_H = 13;

    /** One column of the room list; {@code key} is what clicking its heading sorts by. */
    private record Col(SimMenuPrefs.RoomSort key, String label, int x, int w, boolean right) {
    }

    /** The well the room list sits in: its top, below the search row and the quick filters. */
    private int roomWellTop() {
        return contentY + 22 + quickRows * 22 + 2;
    }

    /** Where the first room row starts, under the column headings. */
    private int roomRowsTop() {
        return roomWellTop() + HEAD_H;
    }

    private int roomRowsHeight() {
        return panelY + panelH - 8 - roomRowsTop();
    }

    /**
     * The columns, laid out right to left so the name gets whatever is left. killer560 (2026-10-15): "you have so
     * much space can you make better use of it on this page" - the type, size, secrets, crypts and routes that used to
     * be one "no routes" word at the far end of each row. A narrow page drops Type and Crypts first.
     */
    private List<Col> columns() {
        boolean wide = contentW >= 380;
        int right = contentX + contentW - 10;
        List<Col> out = new ArrayList<>();
        int[] w = {64, 40, 46, 30, 64};
        SimMenuPrefs.RoomSort[] keys = {SimMenuPrefs.RoomSort.ROUTES, SimMenuPrefs.RoomSort.CRYPTS,
                SimMenuPrefs.RoomSort.SECRETS, SimMenuPrefs.RoomSort.SIZE, SimMenuPrefs.RoomSort.TYPE};
        String[] labels = {"Routes", "Crypts", "Secrets", "Size", "Type"};
        for (int i = 0; i < keys.length; i++) {
            if (!wide && (keys[i] == SimMenuPrefs.RoomSort.CRYPTS || keys[i] == SimMenuPrefs.RoomSort.TYPE)) {
                continue;
            }
            right -= w[i];
            boolean alignRight = keys[i] != SimMenuPrefs.RoomSort.TYPE && keys[i] != SimMenuPrefs.RoomSort.SIZE;
            out.add(new Col(keys[i], labels[i], right, w[i] - 6, alignRight));
        }
        out.add(new Col(SimMenuPrefs.RoomSort.NAME, "Name", contentX + 8, right - contentX - 14, false));
        java.util.Collections.reverse(out);
        return out;
    }

    /** {x, y, w, h} of room row {@code index} as drawn now (it may be scrolled out of the well). For the testkit. */
    public int[] roomRowBounds(int index) {
        return new int[]{contentX, roomRowsTop() + index * ROW_H - scroll, contentW - 6, ROW_H};
    }

    /** {x, y, w, h} of the visible part of the room list (the rows, under the headings). For the testkit. */
    public int[] roomListBounds() {
        return new int[]{contentX, roomRowsTop(), contentW, roomRowsHeight()};
    }

    /** {x, y, w, h} of a column heading ("NAME", "SECRETS"...), or null when the page has no such column. */
    public int[] columnHeaderBounds(String key) {
        for (Col c : columns()) {
            if (c.key().name().equalsIgnoreCase(key)) {
                return new int[]{c.x(), roomWellTop(), c.w(), HEAD_H};
            }
        }
        return null;
    }

    /** The room under a point, or -1. Only a point on a real row counts - not the empty well, not beside it. */
    private int roomAt(double mx, double my) {
        int top = roomRowsTop();
        if (mx < contentX || mx >= contentX + contentW - 6 || my < top || my >= top + roomRowsHeight()) {
            return -1;
        }
        int row = (int) Math.floor((my - top + scroll) / ROW_H);
        return row >= 0 && row < listed.size() ? row : -1;
    }

    private int maxScroll() {
        return Math.max(0, listed.size() * ROW_H - roomRowsHeight());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.round(scrollY * ROW_H * 2);
        if (mode == Mode.PREVIOUS && detail != null) {
            detailScroll = Math.max(0, Math.min(detailMaxScroll(), detailScroll - step));
        } else if (mode == Mode.PREVIOUS) {
            scroll = Math.max(0, Math.min(runsMaxScroll(), scroll - step));
        } else {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - step));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // A key row waiting for a bind takes the next mouse press as the bind, as the settings tabs do.
        if (mode == Mode.CYCLE && capturing != 0) {
            applyCapture(com.killer560.hub.util.KeyUtil.codeForMouseButton(event.button()));
            return true;
        }
        if (mode == Mode.ROOM && event.button() == 0) {
            // A heading sorts by its column; the same one again turns the order round.
            if (event.y() >= roomWellTop() && event.y() < roomRowsTop()) {
                for (Col c : columns()) {
                    if (event.x() >= c.x() - 3 && event.x() < c.x() + c.w() + 3) {
                        setRoomSort(c.key(), c.key() == prefs.roomSort ? !prefs.roomDescending : null);
                        return true;
                    }
                }
            }
            // killer560 (2026-10-15): "my mouse can be all the way out to the right side of the page not hovering
            // anything and it will still select a room if i click and it only should if i am on the right room."
            // The old test looked at the y alone, so any x on the screen picked the row at that height.
            int row = roomAt(event.x(), event.y());
            if (row >= 0) {
                SimBuilder.buildSingleRoom(this.minecraft, listed.get(row));
                McCompat.setScreen(this.minecraft, null);
                return true;
            }
        }
        if (mode == Mode.PREVIOUS && detail == null && event.button() == 0) {
            int i = runAt(event.x(), event.y());
            if (i >= 0) {
                detail = runs.get(i);
                detailScroll = 0;
                rebuildWidgets();
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
        switch (mode) {
            case CYCLE -> {
                if (com.killer560.hub.roomdatabase.RoomDatabase.isReady() != cycleBuiltWithDatabase) {
                    rebuildWidgets();
                }
                ScreenChrome.section(g, "ROUTE PRACTICE", contentX, contentY + 5,
                        contentX + contentW - filtersWidth() - 8, false);
                // killer560 (2026-10-15): "remove the top text and the no puzzles blood text stuff."
                ScreenChrome.section(g, "KEYBINDS (sim only)", contentX, cycleKeybindsY(), contentX + contentW, false);
            }
            case ROOM -> drawRoomList(g, mouseX, mouseY);
            case PREVIOUS -> drawRunList(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawRoomList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean needsDatabase = !SimRoomFilters.PICKER.isDefault() || prefs.roomSort != SimMenuPrefs.RoomSort.NAME;
        boolean ready = com.killer560.hub.roomdatabase.RoomDatabase.isReady();
        if (needsDatabase && ready != listedWithDatabase) {
            refreshRoomList();
        }
        int wellTop = roomWellTop();
        int top = roomRowsTop();
        int h = roomRowsHeight();
        ScreenChrome.well(g, contentX - 1, wellTop - 1, contentW + 2, h + HEAD_H + 2);
        List<Col> cols = columns();
        // The headings: the sorted one in the accent with its arrow, the rest dim; each one sorts.
        GuiRects hr = GuiRects.begin(g);
        hr.fill(contentX, top - 1, contentX + contentW, top, MenuStyle.alpha(MenuStyle.BLACK, 0x80));
        hr.submit();
        for (Col c : cols) {
            boolean sorted = c.key() == prefs.roomSort;
            String label = (c.key() == SimMenuPrefs.RoomSort.NAME ? c.label() + " (" + listed.size() + ")" : c.label())
                    + (sorted ? (prefs.roomDescending ? " ▼" : " ▲") : "");
            boolean hot = mouseY >= wellTop && mouseY < top && mouseX >= c.x() - 3 && mouseX < c.x() + c.w() + 3;
            int colour = sorted ? MenuStyle.OR : hot ? MenuStyle.TXT_HOVER : MenuStyle.DIM;
            int lw = this.font.width(label);
            MenuStyle.text(g, label, c.right() ? c.x() + c.w() - lw : c.x(), wellTop + 3, colour);
        }
        int hovered = roomAt(mouseX, mouseY);
        g.enableScissor(contentX, top, contentX + contentW, top + h);
        try {
            if (listed.isEmpty()) {
                String none = needsDatabase && !listedWithDatabase ? "Loading the room database..." : "No rooms match";
                ScreenChrome.text(g, none, contentX + 8, top + 6, MenuStyle.DIM);
            }
            int first = Math.max(0, scroll / ROW_H);
            GuiRects rr = GuiRects.begin(g);
            for (int i = first; i < listed.size(); i++) {
                int rowY = top + i * ROW_H - scroll;
                if (rowY > top + h) {
                    break;
                }
                ScreenChrome.row(rr, contentX, rowY, contentW - 6, ROW_H, i == hovered, false, i % 2 == 1);
            }
            rr.submit();
            for (int i = first; i < listed.size(); i++) {
                int rowY = top + i * ROW_H - scroll;
                if (rowY > top + h) {
                    break;
                }
                drawRoomRow(g, cols, listed.get(i), rowY + 3, i == hovered, ready);
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll() > 0) {
            ScreenChrome.scrollbar(g, contentX + contentW - 5, top, h, scroll, maxScroll(), h);
        }
    }

    private void drawRoomRow(GuiGraphicsExtractor g, List<Col> cols, String name, int y, boolean hover, boolean ready) {
        for (Col c : cols) {
            String text;
            int colour = MenuStyle.TXT;
            switch (c.key()) {
                case NAME -> {
                    text = name;
                    colour = hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT;
                }
                case TYPE -> {
                    String kind = SimRoomFilter.kindKey(name);
                    text = !ready ? "-" : SimRoomFilter.kindLabel(kind) + (SimRoomFilter.isRare(name) ? " *" : "");
                    colour = ready ? kindColour(kind) : MenuStyle.DIM;
                }
                case SIZE -> text = SimRoomFilter.shapeKey(name);
                case SECRETS -> text = ready ? String.valueOf(SimRoomFilter.secretsOf(name)) : "-";
                case CRYPTS -> text = ready ? String.valueOf(SimRoomFilter.cryptsOf(name)) : "-";
                case ROUTES -> {
                    // Each room's Auto Routes at a glance: green node count when it has a route, a dim "no routes"
                    // when it is one worth routing that has none, a dash for the rooms the routes view leaves out
                    // (puzzles, Blood, Entrance, Fairy, 0 secrets).
                    String marker = routeMarker(name);
                    text = marker == null ? "-" : marker;
                    colour = SimRoomRoutes.routeNodes(name) > 0 ? ScreenChrome.readable(0xFF55FF55) : MenuStyle.DIM;
                }
                default -> text = "";
            }
            String fitted = this.font.plainSubstrByWidth(text, c.w());
            int x = c.right() ? c.x() + c.w() - this.font.width(fitted) : c.x();
            MenuStyle.text(g, fitted, x, y, colour);
        }
    }

    /** A kind's colour in the list: the dungeon map's room colours, made readable on the theme. */
    private static int kindColour(String kind) {
        return switch (kind) {
            case "PUZZLE" -> ScreenChrome.readable(0xFFB67FE8);
            case "TRAP" -> ScreenChrome.readable(0xFFE89A45);
            case "CHAMPION" -> ScreenChrome.readable(0xFFE5D25A);
            case "BLOOD" -> ScreenChrome.readable(0xFFE05A5A);
            case "ENTRANCE" -> ScreenChrome.readable(0xFF55C46A);
            case "FAIRY" -> ScreenChrome.readable(0xFFF29AD0);
            default -> MenuStyle.TXT;
        };
    }

    // ------------------------------------------------------------------------------------------- saved runs

    /** The saved runs as listed, in the chosen order. */
    private List<com.killer560.hub.runsummary.RunRecord> runs = List.of();
    /** The run whose detail page is open, or null for the list. */
    private com.killer560.hub.runsummary.RunRecord detail;
    private int detailScroll;

    private static final int THUMB_CELL = 4;
    private static final int CARD_H = 56;
    private static final int CARD_GAP = 6;

    /**
     * killer560 (2026-10-15): "this page should have a small pic of the dungeon map with the info and it can be sorted
     * a different way to look better as well [...] i should click onto run then it shows more detail about said run."
     * Cards: the map as a thumbnail beside floor, score, time, date, party and secrets; Sort (Newest, Oldest, Best Score,
     * Fastest, Floor) and Floor beside each other above them. A card opens the run's own page; Load This Run is there.
     */
    private void buildPrevious() {
        if (detail != null) {
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Runs"), b -> {
                detail = null;
                detailScroll = 0;
                rebuildWidgets();
            }).bounds(contentX, contentY, 60, 18).build());
            com.killer560.hub.runsummary.RunRecord run = detail;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load This Run"), b -> {
                SimRunHistory.load(this.minecraft, run);
                McCompat.setScreen(this.minecraft, null);
            }).bounds(contentX + contentW - 100, contentY, 100, 18).primary().build());
            return;
        }
        List<String> floors = SimRunHistory.floors();
        if (!floors.contains(prefs.runFloor)) {
            prefs.runFloor = "All";
        }
        runs = SimRunHistory.runs(prefs.runSort, prefs.runFloor);
        int sw = this.font.width("Sort: Best Score") + 14;
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Sort: " + prefs.runSort.label), (b, dir) -> {
            prefs.runSort = SettingsButtonWidget.step(prefs.runSort, dir);
            prefs.save();
            scroll = 0;
            rebuildWidgets();
        }).bounds(contentX, contentY, sw, 18).build());
        int fw = this.font.width("Floor: M7 (Master)") + 14;
        addRenderableWidget(SettingsButtonWidget.cycling(Component.literal("Floor: " + prefs.runFloor), (b, dir) -> {
            List<String> all = SimRunHistory.floors();
            int i = Math.max(0, all.indexOf(prefs.runFloor));
            prefs.runFloor = all.get(SettingsButtonWidget.step(i, dir, all.size()));
            prefs.save();
            scroll = 0;
            rebuildWidgets();
        }).bounds(contentX + sw + 4, contentY, fw, 18).build());
    }

    private int cardsTop() {
        return contentY + 24;
    }

    private int cardsHeight() {
        return panelY + panelH - 8 - cardsTop();
    }

    /** Two columns of cards when the page is wide enough for two readable ones. */
    private int cardColumns() {
        return contentW - 6 >= 2 * 300 + CARD_GAP ? 2 : 1;
    }

    private int cardWidth() {
        int cols = cardColumns();
        return (contentW - 6 - (cols - 1) * CARD_GAP) / cols;
    }

    /** {x, y, w, h} of run card {@code i} as drawn now (it may be scrolled out of view). For the testkit too. */
    public int[] runCardBounds(int i) {
        int cols = cardColumns();
        int w = cardWidth();
        return new int[]{contentX + (i % cols) * (w + CARD_GAP), cardsTop() + (i / cols) * (CARD_H + CARD_GAP) - scroll,
                w, CARD_H};
    }

    /** {x, y, w, h} of card {@code i}'s map thumbnail (the grid itself, inside its 1-unit backdrop). */
    public int[] runThumbBounds(int i) {
        int[] c = runCardBounds(i);
        return new int[]{c[0] + 6, c[1] + 6, com.killer560.hub.runsummary.RunMapPainter.size(THUMB_CELL),
                com.killer560.hub.runsummary.RunMapPainter.size(THUMB_CELL)};
    }

    /** The runs as listed, in order. For the testkit. */
    public List<com.killer560.hub.runsummary.RunRecord> listedRuns() {
        return List.copyOf(runs);
    }

    /** The run whose page is open, or null. For the testkit. */
    public com.killer560.hub.runsummary.RunRecord detailRun() {
        return detail;
    }

    /** The open run page's text, § codes included. For the testkit. */
    public List<String> detailLines() {
        return detail == null ? List.of() : com.killer560.hub.runsummary.RunDetails.lines(detail);
    }

    private int runAt(double mx, double my) {
        if (my < cardsTop() || my >= cardsTop() + cardsHeight()) {
            return -1;
        }
        for (int i = 0; i < runs.size(); i++) {
            int[] c = runCardBounds(i);
            if (mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + c[3]) {
                return i;
            }
        }
        return -1;
    }

    private int runsMaxScroll() {
        int rows = (runs.size() + cardColumns() - 1) / cardColumns();
        return Math.max(0, rows * (CARD_H + CARD_GAP) - CARD_GAP - cardsHeight());
    }

    private void drawRunList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (detail != null) {
            drawRunDetail(g);
            return;
        }
        int top = cardsTop();
        int h = cardsHeight();
        String count = runs.size() + (runs.size() == 1 ? " run" : " runs");
        MenuStyle.text(g, count, contentX + contentW - this.font.width(count), contentY + 5, MenuStyle.DIM);
        if (runs.isEmpty()) {
            ScreenChrome.well(g, contentX - 1, top - 1, contentW + 2, h + 2);
            String none = SimRunHistory.runs().isEmpty()
                    ? "No saved runs yet - runs with a map are saved by Run Summary"
                    : "No runs on " + prefs.runFloor;
            ScreenChrome.text(g, this.font.plainSubstrByWidth(none, contentW - 16), contentX + 8, top + 6, MenuStyle.DIM);
            return;
        }
        int hovered = runAt(mouseX, mouseY);
        g.enableScissor(contentX, top, contentX + contentW, top + h);
        try {
            GuiRects r = GuiRects.begin(g);
            for (int i = 0; i < runs.size(); i++) {
                int[] c = runCardBounds(i);
                if (c[1] > top + h || c[1] + c[3] < top) {
                    continue;
                }
                ScreenChrome.card(r, c[0], c[1], c[2], c[3], i == hovered);
            }
            r.submit();
            for (int i = 0; i < runs.size(); i++) {
                int[] c = runCardBounds(i);
                if (c[1] > top + h || c[1] + c[3] < top) {
                    continue;
                }
                drawRunCard(g, runs.get(i), c, i == hovered);
            }
        } finally {
            g.disableScissor();
        }
        if (runsMaxScroll() > 0) {
            ScreenChrome.scrollbar(g, contentX + contentW - 5, top, h, scroll, runsMaxScroll(), h);
        }
    }

    private void drawRunCard(GuiGraphicsExtractor g, com.killer560.hub.runsummary.RunRecord run, int[] c, boolean hover) {
        int[] thumb = {c[0] + 6, c[1] + 6};
        com.killer560.hub.runsummary.RunMapPainter.draw(g, run.map(), thumb[0], thumb[1], THUMB_CELL, 1);
        int tx = thumb[0] + com.killer560.hub.runsummary.RunMapPainter.size(THUMB_CELL) + 9;
        int tw = c[0] + c[2] - 6 - tx;
        String score = run.effectiveScore() >= 0 ? String.valueOf(run.effectiveScore()) : "-";
        String rank = run.effectiveRank() == null ? "" : " §7(" + run.effectiveRank() + ")";
        String time = run.hypixelTime() != null ? run.hypixelTime()
                : com.killer560.hub.runsummary.RunRecord.formatTime(run.totalMs());
        String best = run.bestTime() || run.bestScore() ? " §a*PB*" : "";
        List<String> lines = new ArrayList<>();
        lines.add("§6" + run.floorLabel() + "  §fscore §6" + score + rank + "  §f" + time + best);
        lines.add("§7" + run.dateText() + "  §8|  §7" + run.map().rooms().size() + " rooms");
        List<String> names = new ArrayList<>();
        for (com.killer560.hub.runsummary.RunRecord.PartyMember m : run.party()) {
            names.add(com.killer560.hub.runsummary.RunSummaryFeature.currentIgn(m));
        }
        lines.add(names.isEmpty() ? "§7Party: -" : "§7Party: §f" + String.join(", ", names));
        String secrets = run.secretsFound() >= 0
                ? run.secretsFound() + (run.totalSecrets() > 0 ? "/" + run.totalSecrets() : "") : "-";
        lines.add("§f" + secrets + " §7secrets  §f" + (run.crypts() >= 0 ? run.crypts() : "-") + " §7crypts  §f"
                + (run.deaths() >= 0 ? run.deaths() : "-") + " §7deaths");
        int y = c[1] + 6;
        for (String line : lines) {
            MenuStyle.text(g, ScreenChrome.readable(this.font.plainSubstrByWidth(line, tw)), tx, y,
                    hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
            y += 11;
        }
    }

    /** The run page: the map large on the left, everything the run recorded beside it, scrolling. */
    private int detailMapCell() {
        int areaH = panelY + panelH - 8 - cardsTop() - 16;
        int byWidth = (int) (contentW * 0.4) / RunRecordGrid.GRID;
        return Math.max(6, Math.min(14, Math.min(byWidth, areaH / RunRecordGrid.GRID)));
    }

    /** The 11 of the run map's 11x11, without importing the record into every line above. */
    private static final class RunRecordGrid {
        static final int GRID = com.killer560.hub.runsummary.RunRecord.MapSnapshot.GRID;
    }

    private int detailTextX() {
        return contentX + 6 + RunRecordGrid.GRID * detailMapCell() + 12;
    }

    private int detailMaxScroll() {
        int lines = detailLines().size();
        return Math.max(0, lines * 10 + 8 - cardsHeight());
    }

    private void drawRunDetail(GuiGraphicsExtractor g) {
        int top = cardsTop();
        int h = cardsHeight();
        ScreenChrome.well(g, contentX - 1, top - 1, contentW + 2, h + 2);
        int cell = detailMapCell();
        com.killer560.hub.runsummary.RunMapPainter.draw(g, detail.map(), contentX + 6, top + 6, cell, 2);
        MenuStyle.text(g, detail.map().rooms().size() + " rooms", contentX + 6,
                top + 6 + RunRecordGrid.GRID * cell + 5, MenuStyle.DIM);
        int tx = detailTextX();
        int tw = contentX + contentW - 8 - tx;
        g.enableScissor(tx, top, contentX + contentW, top + h);
        try {
            int y = top + 6 - detailScroll;
            for (String line : detailLines()) {
                if (y > top + h) {
                    break;
                }
                if (!line.isEmpty() && y + 10 >= top) {
                    MenuStyle.text(g, ScreenChrome.readable(this.font.plainSubstrByWidth(line, tw)), tx, y,
                            MenuStyle.TXT);
                }
                y += 10;
            }
        } finally {
            g.disableScissor();
        }
        if (detailMaxScroll() > 0) {
            ScreenChrome.scrollbar(g, contentX + contentW - 5, top, h, detailScroll, detailMaxScroll(), h);
        }
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
