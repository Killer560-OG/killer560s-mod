package com.killer560.hub.autopuzzles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A walking planner for Auto Boulder that works across HEIGHTS - the doorway is five blocks over the floor and the only
 * legitimate way down is a pair of staircases.
 *
 * <p>Why it exists. killer560 (2026-10-06): "It seems to struggle pathfinding down the stairs right now." The old
 * walk ({@link MazeWalk}) plans on ONE feet level: it samples every column at the target's height and allows a step of
 * at most 0.6 up / 1.25 down between neighbours, so a staircase spanning five blocks is not a path at all. Auto Boulder
 * worked around it by looking for a hole in the roof and dropping through it, which is the "struggle" - a walk to a
 * roof edge that timed out, a step into the air, a five-block fall. A real player takes the stairs.
 *
 * <p>The model: a node is a column (x, z) plus the block his feet are in (y), standable when the block under him (or a
 * low shape in his feet block - a slab) gives a surface and 1.8 blocks over it are clear. Neighbours are the eight
 * columns around, at feet y one up, level, or one/two down, joined when the step is one a player walks: up at most 0.6
 * (a slab, a stair's lower half), or onto a BOTTOM-half stair entered from its low side (one block, the way stairs are
 * climbed without jumping); down at most 1.25 (a stair, a step off a ledge). Diagonals never cut a corner and stay on
 * one level. Going down a ledge that is not a stair costs extra, so stairs win over drops. Optionally barrier blocks
 * are not stood on - Boulder's roof is barrier, and a legit walk does not cross the invisible ceiling.
 *
 * <p>Movement is not done here; see {@link AutoBoulder}. Nothing writes position or velocity.
 */
final class BoulderPath {

    static final double STEP_UP = 0.6;
    static final double STEP_DOWN = 1.25;
    /** Extra cost for stepping down a ledge that is not a stair - stairs are preferred over drops. */
    private static final double LEDGE_COST = 2.5;
    private static final int MAX_NODES = 120_000;

    private final Level level;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int w;
    private final int h;
    private final int d;
    private final boolean avoidBarrier;
    /** Stand height per node, NaN when not standable. Computed lazily (NEGATIVE_INFINITY = not computed yet). */
    private final double[] stand;

    /** The planned path: cell centres at stand height, start first. */
    private final List<Vec3> points = new ArrayList<>();
    private BlockPos goalFeet = null;
    private int expanded = 0;

    BoulderPath(Level level, BlockPos cornerA, BlockPos cornerB, boolean avoidBarrier) {
        this.level = level;
        this.minX = Math.min(cornerA.getX(), cornerB.getX());
        this.minY = Math.min(cornerA.getY(), cornerB.getY());
        this.minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        this.w = Math.abs(cornerA.getX() - cornerB.getX()) + 1;
        this.h = Math.abs(cornerA.getY() - cornerB.getY()) + 1;
        this.d = Math.abs(cornerA.getZ() - cornerB.getZ()) + 1;
        this.avoidBarrier = avoidBarrier;
        this.stand = new double[w * h * d];
        Arrays.fill(stand, Double.NEGATIVE_INFINITY);
    }

    List<Vec3> points() {
        return points;
    }

    BlockPos goal() {
        return goalFeet;
    }

    int expanded() {
        return expanded;
    }

    /** Path length in blocks (3D), from {@code from}. */
    double length(Vec3 from) {
        double total = 0;
        Vec3 at = from;
        for (Vec3 p : points) {
            total += Math.hypot(p.x - at.x, p.z - at.z);
            at = p;
        }
        return total;
    }

    /**
     * Plans from where he stands to the cheapest node {@code goal} accepts. {@code goal} receives the node's feet block
     * and its stand height.
     *
     * @return false when no accepted node can be walked to
     */
    boolean plan(Vec3 from, GoalTest goal) {
        points.clear();
        goalFeet = null;
        expanded = 0;
        int sx = (int) Math.floor(from.x);
        int sz = (int) Math.floor(from.z);
        int sy = (int) Math.floor(from.y + 0.01);
        int start = -1;
        // He may stand on a lip the grid calls unstandable; search a block either way, then take his feet anyway.
        for (int dy : new int[]{0, 1, -1}) {
            int i = index(sx, sy + dy, sz);
            if (i >= 0 && !Double.isNaN(standAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            start = index(sx, sy, sz);
            if (start < 0) {
                return false;
            }
            stand[start] = from.y;
        }
        double[] dist = new double[w * h * d];
        int[] prev = new int[w * h * d];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[start] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        open.add(new double[]{0, start});
        int found = -1;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int cur = (int) top[1];
            if (top[0] > dist[cur]) {
                continue;
            }
            if (++expanded > MAX_NODES) {
                break;
            }
            int cx = xOf(cur);
            int cy = yOf(cur);
            int cz = zOf(cur);
            double cs = standAt(cur);
            if (goal.accept(new BlockPos(cx, cy, cz), cs)) {
                found = cur;
                break;
            }
            for (int[] dir : dirs) {
                boolean diagonal = dir[0] != 0 && dir[1] != 0;
                int nx = cx + dir[0];
                int nz = cz + dir[1];
                for (int dy = 1; dy >= -2; dy--) {
                    if (diagonal && dy != 0) {
                        continue;
                    }
                    int ny = cy + dy;
                    int n = index(nx, ny, nz);
                    if (n < 0) {
                        continue;
                    }
                    double ns = standAt(n);
                    if (Double.isNaN(ns)) {
                        continue;
                    }
                    double rise = ns - cs;
                    boolean stairUp = false;
                    if (rise > STEP_UP) {
                        stairUp = !diagonal && rise <= 1.01 && climbableStair(nx, ny - 1, nz, dir[0], dir[1]);
                        if (!stairUp) {
                            continue;
                        }
                    }
                    if (rise < -STEP_DOWN) {
                        continue;
                    }
                    // His body passes from one column into the other: the lower column must be clear up to the
                    // higher stand + 1.8.
                    if (rise < 0 ? !clearColumn(nx, nz, ns, cs + 1.8) : rise > 0 && !clearColumn(cx, cz, cs, ns + 1.8)) {
                        continue;
                    }
                    if (diagonal) {
                        int a = index(nx, cy, cz);
                        int b = index(cx, cy, nz);
                        if (a < 0 || b < 0 || Double.isNaN(standAt(a)) || Double.isNaN(standAt(b))
                                || Math.abs(standAt(a) - cs) > STEP_UP || Math.abs(standAt(b) - cs) > STEP_UP) {
                            continue;
                        }
                    }
                    double cost = diagonal ? 1.4142 : 1.0;
                    cost += Math.abs(rise) * 0.4;
                    if (rise < -STEP_UP && !onStair(cx, cy - 1, cz) && !onStair(nx, ny - 1, nz)) {
                        cost += LEDGE_COST;
                    }
                    double nd = dist[cur] + cost;
                    if (nd < dist[n]) {
                        dist[n] = nd;
                        prev[n] = cur;
                        open.add(new double[]{nd, n});
                    }
                }
            }
        }
        if (found < 0) {
            return false;
        }
        List<Vec3> rev = new ArrayList<>();
        for (int c = found; c >= 0; c = prev[c]) {
            rev.add(new Vec3(xOf(c) + 0.5, standAt(c), zOf(c) + 0.5));
            if (c == start) {
                break;
            }
        }
        for (int i = rev.size() - 1; i >= 0; i--) {
            points.add(rev.get(i));
        }
        goalFeet = new BlockPos(xOf(found), yOf(found), zOf(found));
        return true;
    }

    /** What a {@link #plan} goal accepts: a node's feet block and stand height. */
    interface GoalTest {
        boolean accept(BlockPos feet, double standY);
    }

    /** A goal that is the node whose feet block is {@code feet}. */
    static GoalTest at(BlockPos feet) {
        return (pos, s) -> pos.equals(feet);
    }

    /** A goal accepting a node whose STANDING eye passes {@code eyeOk}. */
    static GoalTest eye(Predicate<Vec3> eyeOk) {
        return (pos, s) -> eyeOk.test(new Vec3(pos.getX() + 0.5, s + AutoPuzzleUtil.EYE_STANDING, pos.getZ() + 0.5));
    }

    /** The stand height with his feet in this block, or NaN; outside the planned box, also NaN. */
    double standHeight(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? Double.NaN : standAt(i);
    }

    // ------------------------------------------------------------------ grid

    private int index(int x, int y, int z) {
        int i = x - minX;
        int j = y - minY;
        int k = z - minZ;
        if (i < 0 || j < 0 || k < 0 || i >= w || j >= h || k >= d) {
            return -1;
        }
        return (j * d + k) * w + i;
    }

    private int xOf(int idx) {
        return idx % w + minX;
    }

    private int zOf(int idx) {
        return (idx / w) % d + minZ;
    }

    private int yOf(int idx) {
        return idx / (w * d) + minY;
    }

    private double standAt(int idx) {
        double s = stand[idx];
        if (s == Double.NEGATIVE_INFINITY) {
            s = surface(xOf(idx), yOf(idx), zOf(idx));
            stand[idx] = s;
        }
        return s;
    }

    /** MazeWalk's surface rule, plus: never stand on a barrier when {@link #avoidBarrier}. */
    private double surface(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        double s;
        BlockState here = level.getBlockState(feet);
        VoxelShape hereShape = here.getCollisionShape(level, feet);
        if (hereShape.isEmpty()) {
            BlockPos below = feet.below();
            BlockState under = level.getBlockState(below);
            VoxelShape floor = under.getCollisionShape(level, below);
            if (floor.isEmpty()) {
                return Double.NaN;
            }
            double top = floor.max(Direction.Axis.Y);
            if (top > 1.0) {
                return Double.NaN; // a wall or fence top: nothing to walk on at this level
            }
            if (avoidBarrier && under.is(Blocks.BARRIER)) {
                return Double.NaN;
            }
            s = y - 1 + top;
        } else {
            double top = hereShape.max(Direction.Axis.Y);
            if (top > 0.9) {
                return Double.NaN;
            }
            if (avoidBarrier && here.is(Blocks.BARRIER)) {
                return Double.NaN;
            }
            s = y + top;
        }
        return clearColumn(x, z, s, s + 1.8) ? s : Double.NaN;
    }

    /** No collision box in this column between heights {@code from} and {@code to}. */
    private boolean clearColumn(int x, int z, double from, double to) {
        for (int yy = (int) Math.floor(from); yy <= (int) Math.floor(to - 1e-6); yy++) {
            BlockPos p = new BlockPos(x, yy, z);
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (shape.isEmpty()) {
                continue;
            }
            double lo = yy + shape.min(Direction.Axis.Y);
            double hi = yy + shape.max(Direction.Axis.Y);
            if (hi > from + 1e-6 && lo < to - 1e-6) {
                return false;
            }
        }
        return true;
    }

    /** A bottom-half stair at this block entered by a step in (dx, dz) from its low side - climbed without a jump. */
    private boolean climbableStair(int x, int y, int z, int dx, int dz) {
        BlockState st = level.getBlockState(new BlockPos(x, y, z));
        if (!(st.getBlock() instanceof StairBlock) || st.getValue(StairBlock.HALF) != Half.BOTTOM) {
            return false;
        }
        Direction facing = st.getValue(StairBlock.FACING);
        return facing.getStepX() == dx && facing.getStepZ() == dz;
    }

    private boolean onStair(int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof StairBlock;
    }
}
