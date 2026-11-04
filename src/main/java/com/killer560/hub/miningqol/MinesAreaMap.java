package com.killer560.hub.miningqol;

import com.killer560.hub.pathfinding.GraphRepository;
import com.killer560.hub.pathfinding.IslandGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Dwarven Mines area a BLOCK is in (killer560, 2026-11-03: "make it so its not based off of my location but the
 * blocks location. otherwise i can stand in one zone but the blocks are in another").
 *
 * <p>Source: SkyHanni-REPO's {@code constants/island_graphs/DWARVEN_MINES.json}, the navigation graph the mod already
 * downloads for pathfinding ({@link GraphRepository}). Its nodes tagged {@code area} carry an area name ("Upper Mines",
 * "Rampart's Quarry", ... or "no_area") and sit in pairs on either side of every way from one area into the next. SkyHanni
 * names the player's area the same way ({@code IslandAreaBackend.updateNodes}: the area node with the fastest path from the
 * player's nearest node). Here every node gets the area of the area node it reaches fastest (one multi-source Dijkstra over
 * the graph, edges taken both ways), and a block takes the area of the nearest node within {@link #RADIUS} blocks. A block
 * with no node that near, or whose node is "no_area", has no area here ({@link #areaAt} returns null) and the caller falls
 * back to the player's own area. No hand-made boxes: when SkyHanni's file changes, the map follows on its next download.
 *
 * <p>Thread-safe: the chunk mesher asks from its worker threads. Answers are cached per chunk section (one byte per block,
 * filled as blocks are asked about), held by the map they were read from, so a new graph starts a new cache.
 */
public final class MinesAreaMap {

    /** How far a block may be from the nearest graph node and still take its area. */
    public static final int RADIUS = 24;
    /** The wider search for a block with no named-area node within {@link #RADIUS}. */
    public static final int WIDE_RADIUS = 64;
    private static final int CELL = 16;
    private static final int MAX_SECTIONS = 4096;

    private static final String ISLAND = "DWARVEN_MINES";

    /** One built map. */
    private static final class Map3 {
        final IslandGraph graph;
        final String[] areas;          // index -> name
        final byte[] nodeArea;         // node index -> area index + 1, 0 = none
        final Map<Long, int[]> cells;  // 16-block cell -> node indices
        final double[][] xyz;
        final ConcurrentHashMap<Long, byte[]> sections = new ConcurrentHashMap<>();

        Map3(IslandGraph graph, String[] areas, byte[] nodeArea, Map<Long, int[]> cells) {
            this.graph = graph;
            this.areas = areas;
            this.nodeArea = nodeArea;
            this.cells = cells;
            this.xyz = new double[graph.nodes.length][];
            for (int i = 0; i < graph.nodes.length; i++) {
                IslandGraph.Node n = graph.nodes[i];
                xyz[i] = new double[]{n.x, n.y, n.z};
            }
        }
    }

    private static volatile Map3 map = null;
    private static volatile IslandGraph testGraph = null;
    private static volatile int version = 0;

    private MinesAreaMap() {
    }

    /** Client tick: (re)builds the map when the Dwarven Mines graph arrives or changes. */
    static void tick() {
        IslandGraph g = testGraph;
        if (g == null) {
            if (!MiningState.dwarvenMines()) {
                return;
            }
            g = GraphRepository.get(ISLAND);
        }
        Map3 m = map;
        if (g == null) {
            if (m != null && testGraph == null && !GraphRepository.isLoaded(ISLAND)) {
                set(null);
            }
            return;
        }
        if (m == null || m.graph != g) {
            set(build(g));
        }
    }

    private static void set(Map3 m) {
        map = m;
        version++;
    }

    /** Bumped whenever the map changes (the callers re-decide then). */
    public static int version() {
        return version;
    }

    public static boolean loaded() {
        return map != null;
    }

    static Map3 build(IslandGraph g) {
        int n = g.nodes.length;
        List<String> names = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        byte[] seedArea = new byte[n];
        boolean[] seed = new boolean[n];
        for (int i = 0; i < n; i++) {
            IslandGraph.Node node = g.nodes[i];
            if (!node.hasTag("area") || node.cleanName == null || node.cleanName.isBlank()) {
                continue;
            }
            seed[i] = true;
            String name = node.cleanName;
            if (name.equalsIgnoreCase("no_area")) {
                seedArea[i] = 0;
                continue;
            }
            Integer k = index.get(name);
            if (k == null && names.size() < 120) {
                k = names.size();
                names.add(name);
                index.put(name, k);
            }
            seedArea[i] = k == null ? 0 : (byte) (k + 1);
        }
        // Edges both ways (a few of SkyHanni's are one-way drops; an area does not stop at one).
        List<List<double[]>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            int[] out = g.nodes[i].neighbours();
            double[] w = g.nodes[i].weights();
            for (int j = 0; j < out.length; j++) {
                adj.get(i).add(new double[]{out[j], w[j]});
                adj.get(out[j]).add(new double[]{i, w[j]});
            }
        }
        double[] dist = new double[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        byte[] area = new byte[n];
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int i = 0; i < n; i++) {
            if (seed[i]) {
                dist[i] = 0;
                area[i] = seedArea[i];
                pq.add(new double[]{0, i, seedArea[i]});
            }
        }
        boolean[] done = new boolean[n];
        while (!pq.isEmpty()) {
            double[] e = pq.poll();
            int i = (int) e[1];
            if (done[i]) {
                continue;
            }
            done[i] = true;
            area[i] = (byte) e[2];
            for (double[] nb : adj.get(i)) {
                int j = (int) nb[0];
                double d = e[0] + nb[1];
                if (!done[j] && d < dist[j]) {
                    dist[j] = d;
                    pq.add(new double[]{d, j, e[2]});
                }
            }
        }
        Map<Long, List<Integer>> cellLists = new HashMap<>();
        for (int i = 0; i < n; i++) {
            IslandGraph.Node node = g.nodes[i];
            long key = cellKey(Math.floorDiv((int) Math.floor(node.x), CELL), Math.floorDiv((int) Math.floor(node.y), CELL),
                    Math.floorDiv((int) Math.floor(node.z), CELL));
            cellLists.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        Map<Long, int[]> cells = new HashMap<>(cellLists.size() * 2);
        for (Map.Entry<Long, List<Integer>> e : cellLists.entrySet()) {
            cells.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        return new Map3(g, names.toArray(new String[0]), area, cells);
    }

    private static long cellKey(int cx, int cy, int cz) {
        return ((long) (cx & 0x1FFFFF) << 42) | ((long) (cy & 0x1FFFFF) << 21) | (cz & 0x1FFFFF);
    }

    /**
     * The commission area the block at x, y, z is in ("Upper Mines"), or null when unknown (no map yet, no graph node
     * within {@link #RADIUS} blocks, or a "no_area" node). Any thread.
     */
    public static String areaAt(int x, int y, int z) {
        Map3 m = map;
        if (m == null) {
            return null;
        }
        long sec = cellKey(x >> 4, y >> 4, z >> 4);
        byte[] cache = m.sections.get(sec);
        if (cache == null) {
            if (m.sections.size() >= MAX_SECTIONS) {
                m.sections.clear();
            }
            cache = m.sections.computeIfAbsent(sec, k -> new byte[4096]);
        }
        int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        int v = cache[idx];
        if (v == 0) {
            int a = lookup(m, x, y, z, RADIUS, false);
            if (a == 0) {
                // No node near, or only a "no_area" one: the nearest node that names an area within WIDE_RADIUS decides, so a
                // block deep in a cavern still belongs to the area around it and never to the area the player stands in.
                a = lookup(m, x, y, z, WIDE_RADIUS, true);
            }
            v = a + 1; // 1 = none, 2.. = area index + 2
            cache[idx] = (byte) v;
        }
        return v <= 1 ? null : m.areas[v - 2];
    }

    /** Area index + 1 of the nearest node within RADIUS, or 0. */
    private static int lookup(Map3 m, int bx, int by, int bz, int radius, boolean skipNoArea) {
        double px = bx + 0.5, py = by + 0.5, pz = bz + 0.5;
        int cx = Math.floorDiv(bx, CELL), cy = Math.floorDiv(by, CELL), cz = Math.floorDiv(bz, CELL);
        int r = (radius + CELL - 1) / CELL;
        double best = (double) radius * radius;
        int bestNode = -1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int[] list = m.cells.get(cellKey(cx + dx, cy + dy, cz + dz));
                    if (list == null) {
                        continue;
                    }
                    for (int i : list) {
                        if (skipNoArea && m.nodeArea[i] == 0) {
                            continue;
                        }
                        double[] q = m.xyz[i];
                        // Node positions are feet blocks; compare against the block's center.
                        double ex = q[0] + 0.5 - px, ey = q[1] + 0.5 - py, ez = q[2] + 0.5 - pz;
                        double d = ex * ex + ey * ey + ez * ez;
                        if (d <= best) {
                            best = d;
                            bestNode = i;
                        }
                    }
                }
            }
        }
        return bestNode < 0 ? 0 : m.nodeArea[bestNode];
    }

    /** The graph the map was built from, or null (Commission Route paths over it). */
    public static IslandGraph graph() {
        Map3 m = map;
        return m == null ? null : m.graph;
    }

    /** Indices of the graph nodes whose area is {@code area} (case and apostrophe form ignored); empty when none. */
    public static int[] nodesInArea(String area) {
        Map3 m = map;
        if (m == null || area == null) {
            return new int[0];
        }
        int want = -1;
        for (int i = 0; i < m.areas.length; i++) {
            if (MiningState.areaIs(m.areas[i], area)) {
                want = i + 1;
                break;
            }
        }
        if (want < 0) {
            return new int[0];
        }
        int count = 0;
        for (byte b : m.nodeArea) {
            if (b == want) {
                count++;
            }
        }
        int[] out = new int[count];
        int k = 0;
        for (int i = 0; i < m.nodeArea.length; i++) {
            if (m.nodeArea[i] == want) {
                out[k++] = i;
            }
        }
        return out;
    }


    /** Uses this SkyHanni-format graph JSON instead of the downloaded one (null: back to the real graph). */
    public static void useGraphJsonForTest(String json) {
        testGraph = json == null ? null : IslandGraph.parse(ISLAND, json);
        set(testGraph == null ? null : build(testGraph));
    }

    /** Back to the downloaded graph (the next tick rebuilds from it). */
    public static void clearTestGraphForTest() {
        testGraph = null;
        set(null);
    }

    /** Every area name the map knows. */
    public static List<String> areaNames() {
        Map3 m = map;
        return m == null ? List.of() : List.of(m.areas);
    }

}
