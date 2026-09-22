package com.killer560.hub.ap3;

/**
 * Test Align's planner - killer560 (2026-09-22): "one more attempt at making a faster align node. I want it as close
 * to .001 of precision or more as possible but the sole focus is making it as fast as possible to align without doing
 * the lag stuff. Really work hard on this, take new approaches."
 * <p>
 * <b>The idea.</b> The regular planner lands with one or two taps and then COASTS until friction brings the speed under
 * vanilla's 0.003 zeroing line - from 0.1 blocks/tick that alone is ~6 ticks. Here the objective is the total number
 * of ticks until you are AT REST on the point, so the last presses brake: the search looks for the shortest schedule
 * of up to three presses (each any real key combination, sneak included, at ANY yaw - the real yaw snaps freely, one
 * delta per tick) followed by as few coast ticks as possible, such that after it the horizontal speed is under the
 * zeroing line (the next tick does not move you at all) and the feet are within the tolerance.
 * <p>
 * For a total of T ticks it tries k = 1..3 presses and T-k coast ticks; for each combination of k actions it solves the
 * k yaws with Levenberg-Marquardt on the exact vanilla step ({@link Ap3DiscretePlanner#step}): residuals are the
 * position error after T ticks and the part of the final speed above the zeroing line. The first T with a solution is
 * the plan; its first press and yaw go out this tick and the whole thing is re-solved next tick from the measured
 * state (closed loop). Discrete keys only; nothing fractional; no position or velocity writes.
 */
final class Ap3FastAlign {

    private Ap3FastAlign() {
    }

    /** Actions tried per press: nothing, W, W+A (the 45-degree diagonal - a bit more push), their sneaking versions,
     *  and A (sideways - it drops sprint, which shrinks the next pushes). The yaw decides the world direction. */
    private static final Ap3DiscretePlanner.Action[] ACTS = {
            Ap3DiscretePlanner.NONE,
            new Ap3DiscretePlanner.Action(1, 0, false),
            new Ap3DiscretePlanner.Action(1, 1, false),
            new Ap3DiscretePlanner.Action(1, 0, true),
            new Ap3DiscretePlanner.Action(1, 1, true),
            new Ap3DiscretePlanner.Action(0, 1, false),
            new Ap3DiscretePlanner.Action(0, 1, true),
    };
    private static final Ap3DiscretePlanner.Action[] NO_ACTS = {};
    private static final float[] NO_YAWS = {};
    static final int MAX_PRESSES = 3;
    static final int MAX_TICKS = 9;
    /** The final speed must be this far under the zeroing line (margin for float rounding in the real game). */
    private static final double REST_SPEED = Ap3AlignMath.ZERO_VELOCITY * 0.9;

    /** Result of one solve: the first press and yaw, total ticks to rest, predicted error. */
    static final class Result {
        Ap3DiscretePlanner.Action action;
        float yaw;
        int ticks;
        double error;
        int presses;
        /** The whole schedule (full solves only): solve() keeps it for the next tick's warm start. */
        Ap3DiscretePlanner.Action[] acts;
        float[] yaws;
    }

    /**
     * @param s     measured state (error = target - feet, velocity, sprint, crouch, current yaw)
     * @param tol   position tolerance at rest (per axis)
     * @return the fastest plan found, or null (let the regular planner handle it)
     */
    /** Last tick's full schedule, re-checked first (closed loop without re-solving while it still holds). */
    private static Ap3DiscretePlanner.Action[] lastActs;
    private static float[] lastYaws;
    private static int lastTotal;

    static void reset() {
        lastActs = null;
    }

    private static volatile boolean warmed;

    /**
     * Runs a few hundred throwaway solves on a background thread at startup so the JIT has compiled the planner before
     * the first real Test Align (cold, interpreted, the first plan was many times slower). Touches no shared state.
     */
    static void warmUpAsync() {
        if (warmed) {
            return;
        }
        warmed = true;
        Thread t = new Thread(() -> {
            try {
                Ap3DiscretePlanner.Model m = new Ap3DiscretePlanner.Model();
                m.baseSpeedAttr = 0.1;
                m.blockFriction = 0.6f;
                m.onGround = true;
                m.sneakMul = 0.3;
                m.tolerance = 0.001;
                m.trig = new Ap3DiscretePlanner.Trig() {
                    public float cos(float r) { return (float) Math.cos(r); }
                    public float sin(float r) { return (float) Math.sin(r); }
                };
                m.yawSteerable = true;
                m.yawStepCap = 180.0;
                java.util.Random rnd = new java.util.Random(1);
                for (int i = 0; i < 400; i++) {
                    Ap3DiscretePlanner.State s = new Ap3DiscretePlanner.State();
                    double ang = rnd.nextDouble() * Math.PI * 2;
                    s.vx = -Math.sin(ang) * 0.15;
                    s.vz = Math.cos(ang) * 0.15;
                    s.ex = s.vx * 2 + (rnd.nextDouble() - 0.5) * 0.4;
                    s.ez = s.vz * 2 + (rnd.nextDouble() - 0.5) * 0.4;
                    s.sprinting = true;
                    s.sentYaw = (float) Math.toDegrees(ang);
                    fullSolve(s, m, 0.001);
                }
            } catch (Throwable ignored) {
                // warm-up only
            }
        }, "killer560smod-ap3-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static Result solve(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m, double tol) {
        Result warm = warmStart(s, m, tol);
        if (warm != null) {
            return warm;
        }
        Result r = fullSolve(s, m, tol);
        lastActs = r == null ? null : r.acts;
        lastYaws = r == null ? null : r.yaws;
        lastTotal = r == null ? 0 : r.ticks;
        return r;
    }

    /** The rest of last tick's schedule, from the MEASURED state: still lands and stops? Then keep following it. */
    private static Result warmStart(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m, double tol) {
        if (lastActs == null || lastActs.length < 1 || lastTotal < 2) {
            lastActs = null;
            return null;
        }
        int k = lastActs.length - 1;
        Ap3DiscretePlanner.Action[] acts = new Ap3DiscretePlanner.Action[k];
        float[] yaws = new float[k];
        System.arraycopy(lastActs, 1, acts, 0, k);
        System.arraycopy(lastYaws, 1, yaws, 0, k);
        double err = evaluate(s, m, acts, yaws, lastTotal - 1, null);
        if (err > tol) {
            lastActs = null;
            return null;
        }
        lastActs = acts;
        lastYaws = yaws;
        lastTotal--;
        Result r = new Result();
        r.action = k > 0 ? acts[0] : Ap3DiscretePlanner.NONE; // presses done: just coast out the rest
        r.yaw = k > 0 ? yaws[0] : s.sentYaw;
        r.ticks = lastTotal;
        r.error = err;
        r.presses = k;
        return r;
    }

    private static Result fullSolve(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m, double tol) {
        // Already at rest on the point: nothing to do.
        if (Ap3AlignMath.horizontalZeroed(s.vx, s.vz) && Math.max(Math.abs(s.ex), Math.abs(s.ez)) <= tol) {
            Result r = new Result();
            r.action = Ap3DiscretePlanner.NONE;
            r.yaw = s.sentYaw;
            r.ticks = 0;
            r.error = Math.max(Math.abs(s.ex), Math.abs(s.ez));
            return r;
        }
        for (int total = 1; total <= MAX_TICKS; total++) {
            // Coasting alone lands and stops in this many ticks: pressing anything can only be slower or equal.
            double coast = evaluate(s, m, NO_ACTS, NO_YAWS, total, null);
            if (coast <= tol) {
                Result r = new Result();
                r.action = Ap3DiscretePlanner.NONE;
                r.yaw = s.sentYaw;
                r.ticks = total;
                r.error = coast;
                return r;
            }
            for (int k = 1; k <= Math.min(MAX_PRESSES, total); k++) {
                int combos = pow(ACTS.length, k);
                for (int c = 0; c < combos; c++) {
                    Ap3DiscretePlanner.Action[] acts = decode(c, k);
                    if (acts[k - 1].none()) {
                        continue; // a trailing "nothing" is just a shorter schedule with one more coast tick
                    }
                    float[] yaws = solveCombo(s, m, acts, total, tol);
                    if (yaws == null) {
                        continue;
                    }
                    double err = evaluate(s, m, acts, yaws, total, null);
                    if (err > tol) {
                        continue;
                    }
                    // The first schedule that lands at the fewest ticks is the plan - any other one is no faster.
                    Result r = new Result();
                    r.action = acts[0];
                    r.yaw = yaws[0];
                    r.ticks = total;
                    r.error = err;
                    r.presses = k;
                    r.acts = acts;
                    r.yaws = yaws;
                    return r;
                }
            }
        }
        return null;
    }

    // ---- the closed-form model -------------------------------------------------------------------------------------
    // Between the zeroing checks the vanilla step is LINEAR in each push: a press at tick t of length mag (fixed by the
    // keys, sprint and sneak - never by the yaw) in world direction u moves you mag * (1 - f^(T-t)) / (1 - f) * u by
    // tick T and leaves mag * f^(T-t) * u of velocity. So for a given key schedule the end state is
    //     error    = D - sum a_i u_i        velocity = V0 + sum b_i u_i
    // with D, V0, a_i, b_i computed from the state in a few steps, and only the directions u_i unknown. Two presses
    // are then a circle intersection, three a short scan of one angle with the other two exact - microseconds instead
    // of thousands of simulated schedules. Every answer is still checked on the exact step (evaluate) before use.

    /** Target speed at the end in the closed form - a margin under the check in {@link #evaluate}. */
    private static final double LIN_REST = REST_SPEED * 0.98;
    /** A closed-form near miss worth trying on the exact step (where the zeroing can still make it land). */
    private static final double NEAR_POS = 0.02;
    private static final double NEAR_SPEED = Ap3AlignMath.ZERO_VELOCITY;
    private static final int SCAN = 72;
    private static final double[] SCAN_ANG = new double[SCAN];
    private static final double[] SCAN_X = new double[SCAN];
    private static final double[] SCAN_Z = new double[SCAN];
    static {
        for (int i = 0; i < SCAN; i++) {
            double a = i * (2 * Math.PI / SCAN);
            SCAN_ANG[i] = a;
            SCAN_X[i] = -Math.sin(a);
            SCAN_Z[i] = Math.cos(a);
        }
    }

    /** The schedule's linear model: pressing slots, their a / b lengths, and D / V0. */
    private static final class Lin {
        int n;
        final int[] slot = new int[MAX_PRESSES];
        final double[] a = new double[MAX_PRESSES];
        final double[] b = new double[MAX_PRESSES];
        double dx, dz, v0x, v0z;
    }

    private static Lin linearize(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m,
                                 Ap3DiscretePlanner.Action[] acts, int total) {
        double f = m.friction();
        boolean zeroed = Ap3AlignMath.horizontalZeroed(s.vx, s.vz);
        double vx = zeroed ? 0.0 : s.vx;
        double vz = zeroed ? 0.0 : s.vz;
        double fT = Math.pow(f, total);
        double carry = (1.0 - fT) / (1.0 - f);
        Lin l = new Lin();
        l.dx = s.ex - vx * carry;
        l.dz = s.ez - vz * carry;
        l.v0x = vx * fT;
        l.v0z = vz * fT;
        // Sprint / sneak evolve with the keys alone: a scratch state from rest measures each press's push length.
        Ap3DiscretePlanner.State sc = s.copy();
        for (int i = 0; i < acts.length; i++) {
            sc.vx = 0.0;
            sc.vz = 0.0;
            double ex0 = sc.ex;
            double ez0 = sc.ez;
            Ap3DiscretePlanner.step(sc, acts[i], 0f, m);
            if (acts[i].none()) {
                continue;
            }
            double mag = Math.hypot(ex0 - sc.ex, ez0 - sc.ez);
            double fr = Math.pow(f, total - i);
            l.slot[l.n] = i;
            l.a[l.n] = mag * (1.0 - fr) / (1.0 - f);
            l.b[l.n] = mag * fr;
            l.n++;
        }
        return l;
    }

    /** World directions (MC yaw of the push, radians) for the pressing slots, or null; then the key yaws. */
    private static float[] solveCombo(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m,
                                      Ap3DiscretePlanner.Action[] acts, int total, double tol) {
        Lin l = linearize(s, m, acts, total);
        if (l.n == 0) {
            return null;
        }
        // Reach test (triangle inequality) on the position and the speed - most schedules end here.
        double d = Math.hypot(l.dx, l.dz);
        double sumA = 0, maxA = 0, sumB = 0;
        for (int i = 0; i < l.n; i++) {
            sumA += l.a[i];
            maxA = Math.max(maxA, l.a[i]);
            sumB += l.b[i];
        }
        if (d > sumA + NEAR_POS || d < 2 * maxA - sumA - NEAR_POS
                || Math.hypot(l.v0x, l.v0z) > sumB + LIN_REST + NEAR_SPEED) {
            return null;
        }
        double[] th = new double[l.n];
        double best = Double.MAX_VALUE;
        double[] bestTh = null;
        if (l.n == 1) {
            th[0] = mcAngle(l.dx, l.dz);
            best = cost(l, th);
            bestTh = th.clone();
        } else {
            // n = 2: the two exact branches. n = 3: scan the first push's direction, the last two exact.
            int scans = l.n == 3 ? SCAN : 1;
            for (int q = 0; q < scans; q++) {
                double rx = l.dx, rz = l.dz;
                if (l.n == 3) {
                    th[0] = SCAN_ANG[q];
                    rx -= l.a[0] * SCAN_X[q];
                    rz -= l.a[0] * SCAN_Z[q];
                }
                int i1 = l.n - 2;
                int i2 = l.n - 1;
                for (int branch = -1; branch <= 1; branch += 2) {
                    if (!twoPush(rx, rz, l.a[i1], l.a[i2], branch, th, i1, i2)) {
                        continue;
                    }
                    double c = cost(l, th);
                    if (c < best) {
                        best = c;
                        bestTh = th.clone();
                    }
                }
            }
        }
        if (bestTh == null) {
            return null;
        }
        polish(l, bestTh);
        double[] res = new double[4];
        linResid(l, bestTh, res);
        double posErr = Math.max(Math.abs(res[0]), Math.abs(res[1]));
        double overSpeed = Math.hypot(res[2], res[3]);
        if (posErr > NEAR_POS || overSpeed > NEAR_SPEED) {
            // The closed form is exact apart from vanilla's under-0.003 zeroing, which only matters when the speed gets
            // near that line - far from landing here, the exact step cannot land either.
            return null;
        }
        boolean linearLands = posErr <= tol && overSpeed == 0.0;
        float[] yaws = toYaws(s, acts, l, bestTh);
        if (linearLands && evaluate(s, m, acts, yaws, total, null) <= tol) {
            return yaws;
        }
        // The exact step disagrees (a zeroing inside the schedule, float rounding): a short polish on the step itself.
        double[] start = new double[acts.length];
        for (int i = 0; i < acts.length; i++) {
            start[i] = yaws[i] + keyOffset(acts[i]);
        }
        return solveYaws(s, m, acts, total, new double[][]{start});
    }

    /** Two pushes of lengths a1 / a2 that sum to (rx, rz): branch -1 / +1. False when out of reach. */
    private static boolean twoPush(double rx, double rz, double a1, double a2, int branch, double[] th, int i1, int i2) {
        double d = Math.hypot(rx, rz);
        if (d < 1e-12 || a1 < 1e-12) {
            return false;
        }
        double cosA = (a1 * a1 + d * d - a2 * a2) / (2 * a1 * d);
        if (cosA > 1.0 + 1e-3 || cosA < -1.0 - 1e-3) {
            return false;
        }
        double alpha = Math.acos(Math.max(-1.0, Math.min(1.0, cosA)));
        double base = Math.atan2(rz, rx); // standard angle of the remainder
        double ang = base + branch * alpha;
        double p1x = a1 * Math.cos(ang);
        double p1z = a1 * Math.sin(ang);
        th[i1] = mcAngle(p1x, p1z);
        th[i2] = mcAngle(rx - p1x, rz - p1z);
        return true;
    }

    /** MC yaw (radians) of a world direction: dir(t) = (-sin t, cos t). */
    private static double mcAngle(double x, double z) {
        return Math.atan2(-x, z);
    }

    /** Closed-form residuals: position error, and the end speed over LIN_REST. */
    private static void linResid(Lin l, double[] th, double[] r) {
        double ex = l.dx, ez = l.dz, vx = l.v0x, vz = l.v0z;
        for (int i = 0; i < l.n; i++) {
            double ux = -Math.sin(th[i]);
            double uz = Math.cos(th[i]);
            ex -= l.a[i] * ux;
            ez -= l.a[i] * uz;
            vx += l.b[i] * ux;
            vz += l.b[i] * uz;
        }
        double sp = Math.hypot(vx, vz);
        double over = Math.max(0.0, sp - LIN_REST);
        r[0] = ex;
        r[1] = ez;
        r[2] = sp > 1e-12 ? over * vx / sp : 0.0;
        r[3] = sp > 1e-12 ? over * vz / sp : 0.0;
    }

    private static double cost(Lin l, double[] th) {
        double[] r = new double[4];
        linResid(l, th, r);
        return dot(r, r);
    }

    /** A few Gauss-Newton / LM steps on the closed form (lets the position use its tolerance to shed end speed). */
    private static void polish(Lin l, double[] th) {
        int k = l.n;
        double[] r = new double[4];
        double[] r2 = new double[4];
        linResid(l, th, r);
        double c = dot(r, r);
        double lambda = 1e-3;
        for (int it = 0; it < 12 && c > 1e-16; it++) {
            double[][] jac = new double[4][k];
            for (int j = 0; j < k; j++) {
                double h = 1e-6;
                th[j] += h;
                linResid(l, th, r2);
                th[j] -= h;
                for (int q = 0; q < 4; q++) {
                    jac[q][j] = (r2[q] - r[q]) / h;
                }
            }
            double[][] a = new double[k][k];
            double[] g = new double[k];
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    double sum = 0;
                    for (int q = 0; q < 4; q++) {
                        sum += jac[q][i] * jac[q][j];
                    }
                    a[i][j] = sum;
                }
                double sum = 0;
                for (int q = 0; q < 4; q++) {
                    sum += jac[q][i] * r[q];
                }
                g[i] = -sum;
            }
            boolean improved = false;
            for (int tries = 0; tries < 5 && !improved; tries++) {
                double[][] aa = new double[k][k];
                for (int i = 0; i < k; i++) {
                    System.arraycopy(a[i], 0, aa[i], 0, k);
                    aa[i][i] += lambda * (a[i][i] + 1e-12);
                }
                double[] dy = solveLinear(aa, g.clone());
                if (dy == null) {
                    lambda *= 10;
                    continue;
                }
                double[] nt = new double[k];
                for (int i = 0; i < k; i++) {
                    nt[i] = th[i] + Math.max(-1.0, Math.min(1.0, dy[i]));
                }
                linResid(l, nt, r2);
                double nc = dot(r2, r2);
                if (nc < c) {
                    System.arraycopy(nt, 0, th, 0, k);
                    System.arraycopy(r2, 0, r, 0, 4);
                    c = nc;
                    lambda = Math.max(1e-9, lambda / 5);
                    improved = true;
                } else {
                    lambda *= 8;
                }
            }
            if (!improved) {
                break;
            }
        }
    }

    /** Push directions -> the yaw each press goes out at (a nothing-tick keeps the previous yaw: no needless turn). */
    private static float[] toYaws(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Action[] acts, Lin l, double[] th) {
        float[] yaws = new float[acts.length];
        float prev = s.sentYaw;
        int j = 0;
        for (int i = 0; i < acts.length; i++) {
            if (j < l.n && l.slot[j] == i) {
                double want = Math.toDegrees(th[j]) - keyOffset(acts[i]);
                // nearest equivalent to the running yaw, so the turn is the short way (never wrapped to 0-360 itself)
                prev = (float) (prev + wrap(want - prev));
                j++;
            }
            yaws[i] = prev;
        }
        return yaws;
    }

    private static double wrap(double deg) {
        double w = deg % 360.0;
        if (w >= 180.0) {
            w -= 360.0;
        } else if (w < -180.0) {
            w += 360.0;
        }
        return w;
    }

    /**
     * Simulates the presses then coasting to {@code total} ticks; returns the max-axis position error if the final
     * speed is under the zeroing line, else +inf. {@code resid} (length 4) receives the LM residuals when non-null.
     */
    private static double evaluate(Ap3DiscretePlanner.State s0, Ap3DiscretePlanner.Model m,
                                   Ap3DiscretePlanner.Action[] acts, float[] yaws, int total, double[] resid) {
        Ap3DiscretePlanner.State s = s0.copy();
        float yaw = s0.sentYaw;
        for (int t = 0; t < total; t++) {
            Ap3DiscretePlanner.Action a = t < acts.length ? acts[t] : Ap3DiscretePlanner.NONE;
            if (t < acts.length) {
                yaw = yaws[t];
            }
            Ap3DiscretePlanner.step(s, a, yaw, m);
        }
        double speed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        if (resid != null) {
            resid[0] = s.ex;
            resid[1] = s.ez;
            double over = Math.max(0.0, speed - REST_SPEED);
            resid[2] = speed > 1e-9 ? over * s.vx / speed : 0.0;
            resid[3] = speed > 1e-9 ? over * s.vz / speed : 0.0;
        }
        if (speed >= REST_SPEED) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(Math.abs(s.ex), Math.abs(s.ez));
    }

    /** Levenberg-Marquardt over the k yaws from a few starting guesses; null when nothing converges. */
    private static float[] solveYaws(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m,
                                     Ap3DiscretePlanner.Action[] acts, int total, double[][] starts) {
        int k = acts.length;
        float[] bestYaws = null;
        double bestCost = Double.MAX_VALUE;
        double[] r = new double[4];
        double[] r2 = new double[4];
        for (double[] start : starts) {
            double[] y = new double[k];
            for (int i = 0; i < k; i++) {
                // each action pushes at yaw + its key offset: turn the wanted world direction into a yaw
                y[i] = start[i] - keyOffset(acts[i]);
            }
            double lambda = 1e-3;
            float[] fy = toFloat(y);
            evaluate(s, m, acts, fy, total, r);
            double cost = dot(r, r);
            for (int it = 0; it < 14 && cost > 1e-14; it++) {
                // numeric Jacobian (4 x k)
                double[][] jac = new double[4][k];
                for (int j = 0; j < k; j++) {
                    double h = 0.01;
                    y[j] += h;
                    evaluate(s, m, acts, toFloat(y), total, r2);
                    y[j] -= h;
                    for (int q = 0; q < 4; q++) {
                        jac[q][j] = (r2[q] - r[q]) / h;
                    }
                }
                // (J^T J + lambda diag) dy = -J^T r
                double[][] a = new double[k][k];
                double[] g = new double[k];
                for (int i = 0; i < k; i++) {
                    for (int j = 0; j < k; j++) {
                        double sum = 0;
                        for (int q = 0; q < 4; q++) {
                            sum += jac[q][i] * jac[q][j];
                        }
                        a[i][j] = sum;
                    }
                    double sum = 0;
                    for (int q = 0; q < 4; q++) {
                        sum += jac[q][i] * r[q];
                    }
                    g[i] = -sum;
                }
                boolean improved = false;
                for (int tries = 0; tries < 6 && !improved; tries++) {
                    double[][] aa = new double[k][k];
                    for (int i = 0; i < k; i++) {
                        System.arraycopy(a[i], 0, aa[i], 0, k);
                        aa[i][i] += lambda * (a[i][i] + 1e-9);
                    }
                    double[] dy = solveLinear(aa, g.clone());
                    if (dy == null) {
                        lambda *= 10;
                        continue;
                    }
                    double[] ny = new double[k];
                    for (int i = 0; i < k; i++) {
                        ny[i] = y[i] + Math.max(-60.0, Math.min(60.0, dy[i]));
                    }
                    evaluate(s, m, acts, toFloat(ny), total, r2);
                    double nc = dot(r2, r2);
                    if (nc < cost) {
                        y = ny;
                        System.arraycopy(r2, 0, r, 0, 4);
                        cost = nc;
                        lambda = Math.max(1e-7, lambda / 5);
                        improved = true;
                    } else {
                        lambda *= 8;
                    }
                }
                if (!improved) {
                    break;
                }
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestYaws = toFloat(y);
            }
        }
        return bestCost < 1e-5 ? bestYaws : null;
    }

    /** Where an action pushes relative to the facing, in degrees (W = 0, W+A = -45, A = -90). */
    private static double keyOffset(Ap3DiscretePlanner.Action a) {
        if (a.none()) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(-a.st(), a.fw())) ;
    }

    private static float[] toFloat(double[] y) {
        float[] f = new float[y.length];
        for (int i = 0; i < y.length; i++) {
            f[i] = (float) y[i];
        }
        return f;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double[] solveLinear(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][col]) < 1e-15) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = b[r];
            for (int c = r + 1; c < n; c++) {
                sum -= a[r][c] * x[c];
            }
            x[r] = sum / a[r][r];
        }
        return x;
    }

    private static int pow(int b, int e) {
        int r = 1;
        for (int i = 0; i < e; i++) {
            r *= b;
        }
        return r;
    }

    private static Ap3DiscretePlanner.Action[] decode(int code, int k) {
        Ap3DiscretePlanner.Action[] out = new Ap3DiscretePlanner.Action[k];
        for (int i = 0; i < k; i++) {
            out[i] = ACTS[code % ACTS.length];
            code /= ACTS.length;
        }
        return out;
    }
}
