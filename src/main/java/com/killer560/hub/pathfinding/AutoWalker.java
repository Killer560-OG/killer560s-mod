package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Walks the computed path. CHEAT BUILD ONLY - every caller is behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} and
 * its own default-off toggle.
 * <p>
 * Movement is driven the way the Interactive Map drives sneak: a {@code KeyboardInput#tick} mixin rewrites the
 * player's {@code Input} record for that tick (and the matching {@code moveVector}), so nothing is typed into the real
 * key bindings and the physical keys stay readable - which is exactly how "stop the moment the player touches WASD"
 * works. Without the mixin (mixin config not loaded) it falls back to holding the forward key mapping, the way
 * {@code autopuzzles/AutoTeleportMaze} does.
 * <p>
 * Rotation follows this mod's Rotation 360 rule: the yaw is only ever moved by the wrapped delta from the player's own
 * running yaw, never set to a wrapped 0-360 value, so the running rotation Hypixel watches stays continuous. Rotation
 * is applied at the END of the client tick so the next frame interpolates from the old value (smooth camera) instead
 * of snapping.
 */
public final class AutoWalker {

    private static final double CARROT_AHEAD = 2.5;
    private static final float SMOOTHING = 0.35f;
    private static final long STUCK_MS = 2500L;
    private static final double STUCK_PROGRESS = 1.0;

    private static boolean sessionActive;
    private static boolean walking;
    private static boolean mixinApplied;
    private static boolean fallbackKeyHeld;

    private static boolean wantJump;
    private static boolean wantSprint;
    /** No path point to walk to this tick: never drive the player blindly forward. */
    private static boolean noCarrot;

    private static float targetYaw;
    private static float targetPitch;
    /** No aim target yet: never turn the camera before the first real target is known. */
    private static boolean aiming;
    private static float lastAppliedYaw = Float.NaN;
    private static float lastAppliedPitch = Float.NaN;

    private static double bestRemaining = Double.MAX_VALUE;
    private static long bestRemainingAtMs;
    private static boolean stuck;
    private static int lastHurtTime;
    private static String stopReason;

    private AutoWalker() {
    }

    // ------------------------------------------------------------------ session

    /** Starts watching for player input / damage; call before {@link #setWalking}. */
    public static void startSession() {
        sessionActive = true;
        aiming = false;
        noCarrot = false;
        stuck = false;
        stopReason = null;
        bestRemaining = Double.MAX_VALUE;
        bestRemainingAtMs = System.currentTimeMillis();
        lastAppliedYaw = Float.NaN;
        lastAppliedPitch = Float.NaN;
        LocalPlayer player = Minecraft.getInstance().player;
        lastHurtTime = player == null ? 0 : player.hurtTime;
    }

    public static void endSession(String reason) {
        sessionActive = false;
        setWalking(false);
        stopReason = reason;
    }

    public static boolean isSessionActive() {
        return sessionActive;
    }

    /** The reason the session ended, for the caller's chat message. */
    public static String stopReason() {
        return stopReason;
    }

    /** Driving the player forward right now (the mixin asks this). */
    public static boolean isDriving() {
        return sessionActive && walking && !noCarrot && PathfindingConfig.getInstance().isAutoWalk();
    }

    public static boolean wantJump() {
        return wantJump;
    }

    public static boolean wantSprint() {
        return wantSprint;
    }

    public static void setWalking(boolean value) {
        if (walking == value) {
            return;
        }
        walking = value;
        noCarrot = false;
        if (!value) {
            releaseFallbackKey();
        }
        bestRemaining = Double.MAX_VALUE;
        bestRemainingAtMs = System.currentTimeMillis();
    }

    public static boolean isWalking() {
        return walking;
    }

    public static boolean isStuck() {
        return stuck;
    }

    /** Forget the last applied rotation after a server-driven one (the etherwarp teleport reply). */

    public static void rebaseCamera() {

        lastAppliedYaw = Float.NaN;

        lastAppliedPitch = Float.NaN;

    }


    public static void clearStuck() {
        stuck = false;
        bestRemaining = Double.MAX_VALUE;
        bestRemainingAtMs = System.currentTimeMillis();
    }

    /** Called from the input mixin when the player presses a movement key themselves. */
    public static void onUserMovementInput() {
        if (sessionActive) {
            stop("you moved");
        }
    }

    public static void onMixinApplied() {
        mixinApplied = true;
    }

    public static boolean mixinApplied() {
        return mixinApplied;
    }

    private static void stop(String reason) {
        endSession(reason);
        AutoSoulRunner.onWalkerStopped(reason);
    }

    // ------------------------------------------------------------------ ticking

    /** Call at the END of every client tick. */
    public static void tick(Minecraft client) {
        if (!sessionActive) {
            releaseFallbackKey();
            return;
        }
        LocalPlayer player = client.player;
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (player == null || client.level == null || !cfg.isAutoWalk()) {
            stop(player == null ? "world change" : "auto walk turned off");
            return;
        }
        if (client.screen != null) {
            stop("a screen opened");
            return;
        }
        if (player.hurtTime > lastHurtTime) {
            lastHurtTime = player.hurtTime;
            stop("you took damage");
            return;
        }
        lastHurtTime = player.hurtTime;
        if (client.options.keyAttack.isDown() || client.options.keyUse.isDown()) {
            stop("you clicked");
            return;
        }
        if (!EtherwarpHopper.isBusy() && !Float.isNaN(lastAppliedYaw)
                && (Math.abs(player.getYRot() - lastAppliedYaw) > 0.05f || Math.abs(player.getXRot() - lastAppliedPitch) > 0.05f)) {
            stop("you moved the camera");
            return;
        }
        if (!mixinApplied && !walking) {
            releaseFallbackKey();
        }
        if (!walking) {
            wantJump = false;
            wantSprint = false;
            if (aiming) {
                applyRotation(player, cfg);
            }
            return;
        }

        Vec3 pos = player.position();
        Vec3 carrot = NavigationManager.pointAhead(pos, CARROT_AHEAD);
        if (carrot == null) {
            // No path to follow: stop driving (the mixin asks isDriving()) and let go of the fallback keys, otherwise
            // the player keeps sprinting forward with no target and the stuck watchdog below never runs.
            wantJump = false;
            wantSprint = false;
            noCarrot = true;
            releaseFallbackKey();
            checkStuck();
            return;
        }
        noCarrot = false;
        double dx = carrot.x - pos.x;
        double dz = carrot.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = carrot.y - (pos.y + 1.0);
        targetYaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        targetPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.5, horizontal))), -25.0, 35.0);
        aiming = true;
        float yawDelta = Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot()));

        // Turn in place before walking off in a wrong direction.
        boolean forward = yawDelta < 70f || horizontal > 4.0;
        wantSprint = cfg.isAutoSprint() && yawDelta < 25f && horizontal > 3.0 && !player.isInWater();
        boolean needsClimb = carrot.y - pos.y > 0.6;
        wantJump = (player.onGround() && needsClimb && horizontal < 3.0)
                || (player.onGround() && player.horizontalCollision)
                || (player.isInWater() && needsClimb);
        applyRotation(player, cfg);
        if (!mixinApplied) {
            client.options.keyUp.setDown(forward);
            client.options.keyJump.setDown(wantJump);
            client.options.keySprint.setDown(wantSprint);
            fallbackKeyHeld = true;
        }

        checkStuck();
    }

    /** No progress for a few seconds while walking: let the caller try an etherwarp rescue or skip the target. */
    private static void checkStuck() {
        double remaining = NavigationManager.remainingDistance();
        if (remaining < bestRemaining - STUCK_PROGRESS) {
            bestRemaining = remaining;
            bestRemainingAtMs = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - bestRemainingAtMs > STUCK_MS) {
            stuck = true;
            bestRemainingAtMs = System.currentTimeMillis();
        }
    }

    /** Rotation 360 rule: move by the wrapped delta from the running yaw, never assign a wrapped yaw. */
    private static void applyRotation(LocalPlayer player, PathfindingConfig cfg) {
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float yawDelta = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDelta = Mth.wrapDegrees(targetPitch - currentPitch);
        float stepYaw = yawDelta * SMOOTHING;
        float stepPitch = pitchDelta * SMOOTHING;
        float cap = cfg.getRotationSpeed();
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
    }

    /** Aims at an explicit yaw/pitch (a solved projectile angle, e.g. {@link EnderPearlHopper#solve}) rather than a
     *  straight look-at point - a thrown pearl's arc needs a pitch that does NOT point at the target. */
    public static void lookAtAngle(float yaw, float pitch) {
        targetYaw = yaw;
        targetPitch = pitch;
        aiming = true;
    }

    /** Aims at a point without walking (used before an etherwarp / when clicking a soul). */
    public static void lookAt(Vec3 point) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        targetYaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        aiming = true;
    }

    /** How far the camera still has to turn to the current aim target, in degrees. */
    public static float aimError() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !aiming) {
            return 0f;
        }
        float yaw = Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot()));
        float pitch = Math.abs(Mth.wrapDegrees(targetPitch - player.getXRot()));
        return Math.max(yaw, pitch);
    }

    private static void releaseFallbackKey() {
        if (!fallbackKeyHeld) {
            return;
        }
        fallbackKeyHeld = false;
        Minecraft client = Minecraft.getInstance();
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }

    static void announceStop(String feature, String reason) {
        if (reason != null && PathfindingConfig.getInstance().isChatFeedback()) {
            ModChat.send(feature, ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
        }
    }
}
