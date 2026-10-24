package com.killer560.hub.livemap;

import com.killer560.hub.roomdatabase.RoomEntry;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What the dungeon map in his hotbar shows - and nothing more - for the features that may only know what that map
 * knows: Wither Doors without Show All Doors (killer560, 2026-10-15: "it will only show the door it can see from the
 * normal map"), the Fairy door inference, and Predictive Rooms' "which tiles are already opened".
 *
 * <p>On Hypixel every answer is {@link DungeonMapScanner}'s reading of the map item. Nothing here reads the world.
 *
 * <p><b>The sim</b> sends no map item, so without a stand-in every one of those features would show nothing in there.
 * The stand-in follows the rule the real map follows: the Entrance and every room someone has stood in are opened,
 * a room joined to an opened room by a door is shown unopened, and a door is shown once one of its rooms is opened.
 * A door's color is the door the sim built (coal = wither, red terracotta = blood), except the door into a Fairy room
 * nobody has entered, which Hypixel draws black although the doorway is empty (his F1, 2026-10-08_15.48.34) - so the
 * stand-in draws it black too. Sim only: {@link #emulated()} is false whenever the sim is not active.
 */
public final class HeldMap {

    public static final int STATE_OPENED = DungeonMapScanner.STATE_DISCOVERED;
    public static final int STATE_UNOPENED = DungeonMapScanner.STATE_UNOPENED;
    public static final int STATE_NOT_SHOWN = DungeonMapScanner.STATE_UNDISCOVERED;

    private static final int GRID = DungeonLayout.GRID;
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-livemap");

    /** Sim stand-in: rooms (by layout room id) someone has stood in this floor. */
    private static final Set<Integer> SIM_VISITED = new HashSet<>();
    private static int simGeneration = Integer.MIN_VALUE;
    private static int simTick = Integer.MIN_VALUE;
    /** Bumped whenever {@link #SIM_VISITED} changes (the Dungeon Map's draw cache keys on it). */
    private static int simVersion = 0;

    private static boolean simAdd(int room) {
        if (SIM_VISITED.add(room)) {
            simVersion++;
            return true;
        }
        return false;
    }

    /** What the sim's stand-in answers can have changed since the last different value. Refreshes it first, as every
     *  question asked of it does; on Hypixel (not emulated) it never runs the refresh. */
    static int drawVersion() {
        if (emulated()) {
            simRefresh();
        }
        return simVersion;
    }

    private HeldMap() {
    }

    /** Whether there is a map to read: the map item is calibrated, or the sim's stand-in is up. */
    public static boolean available() {
        return DungeonMapScanner.isCalibrated() || emulated();
    }

    /** The sim's stand-in is answering (sim active and no real map item read). */
    public static boolean emulated() {
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        boolean calibrated = DungeonMapScanner.isCalibrated();
        boolean emulated = sim && !calibrated;
        // Asked every frame: logged only when the source of the map's answers changes.
        int source = emulated ? 1 : calibrated ? 2 : 0;
        if (source != lastSource) {
            lastSource = source;
            LOGGER.info("[HeldMap] map answers now come from {} (sim={} mapCalibrated={})",
                    source == 1 ? "the sim stand-in" : source == 2 ? "the map item" : "nothing (no map)", sim, calibrated);
        }
        return emulated;
    }

    /** The answer source last logged (logging only): -1 none yet, 0 no map, 1 sim stand-in, 2 map item. */
    private static int lastSource = -1;

    /** A room tile's state as the map shows it: {@link #STATE_OPENED} or lower (green, cleared, failed counted as
     *  opened: anything below {@link #STATE_UNOPENED}), {@link #STATE_UNOPENED}, or {@link #STATE_NOT_SHOWN}. */
    public static int roomState(int tile) {
        if (tile < 0 || tile >= GRID * GRID) {
            return STATE_NOT_SHOWN;
        }
        if (emulated()) {
            return simRoomState(tile);
        }
        if (DungeonMapScanner.kindAt(tile) != DungeonMapScanner.KIND_ROOM) {
            return STATE_NOT_SHOWN;
        }
        return DungeonMapScanner.stateAt(tile);
    }

    public static boolean roomOpened(int tile) {
        return roomState(tile) < STATE_UNOPENED;
    }

    public static boolean roomShown(int tile) {
        return roomState(tile) != STATE_NOT_SHOWN;
    }

    /** A door cell as the map shows it: a {@link DungeonLayout} DOOR_* constant, DOOR_NONE when it shows none. */
    public static int doorType(int idx) {
        if (idx < 0 || idx >= GRID * GRID || !isDoorCell(idx)) {
            return DungeonLayout.DOOR_NONE;
        }
        if (emulated()) {
            return simDoorType(idx);
        }
        LiveMapFeature.Tile t = DungeonMapScanner.doorTileAt(idx);
        return switch (t) {
            case DOOR_WITHER -> DungeonLayout.DOOR_WITHER;
            case DOOR_BLOOD -> DungeonLayout.DOOR_BLOOD;
            case DOOR_ENTRANCE -> DungeonLayout.DOOR_ENTRANCE;
            case DOOR_NORMAL -> DungeonLayout.DOOR_NORMAL;
            default -> DungeonLayout.DOOR_NONE;
        };
    }

    /** Whether the map joins the two tiles either side of connector cell {@code idx} into one room. */
    public static boolean joined(int idx) {
        if (idx < 0 || idx >= GRID * GRID || !isDoorCell(idx)) {
            return false;
        }
        if (emulated()) {
            DungeonLayout layout = DungeonLayout.current();
            int[] t = sides(idx);
            int a = layout.roomOfCell(t[0]);
            return a >= 0 && a == layout.roomOfCell(t[1]) && layout.roomOfCell(idx) == a && simRoomState(t[0]) != STATE_NOT_SHOWN;
        }
        return DungeonMapScanner.kindAt(idx) == DungeonMapScanner.KIND_SEPARATOR;
    }

    /** An OPENED tile's room type as the map colors it ("FAIRY", "PUZZLE", "TRAP", "CHAMPION", "BLOOD", "ENTRANCE",
     *  "RARE", "NORMAL"), or null for a tile that is not opened (an unopened room is gray whatever it is). */
    public static String roomType(int tile) {
        if (!roomOpened(tile)) {
            return null;
        }
        if (emulated()) {
            DungeonLayout layout = DungeonLayout.current();
            RoomEntry e = layout.entry(layout.roomOfCell(tile));
            return e == null || e.type == null ? "NORMAL" : e.type.toUpperCase(Locale.ROOT);
        }
        return switch (DungeonMapScanner.roomColorAt(tile)) {
            case 18 -> "BLOOD";
            case 82 -> "FAIRY";
            case 34 -> "RARE";
            case 74 -> "CHAMPION";
            case 66 -> "PUZZLE";
            case 62 -> "TRAP";
            case 30 -> "ENTRANCE";
            default -> "NORMAL";
        };
    }

    /** Room slots {across, down} of the floor being played; the whole 6x6 in the sim, which lays floors out anywhere. */
    public static int[] floorTiles() {
        if (com.killer560.hub.roomsim.SimState.isActive()) {
            return new int[]{6, 6};
        }
        return MapPainter.currentFloorRooms();
    }

    /** The two room tiles either side of door cell {@code idx}: {west, east} or {north, south}. */
    public static int[] sides(int idx) {
        int gx = idx % GRID;
        int gz = idx / GRID;
        if (gx % 2 == 1) {
            return new int[]{gz * GRID + gx - 1, gz * GRID + gx + 1};
        }
        return new int[]{(gz - 1) * GRID + gx, (gz + 1) * GRID + gx};
    }

    /** A connector cell: exactly one of its grid coordinates odd. */
    public static boolean isDoorCell(int idx) {
        int gx = idx % GRID;
        int gz = idx / GRID;
        return (gx % 2 == 1) != (gz % 2 == 1);
    }

    // ------------------------------------------------------------------------------------------- the sim's stand-in

    /** Rooms someone stands in become opened; ticked from whoever asks first each client tick. */
    private static void simRefresh() {
        int tick = LiveMapFeature.tickCount();
        int gen = LiveMapFeature.resetGeneration();
        if (gen != simGeneration) {
            LOGGER.info("[HeldMap] sim: new floor generation {} - forgetting {} opened room(s)", gen, SIM_VISITED.size());
            simGeneration = gen;
            SIM_VISITED.clear();
            simVersion++;
        }
        if (tick == simTick) {
            return;
        }
        simTick = tick;
        Minecraft client = Minecraft.getInstance();
        DungeonLayout layout = DungeonLayout.current();
        for (int r = 0; r < layout.roomCount(); r++) {
            RoomEntry e = layout.entry(r);
            String n = layout.name(r);
            if ((e != null && "ENTRANCE".equalsIgnoreCase(e.type)) || "Entrance".equalsIgnoreCase(n)) {
                if (simAdd(r)) {
                    LOGGER.info("[HeldMap] sim: {} (room {}) opened as the Entrance", n, r);
                }
            }
        }
        if (client.player != null && layout.currentRoom() >= 0 && insideRoom(layout, layout.currentRoom(),
                client.player.getX(), client.player.getZ()) && simAdd(layout.currentRoom())) {
            LOGGER.info("[HeldMap] sim: {} opened (you at {}, {})", layout.name(layout.currentRoom()),
                    Math.round(client.player.getX()), Math.round(client.player.getZ()));
        }
        for (LiveMapReads.Mate m : LiveMapReads.teammates(client)) {
            int r = layout.roomAtWorld(m.x(), m.z());
            if (r >= 0 && insideRoom(layout, r, m.x(), m.z()) && simAdd(r)) {
                LOGGER.info("[HeldMap] sim: {} opened ({} at {}, {})", layout.name(r), m.name(), Math.round(m.x()),
                        Math.round(m.z()));
            }
        }
    }

    /** Inside the room's walls, not just in its doorway: a step into the gap does not open the room on Hypixel. */
    private static boolean insideRoom(DungeonLayout layout, int room, double x, double z) {
        for (int t : layout.tiles(room)) {
            net.minecraft.core.BlockPos c = DungeonLayout.cellCenter(t);
            if (Math.abs(Math.floor(x) - c.getX()) <= 14 && Math.abs(Math.floor(z) - c.getZ()) <= 14) {
                return true;
            }
        }
        return false;
    }

    private static boolean simVisited(int room) {
        simRefresh();
        return room >= 0 && SIM_VISITED.contains(room);
    }

    private static int simRoomState(int tile) {
        DungeonLayout layout = DungeonLayout.current();
        int r = layout.roomOfCell(tile);
        if (r < 0) {
            return STATE_NOT_SHOWN;
        }
        if (simVisited(r)) {
            return STATE_OPENED;
        }
        // Shown unopened when a door joins it to an opened room.
        for (int t : layout.tiles(r)) {
            int gx = t % GRID;
            int gz = t / GRID;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int nx = gx + 2 * d[0];
                int nz = gz + 2 * d[1];
                if (nx < 0 || nz < 0 || nx >= GRID || nz >= GRID) {
                    continue;
                }
                int door = (gz + d[1]) * GRID + gx + d[0];
                int other = layout.roomOfCell(nz * GRID + nx);
                if (other >= 0 && other != r && layout.isDoor(door) && simVisited(other)) {
                    return STATE_UNOPENED;
                }
            }
        }
        return STATE_NOT_SHOWN;
    }

    private static int simDoorType(int idx) {
        DungeonLayout layout = DungeonLayout.current();
        if (!layout.isDoor(idx)) {
            return DungeonLayout.DOOR_NONE;
        }
        int[] s = sides(idx);
        int a = layout.roomOfCell(s[0]);
        int b = layout.roomOfCell(s[1]);
        boolean aOpen = simVisited(a);
        boolean bOpen = simVisited(b);
        if (!aOpen && !bOpen) {
            return DungeonLayout.DOOR_NONE;
        }
        int type = layout.doorType(idx);
        if (type == DungeonLayout.DOOR_WITHER) {
            // An opened wither door turns the color of its rooms on the map.
            return layout.isLocked(idx) ? DungeonLayout.DOOR_WITHER : DungeonLayout.DOOR_NORMAL;
        }
        if (type == DungeonLayout.DOOR_NORMAL && com.killer560.hub.roomsim.SimWitherDoors.isTheoretical(idx)) {
            int fairy = isFairy(layout, a) ? a : isFairy(layout, b) ? b : -1;
            if (fairy >= 0 && !simVisited(fairy)) {
                return DungeonLayout.DOOR_WITHER;
            }
        }
        return type;
    }

    private static boolean isFairy(DungeonLayout layout, int room) {
        if (room < 0) {
            return false;
        }
        RoomEntry e = layout.entry(room);
        return (e != null && "FAIRY".equalsIgnoreCase(e.type)) || "Fairy".equalsIgnoreCase(layout.name(room));
    }

    /** Test hook: forget who has stood where in the sim (a new run without rebuilding the floor). */
    public static void resetSimVisits() {
        SIM_VISITED.clear();
        simVersion++;
        simTick = Integer.MIN_VALUE;
    }

    /** Test hook: the sim stand-in counts layout room {@code room} as stood in (opened). */
    public static void simVisit(int room) {
        simRefresh();
        simAdd(room);
    }

    /** Test hook: the sim stand-in's opened rooms, by layout room id. */
    public static int[] simVisitedRooms() {
        simRefresh();
        return SIM_VISITED.stream().mapToInt(Integer::intValue).sorted().toArray();
    }
}
