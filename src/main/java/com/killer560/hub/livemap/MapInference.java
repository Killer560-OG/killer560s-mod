package com.killer560.hub.livemap;

import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Map Inference (mapinfer-1105). killer560, 2026-11-05: "the very bottom left room isnt filled in. however you should know
 * that pit is a 4 long so you should be able to infer that that portion also has to be connected to pit. furthermore you
 * know that the map is full then so there has to be a pathway going into old trap there. implement this logic to our map."
 *
 * <p>On the maps that draw the scanned floor (Funny Map, the Interactive Map) it fills in what the evidence the map already
 * has settles about the parts nobody has seen. A tile is SEEN when it is in a room group (world scan or map item), or the
 * world scan read its column in a loaded chunk and found nothing ({@link LiveMapFeature#seenEmpty}); a gap is a seen door
 * (world scan or map item), a seen join (room blocks in the gap, or the map's separator), a seen wall (an empty loaded
 * column, or the map showing the rooms on both sides and nothing between), or unknown.
 * <ol>
 * <li><b>Shape completion.</b> A room showing fewer tiles than its database shape: every placement of that shape inside the
 * floor that covers all its seen tiles and otherwise only unseen tiles, crosses no seen door or wall between its own tiles
 * and leaves no seen join to an unseen tile outside it. Exactly one: its missing tiles and the joins to them are drawn as
 * that room. Two or more: nothing. A placement over an unidentified room's tile counts as a fit but is never drawn. Two
 * rooms whose only placements claim one tile: nothing for either. A claimed tile counts as that room's in the next round,
 * so one room's completion can settle another's (until nothing changes).</li>
 * <li><b>Full floor.</b> Hypixel fills every slot of a floor's grid, so on a known floor (not the sim) with no slot seen
 * empty, every unseen tile no room claimed is drawn as an unknown room: the unopened gray with "?".</li>
 * <li><b>Forced door.</b> Every room has a door. A room whose whole shape is known (seen, or completed by rule 1), with no
 * seen door, and exactly one side that could still hold one (an unknown gap, or a seen join to another room the scan may
 * have misread, never towards a slot seen empty), has its door there.</li>
 * </ol>
 * Drawn in Predictive Rooms' guess style (the room or door color darkened by {@link PredictedRoomsPainter#GUESS_DARKEN};
 * the unopened gray with "?"), and only on cells nothing was seen on, so real data replaces it the moment it arrives: the
 * result is worked out again whenever the map data changes ({@link LiveMapFeature#mapVersion}, the map item's version,
 * the floor), never per frame. Display only: {@link DungeonLayout}, the pathfinders and every automation read the real
 * data, never this.
 */
final class MapInference {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-livemap");
    private static final int G = DungeonLayout.GRID;

    /** Input tile states besides a room id (>= 0). */
    static final int UNSEEN = -1;
    static final int EMPTY = -2;
    /** Input gap states. */
    static final int GAP_UNKNOWN = 0;
    static final int GAP_DOOR = 1;
    static final int GAP_WALL = 2;
    static final int GAP_JOIN = 3;
    /** {@link Result#cell} of a cell nothing was inferred on, and of an unseen tile of a full floor (some room). */
    static final int NOTHING = -1;
    static final int ANY_ROOM = -3;

    /** What it concluded, by grid cell: the room (id) an inferred cell belongs to, and the inferred doors. */
    record Result(int[] cell, boolean[] door, int count) {
    }

    private static final Result NONE = new Result(new int[0], new boolean[0], 0);

    private MapInference() {
    }

    // ------------------------------------------------------------------------------------------------- the inference

    /**
     * @param cols   room slots across
     * @param rows   room slots down
     * @param tile   per grid cell (room cells only): a room id, {@link #UNSEEN} or {@link #EMPTY}
     * @param gap    per grid cell (gaps between two room slots): {@link #GAP_UNKNOWN} ... {@link #GAP_JOIN}
     * @param shapes per room id: the database shape ("1x1".."1x4", "2x2", "L"), or null when unidentified
     * @param full   whether every slot of the floor is known to hold a room
     */
    static Result infer(int cols, int rows, int[] tile, int[] gap, String[] shapes, boolean full) {
        int rooms = shapes.length;
        // 1. Shape completion, in rounds: a tile one room's only placement claims is that room's from then on, which can
        // leave another room only one placement (Gold's second tile settles which way Pit runs).
        int[] seen = tile.clone();
        int[] known = new int[rooms];
        int[] claimedBy = new int[G * G];
        int[][] only = new int[rooms][];
        boolean changed = true;
        for (int round = 0; changed && round < 8; round++) {
            changed = false;
            Arrays.fill(known, 0);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int t = seen[slot(c, r)];
                    if (t >= 0 && t < rooms) {
                        known[t]++;
                    }
                }
            }
            Arrays.fill(claimedBy, NOTHING);
            Arrays.fill(only, null);
            for (int room = 0; room < rooms; room++) {
                String shape = shapes[room];
                if (shape == null || known[room] == 0 || known[room] >= RoomDatabase.shapeTileCount(shape)) {
                    continue;
                }
                only[room] = onlyPlacement(room, shape, known[room], seen, gap, shapes, cols, rows);
                for (int s : only[room] == null ? new int[0] : only[room]) {
                    if (seen[s] == UNSEEN) {
                        claimedBy[s] = claimedBy[s] == NOTHING ? room : ANY_ROOM;
                    }
                }
            }
            for (int room = 0; room < rooms; room++) {
                boolean clash = only[room] == null;
                for (int s : clash ? new int[0] : only[room]) {
                    clash |= seen[s] == UNSEEN && claimedBy[s] != room; // another room's only placement wants it too
                }
                if (clash) {
                    continue;
                }
                for (int s : only[room]) {
                    if (seen[s] == UNSEEN) {
                        seen[s] = room;
                        changed = true;
                    }
                }
            }
        }
        int[] cell = new int[G * G];
        Arrays.fill(cell, NOTHING);
        int count = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int s = slot(c, r);
                int room = seen[s];
                if (room < 0) {
                    continue;
                }
                boolean guessed = tile[s] == UNSEEN;
                if (guessed) {
                    cell[s] = room;
                    count++;
                }
                // The joins to the right and below, and a square's center, where a guessed tile is involved.
                int right = c + 1 < cols ? slot(c + 1, r) : -1;
                int down = r + 1 < rows ? slot(c, r + 1) : -1;
                if (right >= 0 && seen[right] == room && (guessed || tile[right] == UNSEEN)) {
                    cell[s + 1] = room;
                    count++;
                }
                if (down >= 0 && seen[down] == room && (guessed || tile[down] == UNSEEN)) {
                    cell[s + G] = room;
                    count++;
                }
                if (right >= 0 && down >= 0 && "2x2".equals(shapes[room]) && seen[right] == room && seen[down] == room
                        && seen[down + 2] == room && (guessed || tile[right] == UNSEEN || tile[down] == UNSEEN
                        || tile[down + 2] == UNSEEN)) {
                    cell[s + 1 + G] = room;
                }
            }
        }
        // 2. Full floor: every unseen tile left is some room.
        if (full) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int s = slot(c, r);
                    if (seen[s] == UNSEEN) {
                        cell[s] = ANY_ROOM;
                        count++;
                    }
                }
            }
        }
        // 3. Forced door.
        boolean[] door = new boolean[G * G];
        Arrays.fill(known, 0);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int t = seen[slot(c, r)];
                if (t >= 0 && t < rooms) {
                    known[t]++;
                }
            }
        }
        for (int room = 0; room < rooms; room++) {
            if (shapes[room] == null || known[room] != RoomDatabase.shapeTileCount(shapes[room])) {
                continue; // a room with tiles still unknown may have its door on one of them
            }
            int candidate = -1;
            int candidates = 0;
            boolean hasDoor = false;
            for (int r = 0; r < rows && !hasDoor; r++) {
                for (int c = 0; c < cols && !hasDoor; c++) {
                    int s = slot(c, r);
                    if (seen[s] != room) {
                        continue;
                    }
                    for (int d = 0; d < 4; d++) {
                        int nc = c + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int nr = r + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (nc < 0 || nr < 0 || nc >= cols || nr >= rows || seen[slot(nc, nr)] == room) {
                            continue;
                        }
                        int q = slot(nc, nr);
                        int k = between(s, q);
                        if (gap[k] == GAP_DOOR) {
                            hasDoor = true;
                            break;
                        }
                        // A seen join to another room is a gap the scan may have misread: it could be the door.
                        boolean open = gap[k] == GAP_UNKNOWN || (gap[k] == GAP_JOIN && seen[q] >= 0);
                        if (open && seen[q] != EMPTY) {
                            candidates++;
                            candidate = k;
                        }
                    }
                }
            }
            if (!hasDoor && candidates == 1 && gap[candidate] == GAP_UNKNOWN) {
                door[candidate] = true;
                count++;
            }
        }
        return new Result(cell, door, count);
    }

    /** The one placement of {@code shape} that fits room {@code room}, or null when none or several do, or the one
     *  that does lies over an unidentified room's tile. */
    private static int[] onlyPlacement(int room, String shape, int knownCount, int[] seen, int[] gap, String[] shapes,
                                       int cols, int rows) {
        int[] only = null;
        boolean onlyTainted = false;
        long onlyMask = 0;
        int fits = 0;
        for (int[][] variant : variants(shape)) {
            for (int r0 = -3; r0 < rows; r0++) {
                for (int c0 = -3; c0 < cols; c0++) {
                    int[] slots = place(variant, c0, r0, cols, rows);
                    int fit = slots == null ? 0 : fit(room, slots, knownCount, seen, gap, shapes, cols, rows);
                    if (fit == 0 || (fits > 0 && mask(slots) == onlyMask)) {
                        continue; // no fit, or the same tiles as a fit already counted
                    }
                    fits++;
                    only = slots;
                    onlyMask = mask(slots);
                    onlyTainted = fit == 2;
                }
            }
        }
        return fits == 1 && !onlyTainted ? only : null;
    }

    /** 0 = no fit, 1 = fits, 2 = fits only over an unidentified room's tile (counted, never drawn). */
    private static int fit(int room, int[] slots, int knownCount, int[] tile, int[] gap, String[] shapes, int cols, int rows) {
        int own = 0;
        boolean tainted = false;
        for (int s : slots) {
            int t = tile[s];
            if (t == room) {
                own++;
            } else if (t >= 0 && t < shapes.length && shapes[t] == null) {
                tainted = true;
            } else if (t != UNSEEN) {
                return 0;
            }
        }
        if (own != knownCount) {
            return 0;
        }
        for (int a : slots) {
            for (int b : slots) {
                int k = between(a, b);
                if (k >= 0 && (gap[k] == GAP_DOOR || gap[k] == GAP_WALL)) {
                    return 0; // a seen door or wall inside the placement
                }
            }
            int c = (a % G) / 2;
            int r = (a / G) / 2;
            for (int d = 0; d < 4; d++) {
                int nc = c + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nr = r + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) {
                    continue;
                }
                int q = slot(nc, nr);
                if (tile[q] == UNSEEN && !contains(slots, q) && gap[between(a, q)] == GAP_JOIN) {
                    return 0; // the room seen to go on into a tile this placement leaves out
                }
            }
        }
        return tainted ? 2 : 1;
    }

    /** Each way the shape can lie, as {col, row} offsets. */
    private static int[][][] variants(String shape) {
        return switch (shape) {
            case "1x1" -> new int[][][]{{{0, 0}}};
            case "1x2" -> lines(2);
            case "1x3" -> lines(3);
            case "1x4" -> lines(4);
            case "2x2" -> new int[][][]{{{0, 0}, {1, 0}, {0, 1}, {1, 1}}};
            case "L" -> new int[][][]{{{1, 0}, {0, 1}, {1, 1}}, {{0, 0}, {0, 1}, {1, 1}}, {{0, 0}, {1, 0}, {1, 1}},
                    {{0, 0}, {1, 0}, {0, 1}}};
            default -> new int[0][][];
        };
    }

    private static int[][][] lines(int n) {
        int[][][] out = new int[2][n][];
        for (int i = 0; i < n; i++) {
            out[0][i] = new int[]{i, 0};
            out[1][i] = new int[]{0, i};
        }
        return out;
    }

    private static int[] place(int[][] variant, int c0, int r0, int cols, int rows) {
        int[] out = new int[variant.length];
        for (int i = 0; i < variant.length; i++) {
            int c = c0 + variant[i][0];
            int r = r0 + variant[i][1];
            if (c < 0 || r < 0 || c >= cols || r >= rows) {
                return null;
            }
            out[i] = slot(c, r);
        }
        return out;
    }

    private static long mask(int[] slots) {
        long m = 0;
        for (int s : slots) {
            m |= 1L << ((s % G) / 2 + (s / G) / 2 * 6);
        }
        return m;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static int slot(int c, int r) {
        return 2 * c + 2 * r * G;
    }

    /** The gap cell between two room tiles side by side, or -1. */
    private static int between(int a, int b) {
        int ax = a % G;
        int az = a / G;
        int bx = b % G;
        int bz = b / G;
        return Math.abs(ax - bx) + Math.abs(az - bz) == 2 && (ax == bx || az == bz) ? (a + b) / 2 : -1;
    }

    // ------------------------------------------------------------------------------------------- from the live map

    /** One per map kind (the map item map, a scanned-floor map): the HUD and the Interactive Map may both draw in one
     *  frame, and a single slot would work the floor out again on every frame. */
    private static final class Slot {
        Result res = NONE;
        int mapVersion = Integer.MIN_VALUE;
        int scannerVersion;
        String floor;
        boolean sim;
        String logged = "";
    }

    private static final Slot[] SLOTS = {new Slot(), new Slot()};
    /** Test hook: the floor the live map is taken to be on, instead of the scoreboard's (none at the title screen). */
    private static String testFloor;

    /** The inference for the map as it stands, worked out again only when its inputs changed. Client thread. */
    static Result current() {
        int mv = LiveMapFeature.mapVersion();
        int sv = DungeonMapScanner.version();
        String floor = testFloor != null ? testFloor : com.killer560.hub.secrets.DungeonState.getFloor();
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        boolean hide = MapPainter.hideUnrevealed();
        Slot slot = SLOTS[hide ? 1 : 0];
        if (mv != slot.mapVersion || sv != slot.scannerVersion || sim != slot.sim
                || !java.util.Objects.equals(floor, slot.floor)) {
            slot.mapVersion = mv;
            slot.scannerVersion = sv;
            slot.sim = sim;
            slot.floor = floor;
            slot.res = fromLiveMap(floor, sim, hide);
            String described = describe(slot.res, null);
            if (!described.equals(slot.logged)) {
                slot.logged = described;
                LOGGER.info("[MapInference] {}{}", hide ? "map item map: " : "",
                        described.isEmpty() ? "nothing inferred" : described);
            }
        }
        return slot.res;
    }

    /** {@code hide}: the map item map, which draws only what the map item shows, so only that goes in: a room it does
     *  not show is unseen, a name it does not show gives no shape, and a gap beside an unseen tile is read off the map
     *  item (a tile the world scan found empty is unseen there too). */
    private static Result fromLiveMap(String floor, boolean sim, boolean hide) {
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        if (groups.isEmpty()) {
            return NONE;
        }
        int[] size = sim ? new int[]{6, 6} : MapPainter.floorRooms(floor);
        int cols = size[0];
        int rows = size[1];
        boolean calibrated = DungeonMapScanner.isCalibrated();
        if (hide && !calibrated) {
            return NONE; // no map item read yet: the map item map shows nothing to reason from
        }
        boolean[] shown = new boolean[groups.size()];
        String[] shapes = new String[groups.size()];
        for (int gid = 0; gid < shapes.length; gid++) {
            LiveMapFeature.RoomGroup g = groups.get(gid);
            shown[gid] = !hide || MapPainter.isRevealed(g);
            shapes[gid] = g.entry != null && (!hide || MapPainter.identityRevealed(g)) ? g.entry.shape : null;
        }
        int[] tile = new int[G * G];
        int[] gap = new int[G * G];
        // Rule 2 only where the scanned floor is drawn: on the map item map every room not reached yet would turn "?".
        boolean full = !hide && !sim && floor != null && !floor.isEmpty();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = slot(c, r);
                int gid = LiveMapFeature.groupIdAt(idx);
                tile[idx] = gid >= 0 && gid < shown.length && shown[gid] ? gid
                        : !hide && LiveMapFeature.seenEmpty(idx) ? EMPTY : UNSEEN;
                full &= tile[idx] != EMPTY;
            }
        }
        for (int gz = 0; gz < 2 * rows - 1; gz++) {
            for (int gx = 0; gx < 2 * cols - 1; gx++) {
                if (gx % 2 == gz % 2) {
                    continue; // a tile or a 2x2 center
                }
                int idx = gx + gz * G;
                int a = gx % 2 == 1 ? idx - 1 : idx - G;
                int b = gx % 2 == 1 ? idx + 1 : idx + G;
                if (hide && (tile[a] < 0 || tile[b] < 0)) {
                    gap[idx] = !calibrated ? GAP_UNKNOWN
                            : DungeonMapScanner.kindAt(idx) == DungeonMapScanner.KIND_DOOR ? GAP_DOOR
                            : DungeonMapScanner.kindAt(idx) == DungeonMapScanner.KIND_NONE
                                    && LiveMapFeature.mapShowsRoomsAround(idx) ? GAP_WALL : GAP_UNKNOWN;
                    continue;
                }
                gap[idx] = switch (LiveMapFeature.effectiveTile(idx)) {
                    case DOOR_NORMAL, DOOR_WITHER, DOOR_BLOOD, DOOR_ENTRANCE -> GAP_DOOR;
                    case ROOM -> GAP_JOIN;
                    case UNKNOWN -> LiveMapFeature.seenEmpty(idx) || (calibrated
                            && DungeonMapScanner.kindAt(idx) == DungeonMapScanner.KIND_NONE
                            && LiveMapFeature.mapShowsRoomsAround(idx)) ? GAP_WALL : GAP_UNKNOWN;
                };
            }
        }
        return infer(cols, rows, tile, gap, shapes, full);
    }

    // ------------------------------------------------------------------------------------------------------ drawing

    /** Draws the current inference, on a map that draws the scanned floor. Called from inside the map's draw cache. */
    static void draw(GuiGraphicsExtractor graphics, LiveMapConfig cfg, float ox, float oy, float ppu) {
        Result res = current();
        if (res.count() == 0) {
            return;
        }
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        paint(graphics, cfg, res, gid -> gid < groups.size() && MapPainter.isRevealed(groups.get(gid))
                ? MapPainter.roomColor(groups.get(gid), cfg) : 0, idx -> MapPainter.connectorColor(idx, cfg), ox, oy, ppu);
    }

    /** {@code roomColor}: a room's own fill by id (0 = not drawn); the inferred parts are drawn darkened from it. */
    private static void paint(GuiGraphicsExtractor graphics, LiveMapConfig cfg, Result res, IntUnaryOperator roomColor,
                              IntUnaryOperator doorColor, float ox, float oy, float ppu) {
        GuiRects rects = GuiRects.begin(graphics);
        for (int idx = 0; idx < res.cell().length; idx++) {
            int gx = idx % G;
            int gz = idx / G;
            if (res.door()[idx]) {
                float[] b = MapPainter.doorUnits(gx, gz);
                rects.fill(MapPainter.px(ox, b[0], ppu), MapPainter.px(oy, b[1], ppu), MapPainter.px(ox, b[0] + b[2], ppu),
                        MapPainter.px(oy, b[1] + b[3], ppu),
                        MapPainter.multiply(doorColor.applyAsInt(idx), PredictedRoomsPainter.GUESS_DARKEN));
                continue;
            }
            int owner = res.cell()[idx];
            if (owner == NOTHING) {
                continue;
            }
            int color = owner == ANY_ROOM ? MapPainter.unopenedFill(cfg) : roomColor.applyAsInt(owner);
            if (color == 0) {
                continue;
            }
            rects.fill(MapPainter.px(ox, MapPainter.cellPos(gx), ppu), MapPainter.px(oy, MapPainter.cellPos(gz), ppu),
                    MapPainter.px(ox, MapPainter.cellPos(gx) + MapPainter.cellSize(gx), ppu),
                    MapPainter.px(oy, MapPainter.cellPos(gz) + MapPainter.cellSize(gz), ppu),
                    owner == ANY_ROOM ? color : MapPainter.multiply(color, PredictedRoomsPainter.GUESS_DARKEN));
        }
        rects.submit();
        for (int idx = 0; idx < res.cell().length; idx++) {
            if (res.cell()[idx] == ANY_ROOM) {
                PredictedRoomsPainter.question(graphics, null, cfg, idx % G, idx / G, ox, oy, ppu);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- test hooks

    /**
     * Test hook (testkit 1105-logic-mapinfer-*): the inference on a floor written as text, one string per grid row
     * (2 x rows - 1 of them, 2 x cols - 1 characters each). A room cell: a letter (room a, b, ...), '.' unseen, 'x' seen
     * empty. A gap: ' ' or '?' unknown, 'D' door, '#' wall, '=' join. {@code shapes[letter]}: the room's shape, or null
     * when unidentified. Answers {@link #describe}.
     */
    public static String testInfer(String[] rows, String[] shapes, boolean full) {
        return describe(inferText(rows, shapes, full), null);
    }

    /** Test hook: {@link #testInfer}'s floor drawn by the map's own painter, room letter i in {@code colors[i]}; an
     *  inferred door in the color of the inferred room beside it, else room a's. */
    public static void testDraw(GuiGraphicsExtractor graphics, String[] rows, String[] shapes, boolean full, int[] colors,
                                float ox, float oy, float ppu) {
        Result res = inferText(rows, shapes, full);
        paint(graphics, LiveMapConfig.getInstance(), res, id -> colors[id], idx -> {
            int[] t = HeldMap.sides(idx);
            for (int s : t) {
                if (s >= 0 && s < G * G && res.cell()[s] >= 0) {
                    return colors[res.cell()[s]];
                }
            }
            return colors[0];
        }, ox, oy, ppu);
    }

    /** Test hook: the live map's inference ({@link #current}) as {@link #describe} words it, with the room names. Sets
     *  the floor it is taken to be on first (null: the scoreboard's again). */
    public static String testCurrent(String floor) {
        testFloor = floor;
        for (Slot slot : SLOTS) {
            slot.mapVersion = Integer.MIN_VALUE;
        }
        Result res = current();
        List<LiveMapFeature.RoomGroup> groups = LiveMapFeature.groupsView();
        String[] names = new String[groups.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = groups.get(i).entry != null ? groups.get(i).entry.name : "?";
        }
        return describe(res, names);
    }

    private static Result inferText(String[] rows, String[] shapes, boolean full) {
        int[] tile = new int[G * G];
        int[] gap = new int[G * G];
        for (int gz = 0; gz < rows.length; gz++) {
            for (int gx = 0; gx < rows[gz].length(); gx++) {
                char ch = rows[gz].charAt(gx);
                int idx = gx + gz * G;
                if (gx % 2 == 0 && gz % 2 == 0) {
                    tile[idx] = ch == 'x' ? EMPTY : ch >= 'a' && ch <= 'z' ? ch - 'a' : UNSEEN;
                } else {
                    gap[idx] = ch == 'D' ? GAP_DOOR : ch == '#' ? GAP_WALL : ch == '=' ? GAP_JOIN : GAP_UNKNOWN;
                }
            }
        }
        return infer((rows[0].length() + 1) / 2, (rows.length + 1) / 2, tile, gap, shapes, full);
    }

    /** "room@gx,gz" for each inferred cell ("?" for an unknown room of a full floor) and "door@gx,gz", in grid order. */
    static String describe(Result res, String[] names) {
        List<String> out = new ArrayList<>();
        for (int idx = 0; idx < res.cell().length; idx++) {
            int owner = res.cell()[idx];
            String at = "@" + idx % G + "," + idx / G;
            if (owner == ANY_ROOM) {
                out.add("?" + at);
            } else if (owner >= 0) {
                out.add((names != null && owner < names.length && names[owner] != null ? names[owner]
                        : names != null ? "#" + owner : String.valueOf((char) ('a' + owner))) + at);
            }
            if (res.door()[idx]) {
                out.add("door" + at);
            }
        }
        return String.join(" ", out);
    }
}
