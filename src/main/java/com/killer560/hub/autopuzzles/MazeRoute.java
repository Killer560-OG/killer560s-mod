package com.killer560.hub.autopuzzles;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Picks Auto Teleport Maze's next pad from what this visit has learnt. No Minecraft in it, so it can be checked offline
 * against thousands of drawn mazes ({@code tools/mazecheck/RouteCheck.java}).
 *
 * <p><b>Why it exists (2026-10-06).</b> The old pick was QUOI's: the solver's best pad if unvisited, else an unvisited
 * diagonal, else the farthest unvisited, and - once every pad in the chamber was used - the diagonal again. Every link
 * joins two chambers, so a pad not on the way forward sends him back along the maze, and "the diagonal again" from a pad
 * he came BACK through keeps walking back: 142-sim-autopilot2 saw it on two floors of three ("every pad in this chamber
 * is visited - taking the diagonal again" until the autopilot's 30 s ran out) and 93-solve ended once on the start pad,
 * "no pad to walk to", with the shortest route five pads long. Nothing it had learnt was used: each teleport tells which
 * pad goes where, and that is enough to find the way.
 *
 * <p><b>Now</b>, in order: the exit pad when the solver has narrowed it to one and it is in this chamber; else a way
 * there over links already taken (breadth first over chambers); else the solver's best pad or an unvisited one here
 * (diagonal first, then farthest - QUOI's exploring order); else a way over known links to the nearest chamber that still
 * has an unvisited pad. Each hop either uses a known link or tries a new pad, so it cannot go round for ever: at most 28
 * new pads, and known ways are shortest paths. The start pad and the end pad are never a step in a known way (the start
 * pad leads back out to where he began; the end pad is the goal).
 */
public final class MazeRoute {

    private MazeRoute() {
    }

    /** The pad to walk to and why, for the log. */
    public record Choice(int pad, String reason) {
    }

    /**
     * @param current    the pad he stands on (index into {@code xz}), or -1
     * @param xz         every pad's {x, z}
     * @param cellOf     each pad's chamber, -1 for the start and end pads
     * @param links      pad stepped on -> pad landed on, learnt
     * @param visited    pads already stepped on or landed on
     * @param candidates the solver's unvisited exit candidates
     * @param best       the solver's best pad, or -1
     * @return the next pad, or null when there is none (he is not in a chamber, or nothing is left)
     */
    public static Choice choose(int current, int[][] xz, int[] cellOf, Map<Integer, Integer> links,
                                Set<Integer> visited, Set<Integer> candidates, int best) {
        if (current < 0 || cellOf[current] < 0) {
            return null;
        }
        int cell = cellOf[current];
        if (candidates.size() == 1) {
            int exit = candidates.iterator().next();
            if (cellOf[exit] == cell && exit != current) {
                return new Choice(exit, "the exit pad");
            }
            if (cellOf[exit] >= 0) {
                int first = firstStep(current, xz, cellOf, links, c -> c == cellOf[exit]);
                if (first >= 0) {
                    return new Choice(first, "a known way to the exit's chamber");
                }
            }
        }
        if (best >= 0 && cellOf[best] == cell && best != current && !visited.contains(best)) {
            return new Choice(best, "the solver's best pad");
        }
        int far = -1;
        double farDist = -1;
        for (int p = 0; p < xz.length; p++) {
            if (cellOf[p] != cell || p == current || visited.contains(p)) {
                continue;
            }
            if (xz[p][0] != xz[current][0] && xz[p][1] != xz[current][1]) {
                return new Choice(p, "the unvisited diagonal");
            }
            double d = Math.hypot(xz[p][0] - xz[current][0], xz[p][1] - xz[current][1]);
            if (d > farDist) {
                farDist = d;
                far = p;
            }
        }
        if (far >= 0) {
            return new Choice(far, "an unvisited pad");
        }
        int first = firstStep(current, xz, cellOf, links, c -> {
            for (int p = 0; p < xz.length; p++) {
                if (cellOf[p] == c && !visited.contains(p)) {
                    return true;
                }
            }
            return false;
        });
        return first < 0 ? null : new Choice(first, "a known way to a chamber with an unvisited pad");
    }

    /**
     * Breadth first over chambers along learnt links from where he stands, to the nearest chamber {@code goal} accepts.
     * The pad he stands (or will land) on is not a step: it is inert until he steps off it, and it only leads back.
     *
     * @return the pad to step on first, or -1 when no known way exists
     */
    static int firstStep(int current, int[][] xz, int[] cellOf, Map<Integer, Integer> links,
                         java.util.function.IntPredicate goal) {
        int chambers = 0;
        for (int c : cellOf) {
            chambers = Math.max(chambers, c + 1);
        }
        int[] firstPad = new int[chambers];
        int[] landedOn = new int[chambers];
        Arrays.fill(firstPad, -2);
        int start = cellOf[current];
        firstPad[start] = -1;
        landedOn[start] = current;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            for (int p = 0; p < xz.length; p++) {
                if (cellOf[p] != c || p == landedOn[c]) {
                    continue;
                }
                Integer to = links.get(p);
                if (to == null || to < 0 || to >= cellOf.length || cellOf[to] < 0) {
                    continue; // not learnt, or the start/end pad
                }
                int next = cellOf[to];
                if (firstPad[next] != -2) {
                    continue;
                }
                firstPad[next] = c == start ? p : firstPad[c];
                landedOn[next] = to;
                if (goal.test(next)) {
                    return firstPad[next];
                }
                queue.add(next);
            }
        }
        return -1;
    }
}
