package com.killer560.hub.roomsim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-room map states the sim decides for itself - today, "this puzzle was failed".
 *
 * <h2>Why this exists</h2>
 *
 * <p>On Hypixel a failed puzzle turns its room RED on the dungeon map, and that red square is how you notice
 * from across the floor that someone needs an Architect's First Draft. The mod already draws that colour: it
 * comes from {@code DungeonMapScanner.STATE_FAILED}, which the scanner reads off the vanilla dungeon map ITEM.
 *
 * <p>A singleplayer sim has no map item, so the scanner is never calibrated and every room paints as plain
 * "discovered" - there was no way for a failed sim puzzle to show at all. killer560 asked for it on the Quiz
 * ("the map image for the room should turn red to show that I failed the puzzle. If i then use an
 * archetechs draft itll fix it and restart it"), and the same thing is wanted of any puzzle that can be
 * failed, so it lives here rather than inside one puzzle.
 *
 * <p>Keyed by room NAME, not by grid cell, because that is what the puzzles know about themselves and because
 * a room occupies several cells - {@code MapPainter} already resolves a whole {@code RoomGroup} to one
 * {@code RoomEntry}, so the name is the natural join. A floor cannot hold two rooms of the same name.
 *
 * <p>Read on the render thread and written from puzzle logic on the client tick, hence the concurrent map.
 * Cleared whenever a floor is built, by {@link SimRoomPuzzles}.
 */
public final class SimRoomState {

    /** Room name to a {@code DungeonMapScanner.STATE_*} value. */
    private static final Map<String, Integer> STATES = new ConcurrentHashMap<>();

    /** {@code DungeonMapScanner.STATE_FAILED}. Duplicated as a literal because that class is package-private
     *  to {@code livemap}; the one place the two must agree is {@link #stateFor}'s contract, and the map
     *  feature's own test of this value is right beside it. */
    private static final int STATE_FAILED = 3;

    private SimRoomState() {
    }

    /** Every room back to no opinion - for a new floor or leaving the sim. */
    public static void clear() {
        STATES.clear();
    }

    /** This room's puzzle was failed: paint it red. */
    public static void markFailed(String roomName) {
        if (roomName != null && !roomName.isBlank()) {
            STATES.put(roomName, STATE_FAILED);
        }
    }

    /** This room's puzzle was restarted (an Architect's First Draft, or a reset): back to normal. */
    public static void clearRoom(String roomName) {
        if (roomName != null) {
            STATES.remove(roomName);
        }
    }

    public static boolean isFailed(String roomName) {
        return roomName != null && STATES.getOrDefault(roomName, -1) == STATE_FAILED;
    }

    /**
     * @return the {@code DungeonMapScanner.STATE_*} the sim wants this room painted as, or -1 when it has no
     *         opinion and the map should decide for itself
     */
    public static int stateFor(String roomName) {
        return roomName == null ? -1 : STATES.getOrDefault(roomName, -1);
    }
}
