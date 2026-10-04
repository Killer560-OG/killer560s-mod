package com.killer560.hub.autoroutes;

import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Routes playback. CHEAT BUILD ONLY - only ever started by {@link AutoRoutesFeature} behind
 * {@link AutoRoutesConfig#isEnabled()}.
 * <p>
 * <b>Movement</b> seeks along the recorded {@link RoutePath} ({@link RoutePath#seek}): every tick it picks the
 * furthest recorded sample the player has reached, aims a little further along the path ("carrot") and presses
 * whichever of forward/back/left/right actually carries the player there relative to the live yaw, replaying the
 * recorded sneak/sprint and jumping where the recording jumped or the path climbs. The path SHAPE is the
 * recording's; the TIMING self-corrects, which is the whole lag defence: behind after a lag spike means "not there
 * yet, keep going", rubber-banded back means "re-reach the same sample". Keys go through the
 * {@code mixin/AutoRoutesInputMixin} rewrite of the {@code Input} record (physical keys stay readable, so "the
 * player pressed WASD, stop" works), or the key mappings when the mixin config isn't loaded - exactly
 * {@code pathfinding/AutoWalker}.
 * <p>
 * <b>Rotation</b> is only ever a wrapped delta on the running yaw through {@link RouteRotation} (Rotation 360 rule).
 * Legit mode turns the camera for every discrete action; obvious mode sends the rotated use packet without moving
 * the camera (the Interactive Map's own {@code ClearExecutor.doInteract} technique) - killer560: "legit or obvious -
 * in legit mode it rotates for every etherwarp."
 * <p>
 * <b>Discrete actions wait for real confirmation</b> (never a timer alone): an etherwarp / teleporting item is done
 * when the player actually arrives at the recorded landing, a dungeon-breaker node when its blocks are actually air,
 * a superboom when the block it hit changed. Anything that cannot be confirmed within a generous timeout, any
 * drift off the recorded path, the player taking the movement keys or the camera back, and any screen opening
 * <b>stops the route with a chat message</b> - "a stuck bot in a real run is worse than a stopped one". Mouse CLICKS
 * never stop it (killer560, 2026-10-04: "Auto routes should not stop if I click") - he clicks chests and levers
 * while a route runs.
 * <p>
 * <b>The {@code await} modifier</b> (any node may carry one - see {@link RouteNode#awaitEnabled}, and
 * {@link #tickAwait}) gates a node's own action behind "wait for N secrets" or "wait a fixed delay" FIRST -
 * killer560's old {@code AWAIT} node, folded onto whichever node needs it instead of taking a slot of its own.
 * <p>
 * <b>Timing</b> (killer560, 2026-10-04: "Each action should be done in 1 tick unless it is something like waiting on a
 * bat"). The executor ticks on {@code START_CLIENT_TICK}, before the player's own tick, and a node's action runs on
 * the tick it fires. Everything it needs is asked for at once: the hotbar slot (held-item packet, sent now), the
 * sneak (installed by the input mixin into THIS tick's {@code ServerboundPlayerInputPacket}) and the aim. The only
 * waits left are real ones: an etherwarp that needs a fresh sneak uses on the next tick, so the stream reads
 * held-item, input(shift), move, use - the server handles them in that order, sneak before use; a legit-mode camera
 * turn; an await gate; and the server's own answer (the landing, the broken block).
 */
public final class RouteExecutor {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

    /** A sample counts as reached within this horizontal distance (tight: the path shape is the point)... */
    private static final double REACH_XZ = 0.45;
    /** ...and this vertical tolerance (loose: a jump arc's samples are reached from the ground). */
    private static final double REACH_Y = 1.1;
    private static final int SEEK_WINDOW = 60;
    private static final double LOOKAHEAD = 0.7;
    private static final int SNEAK_TIMEOUT = 20;
    private static final int AIM_TIMEOUT = 80;
    private static final int LANDING_TIMEOUT = 60;
    private static final int BOOM_TIMEOUT = 40;
    private static final int BREAKER_TIMEOUT = 40;
    private static final double LANDING_TOLERANCE = 2.0;
    /**
     * Measured block reach, squared.
     *
     * <p>Was 30.0 - 5.48 blocks, and measured with {@code distToCenterSqr}, so up to half a block further
     * again. The server refuses a block interaction past 4.5 to the BOX, so this was breaking blocks it had
     * no business reaching.
     */
    private static final double BREAKER_RANGE_SQ = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH * com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
    private static final String[] BOOM_IDS = {"INFINITE_SUPERBOOM_TNT", "SUPERBOOM_TNT"};
    private static final String BREAKER_ID = "DUNGEONBREAKER";
    private static final Pattern CHARGES = Pattern.compile("Charges: (\\d+)/(\\d+)");

    /** PREP runs on the tick a node fires and asks for everything at once (hotbar slot, sneak, aim); AIM waits only
     *  for what has a real wait (the server having the sneak, a legit-mode camera turn); DO / CONFIRM as named. */
    private enum Step { PREP, AIM, DO, CONFIRM }

    // ---- session ----
    private static boolean running;
    private static Route route;
    private static RouteCoords.Frame frame;
    private static List<RouteNode> ordered = new ArrayList<>();
    private static int nextNode;
    private static int cursor;
    private static int noProgressTicks;
    private static double bestTargetDistance;
    private static int cameraGraceTicks;
    private static String stopReason;
    private static Object lastLevel;

    // ---- action ----
    private static RouteNode activeNode;
    private static Step step;
    private static int stepTicks;
    private static int settleTicks;
    private static Vec3 actionOrigin;
    private static boolean forceSneak;
    private static boolean unsneakOverride;
    /** Executor ticks since the active node fired: 0 on the firing tick itself. The "[AutoRoutes] Node #n ... acted"
     *  log line reports it, so a log shows directly how long every action took. */
    private static int actionAge;
    /** {@link #actionAge} when the node's await gate opened (0 without one) - the action's own time starts there. */
    private static int awaitDoneAge;
    /** This node had to change the hotbar slot (for the log). */
    private static boolean nodeSwapped;
    /** {@link #actionAge} at which the server was known to have the etherwarp's sneak, or -1. */
    private static int sneakReadyAge;
    /** Set by the position-packet hook ({@code LiveMapPacketListenerMixin}) when the server moves the player, cleared
     *  when a teleport's use is sent - Hypixel and the sim both teleport with that packet, so it is the landing check's
     *  proof that a teleport really happened, however short. */
    private static volatile boolean teleportPacketSeen;
    private static List<BlockPos> breakerQueue = new ArrayList<>();
    private static final Set<BlockPos> breakerSent = new HashSet<>();
    private static BlockPos boomTarget;
    private static final Map<BlockPos, BlockState> boomBefore = new HashMap<>();

    // ---- await ----
    private static int secretsFound = -1;
    private static int awaitBaselineSecrets;
    private static int awaitBatSecrets;
    private static long awaitStartMs;
    private static final Set<Integer> countedBats = new HashSet<>();
    /** True once the CURRENT node's {@code awaitEnabled} gate (if it has one) has been satisfied - the node's
     *  own type-specific action (etherwarp, use, boom, ...) only starts once this is true. Set in
     *  {@link #beginAction}, read/advanced in {@link #tickAction}. AWAIT stopped being its own node type
     *  (2026-09-2x) - {@link #tickAwait} is now this pre-action gate for ANY node, not a case of its own. */
    private static boolean awaitPhaseDone = true;

    // ---- input ----
    private static boolean mixinApplied;
    private static boolean fallbackKeysHeld;
    private static boolean warnedFallback;
    private static boolean warnedNoKeyAccessor;
    private static boolean wantForward;
    private static boolean wantBackward;
    private static boolean wantLeft;
    private static boolean wantRight;
    private static boolean wantJump;
    private static boolean wantSneak;
    private static boolean wantSprint;
    /**
     * True from the moment the route takes the input (it starts, or a node begins) until the player's own movement
     * keys are next seen all up. Keys still held from before that moment are the walk that carried him onto the
     * node, not a request to take over, so they are overridden instead of stopping the route; only a press that
     * comes AFTER a release counts as "you moved". Without this, walking onto a start node with W held stopped a
     * driven route on its first tick, and on a path-less route the held key kept the input mixin from ever
     * installing the etherwarp's sneak, so "/ar add ew start" never warped (2026-10-04).
     */
    private static boolean handsLatched;
    /**
     * Arrival re-fire (killer560, 2026-10-04: "if an ar goes back onto another ar pad, so for instance two ew nodes
     * point at each other, have them retoggle again and again"). Set when a teleporting node (an etherwarp, or a use
     * node with a recorded landing) finishes on a landing the SERVER confirmed with its position packet; the next
     * walk step of a path-less route consumes it, and if the player is now inside a node - any node of the route,
     * the one he warped from included, in any order - that node fires. Two etherwarps aimed at each other therefore
     * ping-pong until something stops the route. Never the node just performed (a warp onto its own ring would
     * warp in place forever), and never same-tick: each fire still runs its whole sneak / swap / aim / use / landing
     * cycle, and {@code settleTicks} sits between landing and the next fire.
     */
    private static RouteNode landedFrom;
    /** True once a landing has sent the route BACK to an earlier node (a loop such as a ping-pong). From then a FRESH
     *  movement key press stops the route ("you moved") on a path-less route too - otherwise a press there is only
     *  overridden during an action, which would leave a loop with no way out but the mouse or {@code /ar stop}. */
    private static boolean arrivalChain;
    /** A path-less route's WALK node: the real-world yaw it holds a sprint along until the next node fires, or null. */
    private static Float walkHoldYaw;
    private static Vec3 walkHoldLastPos;
    private static int walkHoldStallTicks;
    /** A held walk that has not moved for this many ticks has hit something and lets go. */
    private static final int WALK_HOLD_STALL_TICKS = 10;

    // ---- stack ----
    /**
     * Every node on one tile fires, as one unit (killer560, 2026-10-04): the nodes still to run in the stack being
     * performed, in firing order ({@link Route#stackOf}). Each waits for the one before it to finish and for
     * {@code settleTicks} after it, exactly as consecutive nodes always have. Any {@link #stop} - a node failing,
     * the player taking over, {@code /ar stop} - drops whatever is left.
     */
    private static final java.util.ArrayDeque<RouteNode> stackQueue = new java.util.ArrayDeque<>();
    /** The node whose tile the running stack is: its place in the route is the stack's place, so the route moves on
     *  from it once the stack is done. */
    private static RouteNode stackTrigger;
    /** Nodes performed since this run started. The route's walk-on progression skips them, so a node that already
     *  went off as part of an earlier node's stack is not fired a second time when the order reaches its own number.
     *  A landing re-fire ignores this - a ping-pong is meant to fire the same nodes again and again. */
    private static final Set<RouteNode> firedInRun = new HashSet<>();

    private RouteExecutor() {
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isRunning() {
        return running;
    }

    /** Stops playback and tells the user why (chat, when chat feedback is on). Safe to call when idle. */
    /** {@code /ar stop} and its key - one of the user-stop reasons below. */
    private static final String STOPPED_BY_COMMAND = "you stopped it";
    /** Reasons that mean "the player took over" rather than "the route ended or the world changed". After
     *  one of these the feature must not re-arm until they have walked clear of every node (2026-09-16
     *  review: tapping W stopped a route and releasing W restarted it, because the player was still
     *  standing inside the ring the bot had just walked them through). */
    private static final java.util.Set<String> USER_STOP_REASONS =
            java.util.Set.of("you moved", "you moved the camera", STOPPED_BY_COMMAND);

    private static boolean stoppedByUser;
    /** Set when a route runs to its end. The player is then standing IN the last node, which with
     *  start-only OFF is a different node from the one it armed on - so it armed again and re-ran that
     *  node's use/boom/command a second time (2026-09-16 review). */
    private static boolean justFinished;

    /** True when the last stop was the player taking over. Cleared by {@code clearStoppedByUser}. */
    public static boolean wasStoppedByUser() {
        return stoppedByUser;
    }

    /** True while the player is still standing in the node a finished route ended on. */
    public static boolean justFinished() {
        return justFinished;
    }

    public static void clearJustFinished() {
        justFinished = false;
    }

    public static void clearStoppedByUser() {
        stoppedByUser = false;
    }

    /** {@code /ar stop} (and its key): stops the route as the player taking over, so the node he is standing in stays
     *  latched until he steps off it. @return false when nothing was running. */
    public static boolean stopByUser() {
        if (!running) {
            return false;
        }
        stop(STOPPED_BY_COMMAND);
        return true;
    }

    public static void stop(String reason) {
        boolean wasRunning = running;
        if (wasRunning && reason != null && USER_STOP_REASONS.contains(reason)) {
            stoppedByUser = true;
        }
        running = false;
        stopReason = reason;
        if (wasRunning && !stackQueue.isEmpty()) {
            LOGGER.info("[AutoRoutes] {} stacked node(s) not run: {}", stackQueue.size(), describeStack(stackQueue));
        }
        stackQueue.clear();
        stackTrigger = null;
        activeNode = null;
        step = null;
        landedFrom = null;
        arrivalChain = false;
        forceSneak = false;
        unsneakOverride = false;
        endWalkHold();
        handsLatched = false;
        releaseKeys();
        RouteRotation.clear();
        if (wasRunning) {
            LOGGER.info("[AutoRoutes] Stopped: {}", reason);
            if (reason != null && AutoRoutesConfig.getInstance().isChatFeedback()) {
                AutoRoutesFeature.chat(com.killer560.hub.util.ModChat.bad("Stopped"),
                        com.killer560.hub.util.ModChat.dim(" - " + reason));
            }
        }
    }

    public static String stopReason() {
        return stopReason;
    }

    /** The node currently being performed / walked toward, for the renderer's active highlight. */
    static RouteNode activeNode() {
        if (!running) {
            return null;
        }
        if (activeNode != null) {
            return activeNode;
        }
        return nextNode < ordered.size() ? ordered.get(nextNode) : null;
    }

    static Route runningRoute() {
        return running ? route : null;
    }

    // ------------------------------------------------------------------------------------------- session

    /** Starts playback of {@code route} from {@code startNode} (a node of that route). Only the feature's arming
     *  logic calls this, after every interlock in {@link AutoRoutesFeature} has passed. */
    static boolean start(Route r, RouteCoords.Frame f, RouteNode startNode) {
        justFinished = false;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || r == null || f == null || startNode == null) {
            return false;
        }
        if (running) {
            stop("restarted");
        }
        route = r;
        frame = f;
        ordered = r.nodesInPathOrder();
        nextNode = Math.max(0, ordered.indexOf(startNode));
        cursor = Math.max(0, Math.min(Math.max(0, r.path().size() - 1), startNode.pathIndex));
        noProgressTicks = 0;
        bestTargetDistance = Double.MAX_VALUE;
        cameraGraceTicks = 2;
        stopReason = null;
        lastLevel = client.level;
        activeNode = null;
        step = null;
        settleTicks = 0;
        forceSneak = false;
        unsneakOverride = false;
        secretsFound = -1;
        awaitPhaseDone = true;
        landedFrom = null;
        arrivalChain = false;
        stackQueue.clear();
        stackTrigger = null;
        firedInRun.clear();
        endWalkHold();
        RouteRotation.clear();
        running = true;
        handsLatched = true;
        LOGGER.info("[AutoRoutes] Started \"{}\" from node {} ({}) at sample {} of {}", r.roomName(),
                r.indexOf(startNode), startNode.type, cursor, r.path().size());
        if (AutoRoutesConfig.getInstance().isChatFeedback()) {
            AutoRoutesFeature.chat(com.killer560.hub.util.ModChat.good("Started"),
                    com.killer560.hub.util.ModChat.dim(" - " + r.roomName()));
        }
        // The node he stepped onto goes off NOW (killer560, 2026-10-04: "If I do /ar add ew start, that means that
        // the second I hit that node it should go off"). This used to wait for the next tick's walk step to find
        // him inside the ring again, and on a path-less route a player still walking could be out of it by then.
        // Every node stacked on the start node's tile goes with it.
        beginStack(startNode);
        // tick() does this for every later tick; the firing tick runs from the arming code instead.
        applyFallbackKeys(client);
        return true;
    }

    private static void complete() {
        justFinished = true;
        LOGGER.info("[AutoRoutes] Route \"{}\" complete", route.roomName());
        boolean feedback = AutoRoutesConfig.getInstance().isChatFeedback();
        stop(null);
        if (feedback) {
            AutoRoutesFeature.chat(com.killer560.hub.util.ModChat.good("Route complete"));
        }
    }

    // ------------------------------------------------------------------------------------------- input hooks

    /** The input mixin asks this before touching anything. */
    public static boolean isSessionActive() {
        return running;
    }

    /** From the client's position-packet handler, on the client thread. */
    public static void onServerPositionPacket() {
        teleportPacketSeen = true;
    }

    public static void onMixinApplied() {
        mixinApplied = true;
    }

    /**
     * Without the input mixin the executor drives by holding the key MAPPINGS, which means
     * {@code KeyMapping.isDown()} reports the bot's own state and the player's real keypresses become
     * invisible - so "press W to stop" silently stopped working (2026-09-16 review). Poll the physical keys
     * through GLFW instead, the same way every raw-polled keybind in this mod already does.
     * <p>
     * A route that cannot be stopped by the player is the worst failure this feature has, so this also logs
     * once when the fallback is first used: the mixin config is {@code required:false} by design, and
     * without a line in the log a non-applying mixin would be completely invisible.
     */
    private static boolean physicalMovementKeysInFallback(Minecraft client) {
        if (mixinApplied) {
            return false;
        }
        if (!warnedFallback) {
            warnedFallback = true;
            LOGGER.warn("[AutoRoutes] Input mixin did not apply - driving with key mappings instead. "
                    + "Movement keys are polled directly so you can still stop a route.");
        }
        var options = client.options;
        var window = client.getWindow();
        return rawDown(window, options.keyUp) || rawDown(window, options.keyDown)
                || rawDown(window, options.keyLeft) || rawDown(window, options.keyRight)
                || rawDown(window, options.keyJump);
    }

    /** The physical state of whatever key a mapping is bound to, ignoring the mapping's own down-flag
     *  (which the fallback path is busy forcing). Degrades to "not pressed" rather than throwing if the
     *  accessor mixin didn't apply either - the camera latch, clicks, opening a screen and the tab's Stop
     *  Route button all still stop a route without it. */
    private static boolean rawDown(com.mojang.blaze3d.platform.Window window,
                                   net.minecraft.client.KeyMapping mapping) {
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key =
                    ((com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor) (Object) mapping)
                            .killer560smod$getKey();
            if (key == null || key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
                return false;
            }
            return com.killer560.hub.util.KeyUtil.isKeyDown(window, key.getValue());
        } catch (Throwable t) {
            if (!warnedNoKeyAccessor) {
                warnedNoKeyAccessor = true;
                LOGGER.warn("[AutoRoutes] Key accessor unavailable ({}) - movement keys cannot stop a route on "
                        + "the fallback path. Move the mouse or open a screen.", t.toString());
            }
            return false;
        }
    }

    /**
     * From the input mixin every tick while a route runs (and from {@link #tick} on the fallback path):
     * {@code userKeys} is whether the player is holding a movement key himself this tick.
     * <p>
     * Keys held since the route took the input ({@link #handsLatched}) are overridden while the route owns the
     * input - a driven route, a node's action, or a held walk - and left alone otherwise, so he can keep walking
     * between the nodes of a path-less route. A FRESH press stops a driven route ("you moved"), hands a held walk
     * back to him, and is overridden for the few ticks a path-less node's own action needs (the etherwarp's sneak).
     *
     * @return true when the route's input replaces his this tick
     */
    public static boolean onInputTick(boolean userKeys) {
        if (!running) {
            return false;
        }
        boolean driven = route != null && !route.path().isEmpty();
        if (!userKeys) {
            handsLatched = false;
            return isDriving();
        }
        // Between two nodes of one stack the route still owns the input: the stack is one unit.
        boolean acting = activeNode != null || !stackQueue.isEmpty();
        if (handsLatched) {
            return driven || acting || walkHoldYaw != null;
        }
        if (arrivalChain) {
            stop("you moved");
            return false;
        }
        if (driven) {
            stop("you moved");
            return false;
        }
        if (walkHoldYaw != null) {
            endWalkHold();
            RouteRotation.clear();
            return false;
        }
        return acting;
    }

    /** Driving the player this tick (the mixin asks this). Sneak alone (an etherwarp prep) still counts. */
    public static boolean isDriving() {
        return running && (wantForward || wantBackward || wantLeft || wantRight || wantJump || wantSneak || wantSprint);
    }

    /** The {@code Input} record the mixin installs for this tick. */
    public static Input drivenInput() {
        return new Input(wantForward, wantBackward, wantLeft, wantRight, wantJump, wantSneak, wantSprint);
    }

    public static float moveVectorX() {
        return (wantLeft ? 1f : 0f) - (wantRight ? 1f : 0f);
    }

    public static float moveVectorY() {
        return (wantForward ? 1f : 0f) - (wantBackward ? 1f : 0f);
    }

    /** From the feature's action-bar hook: the room's "x/y Secrets" count. */
    static void onSecretsCount(int found) {
        secretsFound = found;
    }

    /** Per render frame (from the feature's {@code LevelRenderEvents} hook): the smooth camera step. Runs every
     *  frame, not every tick, for the same reason SimonSays' Rotate Mode does - 20Hz looks stepped. */
    static void tickFrame() {
        if (running) {
            RouteRotation.frame();
        }
    }

    // ------------------------------------------------------------------------------------------- ticking

    /** Call at the START of every client tick while {@link #isRunning()} (before the player's tick sends this tick's
     *  input and movement packets); the feature has already applied its own interlocks (map open, Blood Rush, room
     *  known) before this runs. */
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
        if (McCompat.screen(client) != null) {
            stop("a screen opened");
            return;
        }
        // Without the input mixin the same hand-over rules run off the physical keys (onInputTick). The fallback
        // cannot hide held keys from the game, but applyFallbackKeys re-asserts the mappings every tick.
        if (!mixinApplied) {
            onInputTick(physicalMovementKeysInFallback(client));
            if (!running) {
                return;
            }
        }
        if (cameraGraceTicks > 0) {
            cameraGraceTicks--;
        } else if (RouteRotation.userMovedCamera(player)) {
            if (!route.path().isEmpty() || activeNode != null || !stackQueue.isEmpty()) {
                stop("you moved the camera");
                return;
            }
            // A path-less route between nodes: only a held walk was turning the camera - hand it back.
            endWalkHold();
            RouteRotation.clear();
        }
        // No click check (killer560, 2026-10-04: "Auto routes should not stop if I click"). A click used to stop a
        // driven route outright and skip an await on any route, so clicking the chest an await:2 was waiting on
        // fired the node early.
        RouteCoords.Frame now = RouteCoords.Frame.current();
        if (now == null || !now.sameRoom(frame)) {
            stop("left the room");
            return;
        }
        if (settleTicks > 0) {
            settleTicks--;
            clearMovement();
            applyFallbackKeys(client);
            return;
        }
        try {
            if (activeNode != null) {
                clearMovement();
                tickAction(client, player);
            } else if (!stackQueue.isEmpty()) {
                // The next node of the stack, now the one before it has finished and settled.
                clearMovement();
                beginAction(stackQueue.poll());
            } else {
                tickWalk(client, player);
            }
        } catch (Exception e) {
            LOGGER.error("[AutoRoutes] Playback error", e);
            stop("internal error (see log)");
            return;
        }
        applyFallbackKeys(client);
    }

    private static void tickWalk(Minecraft client, LocalPlayer player) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        RoutePath path = route.path();
        Vec3 pos = player.position();
        Vec3 rel = RouteCoords.toRelative(frame, pos);

        if (path.isEmpty()) {
            // No recorded movement: QUOI-style, the next node fires when the player walks into its ring.
            clearMovement();
            if (walkHoldYaw != null) {
                tickWalkHold(player);
            }
            if (landedFrom != null) {
                RouteNode from = landedFrom;
                landedFrom = null;
                RouteNode arrived = nodeLandedIn(player, cfg, from);
                if (arrived != null) {
                    int at = ordered.indexOf(arrived);
                    if (at < nextNode) {
                        // Sent BACK to an earlier node (or the one it came from): a loop. A plain landing on the
                        // next node is the ordinary route and leaves his keys as they were.
                        arrivalChain = true;
                    }
                    nextNode = at;
                    LOGGER.info("[AutoRoutes] Landed in node #{} ({}) from #{} - firing it", route.indexOf(arrived) + 1,
                            arrived.type, route.indexOf(from) + 1);
                    beginStack(arrived);
                    return;
                }
            }
            if (nextNode >= ordered.size()) {
                if (walkHoldYaw == null) {
                    complete(); // a walk node at the end keeps going until it runs into something
                }
                return;
            }
            RouteNode node = ordered.get(nextNode);
            if (node.contains(RouteCoords.toReal(frame, node.relativePos()), cfg.getHeight(), player.getBoundingBox())) {
                beginStack(node);
            }
            return;
        }

        int reached = path.seek(cursor, SEEK_WINDOW, rel.x, rel.y, rel.z, REACH_XZ, REACH_Y);
        if (reached > cursor) {
            cursor = reached;
            noProgressTicks = 0;
            bestTargetDistance = Double.MAX_VALUE;
        } else if (path.get(cursor).distance(rel.x, rel.y, rel.z) > 1.0) {
            // Rubber-banded backwards onto an earlier stretch of the path: resume from there instead of cutting
            // straight toward a carrot that may now be through a wall (the spec's "re-converges on the same sample").
            int back = path.nearest(Math.max(0, cursor - SEEK_WINDOW), cursor, rel.x, rel.y, rel.z);
            if (back < cursor && path.reached(back, rel.x, rel.y, rel.z, REACH_XZ + 0.2, REACH_Y)) {
                cursor = back;
                noProgressTicks = 0;
                bestTargetDistance = Double.MAX_VALUE;
            }
        }
        if (nextNode < ordered.size() && cursor >= ordered.get(nextNode).pathIndex) {
            clearMovement();
            beginStack(ordered.get(nextNode));
            return;
        }
        if (cursor >= path.size() - 1) {
            if (nextNode >= ordered.size()) {
                complete();
            } else {
                // Nodes anchored past the end of the path (hand-edited file) - run them where we stand.
                clearMovement();
                beginStack(ordered.get(nextNode));
            }
            return;
        }

        RoutePath.Sample at = path.get(cursor);
        double drift = at.distance(rel.x, rel.y, rel.z);
        if (drift > cfg.getMaxDriftDistance()) {
            stop(String.format(Locale.US, "%.1f blocks off the recorded path", drift));
            return;
        }
        int targetIdx = path.lookahead(cursor, LOOKAHEAD);
        RoutePath.Sample target = path.get(targetIdx);
        double targetDistance = target.distance(rel.x, rel.y, rel.z);
        if (targetDistance < bestTargetDistance - 0.05) {
            bestTargetDistance = targetDistance;
            noProgressTicks = 0;
        } else if (++noProgressTicks > cfg.getReachTimeoutTicks()) {
            stop("couldn't reach the next point in time");
            return;
        }
        steer(player, at, target);
        RouteRotation.follow(RouteCoords.toRealYaw(frame, target.yaw()), target.pitch());
    }

    /** Presses whichever keys move the player toward {@code target} given the LIVE yaw (8-way), replaying the
     *  recorded sneak/sprint and jumping where the recording jumped or the path climbs. */
    private static void steer(LocalPlayer player, RoutePath.Sample at, RoutePath.Sample target) {
        Vec3 tgt = RouteCoords.toReal(frame, target.x(), target.y(), target.z());
        Vec3 pos = player.position();
        double dx = tgt.x - pos.x;
        double dz = tgt.z - pos.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        wantForward = wantBackward = wantLeft = wantRight = false;
        if (h > 0.08) {
            double yr = Math.toRadians(player.getYRot());
            double fx = -Math.sin(yr);
            double fz = Math.cos(yr);
            double lx = Math.cos(yr);
            double lz = Math.sin(yr);
            double fwd = (dx * fx + dz * fz) / h;
            double lft = (dx * lx + dz * lz) / h;
            wantForward = fwd > 0.38;
            wantBackward = fwd < -0.38;
            wantLeft = lft > 0.38;
            wantRight = lft < -0.38;
        }
        boolean climb = tgt.y - pos.y > 0.6 && h < 1.6;
        // horizontalCollision alone is deliberately NOT a jump trigger: brushing a wall while walking past
        // it would jump every single ground tick, which the recording never did (2026-09-16 review). Only
        // jump where the recording jumped, or where the next sample is genuinely above us.
        wantJump = player.onGround() && (target.jump() || at.jump() || climb
                || (player.horizontalCollision && climb))
                || (player.isInWater() && climb);
        wantSneak = forceSneak || (!unsneakOverride && (at.sneak() || target.sneak()));
        wantSprint = !wantSneak && wantForward && (at.sprint() || target.sprint()) && !player.isInWater();
    }

    /**
     * A path-less route's WALK node: sprint along its recorded direction (killer560, 2026-10-04: "the walk should be
     * a sprint as well even though it is titled walk"). Discrete keys picked against the live yaw exactly like
     * {@link #steer}, with the camera eased onto the walk direction so the pick settles on W and the sprint holds.
     * The hold ends when the next node fires, he takes the keys or the mouse back, or he stops moving.
     */
    private static void tickWalkHold(LocalPlayer player) {
        Vec3 pos = player.position();
        if (walkHoldLastPos != null) {
            double mx = pos.x - walkHoldLastPos.x;
            double mz = pos.z - walkHoldLastPos.z;
            walkHoldStallTicks = mx * mx + mz * mz < 0.03 * 0.03 ? walkHoldStallTicks + 1 : 0;
        }
        walkHoldLastPos = pos;
        if (walkHoldStallTicks > WALK_HOLD_STALL_TICKS) {
            endWalkHold();
            RouteRotation.clear();
            clearMovement();
            return;
        }
        double r = Math.toRadians(walkHoldYaw);
        double dx = -Math.sin(r);
        double dz = Math.cos(r);
        double yr = Math.toRadians(player.getYRot());
        double fwd = dx * -Math.sin(yr) + dz * Math.cos(yr);
        double lft = dx * Math.cos(yr) + dz * Math.sin(yr);
        wantForward = fwd > 0.38;
        wantBackward = fwd < -0.38;
        wantLeft = lft > 0.38;
        wantRight = lft < -0.38;
        wantJump = false;
        wantSneak = forceSneak;
        wantSprint = !wantSneak && wantForward && !player.isInWater();
        RouteRotation.follow(walkHoldYaw, player.getXRot());
    }

    /** The node a route-made teleport landed the player in: the next node in order if it is that one, else the first
     *  in path order he is inside - never {@code from}, the node that warped him, nor anything stacked on its tile
     *  (a warp onto its own tile would otherwise fire that stack in place forever). */
    private static RouteNode nodeLandedIn(LocalPlayer player, AutoRoutesConfig cfg, RouteNode from) {
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        if (nextNode < ordered.size()) {
            RouteNode next = ordered.get(nextNode);
            if (next != from && !next.sameTile(from) && next.contains(RouteCoords.toReal(frame, next.relativePos()), cfg.getHeight(), box)) {
                return next;
            }
        }
        for (RouteNode node : ordered) {
            if (node != from && !node.sameTile(from) && node.contains(RouteCoords.toReal(frame, node.relativePos()), cfg.getHeight(), box)) {
                return node;
            }
        }
        return null;
    }

    private static void endWalkHold() {
        walkHoldYaw = null;
        walkHoldLastPos = null;
        walkHoldStallTicks = 0;
    }

    private static void clearMovement() {
        wantForward = wantBackward = wantLeft = wantRight = wantJump = wantSprint = false;
        wantSneak = forceSneak;
    }

    /** Without the input mixin (config not loaded) hold the key mappings instead, the way AutoWalker does. */
    private static void applyFallbackKeys(Minecraft client) {
        if (mixinApplied) {
            return;
        }
        client.options.keyUp.setDown(wantForward);
        client.options.keyDown.setDown(wantBackward);
        client.options.keyLeft.setDown(wantLeft);
        client.options.keyRight.setDown(wantRight);
        client.options.keyJump.setDown(wantJump);
        client.options.keyShift.setDown(wantSneak);
        client.options.keySprint.setDown(wantSprint);
        fallbackKeysHeld = true;
    }

    private static void releaseKeys() {
        wantForward = wantBackward = wantLeft = wantRight = wantJump = wantSneak = wantSprint = false;
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

    // ------------------------------------------------------------------------------------------- actions

    /**
     * Fires {@code trigger} and every node stacked on its tile, in {@link Route#stackOf} order. The first node begins
     * now; {@link #tick} begins each later one once the one before it has finished and settled, and
     * {@link #finishAction} moves the route on from the trigger's place once the last is done.
     */
    private static void beginStack(RouteNode trigger) {
        List<RouteNode> stack = route.stackOf(trigger);
        stackQueue.clear();
        stackQueue.addAll(stack);
        stackTrigger = trigger;
        // The landing re-fire is armed by the LAST teleport of a stack, so it is cleared once here, not per node.
        landedFrom = null;
        if (stack.size() > 1) {
            LOGGER.info("[AutoRoutes] Stack of {} on node #{}'s tile - firing in order: {}", stack.size(),
                    route.indexOf(trigger) + 1, describeStack(stack));
        }
        beginAction(stackQueue.poll());
    }

    private static String describeStack(java.util.Collection<RouteNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (RouteNode n : nodes) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('#').append(route == null ? "?" : String.valueOf(route.indexOf(n) + 1)).append(' ')
                    .append(n.type).append(n.start ? " (start)" : "");
        }
        return sb.toString();
    }

    private static void beginAction(RouteNode node) {
        LocalPlayer self = Minecraft.getInstance().player;
        if (node.type == RouteNode.Type.ETHERWARP) {
            Vec3 at = RouteCoords.toReal(frame, node.relativePos());
            LOGGER.info("[AutoRoutes] Node #{} ETHERWARP begins: player {} node {} landing {} (room {}, sim={}, {} mode)",
                    route.indexOf(node) + 1, fmt(self.position()), fmt(at),
                    node.hasLanding ? fmt(RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ))
                            : "not recorded",
                    frame.roomName(), com.killer560.hub.roomsim.SimState.canAct(Minecraft.getInstance()),
                    AutoRoutesConfig.getInstance().isLegitMode() ? "legit" : "obvious");
        }
        activeNode = node;
        step = Step.PREP;
        stepTicks = 0;
        actionAge = -1;
        awaitDoneAge = 0;
        nodeSwapped = false;
        sneakReadyAge = -1;
        firedInRun.add(node);
        actionOrigin = Minecraft.getInstance().player.position();
        // Any node firing ends a held walk ("keep me walking until I hit a different node", AP3's rule), and the
        // node owns the input from here: keys he is already holding are overridden, not read as a takeover.
        endWalkHold();
        clearMovement();
        handsLatched = true;
        // The await modifier (if this node has one) runs FIRST, as its own PREP/CONFIRM cycle through
        // tickAwait - see tickAction. Nothing else about the node starts until that gate opens.
        // A legacy standalone AWAIT node (an unmigrated file) is nothing but this wait.
        awaitPhaseDone = !node.awaitEnabled && node.type != RouteNode.Type.AWAIT;
        breakerQueue = new ArrayList<>();
        breakerSent.clear();
        boomTarget = null;
        boomBefore.clear();
        // The node acts NOW, on the tick it fired - not on the next tick's pass through tick(). Waiting for that pass
        // was one of the dead ticks in his 2026-10-04 log (an etherwarp took 3-4 ticks from "begins" to the use).
        Minecraft client = Minecraft.getInstance();
        try {
            tickAction(client, self);
        } catch (Exception e) {
            LOGGER.error("[AutoRoutes] Playback error", e);
            stop("internal error (see log)");
        }
    }

    private static void finishAction() {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        // Release the camera as soon as the node is done. Without this a QUOI-style path-less route (nodes
        // only, no recorded walk) kept pulling the view back to the finished node's yaw every frame while
        // the player tried to walk to the next one themselves (2026-09-16 review).
        RouteRotation.clear();
        activeNode = null;
        step = null;
        settleTicks = cfg.getInteractDelayTicks();
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
        if (!stackQueue.isEmpty()) {
            return; // the rest of the stack first - tick() begins the next node after the settle
        }
        // The stack is done: the route moves on from the trigger's place, past anything already fired this run.
        int at = stackTrigger == null ? -1 : ordered.indexOf(stackTrigger);
        nextNode = (at >= 0 ? at : nextNode) + 1;
        while (nextNode < ordered.size() && firedInRun.contains(ordered.get(nextNode))) {
            nextNode++;
        }
        stackTrigger = null;
    }

    private static void tickAction(Minecraft client, LocalPlayer player) {
        RouteNode node = activeNode;
        stepTicks++;
        actionAge++;
        if (!awaitPhaseDone) {
            // The node's own action (etherwarp, use, boom, ...) doesn't start until this clears - see
            // tickAwait, which is this gate for any node now that AWAIT isn't its own type any more. Once it
            // clears, the action starts in this same tick.
            tickAwait(client, player, node);
            if (!awaitPhaseDone || activeNode != node) {
                return;
            }
            awaitDoneAge = actionAge;
            if (actionAge > 0) {
                LOGGER.info("[AutoRoutes] Node #{} {}: await held it {} tick(s)", route.indexOf(node) + 1, node.type,
                        actionAge);
            }
        }
        switch (node.type) {
            case START, AWAIT -> {
                logActed(node, "");
                finishAction(); // a legacy AWAIT's wait already ran in tickAwait
            }
            case WALK -> {
                // A recorded route's walking is the path's job, so there a walk node is just a marker. On a
                // path-less (/ar add) route it is the sprint.
                if (route.path().isEmpty()) {
                    walkHoldYaw = RouteCoords.toRealYaw(frame, node.yaw);
                    walkHoldLastPos = null;
                    walkHoldStallTicks = 0;
                }
                logActed(node, route.path().isEmpty() ? " (sprint starts this tick)" : "");
                finishAction();
            }
            case UNSNEAK -> {
                // Cleared before the player's tick, so this tick's input packet already carries shift up.
                unsneakOverride = true;
                forceSneak = false;
                wantSneak = false;
                logActed(node, "");
                finishAction();
            }
            case COMMAND -> {
                // Always allowed (killer560, 2026-10-04: "Remove the option for allow command nodes, those should
                // always be an option"). The old opt-in existed because a shared routes file can carry one; /ar add
                // cannot make a command node, so one only arrives by hand-editing or receiving the file.
                if (node.command != null && !node.command.isBlank()) {
                    String cmd = node.command.trim();
                    if (cmd.startsWith("/")) {
                        player.connection.sendCommand(cmd.substring(1));
                    } else {
                        player.connection.sendChat(cmd);
                    }
                }
                logActed(node, "");
                finishAction();
            }
            case ROTATE -> tickRotate(node);
            case ETHERWARP -> tickEtherwarp(client, player, node);
            case USE_ITEM -> tickUseItem(client, player, node);
            case BOOM -> tickBoom(client, player, node);
            case DUNGEON_BREAKER -> tickBreaker(client, player, node);
        }
    }

    private static void tickRotate(RouteNode node) {
        if (step == Step.PREP) {
            aimAt(node);
            step = Step.AIM;
            stepTicks = 0;
        }
        // !isActive() covers obvious mode, where aimAt() snaps and clears the controller instead of
        // running an approach - settled() is false forever in that case, so the node used to sit out the
        // whole AIM_TIMEOUT before moving on (2026-09-16 review). It now finishes on the tick it fired.
        if (!RouteRotation.isActive() || RouteRotation.settled(1.5f) || stepTicks > AIM_TIMEOUT) {
            logActed(node, RouteRotation.isActive() ? " (legit camera turn)" : "");
            finishAction();
        }
    }

    /**
     * The {@code awaitEnabled} modifier's own PREP/CONFIRM cycle - "wait for {@link RouteNode#awaitAmount}
     * secrets (or a delay) before this node fires", the old {@code AWAIT} node's behaviour, now gating
     * whatever real action {@code node} is instead of being a node of its own (see {@link #tickAction}).
     * On success it clears {@link #awaitPhaseDone} and resets {@link #step}
     * so the node's own action starts fresh the very next tick, rather than finishing the node outright.
     */
    private static void tickAwait(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            awaitBaselineSecrets = secretsFound;
            awaitBatSecrets = 0;
            countedBats.clear();
            awaitStartMs = System.currentTimeMillis();
            step = Step.CONFIRM;
        }
        boolean done;
        if (node.awaitCondition == RouteNode.AwaitCondition.DELAY) {
            done = System.currentTimeMillis() - awaitStartMs >= node.awaitAmount;
        } else {
            // QUOI AwaitArgument: secret bats near the player count too (they never touch the action bar).
            for (Entity e : client.level.entitiesForRendering()) {
                if (!(e instanceof Bat bat) || countedBats.contains(bat.getId())) {
                    continue;
                }
                float max = bat.getMaxHealth();
                if ((max == 100f || max == 200f || max == 400f || max == 800f) && bat.distanceTo(player) <= 10f) {
                    countedBats.add(bat.getId());
                    awaitBatSecrets++;
                }
            }
            int fromBar = secretsFound < 0 || awaitBaselineSecrets < 0 ? 0 : Math.max(0, secretsFound - awaitBaselineSecrets);
            done = fromBar + awaitBatSecrets >= Math.max(1, node.awaitAmount);
        }
        if (done) {
            awaitPhaseDone = true;
            step = Step.PREP;
            stepTicks = 0;
        }
    }

    /**
     * Fire tick: slot, sneak and aim are all asked for at once. The use goes out as soon as the server has the
     * sneak - this very tick when the last input packet already carried shift, otherwise the next tick, after this
     * tick's input packet has carried it. A swap costs no tick: the held-item packet is sent before either.
     */
    private static void tickEtherwarp(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            int slot = ItemIdentity.findEtherwarpSlot(player);
            if (slot < 0) {
                LOGGER.info("[AutoRoutes] Etherwarp: no hotbar item with ethermerge / ETHERWARP_CONDUIT");
                stop("no etherwarp item in the hotbar");
                return;
            }
            select(client, player, slot);
            forceSneak = true;
            unsneakOverride = false;
            wantSneak = true;
            aimAt(node);
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            wantSneak = true;
            // QUOI ClearExecutor's rule, kept: never use before the SERVER has the sneak, or the warp is a plain AOTV
            // hop. lastSentInput is what the last ServerboundPlayerInputPacket said, and that packet was sent before
            // anything this tick sends, so the server applies the sneak first (handlePlayerInput -> setShiftKeyDown).
            // No round trip to wait for.
            if (!player.getLastSentInput().shift()) {
                if (stepTicks > SNEAK_TIMEOUT) {
                    LOGGER.info("[AutoRoutes] Etherwarp: sneak never reached the server (client shift={}, input mixin={})",
                            player.isShiftKeyDown(), mixinApplied);
                    stop("couldn't start sneaking for the etherwarp");
                }
                return;
            }
            if (sneakReadyAge < 0) {
                sneakReadyAge = actionAge;
            }
            if (!aimReady()) {
                return;
            }
            actionOrigin = player.position();
            teleportPacketSeen = false;
            useHeldItem(client, player, node, false);
            logActed(node, " (sneak " + (sneakReadyAge == awaitDoneAge ? "already held"
                    : "went out in the firing tick's input packet") + ", "
                    + ItemIdentity.skyblockId(player.getMainHandItem()) + ")");
            step = Step.CONFIRM;
            stepTicks = 0;
            cameraGraceTicks = LANDING_TIMEOUT + 5;
            return;
        }
        if (step == Step.CONFIRM) {
            wantSneak = true;
            if (landed(player, node)) {
                LOGGER.info("[AutoRoutes] Etherwarp: landed at {} {} tick(s) after the use ({} from firing)",
                        fmt(player.position()), stepTicks, actionAge);
                // A next etherwarp in the same stack keeps the sneak, so the server still has it when that one fires
                // and it uses on its own firing tick; anything else lets go.
                RouteNode next = stackQueue.peek();
                forceSneak = next != null && next.type == RouteNode.Type.ETHERWARP;
                RouteRotation.rebase();
                cameraGraceTicks = 3;
                rejoinPathAfterTeleport(player, node);
                finishAction();
                noteLanding(node);
            } else if (stepTicks > LANDING_TIMEOUT) {
                LOGGER.info("[AutoRoutes] Etherwarp: no landing after {} ticks - player {} (moved {} from {}), "
                        + "recorded landing {}", stepTicks, fmt(player.position()),
                        String.format(Locale.US, "%.2f", actionOrigin == null ? 0.0 : player.position().distanceTo(actionOrigin)),
                        fmt(actionOrigin),
                        node.hasLanding ? fmt(RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ)) : "none");
                stop("etherwarp didn't land where it was recorded");
            }
        }
    }

    private static void tickUseItem(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            if (node.item == null) {
                stop("use-item node has no item");
                return;
            }
            int slot = ItemIdentity.findHotbarSlot(player, node.item);
            if (slot < 0) {
                stop(node.item + " is not in the hotbar");
                return;
            }
            select(client, player, slot);
            aimAt(node);
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            if (!aimReady()) {
                return;
            }
            actionOrigin = player.position();
            teleportPacketSeen = false;
            useHeldItem(client, player, node, true);
            logActed(node, " (" + node.item + ")");
            step = Step.CONFIRM;
            stepTicks = 0;
            if (!node.hasLanding) {
                // Nothing generic to wait for from the server for an arbitrary item: the use call itself, with
                // the right item in hand, is the confirmation; the interact delay follows.
                finishAction();
                return;
            }
            cameraGraceTicks = LANDING_TIMEOUT + 5;
            return;
        }
        if (step == Step.CONFIRM) {
            if (landed(player, node)) {
                LOGGER.info("[AutoRoutes] Use: landed {} tick(s) after the use", stepTicks);
                RouteRotation.rebase();
                cameraGraceTicks = 3;
                rejoinPathAfterTeleport(player, node);
                finishAction();
                noteLanding(node);
            } else if (stepTicks > LANDING_TIMEOUT) {
                stop(node.item + " didn't teleport where it was recorded");
            }
        }
    }

    private static void tickBoom(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            int slot = ItemIdentity.findHotbarSlotById(player, BOOM_IDS);
            if (slot < 0) {
                stop("no Superboom in the hotbar");
                return;
            }
            select(client, player, slot);
            aimAt(node);
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            if (!aimReady()) {
                return;
            }
            BlockHitResult hit = blockInSight(client, player, node, 4.5);
            if (hit == null) {
                stop("superboom node isn't looking at a block");
                return;
            }
            boomTarget = hit.getBlockPos();
            boomBefore.clear();
            boomBefore.put(boomTarget, client.level.getBlockState(boomTarget));
            for (Direction d : Direction.values()) {
                BlockPos p = boomTarget.relative(d);
                boomBefore.put(p, client.level.getBlockState(p));
            }
            // The same in the dungeon sim: its integrated server answers these packets the way Hypixel's does.
            if (AutoRoutesConfig.getInstance().isLegitMode()) {
                // A real left click: vanilla start + abort, the same packets a tap on an unbreakable block sends.
                client.gameMode.startDestroyBlock(boomTarget, hit.getDirection());
                client.gameMode.stopDestroyBlock();
            } else {
                player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, boomTarget, hit.getDirection()));
                player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, boomTarget, hit.getDirection()));
            }
            player.swing(InteractionHand.MAIN_HAND);
            logActed(node, "");
            step = Step.CONFIRM;
            stepTicks = 0;
            return;
        }
        if (step == Step.CONFIRM) {
            boolean changed = false;
            for (Map.Entry<BlockPos, BlockState> e : boomBefore.entrySet()) {
                if (client.level.getBlockState(e.getKey()) != e.getValue()) {
                    changed = true;
                    break;
                }
            }
            if (changed) {
                LOGGER.info("[AutoRoutes] Boom: blocks changed {} tick(s) after the click", stepTicks);
                finishAction();
            } else if (stepTicks > BOOM_TIMEOUT) {
                stop("superboom didn't break anything");
            }
        }
    }

    private static void tickBreaker(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            if (node.breakerBlocks.isEmpty()) {
                logActed(node, " (no blocks)");
                finishAction(); // nothing to break (a fresh node before /ar edit db)
                return;
            }
            breakerQueue = new ArrayList<>();
            for (BlockPos rel : node.breakerBlocks) {
                breakerQueue.add(RouteCoords.toRealBlock(frame, rel));
            }
            int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
            if (slot < 0) {
                stop("no Dungeon Breaker in the hotbar");
                return;
            }
            select(client, player, slot);
            // The item's lore, in the sim too: the sim's server keeps that line current as Hypixel's does. The
            // client's selected slot changed above, so this already reads the breaker.
            int charges = breakerCharges(player.getMainHandItem());
            LOGGER.info("[AutoRoutes] Breaker: {} block(s) queued, {} charge(s)", breakerQueue.size(), charges);
            if (charges <= 0) {
                stop("Dungeon Breaker has no charges");
                return;
            }
            step = Step.DO;
            stepTicks = 0;
        }
        if (step == Step.DO) {
            // QUOI DungeonBreakerAction: one START_DESTROY_BLOCK per block, interact-delay ticks apart, no
            // rotation (block breaking is range-checked, not look-checked). Air / unloaded / far blocks skip. The
            // first block goes on the firing tick (stepTicks is 0 there), right behind the held-item packet.
            int delay = Math.max(1, AutoRoutesConfig.getInstance().getInteractDelayTicks());
            if (stepTicks % delay != 0) {
                return;
            }
            Vec3 eye = player.getEyePosition();
            while (!breakerQueue.isEmpty()) {
                BlockPos pos = breakerQueue.remove(0);
                if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).isAir()) {
                    continue;
                }
                // To the BOX, not the centre. The constant above was tightened to the real 4.5 and this
                // measure was left on distToCenterSqr, which reads up to sqrt(0.75) further - so the gate
                // its own javadoc describes was still off by that much, refusing blocks well inside reach.
                if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos) > BREAKER_RANGE_SQ) {
                    LOGGER.info("[AutoRoutes] Breaker block {} out of range - skipped", pos);
                    continue;
                }
                player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                player.swing(InteractionHand.MAIN_HAND);
                if (breakerSent.isEmpty()) {
                    logActed(node, "");
                }
                breakerSent.add(pos);
                if (!breakerQueue.isEmpty()) {
                    return; // next block on the next delay tick
                }
                break;
            }
            step = Step.CONFIRM;
            stepTicks = 0;
            return;
        }
        if (step == Step.CONFIRM) {
            if (breakerSent.isEmpty()) {
                logActed(node, " (nothing in range to break)");
                finishAction();
                return;
            }
            int gone = 0;
            for (BlockPos pos : breakerSent) {
                if (client.level.getBlockState(pos).isAir()) {
                    gone++;
                }
            }
            if (gone == breakerSent.size()) {
                LOGGER.info("[AutoRoutes] Breaker: all {} block(s) gone {} tick(s) after the last break", gone, stepTicks);
                finishAction();
            } else if (stepTicks > BREAKER_TIMEOUT) {
                if (gone == 0) {
                    stop("dungeon breaker didn't break the blocks");
                } else {
                    LOGGER.info("[AutoRoutes] Breaker: {} of {} blocks broke - continuing", gone, breakerSent.size());
                    finishAction();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** Legit mode: humanized camera turn toward the node's recorded look (with the next node as the feint hint).
     *  Obvious mode: no camera movement at all - the rotation goes into the packet instead. */
    private static void aimAt(RouteNode node) {
        if (!AutoRoutesConfig.getInstance().isLegitMode()) {
            RouteRotation.clear();
            return;
        }
        float yaw = RouteCoords.toRealYaw(frame, node.yaw);
        RouteNode next = !stackQueue.isEmpty() ? stackQueue.peek()
                : nextNode + 1 < ordered.size() ? ordered.get(nextNode + 1) : null;
        boolean hasNext = next != null && next.isDiscreteAction();
        RouteRotation.beginApproach(yaw, node.pitch, hasNext, hasNext ? RouteCoords.toRealYaw(frame, next.yaw) : 0f,
                hasNext ? next.pitch : 0f);
    }

    /** Aim is ready when settled (legit) or immediately (obvious); a turn that never settles times out into the
     *  click anyway rather than freezing - the click's own confirmation catches a bad aim. */
    private static boolean aimReady() {
        if (!AutoRoutesConfig.getInstance().isLegitMode()) {
            return true;
        }
        return (RouteRotation.settled(1.0f) && stepTicks >= AutoRoutesConfig.getInstance().getInteractDelayTicks())
                || stepTicks > AIM_TIMEOUT;
    }

    /**
     * Selects the hotbar slot and tells the server NOW, through vanilla's own
     * {@code MultiPlayerGameMode.ensureHasSentCarriedItem}, so its {@code carriedIndex} agrees and its
     * {@code tick()} does not send the same slot again. (The old code sent the packet by hand and left
     * {@code carriedIndex} stale, so every route swap went out twice - a same-slot repeat a vanilla client never
     * sends.) The server handles the held-item packet before anything sent after it, so the use / click that follows
     * in the same tick is made with the new item: a swap costs no tick.
     */
    private static void select(Minecraft client, LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() == slot) {
            return;
        }
        player.getInventory().setSelectedSlot(slot);
        nodeSwapped = true;
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        } else {
            // The invoker config did not load: vanilla's next ensureHasSentCarriedItem will send it again.
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    /** The per-node timing line: how many ticks the node's action took from firing (after any await), 0 = the
     *  firing tick itself. */
    private static void logActed(RouteNode node, String detail) {
        LOGGER.info("[AutoRoutes] Node #{} {} acted {} tick(s) after firing{}{}", route == null ? "?" : route.indexOf(node) + 1,
                node.type, actionAge - awaitDoneAge, nodeSwapped ? " (hotbar swap, same tick)" : "", detail);
    }

    /** Legit: a real right click at the live (already turned) rotation - for a use-item node the block in the
     *  crosshair first like vanilla (a recorded lever/chest click replays as one), then the item; an etherwarp is
     *  QUOI's plain {@code gameMode.useItem}. Obvious: the rotated use packet without touching the camera
     *  ({@code ClearExecutor.doInteract}). */
    private static void useHeldItem(Minecraft client, LocalPlayer player, RouteNode node, boolean blockInteraction) {
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        // No sim branch: the dungeon sim's integrated server answers this exact packet as Hypixel's does
        // (roomsim.SimAbilities), so a route runs the same code in both.
        if (legit) {
            InteractionResult result = InteractionResult.PASS;
            if (blockInteraction) {
                HitResult hit = player.pick(4.5, 1f, false);
                if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                    result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
                }
            }
            if (!result.consumesAction()) {
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        float targetYaw = RouteCoords.toRealYaw(frame, node.yaw);
        // Same direction as the target, expressed relative to the running (unwrapped) yaw - never a wrapped absolute.
        float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitch = Mth.clamp(node.pitch, -90f, 90f);
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level,
                    sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        } else {
            float oldYaw = player.getYRot();
            float oldPitch = player.getXRot();
            player.setYRot(yaw);
            player.setXRot(pitch);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Real confirmation of a teleport: at the recorded landing, or (no landing recorded) clearly moved.
     * <p>
     * Being within {@link #LANDING_TOLERANCE} of the landing is not enough on its own: a node whose landing is close
     * to where it stands (a short warp, or one aimed down) read as "landed" the tick the use was sent, whether or not
     * anything teleported him - which is how his third 2026-10-04 attempt "completed" in the same second it started
     * without warping. So something must also show a teleport happened: the server's position packet since the use
     * (Hypixel and the sim both teleport with one, so a real warp of any length passes, including one onto the spot
     * he stands on), or - should that hook not have applied - having moved half a block, or the use having been sent
     * from the landing itself (the old behaviour for a warp in place).
     */
    private static boolean landed(LocalPlayer player, RouteNode node) {
        Vec3 pos = player.position();
        if (node.hasLanding) {
            Vec3 landing = RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ);
            if (pos.distanceTo(landing) > LANDING_TOLERANCE) {
                return false;
            }
            boolean moved = actionOrigin == null || pos.distanceTo(actionOrigin) > 0.5;
            boolean originWasLanding = actionOrigin != null && actionOrigin.distanceTo(landing) <= 0.5;
            return teleportPacketSeen || moved || originWasLanding;
        }
        return actionOrigin != null && pos.distanceTo(actionOrigin) > 3.0;
    }

    /** A teleport node just finished: arm the arrival re-fire, but only on a path-less route and only when the server's
     *  position packet proved the teleport - a landing judged from the client's own position alone never re-fires. */
    private static void noteLanding(RouteNode node) {
        landedFrom = route != null && route.path().isEmpty() && teleportPacketSeen ? node : null;
    }

    private static String fmt(Vec3 v) {
        return v == null ? "null" : String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** After a teleport the player is somewhere past the node's sample: continue from the nearest one there. */
    private static void rejoinPathAfterTeleport(LocalPlayer player, RouteNode node) {
        RoutePath path = route.path();
        if (path.isEmpty()) {
            return;
        }
        Vec3 rel = RouteCoords.toRelative(frame, player.position());
        int from = Math.max(cursor, node.pathIndex);
        cursor = Math.max(cursor, path.nearest(from, from + SEEK_WINDOW * 2, rel.x, rel.y, rel.z));
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
    }

    /** The block a superboom click lands on: the live crosshair in legit mode (the camera was turned), the recorded
     *  look direction in obvious mode. */
    private static BlockHitResult blockInSight(Minecraft client, LocalPlayer player, RouteNode node, double reach) {
        if (AutoRoutesConfig.getInstance().isLegitMode()) {
            HitResult hit = player.pick(reach, 1f, false);
            return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b : null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = TeleportUtils.getLook(RouteCoords.toRealYaw(frame, node.yaw), node.pitch).scale(reach);
        HitResult hit = client.level.clip(new ClipContext(eye, eye.add(look), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b : null;
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
}
