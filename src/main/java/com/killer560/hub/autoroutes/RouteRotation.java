package com.killer560.hub.autoroutes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Human-looking camera controller for Auto Routes - killer560: "Smooth rotations like the ones designed for Auto SS
 * (the slight jerk, human-looking)". Modelled on {@code simonsays/SimonSaysFeature}'s Rotate Mode (per-approach
 * smoothing factor, a smoothly-decaying overshoot, an occasional decaying perpendicular curve, a rise-then-fade
 * feint toward the NEXT target, elapsed-tick easing, per-FRAME updates scaled by real elapsed time). SimonSays
 * itself is live-tested and deliberately not refactored; this is its equivalent as a standalone controller.
 * <p>
 * <b>Rotation 360 rule (never wrap the live yaw):</b> the only thing this class ever writes is
 * {@code current + Mth.wrapDegrees(target - current) * step}. {@link #targetYaw} is a target, never assigned to the
 * player; the running yaw Hypixel watches keeps growing past +-360 exactly as a real mouse would leave it. Pitch gets
 * the same +-90 clamp the game applies, nothing more.
 * <p>
 * Two entry points: {@link #beginApproach} rolls a fresh humanized turn (etherwarp / use-item / rotate nodes) and
 * {@link #follow} continuously eases toward a moving target without any rolled randomness (walking a recorded path,
 * where the recorded look itself already carries the human wobble).
 */
public final class RouteRotation {

    private static boolean active;
    private static boolean humanized;
    private static float targetYaw;
    private static float targetPitch;
    private static float smoothing = 0.5f;
    private static float overshootYaw;
    private static float overshootPitch;
    private static boolean curve;
    private static float curveSign = 1f;
    private static float feintYaw;
    private static float feintPitch;
    private static float elapsedTicks;
    private static long lastFrameNanos;
    private static float lastAppliedYaw = Float.NaN;
    private static float lastAppliedPitch = Float.NaN;

    private RouteRotation() {
    }

    /**
     * Rolls a fresh humanized approach to a real-world look direction, the way {@code beginRotateApproach} does:
     * 0.45-0.65 per-tick smoothing, a 20% chance of a small decaying overshoot, a 20% chance of a slight arc, and
     * (when the next node's direction is known) a 30% chance of a feint most of the way toward it first.
     */
    public static void beginApproach(float yaw, float pitch, boolean hasNext, float nextYaw, float nextPitch) {
        active = true;
        humanized = true;
        targetYaw = yaw;
        targetPitch = pitch;
        elapsedTicks = 0f;
        smoothing = 0.45f + (float) (Math.random() * 0.20);
        if (Math.random() < 0.2) {
            overshootYaw = (float) ((Math.random() * 2 - 1) * 1.0);
            overshootPitch = (float) ((Math.random() * 2 - 1) * 0.8);
        } else {
            overshootYaw = 0f;
            overshootPitch = 0f;
        }
        curve = Math.random() < 0.2;
        curveSign = Math.random() < 0.5 ? 1f : -1f;
        feintYaw = 0f;
        feintPitch = 0f;
        if (hasNext && Math.random() < 0.3) {
            // "have it go all the way to the next button essentially then back" - 85% of the real angular gap.
            feintYaw = Mth.wrapDegrees(nextYaw - yaw) * 0.85f;
            feintPitch = Mth.wrapDegrees(nextPitch - pitch) * 0.85f;
        }
    }

    /** Continuous ease toward a (possibly moving) target with no rolled randomness - SimonSays' pre-drift. */
    public static void follow(float yaw, float pitch) {
        if (!active || humanized) {
            overshootYaw = 0f;
            overshootPitch = 0f;
            curve = false;
            feintYaw = 0f;
            feintPitch = 0f;
            smoothing = 0.5f;
            elapsedTicks = 0f;
        }
        active = true;
        humanized = false;
        targetYaw = yaw;
        targetPitch = pitch;
    }

    public static void clear() {
        active = false;
        humanized = false;
        lastFrameNanos = 0L;
        overshootYaw = 0f;
        overshootPitch = 0f;
        feintYaw = 0f;
        feintPitch = 0f;
        curve = false;
        rebase();
    }

    public static boolean isActive() {
        return active;
    }

    /** Forget the last applied rotation after a server-driven one (the etherwarp teleport reply) - the same fix
     *  {@code AutoWalker.rebaseCamera} carries, so the teleport's own camera change isn't read as the user's mouse. */
    public static void rebase() {
        lastAppliedYaw = Float.NaN;
        lastAppliedPitch = Float.NaN;
    }

    /** Remaining angular error to the target (degrees), ignoring the humanization offsets. */
    public static float error() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !active) {
            return 0f;
        }
        return Math.max(Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())),
                Math.abs(Mth.wrapDegrees(targetPitch - player.getXRot())));
    }

    /** SimonSays' "settled near center": close to the target AND the overshoot/feint tails are gone. */
    public static boolean settled(float degrees) {
        return active && error() < degrees && Math.abs(overshootYaw) < 0.3f && Math.abs(overshootPitch) < 0.3f
                && feintYaw == 0f && feintPitch == 0f;
    }

    /** True when the live rotation no longer matches what this class last wrote - the player touched the mouse. */
    public static boolean userMovedCamera(LocalPlayer player) {
        if (player == null || Float.isNaN(lastAppliedYaw)) {
            return false;
        }
        return Math.abs(player.getYRot() - lastAppliedYaw) > 0.05f
                || Math.abs(player.getXRot() - lastAppliedPitch) > 0.05f;
    }

    /** Per-render-frame step (registered on {@code LevelRenderEvents} by the feature, like SimonSays'
     *  {@code tickRotateFrame}) - real mouse look updates every frame, a 20Hz tick update looks stepped. */
    public static void frame() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!active || player == null) {
            lastFrameNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        double dtTicks = lastFrameNanos == 0L ? 1.0 : (now - lastFrameNanos) / 50_000_000.0;
        lastFrameNanos = now;
        dtTicks = Mth.clamp(dtTicks, 0.0, 3.0); // a lag spike / alt-tab must not produce one huge jump
        elapsedTicks += (float) dtTicks;

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float rawYaw = targetYaw;
        float rawPitch = targetPitch;

        if (curve) {
            // Rise over the first 3 ticks, then decay - a slight arc, never a detour (1 degree peak).
            boolean yawDominant = Math.abs(Mth.wrapDegrees(rawYaw - currentYaw))
                    >= Math.abs(Mth.wrapDegrees(rawPitch - currentPitch));
            float t = elapsedTicks;
            float magnitude = t <= 3f ? (t / 3f) : (float) Math.pow(0.35, t - 3f);
            float offset = magnitude * curveSign;
            if (yawDominant) {
                rawPitch += offset;
            } else {
                rawYaw += offset;
            }
        }

        rawYaw += overshootYaw;
        rawPitch += overshootPitch;
        double overshootDecay = Math.pow(0.35, dtTicks);
        overshootYaw *= (float) overshootDecay;
        overshootPitch *= (float) overshootDecay;
        if (Math.abs(overshootYaw) < 0.05f) {
            overshootYaw = 0f;
        }
        if (Math.abs(overshootPitch) < 0.05f) {
            overshootPitch = 0f;
        }

        if (feintYaw != 0f || feintPitch != 0f) {
            // Rise-then-fade envelope so both the reach toward the next node and the return are real motion.
            float ft = elapsedTicks;
            float envelope = ft <= 4f ? (ft / 4f) : (float) Math.pow(0.7, ft - 4f);
            rawYaw += feintYaw * envelope;
            rawPitch += feintPitch * envelope;
            if (envelope < 0.03f) {
                feintYaw = 0f;
                feintPitch = 0f;
            }
        }

        // Delta from the RUNNING yaw - never an assignment of a wrapped absolute (Rotation 360 rule).
        float yawDelta = Mth.wrapDegrees(rawYaw - currentYaw);
        float pitchDelta = Mth.wrapDegrees(rawPitch - currentPitch);
        float frameSmoothing = 1f - (float) Math.pow(1.0 - smoothing, dtTicks);
        float newYaw = currentYaw + yawDelta * frameSmoothing;
        float newPitch = Mth.clamp(currentPitch + pitchDelta * frameSmoothing, -90f, 90f);
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
        player.setXRot(newPitch);
        lastAppliedYaw = player.getYRot();
        lastAppliedPitch = player.getXRot();
    }
}
