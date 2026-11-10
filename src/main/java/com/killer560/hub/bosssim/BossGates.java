package com.killer560.hub.bosssim;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * P3's three boundaries, each a GATE and a DOOR, as Hypixel changes them (measured in 73 recorded runs, facts from ADM's
 * notes; the blocks themselves are read off his captures, where the two kinds never share a cell):
 * <ul>
 * <li>the gate: the cracked stone bricks and stone brick stairs of the arch, gone in one tick with "The gate has been
 * destroyed!" (a Superboom while its section is in progress, or 5 s after the section ends);</li>
 * <li>the door: barriers, iron blocks and nether brick stairs, then 4 ticks later (5 at the first) the portcullis of
 * cobblestone, cobblestone walls and nether brick fences. It opens with the later of the section's end and its gate. When
 * the section ends with the gate up, the door's iron and stairs from y 118 up go at once and the rest waits.</li>
 * </ul>
 * Over the built-in plan (no capture) a boundary is the plan's iron wall ({@link BossStations#GATES}), gone with the door.
 */
public final class BossGates {

    /** Each door's box (x1, y1, z1, x2, y2, z2), by gate index 0-2; the gate's cells are the arch's kinds inside it. */
    private static final int[][] DOORS = {{95, 115, 121, 105, 136, 124}, {16, 115, 127, 19, 136, 137}, {3, 115, 48, 13, 136, 51}};
    /** Where a Superboom finds each gate: its blocks' box. */
    private static final int[][] NEAR = {{93, 113, 121, 107, 135, 124}, {16, 114, 125, 19, 138, 139}, {1, 114, 48, 15, 138, 51}};
    private static final int[] PORTCULLIS_DELAY = {5, 4, 4};

    private static final Predicate<BlockState> ARCH = s -> s.is(Blocks.CRACKED_STONE_BRICKS) || s.is(Blocks.STONE_BRICK_STAIRS);
    private static final Predicate<BlockState> DOOR = s -> s.is(Blocks.BARRIER) || s.is(Blocks.IRON_BLOCK)
            || s.is(Blocks.NETHER_BRICK_STAIRS);
    private static final Predicate<BlockState> PORTCULLIS = s -> s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLESTONE_WALL)
            || s.is(Blocks.NETHER_BRICK_FENCE);

    private BossGates() {
    }

    private static boolean captured() {
        return "capture".equals(BossArena.source());
    }

    /** Whether {@code s} at {@code p} is one of a P3 gate's own blocks (arch, door or portcullis kinds inside a gate's box): Auto Superboom's target. */
    public static boolean isGateBlock(BlockPos p, BlockState s) {
        for (int[] b : NEAR) {
            if (p.getX() >= b[0] && p.getX() <= b[3] && p.getY() >= b[1] && p.getY() <= b[4] && p.getZ() >= b[2] && p.getZ() <= b[5]) {
                return ARCH.test(s) || DOOR.test(s) || PORTCULLIS.test(s);
            }
        }
        return false;
    }

    /** The gate (1-3) whose blocks are within 1.5 of {@code p}, or 0. */
    static int near(BlockPos p) {
        for (int i = 0; i < 3; i++) {
            int[] b = NEAR[i];
            if (p.getX() >= b[0] - 1.5 && p.getX() <= b[3] + 1.5 && p.getY() >= b[1] - 1.5 && p.getY() <= b[4] + 1.5
                    && p.getZ() >= b[2] - 1.5 && p.getZ() <= b[5] + 1.5) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Gate {@code s}'s arch goes (a capture's; the plan has none). */
    static void removeGate(ServerLevel level, int s) {
        clear(level, s, ARCH, 0);
    }

    /** Section {@code s} ended with its gate up: the door's iron and stairs from y 118 up go now. */
    static void doorTop(ServerLevel level, int s) {
        clear(level, s, st -> !st.is(Blocks.BARRIER) && DOOR.test(st), 118);
    }

    /** Door {@code s} opens: its door blocks now, the portcullis a few ticks later; the plan's iron wall goes at once. */
    static void openDoor(ServerLevel level, int s) {
        if (!captured()) {
            BossBlocks.open(level, BossStations.GATES[s - 1]);
            return;
        }
        clear(level, s, DOOR, 0);
        BossFight.later(PORTCULLIS_DELAY[s - 1], "portcullis " + s, () -> clear(level, s, PORTCULLIS, 0));
    }

    /** A fight started past boundary {@code s}: gate and door already gone. */
    static void gone(ServerLevel level, int s) {
        removeGate(level, s);
        openDoor(level, s);
        clear(level, s, PORTCULLIS, 0);
    }

    private static void clear(ServerLevel level, int s, Predicate<BlockState> what, int minY) {
        if (!captured()) {
            return;
        }
        int[] b = DOORS[s - 1];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = Math.max(b[1], minY); y <= b[4]; y++) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    if (what.test(level.getBlockState(p.set(x, y, z)))) {
                        // No neighbor updates: a wall or fence left beside an opening keeps its shape, as Hypixel's do.
                        level.setBlock(p, BossBlocks.AIR, BossBlocks.FLAGS);
                    }
                }
            }
        }
    }
}
