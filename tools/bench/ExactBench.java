import com.killer560.hub.livemap.autoclear.EtherSearch;
import com.killer560.hub.livemap.autoclear.ExactWarpSearch;
import com.killer560.hub.livemap.autoclear.WarpGraph;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.roomsim.SimFloorGen;
import com.killer560.hub.roomsim.SimFloorLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The exact fewest-warps search ({@link ExactWarpSearch}) against the floor graph the game plans on, and against a
 * BRUTE FORCE ground truth, on whole floors built as FloorBench builds them (the sim's layout over the shipped room
 * captures). No game needed: tools/bench/exact.sh.
 *
 * <p>Per click (a walkable start and an exact goal block, the path-node shape): the warm floor graph's warps (what the
 * game used to save), the exact search's warps and time (bounded above by the graph's), and every returned route
 * replayed ray by ray. With -Dbrute=N, N clicks of each floor are also solved by brute force inside a box around start
 * and goal: breadth first over every landing in the box, an edge being ANY firm aim among 16 x 16 points on every face
 * plus EtherSearch's 26 aim points, every point cast with no hidden-cell shortcut - and the exact search, given the same
 * box, must give the same number of warps. -Dbrutedepth=N (3) caps the brute force's warps.
 *
 * <p>-Dfloors=N (3) -Dclicks=N (60) -Dseed=N (560) -Dsame=F (0.5: share of clicks whose goal is in the start's room, the
 * rest in a room next to it) -Dthreads=N (FloorBench's) -Dbudget=MS (20000) -Ddepth=N (EtherSearch.exactDepth, 4).
 */
public final class ExactBench {

    static final double RANGE = 60.0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/main/resources/assets/killer560smod/rooms");
        int floors = Integer.getInteger("floors", 3);
        int perFloor = Integer.getInteger("clicks", 60);
        int bruteN = Integer.getInteger("brute", 0);
        int bruteDepth = Integer.getInteger("brutedepth", 3);
        long seed = Long.getLong("seed", 560L);
        double same = Double.parseDouble(System.getProperty("same", "0.5"));
        long budget = Long.getLong("budget", 20000L) * 1_000_000L;
        FloorBench.RANGE = RANGE;
        Map<String, RoomLibrary.Room> rooms = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(q -> q.toString().endsWith(".json")).sorted().toList()) {
                RoomLibrary.Room r = LayoutSim.read(p);
                if (r != null && r.usable()) {
                    rooms.put(r.name, r);
                }
            }
        }
        RoomLibrary.ROOMS.putAll(rooms);
        Path types = Path.of(System.getProperty("roomdata", "tools/bench/roomtypes.json"));
        for (var e : com.google.gson.JsonParser.parseString(Files.readString(types)).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if (!o.has("name") || o.get("name").isJsonNull()) {
                continue;
            }
            String nm = o.get("name").getAsString();
            if (o.has("type") && !o.get("type").isJsonNull()) {
                SimFloorGen.TYPE.put(nm, o.get("type").getAsString());
            }
            if (o.has("shape") && !o.get("shape").isJsonNull()) {
                SimFloorGen.SHAPE.put(nm, o.get("shape").getAsString());
            }
        }
        System.out.printf(Locale.ROOT, "%d captures; %d floor(s) x %d clicks, %d brute-force click(s) a floor (depth %d),"
                + " %d thread(s), range %.0f%n", rooms.size(), floors, perFloor, bruteN, bruteDepth, FloorBench.THREADS, RANGE);

        if (Boolean.getBoolean("lip")) {
            lip();
            return;
        }
        Random rng = new Random(seed);
        int[] diff = new int[16];
        int clicksDone = 0;
        int graphFails = 0;
        int exactFound = 0;
        int exactTimedOut = 0;
        int invalid = 0;
        int bruteDone = 0;
        int bruteMismatch = 0;
        int direct1Missed = 0;
        List<Double> times = new ArrayList<>();
        List<Double> timesBetter = new ArrayList<>();
        List<String> examples = new ArrayList<>();
        for (int fi = 0; fi < floors; fi++) {
            SimFloorLayout.Floor lf = SimFloorLayout.generate(LayoutSim.capChampions(rooms, rng), 21, 36, 3, 9, rng);
            FloorBench.Floor f = new FloorBench.Floor(lf);
            EtherSearch.Grid grid = f::flags;
            EtherSearch probe = new EtherSearch(grid);
            List<FloorBench.Click> cs = clicks(f, rng, perFloor, same);
            if (cs.isEmpty()) {
                continue;
            }
            WarpGraph g = FloorBench.graph(f, 2);
            int[] s0 = cs.get(0).start;
            long w0 = System.nanoTime();
            while (g.warm(grid, () -> grid, FloorBench.WORKERS, FloorBench.THREADS, s0[0] + 0.5, s0[1] + 1.0,
                    s0[2] + 0.5, 50_000_000L)) {
                // the whole floor
            }
            System.out.printf(Locale.ROOT, "floor %d: %d rooms, graph warm %d nodes in %.0f ms%n", fi, f.rooms.size(),
                    g.nodeCount(), (System.nanoTime() - w0) / 1e6);
            int bruteLeft = bruteN;
            for (int ci = 0; ci < cs.size(); ci++) {
                FloorBench.Click c = cs.get(ci);
                WarpGraph.Goal gg = new WarpGraph.Goal();
                gg.x = c.exact[0];
                gg.y = c.exact[1];
                gg.z = c.exact[2];
                gg.tile = -1;
                List<EtherSearch.Hop> gp = g.plan(grid, FloorBench.startHop(c.start), gg,
                        System.nanoTime() + 2_000_000_000L, 64);
                int u = gp == null ? -1 : gp.size();
                if (gp == null) {
                    graphFails++;
                }
                ExactWarpSearch ex = search(f, grid, 0, FloorBench.SIZE - 1, 0, FloorBench.SIZE - 1);
                ExactWarpSearch.Goal eg = new ExactWarpSearch.Goal();
                eg.x = c.exact[0];
                eg.y = c.exact[1];
                eg.z = c.exact[2];
                long t0 = System.nanoTime();
                List<EtherSearch.Hop> ep = ex.plan(FloorBench.startHop(c.start), eg, u > 0 ? u - 1 : 16, t0 + budget);
                double ms = (System.nanoTime() - t0) / 1e6;
                times.add(ms);
                clicksDone++;
                int exactWarps;
                if (ep != null) {
                    exactFound++;
                    exactWarps = ep.size();
                    timesBetter.add(ms);
                    if (FloorBench.replay(f, probe, c.start, c, ep) != 0) {
                        invalid++;
                        System.out.println("  INVALID exact route: click " + ci);
                    }
                } else if (ex.timedOut) {
                    exactTimedOut++;
                    exactWarps = -2;
                } else {
                    exactWarps = u;
                }
                if (u > 0 && exactWarps > 0) {
                    diff[Math.min(15, u - exactWarps)]++;
                    if (exactWarps == 1 && u > 1) {
                        direct1Missed++;
                    }
                    if (u - exactWarps >= 1 && examples.size() < 12) {
                        examples.add(String.format(Locale.ROOT, "  floor %d click %d: %s r%d -> %s r%d: graph %d, exact %d"
                                        + " (%.0f ms, F%d/B%d layers, %d landings, %d aim points)", fi, ci,
                                Arrays.toString(c.start), f.roomAt(c.start[0], c.start[2]), Arrays.toString(c.exact),
                                c.goalRoom, u, exactWarps, ms, ex.forwardLayers, ex.backwardLayers, ex.landings,
                                ex.aimTests));
                    }
                }
                if (Boolean.getBoolean("verbose")) {
                    System.out.printf(Locale.ROOT, "  click %d: graph %d, exact %d, %.1f ms, proved fewer than %d%s%n", ci,
                            u, exactWarps, ms, ex.fewerThan, ex.timedOut ? " TIMED OUT" : "");
                }
                if (bruteLeft > 0 && u > 0 && Math.min(u, exactWarps > 0 ? exactWarps : u) <= bruteDepth) {
                    bruteLeft--;
                    int[] box = box(c);
                    long b0 = System.nanoTime();
                    int brute = brute(f, grid, c, box, bruteDepth);
                    double bms = (System.nanoTime() - b0) / 1e6;
                    ExactWarpSearch boxed = search(f, grid, box[0], box[1], box[2], box[3]);
                    List<EtherSearch.Hop> bp = boxed.plan(FloorBench.startHop(c.start), eg, bruteDepth,
                            System.nanoTime() + budget);
                    int boxedWarps = bp != null ? bp.size() : boxed.timedOut ? -2 : -1;
                    bruteDone++;
                    boolean ok = brute == boxedWarps;
                    if (!ok) {
                        bruteMismatch++;
                    }
                    System.out.printf(Locale.ROOT, "  BRUTE floor %d click %d: brute %s, exact in box %s, exact %d,"
                                    + " graph %d (brute %.0f ms)%s%n", fi, ci, brute < 0 ? "none<=" + bruteDepth : brute,
                            boxedWarps == -1 ? "none<=" + bruteDepth : boxedWarps, exactWarps, u, bms,
                            ok ? "" : "  MISMATCH");
                }
            }
        }
        times.sort(null);
        System.out.println();
        System.out.printf(Locale.ROOT, "%d clicks: graph failed %d; exact found FEWER warps on %d, timed out on %d;"
                + " %d invalid exact route(s)%n", clicksDone, graphFails, exactFound, exactTimedOut, invalid);
        StringBuilder sb = new StringBuilder("graph - exact warps:");
        for (int i = 0; i < diff.length; i++) {
            if (diff[i] > 0) {
                sb.append(' ').append(i).append(':').append(diff[i]);
            }
        }
        System.out.println(sb);
        System.out.println("clicks where one warp does it and the graph took more: " + direct1Missed);
        if (!times.isEmpty()) {
            System.out.printf(Locale.ROOT, "exact search time: mean %.1f ms, median %.1f, p90 %.1f, max %.1f%n",
                    times.stream().mapToDouble(Double::doubleValue).average().orElse(0), times.get(times.size() / 2),
                    times.get((int) (times.size() * 0.9)), times.get(times.size() - 1));
        }
        examples.forEach(System.out::println);
        if (bruteDone > 0) {
            System.out.printf(Locale.ROOT, "BRUTE FORCE: %d click(s), %d mismatch(es)%n", bruteDone, bruteMismatch);
        }
        System.out.println(invalid == 0 && bruteMismatch == 0 ? "EXACT-CHECK OK" : "EXACT-CHECK FAILED");
        FloorBench.WORKERS.shutdownNow();
        System.exit(invalid == 0 && bruteMismatch == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------- the lip

    /**
     * -Dlip: the testkit's 631 geometry. A flat stone floor (y 4); the goal T in it at x 20; one stone block on the
     * floor right in front of T (x 19, y 5), so T's top is seen from above only along its far edge;
     * the start on a pillar 10 blocks back and 10 up. One warp does it (aim at the far tenth of T's top), and none of
     * the floor graph's 18 aim points sees it.
     */
    static void lip() {
        int sizeX = 48;
        int sizeY = 32;
        int sizeZ = 24;
        byte[] g = new byte[sizeX * sizeY * sizeZ];
        int air = EtherSearch.PASSABLE | EtherSearch.AIR;
        int stone = 1 << EtherSearch.TOP_SHIFT;
        java.util.Arrays.fill(g, (byte) air);
        java.util.function.IntUnaryOperator nop = v -> v;
        int[] dims = {sizeX, sizeY, sizeZ};
        java.util.function.BiConsumer<int[], Integer> set = (p, v) -> g[(p[1] * sizeZ + p[2]) * sizeX + p[0]] = (byte) (int) v;
        for (int x = 1; x < sizeX - 1; x++) {
            for (int z = 1; z < sizeZ - 1; z++) {
                set.accept(new int[]{x, 4, z}, stone);
            }
        }
        int ty = 4;
        set.accept(new int[]{19, ty + 1, 12}, stone);
        for (int y = ty + 1; y <= ty + 10; y++) {
            set.accept(new int[]{10, y, 12}, stone);
        }
        EtherSearch.Grid grid = (x, y, z) -> x < 0 || y < 0 || z < 0 || x >= dims[0] || y >= dims[1] || z >= dims[2]
                ? air : g[(y * sizeZ + z) * sizeX + x] & 0xFF;
        WarpGraph wg = new WarpGraph(RANGE, 1.05, 2, (gr, x, y, z) -> true, 0, sizeY - 3);
        wg.partialFrom = 0.3;
        EtherSearch.Hop start = new EtherSearch.Hop(10.5, ty + 11.0, 12.5, 10, ty + 11, 12, 0, 0);
        while (wg.warm(grid, () -> grid, null, 1, start.x, start.y, start.z, 50_000_000L)) {
            // all of it
        }
        WarpGraph.Goal gg = new WarpGraph.Goal();
        gg.x = 20;
        gg.y = ty;
        gg.z = 12;
        gg.tile = -1;
        List<EtherSearch.Hop> gp = wg.plan(grid, start, gg, System.nanoTime() + 1_000_000_000L, 16);
        ExactWarpSearch ex = new ExactWarpSearch(grid, () -> grid, null, 1, RANGE, 1.05, EtherSearch.SNEAK_EYE,
                null, null, 0, sizeX - 1, 0, sizeZ - 1, 0, sizeY - 3);
        ExactWarpSearch.Goal eg = new ExactWarpSearch.Goal();
        eg.x = 20;
        eg.y = ty;
        eg.z = 12;
        List<EtherSearch.Hop> ep = ex.plan(start, eg, 16, System.nanoTime() + 5_000_000_000L);
        EtherSearch probe = new EtherSearch(grid);
        boolean direct = bruteAim(probe, start.x, start.y + EtherSearch.SNEAK_EYE, start.z, 20, ty, 12);
        EtherSearch es = new EtherSearch(grid);
        boolean graphAim = es.aim(start.x, start.y + EtherSearch.SNEAK_EYE, start.z, 20, ty, 12, RANGE);
        System.out.printf(Locale.ROOT, "LIP: graph %s warp(s), exact %s warp(s) (aim %s), brute force direct: %s;"
                        + " the 18-point aim sees it: %s%n", gp == null ? "no" : gp.size(), ep == null ? "no" : ep.size(),
                ep == null || ep.isEmpty() ? "-" : ep.get(0).yaw + "/" + ep.get(0).pitch, direct, graphAim);
    }

    static ExactWarpSearch search(FloorBench.Floor f, EtherSearch.Grid grid, int x0, int x1, int z0, int z1) {
        ExactWarpSearch ex = new ExactWarpSearch(grid, () -> grid, FloorBench.WORKERS, FloorBench.THREADS, RANGE, 1.05,
                EtherSearch.SNEAK_EYE, (gr, x, y, z) -> f.landingOk(x, y, z), null, x0, x1, z0, z1,
                FloorBench.FLOOR_Y - 20, FloorBench.FLOOR_Y + 45);
        return ex;
    }

    /** Path-node shaped clicks: a walkable start, a walkable goal in the same room or one with a door to it. */
    static List<FloorBench.Click> clicks(FloorBench.Floor f, Random rng, int n, double same) {
        List<FloorBench.Click> out = new ArrayList<>();
        if (Boolean.getBoolean("far")) {
            // -Dfar: map clicks - any room of the floor, exact blocks only (FloorBench's click maker).
            for (FloorBench.Click c : FloorBench.clicks(f, rng, n * 3)) {
                if (!c.tile && out.size() < n) {
                    out.add(c);
                }
            }
            return out;
        }
        int guard = 0;
        while (out.size() < n && guard++ < n * 50) {
            int sr = rng.nextInt(f.rooms.size());
            if (f.spots.get(sr).isEmpty()) {
                continue;
            }
            int gr = sr;
            if (rng.nextDouble() >= same && !f.adj.get(sr).isEmpty()) {
                gr = f.adj.get(sr).get(rng.nextInt(f.adj.get(sr).size()));
            }
            if (f.spots.get(gr).isEmpty()) {
                continue;
            }
            FloorBench.Click c = new FloorBench.Click();
            c.start = f.spots.get(sr).get(rng.nextInt(f.spots.get(sr).size()));
            c.exact = f.spots.get(gr).get(rng.nextInt(f.spots.get(gr).size()));
            c.goalRoom = gr;
            if (Math.abs(c.exact[0] - c.start[0]) + Math.abs(c.exact[2] - c.start[2]) < 4) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /** Start and goal's bounding box, widened by 6 blocks: the brute force's world. */
    static int[] box(FloorBench.Click c) {
        return new int[]{Math.min(c.start[0], c.exact[0]) - 6, Math.max(c.start[0], c.exact[0]) + 6,
                Math.min(c.start[2], c.exact[2]) - 6, Math.max(c.start[2], c.exact[2]) + 6};
    }

    // ------------------------------------------------------------------------------------------- brute force

    static final int BRUTE_GRID = 16;

    /**
     * Fewest warps by plain breadth first over every landing in the box (the goal block may be any etherwarpable
     * block), with {@link #bruteAim}; -1 for none within {@code maxDepth}.
     */
    static int brute(FloorBench.Floor f, EtherSearch.Grid grid, FloorBench.Click c, int[] box, int maxDepth) {
        EtherSearch es = new EtherSearch(grid);
        List<int[]> lands = new ArrayList<>();
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                for (int y = FloorBench.FLOOR_Y - 20; y <= FloorBench.FLOOR_Y + 45; y++) {
                    if (f.etherwarpable(x, y, z) && (f.flags(x, y, z) & EtherSearch.BLACKLIST) == 0
                            && f.landingOk(x, y, z)) {
                        lands.add(new int[]{x, y, z});
                    }
                }
            }
        }
        int[] goal = c.exact;
        // Frontier entries are standing positions {x, y, z} (feet).
        List<double[]> frontier = new ArrayList<>();
        frontier.add(new double[]{c.start[0] + 0.5, c.start[1] + 1.0, c.start[2] + 0.5});
        Map<Long, Boolean> seen = new HashMap<>();
        for (int depth = 1; depth <= maxDepth; depth++) {
            for (double[] s : frontier) {
                if (bruteAim(es, s[0], s[1] + EtherSearch.SNEAK_EYE, s[2], goal[0], goal[1], goal[2])) {
                    return depth;
                }
            }
            if (depth == maxDepth) {
                break;
            }
            List<double[]> next = new ArrayList<>();
            for (int[] l : lands) {
                long key = EtherSearch.pack(l[0], l[1], l[2]);
                if (seen.containsKey(key)) {
                    continue;
                }
                for (double[] s : frontier) {
                    if (bruteAim(es, s[0], s[1] + EtherSearch.SNEAK_EYE, s[2], l[0], l[1], l[2])) {
                        seen.put(key, true);
                        next.add(new double[]{l[0] + 0.5, l[1] + 1.05, l[2] + 0.5});
                        break;
                    }
                }
            }
            frontier = next;
        }
        return -1;
    }

    static final double[][] POINTS = {
            {0.5, 0.97, 0.5},
            {0.3, 0.97, 0.3}, {0.7, 0.97, 0.3}, {0.3, 0.97, 0.7}, {0.7, 0.97, 0.7},
            {0.5, 0.97, 0.15}, {0.5, 0.97, 0.85}, {0.15, 0.97, 0.5}, {0.85, 0.97, 0.5},
            {0.03, 0.5, 0.5}, {0.97, 0.5, 0.5}, {0.5, 0.5, 0.03}, {0.5, 0.5, 0.97},
            {0.03, 0.85, 0.5}, {0.97, 0.85, 0.5}, {0.5, 0.85, 0.03}, {0.5, 0.85, 0.97},
            {0.5, 0.03, 0.5},
            {0.37, 0.97, 0.61}, {0.63, 0.97, 0.39}, {0.41, 0.97, 0.43}, {0.59, 0.97, 0.57},
            {0.23, 0.97, 0.62}, {0.77, 0.97, 0.38}, {0.62, 0.97, 0.23}, {0.38, 0.97, 0.77}};

    /** Any firm aim among the fixed points and a 16 x 16 grid on every face, each cast; no shortcuts. */
    static boolean bruteAim(EtherSearch es, double ex, double ey, double ez, int bx, int by, int bz) {
        double nx = Math.max(bx, Math.min(ex, bx + 1.0)) - ex;
        double ny = Math.max(by, Math.min(ey, by + 1.0)) - ey;
        double nz = Math.max(bz, Math.min(ez, bz + 1.0)) - ez;
        if (nx * nx + ny * ny + nz * nz > RANGE * RANGE) {
            return false;
        }
        for (double[] o : POINTS) {
            if (brutePoint(es, ex, ey, ez, bx + o[0], by + o[1], bz + o[2], bx, by, bz)) {
                return true;
            }
        }
        double in = 0.004;
        for (int axis = 0; axis < 3; axis++) {
            double e = axis == 0 ? ex : axis == 1 ? ey : ez;
            int b = axis == 0 ? bx : axis == 1 ? by : bz;
            for (int side = 0; side < 2; side++) {
                if (side == 0 ? e >= b : e <= b + 1) {
                    continue;   // a face turned away from the eye (aimExact's rule)
                }
                double plane = side == 0 ? in : 1 - in;
                // Every cell centre of every level aimExact splits a face into, 1 x 1 down to 16 x 16.
                for (int cells = 1; cells <= BRUTE_GRID; cells *= 2) {
                    for (int i = 0; i < cells; i++) {
                        for (int j = 0; j < cells; j++) {
                            double u = in + (1 - 2 * in) * (i + 0.5) / cells;
                            double v = in + (1 - 2 * in) * (j + 0.5) / cells;
                            double px = axis == 0 ? plane : u;
                            double py = axis == 1 ? plane : axis == 0 ? u : v;
                            double pz = axis == 2 ? plane : v;
                            if (brutePoint(es, ex, ey, ez, bx + px, by + py, bz + pz, bx, by, bz)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    static boolean brutePoint(EtherSearch es, double ex, double ey, double ez, double tx, double ty, double tz, int bx,
                              int by, int bz) {
        int r = es.cast(ex, ey, ez, tx, ty, tz);
        if (r == EtherSearch.MISS || es.hitX != bx || es.hitY != by || es.hitZ != bz) {
            return false;
        }
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        float yaw = EtherSearch.wrapDegrees((float) -Math.toDegrees(Math.atan2(dx, dz)));
        float pitch = EtherSearch.wrapDegrees((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        return es.holds(ex, ey, ez, yaw, pitch, bx, by, bz, RANGE);
    }
}
