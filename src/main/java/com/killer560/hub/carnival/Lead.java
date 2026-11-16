package com.killer560.hub.carnival;

import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.PingTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a moving target will be when a dart or a cast gets there, worked out on its own (killer560 11-14: "they shouldnt
 * have an aim ahead amount moreso an auto detect style that auto figures it out"). Each entity's horizontal velocity is
 * smoothed from its position last tick. A dart's lead is that velocity times (its flight time at the MEASURED dart speed +
 * the round trip), plus a lead and a height learned from where each dart really passed its target; the aim is raised by
 * the dart's drop over that flight. A cast's lead uses the measured flight time of the bobber (cast to settle) and a learned
 * range ratio (where it landed against where it was aimed). Every learned value is bounded and starts over at each game
 * start and world change ({@link #clear}); the velocity maps are bounded by what is on screen ({@link #retain}).
 */
public final class Lead {

    /** Dart speed before the first one is measured (his 11-14 games: darts first seen at 3.8-4.4 blocks a tick). */
    static final double DART_SPEED = 4.0;
    /** Dart drop, blocks a tick squared (an arrow's 0.05). */
    static final double DART_GRAVITY = 0.05;
    /** The server acts on a use at its next tick, so a dart leaves one tick after the click. */
    static final int USE_TICK = 1;
    static final double LEAD_LIMIT = 10;
    static final double HEIGHT_LIMIT = 1;
    /** A cast bobber leaves the eye 0.1 lower at 1.5 blocks a tick along the look (Hypixel's 1.8 fishing hook). */
    static final double HOOK_SPEED = 1.5;
    static final double HOOK_DROP = 0.1;
    /** The learned range error is kept within this many blocks. */
    static final double RANGE_LIMIT = 2;
    private static final int NONE = Integer.MIN_VALUE;
    private static final Map<Integer, Vec3> LAST = new HashMap<>();
    private static final Map<Integer, Long> LAST_TICK = new HashMap<>();
    private static final Map<Integer, Vec3> VEL = new HashMap<>();
    /** The tick each mob last turned (a step more than {@link #TURN_DEGREES} off its smoothed heading, or a start or
     *  stop): a dart led along the old heading misses, and a miss read off it teaches the lead nothing true. */
    private static final Map<Integer, Long> TURNED = new HashMap<>();
    static final double TURN_DEGREES = 35;
    static final int STEADY_TICKS = 8;
    /** No new server position for this long: it stands still. */
    static final int STILL_TICKS = 8;

    private static double dartSpeed = DART_SPEED;
    private static double leadBias;
    private static double heightBias;
    private static double castRange;
    private static int learned;
    private static int pingTicks;
    private static long pingAtMs;

    // The dart in flight: our last shot, the arrow taken as its dart, and its closest pass to the target so far.
    private static int shotTarget = NONE;
    private static Vec3 shotEye;
    private static long shotTick;
    private static int dartId = NONE;
    private static Vec3 dartPrev;
    private static Vec3 dartNow;
    private static int dartAge;
    private static boolean dartSeen;
    private static Vec3 targetNow;
    private static Vec3 shotOffset = Vec3.ZERO;
    private static double bestSq;
    private static Vec3 bestOffset;

    // The cast in flight: where the hook physics said it would come to rest, from where, and the water it lands in.
    private static double castPredicted = -1;
    private static Vec3 castEye;
    private static double castSurface;

    private Lead() {
    }

    /** Call once a tick per entity with its {@link #serverPos}; {@code weight} is how much this tick's step counts in the
     *  smoothed velocity (zombies 0.5, they turn corners; fish 0.25, they wobble). */
    static void sample(int id, Vec3 pos, double weight) {
        long now = ActionGate.currentTick();
        Vec3 prev = LAST.get(id);
        if (prev != null && prev.equals(pos)) {
            return; // no new server position this tick (a mob's arrive every few ticks)
        }
        Long since = LAST_TICK.put(id, now);
        LAST.put(id, pos);
        if (prev == null) {
            return;
        }
        // The step over the ticks it took: per tick, the steps read 0, 0, 0.36, 0, 0, 0.36 for a zombie walking 0.12.
        double dt = Math.max(1, Math.min(STILL_TICKS, now - since));
        Vec3 d = new Vec3((pos.x - prev.x) / dt, 0, (pos.z - prev.z) / dt);
        Vec3 old = VEL.get(id);
        if (old != null && turned(old, d)) {
            TURNED.put(id, now);
        }
        VEL.put(id, old == null ? d : old.scale(1 - weight).add(d.scale(weight)));
        if (weight < 0.5) {
            track(id, pos, now); // fish (zombies walk straight lines)
        }
    }

    /** Whether step {@code d} turns away from heading {@code v} (both per tick): his 11-14 pincarn6 game lost darts to
     *  zombies that reversed mid-flight (zombie 138866 between shots 6 and 8). */
    static boolean turned(Vec3 v, Vec3 d) {
        double a = Math.hypot(v.x, v.z);
        double b = Math.hypot(d.x, d.z);
        if (a < 0.03 && b < 0.03) {
            return false;
        }
        if (a < 0.03 || b < 0.03) {
            return true; // started or stopped
        }
        // Heading only: a step's speed jitters with when the update lands (two in one client tick read 3-5x the speed;
        // the speed rule fired so often that 3826 got 4 darts in 600 ticks).
        return (v.x * d.x + v.z * d.z) / (a * b) < Math.cos(Math.toRadians(TURN_DEGREES));
    }

    /** It has held its heading and speed for {@link #STEADY_TICKS} ticks. */
    static boolean steady(int id) {
        Long t = TURNED.get(id);
        return t == null || ActionGate.currentTick() - t >= STEADY_TICKS;
    }

    /** Where the server has {@code id} now: its last position carried on by the velocity for the ticks since it came, so
     *  the aim glides with the target instead of jumping at every update (the look could not settle inside a zombie's one
     *  degree at 15 blocks). */
    static Vec3 now(int id, Vec3 last) {
        Long at = LAST_TICK.get(id);
        long dt = at == null ? 0 : Math.min(STILL_TICKS, ActionGate.currentTick() - at);
        Vec3 v = velocity(id);
        return dt <= 0 || v.lengthSqr() < 1e-8 ? last : last.add(v.x * dt, 0, v.z * dt);
    }

    /** Where the server last put {@code e}: the client draws a mob up to three ticks behind that while it slides there,
     *  which read as darts landing about two ticks behind every walking zombie (his 11-14 game: the learned lead rose to
     *  +1.5..2.4 ticks). */
    static Vec3 serverPos(Entity e) {
        net.minecraft.world.entity.InterpolationHandler h = e.getInterpolation();
        return h != null && h.hasActiveInterpolation() ? com.killer560.hub.compat.McEntities.interpolationTarget(h)
                : e.position();
    }

    /** Its velocity is known (two server positions seen), or it has stood still since it was first seen. A zombie that
     *  just appeared has no velocity yet, so a dart then gets no lead and misses a walking one. */
    static boolean ready(int id) {
        Long at = LAST_TICK.get(id);
        return VEL.containsKey(id) || at != null && ActionGate.currentTick() - at >= 4;
    }

    static Vec3 velocity(int id) {
        Vec3 v = VEL.get(id);
        Long at = LAST_TICK.get(id);
        return v == null || at == null || ActionGate.currentTick() - at > STILL_TICKS ? Vec3.ZERO : v;
    }

    static void retain(Set<Integer> alive) {
        LAST.keySet().retainAll(alive);
        LAST_TICK.keySet().retainAll(alive);
        VEL.keySet().retainAll(alive);
        TURNED.keySet().retainAll(alive);
        TRACKS.keySet().retainAll(alive);
    }

    /** Forgets every sample and every learned value (game start, world change). */
    static void clear() {
        LAST.clear();
        LAST_TICK.clear();
        VEL.clear();
        TURNED.clear();
        TRACKS.clear();
        dartSpeed = DART_SPEED;
        leadBias = 0;
        heightBias = 0;
        castRange = 0;
        lastMeet = 0;
        learned = 0;
        shotTarget = NONE;
        dartId = NONE;
        castPredicted = -1;
    }

    /** The round trip in ticks, read at most once a second. */
    static int pingTicks() {
        long now = System.currentTimeMillis();
        if (now - pingAtMs > 1000) {
            pingAtMs = now;
            pingTicks = (Math.max(0, PingTracker.automationMs()) + 25) / 50;
        }
        return pingTicks;
    }

    // ---- darts (Zombie Shootout) -------------------------------------------------------------------------------------

    /** Where to aim a dart at entity {@code id}'s {@code point} (a lamp: any id with no samples). */
    static Vec3 dartAim(int id, Vec3 point, Vec3 eye) {
        return dartAim(velocity(id), point, eye, pingTicks());
    }

    static Vec3 dartAim(Vec3 v, Vec3 point, Vec3 eye, int ping) {
        Vec3 p = point;
        if (v.lengthSqr() > 1e-6) {
            double lead = Math.max(0, Math.min(40, eye.distanceTo(point) / dartSpeed + ping + USE_TICK + leadBias));
            p = point.add(v.x * lead, 0, v.z * lead);
        }
        double t = eye.distanceTo(p) / dartSpeed;
        return p.add(0, 0.5 * DART_GRAVITY * t * t + heightBias, 0);
    }

    /** Ticks a dart takes to reach {@code point}, plus the round trip. */
    static int dartTicks(Vec3 eye, Vec3 point) {
        return (int) Math.ceil(eye.distanceTo(point) / dartSpeed) + pingTicks() + USE_TICK;
    }

    /** We just shot at entity {@code targetId} (NONE for a lamp, which teaches nothing). */
    static void noteShot(int targetId, Vec3 eye, long tick, Vec3 aimOffset) {
        shotOffset = aimOffset;
        shotTarget = targetId;
        shotEye = eye;
        shotTick = tick;
        dartId = NONE;
        bestOffset = null;
        bestSq = Double.MAX_VALUE;
    }

    /** An arrow in the world this tick (from the target scan). */
    static void seeArrow(Entity a, long tick) {
        if (shotTarget == NONE) {
            return;
        }
        Vec3 p = a.position();
        if (dartId == NONE && tick - shotTick <= 10 && a.tickCount < 4 && p.distanceToSqr(shotEye) < 49) {
            dartId = a.getId();
            dartNow = p;
            dartAge = 0;
        }
        if (a.getId() == dartId) {
            dartSeen = true;
            dartPrev = dartNow;
            dartNow = p;
            dartAge++;
            double step = dartNow.distanceTo(dartPrev);
            if (dartAge >= 2 && dartAge <= 4 && step > 2 && step < 7) {
                dartSpeed = Math.max(2.5, Math.min(6, dartSpeed * 0.7 + step * 0.3));
            }
        }
    }

    /** The shot target's center this tick, if it is still there. */
    static void seeTarget(int id, Vec3 center) {
        if (id == shotTarget) {
            targetNow = center;
        }
    }

    /** Ends a tick of dart tracking: keeps the closest pass and learns from it once the dart has passed or is gone. */
    static void endTick(long tick) {
        if (shotTarget == NONE) {
            return;
        }
        boolean seen = dartSeen;
        // Measured from the spot the dart was aimed at (the rolled spot on the body), not the middle.
        Vec3 target = targetNow == null ? null : targetNow.add(shotOffset);
        dartSeen = false;
        targetNow = null;
        if (target == null) {
            shotTarget = NONE; // the target is gone (one dart kills): a hit, or out of range
            return;
        }
        if (dartId == NONE) {
            if (tick - shotTick > 10) {
                shotTarget = NONE;
            }
            return;
        }
        if (seen && dartPrev != null) {
            // The closest point of this tick's dart path to the target's center.
            Vec3 seg = dartNow.subtract(dartPrev);
            double len2 = seg.lengthSqr();
            double t = len2 < 1e-9 ? 1 : Math.max(0, Math.min(1, target.subtract(dartPrev).dot(seg) / len2));
            Vec3 off = dartPrev.add(seg.scale(t)).subtract(target);
            double d2 = off.lengthSqr();
            if (d2 < bestSq) {
                bestSq = d2;
                bestOffset = off;
                return; // still closing (or just passed inside this tick: the next tick's path is farther)
            }
        }
        // The dart got farther than its closest pass, or is gone without the target dying: a miss to learn from - unless
        // the target turned while the dart flew, which no lead could know.
        Long turn = TURNED.get(shotTarget);
        if (bestOffset != null && bestSq < 9 && (turn == null || turn < shotTick)) {
            learn(bestOffset, velocity(shotTarget));
        }
        shotTarget = NONE;
    }

    /** One dart's miss: {@code off} is the dart minus the target at its closest pass. */
    static void learn(Vec3 off, Vec3 v) {
        double sp = Math.hypot(v.x, v.z);
        if (sp > 0.03) {
            double along = (off.x * v.x + off.z * v.z) / sp; // + = the dart passed in front of the target
            // One dart teaches at most 1 tick, a third of its miss: at 2 ticks and half, his pincarn6 game's lead swung
            // between -1.9 and +1.9 every few darts.
            double step = Math.max(-1, Math.min(1, -0.35 * along / sp));
            leadBias = Math.max(-LEAD_LIMIT, Math.min(LEAD_LIMIT, leadBias + step));
        }
        heightBias = Math.max(-HEIGHT_LIMIT, Math.min(HEIGHT_LIMIT, heightBias - 0.35 * off.y));
        learned++;
    }

    // ---- casts (Catch a Fish) ----------------------------------------------------------------------------------------

    /**
     * Where a bobber cast at {@code pitch} degrees (down positive) from eye height {@code eyeY} comes to rest on water whose
     * surface is at {@code surface}: {range from the eye, ticks}. Hypixel's 1.8 hook each tick moves, then keeps 92% of its
     * speed, gains 0.04 up times (2 x the part of its 0.25-tall box under the surface - 1), and in water keeps a further
     * 90% and 80% of its fall. So a cast flies, splashes and glides on for 3.5-4 blocks before it stops: his 11-14 casts
     * aimed at the fish itself landed 1.9-4.6 blocks beyond it, and this model put their rest points within 0.2-0.7 of
     * where they really stopped (killer560s-mod-logs/carnfix3-bobsim.py). No allocation (it runs a few hundred times a tick
     * while aiming).
     */
    static void castRest(double pitch, double eyeY, double surface, double[] out) {
        int n = castPath(pitch, eyeY, surface);
        out[0] = PATH_R[n];
        out[1] = n;
    }

    static final int PATH_N = 120;
    /** The last {@link #castPath}: range from the eye and height at each tick (index 0 = the spawn). */
    private static final double[] PATH_R = new double[PATH_N + 1];
    private static final double[] PATH_Y = new double[PATH_N + 1];

    /** Runs the hook from a cast at {@code pitch} into {@link #PATH_R}/{@link #PATH_Y}; returns its last tick (at rest,
     *  or far under the surface when it never reaches water). No allocation. */
    private static int castPath(double pitch, double eyeY, double surface) {
        double p = Math.toRadians(pitch);
        double vh = HOOK_SPEED * Math.cos(p);
        double vy = -HOOK_SPEED * Math.sin(p);
        double y = eyeY - HOOK_DROP;
        double r = 0;
        boolean wet = false;
        int t = 0;
        PATH_R[0] = 0;
        PATH_Y[0] = y;
        while (t < PATH_N) {
            r += vh;
            y += vy;
            t++;
            PATH_R[t] = r;
            PATH_Y[t] = y;
            double under = Math.max(0, Math.min(1, (surface - y) / 0.25));
            double keep = 0.92;
            vy += 0.04 * (2 * under - 1);
            if (under > 0) {
                wet = true;
                keep *= 0.9;
                vy *= 0.8;
            }
            vh *= keep;
            vy *= keep;
            if (wet && vh < 0.02) {
                break;
            }
            if (!wet && y < surface - 30) {
                break; // never reaches water
            }
        }
        return t;
    }

    private static final double[] REST = new double[2];

    static final double PITCH_LO = -30;
    static final double PITCH_STEP = 1.5;
    static final int PITCHES = 74;
    /** The 1.8 hook's entity trace grows a mob's box by this much. */
    static final double HOOK_REACH = 0.3;
    /** Ticks after the earliest meeting a pitch may meet and still count as crossing the same box. */
    static final int BAND = 2;
    private static final int[] MEET = new int[PITCHES];
    private static double planPitch = Double.NaN;
    private static int planMeet = -1;
    private static double lastMeet;

    /**
     * Where to look to cast at fish {@code id} (its server box {@code box} now, standing at {@code at}) from {@code eye}
     * onto water at {@code surface}, {@code extra} blocks long (a rolled spread); null when no cast can reach it.
     *
     * <p>The hook catches a fish its path CROSSES while it still moves: all 8 of his pincarn6 hooks came 7-17 ticks after
     * the cast, in flight or glide, none after the bobber settled. So the plan finds the pitch whose path meets the fish's
     * box (grown {@link #HOOK_REACH}, as the 1.8 hook's entity trace grows it) at the tick the fish gets there - its spot
     * {@link #ahead} that many ticks plus the round trip - the earliest meeting first, and only along a path nothing solid
     * stops before it (about 25 of his 38 casts struck the slab at the bank, thrown 45-75 degrees down at a spot under the
     * water). Two passes settle the meeting tick.
     */
    static Vec3 castPlan(net.minecraft.world.level.Level level, int id, Vec3 at, net.minecraft.world.phys.AABB box, Vec3 eye,
                         double surface, double extra) {
        int ping = pingTicks();
        double t = lastMeet > 0 ? lastMeet : 10;
        Vec3 p = at;
        planMeet = -1;
        for (int pass = 0; pass < 3; pass++) {
            p = ahead(id, at, t + ping + USE_TICK);
            double dx = p.x - eye.x;
            double dz = p.z - eye.z;
            double len = Math.max(1e-6, Math.hypot(dx, dz));
            double range = len - castRange + extra;
            double half = box.getXsize() / 2 + HOOK_REACH;
            double lo = box.minY - HOOK_REACH;
            double hi = box.maxY + HOOK_REACH;
            for (int i = 0; i < PITCHES; i++) {
                int n = castPath(PITCH_LO + i * PITCH_STEP, eye.y, surface);
                MEET[i] = crossing(n, range - half, range + half, lo, hi);
            }
            // The earliest meeting whose path is clear; a blocked one is struck off and the next tried. Of the pitches that
            // meet within BAND ticks of the earliest, the middle one: the earliest alone only grazes the box's near corner,
            // so any error in the fish's spot missed (3823: 13 catches for 21 casts).
            planMeet = -1;
            while (true) {
                int first = -1;
                for (int i = 0; i < PITCHES; i++) {
                    if (MEET[i] > 0 && (first < 0 || MEET[i] < MEET[first])) {
                        first = i;
                    }
                }
                if (first < 0) {
                    return null;
                }
                int bandLo = PITCHES;
                int bandHi = -1;
                for (int i = 0; i < PITCHES; i++) {
                    if (MEET[i] > 0 && MEET[i] <= MEET[first] + BAND) {
                        bandLo = Math.min(bandLo, i);
                        bandHi = Math.max(bandHi, i);
                    }
                }
                int best = first;
                for (int i = bandLo; i <= bandHi; i++) {
                    if (MEET[i] > 0 && MEET[i] <= MEET[first] + BAND
                            && Math.abs(2 * i - bandLo - bandHi) < Math.abs(2 * best - bandLo - bandHi)) {
                        best = i;
                    }
                }
                double pitch = PITCH_LO + best * PITCH_STEP;
                castPath(pitch, eye.y, surface);
                if (clear(level, eye, dx / len, dz / len, MEET[best])) {
                    planMeet = MEET[best];
                    planPitch = pitch;
                    break;
                }
                MEET[best] = -1;
            }
            t = planMeet;
        }
        lastMeet = planMeet;
        double dx = p.x - eye.x;
        double dz = p.z - eye.z;
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        double rad = Math.toRadians(planPitch);
        return new Vec3(eye.x + dx / len * 10 * Math.cos(rad), eye.y - 10 * Math.sin(rad), eye.z + dz / len * 10 * Math.cos(rad));
    }

    /** The first tick of the last {@link #castPath} (of {@code n} ticks) whose step crosses the box from range {@code r0}
     *  to {@code r1} and height {@code y0} to {@code y1}, or -1 (a segment against a rectangle, Liang-Barsky). */
    private static int crossing(int n, double r0, double r1, double y0, double y1) {
        for (int i = 1; i <= n; i++) {
            double ar = PATH_R[i - 1];
            double ay = PATH_Y[i - 1];
            double dr = PATH_R[i] - ar;
            double dy = PATH_Y[i] - ay;
            double t0 = 0;
            double t1 = 1;
            double[] pq = CLIP;
            pq[0] = -dr; pq[1] = ar - r0;
            pq[2] = dr; pq[3] = r1 - ar;
            pq[4] = -dy; pq[5] = ay - y0;
            pq[6] = dy; pq[7] = y1 - ay;
            boolean in = true;
            for (int k = 0; k < 8 && in; k += 2) {
                double pk = pq[k];
                double qk = pq[k + 1];
                if (Math.abs(pk) < 1e-12) {
                    in = qk >= 0;
                } else {
                    double u = qk / pk;
                    if (pk < 0) {
                        t0 = Math.max(t0, u);
                    } else {
                        t1 = Math.min(t1, u);
                    }
                    in = t0 <= t1;
                }
            }
            if (in) {
                return i;
            }
            if (ar > r1) {
                return -1; // already past it
            }
        }
        return -1;
    }

    private static final double[] CLIP = new double[8];
    private static final net.minecraft.core.BlockPos.MutableBlockPos PATH_POS = new net.minecraft.core.BlockPos.MutableBlockPos();

    /** Whether the last {@link #castPath} runs to tick {@code ticks} along {@code (ux, uz)} from {@code eye} without its
     *  box meeting anything solid (six samples a tick, each at the box's center and leading edge, with a margin over the
     *  0.25 box: a bank corner clipped between samples stopped 2 casts on the bank in 3828). */
    private static boolean clear(net.minecraft.world.level.Level level, Vec3 eye, double ux, double uz, int ticks) {
        for (int i = 1; i <= ticks; i++) {
            for (int s = 1; s <= 6; s++) {
                double f = s / 6.0;
                double r = PATH_R[i - 1] + (PATH_R[i] - PATH_R[i - 1]) * f;
                double y = PATH_Y[i - 1] + (PATH_Y[i] - PATH_Y[i - 1]) * f;
                if (solidAt(level, eye.x + ux * r, y, eye.z + uz * r) || solidAt(level, eye.x + ux * (r + 0.2), y, eye.z + uz * (r + 0.2))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean solidAt(net.minecraft.world.level.Level level, double x, double y, double z) {
        net.minecraft.core.BlockPos.MutableBlockPos m = PATH_POS;
        m.set(net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(y - 0.2), net.minecraft.util.Mth.floor(z));
        for (int k = 0; k < 2; k++, m.move(net.minecraft.core.Direction.UP)) {
            net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(m).getCollisionShape(level, m);
            if (!shape.isEmpty() && y - 0.2 < m.getY() + shape.max(net.minecraft.core.Direction.Axis.Y)
                    && y + 0.2 > m.getY() + shape.min(net.minecraft.core.Direction.Axis.Y)) {
                return true;
            }
        }
        return false;
    }

    /** The last plan's {pitch, meeting tick} (NaN, -1 before one). */
    static double[] lastPlan() {
        return new double[]{planPitch, planMeet};
    }

    // ---- circling (Catch a Fish) ---------------------------------------------------------------------------------------

    /** His pincarn6 fish swam circles of radius 2-5 at 0.012-0.039 radians a tick, either way (fits of their logged
     *  positions with residuals 0.03-0.6; killer560s-mod-logs/carnfix4-fishcircle.py), so a fish's spot some ticks ahead
     *  is read off a circle fitted to its last server positions while one fits, else its smoothed straight-line speed. */
    private static final class Track {
        final double[] x = new double[TRACK_N];
        final double[] z = new double[TRACK_N];
        final long[] t = new long[TRACK_N];
        int n;
        int head;
        long fitAt = Long.MIN_VALUE;
        boolean circle;
        double cx;
        double cz;
        double r;
        double w;
    }

    static final int TRACK_N = 12;
    static final int TRACK_TICKS = 60;
    private static final Map<Integer, Track> TRACKS = new HashMap<>();

    private static void track(int id, Vec3 pos, long now) {
        Track k = TRACKS.computeIfAbsent(id, i -> new Track());
        k.x[k.head] = pos.x;
        k.z[k.head] = pos.z;
        k.t[k.head] = now;
        k.head = (k.head + 1) % TRACK_N;
        k.n = Math.min(TRACK_N, k.n + 1);
        k.fitAt = Long.MIN_VALUE;
    }

    /** Fits {@code k}'s circle (algebraic least squares) once per tick: at least 5 positions in the last
     *  {@link #TRACK_TICKS} ticks, radius 1-8, rms off the circle under 0.15 and at least 30 degrees swept. */
    private static void fit(Track k, long now) {
        if (k.fitAt == now) {
            return;
        }
        k.fitAt = now;
        k.circle = false;
        int m = 0;
        double mx = 0;
        double mz = 0;
        for (int i = 0; i < k.n; i++) {
            int j = Math.floorMod(k.head - 1 - i, TRACK_N);
            if (now - k.t[j] > TRACK_TICKS) {
                break;
            }
            mx += k.x[j];
            mz += k.z[j];
            m++;
        }
        if (m < 5) {
            return;
        }
        mx /= m;
        mz /= m;
        double suu = 0, svv = 0, suv = 0, suuu = 0, svvv = 0, suvv = 0, svuu = 0;
        for (int i = 0; i < m; i++) {
            int j = Math.floorMod(k.head - 1 - i, TRACK_N);
            double u = k.x[j] - mx;
            double v = k.z[j] - mz;
            suu += u * u;
            svv += v * v;
            suv += u * v;
            suuu += u * u * u;
            svvv += v * v * v;
            suvv += u * v * v;
            svuu += v * u * u;
        }
        double det = suu * svv - suv * suv;
        if (Math.abs(det) < 1e-9) {
            return;
        }
        double uc = (0.5 * (suuu + suvv) * svv - 0.5 * (svvv + svuu) * suv) / det;
        double vc = (0.5 * (svvv + svuu) * suu - 0.5 * (suuu + suvv) * suv) / det;
        double r = Math.sqrt(uc * uc + vc * vc + (suu + svv) / m);
        double cx = uc + mx;
        double cz = vc + mz;
        if (r < 1 || r > 8) {
            return;
        }
        double res = 0;
        double swept = 0;
        double prev = 0;
        for (int i = m - 1; i >= 0; i--) {
            int j = Math.floorMod(k.head - 1 - i, TRACK_N);
            double e = Math.hypot(k.x[j] - cx, k.z[j] - cz) - r;
            res += e * e;
            double ang = Math.atan2(k.z[j] - cz, k.x[j] - cx);
            if (i < m - 1) {
                swept += net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(ang - prev));
            }
            prev = ang;
        }
        long span = k.t[Math.floorMod(k.head - 1, TRACK_N)] - k.t[Math.floorMod(k.head - m, TRACK_N)];
        if (Math.sqrt(res / m) > 0.15 || Math.abs(swept) < 30 || span < 10) {
            return;
        }
        k.circle = true;
        k.cx = cx;
        k.cz = cz;
        k.r = r;
        k.w = Math.toRadians(swept) / span;
    }

    /** Where {@code id}, now at {@code at}, will be {@code ticks} from now: along its fitted circle, else straight on. */
    static Vec3 ahead(int id, Vec3 at, double ticks) {
        Track k = TRACKS.get(id);
        if (k != null) {
            fit(k, ActionGate.currentTick());
            if (k.circle) {
                double a = Math.atan2(at.z - k.cz, at.x - k.cx) + k.w * ticks;
                return new Vec3(k.cx + k.r * Math.cos(a), at.y, k.cz + k.r * Math.sin(a));
            }
        }
        Vec3 v = velocity(id);
        return at.add(v.x * ticks, 0, v.z * ticks);
    }

    /** {cx, cz, r, w} of {@code id}'s fitted circle, or null while none fits (logging and the harness). */
    static double[] circle(int id) {
        Track k = TRACKS.get(id);
        if (k == null) {
            return null;
        }
        fit(k, ActionGate.currentTick());
        return k.circle ? new double[]{k.cx, k.cz, k.r, k.w} : null;
    }

    private static final net.minecraft.core.BlockPos.MutableBlockPos SURFACE_POS = new net.minecraft.core.BlockPos.MutableBlockPos();

    /** The water surface under {@code point} (top of the first water block at or below it, 8/9 of a block up), or the
     *  point's own height less 0.1 when no water is found within 6 blocks. */
    static double surface(net.minecraft.world.level.Level level, Vec3 point) {
        net.minecraft.core.BlockPos.MutableBlockPos m = SURFACE_POS;
        int x = net.minecraft.util.Mth.floor(point.x);
        int z = net.minecraft.util.Mth.floor(point.z);
        int top = net.minecraft.util.Mth.floor(point.y) + 1;
        for (int y = top; y > top - 7; y--) {
            m.set(x, y, z);
            if (level.getFluidState(m).is(net.minecraft.tags.FluidTags.WATER)) {
                return y + level.getFluidState(m).getHeight(level, m);
            }
        }
        return point.y - 0.1;
    }

    /** We just cast with this look ({@code pitch} down positive) from {@code eye} onto water at {@code surface}. */
    static void noteCast(float pitch, Vec3 eye, double surface) {
        castRest(pitch, eye.y, surface, REST);
        castPredicted = REST[0];
        castEye = eye;
        castSurface = surface;
    }

    /** The last cast's bobber came to rest at {@code landed}: learns how much farther (or shorter) casts go than the hook
     *  physics says. A bobber that stopped against a wall or on the shore (not floating) teaches nothing. */
    static void castSettled(Vec3 landed) {
        if (castPredicted < 0) {
            return;
        }
        double predicted = castPredicted;
        castPredicted = -1;
        // A floating bobber sits about 0.55 under the surface here (the client draws it lower than the server's float height).
        // One at 0.89 under, or higher than the surface, lies on the floor or against a wall or the shore and teaches nothing:
        // casts that stopped there set the learned range 1.4-2.2 off in the 11-15 lane (testkit 3823).
        if (landed.y > castSurface + 0.05 || landed.y < castSurface - 0.75 || onFace(landed.x) || onFace(landed.z)) {
            return; // not floating: shore, wall (a hook against a wall rests exactly on its face), floor, or the surface guess was off
        }
        double err = Math.hypot(landed.x - castEye.x, landed.z - castEye.z) - predicted;
        if (Math.abs(err) > 3) {
            return; // stopped by a wall (his pond's west wall stopped four casts) or hooked mid-glide
        }
        castRange = Math.max(-RANGE_LIMIT, Math.min(RANGE_LIMIT, castRange * 0.5 + err * 0.5));
        learned++;
    }

    private static boolean onFace(double v) {
        return Math.abs(v - Math.rint(v)) < 0.002;
    }

    // ---- harness -------------------------------------------------------------------------------------------------

    /** Harness: a target moving {@code vx} blocks a tick along x, {@code dist} blocks away, no ping; how far ahead in x
     *  the dart aim lands, and how far above the point. */
    public static double[] testDartAim(double vx, double dist) {
        Vec3 a = dartAim(new Vec3(vx, 0, 0), new Vec3(0, 0, dist), Vec3.ZERO, 0);
        return new double[]{a.x, a.y};
    }

    /** Harness: feeds {@code n} darts that each passed {@code ahead} blocks in front of a target walking 0.12 a tick and
     *  {@code low} blocks under it; returns {leadBias, heightBias, learned}. */
    public static double[] testLearn(int n, double ahead, double low) {
        for (int i = 0; i < n; i++) {
            learn(new Vec3(ahead, -low, 0), new Vec3(0.12, 0, 0));
        }
        return new double[]{leadBias, heightBias, learned};
    }

    /** Harness: feeds dart speed steps as an arrow's ticks 1..4 would (outliers ignored); returns the speed now. */
    public static double testDartSpeed(double step) {
        dartSpeed = Math.max(2.5, Math.min(6, step > 2 && step < 7 ? dartSpeed * 0.7 + step * 0.3 : dartSpeed));
        return dartSpeed;
    }

    /** Harness: {dartSpeed, leadBias, heightBias, last cast meeting tick, learned cast range error, learned}. */
    public static double[] calibrationForTest() {
        return new double[]{dartSpeed, leadBias, heightBias, lastMeet, castRange, learned};
    }

    /** Harness: {rest range, rest ticks} of a cast at {@code pitch} from eye height {@code eyeY} onto {@code surface}. */
    public static double[] testCastRest(double pitch, double eyeY, double surface) {
        double[] out = new double[2];
        castRest(pitch, eyeY, surface, out);
        return out;
    }

    /** Harness: {@link #clear}. */
    public static void clearForTest() {
        clear();
    }
}
