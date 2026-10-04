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
                return search.aim(ex, ey, ez, x, y, z, range);
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
                    return search.aim(ex, ey, ez, x, y, z, range);
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
        changes.add(new int[]{sx, sy, sz});
    }

    /** A chunk column was (re)sent with different contents, or dropped. Any thread. */
    public void columnChanged(int cx, int cz) {
        changes.add(new int[]{cx, Integer.MIN_VALUE, cz});
    }

    /** Forget every edge and landing. Any thread. */
    public void clear() {
        clearAll = true;
    }

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
        }
        int[] c;
        while ((c = changes.poll()) != null) {
            any = true;
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
        if (any) {
            changeEpoch++;
        }
        if (dropped > 0) {
            warmComplete = false;
            fieldsValid = false;
            droppedSinceWarm += dropped;
        }
        return dropped;
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
        eLen[node] = null;
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
    private float[][] eLen = new float[1024][];

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
            eLen = Arrays.copyOf(eLen, n);
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
        float[] len = new float[n];
        for (int k = 0; k < n; k++) {
            int t = node(r.to[k]);
            to[k] = t;
            double dx = nx[t] - nx[node];
            double dy = ny[t] - ny[node];
            double dz = nz[t] - nz[node];
            len[k] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        eTo[node] = to;
        eYaw[node] = r.yaw;
        ePitch[node] = r.pitch;
        eLen[node] = len;
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

        int count();
    }

    private Tiles tiles;
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
    private void buildFields() {
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
        int[] tileOfNode = new int[n];
        for (int u = 0; u < n; u++) {
            tileOfNode[u] = tiles == null ? -1 : tiles.tileOf(nx[u], ny[u], nz[u]);
        }
        byte[][] out = new byte[tc][];
        int[] queue = new int[n];
        for (int t = 0; t < tc; t++) {
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
            out[t] = f;
        }
        fields = out;
        fieldNodes = n;
        fieldsValid = true;
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
        }
    }

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

    /** Set while a search is re-run without the exact heuristic. */
    private boolean noFields;
    /** False: searches use the geometric bound only (the bench checks that the exact ones never cost a warp). */
    public boolean exactHeuristics = true;

    private List<EtherSearch.Hop> search(EtherSearch.Hop start, EtherSearch.CellTest region, Goal exact, Goal goal,
                                         long deadlineNanos, int maxWarps) {
        List<EtherSearch.Hop> out = searchOnce(start, region, exact, goal, deadlineNanos, maxWarps);
        if (out == null && usedFields && !timedOut) {
            // The fields only know the edges of nodes reachable from where warming started, so a start outside
            // that can be pruned wrongly. Never let that fail a click.
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
                    goalYaw[u] = owner.search.aimYaw;
                    goalPitch[u] = owner.search.aimPitch;
                    n++;
                }
            }
        }
        rays += owner.search.rays - before;
        return n;
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
        while (head < tail && level < maxWarps + 1) {
            int levelEnd = tail;
            for (int k = head; k < levelEnd && !reachedStart; k++) {
                reachedStart = startMark[bfsQueue[k]] == startStamp;
            }
            if (reachedStart) {
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
        labelFloor = reachedStart || level >= maxWarps + 1 ? level + 1 : Integer.MAX_VALUE;
        labels = true;
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
        EtherSearch search = owner.search;
        double sx = start.x;
        double sy = start.y;
        double sz = start.z;
        double gx = goal.x + 0.5;
        double gy = goal.y + 0.5;
        double gz = goal.z + 0.5;
        if (region != null) {
            int bx = (int) Math.floor(sx);
            int by = (int) Math.floor(sy - 0.2);
            int bz = (int) Math.floor(sz);
            if (region.test(bx, by, bz) && search.etherwarpable(bx, by, bz)) {
                return new ArrayList<>();
            }
        }
        if (exact != null && search.aim(sx, sy + EtherSearch.SNEAK_EYE, sz, exact.x, exact.y, exact.z, range)) {
            List<EtherSearch.Hop> one = new ArrayList<>(1);
            one.add(new EtherSearch.Hop(sx, sy, sz, start.bx, start.by, start.bz, search.aimYaw, search.aimPitch));
            return one;
        }
        ensureArrays();
        if (exact != null) {
            long t0 = System.nanoTime();
            goalSetSize = collectAimSet(exact);
            nanosAimSet += System.nanoTime() - t0;
            if (goalSetSize == 0) {
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
        if (region != null && region == goal.region && goal.tile >= 0 && fieldsValid && warmComplete && !noFields
                && goal.tile < fields.length) {
            labels = true;
            labelField = fields[goal.tile];
        } else {
            long t0 = System.nanoTime();
            int base = exact != null ? 1 : 0;
            backwardLabels(base, maxWarps);
            nanosLabels += System.nanoTime() - t0;
        }
        usedFields = labels;
        for (int k = 0; k < startN; k++) {
            int t = node(startPos[k]);
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
            push(t, (1 + h) * WARP + len + distTo(t, gx, gy, gz));
        }
        while (heapSize > 0) {
            int u = pop();
            if (closed[u]) {
                continue;
            }
            closed[u] = true;
            int g = gWarps[u];
            if (region != null) {
                if (region.test(nx[u], ny[u], nz[u])) {
                    return reconstruct(start, u, Float.NaN, Float.NaN);
                }
            } else if (inGoal[u] == stamp && g + 1 <= maxWarps) {
                // h is 1 here and nodes come off in key order, so g + 1 is the fewest warps there are.
                return reconstruct(start, u, goalYaw[u], goalPitch[u]);
            }
            if (g >= maxWarps || (goal.deadEnd != null && goal.deadEnd.test(nx[u], ny[u], nz[u]))) {
                continue;
            }
            if (eTo[u] == null && System.nanoTime() > deadlineNanos) {
                timedOut = true;
                return null;
            }
            int[] to = edges(u);
            ensureArrays();
            float[] len = eLen[u];
            float[] yaw = eYaw[u];
            float[] pitch = ePitch[u];
            double base = gDist[u];
            int ng = g + 1;
            edgesScanned += to.length;
            for (int e = 0; e < to.length; e++) {
                int v = to[e];
                double nd = base + len[e];
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
                push(v, (ng + h) * WARP + nd + distTo(v, gx, gy, gz));
            }
        }
        return null;
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
        }
        if (labels && v < fieldNodes) {
            int l;
            if (labelField != null) {
                l = labelField[v] & 0xFF;
                if (l == NO_WAY) {
                    return Integer.MAX_VALUE;
                }
            } else {
                l = labelStamp[v] == stamp ? label[v] : labelFloor;
                if (l == Integer.MAX_VALUE) {
                    return l;
                }
            }
            if (l > h) {
                h = l;
            }
        }
        return h;
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
        if (!Float.isNaN(lastYaw)) {
            out.add(new EtherSearch.Hop(standX(last), standY(last), standZ(last), nx[last], ny[last], nz[last],
                    lastYaw, lastPitch));
        }
        int at = last;
        while (true) {
            int p = parent[at];
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
        return out;
    }

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
        buildFields();
        return false;
    }

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

    /** The owner's search over this graph's grid, for checking a path hop by hop. */
    public EtherSearch probe() {
        return owner.search;
    }
}
