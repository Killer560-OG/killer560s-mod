package com.killer560.hub.ap3;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.autoroutes.RouteRotation;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.FastLeapConfig;
import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;
import com.killer560.hub.fastleap.LeapManager;
import com.killer560.hub.fastleap.PosmsgTargets;
import com.killer560.hub.fastleap.Teammates;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one AP3 chain: the movement, alignment, waiting and advancing. CHEAT BUILD ONLY - only ever started by
 * {@link Ap3Feature} behind {@link Ap3Config#isEnabled()} and the BOSS-ONLY gate (any boss phase, one chain per
 * {@link Ap3Area}).
 * <p>
 * <b>Movement</b> is written as the analog {@code moveVector} through {@code mixin/Ap3InputMixin} (falling back to
 * holding the key mappings when the mixin config is not loaded, exactly like {@code RouteExecutor}). A WALK / RUN
 * node moves in the world direction its yaw was recorded in <b>without turning the camera</b> - killer560: "whenever
 * I use a walk node, it does not actually make my character face that way, but it will move that way" - by
 * projecting that fixed world direction into the player's LIVE facing frame every tick ({@link #writeMove}).
 * <p>
 * <b>Rotation</b> (LOOK only) is a wrapped delta on the running yaw through {@link RouteRotation} (Rotation 360
 * rule); nothing in this class ever calls {@code setYRot}.
 * <p>
 * <b>Waiting nodes</b> (LEAP, LEAP_DETECTOR, TERMINAL, WAIT) advance on their own real condition, and ANY node
 * advances on a manual LEFT-CLICK ({@link #pollClick}) - one shared mechanism, and it is the player's physical
 * mouse button read straight from GLFW: this class never presses the mouse, so a press can only be theirs.
 * <p>
 * <b>Stopping</b>: the player's own movement keys (mixin path: the untouched {@code keyPresses}; fallback path: the
 * physical keys through {@code KeyMappingKeyAccessor}, since the held mappings would report our own state), their
 * mouse while the camera is being driven ({@link RouteRotation#userMovedCamera} - latched inside the per-frame step
 * before the write), any screen the active node did not ask for, world change, death, leaving the boss, a p3sim
 * restart, and the STOP
 * command / tab button all release every key and clear the rotation controller. There is no auto-arming, so a stop
 * stays stopped.
 */
public final class Ap3Executor {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");
    private static final String CHAT = "AP3";

    /** Mixin/steer thresholds: the 8-way key record that best matches an analog direction. */
    private static final double KEY_THRESHOLD = 0.38;
    /** A plain W press reaches {@code LocalPlayer.modifyInput}'s square mapping at 0.98 - see {@link #writeMove}. */
    private static final double VANILLA_INPUT_SCALE = 0.98;
    private static final double LATERAL_GAIN = 1.5;
    private static final double MIN_NUDGE = 0.08;
    private static final double SNEAK_BELOW = 0.3;
    private static final double SETTLE_SPEED = 0.02;
    private static final int SETTLE_TICKS = 2;
    private static final double CORRIDOR_TAIL = 0.5;
    /** How far sideways off a WALK/RUN node you may be and still have it drive you (2026-09-16 review). */
    private static final double MOVE_START_LATERAL = 1.5;
    private static final double CORRIDOR_ANGLE = 10.0;
    private static final double MAX_CORRIDOR_NUDGE = 0.6;
    private static final double WALL_SEARCH = 8.0;
    private static final double WALL_EYE = 0.9;
    private static final int LOOK_TIMEOUT = 80;
    private static final int LEAP_TIMEOUT = 200;
    private static final int LEAP_FAIL_GRACE = 3;
    private static final int LEAP_CLICKED_GRACE = 30;
    private static final double LEAP_ARRIVED = 4.0;
    private static final double TELEPORT_JUMP = 8.0;
    private static final int BRAKE_TIMEOUT = 40;
    private static final int SWAP_TIMEOUT = 10;
    private static final int BREAKER_TIMEOUT = 40;
    private static final double BREAKER_RANGE_SQ = 30.0;
    private static final String BREAKER_ID = "DUNGEONBREAKER";
    private static final Pattern CHARGES = Pattern.compile("Charges: (\\d+)/(\\d+)");

    private enum Step { PREP, SWAP, DO, CONFIRM }

    // ---- session ----
    private static boolean running;
    private static Ap3Chain chain;
    private static int nextNode;
    private static Ap3Node activeNode;
    private static Step step;
    private static int stepTicks;
    private static String stopReason;
    private static Object lastLevel;
    private static boolean stoppedByUser;
    private static boolean completedNormally;
    private static Ap3Area completedArea;
    private static int cameraGraceTicks;

    // ---- manual click (the player's physical left button) ----
    private static volatile boolean clickLatch;
    private static boolean leftWasDown;

    // ---- terminal ----
    private static int selfCompletions;
    private static int terminalBaseline;

    // ---- wait / leap / leap detector / look ----
    private static long waitStartMs;
    private static long leapStartMs;
    private static Vec3 leapOrigin;
    private static final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private static final Set<UUID> counted = new HashSet<>();
    private static int arrived;
    private static boolean lookHeld;
    private static float lookYaw;
    private static float lookPitch;

    // ---- breaker ----
    private static boolean swapSent;
    private static List<BlockPos> breakerQueue = new ArrayList<>();
    private static final Set<BlockPos> breakerSent = new HashSet<>();

    // ---- alignment / movement progress ----
    private static int settleTicks;
    private static double progressBest;
    private static int noProgressTicks;

    // ---- input ----
    private static boolean mixinApplied;
    private static boolean fallbackKeysHeld;
    private static boolean warnedFallback;
    private static boolean driving;
    private static float moveX;
    private static float moveY;
    private static boolean wantForward;
    private static boolean wantBackward;
    private static boolean wantLeft;
    private static boolean wantRight;
    private static boolean wantSneak;
    private static boolean wantSprint;

    private Ap3Executor() {
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isRunning() {
        return running;
    }

    /** True when the last stop was the player taking over (keys / mouse). Nothing re-arms on its own anyway. */
    public static boolean wasStoppedByUser() {
        return stoppedByUser;
    }

    public static void clearStoppedByUser() {
        stoppedByUser = false;
    }

    /** The area of a chain that just ran to its END (null when none) - consumed once by {@link Ap3Feature} for
     *  "continue into next section", which must never restart the area that just finished. */
    static Ap3Area consumeCompletedArea() {
        Ap3Area a = completedNormally ? completedArea : null;
        completedNormally = false;
        return a;
    }

    /** Starts the chain for the boss area you are standing in and the class you are playing. */
    public static boolean start() {
        if (!Ap3Config.getInstance().isEnabled()) {
            chatBad("AP3 is off (cheat build + Skyblock only).");
            return false;
        }
        Ap3Area area = Ap3Feature.currentArea();
        if (area == null) {
            chatBad(Ap3Feature.noAreaReason());
            return false;
        }
        DungeonClass playing = Ap3Feature.selfClass();
        Ap3Chain c = Ap3Store.getInstance().forArea(area, playing);
        if (c == null || c.isEmpty()) {
            chatBad("No chain for " + area.label() + (playing == null ? "" : " (" + playing.displayName() + " or any class)")
                    + " - add nodes with /ap3 add.");
            return false;
        }
        return start(c);
    }

    /** Starts a specific chain. Only the feature / the API above call this, after every gate has passed. */
    public static boolean start(Ap3Chain c) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || c == null || c.isEmpty()) {
            return false;
        }
        if (running) {
            stop("restarted");
        }
        chain = c;
        nextNode = 0;
        activeNode = null;
        step = null;
        stepTicks = 0;
        stopReason = null;
        lastLevel = client.level;
        stoppedByUser = false;
        completedNormally = false;
        cameraGraceTicks = 2;
        clickLatch = false;
        leftWasDown = leftButtonDown(client); // a button already held when we start is not a "new" click
        lookHeld = false;
        RouteRotation.clear();
        clearMovement();
        running = true;
        LOGGER.info("[AP3] Started {} ({} nodes)", c.label(), c.nodes().size());
        if (Ap3Config.getInstance().isChatFeedback()) {
            chat(ModChat.good("Started"), ModChat.dim(" - " + c.label() + ", " + c.nodes().size() + " node(s)"));
        }
        return true;
    }

    /** Stops the chain and tells the user why (chat, when chat feedback is on). Safe to call when idle. */
    public static void stop(String reason) {
        boolean wasRunning = running;
        if (wasRunning && reason != null && (reason.equals("you moved") || reason.equals("you moved the camera"))) {
            stoppedByUser = true;
        }
        running = false;
        stopReason = reason;
        activeNode = null;
        step = null;
        lookHeld = false;
        releaseKeys();
        RouteRotation.clear();
        lastPositions.clear();
        counted.clear();
        if (wasRunning) {
            LOGGER.info("[AP3] Stopped: {}", reason);
            if (reason != null && Ap3Config.getInstance().isChatFeedback()) {
                chat(ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
            }
        }
    }

    public static String stopReason() {
        return stopReason;
    }

    /** The node currently being performed, for the renderer's active highlight. */
    static Ap3Node activeNode() {
        if (!running) {
            return null;
        }
        if (activeNode != null) {
            return activeNode;
        }
        return chain != null && nextNode < chain.nodes().size() ? chain.nodes().get(nextNode) : null;
    }

    static Ap3Chain runningChain() {
        return running ? chain : null;
    }

    // ------------------------------------------------------------------------------------------- input hooks

    /** The input mixin asks this before touching anything. */
    public static boolean isSessionActive() {
        return running;
    }

    public static void onMixinApplied() {
        mixinApplied = true;
    }

    /** From the input mixin: the player pressed a movement key themselves. Stops at ANY point of a running chain -
     *  the player taking the controls back always wins, and nothing re-arms afterwards. */
    public static void onUserMovementInput() {
        if (running) {
            stop("you moved");
        }
    }

    /** Driving the player this tick (the mixin asks this). Sneak alone (braking / the last bit of an alignment)
     *  still counts. */
    public static boolean isDriving() {
        return running && (driving || wantSneak);
    }

    /** The {@code Input} record the mixin installs: the 8-way keys nearest the analog direction, so the
     *  {@code ServerboundPlayerInputPacket} the server sees is the plausible one for the way we move. */
    public static Input drivenInput() {
        return new Input(wantForward, wantBackward, wantLeft, wantRight, false, wantSneak, wantSprint);
    }

    /** Analog input, LEFT positive (vanilla's {@code calculateImpulse(left, right)} order). NOT unit length - the
     *  length is the speed, see {@link #writeMove}. */
    public static float moveVectorX() {
        return moveX;
    }

    /** Analog input, FORWARD positive. */
    public static float moveVectorY() {
        return moveY;
    }

    /**
     * From {@code mixin/Ap3RotationSendMixin}: true while a LOOK node's client-side-only rotation must not be sent
     * to the server. Lifted the moment the player turns the camera themselves - from then on it is their rotation
     * and it goes out normally.
     */
    public static boolean isRotationSendSuppressed() {
        if (!running || !lookHeld) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (!RouteRotation.isActive() && (Math.abs(Mth.wrapDegrees(player.getYRot() - lookYaw)) > 0.05f
                || Math.abs(player.getXRot() - lookPitch) > 0.05f)) {
            lookHeld = false;
            return false;
        }
        return true;
    }

    /** From {@link Ap3Feature}'s chat hook: YOU completed/activated a terminal, lever or device. */
    static void onSelfCompletedTerminal() {
        selfCompletions++;
    }

    /** Per render frame: the smooth camera step (LOOK) and a frame-rate poll of the mouse button so a short click
     *  between two ticks is never missed. */
    static void tickFrame() {
        if (!running) {
            return;
        }
        RouteRotation.frame();
        pollClick(Minecraft.getInstance());
    }

    // ------------------------------------------------------------------------------------------- ticking

    /** Call at the END of every client tick while {@link #isRunning()}; the feature has already applied the
     *  enabled / boss / P3 gates before this runs. */
    static void tick(Minecraft client) {
        if (!running) {
            releaseKeys();
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.level != lastLevel) {
            stop("world change");
            return;
        }
        if (player.isDeadOrDying() || player.isSpectator()
                || client.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
            stop("you died");
            return;
        }
        boolean screenOpen = client.screen != null;
        if (screenOpen && !screenAllowed()) {
            stop("a screen opened");
            return;
        }
        if (!screenOpen && userPressedMovementKeyInFallback(client)) {
            stop("you moved");
            return;
        }
        if (cameraGraceTicks > 0) {
            cameraGraceTicks--;
        } else if (RouteRotation.isActive() && RouteRotation.userMovedCamera(player)) {
            stop("you moved the camera");
            return;
        }
        pollClick(client);
        if (clickLatch) {
            clickLatch = false;
            if (activeNode != null && activeNode.type.isWaiting()) {
                // killer560: "if I ever left click manually, then it should act like the terminal was completed.
                // Same thing for leaps or any other type of wait modifier." One mechanism for every node type.
                Ap3Node skipped = activeNode;
                if (Ap3Config.getInstance().isChatFeedback()) {
                    chat(ModChat.text("Skipped "), ModChat.value("#" + number(skipped) + " " + skipped.type.label()),
                            ModChat.dim(" - you clicked"));
                }
                finishNode();
                applyFallbackKeys(client);
                return;
            }
        }
        try {
            if (activeNode == null) {
                if (nextNode >= chain.nodes().size()) {
                    complete();
                    return;
                }
                beginNode(chain.nodes().get(nextNode), player);
            }
            if (activeNode != null) {
                tickNode(client, player);
            }
        } catch (Exception e) {
            LOGGER.error("[AP3] Chain error", e);
            stop("internal error (see log)");
            return;
        }
        applyFallbackKeys(client);
    }

    private static boolean screenAllowed() {
        if (activeNode == null) {
            return false;
        }
        // The terminal GUI is the whole point of a TERMINAL node; the leap menu may show while LeapManager clicks it.
        return activeNode.type == Ap3Node.Type.TERMINAL
                || (activeNode.type == Ap3Node.Type.LEAP && LeapManager.isBusy());
    }

    private static void complete() {
        LOGGER.info("[AP3] Chain {} complete", chain.label());
        boolean feedback = Ap3Config.getInstance().isChatFeedback();
        completedArea = chain.area();
        stop(null);
        completedNormally = true;
        if (feedback) {
            chat(ModChat.good("Chain complete"), ModChat.dim(" - " + chain.label()));
        }
    }

    // ------------------------------------------------------------------------------------------- nodes

    private static void beginNode(Ap3Node node, LocalPlayer player) {
        activeNode = node;
        step = Step.PREP;
        stepTicks = 0;
        settleTicks = 0;
        progressBest = Double.NEGATIVE_INFINITY;
        noProgressTicks = 0;
        swapSent = false;
        clearMovement();
        LOGGER.info("[AP3] Node #{} {}", number(node), node.describe());
    }

    private static void finishNode() {
        // Release the camera as soon as the node is done - a finished LOOK must not keep pulling the view back.
        RouteRotation.clear();
        // ...and drop the grace window with it. Click-skipping a LEAP used to leave 205 ticks of grace
        // running, which silently made the next LOOK node uninterruptible by the mouse (2026-09-16 review).
        cameraGraceTicks = 0;
        clearMovement();
        activeNode = null;
        step = null;
        nextNode++;
    }

    private static void tickNode(Minecraft client, LocalPlayer player) {
        Ap3Node node = activeNode;
        stepTicks++;
        switch (node.type) {
            case LINE -> tickLine(client, player, node);
            case AXIS_LINE -> tickAxisLine(client, player, node);
            case WALK, RUN -> tickMove(client, player, node);
            case LEAP -> tickLeap(client, player, node);
            case LEAP_DETECTOR -> tickLeapDetector(client, player, node);
            case TERMINAL -> tickTerminal(node);
            case WAIT -> tickWait(node);
            case STOP -> tickStop(player);
            case LOOK -> tickLook(player, node);
            case BREAKER -> tickBreaker(client, player, node);
        }
    }

    // ---- LINE: "Align should put me to the very center of whatever this node is" -------------------------

    private static void tickLine(Minecraft client, LocalPlayer player, Ap3Node node) {
        Vec3 pos = player.position();
        double along = node.alongOffset(pos);
        double lateral = node.lateralOffset(pos);
        if (!checkCorridor(node, along, lateral)) {
            return;
        }
        // Perpendicular only: the nudge is along the node's left axis, never along its direction of travel.
        Vec3 l = node.left();
        if (settleAligned(client, player, node, Math.abs(lateral), -lateral * l.x, -lateral * l.z)) {
            finishNode();
        }
    }

    // ---- AXIS_LINE: the same alignment measured against a wall, the node centre defining the other axis ----

    private static void tickAxisLine(Minecraft client, LocalPlayer player, Ap3Node node) {
        Vec3 pos = player.position();
        double along = node.alongOffset(pos);
        double lateral = node.lateralOffset(pos);
        if (!checkCorridor(node, along, lateral)) {
            return;
        }
        Vec3 axis = wallAxisVector(node);
        if (axis == null) {
            // A hand-written node with no wall recorded degrades to a plain LINE rather than freezing.
            Vec3 l = node.left();
            if (settleAligned(client, player, node, Math.abs(lateral), -lateral * l.x, -lateral * l.z)) {
                finishNode();
            }
            return;
        }
        double measured = wallDistance(client.level, player, pos, axis, WALL_SEARCH);
        if (Double.isNaN(measured)) {
            stop("no wall found for axis line #" + number(node));
            return;
        }
        // Too far from the wall -> move toward it (+axis); too close -> away (-axis).
        double wallError = measured - node.wallDistance;
        // The other axis is measured from the node centre: lateral for a FRONT wall, along for a side wall.
        double otherError;
        Vec3 other;
        if (node.wallAxis == Ap3Node.WallAxis.FRONT) {
            otherError = lateral;
            other = node.left();
        } else {
            otherError = along;
            other = node.dir();
        }
        double wx = axis.x * wallError - other.x * otherError;
        double wz = axis.z * wallError - other.z * otherError;
        double err = Math.max(Math.abs(wallError), Math.abs(otherError));
        if (settleAligned(client, player, node, err, wx, wz)) {
            finishNode();
        }
    }

    /** Refuses to correct outside the node's active span / tolerance band (killer560's length / width). */
    private static boolean checkCorridor(Ap3Node node, double along, double lateral) {
        if (along < -CORRIDOR_TAIL - 0.5 || along > node.length + 0.5) {
            stop(String.format(Locale.US, "not within line #%d (%.1f blocks along it)", number(node), along));
            return false;
        }
        if (Math.abs(lateral) > node.width / 2.0 + 0.25) {
            stop(String.format(Locale.US, "%.1f blocks off line #%d (width %.1f)", Math.abs(lateral), number(node), node.width));
            return false;
        }
        return true;
    }

    /**
     * Drives the player along the world error vector {@code (ex, ez)} (its length = how far off), sneaking for the
     * last bit so the landing is precise, and reports settled once within the tolerance with no momentum left.
     */
    private static boolean settleAligned(Minecraft client, LocalPlayer player, Ap3Node node, double err,
                                         double ex, double ez) {
        Ap3Config cfg = Ap3Config.getInstance();
        Vec3 vel = player.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (err <= cfg.getAlignTolerance()) {
            // Within tolerance: no input at all and let ground friction kill what momentum is left (sneaking only
            // scales INPUT, it does not brake), then report settled once the player has actually stopped.
            clearMovement();
            if (speed < SETTLE_SPEED) {
                if (++settleTicks >= SETTLE_TICKS) {
                    return true;
                }
            } else {
                settleTicks = 0;
            }
            return false;
        }
        settleTicks = 0;
        if (stepTicks > cfg.getAlignTimeoutTicks()) {
            stop(String.format(Locale.US, "couldn't align on #%d (%.2f blocks off)", number(node), err));
            return false;
        }
        double mag = Mth.clamp(err * LATERAL_GAIN, MIN_NUDGE, 1.0);
        double h = Math.sqrt(ex * ex + ez * ez);
        writeMove(player, ex / h * mag, ez / h * mag, false);
        wantSneak = err < SNEAK_BELOW;
        return false;
    }

    // ---- WALK / RUN: move in the recorded world direction without facing it ------------------------------

    private static void tickMove(Minecraft client, LocalPlayer player, Ap3Node node) {
        if (step == Step.PREP) {
            // Sanity-check that we are actually AT this node before driving off along its heading. The stuck
            // detector only notices when progress stops, so without this a node behind the player (a
            // hand-edited coordinate, the wrong section, a chain entered from somewhere else) walked them
            // forward until the length ran out (2026-09-16 review).
            double startAlong = node.alongOffset(player.position());
            double startLateral = node.lateralOffset(player.position());
            if (Math.abs(startAlong) > node.length + 0.5 || Math.abs(startLateral) > MOVE_START_LATERAL) {
                stop("not standing at " + node.type.label() + " #" + number(node));
                return;
            }
        }
        Ap3Config cfg = Ap3Config.getInstance();
        Vec3 pos = player.position();
        double along = node.alongOffset(pos);
        if (along >= node.length) {
            finishNode();
            return;
        }
        if (along > progressBest + 0.02) {
            progressBest = along;
            noProgressTicks = 0;
        } else if (++noProgressTicks > cfg.getMoveTimeoutTicks()) {
            stop(String.format(Locale.US, "stuck on %s #%d", node.type.label().toLowerCase(Locale.ROOT), number(node)));
            return;
        }
        Vec3 d = node.dir();
        double wx = d.x;
        double wz = d.z;
        // Corridor correction: a LINE / AXIS_LINE of this chain that runs the same way and contains the player adds
        // a lateral nudge (never a push along the travel direction) - that is what its length/width are for.
        Ap3Node corridor = corridorContaining(node, pos);
        if (corridor != null) {
            double lateral = corridor.lateralOffset(pos);
            double nudge = Mth.clamp(-lateral * LATERAL_GAIN, -MAX_CORRIDOR_NUDGE, MAX_CORRIDOR_NUDGE);
            Vec3 l = corridor.left();
            wx += l.x * nudge;
            wz += l.z * nudge;
        }
        writeMove(player, wx, wz, node.type == Ap3Node.Type.RUN);
    }

    private static Ap3Node corridorContaining(Ap3Node mover, Vec3 pos) {
        Ap3Node best = null;
        double bestLateral = Double.MAX_VALUE;
        for (Ap3Node c : chain.nodes()) {
            if (!c.type.isCorridor()) {
                continue;
            }
            float diff = Math.abs(Mth.wrapDegrees(c.yaw - mover.yaw));
            if (diff > CORRIDOR_ANGLE && diff < 180.0 - CORRIDOR_ANGLE) {
                continue;
            }
            double along = c.alongOffset(pos);
            if (along < -CORRIDOR_TAIL || along > c.length) {
                continue;
            }
            double lateral = Math.abs(c.lateralOffset(pos));
            if (lateral > c.width / 2.0 || lateral >= bestLateral) {
                continue;
            }
            best = c;
            bestLateral = lateral;
        }
        return best;
    }

    // ---- LEAP -----------------------------------------------------------------------------------------------

    private static void tickLeap(Minecraft client, LocalPlayer player, Ap3Node node) {
        switch (step) {
            case PREP -> {
                clearMovement();
                leapOrigin = player.position();
                leapStartMs = System.currentTimeMillis();
                if (!requestLeap(node)) {
                    return; // requestLeap stopped the chain with the reason
                }
                step = Step.CONFIRM;
                stepTicks = 0;
                cameraGraceTicks = LEAP_TIMEOUT + 5; // the teleport's own camera change is not the player's mouse
            }
            case CONFIRM -> {
                clearMovement();
                boolean clicked = LeapManager.lastLeapMs() >= leapStartMs;
                if (clicked && player.position().distanceTo(leapOrigin) > LEAP_ARRIVED) {
                    RouteRotation.rebase();
                    cameraGraceTicks = 3;
                    finishNode();
                } else if (clicked && stepTicks > LEAP_CLICKED_GRACE) {
                    // The menu was clicked but we never moved 4+ blocks: the target was standing next to us (a
                    // real, short teleport) - the leap happened as far as the game is concerned, so move on.
                    LOGGER.info("[AP3] Leap clicked but no teleport seen within {} ticks - continuing", LEAP_CLICKED_GRACE);
                    RouteRotation.rebase();
                    cameraGraceTicks = 3;
                    finishNode();
                } else if (!clicked && !LeapManager.isBusy() && stepTicks > LEAP_FAIL_GRACE) {
                    stop("leap failed (see the Fast Leap message)");
                } else if (stepTicks > LEAP_TIMEOUT) {
                    stop("leap timed out");
                }
            }
            default -> finishNode();
        }
    }

    /**
     * Submits the leap. With no modifier: whoever Fast Leap's P3 target for this section resolves to - the same
     * resolution as {@code FastLeapFeature.leapToConfigured} (Name / Class / Posmsg-then-name-then-class), which is
     * package-private, so it is mirrored here on the public {@code FastLeapConfig} / {@code PosmsgTargets} /
     * {@code LeapManager} API. Class targets resolve through {@code Teammates.firstAliveOfClass}, which reads
     * {@code ClassOverrides} - so a manual override wins here exactly as it does for Fast Leap.
     */
    private static boolean requestLeap(Ap3Node node) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        boolean block = cfg.isBlockInputs();
        boolean fast = cfg.isFastMode();
        boolean swap = cfg.isSwapBack();
        switch (node.leapMode) {
            case CLASS -> {
                if (node.leapClass == null) {
                    stop("leap #" + number(node) + " has no class");
                    return false;
                }
                LeapManager.leap(node.leapClass, block, fast, swap);
                return true;
            }
            case IGN -> {
                if (node.leapIgn == null || node.leapIgn.isBlank()) {
                    stop("leap #" + number(node) + " has no IGN");
                    return false;
                }
                LeapManager.leap(node.leapIgn, block, fast, swap);
                return true;
            }
            default -> {
                // Fast Leap's target for the chain's area. P2 has five targets (predev, green, yellow, purple, py)
                // and S5 none, so neither has a single default - those leaps need a class or IGN.
                LeapTarget target = switch (chain.phase()) {
                    case P1 -> LeapTarget.P1;
                    case P3 -> switch (chain.section()) {
                        case 1 -> LeapTarget.S1;
                        case 2 -> LeapTarget.S2;
                        case 3 -> LeapTarget.S3;
                        case 4 -> LeapTarget.S4;
                        default -> null;
                    };
                    case P4 -> LeapTarget.P4;
                    case P5 -> LeapTarget.RELIC;
                    default -> null;
                };
                if (target == null) {
                    stop("Fast Leap has no single " + chain.area().label() + " target - give leap #" + number(node) + " a class or IGN");
                    return false;
                }
                String name = cfg.getTargetName(target);
                DungeonClass clazz = cfg.getTargetClass(target);
                switch (cfg.getTargetMode()) {
                    case NAME -> {
                        if (name.isBlank()) {
                            stop("Fast Leap " + target.label + " has no name set");
                            return false;
                        }
                        LeapManager.leap(name, block, fast, swap);
                        return true;
                    }
                    case CLASS -> {
                        if (clazz == null) {
                            stop("Fast Leap " + target.label + " has no class set");
                            return false;
                        }
                        LeapManager.leap(clazz, block, fast, swap);
                        return true;
                    }
                    default -> {
                        String sender = PosmsgTargets.sender(target);
                        if (sender != null && LeapManager.resolveName(sender) != null) {
                            LeapManager.leap(sender, block, fast, swap);
                            return true;
                        }
                        if (!name.isBlank()) {
                            LeapManager.leap(name, block, fast, swap);
                            return true;
                        }
                        if (clazz != null) {
                            LeapManager.leap(clazz, block, fast, swap);
                            return true;
                        }
                        stop("no one has announced " + target.label + " yet");
                        return false;
                    }
                }
            }
        }
    }

    // ---- LEAP_DETECTOR: N teammates have leapt TO you --------------------------------------------------------

    /**
     * Hypixel prints no chat line when someone leaps to you (nothing in {@code LeapManager} / {@code ChatObserver}
     * sees one), so this counts arrivals by what a Spirit Leap physically is - a teleport: a teammate whose
     * position jumps by {@value #TELEPORT_JUMP}+ blocks in one tick (or who appears from unloaded) and lands within
     * the configured radius of you. Someone walking up to you never jumps that far in a tick, so they don't count.
     */
    private static void tickLeapDetector(Minecraft client, LocalPlayer player, Ap3Node node) {
        clearMovement();
        if (step == Step.PREP) {
            lastPositions.clear();
            counted.clear();
            arrived = 0;
            for (AbstractClientPlayer p : client.level.players()) {
                lastPositions.put(p.getUUID(), p.position());
            }
            step = Step.CONFIRM;
            return;
        }
        double radius = Ap3Config.getInstance().getLeapDetectRadius();
        Vec3 me = player.position();
        String self = Teammates.selfName();
        for (AbstractClientPlayer p : client.level.players()) {
            UUID id = p.getUUID();
            Vec3 now = p.position();
            Vec3 prev = lastPositions.put(id, now);
            if (p == player || counted.contains(id) || id.version() != 4) {
                continue;
            }
            String name = ChatObserver.strip(p.getGameProfile().name());
            if (name.equalsIgnoreCase(self) || (Teammates.isKnown() && Teammates.byName(name) == null)) {
                continue;
            }
            boolean teleported = prev == null || prev.distanceTo(now) >= TELEPORT_JUMP;
            if (teleported && now.distanceTo(me) <= radius) {
                counted.add(id);
                arrived++;
                LOGGER.info("[AP3] {} leapt to you ({}/{})", name, arrived, node.leapCount);
                if (Ap3Config.getInstance().isChatFeedback()) {
                    chat(ModChat.value(name), ModChat.text(" leapt to you "), ModChat.dim("(" + arrived + "/" + node.leapCount + ")"));
                }
            }
        }
        if (arrived >= node.leapCount) {
            finishNode();
        }
    }

    // ---- TERMINAL: only a completion by YOU, never a GUI close --------------------------------------------

    private static void tickTerminal(Ap3Node node) {
        clearMovement();
        if (step == Step.PREP) {
            terminalBaseline = selfCompletions;
            step = Step.CONFIRM;
            return;
        }
        // killer560: "which will only go if it completed the terminal, not just if the terminal gets closed."
        // The counter only moves on Hypixel's own "<you> completed a terminal!" line (Ap3Feature.onChat).
        if (selfCompletions > terminalBaseline) {
            if (Ap3Config.getInstance().isChatFeedback()) {
                chat(ModChat.good("Terminal done"), ModChat.dim(" - #" + number(node)));
            }
            finishNode();
        }
    }

    // ---- WAIT -------------------------------------------------------------------------------------------------

    private static void tickWait(Ap3Node node) {
        clearMovement();
        if (step == Step.PREP) {
            waitStartMs = System.currentTimeMillis();
            step = Step.CONFIRM;
        }
        if (System.currentTimeMillis() - waitStartMs >= node.waitMs) {
            finishNode();
        }
    }

    // ---- STOP: "stops all movement. Exactly that, nothing else." ------------------------------------------

    private static void tickStop(LocalPlayer player) {
        // No input of any kind; the node holds until the slide a RUN leaves behind has died out (ground friction),
        // so whatever follows starts from a standstill. Ends the chain only when it is the last node.
        clearMovement();
        Vec3 vel = player.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (speed <= SETTLE_SPEED || stepTicks > BRAKE_TIMEOUT) {
            finishNode();
        }
    }

    // ---- LOOK: client-side rotation only ----------------------------------------------------------------------

    private static void tickLook(LocalPlayer player, Ap3Node node) {
        clearMovement();
        if (step == Step.PREP) {
            // Target only: RouteRotation turns it into a wrapped delta on the running yaw every frame. The stored
            // yaw is never assigned to the player.
            RouteRotation.beginApproach(node.yaw, node.pitch, false, 0f, 0f);
            RouteRotation.clearUserMoved();
            lookHeld = true; // Ap3RotationSendMixin keeps this rotation off the wire while it holds
            cameraGraceTicks = 1;
            step = Step.CONFIRM;
            return;
        }
        if (RouteRotation.settled(1.0f) || stepTicks > LOOK_TIMEOUT) {
            lookYaw = player.getYRot();
            lookPitch = player.getXRot();
            finishNode(); // clears the controller; lookHeld stays until the chain ends or the player turns
        }
    }

    // ---- BREAKER (same as Auto Routes' DUNGEON_BREAKER, absolute blocks) ------------------------------------

    private static void tickBreaker(Minecraft client, LocalPlayer player, Ap3Node node) {
        clearMovement();
        switch (step) {
            case PREP -> {
                if (node.breakerBlocks.isEmpty()) {
                    finishNode(); // nothing to break (a fresh node before /ap3 edit db)
                    return;
                }
                breakerQueue = new ArrayList<>(node.breakerBlocks);
                breakerSent.clear();
                step = Step.SWAP;
                stepTicks = 0;
            }
            case SWAP -> {
                int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
                if (slot < 0) {
                    stop("no Dungeon Breaker in the hotbar");
                    return;
                }
                if (ensureSelected(player, slot)) {
                    if (breakerCharges(player.getMainHandItem()) <= 0) {
                        stop("Dungeon Breaker has no charges");
                        return;
                    }
                    step = Step.DO;
                    stepTicks = 0;
                } else if (stepTicks > SWAP_TIMEOUT) {
                    stop("couldn't switch to the Dungeon Breaker");
                }
            }
            case DO -> {
                // One START_DESTROY_BLOCK per block, two ticks apart, no rotation (range-checked, not look-checked).
                if (stepTicks % 2 != 0) {
                    return;
                }
                Vec3 eye = player.getEyePosition();
                while (!breakerQueue.isEmpty()) {
                    BlockPos pos = breakerQueue.remove(0);
                    if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    if (pos.distToCenterSqr(eye.x, eye.y, eye.z) > BREAKER_RANGE_SQ) {
                        LOGGER.info("[AP3] Breaker block {} out of range - skipped", pos);
                        continue;
                    }
                    player.connection.send(new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                    player.swing(InteractionHand.MAIN_HAND);
                    breakerSent.add(pos);
                    return; // next block on the next delay tick
                }
                step = Step.CONFIRM;
                stepTicks = 0;
            }
            case CONFIRM -> {
                if (breakerSent.isEmpty()) {
                    finishNode();
                    return;
                }
                int gone = 0;
                for (BlockPos pos : breakerSent) {
                    if (client.level.getBlockState(pos).isAir()) {
                        gone++;
                    }
                }
                if (gone == breakerSent.size()) {
                    finishNode();
                } else if (stepTicks > BREAKER_TIMEOUT) {
                    if (gone == 0) {
                        stop("dungeon breaker didn't break the blocks");
                    } else {
                        LOGGER.info("[AP3] Breaker: {} of {} blocks broke - continuing", gone, breakerSent.size());
                        finishNode();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- movement

    /**
     * Writes the analog input that moves the player along the WORLD vector {@code (wx, wz)} at the LIVE camera yaw,
     * without touching the camera. The yaw is read, never written.
     * <p>
     * What "walk at the 45 degree angle that gets the approximately 2% speed boost" really is in 26.1.2, read from
     * the game's own bytecode (javap on the 26.1.2 jar, {@code LocalPlayer.modifyInput}): the key-derived
     * {@code moveVector} is normalised, scaled by 0.98, then passed through {@code modifyInputSpeedForSquareMovement}
     * = {@code unit * min(length * distanceToUnitSquare(unit), 1)}, where the unit-square distance is 1 for a
     * straight input and sqrt(2) for a 45-degree one. So a plain W press ends at 0.98 and a W+A press at
     * min(0.98 * 1.414, 1) = 1.00 - the ~2%. Since this class writes the vector itself, the length it writes decides
     * which of those the player gets, for ANY travel direction and with the camera left alone:
     * <ul>
     * <li>Diagonal walk ON: length 1/0.98, so the mapping lands on 1.00 - exactly the real W+A speed.</li>
     * <li>OFF: length 1/d for this direction's own unit-square distance d, so the mapping lands on 0.98 - exactly
     * the plain-W speed, even when the direction happens to be off-axis relative to the camera.</li>
     * </ul>
     * The 8-way {@code Input} record the server sees is the nearest real key combination for the direction.
     * Sprinting can only start with a forward component ({@code ClientInput.hasForwardImpulse}: {@code y > 1e-5}),
     * so a RUN whose direction is behind the camera walks - vanilla cannot sprint backwards either.
     */
    private static void writeMove(LocalPlayer player, double wx, double wz, boolean sprint) {
        double h = Math.sqrt(wx * wx + wz * wz);
        if (h < 1e-4) {
            clearMovement();
            return;
        }
        double ux = wx / h;
        double uz = wz / h;
        double mag = Math.min(1.0, h);
        double yr = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yr);
        double fz = Math.cos(yr);
        double lx = Math.cos(yr);
        double lz = Math.sin(yr);
        double fwd = ux * fx + uz * fz;
        double lft = ux * lx + uz * lz;
        double ax = Math.abs(lft);
        double ay = Math.abs(fwd);
        double ratio = ay > ax ? (ay == 0 ? 0 : ax / ay) : (ax == 0 ? 0 : ay / ax);
        double unitSquare = Math.sqrt(1.0 + ratio * ratio);
        double scale = Ap3Config.getInstance().isDiagonalWalk() ? 1.0 / VANILLA_INPUT_SCALE : 1.0 / unitSquare;
        scale *= mag;
        moveX = (float) (lft * scale);
        moveY = (float) (fwd * scale);
        wantForward = fwd > KEY_THRESHOLD;
        wantBackward = fwd < -KEY_THRESHOLD;
        wantLeft = lft > KEY_THRESHOLD;
        wantRight = lft < -KEY_THRESHOLD;
        wantSprint = sprint && fwd > 0.05 && !player.isInWater();
        driving = true;
    }

    private static void clearMovement() {
        driving = false;
        moveX = 0f;
        moveY = 0f;
        wantForward = wantBackward = wantLeft = wantRight = wantSneak = wantSprint = false;
    }

    /** Without the input mixin (config not loaded) hold the key mappings instead, the way AutoWalker does. Only the
     *  8-way approximation survives on this path - analog direction and the diagonal-walk setting do not. */
    private static void applyFallbackKeys(Minecraft client) {
        if (mixinApplied) {
            return;
        }
        boolean any = running && (driving || wantSneak);
        client.options.keyUp.setDown(any && wantForward);
        client.options.keyDown.setDown(any && wantBackward);
        client.options.keyLeft.setDown(any && wantLeft);
        client.options.keyRight.setDown(any && wantRight);
        client.options.keyShift.setDown(any && wantSneak);
        client.options.keySprint.setDown(any && wantSprint);
        fallbackKeysHeld = true;
    }

    private static void releaseKeys() {
        clearMovement();
        if (!fallbackKeysHeld) {
            return;
        }
        fallbackKeysHeld = false;
        Minecraft client = Minecraft.getInstance();
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyShift.setDown(false);
        client.options.keySprint.setDown(false);
    }

    /**
     * Without the input mixin the executor drives by holding the key MAPPINGS, which means {@code KeyMapping.isDown()}
     * reports the bot's own state and the player's real keypresses become invisible (the Auto Routes 2026-09-16
     * review bug). Poll the physical keys through GLFW via {@code KeyMappingKeyAccessor} instead, and warn once
     * that the fallback is in use - a chain that cannot be stopped is the worst failure this feature has.
     */
    private static boolean userPressedMovementKeyInFallback(Minecraft client) {
        if (mixinApplied || !fallbackKeysHeld) {
            return false;
        }
        if (!warnedFallback) {
            warnedFallback = true;
            LOGGER.warn("[AP3] Input mixin did not apply - driving with key mappings instead (8-way only). "
                    + "Movement keys are polled directly so you can still stop a chain.");
        }
        var options = client.options;
        var window = client.getWindow();
        return rawDown(window, options.keyUp) || rawDown(window, options.keyDown)
                || rawDown(window, options.keyLeft) || rawDown(window, options.keyRight)
                || rawDown(window, options.keyJump);
    }

    private static boolean rawDown(com.mojang.blaze3d.platform.Window window, net.minecraft.client.KeyMapping mapping) {
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key =
                    ((com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor) (Object) mapping).killer560smod$getKey();
            if (key == null || key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
                return false;
            }
            return com.killer560.hub.util.KeyUtil.isKeyDown(window, key.getValue());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------- manual click

    /** Edge-triggered poll of the physical left mouse button. Clicks inside a GUI (a terminal being solved) are
     *  the player working, not an override, so a screen being open masks them. */
    private static void pollClick(Minecraft client) {
        boolean down = leftButtonDown(client);
        if (down && !leftWasDown && client.screen == null) {
            clickLatch = true;
        }
        leftWasDown = down;
    }

    private static boolean leftButtonDown(Minecraft client) {
        try {
            var window = client.getWindow();
            return window != null && GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------- walls

    /** The unit world vector along which an AXIS_LINE's wall was recorded, or null when none was. */
    static Vec3 wallAxisVector(Ap3Node node) {
        return switch (node.wallAxis) {
            case FRONT -> node.dir();
            case LEFT -> node.left();
            case RIGHT -> node.left().scale(-1.0);
            default -> null;
        };
    }

    /**
     * Horizontal distance from {@code from} (feet position; the ray runs at chest height so slabs / carpets are
     * ignored) to the first collidable block face along {@code axis}, or NaN when none within {@code max}. Uses the
     * game's own {@code Level.clip} so the distance is to the exact face, not a stepped guess.
     */
    static double wallDistance(Level level, LocalPlayer player, Vec3 from, Vec3 axis, double max) {
        Vec3 start = new Vec3(from.x, from.y + WALL_EYE, from.z);
        Vec3 end = start.add(axis.x * max, 0.0, axis.z * max);
        HitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (!(hit instanceof BlockHitResult) || hit.getType() != HitResult.Type.BLOCK) {
            return Double.NaN;
        }
        Vec3 loc = hit.getLocation();
        double dx = loc.x - start.x;
        double dz = loc.z - start.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** At placement: the nearest wall among front / left / right of the node, written into the node. */
    static void measureWall(Level level, LocalPlayer player, Ap3Node node) {
        Ap3Node.WallAxis bestAxis = Ap3Node.WallAxis.NONE;
        double best = Double.NaN;
        for (Ap3Node.WallAxis axis : new Ap3Node.WallAxis[]{Ap3Node.WallAxis.FRONT, Ap3Node.WallAxis.LEFT, Ap3Node.WallAxis.RIGHT}) {
            node.wallAxis = axis;
            double d = wallDistance(level, player, node.pos(), wallAxisVector(node), WALL_SEARCH);
            if (!Double.isNaN(d) && (Double.isNaN(best) || d < best)) {
                best = d;
                bestAxis = axis;
            }
        }
        node.wallAxis = bestAxis;
        node.wallDistance = Double.isNaN(best) ? 0.0 : best;
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** 1-BASED, to match /ap3 list, /ap3 delete, the world labels and the tab. */
    private static int number(Ap3Node node) {
        return chain == null ? -1 : chain.numberOf(node);
    }

    /** Selects the hotbar slot (client + {@code ServerboundSetCarriedItemPacket}) and reports true once it is the
     *  selected slot - at most one swap per node, the tick after it the server has seen it. */
    private static boolean ensureSelected(LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() == slot) {
            return !swapSent || stepTicks >= 2;
        }
        if (!swapSent) {
            player.getInventory().setSelectedSlot(slot);
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            swapSent = true;
            stepTicks = 0;
        }
        return false;
    }

    /** {@code dungeonbreaker/DungeonBreakerFeature.getBreakerCharges}: the count is only in the item's lore. */
    private static int breakerCharges(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !BREAKER_ID.equalsIgnoreCase(ItemIdentity.skyblockId(stack))) {
            return 0;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            Matcher m = CHARGES.matcher(line.getString());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void chat(Component... parts) {
        ModChat.send(CHAT, parts);
    }

    private static void chatBad(String text) {
        ModChat.send(CHAT, ModChat.bad(text));
    }
}
