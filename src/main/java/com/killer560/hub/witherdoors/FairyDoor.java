package com.killer560.hub.witherdoors;

import com.killer560.hub.chunkcache.ChunkCacheManager;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.LiveMapReads;
import com.killer560.hub.livemap.autoclear.DungeonMapPathfinder;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Wither Doors' Fairy Door (cheat build). killer560, 2026-10-07: "For wither doors it should also show the door between
 * my room and Fairy on the blood rush path as a highlight till I enter Fairy" - then "or till anyone enters fairy".
 *
 * <p><b>Which door.</b> The blood-rush path is the one Auto Blood Rush walks ({@code BloodRush.nextDoor}): the room path
 * {@link DungeonMapPathfinder#findPath} gives from the room he is in to the Blood room, locked doors counted as passable.
 * If the Fairy room is on it, the door taken INTO the Fairy room is the one highlighted. Before the Blood door is on the
 * map the path is taken to the Fairy room itself, because the Fairy always generates on the Entrance-to-Blood path (wiki,
 * The Catacombs). Nothing more general than that: the "next non-wither door on the path" would be the same door on
 * Hypixel, where the Fairy's are the path's only ordinary doors, but in the sim (which builds no wither doors) it would be
 * every door. A Fairy door that is itself a wither or blood door is left to the normal highlight.
 *
 * <p><b>Until when.</b> The first of: he stands inside the Fairy room; any teammate the live map places (a loaded player
 * entity, else the map item's marker for him) stands inside it; the map item turns the Fairy room from unopened to opened
 * after this run had seen it unopened; the door's block turns to air after it had been seen solid. Then it stays clear
 * for the rest of the run ({@link LiveMapFeature#resetGeneration()} or a world change start a new one).
 *
 * <p>Everything is read from {@link DungeonLayout} (cells, door types, Blood door) and {@link DungeonLayout#doorBlock}
 * (which already carries the sim's altitude shift); no room position is worked out here.
 */
public final class FairyDoor {

    private static final Logger LOGGER = ModLog.get("killer560smod-witherdoors");
    /** Fairy's map colour id (NoammAddons {@code RoomType.fromMapColor}). */
    private static final int MAP_COLOR_FAIRY = 82;
    /** Blocks from a tile's centre that count as inside the room: the 31-wide room less its wall. */
    private static final int INSIDE = 14;
    private static final int PATH_EVERY_TICKS = 10;

    private static int door = -1;
    private static int fairyRoom = -1;
    private static int lastRoom = -1;
    private static boolean entered = false;
    private static String status = "idle";
    private static int generation = Integer.MIN_VALUE;
    private static Object level = null;
    private static boolean mapSawUnopened = false;
    private static int solidDoor = -1;
    private static int ticksToPath = 0;

    private FairyDoor() {
    }

    /** The door cell (11x11 index) to highlight, or -1. */
    public static int door() {
        return entered ? -1 : door;
    }

    /** What the Fairy Door is doing, for logs and tests: "door N (...)", "cleared: ...", or why there is none. */
    public static String status() {
        return status;
    }

    public static boolean isCleared() {
        return entered;
    }

    static void off() {
        door = -1;
        ticksToPath = 0;
        setStatus("off");
    }

    /** Client tick, only while Wither Doors may render and the Fairy Door option is on. */
    static void tick(Minecraft client) {
        int gen = LiveMapFeature.resetGeneration();
        if (gen != generation || client.level != level) {
            generation = gen;
            level = client.level;
            door = -1;
            fairyRoom = -1;
            lastRoom = -1;
            entered = false;
            mapSawUnopened = false;
            solidDoor = -1;
            ticksToPath = 0;
            setStatus("new run");
        }
        if (entered || client.player == null || client.level == null) {
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        int room = layout.currentRoom();
        if (room >= 0) {
            lastRoom = room;
        }
        int fairy = findFairy(layout);
        fairyRoom = fairy;
        if (fairy >= 0 && checkEntered(client, layout, fairy)) {
            return;
        }
        if (--ticksToPath > 0) {
            return;
        }
        ticksToPath = PATH_EVERY_TICKS;
        int next = decide(layout, fairy);
        if (next != door) {
            door = next;
            solidDoor = -1;
            WitherDoorsFeature.invalidateCache();
        }
        if (door >= 0) {
            // No room name: it would log a line every time he walks into another room.
            setStatus("door " + door + " into Fairy");
        }
    }

    private static int decide(DungeonLayout layout, int fairy) {
        if (lastRoom < 0) {
            setStatus("no room yet");
            return -1;
        }
        if (fairy < 0) {
            setStatus("no fairy room on the map");
            return -1;
        }
        int blood = layout.bloodDoor();
        int goal = -1;
        if (blood >= 0) {
            int[] resolved = DungeonMapPathfinder.resolve(layout, lastRoom, blood, true);
            if (resolved != null) {
                goal = layout.roomOfCell(resolved[1]);
            }
        }
        boolean toBlood = goal >= 0;
        if (!toBlood) {
            goal = fairy;
        }
        if (goal == lastRoom) {
            setStatus("already at " + (toBlood ? "the blood door" : "fairy"));
            return -1;
        }
        List<DungeonMapPathfinder.RoomStep> path = DungeonMapPathfinder.findPath(layout, lastRoom, goal, true);
        if (path == null) {
            setStatus("no path from " + nameOf(layout, lastRoom));
            return -1;
        }
        // RoomStep is a room and the door taken OUT of it, so the door into Fairy is the previous step's.
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i).room() == fairy) {
                int d = path.get(i - 1).door();
                int type = d >= 0 ? layout.doorType(d) : DungeonLayout.DOOR_NONE;
                if (type == DungeonLayout.DOOR_WITHER || type == DungeonLayout.DOOR_BLOOD) {
                    setStatus("fairy door " + d + " is a wither/blood door (normal highlight)");
                    return -1;
                }
                return d;
            }
        }
        setStatus("blood path avoids fairy");
        return -1;
    }

    private static boolean checkEntered(Minecraft client, DungeonLayout layout, int fairy) {
        int[] tiles = layout.tiles(fairy);
        if (inside(tiles, client.player.getX(), client.player.getZ())) {
            return clear("you entered Fairy");
        }
        for (LiveMapReads.Mate mate : LiveMapReads.teammates(client)) {
            if (inside(tiles, mate.x(), mate.z())) {
                return clear(mate.name() + " entered Fairy");
            }
        }
        if (LiveMapReads.mapItemCalibrated()) {
            int best = Integer.MAX_VALUE;
            for (int t : tiles) {
                best = Math.min(best, LiveMapReads.mapItemState(t));
            }
            if (best >= LiveMapReads.STATE_UNOPENED) {
                mapSawUnopened = true;
            } else if (mapSawUnopened) {
                return clear("the map shows Fairy opened");
            }
        }
        if (door >= 0) {
            BlockPos pos = DungeonLayout.doorBlock(door);
            if (ChunkCacheManager.isLoadedOrCached(client.level, pos)) {
                boolean air = client.level.getBlockState(pos).isAir();
                if (!air) {
                    solidDoor = door;
                } else if (solidDoor == door) {
                    return clear("the fairy door opened");
                }
            }
        }
        return false;
    }

    private static boolean inside(int[] tiles, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int t : tiles) {
            BlockPos c = DungeonLayout.cellCenter(t);
            if (Math.abs(bx - c.getX()) <= INSIDE && Math.abs(bz - c.getZ()) <= INSIDE) {
                return true;
            }
        }
        return false;
    }

    private static boolean clear(String why) {
        entered = true;
        door = -1;
        setStatus("cleared: " + why);
        WitherDoorsFeature.invalidateCache();
        return true;
    }

    /** The room the layout names Fairy: the room database's type, else the map item's Fairy colour. */
    private static int findFairy(DungeonLayout layout) {
        for (int r = 0; r < layout.roomCount(); r++) {
            var entry = layout.entry(r);
            if (entry != null && entry.type != null) {
                if ("FAIRY".equals(entry.type.toUpperCase(Locale.ROOT))) {
                    return r;
                }
                continue;
            }
            for (int t : layout.tiles(r)) {
                if (LiveMapReads.mapItemRoomColor(t) == MAP_COLOR_FAIRY) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static String nameOf(DungeonLayout layout, int room) {
        String n = layout.name(room);
        return n == null ? "?" : n;
    }

    private static void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
            LOGGER.info("[WitherDoors] Fairy door: {}", s);
        }
    }
}
