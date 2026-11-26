package com.killer560.hub.ap3;

import net.minecraft.util.Mth;

/**
 * Fast Align's tick-exact stop (align12-1110, killer560 2026-11-10: "its only goal should be 2 tick align - 1 tick align",
 * 0.0002 on each axis, counted from the tick that enters the box, which itself does not count).
 * <p>
 * {@link #step} is the 26.3 client's own ground tick on a flat floor of friction 0.6 (LocalPlayer.aiStep and modifyInput,
 * LivingEntity's travel, Entity.getInputVector with Mth's sine table; the push uses the sprint state this tick's
 * sprint logic just set, so a sprint started or stopped moves him at the new speed on that same tick), checked tick for
 * tick against the real client (testkit 1110-align12-phys: 0 of 1200 ticks off at 400, 550 and 600 speed, 2026-11-12). The solver finds discrete keys (WASD, shift, sprint) and a free yaw for every tick so that the LAST
 * tick lands on the point with a movement under 0.0055, which the game zeroes on the next tick: he is stopped.
 * <ul>
 * <li>1 tick after the entering tick: two circles cross ({@link #solve1}), exact.</li>
 * <li>2 ticks: a two-link arm per final-movement guess, sign change and bisection ({@link #solve2}).</li>
 * <li>3 ticks: the entering tick's direction sampled, then the 2-tick solve.</li>
 * </ul>
 * Nothing here writes position or velocity: it only picks keys and a yaw. One instance of scratch state, client thread only.
 */
final class Ap3TickAlign {
    /** On the point (each axis) and stopped is done. */
    static final double TOL = 2.0e-4;
    static final float FRICTION = clamp1(1.0F - (1.0F - 0.6F) * 1.0F) * clamp1(1.0F - (1.0F - 0.91F) * 1.0F);
    /** The last movement must be under this for the next tick to zero it (0.003 / friction, with a margin). */
    private static final double R = 0.003 / FRICTION * 0.97;
    static final double SPRINT_AMT = 0.3F;
    private static final int NR = 3, NA = 16, PHI3 = 8;
    /** Shift (in steps) of solve3's first-direction samples: a retry on the same state looks at the directions between. */
    static double phiOff;

    // The 18 choices of one tick: keys (fw, st), shift and the sprint key.
    private static final int[] FW, ST;
    private static final boolean[] SH, SK;

    static {
        int[][] keys = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 1}};
        int n = 0;
        int[] fw = new int[20], st = new int[20];
        boolean[] sh = new boolean[20], sk = new boolean[20];
        for (int[] k : keys) {
            for (int h = 0; h < 2; h++) {
                for (int s = 0; s < 2; s++) {
                    if (k[0] <= 0 && s == 1) {
                        continue; // the sprint key does nothing without W
                    }
                    fw[n] = k[0];
                    st[n] = k[1];
                    sh[n] = h == 1;
                    sk[n] = s == 1;
                    n++;
                }
            }
        }
        FW = java.util.Arrays.copyOf(fw, n);
        ST = java.util.Arrays.copyOf(st, n);
        SH = java.util.Arrays.copyOf(sh, n);
        SK = java.util.Arrays.copyOf(sk, n);
    }

    static int fw(int c) { return FW[c]; }
    static int st(int c) { return ST[c]; }
    static boolean shift(int c) { return SH[c]; }
    static boolean sprintKey(int c) { return SK[c]; }

    /** Where the box is (world x/z bounds): the entering tick must land in it. */
    static final class Box {
        double x0, z0, x1, z1;

        Box set(double x0, double z0, double x1, double z1) {
            this.x0 = x0; this.z0 = z0; this.x1 = x1; this.z1 = z1;
            return this;
        }

        boolean in(double x, double z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }

        /**
         * Does one tick's move from (ax, az) to (bx, bz) touch the box? A fast run (1.1 to 1.7 blocks a tick) can step over a
         * 1x1 box with no tick ending inside it; the look-ahead counts such a tick as the entering one, and the plan then
         * lands him in it.
         */
        boolean crossed(double ax, double az, double bx, double bz) {
            double dx = bx - ax, dz = bz - az, lo = 0, hi = 1;
            if (dx == 0) {
                if (ax < x0 || ax > x1) {
                    return false;
                }
            } else {
                double a = (x0 - ax) / dx, b = (x1 - ax) / dx;
                lo = Math.max(lo, Math.min(a, b));
                hi = Math.min(hi, Math.max(a, b));
            }
            if (dz == 0) {
                return az >= z0 && az <= z1 && lo <= hi;
            }
            double a = (z0 - az) / dz, b = (z1 - az) / dz;
            return Math.max(lo, Math.min(a, b)) <= Math.min(hi, Math.max(a, b));
        }
    }

    /** The player's state at the END of a tick, what the next tick starts from. */
    static final class S {
        double x, z, vx, vz;
        /** Sprinting; the shift key of the tick just ended (the next tick's crouch); its forward impulse. */
        boolean sprint, shiftPrev, fwdPrev;
        /** getSpeed() as set at the end of the tick just ended (the push uses the speed of its own tick's sprint state). */
        float speed;
        /** MOVEMENT_SPEED without the sprint modifier. */
        double u;
        /** LocalPlayer.sprintTriggerTime (the W double-tap window). */
        int trig;
        /** isSprintingPossible: food above 6 or may fly (off, sprint neither starts nor lasts). */
        boolean canSprint = true;
        /** A mover of the mod drives this tick: a sprint starts only on the sprint key, never on the W double tap
         *  (autosprint/AutoSprintOverrideMixin refuses every other start then). */
        boolean driven = true;

        S set(S o) {
            x = o.x; z = o.z; vx = o.vx; vz = o.vz; sprint = o.sprint; shiftPrev = o.shiftPrev; fwdPrev = o.fwdPrev;
            speed = o.speed; u = o.u; trig = o.trig; canSprint = o.canSprint; driven = o.driven;
            return this;
        }
    }

    /** A plan: choices and world directions (atan2(dz, dx) of the push), tick 0 first. */
    static final class Plan {
        final int[] c = new int[4];
        final double[] phi = new double[4];
        int n;
        /** True when tick 0 is the entering tick (it does not count). */
        boolean entry;

        /** Ticks counted the way killer560 counts them: after the entering tick. */
        int counted() {
            return entry ? n - 1 : n;
        }

        void copyFrom(Plan o) {
            System.arraycopy(o.c, 0, c, 0, 4);
            System.arraycopy(o.phi, 0, phi, 0, 4);
            n = o.n;
            entry = o.entry;
        }
    }

    private static float clamp1(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    static float attrSpeed(double u, boolean sprint) {
        return (float) (sprint ? u * (1.0 + SPRINT_AMT) : u);
    }

    // ---- the replica ------------------------------------------------------------------------------------------------

    private static float inX, inZ;

    /** KeyboardInput's normalized vector through LocalPlayer.modifyInput (x0.98, crouch x0.3, the square mapping). */
    private static void input(int fw, int st, boolean crouching) {
        float f = fw, l = st;
        float dist = (float) Math.sqrt(l * l + f * f);
        if (dist < 1.0E-4F) {
            inX = 0f;
            inZ = 0f;
            return;
        }
        float x = l / dist * 0.98F, y = f / dist * 0.98F;
        if (crouching) {
            x *= (float) 0.3;
            y *= (float) 0.3;
        }
        float len = (float) Math.sqrt(x * x + y * y);
        float dx = x * (1.0F / len), dy = y * (1.0F / len);
        float ax = Math.abs(dx), ay = Math.abs(dy);
        float tan = ay > ax ? ax / ay : ay / ax;
        float ml = Math.min(len * (float) Math.sqrt(1.0F + tan * tan), 1.0F);
        inX = dx * ml;
        inZ = dy * ml;
    }

    /** LocalPlayer.aiStep's sprint start (sprint key or the W double-tap) and stop (no forward impulse). */
    private static void sprintLogic(S s, int fw, boolean crouching, boolean sprintKey) {
        boolean fwd = fw > 0;
        if (s.trig > 0) {
            s.trig--;
        }
        if (s.shiftPrev || fw < 0) {
            s.trig = 0;
        }
        if (!s.sprint && fwd && !crouching && s.canSprint) {
            if (!s.fwdPrev) {
                if (s.trig > 0) {
                    s.sprint = !s.driven;
                } else {
                    s.trig = 7;
                }
            }
            if (sprintKey) {
                s.sprint = true;
            }
        }
        if (s.sprint && (!fwd || !s.canSprint)) {
            s.sprint = false;
        }
        s.fwdPrev = fwd;
    }

    /** One client tick on flat ground, in place. */
    static void step(S s, int fw, int st, boolean shift, boolean sprintKey, float yaw) {
        boolean crouching = s.shiftPrev;
        input(fw, st, crouching);
        sprintLogic(s, fw, crouching, sprintKey);
        if (s.vx * s.vx + s.vz * s.vz < 9.0E-6) {
            s.vx = 0;
            s.vz = 0;
        }
        double ix = inX, iz = inZ, lsq = ix * ix + iz * iz;
        if (lsq >= 1.0E-7) {
            if (lsq > 1.0) {
                double n = Math.sqrt(lsq);
                ix /= n;
                iz /= n;
            }
            float sp = attrSpeed(s.u, s.sprint);
            double mx = ix * sp, mz = iz * sp;
            float r = yaw * (float) (Math.PI / 180.0);
            float sn = Mth.sin(r), cs = Mth.cos(r);
            s.vx += mx * cs - mz * sn;
            s.vz += mz * cs + mx * sn;
        }
        // Entity.move drops a move whose collided length^2 is 1e-7 or less (on the ground the gravity step it asked for
        // keeps the other test false): a nudge of 0.000316 blocks or less does not happen, the velocity stays
        if (s.vx * s.vx + s.vz * s.vz > 1.0E-7) {
            s.x += s.vx;
            s.z += s.vz;
        }
        s.vx *= FRICTION;
        s.vz *= FRICTION;
        s.speed = attrSpeed(s.u, s.sprint);
        s.shiftPrev = shift;
    }

    /** The float yaw that points keys (fw, st) along world angle phi. */
    static float yawFor(double phi, int fw, int st, boolean crouching) {
        input(fw, st, crouching);
        return (float) Math.toDegrees(phi - Math.atan2(inZ, inX));
    }

    /** The yaw to set from {@code current}, never wrapped (an unbounded running yaw is the normal signal). */
    static float turnTo(float current, float yaw) {
        return current + Mth.wrapDegrees(yaw - current);
    }

    private static final S MAG = new S();

    /** The push size of choice c from s: at the speed of the sprint state c's own tick leaves. */
    private static double mag(int c, S s) {
        if (FW[c] == 0 && ST[c] == 0) {
            return 0;
        }
        after(s, c, MAG);
        input(FW[c], ST[c], s.shiftPrev);
        return Math.sqrt((double) inX * inX + (double) inZ * inZ) * MAG.speed;
    }

    /** The state the next tick starts from after choice c (push size aside). */
    private static void after(S s, int c, S out) {
        out.set(s);
        sprintLogic(out, FW[c], out.shiftPrev, SK[c]);
        out.speed = attrSpeed(out.u, out.sprint);
        out.shiftPrev = SH[c];
    }

    private static final S DUP = new S();

    /** True when an earlier choice gives the same push size and the same next state (c adds nothing). */
    private static boolean dup(S s, int c, double k, S n) {
        for (int o = 0; o < c; o++) {
            after(s, o, DUP);
            if (mag(o, s) == k && DUP.sprint == n.sprint && DUP.shiftPrev == n.shiftPrev && DUP.speed == n.speed
                    && DUP.trig == n.trig && DUP.fwdPrev == n.fwdPrev) {
                return true;
            }
        }
        return false;
    }

    // ---- checking a plan on the replica, with the yaws exactly as they will be set ---------------------------------

    private static final S CHK = new S();

    /**
     * Runs plan ticks [from, n) from s with the real yaw chain starting at {@code yaw}: on the point and stopped?
     * <p>
     * No tick may cancel a real velocity down to a move Entity.move drops (ee3-r34f-sim-1125): he then stays put, the
     * client sends no position that tick, and GrimAC, which predicts no tick without one, carries the old velocity into the
     * next and flags Simulation by its whole size (r34f's steered EE3 align: 0.764 cancelled to 0.0001, Simulation .764 on
     * every run). A tick that moves sends its position, and one that starts at rest may stay put.
     */
    static boolean holds(S s, Plan p, int from, float yaw, double tx, double tz, double tol) {
        S q = CHK.set(s);
        dropped = false;
        for (int i = from; i < p.n; i++) {
            int c = p.c[i];
            yaw = turnTo(yaw, yawFor(p.phi[i], FW[c], ST[c], q.shiftPrev));
            boolean moving = q.vx * q.vx + q.vz * q.vz >= 9.0E-6;
            double x0 = q.x, z0 = q.z;
            step(q, FW[c], ST[c], SH[c], SK[c], yaw);
            if (moving && q.x == x0 && q.z == z0) {
                return !(dropped = true);
            }
        }
        return q.vx * q.vx + q.vz * q.vz < 9.0E-6 && Math.abs(q.x - tx) < tol && Math.abs(q.z - tz) < tol;
    }

    /** The last {@link #holds} refused a dropped stop tick; a 1-tick stop was refused so since the search began (log only). */
    private static boolean dropped;
    static boolean refusedOneTick;

    private static final S PATH = new S();
    private static final double[] PATH_XZ = new double[14];

    /**
     * Where each tick of a found approach steer leaves him (x, z pairs in the returned array): his keys at the steered yaw
     * on the approach tick(s), then the plan. AP3 checks each against the floor before it fires: the search models a flat
     * floor everywhere, and on r34f's EE3 a steer through a corner walked him off the ledge into the lava.
     */
    static double[] prePath(PreSearch pr) {
        S q = PATH.set(pr.s);
        float yaw = turnTo(pr.yaw, pr.best);
        step(q, pr.fw, pr.st, pr.shift, pr.sprintKey, yaw);
        int n = 0;
        PATH_XZ[n++] = q.x;
        PATH_XZ[n++] = q.z;
        if (!Float.isNaN(pr.best2)) {
            yaw = turnTo(yaw, pr.best2);
            step(q, pr.fw, pr.st, pr.shift, pr.sprintKey, yaw);
            PATH_XZ[n++] = q.x;
            PATH_XZ[n++] = q.z;
        }
        for (int i = 0; i < pr.plan.n && n < PATH_XZ.length; i++) {
            int c = pr.plan.c[i];
            yaw = turnTo(yaw, yawFor(pr.plan.phi[i], FW[c], ST[c], q.shiftPrev));
            step(q, FW[c], ST[c], SH[c], SK[c], yaw);
            PATH_XZ[n++] = q.x;
            PATH_XZ[n++] = q.z;
        }
        for (int i = n; i < PATH_XZ.length; i++) {
            PATH_XZ[i] = Double.NaN;
        }
        return PATH_XZ;
    }

    // ---- the solver -------------------------------------------------------------------------------------------------

    private static final S A = new S(), B = new S(), C = new S(), D = new S();
    private static final Plan TRY = new Plan();
    private static final double[][] G = new double[NR + 1][NA];
    private static final double[] OUT = new double[6];
    private static double c1x, c1z, c2x, c2z;
    private static int crossings;

    /**
     * The shortest plan from s (the state at the end of the tick just played) to (tx, tz) stopped, written into out; the
     * entering tick (when {@code box} is not null) must land in the box. False when none fits in 3 ticks or the deadline.
     */
    static boolean solve(S s, float yaw, double tx, double tz, Box box, double tol, long deadline, Plan out) {
        out.entry = box != null;
        return solve1(s, yaw, tx, tz, box, tol, out) || System.nanoTime() < deadline && solve2(s, yaw, tx, tz, box, tol, deadline, out)
                || solve3(s, yaw, tx, tz, box, tol, deadline, out);
    }

    // ---- steering the approach tick before the entering tick (killer560 2026-11-10: only while AP3 drives, or on a tick
    // with no input from him) ----------------------------------------------------------------------------------------

    private static final S P = new S(), L1 = new S();
    /** Newton's residuals and its difference step (degrees: the sine table's 0.0055-degree steps make a smaller one noise). */
    private static double g0, g1;
    private static final double NEWTON_H = 0.05;
    private static final int PRE_NG = 5;
    /** The late 1-tick search turns up to 90 degrees on each approach tick (60 found 78% at 400 sprint, 90 found 99.5%). */
    private static final double LATE_SPAN = 90;
    /** The widest turn tried on the approach tick, degrees, and the steps of the 2-tick and 1-tick searches. */
    private static final double PRE_SPAN = 60, PRE_COARSE = 1, PRE_FINE = 0.05;
    private static final int PRE_NC = 2 * (int) (PRE_SPAN / PRE_COARSE), PRE_NF = 2 * (int) (PRE_SPAN / PRE_FINE);
    /** solve2 ran out of time inside a candidate (that candidate is not done). */
    private static boolean timedOut;

    /**
     * The search for the approach tick's yaw, resumable across ticks: started on the predicted state at the END of the tick
     * two before the entering one (he plays keys fw/st/shift/sprintKey on the approach tick with a yaw of our choosing and
     * must still be outside the box after it), it runs a slice each tick until the decision tick. A 1-tick stop first
     * (0.05-degree steps, nearest turn first), then a 1-tick one that enters a tick later: the approach tick AND the tick
     * that would have entered are both steered (he stays outside), and the tick after enters; last a 2-tick stop (1-degree
     * steps; solve2 is the dear one). The late 1-tick stops on the same tick as a 2-tick stop entering on time, and reaches entries no on-time 1-tick stop can
     * (one entering on time must start the entering tick 0.72 to 0.95 blocks from the point at 400 speed; offline 75% of
     * 400-speed and 97% of 600-speed entries find one, align12-1110 env/Pre2). {@link #plan} runs from the entering tick on
     * (entry true), {@link #best} is the approach tick's yaw, {@link #best2} the second approach tick's (NaN: none).
     */
    static final class PreSearch {
        final S s = new S();
        final Box box = new Box();
        final Plan plan = new Plan();
        float yaw, best, best2 = Float.NaN;
        int fw, st;
        boolean shift, sprintKey, found, done;
        double tx, tz, tol;
        private int i, j, l, pairs = -1;
        /** The (entering push, last push) size pairs of a late 1-tick stop. */
        private final double[] pk = new double[64];

        void start(S s0, float yaw, int fw, int st, boolean shift, boolean sprintKey, double tx, double tz, Box b, double tol) {
            s.set(s0);
            box.set(b.x0, b.z0, b.x1, b.z1);
            this.yaw = yaw;
            this.fw = fw;
            this.st = st;
            this.shift = shift;
            this.sprintKey = sprintKey;
            this.tx = tx;
            this.tz = tz;
            this.tol = tol;
            i = 0;
            j = 0;
            l = 0;
            pairs = -1;
            best2 = Float.NaN;
            refusedOneTick = false;
            found = false;
            done = false;
            plan.n = 0;
            plan.entry = true;
        }

        /** The same search: the decision state as predicted before (to 1e-9) and the same keys, yaw and target. */
        boolean same(S o, float yaw, int fw, int st, boolean shift, boolean sprintKey, double tx, double tz) {
            return Math.abs(o.x - s.x) < 1e-9 && Math.abs(o.z - s.z) < 1e-9 && Math.abs(o.vx - s.vx) < 1e-9
                    && Math.abs(o.vz - s.vz) < 1e-9 && o.sprint == s.sprint && o.shiftPrev == s.shiftPrev && o.speed == s.speed
                    && o.trig == s.trig && o.fwdPrev == s.fwdPrev && o.canSprint == s.canSprint && yaw == this.yaw && fw == this.fw && st == this.st
                    && shift == this.shift && sprintKey == this.sprintKey && tx == this.tx && tz == this.tz;
        }

        /** Searches until the deadline; true once there is nothing left to try. */
        boolean run(long deadline) {
            while (!done && j <= PRE_NF) {
                if ((j & 31) == 0 && System.nanoTime() > deadline) {
                    return false;
                }
                float y = turn(j++, PRE_FINE);
                if (preStep(s, y, fw, st, shift, sprintKey, box) && solve1(P, y, tx, tz, box, tol, plan)) {
                    best = y;
                    found = done = true;
                }
            }
            if (!done && pairs < 0) {
                pairs = latePairs();
            }
            while (!done && l < pairs * PRE_NG * PRE_NG) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                int pr = l / (PRE_NG * PRE_NG), g = l % (PRE_NG * PRE_NG);
                l++;
                found = done = late(pk[2 * pr], pk[2 * pr + 1], -LATE_SPAN + 2 * LATE_SPAN * (g / PRE_NG) / (PRE_NG - 1),
                        -LATE_SPAN + 2 * LATE_SPAN * (g % PRE_NG) / (PRE_NG - 1));
            }
            while (!done && i <= PRE_NC) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                float y = turn(i, PRE_COARSE);
                timedOut = false;
                if (preStep(s, y, fw, st, shift, sprintKey, box) && solve2(P, y, tx, tz, box, tol, deadline, plan)) {
                    best = y;
                    found = done = true;
                }
                if (timedOut) {
                    return false;
                }
                i++;
            }
            return done = true;
        }

        /** The size pairs (entering push, last push) after two approach ticks on his keys; their count. */
        private int latePairs() {
            S q = L1.set(s);
            step(q, fw, st, shift, sprintKey, yaw);
            step(q, fw, st, shift, sprintKey, yaw);
            int n = 0;
            for (int c0 = 0; c0 < FW.length; c0++) {
                double k0 = mag(c0, q);
                after(q, c0, A);
                for (int c1 = 0; c1 < FW.length && n < pk.length / 2; c1++) {
                    double k1 = mag(c1, A);
                    boolean seen = SH[c1] || SK[c1] || k1 == 0;
                    for (int m = 0; m < n && !seen; m++) {
                        seen = pk[2 * m] == k0 && pk[2 * m + 1] == k1;
                    }
                    if (!seen) {
                        pk[2 * n] = k0;
                        pk[2 * n + 1] = k1;
                        n++;
                    }
                }
            }
            return n;
        }

        /**
         * Newton from turns (da, db) on the two approach ticks toward the two rings a 1-tick stop needs (the entering tick
         * starts k1 / F from the point, and its push of size k0 can reach the circle the last push cancels), then the exact
         * solve1 on the replica. True with best, best2 and plan set.
         */
        private boolean late(double k0, double k1, double da, double db) {
            for (int it = 0; it < 12; it++) {
                if (!rings(da, db, k0, k1)) {
                    return false;
                }
                double r0 = g0, r1 = g1;
                if (Math.abs(r0) < 2e-4 && Math.abs(r1) < 2e-4) {
                    float a = (float) (yaw + da), b = (float) (yaw + db);
                    if (preStep2(a, b) && solve1(P, b, tx, tz, box, tol, plan)) {
                        best = a;
                        best2 = b;
                        return true;
                    }
                    return false;
                }
                if (!rings(da + NEWTON_H, db, k0, k1)) {
                    return false;
                }
                double a0 = (g0 - r0) / NEWTON_H, a1 = (g1 - r1) / NEWTON_H;
                if (!rings(da, db + NEWTON_H, k0, k1)) {
                    return false;
                }
                double b0 = (g0 - r0) / NEWTON_H, b1 = (g1 - r1) / NEWTON_H, det = a0 * b1 - b0 * a1;
                if (Math.abs(det) < 1e-12) {
                    return false;
                }
                double sa = (r0 * b1 - r1 * b0) / det, sb = (r1 * a0 - r0 * a1) / det;
                double sc = Math.max(1, Math.max(Math.abs(sa), Math.abs(sb)) / 15);
                da -= sa / sc;
                db -= sb / sc;
                if (Math.abs(da) > LATE_SPAN || Math.abs(db) > LATE_SPAN) {
                    return false;
                }
            }
            return false;
        }

        /** Both approach ticks at turns (da, db) into P, both outside the box; g0, g1 the ring residuals there. */
        private boolean rings(double da, double db, double k0, double k1) {
            if (!preStep2((float) (yaw + da), (float) (yaw + db))) {
                return false;
            }
            double vx = P.vx, vz = P.vz;
            if (vx * vx + vz * vz < 9.0E-6) {
                vx = 0;
                vz = 0;
            }
            g0 = Math.hypot(tx - P.x - vx, tz - P.z - vz) - k0;
            g1 = (FRICTION * Math.hypot(tx - P.x, tz - P.z) - k1) / (1 + FRICTION);
            return true;
        }

        private boolean preStep2(float a, float b) {
            L1.set(s);
            step(L1, fw, st, shift, sprintKey, a);
            return !box.in(L1.x, L1.z) && preStep(L1, b, fw, st, shift, sprintKey, box);
        }

        /** Candidate k: 0, +step, -step, +2 step, ... from his yaw. */
        private float turn(int k, double step) {
            return (float) (yaw + ((k & 1) == 0 ? 1 : -1) * ((k + 1) >> 1) * step);
        }
    }

    /** The approach tick at yaw y into P; true if he is still outside the box after it. */
    private static boolean preStep(S s, float y, int fw, int st, boolean shift, boolean sprintKey, Box box) {
        P.set(s);
        step(P, fw, st, shift, sprintKey, y);
        return !box.in(P.x, P.z);
    }

    /** Two ticks: the first push free, the second must leave a movement under R on the point. */
    private static boolean solve1(S s0, float yaw, double tx, double tz, Box box, double tol, Plan out) {
        double vx = s0.vx, vz = s0.vz;
        if (vx * vx + vz * vz < 9.0E-6) {
            vx = 0;
            vz = 0;
        }
        double ax = s0.x + vx, az = s0.z + vz;
        double F = FRICTION;
        for (int c0 = 0; c0 < FW.length; c0++) {
            double k0 = mag(c0, s0);
            after(s0, c0, A);
            if (dup(s0, c0, k0, A)) {
                continue;
            }
            for (int c1 = 0; c1 < FW.length; c1++) {
                if (SH[c1] || SK[c1]) {
                    continue; // the last tick's shift and sprint key change nothing that is left
                }
                double k1 = mag(c1, A);
                // s = the final movement: |T - s - (p0 + v)| = k0 and |(1 + F) s - F (T - p0)| = k1
                double cx = F * (tx - s0.x) / (1 + F), cz = F * (tz - s0.z) / (1 + F);
                int n;
                if (k0 == 0 && k1 == 0) {
                    continue;
                } else if (k0 == 0) {
                    c1x = tx - ax;
                    c1z = tz - az;
                    n = 1;
                } else if (k1 == 0) {
                    c1x = cx;
                    c1z = cz;
                    n = 1;
                } else {
                    n = circles(tx - ax, tz - az, k0, cx, cz, k1 / (1 + F));
                }
                for (int i = 0; i < n; i++) {
                    double sx = i == 0 ? c1x : c2x, sz = i == 0 ? c1z : c2z;
                    if (Math.hypot(sx, sz) > R) {
                        continue;
                    }
                    double m0x = tx - sx - s0.x, m0z = tz - sz - s0.z;
                    if (box != null && !box.in(s0.x + m0x, s0.z + m0z)) {
                        continue;
                    }
                    TRY.n = 2;
                    TRY.c[0] = c0;
                    TRY.c[1] = c1;
                    TRY.phi[0] = Math.atan2(m0z - vz, m0x - vx);
                    TRY.phi[1] = Math.atan2(sz - F * m0z, sx - F * m0x);
                    if (holds(s0, TRY, 0, yaw, tx, tz, tol)) {
                        TRY.entry = out.entry;
                        out.copyFrom(TRY);
                        return true;
                    }
                    refusedOneTick |= dropped;
                }
            }
        }
        return false;
    }

    /** Crossings of two circles into (c1x, c1z) and (c2x, c2z); returns how many. */
    private static int circles(double ax, double az, double r1, double bx, double bz, double r2) {
        double dx = bx - ax, dz = bz - az, d = Math.hypot(dx, dz);
        if (d > r1 + r2 || d < Math.abs(r1 - r2) || d < 1e-12) {
            return 0;
        }
        double a = (r1 * r1 - r2 * r2 + d * d) / (2 * d);
        double h = Math.sqrt(Math.max(0, r1 * r1 - a * a));
        double mx = ax + a * dx / d, mz = az + a * dz / d;
        c1x = mx + h * dz / d;
        c1z = mz - h * dx / d;
        c2x = mx - h * dz / d;
        c2z = mz + h * dx / d;
        return 2;
    }

    /** Three ticks: the first push and the second free, the third must leave a movement under R on the point. */
    private static boolean solve2(S s0, float yaw, double tx, double tz, Box box, double tol, long deadline, Plan out) {
        double vx = s0.vx, vz = s0.vz;
        if (vx * vx + vz * vz < 9.0E-6) {
            vx = 0;
            vz = 0;
        }
        double F = FRICTION;
        for (int c0 = 0; c0 < FW.length; c0++) {
            if (System.nanoTime() > deadline) {
                timedOut = true;
                return false;
            }
            double k0 = mag(c0, s0);
            after(s0, c0, A);
            if (dup(s0, c0, k0, A)) {
                continue;
            }
            for (int c1 = 0; c1 < FW.length; c1++) {
                double k1 = mag(c1, A);
                after(A, c1, B);
                if (dup(A, c1, k1, B)) {
                    continue;
                }
                double L1 = (1 + F) * k0, L2 = k1;
                if (L1 == 0 || L2 == 0) {
                    continue;
                }
                for (int cls2 = 1; cls2 <= 2; cls2++) {
                    double k2 = mag(cls2 == 1 ? STRAIGHT : DIAG, B);
                    for (int el = -1; el <= 1; el += 2) {
                        for (int i = 0; i <= NR; i++) {
                            for (int j = 0; j < NA; j++) {
                                double r = R * i / NR, a = 2 * Math.PI * j / NA;
                                G[i][j] = gval(s0, vx, vz, tx, tz, r * Math.cos(a), r * Math.sin(a), L1, L2, k0, k1, k2, el, false);
                            }
                        }
                        for (int i = 0; i <= NR; i++) {
                            for (int j = 0; j < NA; j++) {
                                for (int q = 0; q < 2; q++) {
                                    int qi = q == 0 ? i : i + 1, qj = q == 0 ? (j + 1) % NA : j;
                                    if (qi > NR) {
                                        continue;
                                    }
                                    double ga = G[i][j], gb = G[qi][qj];
                                    if (Double.isNaN(ga) || Double.isNaN(gb) || Math.signum(ga) == Math.signum(gb)) {
                                        continue;
                                    }
                                    double ra = R * i / NR, aa = 2 * Math.PI * j / NA, rb = R * qi / NR, ab = 2 * Math.PI * qj / NA;
                                    double sax = ra * Math.cos(aa), saz = ra * Math.sin(aa);
                                    double sbx = rb * Math.cos(ab), sbz = rb * Math.sin(ab);
                                    double lo = 0, hi = 1, glo = ga;
                                    for (int it = 0; it < 50; it++) {
                                        double mid = (lo + hi) / 2;
                                        double gm = gval(s0, vx, vz, tx, tz, sax + (sbx - sax) * mid, saz + (sbz - saz) * mid,
                                                L1, L2, k0, k1, k2, el, false);
                                        if (Double.isNaN(gm)) {
                                            break;
                                        }
                                        if (Math.signum(gm) == Math.signum(glo)) {
                                            lo = mid;
                                            glo = gm;
                                        } else {
                                            hi = mid;
                                        }
                                    }
                                    double t = (lo + hi) / 2;
                                    double gm = gval(s0, vx, vz, tx, tz, sax + (sbx - sax) * t, saz + (sbz - saz) * t,
                                            L1, L2, k0, k1, k2, el, true);
                                    if (Double.isNaN(gm) || Math.abs(gm) > 1e-7
                                            || box != null && !box.in(s0.x + OUT[0], s0.z + OUT[1])) {
                                        continue;
                                    }
                                    TRY.n = 3;
                                    TRY.c[0] = c0;
                                    TRY.c[1] = c1;
                                    TRY.c[2] = cls2 == 1 ? STRAIGHT : DIAG;
                                    TRY.phi[0] = OUT[3];
                                    TRY.phi[1] = OUT[4];
                                    TRY.phi[2] = OUT[5];
                                    if (holds(s0, TRY, 0, yaw, tx, tz, tol)) {
                                        TRY.entry = out.entry;
                                        out.copyFrom(TRY);
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Choice indices of plain W and W+A (no shift, no sprint key): the last tick's keys. */
    private static final int STRAIGHT = find(1, 0), DIAG = find(1, 1);

    private static int find(int fw, int st) {
        for (int c = 0; c < FW.length; c++) {
            if (FW[c] == fw && ST[c] == st && !SH[c] && !SK[c]) {
                return c;
            }
        }
        throw new IllegalStateException();
    }

    /** g(s) = |s - F m1| - k2 for the two-link solve of elbow el; NaN if out of reach. keep: fill OUT (m0, -, phi0..2). */
    private static double gval(S s0, double vx, double vz, double tx, double tz, double sx, double sz, double L1, double L2,
            double k0, double k1, double k2, int el, boolean keep) {
        double F = FRICTION;
        double wx = tx - sx - s0.x - (1 + F) * vx, wz = tz - sz - s0.z - (1 + F) * vz;
        double d = Math.hypot(wx, wz);
        if (d > L1 + L2 || d < Math.abs(L1 - L2) || d < 1e-12) {
            return Double.NaN;
        }
        double ph0 = Math.atan2(wz, wx) + el * Math.acos(Math.max(-1, Math.min(1, (L1 * L1 + d * d - L2 * L2) / (2 * L1 * d))));
        double e0x = Math.cos(ph0), e0z = Math.sin(ph0);
        double e1x = (wx - L1 * e0x) / L2, e1z = (wz - L1 * e0z) / L2;
        double m0x = vx + k0 * e0x, m0z = vz + k0 * e0z;
        double m1x = F * m0x + k1 * e1x, m1z = F * m0z + k1 * e1z;
        double qx = sx - F * m1x, qz = sz - F * m1z;
        if (keep) {
            OUT[0] = m0x;
            OUT[1] = m0z;
            OUT[3] = ph0;
            OUT[4] = Math.atan2(e1z, e1x);
            OUT[5] = Math.atan2(qz, qx);
        }
        return Math.hypot(qx, qz) - k2;
    }

    /** Four ticks: the first tick's direction sampled (box-checked), the rest by the three-tick solve. */
    private static boolean solve3(S s0, float yaw, double tx, double tz, Box box, double tol, long deadline, Plan out) {
        for (int c0 = 0; c0 < FW.length; c0++) {
            double k0 = mag(c0, s0);
            after(s0, c0, A);
            if (dup(s0, c0, k0, A)) {
                continue;
            }
            for (int j = 0; j < PHI3; j++) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                double phi = 2 * Math.PI * (j + phiOff) / PHI3;
                float y0 = turnTo(yaw, yawFor(phi, FW[c0], ST[c0], s0.shiftPrev));
                C.set(s0);
                step(C, FW[c0], ST[c0], SH[c0], SK[c0], y0);
                if (box != null && !box.in(C.x, C.z)) {
                    continue;
                }
                D.set(C);
                if (solve2(D, y0, tx, tz, null, tol, deadline, out)) {
                    for (int i = 3; i > 0; i--) { // this tick in front of the three-tick plan
                        out.c[i] = out.c[i - 1];
                        out.phi[i] = out.phi[i - 1];
                    }
                    out.c[0] = c0;
                    out.phi[0] = phi;
                    out.n = 4;
                    out.entry = box != null;
                    return true;
                }
            }
        }
        return false;
    }
}
