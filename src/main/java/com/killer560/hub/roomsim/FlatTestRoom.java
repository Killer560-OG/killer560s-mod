package com.killer560.hub.roomsim;

/**
 * A room built in code, so the sim can be tested before a single real room has been scanned.
 *
 * <p>killer560 (2026-09-28): "you can create your own room call it flat and make it have one of each
 * secret/lever and what not to get stuff tested with and working [...] The only difference between that and a
 * scan is the rebuild so the flat one would be a perfect one for you to test and then we can delete it once I
 * start actually scanning rooms in." He is right that it is the same thing downstream: {@link RoomPlacer} cannot
 * tell a synthetic room from a captured one, so everything after capture is exercised by this.
 *
 * <p><b>It is built to make bugs visible, not to look like a dungeon.</b> Three deliberate choices:
 *
 * <ul>
 *   <li><b>It is asymmetric.</b> A marker pillar in ONE corner and a stripe along ONE wall. A rotation bug in a
 *       symmetric room looks exactly like a working one, which is the worst possible outcome for the piece of
 *       code most likely to be wrong.</li>
 *   <li><b>It contains directional blocks</b> - stairs facing a known way, a wall lever, a ladder. Rotating a
 *       room means rotating block STATES as well as positions, and a room of plain cubes would never catch a
 *       placer that moved blocks correctly and left every stair pointing the original way.</li>
 *   <li><b>It has the things the sim has to support</b> - a chest and a lever for secrets, a crypt wall for
 *       superboom, a pillar to etherwarp onto, a gap to cross, and a low ceiling over one corner so etherwarp's
 *       two-air-above rule has something to refuse.</li>
 * </ul>
 *
 * <p>Deliberately never saved to disk. It is regenerated on demand, so it cannot end up in the shipped room
 * library by accident and there is nothing to clean up when he starts scanning for real.
 */
public final class FlatTestRoom {

    public static final String NAME = "flat";

    /** One tile, same as the smallest real room. */
    private static final int SIZE = RoomLibrary.TILE;

    /** Dungeon floors sit at y 69, so the test room sits where a real one would. */
    private static final int FLOOR_Y = 69;
    private static final int WALL_TOP = FLOOR_Y + 5;

    private static final String STONE = "minecraft:stone";
    private static final String BRICKS = "minecraft:stone_bricks";
    private static final String CRACKED = "minecraft:cracked_stone_bricks";
    private static final String CHEST = "minecraft:chest[facing=north,type=single]";
    private static final String LEVER = "minecraft:lever[face=wall,facing=east,powered=false]";
    // Full block STATES, not bare ids. The palette holds states now, so these carry a known facing - which is
    // the only reason the directional blocks in here test anything. A stair written as a bare id comes back
    // pointing whichever way its default state points, and would look identical whether rotation worked or not.
    private static final String STAIRS = "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight]";
    private static final String LADDER = "minecraft:ladder[facing=south]";
    private static final String GOLD = "minecraft:gold_block";
    private static final String AIR = "minecraft:air";

    private FlatTestRoom() {
    }

    /** Builds it (replacing any previous copy) and returns it. */
    public static RoomLibrary.Room build() {
        RoomLibrary.Room r = RoomLibrary.createTestRoom(NAME, SIZE, SIZE);

        // Floor and a five-high wall around the edge, so the room has real boundaries to path against.
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                r.set(x, FLOOR_Y, z, BRICKS);
                boolean edge = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                if (edge) {
                    for (int y = FLOOR_Y + 1; y <= WALL_TOP; y++) {
                        r.set(x, y, z, STONE);
                    }
                }
            }
        }

        // THE ORIENTATION MARKER. A gold pillar in one corner only. If a placed room has this anywhere but the
        // corner the map code says, the rotation is wrong - and you can see that from across the room.
        for (int y = FLOOR_Y + 1; y <= WALL_TOP; y++) {
            r.set(2, y, 2, GOLD);
        }
        // A gold stripe along one wall as well, so a 180 degree error is as obvious as a 90 degree one: a single
        // corner marker looks the same to a glance whichever diagonal it is on.
        for (int x = 2; x < SIZE - 2; x++) {
            r.set(x, FLOOR_Y + 1, 1, GOLD);
        }

        // Directional blocks. These are what catch a placer that moves blocks but forgets to rotate their state.
        for (int i = 0; i < 4; i++) {
            r.set(6 + i, FLOOR_Y + 1, 6, STAIRS);
        }
        r.set(5, FLOOR_Y + 2, 1, LADDER);
        // A wall lever - a secret in its own right, and directional.
        r.set(1, FLOOR_Y + 2, 8, LEVER);

        // Secrets to find: a chest on the floor, and a second one tucked behind the pillar.
        r.set(10, FLOOR_Y + 1, 10, CHEST);
        r.set(3, FLOOR_Y + 1, 3, CHEST);

        // A crypt wall for superboom: cracked bricks, which is what the real ones are.
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
            for (int z = 14; z <= 17; z++) {
                r.set(20, y, z, CRACKED);
            }
        }

        // Something to etherwarp ONTO, well above head height so it cannot be reached by walking.
        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
            r.set(24, y, 24, STONE);
        }

        // A low ceiling over one corner, so etherwarp's two-air-above rule has something to correctly REFUSE.
        // A test room where every ability always succeeds proves only that it never says no.
        for (int x = 26; x < SIZE - 1; x++) {
            for (int z = 4; z < 8; z++) {
                r.set(x, FLOOR_Y + 2, z, STONE);
            }
        }

        // A gap in the floor to cross - Wither Impact should carry you over it, walking should not.
        for (int x = 14; x <= 17; x++) {
            for (int z = 20; z <= 26; z++) {
                r.set(x, FLOOR_Y, z, AIR);
            }
        }

        r.markComplete();
        return r;
    }

    /** Deletes it. One call, for when real scanning starts - see {@link RoomLibrary#clearTestRooms()}. */
    public static void remove() {
        RoomLibrary.clearTestRooms();
    }

    /** Builds it only if it is not already present, so repeated sim entries do not rebuild it every time. */
    public static RoomLibrary.Room ensure() {
        RoomLibrary.Room existing = RoomLibrary.get(NAME);
        return existing != null ? existing : build();
    }
}
