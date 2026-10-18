package com.killer560.hub.roomsim;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts the right half of Hypixel's door piece over each doorway for the way the room is turned.
 *
 * <p>killer560 (2026-10-16), a solo Deuces in the sim: "this door isn't supposed to have almost any blocks above it".
 * Above the 3x4 opening and its one-row trim, a standard Catacombs doorway has a 5 wide, 3 high box (y 74..76) in the
 * wall plane and the layer one block in from it, and the library holds exactly two fillings of that box (sweep of the
 * shipped captures, sim-deuces-door-1016):
 * <ul>
 *   <li><b>East and south doorways</b>, 106 of them: cyan terracotta in the wall plane, AIR one in - a recess over the
 *       trim. Deuces E0 is one.</li>
 *   <li><b>West and north doorways</b>, 101: stone in the wall plane and, one in, the same fifteen blocks every time -
 *       stone bricks, one stone, one mossy brick and four upside-down stairs - flush with the wall above the trim. Deuces
 *       W0, the doorway under its chest, is one in the capture, but on Hypixel it is Deuces' own doorway at every turn
 *       and is skipped here ({@link DeucesChestDoorway}, 2026-10-18).</li>
 * </ul>
 * Not one east or south doorway has the fill and not one west or north the recess, in rooms captured at different turns
 * (88 of 122 captures are turned), with the same fifteen blocks to the stair (the north copy is the west one turned a
 * quarter, stairs facing north). Room design does not repeat block for block across dozens of rooms and follow the
 * WORLD's compass; this is the connector Hypixel pastes into a doorway, one asymmetric piece always laid the same way, so the
 * room west of a door gets the recess and the room east of it the fill.
 *
 * <p>A capture therefore records whichever half its doorway faced on the day, and the sim carried that half round with
 * the room: a room pasted at 180 had the fill over its east doorways and the recess over its west ones, the opposite of
 * Hypixel at that turn, and at 90 or 270 half its doorways were wrong. So the paste swaps the box over each measured
 * doorway whose capture holds exactly one of the two standard fillings, to the filling for the side the doorway faces
 * in the world ({@link #overrides}). Any other doorway (Altar's, Spider's, Mossy's, Supertall's: 70 of the library's 278
 * measured) is the room's own and is left as captured. At rotation 0 nothing changes, so a capture pasted where it was taken is still
 * the capture.
 */
public final class RoomDoorPieces {

    /** The box over the trim: capture y 74..76, u -2..2 along the wall, the wall plane and one block in. */
    static final int Y_LOW = 74;
    static final int Y_HIGH = 76;
    private static final int HALF = 2;

    private static final String SB = "minecraft:stone_bricks";
    private static final String STONE = "minecraft:stone";
    private static final String MOSSY = "minecraft:mossy_stone_bricks";
    private static final String STAIR =
            "minecraft:stone_brick_stairs[facing=west,half=top,shape=straight,waterlogged=false]";

    /**
     * The fill one block in from a WEST wall, rows y 76, 75, 74, u -2..2 (u runs +Z), exactly as all 55 west copies hold
     * it. Other sides are this turned: see {@link #fill}.
     */
    private static final String[][] WEST_FILL = {
            {SB, SB, SB, STONE, SB},
            {SB, STAIR, STAIR, SB, MOSSY},
            {SB, SB, SB, STAIR, STAIR},
    };

    /** Doorways whose piece the pastes swapped since the last {@link #takeSwapped}. */
    private static final AtomicInteger SWAPPED = new AtomicInteger();

    private static volatile BlockState[][] westFill;

    private RoomDoorPieces() {
    }

    /** How many doorways the pastes turned to their world side's piece since the last call, and starts again. */
    public static int takeSwapped() {
        return SWAPPED.getAndSet(0);
    }

    /** Packs a capture position for {@link #overrides}' map. */
    static long key(int x, int y, int z) {
        return ((long) y << 32) | ((long) (x & 0xFFFF) << 16) | (z & 0xFFFF);
    }

    /**
     * The capture-frame blocks to paste instead of the capture's, for {@code room} pasted at {@code rotation}: the
     * filling of the box over each standard doorway, for the side that doorway faces once turned. States are in the
     * capture's frame, so the paste's own rotation turns them like every other block. Empty when nothing changes.
     */
    static Map<Long, BlockState> overrides(RoomLibrary.Room room, int rotation) {
        Map<Long, BlockState> out = new HashMap<>();
        int turns = Math.floorMod(rotation / 90, 4);
        if (room == null || turns == 0) {
            return out;
        }
        RoomDoors.Mask mask = RoomDoors.measure(room);
        int swapped = 0;
        for (int packed : mask.edges()) {
            int side = RoomDoors.sideOf(packed);
            int index = RoomDoors.indexOf(packed);
            if (DeucesChestDoorway.isDoorway(room, side, index)) {
                continue;   // Deuces' own doorway, the same at every turn: DeucesChestDoorway pastes it
            }
            boolean capturedFill = westOrNorth(side);
            boolean worldFill = westOrNorth((side + turns) % 4);
            if (capturedFill == worldFill) {
                continue;
            }
            if (!matches(room, side, index, capturedFill)) {
                continue;   // the room's own doorway, not the connector: left as captured
            }
            for (int k = 0; k <= 1; k++) {
                for (int u = -HALF; u <= HALF; u++) {
                    for (int y = Y_LOW; y <= Y_HIGH; y++) {
                        int[] p = at(room, side, index, k, u, y);
                        out.put(key(p[0], p[1], p[2]), piece(side, k, u, y, worldFill));
                    }
                }
            }
            swapped++;
        }
        if (swapped > 0) {
            SWAPPED.addAndGet(swapped);
        }
        return out;
    }

    /** True for the sides whose doorways carry the fill on Hypixel: north (0) and west (3). */
    static boolean westOrNorth(int side) {
        return side == RoomDoors.NORTH || side == RoomDoors.WEST;
    }

    /** Whether the box over this doorway holds exactly the standard filling for its captured side. */
    private static boolean matches(RoomLibrary.Room room, int side, int index, boolean fill) {
        for (int k = 0; k <= 1; k++) {
            for (int u = -HALF; u <= HALF; u++) {
                for (int y = Y_LOW; y <= Y_HIGH; y++) {
                    int[] p = at(room, side, index, k, u, y);
                    if (p[0] < 0 || p[2] < 0 || p[0] >= room.sizeX || p[2] >= room.sizeZ
                            || y < room.minY || y > room.maxY) {
                        return false;
                    }
                    short idx = room.at(p[0], p[1], p[2]);
                    if (idx < 0) {
                        return false;
                    }
                    BlockState have = RoomPlacer.resolve(room.palette.get(idx));
                    BlockState want = piece(side, k, u, y, fill);
                    if (have == null || (want.isAir() ? !have.isAir() : !want.equals(have))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * One block of a filling, in the capture's frame, for a doorway on {@code side}: {@code k} 0 is the wall plane, 1
     * the layer inside it. The recess is cyan terracotta then air; the fill is stone then {@link #WEST_FILL} turned.
     */
    static BlockState piece(int side, int k, int u, int y, boolean fill) {
        if (!fill) {
            // By id, not Blocks.CYAN_TERRACOTTA: colour blocks are named differently on 26.2 (see McBlocks).
            return k == 0 ? RoomPlacer.resolve("minecraft:cyan_terracotta") : Blocks.AIR.defaultBlockState();
        }
        if (k == 0) {
            return Blocks.STONE.defaultBlockState();
        }
        return fill(side, u, y);
    }

    /**
     * {@link #WEST_FILL} for a doorway on {@code side}: the west piece turned clockwise onto it, which takes its u to -u
     * on the north and east sides and keeps it on the south (worked from the paste's own clockwise turn, (x, z) to
     * (-z, x), and matching the 46 captured north copies block for block).
     */
    private static BlockState fill(int side, int u, int y) {
        BlockState[][] west = westFill;
        if (west == null) {
            west = new BlockState[WEST_FILL.length][];
            for (int r = 0; r < WEST_FILL.length; r++) {
                west[r] = new BlockState[WEST_FILL[r].length];
                for (int c = 0; c < WEST_FILL[r].length; c++) {
                    west[r][c] = RoomPlacer.resolve(WEST_FILL[r][c]);
                }
            }
            westFill = west;
        }
        int uWest = side == RoomDoors.NORTH || side == RoomDoors.EAST ? -u : u;
        BlockState s = west[Y_HIGH - y][uWest + HALF];
        Rotation turn = switch (side) {
            case RoomDoors.NORTH -> Rotation.CLOCKWISE_90;
            case RoomDoors.EAST -> Rotation.CLOCKWISE_180;
            case RoomDoors.SOUTH -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        return s.rotate(turn);
    }

    /**
     * Capture position of the box over doorway {@code (side, index)}: {@code k} blocks in from the wall plane (which is
     * {@code margin} in from the capture's edge, as {@link RoomDoors} measures), {@code u} along the wall from the
     * tile's centre.
     */
    static int[] at(RoomLibrary.Room room, int side, int index, int k, int u, int y) {
        int c = room.margin + RoomLibrary.TILE / 2 + index * (RoomLibrary.TILE + 1);
        int d = room.margin + k;
        return switch (side) {
            case RoomDoors.NORTH -> new int[]{c + u, y, d};
            case RoomDoors.SOUTH -> new int[]{c + u, y, room.sizeZ - 1 - d};
            case RoomDoors.WEST -> new int[]{d, y, c + u};
            default -> new int[]{room.sizeX - 1 - d, y, c + u};
        };
    }
}
