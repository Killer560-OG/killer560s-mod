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

    static Result solve(Ap3DiscretePlanner.State s, Ap3DiscretePlanner.Model m, double tol) {
        Result warm = warmStart(s, m, tol);
        if (warm != null) {
            return warm;
        }
        Result r = fullSolve(s, m, tol);
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
        lastActs = null;
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
            Result best = null;
            for (int k = 1; k <= Math.min(MAX_PRESSES, total); k++) {
                int combos = pow(ACTS.length, k);
                for (int c = 0; c < combos; c++) {
                    Ap3DiscretePlanner.Action[] acts = decode(c, k);
                    if (acts[k - 1].none()) {
                        continue; // a trailing "nothing" is just a shorter schedule with one more coast tick
                    }
                    float[] yaws = solveYaws(s, m, acts, total);
                    if (yaws == null) {
                        continue;
                    }
                    double err = evaluate(s, m, acts, yaws, total, null);
                    if (err <= tol && (best == null || err < best.error)) {
                        best = new Result();
                        best.action = acts[0];
                        best.yaw = yaws[0];
                        best.ticks = total;
                        best.error = err;
                        best.presses = k;
                        lastActs = acts;
                        lastYaws = yaws;
                        lastTotal = total;
                    }
                }
                if (best != null) {
                    return best; // fewest presses at this total is fine - total ticks is what matters
                }
            }
        }
        return null;
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
                                     Ap3DiscretePlanner.Action[] acts, int total) {
        int k = acts.length;
        double toTarget = Math.toDegrees(Math.atan2(-s.ex, s.ez));
        double against = Math.toDegrees(Math.atan2(s.vx, -s.vz)); // opposite the velocity
        double[][] starts = new double[4][k];
        for (int i = 0; i < k; i++) {
            starts[0][i] = toTarget;
            starts[1][i] = against;
            starts[2][i] = i == 0 ? toTarget : against;
            starts[3][i] = i == 0 ? against : toTarget;
        }
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
