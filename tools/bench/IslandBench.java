import com.killer560.hub.pathfinding.GraphPathfinder;
import com.killer560.hub.pathfinding.IslandGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The auto pathfinder's graph searches on real island graphs (pathfind-bench-1126). No game needed:
 * tools/bench/island.sh DIR_WITH_ISLAND_JSONS.
 *
 * <p>LONG TRIPS: per island, -Dtrips=N (20) seeded sources, each to the node farthest from it over the graph (a
 * cross-island trip). {@link GraphPathfinder#shortestPath} is plain Dijkstra, so its length IS the graph optimum; the
 * bench checks that against a Bellman-Ford pass, and reports the time per search (median of 5 runs after warm-up), the
 * nodes it settled, and the path's length over the straight line (how far the graph's roads wander; not a gap, as the
 * world between nodes is not known offline).
 *
 * <p>TOURS: the Fairy Soul route order ({@link GraphPathfinder#orderRoute}, greedy + 2-opt + relocation) against the
 * EXACT best open tour (Held-Karp dynamic program) on -Dsubsets=N (12) seeded subsets of -Dsubset=K (13) souls per
 * island, and on the whole island's set against the best of -Drestarts=N (64) randomized improvement runs (an upper
 * bound on the best, not a proof) and a proved lower bound (each soul's cheapest way in, plus nothing for the end).
 * Prints ISLAND / TOUR lines and a SUMMARY.
 */
public final class IslandBench {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int trips = Integer.getInteger("trips", 20);
        int subsets = Integer.getInteger("subsets", 12);
        int subset = Integer.getInteger("subset", 13);
        int restarts = Integer.getInteger("restarts", 64);
        long seed = Long.getLong("seed", 1126L);
        List<Double> tourGaps = new ArrayList<>();
        int tourExact = 0;
        int tourWorse = 0;
        List<Double> fullGaps = new ArrayList<>();
        List<Double> tripMs = new ArrayList<>();
        int mismatches = 0;
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String island = file.getFileName().toString().replace(".json", "");
            IslandGraph g = IslandGraph.parse(island, Files.readString(file));
            int n = g.nodes.length;
            Random rng = new Random(seed ^ island.hashCode());
            // ---- long trips
            List<Double> ms = new ArrayList<>();
            List<Double> wander = new ArrayList<>();
            long settledSum = 0;
            for (int t = 0; t < trips; t++) {
                int src = rng.nextInt(n);
                GraphPathfinder.Tree all = GraphPathfinder.dijkstra(g, src, -1);
                int far = -1;
                for (int v = 0; v < n; v++) {
                    if (all.reachable(v) && (far < 0 || all.dist()[v] > all.dist()[far])) {
                        far = v;
                    }
                }
                if (far < 0 || far == src) {
                    continue;
                }
                double[] bf = bellmanFord(g, src);
                if (Math.abs(bf[far] - all.dist()[far]) > 1e-6) {
                    mismatches++;
                }
                for (int w = 0; w < 20; w++) {
                    GraphPathfinder.shortestPath(g, src, far);
                }
                double[] runs = new double[5];
                for (int r = 0; r < runs.length; r++) {
                    long t0 = System.nanoTime();
                    GraphPathfinder.shortestPath(g, src, far);
                    runs[r] = (System.nanoTime() - t0) / 1e6;
                }
                Arrays.sort(runs);
                ms.add(runs[2]);
                int settled = 0;
                for (int v = 0; v < n; v++) {
                    settled += all.dist()[v] <= all.dist()[far] ? 1 : 0;
                }
                settledSum += settled;
                IslandGraph.Node a = g.nodes[src];
                IslandGraph.Node b = g.nodes[far];
                double straight = Math.sqrt(a.distSq(b.x, b.y, b.z));
                wander.add(all.dist()[far] / Math.max(1.0, straight));
            }
            Collections.sort(ms);
            Collections.sort(wander);
            tripMs.addAll(ms);
            if (!ms.isEmpty()) {
                System.out.printf(Locale.ROOT, "ISLAND %s: %d nodes; %d long trips, Dijkstra median %.3f ms max %.3f,"
                                + " settled mean %d nodes; graph length over straight line median %.2f max %.2f%n", island, n,
                        ms.size(), ms.get(ms.size() / 2), ms.get(ms.size() - 1), settledSum / ms.size(),
                        wander.get(wander.size() / 2), wander.get(wander.size() - 1));
            }
            // ---- tours
            List<IslandGraph.Node> souls = new ArrayList<>();
            for (IslandGraph.Node node : g.nodes) {
                if (node.hasTag(IslandGraph.TAG_FAIRY_SOUL)) {
                    souls.add(node);
                }
            }
            if (souls.size() < 4) {
                continue;
            }
            for (int si = 0; si < subsets && souls.size() > subset; si++) {
                List<IslandGraph.Node> pick = new ArrayList<>(souls);
                Collections.shuffle(pick, rng);
                pick = pick.subList(0, subset);
                IslandGraph.Node start = g.nodes[rng.nextInt(n)];
                double[][] cost = matrix(g, start, pick);
                if (cost == null) {
                    continue;
                }
                int[] order = GraphPathfinder.orderRoute(g, start, pick, GraphPathfinder.GRAPH_DISTANCE);
                double ours = tourCost(cost, order);
                double best = heldKarp(cost);
                double gap = 100.0 * (ours - best) / best;
                tourGaps.add(gap);
                tourExact += gap < 1e-6 ? 1 : 0;
                tourWorse += gap >= 1e-6 ? 1 : 0;
                System.out.printf(Locale.ROOT, "TOUR %s subset %d (%d souls): ours %.1f best %.1f gap %.2f%%%n", island, si,
                        subset, ours, best, gap);
            }
            IslandGraph.Node start = g.nodes[rng.nextInt(n)];
            double[][] cost = matrix(g, start, souls);
            if (cost == null) {
                continue;
            }
            long t0 = System.nanoTime();
            int[] order = GraphPathfinder.orderRoute(g, start, souls, GraphPathfinder.GRAPH_DISTANCE);
            double oms = (System.nanoTime() - t0) / 1e6;
            double ours = tourCost(cost, order);
            double up = ours;
            Random rr = new Random(seed);
            for (int r = 0; r < restarts; r++) {
                up = Math.min(up, randomized(cost, rr));
            }
            double low = lowerBound(cost);
            fullGaps.add(100.0 * (ours - up) / up);
            System.out.printf(Locale.ROOT, "TOUR %s all %d souls: ours %.1f (%.1f ms), best found %.1f, proved lower bound"
                    + " %.1f; ours over best found %.2f%%%n", island, souls.size(), ours, oms, up, low, 100.0 * (ours - up) / up);
        }
        Collections.sort(tourGaps);
        Collections.sort(fullGaps);
        Collections.sort(tripMs);
        System.out.printf(Locale.ROOT, "SUMMARY trips %d: Dijkstra median %.3f ms p90 %.3f max %.3f, %d Bellman-Ford"
                        + " mismatch(es); tours %d: exact on %d, worse on %d, gap median %.2f%% p90 %.2f%% worst %.2f%%;"
                        + " whole-island tours over best found: median %.2f%% worst %.2f%%%n", tripMs.size(),
                pct(tripMs, 0.5), pct(tripMs, 0.9), pct(tripMs, 1), mismatches, tourGaps.size(), tourExact, tourWorse,
                pct(tourGaps, 0.5), pct(tourGaps, 0.9), pct(tourGaps, 1), pct(fullGaps, 0.5), pct(fullGaps, 1));
        System.out.println(mismatches == 0 ? "ISLAND-CHECK OK" : "ISLAND-CHECK FAILED");
        System.exit(mismatches == 0 ? 0 : 1);
    }

    static double pct(List<Double> v, double p) {
        return v.isEmpty() ? Double.NaN : v.get(Math.min(v.size() - 1, (int) (v.size() * p)));
    }

    /** Stop 0 = start, 1..k = targets; null when some target is unreachable from some stop. */
    static double[][] matrix(IslandGraph g, IslandGraph.Node start, List<IslandGraph.Node> targets) {
        int k = targets.size();
        IslandGraph.Node[] stops = new IslandGraph.Node[k + 1];
        stops[0] = start;
        for (int i = 0; i < k; i++) {
            stops[i + 1] = targets.get(i);
        }
        double[][] c = new double[k + 1][k + 1];
        for (int i = 0; i <= k; i++) {
            double[] d = GraphPathfinder.dijkstra(g, stops[i].index, -1).dist();
            for (int j = 0; j <= k; j++) {
                c[i][j] = i == j ? 0 : d[stops[j].index];
                if (j > 0 && c[i][j] >= GraphPathfinder.UNREACHABLE) {
                    return null;
                }
            }
        }
        return c;
    }

    static double tourCost(double[][] c, int[] order) {
        double t = 0;
        int at = 0;
        for (int o : order) {
            t += c[at][o + 1];
            at = o + 1;
        }
        return t;
    }

    /** Exact best open tour from stop 0 through every other stop, end free. */
    static double heldKarp(double[][] c) {
        int k = c.length - 1;
        int full = 1 << k;
        double[][] dp = new double[full][k];
        for (double[] row : dp) {
            Arrays.fill(row, Double.MAX_VALUE);
        }
        for (int j = 0; j < k; j++) {
            dp[1 << j][j] = c[0][j + 1];
        }
        for (int m = 1; m < full; m++) {
            for (int j = 0; j < k; j++) {
                double v = dp[m][j];
                if (v == Double.MAX_VALUE || (m & (1 << j)) == 0) {
                    continue;
                }
                for (int t = 0; t < k; t++) {
                    if ((m & (1 << t)) == 0) {
                        double nv = v + c[j + 1][t + 1];
                        int nm = m | (1 << t);
                        if (nv < dp[nm][t]) {
                            dp[nm][t] = nv;
                        }
                    }
                }
            }
        }
        double best = Double.MAX_VALUE;
        for (int j = 0; j < k; j++) {
            best = Math.min(best, dp[full - 1][j]);
        }
        return best;
    }

    /** A random order improved by 2-opt-style segment reversal and relocation until neither helps. */
    static double randomized(double[][] c, Random rng) {
        int k = c.length - 1;
        List<Integer> l = new ArrayList<>();
        for (int i = 1; i <= k; i++) {
            l.add(i);
        }
        Collections.shuffle(l, rng);
        int[] route = new int[k + 1];
        for (int i = 0; i < k; i++) {
            route[i + 1] = l.get(i);
        }
        boolean improved = true;
        double cur = routeCost(route, c);
        while (improved) {
            improved = false;
            for (int i = 1; i < k && !improved; i++) {
                for (int j = i + 1; j <= k && !improved; j++) {
                    int[] r2 = route.clone();
                    for (int a = i, b = j; a < b; a++, b--) {
                        int t = r2[a];
                        r2[a] = r2[b];
                        r2[b] = t;
                    }
                    double v = routeCost(r2, c);
                    if (v + 1e-6 < cur) {
                        route = r2;
                        cur = v;
                        improved = true;
                    }
                }
            }
            for (int from = 1; from <= k && !improved; from++) {
                for (int len = 1; len <= 3 && from + len - 1 <= k && !improved; len++) {
                    for (int to = 1; to <= k - len + 1 && !improved; to++) {
                        if (to == from) {
                            continue;
                        }
                        int[] r2 = moveSegment(route, from, len, to);
                        double v = routeCost(r2, c);
                        if (v + 1e-6 < cur) {
                            route = r2;
                            cur = v;
                            improved = true;
                        }
                    }
                }
            }
        }
        return cur;
    }

    static int[] moveSegment(int[] route, int from, int len, int to) {
        List<Integer> l = new ArrayList<>();
        for (int v : route) {
            l.add(v);
        }
        List<Integer> seg = new ArrayList<>(l.subList(from, from + len));
        l.subList(from, from + len).clear();
        l.addAll(Math.min(to, l.size()), seg);
        int[] out = new int[route.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = l.get(i);
        }
        return out;
    }

    static double routeCost(int[] route, double[][] c) {
        double t = 0;
        for (int i = 1; i < route.length; i++) {
            t += c[route[i - 1]][route[i]];
        }
        return t;
    }

    /** Every target is entered exactly once: the sum of each target's cheapest way in is a lower bound. */
    static double lowerBound(double[][] c) {
        double s = 0;
        for (int j = 1; j < c.length; j++) {
            double m = Double.MAX_VALUE;
            for (int i = 0; i < c.length; i++) {
                if (i != j) {
                    m = Math.min(m, c[i][j]);
                }
            }
            s += m;
        }
        return s;
    }

    static double[] bellmanFord(IslandGraph g, int src) {
        int n = g.nodes.length;
        double[] d = new double[n];
        Arrays.fill(d, GraphPathfinder.UNREACHABLE);
        d[src] = 0;
        for (int it = 0; it < n; it++) {
            boolean ch = false;
            for (IslandGraph.Node u : g.nodes) {
                if (d[u.index] >= GraphPathfinder.UNREACHABLE) {
                    continue;
                }
                int[] out = u.neighbours();
                double[] w = u.weights();
                for (int i = 0; i < out.length; i++) {
                    if (d[u.index] + w[i] < d[out[i]] - 1e-12) {
                        d[out[i]] = d[u.index] + w[i];
                        ch = true;
                    }
                }
            }
            if (!ch) {
                break;
            }
        }
        return d;
    }
}
