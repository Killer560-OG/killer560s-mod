package com.killer560.hub.livemap.autoclear;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * The floor's two etherwarp graphs and which one a click is planned on.
 *
 * <p><b>Why two.</b> The full graph ({@link WarpGraph}, every landing thinned to one per 2x2 columns, every doorway
 * and tile centre line kept whole) takes about five seconds of warm-up on six threads before a click can use it, and
 * a click before that had 40 ms on it and then the room-by-room planner: about 14 warps where the warm graph takes 9
 * (tools/bench/FloorBench -Dearly). It cannot simply search harder: until it is warm every node its search closes
 * needs about 1.5 ms of rays, and a near-fewest search closes thousands of them even with a heuristic that follows the
 * doors (docs/SIM.md, "A click during the first warm-up"). So a second, QUICK graph of the same floor - coarser
 * buckets, the doorways' own centre lines kept whole, no partial-occlusion aims - is warmed FIRST: about a tenth of
 * the rays, warm in about half a second, and from then on a click gets a complete fewest-warp answer on it in a
 * millisecond or two. Its paths are verified hop by hop exactly as the full graph's are; they only take a warp or so
 * more, because it has fewer landings to choose from. Once the full graph is warm, clicks use it alone.
 *
 * <p>The quick graph follows the full one ({@link WarpGraph#follower}), so it is told about every block change too.
 *
 * <p>No Minecraft imports: tools/bench/FloorBench runs exactly this policy. Owned by one planner thread, as the graphs
 * are.
 */
public final class FloorGraphs {

    public final WarpGraph full;
    /** Null: no quick graph (clicks before the full graph is warm get the cold budget, as before 2026-10-05). */
    public final WarpGraph quick;

    /** The quick graph's bucket width (the full graph's is EtherwarpPathfinder.BUCKET, 2). */
    public static final int QUICK_BUCKET = 5;
    /** The quick graph refuses a line stopped anywhere short of the landing without the full aim (cheaper). */
    public static final double QUICK_PARTIAL_FROM = 1.0;

    /** How long a click may spend finishing a re-warm of the full graph after a block change before it searches. */
    public static final long FINISH_WARM_NANOS = 60_000_000L;
    /** How long a click searches a full graph that has never been warm, with no quick graph to use. */
    public static final long COLD_BUDGET_NANOS = 40_000_000L;
    /** How long a click may spend finishing the quick graph's warm-up (it is about half a second from nothing). */
    public static final long QUICK_FINISH_NANOS = 600_000_000L;

    /** See {@link #plan}: a once-warm full graph with fewer nodes than this times the quick graph's is re-warming
     *  most of the floor, not finishing after a change. */
    public static final double KNOWN_RATIO = 1.3;
    /** Whether the last click on a not-warm full graph took it to know the floor already. */
    public boolean floorKnown;

    public FloorGraphs(WarpGraph full, WarpGraph quick) {
        this.full = full;
        this.quick = quick;
        full.follower = quick;
    }

    /**
     * One warm-up step of about {@code budgetNanos}: the quick graph until it is warm, then the full one. Returns true
     * while either has more to do.
     */
    public boolean warm(EtherSearch.Grid grid, Supplier<EtherSearch.Grid> grids, ExecutorService workers, int threads,
                        double sx, double sy, double sz, long budgetNanos) {
        if (quick != null && quick.warm(grid, grids, workers, threads, sx, sy, sz, budgetNanos)) {
            return true;
        }
        return full.warm(grid, grids, workers, threads, sx, sy, sz, budgetNanos);
    }

    /** True when neither graph has anything left to work out. */
    public boolean warmDone() {
        return full.warmDone() && (quick == null || quick.warmDone());
    }

    // ------------------------------------------------------------------------------------------- what the last plan did

    /** The graph the returned path (or the last search) was planned on. */
    public WarpGraph used;
    /** "full", "quick", "full, not warm" (the cold budget) - for the log. */
    public String usedName = "";
    /** The tile goal reached nothing and the exact block was planned instead. */
    public boolean usedExact;
    /** The full graph proved there is no way (closed door, sealed room): nothing else needs trying. */
    public boolean provedNoWay;
    /** The last full-graph search ran out of its budget. */
    public boolean timedOut;

    /**
     * A click: the fewest warps to {@code goal} (a tile's region or one block); when a tile reaches nothing,
     * {@code exactFallback} (its block) if not null. Null when nothing is found - then {@link #provedNoWay} says
     * whether anything else is worth trying (the room-by-room planner is the caller's).
     *
     * @param t0      when the click started (its budgets are counted from here)
     * @param timeout the click's whole budget on a warm graph
     */
    public List<EtherSearch.Hop> plan(EtherSearch.Grid grid, Supplier<EtherSearch.Grid> grids,
                                      ExecutorService workers, int threads, EtherSearch.Hop start, WarpGraph.Goal goal,
                                      WarpGraph.Goal exactFallback, long t0, long timeout, int maxWarps) {
        used = full;
        usedName = "full";
        usedExact = false;
        provedNoWay = false;
        timedOut = false;
        floorKnown = false;
        // Changes reported since the last warm-up slice first: until they are applied warmDone() still says true, and a
        // click right after a door opened searched with fields that no longer hold (found 2026-10-05 by FloorBench
        // -Dearlygate: the click right after a sealed entrance opened ran out of time and went room by room).
        full.applyChanges(grid);
        if (full.warmDone() || quick == null) {
            // Once the floor has been warm it stays mostly known: only a never-warm floor gets the short cold budget.
            boolean warm = full.warmDone() || full.warmedOnce();
            usedName = warm ? "full" : "full, not warm";
            return onFull(grid, start, goal, exactFallback, t0 + (warm ? timeout : Math.min(timeout,
                    COLD_BUDGET_NANOS)), maxWarps);
        }
        // The full graph is not warm. The quick graph is brought up to date first: tens of milliseconds after a door
        // (its re-check and fields), about half a second on a floor it has never seen.
        if (!quick.warmDone()) {
            quick.finishWarm(grid, grids, workers, threads, start.x, start.y, start.z,
                    Math.max(1, Math.min(QUICK_FINISH_NANOS, t0 + timeout - System.nanoTime())));
        }
        // "Has been warm" is not "knows the floor": the full graph's last warm can have covered only the sealed
        // entrance (the sim before the run starts) or the few hundred landings loaded before the floor's chunks
        // arrived (his Map Logger log: 279 nodes before the real 13,454). Warm on the whole floor it has about 2.6
        // times the quick graph's nodes; at half that it is taken to know the floor and only to be re-warming after a
        // change, which finishing takes tens of milliseconds.
        floorKnown = full.warmedOnce() && quick.warmDone() && full.warmedNodes() >= KNOWN_RATIO * quick.nodeCount();
        if (floorKnown || !quick.warmDone()) {
            // Blocks changed since the floor was warm (a door, a crypt, a puzzle). The changes re-check only the aims
            // near them, so finishing is tens of milliseconds - do it now rather than search without the exact
            // heuristic (his 2026-10-04 log: 39-40 warps there).
            if (full.warmedOnce()) {
                full.finishWarm(grid, grids, workers, threads, start.x, start.y, start.z, FINISH_WARM_NANOS);
            }
            boolean warm = full.warmDone() || full.warmedOnce();
            usedName = warm ? "full" : "full, not warm";
            return onFull(grid, start, goal, exactFallback, t0 + (warm ? timeout : Math.min(timeout,
                    COLD_BUDGET_NANOS)), maxWarps);
        }
        // The floor's first warm-up (or a re-warm of most of it): the quick graph.
        used = quick;
        usedName = "quick";
        // (A 25 ms look on the half-warm full graph for a path with fewer warps was measured and dropped: 0.0-0.3
        // warps a click for 25 ms on every click - docs/SIM.md.)
        List<EtherSearch.Hop> q = on(quick, grid, start, goal, exactFallback, t0 + timeout, maxWarps);
        if (q == null && !quick.timedOut && System.nanoTime() < t0 + timeout) {
            // Its warm-up pass may have started where it could see nothing of the floor (FloorBench -Dearlygate: a
            // pass from inside the sealed entrance that found no landing stayed "warm" with 0 nodes after the gate
            // opened). Once more from here.
            quick.reseed();
            quick.finishWarm(grid, grids, workers, threads, start.x, start.y, start.z,
                    Math.max(1, Math.min(QUICK_FINISH_NANOS, t0 + timeout - System.nanoTime())));
            if (quick.warmDone()) {
                q = on(quick, grid, start, goal, exactFallback, t0 + timeout, maxWarps);
            }
        }
        if (q != null) {
            return q;
        }
        // Nothing on the quick graph (it has fewer landings; its "no way" proves nothing): the full graph's cold try.
        used = full;
        usedName = "full, not warm";
        return onFull(grid, start, goal, exactFallback, Math.max(System.nanoTime() + COLD_BUDGET_NANOS / 4,
                Math.min(t0 + timeout, System.nanoTime() + COLD_BUDGET_NANOS)), maxWarps);
    }

    private List<EtherSearch.Hop> onFull(EtherSearch.Grid grid, EtherSearch.Hop start, WarpGraph.Goal goal,
                                         WarpGraph.Goal exactFallback, long deadline, int maxWarps) {
        List<EtherSearch.Hop> p = on(full, grid, start, goal, exactFallback, deadline, maxWarps);
        provedNoWay = p == null && full.provedNoWay;
        timedOut = full.timedOut;
        return p;
    }

    /** One graph: the goal, then (a tile that reaches nothing, not for lack of time) the fallback block. */
    private List<EtherSearch.Hop> on(WarpGraph g, EtherSearch.Grid grid, EtherSearch.Hop start, WarpGraph.Goal goal,
                                     WarpGraph.Goal exactFallback, long deadline, int maxWarps) {
        usedExact = false;
        List<EtherSearch.Hop> p = g.plan(grid, start, goal, deadline, maxWarps);
        if (p == null && exactFallback != null && !g.timedOut) {
            p = g.plan(grid, start, exactFallback, deadline, maxWarps);
            usedExact = true;
        }
        return p;
    }
}
