package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.util.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Auto Clear's kill plan for one room with the Hyperion (ackilllogic-1122, killer560 2026-11-22: "there should not
 * necessarily need another switch for things like teleporting with hype to kill multiple sets of mobs instead of using
 * ether warp for everyone that should just be built into the logic ... I really want you to optimize every last bit of
 * this").
 * <p>
 * The whole room is planned as one sequence of moves, the fewest TICKS to kill every mob some move reaches: a Wither
 * Impact in place, a Wither Impact across (any look of {@link WeaponReach}'s fan: a 10-block dash whose landing blast
 * kills what it reaches, priced as the chain link is: a turn, the learned cast time and the fall), or an etherwarp trip
 * onto a stand block of the room's cover followed by a cast in place (this run's measured trip ticks for its warps, then
 * the swap back and the turn). Depth-first branch and bound, children best ticks-per-kill first, so the first leaf is the
 * greedy answer and the search only improves on it; bounded by nodes, fans and time.
 * <p>
 * The targets are the room's known spawn spots (one per spawn box, plus any mob seen off every spot), not sightings, so
 * the plan exists before he enters. The search is bounded by {@link #oldChoice}, a price of what the per-mob choice would
 * do (cast in place while anything is in the blast, else RepositionPlanner's cover tour with its one-warp swap, the old
 * hop, chain cast or trip) minus {@link #margin}: a plan that does not beat it by that much comes back empty and the old
 * choice runs. The old model must stay faithful: a pessimistic one let worse plans through (k8, Museum priced 65 against
 * a real 47).
 * <p>
 * Runs on its own thread (one job at a time, a newer job replaces a waiting one), never on the render thread. The dash
 * landings of a fan depend on the blocks alone, so they are kept per feet position for the room (bounded, dropped on a
 * new room or world). Reads the client level off-thread the way the etherwarp planner reads chunk sections; a read that
 * throws drops the job, and the executing tick checks every cast's landing again before sending it.
 */
final class KillPlanner {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoclear");

    static final int IN_PLACE = 0;
    static final int CAST = 1;
    static final int ETHER = 2;

    /** One move: from {@code from} with {@code aliveBefore} alive, it ends at {@code at} and kills {@code kills}. */
    record Step(int kind, float yaw, float pitch, Vec3 from, Vec3 at, BlockPos stand, long aliveBefore, long kills,
                int ticks, int fall) {
        String describe() {
            return switch (kind) {
                case IN_PLACE -> "cast in place x" + Long.bitCount(kills);
                case CAST -> String.format(java.util.Locale.US, "cast across %.0f/%.0f x%d", yaw, pitch, Long.bitCount(kills));
                default -> "etherwarp " + stand.toShortString();
            };
        }
    }

    /**
     * A finished plan for the room's targets (bit i = target i, see AutoClearFeature's room targets): planned from
     * {@code start} with {@code alive0} alive. {@code oldTicks} is what the old per-mob choice costs under the same prices;
     * {@code steps} is empty when no sequence beat it by {@link #margin}: the old choice then runs unchanged.
     */
    record Plan(long job, int room, Vec3 start, long alive0, List<Step> steps, int ticks, int oldTicks, int nodes,
                int fans, double ms) {
    }

    /** What the planner needs, all copied on the client thread. */
    record Job(long id, int epoch, int room, AABB[] boxes, long alive, Vec3 feet, long[] noLand, int[] trip, int link,
               int killTicks, int inPlaceTicks, boolean casts, boolean keep, Level level, Player player, String name) {
    }

    private static final int MAX_NODES = 4000;
    private static final int MAX_FANS = 40;
    private static final long BUDGET_NANOS = 80_000_000L;
    private static final int CAST_KEEP = 8;
    private static final int ETHER_KEEP = 8;
    private static final int STAND_TRIES = 4;
    /** A cast's blast goes off this many ticks after it is chosen: the turn, then the use (the old hop's price). */
    static final int CAST_KILL_AT = 2;
    private static final int FAN_CACHE = 256;
    private static final int WARP_CACHE = 4096;
    private static final int STAND_CACHE = 32;
    /** The old choice's preferOneWarp: at most this many blocks tried for one a single warp lands on. */
    private static final int ONE_WARP_TRIES = 40;
    /** The old cover plan's search budget per step of the old choice's price. */
    private static final long COVER_NANOS = 2_000_000L;

    /** A plan is taken only when it beats the old choice by this many ticks: the prices are estimates. */
    static int margin(int oldTicks) {
        return Math.max(3, oldTicks / 12);
    }

    private static final ThreadPoolExecutor THREAD = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> {
        Thread t = new Thread(r, "killer560smod-killplanner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    static {
        THREAD.allowCoreThreadTimeOut(true);
    }

    private static volatile Plan latest;
    private static volatile int epoch;
    private static long nextJob = 1;

    // ---- planner thread only ----
    private static int cacheEpoch = -1;
    private static final Map<Long, Land[]> FANS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Land[]> e) {
            return size() > FAN_CACHE;
        }
    };
    private static final Map<Long, Integer> WARPS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> e) {
            return size() > WARP_CACHE;
        }
    };
    /** Stand blocks and what each reaches, per set of target boxes (to the block): {keys, masks}. */
    private static final Map<Long, long[][]> STANDS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[][]> e) {
            return size() > STAND_CACHE;
        }
    };

    private KillPlanner() {
    }

    /** One look of a fan and where its dash lands; the fall is worked out the first time a plan wants it. */
    private static final class Land {
        final float yaw;
        final float pitch;
        final Vec3 at;
        int fall = -1;

        Land(float yaw, float pitch, Vec3 at) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.at = at;
        }
    }

    /** Client thread: a new room or world; every plan and kept landing is stale. */
    static void reset() {
        epoch++;
        latest = null;
    }

    static Plan latest() {
        return latest;
    }

    /** Client thread: queue a plan for {@code room}'s targets from {@code feet} with {@code alive} alive. */
    static long submit(int room, AABB[] boxes, long alive, Vec3 feet, long[] noLand, int[] trip, int link, int killTicks,
                       int inPlaceTicks, boolean casts, boolean keep, Level level, Player player, String name) {
        Job job = new Job(nextJob++, epoch, room, boxes, alive, feet, noLand, trip, link, killTicks, inPlaceTicks, casts,
                keep, level, player, name);
        THREAD.execute(() -> {
            try {
                Plan p = plan(job);
                if (p != null && job.epoch == epoch) {
                    latest = p;
                }
            } catch (RuntimeException e) {
                // A chunk or entity list changing under the read: the next decision asks again.
                LOGGER.debug("[AutoClear] kill plan for {} dropped: {}", job.name, e.toString());
            }
        });
        return job.id;
    }

    // ---- the search (planner thread) ----

    private static final class Search {
        final Job job;
        final long[] stands;
        final long[] standMasks;
        final long goal;
        final int minStep;
        final long t0 = System.nanoTime();
        final Map<Long, Integer> seen = new HashMap<>();
        int nodes;
        int fans;
        int best = Integer.MAX_VALUE;
        int first = -1;
        List<Step> bestPath = List.of();

        Search(Job job, long[][] stands, long goal, int minStep) {
            this.job = job;
            this.stands = stands[0];
            this.standMasks = stands[1];
            this.goal = goal;
            this.minStep = minStep;
        }

        boolean out() {
            return nodes > MAX_NODES || System.nanoTime() - t0 > BUDGET_NANOS;
        }
    }

    static Plan plan(Job job) {
        if (cacheEpoch != job.epoch) {
            cacheEpoch = job.epoch;
            FANS.clear();
            WARPS.clear();
            STANDS.clear();
            AIMS.clear();
        }
        long[][] stands = stands(job);
        long goal = 0;
        for (long m : stands[1]) {
            goal |= m;
        }
        goal |= blast(job.feet, job.boxes);
        goal &= job.alive;
        if (goal == 0) {
            return null;
        }
        int minStep = Math.min(job.inPlaceTicks, Math.min(job.casts ? CAST_KILL_AT : Integer.MAX_VALUE,
                job.trip[1] + job.killTicks));
        Search s = new Search(job, stands, goal, Math.max(1, minStep));
        // Never slower than the old choice by construction: the search starts with the old choice's price less the
        // margin as its bound, so it keeps only a sequence that beats it (and none at all when nothing does).
        int old = oldChoice(s);
        // keep: the plan he was following was left because the server moved him (his own click, a correction), so a
        // plan from where he now stands goes on unless it is slower than the old choice (no margin to win again).
        s.best = job.keep ? old + 1 : old - margin(old);
        dfs(s, job.feet, job.alive, 0, 0, new ArrayList<>());
        return new Plan(job.id, job.room, job.feet, job.alive, List.copyOf(s.bestPath), s.best, old, s.nodes, s.fans,
                (System.nanoTime() - s.t0) / 1e6);
    }

    /**
     * The stand blocks round the live targets (the old cover's 11 x 9 x 11 block search round each, etherwarpable,
     * from which the Hyperion's blast reaches the target) and every target each reaches; noLand blocks left out.
     */
    private static long[][] stands(Job j) {
        long key = 17;
        for (int i = 0; i < j.boxes.length && i < 63; i++) {
            if ((j.alive & (1L << i)) != 0) {
                key = key * 31 + BlockPos.containing(j.boxes[i].getCenter()).asLong() + i;
            }
        }
        key = key * 31 + java.util.Arrays.hashCode(j.noLand);
        long[][] had = STANDS.get(key);
        if (had != null) {
            return had;
        }
        Map<Long, Long> masks = new LinkedHashMap<>();
        for (int i = 0; i < j.boxes.length && i < 63; i++) {
            if ((j.alive & (1L << i)) == 0) {
                continue;
            }
            AABB box = j.boxes[i];
            BlockPos base = BlockPos.containing(box.getCenter());
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dy = -5; dy <= 3; dy++) {
                        BlockPos pos = base.offset(dx, dy, dz);
                        long k = pos.asLong();
                        if (masks.containsKey(k)) {
                            continue;
                        }
                        Vec3 feet = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                        if (!WeaponReach.hyperionHits(feet, box)) {
                            continue;
                        }
                        boolean ok = TeleportUtils.etherwarpable(pos);
                        for (long n : j.noLand) {
                            ok &= n != k;
                        }
                        masks.put(k, ok ? blast(feet, j.boxes) : 0L);
                    }
                }
            }
        }
        int n = 0;
        for (long m : masks.values()) {
            n += m != 0 ? 1 : 0;
        }
        long[][] out = {new long[n], new long[n]};
        n = 0;
        for (Map.Entry<Long, Long> e : masks.entrySet()) {
            if (e.getValue() != 0) {
                out[0][n] = e.getKey();
                out[1][n++] = e.getValue();
            }
        }
        STANDS.put(key, out);
        return out;
    }

    /**
     * What the old per-mob choice (AutoClearFeature.attack) costs from the job's state under the planner's own prices:
     * a cast in place while any target is in its blast; else for the nearest target, the block reaching most of what is
     * left among those reaching it (the cover plan's block), swapped for a one-warp block that does at least half its
     * job; a chain cast instead when one look's blast reaches that block's whole group and its price is not over the trip.
     */
    private static int oldChoice(Search s) {
        Job j = s.job;
        Vec3 pos = j.feet;
        long alive = j.alive & s.goal;
        int g = 0;
        boolean lastCast = false;
        int lastDash = 0;
        for (int guard = 0; alive != 0 && guard < 64; guard++) {
            long here = blast(pos, j.boxes) & alive;
            if (here != 0) {
                g += j.inPlaceTicks;
                alive &= ~here;
                lastCast = false;
                continue;
            }
            // The old cover plan (RepositionPlanner over what is left, planned again after every death), its next block the
            // first in its order one warp from here or swappable to one (nextPlanBlock), else its first.
            int[] order = coverOrder(s, pos, alive);
            if (order.length == 0) {
                break;   // nothing left that a block reaches: the old choice skips those
            }
            int next = order[0];
            int w = Integer.MAX_VALUE;
            int swap = -1;
            for (int o : order) {
                int ow = warps(pos, BlockPos.of(s.stands[o]));
                int sw = ow > 1 ? oneWarpFor(s, pos, o, alive) : -1;
                if (sw >= 0) {
                    ow = 1;
                }
                if (ow < w) {
                    w = ow;
                    next = o;
                    swap = sw;
                }
                if (w == 1) {
                    break;
                }
            }
            long need = s.standMasks[next] & alive;
            int t = -1;
            double td = Double.MAX_VALUE;
            for (int i = 0; i < j.boxes.length && i < 63; i++) {
                double d;
                if ((need & (1L << i)) != 0 && (d = j.boxes[i].getCenter().distanceToSqr(pos)) < td) {
                    td = d;
                    t = i;
                }
            }
            long tb = 1L << t;
            BlockPos stand = BlockPos.of(s.stands[swap >= 0 ? swap : next]);
            int trip = j.trip[Math.min(w, j.trip.length - 1)] + j.killTicks;
            if (j.casts) {
                // The old hop goes first wherever its landing's blast reaches the block's whole group (hopKeepsPlan).
                WeaponReach.Aim hop = WeaponReach.hyperionHop(j.level, j.player, pos, j.boxes[t]);
                long hk = hop == null ? 0 : blast(hop.expectFeet(), j.boxes) & alive;
                if ((hk & need) == need && (hk & tb) != 0) {
                    int t2 = j.link + Math.min(20, WeaponReach.fallTicks(j.level, j.player, hop.expectFeet()));
                    g += t2;
                    alive &= ~hk;
                    pos = hop.expectFeet();
                    lastCast = true;
                    lastDash = Math.max(0, t2 - CAST_KILL_AT);
                    continue;
                }
                Step chain = null;
                for (Step c : casts(s, pos, alive)) {
                    if ((c.kills() & need) == need && (chain == null || c.ticks() < chain.ticks())) {
                        chain = c;
                    }
                }
                if (chain != null && chain.ticks() <= trip) {
                    g += chain.ticks();
                    alive &= ~chain.kills();
                    pos = chain.at();
                    lastCast = true;
                    lastDash = Math.max(0, chain.ticks() - CAST_KILL_AT);
                    continue;
                }
            }
            g += trip;
            alive &= ~(blast(new Vec3(stand.getX() + 0.5, stand.getY() + 1.0, stand.getZ() + 0.5), j.boxes) & alive);
            pos = new Vec3(stand.getX() + 0.5, stand.getY() + 1.0, stand.getZ() + 0.5);
            lastCast = false;
        }
        return lastCast ? g - lastDash : g;
    }

    private static void dfs(Search s, Vec3 pos, long alive, int g, int depth, List<Step> path) {
        long left = alive & s.goal;
        if (left == 0) {
            // The room is done at its last death, not when he stands again after it: the last cast's dash and fall
            // after its blast cost nothing.
            Step last = path.get(path.size() - 1);
            int v = g - (last.kind() == CAST ? Math.max(0, last.ticks() - CAST_KILL_AT) : 0);
            if (v < s.best) {
                s.best = v;
                s.bestPath = new ArrayList<>(path);
                if (s.first < 0) {
                    s.first = v;
                }
            }
            return;
        }
        if (g + s.minStep >= s.best || s.out()) {
            return;
        }
        s.nodes++;
        long key = posKey(pos) * 31 + alive;
        Integer had = s.seen.get(key);
        if (had != null && had <= g) {
            return;
        }
        s.seen.put(key, g);
        List<Step[]> moves = moves(s, pos, alive, depth == 0);
        for (Step[] m : moves) {
            long kills = 0;
            int ticks = 0;
            for (Step st : m) {
                kills |= st.kills();
                ticks += st.ticks();
            }
            int size = path.size();
            for (Step st : m) {
                path.add(st);
            }
            dfs(s, m[m.length - 1].at(), alive & ~kills, g + ticks, depth + 1, path);
            while (path.size() > size) {
                path.remove(path.size() - 1);
            }
            if (s.out()) {
                return;
            }
        }
    }

    /** The moves from {@code pos}, best ticks per kill first. An etherwarp is two steps: the trip, then the cast there. */
    private static List<Step[]> moves(Search s, Vec3 pos, long alive, boolean root) {
        Job j = s.job;
        List<Step[]> out = new ArrayList<>();
        long here = blast(pos, j.boxes) & alive;
        if (root && here != 0) {
            out.add(new Step[]{new Step(IN_PLACE, 0f, 90f, pos, pos, null, alive, here, j.inPlaceTicks, 0)});
        }
        if (j.casts) {
            List<Step> casts = casts(s, pos, alive);
            for (int i = 0; i < casts.size() && i < CAST_KEEP; i++) {
                out.add(new Step[]{casts.get(i)});
            }
        }
        // Stands, grouped by what they kill of what is left: per group the fewest warps from here among its nearest few
        // (the old trip's "one warp onto a block that does the same job"), the groups killing most first.
        BlockPos feetBlock = BlockPos.containing(pos).below();
        Map<Long, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < s.stands.length; i++) {
            long k = s.standMasks[i] & alive;
            if (k != 0 && BlockPos.of(s.stands[i]).compareTo(feetBlock) != 0) {
                groups.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
            }
        }
        List<Long> keys = new ArrayList<>(groups.keySet());
        keys.sort((x, y) -> Long.bitCount(y) != Long.bitCount(x) ? Long.bitCount(y) - Long.bitCount(x)
                : Double.compare(nearest(groups.get(x), s, pos), nearest(groups.get(y), s, pos)));
        for (int g = 0; g < keys.size() && g < ETHER_KEEP; g++) {
            long k = keys.get(g);
            List<Integer> members = groups.get(k);
            members.sort((x, y) -> Double.compare(dist2(pos, s.stands[x]), dist2(pos, s.stands[y])));
            BlockPos best = null;
            int bestW = Integer.MAX_VALUE;
            for (int m = 0; m < members.size() && m < STAND_TRIES && bestW > 1; m++) {
                BlockPos stand = BlockPos.of(s.stands[members.get(m)]);
                int w = warps(pos, stand);
                if (w < bestW) {
                    bestW = w;
                    best = stand;
                }
            }
            int trip = j.trip[Math.min(bestW, j.trip.length - 1)];
            Vec3 top = new Vec3(best.getX() + 0.5, best.getY() + 1.0, best.getZ() + 0.5);
            out.add(new Step[]{new Step(ETHER, 0f, 90f, pos, top, best, alive, 0L, trip + j.killTicks - 1, 0),
                    new Step(IN_PLACE, 0f, 90f, top, top, null, alive, k, 1, 0)});
        }
        out.sort((a, b) -> {
            double ra = perKill(a);
            double rb = perKill(b);
            return Double.compare(ra, rb);
        });
        return out;
    }

    /** The old cover plan's blocks (indices into the stands) for what is left from {@code pos}: the fewest that reach
     *  every live target some block reaches, in the order RepositionPlanner tours them. */
    private static int[] coverOrder(Search s, Vec3 pos, long alive) {
        int[] bit = new int[64];
        int n = 0;
        long reached = 0;
        for (long m : s.standMasks) {
            reached |= m & alive;
        }
        for (int i = 0; i < 63; i++) {
            bit[i] = (reached & (1L << i)) != 0 ? n++ : -1;
        }
        if (n == 0) {
            return new int[0];
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < s.stands.length; i++) {
            if ((s.standMasks[i] & alive) != 0) {
                idx.add(i);
            }
        }
        long[] covers = new long[idx.size()];
        double[][] spots = new double[idx.size()][];
        for (int k = 0; k < covers.length; k++) {
            int i = idx.get(k);
            long m = s.standMasks[i] & alive;
            for (int b = 0; b < 63; b++) {
                if ((m & (1L << b)) != 0) {
                    covers[k] |= 1L << bit[b];
                }
            }
            BlockPos p = BlockPos.of(s.stands[i]);
            spots[k] = new double[]{p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5};
        }
        RepositionPlanner.Plan plan = RepositionPlanner.plan(covers, n, spots, new double[]{pos.x, pos.y, pos.z},
                COVER_NANOS);
        if (plan == null) {
            return new int[0];
        }
        int[] out = new int[plan.spots().length];
        for (int k = 0; k < out.length; k++) {
            out[k] = idx.get(plan.spots()[k]);
        }
        return out;
    }

    /** The old preferOneWarp: a stand reaching every live target stand {@code o} reaches that one warp from {@code pos}
     *  lands on, nearest first among the first few; -1 when none. */
    private static int oneWarpFor(Search s, Vec3 pos, int o, long alive) {
        long need = s.standMasks[o] & alive;
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < s.stands.length; i++) {
            if (i != o && (s.standMasks[i] & need) == need) {
                cand.add(i);
            }
        }
        cand.sort((x, y) -> Double.compare(dist2(pos, s.stands[x]), dist2(pos, s.stands[y])));
        BlockPos feetBlock = BlockPos.containing(pos).below();
        for (int k = 0; k < cand.size() && k < ONE_WARP_TRIES; k++) {
            BlockPos b = BlockPos.of(s.stands[cand.get(k)]);
            if (!b.equals(feetBlock) && warps(pos, b) == 1) {
                return cand.get(k);
            }
        }
        return -1;
    }

    private static double perKill(Step[] m) {
        long k = 0;
        int t = 0;
        for (Step s : m) {
            k |= s.kills();
            t += s.ticks();
        }
        return t / (double) Math.max(1, Long.bitCount(k));
    }

    private static double nearest(List<Integer> members, Search s, Vec3 pos) {
        double d = Double.MAX_VALUE;
        for (int i : members) {
            d = Math.min(d, dist2(pos, s.stands[i]));
        }
        return d;
    }

    /** The casts across from {@code pos} (the fan and the aimed looks), the cheapest per set of kills, most kills first. */
    private static List<Step> casts(Search s, Vec3 pos, long alive) {
        Job j = s.job;
        List<Land> lands = new ArrayList<>(aimed(s, pos, alive));
        Land[] fan = fan(s, pos);
        if (fan != null) {
            lands.addAll(java.util.Arrays.asList(fan));
        }
        Map<Long, Step> byMask = new HashMap<>();
        for (Land l : lands) {
            long k = blast(l.at, j.boxes) & alive;
            if (k == 0) {
                continue;
            }
            if (l.fall < 0) {
                l.fall = Math.min(20, WeaponReach.fallTicks(j.level, j.player, l.at));
            }
            int t = j.link + l.fall;
            Step had = byMask.get(k);
            if (had == null || t < had.ticks()) {
                byMask.put(k, new Step(CAST, l.yaw, l.pitch, pos, l.at, null, alive, k, t, l.fall));
            }
        }
        List<Step> casts = new ArrayList<>(byMask.values());
        casts.sort((x, y) -> Long.bitCount(y.kills()) != Long.bitCount(x.kills())
                ? Long.bitCount(y.kills()) - Long.bitCount(x.kills()) : x.ticks() - y.ticks());
        return casts;
    }

    /** The looks tried at each target in reach: straight at it, 12 degrees either side, and level. */
    private static final float[] AIM_YAWS = {0f, -12f, 12f};
    private static final float[] AIM_PITCHES = {Float.NaN, 0f};
    private static final int AIM_TARGETS = 63;
    private static final int AIM_CACHE = 2048;
    /** The aimed landings from a feet position at one target (they depend on the blocks and that target alone). */
    private static final Map<Long, Land[]> AIMS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Land[]> e) {
            return size() > AIM_CACHE;
        }
    };

    /** The old hop's looks at each of the nearest few live targets within a dash and a blast: the fan's 15-degree steps
     *  miss a lone mob a short hop away. Kept per feet position and target for the room. */
    private static List<Land> aimed(Search s, Vec3 pos, long alive) {
        Job j = s.job;
        List<Land> out = new ArrayList<>();
        Vec3 eye = pos.add(0, WeaponReach.STANDING_EYE, 0);   // casts go standing (WeaponReach.hyperionHop)
        double reach = WeaponReach.HYPERION_TELEPORT + WeaponReach.HYPERION_RADIUS + 1;
        long left = alive & s.goal;
        for (int n = 0; n < AIM_TARGETS && left != 0; n++) {
            int t = -1;
            double td = reach * reach;
            for (int i = 0; i < j.boxes.length && i < 63; i++) {
                double d;
                if ((left & (1L << i)) != 0 && (d = j.boxes[i].getCenter().distanceToSqr(eye)) <= td) {
                    td = d;
                    t = i;
                }
            }
            if (t < 0) {
                break;
            }
            left &= ~(1L << t);
            out.addAll(java.util.Arrays.asList(aimedAt(s, pos, eye, t)));
        }
        return out;
    }

    private static Land[] aimedAt(Search s, Vec3 pos, Vec3 eye, int t) {
        long key = (posKey(pos) * 31 + BlockPos.containing(s.job.boxes[t].getCenter()).asLong()) * 64 + t;
        Land[] had = AIMS.get(key);
        if (had != null) {
            return had;
        }
        Job j = s.job;
        List<Land> out = new ArrayList<>();
        TeleportUtils.Rotation direct = TeleportUtils.getDirection(eye, j.boxes[t].getCenter());
        for (float dy : AIM_YAWS) {
            for (float p : AIM_PITCHES) {
                float yaw = direct.yaw() + dy;
                float pitch = net.minecraft.util.Mth.clamp(Float.isNaN(p) ? direct.pitch() : p, -89f, 89f);
                Vec3 land = com.killer560.hub.roomsim.SimAbilities.dashTarget(j.level, j.player, pos,
                        TeleportUtils.getLook(yaw, pitch), WeaponReach.HYPERION_TELEPORT);
                if (land != null && land.distanceToSqr(pos) >= 1.0) {
                    out.add(new Land(yaw, pitch, land));
                }
            }
        }
        had = out.toArray(new Land[0]);
        AIMS.put(key, had);
        return had;
    }

    private static Land[] fan(Search s, Vec3 feet) {
        long key = posKey(feet);
        Land[] f = FANS.get(key);
        if (f != null) {
            return f;
        }
        if (s.fans >= MAX_FANS) {
            return null;
        }
        s.fans++;
        Job j = s.job;
        List<Land> out = new ArrayList<>(WeaponReach.FAN_YAWS * WeaponReach.FAN_PITCHES.length);
        for (int y = 0; y < WeaponReach.FAN_YAWS; y++) {
            float yaw = y * (360f / WeaponReach.FAN_YAWS);
            for (float pitch : WeaponReach.FAN_PITCHES) {
                Vec3 land = com.killer560.hub.roomsim.SimAbilities.dashTarget(j.level, j.player, feet,
                        TeleportUtils.getLook(yaw, pitch), WeaponReach.HYPERION_TELEPORT);
                if (land != null && land.distanceToSqr(feet) >= 1.0) {
                    out.add(new Land(yaw, pitch, land));
                }
            }
        }
        f = out.toArray(new Land[0]);
        FANS.put(key, f);
        return f;
    }

    private static int warps(Vec3 from, BlockPos stand) {
        long key = posKey(from) * 961 + stand.asLong();
        Integer w = WARPS.get(key);
        if (w == null) {
            w = AutoClearFeature.warpsTo(from, stand);
            WARPS.put(key, w);
        }
        return w;
    }

    static long blast(Vec3 feet, AABB[] boxes) {
        long m = 0;
        for (int i = 0; i < boxes.length && i < 63; i++) {
            if (WeaponReach.hyperionHits(feet, boxes[i])) {
                m |= 1L << i;
            }
        }
        return m;
    }

    private static double dist2(Vec3 p, long stand) {
        BlockPos b = BlockPos.of(stand);
        double dx = b.getX() + 0.5 - p.x;
        double dy = b.getY() + 1.0 - p.y;
        double dz = b.getZ() + 0.5 - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** A feet position to an eighth of a block, packed. */
    static long posKey(Vec3 p) {
        return ((Math.round(p.x * 8) & 0x1FFFFFL) << 42) | ((Math.round(p.y * 8) & 0x1FFFFFL) << 21)
                | (Math.round(p.z * 8) & 0x1FFFFFL);
    }
}
