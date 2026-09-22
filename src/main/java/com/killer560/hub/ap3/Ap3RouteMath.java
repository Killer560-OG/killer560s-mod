package com.killer560.hub.ap3;

/**
 * The movement model the route optimiser plans on: vanilla's horizontal step (the same one {@link Ap3AlignMath} and
 * {@link Ap3DiscretePlanner} use for an align) plus the vertical side, so a JUMP can be planned as well as a run.
 * No Minecraft types on purpose - the offline harness runs this exact code.
 * <p>
 * Read from 26.1.2: {@code LivingEntity.jumpFromGround} sets {@code vy = 0.42 * blockJumpFactor} (+0.1 per Jump Boost
 * level) and, WHEN SPRINTING, adds {@code 0.2} of horizontal push along the facing - that boost, plus the fact that
 * the air keeps {@code 0.91} of your speed per tick where the ground keeps only {@code friction * 0.91} (0.546 on
 * stone), is why a sprint jump travels further than a run. In the air the input is worth {@code 0.02} (0.026
 * sprinting) instead of the ground speed. Gravity is {@code vy = (vy - 0.08) * 0.98} after the move, so a jump from
 * flat ground is airborne for 12 ticks and peaks at 1.252 blocks.
 */
final class Ap3RouteMath {

    private Ap3RouteMath() {
    }

    /** {@code LivingEntity.getJumpPower}: the standing jump's upward velocity. */
    static final double JUMP_POWER = 0.42;
    /** {@code LivingEntity.jumpFromGround}: the extra horizontal push a SPRINTING jump gets, along the facing. */
    static final double SPRINT_JUMP_BOOST = 0.2;
    /** {@code LivingEntity.travel}: gravity per tick, applied before the drag. */
    static final double GRAVITY = 0.08;
    /** {@code LivingEntity.travel}: the vertical drag per tick. */
    static final double VERTICAL_DRAG = 0.98;
    /** {@code LivingEntity.travelInAir}: the horizontal multiplier with no block under you. */
    static final double AIR_DRAG = 0.91;

    // ---- lava bounce (measured, not guessed) -----------------------------------------------------------------
    // From killer560's own /lavalab runs, 2026-09-22 (two sessions, 30 bounces). Landing in lava launches you with a
    // single upward impulse, and everything after it is ordinary air flight - the recorded velocities follow
    // (vy - 0.08) * 0.98 to five decimals. The impulse itself is quantised, not a curve:
    //     impulse = 2.25 * (looking up ? 1.35 : 1) + (jump held ? 0.04 : 0)      [blocks on the launch tick]
    //     the velocity left after that tick = impulse * 0.8 - 0.025             [lava drag, then normal air]
    // which reproduces every measured bounce exactly: 1.775 / 1.807 / 2.405 / 2.437. Peaks: about 18.5 blocks
    // (flat), 19.2 (jump held), 30.5-31 (looking up). A bounce against a wall gives only 0.30 - killer560: "the ones
    // where it didn't bounce very high (the really low ones) I ran up against a wall" - so a wall kills it.

    /**
     * How long after touching lava the launch lands, nominally. killer560 (2026-09-22): "sometimes when you enter
     * lava the server lags a hair and you can spend more time in it. It will apply the bounce no matter what,
     * sometimes it's just because of lag in the lava." So the bounce is CERTAIN and only its timing wanders (2-5
     * ticks in his recordings, occasionally much longer): a route plans on this figure and the runner re-plans from
     * the real launch when it actually happens.
     */
    static final int LAVA_BOUNCE_DELAY = 3;

    /** The impulse a lava bounce gives, in blocks of rise on the launch tick. */
    static final double LAVA_BOUNCE = 2.25;
    /** Looking up multiplies it - killer560: "You go higher looking up". */
    static final double LAVA_BOUNCE_LOOK_UP = 1.35;
    /** Holding jump adds vanilla's in-fluid nudge on top. */
    static final double LAVA_BOUNCE_JUMP = 0.04;
    /**
     * The pitch at which the "looking up" bonus kicks in. NOT PINNED YET: measured big at -72.5 and -82.1 degrees,
     * measured small at -13.8 and everything below it, so the step is somewhere in between and this is the midpoint.
     * Re-measure with bounces at -20 / -35 / -50 / -65 at one spot and set this to where it flips.
     */
    static final double LAVA_LOOK_UP_PITCH = -45.0;
    /** What the launch tick keeps: lava's own drag, then the tick's gravity. */
    static final double LAVA_EXIT_DRAG = 0.8;
    static final double LAVA_EXIT_DROP = 0.025;

    /** The launch impulse for this look and this key, in blocks of rise on the launch tick. */
    static double lavaBounceImpulse(float pitch, boolean jumpHeld) {
        double impulse = LAVA_BOUNCE * (pitch <= LAVA_LOOK_UP_PITCH ? LAVA_BOUNCE_LOOK_UP : 1.0);
        return impulse + (jumpHeld ? LAVA_BOUNCE_JUMP : 0.0);
    }

    /** The upward velocity left once the launch tick is over - after that it is ordinary air flight. */
    static double lavaBounceExitVy(double impulse) {
        return impulse * LAVA_EXIT_DRAG - LAVA_EXIT_DROP;
    }

    /** How high a bounce carries you above the lava, and how many ticks it takes to get there. */
    static double[] lavaBounceApex(double exitVy, double impulse) {
        double y = impulse; // the launch tick's own rise
        double vy = exitVy;
        int ticks = 1;
        while (vy > 0) {
            y += vy;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            ticks++;
        }
        return new double[]{y, ticks};
    }

    /** One tick of the route model, in place. Ground ticks are exactly {@link Ap3DiscretePlanner#step}'s arithmetic. */
    static void step(RouteState s, Ap3DiscretePlanner.Action a, float yaw, boolean jump, Ap3DiscretePlanner.Model m) {
        // aiStep zeroes a horizontal axis under 0.003 before anything else.
        double vx = Math.abs(s.vx) < Ap3AlignMath.ZERO_VELOCITY ? 0.0 : s.vx;
        double vz = Math.abs(s.vz) < Ap3AlignMath.ZERO_VELOCITY ? 0.0 : s.vz;
        boolean onGround = s.onGround;
        boolean sprintNow = s.sprinting && a.fw() > 0;
        float rad = yaw * Ap3AlignMath.DEG_TO_RAD;
        double cos = m.trig.cos(rad);
        double sin = m.trig.sin(rad);

        if (jump && onGround) {
            // The jump goes in before the tick's input: upward velocity, and the sprint boost along the facing.
            s.vy = JUMP_POWER;
            if (sprintNow) {
                vx += -sin * SPRINT_JUMP_BOOST;
                vz += cos * SPRINT_JUMP_BOOST;
            }
            onGround = false;
        }

        if (!a.none()) {
            double eff = Ap3DiscretePlanner.effectiveLength(a, s.crouching, m.sneakMul);
            // The air's input is a FLAT 0.02 / 0.026 - it does NOT scale with the movement-speed attribute, which is
            // why a jump at killer560's Skyblock speed (550 = attribute 0.55) throws speed away: see groundSpeed().
            double speed = onGround ? groundSpeed(m, sprintNow)
                    : (sprintNow ? Ap3AlignMath.AIR_SPEED_SPRINTING : Ap3AlignMath.AIR_SPEED);
            double norm = Math.sqrt(a.fw() * a.fw() + a.st() * a.st());
            double ux = a.st() / norm;
            double uz = a.fw() / norm;
            double mag = speed * eff;
            vx += mag * (ux * cos - uz * sin);
            vz += mag * (uz * cos + ux * sin);
        }

        s.x += vx;
        s.z += vz;
        double drag = onGround ? Ap3AlignMath.frictionMultiplier(m.blockFriction, true) : AIR_DRAG;
        s.vx = vx * drag;
        s.vz = vz * drag;

        if (!onGround) {
            s.y += s.vy;
            s.vy = (s.vy - GRAVITY) * VERTICAL_DRAG;
            if (s.y <= s.groundY && s.vy < 0) {
                // Landing: the box stops on the floor (flat ground under the whole route - see the planner's notes).
                s.y = s.groundY;
                s.vy = 0.0;
                onGround = true;
            }
            s.airTicks++;
        } else {
            s.airTicks = 0;
        }
        s.onGround = onGround;
        s.sprinting = sprintNow;
        s.crouching = a.sneak();
        s.yaw = yaw;
    }

    /** Ground speed for these keys at the model's attribute, independent of the model's own onGround flag. */
    static double groundSpeed(Ap3DiscretePlanner.Model m, boolean sprinting) {
        double attr = sprinting ? m.baseSpeedAttr * Ap3AlignMath.SPRINT_MULTIPLIER : m.baseSpeedAttr;
        return Ap3AlignMath.groundSpeed((float) attr, m.blockFriction);
    }

    /** Position, velocity and the flags one tick of the route model needs. */
    static final class RouteState {
        double x, z, y;
        double vx, vz, vy;
        boolean onGround = true;
        boolean sprinting;
        /** Sneak lands a tick late: this is the PREVIOUS tick's sneak key. */
        boolean crouching;
        int airTicks;
        /** The floor height the jump model lands back on. */
        double groundY;
        float yaw;

        RouteState copy() {
            RouteState c = new RouteState();
            c.x = x;
            c.z = z;
            c.y = y;
            c.vx = vx;
            c.vz = vz;
            c.vy = vy;
            c.onGround = onGround;
            c.sprinting = sprinting;
            c.crouching = crouching;
            c.airTicks = airTicks;
            c.groundY = groundY;
            c.yaw = yaw;
            return c;
        }

        double speed() {
            return Math.sqrt(vx * vx + vz * vz);
        }
    }

    /** The fastest a player can keep moving in this model: the sprint-jump cycle's average, used as the A* bound. */
    static double topSpeed(Ap3DiscretePlanner.Model m, boolean allowJump) {
        double ground = groundTopSpeed(m);
        if (!allowJump) {
            return ground;
        }
        // One 12-tick sprint-jump cycle from the ground top speed: measure it rather than guess.
        RouteState s = new RouteState();
        s.sprinting = true;
        s.vz = ground;
        Ap3DiscretePlanner.Action w = new Ap3DiscretePlanner.Action(1, 0, false);
        double best = ground;
        for (int cycle = 0; cycle < 8; cycle++) {
            double z0 = s.z;
            int ticks = 0;
            step(s, w, 0f, true, m);
            ticks++;
            while (!s.onGround && ticks < 40) {
                step(s, w, 0f, false, m);
                ticks++;
            }
            best = Math.max(best, (s.z - z0) / ticks);
        }
        return best;
    }

    /** Steady speed of a sprint on the ground. */
    static double groundTopSpeed(Ap3DiscretePlanner.Model m) {
        double a = groundSpeed(m, true) * Ap3AlignMath.INPUT_SCALE;
        double f = Ap3AlignMath.frictionMultiplier(m.blockFriction, true);
        return a * f / (1 - f);
    }
}
