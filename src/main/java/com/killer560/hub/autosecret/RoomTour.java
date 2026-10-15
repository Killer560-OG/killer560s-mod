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
    /** The planner-thread budget of one request's searches. */
    private static final long SEARCH_BUDGET_NANOS = 400_000_000L;

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

    /** Straight-line trip estimate (the old one): the distance over the hop range, a quarter longer for walls. */
    static double estimate(Vec3 from, BlockPos to) {
        double dx = from.x - (to.getX() + 0.5);
        double dz = from.z - (to.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 2.0) {
            return 0;
        }
        int warps = Math.max(1, (int) Math.ceil(d * 1.25 / Math.max(1.0, ClearExecutor.hopRange())));
        return TRIP_FIXED_TICKS + TICKS_PER_WARP * warps;
    }

    /** A trip's ticks less the part every trip pays alike. */
    static double variable(double ticks) {
        return ticks >= UNREACHABLE_TICKS ? ticks : Math.max(0, ticks - TRIP_FIXED_TICKS);
    }

    /** Whether every trip from {@code from} into {@code sites} has been searched. */
    static boolean fromReady(Vec3 from, List<Site> sites) {
        String lk = nearestLeave(from);
        Map<String, Double> row = lk == null ? null : COST.get(lk);
        if (row == null) {
            return sites.isEmpty();
        }
        for (Site s : sites) {
            if (!row.containsKey(s.arriveKey())) {
                return false;
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
        return matrix(from, sites, false);
    }

    /**
     * @param travelOnly the trips' warp ticks only, without the {@link #TRIP_FIXED_TICKS} every trip pays alike - Auto
     *                   Secret's "secrets per tick of travel"; Dungeon Autopilot weighs whole seconds (work included)
     */
    static Matrix matrix(Vec3 from, List<Site> sites, boolean travelOnly) {
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
            Double c = row.get(s.arriveKey());
            if (c != null) {
                fromHere[i] = travelOnly ? variable(c) : c;
                fromSearched[i] = true;
                searched++;
            } else {
                fromHere[i] = travelOnly ? variable(estimate(from, s.arrive())) : estimate(from, s.arrive());
                estimated++;
            }
            Map<String, Double> out = COST.getOrDefault(leaveKey(s.leave()), Map.of());
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                Double b = out.get(sites.get(j).arriveKey());
                double t = b != null ? b : estimate(s.leave(), sites.get(j).arrive());
                between[i][j] = travelOnly ? variable(t) : t;
            }
        }
        return new Matrix(fromHere, between, weight, work, searched, estimated, fromSearched);
    }

    /**
     * Searches (on the planner thread) the trips from {@code from} into every site and between the sites, those not
     * searched yet. With {@code planAhead}: then picks the next room from {@code from} by {@link TourPlanner} and searches
     * that trip in full ({@link ClearExecutor#offerPreplan}). Client thread.
     */
    static void request(Vec3 from, List<Site> sites, boolean planAhead, boolean travelOnly, String why) {
        Minecraft client = Minecraft.getInstance();
        if (from == null || client.level == null || busy && !planAhead) {
            return;
        }
        List<Site> snapshot = List.copyOf(sites);
        DungeonLayout layout = DungeonLayout.capture();
        EtherwarpPathfinder.PathConfig cfg = ClearExecutor.pathConfig();
        double range = ClearExecutor.hopRange();
        int gen = generation;
        if (planAhead) {
            aheadPick = null;
            aheadFrom = null;
            ClearExecutor.dropPreplan();
        }
        busy = true;
        ClearExecutor.onPlanner(() -> {
            try {
                run(from, snapshot, planAhead, travelOnly, why, layout, cfg, range, gen);
            } catch (Throwable t) {
                LOGGER.warn("[AutoSecret] room order: the trip search failed: {}", t.toString());
            } finally {
                busy = false;
            }
        });
    }

    /** Planner thread. */
    private static void run(Vec3 from, List<Site> sites, boolean planAhead, boolean travelOnly, String why,
                            DungeonLayout layout,
                            EtherwarpPathfinder.PathConfig cfg, double range, int gen) {
        long t0 = System.nanoTime();
        long deadline = t0 + SEARCH_BUDGET_NANOS;
        String fromKey = leaveKey(from);
        LEAVES.put(fromKey, from);
        int searches = 0;
        int skipped = 0;
        // The trips from here first: the next choice depends on them most.
        List<Vec3> starts = new ArrayList<>();
        starts.add(from);
        for (Site s : sites) {
            LEAVES.putIfAbsent(leaveKey(s.leave()), s.leave());
            starts.add(s.leave());
        }
        for (int k = 0; k < starts.size(); k++) {
            Vec3 start = starts.get(k);
            Map<String, Double> row = COST.computeIfAbsent(leaveKey(start), x -> new ConcurrentHashMap<>());
            for (int j = 0; j < sites.size(); j++) {
                Site to = sites.get(j);
                if (k > 0 && k - 1 == j || row.containsKey(to.arriveKey())) {
                    continue;
                }
                if (gen != generation) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    skipped++;
                    continue;
                }
                List<EtherwarpPathfinder.Node> path = to.tile() >= 0
                        ? EtherwarpPathfinder.findDungeonPathToTile(start, to.arrive(), to.tile(), cfg, range, layout)
                        : EtherwarpPathfinder.findDungeonPath(start, to.arrive(), cfg, range, layout, false);
                searches++;
                row.put(to.arriveKey(), path == null ? UNREACHABLE_TICKS
                        : path.isEmpty() ? 0 : TRIP_FIXED_TICKS + TICKS_PER_WARP * path.size());
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        LOGGER.info(String.format(Locale.US, "[AutoSecret] room order: searched %d trip(s) between %d room(s) in %.0f ms"
                + "%s (%s)", searches, sites.size(), ms, skipped > 0 ? ", " + skipped + " left to the estimate" : "", why));
        if (!planAhead || sites.isEmpty() || gen != generation) {
            return;
        }
        Matrix m = matrix(from, sites, travelOnly);
        TourPlanner.Result res = TourPlanner.plan(m.fromHere(), m.between(), m.weight(), m.work(), 30_000_000L);
        int[] order = legacyOrder ? byWeight(m) : res.order();
        Site next = sites.get(order[0]);
        List<EtherwarpPathfinder.Node> path = next.tile() >= 0
                ? EtherwarpPathfinder.findDungeonPathToTile(from, next.arrive(), next.tile(), cfg, range, layout)
                : EtherwarpPathfinder.findDungeonPath(from, next.arrive(), cfg, range, layout, next.exact());
        LOGGER.info(String.format(Locale.US, "[AutoSecret] planned ahead from %.1f %.1f %.1f: next %s (%s), %s warp(s);"
                        + " order %s", from.x, from.y, from.z, next.key(), next.arriveKey(),
                path == null ? "no" : String.valueOf(path.size()), describe(sites, order)));
        Minecraft.getInstance().execute(() -> {
            if (gen != generation) {
                return;
            }
            aheadPick = next.key();
            aheadFrom = from;
            if (path != null && !path.isEmpty()) {
                ClearExecutor.offerPreplan(from, next.arrive(), next.tile(), next.exact(), path);
            }
        });
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
