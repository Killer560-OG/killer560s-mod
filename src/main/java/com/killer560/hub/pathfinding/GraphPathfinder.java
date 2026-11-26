package com.killer560.hub.pathfinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Shortest paths and multi-target route ordering on an {@link IslandGraph}. Pure Java, thread-safe (no shared state).
 * <p>
 * Standard algorithms (LGPL-2.1):
 * <ul>
 * <li>{@code utils/GraphUtils.kt findDijkstraDistances}: plain Dijkstra from the node closest to the player, with an
 * optional early bail-out once the goal is settled. Dijkstra rather than A*: jump pads / teleport pads are edges whose
 * weight is far below their straight-line length, so a Euclidean A* heuristic would not be admissible.</li>
 * <li>{@code utils/navigation/NavigationUtils.kt getRoute}: distance matrix from one Dijkstra per target, greedy
 * nearest-neighbor tour from the start, then a 2-opt pass. The 2-opt only compares the two boundary edges,
 * which is wrong on a directed graph (reversing a segment reverses every edge inside it); this version re-costs the
 * reversed interior too.</li>
 * </ul>
 * Route order since pathfind-bench-1126 (tools/bench/IslandBench on the 19 real island graphs): the greedy tour plus
 * 2-opt and single-stop moves was 1.6% over the exact best at the median of 60 seeded 13-soul sets, 10.7% at p90 and
 * 34.9% at worst, and 18% over the best found on the Hub's 80 souls. Now: up to {@link #EXACT_STOPS} stops the exact
 * best (Held-Karp); above, 2-opt plus moves of runs of up to three stops, from the greedy tour and from
 * {@link #RESTARTS} seeded random orders, the cheapest kept (same input, same order).
 */
public final class GraphPathfinder {

    /** Stand-in for "unreachable" that still sums safely. */
    public static final double UNREACHABLE = 1.0e9;

    private GraphPathfinder() {
    }

    public record Path(int[] nodes, double length) {
        public boolean isEmpty() { return nodes.length == 0; }
    }

    public record Tree(int source, double[] dist, int[] prev) {
        public boolean reachable(int node) { return dist[node] < UNREACHABLE; }

        public Path pathTo(int target) {
            if (!reachable(target)) return new Path(new int[0], UNREACHABLE);
            List<Integer> rev = new ArrayList<>();
            int cur = target;
            int guard = 0;
            while (cur != -1 && guard++ < dist.length + 1) {
                rev.add(cur);
                if (cur == source) break;
                cur = prev[cur];
            }
            int[] out = new int[rev.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = rev.get(rev.size() - 1 - i);
            }
            return new Path(out, dist[target]);
        }
    }

    /** Dijkstra from {@code source}; stops early once {@code stopAt} (>= 0) is settled. */
    public static Tree dijkstra(IslandGraph graph, int source, int stopAt) {
        int n = graph.nodes.length;
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, UNREACHABLE);
        Arrays.fill(prev, -1);
        dist[source] = 0.0;
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        queue.add(new double[]{0.0, source});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (done[u] || top[0] > dist[u]) continue;
            done[u] = true;
            if (u == stopAt) break;
            IslandGraph.Node node = graph.nodes[u];
            int[] out = node.neighbours();
            double[] w = node.weights();
            for (int i = 0; i < out.length; i++) {
                int v = out[i];
                double nd = dist[u] + w[i];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prev[v] = u;
                    queue.add(new double[]{nd, v});
                }
            }
        }
        return new Tree(source, dist, prev);
    }

    public static Path shortestPath(IslandGraph graph, int from, int to) { return dijkstra(graph, from, to).pathTo(to); }

    /** The candidate cheapest to reach over the graph from the node nearest (x, y, z); the first when none is reachable. */
    public static IslandGraph.Node closestByCost(IslandGraph graph, double x, double y, double z,
                                                 List<IslandGraph.Node> candidates) {
        IslandGraph.Node start = graph.nearest(x, y, z);
        double[] dist = dijkstra(graph, start.index, -1).dist();
        IslandGraph.Node best = candidates.get(0);
        double bestCost = Double.MAX_VALUE;
        for (IslandGraph.Node node : candidates) {
            if (dist[node.index] < bestCost) {
                bestCost = dist[node.index];
                best = node;
            }
        }
        return best;
    }

    /** Cost between two route stops, given the graph distance between their nodes (may be {@link #UNREACHABLE}). */
    @FunctionalInterface
    public interface LegCost {
        double cost(IslandGraph.Node from, IslandGraph.Node to, double graphDistance);
    }

    public static final LegCost GRAPH_DISTANCE = (a, b, d) -> d;

    /**
     * Orders {@code targets} into a short open tour starting at {@code start} (the end is free).
     * @return indices into {@code targets} in visiting order.
     */
    public static int[] orderRoute(IslandGraph graph, IslandGraph.Node start, List<IslandGraph.Node> targets, LegCost legCost) {
        int n = targets.size();
        if (n == 0) return new int[0];
        // stop 0 = start, stops 1..n = targets
        IslandGraph.Node[] stops = new IslandGraph.Node[n + 1];
        stops[0] = start;
        for (int i = 0; i < n; i++) {
            stops[i + 1] = targets.get(i);
        }
        double[][] cost = new double[n + 1][n + 1];
        for (int i = 0; i <= n; i++) {
            Tree tree = dijkstra(graph, stops[i].index, -1);
            for (int j = 0; j <= n; j++) {
                cost[i][j] = i == j ? 0.0 : Math.min(UNREACHABLE, legCost.cost(stops[i], stops[j], tree.dist[stops[j].index]));
            }
        }
        int[] route = n <= EXACT_STOPS ? heldKarp(cost) : bestOfRestarts(cost);
        int[] out = new int[n];
        for (int i = 1; i <= n; i++) {
            out[i - 1] = route[i] - 1;
        }
        return out;
    }

    /** Nearest-neighbor tour over the matrix, starting at stop 0. */
    static int[] greedy(double[][] cost) {
        int m = cost.length;
        int[] route = new int[m];
        boolean[] used = new boolean[m];
        route[0] = 0;
        used[0] = true;
        for (int k = 1; k < m; k++) {
            int cur = route[k - 1];
            int best = -1;
            double bestC = Double.MAX_VALUE;
            for (int j = 1; j < m; j++) {
                if (!used[j] && cost[cur][j] < bestC) {
                    bestC = cost[cur][j];
                    best = j;
                }
            }
            route[k] = best;
            used[best] = true;
        }
        return route;
    }

    /** Open-path 2-opt on a directed cost matrix; route[0] stays fixed. First-improvement, bounded passes. */
    static void twoOpt(int[] route, double[][] cost, int maxPasses) {
        int m = route.length;
        if (m < 4) return;
        double[] fwd = new double[m];
        double[] bwd = new double[m];
        for (int pass = 0; pass < maxPasses; pass++) {
            // fwd[k] = sum cost(route[t-1] -> route[t]) for t=1..k ; bwd[k] = sum cost(route[t] -> route[t-1]) for t=1..k
            for (int k = 1; k < m; k++) {
                fwd[k] = fwd[k - 1] + cost[route[k - 1]][route[k]];
                bwd[k] = bwd[k - 1] + cost[route[k]][route[k - 1]];
            }
            boolean improved = false;
            outer:
            for (int i = 1; i < m - 1; i++) {
                for (int j = i + 1; j < m; j++) {
                    double inForward = fwd[j] - fwd[i];
                    double inReverse = bwd[j] - bwd[i];
                    double before = cost[route[i - 1]][route[i]] + inForward + (j + 1 < m ? cost[route[j]][route[j + 1]] : 0.0);
                    double after = cost[route[i - 1]][route[j]] + inReverse + (j + 1 < m ? cost[route[i]][route[j + 1]] : 0.0);
                    if (after + 1e-6 < before) {
                        for (int a = i, b = j; a < b; a++, b--) {
                            int t = route[a];
                            route[a] = route[b];
                            route[b] = t;
                        }
                        improved = true;
                        break outer;
                    }
                }
            }
            if (!improved) return;
        }
    }

    /** Up to this many stops the route order is the exact best (Held-Karp: 2^n * n^2 steps, ~0.6 M at 12). */
    static final int EXACT_STOPS = 12;
    /** Random starting orders tried above {@link #EXACT_STOPS}, besides the greedy tour. */
    static final int RESTARTS = 24;

    /** The exact cheapest open tour from stop 0 through every other stop (end free). */
    static int[] heldKarp(double[][] cost) {
        int k = cost.length - 1;
        int full = 1 << k;
        double[] dp = new double[full * k];
        byte[] from = new byte[full * k];
        Arrays.fill(dp, Double.MAX_VALUE);
        for (int j = 0; j < k; j++) {
            dp[(1 << j) * k + j] = cost[0][j + 1];
            from[(1 << j) * k + j] = -1;
        }
        for (int m = 1; m < full; m++) {
            for (int j = 0; j < k; j++) {
                double v = dp[m * k + j];
                if (v == Double.MAX_VALUE) continue;
                for (int t = 0; t < k; t++) {
                    if ((m & (1 << t)) != 0) continue;
                    int at = (m | (1 << t)) * k + t;
                    double nv = v + cost[j + 1][t + 1];
                    if (nv < dp[at]) {
                        dp[at] = nv;
                        from[at] = (byte) j;
                    }
                }
            }
        }
        int last = 0;
        for (int j = 1; j < k; j++) {
            if (dp[(full - 1) * k + j] < dp[(full - 1) * k + last]) last = j;
        }
        int[] route = new int[k + 1];
        int m = full - 1;
        for (int pos = k; pos >= 1; pos--) {
            route[pos] = last + 1;
            int prev = from[m * k + last];
            m &= ~(1 << last);
            last = prev;
        }
        return route;
    }

    /** The greedy tour and {@link #RESTARTS} seeded random orders, each improved; the cheapest. */
    static int[] bestOfRestarts(double[][] cost) {
        int[] best = greedy(cost);
        improve(best, cost);
        double bestCost = routeCost(best, cost);
        java.util.Random rng = new java.util.Random(cost.length * 1_000_003L);
        for (int r = 0; r < RESTARTS; r++) {
            int[] route = new int[cost.length];
            for (int i = 1; i < route.length; i++) {
                int j = 1 + rng.nextInt(i);
                route[i] = route[j];
                route[j] = i;
            }
            improve(route, cost);
            double c = routeCost(route, cost);
            if (c + 1e-6 < bestCost) {
                best = route;
                bestCost = c;
            }
        }
        return best;
    }

    /** 2-opt, then moving runs of one to three stops (Or-opt), repeated until neither helps (bounded). */
    static void improve(int[] route, double[][] cost) {
        for (int round = 0; round < 200; round++) {
            twoOpt(route, cost, 400);
            if (!orOptOnce(route, cost)) return;
        }
    }

    /**
     * Moves one run of up to three consecutive stops (kept in order) to the place that shortens the route most, priced
     * in O(1) a move. @return whether a move was made.
     */
    static boolean orOptOnce(int[] route, double[][] cost) {
        int m = route.length;
        int bestI = -1;
        int bestLen = 0;
        int bestT = -1;
        double bestGain = 1e-6;
        for (int len = 1; len <= 3; len++) {
            for (int i = 1; i + len - 1 < m; i++) {
                int s0 = route[i];
                int sL = route[i + len - 1];
                int prev = route[i - 1];
                boolean hasNext = i + len < m;
                double removed = cost[prev][s0] + (hasNext ? cost[sL][route[i + len]] - cost[prev][route[i + len]] : 0.0);
                for (int t = 0; t < m; t++) {
                    if (t >= i - 1 && t <= i + len - 1) continue;
                    int a = route[t];
                    boolean hasB = t + 1 < m;
                    double added = cost[a][s0] + (hasB ? cost[sL][route[t + 1]] - cost[a][route[t + 1]] : 0.0);
                    if (removed - added > bestGain) {
                        bestGain = removed - added;
                        bestI = i;
                        bestLen = len;
                        bestT = t;
                    }
                }
            }
        }
        if (bestI < 0) return false;
        int[] out = new int[m];
        int w = 0;
        for (int t = 0; t < m; t++) {
            if (t >= bestI && t < bestI + bestLen) continue;
            out[w++] = route[t];
            if (t == bestT) {
                for (int q = 0; q < bestLen; q++) out[w++] = route[bestI + q];
            }
        }
        System.arraycopy(out, 0, route, 0, m);
        return true;
    }

    public static double routeCost(int[] route, double[][] cost) {
        double total = 0;
        for (int k = 1; k < route.length; k++) {
            total += cost[route[k - 1]][route[k]];
        }
        return total;
    }
}
