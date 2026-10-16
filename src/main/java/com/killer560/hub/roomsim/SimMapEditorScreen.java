package com.killer560.hub.roomsim;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.killer560.hub.compat.McCompat;

/**
 * Draw a floor room by room, then play it.
 *
 * <p>killer560 (2026-09-29), of Ashfall's Dungeon Maker: "make this page that map selection instead of the
 * pick specific rooms thing." The old picker dropped him into ONE room; this lays out a whole floor - pick a
 * room from the list on the right, click a cell on the left, press Play.
 *
 * <p>Nothing here builds anything itself. It produces a placement per cell and hands that to
 * {@link SimFloorGen#buildExplicit}, which is the same code path the random generator ends in, so a floor he
 * drew and a floor the mod invented are built identically and can only differ in which rooms went where.
 *
 * <p>Only rooms that are {@link RoomLibrary.Room#usable()} are listed. Offering a room captured at the old
 * footprint would let him place something that pastes over its neighbour, and a map editor that silently
 * corrupts the floor is worse than one with a shorter list.
 */
public class SimMapEditorScreen extends Screen implements MenuThemedScreen {

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

    /** Room slots per side. The 11x11 cell grid holds rooms on even cells, so six of them. */
    private static final int GRID = (com.killer560.hub.livemap.DungeonLayout.GRID + 1) / 2;

    /**
     * A list row: two lines with a gap between them and around them. It was 22 with the lines 9 apart, i.e. touching
     * (killer560, 2026-10-15: "make there be a hair more spacing for the text it is really crammed"). 3 units between
     * the lines (27/15 gave 2), so the gap still shows at a small Auto Scale where 2 units can round to no pixel at all.
     */
    private static final int ROW_H = 28;
    /** The second line's offset in a row; the first is at {@link #ROW_TEXT1}. */
    private static final int ROW_TEXT1 = 4;
    private static final int ROW_TEXT2 = 16;

    /** The label lines of the last frame, "Name|0/6;Name|0/4;", for the gametest to read back. */
    private static volatile String labelsDrawn = "";

    /** What the last drawn frame wrote on the grid's rooms; each room's lines are joined by "|", rooms by ";". */
    public static String labelsDrawn() {
        return labelsDrawn;
    }

    /** What the menu closes back to (the title screen, the game). Re-pointed when the session's designer is reopened. */
    private Screen parent;

    /** Anchor cell -> room name. A multi-tile room appears once, at its top-left. */
    private final Map<Integer, String> placements = new LinkedHashMap<>();

    /** Cell -> the anchor occupying it, rebuilt whenever placements change. */
    private final Map<Integer, Integer> occupiedBy = new LinkedHashMap<>();

    /**
     * The rooms HE placed, as opposed to the ones a Generate drew.
     *
     * <p>Kept apart so Clear can take a generated floor off without throwing away his own work, and so a
     * Generate can be asked to build around what he has pinned rather than over it.
     */
    private final Map<Integer, String> pinned = new LinkedHashMap<>();

    /**
     * The rotation each placed room is at, in degrees, keyed by its anchor cell.
     *
     * <p>Without this the grid drew every room at rotation 0, so a 1x4 turned a quarter turn was drawn 1 wide
     * and 4 deep while it really covers 4 by 1 - which is what made a generated floor look like it had holes
     * in it and gave "Flags" a shape no Catacombs room has (killer560, 2026-09-29: "It still is not
     * generating the full map nor is that flags shape normal"). The floor was right; the picture of it was
     * not. Hand-placed rooms are 0, which is what {@code SimFloorGen.planExplicit} builds them at.
     */
    private final Map<Integer, Integer> rotations = new LinkedHashMap<>();

    /** What the right-hand panel is listing. */
    private enum Panel { ROOMS, MAPS }

    private final List<String> listed = new ArrayList<>();
    private String selected;
    /** What Save will call this design. Survives a widget rebuild. */
    private Panel panel = Panel.ROOMS;
    /** The Load list: floors he played from here ({@link SimMapHistory}), newest first, as {@link #listed} shows them. */
    private final List<SimMapHistory.Entry> history = new ArrayList<>();
    private EditBox search;
    /** The search box's text, kept here because a rebuild (and coming back from the filters) makes a new box. */
    private String searchText = "";
    private int scroll;
    private String status = "";
    /** Usable rooms in the library (no longer drawn - the header count went, 2026-10-15; the testkit reads it). */
    private int roomTotal;
    /** Whether the room database had loaded when {@link #listed} was built; the type filters need it. */
    private boolean listedWithDatabase;

    // The random generator's settings, which used to live on a separate "Create a New Map" page.
    //
    // killer560 (2026-09-29): "this screen needs to be the design the map screen. That should be the only one
    // once you click create a new map." So the settings came here rather than being dropped - Generate below
    // still calls the real floor generator, NOT a drawing of it. Those are different things and must stay
    // different: a generated floor carries a rotation per room, worked out by matching doorways, while a drawn
    // one is placed at rotation 0 throughout (SimFloorGen.planExplicit). Filling the drawing grid from the
    // generator would throw those rotations away, and the room database's secret coordinates are translated
    // through exactly that rotation.
    /**
     * The floor Generate laid out, shown on the grid and waiting for Play.
     *
     * <p>killer560 (2026-09-29): "Before I can press play I should have to press generate and it shows on the
     * left map what map it is going to generate." Generate used to build and close the screen immediately.
     *
     * <p>Kept whole rather than re-derived from the grid, because the grid cannot hold everything a plan has:
     * a generated floor carries a rotation per room worked out from doorway matching, and
     * {@code SimFloorGen.planExplicit} places a drawn floor at rotation 0 throughout. Playing the drawing
     * instead of the plan would throw those rotations away - and the room database's secret coordinates are
     * translated through exactly that rotation. So the grid shows the plan, and Play builds the PLAN.
     */
    private SimFloorGen.Planned generated;

    /**
     * The doors a DRAWN grid will really get, re-planned quietly whenever the drawing changes.
     *
     * <p>killer560 (2026-10-04): "have some distinction on the map as to what are actual doorways and wither
     * doors and whatnot instead of just showing all the doors." The grid used to put a nub in every gap between
     * two touching rooms, but a floor is a tree - most of those gaps are bricked up - so the picture promised
     * doors the build never cuts. This is the build's own plan of the drawing, so the grid shows exactly its
     * doors. Null while a generated plan is on the grid; that plan already carries its doors.
     */
    private MapCode.Decoded drawnPlan;

    /** The plan {@link #witherDoors} was worked out for, and the theoretical wither doors on it. */
    private MapCode.Decoded witherFor;
    private boolean[] witherDoors = new boolean[0];

    private SimFloorGen.Floor floor = SimFloorGen.Floor.F7;
    private int roomsToBlood = 5;
    private int puzzleCount = 3;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int cell;
    private int gridX;
    private int gridY;
    /** The square the grid is drawn in: an F7's whole 6x6, and a smaller floor centred in it at a bigger cell. */
    private int areaX;
    private int areaY;
    private int areaSize;
    private int listX;
    private int listW;

    public SimMapEditorScreen(Screen parent) {
        super(Component.literal("Design a map"));
        this.parent = parent;
    }

    void setParent(Screen parent) {
        this.parent = parent;
    }

    /** The shared frame and left column (SimMenuNav); this screen is the menu's Create a New Map page. */
    private SimMenuNav nav;

    @Override
    protected void init() {
        RoomLibrary.loadAsync();
        // The room DATABASE too, not just the captures.
        //
        // Everything that knows a room is a puzzle, a trap, blood or fairy goes through
        // SimFloorGen.typeOf -> RoomDatabase.lookupByName, and the only thing that ever started that load was
        // LiveMapFeature, which runs when he is in a real dungeon on Hypixel. The sim opens from the MAIN
        // MENU, so the database was never loaded and every room came back NORMAL: one colour for the whole
        // grid, "normal" under every name in the list, and - worse than cosmetic - a generator that could not
        // tell a puzzle from an ordinary room while being asked for three of them, and could not see which
        // rooms are L-shaped to exclude them (SimFloorGen.shapeOf reads the same entry).
        RoomDatabase.ensureLoading();
        // The Dungeon Sim menu's frame and left column (killer560, 2026-10-15): this is the menu's first page, and
        // Previous Runs, Load a Room and Route Practice stay one click away in the column.
        nav = new SimMenuNav(this.width, this.height);
        SimMenuNav.shown(SimMenuNav.Page.DESIGNER);
        panelX = nav.panelX;
        panelY = nav.panelY;
        panelW = nav.panelW;
        panelH = nav.panelH;
        nav.addItems(this::addRenderableWidget, SimMenuNav.Page.DESIGNER, parent, null);
        int cx = nav.contentX;
        int cw = nav.contentW;

        // The grid is square and takes the left half of the page; the list takes the rest. Its height stops above the
        // status line, which stops above the two button rows.
        int top = listTop();
        int gridArea = Math.min(rowAY() - 8 - 12 - top, (cw - 14) / 2);
        // Floor of 8, not 14. At 14 the grid could not shrink far enough to clear the settings row on a
        // short window - scenario 83 measured the grid's bottom at y 170 against a button top of y 168 at a
        // 240-unit window height. A cramped grid is better than one drawn over the controls.
        areaSize = Math.max(8, gridArea / GRID) * GRID;
        areaX = cx;
        areaY = top;
        // Only the floor's own rectangle is drawn (killer560, 2026-10-15: "Changing this button did not change the
        // size of the map like it should"), scaled up to the square the 6x6 takes, the way the dungeon map fits a
        // small floor to its frame.
        cell = Math.max(8, areaSize / Math.max(floor.across, floor.down));
        gridX = areaX + (areaSize - cell * floor.across) / 2;
        gridY = areaY + (areaSize - cell * floor.down) / 2;
        listX = areaX + areaSize + 14;
        listW = cx + cw - listX;

        // The room filters (killer560, 2026-10-06: "have a filter section where i can filter based on things like
        // puzzles, room size, secrets in a room, etc."), beside the search box. They open as a popup screen rather
        // than a panel here, because this screen has no free space at a small window. They narrow the list
        // and Generate - see SimRoomFilters. Lit while any chip is chosen, counting them.
        int chosen = SimRoomFilters.DESIGNER.selectedCount();
        int filtersW = this.font.width("Filters (99)") + 12;
        search = new ThemedEditBox(this.font, listX, nav.contentY, Math.max(20, listW - filtersW - 4), 18,
                Component.literal("Search"));
        ((ThemedEditBox) search).hint("Search rooms...");
        search.setValue(searchText);
        search.setResponder(v -> {
            searchText = v;
            scroll = 0;
            refilter();
        });
        addRenderableWidget(search);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(
                chosen == 0 ? "Filters" : "Filters (" + chosen + ")"), b -> {
                    panel = Panel.ROOMS;
                    McCompat.setScreen(this.minecraft, new SimRoomFilterScreen(this));
                }).bounds(listX + listW - filtersW, nav.contentY, filtersW, 18).toggle(chosen > 0).build());


        // Row A - the random floor: its settings and the button that builds one.
        int ay = rowAY();
        int aAvail = cw - 18;
        int wFloor = aAvail * 24 / 100;
        int wSlider = aAvail * 27 / 100;
        int wGen = aAvail - wFloor - wSlider * 2;
        int ax = cx;
        addRenderableWidget(SettingsButtonWidget.cycling(
                Component.literal("§6" + floor.label), (b, dir) -> {
                    floor = SettingsButtonWidget.step(floor, dir);
                    floorChanged();
                    rebuildWidgets();
                }).bounds(ax, ay, wFloor, 20).build());
        ax += wFloor + 6;
        // Both sliders stop at what this floor can hold (SimFloorGen.Floor.maxRoomsToBlood / maxPuzzles), and Puzzles
        // follows Rooms to blood as it moves: a long path and many puzzles compete for a small floor's few cells, and a
        // floor that silently came out a puzzle short was the alternative (coordinator, 2026-10-15).
        clampSliders();
        SimSlider[] puzzlesRef = new SimSlider[1];
        addRenderableWidget(new SimSlider(ax, ay, wSlider, "Rooms to blood",
                SimFloorGen.MIN_ROOMS_TO_BLOOD, floor.maxRoomsToBlood(), roomsToBlood,
                v -> {
                    roomsToBlood = v;
                    if (puzzlesRef[0] != null) {
                        puzzlesRef[0].setMax(puzzlesTop());
                    }
                }));
        ax += wSlider + 6;
        puzzlesRef[0] = addRenderableWidget(new SimSlider(ax, ay, wSlider, "Puzzles",
                SimFloorGen.MIN_PUZZLES, puzzlesTop(), puzzleCount,
                v -> puzzleCount = v));
        ax += wSlider + 6;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aGenerate"), b -> preview())
                .bounds(ax, ay, wGen, 20).build());

        // Row B - the drawn floor: the drawing tools, and Play for what is on the grid.
        int by = rowAY() + 24;
        // Save went (killer560, 2026-10-15: "Remove the save and load buttons in exchange for one load button"): Play
        // records the floor in the Load list itself, so there is nothing to name or save by hand. Back went too: the
        // left column's Back closes the menu, and its entries are the way to the other pages.
        int count = 3;
        int bw = (cw - 6 * (count - 1)) / count;
        int bx = cx;

        // Clear takes the GENERATION off first, and only wipes the drawing if there is no generation to take
        // off. killer560 (2026-09-29): "also had a button to clear the current generation". Two steps rather
        // than two buttons: after a Generate the thing on screen is the generated floor, so that is what Clear
        // should remove, and pressing it again clears whatever he had drawn himself.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Clear"), b -> {
            if (generated != null) {
                planStale();
                placements.clear();
                clearRotations();
                placements.putAll(pinned);
                rebuildOccupancy();
                status = pinned.isEmpty() ? "generation cleared"
                        : "generation cleared - your " + pinned.size() + " room(s) kept";
            } else {
                placements.clear();
                pinned.clear();
                clearRotations();
                rebuildOccupancy();
                status = "cleared";
            }
        }).bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        // Fill went too (killer560, 2026-10-15: "Remove the fill button, it no longer has a use"), so the row is three.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load"), b -> {
            panel = panel == Panel.MAPS ? Panel.ROOMS : Panel.MAPS;
            scroll = 0;
            refilter();
            status = panel == Panel.MAPS ? "pick a floor you played" : "";
        }).bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        // Play takes the division's remainder, so its right edge meets Generate's above it exactly.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aPlay"), b -> play())
                .bounds(bx, by, cx + cw - bx, 20).primary().build());

        refilter();
        rebuildOccupancy();
    }

    /** The room list, filtered by the search box and sorted so puzzles and specials are easy to find. */
    private void refilter() {
        listed.clear();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (panel == Panel.MAPS) {
            history.clear();
            for (SimMapHistory.Entry e : SimMapHistory.entries()) {
                String title = historyTitle(e);
                if (q.isEmpty() || title.toLowerCase(Locale.ROOT).contains(q)
                        || e.dateText().contains(q)) {
                    history.add(e);
                    listed.add(title);
                }
            }
            return;
        }
        listedWithDatabase = RoomDatabase.isReady();
        roomTotal = 0;
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            if (r == null || !r.usable()) {
                continue;
            }
            roomTotal++;
            if (!SimRoomFilters.listMatches(name)) {
                continue;
            }
            if (!q.isEmpty()) {
                String type = SimFloorGen.typeOf(name).toLowerCase(Locale.ROOT);
                if (!name.toLowerCase(Locale.ROOT).contains(q) && !type.contains(q)) {
                    continue;
                }
            }
            listed.add(name);
        }
        listed.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private void rebuildOccupancy() {
        drawnPlan = null;
        if (generated == null && !placements.isEmpty()) {
            SimFloorGen.Planned quiet = SimFloorGen.planExplicit(placements, true);
            drawnPlan = quiet == null ? null : quiet.decoded();
        }
        occupiedBy.clear();
        for (Map.Entry<Integer, String> e : placements.entrySet()) {
            int[] size = footprintOf(e.getValue(), rotations.getOrDefault(e.getKey(), 0));
            int ax = e.getKey() % GRID;
            int az = e.getKey() / GRID;
            for (int dx = 0; dx < size[0]; dx++) {
                for (int dz = 0; dz < size[1]; dz++) {
                    int x = ax + dx;
                    int z = az + dz;
                    if (x < GRID && z < GRID) {
                        occupiedBy.put(z * GRID + x, e.getKey());
                    }
                }
            }
        }
    }

    /** Footprint in ROOM CELLS, read from the capture - the same measure the builder plans against. */
    private static int[] footprintOf(String name, int rotation) {
        RoomLibrary.Room r = RoomLibrary.get(name);
        if (r == null) {
            return new int[]{1, 1};
        }
        // A quarter turn swaps the axes, exactly as RoomPlacer.rotateLocal does when it pastes.
        return rotation == 90 || rotation == 270
                ? new int[]{tiles(r.sizeZ), tiles(r.sizeX)}
                : new int[]{tiles(r.sizeX), tiles(r.sizeZ)};
    }

    /** Same inverse of {@link RoomLibrary#footprint} the builder uses - they must not disagree. */
    private static int tiles(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    /** Colour per room type, so a floor reads at a glance the way the live map does. */
    private static int colourFor(String name) {
        return switch (SimFloorGen.typeOf(name)) {
            case "ENTRANCE" -> 0xFF1F8B3A;
            case "BLOOD" -> 0xFFB23030;
            case "PUZZLE" -> 0xFF7B2D8E;
            case "FAIRY" -> 0xFFD05A9E;
            case "TRAP" -> 0xFFD08A16;
            case "CHAMPION" -> 0xFFB58A2B;
            case "RARE" -> 0xFF2E5FA3;
            default -> 0xFF6B4A2F;
        };
    }

    private boolean canPlace(String name, int slot) {
        int[] size = footprintOf(name, 0);
        int ax = slot % GRID;
        int az = slot / GRID;
        if (ax + size[0] > floor.across || az + size[1] > floor.down) {
            return false;
        }
        for (int dx = 0; dx < size[0]; dx++) {
            for (int dz = 0; dz < size[1]; dz++) {
                if (occupiedBy.containsKey((az + dz) * GRID + ax + dx)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** "Floor 1 · Generated" / "· Drawn" / "My Dungeon · Saved design" - a Load row's first line, what search matches. */
    private static String historyTitle(SimMapHistory.Entry e) {
        return e.title();
    }

    /** Puts a floor from the Load list back on the grid, at its own floor size, ready for Play. */
    private void loadEntry(SimMapHistory.Entry e) {
        planStale();
        clearRotations();
        pinned.clear();
        placements.clear();
        int lost = 0;
        for (SimFloorGen.Floor f : SimFloorGen.Floor.values()) {
            if (f.code.equals(e.floorCode())) {
                floor = f;
            }
        }
        if (e.generated()) {
            MapCode.Decoded decoded;
            try {
                decoded = MapCode.decode(e.code());
            } catch (RuntimeException ex) {
                decoded = null;
            }
            if (decoded == null) {
                status = "could not read that floor";
                return;
            }
            showPlan(new SimFloorGen.Planned(decoded, e.code(), e.puzzles(), -1, 0, List.of(), List.of(), List.of(),
                    null));
        } else {
            int dropped = 0;
            for (Map.Entry<Integer, String> c : e.cells().entrySet()) {
                RoomLibrary.Room r = RoomLibrary.get(c.getValue());
                if (r == null || !r.usable()) {
                    dropped++;
                    continue;
                }
                placements.put(c.getKey(), c.getValue());
                pinned.put(c.getKey(), c.getValue());
            }
            rebuildOccupancy();
            lost = dropped;
        }
        panel = Panel.ROOMS;
        scroll = 0;
        status = lost > 0 ? "loaded - " + lost + " room(s) are no longer captured"
                : "loaded " + historyTitle(e) + " from " + e.dateText();
        rebuildWidgets();   // the grid's size follows the floor it was played on
    }

    /** A slider value, or a fresh draw from {@code min..max} when that value is {@link SimSlider#RANDOM}. */
    private static int roll(int value, int min, int max) {
        return value == SimSlider.RANDOM
                ? min + java.util.concurrent.ThreadLocalRandom.current().nextInt(max - min + 1)
                : value;
    }

    /**
     * Forget the generated plan, because the grid no longer shows it.
     *
     * <p>Play builds the PLAN when there is one, so a plan left standing after he edits, clears or
     * loads over it would build something other than what he is looking at - the exact failure the preview
     * exists to prevent.
     */
    private void planStale() {
        generated = null;
    }

    /** The Puzzles slider's top: what this floor holds at the Rooms to blood set, or at its longest when that is Random. */
    private int puzzlesTop() {
        return floor.maxPuzzles(roomsToBlood == SimSlider.RANDOM ? floor.maxRoomsToBlood() : roomsToBlood);
    }

    /** Brings both slider values within this floor's limits (Random stays Random). */
    private void clampSliders() {
        if (roomsToBlood != SimSlider.RANDOM) {
            roomsToBlood = Math.min(roomsToBlood, floor.maxRoomsToBlood());
        }
        if (puzzleCount != SimSlider.RANDOM) {
            puzzleCount = Math.min(puzzleCount, puzzlesTop());
        }
    }

    /**
     * The floor button moved: the grid becomes that floor's rectangle, and a generated floor is laid out again at once.
     *
     * <p>killer560 (2026-10-15): "if the map is already generated and I press this button then it needs to regenerate
     * automatically." His own rooms that no longer fit the smaller rectangle are taken off and counted, rather than
     * left standing outside the grid where Play would still build them.
     */
    private void floorChanged() {
        clampSliders();
        int before = pinned.size();
        pinned.keySet().removeIf(key -> {
            int[] fp = footprintOf(pinned.get(key), 0);
            return key % GRID + fp[0] > floor.across || key / GRID + fp[1] > floor.down;
        });
        int dropped = before - pinned.size();
        for (Integer key : new ArrayList<>(placements.keySet())) {
            int[] fp = footprintOf(placements.get(key), rotations.getOrDefault(key, 0));
            if (key % GRID + fp[0] > floor.across || key / GRID + fp[1] > floor.down) {
                placements.remove(key);
                rotations.remove(key);
            }
        }
        if (generated != null) {
            preview();
            if (dropped > 0) {
                status = "took off " + dropped + " room(s) outside " + floor.label + " · " + status;
            }
            return;
        }
        rebuildOccupancy();
        status = floor.label + ": " + floor.across + "x" + floor.down
                + (dropped > 0 ? " · took off " + dropped + " room(s) outside it" : "");
    }

    /** Forget the rotations too - they belong to the plan that is being dropped. */
    private void clearRotations() {
        rotations.clear();
    }

    /**
     * Lays out a floor and puts it on the grid. Nothing is built and no world is opened.
     */
    private void preview() {
        // A slider parked at the far right means "pick for me", and the pick is drawn INSIDE the slider's own
        // range, so random can never produce a floor the slider could not have been set to by hand.
        clampSliders();
        int blood = roll(roomsToBlood, SimFloorGen.MIN_ROOMS_TO_BLOOD, floor.maxRoomsToBlood());
        int puzzles = roll(puzzleCount, SimFloorGen.MIN_PUZZLES, floor.maxPuzzles(blood));
        // The rooms HE placed go in as PINS, so Generate builds around them instead of over them.
        // killer560 (2026-09-29): "test stuff like putting in a single room that I want personally in
        // generating a map around the room."
        // The room filters narrow the pool. SimFloorGen keeps Entrance/Blood/Fairy/Trap and his pins whatever they
        // say, and lays the floor out from every room (saying so) if the filtered rooms cannot make a whole one.
        SimFloorGen.Planned planned = SimFloorGen.plan(floor, puzzles, blood, pinned,
                SimRoomFilters.isDefault() ? null : SimRoomFilters::generatorAllows);
        if (planned == null) {
            status = "could not lay out that floor";   // plan() has already said why, in chat
            return;
        }
        showPlan(planned);
        // Pins that could not be used are named, not swallowed - he put them there on purpose.
        String pins = "";
        if (!planned.unusedPins().isEmpty()) {
            pins = " · dropped " + planned.unusedPins().size() + " pin(s)";
        } else if (!planned.keptPins().isEmpty()) {
            pins = " · kept " + planned.keptPins().size();
        }
        // Short. It sits under the grid, and the long form ran on into the room list.
        // The puzzles actually placed, not the slider's ask - the filters can allow fewer.
        status = planned.decoded().nameTable().length + " rooms · " + planned.placedPuzzles() + " puzzles · blood "
                + planned.bloodDistance() + pins;
        // A filter note goes FIRST: the status line is trimmed to the space under the grid, and "filters too
        // strict" is the part he must not miss (it is also in chat in full).
        String note = planned.filterNote();
        // The sliders stop where the floor is measured to hold what they ask, but a rare floor can still miss: say so
        // first rather than let it pass as asked (coordinator, 2026-10-15: "so nothing silently under-delivers").
        if (note == null && (planned.placedPuzzles() < puzzles || planned.bloodDistance() != blood)) {
            status = "short of the sliders (" + puzzles + " puzzles, " + blood + " to blood) · " + status;
        }
        if (note != null && !"filtered".equals(note)) {
            status = note + " · " + status;
        } else if (note != null) {
            status = "filtered · " + status;
        }
    }

    /** Puts a laid-out floor on the grid as the plan Play builds - a fresh Generate, or one from the Load list. */
    private void showPlan(SimFloorGen.Planned planned) {
        generated = planned;
        placements.clear();   // `pinned` is deliberately NOT cleared: Clear restores it
        rotations.clear();
        var decoded = planned.decoded();
        java.util.Set<Integer> anchored = new java.util.HashSet<>();
        // Room ids are per PLACEMENT, never per name, so the first cell carrying an id in reading order is
        // that placement's top-left - which is exactly what the grid wants.
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                int cell = (gz * 2) * com.killer560.hub.livemap.DungeonLayout.GRID + (gx * 2);
                int id = decoded.cellRoom()[cell];
                if (id == MapCode.NO_ROOM || id < 0 || id >= decoded.nameTable().length
                        || !anchored.add(id)) {
                    continue;
                }
                placements.put(gz * GRID + gx, decoded.nameTable()[id]);
                rotations.put(gz * GRID + gx, decoded.cellRotation()[cell]);
            }
        }
        rebuildOccupancy();
    }

    private void play() {
        // A generated plan wins over the drawing, because it carries rotations the drawing cannot.
        if (generated != null) {
            String code = generated.code();
            // Which floor this is, as SimFloorGen.generate sets it - the mimic rule and the sidebar read it.
            SimState.setFloorLabel(floor.code);
            // Into the Load list, dated, before the world opens (killer560, 2026-10-15: "Any map generated and played
            // will show there with a date of when it was created").
            SimMapHistory.recordGenerated(floor.code, floor.label, code, generated.decoded().nameTable().length,
                    generated.placedPuzzles());
            SimWorld.open(this.minecraft, code, c -> SimBuilder.build(c, code), "Generating " + floor.label);
            return;
        }
        if (placements.isEmpty()) {
            status = "press Generate, or place a room first";
            return;
        }
        Map<Integer, String> drawing = new LinkedHashMap<>(placements);
        int built = SimFloorGen.buildExplicit(this.minecraft, placements);
        if (built > 0) {
            int puzzles = 0;
            for (String name : drawing.values()) {
                puzzles += "PUZZLE".equals(SimFloorGen.typeOf(name)) ? 1 : 0;
            }
            SimMapHistory.recordDrawn(floor.code, floor.label, drawing, puzzles);
            McCompat.setScreen(this.minecraft, null);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        // The grid.
        if (mx >= gridX && mx < gridX + cell * floor.across && my >= gridY && my < gridY + cell * floor.down) {
            int gx = (int) ((mx - gridX) / cell);
            int gz = (int) ((my - gridY) / cell);
            int slot = gz * GRID + gx;
            if (event.button() == 1) {
                Integer anchor = occupiedBy.get(slot);
                if (anchor != null) {
                    planStale();
                    rotations.remove(anchor);
                    pinned.remove(anchor);
                    status = "removed " + placements.remove(anchor);
                    rebuildOccupancy();
                }
                return true;
            }
            if (selected == null) {
                status = "pick a room from the list first";
                return true;
            }
            Integer anchor = occupiedBy.get(slot);
            if (anchor != null) {
                planStale();
                rotations.remove(anchor);
                pinned.remove(anchor);
                placements.remove(anchor);
                rebuildOccupancy();
            }
            if (canPlace(selected, slot)) {
                planStale();
                rotations.remove(slot);   // placed by hand, so rotation 0
                placements.put(slot, selected);
                pinned.put(slot, selected);
                rebuildOccupancy();
                status = "placed " + selected;
            } else {
                int[] s = footprintOf(selected, 0);
                status = selected + " needs " + s[0] + "x" + s[1] + " free cells here";
            }
            return true;
        }

        // The list.
        int top = listTop();
        int bottom = listBottom();
        if (mx >= listX && mx < listX + listW && my >= top && my < bottom) {
            int row = (int) ((my - top + scroll) / ROW_H);
            if (row >= 0 && row < listed.size()) {
                if (panel == Panel.MAPS) {
                    if (event.button() == 1) {
                        SimMapHistory.remove(history.get(row));
                        refilter();
                        status = "deleted that floor from the list";
                    } else {
                        loadEntry(history.get(row));
                    }
                } else {
                    selected = listed.get(row);
                    status = "selected " + selected;
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (mouseX >= listX) {
            int visible = listBottom() - listTop();
            int max = Math.max(0, listed.size() * ROW_H - visible);
            scroll = Math.max(0, Math.min(max, scroll - (int) (dy * ROW_H)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The menu theme (screen sweep, 2026-10-13): the menu's panel and header bar; the grid and the room list are
        // recessed wells, list rows hover / select like the menu's, every colour from the active theme.
        themedBounds = new int[]{panelX, panelY, panelW, panelH};
        nav.drawFrame(g, this.width, this.height);
        // The type filters read the room database; a list built before it loaded is rebuilt the moment it does.
        if (!listedWithDatabase && panel == Panel.ROOMS && RoomDatabase.isReady()) {
            refilter();
        }
        // No "N of M rooms" in the header bar any more (killer560, 2026-10-15: "Remove the 134 of 134 rooms in the top
        // right"). The Filters button still says how many filters are on.
        // Where the map name box was (Save went, 2026-10-15): which floor the grid is, and its size.
        ScreenChrome.text(g, fit(floor.label + "  ·  " + floor.across + " x " + floor.down + " rooms",
                floor.label, areaSize), areaX, nav.contentY + 5, MenuStyle.DIM);
        drawGrid(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);

        // Under the grid, not over the settings row. This sat at panelH - 40, which was clear when there was
        // one row of buttons and is inside the Floor / Rooms to blood / Puzzles row now that there are two.
        // Anchored to the GRID rather than to the panel, so it follows the grid when the grid shrinks
        // instead of being swallowed by it.
        //
        // And FITTED to the space, which it was not. The hint is drawn from the grid's left edge and the room
        // list starts at listX, so anything wider than the gap between them runs underneath the list - which
        // is exactly what "click a room, then a cell · right-click a cell to remove" did at his window size.
        // Writing a shorter string would only move the problem to the next window that is narrower still, so
        // the width is measured: the full wording when it fits, a short form when it does not, and a trim as
        // the last resort so a long status line can never reach the list either.
        ScreenChrome.text(g, fit(status.isEmpty()
                        ? "click a room, then a cell · right-click a cell to remove"
                        : status,
                status.isEmpty() ? "click a room, then a cell" : null,
                listX - 8 - areaX), areaX, areaY + areaSize + 4, MenuStyle.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /**
     * The longest of these that fits in {@code maxWidth}, trimmed with an ellipsis if even the last does not.
     *
     * @param full     the preferred wording
     * @param shorter  a fallback wording, or null when there is none
     * @param maxWidth the space available before the next thing on the row
     */
    private String fit(String full, String shorter, int maxWidth) {
        if (maxWidth <= 0 || this.font.width(full) <= maxWidth) {
            return full;
        }
        if (shorter != null && this.font.width(shorter) <= maxWidth) {
            return shorter;
        }
        String candidate = shorter != null ? shorter : full;
        int ellipsis = this.font.width("...");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidate.length(); i++) {
            if (this.font.width(sb.toString() + candidate.charAt(i)) + ellipsis > maxWidth) {
                break;
            }
            sb.append(candidate.charAt(i));
        }
        return sb + "...";
    }

    /**
     * The lowest pixel the room list may use.
     *
     * <p>Derived from the button rows rather than written as a number in three places. It was
     * {@code panelH - 32}, which cleared one row of buttons; a second row was added under it and the list ran
     * straight through the settings row (killer560, 2026-09-29: "the search rooms section goes too low and
     * overlaps the top layer of things").
     */
    private int listBottom() {
        return rowAY() - 8;
    }

    /** The top of the grid and of the room list: under the search row. Read by the testkit. */
    public int listTop() {
        return nav.contentY + 24;
    }

    /** The y of the upper button row (floor, sliders, Generate); the lower row (Clear, Load, Play) is 24 below. */
    private int rowAY() {
        return nav.contentBottom - 20 - 24;
    }

    /** Map units to screen pixels: one grid cell is the live map's room-plus-gap pitch. */
    private int unitPx(float units) {
        return Math.round(units * cell / (float) com.killer560.hub.livemap.MapPainter.ROOM_PITCH_UNITS);
    }

    /**
     * A placed room's box {@code {x0, y0, x1, y1}} in the live map's geometry: the room is 16 units and the
     * next one starts 4 units later, so the gap between neighbours is where a doorway is drawn. A room covering
     * several cells runs straight through the gaps inside it.
     */
    private int[] roomBox(int key, String name) {
        int[] fp = footprintOf(name, rotations.getOrDefault(key, 0));
        int ax = key % GRID;
        int az = key / GRID;
        int w = Math.min(floor.across, ax + fp[0]) - ax;
        int h = Math.min(floor.down, az + fp[1]) - az;
        int pitch = com.killer560.hub.livemap.MapPainter.ROOM_PITCH_UNITS;
        int size = com.killer560.hub.livemap.MapPainter.ROOM_SIZE_UNITS;
        return new int[]{
                gridX + ax * cell,
                gridY + az * cell,
                gridX + unitPx((ax + w - 1) * pitch + size),
                gridY + unitPx((az + h - 1) * pitch + size)};
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int sizeW = cell * floor.across;
        int sizeH = cell * floor.down;
        GuiRects well = GuiRects.begin(g);
        MenuStyle.well(well, gridX - 1, gridY - 1, sizeW + 2, sizeH + 2, MenuStyle.WELL);
        int line = MenuStyle.mix(MenuStyle.WELL, MenuStyle.TXT, 0.10f);
        for (int i = 1; i < floor.across; i++) {
            well.fill(gridX + i * cell, gridY, gridX + i * cell + 1, gridY + sizeH, line);
        }
        for (int i = 1; i < floor.down; i++) {
            well.fill(gridX, gridY + i * cell, gridX + sizeW, gridY + i * cell + 1, line);
        }
        well.submit();
        for (Map.Entry<Integer, String> e : placements.entrySet()) {
            String name = e.getValue();
            int[] b = roomBox(e.getKey(), name);
            g.fill(b[0], b[1], b[2], b[3], colourFor(name));
        }
        drawDoors(g);
        // Labels last, so a door never covers a room name (killer560, 2026-10-06: "have the room text be higher
        // up than the doors").
        // The live map's Room Labels setting decides what is written (killer560, 2026-10-06: "if my normal map has
        // the room name and secrets then this should also show the secrets under their name"). Off and Checkmarks
        // have no text to show, and a designer with unnamed rooms is unusable, so they read as Room Name.
        com.killer560.hub.livemap.LiveMapConfig labelCfg = com.killer560.hub.livemap.LiveMapConfig.getInstance();
        int style = labelCfg.getRoomLabels() < 2 ? 3 : labelCfg.getRoomLabels();
        int textColour = com.killer560.hub.livemap.MapPainter.unopenedTextColor();
        float want = 0.4f * labelCfg.getFontScale() * cell
                / (float) com.killer560.hub.livemap.MapPainter.ROOM_PITCH_UNITS;
        StringBuilder drawnNow = new StringBuilder();
        for (Map.Entry<Integer, String> e : placements.entrySet()) {
            String name = e.getValue();
            int[] b = roomBox(e.getKey(), name);
            int x0 = b[0];
            int y0 = b[1];
            int x1 = b[2];
            int y1 = b[3];
            // Nothing is found in a designer, so the secrets read the way the live map writes a room nobody has
            // opened: 0/N, from the room database's count.
            RoomEntry entry = RoomDatabase.lookupByName(name);
            int secrets = entry == null ? 0 : entry.secrets;
            java.util.List<String> lines = com.killer560.hub.livemap.MapPainter.labelLines(style,
                    SimFloorGen.typeOf(name), name.split(" "), secrets,
                    entry == null ? "?" : com.killer560.hub.livemap.MapPainter.unvisitedSecretsText(secrets), false);
            if (lines.isEmpty()) {
                continue;
            }
            drawnNow.append(String.join("|", lines)).append(';');
            // The live map's own fitting, not a truncation. killer560 (2026-10-04): "make it so the text will
            // fit rooms that are too small to load the whole text, just like our normal map would." One word a
            // line, scaled down until the longest word and the line count both fit the room, centred on it -
            // MapPainter.drawFittedLines is the same code the dungeon map draws its names with, at the same
            // wanted scale.
            com.killer560.hub.livemap.MapPainter.drawFittedLines(g, this.font, lines.toArray(new String[0]),
                    x0 + (x1 - x0) / 2f, y0 + (y1 - y0) / 2f, x1 - x0 - 4, y1 - y0 - 4, want, textColour,
                    com.killer560.hub.livemap.MapPainter.labelShadow(labelCfg, textColour, colourFor(name)));
        }
        labelsDrawn = drawnNow.toString();
        if (mouseX >= gridX && mouseX < gridX + sizeW && mouseY >= gridY && mouseY < gridY + sizeH) {
            int gx = (mouseX - gridX) / cell;
            int gz = (mouseY - gridY) / cell;
            g.outline(gridX + gx * cell, gridY + gz * cell, cell, cell, MenuStyle.OR);
        }
    }

    /**
     * The doors the build will actually cut, coloured the way the dungeon map colours them.
     *
     * <p>Read from the plan's own door cells - the generated plan when there is one, otherwise the quiet plan
     * of the drawing - so a gap with no door in it is drawn as the wall it will be. An ordinary doorway is a
     * narrow opening; wither, blood and entrance doors are wider blocks in the live map's configured colours,
     * a wither door plain black and blood plain red, with no outline.
     */
    private void drawDoors(GuiGraphicsExtractor g) {
        MapCode.Decoded plan = generated != null ? generated.decoded() : drawnPlan;
        if (plan == null) {
            return;
        }
        com.killer560.hub.livemap.LiveMapConfig cfg = com.killer560.hub.livemap.LiveMapConfig.getInstance();
        // Where a real floor would have its wither doors (the sim builds none) - see SimWitherDoors. Worked out
        // once per plan, not per frame.
        if (plan != witherFor) {
            witherFor = plan;
            witherDoors = SimWitherDoors.compute(plan.cellRoom(), plan.cellDoor(), plan.nameTable());
        }
        int big = GRID * 2 - 1;
        for (int gz = 0; gz < big; gz++) {
            for (int gx = 0; gx < big; gx++) {
                boolean betweenX = gx % 2 == 1 && gz % 2 == 0;
                boolean betweenZ = gx % 2 == 0 && gz % 2 == 1;
                if (!betweenX && !betweenZ) {
                    continue;
                }
                int idx = gz * com.killer560.hub.livemap.DungeonLayout.GRID + gx;
                if (idx >= plan.cellDoor().length) {
                    continue;
                }
                int type = plan.cellDoor()[idx];
                if (type == com.killer560.hub.livemap.DungeonLayout.DOOR_NONE) {
                    continue;
                }
                boolean theoretical = idx < witherDoors.length && witherDoors[idx];
                boolean normal = type == com.killer560.hub.livemap.DungeonLayout.DOOR_NORMAL && !theoretical;
                int colour = theoretical ? cfg.getColorWitherDoor() : switch (type) {
                    case com.killer560.hub.livemap.DungeonLayout.DOOR_WITHER -> cfg.getColorWitherDoor();
                    case com.killer560.hub.livemap.DungeonLayout.DOOR_BLOOD -> cfg.getColorBlood();
                    case com.killer560.hub.livemap.DungeonLayout.DOOR_ENTRANCE -> cfg.getColorEntrance();
                    default -> 0xFF8A6A48;
                };
                boolean wither = type == com.killer560.hub.livemap.DungeonLayout.DOOR_WITHER || theoretical;
                // Plain black for a wither door, plain red for blood, no outline (killer560, 2026-10-06: "remove
                // the orange highlight for the wither doors just make them black or red for the blood one").
                if (wither) {
                    colour = 0xFF000000;
                } else if (type == com.killer560.hub.livemap.DungeonLayout.DOOR_BLOOD) {
                    colour = 0xFFB23030;
                } else if (normal) {
                    // The live map's own connector colour: that of the joined room with the best type priority.
                    colour = connectorColour(plan, gx, gz, betweenX);
                }
                // Exactly the live map's doorway box (killer560, 2026-10-06: "make the paths between rooms on the
                // map the exact same as they look on the normal map").
                float[] box = com.killer560.hub.livemap.MapPainter.doorUnits(gx, gz);
                int bx0 = gridX + unitPx(box[0]);
                int by0 = gridY + unitPx(box[1]);
                int bx1 = gridX + unitPx(box[0] + box[2]);
                int by1 = gridY + unitPx(box[1] + box[3]);
                g.fill(bx0, by0, bx1, by1, colour);
                if (wither) {
                    // A thin light-grey rim so a black door reads against the dark gap (killer560, 2026-10-06:
                    // "change something so the black doors are easier to be seen").
                    g.outline(bx0, by0, bx1 - bx0, by1 - by0, 0xFFB0B0B0);
                }
            }
        }
    }

    /** MapPainter.connectorColor's rule for the designer's palette: the joined room with the best type priority. */
    private static int connectorColour(MapCode.Decoded plan, int gx, int gz, boolean betweenX) {
        int best = Integer.MAX_VALUE;
        int colour = 0xFF6B4A2F;
        for (int side = 0; side < 2; side++) {
            int nx = betweenX ? gx + (side == 0 ? -1 : 1) : gx;
            int nz = betweenX ? gz : gz + (side == 0 ? -1 : 1);
            int idx = nz * com.killer560.hub.livemap.DungeonLayout.GRID + nx;
            if (nx < 0 || nz < 0 || idx >= plan.cellRoom().length) {
                continue;
            }
            int id = plan.cellRoom()[idx];
            if (id < 0 || id >= plan.nameTable().length) {
                continue;
            }
            String name = plan.nameTable()[id];
            int prio = com.killer560.hub.livemap.MapPainter.typePriority(SimFloorGen.typeOf(name));
            if (prio < best) {
                best = prio;
                colour = colourFor(name);
            }
        }
        return colour;
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = listTop();
        int bottom = listBottom();
        ScreenChrome.well(g, listX - 1, top - 1, listW + 2, bottom - top + 2);
        g.enableScissor(listX, top, listX + listW, bottom);
        for (int i = 0; i < listed.size(); i++) {
            int y = top + i * ROW_H - scroll;
            if (y + ROW_H < top || y > bottom) {
                continue;
            }
            String name = listed.get(i);
            boolean isSelected = panel == Panel.ROOMS && name.equals(selected);
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H;
            if (isSelected || hover) {
                GuiRects rr = GuiRects.begin(g);
                ScreenChrome.row(rr, listX, y, listX + listW - listX, ROW_H, hover, isSelected, false);
                rr.submit();
            }
            if (panel == Panel.MAPS) {
                SimMapHistory.Entry e = history.get(i);
                g.fill(listX + 3, y + 3, listX + 7, y + ROW_H - 3, MenuStyle.OR);
                // The date he made it on the right of the first line; the title is cut with "..." short of it, so a
                // long title or a narrow list never runs the two together (killer560, 2026-10-15: "make that
                // something that can't happen").
                String date = e.dateText();
                int dateX = listX + listW - 6 - this.font.width(date);
                MenuStyle.text(g, MenuStyle.ellipsize(name, dateX - 8 - (listX + 10)), listX + 10, y + ROW_TEXT1,
                        MenuStyle.TXT);
                MenuStyle.text(g, date, dateX, y + ROW_TEXT1, MenuStyle.OR);
                // A saved design brought in from the old Save file has no puzzle count recorded (-1).
                String counts = e.rooms() + " rooms" + (e.puzzles() >= 0 ? "  ·  " + e.puzzles() + " puzzles" : "");
                MenuStyle.text(g, fit(counts + "  ·  right-click to delete", counts, listW - 16),
                        listX + 10, y + ROW_TEXT2, MenuStyle.DIM);
                continue;
            }
            g.fill(listX + 3, y + 3, listX + 7, y + ROW_H - 3, colourFor(name));
            MenuStyle.text(g, MenuStyle.ellipsize(name, listW - 16), listX + 10, y + ROW_TEXT1,
                    isSelected ? MenuStyle.OR : MenuStyle.TXT);
            RoomEntry entry = RoomDatabase.lookupByName(name);
            String sub = SimFloorGen.typeOf(name).toLowerCase(Locale.ROOT);
            if (entry != null) {
                sub += "  ·  " + (entry.shape == null ? "?" : entry.shape)
                        + "  ·  " + entry.secrets + " secrets";
            }
            MenuStyle.text(g, MenuStyle.ellipsize(sub, listW - 16), listX + 10, y + ROW_TEXT2, MenuStyle.DIM);
        }
        g.disableScissor();
        if (listed.isEmpty()) {
            String empty = panel == Panel.MAPS
                    ? (searchText.isBlank() ? "no floors yet - Generate, then Play" : "no floors match")
                    : !SimRoomFilters.isDefault() ? "no rooms match the filters" : "no rooms match";
            MenuStyle.text(g, empty, listX + 8, top + 8, MenuStyle.DIM);
        }
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
