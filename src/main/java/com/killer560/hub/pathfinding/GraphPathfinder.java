package com.killer560.hub.pathfinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Shortest paths and multi-target route ordering on an {@link IslandGraph}. Pure Java, thread-safe (no shared state).
 * <p>
 * Same algorithms SkyHanni uses (https://github.com/hannibal002/SkyHanni, LGPL-2.1):
 * <ul>
 * <li>{@code utils/GraphUtils.kt findDijkstraDistances}: plain Dijkstra from the node closest to the player, with an
 * optional early bail-out once the goal is settled. Dijkstra rather than A*: jump pads / teleport pads are edges whose
 * weight is far below their straight-line length, so a Euclidean A* heuristic would not be admissible.</li>
 * <li>{@code utils/navigation/NavigationUtils.kt getRoute}: distance matrix from one Dijkstra per target, greedy
 * nearest-neighbour tour from the start, then a 2-opt pass. SkyHanni's 2-opt only compares the two boundary edges,
 * which is wrong on a directed graph (reversing a segment reverses every edge inside it); this version re-costs the
 * reversed interior too.</li>
 * </ul>
 */
public final class GraphPathfinder {

    /** Stand-in for "unreachable" that still sums safely. */
    public static final double UNREACHABLE = 1.0e9;

    private GraphPathfinder() {
    }

    public record Path(int[] nodes, double length) {
        public boolean isEmpty() {
            return nodes.length == 0;
        }
    }

    public record Tree(int source, double[] dist, int[] prev) {
        public boolean reachable(int node) {
            return dist[node] < UNREACHABLE;
        }

        public Path pathTo(int target) {
            if (!reachable(target)) {
                return new Path(new int[0], UNREACHABLE);
            }
            List<Integer> rev = new ArrayList<>();
            int cur = target;
            int guard = 0;
            while (cur != -1 && guard++ < dist.length + 1) {
                rev.add(cur);
                if (cur == source) {
                    break;
                }
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
            if (done[u] || top[0] > dist[u]) {
                continue;
            }
            done[u] = true;
            if (u == stopAt) {
                break;
            }
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

    public static Path shortestPath(IslandGraph graph, int from, int to) {
        return dijkstra(graph, from, to).pathTo(to);
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
        if (n == 0) {
            return new int[0];
        }
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
        int[] route = greedy(cost);
        improve(route, cost);
        int[] out = new int[n];
        for (int i = 1; i <= n; i++) {
            out[i - 1] = route[i] - 1;
        }
        return out;
    }

    /** Nearest-neighbour tour over the matrix, starting at stop 0. */
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
        if (m < 4) {
            return;
        }
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
            if (!improved) {
                return;
            }
        }
    }

    /** 2-opt, then single-stop relocation (Or-opt), repeated until neither helps (bounded). */
    static void improve(int[] route, double[][] cost) {
        for (int round = 0; round < 20; round++) {
            twoOpt(route, cost, 400);
            if (!relocateOnce(route, cost)) {
                return;
            }
        }
    }

    /** Moves one stop to a better position if that shortens the route. @return whether a move was made. */
    static boolean relocateOnce(int[] route, double[][] cost) {
        int m = route.length;
        if (m < 3) {
            return false;
        }
        double base = routeCost(route, cost);
        int[] tmp = new int[m];
        int[] without = new int[m - 1];
        for (int from = 1; from < m; from++) {
            int stop = route[from];
            for (int k = 0, w = 0; k < m; k++) {
                if (k != from) {
                    without[w++] = route[k];
                }
            }
            for (int to = 1; to < m; to++) {
                if (to == from) {
                    continue;
                }
                // insert "stop" before without[to] (to == m-1 appends)
                System.arraycopy(without, 0, tmp, 0, to);
                tmp[to] = stop;
                System.arraycopy(without, to, tmp, to + 1, m - 1 - to);
                if (routeCost(tmp, cost) + 1e-6 < base) {
                    System.arraycopy(tmp, 0, route, 0, m);
                    return true;
                }
            }
        }
        return false;
    }

    public static double routeCost(int[] route, double[][] cost) {
        double total = 0;
        for (int k = 1; k < route.length; k++) {
            total += cost[route[k - 1]][route[k]];
        }
        return total;
    }
}
