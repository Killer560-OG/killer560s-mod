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
        GENERATE("Create a New Map"),
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

    /** Generator settings. */
    private int puzzleCount = 3;
    /** Defaults are the middle of each range rather than an extreme, so the first generate is a usable floor. */
    private int roomsToBlood = 5;

    private SimFloorGen.Floor floor = SimFloorGen.Floor.F7;

    private List<String> listed = List.of();

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
        RoomLibrary.load();

        switch (mode) {
            case HOME -> buildHome();
            case PREVIOUS -> buildPrevious();
            case GENERATE -> buildGenerate();
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
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Create a New Map"), b -> {
            mode = Mode.GENERATE;
            rebuildWidgets();
        }).bounds(x, y + 30, w, 22).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load a Room"), b -> {
            mode = Mode.ROOM;
            scroll = 0;
            rebuildWidgets();
        }).bounds(x, y + 60, w, 22).build());
        // A workbench rather than a floor: every room in one line, for writing routes against rooms that rarely
        // come up otherwise.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("All Rooms (route practice)"), b -> {
            SimGenerator.generateAllRooms(this.minecraft);
            this.minecraft.setScreen(null);
        }).bounds(x, y + 90, w, 22).build());
    }

    private void buildPrevious() {
        listed = SimRunHistory.savedRuns();
        backButton();
    }

    /**
     * The generate panel: which floor, and the two sliders.
     *
     * <p>killer560 (2026-09-28): "have an option to choose what map size so for instance entrance, f1, f6, f7",
     * "Max rooms to blood should be 8 and it should be a sliding bar between 2-8 [...] Make puzzles a bar as
     * well from 2-5."
     *
     * <p>Real sliders rather than buttons that cycle a number. A cycling button made picking 3 out of 0-20 a
     * matter of clicking eight times and overshooting, which is exactly the interaction a slider exists to
     * replace - and the ranges are small and bounded, which is what sliders are good at.
     */
    private void buildGenerate() {
        int x = panelX + 20;
        int y = panelY + 58;
        int full = panelW - 40;

        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal("Floor: \u00a76" + floor.label + " \u00a77(" + floor.rooms + " rooms)"), b -> {
                    var all = SimFloorGen.Floor.values();
                    floor = all[(floor.ordinal() + 1) % all.length];
                    rebuildWidgets();
                }).bounds(x, y, full, 20).build());

        addRenderableWidget(new SimSlider(x, y + 26, full, "Rooms to blood",
                SimFloorGen.MIN_ROOMS_TO_BLOOD, SimFloorGen.MAX_ROOMS_TO_BLOOD, roomsToBlood,
                v -> roomsToBlood = v));

        addRenderableWidget(new SimSlider(x, y + 52, full, "Puzzles",
                SimFloorGen.MIN_PUZZLES, SimFloorGen.MAX_PUZZLES, puzzleCount,
                v -> puzzleCount = v));

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Pick specific rooms..."), b -> {
            mode = Mode.ROOM;
            scroll = 0;
            rebuildWidgets();
        }).bounds(x, y + 80, full, 20).build());

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("\u00a7aGenerate"), b -> {
            SimFloorGen.generate(this.minecraft, floor, puzzleCount, roomsToBlood);
            this.minecraft.setScreen(null);
        }).bounds(x, y + 108, full, 22).build());
        backButton();
    }

    private void buildRoomPicker() {
        int x = panelX + 20;
        int y = panelY + 56;
        search = new EditBox(this.font, x, y, panelW - 130, 18, Component.literal("Search"));
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
        }).bounds(x + panelW - 124, y, 50, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal(puzzlesOnly ? "§6Puzzles" : "§7Puzzles"), b -> {
                    puzzlesOnly = !puzzlesOnly;
                    refreshRoomList();
                    rebuildWidgets();
                }).bounds(x + panelW - 70, y, 50, 18).build());
        refreshRoomList();
        backButton();
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
            if (puzzlesOnly && !name.toLowerCase(Locale.ROOT).contains("puzzle")) {
                continue;
            }
            RoomLibrary.Room room = RoomLibrary.get(name);
            if (room != null && !shapeMatches(room)) {
                continue;
            }
            out.add(name);
        }
        listed = out;
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
                this.minecraft.setScreen(null);
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
        String count = RoomLibrary.completeCount() + " rooms captured";
        g.text(this.font, count, panelX + panelW - 10 - this.font.width(count), panelY + 11,
                ProfitPanels.DIM, false);

        if (mode == Mode.HOME) {
            String hint = RoomLibrary.roomCount() == 0
                    ? "No rooms captured yet - the Room Recorder fills these"
                    : "Pick what to practise";
            g.text(this.font, hint, panelX + 20, panelY + 42, ProfitPanels.DIM, false);
        } else if (mode == Mode.ROOM || mode == Mode.PREVIOUS) {
            int top = listTop();
            int h = listHeight();
            g.fill(panelX + 6, top, panelX + panelW - 6, top + h, ProfitPanels.INNER_BG);
            g.outline(panelX + 5, top - 1, panelW - 10, h + 2, ProfitPanels.BORDER);
            g.enableScissor(panelX + 6, top, panelX + panelW - 6, top + h);
            try {
                if (listed.isEmpty()) {
                    g.text(this.font, mode == Mode.ROOM ? "No rooms match" : "No saved runs yet",
                            panelX + 14, top + 6, ProfitPanels.DIM, false);
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
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
