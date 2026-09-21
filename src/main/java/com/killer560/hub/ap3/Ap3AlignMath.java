package com.killer560.hub.ap3;

/**
 * The arithmetic of an exact, input-only align: vanilla's horizontal movement step, and its inverse. No Minecraft
 * types on purpose, so the offline harness ({@code wave/AlignSim.java}) runs THIS code against a copy of the vanilla
 * step and the numbers it prints are the numbers the game gets.
 * <p>
 * Every constant and every operation order below is read from the 26.1.2 bytecode (javap on the real jar):
 * <ol>
 * <li>{@code LivingEntity.aiStep}: each delta-movement axis with {@code |v| < 0.003} is set to 0 before anything
 *     else ({@link #zeroSmall});</li>
 * <li>{@code LocalPlayer.applyInput -> modifyInput(moveVector)}: {@code moveVector * 0.98f} (float), then the
 *     square mapping {@code modifyInputSpeedForSquareMovement}: {@code unit * min(len * distanceToUnitSquare(unit), 1)}
 *     - direction kept, length capped; the result is {@code xxa} (strafe, left positive) / {@code zza} (forward);</li>
 * <li>{@code LivingEntity.travelInAir}: {@code f = onGround ? block friction : 1}; speed for the input is
 *     {@code getFrictionInfluencedSpeed(f)} = on the ground {@code getSpeed() * (0.21600002f / (f*f*f))} (float; note
 *     the BLOCK friction goes in here, not the x0.91 one), in the air the flying speed (0.02, 0.026 sprinting);</li>
 * <li>{@code Entity.moveRelative -> getInputVector(input, speed, yRot)}: {@code |input|^2 < 1e-7 -> nothing};
 *     {@code (|input| > 1 ? normalize : input) * speed}, rotated by {@code c = Mth.cos(yaw*0.017453292f)},
 *     {@code s = Mth.sin(...)}: {@code world = (x*c - z*s, z*c + x*s)}; added to the delta movement;</li>
 * <li>{@code Entity.move}: position += delta movement (collisions aside);</li>
 * <li>{@code travelInAir} tail: delta movement *= {@code f * 0.91f} horizontally.</li>
 * </ol>
 * So one tick moves the player by {@code v0eff + speed * effectiveInput(world)} and leaves
 * {@code (v0eff + ...) * f * 0.91} as the next tick's velocity. {@link #solveDelta} picks the world velocity change
 * that lands ON the target this tick when one press can do it (and, the tick after, the one that cancels the friction
 * residual so the position does not move off it); {@link #moveVectorFor} turns that into the exact {@code moveVector}
 * the pipeline above maps back to it. Everything is re-solved every tick from the measured position and velocity.
 */
final class Ap3AlignMath {

    /** {@code LivingEntity.aiStep}: a delta-movement axis below this is zeroed before travel. */
    static final double ZERO_VELOCITY = 0.003;
    /** {@code LocalPlayer.modifyInput}. */
    static final float INPUT_SCALE = 0.98f;
    /** {@code LivingEntity.getFrictionInfluencedSpeed}. */
    static final float GROUND_SPEED_CONSTANT = 0.21600002f;
    /** {@code LivingEntity.travelInAir}: block friction x this = the per-tick horizontal velocity multiplier. */
    static final float FRICTION_SCALE = 0.91f;
    /** {@code Entity.getInputVector}: an input this short (squared) is ignored entirely. */
    static final double INPUT_IGNORED_SQR = 1.0E-7;
    /** {@code Entity.getInputVector}: degrees to radians as the game does it (float). */
    static final float DEG_TO_RAD = 0.017453292f;
    /** {@code Player.getFlyingSpeed} while not creative-flying: 0.02, x1.3 sprinting (0.026). */
    static final double AIR_SPEED = 0.02;
    static final double AIR_SPEED_SPRINTING = 0.025999999;
    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}: +30% MULTIPLY_TOTAL on movement speed. */
    static final double SPRINT_MULTIPLIER = 1.3;
    /** The longest effective input a single straight key press gives ({@code 1 * 0.98}); never exceeded. */
    static final double FULL_PRESS = INPUT_SCALE;

    private Ap3AlignMath() {
    }

    /** The velocity axis value the game will actually use next tick. */
    static double zeroSmall(double v) {
        return Math.abs(v) < ZERO_VELOCITY ? 0.0 : v;
    }

    /** {@code getFrictionInfluencedSpeed} on the ground, in the game's float arithmetic. */
    static float groundSpeed(float movementSpeed, float blockFriction) {
        return movementSpeed * (GROUND_SPEED_CONSTANT / (blockFriction * blockFriction * blockFriction));
    }

    /** The per-tick horizontal velocity multiplier after the move ({@code f * 0.91f}, float, as the game multiplies). */
    static float frictionMultiplier(float blockFriction, boolean onGround) {
        return (onGround ? blockFriction : 1.0f) * FRICTION_SCALE;
    }

    /**
     * The world velocity change to ask for this tick. The tick's displacement is {@code v0eff + dv}, so landing on
     * the target ({@code e} away) needs {@code dv = e - v0eff}; that is applied exactly when it fits inside one full
     * press ({@code |dv| <= maxDelta}), else the full press in its direction (accelerate while the slide falls short,
     * brake while it would carry past). With {@code e ~ 0} the same formula is the brake that cancels the residual.
     * @return {dvx, dvz}
     */
    static double[] solveDelta(double ex, double ez, double v0x, double v0z, double maxDelta) {
        double dvx = ex - v0x;
        double dvz = ez - v0z;
        double need = Math.sqrt(dvx * dvx + dvz * dvz);
        if (need > maxDelta && need > 0) {
            double k = maxDelta / need;
            dvx *= k;
            dvz *= k;
        }
        return new double[]{dvx, dvz};
    }

    /**
     * The {@code moveVector} (x = strafe, left positive; y = forward) whose journey through {@code modifyInput} and
     * {@code getInputVector} at speed {@code speed} and camera {@code (cos, sin)} produces exactly the world velocity
     * change {@code (dvx, dvz)}. Inverse of the pipeline in the class doc: camera-space effective input
     * {@code u = R^-1(dv) / speed} (with the table's {@code c^2 + s^2} divided out so R and R^-1 cancel exactly), then
     * the square mapping undone - {@code |m| = |u| * max(|ux|, |uz|) / |u| / 0.98} along {@code u}. {@code |u|} is
     * capped at {@link #FULL_PRESS}; a {@code u} the game would ignore ({@code |u|^2 < 1e-7}) returns zero.
     * @return {moveX, moveY} as the floats the mixin installs
     */
    static float[] moveVectorFor(double dvx, double dvz, double speed, float cos, float sin) {
        if (speed <= 0) {
            return new float[]{0f, 0f};
        }
        double c = cos;
        double s = sin;
        double norm = c * c + s * s;
        double ux = (dvx * c + dvz * s) / norm / speed;
        double uz = (dvz * c - dvx * s) / norm / speed;
        double len = Math.sqrt(ux * ux + uz * uz);
        if (len * len < INPUT_IGNORED_SQR) {
            return new float[]{0f, 0f};
        }
        if (len > FULL_PRESS) {
            ux *= FULL_PRESS / len;
            uz *= FULL_PRESS / len;
            len = FULL_PRESS;
        }
        double ax = Math.abs(ux) / len;
        double az = Math.abs(uz) / len;
        double magnitude = len * Math.max(ax, az) / INPUT_SCALE;
        return new float[]{(float) (ux / len * magnitude), (float) (uz / len * magnitude)};
    }

    /**
     * Forward model of what the game does with a {@code moveVector} - used by the harness and by the prediction log.
     * {@code onGround} decides the speed formula; {@code speed} is the value {@code getFrictionInfluencedSpeed}
     * returns. Returns the world velocity the input adds this tick: {ax, az}.
     */
    static double[] inputToWorld(float moveX, float moveY, float speed, float cos, float sin) {
        // modifyInput (float)
        float x = moveX * INPUT_SCALE;
        float y = moveY * INPUT_SCALE;
        float lenSq = x * x + y * y;
        if (lenSq == 0f) {
            return new double[]{0, 0};
        }
        float len = (float) Math.sqrt(lenSq);
        float ux = x / len;
        float uy = y / len;
        // distanceToUnitSquare, as the game computes it: sqrt(1 + (smaller / larger)^2) of the unit vector
        float ax = Math.abs(ux);
        float ay = Math.abs(uy);
        float ratio = ay > ax ? ax / ay : ay / ax;
        float d = (float) Math.sqrt(1f + ratio * ratio);
        float scaled = Math.min(len * d, 1f);
        float xxa = ux * scaled;
        float zza = uy * scaled;
        // getInputVector (double)
        double ix = xxa;
        double iz = zza;
        double sq = ix * ix + iz * iz;
        if (sq < INPUT_IGNORED_SQR) {
            return new double[]{0, 0};
        }
        if (sq > 1.0) {
            double l = Math.sqrt(sq);
            ix /= l;
            iz /= l;
        }
        ix *= speed;
        iz *= speed;
        return new double[]{ix * cos - iz * sin, iz * cos + ix * sin};
    }
}
