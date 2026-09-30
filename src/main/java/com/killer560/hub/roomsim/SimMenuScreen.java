package com.killer560.hub.roomsim;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;

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
 * <p>Three modes because they answer three different practice questions: run this map again, run something new,
 * or drill one room. The single-room mode is the one that gets used most while learning a room, so it is not
 * buried behind the map generator.
 */
public class SimMenuScreen extends Screen {

    private enum Mode {
        HOME("Dungeon Sim"),
        PREVIOUS("Load a Previous Run"),
        ROOM("Load a Room");

        final String title;

        Mode(String title) {
            this.title = title;
        }
    }

    /** Room shape filter, the "2x2 and 1x1 or whatnot" he asked for. */
    private enum ShapeFilter {
        ANY("Any shape"), ONE_BY_ONE("1x1"), TWO_BY_TWO("2x2"), LARGE("Bigger");

        final String label;

        ShapeFilter(String label) {
            this.label = label;
        }
    }

    private final Screen parent;
    private Mode mode = Mode.HOME;

    private EditBox search;
    private ShapeFilter shape = ShapeFilter.ANY;
    private boolean puzzlesOnly;
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

    public SimMenuScreen(Screen parent) {
        super(Component.literal("Dungeon Sim"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 20, Math.max(340, Math.min((int) (this.width * 0.7), 560)));
        panelH = Math.min(this.height - 20, Math.max(220, Math.min((int) (this.height * 0.8), 440)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        RoomLibrary.loadAsync();
        // See SimMapEditorScreen.init - the sim runs from the main menu, where nothing else starts this.
        com.killer560.hub.roomdatabase.RoomDatabase.ensureLoading();

        switch (mode) {
            case HOME -> buildHome();
            case PREVIOUS -> buildPrevious();
            case ROOM -> buildRoomPicker();
        }
    }

    private void buildHome() {
        int w = panelW - 40;
        int x = panelX + 20;
        int y = panelY + 60;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load a Previous Run"), b -> {
            mode = Mode.PREVIOUS;
            scroll = 0;
            rebuildWidgets();
        }).bounds(x, y, w, 22).build());
        // killer560 (2026-09-29): "this screen needs to be the design the map screen. That should be the only
        // one once you click create a new map." It goes straight there; the settings page it used to open in
        // between is gone, and its Floor / Rooms to blood / Puzzles controls and its Generate moved onto the
        // designer, so nothing it could do was lost.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Create a New Map"), b ->
                McCompat.setScreen(this.minecraft, new SimMapEditorScreen(this)))
                .bounds(x, y + 30, w, 22).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load a Room"), b -> {
            mode = Mode.ROOM;
            scroll = 0;
            rebuildWidgets();
        }).bounds(x, y + 60, w, 22).build());
        // A workbench rather than a floor: every room in one line, for writing routes against rooms that rarely
        // come up otherwise.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("All Rooms (route practice)"), b -> {
            SimGenerator.generateAllRooms(this.minecraft);
            McCompat.setScreen(this.minecraft, null);
        }).bounds(x, y + 90, w, 22).build());
    }

    private void buildPrevious() {
        listed = SimRunHistory.savedRuns();
        backButton();
    }

    private void buildRoomPicker() {
        int x = panelX + 20;
        int y = panelY + 56;
        // The row is sized from the TEXT, not from fixed pixels. "Any shape" is 51 px wide in Minecraft's font
        // and its button was 50, so the label ran out of the box (killer560, 2026-09-30: "the text box to the
        // left of it is too small and the text goes outside of it"); the Puzzles button beside it was also
        // placed to end exactly on the panel border. The buttons are measured against the WIDEST label they
        // can ever show - the shape button cycles, and a button that resized as he clicked would move under
        // the cursor - and the search box takes whatever is left inside the panel's 20 px margins.
        int gap = 4;
        int shapeW = widestShapeLabel() + BUTTON_PAD;
        int puzzleW = this.font.width("Puzzles") + BUTTON_PAD;
        int right = panelX + panelW - 20;
        search = new EditBox(this.font, x, y, right - x - shapeW - puzzleW - gap * 2, 18,
                Component.literal("Search"));
        search.setHint(Component.literal("Search rooms..."));
        search.setResponder(v -> {
            scroll = 0;
            refreshRoomList();
        });
        addRenderableWidget(search);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(shape.label), b -> {
            shape = ShapeFilter.values()[(shape.ordinal() + 1) % ShapeFilter.values().length];
            refreshRoomList();
            rebuildWidgets();
        }).bounds(right - shapeW - gap - puzzleW, y, shapeW, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal(puzzlesOnly ? "§6Puzzles" : "§7Puzzles"), b -> {
                    puzzlesOnly = !puzzlesOnly;
                    refreshRoomList();
                    rebuildWidgets();
                }).bounds(right - puzzleW, y, puzzleW, 18).build());
        refreshRoomList();
        backButton();
    }

    /** Horizontal space a button's label needs on top of its text: a border and a little air each side. */
    private static final int BUTTON_PAD = 14;

    private int widestShapeLabel() {
        int widest = 0;
        for (ShapeFilter f : ShapeFilter.values()) {
            widest = Math.max(widest, this.font.width(f.label));
        }
        return widest;
    }

    private void backButton() {
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> {
            mode = Mode.HOME;
            scroll = 0;
            rebuildWidgets();
        }).bounds(panelX + 6, panelY + panelH - 26, 70, 20).build());
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
            // The room database's own TYPE, not a look at the name. This was
            // name.contains("puzzle"), and no room is called that - Boulder, Quiz and Ice Fill are named for
            // what they are, so the filter matched nothing at all (killer560, 2026-09-30: "no puzzle is under
            // the puzzle rooms tab"). rooms-modern.json holds 11 PUZZLE entries and every type is upper case.
            if (puzzlesOnly && !"PUZZLE".equalsIgnoreCase(SimFloorGen.typeOf(name))) {
                continue;
            }
            RoomLibrary.Room room = RoomLibrary.get(name);
            if (room != null && !shapeMatches(room)) {
                continue;
            }
            out.add(name);
        }
        listed = out;
        listedWithDatabase = com.killer560.hub.roomdatabase.RoomDatabase.isReady();
    }

    private boolean shapeMatches(RoomLibrary.Room room) {
        int tilesX = Math.max(1, room.sizeX / RoomLibrary.TILE);
        int tilesZ = Math.max(1, room.sizeZ / RoomLibrary.TILE);
        return switch (shape) {
            case ANY -> true;
            case ONE_BY_ONE -> tilesX == 1 && tilesZ == 1;
            case TWO_BY_TWO -> tilesX == 2 && tilesZ == 2;
            case LARGE -> tilesX > 2 || tilesZ > 2;
        };
    }

    private int listTop() {
        return mode == Mode.ROOM ? panelY + 80 : panelY + 56;
    }

    private int listHeight() {
        return panelY + panelH - 32 - listTop();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, listed.size() * 14 - listHeight());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(scrollY * 28)));
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
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
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        g.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        g.text(this.font, mode.title.toUpperCase(Locale.ROOT), panelX + 10, panelY + 11,
                ProfitPanels.ACCENT, false);
        if (mode == Mode.HOME) {
            String hint = RoomLibrary.roomCount() == 0
                    ? "No rooms captured yet - the Room Recorder fills these"
                    : "Pick what to practise";
            g.text(this.font, hint, panelX + 20, panelY + 42, ProfitPanels.DIM, false);
        } else if (mode == Mode.ROOM || mode == Mode.PREVIOUS) {
            if (mode == Mode.ROOM && puzzlesOnly
                    && com.killer560.hub.roomdatabase.RoomDatabase.isReady() != listedWithDatabase) {
                refreshRoomList();
            }
            int top = listTop();
            int h = listHeight();
            g.fill(panelX + 6, top, panelX + panelW - 6, top + h, ProfitPanels.INNER_BG);
            g.outline(panelX + 5, top - 1, panelW - 10, h + 2, ProfitPanels.BORDER);
            g.enableScissor(panelX + 6, top, panelX + panelW - 6, top + h);
            try {
                if (listed.isEmpty()) {
                    String none = mode != Mode.ROOM ? "No saved runs yet"
                            : puzzlesOnly && !listedWithDatabase ? "Loading the room database..."
                            : "No rooms match";
                    g.text(this.font, none, panelX + 14, top + 6, ProfitPanels.DIM, false);
                }
                for (int i = 0; i < listed.size(); i++) {
                    int rowY = top + i * 14 - scroll + 3;
                    if (rowY > top + h) {
                        break;
                    }
                    g.text(this.font, this.font.plainSubstrByWidth(listed.get(i), panelW - 28),
                            panelX + 14, rowY, ProfitPanels.TEXT, false);
                }
            } finally {
                g.disableScissor();
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
