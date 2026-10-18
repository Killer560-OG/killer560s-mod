package com.killer560.hub.roomsim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deuces' chest doorway, block for block as Hypixel builds it.
 *
 * <p>killer560 (2026-10-18), on the doorway under Deuces' secret chest: "that door under the chest always looks like that
 * regardless of direction for Deuces but it is for it specifically. Just recreate it exactly". So it is not a door piece
 * at all: it is Deuces' own doorway, the same at every turn, and it is not what the capture holds. His Hypixel shot
 * ({@code killer560s-mod-logs/deuces-door-2/hypixel-2026-10-16_22.21.06.png}) was matched block by block from his camera
 * (testkit 1434, {@code killer560s-mod-logs/deuces-exact/}); {@link #SPEC} is the result, in the capture's frame (the
 * doorway is capture W0: wall plane x 1, the seam x 0, the opening z 15..17). Unlike every other doorway it is three high
 * (y 69..71) under a slab lintel, which is why the carve must not touch it.
 *
 * <p>Pasted over the capture at every rotation, solo and on a floor, in place of {@link RoomDoorPieces} (which leaves this
 * doorway alone). The doorway carve skips these blocks, both its 4-high cut and its kept-trim rule, so what the paste put
 * there is what stands. The solo room's diamond seal fills the opening's air as for any doorway.
 */
public final class DeucesChestDoorway {

    /** The capture's name (sic). */
    public static final String ROOM = "Dueces";
    /** The doorway's side and tile in the capture, as {@link RoomDoors} measures them. */
    public static final int SIDE = RoomDoors.WEST;
    public static final int INDEX = 0;

    /**
     * One edit per line: {@code x y z block}, capture coordinates and the capture's frame, each of x, y, z a value or
     * an inclusive range {@code a..b}; later lines win. Read by testkit 1435 too, so keep the format.
     */
    static final String[] SPEC = {
            // The layer one in from the wall plane, over the opening, cleared (the capture has a polished andesite
            // frame and stair jambs here, and the 15-block door-piece fill over it).
            "2 69..76 14..18 air",
            // Jambs: cobblestone wall posts beside the opening, three high, joined back to the frame behind them.
            "2 69..71 14 cobblestone_wall[east=none,north=none,south=none,up=true,west=tall,waterlogged=false]",
            "2 69..71 18 cobblestone_wall[east=none,north=none,south=none,up=true,west=tall,waterlogged=false]",
            // Lintel: a stone-brick slab, bottom half, as wide as the jambs, at y 72 - the doorway is three high here,
            // not the usual four - and the wall plane's opening closed at the same height behind it.
            "2 72 14..18 stone_brick_slab[type=bottom,waterlogged=false]",
            "1 72 15..17 stone_bricks",
            // The step: one row of stone bricks on the slab. Above it, air in front of three rows of the wall
            // plane's stone.
            "2 73 14..18 stone_bricks",
            // Cobblestone along y 76 at the box's top corners, back to the braziers, and beside them (the capture
            // has stone-brick stairs and magenta wool there).
            "2..5 76 13..14 cobblestone",
            "2..5 76 18..19 cobblestone",
    };

    private static volatile Map<Long, BlockState> blocks;
    private static volatile int minY;
    private static volatile int maxY;

    /** World positions this doorway was pasted to since the last build began, for the carve's trim rule. */
    private static final Set<BlockPos> WORLD = ConcurrentHashMap.newKeySet();

    private DeucesChestDoorway() {
    }

    /** Whether {@code room} is Deuces. */
    public static boolean isRoom(RoomLibrary.Room room) {
        return room != null && ROOM.equals(room.name);
    }

    /** Whether {@code (side, index)} of {@code room} is this doorway, in the capture's frame. */
    public static boolean isDoorway(RoomLibrary.Room room, int side, int index) {
        return isRoom(room) && side == SIDE && index == INDEX;
    }

    /**
     * The capture-frame blocks to paste instead of the capture's, for {@code room}: empty unless it is Deuces. The
     * paste turns them like every other block.
     */
    public static Map<Long, BlockState> overrides(RoomLibrary.Room room) {
        if (!isRoom(room)) {
            return Collections.emptyMap();
        }
        return table();
    }

    /** The lowest and highest capture y {@link #overrides} touches. */
    static int minY() {
        table();
        return minY;
    }

    static int maxY() {
        table();
        return maxY;
    }

    /** Records where a paste put this doorway, so the carve's trim rule can leave it alone. */
    static void pastedAt(BlockPos world) {
        WORLD.add(world.immutable());
    }

    /** Whether {@code pos} is one of this doorway's blocks in the world. */
    public static boolean covers(BlockPos pos) {
        return !WORLD.isEmpty() && WORLD.contains(pos);
    }

    /** Forgets every pasted copy; a new build is starting. */
    static void clearWorld() {
        WORLD.clear();
    }

    /** {@link #SPEC} resolved, keyed by {@link RoomDoorPieces#key}. */
    private static Map<Long, BlockState> table() {
        Map<Long, BlockState> t = blocks;
        if (t != null) {
            return t;
        }
        Map<Long, BlockState> out = new LinkedHashMap<>();
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (String line : SPEC) {
            String[] f = line.trim().split("\\s+", 4);
            int[] xr = range(f[0]);
            int[] yr = range(f[1]);
            int[] zr = range(f[2]);
            BlockState state = RoomPlacer.resolve(f[3].contains(":") ? f[3] : "minecraft:" + f[3]);
            if (state == null) {
                throw new IllegalStateException("DeucesChestDoorway: unknown block " + f[3]);
            }
            for (int x = xr[0]; x <= xr[1]; x++) {
                for (int y = yr[0]; y <= yr[1]; y++) {
                    for (int z = zr[0]; z <= zr[1]; z++) {
                        out.put(RoomDoorPieces.key(x, y, z), state);
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                    }
                }
            }
        }
        minY = lo;
        maxY = hi;
        blocks = Collections.unmodifiableMap(out);
        return blocks;
    }

    private static int[] range(String s) {
        int dots = s.indexOf("..");
        if (dots < 0) {
            int v = Integer.parseInt(s);
            return new int[]{v, v};
        }
        return new int[]{Integer.parseInt(s.substring(0, dots)), Integer.parseInt(s.substring(dots + 2))};
    }
}
