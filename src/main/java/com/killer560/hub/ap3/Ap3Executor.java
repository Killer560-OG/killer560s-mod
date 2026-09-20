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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs one AP3 chain: the movement, alignment, waiting and advancing. CHEAT BUILD ONLY - only ever started by
 * {@link Ap3Feature} behind {@link Ap3Config#isEnabled()} and the BOSS-ONLY gate (any boss phase, one chain per
 * {@link Ap3Area}).
 * <p>
 * <b>Order + boxes.</b> Nodes run in chain order; each one is performed once you are inside its trigger box
 * ({@link Ap3Node#contains}). A WALK / RUN sets a <b>held</b> walk in its recorded world direction that lasts until
 * a STOP or an align node (killer560, 2026-09-20: "keeps you walking until a stop or align node, not just until you
 * leave the node") - that held walk is what carries you into the next node's box. A node nothing is carrying you
 * toward fires on the spot (LOOK, LEAP, TERMINAL...), or refuses if it is positional (WALK, RUN, STOP, BOOM);
 * an align node pulls you in from up to {@value #ALIGN_REACH} blocks.
 * <p>
 * <b>Movement</b> is written as the analog {@code moveVector} through {@code mixin/Ap3InputMixin} (falling back to
 * holding the key mappings when the mixin config is not loaded, exactly like {@code RouteExecutor}). A held walk
 * moves in the world direction its yaw was recorded in <b>without turning the camera</b> - killer560: "whenever
 * I use a walk node, it does not actually make my character face that way, but it will move that way" - by
 * projecting that fixed world direction into the player's LIVE facing frame every tick ({@link #writeMove}).
 * <p>
 * <b>Rotation</b> (LOOK only) is a wrapped delta on the running yaw through {@link RouteRotation} (Rotation 360
 * rule); nothing in this class ever calls {@code setYRot}. The camera step is bounded to one per rendered frame
 * and the node to a real-time timeout - see {@link #tickFrame()} and {@link #tickLook}.
 * <p>
 * <b>Modifiers</b> on any node: {@code wait:<ms>} holds the chain that long after the node ({@link #waitUntilMs});
 * {@code close} makes the node fire only on a manual left click or once a GUI that was open has closed
 * ({@link Step#GATE}).
 * <p>
 * <b>Waiting nodes</b> (LEAP, LEAP_COUNTER, TERMINAL) advance on their own real condition, and ANY wait - a node,
 * a close gate, a wait: modifier - ends on a manual LEFT-CLICK ({@link #pollClick}): one shared mechanism, and it
 * is the player's physical mouse button read straight from GLFW: this class never presses the mouse, so a press
 * can only be theirs.
 * <p>
 * <b>Test mode</b> ({@code /ap3 testmode}): the chain runs every node without waiting on anything the arena would
 * have to provide (terminals, teammates, close gates; a failed leap is skipped), and ANY key or mouse button ends
 * it - "runs every node until he takes control by pressing any button".
 * <p>
 * <b>Stopping</b>: the player's own movement keys (mixin path: the untouched {@code keyPresses}; fallback path: the
 * physical keys through {@code KeyMappingKeyAccessor}, since the held mappings would report our own state), their
 * mouse while the camera is being driven ({@link RouteRotation#userMovedCamera} - latched inside the per-frame step
 * before the write), any screen the active node did not ask for, world change, death, leaving the boss, a p3sim
 * restart, and the STOP command / tab button all release every key and clear the rotation controller. There is no
 * auto-arming, so a stop stays stopped.
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
    /** How far an align node may pull you in from when nothing is carrying you into its box. */
    private static final double ALIGN_REACH = 4.0;
    /** Ground friction is 0.6/tick, so a sliding player still travels ~1.5x the current per-tick velocity before
     *  stopping - what "align must account for player drift" costs when you arrive at a run. */
    private static final double DRIFT_FACTOR = 1.5;
    /** A node that is not about position still fires when the held walk brings you this close to it, so a walk
     *  that passes half a block beside a 1x1 box cannot leave the chain waiting forever. Align nodes get the same
     *  leniency in every case - a version-1 corridor loads as a 1-wide box. */
    private static final double NEAR_ENOUGH = 1.0;
    /** With nothing carrying you, a positional node still fires from this close (the old walk-start tolerance):
     *  "standing on" the first node of a chain was never a half-block affair. */
    private static final double START_TOLERANCE = 1.5;
    /** Walking away from a node you were meant to reach: once the distance has grown this much past its best,
     *  the walk has missed it - say so now rather than after the whole move timeout. */
    private static final double WALKED_PAST = 2.0;
    private static final double WALL_TOUCH = 0.02;
    private static final double WALL_PUSH_HELD = 0.35;
    private static final int LOOK_TIMEOUT = 80;
    private static final long LOOK_TIMEOUT_MS = 3000L;
    /** At most one camera step per 2 ms of wall time, whatever fires the render hook. */
    private static final long MIN_FRAME_STEP_NANOS = 2_000_000L;
    private static final int LEAP_TIMEOUT = 200;
    private static final int LEAP_FAIL_GRACE = 3;
    private static final int LEAP_CLICKED_GRACE = 30;
    private static final double LEAP_ARRIVED = 4.0;
    private static final double TELEPORT_JUMP = 8.0;
    private static final int BRAKE_TIMEOUT = 40;
    private static final int SWAP_TIMEOUT = 10;
    private static final int BOOM_TIMEOUT = 40;
    private static final double BOOM_REACH = 4.5;
    private static final String[] BOOM_IDS = {"INFINITE_SUPERBOOM_TNT", "SUPERBOOM_TNT"};
    private static final int GLFW_FIRST_KEY = GLFW.GLFW_KEY_SPACE;
    private static final int GLFW_LAST_KEY = GLFW.GLFW_KEY_LAST;

    /** GATE = waiting for the close modifier; BOX = waiting to be inside the node's trigger box; the rest are the
     *  per-type phases. */
    private enum Step { GATE, BOX, PREP, SWAP, DO, CONFIRM }

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
    private static boolean testMode;

    // ---- held walk (WALK / RUN until a STOP or align node) ----
    private static Vec3 holdDir;
    private static boolean holdSprint;

    // ---- modifiers ----
    private static long waitUntilMs;
    private static boolean gateSawScreen;

    // ---- manual click (the player's physical left button) ----
    private static volatile boolean clickLatch;
    private static boolean leftWasDown;

    // ---- test mode: any key / button = the player taking over ----
    private static final boolean[] keyWasDown = new boolean[GLFW_LAST_KEY + 1];
    private static final boolean[] mouseWasDown = new boolean[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];

    // ---- terminal ----
    private static int selfCompletions;
    private static int terminalBaseline;

    // ---- leap / leap counter / look ----
    private static long leapStartMs;
    private static Vec3 leapOrigin;
    private static final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private static final Set<UUID> counted = new HashSet<>();
    private static int arrived;
    private static boolean lookHeld;
    private static float lookYaw;
    private static float lookPitch;
    private static long lookStartMs;
    private static long lastFrameStepNanos;

    // ---- boom ----
    private static boolean swapSent;
    private static BlockPos boomTarget;
    private static final Map<BlockPos, BlockState> boomBefore = new HashMap<>();

    // ---- stopwatch (survives across chains and areas; reset on world change) ----
    private static long stopwatchStartMs;
    private static long lastStopwatchMs = -1L;

    // ---- alignment / box progress ----
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

    public static boolean isTestMode() {
        return testMode;
    }

    /** {@code /ap3 testmode}: session-only, never saved - a test mode that survived a restart would surprise him. */
    public static void setTestMode(boolean on) {
        testMode = on;
    }

    /** Milliseconds the stopwatch has been running, or -1 when it is not. */
    public static long stopwatchRunningMs() {
        return stopwatchStartMs == 0L ? -1L : System.currentTimeMillis() - stopwatchStartMs;
    }

    /** The last stopped time in milliseconds, or -1 when none yet. */
    public static long lastStopwatchMs() {
        return lastStopwatchMs;
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
        holdDir = null;
        holdSprint = false;
        waitUntilMs = 0L;
        gateSawScreen = false;
        snapshotButtons(client); // test mode: keys already down (the Enter that sent /ap3 start) are not a takeover
        RouteRotation.clear();
        clearMovement();
        running = true;
        LOGGER.info("[AP3] Started {} ({} nodes{})", c.label(), c.nodes().size(), testMode ? ", TEST MODE" : "");
        if (Ap3Config.getInstance().isChatFeedback()) {
            chat(ModChat.good("Started"), ModChat.dim(" - " + c.label() + ", " + c.nodes().size() + " node(s)"
                    + (testMode ? ", test mode - any key or button stops it" : "")));
        }
        return true;
    }

    /** Stops the chain and tells the user why (chat, when chat feedback is on). Safe to call when idle. */
    public static void stop(String reason) {
        boolean wasRunning = running;
        if (wasRunning && reason != null && (reason.equals("you moved") || reason.equals("you moved the camera")
                || reason.startsWith("you took control"))) {
            stoppedByUser = true;
        }
        running = false;
        stopReason = reason;
        activeNode = null;
        step = null;
        lookHeld = false;
        holdDir = null;
        waitUntilMs = 0L;
        releaseKeys();
        RouteRotation.clear();
        lastPositions.clear();
        counted.clear();
        boomBefore.clear();
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

    /** World change: the stopwatch belongs to the fight, not the client session. */
    static void resetStopwatch() {
        stopwatchStartMs = 0L;
        lastStopwatchMs = -1L;
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

    /**
     * Per render frame: the smooth camera step (LOOK) and a frame-rate poll of the mouse button so a short click
     * between two ticks is never missed.
     * <p>
     * killer560 (2026-09-20): "I used the look node and instantly my game dropped to sub 1fps ... Make sure it isn't
     * making me look thousands of times a second." The step is now taken only while a LOOK is actually turning the
     * camera (never for the rest of a running chain), at most once per {@value #MIN_FRAME_STEP_NANOS} ns however
     * many times the level-render hook fires in a frame, and the node itself ends after {@value #LOOK_TIMEOUT_MS} ms
     * of wall time even if the controller never reports settled - so a LOOK can neither spin nor stay armed.
     */
    static void tickFrame() {
        if (!running) {
            return;
        }
        if (RouteRotation.isActive() && activeNode != null && activeNode.type == Ap3Node.Type.LOOK) {
            long now = System.nanoTime();
            if (now - lastFrameStepNanos >= MIN_FRAME_STEP_NANOS) {
                lastFrameStepNanos = now;
                RouteRotation.frame();
            }
        }
        pollClick(Minecraft.getInstance());
    }

    // ------------------------------------------------------------------------------------------- ticking

    /** Call at the END of every client tick while {@link #isRunning()}; the feature has already applied the
     *  enabled / boss gates before this runs. */
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
        if (testMode && !screenOpen && anyNewButton(client)) {
            stop("you took control (test mode)");
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
            if (testMode) {
                stop("you took control (test mode)");
                return;
            }
            consumeClick(client); // a click with nothing waiting is just a click
        }
        try {
            clearMovement(); // every node writes its own input; the held walk fills in below when none did
            if (waitUntilMs > 0L) {
                if (System.currentTimeMillis() < waitUntilMs) {
                    applyHold(player);
                    applyFallbackKeys(client);
                    return;
                }
                waitUntilMs = 0L;
            }
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
            if (running) {
                applyHold(player);
            }
        } catch (Exception e) {
            LOGGER.error("[AP3] Chain error", e);
            stop("internal error (see log)");
            return;
        }
        applyFallbackKeys(client);
    }

    /** A manual left click ends whatever is waiting: the wait: modifier, a close gate, or a waiting node.
     *  killer560: "if I ever left click manually, then it should act like the terminal was completed. Same thing
     *  for leaps or any other type of wait modifier." One mechanism for every kind of wait. */
    private static boolean consumeClick(Minecraft client) {
        boolean feedback = Ap3Config.getInstance().isChatFeedback();
        if (waitUntilMs > 0L) {
            waitUntilMs = 0L;
            if (feedback) {
                chat(ModChat.text("Skipped wait"), ModChat.dim(" - you clicked"));
            }
            return true;
        }
        if (activeNode != null && step == Step.GATE) {
            releaseGate("you clicked");
            return true;
        }
        if (activeNode != null && activeNode.type.isWaiting() && step != Step.BOX) {
            Ap3Node skipped = activeNode;
            if (feedback) {
                chat(ModChat.text("Skipped "), ModChat.value("#" + number(skipped) + " " + skipped.type.label()),
                        ModChat.dim(" - you clicked"));
            }
            finishNode();
            applyFallbackKeys(client);
            return true;
        }
        return false;
    }

    private static boolean screenAllowed() {
        if (activeNode == null) {
            return false;
        }
        // The terminal GUI is the whole point of a TERMINAL node; the leap menu may show while LeapManager clicks it;
        // a close-gated node is waiting for exactly a GUI to open and close.
        return activeNode.type == Ap3Node.Type.TERMINAL
                || (activeNode.type == Ap3Node.Type.LEAP && LeapManager.isBusy())
                || step == Step.GATE;
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
        stepTicks = 0;
        settleTicks = 0;
        progressBest = Double.POSITIVE_INFINITY;
        noProgressTicks = 0;
        swapSent = false;
        gateSawScreen = false;
        if (node.type == Ap3Node.Type.TERMINAL) {
            // Never keep walking with a terminal GUI open - that is the one screen a node opens on purpose.
            holdDir = null;
        }
        step = node.closeGate && !testMode ? Step.GATE : Step.BOX;
        LOGGER.info("[AP3] Node #{} {}", number(node), node.describe());
    }

    private static void finishNode() {
        // Release the camera as soon as the node is done - a finished LOOK must not keep pulling the view back.
        RouteRotation.clear();
        // ...and drop the grace window with it. Click-skipping a LEAP used to leave 205 ticks of grace
        // running, which silently made the next LOOK node uninterruptible by the mouse (2026-09-16 review).
        cameraGraceTicks = 0;
        if (activeNode != null && activeNode.waitAfterMs > 0) {
            // "/ap3 add walk wait:1000 waits 1000ms after that node" - the held walk keeps going meanwhile.
            waitUntilMs = System.currentTimeMillis() + activeNode.waitAfterMs;
        }
        activeNode = null;
        step = null;
        nextNode++;
    }

    private static void tickNode(Minecraft client, LocalPlayer player) {
        Ap3Node node = activeNode;
        stepTicks++;
        if (step == Step.GATE) {
            tickGate(client);
            return;
        }
        if (step == Step.BOX) {
            if (!tickBoxWait(node, player)) {
                return;
            }
            // reached this tick - perform it now rather than a tick late
            step = Step.PREP;
            stepTicks = 1;
        }
        switch (node.type) {
            case ALIGN -> tickAlign(client, player, node);
            case AXIS_ALIGN -> tickAxisAlign(client, player, node);
            case WALK, RUN -> {
                // The direction and speed persist until a STOP or align node - the node itself is done at once.
                holdDir = node.dir();
                holdSprint = node.type == Ap3Node.Type.RUN;
                finishNode();
            }
            case LEAP -> tickLeap(client, player, node);
            case LEAP_COUNTER -> tickLeapCounter(client, player, node);
            case TERMINAL -> tickTerminal(node);
            case STOP -> tickStop(player);
            case LOOK -> tickLook(player, node);
            case BOOM -> tickBoom(client, player, node);
            case STOPWATCH -> {
                toggleStopwatch();
                finishNode();
            }
        }
    }

    // ---- close gate: "only fires on left click, or after a terminal closes" ------------------------------------

    private static void tickGate(Minecraft client) {
        if (client.screen != null) {
            gateSawScreen = true;
        } else if (gateSawScreen) {
            releaseGate("the screen closed");
        }
        // the click half lives in consumeClick(); the held walk keeps running meanwhile
    }

    private static void releaseGate(String why) {
        if (Ap3Config.getInstance().isChatFeedback()) {
            chat(ModChat.text("#" + number(activeNode) + " " + activeNode.type.label() + " released"), ModChat.dim(" - " + why));
        }
        step = Step.BOX;
        stepTicks = 0;
        gateSawScreen = false;
    }

    // ---- trigger box: perform the node once you are inside it --------------------------------------------------

    /** @return true when the node should be performed this tick. */
    private static boolean tickBoxWait(Ap3Node node, LocalPlayer player) {
        Vec3 pos = player.position();
        if (node.contains(pos)) {
            return true;
        }
        double dist = node.horizontalDistance(pos);
        String what = node.type.label().toLowerCase(Locale.ROOT);
        if (node.type.isAlign() && dist <= NEAR_ENOUGH) {
            return true;
        }
        if (holdDir == null) {
            if (node.type.isAlign()) {
                if (dist <= ALIGN_REACH) {
                    return true; // an align pulls you the rest of the way
                }
                stop(String.format(Locale.US, "too far from %s #%d (%.1f blocks)", what, number(node), dist));
                return false;
            }
            if (node.type.isPositional() && dist > START_TOLERANCE) {
                stop(String.format(Locale.US, "not standing at %s #%d (%.1f blocks away)", what, number(node), dist));
                return false;
            }
            return true; // nothing carries you anywhere - fire where you are
        }
        if (!node.type.isPositional() && dist <= NEAR_ENOUGH) {
            return true;
        }
        // Walking toward it: the distance has to keep shrinking, or the walk is going the wrong way / is blocked.
        if (dist < progressBest - 0.02) {
            progressBest = dist;
            noProgressTicks = 0;
        } else if (dist > progressBest + WALKED_PAST) {
            stop(String.format(Locale.US, "walked past %s #%d (%.1f blocks away)", what, number(node), dist));
        } else if (++noProgressTicks > Ap3Config.getInstance().getMoveTimeoutTicks()) {
            stop(String.format(Locale.US, "never reached %s #%d (%.1f blocks away)", what, number(node), dist));
        }
        return false;
    }

    // ---- ALIGN: to the node's exact point, drift accounted for ---------------------------------------------------

    private static void tickAlign(Minecraft client, LocalPlayer player, Ap3Node node) {
        holdDir = null;
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();
        double ex = node.x - pos.x;
        double ez = node.z - pos.z;
        double err = Math.sqrt(ex * ex + ez * ez);
        if (step == Step.PREP) {
            if (err > ALIGN_REACH + node.length / 2.0 + node.width / 2.0) {
                stop(String.format(Locale.US, "too far from align #%d (%.1f blocks)", number(node), err));
                return;
            }
            step = Step.DO;
        }
        // Drive toward where the target will be relative to where the slide is taking you, not where you are now.
        double px = ex - vel.x * DRIFT_FACTOR;
        double pz = ez - vel.z * DRIFT_FACTOR;
        if (settleAligned(client, player, node, err, px, pz)) {
            finishNode();
        }
    }

    // ---- AXIS_ALIGN: pressed square against the wall you placed it on, the other axis to the node ---------------

    private static void tickAxisAlign(Minecraft client, LocalPlayer player, Ap3Node node) {
        holdDir = null;
        Vec3 wall = node.wallVector();
        if (wall == null) {
            tickAlign(client, player, node); // a hand-written node with no wall recorded degrades to a plain ALIGN
            return;
        }
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();
        Vec3 perp = node.wallPerpendicular();
        double perpErr = (node.x - pos.x) * perp.x + (node.z - pos.z) * perp.z;
        double perpVel = vel.x * perp.x + vel.z * perp.z;
        double dist = node.horizontalDistance(pos);
        if (step == Step.PREP) {
            if (dist > ALIGN_REACH + node.length / 2.0 + node.width / 2.0) {
                stop(String.format(Locale.US, "too far from axis align #%d (%.1f blocks)", number(node), dist));
                return;
            }
            step = Step.DO;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        boolean touching = touching(client.level, player, node.wallDir);
        if (touching && Math.abs(perpErr) <= cfg.getAlignTolerance()) {
            clearMovement();
            if (Math.abs(perpVel) < SETTLE_SPEED) {
                if (++settleTicks >= SETTLE_TICKS) {
                    finishNode();
                }
            } else {
                settleTicks = 0;
            }
            return;
        }
        settleTicks = 0;
        if (stepTicks > cfg.getAlignTimeoutTicks()) {
            stop(String.format(Locale.US, "couldn't align on axis align #%d (%s, %.2f off)", number(node),
                    touching ? "on the wall" : "not on the wall", Math.abs(perpErr)));
            return;
        }
        // Into the wall at full speed until it stops you, then keep leaning on it; across it, the same
        // drift-compensated nudge a plain ALIGN uses. The wall itself is what makes this axis exact.
        double push = touching ? WALL_PUSH_HELD : 1.0;
        double predicted = perpErr - perpVel * DRIFT_FACTOR;
        double corr = Math.abs(perpErr) <= cfg.getAlignTolerance() ? 0.0
                : Math.copySign(Mth.clamp(Math.abs(predicted) * LATERAL_GAIN, MIN_NUDGE, 1.0), predicted);
        writeMove(player, wall.x * push + perp.x * corr, wall.z * push + perp.z * corr, false);
        wantSneak = touching && Math.abs(perpErr) < SNEAK_BELOW;
    }

    /** Whether the player's box is pressed against a collidable block on that side (within {@value #WALL_TOUCH}). */
    static boolean touching(Level level, LocalPlayer player, Direction side) {
        if (level == null || side == null) {
            return false;
        }
        AABB probe = player.getBoundingBox().move(side.getStepX() * WALL_TOUCH, 0.0, side.getStepZ() * WALL_TOUCH);
        return !level.noCollision(player, probe);
    }

    /**
     * At placement: the world side of the wall the player is pressed against, or null when none. killer560: an axis
     * align "cannot be placed unless you are actually touching a wall". Front first, so a corner picks the wall you
     * are facing.
     */
    static Direction touchingWall(Level level, LocalPlayer player) {
        Direction front = Ap3Node.nearestHorizontal(player.getYRot());
        Direction[] order = {front, front.getOpposite(), front.getCounterClockWise(), front.getClockWise()};
        for (Direction d : order) {
            if (touching(level, player, d)) {
                return d;
            }
        }
        return null;
    }

    /**
     * Drives the player along the world vector {@code (ex, ez)} (its length = how far off, drift already taken
     * out), sneaking for the last bit so the landing is precise, and reports settled once the REAL error
     * {@code err} is within tolerance with no momentum left.
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
        double h = Math.sqrt(ex * ex + ez * ez);
        if (h < 1e-3) {
            // The slide is already taking you onto the point - hands off and let it.
            clearMovement();
            wantSneak = err < SNEAK_BELOW;
            return false;
        }
        double mag = Mth.clamp(h * LATERAL_GAIN, MIN_NUDGE, 1.0);
        writeMove(player, ex / h * mag, ez / h * mag, false);
        wantSneak = err < SNEAK_BELOW;
        return false;
    }

    // ---- LEAP -----------------------------------------------------------------------------------------------

    private static void tickLeap(Minecraft client, LocalPlayer player, Ap3Node node) {
        switch (step) {
            case PREP -> {
                leapOrigin = player.position();
                leapStartMs = System.currentTimeMillis();
                if (!requestLeap(node)) {
                    return; // requestLeap stopped (or, in test mode, skipped) the node with the reason
                }
                step = Step.CONFIRM;
                stepTicks = 0;
                cameraGraceTicks = LEAP_TIMEOUT + 5; // the teleport's own camera change is not the player's mouse
            }
            case CONFIRM -> {
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
                    leapFailed(node, "leap failed (see the Fast Leap message)");
                } else if (stepTicks > LEAP_TIMEOUT) {
                    leapFailed(node, "leap timed out");
                }
            }
            default -> finishNode();
        }
    }

    /** In test mode a leap that cannot happen (no party, nobody of that class) is skipped, not fatal. */
    private static void leapFailed(Ap3Node node, String why) {
        if (testMode) {
            if (Ap3Config.getInstance().isChatFeedback()) {
                chat(ModChat.text("Skipped "), ModChat.value("#" + number(node) + " Leap"), ModChat.dim(" - test mode, " + why));
            }
            finishNode();
        } else {
            stop(why);
        }
    }

    /**
     * Submits the leap. With no modifier: whoever Fast Leap's target for this area resolves to - the same
     * resolution as {@code FastLeapFeature.leapToConfigured} (Name / Class / Posmsg-then-name-then-class), which is
     * package-private, so it is mirrored here on the public {@code FastLeapConfig} / {@code PosmsgTargets} /
     * {@code LeapManager} API. Class targets resolve through {@code Teammates.firstAliveOfClass}, which reads
     * {@code ClassOverrides} - so a manual override wins here exactly as it does for Fast Leap.
     * @return false when the node was ended here (stopped, or skipped in test mode)
     */
    private static boolean requestLeap(Ap3Node node) {
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        boolean block = cfg.isBlockInputs();
        boolean fast = cfg.isFastMode();
        boolean swap = cfg.isSwapBack();
        switch (node.leapMode) {
            case CLASS -> {
                if (node.leapClass == null) {
                    leapFailed(node, "leap #" + number(node) + " has no class");
                    return false;
                }
                LeapManager.leap(node.leapClass, block, fast, swap);
                return true;
            }
            case IGN -> {
                if (node.leapIgn == null || node.leapIgn.isBlank()) {
                    leapFailed(node, "leap #" + number(node) + " has no IGN");
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
                    leapFailed(node, "Fast Leap has no single " + chain.area().label() + " target - give leap #" + number(node) + " a class or IGN");
                    return false;
                }
                String name = cfg.getTargetName(target);
                DungeonClass clazz = cfg.getTargetClass(target);
                switch (cfg.getTargetMode()) {
                    case NAME -> {
                        if (name.isBlank()) {
                            leapFailed(node, "Fast Leap " + target.label + " has no name set");
                            return false;
                        }
                        LeapManager.leap(name, block, fast, swap);
                        return true;
                    }
                    case CLASS -> {
                        if (clazz == null) {
                            leapFailed(node, "Fast Leap " + target.label + " has no class set");
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
                        leapFailed(node, "no one has announced " + target.label + " yet");
                        return false;
                    }
                }
            }
        }
    }

    // ---- LEAP_COUNTER: N teammates have leapt TO you ---------------------------------------------------------

    /**
     * Hypixel prints no chat line when someone leaps to you (nothing in {@code LeapManager} / {@code ChatObserver}
     * sees one), so this counts arrivals by what a Spirit Leap physically is - a teleport: a teammate whose
     * position jumps by {@value #TELEPORT_JUMP}+ blocks in one tick (or who appears from unloaded) and lands within
     * the configured radius of you. Someone walking up to you never jumps that far in a tick, so they don't count.
     */
    private static void tickLeapCounter(Minecraft client, LocalPlayer player, Ap3Node node) {
        if (step == Step.PREP) {
            if (testMode) {
                skipForTest(node);
                return;
            }
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
        if (step == Step.PREP) {
            if (testMode) {
                skipForTest(node);
                return;
            }
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

    private static void skipForTest(Ap3Node node) {
        if (Ap3Config.getInstance().isChatFeedback()) {
            chat(ModChat.text("Skipped "), ModChat.value("#" + number(node) + " " + node.type.label()), ModChat.dim(" - test mode"));
        }
        finishNode();
    }

    // ---- STOP: "stops all movement. Exactly that, nothing else." ------------------------------------------

    private static void tickStop(LocalPlayer player) {
        // Ends the held walk; no input of any kind; holds until the slide a RUN leaves behind has died out
        // (ground friction), so whatever follows starts from a standstill.
        holdDir = null;
        clearMovement();
        Vec3 vel = player.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (speed <= SETTLE_SPEED || stepTicks > BRAKE_TIMEOUT) {
            finishNode();
        }
    }

    // ---- LOOK: client-side rotation only ----------------------------------------------------------------------

    private static void tickLook(LocalPlayer player, Ap3Node node) {
        if (step == Step.PREP) {
            // Target only: RouteRotation turns it into a wrapped delta on the running yaw every frame. The stored
            // yaw is never assigned to the player.
            RouteRotation.beginApproach(node.yaw, node.pitch, false, 0f, 0f);
            RouteRotation.clearUserMoved();
            lookHeld = true; // Ap3RotationSendMixin keeps this rotation off the wire while it holds
            lookStartMs = System.currentTimeMillis();
            lastFrameStepNanos = 0L;
            cameraGraceTicks = 1;
            step = Step.CONFIRM;
            return;
        }
        if (RouteRotation.settled(1.0f) || stepTicks > LOOK_TIMEOUT
                || System.currentTimeMillis() - lookStartMs > LOOK_TIMEOUT_MS) {
            lookYaw = player.getYRot();
            lookPitch = player.getXRot();
            finishNode(); // clears the controller; lookHeld stays until the chain ends or the player turns
        }
    }

    // ---- BOOM: superboom where the node was looking, from where you stand ---------------------------------

    private static void tickBoom(Minecraft client, LocalPlayer player, Ap3Node node) {
        switch (step) {
            case PREP -> {
                step = Step.SWAP;
                stepTicks = 0;
            }
            case SWAP -> {
                int slot = ItemIdentity.findHotbarSlotById(player, BOOM_IDS);
                if (slot < 0) {
                    stop("no Superboom in the hotbar");
                    return;
                }
                if (ensureSelected(player, slot)) {
                    step = Step.DO;
                    stepTicks = 0;
                } else if (stepTicks > SWAP_TIMEOUT) {
                    stop("couldn't switch to the Superboom");
                }
            }
            case DO -> {
                // killer560: "uses superboom exactly where you are looking, on that facing angle" - the ray is the
                // node's recorded yaw/pitch from the live eye position; the camera is not turned.
                Vec3 eye = player.getEyePosition();
                Vec3 look = lookVector(node.yaw, node.pitch).scale(BOOM_REACH);
                HitResult hit = client.level.clip(new ClipContext(eye, eye.add(look), ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, player));
                if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) {
                    stop("boom #" + number(node) + " isn't looking at a block");
                    return;
                }
                boomTarget = b.getBlockPos();
                boomBefore.clear();
                boomBefore.put(boomTarget, client.level.getBlockState(boomTarget));
                for (Direction d : Direction.values()) {
                    BlockPos p = boomTarget.relative(d);
                    boomBefore.put(p, client.level.getBlockState(p));
                }
                // Same packets Auto Routes' superboom node sends: a start + abort is the tap Hypixel reads as a click.
                player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, boomTarget, b.getDirection()));
                player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, boomTarget, b.getDirection()));
                player.swing(InteractionHand.MAIN_HAND);
                step = Step.CONFIRM;
                stepTicks = 0;
            }
            case CONFIRM -> {
                boolean changed = false;
                for (Map.Entry<BlockPos, BlockState> e : boomBefore.entrySet()) {
                    if (client.level.getBlockState(e.getKey()) != e.getValue()) {
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    boomBefore.clear();
                    finishNode();
                } else if (stepTicks > BOOM_TIMEOUT) {
                    if (testMode) {
                        skipForTest(node);
                    } else {
                        stop("superboom didn't break anything");
                    }
                }
            }
            default -> finishNode();
        }
    }

    /** Unit look vector for a yaw/pitch pair - the stored angles are only ever read, never written to the player. */
    private static Vec3 lookVector(float yaw, float pitch) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double cp = Math.cos(pr);
        return new Vec3(-Math.sin(yr) * cp, -Math.sin(pr), Math.cos(yr) * cp);
    }

    // ---- STOPWATCH ------------------------------------------------------------------------------------------

    /** First node starts it, the next prints the time (client-side only), the one after starts it again. */
    private static void toggleStopwatch() {
        long now = System.currentTimeMillis();
        if (stopwatchStartMs == 0L) {
            stopwatchStartMs = now;
            chat(ModChat.text("Stopwatch "), ModChat.good("started"));
        } else {
            lastStopwatchMs = now - stopwatchStartMs;
            stopwatchStartMs = 0L;
            chat(ModChat.text("Stopwatch: "), ModChat.value(formatStopwatch(lastStopwatchMs)));
        }
    }

    public static String formatStopwatch(long ms) {
        return String.format(Locale.US, "%.3fs", ms / 1000.0);
    }

    // ------------------------------------------------------------------------------------------- movement

    /** The held walk (WALK / RUN) when no node wrote its own input this tick. */
    private static void applyHold(LocalPlayer player) {
        if (!driving && holdDir != null) {
            writeMove(player, holdDir.x, holdDir.z, holdSprint);
        }
    }

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

    // ------------------------------------------------------------------------------------------- manual click / any button

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

    /** Test mode's "any button": records what is down right now so only a NEW press counts. */
    private static void snapshotButtons(Minecraft client) {
        try {
            long handle = client.getWindow().handle();
            for (int k = GLFW_FIRST_KEY; k <= GLFW_LAST_KEY; k++) {
                keyWasDown[k] = GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS;
            }
            for (int b = 0; b <= GLFW.GLFW_MOUSE_BUTTON_LAST; b++) {
                mouseWasDown[b] = GLFW.glfwGetMouseButton(handle, b) == GLFW.GLFW_PRESS;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Test mode: a rising edge on any keyboard key or mouse button since the last tick. Cheap - GLFW answers
     *  from its own state table, no events involved. */
    private static boolean anyNewButton(Minecraft client) {
        try {
            long handle = client.getWindow().handle();
            boolean pressed = false;
            for (int k = GLFW_FIRST_KEY; k <= GLFW_LAST_KEY; k++) {
                boolean down = GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS;
                if (down && !keyWasDown[k]) {
                    pressed = true;
                }
                keyWasDown[k] = down;
            }
            for (int b = 0; b <= GLFW.GLFW_MOUSE_BUTTON_LAST; b++) {
                boolean down = GLFW.glfwGetMouseButton(handle, b) == GLFW.GLFW_PRESS;
                if (down && !mouseWasDown[b]) {
                    pressed = true;
                }
                mouseWasDown[b] = down;
            }
            return pressed;
        } catch (Throwable t) {
            return false;
        }
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

    private static void chat(Component... parts) {
        ModChat.send(CHAT, parts);
    }

    private static void chatBad(String text) {
        ModChat.send(CHAT, ModChat.bad(text));
    }
}
