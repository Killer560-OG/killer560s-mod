import com.killer560.hub.livemap.autoclear.EtherSearch;
import com.killer560.hub.livemap.autoclear.ExactWarpSearch;
import com.killer560.hub.livemap.autoclear.WarpGraph;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.roomsim.SimFloorLayout;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * SUPER LONG Interactive Map trips (pathfind-bench-1126): on whole 6x6 floors, start and goal in two rooms the most
 * doors apart (the floor's door diameter, then one less), half tile clicks and half exact blocks. Per trip:
 * the warm floor graph's plan as origin made it and as it is now (WarpGraph.shortcut off, then on, on the same graph;
 * what the map flies: warps, plan time, nodes expanded) against the exact search's
 * proved fewest warps ({@link ExactWarpSearch}, bounded above by the graph's plan, budget -Dbudget ms). A warp chain
 * runs at about one tick a warp (LESSONS-ROUTES), so warps are the trip's cost in ticks; the plan time adds
 * ceil(ms / 50) ticks before the first warp. If the exact search times out, its {@code fewerThan} is a PROVED lower
 * bound and the row says so.
 *
 * <p>-Dfloors=N (4) -Dtrips=N (6 a floor) -Dseed=N (1126) -Dbudget=MS (60000) -Dthreads=N (FloorBench's).
 * Prints one TRIP line each and a SUMMARY; exits 1 on any invalid route. tools/bench/long.sh.
 */
public final class LongBench {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/main/resources/assets/killer560smod/rooms");
        int floors = Integer.getInteger("floors", 4);
        int perFloor = Integer.getInteger("trips", 6);
        long seed = Long.getLong("seed", 1126L);
        long budget = Long.getLong("budget", 60000L) * 1_000_000L;
        FloorBench.RANGE = ExactBench.RANGE;
        Map<String, RoomLibrary.Room> rooms = ExactBench.loadRooms(dir);
        Random rng = new Random(seed);
        System.out.printf(Locale.ROOT, "LongBench: %d captures, %d floors x %d trips, seed %d, %d threads, budget %d s%n",
                rooms.size(), floors, perFloor, seed, FloorBench.THREADS, budget / 1_000_000_000L);
        List<double[]> rows = new ArrayList<>();   // {graph, best, proved, graphMs, exactMs, nodes, doors}
        int invalid = 0;
        for (int fi = 0; fi < floors; fi++) {
            SimFloorLayout.Floor lf = SimFloorLayout.generate(LayoutSim.capChampions(rooms, rng), 21, 36, 3, 9, rng);
            FloorBench.Floor f = new FloorBench.Floor(lf);
            EtherSearch.Grid grid = f::flags;
            EtherSearch probe = new EtherSearch(grid);
            List<int[]> pairs = farPairs(f, rng, perFloor);
            if (pairs.isEmpty()) {
                continue;
            }
            WarpGraph g = FloorBench.graph(f, 2);
            int[] s0 = f.spots.get(pairs.get(0)[0]).get(0);
            long w0 = System.nanoTime();
            while (g.warm(grid, () -> grid, FloorBench.WORKERS, FloorBench.THREADS, s0[0] + 0.5, s0[1] + 1.0,
                    s0[2] + 0.5, 50_000_000L)) {
                // the whole floor
            }
            System.out.printf(Locale.ROOT, "floor %d: %d rooms, graph warm %d nodes in %.0f ms%n", fi, f.rooms.size(),
                    g.nodeCount(), (System.nanoTime() - w0) / 1e6);
            WarpGraph alt = null;
            String altSpec = System.getProperty("alt");
            if (altSpec != null) {
                // -Dalt=bucket,partialFrom,extraAimPoints: a second graph of the same floor, planned on the same trips.
                String[] a = altSpec.split(",");
                alt = FloorBench.graph(f, Integer.parseInt(a[0]));
                alt.partialFrom = Double.parseDouble(a[1]);
                alt.setExtraAimPoints(a.length > 2 && Boolean.parseBoolean(a[2]));
                long a0 = System.nanoTime();
                while (alt.warm(grid, () -> grid, FloorBench.WORKERS, FloorBench.THREADS, s0[0] + 0.5, s0[1] + 1.0,
                        s0[2] + 0.5, 50_000_000L)) {
                    // the whole floor
                }
                System.out.printf(Locale.ROOT, "floor %d: alt graph %s warm %d nodes in %.0f ms%n", fi, altSpec,
                        alt.nodeCount(), (System.nanoTime() - a0) / 1e6);
            }
            for (int ti = 0; ti < pairs.size(); ti++) {
                int[] pr = pairs.get(ti);
                FloorBench.Click c = new FloorBench.Click();
                c.start = f.spots.get(pr[0]).get(rng.nextInt(f.spots.get(pr[0]).size()));
                c.goalRoom = pr[1];
                c.tile = (ti & 1) == 0;
                if (c.tile) {
                    List<Integer> cells = new ArrayList<>();
                    for (int i = 0; i < 36; i++) {
                        if (f.owner[i] == pr[1]) {
                            cells.add(i);
                        }
                    }
                    c.tileCell = cells.get(rng.nextInt(cells.size()));
                    c.exact = FloorBench.inTile(f, c.tileCell, c.start);
                    if (c.exact == null) {
                        continue;
                    }
                } else {
                    c.exact = f.spots.get(pr[1]).get(rng.nextInt(f.spots.get(pr[1]).size()));
                }
                // One plan: origin's warps are the plan before WarpGraph's shortcut (shortcutSaved more). A second
                // plan on the same graph is not the same search (firmUp's bans persist), so it is not compared.
                long t0 = System.nanoTime();
                List<EtherSearch.Hop> gp = FloorBench.planGame(g, grid, f, c, t0 + 2_000_000_000L);
                double gms = (System.nanoTime() - t0) / 1e6;
                double gms0 = gms - g.shortcutNanos / 1e6;
                int nodes = g.expandedCold + g.expandedWarm;
                int u = gp == null ? -1 : gp.size();
                int u0 = gp == null ? -1 : u + g.shortcutSaved;
                if (gp != null && FloorBench.replay(f, probe, c.start, c, gp) < 0) {
                    invalid++;
                    System.out.println("  INVALID graph route: floor " + fi + " trip " + ti);
                }
                ExactWarpSearch.Goal eg = exactGoal(f, c);
                String variants = gp == null ? "" : " graph " + u + variants(f, grid, probe, c, gp, eg);
                if (alt != null && gp != null) {
                    long a0 = System.nanoTime();
                    List<EtherSearch.Hop> ap = FloorBench.planGame(alt, grid, f, c, a0 + 2_000_000_000L);
                    variants += String.format(Locale.ROOT, " alt %s (%.2f ms%s)", ap == null ? "none" : ap.size(),
                            (System.nanoTime() - a0) / 1e6,
                            ap != null && FloorBench.replay(f, probe, c.start, c, ap) < 0 ? " INVALID" : "");
                }
                if (!variants.isEmpty()) {
                    System.out.println("  VARIANTS f" + fi + " t" + ti + ":" + variants);
                }
                if (!Boolean.parseBoolean(System.getProperty("exact", "true"))) {
                    rows.add(new double[]{u0, u, u, 0, gms0, gms, 0, nodes});
                    System.out.printf(Locale.ROOT, "TRIP f%d t%d %s doors %d: origin %d warps (%.2f ms), now %d (%.2f ms,"
                                    + " %d nodes, shortcut saved %d)%n", fi, ti, c.tile ? "tile " : "block", pr[2], u0, gms0,
                            u, gms, nodes, g.shortcutSaved);
                    continue;
                }
                ExactWarpSearch ex = ExactBench.search(f, grid, 0, FloorBench.SIZE - 1, 0, FloorBench.SIZE - 1);
                long t1 = System.nanoTime();
                List<EtherSearch.Hop> ep = ex.plan(FloorBench.startHop(c.start), eg, u0 > 0 ? u0 - 1 : 40, t1 + budget);
                double ems = (System.nanoTime() - t1) / 1e6;
                int best;
                boolean proved;
                if (ep != null) {
                    best = ep.size();
                    proved = true;
                    if (FloorBench.replay(f, probe, c.start, c, ep) < 0) {
                        invalid++;
                        System.out.println("  INVALID exact route: floor " + fi + " trip " + ti);
                    }
                } else if (ex.timedOut) {
                    best = ex.fewerThan;   // a proved lower bound, not a route
                    proved = false;
                } else {
                    best = u0;             // proved: nothing shorter than origin's plan
                    proved = u0 > 0;
                }
                rows.add(new double[]{u0, u, best, proved ? 1 : 0, gms0, gms, ems, nodes});
                System.out.printf(Locale.ROOT, "TRIP f%d t%d %s doors %d: origin %d warps (%.2f ms), now %d (%.2f ms, %d"
                                + " nodes), best %s%d (exact %.0f ms, %d landings), gap origin %s now %s%n", fi, ti,
                        c.tile ? "tile " : "block", pr[2], u0, gms0, u, gms, nodes, proved ? "" : ">=", best, ems,
                        ex.landings, gap(u0, best), gap(u, best));
            }
        }
        summary(rows);
        System.out.println(invalid == 0 ? "LONG-CHECK OK" : "LONG-CHECK FAILED (" + invalid + " invalid)");
        FloorBench.WORKERS.shutdownNow();
        System.exit(invalid == 0 ? 0 : 1);
    }

    /**
     * Experiment on the graph's plan, same trip, same warm graph: -Dcorridor=D (the exact search over only the
     * landings within D blocks of the plan's line, -Dcorridorms budget). Prints warps, ms, and whether it replays.
     */
    static String variants(FloorBench.Floor f, EtherSearch.Grid grid, EtherSearch probe, FloorBench.Click c,
                           List<EtherSearch.Hop> gp, ExactWarpSearch.Goal eg) {
        StringBuilder sb = new StringBuilder();
        int d = Integer.getInteger("corridor", 0);
        if (d > 0) {
            boolean[] mask = corridor(gp, c.exact, d);
            ExactWarpSearch ex = new ExactWarpSearch(grid, () -> grid, FloorBench.WORKERS, FloorBench.THREADS,
                    ExactBench.RANGE, 1.05, EtherSearch.SNEAK_EYE,
                    (gr, x, y, z) -> f.landingOk(x, y, z) && x >= 0 && z >= 0 && x < FloorBench.SIZE
                            && z < FloorBench.SIZE && mask[z * FloorBench.SIZE + x], null,
                    0, FloorBench.SIZE - 1, 0, FloorBench.SIZE - 1, FloorBench.FLOOR_Y - 20, FloorBench.FLOOR_Y + 45);
            long t0 = System.nanoTime();
            List<EtherSearch.Hop> cp = ex.plan(FloorBench.startHop(c.start), eg, gp.size() - 1,
                    t0 + Long.getLong("corridorms", 5000L) * 1_000_000L);
            double ms = (System.nanoTime() - t0) / 1e6;
            int ok = cp == null ? 0 : FloorBench.replay(f, probe, c.start, c, cp);
            sb.append(String.format(Locale.ROOT, " corridor%d %s (%.0f ms, %d landings%s%s)", d,
                    cp == null ? (ex.timedOut ? "timeout" : "=" + gp.size()) : String.valueOf(cp.size()), ms, ex.landings,
                    ex.timedOut ? ", fewerThan " + ex.fewerThan : "", ok < 0 ? " INVALID" : ""));
        }
        return sb.toString();
    }

    /** Columns within {@code d} blocks of the plan's line (bench coords, SIZE x SIZE). */
    static boolean[] corridor(List<EtherSearch.Hop> gp, int[] goal, int d) {
        int n = FloorBench.SIZE;
        boolean[] m = new boolean[n * n];
        List<double[]> pts = new ArrayList<>();
        for (EtherSearch.Hop h : gp) {
            pts.add(new double[]{h.x, h.z});
        }
        pts.add(new double[]{goal[0] + 0.5, goal[2] + 0.5});
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i);
            double[] q = pts.get(i + 1);
            int x0 = (int) Math.max(0, Math.min(a[0], q[0]) - d);
            int x1 = (int) Math.min(n - 1, Math.max(a[0], q[0]) + d);
            int z0 = (int) Math.max(0, Math.min(a[1], q[1]) - d);
            int z1 = (int) Math.min(n - 1, Math.max(a[1], q[1]) + d);
            double vx = q[0] - a[0];
            double vz = q[1] - a[1];
            double len2 = Math.max(1e-9, vx * vx + vz * vz);
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    double t = Math.max(0, Math.min(1, ((x + 0.5 - a[0]) * vx + (z + 0.5 - a[1]) * vz) / len2));
                    double ex = a[0] + t * vx - (x + 0.5);
                    double ez = a[1] + t * vz - (z + 0.5);
                    if (ex * ex + ez * ez <= (double) d * d) {
                        m[z * n + x] = true;
                    }
                }
            }
        }
        return m;
    }

    /** Room pairs (start, goal, doors apart) at the floor's door diameter first, then one less; seeded order. */
    static List<int[]> farPairs(FloorBench.Floor f, Random rng, int n) {
        int rc = f.rooms.size();
        int[][] d = new int[rc][];
        int diam = 0;
        for (int s = 0; s < rc; s++) {
            d[s] = bfs(f, s);
            for (int v : d[s]) {
                diam = Math.max(diam, v);
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int want = diam; want >= Math.max(1, diam - 1) && out.size() < n; want--) {
            List<int[]> at = new ArrayList<>();
            for (int s = 0; s < rc; s++) {
                for (int t = 0; t < rc; t++) {
                    if (d[s][t] == want && !f.spots.get(s).isEmpty() && !f.spots.get(t).isEmpty()) {
                        at.add(new int[]{s, t, want});
                    }
                }
            }
            Collections.shuffle(at, rng);
            for (int[] p : at) {
                if (out.size() < n) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    static int[] bfs(FloorBench.Floor f, int s) {
        int[] d = new int[f.rooms.size()];
        Arrays.fill(d, -1);
        d[s] = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(s));
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v : f.adj.get(u)) {
                if (d[v] < 0) {
                    d[v] = d[u] + 1;
                    q.add(v);
                }
            }
        }
        return d;
    }

    /** The exact search's goal for the same click: the block, or the tile's floor band nearest its center first. */
    static ExactWarpSearch.Goal exactGoal(FloorBench.Floor f, FloorBench.Click c) {
        ExactWarpSearch.Goal eg = new ExactWarpSearch.Goal();
        eg.x = c.exact[0];
        eg.y = c.exact[1];
        eg.z = c.exact[2];
        if (c.tile) {
            int cx = FloorBench.START + (c.tileCell % 6) * 32 + FloorBench.OFF;
            int cz = FloorBench.START + (c.tileCell / 6) * 32 + FloorBench.OFF;
            eg.region = FloorBench.tileRegion(f, c.tileCell);
            eg.rx0 = cx - 14;
            eg.rx1 = cx + 14;
            eg.rz0 = cz - 14;
            eg.rz1 = cz + 14;
            eg.preferX = cx + 0.5;
            eg.preferZ = cz + 0.5;
        }
        return eg;
    }

    static String gap(int u, int best) {
        return u > 0 && best > 0 ? String.format(Locale.ROOT, "%+d (%.0f%%)", u - best, 100.0 * (u - best) / best) : "-";
    }

    /** rows: {origin warps, now warps, best, proved, origin ms, now ms, exact ms, nodes}. */
    static void summary(List<double[]> rows) {
        for (int v = 0; v < 2; v++) {
            List<Double> gaps = new ArrayList<>();
            List<Double> ms = new ArrayList<>();
            List<Double> ems = new ArrayList<>();
            int open = 0;
            int worse = 0;
            double gw = 0;
            double bw = 0;
            for (double[] r : rows) {
                if (r[v] <= 0 || r[2] <= 0) {
                    continue;
                }
                gw += r[v];
                bw += r[2];
                gaps.add(100.0 * (r[v] - r[2]) / r[2]);
                ms.add(r[4 + v]);
                ems.add(r[6]);
                open += r[3] == 0 ? 1 : 0;
                worse += r[v] > r[2] ? 1 : 0;
            }
            if (gaps.isEmpty()) {
                System.out.println("SUMMARY no trips");
                return;
            }
            Collections.sort(gaps);
            Collections.sort(ms);
            Collections.sort(ems);
            System.out.printf(Locale.ROOT, "SUMMARY %s %d trips: %.0f warps vs best %.0f (x%.3f); %d over best; %d bounds"
                            + " not proved; gap %% median %.1f p90 %.1f worst %.1f; plan ms median %.2f p90 %.2f max %.2f;"
                            + " exact ms median %.0f p90 %.0f max %.0f%n", v == 0 ? "origin" : "now", gaps.size(), gw, bw,
                    gw / bw, worse, open, pct(gaps, 0.5), pct(gaps, 0.9), gaps.get(gaps.size() - 1), pct(ms, 0.5),
                    pct(ms, 0.9), ms.get(ms.size() - 1), pct(ems, 0.5), pct(ems, 0.9), ems.get(ems.size() - 1));
        }
    }

    static double pct(List<Double> v, double p) {
        return v.get(Math.min(v.size() - 1, (int) (v.size() * p)));
    }
}
