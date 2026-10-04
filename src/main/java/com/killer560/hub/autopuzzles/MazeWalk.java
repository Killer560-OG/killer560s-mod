package com.killer560.hub.autopuzzles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A walk across one floor level that goes round what is in the way - for Auto Teleport Maze, which used to turn to
 * the pad and hold forward. killer560 (2026-10-04): "In some of the tp maze rooms it needs to pathfind around fences
 * blocking a tp pad." The capture shows why: every chamber has a cobblestone wall in the middle of two of its sides
 * (relative x 4 and 10 at z 9 in the first chamber, and the same in all seven), exactly between the two pads on that
 * side, so a straight walk from one pad to the pad beside it ran into the wall and stood there until the 3 s timeout
 * ("no teleport after 3000ms of walking" in his log).
 *
 * <p>The mod's own walking pathfinder ({@code pathfinding.GraphPathfinder}) runs on recorded island graphs loaded
 * from JSON - there is no graph for a dungeon room, and a chamber is a few dozen blocks - so this is a small grid
 * search over the blocks themselves: Dijkstra on the 8-neighbour grid at the target's feet level (no corner cutting,
 * steps up of at most 0.6 like a player's own step height, a block you cannot stand in or a wall/fence/bars at head
 * height is solid), then the cells thinned to the farthest one in a clear straight line.
 *
 * <p>Movement stays what it was: the camera turned towards the next point and the forward key held - one discrete
 * key, no strafing, no fractional input, nothing written to position or velocity.
 */
final class MazeWalk {

    /** How far around the start and goal the grid reaches. */
    private static final int MARGIN = 8;
    private static final int MAX_SIDE = 64;
    private static final double STEP_UP = 0.6;
    private static final double STEP_DOWN = 1.25;
    /** A waypoint counts as reached within this, horizontally. */
    private static final double REACHED = 0.45;
    /** Further than this off the line to the next point and the path is worked out again. */
    private static final double OFF_COURSE = 1.6;

    private final List<Vec3> points = new ArrayList<>();
    private int next = 0;
    private BlockPos goal = null;
    private int feetY = 0;

    /** Plans a walk from where he stands to {@code goalFeet} (the block his feet are in on arrival).
     *  @return false if no walkable way was found (callers fall back to a straight walk) */
    boolean plan(Level level, Vec3 from, BlockPos goalFeet) {
        points.clear();
        next = 0;
        goal = goalFeet;
        feetY = goalFeet.getY();
        int sx = (int) Math.floor(from.x);
        int sz = (int) Math.floor(from.z);
        int minX = Math.min(sx, goalFeet.getX()) - MARGIN;
        int minZ = Math.min(sz, goalFeet.getZ()) - MARGIN;
        int w = Math.min(MAX_SIDE, Math.max(sx, goalFeet.getX()) + MARGIN - minX + 1);
        int d = Math.min(MAX_SIDE, Math.max(sz, goalFeet.getZ()) + MARGIN - minZ + 1);
        if (sx - minX >= w || sz - minZ >= d || goalFeet.getX() - minX >= w || goalFeet.getZ() - minZ >= d) {
            return false;
        }
        double[] surface = new double[w * d];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < d; j++) {
                surface[j * w + i] = surface(level, minX + i, feetY, minZ + j);
            }
        }
        int start = (sz - minZ) * w + (sx - minX);
        int target = (goalFeet.getZ() - minZ) * w + (goalFeet.getX() - minX);
        if (Double.isNaN(surface[target])) {
            return false;
        }
        // He may be standing on something the grid calls unwalkable (an edge, a slab lip): start from it anyway.
        if (Double.isNaN(surface[start])) {
            surface[start] = from.y;
        }
        double[] dist = new double[w * d];
        int[] prev = new int[w * d];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[start] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        open.add(new double[]{0, start});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int cur = (int) top[1];
            if (top[0] > dist[cur]) {
                continue;
            }
            if (cur == target) {
                break;
            }
            int ci = cur % w;
            int cj = cur / w;
            for (int[] dir : dirs) {
                int ni = ci + dir[0];
                int nj = cj + dir[1];
                if (ni < 0 || nj < 0 || ni >= w || nj >= d) {
                    continue;
                }
                int n = nj * w + ni;
                if (!canStep(surface[cur], surface[n])) {
                    continue;
                }
                if (dir[0] != 0 && dir[1] != 0) {
                    // No cutting a corner past something solid.
                    int a = cj * w + ni;
                    int b = nj * w + ci;
                    if (!canStep(surface[cur], surface[a]) || !canStep(surface[cur], surface[b])) {
                        continue;
                    }
                }
                double nd = dist[cur] + (dir[0] != 0 && dir[1] != 0 ? 1.4142 : 1.0);
                if (nd < dist[n]) {
                    dist[n] = nd;
                    prev[n] = cur;
                    open.add(new double[]{nd, n});
                }
            }
        }
        if (dist[target] == Double.MAX_VALUE) {
            return false;
        }
        List<int[]> cells = new ArrayList<>();
        for (int c = target; c >= 0; c = prev[c]) {
            cells.add(0, new int[]{c % w, c / w});
            if (c == start) {
                break;
            }
        }
        // Thin to the farthest cell reachable in a clear straight line, so the walk is a few straight legs rather
        // than a staircase of cell centres.
        int i = 0;
        while (i < cells.size() - 1) {
            int j = cells.size() - 1;
            while (j > i + 1 && !clearLine(surface, w, d, cells.get(i), cells.get(j))) {
                j--;
            }
            int[] c = cells.get(j);
            points.add(new Vec3(minX + c[0] + 0.5, feetY + 0.5, minZ + c[1] + 0.5));
            i = j;
        }
        if (points.isEmpty()) {
            points.add(Vec3.atCenterOf(goalFeet));
        }
        return true;
    }

    /** The number of straight legs planned. */
    int legs() {
        return points.size();
    }

    /** Planned length in blocks from {@code from}, for a timeout that scales with the walk. */
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
     * Turns towards the next point and holds forward. Re-plans if he has been pushed off the line.
     * @return false when there is nothing left to walk to
     */
    boolean tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || goal == null) {
            return false;
        }
        Vec3 at = player.position();
        while (next < points.size() - 1 && Math.hypot(points.get(next).x - at.x, points.get(next).z - at.z) < REACHED) {
            next++;
        }
        if (next >= points.size()) {
            return false;
        }
        Vec3 aim = points.get(next);
        Vec3 from = next == 0 ? null : points.get(next - 1);
        if (from != null && offLine(at, from, aim) > OFF_COURSE && player.onGround()) {
            if (!plan(client.level, at, goal)) {
                return false;
            }
            aim = points.get(0);
        }
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), aim);
        AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
        client.options.keyUp.setDown(true);
        return true;
    }

    private static double offLine(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-6) {
            return Math.hypot(p.x - a.x, p.z - a.z);
        }
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2));
        return Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t));
    }

    private static boolean canStep(double from, double to) {
        return !Double.isNaN(from) && !Double.isNaN(to) && to - from <= STEP_UP && from - to <= STEP_DOWN;
    }

    /** Samples the line every quarter block, with his half-width either side, against the walkable cells. */
    private static boolean clearLine(double[] surface, int w, int d, int[] a, int[] b) {
        double ax = a[0] + 0.5;
        double az = a[1] + 0.5;
        double bx = b[0] + 0.5;
        double bz = b[1] + 0.5;
        double len = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        double last = surface[a[1] * w + a[0]];
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double x = ax + (bx - ax) * t;
            double z = az + (bz - az) * t;
            for (double ox : new double[]{-0.31, 0.31}) {
                for (double oz : new double[]{-0.31, 0.31}) {
                    int ci = (int) Math.floor(x + ox);
                    int cj = (int) Math.floor(z + oz);
                    if (ci < 0 || cj < 0 || ci >= w || cj >= d) {
                        return false;
                    }
                    double sfc = surface[cj * w + ci];
                    if (!canStep(last, sfc)) {
                        return false;
                    }
                }
            }
            last = surface[(int) Math.floor(z) * w + (int) Math.floor(x)];
        }
        return true;
    }

    /**
     * The height he stands at with his feet in block {@code (x, y, z)}, or NaN if he cannot stand there: nothing
     * under him, a block too tall to stand in (a full block, or a wall/fence/bars, whose collision is 1.5 high), or
     * anything solid where his body goes.
     */
    private static double surface(Level level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        double stand;
        VoxelShape here = level.getBlockState(feet).getCollisionShape(level, feet);
        if (here.isEmpty()) {
            BlockPos below = feet.below();
            VoxelShape floor = level.getBlockState(below).getCollisionShape(level, below);
            if (floor.isEmpty()) {
                return Double.NaN;
            }
            double top = floor.max(Direction.Axis.Y);
            if (top > 1.0) {
                return Double.NaN; // the top of a wall or fence below: nothing to walk on at this level
            }
            stand = y - 1 + top;
        } else {
            double top = here.max(Direction.Axis.Y);
            if (top > 0.9) {
                return Double.NaN;
            }
            stand = y + top;
        }
        // Body: 1.8 tall from where he stands.
        for (int yy = y + 1; yy <= (int) Math.floor(stand + 1.8); yy++) {
            BlockPos p = new BlockPos(x, yy, z);
            BlockState state = level.getBlockState(p);
            VoxelShape shape = state.getCollisionShape(level, p);
            if (!shape.isEmpty() && shape.bounds().minY + yy < stand + 1.8) {
                return Double.NaN;
            }
        }
        return stand;
    }
}
