package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ServerCorrections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The walker's camera: a per-RENDER-FRAME ease toward the aim the walker sets each tick, the technique
 * {@code autoroutes/RouteRotation} (itself SimonSays' Rotate Mode) uses. killer560, 2026-10-11: "the rotation is very
 * jumpy use rotation stuff that we have used from other sections to make it look more clean". The old walker wrote the
 * rotation once a tick, so the camera moved in 20 Hz steps; this moves it every frame by real elapsed time.
 * <p>
 * Kept separate from {@code RouteRotation} on purpose: that one is a single static shared by Auto Routes, AP3 and the
 * Mimic killer, each of which clears it on its own stop paths.
 * <p>
 * <b>Rotation 360 rule:</b> the only write is {@code current + wrapDegrees(target - current) * step}; the running yaw
 * is never wrapped or clamped. Pitch gets the game's own +-90 clamp. It is REAL camera rotation - the player's
 * yaw/pitch, which the movement packets report - never a packet-only rotation.
 * <p>
 * The user's own mouse is read here, BEFORE the write (the write would hide it): a frame whose rotation differs from
 * the last one written latches {@link #userMoved()}. A server position packet (teleport, correction, a rotation the
 * server sets) re-bases instead, so it is never read as the mouse.
 */
public final class WalkCamera {

    /** Share of the remaining turn taken per tick (frame steps are scaled by elapsed time). */
    private static final float SMOOTHING = 0.32f;
    /** Below this many degrees a turn eases out more gently, so a straight walk does not twitch on carrot jitter. */
    private static final float SOFT_ZONE = 6f;

    private static boolean active;
    private static float targetYaw;
    private static float targetPitch;
    private static float maxDegPerTick = 20f;
    private static long lastFrameNanos;
    private static float lastAppliedYaw = Float.NaN;
    private static float lastAppliedPitch = Float.NaN;
    private static boolean userMoved;
    private static int packetsSeen;
    private static int framesWritten;

    private WalkCamera() {
    }

    /** Sets (or moves) the aim. {@code maxSpeed} is the Turn Speed setting, in degrees per tick. */
    public static void aim(float yaw, float pitch, float maxSpeed) {
        if (!active) {
            active = true;
            lastFrameNanos = 0L;
            rebase();
        }
        targetYaw = yaw;
        targetPitch = Mth.clamp(pitch, -90f, 90f);
        maxDegPerTick = Math.max(1f, maxSpeed);
    }

    public static void clear() {
        active = false;
        userMoved = false;
        lastFrameNanos = 0L;
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

    /** True once the player turned the mouse since the camera last wrote; cleared by {@link #clear}. */
    public static boolean userMoved() {
        return userMoved;
    }

    /** Frames that wrote a rotation since launch (the testkit reads it to prove the camera moved per frame). */
    public static int framesWritten() {
        return framesWritten;
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
     * nothing, so that turn is neither fought nor read as the mouse.
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
            if (paused) {
                return;
            }
        }
        long now = System.nanoTime();
        double dtTicks = lastFrameNanos == 0L ? 1.0 : (now - lastFrameNanos) / 50_000_000.0;
        lastFrameNanos = now;
        dtTicks = Mth.clamp(dtTicks, 0.0, 3.0); // an alt-tab or lag spike must not become one big jump

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        if (!Float.isNaN(lastAppliedYaw)
                && (Math.abs(Mth.wrapDegrees(currentYaw - lastAppliedYaw)) > 0.05f
                    || Math.abs(currentPitch - lastAppliedPitch) > 0.05f)) {
            userMoved = true;
            return;
        }
        float yawDelta = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;
        float share = SMOOTHING;
        float magnitude = Math.max(Math.abs(yawDelta), Math.abs(pitchDelta));
        if (magnitude < SOFT_ZONE) {
            share *= 0.5f + 0.5f * magnitude / SOFT_ZONE;
        }
        float frameShare = 1f - (float) Math.pow(1.0 - share, dtTicks);
        float stepYaw = yawDelta * frameShare;
        float stepPitch = pitchDelta * frameShare;
        float cap = (float) (maxDegPerTick * dtTicks);
        float length = (float) Math.sqrt(stepYaw * stepYaw + stepPitch * stepPitch);
        if (length > cap && length > 0f) {
            stepYaw *= cap / length;
            stepPitch *= cap / length;
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
}
