package com.killer560.hub.mapcycler;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.HeldMap;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Rooms before Blood (roomcycle-1118; killer560's definition, 2026-11-18: the bare minimum number of rooms he must OPEN
 * to open Blood, "Green -> room A -> Fairy -> room B -> Blood is 2 rooms"): the rooms on the door path from the Entrance
 * to Blood, NOT counting the Entrance, the Fairy or Blood; a room of several cells counts once. Given as a guaranteed
 * least and a possible most from what the map shows so far.
 *
 * <p>A floor's doors join its rooms as a tree (no loops; {@code BloodRushHelperFeature.rushPath} reads them the same
 * way), so a chain of known doors and same-room joins from the Entrance to Blood IS that path: then min == max (or the two
 * differ by one while the Fairy is not yet known and the chain crosses an unidentified room). Otherwise:
 * <ul>
 *   <li>min: the fewest rooms over any route a still unknown slot or gap might allow (a known door enters a room, a
 *       known join stays in one, an unknown gap enters one only when its two sides cannot be one room), one less while
 *       the Fairy is unknown (it may be on that route).</li>
 *   <li>max: every room or unseen slot that could still lie on the path: a room with at most one way out left is a dead
 *       end and cannot, and is dropped until none is left (while Blood is unknown, everything that could be Blood stays);
 *       lowered to {@link #proven} when that proves less (wither doors, room shapes, the door tree).</li>
 * </ul>
 * Pure: {@link #bounds} takes plain arrays, so the testkit checks it against the true path of generated floors.
 */
public final class RushBounds {

    public static final byte UNKNOWN = 0;
    public static final byte JOIN = 1;
    public static final byte DOOR = 2;
    public static final byte WALL = 3;
    /** A gap value: a door the map shows as a wither or blood door. Every one lies on the way from the Entrance to Blood
     *  (the rule Blood Rush Helper's rush path and Auto Clear's Door Only rest on). */
    public static final byte KEY = 4;
    /** Steps the proof's search may take before it gives up (no proof, the plain most stands). */
    private static final int PROOF_BUDGET = 40_000;
    /** A proof is sought only below this: Max Rooms Before Blood goes up to 10 (MapCyclerConfig.MAX_ROOMS). */
    private static final int PROOF_CAP = MapCyclerConfig.MAX_ROOMS + 1;
    /** A slot value: not seen yet. */
    public static final int UNSEEN = -1;
    /** A slot value: seen, and no room is there. */
    public static final int EMPTY = -2;
    public static final int NO_MAX = Integer.MAX_VALUE;

    private RushBounds() {
    }

    /**
     * @param slots    {@code across * down} room slots, slot {@code z * across + x}: its room id, {@link #UNSEEN} or {@link #EMPTY}
     * @param gaps     two per slot: {@code [2s]} the gap to the east neighbor, {@code [2s+1]} to the south one
     * @param names    a room's identified name, or null
     * @param complete whether all of a room's cells are known (it cannot grow into a neighbor slot)
     * @param fairy    the Fairy room's id, or -1 while not known
     * @return {@code {min, max}}, max {@link #NO_MAX} when nothing bounds it
     */
    public static int[] bounds(int across, int down, int[] slots, byte[] gaps, String[] names, boolean[] complete,
                               int entrance, int blood, int fairy) {
        return bounds(across, down, slots, gaps, names, null, complete, entrance, blood, fairy);
    }

    /** {@link #bounds} with each room's database shape ("1x3", "2x2", "L"; null when not known), which lets the most
     *  be proven ({@link #proven}). */
    public static int[] bounds(int across, int down, int[] slots, byte[] gaps, String[] names, String[] shapes,
                               boolean[] complete, int entrance, int blood, int fairy) {
        return bounds(across, down, slots, gaps, names, shapes, null, complete, entrance, blood, fairy);
    }

    /** {@link #bounds} for a real Hypixel floor, with the rules that held on all 454 of his real F7 floors
     *  (mapcycler-history-1121): the rooms in {@code offPath} (puzzles and the trap) are never on the path and count as no
     *  room at all, the Entrance has one door, never to the Fairy ({@link #entranceWalls}), and the way passes the Fairy
     *  (killer560 2026-11-21: "fairy is always on the path to blood"; puzzles always dead ends, doors always a tree). A generated test floor
     *  that breaks them passes null and gets plain {@link #bounds}. */
    public static int[] bounds(int across, int down, int[] slots, byte[] gaps, String[] names, String[] shapes,
                               boolean[] offPath, boolean[] complete, int entrance, int blood, int fairy) {
        if (offPath != null) {
            slots = slots.clone();
            for (int s = 0; s < slots.length; s++) {
                if (slots[s] >= 0 && offPath[slots[s]] && slots[s] != entrance && slots[s] != blood && slots[s] != fairy) {
                    slots[s] = EMPTY;
                }
            }
        }
        int start = -1;
        boolean bloodSeen = false;
        for (int s = 0; s < across * down; s++) {
            if (start < 0 && entrance >= 0 && slots[s] == entrance) {
                start = s;
            }
            bloodSeen |= blood >= 0 && slots[s] == blood;
        }
        if (!bloodSeen) {
            // Named but on no slot yet: it is in an unseen one, as when it is not known at all.
            blood = -1;
        }
        if (start < 0) {
            return new int[]{0, NO_MAX};
        }
        if (offPath != null) {
            gaps = entranceWalls(across, down, slots, gaps, start, fairy);
        }
        int exact = blood < 0 ? -1 : walk(across, down, slots, gaps, names, complete, start, blood, fairy, true, false);
        if (exact >= 0) {
            // Unknown Fairy: it could be one of the chain's unidentified rooms.
            boolean maybeFairy = fairy < 0
                    && walk(across, down, slots, gaps, names, complete, start, blood, fairy, true, true) != exact;
            return new int[]{maybeFairy ? exact - 1 : exact, exact};
        }
        // An unseen slot the walk pays for may be the Fairy (unknown, or known but with cells still unseen).
        int min = Math.max(0, walk(across, down, slots, gaps, names, complete, start, blood, fairy, false, false)
                - (fairy < 0 || !complete[fairy] ? 1 : 0));
        int fairyAt = -1;
        for (int s = 0; offPath != null && fairy >= 0 && complete[fairy] && s < slots.length; s++) {
            fairyAt = slots[s] == fairy ? s : fairyAt;
        }
        if (fairyAt >= 0) {
            // The Fairy is on the path: Entrance to Fairy (the walk does not pay for the Fairy) plus Fairy to Blood.
            int in = walk(across, down, slots, gaps, names, complete, start, fairy, fairy, false, false);
            int out = walk(across, down, slots, gaps, names, complete, fairyAt, blood, fairy, false, false);
            min = in >= 0 && out >= 0 ? Math.max(min, in + 1 + out) : min;
        }
        int max = upper(across, down, slots, gaps, names, entrance, blood, fairy);
        int proof = max <= min ? NO_MAX : proven(across, down, slots, gaps, names, shapes, complete, start, blood, fairy,
                fairyAt >= 0, Math.min(max, PROOF_CAP));
        return new int[]{min, proof >= min && proof < max ? proof : max};
    }

    private static boolean door(byte g) {
        return g == DOOR || g == KEY;
    }

    /** The Entrance is one cell with one door, and that door never leads to the Fairy (all 454 of his real F7 floors,
     *  mapcycler-history-1121): once its door shows, its other unknown gaps are walls, and so is an unknown gap to the
     *  Fairy. A copy when anything changes. */
    private static byte[] entranceWalls(int across, int down, int[] slots, byte[] gaps, int start, int fairy) {
        int x = start % across;
        int z = start / across;
        // East, west, south, north: the gap index and the neighbor slot.
        int[] gap = {x + 1 < across ? 2 * start : -1, x > 0 ? 2 * (start - 1) : -1,
                z + 1 < down ? 2 * start + 1 : -1, z > 0 ? 2 * (start - across) + 1 : -1};
        int[] next = {start + 1, start - 1, start + across, start - across};
        boolean hasDoor = false;
        for (int g : gap) {
            hasDoor |= g >= 0 && door(gaps[g]);
        }
        byte[] out = gaps;
        for (int d = 0; d < 4; d++) {
            if (gap[d] >= 0 && gaps[gap[d]] == UNKNOWN && (hasDoor || fairy >= 0 && slots[next[d]] == fairy)) {
                if (out == gaps) {
                    out = gaps.clone();
                }
                out[gap[d]] = WALL;
            }
        }
        return out;
    }

    /**
     * The most rooms the path can hold, proven (mapcycler-keep-1121; killer560 2026-11-21 on a map whose wither doors ran
     * Well, Wizard, Double Diamond, Altar with only two unseen cells beside Altar: "it is guaranteed to be less than 5
     * rooms"): the longest way from the Entrance to a slot Blood can be in that keeps to what the dungeon guarantees.
     * <ul>
     *   <li>The doors join the rooms as a tree, so rooms already joined by known doors form a piece the path crosses at most
     *       once, along those doors only (an unknown gap inside a piece is no door, at most a join of a room with its own
     *       unseen cell).</li>
     *   <li>The path goes through every wither and blood door ({@link #KEY}).</li>
     *   <li>A room whose database shape fits its seen cells only one way owns the unseen cells that fit gives it.</li>
     *   <li>While Blood is unknown it is in an unseen slot or an unidentified room.</li>
     * </ul>
     * Every unseen slot counts as a room of its own, so the answer can only be too high, never too low. NO_MAX when the
     * search reaches {@code cap} or its budget, or finds no way (the map breaks a rule: prove nothing).
     */
    static int proven(int across, int down, int[] slotsIn, byte[] gaps, String[] names, String[] shapes, boolean[] completeIn,
                      int start, int blood, int fairy, boolean viaFairy, int cap) {
        int n = across * down;
        int rooms = names.length;
        int[] slots = slotsIn.clone();
        boolean[] complete = completeIn.clone();
        // An unseen slot joined to a room is that room.
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int s = 0; s < n; s++) {
                for (int d = 0; d < 2; d++) {
                    int t = neighbor(s, d, across, down);
                    if (t >= 0 && gaps[2 * s + d] == JOIN && (slots[s] == UNSEEN) != (slots[t] == UNSEEN)
                            && slots[s] != EMPTY && slots[t] != EMPTY) {
                        slots[slots[s] == UNSEEN ? s : t] = Math.max(slots[s], slots[t]);
                        changed = true;
                    }
                }
            }
        }
        if (shapes != null) {
            fillShapes(across, down, slots, gaps, shapes, complete);
        }
        int[] node = new int[n];
        int count = rooms;
        for (int s = 0; s < n; s++) {
            node[s] = slots[s] >= 0 ? slots[s] : slots[s] == UNSEEN ? count++ : -1;
        }
        int[] comp = new int[count];
        for (int v = 0; v < count; v++) {
            comp[v] = v;
        }
        for (int s = 0; s < n; s++) {
            for (int d = 0; d < 2; d++) {
                int t = neighbor(s, d, across, down);
                if (t >= 0 && node[s] >= 0 && node[t] >= 0 && door(gaps[2 * s + d])) {
                    comp[find(comp, node[s])] = find(comp, node[t]);
                }
            }
        }
        Search q = new Search(count);
        for (int v = 0; v < count; v++) {
            q.comp[v] = find(comp, v);
            q.weight[v] = v == node[start] || v == fairy ? 0 : 1;
            q.target[v] = blood >= 0 ? v == blood : v != node[start] && (v >= rooms || names[v] == null && v != fairy);
        }
        for (int s = 0; s < n; s++) {
            for (int d = 0; d < 2; d++) {
                int t = neighbor(s, d, across, down);
                int a = t < 0 ? -1 : node[s];
                int b = t < 0 ? -1 : node[t];
                byte g = t < 0 ? WALL : gaps[2 * s + d];
                if (a < 0 || b < 0 || a == b || g == WALL) {
                    continue;
                }
                if (g == JOIN) {
                    // Two unseen slots of one room: passing between them is no door.
                    q.adj[a][b] = q.adj[b][a] = true;
                    continue;
                }
                boolean joinable = a >= rooms && b >= rooms || a >= rooms && !complete[b] || b >= rooms && !complete[a];
                if (door(g) || q.comp[a] != q.comp[b] || joinable) {
                    q.adj[a][b] = q.adj[b][a] = true;
                }
                if (g == KEY && !q.req[a][b]) {
                    q.req[a][b] = q.req[b][a] = true;
                    q.reqLeft++;
                }
            }
        }
        q.cap = cap;
        q.bloodKnown = blood >= 0;
        q.via = viaFairy ? fairy : -1;
        int from = node[start];
        q.visited[from] = true;
        q.entered[q.comp[from]] = true;
        q.dfs(from, -1, 0);
        return q.aborted || q.best < 0 ? NO_MAX : q.best;
    }

    /** The longest-way search of {@link #proven}: plain arrays, made only when the map changes. */
    private static final class Search {
        final int count;
        final boolean[][] adj;
        final boolean[][] req;
        final int[] comp;
        final int[] weight;
        final boolean[] target;
        final boolean[] visited;
        final boolean[] entered;
        int reqLeft;
        /** Blood known by id: it has one door, so a way ends there. */
        boolean bloodKnown;
        /** A room every way must pass first (the Fairy on a real floor), or -1. */
        int via = -1;
        int cap;
        int steps;
        int best = -1;
        boolean aborted;

        Search(int count) {
            this.count = count;
            adj = new boolean[count][count];
            req = new boolean[count][count];
            comp = new int[count];
            weight = new int[count];
            target = new boolean[count];
            visited = new boolean[count];
            entered = new boolean[count];
        }

        void dfs(int v, int pred, int rooms) {
            if (aborted || ++steps > PROOF_BUDGET) {
                aborted = true;
                return;
            }
            if (pred >= 0 && target[v] && reqLeft == 0 && (via < 0 || visited[via])) {
                best = Math.max(best, rooms - weight[v]);
                if (best >= cap) {
                    aborted = true;
                    return;
                }
            }
            if (pred >= 0 && target[v] && bloodKnown) {
                return;
            }
            for (int w = 0; w < count; w++) {
                if (!adj[v][w] || visited[w] || comp[w] != comp[v] && entered[comp[w]] || deadEnds(v, pred, w)) {
                    continue;
                }
                boolean cross = comp[w] != comp[v];
                boolean key = req[v][w];
                visited[w] = true;
                if (cross) {
                    entered[comp[w]] = true;
                }
                if (key) {
                    reqLeft--;
                }
                dfs(w, v, rooms + weight[w]);
                if (key) {
                    reqLeft++;
                }
                if (cross) {
                    entered[comp[w]] = false;
                }
                visited[w] = false;
                if (aborted) {
                    return;
                }
            }
        }

        /** Leaving {@code v} for {@code w} would leave a wither or blood door of {@code v} or {@code w} unwalkable. */
        private boolean deadEnds(int v, int pred, int w) {
            for (int y = 0; y < count; y++) {
                if (req[v][y] && y != pred && y != w || req[w][y] && y != v && visited[y]) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Gives a room the unseen slots that every placement of its database shape over its seen slots covers. */
    private static void fillShapes(int across, int down, int[] slots, byte[] gaps, String[] shapes, boolean[] complete) {
        int n = across * down;
        int[] hits = new int[n];
        int[] cells = new int[4];
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int r = 0; r < shapes.length; r++) {
                int need = shapeTiles(shapes[r]);
                // A room on no slot yet has nothing to place its shape around.
                if (complete[r] || need > 4 || !contains(slots, slots.length, r)) {
                    continue;
                }
                Arrays.fill(hits, 0);
                int fits = 0;
                boolean l = "L".equals(shapes[r]);
                int w = l ? 2 : shapes[r].charAt(0) - '0';
                int h = l ? 2 : shapes[r].charAt(2) - '0';
                for (int turn = 0; turn < (w == h ? 1 : 2); turn++) {
                    int bw = turn == 0 ? w : h;
                    int bh = turn == 0 ? h : w;
                    for (int z0 = 0; z0 + bh <= down; z0++) {
                        for (int x0 = 0; x0 + bw <= across; x0++) {
                            for (int skip = l ? 0 : -1; skip < (l ? 4 : 0); skip++) {
                                int k = 0;
                                for (int i = 0; i < bw * bh; i++) {
                                    if (i != skip) {
                                        cells[k++] = (z0 + i / bw) * across + x0 + i % bw;
                                    }
                                }
                                if (fits(r, cells, k, slots, gaps, across, down)) {
                                    fits++;
                                    for (int i = 0; i < k; i++) {
                                        hits[cells[i]]++;
                                    }
                                }
                            }
                        }
                    }
                }
                int have = 0;
                for (int s = 0; s < n; s++) {
                    if (fits > 0 && hits[s] == fits && slots[s] == UNSEEN) {
                        slots[s] = r;
                        changed = true;
                    }
                    have += slots[s] == r ? 1 : 0;
                }
                complete[r] = have >= need;
            }
        }
    }

    /** Whether room {@code r} can be exactly {@code cells}: all its seen slots and joins inside, nothing else's slot,
     *  no wall or door between two of them. */
    private static boolean fits(int r, int[] cells, int k, int[] slots, byte[] gaps, int across, int down) {
        int inside = 0;
        for (int i = 0; i < k; i++) {
            int s = cells[i];
            if (slots[s] != r && slots[s] != UNSEEN) {
                return false;
            }
            inside += slots[s] == r ? 1 : 0;
        }
        int mine = 0;
        for (int s = 0; s < slots.length; s++) {
            if (slots[s] != r) {
                continue;
            }
            mine++;
            for (int d = 0; d < 4; d++) {
                int t = d < 2 ? neighbor(s, d, across, down) : neighborBack(s, d - 2, across);
                if (t < 0) {
                    continue;
                }
                byte g = d < 2 ? gaps[2 * s + d] : gaps[2 * t + d - 2];
                boolean tIn = contains(cells, k, t);
                if (g == JOIN && !tIn || g != UNKNOWN && g != JOIN && tIn) {
                    return false;
                }
            }
        }
        return inside == mine;
    }

    private static boolean contains(int[] cells, int k, int s) {
        for (int i = 0; i < k; i++) {
            if (cells[i] == s) {
                return true;
            }
        }
        return false;
    }

    /** The east ({@code d} 0) or south (1) neighbor slot, or -1 at the edge. */
    private static int neighbor(int s, int d, int across, int down) {
        return d == 0 ? (s % across + 1 < across ? s + 1 : -1) : (s / across + 1 < down ? s + across : -1);
    }

    /** The west ({@code d} 0) or north (1) neighbor slot, or -1 at the edge. */
    private static int neighborBack(int s, int d, int across) {
        return d == 0 ? (s % across > 0 ? s - 1 : -1) : (s >= across ? s - across : -1);
    }

    private static int find(int[] parent, int v) {
        while (parent[v] != v) {
            parent[v] = parent[parent[v]];
            v = parent[v];
        }
        return v;
    }

    /** 0-1 BFS over the slots: rooms entered between the Entrance and Blood (or, while Blood is unknown, any slot it may
     *  be in), the Fairy free. {@code knownOnly}: only known doors and joins (the exact path); {@code freeUnnamed}: an
     *  unidentified room is free too. -1 when nothing reaches. */
    private static int walk(int across, int down, int[] slots, byte[] gaps, String[] names, boolean[] complete,
                            int start, int blood, int fairy, boolean knownOnly, boolean freeUnnamed) {
        int n = across * down;
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        dist[start] = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(start);
        int best = Integer.MAX_VALUE;
        while (!q.isEmpty()) {
            int s = q.pollFirst();
            int r = slots[s];
            if (blood >= 0 ? r == blood : r == UNSEEN || (r >= 0 && names[r] == null)) {
                best = Math.min(best, dist[s]);
            }
            int x = s % across;
            int z = s / across;
            for (int d = 0; d < 4; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= across || nz >= down) {
                    continue;
                }
                int t = nz * across + nx;
                int rt = slots[t];
                byte g = gaps[d == 0 ? 2 * s : d == 1 ? 2 * t : d == 2 ? 2 * s + 1 : 2 * t + 1];
                if (rt == EMPTY || g == WALL || knownOnly && (g == UNKNOWN || r < 0 || rt < 0)) {
                    continue;
                }
                boolean enters = door(g) || g == UNKNOWN && apart(r, rt, names, complete);
                boolean free = rt >= 0 && (rt == fairy || freeUnnamed && names[rt] == null && rt != blood);
                int w = enters && !free ? 1 : 0;
                if (dist[s] + w < dist[t]) {
                    dist[t] = dist[s] + w;
                    if (w == 0) {
                        q.addFirst(t);
                    } else {
                        q.addLast(t);
                    }
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best - 1;
    }

    /** Whether two slots across an unknown gap are certainly different rooms. */
    private static boolean apart(int a, int b, String[] names, boolean[] complete) {
        if (a >= 0 && b >= 0) {
            return a != b && (complete[a] || complete[b] || names[a] != null && names[b] != null && !names[a].equals(names[b]));
        }
        return a >= 0 && complete[a] || b >= 0 && complete[b];
    }

    /** Rooms (and unseen slots, one each) that could lie on the path, dead ends dropped until none is left. */
    private static int upper(int across, int down, int[] slots, byte[] gaps, String[] names, int entrance, int blood, int fairy) {
        int n = across * down;
        int rooms = names.length;
        // Node per known room, then one per unseen slot.
        int[] node = new int[n];
        int count = rooms;
        for (int s = 0; s < n; s++) {
            node[s] = slots[s] >= 0 ? slots[s] : slots[s] == UNSEEN ? count++ : -1;
        }
        boolean[] keep = new boolean[count];
        boolean[] present = new boolean[count];
        for (int s = 0; s < n; s++) {
            if (node[s] >= 0) {
                present[node[s]] = true;
                // Never dropped: the ends, and while Blood is unknown everything that could be it.
                keep[node[s]] = node[s] == entrance || node[s] == blood
                        || blood < 0 && (node[s] >= rooms || names[node[s]] == null);
            }
        }
        int[][] edges = new int[2 * n][];
        int e = 0;
        for (int s = 0; s < n; s++) {
            int x = s % across;
            int z = s / across;
            for (int d = 0; d < 2; d++) {
                int t = d == 0 ? (x + 1 < across ? s + 1 : -1) : (z + 1 < down ? s + across : -1);
                if (t < 0 || node[s] < 0 || node[t] < 0 || node[s] == node[t]) {
                    continue;
                }
                byte g = gaps[2 * s + d];
                if (door(g) || g == UNKNOWN) {
                    edges[e++] = new int[]{Math.min(node[s], node[t]), Math.max(node[s], node[t])};
                }
            }
        }
        // One edge per pair of nodes: a room touching another along two slots is still one way out.
        Arrays.sort(edges, 0, e, (p, q) -> p[0] != q[0] ? Integer.compare(p[0], q[0]) : Integer.compare(p[1], q[1]));
        boolean changed = true;
        while (changed) {
            changed = false;
            int[] degree = new int[count];
            for (int i = 0; i < e; i++) {
                int[] p = edges[i];
                if (present[p[0]] && present[p[1]] && (i == 0 || edges[i - 1][0] != p[0] || edges[i - 1][1] != p[1])) {
                    degree[p[0]]++;
                    degree[p[1]]++;
                }
            }
            for (int v = 0; v < count; v++) {
                if (present[v] && !keep[v] && degree[v] <= 1) {
                    present[v] = false;
                    changed = true;
                }
            }
        }
        int left = 0;
        boolean maybeBlood = false;
        for (int v = 0; v < count; v++) {
            if (present[v] && v != entrance && v != blood && v != fairy) {
                left++;
                maybeBlood |= blood < 0 && keep[v];
            }
        }
        return maybeBlood ? left - 1 : left;
    }

    /** The bounds of the floor as the live map knows it now ({@link DungeonLayout#current()}), for floor {@code floor},
     *  and the wither and blood doors it shows ({@link RushOdds}): {@code {least, most, key doors}}. */
    public static int[] of(DungeonLayout layout, String floor) {
        int[] size = com.killer560.hub.livemap.MapPainter.floorRooms(floor);
        int across = size[0];
        int down = size[1];
        int rooms = layout.roomCount();
        // One spare id: an unopened Blood room found behind its door.
        String[] names = new String[rooms + 1];
        boolean[] complete = new boolean[rooms + 1];
        String[] shapes = new String[rooms + 1];
        boolean[] offPath = new boolean[rooms + 1];
        int entrance = -1;
        int blood = -1;
        int fairy = -1;
        for (int r = 0; r < rooms; r++) {
            RoomEntry e = layout.entry(r);
            int[] tiles = layout.tiles(r);
            // The map's color names the type of an opened room before the room itself is identified.
            String type = e != null ? e.type : tiles != null && tiles.length > 0 ? HeldMap.roomType(tiles[0]) : null;
            if (e != null) {
                names[r] = e.name;
                shapes[r] = e.shape;
                complete[r] = tiles != null && tiles.length >= shapeTiles(e.shape);
            }
            if ("ENTRANCE".equalsIgnoreCase(type)) {
                entrance = r;
            } else if ("BLOOD".equalsIgnoreCase(type)) {
                blood = r;
            } else if ("FAIRY".equalsIgnoreCase(type)) {
                fairy = r;
            }
            offPath[r] = "PUZZLE".equalsIgnoreCase(type) || "TRAP".equalsIgnoreCase(type);
            if (names[r] == null && type != null && (r == entrance || r == blood || r == fairy)) {
                names[r] = type;
            }
        }
        int[] slots = new int[across * down];
        byte[] gaps = new byte[2 * across * down];
        int grid = DungeonLayout.GRID;
        for (int z = 0; z < down; z++) {
            for (int x = 0; x < across; x++) {
                int s = z * across + x;
                int idx = 2 * z * grid + 2 * x;
                int r = layout.roomOfCell(idx);
                slots[s] = r >= 0 ? r : LiveMapFeature.seenEmpty(idx) ? EMPTY : UNSEEN;
                gaps[2 * s] = x + 1 < across ? gap(layout, idx + 1) : WALL;
                gaps[2 * s + 1] = z + 1 < down ? gap(layout, idx + grid) : WALL;
            }
        }
        if (blood < 0) {
            blood = behindBloodDoor(layout, across, down, slots, names, rooms);
        }
        int[] b = bounds(across, down, slots, gaps, names, shapes, offPath, complete, entrance, blood, fairy);
        int keyDoors = 0;
        for (byte g : gaps) {
            keyDoors += g == KEY ? 1 : 0;
        }
        return new int[]{b[0], b[1], keyDoors};
    }

    /** Blood is the unopened side of the blood door once the map shows that door (SpecialRoomGuess.behindBloodDoor's
     *  rule): its room id (an unseen slot gets the spare id {@code spare}), or -1. */
    private static int behindBloodDoor(DungeonLayout layout, int across, int down, int[] slots, String[] names, int spare) {
        int grid = DungeonLayout.GRID;
        for (int s = 0; s < across * down; s++) {
            int x = s % across;
            int z = s / across;
            for (int d = 0; d < 2; d++) {
                int t = d == 0 ? (x + 1 < across ? s + 1 : -1) : (z + 1 < down ? s + across : -1);
                int idx = 2 * z * grid + 2 * x + (d == 0 ? 1 : grid);
                if (t < 0 || layout.doorType(idx) != DungeonLayout.DOOR_BLOOD) {
                    continue;
                }
                // The room it is reached from is opened (the map colors it); Blood behind it is not.
                boolean sKnown = slots[s] >= 0 && HeldMap.roomType(2 * z * grid + 2 * x) != null;
                boolean tKnown = slots[t] >= 0 && HeldMap.roomType(2 * (t / across) * grid + 2 * (t % across)) != null;
                if (sKnown == tKnown) {
                    return -1;
                }
                int side = sKnown ? t : s;
                if (slots[side] < 0) {
                    slots[side] = spare;
                }
                names[slots[side]] = "BLOOD";
                return slots[side];
            }
        }
        return -1;
    }

    private static byte gap(DungeonLayout layout, int idx) {
        if (layout.roomOfCell(idx) >= 0) {
            return JOIN;
        }
        int type = layout.doorType(idx);
        if (type != DungeonLayout.DOOR_NONE) {
            return type == DungeonLayout.DOOR_WITHER || type == DungeonLayout.DOOR_BLOOD ? KEY : DOOR;
        }
        return LiveMapFeature.seenEmpty(idx) ? WALL : UNKNOWN;
    }

    /** Cells of a room shape as the room database writes it ("1x3", "2x2", "L"). */
    static int shapeTiles(String shape) {
        if (shape == null) {
            return Integer.MAX_VALUE;
        }
        return switch (shape) {
            case "L" -> 3;
            case "2x2" -> 4;
            default -> shape.length() == 3 && shape.charAt(1) == 'x' ? (shape.charAt(0) - '0') * (shape.charAt(2) - '0') : Integer.MAX_VALUE;
        };
    }
}
