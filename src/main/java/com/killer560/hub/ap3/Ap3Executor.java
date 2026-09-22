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
 * <b>Your hands win - except for an align.</b> A physical movement key ends everything AP3 is doing (mixin path:
 * the untouched {@code keyPresses}; fallback path: the physical keys through {@code KeyMappingKeyAccessor}). A node
 * you walk into while holding a key does not fire under your hands: it fires the moment you let go while still
 * inside the box, once, and not again until you leave and come back - so walking onto a node with W held and
 * releasing is the natural hand-over, and tapping a key on a node you already fired does not fire it again. An
 * ALIGN / AXIS_ALIGN is the exception - killer560 (2026-09-21): "if I am holding a movement key and run into them
 * then it should make me stop holding my key and align me." Entering one fires it at once, hands or no hands, and
 * while it is queued or being performed {@link #isInputOverridden()} has the input mixin replace his keys with
 * AP3's own input (the physical keys are never touched), so they count as released; the moment the align completes
 * (or a stop / leap / teleport ends it) his keys are his again and, if still held, move him on the next tick.
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
 * <b>The server-side yaw is locked to the walk</b> - killer560 (2026-09-21): "serverside I am always looking in the
 * proper angle for 45 degree strafing, but client side I am not ... essentially a freecam style." Whenever a held
 * walk drives, a separate running {@link #serverYaw} is what the server receives instead of the camera yaw
 * ({@code mixin/Ap3RotationSendMixin} swaps it in around {@code sendPosition} only); it starts from the live yaw and
 * moves in bounded per-tick steps to the walk direction +-45 degrees with the "45 Degree Strafe" toggle on (the key
 * record the server sees is then W+A / W+D against THAT yaw - "always pressing w and some other movement key ... so I
 * can effectively be sprinting"), or to the walk direction exactly with it off (W only). The sprint is kept alive
 * whatever the camera does ({@code mixin/Ap3StrafeImpulseMixin}). The camera and the movement frame never see it;
 * the THIRD-PERSON model of the local player does ({@code mixin/Ap3ThirdPersonMixin}, {@link #thirdPersonModelYaw}):
 * "If I go into f5 it still looks like my head is facing the way my actual crosshair is pointed when it should be at
 * whatever angle is most optimal." When the walk ends both glide back onto the camera the same way - see
 * {@link #tickStrafe}.
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
    /** Ticks an align must sit inside the tolerance with zero velocity and no input before it counts as done. */
    private static final int SETTLE_TICKS = 2;
    /** How far an align node may pull you in from. A queued align fires even after you walked out of its box; past
     *  this it fails instead of dragging you across the room. */
    private static final double ALIGN_REACH = 4.0;
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
    /** 45 Degree Strafe: the server-side yaw sits this far off the walk direction (W+A / W+D strafing). */
    private static final float STRAFE_ANGLE = 45f;
    /** The server-side yaw never moves more than this in one tick - a fast flick, still a hand's flick. */
    private static final float STRAFE_MAX_STEP = 40f;
    /** Within this of its target the server-side yaw takes the (sub-half-degree) remainder and is done. */
    private static final float STRAFE_DONE = 0.5f;
    /** Most the sent yaw turns in one tick while an align steers it (a flick a hand does; under STRAFE_MAX_STEP). */
    private static final double ALIGN_YAW_STEP = 30.0;

    // ---- server-side yaw lock (a running value; the mixin sends it in place of the camera yaw) ----
    /** True while {@link #serverYaw} is what goes to the server (driving, or gliding back onto the camera yaw). */
    private static boolean strafeLock;
    /** The walk ended: gliding the server-side yaw back onto the camera yaw before letting go. */
    private static boolean strafeReturning;
    /** The running server-side yaw. Seeded from the player's own live yaw, only ever moved by wrapped deltas. */
    private static float serverYaw;
    /** {@link #serverYaw} as it was before this tick's step - the render interpolation's other end. */
    private static float serverYawO;
    // ---- the local player's third-person model (F5), drawn at the server-side yaw while it is locked ----
    /** The yaw the model was last drawn at; after the lock lets go it glides from here onto vanilla's body yaw. */
    private static float modelYaw;
    /** True from the first locked frame until the after-glide has met vanilla's body yaw. */
    private static boolean modelOwned;
    private static long modelLastNanos;
    /** +1 = server yaw is the walk direction + 45 (the server sees W+A), -1 = -45 (W+D). Picked once per hold. */
    private static int strafeSide;
    /** Per-tick fraction of the remaining turn, rolled per lock so no two turns decay identically. */
    private static float strafeSmoothing = 0.55f;
    /** The hold this lock's side was picked for (identity) - a new WALK node re-picks. */
    private static Vec3 strafeHold;
    private static boolean rotationMixinApplied;
    private static boolean sprintMixinApplied;
    /** The world unit direction the last {@link #writeMove} drove along (for re-deriving the key record). */
    private static double driveX;
    private static double driveZ;

    // ---- dev-only align timer (BuildVariant.DEV_TOOLS) ----
    /** Align nodes whose box was entered and not yet completed: {tick, wall-clock ms} at entry (identity). */
    private static final Map<Ap3Node, long[]> alignEntered = new IdentityHashMap<>();
    private static long tickCounter;

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
    /** The sneak flag of the record installed THIS tick (= the crouch multiplier the NEXT travel uses). */
    private static boolean lastSneakSent;

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
        traceEnd();
        activeNode = null;
        step = null;
        alignPredValid = false;
        lookHeld = false;
        holdDir = null;
        holdNode = null;
        waitUntilMs = 0L;
        queue.clear();
        releaseKeys();
        RouteRotation.clear();
        lastPositions.clear();
        counted.clear();
        boomBefore.clear();
        alignEntered.clear();
        // The strafe lock is NOT dropped here: with the hold gone, tickStrafe glides the server-side yaw back onto
        // the camera yaw over the next ticks (never a snap). Only a teleport lets go at once - see onLeapHappened.
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
     * in - the rule after every placement / edit / reload only ("adding a node at your feet must not drive you").
     * Arriving in a new area - walking, leaping or teleported - never seeds: a box you arrive in fires at once.
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
        blockedNodes.clear();
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

    /** {@code mixin/Ap3RotationSendMixin} reports in on every {@code sendPosition} - the strafe lock is refused
     *  until it has, so a W+A key record can never go out against a yaw the server never receives. */
    public static void onRotationMixinApplied() {
        rotationMixinApplied = true;
    }

    /** {@code mixin/Ap3StrafeImpulseMixin} reports in. Without it the lock still works; the client just cannot
     *  sprint while the camera points away from the walk (the server then sees a slower, still coherent, strafe). */
    public static void onSprintMixinApplied() {
        sprintMixinApplied = true;
    }

    /**
     * From {@code mixin/Ap3RotationSendMixin}: the yaw to put in this tick's movement packet in place of the camera
     * yaw, or NaN when the camera yaw goes out as normal. A running value (Rotation 360 rule): seeded from the
     * player's own live yaw and only ever moved by bounded wrapped deltas, never wrapped itself.
     */
    public static float strafeServerYaw() {
        return strafeLock ? serverYaw : Float.NaN;
    }

    /** From {@code mixin/Ap3StrafeImpulseMixin}: the lock is driving a held walk right now (not gliding back). */
    public static boolean isStrafeDriving() {
        return strafeLockedForHold() && driving;
    }

    /**
     * From {@code mixin/Ap3ThirdPersonMixin}, once per frame the local player's model is drawn (F5): the yaw to
     * draw the body AND head at, or NaN to leave vanilla's. While the lock holds it is {@link #serverYaw}
     * interpolated across the tick, so his own third-person view shows what the server (and everyone else) is
     * told; once the lock lets go the model glides from where it was drawn last onto vanilla's body yaw with the
     * same fraction-per-tick / degrees-per-tick bounds the server-side yaw uses ({@link #stepServerYaw}), scaled to
     * the real time between frames - no pop. Render-only: nothing here is written to the player or sent anywhere.
     */
    public static float thirdPersonModelYaw(float vanillaBodyYaw, float partialTick) {
        long now = System.nanoTime();
        if (strafeLock) {
            modelYaw = serverYawO + Mth.wrapDegrees(serverYaw - serverYawO) * Mth.clamp(partialTick, 0f, 1f);
            modelOwned = true;
            modelLastNanos = now;
            return modelYaw;
        }
        if (!modelOwned) {
            return Float.NaN;
        }
        float delta = Mth.wrapDegrees(vanillaBodyYaw - modelYaw);
        if (Math.abs(delta) <= STRAFE_DONE) {
            modelOwned = false;
            return Float.NaN;
        }
        // Frames are not ticks: the per-tick glide (fraction s, at most STRAFE_MAX_STEP) sampled at dt ticks is
        // 1 - (1 - s)^dt of the remainder, capped at STRAFE_MAX_STEP * dt. dt is capped at one tick so a model that
        // was not drawn for a while (first person) does not take one big step when it next is.
        float dtTicks = Mth.clamp((now - modelLastNanos) / 50_000_000f, 0f, 1f);
        modelLastNanos = now;
        float fraction = 1f - (float) Math.pow(1.0 - strafeSmoothing, dtTicks);
        float cap = STRAFE_MAX_STEP * dtTicks;
        modelYaw += Mth.clamp(delta * fraction, -cap, cap);
        return modelYaw;
    }

    /** The lock is on for the current hold (not gliding back). Read inside {@link #writeMove}, i.e. BEFORE this
     *  tick's {@code driving} is set, which is why it does not look at that flag. */
    private static boolean strafeLockedForHold() {
        return strafeLock && !strafeReturning && holdDir != null;
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

    /**
     * From the input mixin: an align owns the input right now, so his held movement keys are replaced by AP3's
     * own input instead of counting as a takeover (see the class doc). True from the tick an ALIGN / AXIS_ALIGN
     * is triggered - queued behind a higher-priority node or already active, gate wait included - until it
     * finishes or is ended by a stop / leap / teleport (both clear the active node and the queue).
     */
    public static boolean isInputOverridden() {
        if (activeNode != null && activeNode.type.isAlign()) {
            return true;
        }
        for (Ap3Node q : queue) {
            if (q.type.isAlign()) {
                return true;
            }
        }
        return false;
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
        // The server just set our rotation itself (a teleport carries one), so the running server-side yaw is
        // stale: let go now rather than glide a value the server no longer holds back onto the camera.
        releaseStrafeNow();
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
        tickCounter++;
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
            // old area. Arriving in a new area, whether you walked, leapt or were teleported into it: the boxes you
            // are in fire at once - killer560 (2026-09-21): "it should always continue the second you hit a node no
            // matter what section you are supposed to be in" (the old Continue Into Next Section toggle is gone).
            // A reload is different: nothing you stand in fires until you re-enter.
            arm(current, !arrival, player);
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
        traceAlignTick(client, player);
    }

    /**
     * The edge detector: entering a node's box (from outside) triggers it; staying inside does nothing; leaving
     * re-arms it. A box entered with a movement key held is remembered in {@link #unfired} and triggers the tick
     * you let go while still inside - once - EXCEPT an align, which fires on entry regardless and takes the keys
     * over ({@link #isInputOverridden()}). The player's keys are read physically so this is right on both the
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
            if (nodeBlocked(node, in)) {
                // a correction cancelled this node: it re-arms only after a second outside its box
                if (in) {
                    inside.add(node);
                } else {
                    inside.remove(node);
                    unfired.remove(node);
                }
                continue;
            }
            boolean was = inside.contains(node);
            if (in && !was) {
                inside.add(node);
                if (node.type.isAlign() && Ap3Config.getInstance().isAlignTimerDev()) {
                    // Dev timer: "from the moment I enter an align to the moment it is fully aligned" - the clock
                    // starts on the entry edge, whether the node fires now, waits its turn, or waits for his hands.
                    alignEntered.put(node, new long[]{tickCounter, System.currentTimeMillis()});
                    traceStart();
                }
                if (handsOn && !node.type.isAlign()) {
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
        if (activeNode != null && activeNode.type.isAlign()) {
            traceEnd();
        }
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
                holdNode = node;
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

    // ---- ALIGN: onto the node's point with DISCRETE keys, by one of three methods -----------------------------
    //
    // killer560 on real Hypixel, fda6ad4 (2026-09-21 19:02): 14 server position corrections in 2 seconds during ONE
    // align - fractional stick values matched no key combination. 316516f: discrete keys, but every tap made under a
    // sent yaw that differed from the camera (the silent strafe lock, 17-35 degrees off) was corrected on the first
    // tap. So every tick's movement is one of the 17 real key combinations, and HOW the direction is chosen is a
    // setting he can A/B on an alt (Ap3Config.AlignMethod, each reporting its corrections in the dev line):
    //   Keys + Sneak (default) - camera yaw only, sent == camera always; exhaustive sneak-tap search (a keyboard's best);
    //   Sent-Yaw Planner       - the two-tap planner steering the sent yaw (exact; corrected on Hypixel in his test);
    //   Camera Planner         - the two-tap planner turning his REAL camera by bounded deltas on the live yaw, so the
    //                            sent yaw always equals the camera and nothing is hidden (exact; expected to pass).
    // NOTHING writes position or velocity. If the server corrects the position anyway, the align is abandoned at once
    // and never retried without re-entering the box (onServerPositionPacket), and two corrections in ten seconds
    // switch AP3 off.

    private static void tickAlign(Minecraft client, LocalPlayer player, Ap3Node node) {
        Vec3 pos = player.position();
        double ex = node.x - pos.x;
        double ez = node.z - pos.z;
        double err = Math.max(Math.abs(ex), Math.abs(ez));
        Ap3Config cfg = Ap3Config.getInstance();
        Ap3Config.AlignMethod method = cfg.getAlignMethod();
        if (step == Step.PREP) {
            double dist = Math.sqrt(ex * ex + ez * ez);
            if (dist > ALIGN_REACH + node.length / 2.0 + node.width / 2.0) {
                failNode(String.format(Locale.US, "too far from align #%d (%.1f blocks)", number(node), dist));
                return;
            }
            step = Step.DO;
            alignModelReset();
            if (method == Ap3Config.AlignMethod.SENT_YAW) {
                // The sent yaw is the keys' frame for the whole align: take it over now (seeded from the live yaw).
                engageYawLock(player);
            }
        }
        Vec3 vel = player.getDeltaMovement();
        boolean still = Ap3AlignMath.horizontalZeroed(vel.x, vel.z);
        alignObserve(player);
        if (keysBestEffort && still) {
            // Keys + Sneak could not get inside the tolerance and nothing in its search gets closer: done as it is.
            clearMovement();
            if (++settleTicks >= SETTLE_TICKS) {
                if (cfg.isChatFeedback()) {
                    chat(ModChat.text("Align "), ModChat.value("#" + number(node)), ModChat.dim(String.format(Locale.US,
                            " - keys-only best: %.4f off (turn on a planner method for exact)", err)));
                }
                reportAlignTimer(node, pos.x - node.x, pos.z - node.z, null);
                finishNode();
            }
            return;
        }
        // Done: both axes inside the tolerance and no velocity the game will still apply, held SETTLE_TICKS with no
        // input so it is really at rest.
        if (err <= cfg.getAlignTolerance() && still) {
            clearMovement();
            if (++settleTicks >= SETTLE_TICKS) {
                reportAlignTimer(node, pos.x - node.x, pos.z - node.z, null);
                finishNode();
            }
            return;
        }
        settleTicks = 0;
        if (stepTicks > cfg.getAlignTimeoutTicks()) {
            // Never a direct correction: if the keys cannot land it in time, the node fails, as it always did.
            failNode(String.format(Locale.US, "couldn't align on #%d (%.4f blocks off)", number(node), err));
            return;
        }
        if (method != Ap3Config.AlignMethod.SENT_YAW && strafeLock
                && Math.abs(Mth.wrapDegrees(serverYaw - player.getYRot())) > STRAFE_DONE) {
            // A walk's lock is still gliding the sent yaw back to the camera: nothing moves under a sent yaw that is
            // not the camera on these methods. tickStrafe glides faster while an align waits (2-3 ticks).
            clearMovement();
            alignPhase = "waiting for the sent yaw to reach the camera";
            return;
        }
        driveDiscrete(player, ex, ez, method);
    }

    /**
     * Dev builds only: how long this align took from box entry to aligned and where it settled on EACH axis - signed,
     * feet minus target, so "-0.0003" means it stopped on the negative side of the point (killer560: "off in x axis
     * and off in z axis") - plus the method, the worst one-tick prediction miss and the server corrections seen. For
     * an axis align the wall axis is marked: that axis is set by the wall and is the only one it aligns.
     */
    private static void reportAlignTimer(Ap3Node node, double offX, double offZ, Direction.Axis wallAxis) {
        long[] entered = alignEntered.remove(node);
        if (entered == null || !Ap3Config.getInstance().isAlignTimerDev()) {
            return;
        }
        long ticks = tickCounter - entered[0];
        double seconds = (System.currentTimeMillis() - entered[1]) / 1000.0;
        String xTag = wallAxis == Direction.Axis.X ? " (wall)" : "";
        String zTag = wallAxis == Direction.Axis.Z ? " (wall)" : "";
        String offs = String.format(Locale.US, "X off %+.4f%s, Z off %+.4f%s", offX, xTag, offZ, zTag);
        String model = String.format(Locale.US, "%s, worst miss %.5f, server corrections %d",
                wallAxis != null ? "wall push" : Ap3Config.getInstance().getAlignMethod().label, alignWorstMiss, alignCorrections);
        LOGGER.info("[AP3 dev] {} #{} took {} ({} ticks) - {} ({})", node.type.label(), number(node),
                String.format(Locale.US, "%.2fs", seconds), ticks, offs, model);
        ModChat.send("AP3 dev", ModChat.text(node.type.label() + " "), ModChat.value("#" + number(node)),
                ModChat.text(" took "), ModChat.value(String.format(Locale.US, "%.2fs", seconds)),
                ModChat.dim(String.format(Locale.US, " (%d ticks) - ", ticks)),
                ModChat.text("X off "), ModChat.value(String.format(Locale.US, "%+.4f", offX)), ModChat.dim(xTag + ", "),
                ModChat.text("Z off "), ModChat.value(String.format(Locale.US, "%+.4f", offZ)), ModChat.dim(zTag),
                ModChat.dim(" (" + model + ")"));
    }

    // ---- the movement model's bookkeeping (per align) ----
    private static boolean alignPredValid;
    private static double alignPredX, alignPredZ;
    private static double alignWorstMiss;
    /** What the planner decided last tick, for the trace. */
    private static String alignPhase = "";

    /** Keys + Sneak gave up improving: finish at rest with the achieved error (see driveDiscrete). */
    private static boolean keysBestEffort;

    private static void alignModelReset() {
        alignPredValid = false;
        alignWorstMiss = 0.0;
        alignCorrections = 0;
        alignPhase = "";
        keysBestEffort = false;
    }

    /** Compares last tick's predicted position with where the player really is now (debug log in dev builds; the
     *  worst miss goes on the dev line). A miss is a fact the model did not know: a collision, a push, a correction. */
    private static void alignObserve(LocalPlayer player) {
        if (!alignPredValid) {
            return;
        }
        alignPredValid = false;
        Vec3 pos = player.position();
        double missX = pos.x - alignPredX;
        double missZ = pos.z - alignPredZ;
        alignWorstMiss = Math.max(alignWorstMiss, Math.max(Math.abs(missX), Math.abs(missZ)));
        if (com.killer560.hub.BuildVariant.DEV_TOOLS && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[AP3 dev] align model: predicted ({}, {}) actual ({}, {}) miss ({}, {})",
                    String.format(Locale.US, "%.5f", alignPredX), String.format(Locale.US, "%.5f", alignPredZ),
                    String.format(Locale.US, "%.5f", pos.x), String.format(Locale.US, "%.5f", pos.z),
                    String.format(Locale.US, "%.6f", missX), String.format(Locale.US, "%.6f", missZ));
        }
    }

    // ---- server corrections (mixin/Ap3PositionPacketMixin), the abort and the circuit breaker ----
    /** Server position packets seen during the current align and the 10 ticks after it. */
    private static int alignCorrections;
    /** Wall-clock times of the last corrections seen while AP3 was moving him (the circuit breaker's window). */
    private static final java.util.ArrayDeque<Long> correctionTimes = new java.util.ArrayDeque<>();
    /** Corrections inside this window disable AP3 outright. */
    private static final int BREAKER_COUNT = 2;
    private static final long BREAKER_WINDOW_MS = 10_000L;
    /** Nodes a correction cancelled: they do not fire again until he has been OUTSIDE their box this many ticks. */
    private static final Map<Ap3Node, int[]> blockedNodes = new IdentityHashMap<>();
    private static final int BLOCKED_OUTSIDE_TICKS = 20;
    /** The WALK / RUN node whose hold is running (for blocking it after a correction). */
    private static Ap3Node holdNode;

    /**
     * From {@code mixin/Ap3PositionPacketMixin}, BEFORE vanilla applies the packet: the server is moving us by this
     * delta from where the client is. While AP3 is moving him that is Hypixel rejecting the movement (killer560:
     * "spit me out right back the way I entered it", 14 of them in one align on fda6ad4) - so: everything AP3 is
     * doing stops at once, the node(s) involved are blocked until he has left their box for a second, and two such
     * corrections inside ten seconds switch AP3 off altogether until he turns it back on. Logged and counted into
     * the dev line either way. Nothing is changed about the packet; vanilla applies it as always.
     */
    public static void onServerPositionPacket(double dx, double dy, double dz) {
        boolean moving = activeNode != null || holdDir != null || driving;
        boolean inAlign = activeNode != null && activeNode.type.isAlign();
        if (inAlign || traceActive) {
            alignCorrections++;
        }
        LOGGER.info("[AP3 dev] SERVER CORRECTION #{}{}: delta ({}, {}, {}) blocks", alignCorrections,
                moving ? " while AP3 was moving you" : (traceActive ? " (align tail)" : ""),
                String.format(Locale.US, "%.4f", dx), String.format(Locale.US, "%.4f", dy), String.format(Locale.US, "%.4f", dz));
        if (!moving) {
            return;
        }
        if (activeNode != null) {
            blockedNodes.put(activeNode, new int[]{0});
        }
        if (holdNode != null) {
            blockedNodes.put(holdNode, new int[]{0});
        }
        stop("server corrected your position - stopped to avoid flags");
        long now = System.currentTimeMillis();
        correctionTimes.addLast(now);
        while (!correctionTimes.isEmpty() && now - correctionTimes.peekFirst() > BREAKER_WINDOW_MS) {
            correctionTimes.pollFirst();
        }
        if (correctionTimes.size() >= BREAKER_COUNT) {
            correctionTimes.clear();
            Ap3Feature.disableAfterError("the server corrected your position " + BREAKER_COUNT
                    + " times in 10 seconds - AP3 is OFF until you turn it back on, to avoid flags");
        }
    }

    /** A node a correction cancelled stays blocked until he has been outside its box for a second - the correction
     *  itself moves him out and back in, which must never re-fire it. Called from scanBoxes for every node. */
    private static boolean nodeBlocked(Ap3Node node, boolean in) {
        int[] outside = blockedNodes.get(node);
        if (outside == null) {
            return false;
        }
        if (in) {
            outside[0] = 0;
            return true;
        }
        if (++outside[0] >= BLOCKED_OUTSIDE_TICKS) {
            blockedNodes.remove(node);
            return false;
        }
        return true;
    }

    // ---- the per-tick trace, dev builds ----
    /** True from the tick an align box is entered until 10 ticks after the align ended (dev builds, timer on). */
    private static boolean traceActive;
    /** Ticks of trace still to log after the align ended; -1 while it is still going. */
    private static int traceCountdown = -1;
    private static int traceTick;

    /** Entry edge of an align box: start the trace (dev builds with the align timer on). */
    private static void traceStart() {
        if (!com.killer560.hub.BuildVariant.DEV_TOOLS || !Ap3Config.getInstance().isAlignTimerDev()) {
            return;
        }
        if (!traceActive) {
            traceTick = 0;
            alignCorrections = 0;
        }
        traceActive = true;
        traceCountdown = -1;
    }

    /** The align ended (done, failed, stopped): keep logging for 10 more ticks so the tail is on record. */
    private static void traceEnd() {
        if (traceActive && traceCountdown < 0) {
            traceCountdown = 10;
        }
    }

    /**
     * One INFO line per tick, dev builds only, from the moment an align box is entered until 10 ticks after the align
     * ended: what was measured, what keys and yaw go out for the next tick, what the planner decided, what the model
     * predicts, and whether the override / lock were on - so the next test log shows exactly where anything comes from.
     */
    private static void traceAlignTick(Minecraft client, LocalPlayer player) {
        if (!traceActive) {
            return;
        }
        traceTick++;
        try {
            Vec3 pos = player.position();
            Vec3 vel = player.getDeltaMovement();
            String input = driving
                    ? String.format(Locale.US, "keys[%s%s%s%s%s%s] move(%.3f,%.3f)",
                            wantForward ? "W" : "", wantBackward ? "S" : "", wantLeft ? "A" : "", wantRight ? "D" : "",
                            wantSneak ? " sneak" : "", wantSprint ? " sprint" : "", moveX, moveY)
                    : "none" + (wantSneak ? " (sneak)" : "");
            String predicted = alignPredValid ? String.format(Locale.US, "(%.5f, %.5f)", alignPredX, alignPredZ) : "-";
            float sent = strafeServerYaw();
            String phase = activeNode != null && activeNode.type.isAlign()
                    ? "ALIGN #" + number(activeNode) + " " + step + (alignPhase.isEmpty() ? "" : " " + alignPhase)
                    : traceCountdown >= 0 ? "after +" + (10 - traceCountdown) : "queued";
            LOGGER.info("[AP3 dev] trace t{} {} [{}] | pos ({}, {}, {}) vel ({}, {}) | input {} | vanilla sprint={} crouch={} onGround={} | predicted next {} | override={} lock={} sentYaw={} camYaw={} | corrections {}",
                    traceTick, phase, Ap3Config.getInstance().getAlignMethod().label,
                    String.format(Locale.US, "%.5f", pos.x), String.format(Locale.US, "%.3f", pos.y), String.format(Locale.US, "%.5f", pos.z),
                    String.format(Locale.US, "%.5f", vel.x), String.format(Locale.US, "%.5f", vel.z),
                    input, player.isSprinting(), player.isCrouching(), player.onGround(), predicted,
                    isInputOverridden(), strafeLock,
                    Float.isNaN(sent) ? "camera" : String.format(Locale.US, "%.2f", sent),
                    String.format(Locale.US, "%.2f", player.getYRot()), alignCorrections);
        } catch (RuntimeException e) {
            LOGGER.warn("[AP3 dev] trace line failed", e);
        }
        if (traceCountdown > 0) {
            traceCountdown--;
        } else if (traceCountdown == 0) {
            traceActive = false;
            traceCountdown = -1;
        }
    }

    // ---- the discrete drive ----

    /** The game's own trig table, so the planner's directions are the ones {@code getInputVector} produces. */
    private static final Ap3DiscretePlanner.Trig MTH = new Ap3DiscretePlanner.Trig() {
        @Override
        public float cos(float rad) {
            return Mth.cos(rad);
        }

        @Override
        public float sin(float rad) {
            return Mth.sin(rad);
        }
    };

    /** The friction of the block under the feet ({@code getBlockPosBelowThatAffectsMyMovement}), as the game reads it. */
    private static float blockFriction(LocalPlayer player) {
        try {
            BlockState below = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement());
            return below.getBlock().getFriction();
        } catch (Throwable t) {
            return 0.6f;
        }
    }

    private static double sneakSpeed(LocalPlayer player) {
        try {
            double v = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
            return v > 0.01 && v <= 1.0 ? v : 0.3;
        } catch (Throwable t) {
            return 0.3;
        }
    }

    /** Everything the planner needs to know about what the game will do with a key, read from the player now. */
    private static Ap3DiscretePlanner.Model modelFor(LocalPlayer player, boolean yawSteerable) {
        Ap3DiscretePlanner.Model m = new Ap3DiscretePlanner.Model();
        double attr = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        m.baseSpeedAttr = player.isSprinting() ? attr / Ap3AlignMath.SPRINT_MULTIPLIER : attr;
        m.blockFriction = blockFriction(player);
        m.onGround = player.onGround();
        m.sneakMul = sneakSpeed(player);
        m.tolerance = Ap3Config.getInstance().getAlignTolerance();
        m.trig = MTH;
        m.yawSteerable = yawSteerable;
        m.yawStepCap = ALIGN_YAW_STEP;
        return m;
    }

    /** Takes the sent yaw over for a Sent-Yaw align (seeded from the live yaw when it is not already ours). */
    private static void engageYawLock(LocalPlayer player) {
        if (!mixinApplied || !rotationMixinApplied) {
            return; // no coherent frame to steer: the align runs keys-only at the camera yaw
        }
        if (!strafeLock) {
            serverYaw = player.getYRot();
            serverYawO = serverYaw;
            strafeSmoothing = 0.5f + (float) (Math.random() * 0.2);
            LOGGER.info("[AP3] Server-side yaw: locking for an align from yaw {}", String.format(Locale.US, "%.1f", serverYaw));
        }
        strafeLock = true;
        strafeReturning = false;
        strafeHold = null;
        lookHeld = false;
    }

    /**
     * One tick of the discrete align: asks {@link Ap3DiscretePlanner} for this tick's keys (and, on the planner
     * methods, the yaw they are to be sent at) from the measured state, applies the yaw as a bounded delta on the
     * running value - the sent yaw (Sent-Yaw Planner) or his real camera yaw (Camera Planner) - and installs exactly
     * those keys. The record the server sees is the keys; the moveVector the client uses is the same keys, turned
     * from the sent frame into the camera frame only when the two differ (Sent-Yaw Planner); on the other two
     * methods the frame IS the camera and the raw key vector goes in.
     */
    private static void driveDiscrete(LocalPlayer player, double ex, double ez, Ap3Config.AlignMethod method) {
        Vec3 vel = player.getDeltaMovement();
        boolean sentYawMethod = method == Ap3Config.AlignMethod.SENT_YAW && strafeLock && mixinApplied && rotationMixinApplied;
        boolean cameraMethod = method == Ap3Config.AlignMethod.CAMERA;
        Ap3DiscretePlanner.Model m = modelFor(player, sentYawMethod || cameraMethod);
        Ap3DiscretePlanner.State s = new Ap3DiscretePlanner.State();
        s.ex = ex;
        s.ez = ez;
        s.vx = vel.x;
        s.vz = vel.z;
        s.sprinting = player.isSprinting();
        s.crouching = lastSneakSent; // the multiplier the next travel uses = the shift of the record installed this tick
        s.sentYaw = sentYawMethod ? serverYaw : player.getYRot();

        Ap3DiscretePlanner.Plan plan = Ap3DiscretePlanner.plan(s, m);
        alignPhase = plan.phase + (plan.reaches ? " ok" : " best") + String.format(Locale.US, " %.1e in %d", plan.restError, plan.ticks);
        double errNow = Math.max(Math.abs(ex), Math.abs(ez));
        if (method == Ap3Config.AlignMethod.KEYS_SNEAK && !plan.reaches && Ap3AlignMath.horizontalZeroed(vel.x, vel.z)
                && plan.restError >= errNow - 1e-9) {
            // Keys + Sneak, at rest, and no key sequence the search can find lands closer: that is a keyboard's honest
            // best here. The node ends as done with the achieved error on the dev line and in chat - not a failure,
            // the chain goes on - rather than tapping around until the timeout.
            keysBestEffort = true;
            clearMovement();
            return;
        }
        float frameYaw = s.sentYaw;
        if (sentYawMethod) {
            // A bounded delta on the running sent yaw (the planner never asks for more than the cap).
            float delta = Mth.clamp(Mth.wrapDegrees(plan.yaw - serverYaw), -STRAFE_MAX_STEP, STRAFE_MAX_STEP);
            serverYawO = serverYaw;
            serverYaw += delta;
            frameYaw = serverYaw;
        } else if (cameraMethod) {
            // His real camera turns by a bounded delta on the live running yaw - the same primitive a LOOK uses
            // (RouteRotation), never an assignment - so the yaw the server receives is the camera, and it is the
            // frame the keys go out in. He sees the turn; nothing is hidden.
            float delta = Mth.clamp(Mth.wrapDegrees(plan.yaw - player.getYRot()), -(float) ALIGN_YAW_STEP, (float) ALIGN_YAW_STEP);
            if (Math.abs(delta) > 1e-4f) {
                player.setYRot(player.getYRot() + delta);
                RouteRotation.rebase(); // our turn is not the player's mouse
            }
            frameYaw = player.getYRot();
        }
        Ap3DiscretePlanner.Action a = plan.action;
        // prediction for the trace / miss log: one exact step of the model at the yaw that will go out
        Ap3DiscretePlanner.State pred = s.copy();
        Ap3DiscretePlanner.step(pred, a, frameYaw, m);
        Vec3 pos = player.position();
        alignPredX = pos.x + (ex - pred.ex);
        alignPredZ = pos.z + (ez - pred.ez);
        alignPredValid = true;
        writeDiscrete(player, a.fw(), a.st(), a.sneak(), false, frameYaw, m, s.crouching);
    }

    /** The nearest of the eight key directions at {@code yaw} to the world direction {@code (dx, dz)}: {fw, st}. */
    private static int[] nearestKey8(double dx, double dz, float yaw) {
        double dirYaw = Math.toDegrees(Math.atan2(-dx, dz));
        float rel = Mth.wrapDegrees((float) (dirYaw - yaw));
        int octant = Math.round(rel / 45f);
        switch (((octant % 8) + 8) % 8) {
            case 0: return new int[]{1, 0};      // W
            case 1: return new int[]{1, -1};     // W+D
            case 2: return new int[]{0, -1};     // D
            case 3: return new int[]{-1, -1};    // S+D
            case 4: return new int[]{-1, 0};     // S
            case 5: return new int[]{-1, 1};     // S+A
            case 6: return new int[]{0, 1};      // A
            default: return new int[]{1, 1};     // W+A
        }
    }

    /**
     * Installs one real key combination for the next tick. The record the server sees is exactly {@code (fw, st,
     * sneak, sprint)}. The client moveVector is the same keys: their world direction at {@code frameYaw} (what the
     * server is told), turned into the camera frame through the exact inverse of vanilla's pipeline, so the player
     * moves exactly as the server expects. When the frame IS the camera the raw key vector is installed as is.
     */
    private static void writeDiscrete(LocalPlayer player, int fw, int st, boolean sneak, boolean sprint, float frameYaw,
                                      Ap3DiscretePlanner.Model m, boolean crouchingNow) {
        if (fw == 0 && st == 0) {
            clearMovement();
            wantSneak = sneak;
            return;
        }
        double norm = Math.sqrt(fw * fw + st * st);
        float camYaw = player.getYRot();
        if (Math.abs(Mth.wrapDegrees(frameYaw - camYaw)) < 1e-4f) {
            moveX = (float) (st / norm);
            moveY = (float) (fw / norm);
        } else {
            Ap3DiscretePlanner.Action a = new Ap3DiscretePlanner.Action(fw, st, sneak);
            double eff = Ap3DiscretePlanner.effectiveLength(a, crouchingNow, m.sneakMul);
            double speed = m.tickSpeed(sprint || (player.isSprinting() && fw > 0));
            float fr = frameYaw * Ap3AlignMath.DEG_TO_RAD;
            double c = Mth.cos(fr), sn = Mth.sin(fr);
            double ux = st / norm, uz = fw / norm;
            double dvx = speed * eff * (ux * c - uz * sn);
            double dvz = speed * eff * (uz * c + ux * sn);
            float cr = camYaw * Ap3AlignMath.DEG_TO_RAD;
            float[] mv = Ap3AlignMath.moveVectorFor(dvx, dvz, speed, Mth.cos(cr), Mth.sin(cr), crouchingNow ? m.sneakMul : 1.0);
            moveX = mv[0];
            moveY = mv[1];
        }
        float fr = frameYaw * Ap3AlignMath.DEG_TO_RAD;
        double c = Mth.cos(fr), sn = Mth.sin(fr);
        driveX = (st / norm) * c - (fw / norm) * sn;
        driveZ = (fw / norm) * c + (st / norm) * sn;
        wantForward = fw > 0;
        wantBackward = fw < 0;
        wantLeft = st > 0;
        wantRight = st < 0;
        wantSneak = sneak;
        wantSprint = sprint;
        driving = true;
    }

    // ---- AXIS_ALIGN: walk into the wall; the collision pins the wall axis exactly ---------------------------------
    //
    // killer560 (2026-09-21): "the axis align logic should be different since it should align touching the wall, it
    // should be a lot more precise, a lot easier." An axis align needs ONLY the wall axis: one key held toward the
    // wall at the camera yaw (the nearest of the eight directions, sprinting on the way in when it is W) until the
    // collision stops him - the block face pins that coordinate exactly (.300 / .700) with no fine control at all -
    // then keep leaning for the settle ticks. The along-wall axis is left alone. Camera yaw only, discrete keys only.

    private static void tickAxisAlign(Minecraft client, LocalPlayer player, Ap3Node node) {
        Vec3 wall = node.wallVector();
        if (wall == null) {
            tickAlign(client, player, node); // a hand-written node with no wall recorded degrades to a plain ALIGN
            return;
        }
        Vec3 pos = player.position();
        double dist = node.horizontalDistance(pos);
        if (step == Step.PREP) {
            if (dist > ALIGN_REACH + node.length / 2.0 + node.width / 2.0) {
                failNode(String.format(Locale.US, "too far from axis align #%d (%.1f blocks)", number(node), dist));
                return;
            }
            step = Step.DO;
            alignModelReset();
        }
        Ap3Config cfg = Ap3Config.getInstance();
        boolean touching = touching(client.level, player, node.wallDir);
        alignPredValid = false; // the wall zeroes the axis; nothing to predict
        Direction.Axis axis = node.wallDir.getAxis();
        Vec3 vel = player.getDeltaMovement();
        double wallVel = axis == Direction.Axis.X ? vel.x : vel.z;
        int[] key = nearestKey8(wall.x, wall.z, player.getYRot());
        boolean straightIn = key[0] == 1 && key[1] == 0;
        if (touching && Math.abs(wallVel) < 1e-9) {
            // Pinned by the block face. Keep leaning through the settle so the coordinate cannot drift.
            if (++settleTicks >= SETTLE_TICKS) {
                reportAlignTimer(node, pos.x - node.x, pos.z - node.z, axis);
                finishNode();
                return;
            }
            writeDiscrete(player, key[0], key[1], false, false, player.getYRot(), modelFor(player, false), lastSneakSent);
            return;
        }
        settleTicks = 0;
        if (stepTicks > cfg.getAlignTimeoutTicks()) {
            double off = axis == Direction.Axis.X ? pos.x - node.x : pos.z - node.z;
            failNode(String.format(Locale.US, "couldn't reach the wall on axis align #%d (%s, %.4f off on the wall axis)",
                    number(node), touching ? "on the wall" : "not on the wall", off));
            return;
        }
        alignPhase = touching ? "leaning on the wall" : "walking into the wall";
        // Into the wall; sprint on the way in when the key is W (vanilla starts it from the record and the forward
        // impulse), never once touching.
        writeDiscrete(player, key[0], key[1], false, straightIn && !touching, player.getYRot(), modelFor(player, false), lastSneakSent);
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
        if (speed <= Ap3AlignMath.ZERO_VELOCITY || stepTicks > BRAKE_TIMEOUT) {
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

    /**
     * The held walk (WALK / RUN) when no node wrote its own input this tick. DISCRETE: the movement is exactly the
     * key combination the server is told, at the yaw it is told - W+A / W+D (45 Degree Strafe on) or W at the
     * locked server-side yaw, which is the walk's recorded direction once the lock has settled there (a curve of a
     * few degrees for the 2-4 ticks it takes); without the lock (mixins missing) the nearest of the eight key
     * directions at the camera yaw. Never the recorded direction as a fractional stick vector.
     */
    private static void applyHold(LocalPlayer player) {
        if (driving || holdDir == null) {
            return;
        }
        if (strafeLockedForHold()) {
            boolean diag = Ap3Config.getInstance().isStrafe45();
            float keyYaw = diag ? serverYaw - strafeSide * STRAFE_ANGLE : serverYaw;
            double r = Math.toRadians(keyYaw);
            writeMove(player, -Math.sin(r), Math.cos(r), holdSprint, diag);
            return;
        }
        float walkYaw = (float) Math.toDegrees(Math.atan2(-holdDir.x, holdDir.z));
        float rel = Mth.wrapDegrees(walkYaw - player.getYRot());
        int octant = Math.round(rel / 45f);
        double r = Math.toRadians(player.getYRot() + octant * 45f);
        writeMove(player, -Math.sin(r), Math.cos(r), holdSprint, (octant & 1) != 0);
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
     * <li>45 Degree Strafe ON: length 1/0.98, so the mapping lands on 1.00 - exactly the real W+A speed the server
     * expects from the W+A / W+D record it is sent.</li>
     * <li>OFF: length 1/d for this direction's own unit-square distance d, so the mapping lands on 0.98 - exactly
     * the plain-W speed the server expects from the W record, even when the direction happens to be off-axis
     * relative to the camera.</li>
     * </ul>
     * The 8-way {@code Input} record the server sees is the nearest real key combination for the direction against
     * the yaw the server receives (see {@link #writeKeys}). Sprinting can only start with a forward component
     * ({@code ClientInput.hasForwardImpulse}: {@code y > 1e-5}); while the server-side yaw is locked the
     * {@code Ap3StrafeImpulseMixin} supplies that, so a walk sprints whatever the camera does.
     */
    private static void writeMove(LocalPlayer player, double wx, double wz, boolean sprint, boolean diagonalKey) {
        double h = Math.sqrt(wx * wx + wz * wz);
        if (h < 1e-4) {
            clearMovement();
            return;
        }
        double ux = wx / h;
        double uz = wz / h;
        double mag = 1.0;
        // The analog vector is ALWAYS camera-relative: LivingEntity.travel turns it back into the world through the
        // live yaw, so this is what makes the movement go the recorded way whatever the camera does.
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
        boolean locked = strafeLockedForHold();
        // The speed is the one the key combination the server sees really gets: a diagonal (W+A / W+D) maps to
        // 1.00, a straight key (W) to 0.98 - vanilla's own square mapping, reproduced for the rotated vector.
        double scale = diagonalKey ? 1.0 / VANILLA_INPUT_SCALE : 1.0 / unitSquare;
        scale *= mag;
        moveX = (float) (lft * scale);
        moveY = (float) (fwd * scale);
        driveX = ux;
        driveZ = uz;
        // The key record the SERVER sees is relative to the yaw the server receives: the camera yaw normally, the
        // strafe yaw while locked (W+A / W+D). Vanilla can only sprint with a forward component, so an unlocked
        // walk whose direction is behind the camera walks; a locked one sprints (Ap3StrafeImpulseMixin).
        boolean sprintOk = sprint && !player.isInWater() && (locked ? sprintMixinApplied || fwd > 0.05 : fwd > 0.05);
        writeKeys(locked ? serverYaw : player.getYRot(), sprintOk);
        driving = true;
    }

    /** The 8-way key record for the current drive direction against {@code yaw} (what the server is told). */
    private static void writeKeys(float yaw, boolean sprint) {
        double yr = Math.toRadians(yaw);
        double fwd = driveX * -Math.sin(yr) + driveZ * Math.cos(yr);
        double lft = driveX * Math.cos(yr) + driveZ * Math.sin(yr);
        wantForward = fwd > KEY_THRESHOLD;
        wantBackward = fwd < -KEY_THRESHOLD;
        wantLeft = lft > KEY_THRESHOLD;
        wantRight = lft < -KEY_THRESHOLD;
        wantSprint = sprint;
    }

    private static void clearMovement() {
        lastSneakSent = wantSneak;
        driving = false;
        moveX = 0f;
        moveY = 0f;
        wantForward = wantBackward = wantLeft = wantRight = wantSneak = wantSprint = false;
    }

    // ------------------------------------------------------------------------------------------- server-side yaw lock

    /**
     * Every client tick, AFTER the executor has decided this tick's movement ({@link Ap3Feature} calls it last,
     * whether or not the executor itself ran - a glide has to finish even after AP3 is turned off or the boss is
     * left). Three states:
     * <ol>
     * <li>a held walk is driving: lock (seed {@link #serverYaw} from the live yaw and, with 45 Degree Strafe on,
     *     pick the side - +45 (W+A) or -45 (W+D) - that is the shorter turn from where the server-side yaw is now),
     *     then step the server-side yaw toward the walk direction +-45 (toggle on) or the walk direction itself
     *     (toggle off) by a bounded wrapped delta, and re-derive the key record against it;</li>
     * <li>the hold ended (any other node, a stop, his hands): glide the server-side yaw back onto the live camera
     *     yaw the same way, chasing it if he keeps turning, and let go once within {@value #STRAFE_DONE} degrees;</li>
     * <li>no lock: nothing - the camera yaw goes out untouched.</li>
     * </ol>
     * Both mixins must have reported in (the sendPosition swap is what makes the key record coherent); the
     * no-mixin fallback path never locks. Rotation 360 rule: the only assignment to {@link #serverYaw} that is not
     * {@code serverYaw += delta} is the seed from {@code player.getYRot()}, itself the running value.
     */
    static void tickStrafe(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            releaseStrafeNow();
            return;
        }
        if (strafeLock && activeNode != null && activeNode.type.isAlign()) {
            if (Ap3Config.getInstance().getAlignMethod() == Ap3Config.AlignMethod.SENT_YAW) {
                // The Sent-Yaw Planner steers the sent yaw itself (driveDiscrete) - no glide, no release, until it ends.
                return;
            }
            // The other methods wait for the sent yaw to be the camera again: glide back faster (0.8 of what is left
            // a tick, still capped) so the wait is 2-3 ticks.
            strafeSmoothing = 0.8f;
        }
        boolean wanted = holdDir != null && driving && mixinApplied && rotationMixinApplied;
        boolean strafe45 = Ap3Config.getInstance().isStrafe45();
        if (wanted) {
            if (!strafeLock || strafeReturning) {
                if (!strafeLock) {
                    serverYaw = player.getYRot();
                    strafeSmoothing = 0.5f + (float) (Math.random() * 0.2);
                }
                strafeLock = true;
                strafeReturning = false;
                strafeHold = null;
                lookHeld = false; // a walk's explicit yaw supersedes a finished LOOK's client-only hold
                LOGGER.info("[AP3] Server-side yaw: locking from yaw {} ({})",
                        String.format(Locale.US, "%.1f", serverYaw), strafe45 ? "45 degree strafe" : "straight");
            }
            serverYawO = serverYaw;
            float walkYaw = (float) Math.toDegrees(Math.atan2(-holdDir.x, holdDir.z));
            if (strafeHold != holdDir) {
                strafeHold = holdDir;
                float toA = Math.abs(Mth.wrapDegrees(walkYaw + STRAFE_ANGLE - serverYaw));
                float toD = Math.abs(Mth.wrapDegrees(walkYaw - STRAFE_ANGLE - serverYaw));
                strafeSide = toA <= toD ? 1 : -1;
            }
            // ON: the strafe angle, so the record derived below is W plus A or D once there ("always pressing w and
            // some other movement key"). OFF: the walk direction itself, so the record is W alone.
            stepServerYaw(walkYaw + (strafe45 ? strafeSide * STRAFE_ANGLE : 0f));
            // The movement AND the key record have to be the keys at the yaw that goes out with them: rewrite the
            // hold against the stepped value (applyHold ran earlier this tick against last tick's yaw).
            driving = false;
            applyHold(player);
            return;
        }
        if (!strafeLock) {
            return;
        }
        if (!strafeReturning) {
            strafeReturning = true;
            strafeHold = null;
            LOGGER.info("[AP3] Server-side yaw: returning to the camera yaw");
        }
        serverYawO = serverYaw;
        float cameraYaw = player.getYRot();
        stepServerYaw(cameraYaw);
        if (Math.abs(Mth.wrapDegrees(cameraYaw - serverYaw)) <= STRAFE_DONE) {
            // Within half a degree of the camera: the next packet carries the camera yaw itself, a step no bigger
            // than that. Not a snap - and not an assignment of a wrapped value to anything.
            releaseStrafeNow();
        }
    }

    /** One bounded step of the running server-side yaw toward {@code target} (the target is DATA; only its wrapped
     *  delta from the running value is ever used). */
    private static void stepServerYaw(float target) {
        float delta = Mth.wrapDegrees(target - serverYaw);
        float step = Math.abs(delta) <= STRAFE_DONE ? delta
                : Mth.clamp(delta * strafeSmoothing, -STRAFE_MAX_STEP, STRAFE_MAX_STEP);
        serverYaw += step;
    }

    /** Drops the lock at once: the next packet carries the live camera yaw. Only for the moments the server has
     *  just set our rotation itself (a teleport) or there is no player to speak of. */
    private static void releaseStrafeNow() {
        if (strafeLock) {
            LOGGER.info("[AP3] Server-side yaw: released");
        }
        strafeLock = false;
        strafeReturning = false;
        strafeHold = null;
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
