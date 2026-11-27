package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.puzzlesolvers.WaterSolverFeature;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Whether Water Board's gates, the color layers across the bottom walkway, are out of the way: the moment Hypixel
 * unlocks the reward chest (waterboard-chest-timed-click-1126, killer560 2026-11-26: "click the second that the bars in
 * the middle move out of the way"). Measured on his logger sessions of 11-18 to 11-26 (10 boards): every click before the
 * last layer's piston pulled back was answered "That chest is locked!", every click after it opened the chest, the
 * soonest one tick after the pull arrived (killer560s-mod-logs/waterboard-chest-timed-click-1126-state.md).
 *
 * <p>A layer is in the way while its middle cell {@code (15, 56, z)} holds a block, z 15..19 - the cell
 * {@link WaterSolverFeature} reads to find the three colors pushed out. Read only when a packet touched one of the five
 * columns ({@link #onBlock}: a block update, or the piston's block event two below) or any section update came in while
 * armed, never on a timer; the five reads go through one mutable position.
 */
public final class WaterGates {

    /** The five cells as {@link BlockPos#asLong}; null while nothing waits on the gates. */
    private static volatile long[] cells;
    private static volatile boolean dirty = true;
    private static Boolean state;
    private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();

    private WaterGates() {
    }

    /** Watch the gates of the room with this clay position and rotation (from {@code currentRoomClayAndRotation}). */
    static void arm(int[] cr) {
        WaterSolverFeature.WoolColor[] colors = WaterSolverFeature.WoolColor.values();
        long[] c = new long[colors.length];
        for (int i = 0; i < colors.length; i++) {
            c[i] = PuzzleCoords.real(15, 56, colors[i].relZ(), cr).asLong();
        }
        state = null;
        dirty = true;
        cells = c;
    }

    static boolean armed() {
        return cells != null;
    }

    static void disarm() {
        cells = null;
        state = null;
        dirty = true;
    }

    /** Client thread, from the packet hooks: a block update, or a block event (a piston's sits two below its cell). */
    public static void onBlock(BlockPos pos) {
        long[] c = cells;
        if (c == null) {
            return;
        }
        for (long cell : c) {
            if (BlockPos.getX(cell) == pos.getX() && BlockPos.getZ(cell) == pos.getZ()
                    && Math.abs(BlockPos.getY(cell) - pos.getY()) <= 2) {
                dirty = true;
                return;
            }
        }
    }

    /** Client thread: a section update (it may carry a gate cell). */
    public static void onSection() {
        if (cells != null) {
            dirty = true;
        }
    }

    /** TRUE every gate is out of the way, FALSE one is in it, null not armed or a cell is not loaded. */
    static Boolean clear(ClientLevel level) {
        long[] c = cells;
        if (c == null || level == null) {
            return null;
        }
        if (!dirty) {
            return state;
        }
        boolean open = true;
        for (long cell : c) {
            AT.set(cell);
            if (!level.isLoaded(AT)) {
                return null; // read again next time: a chunk load is not one of the hooked packets
            }
            if (!level.getBlockState(AT).isAir()) {
                open = false;
            }
        }
        dirty = false;
        state = open;
        return state;
    }
}
