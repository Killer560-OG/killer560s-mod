package com.killer560.hub.livemap.autoclear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The FEWEST etherwarps from where he stands to a block (or into a region), proved: breadth first by warp count over
 * EVERY landing block, from both ends.
 *
 * <p>killer560 (2026-10-09): "I will line up where it is only 1 etherwarp and it will take 2 for some reason. Another
 * was a 6 warp I figured out in 3. [...] Make sure that it is 100% perfect as to the most optimal route." The floor
 * graph ({@link WarpGraph}) answers a click in a millisecond because it keeps one landing per 2x2 columns, tries 18
 * aim points a block, refuses a line blocked early without trying the rest, and starts a click from the edges of the
 * node he stands near; each of those can drop the one warp that makes a route shorter. Here none of them is made:
 * <ul>
 *   <li><b>Nodes</b> are every landing block (etherwarpable, not on the landing blacklist - bottom slabs and every
 *       other block he would land half a block or more down from - and allowed by the caller's rule), plus his exact
 *       position and the goal block itself.</li>
 *   <li><b>An edge</b> is {@link EtherSearch#aimExact}: any firm aim at the block, faces searched to a sixteenth of a
 *       block, the real float hop cast the full range.</li>
 *   <li><b>The search</b> is breadth first from both ends: forward layers F0 (him), F1, ... (landings exactly a warps
 *       from him) and backward layers B0 (the goal), B1, ... (landings exactly b warps from it). A route of a + b + 1
 *       warps exists exactly when some node of F_a reaches some node of B_b in one warp; the rounds try a + b + 1 = 1,
 *       2, 3, ... and between rounds the smaller side grows by one whole layer. The first round that finds a pair is
 *       the fewest warps there are: the node a shortest route has after a warps is in F_a and the one after it in B_b,
 *       or a shorter route would exist (so no pruning here can drop an optimal route). Among the pairs of that round
 *       the one with the shortest total distance is taken (for a region, the landing nearest its preferred point
 *       first).</li>
 * </ul>
 *
 * <p>Bounded by a deadline: past it the search stops and says so ({@link #timedOut}); {@link #fewerThan} then says how
 * many warps were proved impossible so far. No Minecraft imports (tools/bench compiles it). One instance per search;
 * the caller's thread owns it, and the rays of a layer run on the workers it is given.
 */
public final class ExactWarpSearch {

    /** Where an intermediate landing may be, beyond etherwarpable and the blacklist (bounds, the sim's roof rule). */
    public interface Rule {
        boolean ok(EtherSearch.Grid grid, int x, int y, int z);
    }

    public final double range;
    public final double standOffset;
    public final double eyeHeight;
    private final EtherSearch.Grid grid;
    private final Supplier<EtherSearch.Grid> grids;
    private final ExecutorService workers;
    private final int threads;
    private final Rule rule;
    private final EtherSearch.CellTest deadEnd;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int minY;
    private final int maxY;
    private final EtherSearch own;

    // ------------------------------------------------------------------------------------------- results

    /** Set from any thread: the search stops at its next check, as if its deadline had passed. */
    public volatile boolean cancelled;
    /** The search ended at its deadline: {@link #fewerThan} is what it proved. */
    public boolean timedOut;
    /** The returned route has the fewest warps, but the deadline cut the choice among routes of that many short. */
    public boolean tieBreakCut;
    /** No route of fewer than this many warps exists (proved). */
    public int fewerThan;
    /** Warps of the returned route, or -1. */
    public int warps = -1;
    public int forwardLayers;
    public int backwardLayers;
    public int landings;
    public long aimTests;
    public long rays;
    public long nanos;

    public ExactWarpSearch(EtherSearch.Grid grid, Supplier<EtherSearch.Grid> grids, ExecutorService workers,
                           int threads, double range, double standOffset, double eyeHeight, Rule rule,
                           EtherSearch.CellTest deadEnd, int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        this.grid = grid;
        this.grids = grids;
        this.workers = workers;
        this.threads = Math.max(1, threads);
        this.range = range;
        this.standOffset = standOffset;
        this.eyeHeight = eyeHeight;
        this.rule = rule;
        this.deadEnd = deadEnd;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.minY = minY;
        this.maxY = maxY;
        this.own = new EtherSearch(grid);
    }

    // ------------------------------------------------------------------------------------------- nodes

    private int n;
    private int[] nx = new int[1024];
    private int[] ny = new int[1024];
    private int[] nz = new int[1024];
    private double[] sx = new double[1024];
    private double[] sy = new double[1024];
    private double[] sz = new double[1024];
    private boolean[] dead = new boolean[1024];
    private int[] dF = new int[1024];
    private int[] dB = new int[1024];
    private int[] parF = new int[1024];
    private int[] parB = new int[1024];
    private float[] yawF = new float[1024];
    private float[] pitchF = new float[1024];
    private float[] yawB = new float[1024];
    private float[] pitchB = new float[1024];
    private double[] gF = new double[1024];
    private double[] gB = new double[1024];
    private final Map<Long, Integer> ids = new HashMap<>();
    private final Map<Long, int[]> columns = new HashMap<>();

    private int addNode(int x, int y, int z, double px, double py, double pz) {
        if (n == nx.length) {
            int m = n * 2;
            nx = Arrays.copyOf(nx, m);
            ny = Arrays.copyOf(ny, m);
            nz = Arrays.copyOf(nz, m);
            sx = Arrays.copyOf(sx, m);
            sy = Arrays.copyOf(sy, m);
            sz = Arrays.copyOf(sz, m);
            dead = Arrays.copyOf(dead, m);
            dF = Arrays.copyOf(dF, m);
            dB = Arrays.copyOf(dB, m);
            parF = Arrays.copyOf(parF, m);
            parB = Arrays.copyOf(parB, m);
            yawF = Arrays.copyOf(yawF, m);
            pitchF = Arrays.copyOf(pitchF, m);
            yawB = Arrays.copyOf(yawB, m);
            pitchB = Arrays.copyOf(pitchB, m);
            gF = Arrays.copyOf(gF, m);
            gB = Arrays.copyOf(gB, m);
        }
        int i = n++;
        nx[i] = x;
        ny[i] = y;
        nz[i] = z;
        sx[i] = px;
        sy[i] = py;
        sz[i] = pz;
        dead[i] = deadEnd != null && deadEnd.test(x, y, z);
        dF[i] = -1;
        dB[i] = -1;
        parF[i] = -1;
        parB[i] = -1;
        return i;
    }

    private int landingNode(int x, int y, int z) {
        long key = EtherSearch.pack(x, y, z);
        Integer id = ids.get(key);
        if (id != null) {
            return id;
        }
        int i = addNode(x, y, z, x + 0.5, y + standOffset, z + 0.5);
        ids.put(key, i);
        return i;
    }

    /** Whether a block may be landed on on the way (not the goal): standable, not blacklisted, inside the rule. */
    public boolean landing(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY
                && own.etherwarpable(x, y, z) && (grid.flags(x, y, z) & EtherSearch.BLACKLIST) == 0
                && (rule == null || rule.ok(grid, x, y, z));
    }

    /** The landing nodes of one chunk column, scanned on first use. Owner thread only. */
    private int[] column(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        int[] out = columns.get(key);
        if (out != null) {
            return out;
        }
        int x0 = Math.max(minX, cx << 4);
        int x1 = Math.min(maxX, (cx << 4) + 15);
        int z0 = Math.max(minZ, cz << 4);
        int z1 = Math.min(maxZ, (cz << 4) + 15);
        int[] buf = new int[64];
        int k = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (landing(x, y, z)) {
                        if (k == buf.length) {
                            buf = Arrays.copyOf(buf, k * 2);
                        }
                        buf[k++] = landingNode(x, y, z);
                    }
                }
            }
        }
        out = Arrays.copyOf(buf, k);
        columns.put(key, out);
        landings += k;
        return out;
    }

    /** Reach radius in x/z from a standing position to any block a hop may land on. */
    private double reachXZ() {
        return range + 1.0;
    }

    private double eyeY(int i) {
        return sy[i] + eyeHeight;
    }

    /** Whether the nearest point of block {@code t} is within a hop of node {@code f}'s eye. */
    private boolean inReach(int f, int t) {
        double ex = sx[f];
        double ey = eyeY(f);
        double ez = sz[f];
        double dx = Math.max(nx[t], Math.min(ex, nx[t] + 1.0)) - ex;
        double dy = Math.max(ny[t], Math.min(ey, ny[t] + 1.0)) - ey;
        double dz = Math.max(nz[t], Math.min(ez, nz[t] + 1.0)) - ez;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private double hop(int a, int b) {
        double dx = sx[a] - sx[b];
        double dy = sy[a] - sy[b];
        double dz = sz[a] - sz[b];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Node ids by chunk column, for "which of these are near that". */
    private Map<Long, List<Integer>> bucket(List<Integer> nodes) {
        Map<Long, List<Integer>> out = new HashMap<>();
        for (int i : nodes) {
            long key = ((long) Math.floorDiv((int) Math.floor(sx[i]), 16) << 32)
                    ^ (Math.floorDiv((int) Math.floor(sz[i]), 16) & 0xFFFFFFFFL);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        return out;
    }

    private List<Integer> near(Map<Long, List<Integer>> buckets, double x, double z, double r) {
        List<Integer> out = new ArrayList<>();
        int cx0 = Math.floorDiv((int) Math.floor(x - r), 16);
        int cx1 = Math.floorDiv((int) Math.floor(x + r), 16);
        int cz0 = Math.floorDiv((int) Math.floor(z - r), 16);
        int cz1 = Math.floorDiv((int) Math.floor(z + r), 16);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<Integer> l = buckets.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
                if (l != null) {
                    out.addAll(l);
                }
            }
        }
        return out;
    }

    /** Landing nodes in every column within {@code r} of (x, z). Owner thread. */
    private void landingsNear(double x, double z, double r, List<Integer> out, int stamp) {
        int cx0 = Math.floorDiv((int) Math.floor(x - r), 16);
        int cx1 = Math.floorDiv((int) Math.floor(x + r), 16);
        int cz0 = Math.floorDiv((int) Math.floor(z - r), 16);
        int cz1 = Math.floorDiv((int) Math.floor(z + r), 16);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if ((cx << 4) + 15 < minX || (cx << 4) > maxX || (cz << 4) + 15 < minZ || (cz << 4) > maxZ) {
                    continue;
                }
                int[] col = column(cx, cz);
                ensureMark();
                for (int i : col) {
                    if (mark[i] != stamp) {
                        mark[i] = stamp;
                        out.add(i);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the goal

    /** What the search is for: one block (which may be a blacklisted block - the goal is never refused for that), or
     *  any landing a region accepts inside its box. */
    public static final class Goal {
        public int x;
        public int y;
        public int z;
        /** Non-null: any landing it accepts, within the box below, ends the route. */
        public EtherSearch.CellTest region;
        public int rx0;
        public int rx1;
        public int rz0;
        public int rz1;
        /** For a region: of the landings reached in the fewest warps, the one nearest this point (x, z) first. */
        public double preferX = Double.NaN;
        public double preferZ = Double.NaN;
    }

    // ------------------------------------------------------------------------------------------- the search

    /**
     * The fewest-warp route from {@code start} to the goal with at most {@code maxWarps} warps, or null: then
     * {@link #timedOut} says whether the deadline stopped it, and {@link #fewerThan} how many warps are proved
     * impossible (with no time-out, every route has more than {@code maxWarps} warps, or there is none).
     */
    public List<EtherSearch.Hop> plan(EtherSearch.Hop start, Goal goal, int maxWarps, long deadlineNanos) {
        long t0 = System.nanoTime();
        try {
            return search(start, goal, maxWarps, deadlineNanos);
        } finally {
            nanos = System.nanoTime() - t0;
            rays += own.rays;
            aimTests += own.exactPoints;
        }
    }

    private List<EtherSearch.Hop> search(EtherSearch.Hop start, Goal goal, int maxWarps, long deadline) {
        timedOut = false;
        tieBreakCut = false;
        fewerThan = 1;
        warps = -1;
        int s = addNode(start.bx, start.by, start.bz, start.x, start.y, start.z);
        startId = s;
        dead[s] = false;
        dF[s] = 0;
        gF[s] = 0;
        List<Integer> fLayer = new ArrayList<>(List.of(s));
        List<Integer> bLayer = new ArrayList<>();
        regionGoal = goal.region != null;
        preferX = goal.preferX;
        preferZ = goal.preferZ;
        if (goal.region == null) {
            Integer id = ids.get(EtherSearch.pack(goal.x, goal.y, goal.z));
            int g = id != null ? id : addNode(goal.x, goal.y, goal.z, goal.x + 0.5, goal.y + standOffset, goal.z + 0.5);
            if (id == null) {
                ids.put(EtherSearch.pack(goal.x, goal.y, goal.z), g);
            }
            bLayer.add(g);
        } else {
            for (int cx = Math.floorDiv(goal.rx0, 16); cx <= Math.floorDiv(goal.rx1, 16); cx++) {
                for (int cz = Math.floorDiv(goal.rz0, 16); cz <= Math.floorDiv(goal.rz1, 16); cz++) {
                    for (int i : column(cx, cz)) {
                        if (nx[i] >= goal.rx0 && nx[i] <= goal.rx1 && nz[i] >= goal.rz0 && nz[i] <= goal.rz1
                                && goal.region.test(nx[i], ny[i], nz[i])) {
                            bLayer.add(i);
                        }
                    }
                }
            }
        }
        for (int g : bLayer) {
            dB[g] = 0;
            gB[g] = 0;
            parB[g] = -1;
        }
        if (bLayer.isEmpty()) {
            fewerThan = Integer.MAX_VALUE;
            return null;
        }
        int a = 0;
        int b = 0;
        for (int w = 1; w <= maxWarps; w++) {
            int[] pair = pairs(fLayer, bLayer, deadline);
            if (pair != null) {
                warps = w;
                fewerThan = w;
                forwardLayers = a;
                backwardLayers = b;
                return route(start, pair);
            }
            if (timedOut) {
                return null;
            }
            fewerThan = w + 1;
            if (w == maxWarps) {
                break;
            }
            int fCost = 0;
            for (int f : fLayer) {
                fCost += dead[f] && f != s ? 0 : 1;
            }
            if (fCost <= bLayer.size()) {
                fLayer = forward(fLayer, a, deadline);
                a++;
            } else {
                bLayer = backward(bLayer, b, deadline);
                b++;
            }
            if (timedOut) {
                return null;
            }
            if (fLayer.isEmpty() || bLayer.isEmpty()) {
                // One side has nowhere left to go: no route at any length.
                fewerThan = Integer.MAX_VALUE;
                forwardLayers = a;
                backwardLayers = b;
                return null;
            }
        }
        forwardLayers = a;
        backwardLayers = b;
        return null;
    }

    private int startId = -1;
    private boolean regionGoal;
    private double preferX = Double.NaN;
    private double preferZ = Double.NaN;

    /** A region route's landing, from its preferred point: the larger of the x and z offsets, whole blocks. */
    private int preferDepth(int t) {
        if (Double.isNaN(preferX)) {
            return 0;
        }
        return (int) Math.floor(Math.max(Math.abs(nx[t] + 0.5 - preferX), Math.abs(nz[t] + 0.5 - preferZ)));
    }

    // ------------------------------------------------------------------------------------------- one round

    /** Per pair round: the best pair found so far, its key and its aim. */
    private final Object bestLock = new Object();
    private double bestKey;
    private int bestF;
    private int bestT;
    private float bestYaw;
    private float bestPitch;

    /** Some f of F_a reaching some t of B_b in one warp: the best such pair {f, t}, or null. */
    private int[] pairs(List<Integer> fLayer, List<Integer> bLayer, long deadline) {
        Map<Long, List<Integer>> targets = bucket(bLayer);
        double r = reachXZ();
        List<Integer> from = new ArrayList<>();
        for (int f : fLayer) {
            if (!dead[f] || dF[f] == 0) {
                from.add(f);
            }
        }
        bestKey = Double.MAX_VALUE;
        bestF = -1;
        forAll(from.size(), deadline, (es, k) -> {
            int f = from.get(k);
            List<Integer> ts = near(targets, sx[f], sz[f], r);
            ts.removeIf(t -> t == f || !inReach(f, t));
            if (ts.isEmpty()) {
                return;
            }
            double[] key = new double[ts.size()];
            Integer[] order = new Integer[ts.size()];
            for (int i = 0; i < key.length; i++) {
                int t = ts.get(i);
                key[i] = (regionGoal ? preferDepth(root(t)) * 1e6 : 0) + gF[f] + hop(f, t) + gB[t];
                order[i] = i;
            }
            Arrays.sort(order, (p, q) -> Double.compare(key[p], key[q]));
            for (int i : order) {
                synchronized (bestLock) {
                    if (key[i] >= bestKey) {
                        return;
                    }
                }
                int t = ts.get(i);
                if (es.aimExact(sx[f], eyeY(f), sz[f], nx[t], ny[t], nz[t], range)) {
                    synchronized (bestLock) {
                        if (key[i] < bestKey) {
                            bestKey = key[i];
                            bestF = f;
                            bestT = t;
                            bestYaw = es.aimYaw;
                            bestPitch = es.aimPitch;
                        }
                    }
                    return;
                }
            }
        });
        // A pair found before the deadline is still a route of the fewest warps (every shorter round failed); only
        // the choice among this round's pairs may be cut short.
        if (bestF >= 0) {
            tieBreakCut = timedOut;
            timedOut = false;
            return new int[]{bestF, bestT};
        }
        return null;
    }

    /** F_{a+1}: every landing not yet reached that some node of F_a reaches in one warp. */
    private List<Integer> forward(List<Integer> fLayer, int a, long deadline) {
        double r = reachXZ();
        List<Integer> from = new ArrayList<>();
        for (int f : fLayer) {
            if (!dead[f] || dF[f] == 0) {
                from.add(f);
            }
        }
        int stamp = ++markStamp;
        List<Integer> cand = new ArrayList<>();
        for (int f : from) {
            landingsNear(sx[f], sz[f], r, cand, stamp);
        }
        cand.removeIf(c -> dF[c] >= 0);
        Map<Long, List<Integer>> sources = bucket(from);
        int[] par = new int[cand.size()];
        float[] yaw = new float[cand.size()];
        float[] pitch = new float[cand.size()];
        Arrays.fill(par, -1);
        forAll(cand.size(), deadline, (es, k) -> {
            int c = cand.get(k);
            List<Integer> fs = near(sources, nx[c] + 0.5, nz[c] + 0.5, r);
            fs.removeIf(f -> !inReach(f, c));
            if (fs.isEmpty()) {
                return;
            }
            double[] key = new double[fs.size()];
            Integer[] order = new Integer[fs.size()];
            for (int i = 0; i < key.length; i++) {
                key[i] = gF[fs.get(i)] + hop(fs.get(i), c);
                order[i] = i;
            }
            Arrays.sort(order, (p, q) -> Double.compare(key[p], key[q]));
            for (int i : order) {
                int f = fs.get(i);
                if (es.aimExact(sx[f], eyeY(f), sz[f], nx[c], ny[c], nz[c], range)) {
                    par[k] = f;
                    yaw[k] = es.aimYaw;
                    pitch[k] = es.aimPitch;
                    return;
                }
            }
        });
        List<Integer> next = new ArrayList<>();
        for (int k = 0; k < par.length; k++) {
            if (par[k] >= 0) {
                int c = cand.get(k);
                dF[c] = a + 1;
                parF[c] = par[k];
                yawF[c] = yaw[k];
                pitchF[c] = pitch[k];
                gF[c] = gF[par[k]] + hop(par[k], c);
                next.add(c);
            }
        }
        return next;
    }

    /** B_{b+1}: every landing not yet labelled (and not a dead end) that reaches some node of B_b in one warp. */
    private List<Integer> backward(List<Integer> bLayer, int b, long deadline) {
        double r = reachXZ();
        int stamp = ++markStamp;
        List<Integer> cand = new ArrayList<>();
        for (int t : bLayer) {
            landingsNear(nx[t] + 0.5, nz[t] + 0.5, r, cand, stamp);
        }
        cand.removeIf(c -> dB[c] >= 0 || dead[c]);
        Map<Long, List<Integer>> targets = bucket(bLayer);
        int[] par = new int[cand.size()];
        float[] yaw = new float[cand.size()];
        float[] pitch = new float[cand.size()];
        Arrays.fill(par, -1);
        forAll(cand.size(), deadline, (es, k) -> {
            int c = cand.get(k);
            List<Integer> ts = near(targets, sx[c], sz[c], r);
            ts.removeIf(t -> t == c || !inReach(c, t));
            if (ts.isEmpty()) {
                return;
            }
            double[] key = new double[ts.size()];
            Integer[] order = new Integer[ts.size()];
            for (int i = 0; i < key.length; i++) {
                int t = ts.get(i);
                key[i] = (regionGoal ? preferDepth(root(t)) * 1e6 : 0) + hop(c, t) + gB[t];
                order[i] = i;
            }
            Arrays.sort(order, (p, q) -> Double.compare(key[p], key[q]));
            for (int i : order) {
                int t = ts.get(i);
                if (es.aimExact(sx[c], eyeY(c), sz[c], nx[t], ny[t], nz[t], range)) {
                    par[k] = t;
                    yaw[k] = es.aimYaw;
                    pitch[k] = es.aimPitch;
                    return;
                }
            }
        });
        List<Integer> next = new ArrayList<>();
        for (int k = 0; k < par.length; k++) {
            if (par[k] >= 0) {
                int c = cand.get(k);
                dB[c] = b + 1;
                parB[c] = par[k];
                yawB[c] = yaw[k];
                pitchB[c] = pitch[k];
                gB[c] = gB[par[k]] + hop(c, par[k]);
                next.add(c);
            }
        }
        return next;
    }

    /** The goal-side landing a backward chain from {@code t} ends on. */
    private int root(int t) {
        int at = t;
        while (parB[at] >= 0) {
            at = parB[at];
        }
        return at;
    }

    private int markStamp;
    private int[] mark = new int[0];

    private void ensureMark() {
        if (mark.length < nx.length) {
            mark = Arrays.copyOf(mark, nx.length);
        }
    }

    /** The hops: F's chain to f, the pair's warp, then B's chain from t to the goal. */
    private List<EtherSearch.Hop> route(EtherSearch.Hop start, int[] pair) {
        int f = pair[0];
        int t = pair[1];
        List<EtherSearch.Hop> out = new ArrayList<>();
        // Backward from f to the start: each node v reached from parF[v] with (yawF, pitchF).
        List<EtherSearch.Hop> head = new ArrayList<>();
        int at = f;
        while (dF[at] > 0) {
            int p = parF[at];
            head.add(hopFrom(p, start, yawF[at], pitchF[at]));
            at = p;
        }
        Collections.reverse(head);
        out.addAll(head);
        out.add(hopFrom(f, start, bestYaw, bestPitch));
        at = t;
        while (dB[at] > 0) {
            out.add(hopFrom(at, start, yawB[at], pitchB[at]));
            at = parB[at];
        }
        landX = nx[at];
        landY = ny[at];
        landZ = nz[at];
        return out;
    }

    /** Where the returned route ends (the goal, or the region landing it chose). */
    public int landX;
    public int landY;
    public int landZ;

    private EtherSearch.Hop hopFrom(int node, EtherSearch.Hop start, float yaw, float pitch) {
        if (node == startId) {
            return new EtherSearch.Hop(start.x, start.y, start.z, start.bx, start.by, start.bz, yaw, pitch);
        }
        return new EtherSearch.Hop(sx[node], sy[node], sz[node], nx[node], ny[node], nz[node], yaw, pitch);
    }

    // ------------------------------------------------------------------------------------------- threads

    private interface Job {
        void run(EtherSearch search, int index);
    }

    /** Runs {@code job} for 0..count-1 across the owner and the workers, each with its own grid and search. */
    private void forAll(int count, long deadline, Job job) {
        if (count == 0) {
            return;
        }
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean late = new AtomicBoolean();
        final int chunk = 8;
        Runnable[] body = new Runnable[1];
        boolean parallel = workers != null && grids != null && threads > 1 && count >= 32;
        List<Future<?>> futures = new ArrayList<>();
        List<EtherSearch> made = Collections.synchronizedList(new ArrayList<>());
        if (parallel) {
            for (int w = 0; w < threads - 1; w++) {
                futures.add(workers.submit(() -> {
                    EtherSearch es = new EtherSearch(grids.get());
                    made.add(es);
                    drain(es, count, next, late, chunk, deadline, job);
                }));
            }
        }
        drain(own, count, next, late, chunk, deadline, job);
        for (Future<?> fu : futures) {
            try {
                fu.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                late.set(true);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("exact warp search worker failed", e.getCause());
            }
        }
        for (EtherSearch es : made) {
            rays += es.rays;
            aimTests += es.exactPoints;
        }
        if (late.get()) {
            timedOut = true;
        }
    }

    private void drain(EtherSearch es, int count, AtomicInteger next, AtomicBoolean late, int chunk,
                       long deadline, Job job) {
        while (!late.get()) {
            int i0 = next.getAndAdd(chunk);
            if (i0 >= count) {
                return;
            }
            if (cancelled || System.nanoTime() > deadline) {
                late.set(true);
                return;
            }
            for (int i = i0; i < Math.min(count, i0 + chunk); i++) {
                job.run(es, i);
            }
        }
    }
}
