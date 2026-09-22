package com.killer560.hub.ap3;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans an align with DISCRETE keyboard input only. Every tick is one of the real key combinations - forward/back in
 * {-1, 0, +1}, strafe in {-1, 0, +1}, sneak on or off - through vanilla's own pipeline (the 0.98, the diagonal mapping,
 * the sneak multiplier); there is no fractional stick value anywhere. killer560's real Hypixel test of fda6ad4
 * (2026-09-21, 19:02): 14 server position corrections in 2 seconds during ONE align - the server reconstructs which
 * key combination could have produced each position delta and sets you back when none can, and "3% of S" is no key.
 * <p>
 * <b>Why keys alone cannot land to a thousandth, and what does.</b> With no more input, a press's whole slide is
 * {@code a / (1 - f)} of displacement whenever it is pressed (friction is linear), so the rest position of any key
 * sequence is a coarse lattice - 0.42 blocks a sneak tap at his speed - minus the tail the 0.003 zeroing rule cuts
 * off, which is only 0.0036-0.0066. A key sequence therefore lands within a few thousandths at best, exactly what
 * the other key-based mods manage (RSA public ~0.01, SeX5 ~0.005). The free continuous variable is the YAW the keys
 * are interpreted at: a W tap at yaw {@code t} slides {@code a / (1 - f)} in direction {@code t}, any direction, and the
 * yaw AP3 sends is already its own bounded-step running value (the walk lock). So the fine phase is: two taps, each
 * of a fixed size (sneak or full, straight key) and a chosen direction, turning at most {@link Model#yawStepCap}
 * degrees a tick between them - a triangle that closes on the point - solved on the EXACT simulation (zeroing tail
 * and all) with Newton's method to ~1e-7, or one tap when the remaining error is already one tap long. A human turning
 * while tapping is what the server sees. The nearest of W/A/S/D to the wanted direction is used so the turn stays
 * small. The coarse phase (running in, braking) is an exhaustive joint look-ahead over all 17 key combinations at the
 * current yaw, scored by the rest position.
 * <p>
 * No Minecraft types: the offline harness ({@code wave/AlignSimDiscrete.java}) runs this exact code against an
 * independent copy of the vanilla step. Every physics constant is {@link Ap3AlignMath}'s (26.1.2 bytecode); the
 * trig functions are injected so the game uses its own {@code Mth} table. Timing facts modelled (javap): sneak's
 * speed effect lags the key by ONE tick ({@code LocalPlayer.aiStep} computes {@code crouching} from
 * {@code isShiftKeyDown()} BEFORE {@code ClientInput.tick} installs the record); sprint continues only while the
 * record has a forward impulse and is never started here.
 */
final class Ap3DiscretePlanner {

    /** The game's own trig, so the model's directions are the directions {@code getInputVector} produces. */
    interface Trig {
        float cos(float rad);

        float sin(float rad);
    }

    static final double SQRT2 = Math.sqrt(2.0);
    static final double STRAIGHT = Ap3AlignMath.INPUT_SCALE;
    /** Coarse joint look-ahead depth (17^3 leaves). */
    static final int COARSE_DEPTH = 3;
    /** Newton iterations for the two-tap solve. */
    static final int NEWTON_ITERATIONS = 14;
    /** Angle samples for the one-tap solve. */
    static final int ONE_TAP_SAMPLES = 180;

    /** One tick's keys. {@code st}: +1 = A (left), -1 = D (right). */
    record Action(int fw, int st, boolean sneak) {
        boolean none() {
            return fw == 0 && st == 0;
        }

        String label() {
            if (none()) {
                return sneak ? "sneak" : "none";
            }
            return (fw > 0 ? "W" : fw < 0 ? "S" : "") + (st > 0 ? "A" : st < 0 ? "D" : "") + (sneak ? "+sneak" : "");
        }
    }

    static final Action NONE = new Action(0, 0, false);
    static final Action SNEAK_ONLY = new Action(0, 0, true);
    /** The four straight keys, with the yaw offset (degrees) that points each one along "forward". */
    private static final int[][] STRAIGHT_KEYS = {{1, 0, 0}, {0, 1, -90}, {-1, 0, 180}, {0, -1, 90}};

    /** What the game will do with the keys: everything read from the player at plan time. */
    static final class Model {
        /** MOVEMENT_SPEED attribute WITHOUT the sprint modifier. */
        double baseSpeedAttr;
        /** Block friction under the feet (the block's own value, not x0.91). */
        float blockFriction;
        boolean onGround;
        /** SNEAKING_SPEED attribute (0.3 default). */
        double sneakMul;
        double tolerance;
        Trig trig;
        /** Whether the sent yaw can be steered (the rotation-send mixin is live); without it only keys at the camera
         *  yaw are available and the fine phase is keys-only (a few thousandths at best). */
        boolean yawSteerable = true;
        /** Most the sent yaw moves in one tick, degrees. */
        double yawStepCap = 30.0;

        double tickSpeed(boolean sprinting) {
            if (onGround) {
                double attr = sprinting ? baseSpeedAttr * Ap3AlignMath.SPRINT_MULTIPLIER : baseSpeedAttr;
                return Ap3AlignMath.groundSpeed((float) attr, blockFriction);
            }
            return sprinting ? Ap3AlignMath.AIR_SPEED_SPRINTING : Ap3AlignMath.AIR_SPEED;
        }

        double friction() {
            return Ap3AlignMath.frictionMultiplier(blockFriction, onGround);
        }
    }

    /** The movement state the plan runs on, in WORLD coordinates. */
    static final class State {
        /** Error = target minus feet. */
        double ex, ez;
        double vx, vz;
        boolean sprinting;
        /** The sneak multiplier applies to the NEXT tick's travel (= the previous tick's sneak key). */
        boolean crouching;
        /** The yaw the server currently receives (the keys' frame). */
        float sentYaw;

        State copy() {
            State s = new State();
            s.ex = ex;
            s.ez = ez;
            s.vx = vx;
            s.vz = vz;
            s.sprinting = sprinting;
            s.crouching = crouching;
            s.sentYaw = sentYaw;
            return s;
        }
    }

    /** One scripted tick: the keys and the yaw they are sent with. */
    record Step(Action action, float yaw) {
    }

    /** The plan's answer for this tick. */
    static final class Plan {
        Action action = NONE;
        /** The yaw to send this tick (bounded step from the current one). */
        float yaw;
        /** True when the chosen sequence ends inside the tolerance. */
        boolean reaches;
        /** Predicted rest error (max over the two axes) of the chosen sequence. */
        double restError = Double.MAX_VALUE;
        /** Ticks the chosen sequence needs including its coast. */
        int ticks;
        /** "settle" / "tap1" / "tap2" / "coarse" / "keys-only" - for the trace. */
        String phase = "coarse";
        /** Total degrees of turning the sequence needs (two-tap plans; used to pick between mirror solutions). */
        double turn;
    }

    /** The effective input length vanilla ends up with for these keys ({@code modifyInput}: normalized keys x 0.98,
     *  x sneak multiplier while crouching, then {@code min(len * distanceToUnitSquare, 1)}). */
    static double effectiveLength(Action a, boolean crouching, double sneakMul) {
        if (a.none()) {
            return 0.0;
        }
        double len = STRAIGHT * (crouching ? sneakMul : 1.0);
        double d = (a.fw != 0 && a.st != 0) ? SQRT2 : 1.0;
        return Math.min(len * d, 1.0);
    }

    // ------------------------------------------------------------------------------------------- the exact model

    /** One vanilla tick applied to {@code s} in place: aiStep zeroing, the keys at {@code yaw}, travel, friction. */
    static void step(State s, Action a, float yaw, Model m) {
        double v0x = Ap3AlignMath.zeroSmall(s.vx);
        double v0z = Ap3AlignMath.zeroSmall(s.vz);
        boolean sprintNow = s.sprinting && a.fw > 0;
        double vx = v0x, vz = v0z;
        if (!a.none()) {
            double eff = effectiveLength(a, s.crouching, m.sneakMul);
            double speed = m.tickSpeed(sprintNow);
            double norm = Math.sqrt(a.fw * a.fw + a.st * a.st);
            double ux = a.st / norm;
            double uz = a.fw / norm;
            float r = yaw * Ap3AlignMath.DEG_TO_RAD;
            double c = m.trig.cos(r);
            double sn = m.trig.sin(r);
            double mag = speed * eff;
            vx += mag * (ux * c - uz * sn);
            vz += mag * (uz * c + ux * sn);
        }
        s.ex -= vx;
        s.ez -= vz;
        double f = m.friction();
        s.vx = vx * f;
        s.vz = vz * f;
        s.sprinting = sprintNow;
        s.crouching = a.sneak;
        s.sentYaw = yaw;
    }

    /** Coasts {@code s} to rest in place (no keys), returning the ticks it took. */
    static int coast(State s, Model m) {
        double f = m.friction();
        int ticks = 0;
        for (int i = 0; i < 60; i++) {
            double v0x = Ap3AlignMath.zeroSmall(s.vx);
            double v0z = Ap3AlignMath.zeroSmall(s.vz);
            if (v0x == 0.0 && v0z == 0.0) {
                break;
            }
            s.ex -= v0x;
            s.ez -= v0z;
            s.vx = v0x * f;
            s.vz = v0z * f;
            ticks++;
        }
        s.crouching = false;
        return ticks;
    }

    /** Runs {@code steps} then coasts; the final error is left in {@code out} (ex, ez) and the tick count returned. */
    static int simulate(State start, Model m, List<Step> steps, State out) {
        State s = start.copy();
        for (Step st : steps) {
            step(s, st.action(), st.yaw(), m);
        }
        int ticks = steps.size() + coast(s, m);
        out.ex = s.ex;
        out.ez = s.ez;
        return ticks;
    }

    static double maxErr(State s) {
        return Math.max(Math.abs(s.ex), Math.abs(s.ez));
    }

    static float wrap(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return (float) d;
    }

    // ------------------------------------------------------------------------------------------- tap sequences

    /**
     * A straight-key tap of one size in world direction {@code dirDeg} (the yaw a W press would need): the nearest of
     * W/A/S/D is chosen so the turn from {@code fromYaw} is at most 45 degrees; the yaw ramps there in equal steps no
     * bigger than the cap, the tick before the tap carries the sneak state the tap needs (the multiplier lags a
     * tick), and the tap itself sends the exact yaw.
     */
    private static List<Step> tapSequence(float fromYaw, boolean fromCrouching, double dirDeg, boolean sneak, Model m,
                                          int[] pressIndex) {
        int bestKey = 0;
        float bestTurn = Float.MAX_VALUE;
        for (int k = 0; k < STRAIGHT_KEYS.length; k++) {
            float turn = Math.abs(wrap(dirDeg + STRAIGHT_KEYS[k][2] - fromYaw));
            if (turn < bestTurn) {
                bestTurn = turn;
                bestKey = k;
            }
        }
        float targetYaw = wrap(dirDeg + STRAIGHT_KEYS[bestKey][2]);
        float delta = wrap(targetYaw - fromYaw);
        int turnTicks = Math.max(1, (int) Math.ceil(Math.abs(delta) / m.yawStepCap));
        // ticks before the tap: the yaw needs (turnTicks - 1) of them; the crouch state needs one when it differs
        int before = Math.max(turnTicks - 1, fromCrouching == sneak ? 0 : 1);
        List<Step> seq = new ArrayList<>();
        Action wait = sneak ? SNEAK_ONLY : NONE;
        float yaw = fromYaw;
        int rampTicks = before + 1;
        float stepDeg = delta / rampTicks;
        for (int i = 0; i < before; i++) {
            yaw = wrap(yaw + stepDeg);
            seq.add(new Step(wait, yaw));
        }
        pressIndex[0] = seq.size();
        seq.add(new Step(new Action(STRAIGHT_KEYS[bestKey][0], STRAIGHT_KEYS[bestKey][1], sneak), targetYaw));
        return seq;
    }

    private static List<Step> twoTaps(State s, Model m, double dir1, boolean sneak1, double dir2, boolean sneak2) {
        int[] idx = new int[1];
        List<Step> a = tapSequence(s.sentYaw, s.crouching, dir1, sneak1, m, idx);
        Step last = a.get(a.size() - 1);
        List<Step> b = tapSequence(last.yaw(), last.action().sneak(), dir2, sneak2, m, idx);
        List<Step> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** The slide one tap of this size gives in total ({@code a / (1 - f)}), ignoring the zeroing tail. */
    private static double tapReach(Model m, boolean sneak) {
        double a = STRAIGHT * (sneak ? m.sneakMul : 1.0) * m.tickSpeed(false);
        return a / (1.0 - m.friction());
    }

    // ------------------------------------------------------------------------------------------- solvers

    /** Zero taps: where the current slide ends. */
    private static Plan settlePlan(State s, Model m) {
        State r = s.copy();
        int ticks = coast(r, m);
        double err = maxErr(r);
        if (err > m.tolerance) {
            return null;
        }
        Plan p = new Plan();
        boolean moving = Ap3AlignMath.zeroSmall(s.vx) != 0.0 || Ap3AlignMath.zeroSmall(s.vz) != 0.0;
        p.action = moving && s.crouching ? SNEAK_ONLY : NONE;
        p.yaw = s.sentYaw;
        p.reaches = true;
        p.restError = err;
        p.ticks = ticks;
        p.phase = "settle";
        return p;
    }

    /** One tap: sample the direction, refine the best locally. The size whose sneak state matches the current pose
     *  is tried first (its first step is the tap itself, not a pose change), so a plan never dithers between two
     *  preparations tick after tick. */
    private static Plan oneTapPlan(State s, Model m) {
        Plan best = null;
        for (boolean sneak : new boolean[]{s.crouching, !s.crouching}) {
            double bestErr = Double.MAX_VALUE;
            double bestDir = 0;
            State out = new State();
            int[] idx = new int[1];
            for (int i = 0; i < ONE_TAP_SAMPLES; i++) {
                double dir = i * (360.0 / ONE_TAP_SAMPLES);
                List<Step> seq = tapSequence(s.sentYaw, s.crouching, dir, sneak, m, idx);
                simulate(s, m, seq, out);
                double e = maxErr(out);
                if (e < bestErr) {
                    bestErr = e;
                    bestDir = dir;
                }
            }
            for (double span = 2.0; span > 1e-5; span /= 4.0) {
                double base = bestDir;
                for (int i = -4; i <= 4; i++) {
                    double dir = base + i * span / 4.0;
                    List<Step> seq = tapSequence(s.sentYaw, s.crouching, dir, sneak, m, idx);
                    simulate(s, m, seq, out);
                    double e = maxErr(out);
                    if (e < bestErr) {
                        bestErr = e;
                        bestDir = dir;
                    }
                }
            }
            if (bestErr <= m.tolerance) {
                List<Step> seq = tapSequence(s.sentYaw, s.crouching, bestDir, sneak, m, idx);
                int ticks = simulate(s, m, seq, out);
                Plan p = new Plan();
                p.action = seq.get(0).action();
                p.yaw = seq.get(0).yaw();
                p.reaches = true;
                p.restError = bestErr;
                p.ticks = ticks;
                p.phase = "tap1" + (sneak ? "-sneak" : "-full");
                // sneak first, full only when sneak cannot: a stable choice, not a ~1e-8 comparison
                return p;
            }
        }
        return best;
    }

    /**
     * Two taps: the triangle {@code R1 * dir1 + R2 * dir2 = E} (E = error after the current slide) gives the start,
     * Newton on the exact simulation closes it. Sizes tried gentlest first.
     */
    private static Plan twoTapPlan(State s, Model m) {
        State slid = s.copy();
        coast(slid, m);
        double ex = slid.ex;
        double ez = slid.ez;
        double dist = Math.sqrt(ex * ex + ez * ez);
        // Gentlest first, but pairs whose FIRST tap matches the current pose come before the rest: their first step
        // is the tap, not a pose change, so a plan never dithers between two preparations tick after tick (a cycle
        // seen in the harness: crouch for one plan, uncrouch for the other, for ever).
        boolean c = s.crouching;
        boolean[][] sizes = c
                ? new boolean[][]{{true, true}, {true, false}, {false, true}, {false, false}}
                : new boolean[][]{{false, true}, {false, false}, {true, true}, {true, false}};
        Plan best = null;
        for (boolean[] sz : sizes) {
            double r1 = tapReach(m, sz[0]);
            double r2 = tapReach(m, sz[1]);
            if (dist > r1 + r2 + 1e-9 || dist < Math.abs(r1 - r2) - 1e-9) {
                continue;
            }
            // law of cosines: the angle each side makes with E
            double cos1 = dist < 1e-12 ? 0 : (dist * dist + r1 * r1 - r2 * r2) / (2 * dist * r1);
            cos1 = Math.max(-1.0, Math.min(1.0, cos1));
            double phi1 = Math.toDegrees(Math.acos(cos1));
            double cos2 = dist < 1e-12 ? 0 : (dist * dist + r2 * r2 - r1 * r1) / (2 * dist * r2);
            cos2 = Math.max(-1.0, Math.min(1.0, cos2));
            double phi2 = Math.toDegrees(Math.acos(cos2));
            // a world direction (dx, dz) as the yaw a W press needs: W moves along (-sin yaw, cos yaw)
            double eDir = dist < 1e-12 ? s.sentYaw : Math.toDegrees(Math.atan2(-ex, ez));
            // The two mirror solutions: the one with the smaller total turn wins DETERMINISTICALLY (never by the
            // ~1e-8 rest error, which flipped the plan tick to tick and had it turning back and forth for ever).
            Plan pairBest = null;
            for (int side = -1; side <= 1; side += 2) {
                double d1 = eDir + side * phi1;
                double d2 = eDir - side * phi2;
                Plan p = newton(s, m, d1, sz[0], d2, sz[1]);
                if (p == null) {
                    continue;
                }
                if (pairBest == null || (p.reaches && !pairBest.reaches)
                        || (p.reaches == pairBest.reaches && p.turn < pairBest.turn - 1e-6)) {
                    pairBest = p;
                }
            }
            if (pairBest != null && pairBest.reaches) {
                // First size pair (gentlest first) that lands wins, so the choice is stable from tick to tick.
                return pairBest;
            }
            if (pairBest != null && (best == null || pairBest.restError < best.restError)) {
                best = pairBest;
            }
        }
        return best;
    }

    private static Plan newton(State s, Model m, double d1, boolean sneak1, double d2, boolean sneak2) {
        State out = new State();
        double bestErr = Double.MAX_VALUE;
        double bestD1 = d1, bestD2 = d2;
        for (int it = 0; it < NEWTON_ITERATIONS; it++) {
            List<Step> seq = twoTaps(s, m, d1, sneak1, d2, sneak2);
            simulate(s, m, seq, out);
            double fx = out.ex, fz = out.ez;
            double err = Math.max(Math.abs(fx), Math.abs(fz));
            if (err < bestErr) {
                bestErr = err;
                bestD1 = d1;
                bestD2 = d2;
            }
            if (err < 1e-7) {
                break;
            }
            double h = 0.01;
            simulate(s, m, twoTaps(s, m, d1 + h, sneak1, d2, sneak2), out);
            double j11 = (out.ex - fx) / h, j21 = (out.ez - fz) / h;
            simulate(s, m, twoTaps(s, m, d1, sneak1, d2 + h, sneak2), out);
            double j12 = (out.ex - fx) / h, j22 = (out.ez - fz) / h;
            double det = j11 * j22 - j12 * j21;
            if (Math.abs(det) < 1e-12) {
                break;
            }
            // solve J * delta = -F
            double dd1 = (-fx * j22 + fz * j12) / det;
            double dd2 = (-fz * j11 + fx * j21) / det;
            double lim = 25.0;
            dd1 = Math.max(-lim, Math.min(lim, dd1));
            dd2 = Math.max(-lim, Math.min(lim, dd2));
            d1 += dd1;
            d2 += dd2;
        }
        List<Step> seq = twoTaps(s, m, bestD1, sneak1, bestD2, sneak2);
        int ticks = simulate(s, m, seq, out);
        Plan p = new Plan();
        p.action = seq.get(0).action();
        p.yaw = seq.get(0).yaw();
        p.reaches = bestErr <= m.tolerance;
        p.restError = bestErr;
        p.ticks = ticks;
        p.phase = "tap2" + (sneak1 ? "-sneak" : "-full") + (sneak2 ? "-sneak" : "-full");
        double turn = 0;
        float prev = s.sentYaw;
        for (Step st : seq) {
            turn += Math.abs(wrap(st.yaw() - prev));
            prev = st.yaw();
        }
        p.turn = turn;
        return p;
    }

    // ------------------------------------------------------------------------------------------- coarse

    static final Action[] ACTIONS;

    static {
        List<Action> list = new ArrayList<>();
        list.add(NONE);
        list.add(SNEAK_ONLY);
        for (int fw = -1; fw <= 1; fw++) {
            for (int st = -1; st <= 1; st++) {
                if (fw == 0 && st == 0) {
                    continue;
                }
                list.add(new Action(fw, st, false));
                list.add(new Action(fw, st, true));
            }
        }
        ACTIONS = list.toArray(new Action[0]);
    }

    private static final class JointResult {
        Action first = NONE;
        double restErr = Double.MAX_VALUE;
        int ticks = Integer.MAX_VALUE;
    }

    /** Exhaustive joint search over all 17 combinations for {@code depth} ticks at the current yaw, scored by the rest
     *  error; equal errors go to the sequence that comes to rest sooner (so a needed press is never deferred). */
    private static void jointSearch(State s, Model m, int depth, int k, Action first, JointResult out) {
        if (k == depth) {
            State r = s.copy();
            int ticks = k + coast(r, m);
            double rest = maxErr(r);
            if (rest < out.restErr - 1e-9 || (Math.abs(rest - out.restErr) <= 1e-9 && ticks < out.ticks)) {
                out.restErr = rest;
                out.first = first;
                out.ticks = ticks;
            }
            return;
        }
        for (Action a : ACTIONS) {
            State n = s.copy();
            step(n, a, s.sentYaw, m);
            jointSearch(n, m, depth, k + 1, first == null ? a : first, out);
        }
    }

    // ------------------------------------------------------------------------------------------- the plan

    /**
     * The keys and yaw for this tick. Order: the current slide already ends inside the tolerance -> no keys; one tap
     * lands -> take it; two taps land -> take the first; otherwise the coarse look-ahead at the current yaw drives
     * closer (or, when it cannot improve, the best two-tap plan is followed as progress).
     */
    /** A slide that already ends inside the tolerance but not this close is still refined when a tap or two can land
     *  ten times closer within a few more ticks - killer560: 0.001 is what he settles for, not what he wants. */
    static final double REFINE_ABOVE = 1.0E-4;
    static final int REFINE_EXTRA_TICKS = 3;

    static Plan plan(State s, Model m) {
        Plan settle = settlePlan(s, m);
        if (settle != null) {
            if (settle.restError > REFINE_ABOVE && m.yawSteerable) {
                Plan better = oneTapPlan(s, m);
                if (better == null || !better.reaches) {
                    Plan two = twoTapPlan(s, m);
                    if (two != null && two.reaches) {
                        better = two;
                    }
                }
                if (better != null && better.reaches && better.restError < settle.restError / 10.0
                        && better.ticks <= settle.ticks + REFINE_EXTRA_TICKS) {
                    better.phase = "refine-" + better.phase;
                    return better;
                }
            }
            return settle;
        }
        Plan fine = null;
        if (m.yawSteerable) {
            fine = oneTapPlan(s, m);
            if (fine == null || !fine.reaches) {
                Plan two = twoTapPlan(s, m);
                if (two != null && (fine == null || two.restError < fine.restError)) {
                    fine = two;
                }
            }
            if (fine != null && fine.reaches) {
                return fine;
            }
        }
        JointResult joint = new JointResult();
        jointSearch(s, m, COARSE_DEPTH, 0, null, joint);
        State r = s.copy();
        coast(r, m);
        double restNow = maxErr(r);
        Plan p = new Plan();
        if (fine != null && fine.restError < joint.restErr && fine.restError < restNow) {
            return fine;
        }
        p.action = joint.first == null ? NONE : joint.first;
        p.yaw = s.sentYaw;
        p.reaches = false;
        p.restError = joint.restErr;
        p.ticks = joint.ticks;
        p.phase = m.yawSteerable ? "coarse" : "keys-only";
        return p;
    }
}
