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
 * he collides with, and something to stand on, water at the feet, or a ladder/vine. Moves: the eight walking neighbours
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

    /** Feet cells of the plan, start first; empty when no way exists. */
    static List<BlockPos> plan(Level level, BlockPos start, List<BlockPos> goals, double jumpHeight) {
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
            if (isGoal(at, goals) && key != startKey) {
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
                    if (!passable(level, fall) || lava(level, fall)) {
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

    /** A place he can be: room for the body, and something holding him there. */
    static boolean cell(Level level, BlockPos feet) {
        if (!body(level, feet)) {
            return false;
        }
        return water(level, feet) || lava(level, feet) || WalkTerrain.climbable(level, feet)
                || standOn(level, feet.below());
    }

    /** Room for a 1.8-tall player at these feet (fluids and ladders are not in the way). */
    static boolean body(Level level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above());
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

    /** Feet points (block centre, feet height) for the walker. */
    static List<Vec3> toPoints(List<BlockPos> cells) {
        List<Vec3> out = new ArrayList<>(cells.size());
        for (BlockPos c : cells) {
            out.add(new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5));
        }
        return out;
    }
}
