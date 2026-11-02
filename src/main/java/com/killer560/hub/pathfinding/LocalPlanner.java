package com.killer560.hub.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A short way back onto the route over the blocks loaded around him, for when the island graph cannot help: he fell
 * into water, off a ledge or into a pit, was knocked off, or ended up in lava (killer560, 2026-10-17: "you need to teach
 * it stuff like if it falls off into water how to pathfind back up"). The graph only knows its own nodes, so planning
 * again from the nearest node leaves him walking into the side of the pit he is in.
 * <p>
 * A* over feet blocks within {@link #RADIUS} horizontally. A cell is a place he can be: feet and head free of anything
 * he collides with, and something to stand on, water at the feet, or a ladder/vine. Moves: the eight walking neighbors
 * (a diagonal only when both sides are free); a step up of one block, or as many as his real jump clears (Jump Boost
 * included) with the headroom for it; a drop of up to {@link #MAX_FALL}; swimming up and down in water and out over a
 * one-block bank (vanilla's jump against a wall in water lifts him out); climbing a ladder. Lava is never entered, only
 * left. Goals are the route's points and the soul.
 * <p>
 * Runs on the render thread on the client's own world (bounded by {@link #MAX_EXPANSIONS}); unloaded blocks read as air
 * with nothing to stand on, so a plan never goes through them.
 */
final class LocalPlanner {

    static final int RADIUS = 24;
    static final int HEIGHT = 16;
    static final int MAX_FALL = 8;
    static final int MAX_EXPANSIONS = 12_000;

    private LocalPlanner() {
    }

    /** Feet cells of the plan, start first; empty when no way exists. A goal is reached within one block. */
    static List<BlockPos> plan(Level level, BlockPos start, List<BlockPos> goals, double jumpHeight) {
        return plan(level, start, goals, jumpHeight, false);
    }

    /**
     * As {@link #plan(Level, BlockPos, List, double)}; {@code exact} = only a goal cell itself ends the plan (a place
     * a soul is in reach from: one block off may be out of reach).
     */
    static List<BlockPos> plan(Level level, BlockPos start, List<BlockPos> goals, double jumpHeight, boolean exact) {
        if (goals.isEmpty()) {
            return List.of();
        }
        int maxUp = Math.max(1, (int) Math.floor(jumpHeight - 0.05));
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        long startKey = start.asLong();
        cost.put(startKey, 0.0);
        open.add(new double[]{heuristic(start, goals), startKey});
        int expansions = 0;
        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            double[] top = open.poll();
            long key = (long) top[1];
            BlockPos at = BlockPos.of(key);
            double g = cost.get(key);
            if (top[0] - heuristic(at, goals) > g + 1.0e-6) {
                continue; // a stale entry
            }
            expansions++;
            if ((exact ? goals.contains(at) : isGoal(at, goals)) && key != startKey) {
                return rebuild(parent, key);
            }
            for (Move move : moves(level, at, start, maxUp)) {
                long nk = move.to.asLong();
                double ng = g + move.cost;
                Double old = cost.get(nk);
                if (old == null || ng < old - 1.0e-9) {
                    cost.put(nk, ng);
                    parent.put(nk, key);
                    open.add(new double[]{ng + heuristic(move.to, goals), nk});
                }
            }
        }
        return List.of();
    }

    private record Move(BlockPos to, double cost) {
    }

    private static List<BlockPos> rebuild(Map<Long, Long> parent, long end) {
        List<BlockPos> out = new ArrayList<>();
        Long k = end;
        while (k != null) {
            out.add(BlockPos.of(k));
            k = parent.get(k);
        }
        Collections.reverse(out);
        return out;
    }

    static boolean isGoal(BlockPos p, List<BlockPos> goals) {
        for (BlockPos g : goals) {
            int dx = Math.abs(g.getX() - p.getX());
            int dz = Math.abs(g.getZ() - p.getZ());
            int dy = Math.abs(g.getY() - p.getY());
            if (dx <= 1 && dz <= 1 && dy <= 1) {
                return true;
            }
        }
        return false;
    }

    private static double heuristic(BlockPos p, List<BlockPos> goals) {
        double best = Double.MAX_VALUE;
        for (BlockPos g : goals) {
            double dx = g.getX() - p.getX();
            double dy = g.getY() - p.getY();
            double dz = g.getZ() - p.getZ();
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5);
        }
        return Math.max(0.0, best - 1.5);
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static List<Move> moves(Level level, BlockPos at, BlockPos origin, int maxUp) {
        List<Move> out = new ArrayList<>(12);
        boolean inLava = lava(level, at);
        boolean inWater = water(level, at) || inLava;
        boolean onLadder = WalkTerrain.climbable(level, at);
        // swim / climb straight up and down
        if ((inWater || onLadder) && passable(level, at.above(2)) && inBounds(at.above(), origin)) {
            BlockPos up = at.above();
            if (cell(level, up) || passable(level, up.above())) {
                out.add(new Move(up, inWater ? 1.4 : 1.2));
            }
        }
        if ((inWater || onLadder) && inBounds(at.below(), origin)) {
            BlockPos down = at.below();
            if (passable(level, down) && (inLava || !lava(level, down)) && cell(level, down)) {
                out.add(new Move(down, 1.2));
            }
        }
        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && (!body(level, at.offset(d[0], 0, 0)) || !body(level, at.offset(0, 0, d[1])))) {
                continue;
            }
            BlockPos side = at.offset(d[0], 0, d[1]);
            if (!inBounds(side, origin)) {
                continue;
            }
            double base = diagonal ? 1.414 : 1.0;
            if (!diagonal && !body(level, side) && doorway(level, side, d[0], d[1]) && standOn(level, side.below())) {
                // An open door, a trapdoor up against a wall, a pane edge: a thin panel along the way he walks.
                out.add(new Move(side, base * 1.2));
                continue;
            }
            if (body(level, side)) {
                boolean sideLava = lava(level, side);
                if (sideLava && !inLava) {
                    continue; // lava is only ever left, never entered
                }
                if (cell(level, side)) {
                    out.add(new Move(side, base * (sideLava ? 4.0 : water(level, side) ? 1.8 : 1.0)));
                    continue;
                }
                // Nothing under it: walking off drops him to the first thing below.
                for (int k = 1; k <= MAX_FALL; k++) {
                    BlockPos fall = side.below(k);
                    if ((!passable(level, fall) && lowTop(level, fall) < 0.0) || lava(level, fall)) {
                        break;
                    }
                    if (cell(level, fall)) {
                        out.add(new Move(fall, base + k * 0.4));
                        break;
                    }
                }
                continue;
            }
            // Blocked at the feet: a step up (a jump, or out of the water over a one-block bank).
            int up = inWater ? 1 : maxUp;
            for (int k = 1; k <= up; k++) {
                BlockPos lifted = side.above(k);
                if (!passable(level, at.above(k + 1))) {
                    break; // no headroom over his own column for this jump
                }
                if (body(level, lifted) && cell(level, lifted) && !lava(level, lifted)) {
                    out.add(new Move(lifted, base + 0.6 + k * 0.5));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean inBounds(BlockPos p, BlockPos origin) {
        return Math.abs(p.getX() - origin.getX()) <= RADIUS && Math.abs(p.getZ() - origin.getZ()) <= RADIUS
                && Math.abs(p.getY() - origin.getY()) <= HEIGHT;
    }

    /**
     * A place he can be: room for the body, and something holding him there. A LOW block in the feet cell itself (a
     * bottom slab, a carpet, snow layers, a lily pad - collision no taller than {@link #LOW_TOP}) is a floor he stands
     * ON inside that cell, not a wall: before 2026-11-01 every carpeted or slab-floored cell read as solid, so a way
     * back could never cross one (testkit 1101-fairy-plan-floors).
     */
    static boolean cell(Level level, BlockPos feet) {
        if (!body(level, feet)) {
            return false;
        }
        return water(level, feet) || lava(level, feet) || WalkTerrain.climbable(level, feet)
                || lowTop(level, feet) > 0.0 || standOn(level, feet.below());
    }

    /** The tallest collision in a block that still counts as a floor inside the feet cell (a bottom slab is 0.5). */
    static final double LOW_TOP = 0.5625;

    /**
     * Room for a 1.8-tall player at these feet (fluids and ladders are not in the way). Standing on a low block raises
     * the head into the cell two up, which must then be free as well.
     */
    static boolean body(Level level, BlockPos feet) {
        double low = lowTop(level, feet);
        if (low < 0.0 && !passable(level, feet)) {
            return false;
        }
        if (!passable(level, feet.above())) {
            return false;
        }
        return low <= 0.2 || passable(level, feet.above(2));
    }

    /**
     * Walking straight through this column along {@code (dx, dz)} (one of them 0) passes whatever is in its feet and head
     * blocks: each is open, or a panel no thicker than {@link #PANEL} flat against one of the two faces PARALLEL to the
     * walk - an open door, which before 2026-11-01 read as a wall, so no way back went through a doorway. A closed door
     * across the way is thin against a face the walk crosses, and stays a wall.
     */
    static boolean doorway(Level level, BlockPos side, int dx, int dz) {
        return panelOrOpen(level, side, dx, dz) && panelOrOpen(level, side.above(), dx, dz);
    }

    static final double PANEL = 0.25;

    private static boolean panelOrOpen(Level level, BlockPos p, int dx, int dz) {
        if (passable(level, p)) {
            return true;
        }
        if (!level.isLoaded(p)) {
            return false;
        }
        var shape = level.getBlockState(p).getCollisionShape(level, p);
        if (dx != 0) {
            double lo = shape.min(net.minecraft.core.Direction.Axis.Z);
            double hi = shape.max(net.minecraft.core.Direction.Axis.Z);
            return hi - lo <= PANEL && (lo <= 0.01 || hi >= 0.99);
        }
        double lo = shape.min(net.minecraft.core.Direction.Axis.X);
        double hi = shape.max(net.minecraft.core.Direction.Axis.X);
        return hi - lo <= PANEL && (lo <= 0.01 || hi >= 0.99);
    }

    /** The collision top (0..LOW_TOP] of a low block in this cell, 0 for no collision, -1 for anything taller. */
    static double lowTop(Level level, BlockPos p) {
        if (!level.isLoaded(p)) {
            return -1.0;
        }
        BlockState s = level.getBlockState(p);
        if (s.is(BlockTags.CLIMBABLE)) {
            return 0.0;
        }
        var shape = s.getCollisionShape(level, p);
        if (shape.isEmpty()) {
            return 0.0;
        }
        double top = shape.max(net.minecraft.core.Direction.Axis.Y);
        return shape.min(net.minecraft.core.Direction.Axis.Y) <= 0.01 && top <= LOW_TOP ? top : -1.0;
    }

    static boolean passable(Level level, BlockPos p) {
        if (!level.isLoaded(p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        return s.is(BlockTags.CLIMBABLE) || s.getCollisionShape(level, p).isEmpty();
    }

    private static boolean standOn(Level level, BlockPos p) {
        if (!level.isLoaded(p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        if (s.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        return !s.getCollisionShape(level, p).isEmpty() || s.is(BlockTags.CLIMBABLE);
    }

    static boolean water(Level level, BlockPos p) {
        return level.getBlockState(p).getFluidState().is(FluidTags.WATER);
    }

    static boolean lava(Level level, BlockPos p) {
        return level.getBlockState(p).getFluidState().is(FluidTags.LAVA);
    }

    /**
     * The cell his feet are in: the block containing the feet, or the one above it when the feet rest on top of a
     * block taller than a slab but short of a full block (soul sand 0.875, a path block or farmland 0.9375) - the
     * block containing those feet is that block itself.
     */
    static BlockPos startCell(Level level, Vec3 pos) {
        BlockPos p = BlockPos.containing(pos.x, pos.y + 0.01, pos.z);
        if (!passable(level, p) && lowTop(level, p) < 0.0) {
            // On top of it (its collision ends at or under his feet), not beside a door panel in his own cell.
            var shape = level.getBlockState(p).getCollisionShape(level, p);
            if (shape.max(net.minecraft.core.Direction.Axis.Y) <= pos.y - p.getY() + 0.02) {
                p = p.above();
            }
        }
        if (!passable(level, p) && lowTop(level, p) < 0.0) {
            // Beside a fence post, a wall or a pane whose block his feet are counted in: plan from the open cell on HIS
            // side of it. Planned from the fence's own cell, the way crossed the fence line he was standing behind and
            // he pushed into it until the soul was skipped (testkit 1101-fairy-island-GOLD_MINES on terrain v2).
            double rx = pos.x - p.getX() - 0.5;
            double rz = pos.z - p.getZ() - 0.5;
            int sx = Math.abs(rx) > 0.15 ? (rx > 0 ? 1 : -1) : 0;
            int sz = Math.abs(rz) > 0.15 ? (rz > 0 ? 1 : -1) : 0;
            for (int[] d : new int[][]{{sx, sz}, {sx, 0}, {0, sz}}) {
                if (d[0] == 0 && d[1] == 0) {
                    continue;
                }
                BlockPos q = p.offset(d[0], 0, d[1]);
                if (cell(level, q)) {
                    return q;
                }
            }
        }
        return p;
    }

    /** Feet points (block center, feet height) for the walker. */
    static List<Vec3> toPoints(List<BlockPos> cells) {
        List<Vec3> out = new ArrayList<>(cells.size());
        for (BlockPos c : cells) {
            out.add(new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5));
        }
        return out;
    }

    /** Feet points at the real standing height: on a low block (slab, carpet) the feet are its top, not the cell's floor. */
    static List<Vec3> toPoints(Level level, List<BlockPos> cells) {
        List<Vec3> out = new ArrayList<>(cells.size());
        for (BlockPos c : cells) {
            out.add(new Vec3(c.getX() + 0.5, c.getY() + Math.max(0.0, lowTop(level, c)), c.getZ() + 0.5));
        }
        return out;
    }
}
