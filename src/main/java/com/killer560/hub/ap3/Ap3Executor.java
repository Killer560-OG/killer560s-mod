package com.killer560.hub.ap3;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.autoroutes.RouteRotation;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.FastLeapConfig;
import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;
import com.killer560.hub.fastleap.LeapManager;
import com.killer560.hub.fastleap.PosmsgTargets;
import com.killer560.hub.fastleap.Teammates;
import com.killer560.hub.util.ActionGate;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Performs AP3 nodes. CHEAT BUILD ONLY - only ever ticked by {@link Ap3Feature} behind {@link Ap3Config#isEnabled()}
 * and the BOSS-ONLY gate (any boss phase, one node set per {@link Ap3Area}).
 * <p>
 * <b>Every node is armed on its own; there is no sequence.</b> killer560 (2026-09-20): "if i hit node one, then
 * node two, then node 4, then node 18, they should all fire even though they arent in sequential order. The order
 * never matters in that sence." Walking into a node's trigger box ({@link Ap3Node#contains}) fires THAT node,
 * whichever one it is. A node that has fired does not fire again while you stay inside its box - it re-arms when
 * you leave (a rising edge on entry), so standing in one never machine-guns it.
 * <p>
 * <b>Universal priority, one node per tick.</b> When more than one node triggers on the same tick, they fire one per
 * tick in this order (killer560: "stop should go first, then align, then look, then walk, then boom, then leap. So
 * if i hit a boom and leap node on the same tick then it should boom then the next tick leap"):
 * {@code STOPWATCH > STOP > ALIGN / AXIS_ALIGN > LOOK > TERMINAL > LEAP_COUNTER > WALK / RUN > BOOM > LEAP}
 * ({@link Ap3Node.Type#priority()}). A triggered node waits in the {@link #queue} until the executor is free (the
 * previous node finished and any {@code wait:} modifier has elapsed) and then fires - <b>even if you have left its
 * box by then</b> (killer560: "yes it should still fire even if you leave the box by then"). Entering a box queues a
 * node once: it cannot be queued twice by a quick exit and re-entry while it is still waiting or being performed.
 * <p>
 * <b>What clears the queue</b> (a queued node is never fired somewhere it no longer makes sense): you take control
 * (a movement key, the mouse while a LOOK turns the camera, any key or button in test mode), a screen the active
 * node did not ask for opens, a node fails, {@code /ap3 stop}, leaving the boss, the area changing, a p3sim
 * restart, a world change, AP3 being turned off, the file being reloaded or a node being edited - and <b>a leap
 * landing</b>: whatever was queued was for where you were, and killer560's leap rule (below) says nothing moves on
 * the far side until a node THERE fires. Every one of those goes through {@link #stop} or {@link #onLeapHappened}.
 * <p>
 * <b>Held walk.</b> A WALK / RUN sets a <b>held</b> walk in its recorded world direction and completes at once; the
 * hold carries you along and lasts <b>until any other node fires</b> (killer560: "keep me walking until i hit a
 * different node") - so the node that ends it can be anything, a STOP, an ALIGN, a LOOK, a BOOM. With nothing
 * after it a walk keeps you moving until you take control.
 * <p>
 * <b>Leaping drops all movement</b> - killer560 (2026-09-20): "if it even encounters a leap node, it should stop all
 * movement when it goes to leap, so that tick it would look like drops all movement, leaps, and then no movement
 * on the other side unless it hits a node", and "Make sure that also happens if I am using auto leap and auto leap
 * goes off during it even if it isnt a node." A LEAP node releases every input first and THEN asks for the leap
 * ({@link #tickLeap}); a leap from anywhere else - Fast Leap / Auto Leap, a manual Spirit Leap click, a boss
 * teleport - is observed by {@link #onLeapHappened} and gets the same treatment: the hold is dropped for good, the
 * queue is cleared, and nothing moves again until a node at the destination fires. AP3 only ever OBSERVES Fast
 * Leap's leap; it never cancels, delays or re-triggers it.
 * <p>
 * <b>Your hands win.</b> A physical movement key ends everything AP3 is doing (mixin path: the untouched
 * {@code keyPresses}; fallback path: the physical keys through {@code KeyMappingKeyAccessor}). A node you walk
 * into while holding a key does not fire under your hands: it fires the moment you let go while still inside the
 * box, once, and not again until you leave and come back - so walking onto a node with W held and releasing is the
 * natural hand-over, and tapping a key on a node you already fired does not fire it again.
 * <p>
 * <b>Interactions go through {@link ActionGate}</b> as {@link ActionGate.Actor#ROUTE}: the boom's hotbar swap and
 * its destroy tap, and the leap request. AP3 already fires at most one node per tick and performs one node at a
 * time, so it asks the gate at most once per tick; a refused tick simply retries the same step next tick. The two
 * rules compose: AP3's priority decides WHICH node has the executor, the gate decides WHETHER that node's packet
 * goes out this tick alongside every other feature's.
 * <p>
 * <b>Movement</b> is written as the analog {@code moveVector} through {@code mixin/Ap3InputMixin} (falling back to
 * holding the key mappings when the mixin config is not loaded, exactly like {@code RouteExecutor}). A held walk
 * moves in the world direction its yaw was recorded in <b>without turning the camera</b> by projecting that fixed
 * world direction into the player's LIVE facing frame every tick ({@link #writeMove}).
 * <p>
 * <b>Rotation</b> (LOOK only) is a wrapped delta on the running yaw through {@link RouteRotation} (Rotation 360
 * rule); nothing in this class ever calls {@code setYRot}. The camera step is bounded to one per rendered frame
 * and the node to a real-time timeout - see {@link #tickFrame()} and {@link #tickLook}.
 * <p>
 * <b>Modifiers</b> on any node: {@code wait:<ms>} holds the NEXT queued node that long after this one
 * ({@link #waitUntilMs}); {@code close} makes the node perform only on a manual left click or once a GUI that was
 * open has closed ({@link Step#GATE}). ANY wait - a waiting node (LEAP, LEAP_COUNTER, TERMINAL), a close gate, a
 * wait: modifier - ends on a manual LEFT-CLICK ({@link #pollClick}), read straight from GLFW: this class never
 * presses the mouse, so a press can only be the player's.
 * <p>
 * <b>Test mode</b> ({@code /ap3 testmode}): nodes run without waiting on anything the arena would have to provide
 * (terminals, teammates, close gates; a failed leap is skipped), and ANY key or mouse button stops everything.
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
    /** Start crouching once within this much of the target: sneaking scales the input by the ~0.3 SNEAKING_SPEED
     *  attribute, which is the tool killer560 named ("use crouches") for cutting the per-tick step on the approach. */
    private static final double SNEAK_APPROACH = 0.4;
    private static final double SETTLE_SPEED = 0.02;
    private static final int SETTLE_TICKS = 2;
    /** How far an align node may pull you in from. A queued align fires even after you walked out of its box; past
     *  this it fails instead of dragging you across the room. */
    private static final double ALIGN_REACH = 4.0;
    /**
     * Below this error, movement input stops resolving the last fraction of a block: at Skyblock 550%+ speed even a
     * crouched single-tick step is ~0.2 blocks (movement-speed attribute, not vanilla), so no input can land inside
     * 0.05. killer560 (2026-09-20 in-game test): "it does a very poor job of centering ... consistent down to the
     * point where each number is .000 of the same ... my coordinate decimals should always read .500 .500". The last
     * bit is closed by a bounded DIRECT position correction (see {@link #settleAligned}) - honest, and small.
     */
    private static final double CORRECT_BELOW = 0.15;
    /** The direct correction never moves you more than this in one tick (a normal walking step is ~0.2), so it reads
     *  as ordinary movement rather than a teleport. At most ~2 ticks are ever needed (0.15 / 0.08). */
    private static final double MAX_CORRECT_PER_TICK = 0.08;
    /** Finished only within this of the exact target, so the coordinate reads {@code .500} to three decimals. */
    private static final double EXACT_EPS = 0.0004;
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
    /** A position jump of this much in ONE tick is a teleport, never running: sprinting at the 500 speed cap is
     *  ~1.4 blocks/tick. Used both for teammates leaping to you and for noticing that YOU were moved. */
    private static final double TELEPORT_JUMP = 8.0;
    private static final int BRAKE_TIMEOUT = 40;
    private static final int SWAP_TIMEOUT = 10;
    private static final int BOOM_TIMEOUT = 40;
    private static final double BOOM_REACH = 4.5;
    private static final String[] BOOM_IDS = {"INFINITE_SUPERBOOM_TNT", "SUPERBOOM_TNT"};
    private static final int GLFW_FIRST_KEY = GLFW.GLFW_KEY_SPACE;
    private static final int GLFW_LAST_KEY = GLFW.GLFW_KEY_LAST;

    /** GATE = waiting for the close modifier; the rest are the per-type phases. */
    private enum Step { GATE, PREP, SWAP, DO, CONFIRM }

    // ---- the armed node set ----
    /** The nodes of the area you stand in, all armed; null between sections / outside the boss. */
    private static Ap3Chain chain;
    /** Nodes whose box you were inside last tick (identity) - the edge detector's memory. */
    private static final Set<Ap3Node> inside = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Nodes you entered with a movement key held: they fire the moment you let go while still inside. */
    private static final Set<Ap3Node> unfired = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Triggered nodes waiting their turn, kept in priority order (ties keep trigger order). */
    private static final List<Ap3Node> queue = new ArrayList<>();
    /** Scratch: the nodes {@link #scanBoxes} triggered this tick, for the "queued behind" report. */
    private static final List<Ap3Node> triggeredThisTick = new ArrayList<>();
    private static Vec3 lastSelfPos;

    // ---- the node being performed ----
    private static Ap3Node activeNode;
    private static Step step;
    private static int stepTicks;
    private static String stopReason;
    private static int cameraGraceTicks;
    private static boolean testMode;

    // ---- held walk (WALK / RUN until any other node fires) ----
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
    /** Set by {@link #onGateDestroyed()} from the chat hook: Hypixel's own confirmation that a boom worked. */
    private static volatile boolean boomChatConfirmed;
    /** Half-extent of the cube of blocks a boom snapshots for its block-change confirmation. A Superboom's blast is
     *  bigger than one block, so the 6 face-neighbours the first cut sampled could all be unchanged even when the
     *  gate really broke (killer560, 2026-09-20: the gate WAS destroyed but AP3 said it wasn't). */
    private static final int BOOM_SCAN_RADIUS = 2;

    // ---- stopwatch (survives across areas; reset on world change) ----
    private static long stopwatchStartMs;
    private static long lastStopwatchMs = -1L;

    // ---- alignment progress ----
    private static int settleTicks;

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

    /** True while AP3 is doing something: performing a node, holding a walk, or holding triggered nodes in the
     *  queue. Nodes stay ARMED regardless - this is "busy", not "armed". */
    public static boolean isRunning() {
        return activeNode != null || holdDir != null || !queue.isEmpty();
    }

    /** True while an area's nodes are armed (you are in a boss area that has nodes). */
    public static boolean isArmed() {
        return chain != null && !chain.isEmpty();
    }

    /** Triggered nodes waiting their turn behind the active one. */
    public static int queuedCount() {
        return queue.size();
    }

    public static boolean isTestMode() {
        return testMode;
    }

    /** {@code /ap3 testmode}: session-only, never saved - a test mode that survived a restart would surprise him. */
    public static void setTestMode(boolean on) {
        testMode = on;
        if (on) {
            snapshotButtons(Minecraft.getInstance()); // keys already down (the Enter that sent the command) are not a takeover
        }
    }

    /** Milliseconds the stopwatch has been running, or -1 when it is not. */
    public static long stopwatchRunningMs() {
        return stopwatchStartMs == 0L ? -1L : System.currentTimeMillis() - stopwatchStartMs;
    }

    /** The last stopped time in milliseconds, or -1 when none yet. */
    public static long lastStopwatchMs() {
        return lastStopwatchMs;
    }

    /**
     * Ends everything AP3 is doing: the active node, the held walk, every queued node, every key. Tells the user why
     * (chat, when chat feedback is on) if anything was actually going on. Safe to call when idle. The nodes stay
     * armed - the ones you are standing in do not fire again until you leave and re-enter their box.
     */
    public static void stop(String reason) {
        boolean wasBusy = isRunning();
        stopReason = reason;
        activeNode = null;
        step = null;
        lookHeld = false;
        holdDir = null;
        waitUntilMs = 0L;
        queue.clear();
        releaseKeys();
        RouteRotation.clear();
        lastPositions.clear();
        counted.clear();
        boomBefore.clear();
        if (wasBusy) {
            LOGGER.info("[AP3] Stopped: {}", reason);
            if (reason != null && Ap3Config.getInstance().isChatFeedback()) {
                chat(ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
            }
        }
    }

    public static String stopReason() {
        return stopReason;
    }

    /** The node currently being performed (for the renderer's active highlight), or null. */
    static Ap3Node activeNode() {
        return activeNode;
    }

    /** The armed node set, or null. */
    static Ap3Chain armedChain() {
        return chain;
    }

    /** World change: the stopwatch belongs to the fight, not the client session. */
    static void resetStopwatch() {
        stopwatchStartMs = 0L;
        lastStopwatchMs = -1L;
    }

    // ------------------------------------------------------------------------------------------- arming

    /**
     * Arms {@code nodes} (the area you just entered, or the freshly reloaded file). {@code seedFromPosition} = the
     * boxes you are standing in right now count as already entered, so nothing fires until you step out and back
     * in - the rule after every placement / edit / reload ("adding a node at your feet must not drive you") and,
     * unless Continue Into Next Section is on, on arriving in a new area by a leap or teleport.
     */
    private static void arm(Ap3Chain nodes, boolean seedFromPosition, LocalPlayer player) {
        chain = nodes;
        queue.clear();
        inside.clear();
        unfired.clear();
        if (nodes != null && seedFromPosition && player != null) {
            Vec3 pos = player.position();
            for (Ap3Node n : nodes.nodes()) {
                if (n.contains(pos)) {
                    inside.add(n);
                }
            }
        }
    }

    /** After a placement / edit / reload: whatever box you stand in counts as already entered (see {@link #arm}).
     *  Also drops anything queued - the queued nodes may no longer exist. */
    static void resync() {
        queue.clear();
        LocalPlayer player = Minecraft.getInstance().player;
        arm(chain, true, player);
    }

    /** Leaving the boss / AP3 off / world change: stop everything and forget the armed set. */
    static void disarm(String reason) {
        stop(reason);
        chain = null;
        inside.clear();
        unfired.clear();
        triggeredThisTick.clear();
        lastSelfPos = null;
    }

    // ------------------------------------------------------------------------------------------- input hooks

    /** The input mixin asks this before touching anything. */
    public static boolean isSessionActive() {
        return isRunning();
    }

    public static void onMixinApplied() {
        mixinApplied = true;
    }

    /** From the input mixin: the player pressed a movement key themselves. Ends whatever AP3 is doing - the player
     *  taking the controls back always wins. Nodes stay armed; the ones you stand in re-fire only on re-entry. */
    public static void onUserMovementInput() {
        if (isRunning()) {
            stop("you moved");
        }
    }

    /** Driving the player this tick (the mixin asks this). Sneak alone (braking / the last bit of an alignment)
     *  still counts. */
    public static boolean isDriving() {
        return driving || wantSneak;
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
        if (!lookHeld) {
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
     * From {@link Ap3Feature}'s chat hook: Hypixel's "The gate has been destroyed!" line. This is the authoritative
     * confirmation for a boom - a Superboom's block-change can arrive a tick late or outside the sampled cube, but
     * the chat line means the gate is down (killer560, 2026-09-20: the line came through yet AP3 reported failure).
     */
    static void onGateDestroyed() {
        if (activeNode != null && activeNode.type == Ap3Node.Type.BOOM) {
            boomChatConfirmed = true;
        }
    }

    /**
     * A leap (or any teleport of you) just happened, whoever caused it: AP3's own LEAP node, Fast Leap / Auto Leap
     * firing on its own, a manual Spirit Leap click, a boss teleport. Fed by {@link Ap3Feature}'s chat hook
     * (Hypixel's "You have teleported to Name!" - the same line {@code leapcounter/LeapTracker} keys its own-leap
     * suppression on) and by {@link #tick}'s own-position jump check, which also covers p3sim if it prints nothing.
     * <p>
     * killer560: "drops all movement, leaps, and then no movement on the other side unless it hits a node" - and
     * "Make sure that also happens if I am using auto leap and auto leap goes off during it even if it isnt a node."
     * So: the held walk is dropped for good (never resumed on the far side), the queue is cleared (it was for where
     * you were), every key is released, and a node mid-way (an align, a stop) is cancelled - you are not there any
     * more. The one exception is AP3's own LEAP node waiting to confirm its landing: that IS this leap, so it is left
     * to finish. Nothing here touches Fast Leap: AP3 observes the leap, it never cancels, delays or re-triggers it.
     */
    static void onLeapHappened(String why) {
        RouteRotation.rebase(); // the teleport's own camera change is not the player's mouse
        boolean ownLeapConfirming = activeNode != null && activeNode.type == Ap3Node.Type.LEAP && step == Step.CONFIRM;
        if (!ownLeapConfirming) {
            stop(why);
            return;
        }
        holdDir = null;
        queue.clear();
        clearMovement();
        releaseKeys();
    }

    /**
     * Per render frame: the smooth camera step (LOOK) and a frame-rate poll of the mouse button so a short click
     * between two ticks is never missed.
     * <p>
     * killer560 (2026-09-20): "I used the look node and instantly my game dropped to sub 1fps ... Make sure it isn't
     * making me look thousands of times a second." The step is taken only while a LOOK is actually turning the
     * camera, at most once per {@value #MIN_FRAME_STEP_NANOS} ns however many times the level-render hook fires in
     * a frame, and the node itself ends after {@value #LOOK_TIMEOUT_MS} ms of wall time even if the controller
     * never reports settled - so a LOOK can neither spin nor stay armed.
     */
    static void tickFrame() {
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

    /**
     * Call at the END of every client tick while AP3 is enabled and the boss gate is open (the feature applies both
     * first). {@code current} is the node set of the area you stand in (null between sections); {@code arrival} is
     * true on the tick the area changed.
     */
    static void tick(Minecraft client, Ap3Chain current, boolean arrival) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            disarm("world change");
            return;
        }
        // YOU were moved 8+ blocks in one tick: a leap (auto, manual or ours), an etherwarp, a boss teleport. With
        // no previous position (first tick in the boss, a rejoin) the origin is unknown and treated the same way.
        Vec3 pos = player.position();
        boolean jumped = lastSelfPos == null || pos.distanceTo(lastSelfPos) >= TELEPORT_JUMP;
        if (current != chain) {
            // A new area, or the file was reloaded (new objects). The held walk and the node being performed carry
            // on - a walk from S1's last node has to survive the gap into S2 - but whatever was queued was for the
            // old area. WALKING into a new area: the boxes you are in fire, that is hitting a node. LANDING in one
            // by a leap / teleport: with Continue Into Next Section ON they fire at once; OFF, you must step out
            // and back in first. A reload is neither: nothing you stand in fires until you re-enter.
            boolean seed = !arrival || (jumped && !Ap3Config.getInstance().isContinueIntoNextSection());
            arm(current, seed, player);
        }
        if (lastSelfPos != null && jumped) {
            onLeapHappened("you leapt");
        }
        lastSelfPos = pos;
        if (player.isDeadOrDying() || player.isSpectator()
                || client.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
            stop("you died");
            applyFallbackKeys(client);
            return;
        }
        boolean busy = isRunning();
        boolean screenOpen = client.screen != null;
        if (busy) {
            if (screenOpen && !screenAllowed()) {
                stop("a screen opened");
                return;
            }
            if (!screenOpen && userPressedMovementKeyInFallback(client)) {
                stop("you moved");
                return;
            }
        }
        if (testMode) {
            // Polled every tick so the snapshot stays fresh; only a press while something is going on is a takeover.
            boolean pressed = anyNewButton(client);
            if (pressed && busy && client.screen == null) {
                stop("you took control (test mode)");
                return;
            }
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
            if (isRunning()) {
                if (testMode) {
                    stop("you took control (test mode)");
                    return;
                }
                consumeClick(client); // a click with nothing waiting is just a click
            }
        }
        try {
            clearMovement(); // every node writes its own input; the held walk fills in below when none did
            // With a screen the active node did not ask for open (chat, inventory, the mod menu) nothing new is
            // entered or begun - a box is "walked into" when you can act; the edge is seen once the screen closes.
            boolean canAct = !screenOpen || screenAllowed();
            triggeredThisTick.clear();
            if (canAct) {
                scanBoxes(client, player);
            }
            if (waitUntilMs > 0L) {
                if (System.currentTimeMillis() < waitUntilMs) {
                    reportQueued();
                    applyHold(player);
                    applyFallbackKeys(client);
                    return;
                }
                waitUntilMs = 0L;
            }
            if (activeNode == null && !queue.isEmpty() && canAct) {
                // ONE node per tick, highest priority first; the rest wait for the following ticks.
                beginNode(queue.remove(0), player);
            }
            reportQueued();
            if (activeNode != null) {
                tickNode(client, player);
            }
            applyHold(player);
        } catch (Exception e) {
            LOGGER.error("[AP3] Node error", e);
            stop("internal error (see log)");
            return;
        }
        applyFallbackKeys(client);
    }

    /**
     * The edge detector: entering a node's box (from outside) triggers it; staying inside does nothing; leaving
     * re-arms it. A box entered with a movement key held is remembered in {@link #unfired} and triggers the tick
     * you let go while still inside - once. The player's keys are read physically so this is right on both the
     * mixin path (where the key mappings are untouched) and the fallback path (where AP3 holds the mappings).
     */
    private static void scanBoxes(Minecraft client, LocalPlayer player) {
        if (chain == null) {
            return;
        }
        Vec3 pos = player.position();
        boolean handsOn = physicalMovementKeyDown(client);
        for (Ap3Node node : chain.nodes()) {
            boolean in = node.contains(pos);
            boolean was = inside.contains(node);
            if (in && !was) {
                inside.add(node);
                if (handsOn) {
                    unfired.add(node);
                } else {
                    trigger(node);
                }
            } else if (!in && was) {
                inside.remove(node);
                unfired.remove(node);
            } else if (in && !handsOn && unfired.remove(node)) {
                trigger(node);
            }
        }
    }

    /** Queues a triggered node in priority order. A node already waiting or being performed is not queued again -
     *  stepping out and back in while it waits its turn must not fire it twice. */
    private static void trigger(Ap3Node node) {
        if (node == activeNode) {
            return;
        }
        for (Ap3Node q : queue) {
            if (q == node) {
                return;
            }
        }
        int p = node.type.priority();
        int at = queue.size();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).type.priority() > p) {
                at = i;
                break;
            }
        }
        queue.add(at, node);
        triggeredThisTick.add(node);
    }

    /** The nodes triggered this tick that did NOT fire this tick - say so, so a boom that fires "late" is
     *  understood as the priority (or a wait) at work, not a miss. */
    private static void reportQueued() {
        if (triggeredThisTick.isEmpty()) {
            return;
        }
        for (Ap3Node node : triggeredThisTick) {
            boolean waiting = false;
            for (Ap3Node q : queue) {
                if (q == node) {
                    waiting = true;
                    break;
                }
            }
            if (!waiting) {
                continue;
            }
            String behind = activeNode != null ? "behind #" + number(activeNode) + " " + activeNode.type.label()
                    : waitUntilMs > 0L ? "behind a wait" : "(" + queue.size() + " waiting)";
            LOGGER.info("[AP3] Queued #{} {} {}", number(node), node.type.label(), behind);
            if (Ap3Config.getInstance().isChatFeedback()) {
                chat(ModChat.dim("Queued "), ModChat.value("#" + number(node) + " " + node.type.label()), ModChat.dim(" " + behind));
            }
        }
        triggeredThisTick.clear();
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
        if (activeNode != null && activeNode.type.isWaiting()) {
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

    // ------------------------------------------------------------------------------------------- nodes

    private static void beginNode(Ap3Node node, LocalPlayer player) {
        activeNode = node;
        stepTicks = 0;
        settleTicks = 0;
        swapSent = false;
        gateSawScreen = false;
        if (!node.type.isMover()) {
            // "keep me walking until i hit a different node" - this is that node, whatever it is. A WALK / RUN
            // replaces the hold with its own instead.
            holdDir = null;
        }
        step = node.closeGate && !testMode ? Step.GATE : Step.PREP;
        LOGGER.info("[AP3] Node #{} {}", number(node), node.describe());
    }

    /** A node failed: it ends, and so does everything else (the hold, the queue) - the nodes behind it were placed
     *  assuming it worked, and standing down is the only safe default. Re-enter a box to go again. */
    private static void failNode(String reason) {
        stop(reason);
    }

    private static void finishNode() {
        // Release the camera as soon as the node is done - a finished LOOK must not keep pulling the view back.
        RouteRotation.clear();
        // ...and drop the grace window with it. Click-skipping a LEAP used to leave 205 ticks of grace
        // running, which silently made the next LOOK node uninterruptible by the mouse (2026-09-16 review).
        cameraGraceTicks = 0;
        if (activeNode != null && activeNode.waitAfterMs > 0) {
            // "/ap3 add walk wait:1000 waits 1000ms after that node" - the next queued node waits that long; a
            // held walk keeps going meanwhile.
            waitUntilMs = System.currentTimeMillis() + activeNode.waitAfterMs;
        }
        activeNode = null;
        step = null;
    }

    private static void tickNode(Minecraft client, LocalPlayer player) {
        Ap3Node node = activeNode;
        stepTicks++;
        if (step == Step.GATE) {
            tickGate(client);
            return;
        }
        switch (node.type) {
            case ALIGN -> tickAlign(client, player, node);
            case AXIS_ALIGN -> tickAxisAlign(client, player, node);
            case WALK, RUN -> {
                // The direction and speed persist until any other node fires - the node itself is done at once.
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
        // the click half lives in consumeClick()
    }

    private static void releaseGate(String why) {
        if (Ap3Config.getInstance().isChatFeedback()) {
            chat(ModChat.text("#" + number(activeNode) + " " + activeNode.type.label() + " released"), ModChat.dim(" - " + why));
        }
        step = Step.PREP;
        stepTicks = 0;
        gateSawScreen = false;
    }

    // ---- ALIGN: to the node's exact point, drift accounted for ---------------------------------------------------

    private static void tickAlign(Minecraft client, LocalPlayer player, Ap3Node node) {
        Vec3 pos = player.position();
        Vec3 vel = player.getDeltaMovement();
        double ex = node.x - pos.x;
        double ez = node.z - pos.z;
        double err = Math.sqrt(ex * ex + ez * ez);
        if (step == Step.PREP) {
            if (err > ALIGN_REACH + node.length / 2.0 + node.width / 2.0) {
                failNode(String.format(Locale.US, "too far from align #%d (%.1f blocks)", number(node), err));
                return;
            }
            step = Step.DO;
        }
        // Drive toward where the target will be relative to where the slide is taking you, not where you are now.
        // The drift is modelled from the REAL block friction under your feet, not a fixed constant, so it holds at
        // 550%+ Skyblock speed where the old fixed 1.5x under-predicted the overshoot (killer560's report).
        double stopF = stopFactor(player);
        double px = ex - vel.x * stopF;
        double pz = ez - vel.z * stopF;
        if (settleAligned(client, player, node, err, px, pz)) {
            finishNode();
        }
    }

    /**
     * How far a horizontal slide carries you before friction stops it, as a multiple of the current per-tick
     * velocity: {@code f / (1 - f)} where {@code f} is the real per-tick horizontal friction (block friction x 0.91
     * on the ground, 0.91 in the air), read from the block under your feet - so "align must account for drift" is
     * computed from the surface you are actually on rather than an assumed vanilla value. Clamped so an airborne or
     * ice reading can't produce a wild prediction.
     */
    private static double stopFactor(LocalPlayer player) {
        double f = 0.6 * 0.91;
        try {
            if (player.onGround()) {
                BlockState below = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement());
                f = below.getBlock().getFriction() * 0.91;
            } else {
                f = 0.91;
            }
        } catch (Throwable ignored) {
        }
        double factor = f / (1.0 - f);
        return Mth.clamp(factor, 0.5, 3.0);
    }

    // ---- AXIS_ALIGN: pressed square against the wall you placed it on, the other axis to the node ---------------

    private static void tickAxisAlign(Minecraft client, LocalPlayer player, Ap3Node node) {
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
                failNode(String.format(Locale.US, "too far from axis align #%d (%.1f blocks)", number(node), dist));
                return;
            }
            step = Step.DO;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        boolean touching = touching(client.level, player, node.wallDir);
        // Exact on the perpendicular axis with no momentum, pressed to the wall - the wall makes the other axis exact.
        if (touching && Math.abs(perpErr) <= EXACT_EPS && Math.abs(perpVel) < SETTLE_SPEED) {
            clearMovement();
            if (++settleTicks >= SETTLE_TICKS) {
                finishNode();
            }
            return;
        }
        settleTicks = 0;
        if (stepTicks > cfg.getAlignTimeoutTicks()) {
            failNode(String.format(Locale.US, "couldn't align on axis align #%d (%s, %.3f off)", number(node),
                    touching ? "on the wall" : "not on the wall", Math.abs(perpErr)));
            return;
        }
        // Final approach on the perpendicular axis: same bounded direct correction a plain ALIGN uses, while still
        // leaning into the wall so the wall axis stays flush. Movement input can't hit .000 at 550% speed.
        if (Math.abs(perpErr) <= CORRECT_BELOW) {
            double stepMag = Math.copySign(Math.min(Math.abs(perpErr), MAX_CORRECT_PER_TICK), perpErr);
            player.setPos(pos.x + perp.x * stepMag, pos.y, pos.z + perp.z * stepMag);
            Vec3 nv = player.getDeltaMovement();
            double vPerp = nv.x * perp.x + nv.z * perp.z; // cancel the perpendicular slide, keep the wall-ward push
            player.setDeltaMovement(nv.x - perp.x * vPerp, nv.y, nv.z - perp.z * vPerp);
            writeMove(player, wall.x * WALL_PUSH_HELD, wall.z * WALL_PUSH_HELD, false);
            wantSneak = true;
            return;
        }
        // Into the wall at full speed until it stops you, then keep leaning on it; across it, the same
        // drift-compensated nudge a plain ALIGN uses, crouched as it closes in.
        double push = touching ? WALL_PUSH_HELD : 1.0;
        double predicted = perpErr - perpVel * stopFactor(player);
        double corr = Math.copySign(Mth.clamp(Math.abs(predicted) * LATERAL_GAIN, MIN_NUDGE, 1.0), predicted);
        writeMove(player, wall.x * push + perp.x * corr, wall.z * push + perp.z * corr, false);
        wantSneak = touching && Math.abs(perpErr) < SNEAK_APPROACH;
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
     * {@code err} is on the exact point with no momentum left.
     */
    private static boolean settleAligned(Minecraft client, LocalPlayer player, Ap3Node node, double err,
                                         double px, double pz) {
        Ap3Config cfg = Ap3Config.getInstance();
        Vec3 vel = player.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Finished only on the exact point with no momentum, so the coordinate reads .500/.500 (or the precise
        // node's own value) rather than "somewhere inside the tolerance".
        if (err <= EXACT_EPS && speed < SETTLE_SPEED) {
            clearMovement();
            return true;
        }
        if (stepTicks > cfg.getAlignTimeoutTicks()) {
            failNode(String.format(Locale.US, "couldn't align on #%d (%.3f blocks off)", number(node), err));
            return false;
        }
        // Final approach: at 550%+ speed no movement input can land the last fraction of a block, so close it with a
        // bounded DIRECT position correction toward the exact target and kill the horizontal slide. Capped at
        // MAX_CORRECT_PER_TICK (< a normal walking step) so it is indistinguishable from ordinary movement.
        if (err <= CORRECT_BELOW) {
            clearMovement();
            Vec3 pos = player.position();
            double ex = node.x - pos.x;
            double ez = node.z - pos.z;
            if (err > 1e-6) {
                double stepMag = Math.min(err, MAX_CORRECT_PER_TICK);
                player.setPos(pos.x + ex / err * stepMag, pos.y, pos.z + ez / err * stepMag);
            }
            player.setDeltaMovement(0.0, vel.y, 0.0);
            return false;
        }
        settleTicks = 0;
        double h = Math.sqrt(px * px + pz * pz);
        if (h < 1e-3) {
            // The slide is already taking you onto the point - hands off, crouched, and let it.
            clearMovement();
            wantSneak = true;
            return false;
        }
        double mag = Mth.clamp(h * LATERAL_GAIN, MIN_NUDGE, 1.0);
        writeMove(player, px / h * mag, pz / h * mag, false);
        // Crouch on the approach to cut the per-tick step (killer560: "use crouches"), so the drive doesn't blow
        // past CORRECT_BELOW at high speed.
        wantSneak = err < SNEAK_APPROACH;
        return false;
    }

    // ---- LEAP -----------------------------------------------------------------------------------------------

    /**
     * killer560: "it should stop all movement when it goes to leap, so that tick it would look like drops all
     * movement, leaps, and then no movement on the other side unless it hits a node." In that order, here:
     * <ol>
     * <li>drop all movement - the held walk is ended for good (nothing resumes it on the far side), the analog
     *     input is cleared and, on the fallback path, the key mappings are let go NOW;</li>
     * <li>then leap - through the gate, then {@link #requestLeap};</li>
     * <li>on the far side nothing moves: {@link #onLeapHappened} (fed by the landing) clears anything queued, and
     *     only a node at the destination can start movement again.</li>
     * </ol>
     * If the leap fails or is cancelled (no target, menu never opened, timeout) the movement stays released - the
     * hold was dropped before the request, and a failure ends in {@link #stop}, never in a resumed walk.
     */
    private static void tickLeap(Minecraft client, LocalPlayer player, Ap3Node node) {
        switch (step) {
            case PREP -> {
                // 1. drops all movement
                holdDir = null;
                clearMovement();
                releaseKeys();
                // 2. leaps - one interaction, through the mod-wide gate like every other automated click
                if (!ActionGate.tryAct(ActionGate.Actor.ROUTE)) {
                    return; // standing still; ask again next tick
                }
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
                // 3. no movement on the other side: holdDir stays null; a node at the destination has to fire.
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

    /** In test mode a leap that cannot happen (no party, nobody of that class) is skipped, not fatal. Either way
     *  the movement released before the request stays released. */
    private static void leapFailed(Ap3Node node, String why) {
        if (testMode) {
            if (Ap3Config.getInstance().isChatFeedback()) {
                chat(ModChat.text("Skipped "), ModChat.value("#" + number(node) + " Leap"), ModChat.dim(" - test mode, " + why));
            }
            finishNode();
        } else {
            failNode(why);
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
                // Fast Leap's target for the node's area. P2 has five targets (predev, green, yellow, purple, py)
                // and S5 none, so neither has a single default - those leaps need a class or IGN.
                Ap3Area area = chain == null ? null : chain.area();
                LeapTarget target = area == null ? null : switch (area.phase()) {
                    case P1 -> LeapTarget.P1;
                    case P3 -> switch (area.section()) {
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
                    leapFailed(node, "Fast Leap has no single " + (area == null ? "area" : area.label())
                            + " target - give leap #" + number(node) + " a class or IGN");
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
        // The held walk was already ended when this node began; no input of any kind; holds until the slide a RUN
        // leaves behind has died out (ground friction), so whatever follows starts from a standstill.
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
            finishNode(); // clears the controller; lookHeld stays until a stop or the player turns
        }
    }

    // ---- BOOM: superboom where the node was looking, from where you stand ---------------------------------

    private static void tickBoom(Minecraft client, LocalPlayer player, Ap3Node node) {
        switch (step) {
            case PREP -> {
                boomChatConfirmed = false;
                step = Step.SWAP;
                stepTicks = 0;
            }
            case SWAP -> {
                int slot = ItemIdentity.findHotbarSlotById(player, BOOM_IDS);
                if (slot < 0) {
                    failNode("no Superboom in the hotbar");
                    return;
                }
                if (player.getInventory().getSelectedSlot() != slot) {
                    if (!swapSent) {
                        // The swap packet takes the tick's interaction slot like any other automated action; a
                        // refused tick costs nothing - the same swap is asked for again next tick.
                        if (!ActionGate.tryAct(ActionGate.Actor.ROUTE)) {
                            return;
                        }
                        player.getInventory().setSelectedSlot(slot);
                        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
                        swapSent = true;
                        stepTicks = 0;
                    } else if (stepTicks > SWAP_TIMEOUT) {
                        failNode("couldn't switch to the Superboom");
                    }
                    return;
                }
                if (!swapSent || stepTicks >= 2) { // the tick after a swap the server has seen it
                    step = Step.DO;
                    stepTicks = 0;
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
                    failNode("boom #" + number(node) + " isn't looking at a block");
                    return;
                }
                // Last check before anything is sent: the gate decides whether this tick's interaction is ours.
                if (!ActionGate.tryAct(ActionGate.Actor.ROUTE)) {
                    return;
                }
                boomTarget = b.getBlockPos();
                boomBefore.clear();
                // Snapshot a cube around the hit block, not just its 6 faces - a Superboom breaks a wider area.
                for (int dx = -BOOM_SCAN_RADIUS; dx <= BOOM_SCAN_RADIUS; dx++) {
                    for (int dy = -BOOM_SCAN_RADIUS; dy <= BOOM_SCAN_RADIUS; dy++) {
                        for (int dz = -BOOM_SCAN_RADIUS; dz <= BOOM_SCAN_RADIUS; dz++) {
                            BlockPos p = boomTarget.offset(dx, dy, dz);
                            boomBefore.put(p, client.level.getBlockState(p));
                        }
                    }
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
                boolean changed = boomChatConfirmed; // Hypixel's "gate destroyed" line is proof enough on its own
                if (!changed) {
                    for (Map.Entry<BlockPos, BlockState> e : boomBefore.entrySet()) {
                        if (client.level.getBlockState(e.getKey()) != e.getValue()) {
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) {
                    boomBefore.clear();
                    boomChatConfirmed = false;
                    finishNode();
                } else if (stepTicks > BOOM_TIMEOUT) {
                    if (testMode) {
                        skipForTest(node);
                    } else {
                        failNode("superboom didn't break anything");
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
        boolean any = driving || wantSneak;
        if (!any) {
            // Idle: the mappings are the player's own - only let go of what WE were holding, never write them.
            if (fallbackKeysHeld) {
                releaseKeys();
            }
            return;
        }
        client.options.keyUp.setDown(wantForward);
        client.options.keyDown.setDown(wantBackward);
        client.options.keyLeft.setDown(wantLeft);
        client.options.keyRight.setDown(wantRight);
        client.options.keyShift.setDown(wantSneak);
        client.options.keySprint.setDown(wantSprint);
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
     * that the fallback is in use - a walk that cannot be stopped is the worst failure this feature has.
     */
    private static boolean userPressedMovementKeyInFallback(Minecraft client) {
        if (mixinApplied || !fallbackKeysHeld) {
            return false;
        }
        if (!warnedFallback) {
            warnedFallback = true;
            LOGGER.warn("[AP3] Input mixin did not apply - driving with key mappings instead (8-way only). "
                    + "Movement keys are polled directly so you can still stop it.");
        }
        return physicalMovementKeyDown(client);
    }

    /** Whether any movement key (WASD / jump) is physically down right now - GLFW state, so it is the player's own
     *  hand on both input paths. */
    private static boolean physicalMovementKeyDown(Minecraft client) {
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

    private static void chat(Component... parts) {
        ModChat.send(CHAT, parts);
    }
}
