import com.killer560.hub.livemap.autoclear.EtherSearch;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Times {@link EtherSearch} on a floor built from the mod's own room captures, without a game.
 *
 * <p>Build and run from the repo root (see tools/bench/run.sh):
 * <pre>
 *   javac -d build/bench src/main/java/com/killer560/hub/livemap/autoclear/EtherSearch.java tools/bench/EtherSearchBench.java
 *   java -cp build/bench EtherSearchBench src/main/resources/assets/killer560smod/rooms
 * </pre>
 *
 * <p>The floor is a 6x6 grid of the shipped 1x1 captures at the real 32-block pitch, with a 3x4 doorway carved
 * through every seam at y 69 the way {@code SimDoors.carveDoorway} does. Block flags come from the palette
 * NAMES, so they approximate {@code TeleportUtils}' instanceof rules rather than reproduce them; the cost of a
 * search depends on how far rays travel and how many landings there are, which the real room geometry gives.
 *
 * <p>Each trial is what the Interactive Map does for one click: a room path of 1 to 6 legs (room to room through
 * the shared doorway, then into the goal room), each leg a {@code searchLeg} bounded to its two rooms, and the
 * whole path smoothed. Timed end to end, after a warm-up so the numbers are the JIT-compiled steady state the
 * planner thread runs at in game.
 */
public final class EtherSearchBench {

    static final int ROOMS = 6;
    static final int PITCH = 32;
    static final int MIN_Y = 0;
    static final int MAX_Y = 160;
    static final int SIZE = ROOMS * PITCH + 2;

    static byte[] grid = new byte[SIZE * SIZE * (MAX_Y - MIN_Y)];

    static final int SOLID = 1 << EtherSearch.TOP_SHIFT;
    static final int AIRF = EtherSearch.PASSABLE | EtherSearch.AIR;

    static int idx(int x, int y, int z) {
        return ((y - MIN_Y) * SIZE + z) * SIZE + x;
    }

    static int flagsAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < MIN_Y || y >= MAX_Y) {
            return AIRF;
        }
        return grid[idx(x, y, z)] & 0xFF;
    }

    static final Pattern NUM = Pattern.compile("\"(sizeX|sizeZ|minY|maxY|margin)\":\\s*(-?\\d+)");
    static final Pattern BLOCKS = Pattern.compile("\"blocksZ\":\\s*\"([^\"]+)\"");
    static final Pattern PALETTE = Pattern.compile("\"palette\":\\s*\\[(.*?)\\]", Pattern.DOTALL);
    static final Pattern STR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    static int flagsForName(String full) {
        String n = full.replace("\\u003d", "=");
        String base = n.contains("[") ? n.substring(0, n.indexOf('[')) : n;
        base = base.replace("minecraft:", "");
        if (base.equals("air") || base.equals("cave_air") || base.equals("void_air")) {
            return AIRF;
        }
        boolean feet = base.contains("skull") || base.endsWith("_head") || base.equals("flower_pot")
                || base.startsWith("potted_") || base.equals("ladder");
        boolean passable = feet || base.contains("torch") || base.contains("tripwire") || base.contains("rail")
                || base.equals("fire") || base.equals("vine") || base.equals("water") || base.equals("lava")
                || base.contains("sapling") || base.equals("short_grass") || base.equals("tall_grass")
                || base.equals("fern") || base.equals("large_fern") || base.equals("dead_bush")
                || base.equals("brown_mushroom") || base.equals("red_mushroom") || base.equals("redstone_wire")
                || base.equals("comparator") || base.equals("repeater") || base.equals("lever")
                || base.equals("snow") || base.endsWith("_button") || base.endsWith("lantern")
                || base.equals("cobweb") || base.equals("nether_portal") || base.endsWith("candle")
                || base.equals("sugar_cane") || base.contains("seagrass") || base.equals("kelp")
                || base.equals("dandelion") || base.equals("poppy") || base.endsWith("_tulip")
                || base.equals("cornflower") || base.equals("oxeye_daisy") || base.equals("allium")
                || base.equals("azure_bluet") || base.equals("blue_orchid") || base.equals("lily_of_the_valley")
                || base.equals("wither_rose") || base.equals("sunflower") || base.equals("lilac")
                || base.equals("rose_bush") || base.equals("peony") || base.equals("wheat")
                || base.equals("carrots") || base.equals("potatoes") || base.equals("beetroots");
        int f = 0;
        if (passable) {
            f |= EtherSearch.PASSABLE;
        }
        if (feet) {
            f |= EtherSearch.BLOCKS_FEET;
        }
        boolean wallOrFence = (base.endsWith("_wall") || base.endsWith("_fence") || base.endsWith("fence_gate"));
        boolean black = wallOrFence || base.endsWith("_carpet") || base.equals("hopper") || base.contains("cauldron")
                || base.endsWith("_banner") || (base.endsWith("_slab") && n.contains("type=bottom"));
        if (black) {
            f |= EtherSearch.BLACKLIST;
        }
        if (!passable) {
            f |= (wallOrFence ? 2 : 1) << EtherSearch.TOP_SHIFT;
        }
        return f;
    }

    static boolean paste(Path file, int ox, int oz) throws Exception {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        int sx = 0, sz = 0, minY = 0, maxY = 0, margin = 1;
        Matcher m = NUM.matcher(json);
        while (m.find()) {
            int v = Integer.parseInt(m.group(2));
            switch (m.group(1)) {
                case "sizeX" -> sx = v;
                case "sizeZ" -> sz = v;
                case "minY" -> minY = v;
                case "maxY" -> maxY = v;
                case "margin" -> margin = v;
                default -> { }
            }
        }
        if (sx != 33 || sz != 33) {
            return false;
        }
        Matcher pm = PALETTE.matcher(json);
        if (!pm.find()) {
            return false;
        }
        List<Integer> pal = new ArrayList<>();
        Matcher sm = STR.matcher(pm.group(1));
        while (sm.find()) {
            pal.add(flagsForName(sm.group(1)));
        }
        Matcher bm = BLOCKS.matcher(json);
        if (!bm.find()) {
            return false;
        }
        String bs = String.valueOf((char) 92);   // Gson escapes some base64 characters as unicode escapes
        String b64 = bm.group(1).replace(bs + "u003d", "=").replace(bs + "u002b", "+").replace(bs + "/", "/");
        byte[] gz = Base64.getDecoder().decode(b64);
        byte[] raw = new GZIPInputStream(new ByteArrayInputStream(gz)).readAllBytes();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int count = raw.length / 2;
        for (int i = 0; i < count; i++) {
            int p = in.readShort();
            int y = minY + i / (sx * sz);
            int rem = i % (sx * sz);
            int z = rem / sx;
            int x = rem % sx;
            int wx = ox + x - margin;
            int wz = oz + z - margin;
            if (y < MIN_Y || y >= MAX_Y || wx < 0 || wz < 0 || wx >= SIZE || wz >= SIZE) {
                continue;
            }
            int f = p >= 0 && p < pal.size() ? pal.get(p) : AIRF;
            grid[idx(wx, y, wz)] = (byte) f;
        }
        return true;
    }

    /** SimDoors.carveDoorway, as air: 3 wide, 4 high, 7 deep through the seam, with a floor laid under it. */
    static void carve(int cx, int cz, boolean alongX) {
        int floorY = 69;
        for (int d = -3; d <= 3; d++) {
            for (int w = -1; w <= 1; w++) {
                int x = alongX ? cx + d : cx + w;
                int z = alongX ? cz + w : cz + d;
                for (int y = floorY; y < floorY + 4; y++) {
                    grid[idx(x, y, z)] = (byte) AIRF;
                }
                if ((flagsAt(x, floorY - 1, z) & EtherSearch.PASSABLE) != 0) {
                    grid[idx(x, floorY - 1, z)] = (byte) SOLID;
                }
            }
        }
    }

    static int roomOf(int x, int z) {
        int gx = Math.max(0, Math.min(ROOMS - 1, Math.round((x - 16) / (float) PITCH)));
        int gz = Math.max(0, Math.min(ROOMS - 1, Math.round((z - 16) / (float) PITCH)));
        return gz * ROOMS + gx;
    }

    /**
     * The same shape as the game's LevelEtherGrid: a fresh per-search open-addressed table of 4096-byte section
     * arrays in front of a shared ConcurrentHashMap, so the per-voxel cost includes the section switching the
     * game pays. A section "fill" here copies from the flat array, which is cheaper than reading a real
     * LevelChunkSection - so a -Dcold=true run is a lower bound on a cold search in game.
     */
    static final class SectionGrid implements EtherSearch.Grid {
        static final java.util.concurrent.ConcurrentHashMap<Long, byte[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
        private long[] keys = new long[256];
        private byte[][] vals = new byte[256][];
        private int size;
        private long lastKey = Long.MIN_VALUE;
        private byte[] last;
        // A direct window of section refs around where the search starts - 33 x 33 columns by 32 sections, which
        // covers any dungeon - so the common case is an array index rather than a hash probe. Outside it, the table.
        private static final int WIN_XZ = 33;
        private static final int WIN_Y = 32;
        private byte[][] window;
        private int winX;
        private int winY;
        private int winZ;

        SectionGrid() {
            Arrays.fill(keys, Long.MIN_VALUE);
        }

        static long key(int sx, int sy, int sz) {
            return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFFL);
        }

        @Override
        public int flags(int x, int y, int z) {
            int sx = x >> 4;
            int sy = y >> 4;
            int sz = z >> 4;
            long k = key(sx, sy, sz);
            byte[] arr;
            if (k == lastKey) {
                arr = last;
            } else {
                if (window == null) {
                    window = new byte[WIN_XZ * WIN_XZ * WIN_Y][];
                    winX = sx - WIN_XZ / 2;
                    winY = sy - WIN_Y / 2;
                    winZ = sz - WIN_XZ / 2;
                }
                int wx = sx - winX;
                int wy = sy - winY;
                int wz = sz - winZ;
                int wi = (wx | wy | wz) >= 0 && wx < WIN_XZ && wz < WIN_XZ && wy < WIN_Y
                        ? (wy * WIN_XZ + wz) * WIN_XZ + wx : -1;
                arr = wi >= 0 ? window[wi] : null;
                if (arr == null) {
                    arr = get(k);
                    if (arr == null) {
                        arr = fillSection(k, sx, sy, sz);
                        put(k, arr);
                    }
                    if (wi >= 0) {
                        window[wi] = arr;
                    }
                }
                lastKey = k;
                last = arr;
            }
            return arr[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] & 0xFF;
        }

        private static byte[] fillSection(long k, int sx, int sy, int sz) {
            byte[] arr = CACHE.get(k);
            if (arr == null) {
                arr = new byte[4096];
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            arr[(ly << 8) | (lz << 4) | lx] = (byte) flagsAt((sx << 4) + lx, (sy << 4) + ly,
                                    (sz << 4) + lz);
                        }
                    }
                }
                CACHE.put(k, arr);
            }
            return arr;
        }

        private byte[] get(long key) {
            int mask = keys.length - 1;
            int i = (int) (key ^ (key >>> 29) ^ (key >>> 43)) & mask;
            while (keys[i] != Long.MIN_VALUE) {
                if (keys[i] == key) {
                    return vals[i];
                }
                i = (i + 1) & mask;
            }
            return null;
        }

        private void put(long key, byte[] val) {
            if ((size + 1) * 2 > keys.length) {
                long[] oldK = keys;
                byte[][] oldV = vals;
                keys = new long[oldK.length * 2];
                vals = new byte[oldK.length * 2][];
                Arrays.fill(keys, Long.MIN_VALUE);
                size = 0;
                for (int i = 0; i < oldK.length; i++) {
                    if (oldK[i] != Long.MIN_VALUE) {
                        put(oldK[i], oldV[i]);
                    }
                }
            }
            int mask = keys.length - 1;
            int i = (int) (key ^ (key >>> 29) ^ (key >>> 43)) & mask;
            while (keys[i] != Long.MIN_VALUE && keys[i] != key) {
                i = (i + 1) & mask;
            }
            if (keys[i] == Long.MIN_VALUE) {
                size++;
            }
            keys[i] = key;
            vals[i] = val;
        }
    }

    /** Every standable block reachable on foot from (x, y, z), within rooms a and b. */
    static java.util.Set<Long> walk(EtherSearch probe, int x0, int y0, int z0, int a, int b) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        for (int y = y0 - 1; y <= y0 + 1; y++) {
            if (probe.etherwarpable(x0, y, z0)) {
                seen.add(EtherSearch.pack(x0, y, z0));
                queue.add(new int[]{x0, y, z0});
                break;
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c[0] + d[0];
                int z = c[2] + d[1];
                int rr = roomOf(x, z);
                if (rr != a && rr != b) {
                    continue;
                }
                for (int y = c[1] + 1; y >= c[1] - 1; y--) {
                    if (probe.etherwarpable(x, y, z)) {
                        if (seen.add(EtherSearch.pack(x, y, z))) {
                            queue.add(new int[]{x, y, z});
                        }
                        break;
                    }
                }
            }
        }
        return seen;
    }

    static void report(String what, long[] t, int n) {
        if (n == 0) {
            System.out.println(what + ": none");
            return;
        }
        long[] s = t.clone();
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1e6;
        System.out.printf("%s (%d): mean %.3f ms, median %.3f, p90 %.3f, p99 %.3f, max %.3f%n", what, n, mean,
                s[n / 2] / 1e6, s[n * 9 / 10] / 1e6, s[Math.min(n - 1, n * 99 / 100)] / 1e6, s[n - 1] / 1e6);
    }

    public static void main(String[] args) throws Exception {
        Arrays.fill(grid, (byte) AIRF);
        Path dir = Path.of(args.length > 0 ? args[0] : "src/main/resources/assets/killer560smod/rooms");
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(files::add);
        }
        Random rng = new Random(560);
        java.util.Collections.shuffle(files, rng);
        int placed = 0;
        for (Path f : files) {
            if (placed >= ROOMS * ROOMS) {
                break;
            }
            int gx = placed % ROOMS;
            int gz = placed / ROOMS;
            // Tile origin, plus one so the outer wall's margin column is still on the grid.
            if (paste(f, 1 + gx * PITCH, 1 + gz * PITCH)) {
                placed++;
            }
        }
        for (int gz = 0; gz < ROOMS; gz++) {
            for (int gx = 0; gx < ROOMS; gx++) {
                int cx = 1 + gx * PITCH + 15;
                int cz = 1 + gz * PITCH + 15;
                if (gx + 1 < ROOMS) {
                    carve(cx + 16, cz, true);
                }
                if (gz + 1 < ROOMS) {
                    carve(cx, cz + 16, false);
                }
            }
        }
        System.out.println("rooms placed: " + placed);

        EtherSearch.Grid g = EtherSearchBench::flagsAt;
        EtherSearch.Fan fan = EtherSearch.fan(57.0, args.length > 1 ? Float.parseFloat(args[1]) : 6f, args.length > 2 ? Float.parseFloat(args[2]) : 7f);
        System.out.println("fan rays: " + fan.size());

        // Which seams really join two rooms, and where a player can stand. A capture's own doorways are wherever
        // they were in the instance he walked, so a seam carved at the tile midpoint does not always open onto
        // anything; a real floor only ever puts a door where both rooms have one. So: walk (one block of step
        // up or down) from each carved seam floor; a seam is a door only if that walk reaches well into BOTH
        // rooms, and the standable spots of a room are what its doors' walks reached inside it - never a sealed
        // pocket in the stonework, which the captures are full of.
        EtherSearch probe = new EtherSearch(g);
        List<java.util.Set<Long>> spotSets = new ArrayList<>();
        List<List<Integer>> neighbours = new ArrayList<>();
        for (int r = 0; r < ROOMS * ROOMS; r++) {
            spotSets.add(new java.util.HashSet<>());
            neighbours.add(new ArrayList<>());
        }
        int doors = 0;
        for (int r = 0; r < ROOMS * ROOMS; r++) {
            int gx0 = r % ROOMS;
            int gz0 = r / ROOMS;
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                int gx1 = gx0 + d[0];
                int gz1 = gz0 + d[1];
                if (gx1 >= ROOMS || gz1 >= ROOMS) {
                    continue;
                }
                int other = gz1 * ROOMS + gx1;
                int sx = 1 + gx0 * PITCH + 15 + d[0] * 16;
                int sz = 1 + gz0 * PITCH + 15 + d[1] * 16;
                java.util.Set<Long> reach = walk(probe, sx, 68, sz, r, other);
                int inA = 0;
                int inB = 0;
                for (long k : reach) {
                    int rr = roomOf((int) (k >> 38), (int) (k << 26 >> 38));
                    if (rr == r) {
                        inA++;
                    } else if (rr == other) {
                        inB++;
                    }
                }
                if (inA < 40 || inB < 40) {
                    continue;
                }
                doors++;
                neighbours.get(r).add(other);
                neighbours.get(other).add(r);
                for (long k : reach) {
                    spotSets.get(roomOf((int) (k >> 38), (int) (k << 26 >> 38))).add(k);
                }
            }
        }
        List<List<int[]>> spots = new ArrayList<>();
        for (java.util.Set<Long> set : spotSets) {
            List<int[]> l = new ArrayList<>();
            for (long k : set) {
                l.add(new int[]{(int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38)});
            }
            l.sort((p1, p2) -> p1[0] != p2[0] ? p1[0] - p2[0] : p1[1] != p2[1] ? p1[1] - p2[1] : p1[2] - p2[2]);
            spots.add(l);
        }
        System.out.println("doors that really join two rooms: " + doors + " of " + (2 * ROOMS * (ROOMS - 1)));

        int warm = 400;
        int trials = 2000;
        long[] times = new long[trials];
        long[] smoothNanos = new long[trials];
        int[] pathLen = new int[trials];
        int failDoorLegs = 0;
        int failFirstLeg = 0;
        int failGoalLegs = 0;
        List<Integer> failExpanded = new ArrayList<>();
        List<Integer> okExpanded = new ArrayList<>();
        long[] okTimes = new long[trials];
        long[] failTimes = new long[trials];
        int ok = 0;
        int legsTotal = 0;
        int legsDirect = 0;
        long expandedTotal = 0;
        long raysTotal = 0;
        java.util.Set<Long> touched = new java.util.HashSet<>();
        long[] sectionsPerClick = new long[trials];
        EtherSearch.Grid counting = (x, y, z) -> {
            touched.add(((long) (x >> 4) << 40) ^ ((long) (z >> 4) << 20) ^ (y >> 4));
            return flagsAt(x, y, z);
        };
        boolean countSections = Boolean.getBoolean("sections");
        boolean sectioned = Boolean.getBoolean("sectioned");
        boolean cold = Boolean.getBoolean("cold");
        EtherSearch search = new EtherSearch(countSections ? counting : g);
        for (int t = -warm; t < trials; t++) {
            // A room path: a random walk of 0..6 steps along real doors, never revisiting a room.
            List<Integer> rooms = new ArrayList<>();
            List<int[]> startSpots;
            List<int[]> goalSpots;
            do {
                rooms.clear();
                int legs = rng.nextInt(7);
                int at = rng.nextInt(ROOMS * ROOMS);
                rooms.add(at);
                for (int i = 0; i < legs; i++) {
                    List<Integer> nb = new ArrayList<>(neighbours.get(at));
                    nb.removeAll(rooms);
                    if (nb.isEmpty()) {
                        break;
                    }
                    at = nb.get(rng.nextInt(nb.size()));
                    rooms.add(at);
                }
                startSpots = spots.get(rooms.get(0));
                goalSpots = spots.get(rooms.get(rooms.size() - 1));
            } while (startSpots.isEmpty() || goalSpots.isEmpty());
            int[] s = startSpots.get(rng.nextInt(startSpots.size()));
            int[] goal = goalSpots.get(rng.nextInt(goalSpots.size()));

            if (sectioned) {
                if (cold) {
                    SectionGrid.CACHE.clear();
                }
                search = new EtherSearch(new SectionGrid());
            }
            long t0 = System.nanoTime();
            EtherSearch.Hop start = new EtherSearch.Hop(s[0] + 0.5, s[1] + 1.0, s[2] + 0.5, s[0], s[1], s[2], 0, 0);
            List<EtherSearch.Hop> path = new ArrayList<>();
            path.add(start);
            boolean failed = false;
            EtherSearch.Hop cur = start;
            for (int i = 0; i < rooms.size(); i++) {
                int room = rooms.get(i);
                int next = i + 1 < rooms.size() ? rooms.get(i + 1) : -1;
                EtherSearch.Leg leg = new EtherSearch.Leg();
                leg.fan = fan;
                leg.hWeight = 6.7;
                leg.standOffset = 1.0;
                leg.deadlineNanos = System.nanoTime() + 670_000_000L;
                if (next >= 0) {
                    int ax = 1 + (room % ROOMS) * PITCH + 15;
                    int az = 1 + (room / ROOMS) * PITCH + 15;
                    int bx2 = 1 + (next % ROOMS) * PITCH + 15;
                    int bz2 = 1 + (next / ROOMS) * PITCH + 15;
                    int dx = (ax + bx2) / 2;
                    int dz = (az + bz2) / 2;
                    leg.goalX = dx;
                    leg.goalY = 68;
                    leg.goalZ = dz;
                    leg.directRadiusSq = 9;
                    final int gyy = 68;
                    final int fdx = dx;
                    final int fdz = dz;
                    final int nr = next;
                    leg.isGoal = (x, y, z) -> {
                        int ddx = x - fdx;
                        int ddy = y - gyy;
                        int ddz = z - fdz;
                        return ddx * ddx + ddy * ddy + ddz * ddz <= 9
                                || (roomOf(x, z) == nr && Math.abs(y - gyy) <= 3);
                    };
                    final int r1 = room;
                    leg.landingOk = (x, y, z) -> {
                        int rr = roomOf(x, z);
                        return rr == r1 || rr == nr;
                    };
                } else {
                    leg.goalX = goal[0];
                    leg.goalY = goal[1];
                    leg.goalZ = goal[2];
                    leg.directRadiusSq = 0;
                    leg.isGoal = (x, y, z) -> x == goal[0] && y == goal[1] && z == goal[2];
                    final int r1 = room;
                    leg.landingOk = (x, y, z) -> roomOf(x, z) == r1;
                }
                List<EtherSearch.Hop> seg = search.searchLeg(cur, leg);
                if (seg == null) {
                    if (t >= 0) {
                        if (i == 0) {
                            failFirstLeg++;
                        }
                        if (next >= 0) {
                            failDoorLegs++;
                        } else {
                            failGoalLegs++;
                        }
                        failExpanded.add(search.expanded);
                    }
                    failed = true;
                    break;
                }
                if (t >= 0) {
                    legsTotal++;
                    if (search.expanded == 0) {
                        legsDirect++;
                    }
                    expandedTotal += search.expanded;
                    if (search.expanded > 0) {
                        okExpanded.add(search.expanded);
                    }
                }
                for (int j = 1; j < seg.size(); j++) {
                    path.add(seg.get(j));
                }
                cur = path.get(path.size() - 1);
            }
            long ts = System.nanoTime();
            List<EtherSearch.Hop> smoothed = failed ? null : search.smooth(path, 57.0, false);
            if (t >= 0) {
                smoothNanos[t] = System.nanoTime() - ts;
                pathLen[t] = path.size();
            }
            long took = System.nanoTime() - t0;
            if (t >= 0) {
                times[t] = took;
                if (!failed && smoothed != null) {
                    okTimes[ok++] = took;
                } else {
                    failTimes[t - ok] = took;
                }
                raysTotal += search.rays;
            }
            search.rays = 0;
            if (t >= 0) {
                sectionsPerClick[t] = touched.size();
            }
            touched.clear();
        }
        System.out.println("failed legs: door " + failDoorLegs + ", final " + failGoalLegs + ", of which first leg "
                + failFirstLeg);
        java.util.Collections.sort(failExpanded);
        java.util.Collections.sort(okExpanded);
        if (!failExpanded.isEmpty()) {
            System.out.println("expansions in failed legs: median " + failExpanded.get(failExpanded.size() / 2)
                    + ", max " + failExpanded.get(failExpanded.size() - 1));
        }
        if (!okExpanded.isEmpty()) {
            System.out.println("expansions in successful searched legs: median " + okExpanded.get(okExpanded.size() / 2)
                    + ", p90 " + okExpanded.get(okExpanded.size() * 9 / 10)
                    + ", p99 " + okExpanded.get(okExpanded.size() * 99 / 100)
                    + ", max " + okExpanded.get(okExpanded.size() - 1));
        }
        if (countSections) {
            long[] sc = sectionsPerClick.clone();
            Arrays.sort(sc);
            System.out.println("distinct 16^3 sections read per click: median " + sc[trials / 2] + ", p90 "
                    + sc[trials * 9 / 10] + ", max " + sc[trials - 1]);
        }
        report("smoothing alone", smoothNanos, trials);
        System.out.println("raw hops before smoothing: mean "
                + Arrays.stream(pathLen).average().orElse(0) + ", max " + Arrays.stream(pathLen).max().orElse(0));
        report("all clicks", times, trials);
        report("clicks that found a path", Arrays.copyOf(okTimes, ok), ok);
        report("clicks that failed", Arrays.copyOf(failTimes, trials - ok), trials - ok);
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(times).average().orElse(0) / 1e6;
        System.out.printf("trials %d, paths found %d (%.1f%%)%n", trials, ok, 100.0 * ok / trials);
        System.out.printf("legs %d, direct %d, A* nodes expanded per searched leg %.1f, rays per path %.0f%n",
                legsTotal, legsDirect, (double) expandedTotal / Math.max(1, legsTotal - legsDirect),
                (double) raysTotal / trials);
        System.out.printf("per click (legs + smoothing): mean %.3f ms, median %.3f, p90 %.3f, p99 %.3f, max %.3f%n",
                mean, sorted[trials / 2] / 1e6, sorted[trials * 9 / 10] / 1e6, sorted[trials * 99 / 100] / 1e6,
                sorted[trials - 1] / 1e6);
    }
}
