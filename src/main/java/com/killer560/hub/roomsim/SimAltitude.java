package com.killer560.hub.roomsim;

import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * How high off the floor of the world a floor is built.
 *
 * <p>killer560 (2026-09-29): "can you make it so not all builds are created at build limit it likes to send a
 * chat message to me when that happens and it can break builds. Once it generates the map that it wants to
 * build have it find the lowest block and put that 1 block above the void. If it wants to generate high blaze
 * then have it do the top block one below the build limit."
 *
 * <p>Rooms are captured between y 60 and y 140 and were pasted at exactly those coordinates, whatever world
 * they went into. That is fine in a world 384 blocks tall and not fine in a shorter one, and it wastes the
 * whole of the space below: a room whose ceiling runs high has nothing under it and no room over it.
 *
 * <p>So the whole floor is shifted once, by a single offset that everything in the sim adds. Normally the
 * lowest block of the whole map lands one above the void, which leaves every block of headroom the world has
 * for the tall rooms. When Higher Blaze is on the floor the map is aligned the other way instead and its top
 * block sits one under the build limit, because that room is entered near its ceiling.
 *
 * <p><b>One offset for the whole map, not one per room.</b> Rooms have to line up with each other - a doorway
 * is cut between two of them at a y found by searching, and secrets, mobs and the player's landing spot are
 * all measured from the same capture. Shifting rooms independently would leave doorways in mid-air.
 *
 * <p>The world's real limits are read from the level rather than assumed. The sim world is a flat overworld
 * today, so -64 to 319, but that is a property of how the world was created and this is the only place that
 * should care.
 */
public final class SimAltitude {

    /** The room that is entered near its ceiling, so a floor holding it is aligned to the top instead. */
    private static final String TOP_ALIGNED_ROOM = "higher blaze";

    private static int offset;

    /**
     * The offset the floor that is being REPLACED was built at.
     *
     * <p>The clear has to wipe the old floor, which is wherever the old offset put it - not where the new one
     * will put the next. Getting this wrong leaves the previous map standing in a band the new one does not
     * reach, which is the same class of bug as the touched-bounds one that left chests behind.
     */
    private static int previous;

    private SimAltitude() {
    }

    /** How far down (or up) the whole floor is shifted from its captured coordinates. */
    public static int offset() {
        return offset;
    }

    /** A captured y in the world the floor was built into. */
    public static int toWorld(int capturedY) {
        return capturedY + offset;
    }

    /** The world y the lowest captured block can occupy. */
    public static int minWorldY() {
        return RoomLibrary.MIN_Y + offset;
    }

    /** The world y the highest captured block can occupy. */
    public static int maxWorldY() {
        return RoomLibrary.MAX_Y + offset;
    }

    /** Back to the capture's own coordinates, for anything holding a world y. */
    public static int toCaptured(int worldY) {
        return worldY - offset;
    }

    /** The band the floor being replaced occupies. */
    public static int previousMinWorldY() {
        return RoomLibrary.MIN_Y + previous;
    }

    public static int previousMaxWorldY() {
        return RoomLibrary.MAX_Y + previous;
    }

    /** Drops the shift. A floor that has not been planned builds where it always did. */
    public static void reset() {
        previous = offset;
        offset = 0;
    }

    /**
     * Works out the shift for a map, from the rooms that are actually in it.
     *
     * <p>Called once, before anything is pasted, because every other coordinate in the build is derived from
     * this one.
     *
     * @return the offset chosen
     */
    public static int plan(ServerLevel level, String[] roomNames) {
        previous = offset;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        boolean topAligned = false;
        for (String name : roomNames) {
            RoomLibrary.Room room = RoomLibrary.get(name);
            if (room == null) {
                continue;
            }
            lowest = Math.min(lowest, room.contentMinY());
            highest = Math.max(highest, room.contentMaxY());
            if (TOP_ALIGNED_ROOM.equals(name.toLowerCase(Locale.ROOT))) {
                topAligned = true;
            }
        }
        if (lowest > highest) {
            offset = 0;   // nothing to measure - build where the capture says
            return offset;
        }
        // Never so far that the map will not fit: a world shorter than the floor is tall would otherwise be
        // given an offset that puts half of it outside, and SectionWriter drops those blocks silently.
        if (highest - lowest > level.getMaxY() - level.getMinY() - 2) {
            offset = 0;
            return offset;
        }
        offset = topAligned
                ? (level.getMaxY() - 1) - highest
                : (level.getMinY() + 1) - lowest;
        return offset;
    }
}
