package com.killer560.hub.livemap.autoclear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * The whole floor as one etherwarp graph, searched by the NUMBER OF WARPS.
 *
 * <p>killer560 (2026-10-04): "it is taking a lot of warps and taking like 40ms [...] Get it to the point where it
 * is only a few ms every time and prioritize using as few warps as physically possible." His log: "11 leg(s) ...
 * total 94.02 ms, 40 warp(s)". The planner before this went ROOM BY ROOM - one weighted-A* leg per doorway on
 * {@link DungeonMapPathfinder}'s room route, each leg bounded to the two rooms it joins - so a warp could never
 * skip a room even when one etherwarp reaches 57+ blocks straight through two doorways, and every leg paid for
 * its own search.
 *
 * <p><b>Nodes.</b> The floor's landing blocks, thinned to one per bucket: for every {@code bucket x bucket}
 * square of columns and every height, the landing nearest the square's centre (its representative). A landing
 * is what {@link EtherSearch#etherwarpable} accepts, minus the landing blacklist, minus whatever
 * {@code landingOk} refuses (the dungeon's bounds, the sim's roofs). Columns {@link Fine} accepts (the game passes
 * the doorways and tile centre lines) keep every landing as its own node.
 *
 * <p><b>Edges.</b> From a node, an edge to every representative within reach that {@link EtherSearch#aim}'s rule
 * reaches: a yaw/pitch is worked out, the real hop - that float yaw and pitch, the executor's look vector,
 * exactly {@code range} blocks - is cast, and only a hop seen to land on the block is an edge. So every hop of
 * every path is verified the way the old search verified it. A line to the top centre stopped early is refused
 * without the other aim points (see fastAim and {@link #partialFrom}).
 *
 * <p><b>Why not the fan.</b> The old search moved along the rays of a 6 x 7 degree fan. Near the horizon a
 * 7-degree pitch row lands on a flat floor only at about 22 blocks (pitch -6) and then 10 (pitch -13): nothing
 * between 22 and 57 blocks along a floor is ever hit, so long rooms were crossed in 9-to-20-block hops. On
 * tools/bench/FloorBench a 2-degree fan cut the warps by 30%, and aiming at every representative cut them by a
 * third.
 *
 * <p><b>Search.</b> A* on (warps, distance) lexicographically: the fewest warps the graph has, and the shortest
 * total distance among those. Once the floor is warm the heuristic is EXACT: a room click reads a per-tile
 * distance field built when warming completed, and any other goal gets a backward breadth-first search from its
 * goal set, stopped as soon as it reaches one of his own landings - so the A* only ever looks at nodes on
 * fewest-warp paths. Before the floor is warm it falls back to a geometric bound.
 *
 * <p><b>Why it is fast.</b> A node's edges depend only on the blocks its rays read, so they are worked out once
 * and kept. Every section a node's rays read is recorded, and {@link #sectionChanged}/{@link #columnChanged}
 * throw away exactly the nodes that read it, plus every node whose candidates came from a column whose landings
 * changed. {@link #warm} works edges out ahead of time from wherever he is, on worker threads when it is given
 * any, so the click itself rarely has to.
 *
 * <p>No Minecraft imports, so {@code tools/bench/FloorBench} compiles it on its own. NOT thread-safe except for
 * the change notifications: one planner thread owns an instance, and only {@link #warm} uses other threads.
 */
public final class WarpGraph {

    // ------------------------------------------------------------------------------------------- settings

    /** Hop range: the ray length, as the executor casts it. */
    public final double range;
    /** Feet above the landing block: EtherwarpPathfinder.STAND_OFFSET. */
    public final double standOffset;
    /** Bucket width in blocks; 1 is every landing (the bench's reference). */
    public final int bucket;
    /**
     * Where a landing may be at all (dungeon bounds, the sim's "under cover"); fixed for the graph's life. Given
     * the grid of the thread asking, because warm-up workers each read through their own.
     */
    public interface LandingRule {
        boolean ok(EtherSearch.Grid grid, int x, int y, int z);
    }

    private final LandingRule landingOk;
    /** The height band landings are looked for in. */
    public final int minY;
    public final int maxY;

    public WarpGraph(double range, double standOffset, int bucket, LandingRule landingOk, int minY, int maxY) {
        this.range = range;
        this.standOffset = standOffset;
        this.bucket = Math.max(1, bucket);
        this.landingOk = landingOk;
        this.minY = minY;
        this.maxY = maxY;
        Arrays.fill(mapKeys, EMPTY);
        owner = new Expander();
    }

    // ------------------------------------------------------------------------------------------- statistics

    /** Nodes whose edges had to be worked out during the last call. */
    public int expandedCold;
    /** Nodes whose known edges were reused during the last search. */
    public int expandedWarm;
    /** Rays cast during the last call. */
    public long rays;
    /** Edges looked at during the last search. */
    public long edgesScanned;
    /** Whether the last search had an exact heuristic (tile field or backward labels). */
    public boolean usedFields;
    /** Whether the last search ended on the near fallback rather than the goal itself. */
    public boolean endedNear;
    /**
     * Whether the last search's "nothing" is a proof: the floor was warm, the exact heuristic was on, and every landing
     * his first warp reaches had been walked by warm-up - so no path exists on the graph at all, and the room-by-room
     * planner (whose landings are a subset) is not worth its 670 ms.
     */
    public boolean provedNoWay;
    private boolean aimSetEmpty;
    /** Whether the last search ran out of time. */
    public boolean timedOut;
    /** Nodes in the exact goal's aim set of the last search. */
    public int goalSetSize;
    /** Nodes the backward search labelled in the last search. */
    public int labelled;
    /** Where the last plan's time went, nanoseconds: the start's expansion, the aim set, the backward labels. */
    public long nanosStart;
    public long nanosAimSet;
    public long nanosLabels;
    /** Rays cast by every expansion since the graph was made, for the bench. */
    public long totalRays;

    public int nodeCount() {
        return count;
    }

    /** How many edges are stored, over every node whose edges are worked out. */
    public long edgeCount() {
        long n = 0;
        for (int i = 0; i < count; i++) {
            n += eTo[i] != null ? eTo[i].length : 0;
        }
        return n;
    }

    /** How many nodes currently have their edges worked out. */
    public int expandedCount() {
        int n = 0;
        for (int i = 0; i < count; i++) {
            n += eTo[i] != null ? 1 : 0;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------- keys

    private static final long EMPTY = Long.MIN_VALUE;

    /** {@link LevelEtherGrid}'s section key. */
    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFFL);
    }

    private static long columnKey(int cx, int cz) {
        return ((long) (cx & 0x3FFFFF) << 22) | (cz & 0x3FFFFF);
    }

    private static int sxOf(long sectionKey) {
        return (int) (sectionKey >>> 42) << 10 >> 10;
    }

    private static int szOf(long sectionKey) {
        return (int) ((sectionKey >>> 20) & 0x3FFFFF) << 10 >> 10;
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return k;
    }

    // ------------------------------------------------------------------------------------------- landings

    /**
     * Representatives per chunk column (the buckets whose first column lies in it), packed positions. Written
     * only by the owning thread; workers only read it, and every column a batch needs is filled before it goes.
     */
    private final Map<Long, long[]> repsByColumn = new ConcurrentHashMap<>();

    private long[] repsOf(int cx, int cz) {
        long key = columnKey(cx, cz);
        long[] reps = repsByColumn.get(key);
        if (reps == null) {
            reps = owner.scanReps(cx, cz);
            repsByColumn.put(key, reps);
        }
        return reps;
    }

    /** Fills the representatives of every column within reach of a standing position. Owning thread. */
    private void ensureRepsAround(double x, double z) {
        double reach = range + 1.0;
        int cx0 = (int) Math.floor((x - reach) / 16.0);
        int cx1 = (int) Math.floor((x + reach) / 16.0);
        int cz0 = (int) Math.floor((z - reach) / 16.0);
        int cz1 = (int) Math.floor((z + reach) / 16.0);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                repsOf(cx, cz);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- expansion

    /**
     * One thread's ray caster: its own grid wrapper (which records the 16^3 sections the rays read), its own
     * {@link EtherSearch}, its own buffers. The owner has one; each warm-up worker makes its own.
     */
    private final class Expander implements EtherSearch.Grid {
        EtherSearch.Grid inner;
        boolean tracking;
        /** Expanding his own position for a click rather than a node: see startPartialFrom. */
        boolean startExpansion;
        long lastSection = Long.MIN_VALUE;
        final EtherSearch search = new EtherSearch(this);
        final double[] lookTmp = new double[3];

        // Sections read by the expansion in progress: an open-addressed set.
        long[] tKeys = new long[512];
        int tCount;

        // The result of the last expandFrom.
        int outN;
        long[] outPos = new long[1024];
        float[] outYaw = new float[1024];
        float[] outPitch = new float[1024];
        long[] usedColumns = new long[64];
        int usedColumnCount;

        Expander() {
            Arrays.fill(tKeys, EMPTY);
        }

        @Override
        public int flags(int x, int y, int z) {
            if (tracking) {
                long k = sectionKey(x >> 4, y >> 4, z >> 4);
                if (k != lastSection) {
                    lastSection = k;
                    touch(k);
                }
            }
            return inner.flags(x, y, z);
        }

        void touch(long k) {
            int mask = tKeys.length - 1;
            int i = (int) mix(k) & mask;
            while (tKeys[i] != EMPTY) {
                if (tKeys[i] == k) {
                    return;
                }
                i = (i + 1) & mask;
            }
            tKeys[i] = k;
            if (++tCount * 2 > tKeys.length) {
                long[] old = tKeys;
                tKeys = new long[old.length * 2];
                Arrays.fill(tKeys, EMPTY);
                tCount = 0;
                for (long o : old) {
                    if (o != EMPTY) {
                        touch(o);
                    }
                }
            }
        }

        long[] touched() {
            long[] out = new long[tCount];
            int n = 0;
            for (long k : tKeys) {
                if (k != EMPTY) {
                    out[n++] = k;
                }
            }
            return out;
        }

        /** Solid, two blocks of room on top, not on the landing blacklist, and allowed by landingOk. */
        boolean landing(int x, int y, int z) {
            return search.etherwarpable(x, y, z) && (flags(x, y, z) & EtherSearch.BLACKLIST) == 0
                    && landingOk.ok(this, x, y, z);
        }

        /** Every bucket representative of a chunk column: per bucket and height, the landing nearest its centre. */
        long[] scanReps(int cx, int cz) {
            int b = bucket;
            int x0 = cx << 4;
            int z0 = cz << 4;
            int bx0 = Math.floorDiv(x0 + b - 1, b);
            int bx1 = Math.floorDiv(x0 + 15, b);
            int bz0 = Math.floorDiv(z0 + b - 1, b);
            int bz1 = Math.floorDiv(z0 + 15, b);
            int h = maxY - minY + 1;
            int[] bestD = new int[h];
            long[] bestP = new long[h];
            long[] out = new long[64];
            int n = 0;
            boolean was = tracking;
            tracking = false;
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    Arrays.fill(bestD, Integer.MAX_VALUE);
                    for (int dx = 0; dx < b; dx++) {
                        for (int dz = 0; dz < b; dz++) {
                            int d = (2 * dx - (b - 1)) * (2 * dx - (b - 1)) + (2 * dz - (b - 1)) * (2 * dz - (b - 1));
                            int x = bx * b + dx;
                            int z = bz * b + dz;
                            if (fine != null && b > 1 && fine.fine(x, z)) {
                                for (int y = minY; y <= maxY; y++) {
                                    if (landing(x, y, z)) {
                                        if (n == out.length) {
                                            out = Arrays.copyOf(out, n * 2);
                                        }
                                        out[n++] = EtherSearch.pack(x, y, z);
                                    }
                                }
                                continue;
                            }
                            for (int y = minY; y <= maxY; y++) {
                                if (d < bestD[y - minY] && landing(x, y, z)) {
                                    bestD[y - minY] = d;
                                    bestP[y - minY] = EtherSearch.pack(x, y, z);
                                }
                            }
                        }
                    }
                    for (int i = 0; i < h; i++) {
                        if (bestD[i] != Integer.MAX_VALUE) {
                            if (n == out.length) {
                                out = Arrays.copyOf(out, n * 2);
                            }
                            out[n++] = bestP[i];
                        }
                    }
                }
            }
            tracking = was;
            return Arrays.copyOf(out, n);
        }

        /** The representatives of a column: the shared table, or (a worker meeting a column the owner did not
         *  fill, which it should not) worked out locally and not kept. */
        long[] reps(int cx, int cz) {
            if (this == owner) {
                return repsOf(cx, cz);
            }
            long[] reps = repsByColumn.get(columnKey(cx, cz));
            return reps != null ? reps : scanReps(cx, cz);
        }

        /**
         * Every representative in reach that a verified aim from this standing position lands on, into
         * {@link #outPos} etc. With {@code track}, the sections read are recorded from scratch.
         */
        void expandFrom(double sx, double sy, double sz, long self, boolean track) {
            if (track) {
                Arrays.fill(tKeys, EMPTY);
                tCount = 0;
                lastSection = Long.MIN_VALUE;
                tracking = true;
            }
            double ex = sx;
            double ey = sy + EtherSearch.SNEAK_EYE;
            double ez = sz;
            outN = 0;
            usedColumnCount = 0;
            double reach = range + 1.0;
            double reach2 = reach * reach;
            int cx0 = (int) Math.floor((ex - reach) / 16.0);
            int cx1 = (int) Math.floor((ex + reach) / 16.0);
            int cz0 = (int) Math.floor((ez - reach) / 16.0);
            int cz1 = (int) Math.floor((ez + reach) / 16.0);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    double nxp = Math.max(cx * 16.0, Math.min(ex, cx * 16.0 + 16.0));
                    double nzp = Math.max(cz * 16.0, Math.min(ez, cz * 16.0 + 16.0));
                    if ((nxp - ex) * (nxp - ex) + (nzp - ez) * (nzp - ez) > reach2) {
                        continue;
                    }
                    if (usedColumnCount == usedColumns.length) {
                        usedColumns = Arrays.copyOf(usedColumns, usedColumnCount * 2);
                    }
                    usedColumns[usedColumnCount++] = columnKey(cx, cz);
                    for (long rep : reps(cx, cz)) {
                        if (rep == self) {
                            continue;
                        }
                        int x = unpackX(rep);
                        int y = unpackY(rep);
                        int z = unpackZ(rep);
                        double dx = x + 0.5 - ex;
                        double dy = y + 0.5 - ey;
                        double dz = z + 0.5 - ez;
                        if (dx * dx + dy * dy + dz * dz > reach2 || !fastAim(ex, ey, ez, x, y, z)) {
                            continue;
                        }
                        if (outN == outPos.length) {
                            outPos = Arrays.copyOf(outPos, outN * 2);
                            outYaw = Arrays.copyOf(outYaw, outN * 2);
                            outPitch = Arrays.copyOf(outPitch, outN * 2);
                        }
                        outPos[outN] = rep;
                        outYaw[outN] = search.aimYaw;
                        outPitch[outN] = search.aimPitch;
                        outN++;
                    }
                }
            }
            tracking = false;
        }

        /**
         * {@link EtherSearch#aim}, cheaply refused when the straight line to the block's top centre is stopped
         * well short of it: the other aim points are inside the same block, so a wall in the way of one is in the
         * way of all of them. Only a line stopped within two blocks of the target (a lip, a step, a railing) gets
         * the full eighteen-point aim. Sets the search's aimYaw/aimPitch.
         */
        boolean fastAim(double ex, double ey, double ez, int x, int y, int z) {
            if (fullAim) {
                return search.aim(ex, ey, ez, x, y, z, range);
            }
            double tx = x + 0.5;
            double ty = y + 0.97;
            double tz = z + 0.5;
            int r = search.cast(ex, ey, ez, tx, ty, tz);
            if (r != EtherSearch.MISS && search.hitX == x && search.hitY == y && search.hitZ == z) {
                double dx = tx - ex;
                double dy = ty - ey;
                double dz = tz - ez;
                double distXZ = Math.sqrt(dx * dx + dz * dz);
                float yaw = EtherSearch.wrapDegrees((float) -Math.toDegrees(Math.atan2(dx, dz)));
                float pitch = EtherSearch.wrapDegrees((float) -Math.toDegrees(Math.atan2(dy, distXZ)));
                EtherSearch.look(yaw, pitch, lookTmp);
                int real = search.cast(ex, ey, ez, ex + lookTmp[0] * range, ey + lookTmp[1] * range,
                        ez + lookTmp[2] * range);
                if (real == EtherSearch.LANDS && search.hitX == x && search.hitY == y && search.hitZ == z) {
                    search.aimYaw = yaw;
                    search.aimPitch = pitch;
                    return true;
                }
                return search.aim(ex, ey, ez, x, y, z, range);
            }
            if (r == EtherSearch.MISS
                    || (Math.abs(search.hitX - x) <= 2 && Math.abs(search.hitY - y) <= 2
                    && Math.abs(search.hitZ - z) <= 2)) {
                return search.aimPast(ex, ey, ez, x, y, z, range, r != EtherSearch.MISS, search.hitX, search.hitY,
                        search.hitZ);
            }
            double partialFrom = startExpansion ? startPartialFrom : WarpGraph.this.partialFrom;
            if (partialFrom < 1.0) {
                double hx = search.hitX + 0.5 - ex;
                double hy = search.hitY + 0.5 - ey;
                double hz = search.hitZ + 0.5 - ez;
                double tx2 = tx - ex;
                double ty2 = ty - ey;
                double tz2 = tz - ez;
                if (hx * hx + hy * hy + hz * hz >= partialFrom * partialFrom * (tx2 * tx2 + ty2 * ty2 + tz2 * tz2)) {
                    return search.aimPast(ex, ey, ez, x, y, z, range, true, search.hitX, search.hitY, search.hitZ);
                }
            }
            return false;
        }
    }

    /** Columns where every landing is its own node (doorways: the bottlenecks, where a block either way decides
     *  whether a line passes through). Null for none. */
    public interface Fine {
        boolean fine(int x, int z);
    }

    private Fine fine;

    public void setFine(Fine fine) {
        this.fine = fine;
    }

    /** Every edge test is the full eighteen-point aim (the bench's reference); slower. */
    public boolean fullAim;
    /** A line blocked this far along (fraction of the way) still gets the full aim; 1 = only the last 2 blocks. */
    public double partialFrom = 0.6;
    /**
     * The same for the click's own start, which is paid for on every click: the partial-occlusion aims doubled its
     * worst case (7 ms on the bench) for a first hop that, without them, still reaches every landing it sees the
     * middle of.
     */
    public double startPartialFrom = 1.0;

    /** The owning thread's expander; also what plan() aims and checks with. */
    private final Expander owner;

    /** One node's worked-out edges, as a worker hands them back. */
    private static final class Result {
        long self;
        long[] to;
        float[] yaw;
        float[] pitch;
        long[] sections;
        long[] columns;
    }

    private Result capture(Expander x, long self) {
        Result r = new Result();
        r.self = self;
        r.to = Arrays.copyOf(x.outPos, x.outN);
        r.yaw = Arrays.copyOf(x.outYaw, x.outN);
        r.pitch = Arrays.copyOf(x.outPitch, x.outN);
        r.sections = x.touched();
        r.columns = Arrays.copyOf(x.usedColumns, x.usedColumnCount);
        return r;
    }

    // ------------------------------------------------------------------------------------------- changes

    /** {@code {sx, sy, sz}} for a section, {@code {cx, Integer.MIN_VALUE, cz}} for a whole column. */
    private final ConcurrentLinkedQueue<int[]> changes = new ConcurrentLinkedQueue<>();
    private volatile boolean clearAll;
    /** Bumped whenever a change is applied, so a warm-up batch computed across one is thrown away. */
    private int changeEpoch;

    /** A block in this section changed in a way the flags can see. Any thread. */
    public void sectionChanged(int sx, int sy, int sz) {
        blocksChanged(sx << 4, sy << 4, sz << 4, (sx << 4) + 15, (sy << 4) + 15, (sz << 4) + 15);
    }

    /**
     * These blocks (inclusive box) changed in a way the flags can see. Any thread. Unlike {@link #sectionChanged},
     * which throws away every node whose rays read any of the section's 4,096 blocks (one door: 2,000 to 6,000 of
     * the floor's 15,000 nodes), this re-checks only the aims whose line of sight can pass within reach of the box
     * and keeps every other edge as it was - see {@link #revalidate}.
     */
    public void blocksChanged(int x0, int y0, int z0, int x1, int y1, int z1) {
        changes.add(new int[]{Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1),
                Math.max(y0, y1), Math.max(z0, z1)});
        WarpGraph f = follower;
        if (f != null) {
            f.blocksChanged(x0, y0, z0, x1, y1, z1);
        }
    }

    /** A chunk column was (re)sent with different contents, or dropped. Any thread. */
    public void columnChanged(int cx, int cz) {
        changes.add(new int[]{cx, Integer.MIN_VALUE, cz});
        WarpGraph f = follower;
        if (f != null) {
            f.columnChanged(cx, cz);
        }
    }

    /** Forget every edge and landing. Any thread. */
    public void clear() {
        clearAll = true;
        WarpGraph f = follower;
        if (f != null) {
            f.clear();
        }
    }

    /**
     * Another graph of the same floor that is told about every change this one is told about ({@link FloorGraphs}:
     * the quick graph follows the full one, so LevelEtherGrid keeps reporting to one listener).
     */
    public volatile WarpGraph follower;

    /** Nodes whose rays read a section / any section of a column. */
    private final java.util.HashMap<Long, IntList> bySection = new java.util.HashMap<>();
    private final java.util.HashMap<Long, IntList> byColumn = new java.util.HashMap<>();
    /** Nodes whose candidates included a column's representatives. */
    private final java.util.HashMap<Long, IntList> byRepColumn = new java.util.HashMap<>();

    private static final class IntList {
        int[] a = new int[8];
        int n;

        void add(int v) {
            if (n > 0 && a[n - 1] == v) {
                return;
            }
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = v;
        }
    }

    /** {@link #applyChanges()} reading the world through {@code grid}. Owning thread. */
    public int applyChanges(EtherSearch.Grid grid) {
        owner.inner = grid;
        return applyChanges();
    }

    /** Applies queued changes. Owning thread. Returns how many nodes lost their edges. */
    public int applyChanges() {
        int dropped = 0;
        boolean any = false;
        if (clearAll) {
            clearAll = false;
            any = true;
            changes.clear();
            for (int i = 0; i < count; i++) {
                if (eTo[i] != null) {
                    dropped++;
                }
                drop(i);
            }
            bySection.clear();
            byColumn.clear();
            byRepColumn.clear();
            repsByColumn.clear();
            bannedEdges.clear();   // a new floor (a ban left after a smaller change only ever costs a warp)
        }
        int[] c;
        List<int[]> boxes = null;
        while ((c = changes.poll()) != null) {
            any = true;
            if (c.length == 6) {
                if (boxes == null) {
                    boxes = new ArrayList<>();
                }
                boxes.add(c);
                continue;
            }
            long col = columnKey(c[0], c[2]);
            if (c[1] == Integer.MIN_VALUE) {
                dropped += dropAll(byColumn.remove(col));
                dropped += dropAll(byRepColumn.remove(col));
                repsByColumn.remove(col);
                continue;
            }
            dropped += dropAll(bySection.remove(sectionKey(c[0], c[1], c[2])));
            long[] old = repsByColumn.remove(col);
            if (old != null && !Arrays.equals(old, repsOf(c[0], c[2]))) {
                // A landing appeared or went: every node that chose its candidates from here must look again.
                dropped += dropAll(byRepColumn.remove(col));
            }
        }
        if (boxes != null) {
            dropped += applyBoxes(boxes);
        }
        if (any) {
            changeEpoch++;
        }
        if (dropped > 0) {
            warmComplete = false;
            fieldsValid = false;
            droppedSinceWarm += dropped;
        }
        if (edgesChangedSinceWarm) {
            edgesChangedSinceWarm = false;
            if (warmComplete && dropped == 0) {
                // Only edges changed. Every node keeps its edges, so there is nothing to find again from where he
                // stands: carry on the finished pass with just the nodes the new edges lead to (none, usually),
                // then rebuild the fields. A re-seed would walk all ~2M edges for nothing.
                warmSeeded = true;
            }
            warmComplete = false;
            fieldsValid = false;
        }
        if (newSinceWarm.n > 0) {
            if (warmSeeded) {
                for (int k = 0; k < newSinceWarm.n; k++) {
                    warmPush(newSinceWarm.a[k]);
                }
            }
            newSinceWarm.n = 0;
        }
        return dropped;
    }

    /** Edges added or removed by the last {@link #applyChanges}' re-checks, for the bench and the log. */
    public int edgesAdded;
    public int edgesRemoved;
    /** Nodes whose aims were re-checked by the last applyChanges, and the aims it cast again. */
    public int revalidated;
    public long revalidateRays;
    /** How long the last applyChanges' re-checks took, nanoseconds. */
    public long nanosRevalidate;

    /** A changed block box (inclusive block coordinates) widened by this much: an aim's cone and float error. */
    private static final double BOX_REACH = 1.4;

    /** Worker threads for re-checks and field building, as the last {@link #warm} call gave them; may be null. */
    private ExecutorService lastWorkers;
    private Supplier<EtherSearch.Grid> lastGrids;
    private int lastThreads;

    /**
     * Block-level changes. Every node that could be affected is re-checked rather than thrown away:
     * <ul>
     *   <li>a node whose rays read a changed section has every aim whose cone from its eye into the target block
     *       passes within {@link #BOX_REACH} of a changed block cast again; every other aim keeps its answer, because
     *       an aim's answer depends only on the blocks its casts cross, and those all lie in that cone;</li>
     *   <li>a node that chose candidates from a column whose landings changed aims at the new candidates and
     *       loses its edges to the ones that went; its other answers stand (if its rays read no changed section,
     *       none of them crossed a changed block).</li>
     * </ul>
     * The answers are the same as expanding the node again from scratch would give, for a fraction of the rays.
     * Returns how many nodes lost their edges outright (none, now; kept for the caller's bookkeeping).
     */
    private int applyBoxes(List<int[]> boxes) {
        long t0 = System.nanoTime();
        java.util.HashSet<Long> sections = new java.util.HashSet<>();
        java.util.HashSet<Long> columns = new java.util.HashSet<>();
        for (int[] b : boxes) {
            for (int sx = b[0] >> 4; sx <= b[3] >> 4; sx++) {
                for (int sz = b[2] >> 4; sz <= b[5] >> 4; sz++) {
                    for (int sy = b[1] >> 4; sy <= b[4] >> 4; sy++) {
                        sections.add(sectionKey(sx, sy, sz));
                    }
                }
            }
            // A bucket that starts in the column before (bucket - 1 blocks back) is listed under that column.
            for (int sx = (b[0] - bucket + 1) >> 4; sx <= b[3] >> 4; sx++) {
                for (int sz = (b[2] - bucket + 1) >> 4; sz <= b[5] >> 4; sz++) {
                    columns.add(columnKey(sx, sz));
                }
            }
        }
        ColumnChange cc = new ColumnChange();
        List<IntList> repUsers = new ArrayList<>();
        for (long col : columns) {
            long[] old = repsByColumn.remove(col);
            int cx = (int) (col >>> 22) << 10 >> 10;
            int cz = (int) (col & 0x3FFFFF) << 10 >> 10;
            long[] now = repsOf(cx, cz);
            if (old != null && !Arrays.equals(old, now)) {
                cc.add(col, old, now);
                IntList users = byRepColumn.get(col);
                if (users != null) {
                    repUsers.add(users);
                }
            }
        }
        // Merge boxes that touch (a door spans several sections: one box per section arrives).
        List<double[]> merged = new ArrayList<>();
        for (int[] b : boxes) {
            merged.add(new double[]{b[0], b[1], b[2], b[3] + 1.0, b[4] + 1.0, b[5] + 1.0});
        }
        boolean again = true;
        while (again && merged.size() > 1) {
            again = false;
            outer:
            for (int i = 0; i < merged.size(); i++) {
                for (int j = i + 1; j < merged.size(); j++) {
                    double[] a = merged.get(i);
                    double[] c = merged.get(j);
                    if (a[0] <= c[3] && c[0] <= a[3] && a[1] <= c[4] && c[1] <= a[4] && a[2] <= c[5] && c[2] <= a[5]) {
                        for (int k = 0; k < 3; k++) {
                            a[k] = Math.min(a[k], c[k]);
                            a[k + 3] = Math.max(a[k + 3], c[k + 3]);
                        }
                        merged.remove(j);
                        again = true;
                        break outer;
                    }
                }
            }
        }
        if (Boolean.getBoolean("warpgraph.debugboxes")) {
            for (double[] b : merged) {
                System.out.println("  BOX " + Arrays.toString(b));
            }
        }
        double[] bx = new double[merged.size() * 6];
        for (int i = 0; i < merged.size(); i++) {
            double[] b = merged.get(i);
            for (int k = 0; k < 3; k++) {
                bx[6 * i + k] = b[k] - BOX_REACH;
                bx[6 * i + k + 3] = b[k + 3] + BOX_REACH;
            }
        }
        // Who to re-check, and whether their rays read a changed section (only those need the cone test).
        revalStamp++;
        ensureReval();
        List<Integer> work = new ArrayList<>();
        for (long sk : sections) {
            IntList list = bySection.get(sk);
            if (list == null) {
                continue;
            }
            for (int k = 0; k < list.n; k++) {
                int u = list.a[k];
                if (revalSeen[u] != revalStamp && eTo[u] != null) {
                    revalSeen[u] = revalStamp;
                    revalReader[u] = true;
                    work.add(u);
                }
            }
        }
        for (IntList list : repUsers) {
            for (int k = 0; k < list.n; k++) {
                int u = list.a[k];
                if (revalSeen[u] != revalStamp && eTo[u] != null) {
                    revalSeen[u] = revalStamp;
                    revalReader[u] = false;
                    work.add(u);
                }
            }
        }
        edgesAdded = 0;
        edgesRemoved = 0;
        revalidated = work.size();
        revalidateRays = 0;
        List<Reval> results = new ArrayList<>(work.size());
        ExecutorService workers = lastWorkers;
        if (workers != null && lastThreads > 1 && lastGrids != null && work.size() >= 64) {
            int threads = lastThreads;
            int per = (work.size() + threads - 1) / threads;
            List<Callable<List<Reval>>> jobs = new ArrayList<>();
            for (int i = 0; i < work.size(); i += per) {
                List<Integer> slice = work.subList(i, Math.min(work.size(), i + per));
                Supplier<EtherSearch.Grid> grids = lastGrids;
                jobs.add(() -> {
                    Expander x = new Expander();
                    x.inner = grids.get();
                    List<Reval> out = new ArrayList<>(slice.size());
                    for (int u : slice) {
                        out.add(revalCompute(x, u, bx, revalReader[u], cc));
                    }
                    return out;
                });
            }
            try {
                for (Future<List<Reval>> f : workers.invokeAll(jobs)) {
                    results.addAll(f.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.clear();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
            if (results.size() != work.size()) {
                // Interrupted: do the rest here rather than leave a node with answers from before the change.
                results.clear();
                for (int u : work) {
                    results.add(revalCompute(owner, u, bx, revalReader[u], cc));
                }
            }
        } else {
            for (int u : work) {
                results.add(revalCompute(owner, u, bx, revalReader[u], cc));
            }
        }
        int countBefore = count;
        for (Reval r : results) {
            revalidateRays += r.rays;
            registerSections(r.u, r.sections);
            if (r.added == 0 && r.removed == 0) {
                continue;
            }
            edgesAdded += r.added;
            edgesRemoved += r.removed;
            int u = r.u;
            int n = r.pos.length;
            int[] to = new int[n];
            for (int k = 0; k < n; k++) {
                to[k] = node(r.pos[k]);
            }
            eTo[u] = to;
            eYaw[u] = r.yaw;
            ePitch[u] = r.pitch;
        }
        totalRays += revalidateRays;
        for (int v = countBefore; v < count; v++) {
            newSinceWarm.add(v);
        }
        if (edgesAdded + edgesRemoved > 0) {
            // The fields and reverse edges describe the old edges, and an added edge may lead somewhere never
            // expanded: the next warm pass rebuilds both and expands what is new.
            edgesChangedSinceWarm = true;
        }
        nanosRevalidate = System.nanoTime() - t0;
        return 0;
    }

    /** One node's re-checked edges, as a worker hands them back. */
    private static final class Reval {
        int u;
        long[] pos;
        float[] yaw;
        float[] pitch;
        long[] sections;
        int added;
        int removed;
        long rays;
    }

    private int revalStamp;
    private int[] revalSeen = new int[1024];
    private boolean[] revalReader = new boolean[1024];
    /** Set when a re-check changed an edge: warming must run again (fields, reverse edges, new nodes). */
    private boolean edgesChangedSinceWarm;
    /** Nodes a re-check created (an added edge to a landing never seen): warming expands them. */
    private final IntList newSinceWarm = new IntList();

    private void ensureReval() {
        if (revalSeen.length < count + 1) {
            int n = Math.max(count + 1, revalSeen.length * 2);
            revalSeen = Arrays.copyOf(revalSeen, n);
            revalReader = Arrays.copyOf(revalReader, n);
        }
    }

    /**
     * Whether the segment from the eye to the target block's centre passes through any of the widened boxes. The
     * boxes are grown by {@link #BOX_REACH}, which covers the block's half-diagonal: every cast an aim makes runs
     * from the eye to a point inside the target block (or stops short of it), so it stays inside that cone.
     */
    private static boolean nearAnyBox(double ex, double ey, double ez, double tx, double ty, double tz, double[] bx) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        for (int i = 0; i < bx.length; i += 6) {
            if (segmentHitsBox(ex, dx, bx[i], bx[i + 3], ey, dy, bx[i + 1], bx[i + 4], ez, dz, bx[i + 2], bx[i + 5])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seen from above, the wedge from the eye through each widened box: {@code {all, ax, az, bx, bz}} per box, where
     * a and b are the box's clockwise-most and anticlockwise-most corners relative to the eye, or all = 1 when the
     * eye is inside the box (every direction can pass through it). A segment from the eye that misses the wedge
     * misses the box, so this only prunes; {@link #nearAnyBox} still decides.
     */
    private static double[] wedges(double ex, double ez, double[] bx) {
        int n = bx.length / 6;
        double[] w = new double[n * 5];
        for (int i = 0; i < n; i++) {
            double x0 = bx[6 * i] - ex;
            double z0 = bx[6 * i + 2] - ez;
            double x1 = bx[6 * i + 3] - ex;
            double z1 = bx[6 * i + 5] - ez;
            if (x0 <= 0 && x1 >= 0 && z0 <= 0 && z1 >= 0) {
                w[5 * i] = 1;
                continue;
            }
            double[] cxs = {x0, x1, x0, x1};
            double[] czs = {z0, z0, z1, z1};
            double ax = cxs[0];
            double az = czs[0];
            double bxx = cxs[0];
            double bz = czs[0];
            for (int k = 1; k < 4; k++) {
                if (ax * czs[k] - az * cxs[k] < 0) {
                    ax = cxs[k];
                    az = czs[k];
                }
                if (bxx * czs[k] - bz * cxs[k] > 0) {
                    bxx = cxs[k];
                    bz = czs[k];
                }
            }
            w[5 * i + 1] = ax;
            w[5 * i + 2] = az;
            w[5 * i + 3] = bxx;
            w[5 * i + 4] = bz;
        }
        return w;
    }

    private static boolean inAnyWedge(double ex, double ez, double tx, double tz, double[] w) {
        double dx = tx - ex;
        double dz = tz - ez;
        for (int i = 0; i < w.length; i += 5) {
            if (w[i] != 0 || (w[i + 1] * dz - w[i + 2] * dx >= -1e-9 && dx * w[i + 4] - dz * w[i + 3] >= -1e-9)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any of the 16 x 16 column's area can be in a wedge (false only when all of it is on one wrong side). */
    private static boolean columnInAnyWedge(double ex, double ez, double x0, double z0, double[] w) {
        for (int i = 0; i < w.length; i += 5) {
            if (w[i] != 0) {
                return true;
            }
            boolean allRightOfA = true;
            boolean allLeftOfB = true;
            for (int k = 0; k < 4; k++) {
                double dx = x0 + ((k & 1) != 0 ? 16.0 : 0.0) - ex;
                double dz = z0 + ((k & 2) != 0 ? 16.0 : 0.0) - ez;
                if (w[i + 1] * dz - w[i + 2] * dx >= -1e-9) {
                    allRightOfA = false;
                }
                if (dx * w[i + 4] - dz * w[i + 3] >= -1e-9) {
                    allLeftOfB = false;
                }
            }
            if (!allRightOfA && !allLeftOfB) {
                return true;
            }
        }
        return false;
    }

    /** Slab test of the segment e + t d, t in [0, 1], against the box, axis by axis. */
    private static boolean segmentHitsBox(double ex, double dx, double x0, double x1, double ey, double dy, double y0,
                                          double y1, double ez, double dz, double z0, double z1) {
        double t0 = 0.0;
        double t1 = 1.0;
        for (int a = 0; a < 3; a++) {
            double e = a == 0 ? ex : a == 1 ? ey : ez;
            double d = a == 0 ? dx : a == 1 ? dy : dz;
            double lo = a == 0 ? x0 : a == 1 ? y0 : z0;
            double hi = a == 0 ? x1 : a == 1 ? y1 : z1;
            if (Math.abs(d) < 1e-12) {
                if (e < lo || e > hi) {
                    return false;
                }
                continue;
            }
            double inv = 1.0 / d;
            double ta = (lo - e) * inv;
            double tb = (hi - e) * inv;
            if (ta > tb) {
                double t = ta;
                ta = tb;
                tb = t;
            }
            if (ta > t0) {
                t0 = ta;
            }
            if (tb < t1) {
                t1 = tb;
            }
            if (t0 > t1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-checks one node's aims (any thread with its own expander; reads only). The candidates are the same
     * representatives in the same order {@link Expander#expandFrom} takes them. A candidate it had no answer for
     * before (a representative that just appeared) is aimed at; a reader's candidate whose cone passes near a
     * changed block is aimed at again; every other candidate keeps the answer it had - an edge, with its aim, or none.
     */
    private Reval revalCompute(Expander x, int u, double[] bx, boolean reader, ColumnChange cc) {
        int[] oldTo = eTo[u];
        float[] oldYaw = eYaw[u];
        float[] oldPitch = ePitch[u];
        int on = oldTo.length;
        // The old targets as sorted packed positions, with where each came from.
        long[] oldKeys = new long[on];
        for (int k = 0; k < on; k++) {
            int t = oldTo[k];
            oldKeys[k] = EtherSearch.pack(nx[t], ny[t], nz[t]);
        }
        long[] sorted = oldKeys.clone();
        int[] sortedIdx = new int[on];
        sortIndex(sorted, sortedIdx);
        boolean[] removed = new boolean[on];
        double ex = standX(u);
        double ey = standY(u) + EtherSearch.SNEAK_EYE;
        double ez = standZ(u);
        long self = EtherSearch.pack(nx[u], ny[u], nz[u]);
        Arrays.fill(x.tKeys, EMPTY);
        x.tCount = 0;
        x.lastSection = Long.MIN_VALUE;
        x.tracking = true;
        x.outN = 0;
        long raysBefore = x.search.rays;
        double reach = range + 1.0;
        double reach2 = reach * reach;
        int cx0 = (int) Math.floor((ex - reach) / 16.0);
        int cx1 = (int) Math.floor((ex + reach) / 16.0);
        int cz0 = (int) Math.floor((ez - reach) / 16.0);
        int cz1 = (int) Math.floor((ez + reach) / 16.0);
        int added = 0;
        int nRemoved = 0;
        double[] wedges = reader ? wedges(ex, ez, bx) : null;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                double nxp = Math.max(cx * 16.0, Math.min(ex, cx * 16.0 + 16.0));
                double nzp = Math.max(cz * 16.0, Math.min(ez, cz * 16.0 + 16.0));
                if ((nxp - ex) * (nxp - ex) + (nzp - ez) * (nzp - ez) > reach2) {
                    continue;
                }
                int changed = cc.indexOf(columnKey(cx, cz));
                boolean looks = reader && columnInAnyWedge(ex, ez, cx * 16.0, cz * 16.0, wedges);
                if (!looks && changed < 0) {
                    continue;   // no ray into here can cross a changed block and no candidate here changed
                }
                for (long rep : x.reps(cx, cz)) {
                    if (rep == self) {
                        continue;
                    }
                    int tx = unpackX(rep);
                    int ty = unpackY(rep);
                    int tz = unpackZ(rep);
                    double dx = tx + 0.5 - ex;
                    double dy = ty + 0.5 - ey;
                    double dz = tz + 0.5 - ez;
                    if (dx * dx + dy * dy + dz * dz > reach2) {
                        continue;
                    }
                    boolean fresh = changed >= 0 && Arrays.binarySearch(cc.oldReps[changed], rep) < 0;
                    if (!fresh && !(looks && inAnyWedge(ex, ez, tx + 0.5, tz + 0.5, wedges)
                            && nearAnyBox(ex, ey, ez, tx + 0.5, ty + 0.5, tz + 0.5, bx))) {
                        continue;   // keeps the answer it had
                    }
                    int at = Arrays.binarySearch(sorted, rep);
                    int oi = at >= 0 ? sortedIdx[at] : -1;
                    boolean has = x.fastAim(ex, ey, ez, tx, ty, tz);
                    if (oi >= 0) {
                        if (!has && !removed[oi]) {
                            removed[oi] = true;
                            nRemoved++;
                        }
                        continue;   // still an edge: the aim it had still lands, keep it
                    }
                    if (!has) {
                        continue;
                    }
                    added++;
                    if (x.outN == x.outPos.length) {
                        x.outPos = Arrays.copyOf(x.outPos, x.outN * 2);
                        x.outYaw = Arrays.copyOf(x.outYaw, x.outN * 2);
                        x.outPitch = Arrays.copyOf(x.outPitch, x.outN * 2);
                    }
                    x.outPos[x.outN] = rep;
                    x.outYaw[x.outN] = x.search.aimYaw;
                    x.outPitch[x.outN] = x.search.aimPitch;
                    x.outN++;
                }
            }
        }
        x.tracking = false;
        // Targets that are no longer candidates at all (a representative that went).
        // A bucket's list is kept under the column its FIRST block is in, which is not always the target's own
        // column, so look in every changed list.
        for (int k = 0; k < on; k++) {
            if (removed[k]) {
                continue;
            }
            long key = oldKeys[k];
            for (int i = 0; i < cc.cols.length; i++) {
                if (Arrays.binarySearch(cc.oldReps[i], key) >= 0 && Arrays.binarySearch(cc.nowReps[i], key) < 0) {
                    removed[k] = true;
                    nRemoved++;
                    break;
                }
            }
        }
        Reval r = new Reval();
        r.u = u;
        r.added = added;
        r.removed = nRemoved;
        r.sections = x.touched();
        r.rays = x.search.rays - raysBefore;
        if (added != 0 || nRemoved != 0) {
            int n = on - nRemoved + added;
            r.pos = new long[n];
            r.yaw = new float[n];
            r.pitch = new float[n];
            int w = 0;
            for (int k = 0; k < on; k++) {
                if (!removed[k]) {
                    r.pos[w] = oldKeys[k];
                    r.yaw[w] = oldYaw[k];
                    r.pitch[w] = oldPitch[k];
                    w++;
                }
            }
            System.arraycopy(x.outPos, 0, r.pos, w, added);
            System.arraycopy(x.outYaw, 0, r.yaw, w, added);
            System.arraycopy(x.outPitch, 0, r.pitch, w, added);
        }
        return r;
    }

    /** Sorts {@code keys} ascending in place and fills {@code idx} with each sorted key's original position. */
    private static void sortIndex(long[] keys, int[] idx) {
        int n = keys.length;
        Integer[] o = new Integer[n];
        for (int i = 0; i < n; i++) {
            o[i] = i;
        }
        long[] copy = keys.clone();
        Arrays.sort(o, (a, b) -> Long.compare(copy[a], copy[b]));
        for (int i = 0; i < n; i++) {
            keys[i] = copy[o[i]];
            idx[i] = o[i];
        }
    }

    /** The columns whose representatives changed, with their old and new representatives (sorted). */
    private static final class ColumnChange {
        long[] cols = new long[0];
        long[][] oldReps = new long[0][];
        long[][] nowReps = new long[0][];

        void add(long col, long[] old, long[] now) {
            int n = cols.length;
            cols = Arrays.copyOf(cols, n + 1);
            oldReps = Arrays.copyOf(oldReps, n + 1);
            nowReps = Arrays.copyOf(nowReps, n + 1);
            cols[n] = col;
            long[] o = old.clone();
            long[] w = now.clone();
            Arrays.sort(o);
            Arrays.sort(w);
            oldReps[n] = o;
            nowReps[n] = w;
        }

        int indexOf(long col) {
            for (int i = 0; i < cols.length; i++) {
                if (cols[i] == col) {
                    return i;
                }
            }
            return -1;
        }
    }

    private void registerSections(int node, long[] sections) {
        for (long k : sections) {
            bySection.computeIfAbsent(k, q -> new IntList()).add(node);
            byColumn.computeIfAbsent(columnKey(sxOf(k), szOf(k)), q -> new IntList()).add(node);
        }
    }

    private int dropAll(IntList list) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (int k = 0; k < list.n; k++) {
            int node = list.a[k];
            if (eTo[node] != null) {
                n++;
                drop(node);
            }
        }
        return n;
    }

    private void drop(int node) {
        eTo[node] = null;
        eYaw[node] = null;
        ePitch[node] = null;
    }

    // ------------------------------------------------------------------------------------------- nodes

    private long[] mapKeys = new long[4096];
    private int[] mapVals = new int[4096];
    private int count;
    private int[] nx = new int[1024];
    private int[] ny = new int[1024];
    private int[] nz = new int[1024];
    private int[][] eTo = new int[1024][];
    private float[][] eYaw = new float[1024][];
    private float[][] ePitch = new float[1024][];

    private int find(long key) {
        int mask = mapKeys.length - 1;
        int i = (int) mix(key) & mask;
        while (true) {
            long k = mapKeys[i];
            if (k == EMPTY) {
                return -1;
            }
            if (k == key) {
                return mapVals[i];
            }
            i = (i + 1) & mask;
        }
    }

    private int node(long key) {
        int found = find(key);
        if (found >= 0) {
            return found;
        }
        if ((count + 1) * 2 > mapKeys.length) {
            long[] oldK = mapKeys;
            int[] oldV = mapVals;
            mapKeys = new long[oldK.length * 2];
            mapVals = new int[oldK.length * 2];
            Arrays.fill(mapKeys, EMPTY);
            for (int i = 0; i < oldK.length; i++) {
                if (oldK[i] != EMPTY) {
                    put(oldK[i], oldV[i]);
                }
            }
        }
        if (count == nx.length) {
            int n = count * 2;
            nx = Arrays.copyOf(nx, n);
            ny = Arrays.copyOf(ny, n);
            nz = Arrays.copyOf(nz, n);
            eTo = Arrays.copyOf(eTo, n);
            eYaw = Arrays.copyOf(eYaw, n);
            ePitch = Arrays.copyOf(ePitch, n);
        }
        int id = count++;
        nx[id] = unpackX(key);
        ny[id] = unpackY(key);
        nz[id] = unpackZ(key);
        put(key, id);
        return id;
    }

    private void put(long key, int val) {
        int mask = mapKeys.length - 1;
        int i = (int) mix(key) & mask;
        while (mapKeys[i] != EMPTY && mapKeys[i] != key) {
            i = (i + 1) & mask;
        }
        mapKeys[i] = key;
        mapVals[i] = val;
    }

    private double standX(int node) {
        return nx[node] + 0.5;
    }

    private double standY(int node) {
        return ny[node] + standOffset;
    }

    private double standZ(int node) {
        return nz[node] + 0.5;
    }

    /** The node's out-edges, worked out now (on this thread) if they are not known. */
    private int[] edges(int node) {
        int[] known = eTo[node];
        if (known != null) {
            expandedWarm++;
            return known;
        }
        expandedCold++;
        long before = owner.search.rays;
        long self = EtherSearch.pack(nx[node], ny[node], nz[node]);
        owner.expandFrom(standX(node), standY(node), standZ(node), self, true);
        rays += owner.search.rays - before;
        totalRays += owner.search.rays - before;
        integrate(node, capture(owner, self));
        return eTo[node];
    }

    /** Stores a node's edges and registers it against every section and column they depend on. */
    private void integrate(int node, Result r) {
        int n = r.to.length;
        int[] to = new int[n];
        for (int k = 0; k < n; k++) {
            to[k] = node(r.to[k]);
        }
        eTo[node] = to;
        eYaw[node] = r.yaw;
        ePitch[node] = r.pitch;
        for (long k : r.sections) {
            bySection.computeIfAbsent(k, q -> new IntList()).add(node);
            byColumn.computeIfAbsent(columnKey(sxOf(k), szOf(k)), q -> new IntList()).add(node);
        }
        for (long col : r.columns) {
            byRepColumn.computeIfAbsent(col, q -> new IntList()).add(node);
        }
    }

    // ------------------------------------------------------------------------------------------- tile fields

    /** Which map tile's click region a landing is in, for the distance fields. */
    public interface Tiles {
        /** The tile whose click region holds this landing, or -1. */
        int tileOf(int x, int y, int z);

        /**
         * The map cell whose 32 x 32 column holds this block at ANY height, or -1 off the floor. Every landing on the
         * floor is in exactly one; the exact-goal bounds use them (see altSetup).
         */
        int cellOf(int x, int z);

        int count();
    }

    private Tiles tiles;
    /** fields[0..tileFieldCount) are the tiles' click regions; the next tileFieldCount are the cells' columns. */
    private int tileFieldCount;
    private int[] cellOfNodeArr;
    /** Reverse adjacency (CSR) of every known edge, from the last time warming completed. */
    private int[] revStart;
    private int[] rev;
    /** Per tile, per node: fewest warps from the node to any node in the tile's click region; 255 = none. */
    private byte[][] fields;
    private int fieldNodes;
    private boolean fieldsValid;
    private static final int NO_WAY = 255;

    /** The map tiles; set before warming so the fields can be built when warming completes. */
    public void setTiles(Tiles tiles) {
        this.tiles = tiles;
        fieldsValid = false;
    }

    /**
     * The reverse edges, and breadth first BACKWARDS from every tile's click region over them: the exact fewest
     * warps from any node into that tile, which makes a room click's A* walk straight down its optimal paths.
     * Built when warming completes, so every node reachable from him has its edges.
     */
    /** How long the last {@link #buildFields} took, nanoseconds. */
    public long nanosFields;

    private void buildFields() {
        long tf0 = System.nanoTime();
        try {
            buildFieldsNow();
        } finally {
            nanosFields = System.nanoTime() - tf0;
        }
    }

    private void buildFieldsNow() {
        fieldsValid = false;
        if (count == 0) {
            return;
        }
        int n = count;
        int[] starts = new int[n + 1];
        for (int u = 0; u < n; u++) {
            int[] to = eTo[u];
            if (to != null) {
                for (int v : to) {
                    starts[v + 1]++;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            starts[i + 1] += starts[i];
        }
        int[] r = new int[starts[n]];
        int[] fill = Arrays.copyOf(starts, n);
        for (int u = 0; u < n; u++) {
            int[] to = eTo[u];
            if (to != null) {
                for (int v : to) {
                    r[fill[v]++] = u;
                }
            }
        }
        revStart = starts;
        rev = r;
        int tc = tiles == null ? 0 : tiles.count();
        // Fields 0..tc-1: into each tile's click region (room clicks). tc..2tc-1: into each cell's whole column
        // (lower bounds for exact goals).
        int[] tileOfNode = new int[n];
        int[] cellOfNode = new int[n];
        for (int u = 0; u < n; u++) {
            tileOfNode[u] = tiles == null ? -1 : tiles.tileOf(nx[u], ny[u], nz[u]);
            cellOfNode[u] = tiles == null ? -1 : tiles.cellOf(nx[u], nz[u]);
        }
        cellOfNodeArr = cellOfNode;
        int fc = 2 * tc;
        byte[][] out = new byte[fc][];
        ExecutorService workers = lastWorkers;
        if (workers != null && lastThreads > 1 && tc > 1) {
            // One breadth-first search per tile, independent of each other: spread over the warm-up workers.
            List<Callable<Void>> jobs = new ArrayList<>();
            int per = (fc + lastThreads - 1) / lastThreads;
            for (int t0 = 0; t0 < fc; t0 += per) {
                int from = t0;
                int to = Math.min(fc, t0 + per);
                jobs.add(() -> {
                    int[] queue = new int[n];
                    for (int t = from; t < to; t++) {
                        out[t] = t < tc ? tileField(t, n, tileOfNode, starts, r, queue)
                                : tileField(t - tc, n, cellOfNode, starts, r, queue);
                    }
                    return null;
                });
            }
            try {
                for (Future<Void> f : workers.invokeAll(jobs)) {
                    f.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        int[] queue = null;
        for (int t = 0; t < fc; t++) {
            if (out[t] == null) {
                if (queue == null) {
                    queue = new int[n];
                }
                out[t] = t < tc ? tileField(t, n, tileOfNode, starts, r, queue)
                        : tileField(t - tc, n, cellOfNode, starts, r, queue);
            }
        }
        fields = out;
        tileFieldCount = tc;
        fieldNodes = n;
        fieldsValid = true;
    }

    /** Breadth first backwards from tile {@code t}'s nodes over the reverse edges: fewest warps into the tile. */
    private static byte[] tileField(int t, int n, int[] tileOfNode, int[] starts, int[] r, int[] queue) {
        byte[] f = new byte[n];
        Arrays.fill(f, (byte) NO_WAY);
        int head = 0;
        int tail = 0;
        for (int u = 0; u < n; u++) {
            if (tileOfNode[u] == t) {
                f[u] = 0;
                queue[tail++] = u;
            }
        }
        while (head < tail) {
            int v = queue[head++];
            int d = (f[v] & 0xFF) + 1;
            if (d >= NO_WAY) {
                continue;
            }
            for (int k = starts[v]; k < starts[v + 1]; k++) {
                int u = r[k];
                if ((f[u] & 0xFF) == NO_WAY) {
                    f[u] = (byte) d;
                    queue[tail++] = u;
                }
            }
        }
        return f;
    }

    // ------------------------------------------------------------------------------------------- the search

    /** What a click is looking for. */
    public static final class Goal {
        /** The clicked block - the goal itself when {@link #region} is null, the tie-break point otherwise. */
        public int x;
        public int y;
        public int z;
        /** Non-null: any landing it accepts ends the search (a whole room tile). */
        public EtherSearch.CellTest region;
        /** The map tile {@link #region} is the click region of ({@link Tiles}), or -1. */
        public int tile = -1;
        /**
         * Not NaN: of the landings in the region reached in the FEWEST warps, the one nearest this point (x, z - the
         * clicked tile's centre). Never a warp more for it. killer560 (2026-10-06): "the quadrant I click on it takes
         * me essentially still in the hallway to that room" and "I want it to go as central and far into the square as
         * possible with the exact same etherwarps."
         */
        public double preferX = Double.NaN;
        public double preferZ = Double.NaN;

        /** Non-null: landings to settle for when the exact block cannot be reached (an alcove chest). */
        public EtherSearch.CellTest near;
        /** Non-null: nodes he may land on but never warp FROM (abilities refused there). */
        public EtherSearch.CellTest deadEnd;
    }

    // Per-search node state, stamped.
    private int stamp;
    private int[] seen = new int[1024];
    private int[] gWarps = new int[1024];
    private boolean[] closed = new boolean[1024];
    private int[] parent = new int[1024];
    private float[] pYaw = new float[1024];
    private float[] pPitch = new float[1024];
    private double[] gDist = new double[1024];
    private static final int START = -2;

    // The goal set of the search in progress: for an exact goal every node that can aim at it (with that aim),
    // for a region goal every node in it.
    private int[] inGoal = new int[1024];
    private float[] goalYaw = new float[1024];
    private float[] goalPitch = new float[1024];

    // Exact remaining warps for the search in progress (see backwardLabels).
    private int[] labelStamp = new int[1024];
    private int[] label = new int[1024];
    private int labelFloor;
    private byte[] labelField;
    private boolean labels;
    private int[] bfsQueue = new int[1024];
    private long[] visited = new long[64];

    // The start's landings, marked, so the backward search knows when it has gone far enough.
    private int startStamp;
    private int[] startMark = new int[1024];

    // Binary heap of nodes on a double key.
    private int heapSize;
    private int[] heap = new int[1024];
    private double[] heapKey = new double[1024];

    private void ensureArrays() {
        if (seen.length < count + 1) {
            int n = Math.max(count + 1, seen.length * 2);
            seen = Arrays.copyOf(seen, n);
            gWarps = Arrays.copyOf(gWarps, n);
            closed = Arrays.copyOf(closed, n);
            parent = Arrays.copyOf(parent, n);
            pYaw = Arrays.copyOf(pYaw, n);
            pPitch = Arrays.copyOf(pPitch, n);
            gDist = Arrays.copyOf(gDist, n);
            inGoal = Arrays.copyOf(inGoal, n);
            goalYaw = Arrays.copyOf(goalYaw, n);
            goalPitch = Arrays.copyOf(goalPitch, n);
            labelStamp = Arrays.copyOf(labelStamp, n);
            label = Arrays.copyOf(label, n);
            startMark = Arrays.copyOf(startMark, n);
            fW = Arrays.copyOf(fW, n);
        }
    }

    /** The bound on total warps each node was pushed with (its f), for the preferred-landing search. */
    private int[] fW = new int[1024];

    /** How long a click may spend, after it found its fewest warps, looking for the landing nearest the centre. */
    private static final long PREFER_NANOS = 5_000_000L;
    /** The last region search's landing, as the larger of its x and z offsets from the preferred point; or -1. */
    public int lastDepth = -1;

    private void push(int node, double key) {
        if (heapSize == heap.length) {
            heap = Arrays.copyOf(heap, heapSize * 2);
            heapKey = Arrays.copyOf(heapKey, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heapKey[p] <= key) {
                break;
            }
            heap[i] = heap[p];
            heapKey[i] = heapKey[p];
            i = p;
        }
        heap[i] = node;
        heapKey[i] = key;
    }

    private int pop() {
        int top = heap[0];
        int last = heap[--heapSize];
        double lastKey = heapKey[heapSize];
        int i = 0;
        int half = heapSize >>> 1;
        while (i < half) {
            int c = 2 * i + 1;
            if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) {
                c++;
            }
            if (heapKey[c] >= lastKey) {
                break;
            }
            heap[i] = heap[c];
            heapKey[i] = heapKey[c];
            i = c;
        }
        if (heapSize > 0) {
            heap[i] = last;
            heapKey[i] = lastKey;
        }
        return top;
    }

    /**
     * Among nodes with the same bound on the total warps, which to look at first. With {@link #deepFirst} the one
     * already more warps along (then nearer the goal), which walks one optimal path instead of widening over all of
     * them; the warp count is the same either way (nodes still come off in order of the bound), only the total
     * distance among equally short paths may differ. Without it, the shortest distance so far plus the straight line.
     */
    public boolean deepFirst = true;

    private double key(int f, int g, double dist, double toGoal) {
        if (deepFirst) {
            return f * DEEP_F - g * DEEP_G + toGoal;
        }
        return f * WARP + dist + toGoal;
    }

    private static final double DEEP_F = 1.0e7;
    private static final double DEEP_G = 1.0e5;

    /** Warps outweigh any distance: the key is warps * WARP + distance. */
    private static final double WARP = 100_000.0;

    // The start's own expansion, shared by the searches of one plan().
    private int startN;
    private long[] startPos = new long[256];
    private float[] startYaw = new float[256];
    private float[] startPitch = new float[256];

    /**
     * Fewest warps from {@code start} to the goal, ties to the shortest distance; when an exact goal cannot be
     * reached at all (no node can aim at it - a chest in a sealed alcove), the nearest landing {@link Goal#near}
     * accepts.
     *
     * @param start where he stands (his real position)
     * @return the hops, each standing where a warp starts and carrying the aim used FROM there (the form
     *         {@link EtherSearch#smooth} returns); the last landing is not in the list. Empty if he already
     *         satisfies the goal; null if nothing reaches it before {@code deadlineNanos}.
     */
    public List<EtherSearch.Hop> plan(EtherSearch.Grid grid, EtherSearch.Hop start, Goal goal, long deadlineNanos,
                                      int maxWarps) {
        bannedFromStart.clear();
        bannedIntoGoal.clear();
        directBanned = false;
        firmFixed = 0;
        firmDropped = 0;
        fragileLeft = false;
        List<EtherSearch.Hop> path = null;
        for (int attempt = 0; attempt <= MAX_FIRM_REPLANS; attempt++) {
            path = planAimed(grid, start, goal, deadlineNanos, maxWarps);
            if (path == null || path.isEmpty()) {
                return path;
            }
            List<Integer> bad = firmUp(path, goal);
            if (bad.isEmpty()) {
                return path;
            }
            if (System.nanoTime() > deadlineNanos) {
                break;
            }
            // Every fragile hop of this path at once, so a path with several costs one search more, not several.
            for (int i : bad) {
                dropFragile(i);
                firmDropped++;
            }
        }
        // Still a hop that only lands at the exact float aim: run it anyway (the executor plans again from wherever
        // the server leaves him), but say so in the log.
        fragileLeft = true;
        return path;
    }

    /** How many times a plan is searched again after dropping a hop that lands only at its exact aim. */
    private static final int MAX_FIRM_REPLANS = 6;
    /** Hops of the last plan whose aim was moved to one that holds (EtherSearch.AIM_MARGIN). */
    public int firmFixed;
    /** Hops dropped from the graph by the last plan because no aim at their block holds. */
    public int firmDropped;
    /** The last plan still has a hop that lands only at its exact aim (out of attempts or time). */
    public boolean fragileLeft;

    /** Edges (from << 32 | to) no aim holds for: never taken again by this graph. */
    private final java.util.HashSet<Long> bannedEdges = new java.util.HashSet<>();
    /** Per plan: landings his own position has no firm aim at, and nodes with no firm aim onto the exact goal. */
    private final java.util.HashSet<Integer> bannedFromStart = new java.util.HashSet<>();
    private final java.util.HashSet<Integer> bannedIntoGoal = new java.util.HashSet<>();
    /** Per plan: his own position has no firm aim onto the exact goal. */
    private boolean directBanned;

    private static long edgeKey(int from, int to) {
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    private boolean edgeBanned(int from, int to) {
        return !bannedEdges.isEmpty() && bannedEdges.contains(edgeKey(from, to));
    }

    /**
     * Makes every hop of a path land for any aim within {@link EtherSearch#AIM_MARGIN} of the one it will send: a hop
     * that lands only at its exact float aim is aimed again at the same block through another aim point, if one holds.
     * Returns the indices of the hops no aim holds for (empty when every hop is firm).
     */
    private List<Integer> firmUp(List<EtherSearch.Hop> path, Goal goal) {
        List<Integer> bad = new ArrayList<>();
        EtherSearch s = owner.search;
        for (int i = 0; i < path.size(); i++) {
            EtherSearch.Hop h = path.get(i);
            int land = i < lastLands.size() ? lastLands.get(i) : -1;
            int bx = land >= 0 ? nx[land] : goal.x;
            int by = land >= 0 ? ny[land] : goal.y;
            int bz = land >= 0 ? nz[land] : goal.z;
            double ey = h.y + EtherSearch.SNEAK_EYE;
            if (s.holds(h.x, ey, h.z, h.yaw, h.pitch, bx, by, bz, range)) {
                continue;
            }
            if (s.aimFirm(h.x, ey, h.z, bx, by, bz, range)) {
                h.yaw = s.aimYaw;
                h.pitch = s.aimPitch;
                firmFixed++;
                continue;
            }
            bad.add(i);
        }
        return bad;
    }

    /** Takes the hop {@code i} of the last path out of the next search (see {@link #firmUp}). */
    private void dropFragile(int i) {
        int from = i == 0 ? startFromNode : lastLands.get(i - 1);
        int land = lastLands.get(i);
        if (land < 0) {
            if (from >= 0) {
                bannedIntoGoal.add(from);
            } else {
                directBanned = true;
            }
        } else if (from >= 0) {
            bannedEdges.add(edgeKey(from, land));
        } else {
            bannedFromStart.add(land);
        }
    }

    /** {@link #plan} without the firm-aim check: the search, and the first hop aimed again from where he stands. */
    private List<EtherSearch.Hop> planAimed(EtherSearch.Grid grid, EtherSearch.Hop start, Goal goal,
                                            long deadlineNanos, int maxWarps) {
        allowNodeStart = true;
        List<EtherSearch.Hop> path = planOnce(grid, start, goal, deadlineNanos, maxWarps);
        if (startFromNode < 0 || path == null || path.isEmpty()) {
            return path;
        }
        // Planned from the node he is standing on, whose edges were aimed from its landing height (+1.05); he is
        // standing a little lower once he has settled. The first hop is aimed again from where he really is, and
        // if that cannot reach the same block the click is planned again from his own position.
        EtherSearch.Hop first = path.get(0);
        int from = startFromNode;
        double[] look = new double[3];
        EtherSearch.look(first.yaw, first.pitch, look);
        double ex = standX(from);
        double ey = standY(from) + EtherSearch.SNEAK_EYE;
        double ez = standZ(from);
        EtherSearch search = owner.search;
        if (search.cast(ex, ey, ez, ex + look[0] * range, ey + look[1] * range, ez + look[2] * range)
                == EtherSearch.LANDS
                && search.aim(start.x, start.y + EtherSearch.SNEAK_EYE, start.z, search.hitX, search.hitY,
                search.hitZ, range)) {
            first.yaw = search.aimYaw;
            first.pitch = search.aimPitch;
            return path;
        }
        allowNodeStart = false;
        return planOnce(grid, start, goal, deadlineNanos, maxWarps);
    }

    /** Whether the start may borrow the edges of the node he is standing on (see plan). */
    private boolean allowNodeStart;
    /** The node whose edges the last search started from, or -1 when it expanded his own position. */
    private int startFromNode = -1;

    /** The node he is standing on, settled or just landed, whose edges are known; or -1. */
    private int nodeUnder(double sx, double sy, double sz) {
        int bx = (int) Math.floor(sx);
        int by = (int) Math.floor(sy - 0.2);
        int bz = (int) Math.floor(sz);
        if (Math.abs(sx - (bx + 0.5)) > 0.3 || Math.abs(sz - (bz + 0.5)) > 0.3
                || Math.abs(sy - (by + standOffset)) > 0.1) {
            return -1;
        }
        int u = find(EtherSearch.pack(bx, by, bz));
        return u >= 0 && eTo[u] != null ? u : -1;
    }

    private List<EtherSearch.Hop> planOnce(EtherSearch.Grid grid, EtherSearch.Hop start, Goal goal,
                                           long deadlineNanos, int maxWarps) {
        owner.inner = grid;
        startFromNode = -1;
        expandedCold = 0;
        expandedWarm = 0;
        rays = 0;
        edgesScanned = 0;
        endedNear = false;
        timedOut = false;
        provedNoWay = false;
        usedFields = false;
        goalSetSize = 0;
        labelled = 0;
        nanosStart = 0;
        nanosAimSet = 0;
        nanosLabels = 0;
        applyChanges();
        startN = -1;
        noFields = !exactHeuristics;
        if (goal.region != null) {
            return search(start, goal.region, null, goal, deadlineNanos, maxWarps);
        }
        if (Math.floor(start.x) == goal.x && Math.floor(start.z) == goal.z
                && Math.abs(Math.floor(start.y - 0.2) - goal.y) <= 1) {
            return new ArrayList<>();
        }
        // The exact block whenever anything reaches it; an empty aim set ends that search at once.
        List<EtherSearch.Hop> exact = search(start, null, goal, goal, deadlineNanos, maxWarps);
        if (exact != null || timedOut || goal.near == null) {
            return exact;
        }
        List<EtherSearch.Hop> near = search(start, goal.near, null, goal, deadlineNanos, maxWarps);
        endedNear = near != null;
        return near;
    }

    /**
     * Whether every landing the click's first warp reaches was visited by the finished warm-up pass - so every node
     * reachable from them has its edges, and the fields are exact for them.
     */
    /** Why the last search's "nothing" was or was not a proof, for the log. */
    public String noWayWhy = "";

    private String startCoverage() {
        if (startN < 0) {
            return "not expanded";
        }
        int newer = 0;
        int unwalked = 0;
        for (int k = 0; k < startN; k++) {
            int t = find(startPos[k]);
            if (t < 0 || t >= fieldNodes) {
                newer++;
            } else if (t >= warmQueued.length || !warmQueued[t] || eTo[t] == null) {
                unwalked++;
            }
        }
        return startN + " landing(s), " + newer + " newer than the fields, " + unwalked + " not walked by warm-up";
    }

    private boolean startCovered() {
        if (!warmComplete || !fieldsValid || startN < 0) {
            return false;
        }
        for (int k = 0; k < startN; k++) {
            int t = find(startPos[k]);
            if (t < 0 || t >= fieldNodes || t >= warmQueued.length || !warmQueued[t] || eTo[t] == null) {
                return false;
            }
        }
        return true;
    }

    /** Set while a search is re-run without the exact heuristic. */
    private boolean noFields;
    /** False: searches use the geometric bound only (the bench checks that the exact ones never cost a warp). */
    public boolean exactHeuristics = true;

    private List<EtherSearch.Hop> search(EtherSearch.Hop start, EtherSearch.CellTest region, Goal exact, Goal goal,
                                         long deadlineNanos, int maxWarps) {
        List<EtherSearch.Hop> out = searchOnce(start, region, exact, goal, deadlineNanos, maxWarps);
        boolean covered = out == null && !timedOut && (aimSetEmpty || (usedFields && startCovered()));
        provedNoWay = covered;
        if (out == null) {
            noWayWhy = "timedOut " + timedOut + ", aim set empty " + aimSetEmpty + ", exact heuristic " + usedFields
                    + ", warm " + warmComplete + ", fields " + fieldsValid + ", start " + startCoverage();
        }
        if (out == null && usedFields && !timedOut && !covered) {
            // The fields only know the edges of nodes reachable from where warming started, so a start outside
            // that can be pruned wrongly. Never let that fail a click. (When every landing his first warp can
            // reach was walked by the finished warm-up, everything beyond them is known too, so "no way" from the
            // fields is the answer - and searching the floor again without them only takes time: up to 670 ms
            // per click into a room a closed door cuts off.)
            noFields = true;
            try {
                out = searchOnce(start, region, exact, goal, deadlineNanos, maxWarps);
            } finally {
                noFields = !exactHeuristics;
            }
        }
        return out;
    }

    /**
     * Every node that can aim at the exact goal: the representatives within reach of it, each tried with the
     * same verified aim an edge uses. These are the goal's in-edges, so "reached one of these" is "one warp from
     * the goal", and an empty set ends an exact search at once.
     */
    private int collectAimSet(Goal goal) {
        goalN = 0;
        int g = find(EtherSearch.pack(goal.x, goal.y, goal.z));
        if (g >= 0 && g < fieldNodes && fieldsValid && warmComplete) {
            // The goal is a node itself (a doorway, a spot on a tile's centre line): its aim set IS its in-edges,
            // worked out by the same aim, so read them rather than aim again.
            int n = 0;
            for (int k = revStart[g]; k < revStart[g + 1]; k++) {
                int u = rev[k];
                if (goal.deadEnd != null && goal.deadEnd.test(nx[u], ny[u], nz[u])) {
                    continue;
                }
                int[] to = eTo[u];
                if (to == null) {
                    continue;
                }
                for (int e = 0; e < to.length; e++) {
                    if (to[e] == g) {
                        inGoal[u] = stamp;
                    addGoalNode(u);
                        addGoalNode(u);
                        goalYaw[u] = eYaw[u][e];
                        goalPitch[u] = ePitch[u][e];
                        n++;
                        break;
                    }
                }
            }
            return n;
        }
        int n = 0;
        double gx = goal.x + 0.5;
        double gy = goal.y + 0.5;
        double gz = goal.z + 0.5;
        double reach = range + 1.0 + standOffset + EtherSearch.SNEAK_EYE;
        double reach2 = (range + 1.0) * (range + 1.0);
        int cx0 = (int) Math.floor((gx - reach) / 16.0);
        int cx1 = (int) Math.floor((gx + reach) / 16.0);
        int cz0 = (int) Math.floor((gz - reach) / 16.0);
        int cz1 = (int) Math.floor((gz + reach) / 16.0);
        long goalKey = EtherSearch.pack(goal.x, goal.y, goal.z);
        long before = owner.search.rays;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (long rep : repsOf(cx, cz)) {
                    if (rep == goalKey) {
                        continue;
                    }
                    int x = unpackX(rep);
                    int y = unpackY(rep);
                    int z = unpackZ(rep);
                    double ex = x + 0.5;
                    double ey = y + standOffset + EtherSearch.SNEAK_EYE;
                    double ez = z + 0.5;
                    double dx = gx - ex;
                    double dy = gy - ey;
                    double dz = gz - ez;
                    if (dx * dx + dy * dy + dz * dz > reach2) {
                        continue;
                    }
                    if (goal.deadEnd != null && goal.deadEnd.test(x, y, z)) {
                        continue;
                    }
                    if (!owner.fastAim(ex, ey, ez, goal.x, goal.y, goal.z)) {
                        continue;
                    }
                    int u = node(rep);
                    ensureArrays();
                    inGoal[u] = stamp;
                    addGoalNode(u);
                    goalYaw[u] = owner.search.aimYaw;
                    goalPitch[u] = owner.search.aimPitch;
                    n++;
                }
            }
        }
        rays += owner.search.rays - before;
        return n;
    }

    /** Labelled nodes after which an exact goal's backward search stops and the cell fields bound the rest. */
    public int labelBudget = 3000;
    private final byte[][] frontierField = new byte[36][];
    private int frontierN;
    private int frontierLevel;

    private int[] goalList = new int[256];
    private int goalN;

    private void addGoalNode(int u) {
        if (goalN == goalList.length) {
            goalList = Arrays.copyOf(goalList, goalN * 2);
        }
        goalList[goalN++] = u;
    }

    /** Use the tile fields as landmarks for an exact goal instead of a backward search per click (see altSetup). */
    public boolean altExact = false;
    private static final int MAX_LANDMARKS = 6;
    private final byte[][] lmField = new byte[MAX_LANDMARKS][];
    private final int[] lmMax = new int[MAX_LANDMARKS];
    private int lmN;
    private boolean altOn;

    /** Tiles holding aim-set nodes in their click region: warps(v -> such a node) >= field_T(v). */
    private final byte[][] gtField = new byte[36][];
    private int gtN;
    /** Whether some aim-set node lies in no tile's click region (a ledge, a stair) - bounded by landmarks. */
    private boolean looseGoal;

    /**
     * Lower bounds for an exact goal from the tile fields built at warm-up, instead of a backward breadth-first
     * search on every click (13-73 ms on a dense graph). The goal is reached by one warp from a node of its aim set,
     * so warps(v -> goal) = 1 + min over the aim set of warps(v -> a). Split the aim set by the tile whose click
     * region holds each node: for the nodes in tile T's region, warps(v -> a) >= field_T(v), exactly the field. For
     * the rest ("loose"), landmarks: for any tile L, warps(v -> a) >= field_L(v) - field_L(a), so the loose part is
     * at least field_L(v) - max over the loose nodes of field_L(a). The minimum over the parts is admissible, so the
     * A* still returns the fewest warps; and a node no part can be reached from is pruned.
     */
    private void altSetup() {
        lmN = 0;
        gtN = 0;
        looseGoal = false;
        if (fields == null || goalN == 0 || tiles == null || cellOfNodeArr == null) {
            return;
        }
        int tc = tileFieldCount;
        boolean[] haveCell = new boolean[tc];
        for (int i = 0; i < goalN; i++) {
            int u = goalList[i];
            int c = u < fieldNodes ? cellOfNodeArr[u] : -1;
            if (c >= 0 && c < tc) {
                if (!haveCell[c]) {
                    haveCell[c] = true;
                    gtField[gtN++] = fields[tc + c];
                }
            } else {
                looseGoal = true;   // off the floor's cells, or newer than the fields: no bound from it
            }
        }
    }

    /** The exact goal's bound from {@link #altSetup}: 1 + the least of its parts; MAX_VALUE if none is reachable. */
    private int altBound(int v) {
        if (looseGoal) {
            return 1;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < gtN; i++) {
            int f = gtField[i][v] & 0xFF;
            if (f < best) {
                best = f;
            }
        }
        return best == NO_WAY ? Integer.MAX_VALUE : 1 + best;
    }

    /**
     * Backward breadth first from the goal set over every known edge, layer by layer, until the layer that holds
     * one of the start's own landings. Then {@code label} is the exact number of warps left from any labelled
     * node and anything unlabelled needs at least {@code labelFloor}: the A* that follows only looks at nodes on
     * fewest-warp paths. Needs the reverse edges, so only once the floor is warm.
     */
    private void backwardLabels(int base, int maxWarps) {
        labels = false;
        labelField = null;
        if (!fieldsValid || !warmComplete || noFields) {
            return;
        }
        int n = Math.min(count, fieldNodes);
        if (bfsQueue.length < n) {
            bfsQueue = new int[n];
        }
        int head = 0;
        int tail = 0;
        // Visited as a bitset: a few kilobytes that stay in the first-level cache, where the stamp array does not -
        // every in-edge of the floor is tested against it once.
        int words = (n + 63) >>> 6;
        if (visited.length < words) {
            visited = new long[words];
        } else {
            Arrays.fill(visited, 0, words, 0L);
        }
        for (int u = 0; u < n; u++) {
            if (inGoal[u] == stamp) {
                visited[u >>> 6] |= 1L << (u & 63);
                labelStamp[u] = stamp;
                label[u] = base;
                bfsQueue[tail++] = u;
            }
        }
        if (tail == 0) {
            return;
        }
        int level = base;
        boolean reachedStart = false;
        boolean budgetHit = false;
        frontierN = 0;
        while (head < tail && level < maxWarps + 1) {
            int levelEnd = tail;
            for (int k = head; k < levelEnd && !reachedStart; k++) {
                reachedStart = startMark[bfsQueue[k]] == startStamp;
            }
            if (reachedStart) {
                break;
            }
            if (labelBudget > 0 && tail > labelBudget && cellOfNodeArr != null) {
                budgetHit = true;
                break;
            }
            while (head < levelEnd) {
                int v = bfsQueue[head++];
                for (int k = revStart[v]; k < revStart[v + 1]; k++) {
                    int u = rev[k];
                    long bit = 1L << (u & 63);
                    if ((visited[u >>> 6] & bit) == 0) {
                        visited[u >>> 6] |= bit;
                        labelStamp[u] = stamp;
                        label[u] = level + 1;
                        bfsQueue[tail++] = u;
                    }
                }
            }
            level++;
        }
        labelled = tail;
        // Run dry without reaching him: nothing unlabelled can reach the goal at all.
        labelFloor = reachedStart || budgetHit || level >= maxWarps + 1 ? level + 1 : Integer.MAX_VALUE;
        labels = true;
        if (budgetHit) {
            // Stopped early to save time: layer `level` (queue[head, tail)) is complete, and every path from an
            // unlabelled node enters the goal's labelled ball through it. So warps(v -> goal) >= level + warps(v ->
            // that layer), and the second term is at least the cell field of the layer's cells.
            boolean[] seenCell = new boolean[tileFieldCount];
            boolean ok = true;
            for (int k = head; k < tail && ok; k++) {
                int c = cellOfNodeArr[bfsQueue[k]];
                if (c < 0 || c >= tileFieldCount) {
                    ok = false;
                } else if (!seenCell[c]) {
                    seenCell[c] = true;
                    frontierField[frontierN++] = fields[tileFieldCount + c];
                }
            }
            if (!ok) {
                frontierN = 0;
            }
            frontierLevel = level;
        }
    }

    /** One A*: to any node {@code region} accepts, or (region null) onto the exact block of {@code exact}. */
    private List<EtherSearch.Hop> searchOnce(EtherSearch.Hop start, EtherSearch.CellTest region, Goal exact,
                                             Goal goal, long deadlineNanos, int maxWarps) {
        stamp++;
        if (stamp == Integer.MAX_VALUE) {
            stamp = 1;
            Arrays.fill(seen, 0);
            Arrays.fill(inGoal, 0);
            Arrays.fill(labelStamp, 0);
        }
        heapSize = 0;
        aimSetEmpty = false;
        EtherSearch search = owner.search;
        double sx = start.x;
        double sy = start.y;
        double sz = start.z;
        double gx = goal.x + 0.5;
        double gy = goal.y + 0.5;
        double gz = goal.z + 0.5;
        // A tile click: ties among equally few warps go to the landing nearest the tile's centre, not the clicked
        // block (which is the tile's block nearest HIM, so on his side of the room - the doorway).
        boolean preferOn = region != null && !Double.isNaN(goal.preferX) && !Double.isNaN(goal.preferZ);
        if (preferOn) {
            gx = goal.preferX;
            gz = goal.preferZ;
        }
        lastDepth = -1;
        if (region != null) {
            int bx = (int) Math.floor(sx);
            int by = (int) Math.floor(sy - 0.2);
            int bz = (int) Math.floor(sz);
            if (region.test(bx, by, bz) && search.etherwarpable(bx, by, bz)) {
                return new ArrayList<>();   // already there: no warp, wherever in the tile (the count is sacred)
            }
        }
        if (exact != null && !directBanned
                && search.aim(sx, sy + EtherSearch.SNEAK_EYE, sz, exact.x, exact.y, exact.z, range)) {
            List<EtherSearch.Hop> one = new ArrayList<>(1);
            one.add(new EtherSearch.Hop(sx, sy, sz, start.bx, start.by, start.bz, search.aimYaw, search.aimPitch));
            lastLands.clear();
            lastLands.add(-1);
            return one;
        }
        ensureArrays();
        if (exact != null) {
            long t0 = System.nanoTime();
            goalSetSize = collectAimSet(exact);
            nanosAimSet += System.nanoTime() - t0;
            if (goalSetSize == 0) {
                aimSetEmpty = true;   // nothing he can stand on can aim at it, and he cannot from here
                return null;
            }
        } else if (fieldsValid && warmComplete && !noFields && !(region == goal.region && goal.tile >= 0)) {
            int limit = Math.min(count, fieldNodes);
            for (int u = 0; u < limit; u++) {
                if (region.test(nx[u], ny[u], nz[u])) {
                    inGoal[u] = stamp;
                }
            }
        }
        // The start is not a node: he can be anywhere. Its expansion is shared by the searches of one plan.
        int under = startN < 0 && allowNodeStart ? nodeUnder(sx, sy, sz) : -1;
        if (under >= 0) {
            int[] to = eTo[under];
            int n = to.length;
            if (startPos.length < n) {
                startPos = new long[n];
                startYaw = new float[n];
                startPitch = new float[n];
            }
            for (int k = 0; k < n; k++) {
                startPos[k] = EtherSearch.pack(nx[to[k]], ny[to[k]], nz[to[k]]);
            }
            System.arraycopy(eYaw[under], 0, startYaw, 0, n);
            System.arraycopy(ePitch[under], 0, startPitch, 0, n);
            startN = n;
            startFromNode = under;
        }
        if (startN < 0) {
            expandedCold++;
            long t0 = System.nanoTime();
            long before = search.rays;
            owner.startExpansion = true;
            owner.expandFrom(sx, sy, sz, EMPTY, false);
            owner.startExpansion = false;
            rays += search.rays - before;
            nanosStart += System.nanoTime() - t0;
            int n = owner.outN;
            if (startPos.length < n) {
                startPos = new long[n];
                startYaw = new float[n];
                startPitch = new float[n];
            }
            System.arraycopy(owner.outPos, 0, startPos, 0, n);
            System.arraycopy(owner.outYaw, 0, startYaw, 0, n);
            System.arraycopy(owner.outPitch, 0, startPitch, 0, n);
            startN = n;
        }
        startStamp++;
        for (int k = 0; k < startN; k++) {
            int t = node(startPos[k]);
            ensureArrays();
            startMark[t] = startStamp;
        }
        altOn = false;
        if (region != null && region == goal.region && goal.tile >= 0 && fieldsValid && warmComplete && !noFields
                && goal.tile < tileFieldCount) {
            labels = true;
            labelField = fields[goal.tile];
        } else {
            long t0 = System.nanoTime();
            int base = exact != null ? 1 : 0;
            altOn = false;
            if (exact != null && altExact && fieldsValid && warmComplete && !noFields) {
                labels = false;
                labelField = null;
                altSetup();
                altOn = true;
            } else {
                backwardLabels(base, maxWarps);
            }
            nanosLabels += System.nanoTime() - t0;
        }
        usedFields = labels || altOn;
        for (int k = 0; k < startN; k++) {
            int t = node(startPos[k]);
            if ((!bannedFromStart.isEmpty() && bannedFromStart.contains(t))
                    || (startFromNode >= 0 && edgeBanned(startFromNode, t))) {
                continue;   // no aim at it from here holds (firmUp)
            }
            double dx = nx[t] + 0.5 - sx;
            double dy = ny[t] + standOffset - sy;
            double dz = nz[t] + 0.5 - sz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (seen[t] == stamp && len >= gDist[t]) {
                continue;
            }
            int h = heuristic(t, region, gx, gy, gz);
            if (h == Integer.MAX_VALUE) {
                continue;
            }
            seen[t] = stamp;
            closed[t] = false;
            gWarps[t] = 1;
            gDist[t] = len;
            parent[t] = START;
            pYaw[t] = startYaw[k];
            pPitch[t] = startPitch[k];
            fW[t] = 1 + h;
            push(t, key(1 + h, 1, len, distTo(t, gx, gy, gz)));
        }
        // The preferred-landing search (preferOn): the best region node so far, its warps, its distance from the
        // preferred point, and when to stop looking.
        int bestU = -1;
        int bestW = 0;
        double bestS = 0;
        long preferUntil = 0;
        while (heapSize > 0) {
            int u = pop();
            if (closed[u]) {
                continue;
            }
            if (bestU >= 0 && (fW[u] > bestW || System.nanoTime() > preferUntil)) {
                // Every landing with the fewest warps has come off once f passes them. Never a warp more for a
                // landing nearer the centre (killer560, 2026-10-06: "if it is going to take even 1 extra
                // etherwarp to go more center I don't want that").
                break;
            }
            closed[u] = true;
            int g = gWarps[u];
            if (region != null) {
                if (region.test(nx[u], ny[u], nz[u])) {
                    if (!preferOn) {
                        return reconstruct(start, u, Float.NaN, Float.NaN);
                    }
                    // Nearest the centre wins; then the deeper in (the larger of the x and z offsets, smaller).
                    double s = preferDist(u, gx, gz) + 0.001 * preferDepth(u, gx, gz);
                    if (bestU < 0) {
                        bestW = g;
                        preferUntil = Math.min(deadlineNanos, System.nanoTime() + PREFER_NANOS);
                    }
                    if (bestU < 0 || (g == bestW && s < bestS)) {
                        bestU = u;
                        bestS = s;
                    }
                    continue;   // a landing in the tile is never a step to another (that is a warp more)
                }
            } else if (inGoal[u] == stamp && g + 1 <= maxWarps
                    && (bannedIntoGoal.isEmpty() || !bannedIntoGoal.contains(u))) {
                // h is 1 here and nodes come off in key order, so g + 1 is the fewest warps there are.
                return reconstruct(start, u, goalYaw[u], goalPitch[u]);
            }
            if (g >= maxWarps || (goal.deadEnd != null && goal.deadEnd.test(nx[u], ny[u], nz[u]))) {
                continue;
            }
            if (eTo[u] == null && System.nanoTime() > deadlineNanos) {
                if (bestU >= 0) {
                    break;
                }
                timedOut = true;
                return null;
            }
            int[] to = edges(u);
            ensureArrays();
            float[] yaw = eYaw[u];
            float[] pitch = ePitch[u];
            double base = gDist[u];
            int ng = g + 1;
            edgesScanned += to.length;
            boolean anyBanned = !bannedEdges.isEmpty();
            for (int e = 0; e < to.length; e++) {
                int v = to[e];
                if (anyBanned && bannedEdges.contains(edgeKey(u, v))) {
                    continue;   // no aim along it holds (firmUp)
                }
                // Deep-first ignores distance entirely; otherwise the hop's length, worked out here rather than
                // stored with every one of a few million edges.
                double nd = deepFirst ? 0.0 : base + hopLength(u, v);
                if (seen[v] == stamp) {
                    if (closed[v] || gWarps[v] < ng || (gWarps[v] == ng && gDist[v] <= nd)) {
                        continue;
                    }
                } else {
                    seen[v] = stamp;
                    closed[v] = false;
                }
                int h = heuristic(v, region, gx, gy, gz);
                if (h == Integer.MAX_VALUE) {
                    gWarps[v] = Integer.MIN_VALUE;   // seen and never worth pushing
                    closed[v] = true;
                    continue;
                }
                gWarps[v] = ng;
                gDist[v] = nd;
                parent[v] = u;
                pYaw[v] = yaw[e];
                pPitch[v] = pitch[e];
                fW[v] = ng + h;
                push(v, key(ng + h, ng, nd, distTo(v, gx, gy, gz)));
            }
        }
        if (bestU >= 0) {
            lastDepth = preferDepth(bestU, gx, gz);
            return reconstruct(start, bestU, Float.NaN, Float.NaN);
        }
        return null;
    }

    /** Horizontal distance of a node's landing (its block's centre) from the preferred point. */
    private double preferDist(int u, double px, double pz) {
        double dx = nx[u] + 0.5 - px;
        double dz = nz[u] + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The larger of a node's x and z offsets from the preferred point, in whole blocks. */
    private int preferDepth(int u, double px, double pz) {
        return (int) Math.floor(Math.max(Math.abs(nx[u] + 0.5 - px), Math.abs(nz[u] + 0.5 - pz)));
    }

    /**
     * A lower bound on warps from node {@code v} to the goal, or MAX_VALUE when the labels prove it cannot get
     * there. Exact goal: 1 from the aim set, otherwise at least 2. Geometric: one warp moves the eye at most
     * {@code range}, the block a little more. Labels (once warm): exact, see {@link #backwardLabels}.
     */
    private int heuristic(int v, EtherSearch.CellTest region, double gx, double gy, double gz) {
        int h;
        if (region != null) {
            if (region.test(nx[v], ny[v], nz[v])) {
                return 0;
            }
            h = 1;
        } else {
            if (inGoal[v] == stamp) {
                return 1;
            }
            double d = distTo(v, gx, gy, gz);
            h = Math.max(2, 1 + (int) Math.ceil((d - 5.0 - range) / (range + 4.0)));
            if (altOn && v < fieldNodes) {
                int b = altBound(v);
                if (b == Integer.MAX_VALUE) {
                    return b;
                }
                if (b > h) {
                    h = b;
                }
            }
        }
        if (labels && v < fieldNodes) {
            int l;
            if (labelField != null) {
                l = labelField[v] & 0xFF;
                if (l == NO_WAY) {
                    return Integer.MAX_VALUE;
                }
            } else if (labelStamp[v] == stamp) {
                l = label[v];
            } else {
                l = labelFloor;
                if (l == Integer.MAX_VALUE) {
                    return l;
                }
                if (frontierN > 0) {
                    int m = NO_WAY;
                    for (int i = 0; i < frontierN; i++) {
                        int f = frontierField[i][v] & 0xFF;
                        if (f < m) {
                            m = f;
                        }
                    }
                    if (m == NO_WAY) {
                        return Integer.MAX_VALUE;   // reaches none of the cells every way to the goal passes
                    }
                    l = Math.max(l, frontierLevel + Math.max(1, m));
                }
            }
            if (l > h) {
                h = l;
            }
        }
        return h;
    }

    private double hopLength(int u, int v) {
        double dx = nx[v] - nx[u];
        double dy = ny[v] - ny[u];
        double dz = nz[v] - nz[u];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double distTo(int u, double gx, double gy, double gz) {
        double dx = nx[u] + 0.5 - gx;
        double dy = ny[u] + 0.5 - gy;
        double dz = nz[u] + 0.5 - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The hops from the start to {@code last}, plus one more aimed {@code lastYaw/lastPitch} from {@code last}
     * when that is not NaN (the final warp onto an exact goal).
     */
    private List<EtherSearch.Hop> reconstruct(EtherSearch.Hop start, int last, float lastYaw, float lastPitch) {
        ArrayList<EtherSearch.Hop> out = new ArrayList<>();
        // The node each hop lands on, in the same (reversed, then turned round) order; -1 for the exact goal block.
        lastLands.clear();
        lastLandX = Float.isNaN(lastYaw) ? nx[last] : Integer.MIN_VALUE;
        lastLandZ = Float.isNaN(lastYaw) ? nz[last] : Integer.MIN_VALUE;
        if (!Float.isNaN(lastYaw)) {
            out.add(new EtherSearch.Hop(standX(last), standY(last), standZ(last), nx[last], ny[last], nz[last],
                    lastYaw, lastPitch));
            lastLands.add(-1);
        }
        int at = last;
        while (true) {
            int p = parent[at];
            lastLands.add(at);
            if (p == START) {
                out.add(new EtherSearch.Hop(start.x, start.y, start.z, start.bx, start.by, start.bz, pYaw[at],
                        pPitch[at]));
                break;
            }
            out.add(new EtherSearch.Hop(standX(p), standY(p), standZ(p), nx[p], ny[p], nz[p], pYaw[at],
                    pPitch[at]));
            at = p;
        }
        java.util.Collections.reverse(out);
        java.util.Collections.reverse(lastLands);
        return out;
    }

    /** The block x/z the last region path lands on (MIN_VALUE after an exact-goal path). */
    public int lastLandX = Integer.MIN_VALUE;
    public int lastLandZ = Integer.MIN_VALUE;

    /** For the path {@link #reconstruct} returned last: the node each hop lands on, or -1 for an exact goal block. */
    private final ArrayList<Integer> lastLands = new ArrayList<>();

    // ------------------------------------------------------------------------------------------- warming

    private boolean[] warmQueued = new boolean[1024];
    private int[] warmQueue = new int[1024];
    private int warmHead;
    private int warmTail;
    private boolean warmComplete;
    private boolean warmSeeded;
    /** Nodes that lost their edges to a change since warming last completed. */
    public int droppedSinceWarm;
    /** Expansions thrown away because a change arrived while a batch was out. */
    public int warmDiscarded;

    /** How many nodes a worker batch holds, per worker. */
    private static final int BATCH_PER_WORKER = 8;

    /**
     * Works out edges ahead of time, breadth first from where he stands, for about {@code budgetNanos}, and when
     * everything reachable is known builds the tile fields. Returns true while there is more to do. Same thread
     * as {@link #plan}.
     *
     * @param grids    makes a grid for a worker (each worker reads through its own); null with no workers
     * @param workers  worker threads, or null to do it all on this thread
     * @param threads  how many batches to hand out at once
     */
    public boolean warm(EtherSearch.Grid grid, Supplier<EtherSearch.Grid> grids, ExecutorService workers, int threads,
                        double sx, double sy, double sz, long budgetNanos) {
        owner.inner = grid;
        lastWorkers = workers;
        lastGrids = grids;
        lastThreads = threads;
        applyChanges();
        if (warmComplete) {
            return false;
        }
        long end = System.nanoTime() + budgetNanos;
        if (!warmSeeded) {
            // Seed (again) from where he is. Anything already known is passed over cheaply, and anything a change
            // dropped since is found again.
            warmSeeded = true;
            warmHead = 0;
            warmTail = 0;
            Arrays.fill(warmQueued, false);
            ensureRepsAround(sx, sz);
            owner.expandFrom(sx, sy, sz, EMPTY, false);
            for (int k = 0; k < owner.outN; k++) {
                warmPush(node(owner.outPos[k]));
            }
        }
        boolean parallel = workers != null && threads > 1 && grids != null;
        List<Integer> batch = new ArrayList<>();
        while (warmHead != warmTail) {
            if (System.nanoTime() > end) {
                return true;
            }
            // Take the next nodes off the queue; the known ones just pass their neighbours on.
            batch.clear();
            int want = parallel ? BATCH_PER_WORKER * threads : 1;
            while (warmHead != warmTail && batch.size() < want) {
                int u = warmQueue[warmHead++];
                if (eTo[u] != null) {
                    for (int v : eTo[u]) {
                        warmPush(v);
                    }
                } else {
                    batch.add(u);
                }
            }
            if (batch.isEmpty()) {
                continue;
            }
            if (!parallel) {
                for (int u : batch) {
                    for (int v : edges(u)) {
                        warmPush(v);
                    }
                }
                continue;
            }
            for (int u : batch) {
                ensureRepsAround(standX(u), standZ(u));
            }
            int epoch = changeEpoch;
            List<Callable<List<Result>>> jobs = new ArrayList<>();
            int per = (batch.size() + threads - 1) / threads;
            for (int i = 0; i < batch.size(); i += per) {
                int[] slice = new int[Math.min(per, batch.size() - i)];
                double[] pos = new double[slice.length * 3];
                for (int k = 0; k < slice.length; k++) {
                    int u = batch.get(i + k);
                    slice[k] = u;
                    pos[3 * k] = standX(u);
                    pos[3 * k + 1] = standY(u);
                    pos[3 * k + 2] = standZ(u);
                }
                long[] selves = new long[slice.length];
                for (int k = 0; k < slice.length; k++) {
                    selves[k] = EtherSearch.pack(nx[slice[k]], ny[slice[k]], nz[slice[k]]);
                }
                jobs.add(() -> {
                    Expander x = new Expander();
                    x.inner = grids.get();
                    List<Result> out = new ArrayList<>(selves.length);
                    for (int k = 0; k < selves.length; k++) {
                        x.expandFrom(pos[3 * k], pos[3 * k + 1], pos[3 * k + 2], selves[k], true);
                        out.add(capture(x, selves[k]));
                    }
                    synchronized (WarpGraph.this) {
                        totalRays += x.search.rays;
                    }
                    return out;
                });
            }
            List<Result> results = new ArrayList<>();
            try {
                for (Future<List<Result>> f : workers.invokeAll(jobs)) {
                    results.addAll(f.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
            applyChanges();
            if (changeEpoch != epoch) {
                // Worked out against blocks that have since changed: do them again.
                warmDiscarded += results.size();
                for (int u : batch) {
                    warmQueued[u] = false;
                    warmPush(u);
                }
                continue;
            }
            for (Result r : results) {
                int u = find(r.self);
                if (u < 0) {
                    continue;
                }
                if (eTo[u] == null) {
                    integrate(u, r);
                }
                for (int v : eTo[u]) {
                    warmPush(v);
                }
            }
        }
        warmSeeded = false;
        // Something dropped while this pass ran (behind the queue head): go round again.
        if (droppedSinceWarm > 0) {
            droppedSinceWarm = 0;
            for (int i = 0; i < count; i++) {
                if (i < warmQueued.length && warmQueued[i] && eTo[i] == null) {
                    return true;
                }
            }
        }
        droppedSinceWarm = 0;
        warmComplete = true;
        warmedOnce = true;
        warmedNodes = count;
        buildFields();
        return false;
    }

    private boolean warmedOnce;

    /** True once warming has completed at least once: the floor is known, changes only adjust it. */
    public boolean warmedOnce() {
        return warmedOnce;
    }

    /**
     * For a click on a graph that was warm and has had blocks change since: finish re-warming now, on the workers the
     * last {@link #warm} call was given, for at most {@code budgetNanos}. After a change that is the re-checked edges'
     * new neighbours (usually none) and the fields - tens of milliseconds - and it puts the exact heuristic back, so
     * the click does not have to search the floor without it. Returns whether the graph is warm now.
     */
    public boolean finishWarm(EtherSearch.Grid grid, double sx, double sy, double sz, long budgetNanos) {
        return finishWarm(grid, lastGrids, lastGrids == null ? null : lastWorkers, lastGrids == null ? 1 : lastThreads,
                sx, sy, sz, budgetNanos);
    }

    /** {@link #finishWarm} on the given workers (a graph no warm-up call has given any yet). */
    public boolean finishWarm(EtherSearch.Grid grid, Supplier<EtherSearch.Grid> grids, ExecutorService workers,
                              int threads, double sx, double sy, double sz, long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        boolean more = true;
        while (more && System.nanoTime() < end) {
            more = warm(grid, grids, workers, threads, sx, sy, sz, Math.max(1, end - System.nanoTime()));
        }
        return warmComplete;
    }

    /**
     * Start the warm-up pass again from wherever the next {@link #warm} call stands, keeping every edge already known
     * (they are passed over cheaply). For a graph whose finished pass began somewhere that saw nothing of what is
     * now reachable - a pass from inside a sealed entrance that found no landing at all stays "warm" with no nodes,
     * and a door opening re-checks only existing nodes' aims. Owning thread.
     */
    public void reseed() {
        warmSeeded = false;
        warmComplete = false;
        fieldsValid = false;
    }

    /** How many nodes the graph had when warming last completed (0 before it ever has). */
    public int warmedNodes() {
        return warmedNodes;
    }

    private int warmedNodes;

    /** True when every node reachable from where warming started has its edges and nothing changed since. */
    public boolean warmDone() {
        return warmComplete;
    }

    private void warmPush(int v) {
        if (warmQueued.length <= v) {
            warmQueued = Arrays.copyOf(warmQueued, Math.max(v + 1, warmQueued.length * 2));
        }
        if (warmQueued[v]) {
            return;
        }
        warmQueued[v] = true;
        if (warmTail == warmQueue.length) {
            warmQueue = Arrays.copyOf(warmQueue, warmQueue.length * 2);
        }
        warmQueue[warmTail++] = v;
    }

    // ------------------------------------------------------------------------------------------- bench access

    /** The graph's own aim test, for the bench to compare against {@link EtherSearch#aim}. */
    public boolean fastAimForBench(EtherSearch.Grid grid, double ex, double ey, double ez, int x, int y, int z) {
        owner.inner = grid;
        return owner.fastAim(ex, ey, ez, x, y, z);
    }

    /**
     * For the bench: every node with edges is expanded again from scratch, and its target set compared with the one
     * the graph holds. Returns the number of nodes that differ (the first few are printed with what differs).
     */
    public int verifyEdges(EtherSearch.Grid grid, int print) {
        Expander x = new Expander();
        x.inner = grid;
        int bad = 0;
        for (int u = 0; u < count; u++) {
            int[] to = eTo[u];
            if (to == null) {
                continue;
            }
            long self = EtherSearch.pack(nx[u], ny[u], nz[u]);
            x.expandFrom(standX(u), standY(u), standZ(u), self, false);
            java.util.HashSet<Long> want = new java.util.HashSet<>();
            for (int k = 0; k < x.outN; k++) {
                want.add(x.outPos[k]);
            }
            java.util.HashSet<Long> have = new java.util.HashSet<>();
            for (int t : to) {
                have.add(EtherSearch.pack(nx[t], ny[t], nz[t]));
            }
            if (!want.equals(have) || have.size() != to.length) {
                bad++;
                if (bad <= print) {
                    java.util.HashSet<Long> extra = new java.util.HashSet<>(have);
                    extra.removeAll(want);
                    java.util.HashSet<Long> missing = new java.util.HashSet<>(want);
                    missing.removeAll(have);
                    System.out.println("  EDGE CHECK node " + nx[u] + "," + ny[u] + "," + nz[u] + ": " + to.length
                            + " held (" + have.size() + " distinct), " + x.outN + " fresh; " + extra.size()
                            + " extra, " + missing.size() + " missing; extra e.g. " + extra.stream().limit(3)
                            .map(k -> unpackX(k) + "," + unpackY(k) + "," + unpackZ(k)).toList());
                }
            }
        }
        return bad;
    }

    /** The owner's search over this graph's grid, for checking a path hop by hop. */
    public EtherSearch probe() {
        return owner.search;
    }
}
