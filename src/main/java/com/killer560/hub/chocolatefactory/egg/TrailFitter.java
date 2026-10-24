package com.killer560.hub.chocolatefactory.egg;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Works out where the Egglocator's particle trail is heading. The trail does not reach the egg: Hypixel draws only the start of a
 * cubic Bezier curve from the player toward it, so the last particle is wherever the trail happened to be when it stopped, often
 * far short of the egg. This is SkyHanni's method (PolynomialFitter.kt's BezierFitter / ParticlePathBezierFitter and
 * LocationUtils.computePitchWeight, LGPL-2.1, read for behavior; written again here): fit a cubic in the particle's index to
 * each of x, y and z by least squares, then follow the fitted curve out to the parameter where the real curve ends.
 * <p>
 * That end parameter comes from the curve's start: the first control point sits a pitch-dependent distance from the player
 * ({@link #controlPointDistance}), the derivative of a cubic Bezier at 0 is 3 x (P1 - P0), so the curve's own end is at
 * t = 3 x distance / |derivative at 0| in particle-index units.
 * <p>
 * Render thread only.
 */
final class TrailFitter {

    /** Cubic, as SkyHanni's {@code ParticlePathBezierFitter(3)}. */
    private static final int DEGREE = 3;
    /** Hypixel repeats a particle position (A, A or A, B, A, B); a repeat would stall the index parameter. 0.01 blocks. */
    private static final double REPEAT_TOLERANCE_SQ = 0.0001;

    private final List<Vec3> points = new ArrayList<>();

    /** Why the last {@link #tryAdd} refused its point, for the log. */
    private String lastReject = "";

    void reset() {
        points.clear();
        lastReject = "";
    }

    boolean isEmpty() {
        return points.isEmpty();
    }

    int count() {
        return points.size();
    }

    String lastReject() {
        return lastReject;
    }

    /**
     * Offers one trail particle. The first point is always taken and returns false (nothing to fit yet); while it is the only
     * one, a second point too far from it replaces it. After that a point is taken only when it is within {@code maxDistanceToLast} of the previous one, is not a repeat of any point already
     * held, and {@code endCondition} does not hold for it; true when it was taken.
     */
    boolean tryAdd(Vec3 p, double maxDistanceToLast, Predicate<Vec3> endCondition) {
        lastReject = "";
        if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) {
            lastReject = "not finite";
            return false;
        }
        if (points.isEmpty()) {
            points.add(p);
            return false;
        }
        double toLast = points.get(points.size() - 1).distanceTo(p);
        if (toLast > maxDistanceToLast) {
            if (points.size() == 1) {
                // One point is no trail yet. SkyHanni keeps whatever arrived first, so one stray happy villager (an NPC,
                // another player's Egglocator) ahead of the real trail made every real point "too far" and the click
                // found nothing. Here an unconnected second point starts the trail over instead.
                points.set(0, p);
                lastReject = String.format("%.1f from a lone first point: restarted the trail here", toLast);
                return false;
            }
            lastReject = String.format("%.1f from the last point", toLast);
            return false;
        }
        for (Vec3 q : points) {
            if (q.distanceToSqr(p) < REPEAT_TOLERANCE_SQ) {
                lastReject = "repeat";
                return false;
            }
        }
        if (endCondition.test(p)) {
            lastReject = "at a fishing hook";
            return false;
        }
        points.add(p);
        return true;
    }

    /** The end of the fitted curve, or null with fewer than four points or a singular fit. */
    Vec3 solve() {
        int n = points.size();
        if (n <= DEGREE) {
            return null;
        }
        double[] cx = new double[n];
        double[] cy = new double[n];
        double[] cz = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 p = points.get(i);
            cx[i] = p.x;
            cy[i] = p.y;
            cz[i] = p.z;
        }
        double[] ax = fit(cx);
        double[] ay = fit(cy);
        double[] az = fit(cz);
        if (ax == null || ay == null || az == null) {
            return null;
        }
        // Derivative of the cubic at t = 0 is its linear coefficient.
        Vec3 d0 = new Vec3(ax[1], ay[1], az[1]);
        double len = d0.length();
        if (!(len > 1e-9)) {
            return null;
        }
        double t = 3 * controlPointDistance(d0) / len;
        Vec3 end = new Vec3(eval(ax, t), eval(ay, t), eval(az, t));
        return Double.isFinite(end.x) && Double.isFinite(end.y) && Double.isFinite(end.z) ? end : null;
    }

    /** Least-squares cubic through (i, v[i]): coefficients a0..a3, or null when the normal matrix is singular. */
    private static double[] fit(double[] v) {
        int m = DEGREE + 1;
        double[][] a = new double[m][m + 1];
        for (int i = 0; i < v.length; i++) {
            double[] pow = new double[m];
            pow[0] = 1;
            for (int k = 1; k < m; k++) {
                pow[k] = pow[k - 1] * i;
            }
            for (int r = 0; r < m; r++) {
                for (int c = 0; c < m; c++) {
                    a[r][c] += pow[r] * pow[c];
                }
                a[r][m] += pow[r] * v[i];
            }
        }
        // Gauss-Jordan with partial pivoting on the normal equations (X^T X) a = X^T y.
        for (int c = 0; c < m; c++) {
            int big = c;
            for (int r = c + 1; r < m; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[big][c])) {
                    big = r;
                }
            }
            if (a[big][c] == 0.0) {
                return null;
            }
            double[] tmp = a[c];
            a[c] = a[big];
            a[big] = tmp;
            double pivot = a[c][c];
            for (int s = c; s <= m; s++) {
                a[c][s] /= pivot;
            }
            for (int r = 0; r < m; r++) {
                if (r == c) {
                    continue;
                }
                double f = a[r][c];
                if (f != 0.0) {
                    for (int s = c; s <= m; s++) {
                        a[r][s] -= f * a[c][s];
                    }
                }
            }
        }
        double[] out = new double[m];
        for (int r = 0; r < m; r++) {
            out[r] = a[r][m];
        }
        return out;
    }

    private static double eval(double[] coeff, double t) {
        double r = 0;
        for (int i = coeff.length - 1; i >= 0; i--) {
            r = r * t + coeff[i];
        }
        return r;
    }

    /**
     * How far the curve's first control point sits from its start, from the pitch the trail leaves at: sqrt(24 sin(pitch - pi)
     * + 25), where pitch is the player's look pitch recovered by inverting y = atan2(sin(x) - 0.75, cos(x)) (SkyHanni's
     * computePitchWeight and getPitchFromDerivative, a 100-step bisection).
     */
    static double controlPointDistance(Vec3 derivative) {
        double xz = Math.sqrt(derivative.x * derivative.x + derivative.z * derivative.z);
        double target = -Math.atan2(derivative.y, xz);
        double guess = target;
        double result = Math.atan2(Math.sin(guess) - 0.75, Math.cos(guess));
        double max = Math.PI / 2;
        double min = -Math.PI / 2;
        for (int i = 0; i < 100; i++) {
            if (result < target) {
                min = guess;
            } else {
                max = guess;
            }
            guess = (min + max) / 2;
            result = Math.atan2(Math.sin(guess) - 0.75, Math.cos(guess));
            if (result == target) {
                break;
            }
        }
        return Math.sqrt(24 * Math.sin(guess - Math.PI) + 25);
    }
}
