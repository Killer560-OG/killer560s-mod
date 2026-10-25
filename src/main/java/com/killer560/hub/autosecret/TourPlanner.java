package com.killer560.hub.autosecret;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The order to take the rooms in - pure, no Minecraft state (testkit 364-logic-room-tour drives it with made-up floors).
 *
 * <p>killer560, 2026-10-15: "you need to focus more on not etherwarping all the way across the map. Rooms of 5 secrets or
 * more are worth it. Rooms of 3 secrets or less are about equal time wise. But for instance spending 20 ticks
 * etherwarping across a map for 2 secrets is less optimal than doing 2 1 secret rooms taking 5 ticks each." Taking the
 * room with the most secrets first, or the best secrets-per-second next, looks one room ahead and crosses the floor for a
 * small room whenever it is the biggest left. This looks at the whole order: every room has a value {@code w} (its
 * secrets, or Dungeon Autopilot's score) and is worth it by the tick it is done, so the order minimizes
 * {@code sum(w_i * done_i)} - the value-weighted time every room is waiting for. With no travel at all that is exactly
 * "best value per tick first" (Smith's rule); with travel it also counts what a long trip makes every OTHER room wait,
 * which is the cost of crossing the map for two secrets.
 *
 * <p>{@code fromHere[i]} is the trip from where he is (or will be) to room {@code i}'s start, {@code between[i][j]} from
 * where room {@code i}'s work ends to room {@code j}'s start, {@code work[i]} the time the room itself takes, all in
 * ticks. Exact (branch and bound) while it fits in the budget - every floor's handful of rooms does; past it, the best
 * order found so far, which starts from the value-per-tick greedy order and is improved by moving single rooms.
 */
public final class TourPlanner {

    private TourPlanner() {
    }

    /**
     * @param order  indices in the order to take them
     * @param cost   {@code sum(w * done)} of that order
     * @param ticks  when the last room is done
     * @param exact  the branch and bound finished: no order is cheaper
     */
    public record Result(int[] order, double cost, double ticks, boolean exact) {
    }

    /** {@code sum(w * done)} and the finishing tick of {@code order}. */
    public static double[] evaluate(int[] order, double[] fromHere, double[][] between, double[] weight, double[] work) {
        double t = 0;
        double cost = 0;
        int prev = -1;
        for (int i : order) {
            t += (prev < 0 ? fromHere[i] : between[prev][i]) + work[i];
            cost += weight[i] * t;
            prev = i;
        }
        return new double[]{cost, t};
    }

    /** The plan for every room ({@code budgetNanos} bounds the exact search; the greedy order is always available). */
    public static Result plan(double[] fromHere, double[][] between, double[] weight, double[] work, long budgetNanos) {
        int n = fromHere.length;
        if (n == 0) {
            return new Result(new int[0], 0, 0, true);
        }
        int[] best = greedy(fromHere, between, weight, work);
        best = improve(best, fromHere, between, weight, work);
        double[] e = evaluate(best, fromHere, between, weight, work);
        Search s = new Search(fromHere, between, weight, work, System.nanoTime() + Math.max(0, budgetNanos));
        s.bestCost = e[0];
        s.bestOrder = best.clone();
        s.run();
        int[] order = s.bestOrder;
        double[] f = evaluate(order, fromHere, between, weight, work);
        return new Result(order, f[0], f[1], !s.timedOut);
    }

    /** The travel of {@code order} alone: every trip added up, no work, no weights. */
    public static double travel(int[] order, double[] fromHere, double[][] between) {
        double t = 0;
        int prev = -1;
        for (int i : order) {
            t += prev < 0 ? fromHere[i] : between[prev][i];
            prev = i;
        }
        return t;
    }

    /** Exact least-travel tour over every subset of rooms with each room last (Held-Karp dynamic programming). */
    static Result heldKarp(double[] fromHere, double[][] between) {
        int n = fromHere.length;
        int full = 1 << n;
        double[][] dp = new double[full][n];
        int[][] parent = new int[full][n];
        for (int m = 0; m < full; m++) {
            java.util.Arrays.fill(dp[m], Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(parent[m], -1);
        }
        for (int i = 0; i < n; i++) {
            dp[1 << i][i] = fromHere[i];
        }
        for (int mask = 1; mask < full; mask++) {
            for (int last = 0; last < n; last++) {
                double cur = dp[mask][last];
                if (cur == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int next = 0; next < n; next++) {
                    if ((mask & (1 << next)) != 0) {
                        continue;
                    }
                    int nm = mask | (1 << next);
                    double c = cur + between[last][next];
                    if (c < dp[nm][next]) {
                        dp[nm][next] = c;
                        parent[nm][next] = last;
                    }
                }
            }
        }
        int fm = full - 1;
        int end = 0;
        for (int i = 1; i < n; i++) {
            if (dp[fm][i] < dp[fm][end]) {
                end = i;
            }
        }
        int[] order = new int[n];
        int mask = fm;
        int at = end;
        for (int k = n - 1; k >= 0; k--) {
            order[k] = at;
            int p = parent[mask][at];
            mask &= ~(1 << at);
            at = p;
        }
        double t = travel(order, fromHere, between);
        return new Result(order, t, t, true);
    }

    /**
     * The order with the least travel through EVERY room, whatever they hold - Auto Secret's "Least Etherwarps" (killer560,
     * 2026-10-18: "as few etherwarps possible pathfinding the whole dungeon no room order preference"). The trips are in
     * ticks, which are a warp count times a constant ({@code RoomTour}), so least travel is fewest warps. Always exact, on
     * the calling thread, with no time limit (killer560, 2026-10-19: "give it no time budget"): {@code budgetNanos} is
     * ignored and kept only for callers. Auto Secret itself uses {@link LeastWarpSearch#order}, which answers at once and
     * keeps searching in the background.
     */
    public static Result shortest(double[] fromHere, double[][] between, long budgetNanos) {
        LeastWarpSearch.Snapshot s = LeastWarpSearch.solve(fromHere, between, () -> false);
        return new Result(s.order(), s.travel(), s.travel(), s.proven());
    }

    /** Value per tick of the trip and the work, next room first, from wherever the last one ended. */
    public static int[] greedy(double[] fromHere, double[][] between, double[] weight, double[] work) {
        int n = fromHere.length;
        boolean[] used = new boolean[n];
        int[] order = new int[n];
        int prev = -1;
        for (int k = 0; k < n; k++) {
            int pick = -1;
            double bestRate = Double.NEGATIVE_INFINITY;
            double bestTime = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                double time = (prev < 0 ? fromHere[i] : between[prev][i]) + work[i];
                double rate = weight[i] / Math.max(1.0, time);
                if (rate > bestRate + 1e-12 || Math.abs(rate - bestRate) <= 1e-12 && time < bestTime) {
                    bestRate = rate;
                    bestTime = time;
                    pick = i;
                }
            }
            used[pick] = true;
            order[k] = pick;
            prev = pick;
        }
        return order;
    }

    /** Moves single rooms to other places in the order while that lowers the cost (or-opt with segments of one). */
    static int[] improve(int[] order, double[] fromHere, double[][] between, double[] weight, double[] work) {
        int[] cur = order.clone();
        double cost = evaluate(cur, fromHere, between, weight, work)[0];
        boolean better = true;
        int rounds = 0;
        while (better && rounds++ < 50) {
            better = false;
            for (int i = 0; i < cur.length; i++) {
                for (int j = 0; j < cur.length; j++) {
                    if (i == j) {
                        continue;
                    }
                    int[] moved = move(cur, i, j);
                    double c = evaluate(moved, fromHere, between, weight, work)[0];
                    if (c < cost - 1e-9) {
                        cur = moved;
                        cost = c;
                        better = true;
                    }
                }
            }
        }
        return cur;
    }

    private static int[] move(int[] order, int from, int to) {
        List<Integer> l = new ArrayList<>();
        for (int v : order) {
            l.add(v);
        }
        int v = l.remove(from);
        l.add(to, v);
        int[] out = new int[order.length];
        for (int k = 0; k < out.length; k++) {
            out[k] = l.get(k);
        }
        return out;
    }

    /** Depth-first branch and bound over the orders, cheapest-looking room first at every level. */
    private static final class Search {
        final double[] fromHere;
        final double[][] between;
        final double[] weight;
        final double[] work;
        final long deadline;
        final int n;
        final int[] path;
        final boolean[] used;
        double bestCost;
        int[] bestOrder;
        boolean timedOut;
        long steps;

        Search(double[] fromHere, double[][] between, double[] weight, double[] work, long deadline) {
            this.fromHere = fromHere;
            this.between = between;
            this.weight = weight;
            this.work = work;
            this.deadline = deadline;
            this.n = fromHere.length;
            this.path = new int[n];
            this.used = new boolean[n];
        }

        void run() {
            dfs(0, -1, 0, 0);
        }

        private void dfs(int depth, int prev, double t, double cost) {
            if (timedOut) {
                return;
            }
            if ((++steps & 1023) == 0 && System.nanoTime() > deadline) {
                timedOut = true;
                return;
            }
            if (depth == n) {
                if (cost < bestCost - 1e-9) {
                    bestCost = cost;
                    bestOrder = path.clone();
                }
                return;
            }
            if (cost + bound(prev, t) >= bestCost - 1e-9) {
                return;
            }
            // Children cheapest-looking first (value per tick from here), so a good order is found early.
            Integer[] kids = new Integer[n - depth];
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (!used[i]) {
                    kids[k++] = i;
                }
            }
            double[] key = new double[n];
            for (Integer i : kids) {
                double time = (prev < 0 ? fromHere[i] : between[prev][i]) + work[i];
                key[i] = -weight[i] / Math.max(1.0, time);
            }
            Arrays.sort(kids, (a, b) -> Double.compare(key[a], key[b]));
            for (int i : kids) {
                double nt = t + (prev < 0 ? fromHere[i] : between[prev][i]) + work[i];
                used[i] = true;
                path[depth] = i;
                dfs(depth + 1, i, nt, cost + weight[i] * nt);
                used[i] = false;
                if (timedOut) {
                    return;
                }
            }
        }

        /**
         * A lower bound on what the rooms left still add: each is done no sooner than now plus its cheapest way in plus its
         * work, and, taken one after another, at least the works of the rooms before it - so the rooms sorted by Smith's
         * rule over (cheapest way in + work) give a bound that never overestimates.
         */
        private double bound(int prev, double t) {
            int m = 0;
            double[] p = new double[n];
            double[] w = new double[n];
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                double in = prev < 0 ? fromHere[i] : between[prev][i];
                for (int j = 0; j < n; j++) {
                    if (!used[j] && j != i) {
                        in = Math.min(in, between[j][i]);
                    }
                }
                p[m] = Math.max(0, in) + work[i];
                w[m] = weight[i];
                m++;
            }
            Integer[] idx = new Integer[m];
            for (int i = 0; i < m; i++) {
                idx[i] = i;
            }
            final double[] pp = p;
            final double[] ww = w;
            Arrays.sort(idx, (a, b) -> Double.compare(pp[a] * ww[b], pp[b] * ww[a]));
            double time = t;
            double add = 0;
            for (int i : idx) {
                time += pp[i];
                add += ww[i] * time;
            }
            return add;
        }
    }
}
