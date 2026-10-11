package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModSounds;
import com.killer560.hub.util.ServerCorrections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.killer560.hub.compat.McCompat;

import java.util.Locale;

/**
 * Walks the computed path. CHEAT BUILD ONLY - every caller is behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} and
 * its own default-off toggle.
 * <p>
 * Movement is discrete key presses only: a {@code KeyboardInput#tick} mixin replaces the player's {@code Input} record
 * (forward, back, left, right, jump, sprint - each on or off) and the matching {@code moveVector}; nothing writes a
 * position or a velocity. The physical keys stay readable, which is how "stop the moment the player touches WASD"
 * works. Without the mixin it falls back to holding the key mappings.
 * <p>
 * The camera is {@link WalkCamera}: a per-frame ease, never a snap, never a wrapped or clamped yaw.
 * <p>
 * Where to walk (2026-10-11 rework, killer560's report): the carrot is the FURTHEST point along the path (up to 8
 * blocks) that a straight walk reaches by {@link WalkTerrain#walkable}, so corners are cut only where the body fits and
 * the walker no longer drags along walls. Jumps come from the block in front of him, not from the path's height. A
 * ladder on the way is climbed facing its wall. Running into a wall anyway sidesteps to the free side, then backs off,
 * before the stuck rescue.
 * <p>
 * Failsafes stop the session with the alarm: damage, knockback (a server velocity packet away from a jump pad),
 * death, a screen, a world change or disconnect, and the player's own keys, clicks or mouse. A server position
 * correction is NOT one of them (mod rule, 2026-10-06): it is reported and the walk carries on from where the server
 * put him; a server-set rotation is reported the same way.
 */
public final class AutoWalker {

    private static final double CARROT_MAX = 8.0;
    private static final double[] CARROT_TRIES = {8.0, 6.0, 4.5, 3.2, 2.2, 1.4};
    private static final long STUCK_MS = 2500L;
    private static final double STUCK_PROGRESS = 1.0;
    /** A server move larger than this is a teleport (warp, pad), which NavigationManager re-plans on - not a correction. */
    private static final double CORRECTION_MAX_BLOCKS = 8.0;
    /** A server velocity this strong away from a jump pad is knockback. */
    private static final double KNOCKBACK_MIN = 0.25;
    private static final int WALL_TICKS_BEFORE_SIDESTEP = 4;
    private static final int SIDESTEP_TICKS = 9;
    private static final int BACKOFF_TICKS = 6;

    private static boolean sessionActive;
    private static int positionPacketsSeen;
    private static boolean walking;
    private static boolean mixinApplied;
    private static boolean fallbackKeyHeld;

    private static boolean wantForward;
    private static boolean wantBack;
    private static boolean wantLeft;
    private static boolean wantRight;
    private static boolean wantJump;
    private static boolean wantSprint;
    private static boolean noCarrot;

    private static int wallTicks;
    private static int sidestepTicks;
    private static int sidestepDir;
    private static int backoffTicks;
    private static boolean climbing;

    private static double bestRemaining = Double.MAX_VALUE;
    private static long bestRemainingAtMs;
    private static boolean stuck;
    private static int lastHurtTime;
    private static String stopReason;
    private static volatile Vec3 pendingMotion;

    /** Testkit counters: jumps pressed, sidesteps, ticks on a ladder, failsafe stops. */
    private static int jumpsPressed;
    private static int sidesteps;
    private static int climbTicks;
    private static int failsafes;
    private static String lastFailsafe = "";

    private AutoWalker() {
    }

    // ------------------------------------------------------------------ session

    /** Starts watching for player input / damage; call before {@link #setWalking}. */
    public static void startSession() {
        sessionActive = true;
        positionPacketsSeen = ServerCorrections.packetCount();
        noCarrot = false;
        stuck = false;
        stopReason = null;
        pendingMotion = null;
        clearKeys();
        wallTicks = 0;
        sidestepTicks = 0;
        backoffTicks = 0;
        climbing = false;
        bestRemaining = Double.MAX_VALUE;
        bestRemainingAtMs = System.currentTimeMillis();
        WalkCamera.clear();
        LocalPlayer player = Minecraft.getInstance().player;
        lastHurtTime = player == null ? 0 : player.hurtTime;
    }

    public static void endSession(String reason) {
        sessionActive = false;
        setWalking(false);
        clearKeys();
        WalkCamera.clear();
        stopReason = reason;
    }

    public static boolean isSessionActive() {
        return sessionActive;
    }

    public static String stopReason() {
        return stopReason;
    }

    /** Driving the player right now (the mixin asks this). */
    public static boolean isDriving() {
        return sessionActive && walking && !noCarrot && PathfindingConfig.getInstance().isAutoWalk();
    }

    public static boolean wantForward() {
        return wantForward;
    }

    public static boolean wantBack() {
        return wantBack;
    }

    public static boolean wantLeft() {
        return wantLeft;
    }

    public static boolean wantRight() {
        return wantRight;
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
            clearKeys();
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
        WalkCamera.rebase();
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

    /** From {@code PathMotionPacketMixin}: the server set the local player's velocity. Netty-safe (main thread). */
    public static void onServerMotion(Vec3 motion) {
        if (sessionActive) {
            pendingMotion = motion;
        }
    }

    private static int deathWatchTicks;

    /** From {@code PathMotionPacketMixin}, main thread: the server says he died. */
    public static void onServerDeath() {
        if (sessionActive) {
            failsafe("you died");
        } else if (deathWatchTicks > 0 && !"you died".equals(lastFailsafe)) {
            // The killing blow's damage already stopped it a tick ago: name the real reason.
            deathWatchTicks = 0;
            lastFailsafe = "you died";
            ModChat.send("Auto Fairy Souls", ModChat.bad("Failsafe: you died"), ModChat.dim(" - the run stays stopped."));
        }
    }

    /** After a damage failsafe: if the same hit killed him, say so (the chat line and {@link #lastFailsafe}). */
    private static void watchForDeath(LocalPlayer player) {
        if (deathWatchTicks <= 0) {
            return;
        }
        deathWatchTicks--;
        if (player != null && player.isDeadOrDying() && !"you died".equals(lastFailsafe)) {
            deathWatchTicks = 0;
            lastFailsafe = "you died";
            ModChat.send("Auto Fairy Souls", ModChat.bad("Failsafe: you died"), ModChat.dim(" - the run stays stopped."));
        }
    }

    private static void stop(String reason) {
        endSession(reason);
        AutoSoulRunner.onWalkerStopped(reason);
    }

    /** A failsafe stop: the alarm plays and the chat line says why. */
    static void failsafe(String reason) {
        failsafes++;
        lastFailsafe = reason;
        ModSounds.playCorrectionAlarm();
        stop("failsafe: " + reason);
    }

    // ------------------------------------------------------------------ ticking

    /** Per render frame: the camera step (see {@link WalkCamera}). */
    public static void frame() {
        if (sessionActive) {
            WalkCamera.frame(EtherwarpHopper.isBusy());
        }
    }

    /** Called every client tick (START, before the player's own tick reads the input). */
    public static void tick(Minecraft client) {
        if (!sessionActive) {
            releaseFallbackKey();
            watchForDeath(client.player);
            return;
        }
        LocalPlayer player = client.player;
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (player == null || client.level == null) {
            failsafe("disconnected or left the world");
            return;
        }
        if (!cfg.isAutoWalk()) {
            stop("auto walk turned off");
            return;
        }
        if (player.isDeadOrDying()) {
            failsafe("you died");
            return;
        }
        if (McCompat.screen(client) != null) {
            stop("a screen opened");
            return;
        }
        if (player.hurtTime > lastHurtTime) {
            lastHurtTime = player.hurtTime;
            failsafe(player.getHealth() <= 0f ? "you died" : "you took damage");
            // The killing blow's damage event can land a tick before the health that says so: watch a few ticks.
            deathWatchTicks = 10;
            return;
        }
        lastHurtTime = player.hurtTime;
        Vec3 motion = pendingMotion;
        pendingMotion = null;
        if (motion != null && !EtherwarpHopper.isBusy() && !EnderPearlHopper.isBusy()
                && motion.horizontalDistance() + Math.max(0.0, motion.y - 0.42) > KNOCKBACK_MIN
                && !nearJumpPad(player.position())) {
            failsafe(String.format(Locale.US, "knockback (server set your speed to %.2f)", motion.length()));
            return;
        }
        int packets = ServerCorrections.packetCount();
        if (packets != positionPacketsSeen) {
            positionPacketsSeen = packets;
            double moved = ServerCorrections.lastMoveDistance();
            float turned = ServerCorrections.lastRotationChange();
            WalkCamera.rebase();
            if (!EtherwarpHopper.isBusy() && !EnderPearlHopper.isBusy()) {
                if (moved <= CORRECTION_MAX_BLOCKS && moved >= ServerCorrections.MIN_MOVE_BLOCKS) {
                    // Mod rule (2026-10-06): a correction never stops anything - chat line + alarm, the path is
                    // planned again from where the server put him.
                    ServerCorrections.report("Auto Fairy Souls", "- carrying on from here", moved);
                    NavigationManager.recalculate(true);
                } else if (moved < ServerCorrections.MIN_MOVE_BLOCKS && turned >= 1.0f) {
                    // A rotation the server set without moving him - what a staff check looks like. Same rule:
                    // reported loudly, never a stop.
                    ServerCorrections.reportEvent("Auto Fairy Souls", String.format(Locale.US,
                            "the server turned your camera %.0f degrees (possible staff check) - carrying on", turned));
                }
            }
        }
        if (client.options.keyAttack.isDown() || client.options.keyUse.isDown()) {
            stop("you clicked");
            return;
        }
        if (WalkCamera.userMoved()) {
            stop("you moved the camera");
            return;
        }
        if (!walking) {
            clearKeys();
            releaseFallbackKey();
            return;
        }

        drive(client, player, cfg);
        if (!mixinApplied) {
            client.options.keyUp.setDown(wantForward);
            client.options.keyDown.setDown(wantBack);
            client.options.keyLeft.setDown(wantLeft);
            client.options.keyRight.setDown(wantRight);
            client.options.keyJump.setDown(wantJump);
            client.options.keySprint.setDown(wantSprint);
            fallbackKeyHeld = true;
        }
        checkStuck();
    }

    private static void drive(Minecraft client, LocalPlayer player, PathfindingConfig cfg) {
        Level level = client.level;
        Vec3 pos = player.position();
        double jumpH = WalkTerrain.jumpHeight(player);
        Vec3 carrot = chooseCarrot(level, pos, jumpH);
        clearKeys();
        if (carrot == null) {
            noCarrot = true;
            releaseFallbackKey();
            return;
        }
        noCarrot = false;
        Vec3 far = NavigationManager.pointAhead(pos, 3.0);
        double dx = carrot.x - pos.x;
        double dz = carrot.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Straight below the carrot (the foot of a climb) the direction is noise: atan2 of two near-zeros spun the
        // camera round and round (testkit 747, 2026-10-11). Hold the current aim there instead.
        float yaw = horizontal < 0.6 ? (WalkCamera.isActive() ? WalkCamera.targetYaw() : player.getYRot())
                : (float) -Math.toDegrees(Math.atan2(dx, dz));
        // A natural walking look: about chest height on the carrot, a little down, never staring at the sky.
        double dy = carrot.y + 1.1 - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(1.0, horizontal))) + 6.0, -30.0, 35.0);

        // ---- ladders: the path climbs and a ladder is right here (or he is on one)
        boolean pathClimbs = far != null && far.y - pos.y > 1.2;
        BlockPos ladder = pathClimbs ? WalkTerrain.climbableNear(level, pos, 1.6) : null;
        climbing = pathClimbs && (player.onClimbable() || ladder != null);
        if (climbing) {
            climbTicks++;
            BlockPos at = player.onClimbable() ? BlockPos.containing(pos) : ladder;
            float into = at == null ? Float.NaN : WalkTerrain.ladderYaw(level, at);
            Vec3 c = Vec3.atCenterOf(ladder != null ? ladder : at);
            double toLadder = Math.hypot(c.x - pos.x, c.z - pos.z);
            if (Float.isNaN(into) || (!player.onClimbable() && toLadder > 0.7)) {
                // Walk to the ladder first (or face a facing-less vine); from beside it, face its wall.
                into = toLadder < 0.2 ? (Float.isNaN(into) ? player.getYRot() : into)
                        : (float) -Math.toDegrees(Math.atan2(c.x - pos.x, c.z - pos.z));
            }
            WalkCamera.aim(yaw(into, player), -28f, cfg.getRotationSpeed());
            wantForward = Math.abs(Mth.wrapDegrees(into - player.getYRot())) < 50f;
            return;
        }

        WalkCamera.aim(yaw(yaw, player), pitch, cfg.getRotationSpeed());
        float yawError = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));

        // ---- walls: blocked, nothing jumpable in front -> sidestep to the free side, then back off
        if (backoffTicks > 0) {
            backoffTicks--;
            wantBack = true;
            wantLeft = sidestepDir < 0;
            wantRight = sidestepDir > 0;
            return;
        }
        boolean blocked = player.horizontalCollision && player.onGround();
        double heading = Math.toRadians(player.getYRot());
        double hx = -Math.sin(heading);
        double hz = Math.cos(heading);
        double riseAhead = Math.max(WalkTerrain.rise(level, pos.x + hx * 0.45, pos.z + hz * 0.45, pos.y),
                WalkTerrain.rise(level, pos.x + hx * 0.85, pos.z + hz * 0.85, pos.y));
        boolean jumpable = riseAhead > player.maxUpStep() + 0.01 && riseAhead <= jumpH - 0.05
                && WalkTerrain.headroom(level, pos.x + hx * 0.85, pos.z + hz * 0.85, pos.y + riseAhead);
        if (sidestepTicks > 0) {
            sidestepTicks--;
        } else if (blocked && !jumpable) {
            if (++wallTicks >= WALL_TICKS_BEFORE_SIDESTEP) {
                wallTicks = 0;
                sidestepDir = freeSide(level, pos, hx, hz, jumpH);
                if (sidestepDir == 0) {
                    backoffTicks = BACKOFF_TICKS;
                    sidestepDir = Math.random() < 0.5 ? -1 : 1;
                } else {
                    sidestepTicks = SIDESTEP_TICKS;
                    sidesteps++;
                }
            }
        } else {
            wallTicks = 0;
        }

        wantForward = yawError < 70f || horizontal > 4.0;
        if (sidestepTicks > 0) {
            wantLeft = sidestepDir < 0;
            wantRight = sidestepDir > 0;
        }
        wantSprint = cfg.isAutoSprint() && yawError < 25f && horizontal > 3.0 && !player.isInWater()
                && sidestepTicks == 0;

        // ---- jumps: an obstacle this jump clears, a gap the path crosses, or swimming up
        if (player.onGround() && wantForward && jumpable) {
            wantJump = true;
        } else if (player.onGround() && wantForward && wantSprint
                && WalkTerrain.dropBelow(level, pos.x + hx * 1.1, pos.z + hz * 1.1, pos.y) > WalkTerrain.MAX_DROP
                && carrot.y - 0.5 >= pos.y - 1.0) {
            wantJump = true;
        } else if (player.isInWater() && carrot.y - 0.5 > pos.y - 0.2) {
            wantJump = true;
        }
        if (wantJump) {
            jumpsPressed++;
        }
    }

    /** The target yaw expressed next to the running yaw, so the camera never turns the long way. */
    private static float yaw(float target, LocalPlayer player) {
        return player.getYRot() + Mth.wrapDegrees(target - player.getYRot());
    }

    /** The furthest point along the path a straight walk reaches, else the path's next vertex. */
    private static Vec3 chooseCarrot(Level level, Vec3 pos, double jumpH) {
        Vec3 last = null;
        for (double ahead : CARROT_TRIES) {
            Vec3 c = NavigationManager.pointAhead(pos, ahead);
            if (c == null) {
                return null;
            }
            last = c;
            // Path points sit half a block above the feet; walkable() compares feet heights.
            if (WalkTerrain.walkable(level, pos, new Vec3(c.x, c.y - 0.5, c.z), WalkTerrain.jumpHeight(
                    Minecraft.getInstance().player))) {
                return c;
            }
        }
        return last;
    }

    /** -1 = left is free, 1 = right is free, 0 = neither (relative to the heading {@code hx, hz}). */
    private static int freeSide(Level level, Vec3 pos, double hx, double hz, double jumpH) {
        // Left of a heading (hx, hz) in Minecraft's yaw convention is (hz, -hx).
        Vec3 left = new Vec3(pos.x + hz * 1.2 + hx * 0.6, pos.y, pos.z - hx * 1.2 + hz * 0.6);
        Vec3 right = new Vec3(pos.x - hz * 1.2 + hx * 0.6, pos.y, pos.z + hx * 1.2 + hz * 0.6);
        boolean l = WalkTerrain.walkable(level, pos, left, jumpH);
        boolean r = WalkTerrain.walkable(level, pos, right, jumpH);
        if (l && r) {
            // Both free: go round on the side the path bends to.
            Vec3 c = NavigationManager.pointAhead(pos, 4.0);
            if (c != null) {
                double cross = hx * (c.z - pos.z) - hz * (c.x - pos.x);
                return cross > 0 ? 1 : -1;
            }
            return -1;
        }
        return l ? -1 : r ? 1 : 0;
    }

    private static boolean nearJumpPad(Vec3 pos) {
        String island = IslandDetector.graphIsland();
        IslandGraph graph = island == null ? null : GraphRepository.get(island);
        if (graph == null) {
            return false;
        }
        for (IslandGraph.Node node : graph.withTag("jump_pad")) {
            if (node.distSq(pos.x, pos.y, pos.z) < 6.0 * 6.0) {
                return true;
            }
        }
        return false;
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

    /** Aims at an explicit yaw/pitch (a solved projectile angle, e.g. {@link EnderPearlHopper#solve}). */
    public static void lookAtAngle(float yaw, float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        WalkCamera.aim(player == null ? yaw : yaw(yaw, player), pitch, PathfindingConfig.getInstance().getRotationSpeed());
    }

    /** Aims at a point without walking (clicking a soul). */
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
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        WalkCamera.aim(yaw(yaw, player), pitch, PathfindingConfig.getInstance().getRotationSpeed());
    }

    /** How far the camera still has to turn to the current aim target, in degrees. */
    public static float aimError() {
        return WalkCamera.error();
    }

    private static void clearKeys() {
        wantForward = false;
        wantBack = false;
        wantLeft = false;
        wantRight = false;
        wantJump = false;
        wantSprint = false;
    }

    private static void releaseFallbackKey() {
        if (!fallbackKeyHeld) {
            return;
        }
        fallbackKeyHeld = false;
        Minecraft client = Minecraft.getInstance();
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }

    /** One line for the start message: this player's jump, and whether Jump Boost (a God Potion) is raising it. */
    static String jumpSummary(LocalPlayer player) {
        int boost = WalkTerrain.jumpBoostLevel(player);
        double h = WalkTerrain.jumpHeight(player);
        return boost > 0
                ? String.format(Locale.US, "Jump Boost %d (god potion?) - jumps %.2f blocks", boost, h)
                : String.format(Locale.US, "no jump boost - jumps %.2f blocks", h);
    }

    // ---- testkit read-outs
    public static int jumpsPressed() {
        return jumpsPressed;
    }

    public static int sidesteps() {
        return sidesteps;
    }

    public static int climbTicks() {
        return climbTicks;
    }

    public static int failsafes() {
        return failsafes;
    }

    public static String lastFailsafe() {
        return lastFailsafe;
    }

    static void announceStop(String feature, String reason) {
        if (reason != null && PathfindingConfig.getInstance().isChatFeedback()) {
            ModChat.send(feature, ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
        }
    }
}
