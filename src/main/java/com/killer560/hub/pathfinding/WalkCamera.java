package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ServerCorrections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The walker's turning: the BODY's real yaw and pitch (what every movement packet reports), moved every render frame
 * by elapsed time. While Auto Fairy Souls runs, the camera is held on its own view ({@code util/ViewFreeze}, the free
 * camera Auto Routes and the auto puzzles use), so none of this is on his screen.
 * <p>
 * killer560, 2026-10-17: "don't have the turn speed as it should attempt to make it look as human as possible so it
 * should be more jittery sometimes and not always perfectly smooth". The old ease was one fixed curve (32% of what was
 * left per tick, capped by the Turn Speed setting), the same shape for every turn. Each turn is now its own movement:
 * <ul>
 *   <li>its peak speed grows with the size of the turn, as a hand's does, and is drawn afresh each time (a quarter
 *       turn peaks somewhere around 15-35 degrees a tick);</li>
 *   <li>it speeds up and slows down at a drawn acceleration, so the speed profile is a ramp, never a step;</li>
 *   <li>about a third of the larger turns overshoot by 3-10% and come back;</li>
 *   <li>a small wandering offset (a few tenths of a degree, now and then a twitch) rides on top, and a turn now and
 *       then stops for a tick or three before going on.</li>
 * </ul>
 * <b>Rotation rules:</b> the only write is {@code current + step}; the running yaw is never wrapped or clamped, pitch
 * stays within +-90. {@link #lock} holds the body perfectly still (no offset either) so a click can go out with the
 * rotation the last movement packet already reported.
 */
public final class WalkCamera {

    private static boolean active;
    private static float targetYaw;
    private static float targetPitch;
    private static long lastFrameNanos;
    private static float lastAppliedYaw = Float.NaN;
    private static float lastAppliedPitch = Float.NaN;
    private static boolean userMoved;
    private static int packetsSeen;
    private static int framesWritten;

    // ---- the current movement
    private static float velYaw;
    private static float velPitch;
    private static float peak = 20f;
    private static float accel = 8f;
    private static float overshoot;
    private static double pauseTicks;
    private static float jitterYaw;
    private static float jitterPitch;
    private static float movementFromYaw = Float.NaN;
    private static boolean locked;
    private static int movements;
    private static int overshoots;
    private static int pauses;

    private WalkCamera() {
    }

    /** Sets (or moves) the aim: {@code yaw} already expressed next to the running yaw. */
    public static void aim(float yaw, float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!active) {
            active = true;
            lastFrameNanos = 0L;
            velYaw = 0f;
            velPitch = 0f;
            rebase();
        }
        float clampedPitch = Mth.clamp(pitch, -90f, 90f);
        if (player != null && (Float.isNaN(movementFromYaw) || Math.abs(Mth.wrapDegrees(yaw - movementFromYaw)) > 12f
                || Math.abs(clampedPitch - targetPitch) > 12f)) {
            newMovement(Math.abs(Mth.wrapDegrees(yaw - player.getYRot())), Math.abs(clampedPitch - player.getXRot()),
                    Math.signum(Mth.wrapDegrees(yaw - player.getYRot())));
            movementFromYaw = yaw;
        }
        targetYaw = yaw;
        targetPitch = clampedPitch;
        locked = false;
    }

    /** Draws a fresh movement for a turn of {@code yawSize}/{@code pitchSize} degrees. */
    private static void newMovement(float yawSize, float pitchSize, float sign) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float size = Math.max(yawSize, pitchSize);
        peak = Mth.clamp((float) ((4.0 + size * r.nextDouble(0.16, 0.38)) * r.nextDouble(0.85, 1.15)), 3.5f, 42f);
        accel = (float) (peak * r.nextDouble(0.3, 0.65));
        overshoot = 0f;
        if (yawSize > 18f && r.nextDouble() < 0.35) {
            overshoot = sign * Math.min(6f, (float) (yawSize * r.nextDouble(0.03, 0.10)));
            overshoots++;
        }
        movements++;
    }

    /** Holds the body exactly where it is (no offset, no movement) until the next {@link #aim}. */
    public static void lock() {
        locked = true;
        velYaw = 0f;
        velPitch = 0f;
    }

    public static boolean isLocked() {
        return locked;
    }

    public static void clear() {
        active = false;
        userMoved = false;
        locked = false;
        lastFrameNanos = 0L;
        velYaw = 0f;
        velPitch = 0f;
        overshoot = 0f;
        pauseTicks = 0;
        jitterYaw = 0f;
        jitterPitch = 0f;
        movementFromYaw = Float.NaN;
        rebase();
    }

    public static boolean isActive() {
        return active;
    }

    public static float targetYaw() {
        return targetYaw;
    }

    public static float targetPitch() {
        return targetPitch;
    }

    /** Forget the last write: the next frame's rotation is not compared against it (a server-set rotation). */
    public static void rebase() {
        lastAppliedYaw = Float.NaN;
        lastAppliedPitch = Float.NaN;
        packetsSeen = ServerCorrections.packetCount();
    }

    /** True once something other than this class turned the body since the last write; cleared by {@link #clear}. */
    public static boolean userMoved() {
        return userMoved;
    }

    /** Frames that wrote a rotation since launch (the testkit reads it to prove the camera moved per frame). */
    public static int framesWritten() {
        return framesWritten;
    }

    /** Testkit read-outs: movements drawn, of them overshooting, micro-pauses taken. */
    public static int movements() {
        return movements;
    }

    public static int overshoots() {
        return overshoots;
    }

    public static int pauses() {
        return pauses;
    }

    /** Remaining turn to the aim, in degrees (the larger of yaw and pitch). */
    public static float error() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !active) {
            return 0f;
        }
        return Math.max(Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())),
                Math.abs(targetPitch - player.getXRot()));
    }

    /**
     * Per render frame. {@code paused} (an etherwarp the Interactive Map's executor is turning for) re-bases and writes
     * nothing, so that turn is neither fought nor read as somebody else's.
     */
    public static void frame(boolean paused) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!active || player == null) {
            lastFrameNanos = 0L;
            return;
        }
        if (paused || ServerCorrections.packetCount() != packetsSeen) {
            rebase();
            lastFrameNanos = 0L;
            velYaw = 0f;
            velPitch = 0f;
            if (paused) {
                return;
            }
        }
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 1.0 : (now - lastFrameNanos) / 50_000_000.0;
        lastFrameNanos = now;
        dt = Mth.clamp(dt, 0.0, 3.0); // an alt-tab or lag spike must not become one big jump
        if (dt <= 0.0) {
            return;
        }

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        if (!Float.isNaN(lastAppliedYaw)
                && (Math.abs(Mth.wrapDegrees(currentYaw - lastAppliedYaw)) > 0.05f
                    || Math.abs(currentPitch - lastAppliedPitch) > 0.05f)) {
            // Something else turned the body (another auto's BodyAim). Carry on from there.
            userMoved = true;
            velYaw = 0f;
            velPitch = 0f;
        }
        if (locked) {
            lastAppliedYaw = currentYaw;
            lastAppliedPitch = currentPitch;
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // The wandering offset: decays toward 0, nudged by noise; now and then a twitch.
        double sq = Math.sqrt(dt);
        jitterYaw += (float) (-jitterYaw * 0.18 * dt + r.nextGaussian() * 0.10 * sq);
        jitterPitch += (float) (-jitterPitch * 0.18 * dt + r.nextGaussian() * 0.06 * sq);
        if (r.nextDouble() < 0.004 * dt) {
            jitterYaw += (float) (r.nextGaussian() * 1.0);
            jitterPitch += (float) (r.nextGaussian() * 0.5);
        }
        jitterYaw = Mth.clamp(jitterYaw, -1.6f, 1.6f);
        jitterPitch = Mth.clamp(jitterPitch, -1.0f, 1.0f);

        float errYaw = Mth.wrapDegrees(targetYaw - currentYaw) + overshoot + jitterYaw;
        float errPitch = targetPitch - currentPitch + jitterPitch;
        if (overshoot != 0f && Math.abs(errYaw - jitterYaw) < 0.8f) {
            // Reached the overshot point: the correction back is the next movement, sometimes after a beat.
            overshoot = 0f;
            velYaw *= 0.2f;
            if (r.nextDouble() < 0.5) {
                pauseTicks = r.nextDouble(0.6, 2.5);
                pauses++;
            }
        }
        if (pauseTicks > 0) {
            pauseTicks -= dt;
            errYaw = jitterYaw * 0.3f;
            errPitch = jitterPitch * 0.3f;
            velYaw = 0f;
            velPitch = 0f;
        } else if (Math.abs(errYaw) > 6f && r.nextDouble() < 0.012 * dt) {
            pauseTicks = r.nextDouble(0.8, 3.0);
            pauses++;
        }
        velYaw = approach(velYaw, profile(errYaw, peak, accel), accel, dt);
        velPitch = approach(velPitch, profile(errPitch, peak * 0.6f, accel * 0.6f), accel * 0.6f, dt);
        float stepYaw = limit(velYaw * (float) dt, errYaw);
        float stepPitch = limit(velPitch * (float) dt, errPitch);
        if (Math.abs(stepYaw) < 1.0e-4f && Math.abs(stepPitch) < 1.0e-4f) {
            lastAppliedYaw = currentYaw;
            lastAppliedPitch = currentPitch;
            return;
        }
        float newYaw = currentYaw + stepYaw;
        float newPitch = Mth.clamp(currentPitch + stepPitch, -90f, 90f);
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
        player.setXRot(newPitch);
        lastAppliedYaw = player.getYRot();
        lastAppliedPitch = player.getXRot();
        framesWritten++;
    }

    /** The speed (degrees per tick) this error wants: up to the peak, and no more than stops in time at {@code a}. */
    private static float profile(float err, float peakSpeed, float a) {
        float mag = Math.abs(err);
        if (mag < 0.02f) {
            return 0f;
        }
        return Math.signum(err) * Math.min(peakSpeed, (float) Math.sqrt(2.0 * a * mag));
    }

    private static float approach(float v, float want, float a, double dt) {
        float max = (float) (a * 1.6 * dt);
        return v + Mth.clamp(want - v, -max, max);
    }

    /** Never step past the point the error aims at (the overshoot is aimed at, not integrated into). */
    private static float limit(float step, float err) {
        if (Math.signum(step) == Math.signum(err) && Math.abs(step) > Math.abs(err)) {
            return err;
        }
        return step;
    }
}
