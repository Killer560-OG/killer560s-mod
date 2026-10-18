package com.killer560.hub.autosecret;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.EtherwarpPathfinder;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Travel between the rooms Auto Secret and Dungeon Autopilot can go to next, in ticks, from the Interactive Map's own
 * planner - the floor graph's warp count for each trip ({@link EtherwarpPathfinder#findDungeonPath}), searched on its
 * planner thread (the only thread the graph may be used from) while a route runs - and the order to take them in
 * ({@link TourPlanner}).
 *
 * <p>Each room is a {@link Site}: where a trip into it lands ({@code arrive}: the route's start node block, or a map tile)
 * and where its work leaves him ({@code leave}: the route's predicted end, or the tile's landing). A trip's cost is
 * {@link #TRIP_FIXED_TICKS} plus {@link #TICKS_PER_WARP} a warp - Auto Clear's measured model of the Interactive Map's
 * runner. Costs are kept for the run, so after the first search only the trips from where he now is are new; a trip not
 * searched yet (the budget ran out, or it was never asked) is estimated from the straight line, as before.
 *
 * <p>When the search was asked for from a route's predicted end ({@link #request} with {@code planAhead}), it also picks
 * the next room there and then and searches its trip ({@link ClearExecutor#offerPreplan}), so the warp goes the tick the
 * route is over.
 */
final class RoomTour {

    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");

    /** The runner's fixed cost of a trip in ticks (Auto Clear's ETHER_FIXED_TICKS: search, sneak, arrival settle). */
    static final double TRIP_FIXED_TICKS = 14;
    /** Ticks per warp once a chain is going (Auto Clear's ETHER_TICKS_PER_WARP). */
    static final double TICKS_PER_WARP = 2;
    /** A trip the planner found no way for: far worse than any real one, but finite (so orders still compare). */
    static final double UNREACHABLE_TICKS = 2000;
    /** A trip's from-row is looked up from a searched start within this many blocks of where he stands. */
    private static final double FROM_MATCH = 3.0;
    /** The planner-thread budget of all of one request's searches (in slices; the rest is estimated). */
    private static final long TOTAL_BUDGET_NANOS = 3_000_000_000L;

    /**
     * One room to go to.
     * @param key    the candidate's key (room name, or "Unknown@tile")
     * @param arrive the block a trip into it aims at (the start node block; a tile trip's fallback block)
     * @param tile   the map tile of a tile trip, -1 for a block trip
     * @param exact  a block trip that must land ON the block (a start node)
     * @param leave  where the room's work leaves him (feet)
     * @param weight what the room is worth (its secrets; Autopilot's score)
     * @param work   ticks the room itself takes
     */
    record Site(String key, BlockPos arrive, int tile, boolean exact, Vec3 leave, double weight, double work) {
        String arriveKey() {
            return tile >= 0 ? "T" + tile : "B" + arrive.getX() + "," + arrive.getY() + "," + arrive.getZ();
        }
    }

    /** For the testkit (145-sim-autosecret-order): take the rooms most unfound first, as before 2026-10-15. */
    static volatile boolean legacyOrder;

    /** The trips searched this run: leave point key -> arrive key -> ticks. */
    private static final Map<String, Map<String, Double>> COST = new ConcurrentHashMap<>();
    /** The leave points searched from, by key. */
    private static final Map<String, Vec3> LEAVES = new ConcurrentHashMap<>();
    private static volatile int generation;
    /** A request is on the planner thread now. */
    private static volatile boolean busy;
    /** The site the last plan-ahead picked for the trip from its leave point, and that point. */
    private static volatile String aheadPick;
    private static volatile Vec3 aheadFrom;

    private RoomTour() {
    }

    static void reset() {
        generation++;
        COST.clear();
        LEAVES.clear();
        aheadPick = null;
        aheadFrom = null;
        ClearExecutor.dropPreplan();
    }

    static boolean isBusy() {
        return busy;
    }

    private static String leaveKey(Vec3 v) {
        return (int) Math.floor(v.x) + "," + (int) Math.floor(v.y) + "," + (int) Math.floor(v.z);
    }

    /** The searched start nearest {@code from} within {@link #FROM_MATCH}, or null. */
    private static String nearestLeave(Vec3 from) {
        String best = null;
        double bestD = FROM_MATCH;
        for (Map.Entry<String, Vec3> e : LEAVES.entrySet()) {
            double d = e.getValue().distanceTo(from);
            if (d <= bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    /** Warps an estimated trip takes for each room it crosses on the map: about what the searched trips took (2026-10-15,
     *  145's floors: 13 warps for 6 rooms, 19 for 9, 3 for 2) - walls send a trip round by the doors. */
    static final double WARPS_PER_ROOM = 2.0;

    /**
     * A trip not searched yet: the more of the straight distance over the hop range (a quarter longer for walls) and
     * {@link #WARPS_PER_ROOM} for every room of the map's path between the two rooms.
     */
    static double estimate(Vec3 from, BlockPos to, DungeonLayout layout) {
        double dx = from.x - (to.getX() + 0.5);
        double dz = from.z - (to.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 2.0) {
            return 0;
        }
        double warps = Math.max(1, Math.ceil(d * 1.25 / Math.max(1.0, ClearExecutor.hopRange())));
        if (layout != null) {
            int a = layout.roomAtWorld(from.x, from.z);
            int b = layout.roomAtWorld(to.getX() + 0.5, to.getZ() + 0.5);
            if (a >= 0 && b >= 0 && a != b) {
                List<com.killer560.hub.livemap.autoclear.DungeonMapPathfinder.RoomStep> path =
                        com.killer560.hub.livemap.autoclear.DungeonMapPathfinder.findPath(layout, a, b, false);
                if (path != null) {
                    warps = Math.max(warps, WARPS_PER_ROOM * (path.size() - 1));
                }
            }
        }
        return TRIP_FIXED_TICKS + TICKS_PER_WARP * warps;
    }

    /** A trip's ticks less the part every trip pays alike. */
    static double variable(double ticks) {
        return ticks >= UNREACHABLE_TICKS ? ticks : Math.max(0, ticks - TRIP_FIXED_TICKS);
    }

    /** A precomputed trip's ticks from {@code start} into {@code s} (the Interactive Map's TripCache), or null. */
    private static Double cached(Vec3 start, Site s) {
        int w = com.killer560.hub.livemap.autoclear.TripCache.warps(start, s.arrive(), s.tile());
        return w < 0 ? null : TRIP_FIXED_TICKS + TICKS_PER_WARP * w;
    }

    /** The searched trip's ticks (this run's matrix), else the precomputed one's, else null. */
    private static Double known(Map<String, Double> row, Vec3 start, Site s) {
        Double c = row == null ? null : row.get(s.arriveKey());
        return c != null ? c : start == null ? null : cached(start, s);
    }

    /** Whether every trip from {@code from} into {@code sites}, and between them, has been searched. */
    static boolean fromReady(Vec3 from, List<Site> sites) {
        String lk = nearestLeave(from);
        Map<String, Double> row = lk == null ? null : COST.get(lk);
        for (Site s : sites) {
            if (known(row, from, s) == null) {
                return false;
            }
            Map<String, Double> out = COST.get(leaveKey(s.leave()));
            for (Site t : sites) {
                if (t != s && known(out, s.leave(), t) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The site the plan-ahead from {@code from} picked (a preplan was offered for it), or null. */
    static String aheadPick(Vec3 from) {
        Vec3 f = aheadFrom;
        return f != null && f.distanceTo(from) <= FROM_MATCH ? aheadPick : null;
    }

    /** The cost matrix for these sites from {@code from}: searched trips where there are any, estimates elsewhere. */
    record Matrix(double[] fromHere, double[][] between, double[] weight, double[] work, int searched, int estimated,
                  boolean[] fromSearched) {
    }

    static Matrix matrix(Vec3 from, List<Site> sites) {
        return matrix(from, sites, false, DungeonLayout.capture());
    }

    /**
     * @param travelOnly the trips' warp ticks only, without the {@link #TRIP_FIXED_TICKS} every trip pays alike - Auto
     *                   Secret's "secrets per tick of travel"; Dungeon Autopilot weighs whole seconds (work included)
     */
    static Matrix matrix(Vec3 from, List<Site> sites, boolean travelOnly, DungeonLayout layout) {
        int n = sites.size();
        double[] fromHere = new double[n];
        double[][] between = new double[n][n];
        double[] weight = new double[n];
        double[] work = new double[n];
        int searched = 0;
        int estimated = 0;
        boolean[] fromSearched = new boolean[n];
        String lk = nearestLeave(from);
        Map<String, Double> row = lk == null ? Map.of() : COST.getOrDefault(lk, Map.of());
        for (int i = 0; i < n; i++) {
            Site s = sites.get(i);
            weight[i] = s.weight();
            work[i] = s.work();
            Double c = known(row, from, s);
            if (c != null) {
                fromHere[i] = travelOnly ? variable(c) : c;
                fromSearched[i] = true;
                searched++;
            } else {
                fromHere[i] = travelOnly ? variable(estimate(from, s.arrive(), layout)) : estimate(from, s.arrive(), layout);
                estimated++;
            }
            Map<String, Double> out = COST.getOrDefault(leaveKey(s.leave()), Map.of());
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                Double b = known(out, s.leave(), sites.get(j));
                double t = b != null ? b : estimate(s.leave(), sites.get(j).arrive(), layout);
                between[i][j] = travelOnly ? variable(t) : t;
            }
        }
        return new Matrix(fromHere, between, weight, work, searched, estimated, fromSearched);
    }

    /**
     * Searches (on the planner thread) the trips from {@code from} into every site and between the sites, those not
     * searched yet - in slices of {@link #SLICE_NANOS}, each queued behind whatever the planner thread has waiting, so a
     * trip of the map's own never waits long for it. With {@code planAhead}: then picks the next room from {@code from}
     * by {@link TourPlanner} and searches that trip in full ({@link ClearExecutor#offerPreplan}). Client thread.
     */
    static void request(Vec3 from, List<Site> sites, boolean planAhead, boolean travelOnly, String why) {
        Minecraft client = Minecraft.getInstance();
        if (from == null || client.level == null || busy && !planAhead) {
            return;
        }
        if (planAhead) {
            aheadPick = null;
            aheadFrom = null;
            ClearExecutor.dropPreplan();
        }
        busy = true;
        new Job(from, List.copyOf(sites), planAhead, travelOnly, why, DungeonLayout.capture(), ClearExecutor.pathConfig(),
                ClearExecutor.hopRange(), generation).submit();
    }

    /** One slice of a request's searches on the planner thread at most this long, then it queues again. */
    private static final long SLICE_NANOS = 30_000_000L;

    /** A request's searches, run a slice at a time on the planner thread. */
    private static final class Job implements Runnable {
        final Vec3 from;
        final List<Site> sites;
        final boolean planAhead;
        final boolean travelOnly;
        final String why;
        final DungeonLayout layout;
        final EtherwarpPathfinder.PathConfig cfg;
        final double range;
        final int gen;
        final List<Vec3> starts = new ArrayList<>();
        final long t0 = System.nanoTime();
        int k;
        int j;
        int searches;
        int fromCache;
        int skipped;
        int slices;

        Job(Vec3 from, List<Site> sites, boolean planAhead, boolean travelOnly, String why, DungeonLayout layout,
            EtherwarpPathfinder.PathConfig cfg, double range, int gen) {
            this.from = from;
            this.sites = sites;
            this.planAhead = planAhead;
            this.travelOnly = travelOnly;
            this.why = why;
            this.layout = layout;
            this.cfg = cfg;
            this.range = range;
            this.gen = gen;
            // The trips from here first: the next choice depends on them most.
            starts.add(from);
            LEAVES.put(leaveKey(from), from);
            for (Site s : sites) {
                LEAVES.putIfAbsent(leaveKey(s.leave()), s.leave());
                starts.add(s.leave());
            }
        }

        void submit() {
            ClearExecutor.onPlanner(this);
        }

        @Override
        public void run() {
            try {
                if (step()) {
                    finish();
                    busy = false;
                } else {
                    submit();   // the rest after whatever was queued meanwhile (a trip of the map's own first)
                }
            } catch (Throwable t) {
                LOGGER.warn("[AutoSecret] room order: the trip search failed: {}", t.toString());
                if (planAhead) {
                    ClearExecutor.preplanFailed();
                }
                busy = false;
            }
        }

        /** One slice. @return true when every search is done (or left to the estimate) */
        private boolean step() {
            slices++;
            long sliceEnd = System.nanoTime() + SLICE_NANOS;
            boolean warm = EtherwarpPathfinder.graphWarm();
            for (; k < starts.size(); k++, j = 0) {
                Vec3 start = starts.get(k);
                Map<String, Double> row = COST.computeIfAbsent(leaveKey(start), x -> new ConcurrentHashMap<>());
                for (; j < sites.size(); j++) {
                    Site to = sites.get(j);
                    if (k > 0 && k - 1 == j || row.containsKey(to.arriveKey())) {
                        continue;
                    }
                    // Already precomputed by the Interactive Map (TripCache): its warps, no search.
                    Double pre = cached(start, to);
                    if (pre != null) {
                        row.put(to.arriveKey(), pre);
                        fromCache++;
                        continue;
                    }
                    if (gen != generation) {
                        return true;
                    }
                    // On a cold floor graph one search can take a second: the estimate stands in until it is warm.
                    if (!warm || System.nanoTime() - t0 > TOTAL_BUDGET_NANOS) {
                        skipped++;
                        continue;
                    }
                    // A trip of the map's own that is waiting goes first; the rest in the next slice.
                    if (System.nanoTime() > sliceEnd || ClearExecutor.tripSearchWaiting()) {
                        return false;
                    }
                    List<EtherwarpPathfinder.Node> path = to.tile() >= 0
                            ? EtherwarpPathfinder.findDungeonPathToTile(start, to.arrive(), to.tile(), cfg, range, layout)
                            : EtherwarpPathfinder.findDungeonPath(start, to.arrive(), cfg, range, layout, false);
                    searches++;
                    row.put(to.arriveKey(), path == null ? UNREACHABLE_TICKS
                            : path.isEmpty() ? 0 : TRIP_FIXED_TICKS + TICKS_PER_WARP * path.size());
                }
            }
            return true;
        }

        /** Planner thread: everything searched - log it, and plan the next trip ahead if asked. */
        private void finish() {
            double ms = (System.nanoTime() - t0) / 1e6;
            LOGGER.info(String.format(Locale.US, "[AutoSecret] room order: searched %d trip(s), %d precomputed, between %d"
                            + " room(s) in %.0f ms (%d slice(s))%s (%s)", searches, fromCache, sites.size(), ms, slices,
                    skipped > 0 ? ", " + skipped + " left to the estimate" : "", why));
            if (!planAhead || sites.isEmpty() || gen != generation) {
                return;
            }
            Matrix m = matrix(from, sites, travelOnly, layout);
            TourPlanner.Result res = TourPlanner.plan(m.fromHere(), m.between(), m.weight(), m.work(), 30_000_000L);
            int[] order = legacyOrder ? byWeight(m) : travelOnly ? secretOrder(m) : res.order();
            Site next = sites.get(order[0]);
            ClearExecutor.preplanStarted(from, next.arrive(), next.tile(), next.exact());
            // The trip precomputed (TripCache) when there is one - read on this thread, it is a concurrent map.
            List<EtherwarpPathfinder.Node> pre = com.killer560.hub.livemap.autoclear.TripCache.pathFor(from, next.arrive(),
                    next.tile(), next.exact());
            List<EtherwarpPathfinder.Node> path = pre != null ? pre : next.tile() >= 0
                    ? EtherwarpPathfinder.findDungeonPathToTile(from, next.arrive(), next.tile(), cfg, range, layout)
                    : EtherwarpPathfinder.findDungeonPath(from, next.arrive(), cfg, range, layout, next.exact());
            LOGGER.info(String.format(Locale.US, "[AutoSecret] planned ahead from %.1f %.1f %.1f: next %s (%s), %s warp(s);"
                            + " order %s | %s", from.x, from.y, from.z, next.key(), next.arriveKey(),
                    path == null ? "no" : String.valueOf(path.size()), describe(sites, order),
                    compare(m, order, byWeight(m), "most unfound first")));
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) {
                    ClearExecutor.preplanFailed();
                    return;
                }
                aheadPick = next.key();
                aheadFrom = from;
                ClearExecutor.offerPreplan(from, next.arrive(), next.tile(), next.exact(), path);
            });
        }
    }

    static String describe(List<Site> sites, int[] order) {
        List<String> parts = new ArrayList<>();
        for (int i : order) {
            Site s = sites.get(i);
            parts.add(String.format(Locale.US, "%s(%.1f)", s.key(), s.weight()));
        }
        return String.join(" > ", parts);
    }

    /** For logs: the costs of an order beside the costs of another (the old rule's) for the same matrix. */
    static String compare(Matrix m, int[] order, int[] old, String oldName) {
        double[] a = TourPlanner.evaluate(order, m.fromHere(), m.between(), m.weight(), m.work());
        double[] b = TourPlanner.evaluate(old, m.fromHere(), m.between(), m.weight(), m.work());
        return String.format(Locale.US, "value-weighted wait %.0f, all done in %.0f ticks; %s: %.0f, %.0f ticks"
                + " (%d trip(s) searched, %d estimated)", a[0], a[1], oldName, b[0], b[1], m.searched(), m.estimated());
    }

    /**
     * Auto Secret's own order for these sites, by its Room Order setting: Largest First is {@link #byWeight} (each site's
     * weight is its room's unfound secrets), Fewest Warps is {@link TourPlanner#shortest} over the trips.
     */
    static int[] secretOrder(Matrix m) {
        if (AutoSecretConfig.getInstance().getSecretOrder() == AutoSecretConfig.SecretOrder.LARGEST_FIRST) {
            return byWeight(m);
        }
        return TourPlanner.shortest(m.fromHere(), m.between(), 30_000_000L).order();
    }

    /** Indices in descending weight (ties: nearer first) - the old "most unfound first" order, for comparison. */
    static int[] byWeight(Matrix m) {
        int n = m.weight().length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (x, y) -> {
            int c = Double.compare(m.weight()[y], m.weight()[x]);
            return c != 0 ? c : Double.compare(m.fromHere()[x], m.fromHere()[y]);
        });
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /** Sites by key, for looking a candidate up. */
    static Map<String, Site> byKey(List<Site> sites) {
        Map<String, Site> out = new HashMap<>();
        for (Site s : sites) {
            out.put(s.key(), s);
        }
        return out;
    }
}
