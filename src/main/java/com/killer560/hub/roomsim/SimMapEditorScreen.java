package com.killer560.hub.roomsim;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.profit.ProfitPanels;
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
public class SimMapEditorScreen extends Screen {

    /** Room slots per side. The 11x11 cell grid holds rooms on even cells, so six of them. */
    private static final int GRID = (com.killer560.hub.livemap.DungeonLayout.GRID + 1) / 2;

    private static final int ROW_H = 22;

    private final Screen parent;

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

    /** What the right-hand panel is listing. */
    private enum Panel { ROOMS, MAPS }

    private final List<String> listed = new ArrayList<>();
    private String selected;
    /** What Save will call this design. Survives a widget rebuild. */
    private String mapName = "My Dungeon";
    private EditBox nameBox;
    private Panel panel = Panel.ROOMS;
    private EditBox search;
    private int scroll;
    private String status = "";

    // The random generator's settings, which used to live on a separate "Create a New Map" page.
    //
    // killer560 (2026-09-29): "this screen needs to be the design the map screen. That should be the only one
    // once you click create a new map." So the settings came here rather than being dropped - Generate below
    // still calls the real floor generator, NOT the Fill button. Those are different things and must stay
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
    private int listX;
    private int listW;

    public SimMapEditorScreen(Screen parent) {
        super(Component.literal("Design a map"));
        this.parent = parent;
    }

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
        panelW = Math.min(this.width - 40, 640);
        // One row taller than it was: the generator settings moved onto this screen.
        panelH = Math.min(this.height - 40, 366);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        // The grid is square and takes the left half; the list takes the rest.
        int gridArea = Math.min(panelH - 144, (panelW - 30) / 2);
        // Floor of 8, not 14. At 14 the grid could not shrink far enough to clear the settings row on a
        // short window - scenario 83 measured the grid's bottom at y 170 against a button top of y 168 at a
        // 240-unit window height. A cramped grid is better than one drawn over the controls.
        cell = Math.max(8, gridArea / GRID);
        gridX = panelX + 14;
        gridY = panelY + 66;
        listX = gridX + cell * GRID + 14;
        listW = panelX + panelW - 12 - listX;

        search = new EditBox(this.font, listX, panelY + 44, listW, 16, Component.literal("Search"));
        search.setHint(Component.literal("Search rooms..."));
        search.setResponder(v -> {
            scroll = 0;
            refilter();
        });
        addRenderableWidget(search);

        // The map's name, like the field in Ashfall's Dungeon Maker. Save uses it; Load fills it in.
        nameBox = new EditBox(this.font, gridX, panelY + 44, cell * GRID, 16, Component.literal("Name"));
        nameBox.setHint(Component.literal("Map name..."));
        nameBox.setValue(mapName);
        nameBox.setResponder(v -> mapName = v);
        addRenderableWidget(nameBox);

        // Row A - the random floor: its settings and the button that builds one.
        int ay = panelY + panelH - 52;
        int aAvail = panelW - 28 - 18;
        int wFloor = aAvail * 24 / 100;
        int wSlider = aAvail * 27 / 100;
        int wGen = aAvail - wFloor - wSlider * 2;
        int ax = panelX + 14;
        addRenderableWidget(SettingsButtonWidget.builder(
                Component.literal("§6" + floor.label), b -> {
                    var all = SimFloorGen.Floor.values();
                    floor = all[(floor.ordinal() + 1) % all.length];
                    rebuildWidgets();
                }).bounds(ax, ay, wFloor, 20).build());
        ax += wFloor + 6;
        addRenderableWidget(new SimSlider(ax, ay, wSlider, "Rooms to blood",
                SimFloorGen.MIN_ROOMS_TO_BLOOD, SimFloorGen.MAX_ROOMS_TO_BLOOD, roomsToBlood,
                v -> roomsToBlood = v));
        ax += wSlider + 6;
        addRenderableWidget(new SimSlider(ax, ay, wSlider, "Puzzles",
                SimFloorGen.MIN_PUZZLES, SimFloorGen.MAX_PUZZLES, puzzleCount,
                v -> puzzleCount = v));
        ax += wSlider + 6;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aGenerate"), b -> preview())
                .bounds(ax, ay, wGen, 20).build());

        // Row B - the drawn floor: the drawing tools, and Play for what is on the grid.
        int by = panelY + panelH - 26;
        int count = 6;
        int bw = (panelW - 28 - 6 * (count - 1)) / count;
        int bx = panelX + 14;

        // Clear takes the GENERATION off first, and only wipes the drawing if there is no generation to take
        // off. killer560 (2026-09-29): "also had a button to clear the current generation". Two steps rather
        // than two buttons: after a Generate the thing on screen is the generated floor, so that is what Clear
        // should remove, and pressing it again clears whatever he had drawn himself.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Clear"), b -> {
            if (generated != null) {
                planStale();
                placements.clear();
                placements.putAll(pinned);
                rebuildOccupancy();
                status = pinned.isEmpty() ? "generation cleared"
                        : "generation cleared - your " + pinned.size() + " room(s) kept";
            } else {
                placements.clear();
                pinned.clear();
                rebuildOccupancy();
                status = "cleared";
            }
        }).bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Fill"), b -> {
            planStale();
            autoFill();
            // A filled grid is his drawing, not a generated floor - Clear should treat it that way.
            pinned.clear();
            pinned.putAll(placements);
        })
                .bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save"), b -> saveMap())
                .bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Load"), b -> {
            panel = panel == Panel.MAPS ? Panel.ROOMS : Panel.MAPS;
            scroll = 0;
            refilter();
            status = panel == Panel.MAPS ? "pick a saved map" : "";
        }).bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b ->
                this.minecraft.setScreen(parent)).bounds(bx, by, bw, 20).build());
        bx += bw + 6;

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("§aPlay"), b -> play())
                .bounds(bx, by, bw, 20).build());

        refilter();
        rebuildOccupancy();
    }

    /** The room list, filtered by the search box and sorted so puzzles and specials are easy to find. */
    private void refilter() {
        listed.clear();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (panel == Panel.MAPS) {
            for (String name : SimMapPresets.names()) {
                if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) {
                    listed.add(name);
                }
            }
            return;
        }
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            if (r == null || !r.usable()) {
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
        occupiedBy.clear();
        for (Map.Entry<Integer, String> e : placements.entrySet()) {
            int[] size = footprintOf(e.getValue());
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
    private static int[] footprintOf(String name) {
        RoomLibrary.Room r = RoomLibrary.get(name);
        if (r == null) {
            return new int[]{1, 1};
        }
        return new int[]{tiles(r.sizeX), tiles(r.sizeZ)};
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
        int[] size = footprintOf(name);
        int ax = slot % GRID;
        int az = slot / GRID;
        if (ax + size[0] > GRID || az + size[1] > GRID) {
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

    /** Fills the empty cells with random usable rooms - a starting point rather than a blank grid. */
    private void autoFill() {
        List<String> pool = new ArrayList<>(listed);
        if (pool.isEmpty()) {
            status = "no usable rooms";
            return;
        }
        java.util.Collections.shuffle(pool);
        int added = 0;
        for (int slot = 0; slot < GRID * GRID; slot++) {
            if (occupiedBy.containsKey(slot)) {
                continue;
            }
            for (String name : pool) {
                if (canPlace(name, slot)) {
                    placements.put(slot, name);
                    rebuildOccupancy();
                    added++;
                    break;
                }
            }
        }
        status = "filled " + added + " cell(s)";
    }

    private void saveMap() {
        if (placements.isEmpty()) {
            status = "nothing to save";
            return;
        }
        String name = nameBox == null ? mapName : nameBox.getValue();
        if (name == null || name.isBlank()) {
            status = "give the map a name first";
            return;
        }
        SimMapPresets.put(name, placements);
        status = "saved \"" + name.trim() + "\"";
    }

    private void loadMap(String name) {
        planStale();
        pinned.clear();
        Map<Integer, String> saved = SimMapPresets.get(name);
        if (saved == null) {
            status = "could not read \"" + name + "\"";
            return;
        }
        placements.clear();
        // Only rooms still in the library. A preset naming one he has since lost would otherwise sit on the
        // grid looking placeable and be dropped at Play, which is a worse way to find out.
        int dropped = 0;
        for (Map.Entry<Integer, String> e : saved.entrySet()) {
            RoomLibrary.Room r = RoomLibrary.get(e.getValue());
            if (r == null || !r.usable()) {
                dropped++;
                continue;
            }
            placements.put(e.getKey(), e.getValue());
            pinned.put(e.getKey(), e.getValue());
        }
        rebuildOccupancy();
        mapName = name;
        if (nameBox != null) {
            nameBox.setValue(name);
        }
        panel = Panel.ROOMS;
        scroll = 0;
        refilter();
        status = dropped == 0 ? "loaded \"" + name + "\""
                : "loaded \"" + name + "\" - " + dropped + " room(s) are no longer captured";
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
     * <p>Play builds the PLAN when there is one, so a plan left standing after he edits, clears, fills or
     * loads over it would build something other than what he is looking at - the exact failure the preview
     * exists to prevent.
     */
    private void planStale() {
        generated = null;
    }

    /**
     * Lays out a floor and puts it on the grid. Nothing is built and no world is opened.
     */
    private void preview() {
        // A slider parked at the far right means "pick for me", and the pick is drawn INSIDE the slider's own
        // range, so random can never produce a floor the slider could not have been set to by hand.
        int puzzles = roll(puzzleCount, SimFloorGen.MIN_PUZZLES, SimFloorGen.MAX_PUZZLES);
        int blood = roll(roomsToBlood, SimFloorGen.MIN_ROOMS_TO_BLOOD, SimFloorGen.MAX_ROOMS_TO_BLOOD);
        SimFloorGen.Planned planned = SimFloorGen.plan(floor, puzzles, blood);
        if (planned == null) {
            status = "could not lay out that floor";   // plan() has already said why, in chat
            return;
        }
        generated = planned;
        placements.clear();
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
            }
        }
        rebuildOccupancy();
        status = planned.decoded().nameTable().length + " rooms, " + puzzles + " puzzle(s), blood "
                + planned.bloodDistance() + " in - press Play to build it";
    }

    private void play() {
        // A generated plan wins over the drawing, because it carries rotations the drawing cannot.
        if (generated != null) {
            String code = generated.code();
            SimWorld.open(this.minecraft, code, c -> SimBuilder.build(c, code), "Generating " + floor.label);
            return;
        }
        if (placements.isEmpty()) {
            status = "press Generate, or place a room first";
            return;
        }
        int built = SimFloorGen.buildExplicit(this.minecraft, placements);
        if (built > 0) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        // The grid.
        if (mx >= gridX && mx < gridX + cell * GRID && my >= gridY && my < gridY + cell * GRID) {
            int gx = (int) ((mx - gridX) / cell);
            int gz = (int) ((my - gridY) / cell);
            int slot = gz * GRID + gx;
            if (event.button() == 1) {
                Integer anchor = occupiedBy.get(slot);
                if (anchor != null) {
                    planStale();
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
                pinned.remove(anchor);
                placements.remove(anchor);
                rebuildOccupancy();
            }
            if (canPlace(selected, slot)) {
                planStale();
                placements.put(slot, selected);
                pinned.put(slot, selected);
                rebuildOccupancy();
                status = "placed " + selected;
            } else {
                int[] s = footprintOf(selected);
                status = selected + " needs " + s[0] + "x" + s[1] + " free cells here";
            }
            return true;
        }

        // The list.
        int top = panelY + 66;
        int bottom = listBottom();
        if (mx >= listX && mx < listX + listW && my >= top && my < bottom) {
            int row = (int) ((my - top + scroll) / ROW_H);
            if (row >= 0 && row < listed.size()) {
                if (panel == Panel.MAPS) {
                    if (event.button() == 1) {
                        SimMapPresets.remove(listed.get(row));
                        refilter();
                        status = "deleted that saved map";
                    } else {
                        loadMap(listed.get(row));
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
            int visible = listBottom() - (panelY + 66);
            int max = Math.max(0, listed.size() * ROW_H - visible);
            scroll = Math.max(0, Math.min(max, scroll - (int) (dy * ROW_H)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xCC000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, ProfitPanels.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, ProfitPanels.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        g.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, ProfitPanels.ACCENT);
        g.text(this.font, "DESIGN A MAP", panelX + 10, panelY + 11, ProfitPanels.ACCENT, false);
        String right = placements.size() + " placed  ·  " + listed.size() + " rooms available";
        g.text(this.font, right, panelX + panelW - 10 - this.font.width(right), panelY + 11,
                ProfitPanels.DIM, false);

        drawGrid(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);

        String hint = status.isEmpty()
                ? "click a room, then a cell · right-click a cell to remove"
                : status;
        // Under the grid, not over the settings row. This sat at panelH - 40, which was clear when there was
        // one row of buttons and is inside the Floor / Rooms to blood / Puzzles row now that there are two.
        // Anchored to the GRID rather than to the panel, so it follows the grid when the grid shrinks
        // instead of being swallowed by it.
        g.text(this.font, hint, panelX + 14, gridY + cell * GRID + 4, ProfitPanels.DIM, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
        return panelY + panelH - 58;
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int size = cell * GRID;
        g.fill(gridX, gridY, gridX + size, gridY + size, ProfitPanels.INNER_BG);
        for (int i = 0; i <= GRID; i++) {
            g.fill(gridX + i * cell, gridY, gridX + i * cell + 1, gridY + size, 0xFF1A1A1A);
            g.fill(gridX, gridY + i * cell, gridX + size, gridY + i * cell + 1, 0xFF1A1A1A);
        }
        for (Map.Entry<Integer, String> e : placements.entrySet()) {
            String name = e.getValue();
            int[] fp = footprintOf(name);
            int ax = e.getKey() % GRID;
            int az = e.getKey() / GRID;
            int x0 = gridX + ax * cell + 1;
            int y0 = gridY + az * cell + 1;
            int x1 = gridX + Math.min(GRID, ax + fp[0]) * cell - 1;
            int y1 = gridY + Math.min(GRID, az + fp[1]) * cell - 1;
            g.fill(x0, y0, x1, y1, colourFor(name));
            // Only label a room the cell is wide enough to read, or the grid turns to mush.
            String label = name;
            int room = x1 - x0 - 4;
            if (this.font.width(label) > room) {
                label = label.substring(0, Math.max(1, Math.min(label.length(),
                        room / Math.max(1, this.font.width("n"))))).trim();
            }
            if (room > 8) {
                // Centred in the room's whole box, not pinned to its left edge - a 2x2's name belongs in the
                // middle of the 2x2. killer560 (2026-09-29): "The room names need centered".
                g.text(this.font, label, x0 + ((x1 - x0) - this.font.width(label)) / 2,
                        y0 + (y1 - y0) / 2 - 4, 0xFFFFFFFF, false);
            }
        }
        // A nub between two different rooms that touch, so the drawing shows where doors will be cut.
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                Integer a = occupiedBy.get(gz * GRID + gx);
                if (a == null) {
                    continue;
                }
                Integer rightN = gx + 1 < GRID ? occupiedBy.get(gz * GRID + gx + 1) : null;
                if (rightN != null && !rightN.equals(a)) {
                    int x = gridX + (gx + 1) * cell;
                    int y = gridY + gz * cell + cell / 2;
                    g.fill(x - 2, y - 3, x + 3, y + 3, 0xFF2A2A2A);
                }
                Integer downN = gz + 1 < GRID ? occupiedBy.get((gz + 1) * GRID + gx) : null;
                if (downN != null && !downN.equals(a)) {
                    int x = gridX + gx * cell + cell / 2;
                    int y = gridY + (gz + 1) * cell;
                    g.fill(x - 3, y - 2, x + 3, y + 3, 0xFF2A2A2A);
                }
            }
        }
        if (mouseX >= gridX && mouseX < gridX + size && mouseY >= gridY && mouseY < gridY + size) {
            int gx = (mouseX - gridX) / cell;
            int gz = (mouseY - gridY) / cell;
            g.outline(gridX + gx * cell, gridY + gz * cell, cell, cell, ProfitPanels.ACCENT);
        }
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = panelY + 66;
        int bottom = listBottom();
        g.fill(listX, top, listX + listW, bottom, ProfitPanels.INNER_BG);
        g.outline(listX - 1, top - 1, listW + 2, bottom - top + 2, ProfitPanels.BORDER);
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
                g.fill(listX, y, listX + listW, y + ROW_H, isSelected ? 0xFF241A0E : 0xFF141414);
            }
            if (panel == Panel.MAPS) {
                Map<Integer, String> saved = SimMapPresets.get(name);
                g.fill(listX + 2, y + 3, listX + 6, y + ROW_H - 3, ProfitPanels.ACCENT);
                g.text(this.font, name, listX + 10, y + 3, ProfitPanels.TEXT, false);
                g.text(this.font, (saved == null ? 0 : saved.size())
                                + " room(s), right-click to delete",
                        listX + 10, y + 12, ProfitPanels.DIM, false);
                continue;
            }
            g.fill(listX + 2, y + 3, listX + 6, y + ROW_H - 3, colourFor(name));
            g.text(this.font, name, listX + 10, y + 3, isSelected ? ProfitPanels.ACCENT : ProfitPanels.TEXT,
                    false);
            RoomEntry entry = RoomDatabase.lookupByName(name);
            String sub = SimFloorGen.typeOf(name).toLowerCase(Locale.ROOT);
            if (entry != null) {
                sub += "  ·  " + (entry.shape == null ? "?" : entry.shape)
                        + "  ·  " + entry.secrets + " secrets";
            }
            g.text(this.font, sub, listX + 10, y + 12, ProfitPanels.DIM, false);
        }
        g.disableScissor();
        if (listed.isEmpty()) {
            g.text(this.font, "no rooms match", listX + 8, top + 8, ProfitPanels.DIM, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
