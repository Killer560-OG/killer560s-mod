package com.killer560.hub.livemap.autoclear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The etherwarp path search itself, with no Minecraft types in it.
 *
 * <p>killer560 (2026-10-04): "the found path needs to be faster, like 1-2 ms every time it has to calculate."
 * The QUOI port it replaces read every voxel through {@code LevelChunk.getBlockState}, a registry lookup for its
 * flags and, on every hit, a {@code VoxelShape} query for the collision top; it allocated a {@code Node}, a
 * {@code BlockPos} and a boxed map key per landing, ran four threads over one synchronized priority queue, and
 * expanded a fan of about a thousand rays per node. Here every block is one byte from a {@link Grid} (built
 * once per chunk section and kept - see {@link LevelEtherGrid}), the open set is a primitive binary heap over
 * parallel arrays, the closed map is open-addressed on the packed block position, the search is one thread,
 * and a leg finishes the moment any landing satisfies its goal rather than when that landing is popped.
 *
 * <p>Kept free of Minecraft imports on purpose, so {@code tools/bench/EtherSearchBench.java} can compile it on
 * its own and time it against the real room captures without booting a game. Every rule here is a straight
 * port of {@link TeleportUtils#traverseVoxels}, {@code etherwarpable} and {@code getEtherwarpDirection}, and the
 * look vector is computed with exactly the float arithmetic of {@link TeleportUtils#getLook} - the executor and
 * the sim's server-side etherwarp cast with that, so an aim found here lands where it was planned.
 */
public final class EtherSearch {

    /** Not solid for an etherwarp ray. */
    public static final int PASSABLE = 1;
    /** Cannot be stood in even though a ray passes it (skulls, pots, ladders). */
    public static final int BLOCKS_FEET = 2;
    /** Never a landing unless it is the goal itself (bottom slabs, carpets, walls, fences...). */
    public static final int BLACKLIST = 4;
    /** {@code BlockState.isAir()}, for the sim's "under cover" test. */
    public static final int AIR = 8;
    /** Bits 4-5: {@code max(1, ceil(collision top))}, clamped to 3 - where the feet go on top of the block. */
    public static final int TOP_SHIFT = 4;

    public static final double SNEAK_EYE = 1.27;

    /** One byte of flags per block. */
    public interface Grid {
        int flags(int x, int y, int z);
    }

    /** A per-block yes/no, for goals and for where a landing is allowed. */
    public interface CellTest {
        boolean test(int x, int y, int z);
    }

    /** One stop on a path: where he stands, the block under him, and the aim used FROM here. */
    public static final class Hop {
        public final double x;
        public final double y;
        public final double z;
        public final int bx;
        public final int by;
        public final int bz;
        public float yaw;
        public float pitch;

        public Hop(double x, double y, double z, int bx, int by, int bz, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** The ray fan an expansion casts, precomputed per (range, steps). */
    public static final class Fan {
        final double[] dx;
        final double[] dy;
        final double[] dz;
        final float[] yaw;
        final float[] pitch;
        final double range;
        final float yawStep;
        final float pitchStep;

        Fan(double[] dx, double[] dy, double[] dz, float[] yaw, float[] pitch, double range, float yawStep,
            float pitchStep) {
            this.yawStep = yawStep;
            this.pitchStep = pitchStep;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.yaw = yaw;
            this.pitch = pitch;
            this.range = range;
        }

        public int size() {
            return dx.length;
        }
    }

    // ------------------------------------------------------------------------------------------- statistics

    /** Nodes expanded and rays cast by the last search on this instance, for the timing log. */
    public int expanded;
    public long rays;

    private final Grid grid;
    /** Where the last {@link #cast} stopped; valid unless it returned {@link #MISS}. */
    public int hitX;
    public int hitY;
    public int hitZ;
    public int hitFlags;

    public static final int MISS = 0;
    public static final int BLOCKED = 1;
    public static final int LANDS = 2;

    public EtherSearch(Grid grid) {
        this.grid = grid;
    }

    // ------------------------------------------------------------------------------------------- geometry

    /** {@link TeleportUtils#getLook}, float arithmetic and all, into {@code out}. */
    public static void look(float yaw, float pitch, double[] out) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        out[0] = Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2;
        out[1] = Math.sin(-pitch * 0.017453292f);
        out[2] = Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2;
    }

    /** Minecraft's {@code Mth.wrapDegrees(float)}. */
    public static float wrapDegrees(float value) {
        float f = value % 360.0F;
        if (f >= 180.0F) {
            f -= 360.0F;
        }
        if (f < -180.0F) {
            f += 360.0F;
        }
        return f;
    }

    private static volatile Fan cachedFan;

    /**
     * QUOI's {@code generateRaycasts}: rows of pitch, each with as many yaws as keep the spacing even. The yaw is
     * stored WRAPPED and the direction computed from the wrapped value, because the hop is executed with
     * {@code getLook(wrapDegrees(yaw))} and 350 and -10 are not the same float arithmetic.
     */
    public static Fan fan(double range, float yawStep, float pitchStep) {
        Fan f = cachedFan;
        if (f != null && f.range == range && f.yawStep == yawStep && f.pitchStep == pitchStep) {
            return f;
        }
        List<double[]> dirs = new ArrayList<>();
        List<float[]> rots = new ArrayList<>();
        double[] v = new double[3];
        for (float pitch = -90f; pitch <= 90f; pitch += pitchStep) {
            float actualYawStep = yawStep / Math.max(0.01f, (float) Math.cos(Math.toRadians(pitch)));
            for (float yaw = 0f; yaw < 360f; yaw += actualYawStep) {
                float w = wrapDegrees(yaw);
                look(w, pitch, v);
                dirs.add(new double[]{v[0] * range, v[1] * range, v[2] * range});
                rots.add(new float[]{w, pitch});
            }
        }
        int n = dirs.size();
        double[] dx = new double[n];
        double[] dy = new double[n];
        double[] dz = new double[n];
        float[] yaws = new float[n];
        float[] pitches = new float[n];
        for (int i = 0; i < n; i++) {
            dx[i] = dirs.get(i)[0];
            dy[i] = dirs.get(i)[1];
            dz[i] = dirs.get(i)[2];
            yaws[i] = rots.get(i)[0];
            pitches[i] = rots.get(i)[1];
        }
        Fan out = new Fan(dx, dy, dz, yaws, pitches, range, yawStep, pitchStep);
        cachedFan = out;
        return out;
    }

    /**
     * {@link TeleportUtils#traverseVoxels} with {@code etherwarp = true}: the first solid block along the ray,
     * and whether there are two blocks of standing room on top of it.
     *
     * @return {@link #MISS}, {@link #BLOCKED} (solid but no room on it - {@link #hitX} etc. are still set) or
     *         {@link #LANDS}
     */
    public int cast(double x0, double y0, double z0, double x1, double y1, double z1) {
        rays++;
        double x = Math.floor(x0);
        double y = Math.floor(y0);
        double z = Math.floor(z0);
        double endX = Math.floor(x1);
        double endY = Math.floor(y1);
        double endZ = Math.floor(z1);
        double dirX = x1 - x0;
        double dirY = y1 - y0;
        double dirZ = z1 - z0;
        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);
        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);
        for (int iter = 0; iter < 1000; iter++) {
            int ix = (int) x;
            int iy = (int) y;
            int iz = (int) z;
            int f = grid.flags(ix, iy, iz);
            if ((f & PASSABLE) == 0) {
                hitX = ix;
                hitY = iy;
                hitZ = iz;
                hitFlags = f;
                int base = iy + Math.max(1, (f >> TOP_SHIFT) & 3);
                int feet = grid.flags(ix, base, iz);
                if ((feet & PASSABLE) == 0 || (feet & BLOCKS_FEET) != 0) {
                    return BLOCKED;
                }
                int head = grid.flags(ix, base + 1, iz);
                if ((head & PASSABLE) == 0 || (head & BLOCKS_FEET) != 0) {
                    return BLOCKED;
                }
                return LANDS;
            }
            if (x == endX && y == endY && z == endZ) {
                return MISS;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return MISS;
    }

    /** {@link TeleportUtils#etherwarpable}: solid, with two blocks of standing room on top. */
    public boolean etherwarpable(int x, int y, int z) {
        int f = grid.flags(x, y, z);
        if ((f & PASSABLE) != 0) {
            return false;
        }
        int base = y + Math.max(1, (f >> TOP_SHIFT) & 3);
        int feet = grid.flags(x, base, z);
        if ((feet & PASSABLE) == 0 || (feet & BLOCKS_FEET) != 0) {
            return false;
        }
        int head = grid.flags(x, base + 1, z);
        return (head & PASSABLE) != 0 && (head & BLOCKS_FEET) == 0;
    }

    /** Aim points inside a block's faces, best first - see {@link TeleportUtils#getEtherwarpDirection}. */
    private static final double[][] AIM_POINTS = {
            {0.5, 0.97, 0.5},
            {0.3, 0.97, 0.3}, {0.7, 0.97, 0.3}, {0.3, 0.97, 0.7}, {0.7, 0.97, 0.7},
            {0.5, 0.97, 0.15}, {0.5, 0.97, 0.85}, {0.15, 0.97, 0.5}, {0.85, 0.97, 0.5},
            {0.03, 0.5, 0.5}, {0.97, 0.5, 0.5}, {0.5, 0.5, 0.03}, {0.5, 0.5, 0.97},
            {0.03, 0.85, 0.5}, {0.97, 0.85, 0.5}, {0.5, 0.85, 0.03}, {0.5, 0.85, 0.97},
            {0.5, 0.03, 0.5}
    };

    private final double[] lookTmp = new double[3];
    /** The aim found by the last successful {@link #aim}. */
    public float aimYaw;
    public float aimPitch;

    /**
     * {@link TeleportUtils#getEtherwarpDirection} with {@code thorough = false}: an aim from {@code eye} that the
     * real hop - this float yaw and pitch, for exactly {@code range} blocks - lands on the block. Sets
     * {@link #aimYaw}/{@link #aimPitch}.
     */
    public boolean aim(double ex, double ey, double ez, int bx, int by, int bz, double range) {
        double cx = bx + 0.5 - ex;
        double cy = by + 0.5 - ey;
        double cz = bz + 0.5 - ez;
        if (cx * cx + cy * cy + cz * cz > (range + 1) * (range + 1)) {
            return false;
        }
        for (double[] o : AIM_POINTS) {
            double tx = bx + o[0];
            double ty = by + o[1];
            double tz = bz + o[2];
            if (cast(ex, ey, ez, tx, ty, tz) == MISS || hitX != bx || hitY != by || hitZ != bz) {
                continue;
            }
            double dx = tx - ex;
            double dy = ty - ey;
            double dz = tz - ez;
            double distXZ = Math.sqrt(dx * dx + dz * dz);
            float yaw = wrapDegrees((float) -Math.toDegrees(Math.atan2(dx, dz)));
            float pitch = wrapDegrees((float) -Math.toDegrees(Math.atan2(dy, distXZ)));
            look(yaw, pitch, lookTmp);
            int real = cast(ex, ey, ez, ex + lookTmp[0] * range, ey + lookTmp[1] * range, ez + lookTmp[2] * range);
            if (real == LANDS && hitX == bx && hitY == by && hitZ == bz) {
                aimYaw = yaw;
                aimPitch = pitch;
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------- A*

    /** What one leg is searching for. */
    public static final class Leg {
        public int goalX;
        public int goalY;
        public int goalZ;
        /** Squared block radius around the goal the direct shot may try; 0 for "the goal block only". */
        public int directRadiusSq;
        public CellTest isGoal;
        /** Where a landing may be; null for anywhere. */
        public CellTest landingOk;
        public Fan fan;
        public double hWeight;
        /** Feet height above the block top: 1.05, Hypixel's landing and the sim's (EtherwarpPathfinder.STAND_OFFSET). */
        public double standOffset;
        public long deadlineNanos;
        public int maxDirectCandidates = 8;
    }

    // Node storage, parallel arrays.
    private int count;
    private int[] nx = new int[256];
    private int[] ny = new int[256];
    private int[] nz = new int[256];
    private double[] ng = new double[256];
    private double[] nf = new double[256];
    private int[] nparent = new int[256];
    private float[] nyaw = new float[256];
    private float[] npitch = new float[256];
    private double[] px = new double[256];
    private double[] py = new double[256];
    private double[] pz = new double[256];
    private boolean[] nclosed = new boolean[256];

    // Binary heap of node indices keyed on f at push time.
    private int heapSize;
    private int[] heap = new int[256];
    private double[] heapKey = new double[256];

    // Open addressing: packed block -> node index.
    private long[] mapKeys = new long[1024];
    private int[] mapVals = new int[1024];
    private int mapSize;
    private static final long EMPTY = Long.MIN_VALUE;

    private void reset() {
        count = 0;
        heapSize = 0;
        Arrays.fill(mapKeys, EMPTY);
        mapSize = 0;
        expanded = 0;
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private int mapGet(long key) {
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

    private void mapPut(long key, int val) {
        if ((mapSize + 1) * 2 > mapKeys.length) {
            long[] oldK = mapKeys;
            int[] oldV = mapVals;
            mapKeys = new long[oldK.length * 2];
            mapVals = new int[oldK.length * 2];
            Arrays.fill(mapKeys, EMPTY);
            mapSize = 0;
            for (int i = 0; i < oldK.length; i++) {
                if (oldK[i] != EMPTY) {
                    mapPut(oldK[i], oldV[i]);
                }
            }
        }
        int mask = mapKeys.length - 1;
        int i = (int) mix(key) & mask;
        while (mapKeys[i] != EMPTY && mapKeys[i] != key) {
            i = (i + 1) & mask;
        }
        if (mapKeys[i] == EMPTY) {
            mapSize++;
        }
        mapKeys[i] = key;
        mapVals[i] = val;
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return k;
    }

    private int newNode(int x, int y, int z, double sx, double sy, double sz, double g, double f, int parent,
                        float yaw, float pitch) {
        if (count == nx.length) {
            int n = count * 2;
            nx = Arrays.copyOf(nx, n);
            ny = Arrays.copyOf(ny, n);
            nz = Arrays.copyOf(nz, n);
            ng = Arrays.copyOf(ng, n);
            nf = Arrays.copyOf(nf, n);
            nparent = Arrays.copyOf(nparent, n);
            nyaw = Arrays.copyOf(nyaw, n);
            npitch = Arrays.copyOf(npitch, n);
            px = Arrays.copyOf(px, n);
            py = Arrays.copyOf(py, n);
            pz = Arrays.copyOf(pz, n);
            nclosed = Arrays.copyOf(nclosed, n);
        }
        int i = count++;
        nx[i] = x;
        ny[i] = y;
        nz[i] = z;
        px[i] = sx;
        py[i] = sy;
        pz[i] = sz;
        ng[i] = g;
        nf[i] = f;
        nparent[i] = parent;
        nyaw[i] = yaw;
        npitch[i] = pitch;
        nclosed[i] = false;
        return i;
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

    private static double blockDist(int ax, int ay, int az, int bx, int by, int bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * One leg: a straight shot if there is one, else weighted A* over etherwarp landings.
     *
     * @param start where he stands now ({@code start.bx/by/bz} is the block containing his feet)
     * @return the hops from {@code start} (included, first) to a landing that satisfies the goal, each hop's
     *         yaw/pitch being the aim that reached it; null when no path was found in time
     */
    public List<Hop> searchLeg(Hop start, Leg leg) {
        reset();
        if (leg.isGoal.test(start.bx, start.by, start.bz)) {
            List<Hop> only = new ArrayList<>(1);
            only.add(start);
            return only;
        }
        List<Hop> direct = directHop(start, leg);
        if (direct != null) {
            return direct;
        }
        Fan fan = leg.fan;
        double range = fan.range;
        double gx = leg.goalX;
        double gy = leg.goalY;
        double gz = leg.goalZ;
        int s = newNode(start.bx, start.by, start.bz, start.x, start.y, start.z, 0.0,
                blockDist(start.bx, start.by, start.bz, leg.goalX, leg.goalY, leg.goalZ) / range * leg.hWeight,
                -1, 0f, 0f);
        mapPut(pack(start.bx, start.by, start.bz), s);
        push(s, nf[s]);
        // AIM AT THE GOAL FROM EVERY NODE, not only from the start. The fan is 6 degrees of yaw by 7 of pitch, so
        // a single block 14 blocks away (about 4 degrees across) can fall between every ray of every node the
        // search reaches, and a leg whose goal IS one block - a click on a chest, a raised step - then expands
        // the whole floor until the deadline. killer560's log (2026-10-04): "No single-room path ... within
        // 57.0 blocks a hop" for a Tic Tac Toe chest 14 blocks from him, ten times in a row, 670 ms each. The
        // aim is the same verified one directHop uses (the real float yaw/pitch is cast and must land on it).
        boolean goalAimable = etherwarpable(leg.goalX, leg.goalY, leg.goalZ)
                && leg.isGoal.test(leg.goalX, leg.goalY, leg.goalZ);
        while (heapSize > 0) {
            int cur = pop();
            if (nclosed[cur]) {
                continue;   // a stale heap entry for a node that was improved and re-pushed
            }
            nclosed[cur] = true;
            if ((++expanded & 7) == 0 && System.nanoTime() > leg.deadlineNanos) {
                return null;
            }
            double eyeX = px[cur];
            double eyeY = py[cur] + SNEAK_EYE;
            double eyeZ = pz[cur];
            if (goalAimable && cur != s
                    && aim(eyeX, eyeY, eyeZ, leg.goalX, leg.goalY, leg.goalZ, range)) {
                int node = newNode(leg.goalX, leg.goalY, leg.goalZ, leg.goalX + 0.5, leg.goalY + leg.standOffset,
                        leg.goalZ + 0.5, ng[cur] + 1.0, ng[cur] + 1.0, cur, aimYaw, aimPitch);
                return reconstruct(node);
            }
            double vx = gx + 0.5 - px[cur];
            double vy = gy - py[cur];
            double vz = gz + 0.5 - pz[cur];
            double vd = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double inv = vd > 0 ? 1.0 / vd : 0.0;
            double dirX = vx * inv;
            double dirY = vy * inv;
            double dirZ = vz * inv;
            int par = nparent[cur];
            double pDirX = 0;
            double pDirY = 0;
            double pDirZ = 0;
            if (par >= 0) {
                double qx = nx[par] - nx[cur];
                double qy = ny[par] - ny[cur];
                double qz = nz[par] - nz[cur];
                double qd = Math.sqrt(qx * qx + qy * qy + qz * qz);
                if (qd > 0) {
                    pDirX = qx / qd;
                    pDirY = qy / qd;
                    pDirZ = qz / qd;
                }
            }
            double invRange = 1.0 / range;
            double gNew = ng[cur] + 1.0;
            for (int i = 0; i < fan.dx.length; i++) {
                double dx = fan.dx[i];
                double dy = fan.dy[i];
                double dz = fan.dz[i];
                double gDot = (dx * dirX + dy * dirY + dz * dirZ) * invRange;
                if (gDot <= 0.5) {
                    if (gDot > 0.0 && (i & 1) != 0) {
                        continue;
                    } else if (gDot <= 0.0 && (i & 3) != 0) {
                        continue;
                    }
                }
                if (par >= 0 && (dx * pDirX + dy * pDirY + dz * pDirZ) * invRange > 0.65) {
                    continue;
                }
                if (cast(eyeX, eyeY, eyeZ, eyeX + dx, eyeY + dy, eyeZ + dz) != LANDS) {
                    continue;
                }
                int hx = hitX;
                int hy = hitY;
                int hz = hitZ;
                boolean isGoalBlock = hx == leg.goalX && hy == leg.goalY && hz == leg.goalZ;
                if (!isGoalBlock && (hitFlags & BLACKLIST) != 0) {
                    continue;
                }
                long key = pack(hx, hy, hz);
                int existing = mapGet(key);
                if (existing >= 0 && ng[existing] <= gNew) {
                    continue;
                }
                if (leg.landingOk != null && !isGoalBlock && !leg.landingOk.test(hx, hy, hz)) {
                    continue;
                }
                double h = blockDist(hx, hy, hz, leg.goalX, leg.goalY, leg.goalZ) * invRange * leg.hWeight;
                int node;
                if (existing >= 0) {
                    node = existing;
                    ng[node] = gNew;
                    nf[node] = gNew + h;
                    nparent[node] = cur;
                    nyaw[node] = fan.yaw[i];
                    npitch[node] = fan.pitch[i];
                    nclosed[node] = false;
                } else {
                    node = newNode(hx, hy, hz, hx + 0.5, hy + leg.standOffset, hz + 0.5, gNew, gNew + h, cur,
                            fan.yaw[i], fan.pitch[i]);
                    mapPut(key, node);
                }
                // A landing that satisfies the goal finishes the leg on the spot. Weighted A* is not
                // optimal anyway, and waiting for it to come out of the heap only buys more expansions.
                if (leg.isGoal.test(hx, hy, hz)) {
                    return reconstruct(node);
                }
                push(node, nf[node]);
            }
        }
        return null;
    }

    private List<Hop> reconstruct(int node) {
        List<Hop> out = new ArrayList<>();
        for (int at = node; at >= 0; at = nparent[at]) {
            out.add(new Hop(px[at], py[at], pz[at], nx[at], ny[at], nz[at], nyaw[at], npitch[at]));
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * One warp from {@code start} straight into the goal: the goal block, then the standable blocks within the
     * leg's radius, nearest the goal first, each tried with {@link #aim}.
     */
    private List<Hop> directHop(Hop start, Leg leg) {
        double ex = start.x;
        double ey = start.y + SNEAK_EYE;
        double ez = start.z;
        int r = (int) Math.ceil(Math.sqrt(leg.directRadiusSq));
        int n = 0;
        long[] cand = new long[(2 * r + 1) * (2 * r + 1) * (2 * r + 1)];
        int[] candD = new int[cand.length];
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d = dx * dx + dy * dy + dz * dz;
                    if (d > leg.directRadiusSq) {
                        continue;
                    }
                    int x = leg.goalX + dx;
                    int y = leg.goalY + dy;
                    int z = leg.goalZ + dz;
                    if (!etherwarpable(x, y, z)) {
                        continue;
                    }
                    if (d != 0 && (((grid.flags(x, y, z) & BLACKLIST) != 0)
                            || (leg.landingOk != null && !leg.landingOk.test(x, y, z)))) {
                        continue;
                    }
                    cand[n] = pack(x, y, z);
                    candD[n] = d;
                    n++;
                }
            }
        }
        // Nearest first; n is at most a few hundred, so an insertion sort on the index order is plenty.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(candD[a], candD[b]));
        int tried = 0;
        for (int k = 0; k < n && tried < leg.maxDirectCandidates; k++) {
            long p = cand[order[k]];
            int x = (int) (p >> 38);
            int y = (int) (p << 52 >> 52);
            int z = (int) (p << 26 >> 38);
            tried++;
            if (!aim(ex, ey, ez, x, y, z, leg.fan.range)) {
                continue;
            }
            List<Hop> out = new ArrayList<>(2);
            out.add(start);
            out.add(new Hop(x + 0.5, y + leg.standOffset, z + 0.5, x, y, z, aimYaw, aimPitch));
            return out;
        }
        return null;
    }

    /**
     * QUOI's {@code smoothPath}: from each stop, jump to the furthest later stop one warp can reach, aimed with
     * {@link #aim}. Where no later stop is aimable the search's own ray to the next one is kept - it reached that
     * block by construction.
     */
    public List<Hop> smooth(List<Hop> path, double range, boolean withLast) {
        if (path.size() < 2) {
            return path;
        }
        List<Hop> out = new ArrayList<>();
        int i = 0;
        while (i < path.size() - 1) {
            int next = i + 1;
            Hop cur = path.get(i);
            float yaw = path.get(next).yaw;
            float pitch = path.get(next).pitch;
            double ex = cur.x;
            double ey = cur.y + SNEAK_EYE;
            double ez = cur.z;
            for (int j = path.size() - 1; j >= i + 1; j--) {
                Hop t = path.get(j);
                if (aim(ex, ey, ez, t.bx, t.by, t.bz, range)) {
                    next = j;
                    yaw = aimYaw;
                    pitch = aimPitch;
                    break;
                }
            }
            out.add(new Hop(cur.x, cur.y, cur.z, cur.bx, cur.by, cur.bz, yaw, pitch));
            i = next;
        }
        if (withLast) {
            out.add(path.get(path.size() - 1));
        }
        return out;
    }
}
