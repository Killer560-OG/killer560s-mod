package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModSounds;
import com.killer560.hub.util.ServerCorrections;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.killer560.hub.compat.McCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Walks the computed path. CHEAT BUILD ONLY - every caller is behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 * <p>
 * Movement is discrete key presses only: a {@code KeyboardInput#tick} mixin replaces the player's {@code Input} record
 * (forward, back, left, right, jump, sprint - each on or off) and the matching {@code moveVector}; nothing writes a
 * position or a velocity.
 * <p>
 * <b>Free camera</b> (killer560, 2026-10-17: "have it put the player in freecam like auto routes and such things and
 * only if they press a key have it stop"): for the whole session the camera is held on its own view with
 * {@code util/ViewFreeze} - his mouse steers that view - while the walker turns the real body ({@link WalkCamera}).
 * Any key or mouse button he presses with no screen open stops it ({@code PathStopKeyMixin}), the Start/Stop key
 * excepted (that one is the toggle); moving the mouse does not. At the end the body is turned back under the view he
 * is looking at, so nothing on screen jumps.
 * <p>
 * <b>Keys from a model, not from a rule</b> ({@link WalkPhysics}): every tick each of the nine key combinations (and
 * sprint) is played one move ahead with his real speed attribute, friction, momentum and air control, and the one
 * whose move lands closest to the wanted velocity is pressed. The wanted speed is capped so that what is left - the
 * slide after the move, or an airborne arc with his Jump Boost - still stops at the soul, and slows for every corner
 * ahead in proportion to how sharp it is. So corners and souls are braked for with S or a release, at whatever speed
 * Hypixel gives him.
 * <p>
 * <b>Back onto the route</b> ({@link LocalPlanner}): in water or lava, fallen well below the route, knocked off it, or
 * with no straight walk to anywhere on it for a moment, he plans a short way back over the loaded blocks (swimming up,
 * out over a bank, up a ladder, jumps his Jump Boost clears) and follows it; with no way at all {@link #needsEscape}
 * hands over to the runner's etherwarp / Ender Pearl escape, or the soul is skipped.
 * <p>
 * Failsafes stop the session with the alarm: a world change (lobby, limbo) or disconnect. Damage, knockback and a death
 * are NOT stops (killer560, 2026-10-11); a server correction is reported and the walk carries on (mod rule,
 * 2026-10-06); a server-set rotation is reported the same way.
 */
public final class AutoWalker {

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
    /** How far short of the soul (feet to its center) the walk aims to come to rest - inside the 3.0 click reach. */
    static final double STOP_SHORT = 1.6;
    /** A turn of less than this along the path is not slowed for. */
    private static final double CORNER_MIN_DEG = 25.0;
    /** Ticks with no straight walk onto the route before a way back is planned. */
    private static final int BLOCKED_TICKS_BEFORE_RESCUE = 8;
    /** This far below the route's next point (feet) is "fallen off". */
    private static final double FALLEN_BELOW = 2.2;
    private static final int MAX_RESCUES_PER_TARGET = 8;

    private static volatile boolean sessionActive;
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
    /** The key combination pressed last tick (index into {@link WalkPhysics#KEYS}), for the switch penalty. */
    private static int lastCombo;

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
    /** Whether this session holds the camera (Auto Walk on). */
    private static boolean viewHeld;

    // ---- the way back onto the route
    private static List<Vec3> rescuePath;
    private static int rescueIndex;
    private static String rescueWhy = "";
    private static int rescuesForTarget;
    private static Vec3 rescueTarget;
    private static int blockedTicks;
    private static int rescueCooldown;
    private static double rescueBest;
    private static long rescueBestAtMs;
    private static boolean needsEscape;
    private static String escapeWhy = "";

    /** Testkit counters. */
    private static int jumpsPressed;
    private static int sidesteps;
    private static int climbTicks;
    private static int failsafes;
    private static String lastFailsafe = "";
    private static int rescuesPlanned;
    private static int rescuesDone;
    private static String lastRescue = "";

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
        respawning = false;
        graceUntilMs = 0L;
        repathOnGround = false;
        clearRescueState();
        rescuesForTarget = 0;
        rescueTarget = null;
        LocalPlayer player = Minecraft.getInstance().player;
        lastHurtTime = player == null ? 0 : player.hurtTime;
        viewHeld = false;
        if (player != null && PathfindingConfig.getInstance().isAutoWalk()) {
            viewHeld = true;
            ViewFreeze.hold(player.getYRot(), Mth.clamp(player.getXRot(), -90f, 90f));
        }
    }

    public static void endSession(String reason) {
        sessionActive = false;
        setWalking(false);
        clearKeys();
        WalkCamera.clear();
        clearRescueState();
        releaseView();
        stopReason = reason;
    }

    /** The body back under the view he is looking at, then the camera let go: nothing on screen moves. */
    private static void releaseView() {
        if (!viewHeld) {
            return;
        }
        viewHeld = false;
        LocalPlayer player = Minecraft.getInstance().player;
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        if (player != null && !Float.isNaN(viewYaw)) {
            float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.clamp(viewPitch, -90f, 90f));
            float shift = yaw - viewYaw;
            player.yBob += shift;
            player.yBobO += shift;
        }
        ViewFreeze.release();
    }

    public static boolean isSessionActive() {
        return sessionActive;
    }

    /** True while this session holds the free camera. */
    public static boolean holdsView() {
        return sessionActive && viewHeld;
    }

    public static String stopReason() {
        return stopReason;
    }

    /** Driving the player right now (the mixin asks this). */
    public static boolean isDriving() {
        return sessionActive && walking && !noCarrot && !respawning && PathfindingConfig.getInstance().isAutoWalk();
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

    /** Forget the last applied rotation after a server-driven one (the etherwarp teleport reply). */
    public static void rebaseCamera() {
        WalkCamera.rebase();
    }

    public static void clearStuck() {
        stuck = false;
        bestRemaining = Double.MAX_VALUE;
        bestRemainingAtMs = System.currentTimeMillis();
    }

    /** A new soul (or a fresh route): the rescue budget starts over. */
    static void newTarget() {
        rescuesForTarget = 0;
        clearRescueState();
        clearStuck();
    }

    /**
     * A key or mouse button he pressed, from {@code PathStopKeyMixin} (no screen open). Stops the session - the
     * "any key" rule - unless it is the Start/Stop key (the poll toggles on that one) or Auto Walk is off (then he is
     * the one walking and his keys are his).
     */
    public static void onPlayerInput(String what, int keyCode) {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED || McCompat.screen(Minecraft.getInstance()) != null) {
            return; // a key typed into chat or a menu is not his input to the world
        }
        if (keyCode != Integer.MIN_VALUE && keyCode == PathfindingConfig.getInstance().getResumeKeyCode()) {
            PathfindingFeature.onStartKey(); // the toggle, taken on the next tick; never an "any key" stop
            return;
        }
        if (!sessionActive || !viewHeld) {
            return;
        }
        stop("you pressed " + what);
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

    // ---- death and knockback: re-path, never stop (killer560, 2026-10-11) ----------------------------------

    private static int damageHits;
    /** How long after a death a server teleport is the respawn, not a correction or a staff check. */
    private static final long RESPAWN_GRACE_MS = 15_000L;
    /** A respawn that never shows a teleport still resumes after this. */
    private static final long RESPAWN_FALLBACK_MS = 3_000L;
    /** Hypixel's own death line ("☠ You died." / "☠ You were killed by Zombie."): a Skyblock death has no death
     *  screen and no 0 health, just this line and a teleport. Anchored on the line start, which no player's chat can
     *  produce (theirs starts with a rank or a name). */
    private static final java.util.regex.Pattern HYPIXEL_DEATH =
            java.util.regex.Pattern.compile("^\\s?☠ You [A-Za-z0-9 ',.()-]{1,80}$");

    /** Waiting for the respawn after a death: no driving, no stuck count, server moves are the respawn. */
    private static boolean respawning;
    private static long diedAtMs;
    private static boolean respawnTeleportSeen;
    private static LocalPlayer playerAtDeath;
    /** Until then a server teleport is still the death's respawn, never a correction or a staff check. */
    private static long graceUntilMs;
    private static boolean repathOnGround;
    private static int deaths;
    private static int knockbacks;
    private static boolean chatSubscribed;
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-pathfinding");

    /** From {@code PathMotionPacketMixin}, main thread: the server says he died. */
    public static void onServerDeath() {
        onDeath("death packet");
    }

    /** Hypixel's death chat line (see {@link #HYPIXEL_DEATH}). */
    private static void onChat(net.minecraft.network.chat.Component message) {
        if (!sessionActive) {
            return; // every chat line passes here; onDeath ignores one outside a session anyway
        }
        String plain = com.killer560.hub.util.ChatObserver.strip(message).trim();
        if (HYPIXEL_DEATH.matcher(plain).matches()) {
            Minecraft.getInstance().execute(() -> onDeath("death message"));
        }
    }

    /** Subscribes the Hypixel death-line listener once (from {@code PathfindingFeature.register}). */
    static void register() {
        if (!chatSubscribed) {
            chatSubscribed = true;
            com.killer560.hub.util.ChatObserver.subscribe(AutoWalker::onChat);
        }
    }

    /**
     * killer560 (2026-10-11): "Make sure that if I die while auto fairy souls is on then it will still work and not
     * think it is a staff check." Nothing is driven until the respawn; the respawn teleport (and its rotation) is
     * neither a correction nor a staff check; then the path is planned again from the respawn point.
     */
    private static void onDeath(String how) {
        if (!sessionActive) {
            return;
        }
        long now = System.currentTimeMillis();
        if (respawning && now - diedAtMs < 2_000L) {
            return; // the packet and the chat line of one death
        }
        deaths++;
        respawning = true;
        respawnTeleportSeen = false;
        diedAtMs = now;
        graceUntilMs = now + RESPAWN_GRACE_MS;
        playerAtDeath = Minecraft.getInstance().player;
        clearKeys();
        releaseFallbackKey();
        clearRescueState();
        WalkCamera.rebase();
        LOGGER.info("[AutoWalker] died ({}) - waiting for the respawn, then re-pathing", how);
    }

    /** Dead and waiting, or inside the grace after the respawn (the world-change hook and the testkit ask). */
    public static boolean inDeathGrace() {
        return sessionActive && (respawning || System.currentTimeMillis() < graceUntilMs);
    }

    /** The respawn is done: a fresh start for the path, the camera and the stuck clock. */
    private static void resumeAfterRespawn(LocalPlayer player, String why) {
        respawning = false;
        graceUntilMs = Math.max(graceUntilMs, System.currentTimeMillis() + 2_000L);
        lastHurtTime = player.hurtTime;
        WalkCamera.rebase();
        clearStuck();
        NavigationManager.recalculate(true);
        LOGGER.info("[AutoWalker] respawned ({}) - re-pathing from {}", why, player.blockPosition());
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

    /** Per render frame: the body's turn (see {@link WalkCamera}). */
    public static void frame() {
        if (sessionActive) {
            WalkCamera.frame(EtherwarpHopper.isBusy() || respawning);
        }
    }

    /** Called every client tick (START, before the player's own tick reads the input). */
    public static void tick(Minecraft client) {
        if (!sessionActive) {
            releaseFallbackKey();
            return;
        }
        LocalPlayer player = client.player;
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (player == null || client.level == null) {
            failsafe("disconnected or left the world");
            return;
        }
        if (viewHeld) {
            // The free camera's lease, renewed every tick of the session (it lapses on its own if this ever stops).
            ViewFreeze.hold(player.getYRot(), player.getXRot());
        }
        if (player.isDeadOrDying()) {
            onDeath("0 health");
        }
        int packets = ServerCorrections.packetCount();
        boolean serverMoved = packets != positionPacketsSeen;
        if (respawning) {
            // Dead: nothing is driven. A vanilla death shows the death screen until he respawns (a new player
            // object); Hypixel's never does - its respawn is a teleport. Either one, or a few seconds, ends the wait.
            positionPacketsSeen = packets;
            clearKeys();
            WalkCamera.rebase();
            if (serverMoved) {
                respawnTeleportSeen = true;
            }
            boolean newPlayer = player != playerAtDeath;
            if (!player.isDeadOrDying() && player.getHealth() > 0f && McCompat.screen(client) == null
                    && (newPlayer || respawnTeleportSeen || System.currentTimeMillis() - diedAtMs > RESPAWN_FALLBACK_MS)) {
                resumeAfterRespawn(player, newPlayer ? "new player" : respawnTeleportSeen ? "respawn teleport" : "timeout");
            }
            return;
        }
        if (McCompat.screen(client) != null) {
            stop("a screen opened");
            return;
        }
        Vec3 motion = pendingMotion;
        pendingMotion = null;
        boolean knocked = motion != null && !EtherwarpHopper.isBusy() && !EnderPearlHopper.isBusy()
                && motion.horizontalDistance() + Math.max(0.0, motion.y - 0.42) > KNOCKBACK_MIN
                && !nearJumpPad(player.position());
        if (knocked) {
            // killer560 (2026-10-11): "have it keep working if it gets knocked back, it just needs to repathfind from
            // its new spot" - with or without the hit that caused it. Planned again now and once more on landing.
            knockbacks++;
            lastHurtTime = player.hurtTime;
            clearStuck();
            clearRescueState();
            repathOnGround = true;
            NavigationManager.recalculate(true);
            LOGGER.info(String.format(Locale.US, "[AutoWalker] knocked back (speed %.2f) - re-pathing", motion.length()));
        } else if (player.hurtTime > lastHurtTime) {
            // Plain damage (no knockback) is not a stop either (killer560, 2026-10-11: "if you take damage with no
            // knockback it should just continue. This can happen very often because of shellwise shard"). Counted and
            // logged, never said (killer560, 2026-10-16: start/stop lines only).
            lastHurtTime = player.hurtTime;
            damageHits++;
            LOGGER.info("[AutoWalker] took damage with no knockback - carrying on");
        } else {
            lastHurtTime = player.hurtTime;
        }
        if (repathOnGround && player.onGround()) {
            repathOnGround = false;
            NavigationManager.recalculate(true);
        }
        if (serverMoved) {
            positionPacketsSeen = packets;
            double moved = ServerCorrections.lastMoveDistance();
            float turned = ServerCorrections.lastRotationChange();
            WalkCamera.rebase();
            if (System.currentTimeMillis() < graceUntilMs) {
                // A late respawn teleport (or its rotation): the death's, not a correction or a staff check.
                NavigationManager.recalculate(true);
                LOGGER.info("[AutoWalker] server move of {} blocks inside the respawn grace - not a correction", moved);
            } else if (!EtherwarpHopper.isBusy() && !EnderPearlHopper.isBusy()) {
                if (moved <= CORRECTION_MAX_BLOCKS && moved >= ServerCorrections.MIN_MOVE_BLOCKS) {
                    // Mod rule (2026-10-06): a correction never stops anything - chat line + alarm, the path is
                    // planned again from where the server put him.
                    ServerCorrections.report("Auto Fairy Souls", "- carrying on from here", moved);
                    clearRescueState();
                    NavigationManager.recalculate(true);
                } else if (moved < ServerCorrections.MIN_MOVE_BLOCKS && turned >= 1.0f) {
                    // A rotation the server set without moving him - what a staff check looks like. Same rule:
                    // reported loudly, never a stop.
                    ServerCorrections.reportEvent("Auto Fairy Souls", String.format(Locale.US,
                            "the server turned your camera %.0f degrees (possible staff check) - carrying on", turned));
                }
            }
        }
        if (!walking || !cfg.isAutoWalk()) {
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
        checkStuck(player);
    }

    private static void drive(Minecraft client, LocalPlayer player, PathfindingConfig cfg) {
        Level level = client.level;
        Vec3 pos = player.position();
        double jumpH = WalkTerrain.jumpHeight(player);
        clearKeys();
        if (rescueCooldown > 0) {
            rescueCooldown--;
        }
        if (needsEscape) {
            return; // nothing to walk to: the runner escapes with a tool or skips the soul
        }
        if (rescuePath != null) {
            driveRescue(level, player, pos, jumpH);
            return;
        }
        Vec3 carrot = chooseCarrot(level, pos, jumpH);
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

        // ---- off the route: water, lava, fallen below it, or blocked - ahead of the route's own ladders, so a pit
        // with a ladder in it is planned out of, not taken for the route's climb (testkit 1017-fairy-pit)
        String why = player.onClimbable() ? null : offRouteReason(level, player, pos, carrot, jumpH);
        if (why != null) {
            startRescue(level, player, pos, jumpH, why);
            if (rescuePath != null) {
                driveRescue(level, player, pos, jumpH);
            }
            return;
        }

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
            WalkCamera.aim(yaw(into, player), -28f);
            wantForward = Math.abs(Mth.wrapDegrees(into - player.getYRot())) < 50f;
            return;
        }

        WalkCamera.aim(yaw(yaw, player), pitch);

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
        if (sidestepTicks > 0) {
            wantForward = true;
            wantLeft = sidestepDir < 0;
            wantRight = sidestepDir > 0;
            return;
        }

        // ---- the keys: the combination whose next move lands closest to the speed the path allows here
        WalkPhysics.Model model = WalkPhysics.read(player);
        double allowed = allowedSpeed(level, player, pos, model);
        double ux = horizontal < 1.0e-6 ? 0 : dx / horizontal;
        double uz = horizontal < 1.0e-6 ? 0 : dz / horizontal;
        boolean sprintOk = cfg.isAutoSprint() && !player.isInWater() && horizontal > 1.5;
        chooseKeys(model, player.getYRot(), ux * allowed, uz * allowed, sprintOk);
        // At the water surface (feet in the water, eyes out) the keys are not steered either: the same flag came from
        // the walker starting from the surface. He sinks or floats there on his own, and steering resumes once
        // his eyes are under.
        if (player.isInWater() && !player.isUnderWater()) {
            wantForward = wantBack = wantLeft = wantRight = false;
        }
        NavigationManager.Target navTarget = NavigationManager.target();
        if (LOGGER.isDebugEnabled() && navTarget != null && navTarget.stayOnArrive && NavigationManager.remainingDistance() < 7.0) {
            LOGGER.debug(String.format(Locale.US, "[AutoWalker] approach: %.2f left, speed %.2f, allowed %.2f, ground %s, "
                            + "keys %s%s%s%s%s", NavigationManager.remainingDistance(), model.speed(), allowed, model.onGround,
                    wantForward ? "W" : "", wantBack ? "S" : "", wantLeft ? "A" : "", wantRight ? "D" : "",
                    wantSprint ? "+sprint" : ""));
        }

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

    /**
     * The fastest move this tick (blocks) from which he can still stop where the walk ends and get round every corner
     * ahead without running past it.
     */
    private static double allowedSpeed(Level level, LocalPlayer player, Vec3 pos, WalkPhysics.Model model) {
        double k = model.friction * WalkPhysics.FRICTION_SCALE;
        double allowed = 10.0;
        NavigationManager.Target target = NavigationManager.target();
        if (target != null && target.stayOnArrive) {
            double remaining = NavigationManager.remainingDistance() - STOP_SHORT;
            allowed = Math.min(allowed, WalkPhysics.speedToStopWithin(remaining, model.friction) + 0.04);
        }
        List<Vec3> path = NavigationManager.path();
        if (path.size() >= 3) {
            int seg = NavigationManager.closestSegment(pos);
            Vec3 proj = NavigationManager.projectOnSegment(pos, path.get(seg), path.get(seg + 1));
            double along = proj.distanceTo(path.get(seg + 1));
            double horizon = 6.0 + model.speed() * 8.0;
            for (int i = seg + 1; i < path.size() - 1 && along < horizon; i++) {
                double turn = turnDegrees(path.get(i - 1), path.get(i), path.get(i + 1));
                if (turn > CORNER_MIN_DEG) {
                    allowed = Math.min(allowed, cornerSpeed(turn) + (1.0 - k) * Math.max(0.0, along - 0.4));
                }
                along += path.get(i).distanceTo(path.get(i + 1));
            }
        }
        return Math.max(0.05, allowed);
    }

    /** Horizontal turn at {@code b}, degrees (0 = straight on). */
    private static double turnDegrees(Vec3 a, Vec3 b, Vec3 c) {
        double ax = b.x - a.x;
        double az = b.z - a.z;
        double bx = c.x - b.x;
        double bz = c.z - b.z;
        double la = Math.sqrt(ax * ax + az * az);
        double lb = Math.sqrt(bx * bx + bz * bz);
        if (la < 0.3 || lb < 0.3) {
            return 0;
        }
        double cos = (ax * bx + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
    }

    /** The move length (blocks a tick) a corner this sharp is taken at: a quarter turn at ~a walk, a slight bend fast. */
    static double cornerSpeed(double turnDeg) {
        double t = Math.min(1.0, Math.max(0.0, (turnDeg - CORNER_MIN_DEG) / (120.0 - CORNER_MIN_DEG)));
        return 0.24 + 1.4 * (1.0 - t) * (1.0 - t);
    }

    /**
     * Sets the want-keys to the combination (of the nine, sprint or not) whose move this tick comes closest to
     * {@code (wantX, wantZ)}. Holding the previous combination is preferred a little, so the keys do not flutter.
     */
    private static void chooseKeys(WalkPhysics.Model model, float bodyYaw, double wantX, double wantZ, boolean sprintOk) {
        int chosen = WalkPhysics.chooseCombo(model, bodyYaw, wantX, wantZ, sprintOk, lastCombo);
        int bestCombo = chosen >> 1;
        boolean bestSprint = (chosen & 1) != 0;
        lastCombo = bestCombo;
        int fw = WalkPhysics.KEYS[bestCombo][0];
        int st = WalkPhysics.KEYS[bestCombo][1];
        wantForward = fw > 0;
        wantBack = fw < 0;
        wantLeft = st > 0;
        wantRight = st < 0;
        wantSprint = bestSprint;
    }

    // ------------------------------------------------------------------ the way back onto the route

    /** Why he is off the route right now, or null. */
    private static String offRouteReason(Level level, LocalPlayer player, Vec3 pos, Vec3 carrot, double jumpH) {
        if (rescueCooldown > 0) {
            return null;
        }
        if (player.isInLava()) {
            return "in lava";
        }
        Vec3 here = NavigationManager.pointAhead(pos, 0.0);
        if (player.isInWater()) {
            Vec3 ahead = NavigationManager.pointAhead(pos, 2.0);
            boolean routeSwims = ahead != null && LocalPlanner.water(level, BlockPos.containing(ahead.x, ahead.y - 0.5, ahead.z));
            if (!routeSwims) {
                return "in water";
            }
        }
        if (here != null && player.onGround() && here.y - 0.5 - pos.y > FALLEN_BELOW
                && Math.hypot(here.x - pos.x, here.z - pos.z) < 12.0) {
            return "fell off the route";
        }
        // No straight walk to the carrot is NOT a reason by itself: real island paths fail the strict check past
        // fences, slabs and foliage all the time, and the carrot fallback and the wall sidestep handle those
        // (testkit 746 planned eight ways back round one wall end before this). Blocked against something while
        // making no progress is: that is "stuck", after STUCK_MS - or sooner when he is pressed against a wall
        // with no straight walk anywhere ahead.
        boolean anyWalkable = carrot != null && WalkTerrain.walkable(level, pos, new Vec3(carrot.x, carrot.y - 0.5, carrot.z), jumpH);
        if (!anyWalkable && player.onGround() && player.horizontalCollision) {
            if (++blockedTicks >= BLOCKED_TICKS_BEFORE_RESCUE + WALL_TICKS_BEFORE_SIDESTEP + SIDESTEP_TICKS) {
                return "blocked, with no straight walk on along the route";
            }
        } else if (anyWalkable) {
            blockedTicks = 0;
        }
        if (stuck) {
            return "stuck";
        }
        return null;
    }

    private static void startRescue(Level level, LocalPlayer player, Vec3 pos, double jumpH, String why) {
        blockedTicks = 0;
        stuck = false;
        if (++rescuesForTarget > MAX_RESCUES_PER_TARGET) {
            fail("tried " + MAX_RESCUES_PER_TARGET + " ways back for this soul");
            return;
        }
        BlockPos start = BlockPos.containing(pos.x, pos.y + 0.01, pos.z);
        boolean ahead = why.startsWith("blocked") || why.startsWith("stuck");
        List<BlockPos> goals = rescueGoals(level, pos, start, ahead);
        long t0 = System.nanoTime();
        List<BlockPos> cells = LocalPlanner.plan(level, start, goals, jumpH);
        long us = (System.nanoTime() - t0) / 1000;
        rescuesPlanned++;
        lastRescue = why;
        if (cells.size() < 2) {
            LOGGER.info("[AutoWalker] {} at {} - no way back over the loaded blocks ({} goals, {} us)", why, start,
                    goals.size(), us);
            fail(why + ", and no way back on foot");
            return;
        }
        rescuePath = LocalPlanner.toPoints(cells);
        rescueIndex = 1;
        rescueWhy = why;
        rescueTarget = rescuePath.get(rescuePath.size() - 1);
        rescueBest = Double.MAX_VALUE;
        rescueBestAtMs = System.currentTimeMillis();
        ModChat.log("Auto Fairy Souls", ModChat.text("Off the route (" + why + ") - "),
                ModChat.dim(cells.size() + " steps back"));
        LOGGER.info("[AutoWalker] {} at {} - {} step(s) back to {} ({} us)", why, start, cells.size(), cells.get(cells.size() - 1), us);
    }

    /** The route's feet cells around {@code start} and the soul (an escape's landing must reach one of them). */
    static List<BlockPos> routeGoals(Level level, Vec3 pos, BlockPos start) {
        return rescueGoals(level, pos, start, false);
    }

    /**
     * Where a way back may end: route points (feet cells he can stand in, not water or lava) and the soul. Blocked or
     * stuck, only points AHEAD of where he is on the route and not right beside him count, or the nearest goal would
     * be the spot he is stuck on.
     */
    private static List<BlockPos> rescueGoals(Level level, Vec3 pos, BlockPos start, boolean aheadOnly) {
        List<BlockPos> goals = new ArrayList<>();
        List<Vec3> path = NavigationManager.path();
        int from = aheadOnly && path.size() >= 2 ? NavigationManager.closestSegment(pos) + 1 : 0;
        for (int i = from; i < path.size(); i++) {
            Vec3 p = path.get(i);
            if (aheadOnly && Math.hypot(p.x - pos.x, p.z - pos.z) < 2.5) {
                continue;
            }
            addGoal(level, start, BlockPos.containing(p.x, p.y - 0.5 + 0.01, p.z), goals);
        }
        NavigationManager.Target target = NavigationManager.target();
        if (target != null) {
            addGoal(level, start, BlockPos.containing(target.pos.x, target.pos.y - 0.5 + 0.01, target.pos.z), goals);
        }
        return goals;
    }

    private static void addGoal(Level level, BlockPos start, BlockPos g, List<BlockPos> goals) {
        if (Math.abs(g.getX() - start.getX()) > LocalPlanner.RADIUS || Math.abs(g.getZ() - start.getZ()) > LocalPlanner.RADIUS
                || Math.abs(g.getY() - start.getY()) > LocalPlanner.HEIGHT) {
            return;
        }
        if (g.distManhattan(start) <= 1 || LocalPlanner.water(level, g) || LocalPlanner.lava(level, g)) {
            return;
        }
        // A graph point can sit a block off the real floor: take the cell on, just above or just below it.
        for (int dy : new int[]{0, 1, -1}) {
            BlockPos c = g.above(dy);
            if (LocalPlanner.cell(level, c) && !LocalPlanner.water(level, c)) {
                goals.add(c);
                return;
            }
        }
    }

    private static void fail(String why) {
        rescuePath = null;
        needsEscape = true;
        escapeWhy = why;
        clearKeys();
    }

    private static void clearRescueState() {
        rescuePath = null;
        rescueIndex = 0;
        blockedTicks = 0;
        needsEscape = false;
        escapeWhy = "";
    }

    /** Following the planned way back: one cell at a time, slow and exact, swimming and jumping as it needs. */
    private static void driveRescue(Level level, LocalPlayer player, Vec3 pos, double jumpH) {
        List<Vec3> pts = rescuePath;
        // Skip cells he has reached, and ones a straight walk on dry ground passes anyway.
        while (rescueIndex < pts.size()) {
            Vec3 p = pts.get(rescueIndex);
            double h = Math.hypot(p.x - pos.x, p.z - pos.z);
            if (h < 0.45 && Math.abs(p.y - pos.y) < 0.9) {
                rescueIndex++;
            } else {
                break;
            }
        }
        if (rescueIndex >= pts.size()) {
            finishRescue("arrived");
            return;
        }
        boolean fluid = player.isInWater() || player.isInLava();
        while (!fluid && player.onGround() && rescueIndex + 1 < pts.size()) {
            Vec3 next = pts.get(rescueIndex + 1);
            Vec3 cur = pts.get(rescueIndex);
            if (Math.abs(next.y - pos.y) < 0.6 && Math.abs(cur.y - pos.y) < 0.6
                    && WalkTerrain.walkable(level, pos, next, jumpH) && !LocalPlanner.water(level, BlockPos.containing(next))) {
                rescueIndex++;
            } else {
                break;
            }
        }
        // Off the planned way (fell again, pushed): plan again from here. Measured to the way's segments up to the cell
        // he is heading for, so a skip ahead along a straight walk is not "off".
        double off = pts.get(0).distanceTo(pos);
        for (int i = 1; i <= Math.min(pts.size() - 1, rescueIndex); i++) {
            off = Math.min(off, NavigationManager.projectOnSegment(pos, pts.get(i - 1), pts.get(i)).distanceTo(pos));
        }
        if (off > 3.0) {
            rescuePath = null;
            startRescue(level, player, pos, jumpH, "off the way back");
            return;
        }
        double toEnd = pos.distanceTo(rescueTarget);
        if (toEnd < rescueBest - 0.5) {
            rescueBest = toEnd;
            rescueBestAtMs = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - rescueBestAtMs > STUCK_MS) {
            rescuePath = null;
            startRescue(level, player, pos, jumpH, "stuck on the way back");
            return;
        }
        Vec3 target = pts.get(rescueIndex);
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = horizontal < 0.25 ? WalkCamera.isActive() ? WalkCamera.targetYaw() : player.getYRot()
                : (float) -Math.toDegrees(Math.atan2(dx, dz));
        WalkCamera.aim(yaw(yaw, player), target.y > pos.y + 0.5 ? -15f : 15f);
        double rise = target.y - pos.y;
        if (fluid) {
            float err = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
            wantForward = horizontal > 0.3 && err < 50f;
            // Swim up toward the cell, out over the bank, and always up out of lava.
            wantJump = player.isInLava() || rise > -0.3 || player.horizontalCollision;
        } else {
            WalkPhysics.Model model = WalkPhysics.read(player);
            boolean turnNext = rescueIndex + 1 < pts.size()
                    && turnDegrees(pos, target, pts.get(rescueIndex + 1)) > CORNER_MIN_DEG;
            double allowed = Math.min(0.22, turnNext || rescueIndex == pts.size() - 1
                    ? WalkPhysics.speedToStopWithin(horizontal, model.friction) + 0.08 : 0.22);
            double ux = horizontal < 1.0e-6 ? 0 : dx / horizontal;
            double uz = horizontal < 1.0e-6 ? 0 : dz / horizontal;
            chooseKeys(model, player.getYRot(), ux * allowed, uz * allowed, false);
            boolean onLadder = player.onClimbable();
            if (onLadder && rise > 0.3) {
                wantJump = true; // vanilla climbs while jump is held on a ladder, whichever way he faces
                climbTicks++;
            } else if (player.onGround() && rise > player.maxUpStep() + 0.01 && rise <= jumpH && horizontal < 1.7) {
                wantJump = true;
                jumpsPressed++;
            }
        }
    }

    private static void finishRescue(String how) {
        LOGGER.info("[AutoWalker] back on the route ({}, after {})", how, rescueWhy);
        rescuesDone++;
        rescuePath = null;
        rescueCooldown = 10;
        blockedTicks = 0;
        clearStuck();
        NavigationManager.recalculate(true);
    }

    /** No way back on foot: the runner escapes with a tool it is allowed, or skips the soul. */
    public static boolean needsEscape() {
        return sessionActive && needsEscape;
    }

    public static String escapeWhy() {
        return escapeWhy;
    }

    /** After an escape landed (or a skip): walk on from wherever he is now. */
    static void escaped() {
        clearRescueState();
        rescueCooldown = 10;
        clearStuck();
        NavigationManager.recalculate(true);
    }

    public static boolean isRescuing() {
        return rescuePath != null;
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
            if (WalkTerrain.walkable(level, pos, new Vec3(c.x, c.y - 0.5, c.z), jumpH)) {
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
        IslandGraph graph = GraphRepository.get(island);
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

    /** No progress for a few seconds while walking: plan a way round (see {@link #offRouteReason}). */
    private static void checkStuck(LocalPlayer player) {
        double remaining = rescuePath != null ? player.position().distanceTo(rescueTarget)
                : NavigationManager.remainingDistance();
        if (remaining < bestRemaining - STUCK_PROGRESS) {
            bestRemaining = remaining;
            bestRemainingAtMs = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - bestRemainingAtMs > STUCK_MS) {
            stuck = rescuePath == null && !needsEscape;
            bestRemainingAtMs = System.currentTimeMillis();
        }
    }

    /** Aims at an explicit yaw/pitch (a solved projectile angle, e.g. {@link EnderPearlHopper#solve}). */
    public static void lookAtAngle(float yaw, float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        WalkCamera.aim(player == null ? yaw : yaw(yaw, player), pitch);
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
        WalkCamera.aim(yaw(yaw, player), pitch);
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

    public static int damageHits() {
        return damageHits;
    }

    public static int deaths() {
        return deaths;
    }

    public static int knockbacks() {
        return knockbacks;
    }

    public static boolean isRespawning() {
        return respawning;
    }

    public static int failsafes() {
        return failsafes;
    }

    public static String lastFailsafe() {
        return lastFailsafe;
    }

    public static int rescuesPlanned() {
        return rescuesPlanned;
    }

    public static int rescuesDone() {
        return rescuesDone;
    }

    public static String lastRescue() {
        return lastRescue;
    }
}
