package com.killer560.hub.pathfinding;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The walker's model of what the game does with a key press, read off the player each tick (killer560, 2026-10-17: "it
 * was always overshooting not accounting for if it has jump boost or what its speed is"). The old walker pressed W at a
 * carrot up to 8 blocks ahead whenever it faced within 70 degrees of it, and let go only when the runner said "in
 * reach": at Skyblock speed 500-600 one sprinting tick carries ~1.7 blocks and letting go still slides ~2 more, so every
 * corner was run past and every soul approached at full speed.
 * <p>
 * Vanilla's player travel, the same constants AP3's align model uses ({@code ap3/Ap3AlignMath}), javap 26.1.2:
 * <ul>
 *   <li>the input is the key vector (each axis -1/0/1, normalized, x0.98), turned by the body yaw;</li>
 *   <li>on the ground it pushes {@code speed * 0.216 / f^3} (f = the block's friction, 0.6 for most blocks, so exactly
 *       the MOVEMENT_SPEED attribute - Skyblock speed, the Speed effect and the sprint modifier all live there); in the
 *       air 0.02, 0.026 sprinting;</li>
 *   <li>the tick moves by the carried velocity PLUS that push, then the horizontal velocity is multiplied by
 *       {@code f * 0.91} on the ground and 0.91 in the air, and the vertical by 0.98 after gravity 0.08;</li>
 *   <li>a jump sets the vertical speed to the jump strength plus Jump Boost, and a sprinting one adds 0.2 along the
 *       facing.</li>
 * </ul>
 * The walker decides at START_CLIENT_TICK and its keys are read by the same tick's input, so a choice made now is the
 * push of the very next move: the model predicts one move ahead, then the slide that follows with no keys at all.
 */
final class WalkPhysics {

    static final double INPUT_SCALE = 0.98;
    static final double GROUND_CONSTANT = 0.21600002;
    static final double FRICTION_SCALE = 0.91;
    static final double AIR_SPEED = 0.02;
    static final double AIR_SPEED_SPRINTING = 0.025999999;
    static final double SPRINT_MULTIPLIER = 1.3;

    /** The eight key combinations plus "nothing": {forward, strafe} with left = +1, as {@code moveVector} has it. */
    static final int[][] KEYS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** What the game will do, read now. */
    static final class Model {
        /** MOVEMENT_SPEED without the sprint modifier. */
        double walkAttr;
        double friction;
        boolean onGround;
        double vx;
        double vy;
        double vz;
        double jumpVelocity;
        boolean sprinting;
        /**
         * What the input vector is scaled by before it pushes: 1, or the SNEAKING_SPEED attribute (0.3) while he
         * crouches - {@code LocalPlayer.modifyInput} scales the key vector when {@code isMovingSlowly()} (javap 26.3 and
         * 26.2), on the ground and in the air alike.
         */
        double inputScale = 1.0;
        /** SNEAKING_SPEED, read once (the walker sets {@link #inputScale} to it on a tick it crouches). */
        double sneakScale = 0.3;

        /** The ground (or air) push of one tick with a full key, sprinting or not. */
        double push(boolean sprint) {
            if (onGround) {
                double attr = sprint ? walkAttr * SPRINT_MULTIPLIER : walkAttr;
                return attr * (GROUND_CONSTANT / (friction * friction * friction)) * inputScale;
            }
            return (sprint ? AIR_SPEED_SPRINTING : AIR_SPEED) * inputScale;
        }

        double speed() { return Math.sqrt(vx * vx + vz * vz); }
    }

    private WalkPhysics() {
    }

    static Model read(LocalPlayer player) {
        Model m = new Model();
        double attr = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        m.sprinting = player.isSprinting();
        m.walkAttr = m.sprinting ? attr / SPRINT_MULTIPLIER : attr;
        m.friction = blockFriction(player);
        m.onGround = player.onGround();
        Vec3 v = player.getDeltaMovement();
        m.vx = v.x;
        m.vy = v.y;
        m.vz = v.z;
        m.jumpVelocity = WalkTerrain.jumpVelocity(player);
        try {
            m.sneakScale = player.getAttributeValue(Attributes.SNEAKING_SPEED);
        } catch (Throwable t) {
            m.sneakScale = 0.3;
        }
        m.inputScale = player.isMovingSlowly() ? m.sneakScale : 1.0;
        return m;
    }

    // ---- the jump, flown tick by tick (vanilla LivingEntity.travel: y += v, then v = (v - 0.08) * 0.98)

    static final double GRAVITY = 0.08;
    static final double DRAG_Y = 0.98;

    /**
     * Client ticks until feet that are at {@code y} moving up {@code vy} a tick come down through {@code landY} (the tick
     * whose move takes them to or below it), or, when the arc never gets that high, the ticks to its top as a negative
     * number. Bounded at 80 ticks.
     */
    static int ticksToLand(double y, double vy, double landY) {
        double h = y;
        double v = vy;
        boolean above = h >= landY - 1.0e-4;
        for (int t = 1; t <= 80; t++) {
            double next = h + v;
            if (above && next <= landY + 1.0e-4 && v <= 0) {
                return t;
            }
            h = next;
            if (h >= landY - 1.0e-4) {
                above = true;
            }
            v = (v - GRAVITY) * DRAG_Y;
            if (!above && v <= 0) {
                return -t; // never gets up there: this is its top
            }
        }
        return 80;
    }

    /** The highest the feet get above the take-off for a jump of initial speed {@code v0} (no ceiling). */
    static double apex(double v0) {
        double v = v0;
        double h = 0;
        for (int i = 0; i < 80 && v > 0; i++) {
            h += v;
            v = (v - GRAVITY) * DRAG_Y;
        }
        return h;
    }

    static double blockFriction(LocalPlayer player) {
        try {
            return player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
        } catch (Throwable t) {
            return 0.6;
        }
    }

    /**
     * The world direction a key combination pushes, unit length x0.98 (or zero), for body yaw {@code yawDeg}. Vanilla's
     * {@code Entity.getInputVector}: x = strafe*cos - forward*sin, z = forward*cos + strafe*sin.
     */
    static double[] keyDirection(int forward, int strafe, float yawDeg) {
        double len = Math.sqrt(forward * forward + strafe * strafe);
        if (len == 0) {
            return new double[]{0, 0};
        }
        double f = forward / len * INPUT_SCALE;
        double s = strafe / len * INPUT_SCALE;
        double rad = Math.toRadians(yawDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        return new double[]{s * cos - f * sin, f * cos + s * sin};
    }

    /**
     * The key combination (index into {@link #KEYS}, shifted left one bit, with bit 0 set for sprint) whose move this
     * tick comes closest to {@code (wantX, wantZ)}; AutoWalker and ShortWalk both press it. {@code lastCombo} is held a
     * little, so the keys do not flutter.
     */
    static int chooseCombo(Model model, float bodyYaw, double wantX, double wantZ, boolean sprintOk, int lastCombo) {
        int bestCombo = 0;
        boolean bestSprint = false;
        double bestScore = Double.MAX_VALUE;
        double wantLen = Math.sqrt(wantX * wantX + wantZ * wantZ);
        for (int c = 0; c < KEYS.length; c++) {
            int fw = KEYS[c][0];
            int st = KEYS[c][1];
            for (int s = 0; s < 2; s++) {
                boolean sprint = s == 1;
                if (sprint && (!sprintOk || fw <= 0)) {
                    continue;
                }
                double[] dir = keyDirection(fw, st, bodyYaw);
                double push = model.push(sprint);
                double mx = model.vx + dir[0] * push;
                double mz = model.vz + dir[1] * push;
                double ex = mx - wantX;
                double ez = mz - wantZ;
                double score = ex * ex + ez * ez;
                // Faster than wanted costs more than slower: at Skyblock 600 one key is a 0.6-block push, so a tie
                // between "too fast" and "too slow" decided the wrong way slid him onto the soul (testkit
                // 1017-fairy-overshoot: rest 0.05 from it, aiming for 1.6).
                if (wantLen > 1.0e-6 && (mx * wantX + mz * wantZ) / wantLen > wantLen) {
                    score *= 3.0;
                }
                if (c != lastCombo) {
                    score += 0.0004;
                }
                if (fw != 1) {
                    score += 0.00005;
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestCombo = c;
                    bestSprint = sprint;
                }
            }
        }
        return bestCombo << 1 | (bestSprint ? 1 : 0);
    }

    /**
     * The fastest move (blocks this tick) from which he still stops within {@code distance}: the move itself plus the
     * slide after it. On normal ground that is distance / 2.2.
     */
    static double speedToStopWithin(double distance, double friction) {
        double k = friction * FRICTION_SCALE;
        return Math.max(0.0, distance) * (1.0 - k);
    }
}
