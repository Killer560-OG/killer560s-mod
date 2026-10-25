package com.killer560.hub.autosecret;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Auto Secret's "Least Etherwarps" room order: the order through every room with the least travel, found with no time
 * limit (killer560, 2026-10-19: "give it no time budget. It should keep working. ... At the start it should just choose a
 * close room as it starts doing the calculations, then once it gets the best one have it follow that"). Pure - no
 * Minecraft state, so the background search may run on its own thread.
 *
 * <p>The problem is an open path from where he is through every room, any room last: {@code fromHere[i]} the trip into
 * room {@code i}, {@code between[i][j]} from room {@code i}'s end to room {@code j}'s start. Up to
 * {@link #SYNC_HELD_KARP_MAX} rooms it is solved exactly at once (Held-Karp). Past that {@link #order} answers at once with
 * the nearest-room-next order improved by moving rooms, and a worker thread keeps searching: Held-Karp up to
 * {@link #HELD_KARP_MAX}, else iterated local search and then a depth-first branch and bound to the end. The bound is the
 * Held-Karp Lagrangian one (a minimum spanning tree of what is left, its degrees pushed toward a path's by subgradient
 * steps), and a bounded table of (rooms done, last room) to the cheapest way seen there cuts every repeat. A branch and
 * bound that runs out is a proof: no order is cheaper. Each better order is published as it is found; the caller reads
 * the best so far at every room boundary.
 */
public final class LeastWarpSearch {

    /**
     * @param order  room indices in order
     * @param travel the order's travel in ticks
     * @param proven no order is cheaper
     * @param method how the order was found (and, once proven, how)
     */
    public record Snapshot(int[] order, double travel, boolean proven, String method) {
    }

    /** Solved on the calling thread, exactly, at or under this many rooms (2^12 x 12 x 12 steps, about a millisecond). */
    static final int SYNC_HELD_KARP_MAX = 12;
    /** The worker solves by Held-Karp up to this many rooms (8 MB of table, tens of milliseconds); past it, branch and bound. */
    static final int HELD_KARP_MAX = 16;

    private static final Object LOCK = new Object();
    private static Problem current;
    private static volatile Consumer<String> log = s -> {
    };

    private LeastWarpSearch() {
    }

    static void setLog(Consumer<String> sink) {
        log = sink == null ? s -> {
        } : sink;
    }

    /** Drops the problem being searched (a new run, Auto Secret stopped). */
    public static void cancel() {
        synchronized (LOCK) {
            if (current != null) {
                current.cancelled = true;
            }
            current = null;
        }
    }

    /**
     * The best order known for these rooms and trips. When it is the problem already being searched (the same rooms, the
     * same trips) that search's best so far; when it is new, an order at once - exact up to {@link #SYNC_HELD_KARP_MAX}
     * rooms, else the nearest room next improved by moves (or the last search's order for the rooms left, if better) - and
     * a background search that keeps improving it. Thread-safe.
     */
    public static Snapshot order(List<String> keys, double[] fromHere, double[][] between) {
        int n = keys.size();
        if (n == 0) {
            return new Snapshot(new int[0], 0, true, "no rooms");
        }
        synchronized (LOCK) {
            Problem prev = current;
            if (prev != null && !prev.cancelled) {
                int[] map = prev.sameAs(keys, fromHere, between);
                if (map != null) {
                    return prev.snapshotFor(map);
                }
            }
            if (prev != null) {
                prev.cancelled = true;
            }
            Problem p = new Problem(keys, fromHere, between);
            current = p;
            int[] suffix = prev == null ? null : prev.provenSuffix(p);
            if (suffix != null) {
                p.offer(suffix, TourPlanner.travel(suffix, p.f, p.b), "the rest of the proven order");
                p.prove(prev.proof);
                return p.snapshot();
            }
            if (n <= SYNC_HELD_KARP_MAX) {
                TourPlanner.Result r = TourPlanner.heldKarp(p.f, p.b);
                p.offer(r.order(), r.ticks(), "Held-Karp");
                p.prove("Held-Karp");
                log.accept(String.format(Locale.US, "[AutoSecret] Least Etherwarps: %d room(s), travel %.0f ticks, proven"
                        + " optimal by Held-Karp at once", n, r.ticks()));
                return p.snapshot();
            }
            Solver quick = new Solver(p.f, p.b, () -> false);
            int[] start = quick.localSearch(quick.nearest());
            p.offer(start, quick.travel(start), "nearest room next, improved by moves");
            if (prev != null) {
                int[] carried = prev.carriedTo(p);
                if (carried != null) {
                    carried = quick.localSearch(carried);
                    p.offer(carried, quick.travel(carried), "the last search's order for the rooms left");
                }
            }
            Thread t = new Thread(() -> search(p), "killer560smod-least-etherwarps");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            return p.snapshot();
        }
    }

    /** The worker: keeps improving {@code p}'s order until it is proven, or until {@code p} is replaced. */
    private static void search(Problem p) {
        long t0 = System.nanoTime();
        int n = p.f.length;
        try {
            Snapshot start = p.snapshot();
            String startHow = p.found();
            if (n <= HELD_KARP_MAX) {
                TourPlanner.Result r = TourPlanner.heldKarp(p.f, p.b);
                if (p.cancelled) {
                    return;
                }
                p.offer(r.order(), r.ticks(), "Held-Karp");
                p.prove("Held-Karp");
            } else {
                Solver s = new Solver(p.f, p.b, () -> p.cancelled);
                s.onImprove = (o, t, how) -> p.offer(o, t, how);
                s.seed(start.order(), start.method());
                s.iteratedLocalSearch();
                s.branchAndBound();
                if (s.aborted) {
                    Snapshot b = p.snapshot();
                    log.accept(String.format(Locale.US, "[AutoSecret] Least Etherwarps: the search over %d room(s) was"
                                    + " replaced after %.0f ms - best travel %.0f ticks (%s), not proven (lower bound %.0f,"
                                    + " %d node(s))", n, (System.nanoTime() - t0) / 1e6, b.travel(), b.method(),
                            s.rootBound, s.nodes));
                    return;
                }
                p.offer(s.best, s.bestCost, s.bestHow);
                p.prove(String.format(Locale.US, "branch and bound, %d node(s)", s.nodes));
            }
            Snapshot b = p.snapshot();
            log.accept(String.format(Locale.US, "[AutoSecret] Least Etherwarps: %d room(s), travel %.0f ticks, proven optimal"
                            + " in %.0f ms (%s); the start order was %.0f ticks (%s)", n, b.travel(),
                    (System.nanoTime() - t0) / 1e6, b.method(), start.travel(), startHow));
        } catch (Throwable t) {
            log.accept("[AutoSecret] Least Etherwarps: the search failed: " + t);
        }
    }

    /**
     * Exact, on the calling thread, no time limit (TourPlanner.shortest and the bench). {@code cancelled} may end it early;
     * the result then says not proven.
     */
    public static Snapshot solve(double[] fromHere, double[][] between, BooleanSupplier cancelled) {
        int n = fromHere.length;
        if (n == 0) {
            return new Snapshot(new int[0], 0, true, "no rooms");
        }
        if (n <= HELD_KARP_MAX) {
            TourPlanner.Result r = TourPlanner.heldKarp(fromHere, between);
            return new Snapshot(r.order(), r.ticks(), true, "Held-Karp");
        }
        return branchAndBound(fromHere, between, cancelled);
    }

    /** Local search, iterated local search and branch and bound, whatever the room count (the bench checks it on small ones). */
    static Snapshot branchAndBound(double[] fromHere, double[][] between, BooleanSupplier cancelled) {
        Solver s = new Solver(fromHere, between, cancelled == null ? () -> false : cancelled);
        int[] start = s.localSearch(s.nearest());
        s.seed(start, "nearest room next, improved by moves");
        s.iteratedLocalSearch();
        s.branchAndBound();
        return new Snapshot(s.best.clone(), s.bestCost, !s.aborted, s.bestHow + (s.aborted ? ""
                : String.format(Locale.US, "; proven by branch and bound, %d node(s), root bound %.1f", s.nodes,
                s.rootBound)));
    }

    // ------------------------------------------------------------------------------------------------- one problem

    private static final class Problem {
        final List<String> keys;
        final double[] f;
        final double[][] b;
        volatile boolean cancelled;
        private int[] best;
        private double travel = Double.POSITIVE_INFINITY;
        private String found = "";
        private boolean proven;
        private String proof = "";

        Problem(List<String> keys, double[] f, double[][] b) {
            this.keys = List.copyOf(keys);
            this.f = f.clone();
            this.b = new double[b.length][];
            for (int i = 0; i < b.length; i++) {
                this.b[i] = b[i].clone();
            }
        }

        synchronized void offer(int[] order, double t, String how) {
            if (best == null || t < travel - 1e-9) {
                best = order.clone();
                travel = t;
                found = how;
            }
        }

        synchronized String found() {
            return found;
        }

        synchronized void prove(String how) {
            proven = true;
            proof = how;
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(best.clone(), travel, proven, found + (proven ? "; proven optimal by " + proof
                    : "; the search goes on"));
        }

        /** {@code snapshot()} in the indices of a caller whose room {@code i} is this problem's {@code map[i]}. */
        synchronized Snapshot snapshotFor(int[] map) {
            int[] inv = new int[map.length];
            for (int i = 0; i < map.length; i++) {
                inv[map[i]] = i;
            }
            int[] o = new int[best.length];
            for (int k = 0; k < o.length; k++) {
                o[k] = inv[best[k]];
            }
            return new Snapshot(o, travel, proven, found + (proven ? "; proven optimal by " + proof
                    : "; the search goes on"));
        }

        private Map<String, Integer> index() {
            Map<String, Integer> m = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                m.put(keys.get(i), i);
            }
            return m.size() == keys.size() ? m : null;
        }

        /** The index map (theirs to mine) when {@code keys}/{@code f}/{@code b} are this very problem, else null. */
        int[] sameAs(List<String> keys, double[] f, double[][] b) {
            Map<String, Integer> idx = index();
            if (idx == null || keys.size() != this.keys.size()) {
                return null;
            }
            int[] map = new int[keys.size()];
            for (int i = 0; i < map.length; i++) {
                Integer m = idx.get(keys.get(i));
                if (m == null) {
                    return null;
                }
                map[i] = m;
            }
            for (int i = 0; i < map.length; i++) {
                if (!near(f[i], this.f[map[i]])) {
                    return null;
                }
                for (int j = 0; j < map.length; j++) {
                    if (i != j && !near(b[i][j], this.b[map[i]][map[j]])) {
                        return null;
                    }
                }
            }
            return map;
        }

        /**
         * When this problem's proven order began with a room {@code q} no longer has, and {@code q} is the rest of this one
         * from that room's end (the same trips), the rest of the order in {@code q}'s indices - optimal for {@code q} too,
         * since the tail of an optimal path is an optimal path from where it starts. Else null.
         */
        int[] provenSuffix(Problem q) {
            Snapshot s;
            synchronized (this) {
                if (!proven || best.length != q.keys.size() + 1) {
                    return null;
                }
                s = new Snapshot(best.clone(), travel, true, proof);
            }
            Map<String, Integer> idx = index();
            if (idx == null) {
                return null;
            }
            int first = s.order()[0];
            int[] map = new int[q.keys.size()];
            for (int i = 0; i < map.length; i++) {
                Integer m = idx.get(q.keys.get(i));
                if (m == null || m == first) {
                    return null;
                }
                map[i] = m;
            }
            for (int i = 0; i < map.length; i++) {
                if (!near(q.f[i], b[first][map[i]])) {
                    return null;
                }
                for (int j = 0; j < map.length; j++) {
                    if (i != j && !near(q.b[i][j], b[map[i]][map[j]])) {
                        return null;
                    }
                }
            }
            int[] inv = new int[keys.size()];
            Arrays.fill(inv, -1);
            for (int i = 0; i < map.length; i++) {
                inv[map[i]] = i;
            }
            int[] out = new int[map.length];
            for (int k = 1; k < s.order().length; k++) {
                out[k - 1] = inv[s.order()[k]];
            }
            return out;
        }

        /** This problem's best order carried to {@code q}'s rooms: the ones both have in this order, then q's new ones. */
        int[] carriedTo(Problem q) {
            int[] mine;
            synchronized (this) {
                mine = best.clone();
            }
            Map<String, Integer> qi = q.index();
            if (qi == null) {
                return null;
            }
            int n = q.keys.size();
            boolean[] in = new boolean[n];
            int[] out = new int[n];
            int k = 0;
            for (int i : mine) {
                Integer j = qi.get(keys.get(i));
                if (j != null && !in[j]) {
                    in[j] = true;
                    out[k++] = j;
                }
            }
            for (int j = 0; j < n; j++) {
                if (!in[j]) {
                    out[k++] = j;
                }
            }
            return out;
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= 1e-9;
    }

    // ------------------------------------------------------------------------------------------------- the solver

    interface Improve {
        void found(int[] order, double travel, String how);
    }

    static final class Solver {
        final int n;
        final double[] f;
        final double[][] b;
        /** min(b[i][j], b[j][i]): a path's edge between two rooms left costs at least this either way round. */
        final double[][] s;
        final boolean integral;
        final BooleanSupplier cancel;
        Improve onImprove;
        int[] best;
        double bestCost = Double.POSITIVE_INFINITY;
        String bestHow = "";
        long nodes;
        boolean aborted;
        double rootBound = Double.NaN;
        private final Random rnd = new Random(0x1019);
        private int[] scratch;

        Solver(double[] f, double[][] b, BooleanSupplier cancel) {
            this.n = f.length;
            this.f = f;
            this.b = b;
            this.cancel = cancel;
            this.s = new double[n][n];
            boolean whole = true;
            for (int i = 0; i < n; i++) {
                whole &= isWhole(f[i]);
                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        s[i][j] = Math.min(b[i][j], b[j][i]);
                        whole &= isWhole(b[i][j]);
                    }
                }
            }
            this.integral = whole;
            this.scratch = new int[n];
        }

        private static boolean isWhole(double v) {
            return Math.abs(v - Math.rint(v)) < 1e-9;
        }

        /** The trip from {@code u} (n = where he is) into room {@code v}. */
        double c(int u, int v) {
            return u == n ? f[v] : b[u][v];
        }

        double travel(int[] o) {
            double t = 0;
            int prev = n;
            for (int v : o) {
                t += c(prev, v);
                prev = v;
            }
            return t;
        }

        int[] nearest() {
            boolean[] used = new boolean[n];
            int[] o = new int[n];
            int prev = n;
            for (int k = 0; k < n; k++) {
                int pick = -1;
                for (int i = 0; i < n; i++) {
                    if (!used[i] && (pick < 0 || c(prev, i) < c(prev, pick))) {
                        pick = i;
                    }
                }
                used[pick] = true;
                o[k] = pick;
                prev = pick;
            }
            return o;
        }

        void seed(int[] order, String how) {
            double t = travel(order);
            if (t < bestCost - 1e-9) {
                best = order.clone();
                bestCost = t;
                bestHow = how;
            }
        }

        private void better(int[] order, String how) {
            int[] o = localSearch(order);
            double t = travel(o);
            if (t < bestCost - 1e-9) {
                best = o;
                bestCost = t;
                bestHow = how;
                if (onImprove != null) {
                    onImprove.found(o.clone(), t, how);
                }
            }
        }

        /**
         * Moves while any lowers the travel: a run of 1 to 3 rooms moved elsewhere (either way round), and a stretch of the
         * order reversed (2-opt).
         */
        int[] localSearch(int[] order) {
            int[] cur = order.clone();
            double cost = travel(cur);
            int[] cand = new int[n];
            boolean improved = true;
            while (improved) {
                improved = false;
                for (int len = 1; len <= Math.min(3, n - 1); len++) {
                    for (int i = 0; i + len <= n; i++) {
                        for (int pos = 0; pos <= n - len; pos++) {
                            if (pos == i) {
                                continue;
                            }
                            for (int rev = 0; rev < (len > 1 ? 2 : 1); rev++) {
                                orMove(cur, i, len, pos, rev == 1, cand);
                                double c = travel(cand);
                                if (c < cost - 1e-9) {
                                    System.arraycopy(cand, 0, cur, 0, n);
                                    cost = c;
                                    improved = true;
                                }
                            }
                        }
                    }
                }
                for (int i = 0; i < n - 1; i++) {
                    for (int j = i + 1; j < n; j++) {
                        System.arraycopy(cur, 0, cand, 0, n);
                        for (int a = i, z = j; a < z; a++, z--) {
                            int t = cand[a];
                            cand[a] = cand[z];
                            cand[z] = t;
                        }
                        double c = travel(cand);
                        if (c < cost - 1e-9) {
                            System.arraycopy(cand, 0, cur, 0, n);
                            cost = c;
                            improved = true;
                        }
                    }
                }
            }
            return cur;
        }

        /** {@code src} with {@code src[i..i+len)} taken out and put back at {@code pos} of what is left, into {@code out}. */
        private void orMove(int[] src, int i, int len, int pos, boolean reverse, int[] out) {
            int[] rest = scratch;
            int r = 0;
            for (int k = 0; k < n; k++) {
                if (k < i || k >= i + len) {
                    rest[r++] = src[k];
                }
            }
            int o = 0;
            for (int k = 0; k <= r; k++) {
                if (k == pos) {
                    for (int q = 0; q < len; q++) {
                        out[o++] = src[reverse ? i + len - 1 - q : i + q];
                    }
                }
                if (k < r) {
                    out[o++] = rest[k];
                }
            }
        }

        /** Kicks (two stretches of the best order swapped) each followed by the local search, a fixed number of times. */
        void iteratedLocalSearch() {
            int kicks = 20 * n;
            int[] cand = new int[n];
            for (int k = 0; k < kicks && n >= 4; k++) {
                if ((k & 15) == 0 && cancel.getAsBoolean()) {
                    aborted = true;
                    return;
                }
                int a = 1 + rnd.nextInt(n - 2);
                int bb = a + 1 + rnd.nextInt(n - a - 1);
                int cc = bb + rnd.nextInt(n - bb + 1);
                // best = A [0,a) B [a,bb) C [bb,cc) D [cc,n)  ->  A C B D
                int o = 0;
                for (int q = 0; q < a; q++) {
                    cand[o++] = best[q];
                }
                for (int q = bb; q < cc; q++) {
                    cand[o++] = best[q];
                }
                for (int q = a; q < bb; q++) {
                    cand[o++] = best[q];
                }
                for (int q = cc; q < n; q++) {
                    cand[o++] = best[q];
                }
                better(cand, "iterated local search");
            }
        }

        // ---------------------------------------------------------------------------------------- branch and bound

        private static final int ROOT_ITERS = 400;
        private static final int NODE_ITERS = 10;
        private static final int MEMO_BITS = 20;
        private int[] path;
        private double[][] piStack;
        private int[][] kidsStack;
        private int[] verts;
        private int[] deg;
        private double[] key;
        private int[] par;
        private boolean[] inTree;
        private double[] grad;
        private long[] memoKey;
        private double[] memoCost;

        void branchAndBound() {
            if (aborted) {
                return;
            }
            if (best == null) {
                seed(localSearch(nearest()), "nearest room next, improved by moves");
            }
            path = new int[n];
            piStack = new double[n + 1][n + 1];
            kidsStack = new int[n + 1][n];
            verts = new int[n + 1];
            deg = new int[n + 1];
            key = new double[n + 1];
            par = new int[n + 1];
            inTree = new boolean[n + 1];
            grad = new double[n + 1];
            if (n <= 57) {
                memoKey = new long[1 << MEMO_BITS];
                memoCost = new double[1 << MEMO_BITS];
            }
            dfs(0, n, 0L, 0.0);
        }

        private boolean prunes(double lower) {
            return integral ? Math.ceil(lower - 1e-6) >= bestCost - 1e-9 : lower >= bestCost - 1e-9;
        }

        private void dfs(int depth, int cur, long mask, double cost) {
            if (aborted) {
                return;
            }
            if ((++nodes & 1023) == 0 && cancel.getAsBoolean()) {
                aborted = true;
                return;
            }
            int left = n - depth;
            if (left == 0) {
                if (cost < bestCost - 1e-9) {
                    better(path, "branch and bound");
                }
                return;
            }
            if (left == 1) {
                for (int j = 0; j < n; j++) {
                    if ((mask & (1L << j)) == 0) {
                        path[depth] = j;
                        if (cost + c(cur, j) < bestCost - 1e-9) {
                            better(path, "branch and bound");
                        }
                    }
                }
                return;
            }
            if (depth > 0 && memoPrunes(mask, cur, cost)) {
                return;
            }
            double lb = bound(depth, cur, mask, cost);
            if (depth == 0) {
                rootBound = lb;
            }
            if (prunes(cost + lb)) {
                return;
            }
            int[] kids = kidsStack[depth];
            int k = 0;
            for (int j = 0; j < n; j++) {
                if ((mask & (1L << j)) == 0) {
                    // nearest first (insertion sort; a handful of rooms)
                    double cj = c(cur, j);
                    int q = k++;
                    while (q > 0 && c(cur, kids[q - 1]) > cj) {
                        kids[q] = kids[q - 1];
                        q--;
                    }
                    kids[q] = j;
                }
            }
            for (int q = 0; q < k; q++) {
                int j = kids[q];
                double nc = cost + c(cur, j);
                if (prunes(nc)) {
                    continue;
                }
                path[depth] = j;
                dfs(depth + 1, j, mask | (1L << j), nc);
                if (aborted) {
                    return;
                }
            }
        }

        /**
         * The table of the cheapest way seen to each (rooms done, last room): reaching one again no cheaper means everything
         * after it was already searched from a way at least as good. Bounded; a full slot is overwritten.
         */
        private boolean memoPrunes(long mask, int cur, double cost) {
            if (memoKey == null) {
                return false;
            }
            long k = (mask << 6) | cur;
            long h = k * 0x9E3779B97F4A7C15L;
            int size = memoKey.length;
            int base = (int) (h >>> (64 - MEMO_BITS));
            int empty = -1;
            for (int p = 0; p < 4; p++) {
                int slot = (base + p) & (size - 1);
                long mk = memoKey[slot];
                if (mk == k) {
                    if (memoCost[slot] <= cost + 1e-9) {
                        return true;
                    }
                    memoCost[slot] = cost;
                    return false;
                }
                if (mk == 0 && empty < 0) {
                    empty = slot;
                }
            }
            int slot = empty >= 0 ? empty : base;
            memoKey[slot] = k;
            memoCost[slot] = cost;
            return false;
        }

        /**
         * A lower bound on the travel still to come from {@code cur} through every room left: a path through them is a
         * spanning tree of {cur} + the rooms left in which {@code cur} has one edge (its own trip out) and every room at most
         * two, so its travel is at least the minimum spanning tree's (each room-room edge at the cheaper way round). The
         * Lagrangian penalties {@code pi} (free at {@code cur}, never below 0 at a room) push the tree's degrees to a path's;
         * the bound stays valid for any of them. Started from the parent's penalties, so a few steps a node suffice.
         */
        private double bound(int depth, int cur, long mask, double cost) {
            double[] pi = piStack[depth];
            if (depth == 0) {
                Arrays.fill(pi, 0);
            } else {
                System.arraycopy(piStack[depth - 1], 0, pi, 0, n + 1);
            }
            int m = 0;
            verts[m++] = cur;
            for (int j = 0; j < n; j++) {
                if ((mask & (1L << j)) == 0) {
                    verts[m++] = j;
                }
            }
            // The one-way bounds first (cheap, and the stronger ones when the trips differ much by direction): every room left
            // is entered once, by no trip cheaper than its cheapest way in; and left once, but for the last, by its cheapest way out.
            double in = 0;
            double curOut = Double.POSITIVE_INFINITY;
            double out = 0;
            double maxOut = 0;
            for (int q = 1; q < m; q++) {
                int j = verts[q];
                double cj = c(cur, j);
                curOut = Math.min(curOut, cj);   // the trip out of cur
                double ci = cj;
                double co = Double.POSITIVE_INFINITY;
                for (int r = 1; r < m; r++) {
                    int i = verts[r];
                    if (i != j) {
                        ci = Math.min(ci, b[i][j]);
                        co = Math.min(co, b[j][i]);
                    }
                }
                in += ci;
                if (m > 2) {
                    out += co;
                    maxOut = Math.max(maxOut, co);
                }
            }
            double oneWay = Math.max(in, curOut + out - maxOut);
            if (prunes(cost + oneWay)) {
                return oneWay;
            }
            int iters = depth == 0 ? ROOT_ITERS : NODE_ITERS;
            double lambda = depth == 0 ? 2.0 : 1.0;
            double bestL = oneWay;
            for (int it = 0; it < iters; it++) {
                double L = mst(m, cur, pi);
                if (L > bestL) {
                    bestL = L;
                }
                if (prunes(cost + bestL)) {
                    return bestL;
                }
                double norm = 0;
                for (int q = 0; q < m; q++) {
                    int v = verts[q];
                    double g = deg[q] - (q == 0 ? 1 : 2);
                    if (q > 0 && pi[v] <= 0 && g < 0) {
                        g = 0;
                    }
                    grad[q] = g;
                    norm += g * g;
                }
                if (norm == 0) {
                    break;
                }
                double gap = Math.max(bestCost - cost - L, integral ? 1.0 : 1e-3);
                double step = lambda * gap / norm;
                for (int q = 0; q < m; q++) {
                    int v = verts[q];
                    pi[v] += step * grad[q];
                    if (q > 0 && pi[v] < 0) {
                        pi[v] = 0;
                    }
                }
                lambda *= depth == 0 ? 0.98 : 0.9;
            }
            return bestL;
        }

        /** Prim over verts[0..m) with the penalized costs; fills deg[], returns the tree less the penalties. */
        private double mst(int m, int cur, double[] pi) {
            for (int q = 0; q < m; q++) {
                inTree[q] = false;
                deg[q] = 0;
                key[q] = Double.POSITIVE_INFINITY;
                par[q] = -1;
            }
            key[0] = 0;
            double total = 0;
            for (int added = 0; added < m; added++) {
                int u = -1;
                for (int q = 0; q < m; q++) {
                    if (!inTree[q] && (u < 0 || key[q] < key[u])) {
                        u = q;
                    }
                }
                inTree[u] = true;
                total += key[u];
                if (par[u] >= 0) {
                    deg[u]++;
                    deg[par[u]]++;
                }
                int vu = verts[u];
                for (int q = 0; q < m; q++) {
                    if (inTree[q]) {
                        continue;
                    }
                    int vq = verts[q];
                    double w = u == 0 ? c(cur, vq) : s[vu][vq];
                    w += pi[vu] + pi[vq];
                    if (w < key[q]) {
                        key[q] = w;
                        par[q] = u;
                    }
                }
            }
            double sub = pi[cur];
            for (int q = 1; q < m; q++) {
                sub += 2 * pi[verts[q]];
            }
            return total - sub;
        }
    }
}
