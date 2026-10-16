package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interactive Map trips planned before anybody asks for them (killer560, 2026-10-15: "Every time a new map is loaded ...
 * have it figure ways to get to other rooms that it can see automatically - it just needs to get something ready within
 * the first 5s so my first warp goes out near instantly. Then as it loads in all rooms it should calculate from the very
 * last node of every auto routes to the start node of every other room's auto routes and every other room ... and save
 * them so it always hits the most optimal route afterwards").
 *
 * <p><b>What is planned.</b> Phase 1: from where he stands (re-planned whenever he settles somewhere new) to every room
 * the map knows - each of its tiles as a map press goes there ({@link AutoClearUtils#pathToRoom}: its recorded spot, or
 * the whole tile) and its Auto Routes start node - nearest rooms first, the rooms on the way to Blood and the routed
 * rooms ahead of the rest. Phase 2: from the end of every room's route ({@code RouteExecutor.predictedEnd}) to every
 * other room's start node, or to its main tile when it has no route. Every trip is the floor graph's own plan, the same
 * search a press makes ({@link EtherwarpPathfinder#planForCache}), so a cached trip costs exactly what planning it fresh
 * would have; a goal the graph cannot plan is left to the press.
 *
 * <p><b>Where it runs.</b> On the Interactive Map's planner thread - the one thread the floor graph may be used from, and
 * where every map search already runs - in slices of at most {@link #SLICE_NANOS}, queued behind anything else there and
 * giving way between searches to a trip somebody asked for ({@link ClearExecutor#tripSearchWaiting}). Only once a search
 * takes milliseconds ({@link EtherwarpPathfinder#searchReady}: the quick graph warm and no change waiting); a trip planned
 * on the quick graph is planned again once the full graph is warm. The client tick only picks the next batch and
 * resolves a few goals; nothing at all runs outside a dungeon, in the boss, or with the setting
 * ({@link LiveMapConfig#isPrecomputeTrips}) or the Interactive Map off. At most {@link #MAX_ENTRIES} trips are kept.
 *
 * <p><b>A cached trip is only a starting point.</b> {@link ClearExecutor} takes one when he stands within
 * {@link #MATCH_XZ} of where it starts and checks it on the spot like any plan (every hop cast in the world as it is now,
 * the first warp aimed again from exactly where he stands, the last landing where it was planned); one that does not
 * hold is dropped and the trip planned fresh at once. A block change drops every trip whose rays pass within a block of
 * it ({@link #blocksChanged}, from the planner thread's change check) and marks the rest to be planned again soon, since
 * an opened door may have made a shorter way; a trip that goes wrong in flight is dropped too.
 */
public final class TripCache {

    private static final Logger LOGGER = ModLog.get("killer560smod-interactivemap");

    /** He must stand within this of a trip's start (horizontally, and vertically) for it to be his trip. */
    static final double MATCH_XZ = 1.5;
    static final double MATCH_Y = 1.5;
    /** Trips kept at most (a floor's phase 2 is a few hundred; each holds about ten hops). */
    static final int MAX_ENTRIES = 3000;
    /** One planner-thread task of precomputing at most this long, then it queues again behind anything else. */
    static final long SLICE_NANOS = 6_000_000L;
    /** Searches per task at most (the slice usually ends it first). */
    private static final int BATCH = 24;
    /** No refresh until the block changes have been quiet this long (a door is several packets). */
    private static final long CHANGE_QUIET_MS = 150L;
    /** He has stood still (on the ground, under 0.1 moved) this many ticks: his spot is a phase 1 origin. */
    private static final int SETTLE_TICKS = 4;
    /** Rooms whose goals are worked out again at most this many a tick, and how often a room's goals are refreshed. */
    private static final int ROOMS_PER_TICK = 1;
    private static final int NEW_ROOMS_PER_TICK = 6;
    private static final int ROOM_REFRESH_TICKS = 100;
    /** How often the list of trips wanted is rebuilt, in ticks (and at once when an origin or the rooms change). */
    private static final int WANTS_TICKS = 4;

    private TripCache() {
    }

    // ------------------------------------------------------------------------------------------- the trips

    /** A goal as a press asks for it: a tile ({@code tileIdx >= 0}, {@code to} its fallback) or a block. */
    record Goal(BlockPos to, int tileIdx, boolean exact) {
        String key() {
            return keyOf(to, tileIdx);
        }
    }

    static String keyOf(BlockPos to, int tileIdx) {
        return tileIdx >= 0 ? "T" + tileIdx : "B" + to.getX() + "," + to.getY() + "," + to.getZ();
    }

    /** One planned trip. {@code corridor}: per hop the eye and the landing block's centre, six doubles a hop. */
    public static final class Entry {
        final Vec3 from;
        final Goal goal;
        final List<EtherwarpPathfinder.Node> path;
        final BlockPos lastLanding;
        final double[] corridor;
        final boolean onFull;
        final boolean onGoal;
        final long epoch;
        final long madeMs;
        final int phase;
        final double range;

        Entry(Vec3 from, Goal goal, List<EtherwarpPathfinder.Node> path, BlockPos lastLanding, double[] corridor,
              boolean onFull, boolean onGoal, long epoch, int phase, double range) {
            this.from = from;
            this.goal = goal;
            this.path = path;
            this.lastLanding = lastLanding;
            this.corridor = corridor;
            this.onFull = onFull;
            this.onGoal = onGoal;
            this.epoch = epoch;
            this.madeMs = System.currentTimeMillis();
            this.phase = phase;
            this.range = range;
        }

        public List<EtherwarpPathfinder.Node> path() {
            return path;
        }

        public Vec3 from() {
            return from;
        }

        public int warps() {
            return path.size();
        }

        public BlockPos lastLanding() {
            return lastLanding;
        }

        public long ageMs() {
            return System.currentTimeMillis() - madeMs;
        }

        public int phase() {
            return phase;
        }

        /** Planned before the latest block change, or on the quick graph while the full one is warm now. */
        public boolean stale() {
            return epoch < TripCache.epoch.get() || !onFull && EtherwarpPathfinder.fullWarm();
        }
    }

    /** goal key -> from key -> trip. Written by the planner thread, read by the client thread. */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>> TRIPS = new ConcurrentHashMap<>();
    /** want key -> the change epoch its search found nothing at (not tried again until something changes). */
    private static final ConcurrentHashMap<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final AtomicLong epoch = new AtomicLong();
    private static volatile long lastChangeMs;
    private static final java.util.concurrent.atomic.AtomicInteger SIZE = new java.util.concurrent.atomic.AtomicInteger();

    static String fromKey(Vec3 v) {
        return (int) Math.floor(v.x) + "," + (int) Math.floor(v.y) + "," + (int) Math.floor(v.z);
    }

    // ------------------------------------------------------------------------------------------- lookups

    /**
     * The trip for this goal from where he stands, or null: the one starting nearest him within {@link #MATCH_XZ}; for an
     * exact goal only one whose last hop lands ON the block. Client thread (any thread is safe).
     */
    public static Entry find(Vec3 at, BlockPos to, int tileIdx, boolean exact) {
        if (!enabledNow()) {
            return null;
        }
        Map<String, Entry> byFrom = TRIPS.get(keyOf(to, tileIdx));
        Entry best = null;
        double bestD = Double.MAX_VALUE;
        if (byFrom != null) {
            double range = lastRange;   // the client tick's reading of the held item (this may be the planner thread)
            for (Entry e : byFrom.values()) {
                if (exact && !e.onGoal || e.range != range) {
                    continue;
                }
                double dx = e.from.x - at.x;
                double dz = e.from.z - at.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= MATCH_XZ && Math.abs(e.from.y - at.y) <= MATCH_Y && d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
        }
        return best;
    }

    /** The warps of the cached trip from {@code at} (RoomTour's room order), or -1 when none is cached. */
    public static int warps(Vec3 at, BlockPos to, int tileIdx) {
        Entry e = find(at, to, tileIdx, false);
        if (e != null) {
            lookupsHit++;
            return e.warps();
        }
        return -1;
    }

    /** {@link #find}'s path for a trip planned ahead (RoomTour), or null. */
    public static List<EtherwarpPathfinder.Node> pathFor(Vec3 at, BlockPos to, int tileIdx, boolean exact) {
        Entry e = find(at, to, tileIdx, exact);
        return e == null ? null : e.path;
    }

    // ------------------------------------------------------------------------------------------- what the map did with them

    private static int hits;
    private static int misses;
    private static int rejected;
    private static volatile int dropped;
    private static volatile int searches;
    private static volatile int stored;
    private static volatile int failures;
    private static int lookupsHit;
    private static String lastRejectWhy = "";

    /** ClearExecutor ran this trip. */
    static void hit(Entry e) {
        hits++;
    }

    /** ClearExecutor looked and found none for a trip it was asked for. */
    static void miss() {
        misses++;
    }

    /** The trip did not hold (re-validation at use, or it went wrong in flight): gone, planned fresh. */
    static void reject(Entry e, String why) {
        rejected++;
        lastRejectWhy = why;
        Map<String, Entry> byFrom = TRIPS.get(e.goal.key());
        if (byFrom != null && byFrom.remove(fromKey(e.from), e)) {
            SIZE.decrementAndGet();
        }
        LOGGER.info("[Path] precomputed trip to {} from {} dropped: {}", e.goal.key(), fmt(e.from), why);
    }

    /**
     * For the testkit: {hits, misses, rejected, dropped by a block change, searches, stored, failed searches, trips held,
     * RoomTour costs answered from the cache}.
     */
    public static int[] stats() {
        return new int[]{hits, misses, rejected, dropped, searches, stored, failures, SIZE.get(), lookupsHit};
    }

    public static String lastRejectWhy() {
        return lastRejectWhy;
    }

    // ------------------------------------------------------------------------------------------- block changes

    /** Test hook (testkit): corridor drops off, so only the check at use stands between a stale trip and a warp. */
    public static volatile boolean testNoCorridorDrop;

    /**
     * A box of blocks whose etherwarp flags changed (LevelEtherGrid's change check, planner thread; a chunk column the
     * client switched to: client thread). Every trip with a ray within a block of it is dropped; every other one is
     * planned again soon (the change may have opened a shorter way); every "nothing found" is forgotten.
     */
    static void blocksChanged(int x0, int y0, int z0, int x1, int y1, int z1) {
        epoch.incrementAndGet();
        lastChangeMs = System.currentTimeMillis();
        FAILED.clear();
        if (testNoCorridorDrop) {
            return;
        }
        double bx0 = x0 - 1.0;
        double by0 = y0 - 1.0;
        double bz0 = z0 - 1.0;
        double bx1 = x1 + 2.0;
        double by1 = y1 + 3.0;
        double bz1 = z1 + 2.0;
        int n = 0;
        for (Map<String, Entry> byFrom : TRIPS.values()) {
            for (Map.Entry<String, Entry> me : byFrom.entrySet()) {
                if (crosses(me.getValue().corridor, bx0, by0, bz0, bx1, by1, bz1) && byFrom.remove(me.getKey(), me.getValue())) {
                    n++;
                }
            }
        }
        if (n > 0) {
            SIZE.addAndGet(-n);
            dropped += n;
        }
    }

    /** Whether any hop's ray (eye to landing block centre) passes through the box. */
    static boolean crosses(double[] c, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int i = 0; i + 5 < c.length; i += 6) {
            if (segmentHitsBox(c[i], c[i + 1], c[i + 2], c[i + 3], c[i + 4], c[i + 5], x0, y0, z0, x1, y1, z1)) {
                return true;
            }
        }
        return false;
    }

    /** Slab test of the segment a-b against the box. */
    static boolean segmentHitsBox(double ax, double ay, double az, double bx, double by, double bz,
                                  double x0, double y0, double z0, double x1, double y1, double z1) {
        double t0 = 0.0;
        double t1 = 1.0;
        double[] a = {ax, ay, az};
        double[] d = {bx - ax, by - ay, bz - az};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-12) {
                if (a[k] < lo[k] || a[k] > hi[k]) {
                    return false;
                }
                continue;
            }
            double ta = (lo[k] - a[k]) / d[k];
            double tb = (hi[k] - a[k]) / d[k];
            if (ta > tb) {
                double t = ta;
                ta = tb;
                tb = t;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------- the schedule

    /** One trip wanted: from a spot to a goal, its phase (1 from where he stands, 2 from a route's end), its order. */
    private record Want(Vec3 from, Goal goal, int phase, double priority, String fromKey, String goalKey, String key) {
        static Want of(Vec3 from, Goal goal, int phase, double priority) {
            String fk = TripCache.fromKey(from);
            String gk = goal.key();
            return new Want(from, goal, phase, priority, fk, gk, fk + "|" + gk);
        }
    }

    /** What a room offers as goals, worked out on the client thread and kept a while. */
    private record RoomGoals(String name, int mainTile, int[] tiles, BlockPos override, BlockPos startNode, Vec3 routeEnd,
                             long madeTick, boolean blood) {
        boolean sameGoals(RoomGoals o) {
            return name.equals(o.name) && mainTile == o.mainTile && java.util.Arrays.equals(tiles, o.tiles)
                    && java.util.Objects.equals(override, o.override) && java.util.Objects.equals(startNode, o.startNode)
                    && java.util.Objects.equals(routeEnd, o.routeEnd) && blood == o.blood;
        }
    }

    private static final Map<Integer, RoomGoals> ROOMS = new HashMap<>();
    private static List<Want> wants = List.of();
    private static int wantsBuiltTick = -1000;
    private static boolean wantsDirty = true;
    private static volatile boolean jobInFlight;
    private static volatile int generation;
    private static Object lastLevel;
    private static volatile double lastRange = -1;
    private static int tick;
    private static int lastHere = Integer.MIN_VALUE;
    /** {@link #idleKey} when a scan last found nothing to do. */
    private static long idleSince = Long.MIN_VALUE;
    private static int wantsVersion;

    /** Everything the list of trips still to plan depends on, folded into one number. */
    private static long idleKey() {
        long k = wantsVersion;
        k = k * 31 + epoch.get();
        k = k * 31 + SIZE.get();
        k = k * 31 + generation;
        k = k * 31 + (EtherwarpPathfinder.fullWarm() ? 1 : 0);
        return k;
    }

    /** Where he stood when phase 1 was last planned from, and how long he has stood still. */
    private static Vec3 origin;
    private static Vec3 stillAt;
    private static int stillTicks;

    /** Phase 1 readiness (System.currentTimeMillis): the floor known, phase 1 from the first origin done. */
    private static long floorKnownMs;
    private static long phase1ReadyMs;
    private static long phase1FullMs;
    private static int phase1Wanted;
    private static int phase1Failed;
    private static Vec3 phase1Origin;

    /** For the testkit: {floor known ms, phase 1 ready ms, phase 1 on the full graph ms, trips wanted, failed}. */
    public static long[] phase1() {
        return new long[]{floorKnownMs, phase1ReadyMs, phase1FullMs, phase1Wanted, phase1Failed};
    }

    /** For the testkit: trips wanted now that are not planned yet, and trips wanted in all. */
    public static int[] pending() {
        int missing = 0;
        for (Want w : wants) {
            if (needs(w) != Need.NONE) {
                missing++;
            }
        }
        return new int[]{missing, wants.size()};
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.end("TripCache.tick", TripCache::tick));
    }

    private static boolean enabledNow() {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        return cfg.isInteractiveMapEnabled() && cfg.isPrecomputeTrips();
    }

    /** Everything forgotten: a new world, the setting off, out of the dungeon. */
    static void clear() {
        generation++;
        TRIPS.clear();
        FAILED.clear();
        ROOMS.clear();
        SIZE.set(0);
        wants = List.of();
        wantsDirty = true;
        wantsVersion++;
        idleSince = Long.MIN_VALUE;
        lastHere = Integer.MIN_VALUE;
        origin = null;
        stillAt = null;
        stillTicks = 0;
        floorKnownMs = 0;
        phase1ReadyMs = 0;
        phase1FullMs = 0;
        phase1Wanted = 0;
        phase1Failed = 0;
        phase1Origin = null;
    }

    private static boolean holding() {
        return SIZE.get() > 0 || !ROOMS.isEmpty() || floorKnownMs != 0;
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (holding()) {
                clear();
            }
        }
        LocalPlayer player = client.player;
        if (player == null || !DungeonState.isInDungeon() || !enabledNow() || LiveMapFeature.isInBoss()) {
            if (holding()) {
                clear();
            }
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout.roomCount() == 0) {
            return;
        }
        tick++;
        if (floorKnownMs == 0) {
            floorKnownMs = System.currentTimeMillis();
        }
        double range = ClearExecutor.hopRange();
        if (range != lastRange) {
            if (lastRange >= 0) {
                generation++;
                TRIPS.clear();
                FAILED.clear();
                SIZE.set(0);
            }
            lastRange = range;
        }
        trackOrigin(player);
        refreshRooms(layout, player);
        int here = layout.currentRoom();
        if (here != lastHere) {
            lastHere = here;
            wantsDirty = true;
        }
        if (wantsDirty && tick - wantsBuiltTick >= WANTS_TICKS) {
            buildWants(layout, player);
        }
        notePhase1();
        if (jobInFlight || ClearExecutor.tripSearchWaiting() || !EtherwarpPathfinder.searchReady(range)) {
            return;
        }
        // Nothing to do since the last look, and nothing it depends on has moved: no scan this tick.
        long idle = idleKey();
        if (idle == idleSince) {
            return;
        }
        List<Want> batch = nextBatch();
        if (batch.isEmpty()) {
            if (System.currentTimeMillis() - lastChangeMs >= CHANGE_QUIET_MS) {
                idleSince = idle;
            }
            return;
        }
        idleSince = Long.MIN_VALUE;
        jobInFlight = true;
        Job job = new Job(batch, layout, ClearExecutor.pathConfig(), range, generation);
        try {
            ClearExecutor.onPlannerBackground(job);
        } catch (RuntimeException e) {
            jobInFlight = false;
        }
    }

    /** A new phase 1 origin once he has stood still somewhere new for {@link #SETTLE_TICKS}. */
    private static void trackOrigin(LocalPlayer player) {
        Vec3 at = player.position();
        if (stillAt != null && at.distanceTo(stillAt) < 0.1 && player.onGround()) {
            stillTicks++;
        } else {
            stillTicks = 0;
            stillAt = at;
        }
        if (stillTicks >= SETTLE_TICKS && (origin == null || horizontal(origin, at) > 1.0 || Math.abs(origin.y - at.y) > 1.0)) {
            origin = at;
            wantsDirty = true;
        }
    }

    /** At most {@link #ROOMS_PER_TICK} rooms' goals worked out again a tick: new rooms first, then the oldest. */
    private static void refreshRooms(DungeonLayout layout, LocalPlayer player) {
        int fresh = 0;
        int refreshed = 0;
        int n = layout.roomCount();
        for (int room = 0; room < n && fresh < NEW_ROOMS_PER_TICK && refreshed < ROOMS_PER_TICK; room++) {
            int[] tiles = layout.tiles(room);
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            int main = mainTile(tiles);
            RoomGoals g = ROOMS.get(main);
            String name = layout.name(room);
            boolean isNew = g == null || !g.name().equals(name) || !java.util.Arrays.equals(g.tiles(), tiles);
            if (!isNew && tick - g.madeTick() < ROOM_REFRESH_TICKS) {
                continue;
            }
            if (isNew) {
                fresh++;
            } else {
                refreshed++;
            }
            RoomGoals made = goalsOf(layout, room, name, main, tiles);
            if (g == null || !g.sameGoals(made)) {
                wantsDirty = true;
            }
            ROOMS.put(main, made);
        }
    }

    private static int mainTile(int[] tiles) {
        int main = tiles[0];
        for (int t : tiles) {
            int tx = t % DungeonLayout.GRID;
            int mx = main % DungeonLayout.GRID;
            if (tx < mx || (tx == mx && t / DungeonLayout.GRID < main / DungeonLayout.GRID)) {
                main = t;
            }
        }
        return main;
    }

    private static RoomGoals goalsOf(DungeonLayout layout, int room, String name, int main, int[] tiles) {
        BlockPos override = null;
        BlockPos start = null;
        Vec3 end = null;
        if (!"Unknown".equals(name)) {
            override = AutoClearUtils.overrideGoal(layout, room, main, true);
            if (com.killer560.hub.autoroutes.AutoRoutesConfig.getInstance().isEnabled()) {
                com.killer560.hub.autoroutes.AutoRoutesFeature.RoomRoute rr =
                        com.killer560.hub.autoroutes.AutoRoutesFeature.roomRoute(layout, room);
                if (rr != null) {
                    start = rr.goal();
                    end = rr.predictedEnd();
                }
            }
        }
        var entry = layout.entry(room);
        boolean blood = entry != null && entry.type != null && "BLOOD".equalsIgnoreCase(entry.type);
        return new RoomGoals(name, main, tiles.clone(), override, start, end, tick, blood);
    }

    /** The goals a press on this room can ask for: its recorded spot or each tile, and its start node. */
    private static void goalsFor(RoomGoals g, boolean allTiles, boolean startNode, List<Goal> out) {
        if (g.override() != null) {
            out.add(new Goal(g.override(), -1, false));
        } else if (allTiles) {
            for (int t : g.tiles()) {
                out.add(new Goal(DungeonLayout.cellCenter(t), t, false));
            }
        } else {
            out.add(new Goal(DungeonLayout.cellCenter(g.mainTile()), g.mainTile(), false));
        }
        if (startNode && g.startNode() != null) {
            out.add(new Goal(g.startNode(), -1, true));
        }
    }

    /**
     * Every trip wanted now, best first: phase 1 from his origin (rooms nearest on the map first; on the way to Blood, and
     * routed rooms, a little ahead), then phase 2 from each route's end (routes nearest him first).
     */
    private static void buildWants(DungeonLayout layout, LocalPlayer player) {
        wantsBuiltTick = tick;
        wantsDirty = false;
        int here = layout.currentRoom();
        int n = layout.roomCount();
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, 99);
        int bloodRoom = -1;
        Map<Integer, Integer> roomOfMain = new HashMap<>();
        for (int room = 0; room < n; room++) {
            int[] tiles = layout.tiles(room);
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            int main = mainTile(tiles);
            roomOfMain.put(main, room);
            RoomGoals g = ROOMS.get(main);
            if (g != null && g.blood()) {
                bloodRoom = room;
            }
            if (here >= 0) {
                if (room == here) {
                    dist[room] = 0;
                } else {
                    List<DungeonMapPathfinder.RoomStep> p = DungeonMapPathfinder.findPath(layout, here, room, true);
                    dist[room] = p == null ? 99 : p.size() - 1;
                }
            }
        }
        java.util.Set<Integer> towardBlood = new java.util.HashSet<>();
        if (here >= 0 && bloodRoom >= 0) {
            List<DungeonMapPathfinder.RoomStep> p = DungeonMapPathfinder.findPath(layout, here, bloodRoom, true);
            if (p != null) {
                for (DungeonMapPathfinder.RoomStep s : p) {
                    towardBlood.add(s.room());
                }
            }
        }
        List<Want> out = new ArrayList<>();
        List<Goal> goals = new ArrayList<>();
        Vec3 o = origin;
        if (o != null) {
            for (Map.Entry<Integer, RoomGoals> e : ROOMS.entrySet()) {
                Integer room = roomOfMain.get(e.getKey());
                if (room == null) {
                    continue;
                }
                RoomGoals g = e.getValue();
                goals.clear();
                goalsFor(g, true, true, goals);
                double pri = dist[room] * 10.0 - (towardBlood.contains(room) ? 5 : 0) - (g.startNode() != null ? 3 : 0);
                for (Goal goal : goals) {
                    out.add(Want.of(o, goal, 1, pri + (goal.exact() ? 0 : 1)));
                }
            }
        }
        for (Map.Entry<Integer, RoomGoals> e : ROOMS.entrySet()) {
            Integer from = roomOfMain.get(e.getKey());
            RoomGoals src = e.getValue();
            if (from == null || src.routeEnd() == null) {
                continue;
            }
            for (Map.Entry<Integer, RoomGoals> f : ROOMS.entrySet()) {
                Integer to = roomOfMain.get(f.getKey());
                if (to == null || to.equals(from)) {
                    continue;
                }
                RoomGoals dst = f.getValue();
                goals.clear();
                if (dst.startNode() != null) {
                    goals.add(new Goal(dst.startNode(), -1, true));
                } else {
                    goalsFor(dst, false, false, goals);
                }
                for (Goal goal : goals) {
                    out.add(Want.of(src.routeEnd(), goal, 2, 1000.0 + dist[from] * 10.0 + dist[to]));
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.priority(), b.priority()));
        wants = out;
        wantsVersion++;
    }

    private enum Need { NONE, MISSING, STALE }

    private static Need needs(Want w) {
        Map<String, Entry> byFrom = TRIPS.get(w.goalKey());
        Entry e = byFrom == null ? null : byFrom.get(w.fromKey());
        if (e != null && e.from.distanceToSqr(w.from()) < 0.09) {
            return e.stale() ? Need.STALE : Need.NONE;
        }
        Long failedAt = FAILED.get(w.key());
        if (failedAt != null && failedAt == epoch.get()) {
            return Need.NONE;
        }
        return Need.MISSING;
    }

    /** Missing trips first (in priority order), then stale ones - not until the block changes have gone quiet. */
    private static List<Want> nextBatch() {
        List<Want> missing = new ArrayList<>();
        List<Want> stale = new ArrayList<>();
        boolean quiet = System.currentTimeMillis() - lastChangeMs >= CHANGE_QUIET_MS;
        for (Want w : wants) {
            Need need = needs(w);
            if (need == Need.MISSING && missing.size() < BATCH) {
                missing.add(w);
            } else if (need == Need.STALE && quiet && stale.size() < BATCH) {
                stale.add(w);
            }
            if (missing.size() >= BATCH) {
                break;
            }
        }
        if (!missing.isEmpty()) {
            return missing;
        }
        return stale;
    }

    /** The first time phase 1 from the floor's first origin has every trip it wants (or knows it cannot have it). */
    private static void notePhase1() {
        if (origin == null || phase1ReadyMs != 0 && phase1FullMs != 0) {
            return;
        }
        if (phase1Origin == null) {
            phase1Origin = origin;
        }
        if (phase1Origin != origin) {
            return;   // phase 1 readiness is measured from the first spot he stood on
        }
        int wanted = 0;
        int open = 0;
        int failed = 0;
        int quick = 0;
        for (Want w : wants) {
            if (w.phase() != 1) {
                continue;
            }
            wanted++;
            Need need = needs(w);
            Map<String, Entry> byFrom = TRIPS.get(w.goalKey());
            Entry e = byFrom == null ? null : byFrom.get(w.fromKey());
            if (need == Need.MISSING) {
                open++;
            } else if (e == null) {
                failed++;
            } else if (!e.onFull) {
                quick++;
            }
        }
        if (wanted == 0 || open > 0 || ROOMS.size() < roomCountNow()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phase1ReadyMs == 0) {
            phase1ReadyMs = now;
            phase1Wanted = wanted;
            phase1Failed = failed;
            LOGGER.info(String.format(Locale.US, "[Path] precompute: phase 1 ready %d ms after the floor was known - %d trip(s)"
                            + " from %s to every room (%d on the quick graph, %d with no graph plan)",
                    now - floorKnownMs, wanted, fmt(phase1Origin), quick, failed));
        }
        if (phase1FullMs == 0 && quick == 0 && EtherwarpPathfinder.fullWarm()) {
            phase1FullMs = now;
            LOGGER.info(String.format(Locale.US, "[Path] precompute: phase 1 on the full floor graph %d ms after the floor was"
                    + " known", now - floorKnownMs));
        }
    }

    private static int roomCountNow() {
        DungeonLayout layout = DungeonLayout.current();
        int n = 0;
        for (int room = 0; room < layout.roomCount(); room++) {
            int[] tiles = layout.tiles(room);
            n += tiles != null && tiles.length > 0 ? 1 : 0;
        }
        return n;
    }

    /** A batch of searches on the planner thread, at most {@link #SLICE_NANOS}. */
    private static final class Job implements Runnable {
        final List<Want> batch;
        final DungeonLayout layout;
        final EtherwarpPathfinder.PathConfig cfg;
        final double range;
        final int gen;

        Job(List<Want> batch, DungeonLayout layout, EtherwarpPathfinder.PathConfig cfg, double range, int gen) {
            this.batch = batch;
            this.layout = layout;
            this.cfg = cfg;
            this.range = range;
            this.gen = gen;
        }

        @Override
        public void run() {
            try {
                long end = System.nanoTime() + SLICE_NANOS;
                for (Want w : batch) {
                    if (gen != generation || System.nanoTime() > end || ClearExecutor.tripSearchWaiting()) {
                        break;
                    }
                    long before = epoch.get();
                    EtherwarpPathfinder.CachePlan plan = EtherwarpPathfinder.planForCache(w.from(), w.goal().to(),
                            w.goal().tileIdx(), cfg, range, layout, w.goal().exact());
                    searches++;
                    if (gen != generation) {
                        break;
                    }
                    long after = epoch.get();
                    if (plan == null) {
                        failures++;
                        FAILED.put(w.key(), after);
                        continue;
                    }
                    store(w, plan, before == after ? after : before, range);
                }
            } catch (Throwable t) {
                LOGGER.warn("[Path] precompute: a search failed: {}", t.toString());
            } finally {
                jobInFlight = false;
            }
        }
    }

    private static void store(Want w, EtherwarpPathfinder.CachePlan plan, long atEpoch, double range) {
        List<EtherwarpPathfinder.Node> path = plan.path();
        double[] corridor = new double[path.size() * 6];
        BlockPos last = null;
        for (int i = 0; i < path.size(); i++) {
            EtherwarpPathfinder.Node n = path.get(i);
            BlockPos land = i + 1 < path.size() ? path.get(i + 1).pos : plan.lastLanding();
            corridor[i * 6] = n.x;
            corridor[i * 6 + 1] = n.y + EtherSearch.SNEAK_EYE;
            corridor[i * 6 + 2] = n.z;
            if (land != null) {
                corridor[i * 6 + 3] = land.getX() + 0.5;
                corridor[i * 6 + 4] = land.getY() + 1.0;
                corridor[i * 6 + 5] = land.getZ() + 0.5;
            } else {
                corridor[i * 6 + 3] = n.x;
                corridor[i * 6 + 4] = n.y;
                corridor[i * 6 + 5] = n.z;
            }
            last = land;
        }
        if (last == null) {
            return;
        }
        Entry e = new Entry(w.from(), w.goal(), path, last, corridor, plan.onFull(), plan.onGoal(), atEpoch, w.phase(),
                range);
        ConcurrentHashMap<String, Entry> byFrom = TRIPS.computeIfAbsent(w.goal().key(), k -> new ConcurrentHashMap<>());
        if (byFrom.put(w.fromKey(), e) == null) {
            SIZE.incrementAndGet();
        }
        stored++;
        if (SIZE.get() > MAX_ENTRIES) {
            evictOldest();
        }
    }

    /** Over {@link #MAX_ENTRIES}: the oldest tenth goes. */
    private static void evictOldest() {
        List<Entry> all = new ArrayList<>();
        for (Map<String, Entry> byFrom : TRIPS.values()) {
            all.addAll(byFrom.values());
        }
        all.sort((a, b) -> Long.compare(a.madeMs, b.madeMs));
        int n = Math.max(1, all.size() / 10);
        for (int i = 0; i < n; i++) {
            Entry e = all.get(i);
            Map<String, Entry> byFrom = TRIPS.get(e.goal.key());
            if (byFrom != null && byFrom.remove(fromKey(e.from), e)) {
                SIZE.decrementAndGet();
            }
        }
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static String fmt(Vec3 v) {
        return String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
