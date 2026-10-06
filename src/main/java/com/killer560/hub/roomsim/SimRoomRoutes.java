package com.killer560.hub.roomsim;

import com.killer560.hub.autoroutes.Route;
import com.killer560.hub.autoroutes.RouteStore;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;

import java.util.List;
import java.util.Locale;

/**
 * Which sim rooms already have an Auto Route, for the sim's room filters' "Your routes" row ({@link SimRoomFilter})
 * and All Rooms' route sets ({@link SimRoomCycle}). The pause menu's "Next room with no routes" for a room loaded by
 * itself was replaced on 2026-10-07 by All Rooms' own next-room button: killer560 asked that a single room have no
 * next-room action at all.
 *
 * <p>killer560 (2026-10-05): "I would like a way to sort maps based off of if they have secret routes in them or
 * not, and while in a solo map an option that says something like go to a new room with 0 routes in it [...]
 * that way someone configging has a really easy way of swapping maps without going out and scrolling through the
 * long list trying to find the one they are looking for. You do not need to have puzzles, blood, green, or fairy,
 * or any 0 secret rooms in this."
 *
 * <p>Two questions, each answered from the one place the rest of the mod answers it:
 * <ul>
 *   <li><b>Is the room worth routing?</b> The room database's own entry ({@link RoomDatabase#lookupByName}, the
 *       same source as {@link SimFloorGen#typeOf}): not PUZZLE, BLOOD, ENTRANCE or FAIRY, and {@code secrets > 0}.
 *       On his library that leaves 113 of 135 - every CHAMPION room is 0 secrets, so the miniboss rooms go too.
 *       A room the database does not know is not eligible: its type and secrets are unknown.</li>
 *   <li><b>Does it have a route?</b> {@link RouteStore#forRoom}, the store Auto Routes plays from, keyed by the
 *       room database name - which is also the library's name for it (all 135 match). Routes are stored
 *       room-relative and turned by the live rotation when played ({@code RouteCoords}), so one entry covers the
 *       room at every rotation; there is no per-rotation key to check. "Has a route" means at least one NODE: a
 *       recorded path with no nodes plays nothing.</li>
 * </ul>
 */
public final class SimRoomRoutes {

    /** The "Your routes" row: any room, rooms with a route, rooms worth routing without one. */
    public enum Filter { ALL, NONE, HAS }

    private SimRoomRoutes() {
    }

    // ------------------------------------------------------------------------------------------- questions

    /**
     * Whether a room belongs in the routes view at all: a non-puzzle, non-Blood, non-Entrance, non-Fairy room with
     * at least one secret, by the room database. False while the database is still loading.
     */
    public static boolean isEligible(String roomName) {
        RoomEntry entry = RoomDatabase.lookupByName(roomName);
        if (entry == null || entry.secrets <= 0) {
            return false;
        }
        String type = entry.type == null ? "" : entry.type.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "PUZZLE", "BLOOD", "ENTRANCE", "FAIRY" -> false;
            default -> true;
        };
    }

    /** Auto Routes nodes saved for this room, 0 when it has none. */
    public static int routeNodes(String roomName) {
        Route route = RouteStore.getInstance().forRoom(roomName);
        return route == null ? 0 : route.nodes().size();
    }

    public static boolean matches(String roomName, Filter f) {
        return switch (f) {
            case ALL -> true;
            case NONE -> isEligible(roomName) && routeNodes(roomName) == 0;
            case HAS -> isEligible(roomName) && routeNodes(roomName) > 0;
        };
    }

    /**
     * The room loaded on its own right now, or null when what is loaded is a generated floor or nothing yet.
     */
    public static String currentSoloRoom() {
        if (SimState.isGeneratedFloor()) {
            return null;
        }
        List<SimRoomIndex.Placed> placed = SimRoomIndex.placed();
        return placed.size() == 1 ? placed.get(0).name() : null;
    }
}
