package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rooms that still hold the key to a shut door, so no automation insta clears one (killer560, 2026-10-20: "for any
 * auto clear it needs to be very careful about etherwarping into the room before any unopened wither door too early or
 * too fast. Any room before a wither door is insta cleared, then you can no longer get a wither key. Once the wither
 * door is opened, it is no issue at all. It can insta clear any room that isn't leading to a wither key or blood key.").
 *
 * <p><b>Which rooms.</b> For every LOCKED wither door and the locked blood door on the map: the room on its near side -
 * the one of the two rooms it joins that is fewer rooms from the Entrance over the map's door graph (locked doors
 * passable; both on a tie) - while that room is not cleared on the map. The key drops at that room's last starred death
 * (his runs, 2026-10-20 22:04 and 22:09: Diagonal's Wither Key lay a few blocks from its wither door the moment its last
 * mob died), so a room the map shows cleared has already given its key (or lost it), and an open door needs none.
 *
 * <p><b>When it is at risk.</b> The insta-clear tracker's evidence from his runs (insta-clear.json, 2026-10-20): a room
 * etherwarped into while the map showed it UNDISCOVERED insta cleared 13 times of 15, UNOPENED 15 of 74, but once it was
 * DISCOVERED (someone had been in it) 1 of 75 - and no walk-in has a real insta clear on record (the five counted flipped
 * 3.6 to 12.5 s later, a kill's timing, where every real one flipped in 150-900 ms). So a key room is never warped into
 * until it has been entered: {@link #mustNotWarpInto}. "Entered" is the map's own state (discovered, cleared, green or
 * failed on any tile - a teammate's visit counts), or him having stood in it this run ({@link #observe}).
 *
 * <p>Client thread. Read-only: it sends nothing.
 */
public final class KeyRooms {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoclear");

    /** A room the key to {@code door} is still in: {@code blood} when it is the blood door. */
    public record Guard(int room, int door, boolean blood) {
    }

    private KeyRooms() {
    }

    // ---- rooms he has stood in this run (by name), for a map that cannot say (the sim, before the map item) ----
    private static final Set<String> STOOD_IN = new HashSet<>();
    private static Object stoodLevel;
    private static int stoodGeneration = Integer.MIN_VALUE;

    /** Every client tick, in a dungeon: the room he stands in counts as entered from now on. Cheap (a cached layout). */
    public static void observe(Minecraft client) {
        if (client.level != stoodLevel || LiveMapFeature.resetGeneration() != stoodGeneration) {
            // A new run (a new world, or a new floor in the same one - the sim's) has its own rooms.
            stoodLevel = client.level;
            stoodGeneration = LiveMapFeature.resetGeneration();
            STOOD_IN.clear();
        }
        if (client.player == null || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        int here = layout.roomAtWorld(client.player.getX(), client.player.getZ());
        String name = here < 0 ? null : layout.name(here);
        if (name == null || "Unknown".equals(name) || !inside(layout, here, client.player.getX(), client.player.getZ())) {
            return;
        }
        if (STOOD_IN.add(name) && guardOf(layout, here) != null) {
            LOGGER.info("[AutoClear] key room: {} entered ({} at {}, {}) - etherwarps into it are safe from now on", name,
                    client.player.onGround() ? "standing" : "in the air",
                    String.format(Locale.US, "%.1f", client.player.getX()),
                    String.format(Locale.US, "%.1f", client.player.getZ()));
        }
    }

    /** Strictly inside one of the room's tiles (not the shared gap block of a doorway, which roomAtWorld splits). */
    static boolean inside(DungeonLayout layout, int room, double x, double z) {
        for (int t : layout.tiles(room)) {
            net.minecraft.core.BlockPos c = DungeonLayout.cellCenter(t);
            if (x >= c.getX() - 15 && x < c.getX() + 16 && z >= c.getZ() - 15 && z < c.getZ() + 16) {
                return true;
            }
        }
        return false;
    }

    /** Every room holding a key to a shut door, with the door. */
    public static List<Guard> guards(DungeonLayout layout) {
        List<Guard> out = new ArrayList<>();
        if (!enabled) {
            return out;
        }
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        int shutWither = 0;
        for (int idx = 0; idx < cells; idx++) {
            if (layout.doorType(idx) == DungeonLayout.DOOR_WITHER && layout.isLocked(idx)) {
                shutWither++;
            }
        }
        // Keys in hand for every shut door: none of the keys still lying in rooms is needed any more.
        boolean witherCovered = shutWither > 0 && com.killer560.hub.doorkeys.DungeonKeys.witherKeys() >= shutWither;
        boolean bloodCovered = com.killer560.hub.doorkeys.DungeonKeys.bloodKey() == 1;
        int entrance = AutoClearTargets.entranceRoom(layout);
        int[] dist = AutoClearTargets.bfsDistances(layout, entrance >= 0 ? entrance : layout.currentRoom());
        for (int idx = 0; idx < cells; idx++) {
            int type = layout.doorType(idx);
            if ((type != DungeonLayout.DOOR_WITHER && type != DungeonLayout.DOOR_BLOOD) || !layout.isLocked(idx)
                    || (type == DungeonLayout.DOOR_WITHER ? witherCovered : bloodCovered)) {
                continue;
            }
            int[] sides = sides(layout, idx);
            int a = sides[0];
            int b = sides[1];
            int da = a < 0 ? Integer.MAX_VALUE : dist[a];
            int db = b < 0 ? Integer.MAX_VALUE : dist[b];
            for (int r : new int[]{a, b}) {
                if (r < 0) {
                    continue;
                }
                int dr = r == a ? da : db;
                int other = r == a ? db : da;
                if (dr > other || isClearedOnMap(layout, r) || !mayHoldKey(layout, r)) {
                    continue;
                }
                out.add(new Guard(r, idx, type == DungeonLayout.DOOR_BLOOD));
            }
        }
        return out;
    }

    /** The guard on {@code room}, or null when no shut door's key is in it. */
    public static Guard guardOf(DungeonLayout layout, int room) {
        if (room < 0) {
            return null;
        }
        for (Guard g : guards(layout)) {
            if (g.room() == room) {
                return g;
            }
        }
        return null;
    }

    /** Whether anyone has been in {@code room}: the map says so (any tile discovered or further), or he stood in it. */
    public static boolean entered(DungeonLayout layout, int room) {
        if (room < 0) {
            return false;
        }
        String name = layout.name(room);
        if (name != null && STOOD_IN.contains(name)) {
            return true;
        }
        for (int t : layout.tiles(room)) {
            int s = LiveMapFeature.mapStateAt(t);
            // GREEN 0, CLEARED 1, DISCOVERED 2, FAILED 3; UNOPENED 4 and UNDISCOVERED 5 are rooms nobody has been in.
            if (s >= 0 && s <= LiveMapFeature.MAP_FAILED) {
                return true;
            }
        }
        return false;
    }

    /** A key room nobody has been in yet: never etherwarp into it (walk in through its door). */
    public static boolean mustNotWarpInto(DungeonLayout layout, int room) {
        return room >= 0 && !entered(layout, room) && guardOf(layout, room) != null;
    }

    /**
     * The first room on the room path from {@code from} to {@code to} (open doors only), {@code to} itself excluded, that
     * {@link #mustNotWarpInto} - a map trip to {@code to} would land in it on the way. -1 when none (or no path).
     */
    public static int unenteredKeyRoomOnTheWay(DungeonLayout layout, int from, int to) {
        if (from < 0 || to < 0 || from == to) {
            return -1;
        }
        List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, from, to, false);
        if (path == null) {
            return -1;
        }
        for (DungeonMapPathfinder.RoomStep step : path) {
            int r = step.room();
            if (r != from && r != to && mustNotWarpInto(layout, r)) {
                return r;
            }
        }
        return -1;
    }

    /** A short reason for logs: "holds the key to the shut wither door 39". */
    public static String describe(Guard g) {
        return g == null ? "no key" : "holds the key to the shut " + (g.blood() ? "blood" : "wither") + " door " + g.door();
    }

    /** The two rooms a door cell joins ({@code -1} for a side with none). */
    static int[] sides(DungeonLayout layout, int door) {
        int x = door % DungeonLayout.GRID;
        int z = door / DungeonLayout.GRID;
        boolean alongX = x % 2 != 0;
        int i1 = alongX ? z * DungeonLayout.GRID + (x - 1) : (z - 1) * DungeonLayout.GRID + x;
        int i2 = alongX ? z * DungeonLayout.GRID + (x + 1) : (z + 1) * DungeonLayout.GRID + x;
        int n = DungeonLayout.GRID * DungeonLayout.GRID;
        return new int[]{i1 >= 0 && i1 < n ? layout.roomOfCell(i1) : -1, i2 >= 0 && i2 < n ? layout.roomOfCell(i2) : -1};
    }

    /** The cell of {@code door}'s side that belongs to {@code room}, or -1. */
    static int sideCell(DungeonLayout layout, int door, int room) {
        int x = door % DungeonLayout.GRID;
        int z = door / DungeonLayout.GRID;
        boolean alongX = x % 2 != 0;
        int i1 = alongX ? z * DungeonLayout.GRID + (x - 1) : (z - 1) * DungeonLayout.GRID + x;
        int i2 = alongX ? z * DungeonLayout.GRID + (x + 1) : (z + 1) * DungeonLayout.GRID + x;
        int n = DungeonLayout.GRID * DungeonLayout.GRID;
        if (i1 >= 0 && i1 < n && layout.roomOfCell(i1) == room) {
            return i1;
        }
        if (i2 >= 0 && i2 < n && layout.roomOfCell(i2) == room) {
            return i2;
        }
        return -1;
    }

    /** A room whose mobs can drop a key: not the Entrance, a puzzle, the Blood or Fairy room (an unknown one may). */
    private static boolean mayHoldKey(DungeonLayout layout, int room) {
        RoomEntry e = layout.entry(room);
        if (e == null || e.type == null) {
            return true;
        }
        String t = e.type.toUpperCase(Locale.ROOT);
        return !t.equals("ENTRANCE") && !t.equals("PUZZLE") && !t.equals("BLOOD") && !t.equals("FAIRY");
    }

    /** Cleared on the map (or the sim's own state) - the chest rule does not count: the room's mobs may still stand. */
    private static boolean isClearedOnMap(DungeonLayout layout, int room) {
        return AutoClearTargets.isClearedOnMap(layout, room);
    }

    // ---- test hooks (testkit, reflection) ----

    /** Off: no room is a key room (the testkit's control case, to see what the rule prevents). On by default. */
    private static volatile boolean enabled = true;

    public static void testSetEnabled(boolean on) {
        enabled = on;
        LOGGER.info("[AutoClear] key rooms {} (test)", on ? "on" : "OFF");
    }

    /**
     * {@code leaf|front|door} for every room with exactly one door to another room, an ordinary one, whose other side is a
     * different room: turning that door into a wither door makes {@code front} the leaf's key room.
     */
    public static List<String> testLeafPairs() {
        DungeonLayout layout = DungeonLayout.capture();
        List<String> out = new ArrayList<>();
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        for (int r = 0; r < layout.roomCount(); r++) {
            List<int[]> doors = new ArrayList<>();
            for (int t : layout.tiles(r)) {
                int x = t % DungeonLayout.GRID;
                int z = t / DungeonLayout.GRID;
                for (int[] d : dirs) {
                    int nx = x + d[0] * 2;
                    int nz = z + d[1] * 2;
                    if (nx < 0 || nx > 10 || nz < 0 || nz > 10) {
                        continue;
                    }
                    int door = (z + d[1]) * DungeonLayout.GRID + (x + d[0]);
                    int next = layout.roomOfCell(nz * DungeonLayout.GRID + nx);
                    if (layout.isDoor(door) && next >= 0 && next != r) {
                        doors.add(new int[]{door, next, layout.doorType(door)});
                    }
                }
            }
            if (doors.size() == 1 && doors.get(0)[2] == DungeonLayout.DOOR_NORMAL) {
                out.add(layout.name(r) + "|" + layout.name(doors.get(0)[1]) + "|" + doors.get(0)[0]);
            }
        }
        return out;
    }

    /** The room path (open doors only) between two rooms by name, or an empty list. */
    public static List<String> testRoomPath(String from, String to) {
        DungeonLayout layout = DungeonLayout.capture();
        int a = AutoClearTargets.roomByName(layout, from);
        int b = AutoClearTargets.roomByName(layout, to);
        List<String> out = new ArrayList<>();
        List<DungeonMapPathfinder.RoomStep> path = a < 0 || b < 0 ? null : DungeonMapPathfinder.findPath(layout, a, b, false);
        if (path != null) {
            for (DungeonMapPathfinder.RoomStep s : path) {
                out.add(layout.name(s.room()));
            }
        }
        return out;
    }

    /** {@code room|door|wither-or-blood|entered} for every guard, by room name. */
    public static List<String> testGuards() {
        DungeonLayout layout = DungeonLayout.capture();
        List<String> out = new ArrayList<>();
        for (Guard g : guards(layout)) {
            out.add(layout.name(g.room()) + "|" + g.door() + "|" + (g.blood() ? "blood" : "wither") + "|"
                    + entered(layout, g.room()));
        }
        return out;
    }

    public static List<String> testStoodIn() {
        return new ArrayList<>(STOOD_IN);
    }
}
