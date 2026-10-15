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
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCorrections;

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
import com.killer560.hub.util.ModChat;

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
 * Legit mode turns the camera for every discrete action; obvious mode turns the BODY to each action's look on the
 * action's own tick while the camera is held still ({@code util/ViewFreeze}), and gives the body back to the held view
 * a tick after the last action - so every use and dig carries a rotation the next movement packet reports, as a
 * vanilla client's must (GrimAC BadPacketsJ / RotationBreak, 2026-10-05) - killer560: "legit or obvious - in legit
 * mode it rotates for every etherwarp."
 * <p>
 * <b>Discrete actions wait for real confirmation</b> (never a timer alone): an etherwarp / teleporting item is done
 * when the player actually arrives at the recorded landing, a dungeon-breaker node when its blocks are actually air,
 * a superboom when a block within {@link #BOOM_WATCH_RADIUS} of its hit changed (and one that changes nothing within a
 * round trip completes: there was nothing left to blow). Anything else that cannot be confirmed within a generous timeout, any
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
    /**
     * How far round the block a superboom hit its confirmation watches for a change, as a cube. A Superboom does not
     * break the block it lands on: it blows the crypt or weak wall NEAR it ("anywhere close to a crypt", killer560,
     * 2026-10-01), so the old check - the hit block and its six neighbours - read a boom that opened a crypt one block
     * over as "didn't break anything" and stopped the route (his Museum stack, 2026-10-06: the sim broke 30 blocks).
     */
    private static final int BOOM_WATCH_RADIUS = 3;
    /** The least a boom that changed nothing waits for the server's answer before moving on, plus the round trip. */
    private static final int BOOM_SETTLE_MIN_TICKS = 4;
    private static final int BOOM_SETTLE_MAX_TICKS = 20;
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
    private static final String BREAKER_ID = ItemIdentity.BREAKER_ID;
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
    /**
     * Sneak the route holds whatever the node: set by an etherwarp and kept BETWEEN nodes while the next node is an
     * etherwarp (killer560, 2026-10-04: "Make it so it will hold crouch if it knows the next node it is going to hit
     * is an etherwarp. It should only uncrouch if it hits a walk node"), so the server already has the sneak when that
     * etherwarp fires and it uses on its firing tick. {@link #planSneak} decides after every node; a WALK, UNSNEAK or
     * USE_ITEM node, the route ending and {@link #stop} let go.
     */
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
    /** Every server position packet bumps this (the hook's thread); {@link #positionPacketsHandled} is the tick's copy.
     *  A packet the route did not ask for is a correction ({@link #checkCorrections}). */
    private static volatile int positionPackets;
    private static int positionPacketsHandled;
    /** {@link #execTicks} until which a packet is presumed to be the answer to a node that may teleport him itself (a
     *  USE_ITEM such as an AOTV, a COMMAND), not a correction. */
    private static long ownTeleportUntil;
    /** How long after such a node its teleport may still arrive (ping). */
    private static final int OWN_TELEPORT_GRACE_TICKS = 20;
    // ---- the warp being flown: an etherwarp node's own, or one saved hop of a path node ----
    /** Real-world look, landing and landing block of the warp under way ({@link #tickWarp}). */
    private static float warpYaw;
    private static float warpPitch;
    private static Vec3 warpLanding;
    private static BlockPos warpTarget;
    /**
     * Where {@link #warpYaw}/{@link #warpPitch} is the right look from: the node's own spot for its recorded look, a
     * path hop's origin for its saved one, or where he stood when it was worked out again. Null when there is no
     * landing block to aim at (nothing to re-aim to). See {@link #reaimIfMoved}.
     */
    private static Vec3 warpAimFrom;
    /** Where the warp's SAVED look is right from (the node's spot, a hop's origin); null with no landing block. */
    private static Vec3 warpSpot;
    /** Ticks this warp has spent stepping back onto {@link #warpSpot} because no ray from where he stood lands. */
    private static int warpCentreTicks;
    /** PATH: the saved hop being flown, or -1 before the first. */
    private static int hopIndex = -1;
    /**
     * The active node's teleport landed and the SERVER said so (its position packet arrived since the use). Then
     * {@link #finishAction} skips the interact delay and {@link #tick} fires whatever comes next on that same tick
     * ({@link #chainAfterLanding}): the server has already moved him, so there is nothing left to wait for - an
     * unconditional etherwarp chain runs at one warp per tick at zero ping (killer560, 2026-10-06: "it should only
     * really need to wait if it needs a secret or other nodes are going off").
     */
    private static boolean landingConfirmed;
    /** Set by {@link #finishAction} after a {@link #landingConfirmed} node: {@link #tick} carries on this same tick. */
    private static boolean chainAfterLanding;
    /**
     * Etherwarps Per Second (killer560, 2026-10-06: "something you use as a general time constraint, not a hard cap"):
     * a PACE, not a per-second budget. {@link #execTicks} counts executor ticks since the route started; a warp may go
     * out once it reaches {@link #nextWarpAt}, which each warp sets 20/rate ticks on. Fractions carry: a warp sent on the
     * tick the pace allowed schedules the next from the pace itself (3/s goes 0, 7, 14, 20 - 6.67 on average); a warp that
     * something else held longer (the landing, an await, a boom) schedules from its own tick, so time already spent counts
     * and nothing waits twice. Never ahead of the server: the landing check still comes first.
     */
    private static long execTicks;
    private static double nextWarpAt = Double.NEGATIVE_INFINITY;
    /** Ticks the warp under way has waited for the pace (for its "acted" line). */
    private static int warpPacedTicks;
    /** Ticks this warp's use waited for the server to hear its look first, a block click being unpaired (util/RightClick). */
    private static int warpTurnFirstTicks;

    /** A warp is being sent on this tick: where the pace puts the next one. */
    private static void notePacedWarp() {
        double spacing = 20.0 / AutoRoutesConfig.getInstance().getEtherwarpsPerSecond();
        double from = execTicks - nextWarpAt < 1.0 ? nextWarpAt : execTicks;
        nextWarpAt = from + spacing;
    }

    /** {@link #planSneak}'s answer to "what fires next, and where he stands?" - read by {@link #settleAfter}. */
    private static RouteNode plannedNext;
    private static boolean plannedInPlace;
    /** PATH: a plan this node asked for, and how it came out ("ok", a reason, or null while it runs). */
    private static boolean planAsked;
    /** PATH: the pair the "planned before its breaker blocks changed" line last went out for. */
    private static RouteNode staleSaid;
    /** PATH: the "waits for its proof" line went out for the pair being held. */
    private static boolean provingSaid;
    private static volatile String planOutcome;
    private static final int PLAN_TIMEOUT = 600;
    /** Ticks spent waiting for a missing landing block, and whether chat has been told. */
    private static int blockWaitTicks;
    private static boolean blockWaitSaid;
    /** How long a warp waits for its landing block to come back before the route gives up (60 s). */
    private static final int BLOCK_WAIT_TIMEOUT = 1200;
    private static List<BlockPos> breakerQueue = new ArrayList<>();
    /** Charges the breaker had when the node fired, less what it has sent since - a node never asks for more. */
    private static int breakerChargesLeft;
    /** The breaker node has had enough charges for its standing blocks and is breaking; until then it waits. */
    private static boolean breakerReady;
    /** Ticks the breaker node has waited for charges, and whether chat has been told. */
    private static int breakerWaitTicks;
    private static boolean breakerWaitSaid;
    /** How long a breaker node waits for its charges before the route gives up (30 s; 2 come back a second). */
    private static final int BREAKER_WAIT_TIMEOUT = 600;
    private static final Set<BlockPos> breakerSent = new HashSet<>();
    private static BlockPos boomTarget;
    // ---- crypt ----
    /** The held slot before a crypt node swapped to its weapon, put back when it is done. */
    private static int cryptSlotBefore = -1;
    /** A crypt node is holding the use key down ({@link #holdUse}); {@link #releaseUse} lets go, and so does {@link #stop}. */
    private static boolean useHeld;
    /** A crypt node with no crypt or prince of his killed in this many ticks stops the route. */
    private static final int CRYPT_TIMEOUT = 100;
    /** Legit crypt node: how long it aims for pitch 90.0 before moving on with nothing used. */
    private static final int CRYPT_AIM_GIVE_UP = 60;
    /** Crypt node: route ticks in a row that ended with the look at pitch 90.0 (the server has it once this is 2). */
    private static int cryptDownTicks;
    private static final Map<BlockPos, BlockState> boomBefore = new HashMap<>();

    // ---- await ----
    private static long awaitStartMs;
    /** True once the CURRENT node's {@code awaitEnabled} gate (if it has one) has been satisfied - the node's
     *  own type-specific action (etherwarp, use, boom, ...) only starts once this is true. Set in
     *  {@link #beginAction}, read/advanced in {@link #tickAction}. AWAIT stopped being its own node type
     *  (2026-09-2x) - {@link #tickAwait} is now this pre-action gate for ANY node, not a case of its own. */
    private static boolean awaitPhaseDone = true;
    /** True from a node with an await firing until its own action starts - the span a screen may be open in
     *  (a secret chest's window) without stopping the route. */
    private static boolean awaitHeld;
    /** The active node's {@link RouteRunMemory#nodeKey}, and the secrets of this run remembered toward its await. */
    private static String awaitNodeKey;
    private static List<RouteRunMemory.Got> awaitRemembered = List.of();
    /** {@link AwaitEvents#now()} when the active node's own action began (its await met or skipped, or it had none):
     *  a secret of his after this and in the last {@link AwaitEvents#CARRY_TICKS} before it finished is the NEXT
     *  node's, not lost with this node's window. */
    private static int actionFromTick;
    /** Set by {@link #finishAction}: the next node's await also counts remembered secrets got at or after this tick.
     *  {@code Integer.MAX_VALUE} when there is no node before it (the route's start has its own lookback). */
    private static int carryFrom = Integer.MAX_VALUE;
    /** {@link #execTicks} of a left click of his taken while the route was teleporting him between awaits (a warp
     *  or path node in flight, or the settle before the next node of a stack) - see {@link #takeEarlySkip}. */
    private static long earlySkipClickAt = Long.MIN_VALUE / 2;
    /** How long such a click still skips the await of the node it lands him on, in route ticks. */
    static final int EARLY_SKIP_TICKS = 10;
    /** The active node has sent its action (its "acted" line is written then). */
    private static boolean nodeActed;

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
     * cycle; the next fire goes on the tick the server-confirmed landing is seen ({@link #chainAfterLanding}).
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

    /** The room of the route being played, or null when none is (Auto Trap stops a trap route the moment he leaves). */
    public static String runningRoom() {
        Route r = route;
        return running && r != null ? r.roomName() : null;
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
        // A breaker node only keeps mining once it has FINISHED; stopped while it was still acting (waiting for charges,
        // say), or stopped by him (a fresh movement key, his click), it sends nothing more (96-ar-breakerwait pass 2).
        if (wasRunning && (activeNode != null && activeNode == lingerNode
                || reason != null && USER_STOP_REASONS.contains(reason))) {
            clearBreakerLinger("route stopped: " + reason);
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
        chainAfterLanding = false;
        landingConfirmed = false;
        // A crypt node stopped mid-hold must not leave right click held down.
        releaseUse(Minecraft.getInstance());
        forceSneak = false;
        unsneakOverride = false;
        endWalkHold();
        handsLatched = false;
        releaseKeys();
        RouteRotation.clear();
        AwaitEvents.end();
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
        chainAfterLanding = false;
        landingConfirmed = false;
        execTicks = 0;
        positionPacketsHandled = positionPackets;
        ownTeleportUntil = 0;
        nextWarpAt = Double.NEGATIVE_INFINITY;
        forceSneak = false;
        unsneakOverride = false;
        awaitPhaseDone = true;
        carryFrom = Integer.MAX_VALUE;
        earlySkipClickAt = Long.MIN_VALUE / 2;
        AwaitEvents.begin(client);
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
        RouteRunMemory.routeCompleted(route.roomName());
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
        positionPackets++;
    }

    /**
     * Mod rule (killer560, 2026-10-06: "Nothing in this mod should stop from server corrections ever - just have it send
     * a chat message and make noises"). Run at the top of every tick: a server position packet the route did not ask
     * for is a correction - a chat line and the correction alarm ({@link ServerCorrections#report}), and the route
     * carries on from where the server put him. Never a stop, and nothing moves him back.
     * <ul>
     * <li>During a warp's CONFIRM (etherwarp node or path hop) a packet that does not leave him on the landing: the warp
     * is flown again from here - a path picks the hop that starts nearest him; an etherwarp re-fires when he is still in
     * its ring, else the node is let go and the route goes on (a path-less route fires the next node he stands in, a
     * recorded one rejoins its path).</li>
     * <li>While walking a recorded path: the cursor is put on the nearest sample of the whole path (a replan of where he
     * is on it), so the drift check measures from there rather than from a sample the correction left behind.</li>
     * <li>During a USE_ITEM or COMMAND node, and for {@link #OWN_TELEPORT_GRACE_TICKS} after one, a packet is presumed to
     * be that node's own teleport and is not reported.</li>
     * </ul>
     */
    private static void checkCorrections(Minecraft client, LocalPlayer player) {
        int packets = positionPackets;
        if (packets == positionPacketsHandled) {
            return;
        }
        positionPacketsHandled = packets;
        if (ServerCorrections.lastMoveDistance() < ServerCorrections.MIN_MOVE_BLOCKS) {
            return; // rotation-only: he is where he was
        }
        RouteNode node = activeNode;
        // The WARP's confirm, not the await's: tickAwait runs its own PREP/CONFIRM cycle on the same step field before
        // the node acts, and a packet then was judged against warpLanding - unset for this node, so another warp's - and
        // let a start node go that had sent nothing (killer560, 2026-10-07: "the interactive map was off when it tried
        // to take me to the start node", testkit 96-ar-405-startwarp).
        boolean warping = node != null && awaitPhaseDone && step == Step.CONFIRM
                && (node.type == RouteNode.Type.ETHERWARP || node.type == RouteNode.Type.PATH);
        if (warping) {
            if (warpLanding == null || player.position().distanceTo(warpLanding) <= LANDING_TOLERANCE) {
                return; // the warp's own landing (tickWarp confirms it this tick)
            }
            double off = player.position().distanceTo(warpLanding);
            if (node.type == RouteNode.Type.PATH && node.pathHops != null && !node.pathHops.isEmpty()) {
                int hop = nearestHopOrigin(player, node);
                reportCorrection(String.format(Locale.US,
                        "during path #%d's warp %d (%.1f from its landing) - flying from warp %d again",
                        route.indexOf(node) + 1, hopIndex + 1, off, hop + 1));
                loadHop(node, hop);
                return;
            }
            if (node.contains(RouteCoords.toReal(frame, node.relativePos()), AutoRoutesConfig.getInstance().getHeight(),
                    player.getBoundingBox())) {
                reportCorrection(String.format(Locale.US,
                        "during etherwarp #%d (%.1f from its landing) - warping again", route.indexOf(node) + 1, off));
                step = Step.PREP;
                stepTicks = 0;
                return; // this tick's tickAction re-aims from here (the PREP branch) and fires as usual
            }
            reportCorrection(String.format(Locale.US,
                    "during etherwarp #%d (%.1f from its landing) - carrying on from here", route.indexOf(node) + 1, off));
            letGoAfterCorrection(client, player);
            return;
        }
        if (node != null && (node.type == RouteNode.Type.USE_ITEM || node.type == RouteNode.Type.COMMAND
                || node.type == RouteNode.Type.ETHERWARP || node.type == RouteNode.Type.PATH)) {
            return; // the node's own teleport, or a warp not yet sent / already confirmed
        }
        if (execTicks <= ownTeleportUntil) {
            return;
        }
        reportCorrection((node != null ? "during node #" + (route.indexOf(node) + 1) + " "
                + node.type.name().toLowerCase(Locale.ROOT) : route.path().isEmpty() ? "between nodes" : "on the path")
                + " - carrying on from here");
        relocateOnPath(player);
    }

    /** Chat line + alarm, and the camera check re-based: a position packet may carry a rotation of its own, which must
     *  not read as him turning the camera (the same re-base a confirmed landing does). */
    private static void reportCorrection(String what) {
        ServerCorrections.report("Auto Routes", what, ServerCorrections.lastMoveDistance());
        RouteRotation.rebase();
        cameraGraceTicks = Math.max(cameraGraceTicks, 3);
    }

    /** The saved hop of {@code node} whose origin is nearest him (the corrected position). */
    private static int nearestHopOrigin(LocalPlayer player, RouteNode node) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < node.pathHops.size(); i++) {
            RouteNode.PathHop h = node.pathHops.get(i);
            double d = player.position().distanceTo(RouteCoords.toReal(frame, h.ox(), h.oy(), h.oz()));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** A recorded path: the cursor goes to the nearest sample from the path's start up to a seek window ahead - a setback
     *  puts him back any distance, but never far ahead, and a cursor jumping past a node would fire it out of place. */
    private static void relocateOnPath(LocalPlayer player) {
        RoutePath path = route.path();
        if (path.isEmpty()) {
            return;
        }
        Vec3 rel = RouteCoords.toRelative(frame, player.position());
        cursor = path.nearest(0, cursor + SEEK_WINDOW, rel.x, rel.y, rel.z);
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
    }

    /**
     * A warp the correction took him away from: the node is let go WITHOUT counting as done - on a path-less route the
     * walk step fires whichever node he stands in next (this one again if he walks back into it), on a recorded one the
     * cursor rejoins the path where he is. The rest of the stack goes with it; nothing stops.
     */
    private static void letGoAfterCorrection(Minecraft client, LocalPlayer player) {
        RouteRotation.clear();
        activeNode = null;
        step = null;
        stackQueue.clear();
        stackTrigger = null;
        landingConfirmed = false;
        chainAfterLanding = false;
        landedFrom = null;
        settleTicks = AutoRoutesConfig.getInstance().getInteractDelayTicks();
        releaseUse(client);
        clearMovement();
        relocateOnPath(player);
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
        if (breakerWaiting()) {
            // A breaker node waiting for charges can wait up to 30 s: a fresh movement key is him taking over, on a
            // path-less route too (a node's own few ticks of action override it; this is not a few ticks).
            stop("you moved");
            return false;
        }
        return acting;
    }

    /** A breaker node is holding for charges ({@link #tickBreaker}), nothing sent yet. */
    private static boolean breakerWaiting() {
        return activeNode != null && activeNode.type == RouteNode.Type.DUNGEON_BREAKER && step == Step.DO
                && !breakerReady && breakerWaitTicks > 0;
    }

    /** Driving the player this tick (the mixin asks this). Sneak alone (an etherwarp prep) still counts. */
    public static boolean isDriving() {
        return running && (wantForward || wantBackward || wantLeft || wantRight || wantJump || wantSneak || wantSprint);
    }

    /**
     * The route is holding sneak between nodes ({@link #forceSneak}). The input mixin adds it to his own keys on a
     * tick it leaves them alone (he is walking a path-less route to the next node himself), so the held sneak is not
     * dropped for those ticks - otherwise the next etherwarp would wait a tick for a fresh one.
     */
    public static boolean holdsSneak() {
        // Not while a node waits on its await: see tickAction - he is clicking the secrets it waits for.
        // Nor under a screen: a vanilla client's keys are all up while one is open, and a container closed while the
        // input packet says sneaking is GrimAC MultiActionsD (2026-10-05, 62-argrim: an await met by the chest click
        // itself started the etherwarp's sneak a tick before the chest's window arrived).
        return running && forceSneak && !(activeNode != null && awaitHeld)
                && !AutoRoutesFeature.screenBlocks(Minecraft.getInstance());
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

    /** Per render frame (from the feature's {@code LevelRenderEvents} hook): the smooth camera step. Runs every
     *  frame, not every tick, for the same reason SimonSays' Rotate Mode does - 20Hz looks stepped. */
    static void tickFrame() {
        if (running || MimicKiller.isBusy()) {
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
        execTicks++;
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.level != lastLevel) {
            stop("world change");
            return;
        }
        checkCorrections(client, player);
        if (AutoRoutesFeature.screenBlocks(client)) {
            if (activeNode != null && (awaitHeld || (activeNode.awaitEnabled && !nodeActed))) {
                // Waiting on secrets: the screen is almost always the secret itself - a chest's own window
                // (killer560's await:2 waits for exactly that). The wait carries on under it, the keys stay off,
                // and the node fires on the first tick the screen is closed. Anything else still stops the route.
                clearMovement();
                wantSneak = false;
                applyFallbackKeys(client);
                if (awaitPhaseDone) {
                    // The await was met a tick before the chest's window arrived (the secret count and the window
                    // come in either order): the action had started but sent nothing, so it starts over, cleanly,
                    // when the window closes - sneak, aim and all.
                    step = Step.PREP;
                    stepTicks = 0;
                } else {
                    stepTicks++;
                    actionAge++;
                    tickAwait(client, player, activeNode);
                    if (awaitPhaseDone) {
                        awaitDoneAge = actionAge;
                        LOGGER.info("[AutoRoutes] Node #{} {}: await met under a screen after {} tick(s) - fires when "
                                + "it closes", route.indexOf(activeNode) + 1, activeNode.type, actionAge);
                    }
                }
                return;
            }
            net.minecraft.client.gui.screens.Screen screen = McCompat.screen(client);
            if (screen == lateChestWindow || (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                    && AwaitEvents.chestClickedWithin(LATE_CHEST_WINDOW_TICKS))) {
                // The window of a chest he just clicked, arriving after the route moved on. An await is met by the
                // click itself (his rule), and Hypixel credits the chest on that click - the window is only its view, a
                // round trip behind - so with any ping the route can warp before it arrives. That is not him opening
                // something: wait under it like the await's own window (keys off, nothing sent), carry on once it is
                // closed. It used to stop the route (2026-10-05, 96-ar-play on 26.2: "Stopped: a screen opened").
                if (screen != lateChestWindow) {
                    LOGGER.info("[AutoRoutes] The clicked chest's window arrived after the route moved on - waiting "
                            + "under it, carries on when it closes");
                }
                lateChestWindow = screen;
                clearMovement();
                wantSneak = false;
                applyFallbackKeys(client);
                return;
            }
            stop("a screen opened");
            return;
        }
        lateChestWindow = null;
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
            if (activeNode != null && awaitHeld) {
                // Waiting on its await, the node is waiting for HIM: turning to click the chest it waits for is not
                // taking the route back (found 2026-10-05, 62-argrim: the await stopped as "you moved the camera").
                RouteRotation.clear();
            } else if (!route.path().isEmpty() || activeNode != null || !stackQueue.isEmpty()) {
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
        for (String line : AwaitEvents.drain()) {
            RouteNode waiting = activeNode != null && !awaitPhaseDone ? activeNode : null;
            LOGGER.info("[AutoRoutes] {}{}", line, waiting == null ? ""
                    : " - node #" + (route.indexOf(waiting) + 1) + " waits for " + Math.max(1, waiting.awaitAmount));
        }
        if (teleportingBetweenAwaits() && takeSkipClick(client)) {
            // killer560, 2026-10-14: "Left clicking for auto routes does not always skip the node." A click he makes
            // while the warp that lands him on the waiting node is still in flight (or the stack settles) reached
            // vanilla as a swing a tick or two BEFORE the node began waiting, and was gone by then: the node sat at
            // 0/N. Held for EARLY_SKIP_TICKS - the node it lands him on skips its await with it.
            earlySkipClickAt = execTicks;
            LOGGER.info("[AutoRoutes] Left click while the route was warping you - held for the next node's await");
        }
        if (MimicKiller.isBusy() && activeNode != null && !awaitPhaseDone
                && (takeSkipClick(client) || takeEarlySkip())) {
            // A left click skips a waiting node while Kill Mimic works too (it used to be swallowed by vanilla for as
            // long as Kill Mimic kept the hands - up to five seconds of Spirit Sceptre uses). The click means "go":
            // Kill Mimic lets go and the node acts now.
            LOGGER.info("[AutoRoutes] Node #{} {}: left click skipped the await ({}/{} secrets) - Kill Mimic stopped",
                    route.indexOf(activeNode) + 1, activeNode.type, AwaitEvents.secrets() + awaitRemembered.size(),
                    Math.max(1, activeNode.awaitAmount));
            MimicKiller.cancel(client, "left click skipped the await");
            awaitPhaseDone = true;
            actionFromTick = AwaitEvents.now();
            step = Step.PREP;
            stepTicks = 0;
        }
        if (MimicKiller.isBusy() && mayYieldToMimic()) {
            // Kill Mimic has the hands: no node starts and nothing moves until it is done. A node already waiting on
            // its await keeps counting - the mimic's death may be the very secret it waits for.
            clearMovement();
            forceSneak = false;
            wantSneak = false;
            if (activeNode != null && !awaitPhaseDone) {
                stepTicks++;
                actionAge++;
                tickAwait(client, player, activeNode);
                if (awaitPhaseDone) {
                    awaitDoneAge = actionAge;
                }
            }
            applyFallbackKeys(client);
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
                if (running && activeNode == null && chainAfterLanding) {
                    // The server's position packet just put him on the landing (handled before this tick began, and
                    // vanilla answered it with its teleport accept and a position packet of its own), so the next node
                    // goes NOW: the rest of the stack, else whatever the walk step fires - the landing re-fire's node, or
                    // the next node he stands in. One warp per tick at zero ping, instead of landing + settle + a tick.
                    chainAfterLanding = false;
                    clearMovement();
                    if (!stackQueue.isEmpty()) {
                        beginAction(stackQueue.poll());
                    } else {
                        tickWalk(client, player);
                    }
                }
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

    /** Node actions begun, ever (only goes up). Auto Secret reads it to tell a route that is still working from one
     *  waiting on a walk nobody will make. */
    private static int actionsBegun = 0;

    public static int actionsBegun() {
        return actionsBegun;
    }

    private static void beginAction(RouteNode node) {
        actionsBegun++;
        // Another node fired: a breaker node still mining what was out of reach stops here (see tickBreakerLinger).
        clearBreakerLinger(node.type == RouteNode.Type.DUNGEON_BREAKER ? "the breaker fired again" : "another node fired");
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
        if (walkHoldYaw != null) {
            // The walk's camera ease goes with it - left running it kept turning him back to the walk's yaw through
            // the next node's await, and read his own turn toward the chest as "you moved the camera".
            RouteRotation.clear();
        }
        endWalkHold();
        clearMovement();
        handsLatched = true;
        // The await modifier (if this node has one) runs FIRST, as its own PREP/CONFIRM cycle through
        // tickAwait - see tickAction. Nothing else about the node starts until that gate opens.
        // A legacy standalone AWAIT node (an unmigrated file) is nothing but this wait.
        // A CRYPT node's await is not a gate before it acts but the number of kills its action goes on for ("crypt
        // await:1" = attack until one crypt or prince of yours dies) - see tickCrypt.
        awaitPhaseDone = (!node.awaitEnabled || node.type == RouteNode.Type.CRYPT || node.type == RouteNode.Type.BAT)
                && node.type != RouteNode.Type.AWAIT;
        awaitNodeKey = RouteRunMemory.nodeKey(route, node);
        awaitRemembered = List.of();
        actionFromTick = AwaitEvents.now();
        int carry = carryFrom;
        carryFrom = Integer.MAX_VALUE;
        if (!awaitPhaseDone && node.awaitCondition != RouteNode.AwaitCondition.DELAY) {
            // killer560, 2026-10-14: "if it remembers I already got that secret or that bat then it will not wait for
            // the await before doing that action". Met earlier this run: met now. First visit: a secret he already got
            // beside this node before the route started, and no other node's await used, counts toward it.
            if (RouteRunMemory.metThisRun(awaitNodeKey)) {
                LOGGER.info("[AutoRoutes] Node #{} {}: await already met earlier this run - secrets met from memory, "
                        + "not waiting", route.indexOf(node) + 1, node.type);
                awaitPhaseDone = true;
            } else {
                Vec3 nodeAt = RouteCoords.toReal(frame, node.relativePos());
                java.util.Set<String> window = AwaitEvents.windowSecrets();
                List<RouteRunMemory.Got> remembered = new ArrayList<>(RouteRunMemory.unclaimedNear(nodeAt,
                        AwaitEvents.beganAt(), window));
                for (RouteRunMemory.Got g : remembered) {
                    LOGGER.info("[AutoRoutes] Node #{} {}: await counts {} - already got this run", route.indexOf(node) + 1,
                            node.type, g.what());
                }
                if (carry != Integer.MAX_VALUE) {
                    // His secret taken while the node before this one was still acting - the item Hypixel's pickup
                    // packet hands him in the same batch as the warp's landing, or Secret Aura's click on the landing
                    // tick - arrives before this node's window opens (AwaitEvents.CARRY_TICKS). It is this node's.
                    java.util.Set<String> seen = new java.util.HashSet<>(window);
                    remembered.forEach(g -> seen.add(g.key()));
                    for (RouteRunMemory.Got g : RouteRunMemory.unclaimedNearSince(nodeAt, carry, seen)) {
                        remembered.add(g);
                        LOGGER.info("[AutoRoutes] Node #{} {}: await counts {} - got {} tick(s) ago, while the node "
                                + "before it was still acting", route.indexOf(node) + 1, node.type,
                                g.what(), Math.max(0, AwaitEvents.now() - g.tick()));
                    }
                }
                awaitRemembered = remembered;
            }
        }
        awaitHeld = !awaitPhaseDone;
        nodeActed = false;
        breakerQueue = new ArrayList<>();
        breakerSent.clear();
        breakerReady = false;
        breakerWaitTicks = 0;
        breakerWaitSaid = false;
        hopIndex = -1;
        // The last warp's landing and aim belong to that warp. Left over, checkCorrections measured a packet during
        // this node's await from them - in killer560's 2026-10-07 log from the landing of #6 of a run stopped two
        // minutes earlier, "47.4 from its landing" - and let the start node go (see checkCorrections).
        warpLanding = null;
        warpTarget = null;
        warpSpot = null;
        warpAimFrom = null;
        lineWaitTicks = 0;
        lineWaitBlock = null;
        jumpsDone = 0;
        landingConfirmed = false;
        planAsked = false;
        planOutcome = null;
        blockWaitTicks = 0;
        blockWaitSaid = false;
        boomTarget = null;
        boomBefore.clear();
        cryptSlotBefore = -1;
        releaseUse(Minecraft.getInstance());
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
        RouteNode finished = activeNode;
        if (finished != null && (finished.type == RouteNode.Type.USE_ITEM || finished.type == RouteNode.Type.COMMAND)) {
            ownTeleportUntil = execTicks + OWN_TELEPORT_GRACE_TICKS; // its teleport, if it has one, may still be coming
        } else if (finished != null && (finished.type == RouteNode.Type.ETHERWARP || finished.type == RouteNode.Type.PATH)) {
            ownTeleportUntil = Math.max(ownTeleportUntil, execTicks + 3); // a late duplicate of the landing packet
        }
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        // Release the camera as soon as the node is done. Without this a QUOI-style path-less route (nodes
        // only, no recorded walk) kept pulling the view back to the finished node's yaw every frame while
        // the player tried to walk to the next one themselves (2026-09-16 review).
        RouteRotation.clear();
        activeNode = null;
        step = null;
        // What the next node's await counts starts now ("since the previous node finished") - plus what this node
        // picked up on its way in during its last few ticks (AwaitEvents.CARRY_TICKS: Hypixel's pickup packet comes
        // in the same batch as the warp's landing, ahead of the tick that sees it).
        carryFrom = Math.max(actionFromTick, AwaitEvents.now() - AwaitEvents.CARRY_TICKS);
        AwaitEvents.window(Minecraft.getInstance());
        boolean landed = landingConfirmed;
        landingConfirmed = false;
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
        if (!stackQueue.isEmpty()) {
            planSneak(finished);
            settleAfter(landed, cfg);
            return; // the rest of the stack first - tick() begins the next node after the settle
        }
        // The stack is done: the route moves on from the trigger's place, past anything already fired this run.
        int at = stackTrigger == null ? -1 : ordered.indexOf(stackTrigger);
        nextNode = (at >= 0 ? at : nextNode) + 1;
        while (nextNode < ordered.size() && firedInRun.contains(ordered.get(nextNode))) {
            nextNode++;
        }
        stackTrigger = null;
        planSneak(finished);
        settleAfter(landed, cfg);
    }

    /**
     * The wait before the next node. Etherwarp into etherwarp after a SERVER-confirmed landing: none - the next warp goes
     * on this same tick ({@link #chainAfterLanding}), so an unconditional chain runs at one warp per tick at zero ping
     * (killer560, 2026-10-06). Anything else - a landing followed by a boom, breaker, use or crypt, or any node after a
     * non-teleport - keeps the interact delay, as before: a breaker stacked on the landing tile and fired on the landing
     * tick itself sent its digs and the dedicated server broke nothing (62-argrim-play, 2026-10-06, not traced further),
     * while two ticks later, as before, it breaks them.
     */
    private static void settleAfter(boolean landed, AutoRoutesConfig cfg) {
        boolean warpNext = landed && plannedInPlace && plannedNext != null
                && (plannedNext.type == RouteNode.Type.ETHERWARP || plannedNext.type == RouteNode.Type.PATH);
        chainAfterLanding = warpNext;
        settleTicks = warpNext ? 0 : cfg.getInteractDelayTicks();
    }

    /**
     * After every node: does the route hold sneak until the next one ({@link #forceSneak})? The next node is the one
     * that will actually fire next - the rest of this stack; else, on a path-less route after a teleport the server
     * confirmed, the node he landed in (the landing re-fire's own pick, {@link #nodeLandedIn}); else the next node in
     * order. Of a stack, its first node to fire.
     * <ul>
     * <li>an ETHERWARP: held when it fires where he stands now (the same stack, the node he landed in, or the path
     * already at its sample) - set even if nothing was sneaking, so a boom before an etherwarp hands over a ready
     * sneak; and on a path-less route kept while he walks to it himself, if it was already held.</li>
     * <li>a WALK, UNSNEAK or USE_ITEM: let go now - the walk sprints, the unsneak says so, and a use must not be a
     * sneak-click.</li>
     * <li>anything else (boom, breaker, rotate, command): sneak does not change them, so a held sneak stays held
     * through them on a path-less route, ready for an etherwarp after; on a recorded route only if it fires in place
     * (the recording's own sneak flags drive the walk in between).</li>
     * <li>nothing left: let go; the route is about to complete.</li>
     * </ul>
     * Holding is just a held key: one input packet with shift down when it starts, nothing more until it ends.
     */
    private static void planSneak(RouteNode finished) {
        plannedNext = null;
        plannedInPlace = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || route == null || !running) {
            return;
        }
        if (finished != null && (finished.type == RouteNode.Type.WALK || finished.type == RouteNode.Type.UNSNEAK)) {
            return; // they let go themselves, and a walk's held sprint must not be turned into a sneak
        }
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        boolean pathless = route.path().isEmpty();
        RouteNode up = null;
        boolean inPlace = false;
        if (!stackQueue.isEmpty()) {
            up = stackQueue.peek();
            inPlace = true;
        } else {
            boolean teleported = finished != null && teleportPacketSeen
                    && (finished.type == RouteNode.Type.ETHERWARP || finished.type == RouteNode.Type.PATH
                    || (finished.type == RouteNode.Type.USE_ITEM && finished.hasLanding));
            if (pathless && teleported) {
                RouteNode landedIn = nodeLandedIn(player, cfg, finished);
                if (landedIn != null) {
                    up = route.stackOf(landedIn).get(0);
                    inPlace = true;
                }
            }
            if (up == null && nextNode < ordered.size()) {
                RouteNode n = ordered.get(nextNode);
                up = route.stackOf(n).get(0);
                inPlace = pathless
                        ? n.contains(RouteCoords.toReal(frame, n.relativePos()), cfg.getHeight(), player.getBoundingBox())
                        : n.pathIndex <= cursor;
            }
        }
        plannedNext = up;
        plannedInPlace = inPlace;
        boolean was = forceSneak;
        if (up == null) {
            forceSneak = false;
        } else {
            forceSneak = switch (up.type) {
                case ETHERWARP, PATH -> inPlace || (was && pathless);
                case WALK, UNSNEAK, USE_ITEM, CRYPT, BAT -> false;
                default -> was && (pathless || inPlace);
            };
        }
        if (forceSneak) {
            unsneakOverride = false;
        }
        wantSneak = forceSneak;
        if (forceSneak != was) {
            LOGGER.info("[AutoRoutes] Sneak {} after node #{}: next is {}{}", forceSneak ? "held" : "released",
                    finished == null ? "?" : String.valueOf(route.indexOf(finished) + 1),
                    up == null ? "nothing" : "#" + (route.indexOf(up) + 1) + " " + up.type,
                    up == null ? "" : inPlace ? " (fires where you stand)" : " (you walk to it)");
        }
    }

    private static void tickAction(Minecraft client, LocalPlayer player) {
        RouteNode node = activeNode;
        stepTicks++;
        actionAge++;
        if (!awaitPhaseDone) {
            // No sneak while it waits: he is collecting the secrets it waits for, and a sneaking right click with an
            // item in hand USES the item instead of opening the chest - with the etherwarp item the route just held,
            // that is a warp out of the room (found 2026-10-05, 96-ar-path). The sneak comes back with the action.
            wantSneak = false;
            // The node's own action (etherwarp, use, boom, ...) doesn't start until this clears - see
            // tickAwait, which is this gate for any node now that AWAIT isn't its own type any more. Once it
            // clears, the action starts in this same tick.
            tickAwait(client, player, node);
            boolean early = false;
            if (!awaitPhaseDone && activeNode == node && (takeSkipClick(client) || (early = takeEarlySkip()))) {
                // killer560, 2026-10-06: "if I left click while on an await node then it should perform the teleport
                // even if it didn't grab a secret yet". The click is consumed here, at START_CLIENT_TICK, so vanilla's
                // handleKeybinds later this tick never sees it: no startAttack, no swing, no dig - the node's own
                // action (the warp) is the only thing that click sends.
                LOGGER.info("[AutoRoutes] Node #{} {}: left click{} skipped the await ({}/{} secrets) after {} tick(s)",
                        route.indexOf(node) + 1, node.type, early ? " (made while the route warped you here)" : "",
                        AwaitEvents.secrets() + awaitRemembered.size(), Math.max(1, node.awaitAmount), actionAge);
                awaitPhaseDone = true;
                actionFromTick = AwaitEvents.now();
                step = Step.PREP;
                stepTicks = 0;
            }
            if (!awaitPhaseDone || activeNode != node) {
                return;
            }
            awaitDoneAge = actionAge;
            if (actionAge > 0) {
                LOGGER.info("[AutoRoutes] Node #{} {}: await held it {} tick(s)", route.indexOf(node) + 1, node.type,
                        actionAge);
            }
        }
        awaitHeld = false;
        switch (node.type) {
            case START, AWAIT -> {
                logActed(node, "");
                finishAction(); // a legacy AWAIT's wait already ran in tickAwait
            }
            case WALK -> {
                // The one node that ends a held etherwarp sneak (killer560: "It should only uncrouch if it hits a
                // walk node"). Cleared before the player's tick, so this tick's input packet carries shift up.
                boolean wasSneaking = forceSneak;
                forceSneak = false;
                wantSneak = false;
                // A recorded route's walking is the path's job, so there a walk node is just a marker. On a
                // path-less (/ar add) route it is the sprint.
                if (route.path().isEmpty()) {
                    walkHoldYaw = RouteCoords.toRealYaw(frame, node.yaw);
                    walkHoldLastPos = null;
                    walkHoldStallTicks = 0;
                }
                logActed(node, (route.path().isEmpty() ? " (sprint starts this tick)" : "")
                        + (wasSneaking ? " (released the held sneak)" : ""));
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
            case PATH -> tickPath(client, player, node);
            case USE_ITEM -> tickUseItem(client, player, node);
            case CRYPT, BAT -> tickCrypt(client, player, node);
            case BOOM -> tickBoom(client, player, node);
            case DUNGEON_BREAKER -> tickBreaker(client, player, node);
            case JUMP -> tickJump(player, node);
        }
    }

    /** Ticks a jump node waits for him to be on the ground before pressing anyway (a press in the air does nothing). */
    private static final int JUMP_GROUND_TIMEOUT = 20;
    /** Jump presses jump nodes have made since launch - read by the testkit (96-ar-662). */
    private static int jumpPresses;

    public static int jumpPresses() {
        return jumpPresses;
    }

    /** Jumps the active JUMP node has pressed so far; reset by {@link #beginAction}. */
    private static int jumpsDone;

    /**
     * A JUMP node (killer560, 2026-10-09; 2026-10-12: "That number is how many times it will jump"): {@link
     * RouteNode#jumpCount} presses of the jump key, each down for exactly one tick through the route's own input (the
     * input mixin installs {@link #drivenInput}, a discrete key press like every other route key) and let go the next.
     * Vanilla jumps on a tick the key is down and he is on the ground, so before each press it waits - pressing nothing -
     * until he is (after a jump, until he has landed), at most {@link #JUMP_GROUND_TIMEOUT} ticks per press. Done after
     * the last press is let go; the route carries on.
     */
    private static void tickJump(LocalPlayer player, RouteNode node) {
        int want = Math.max(RouteNode.MIN_JUMPS, Math.min(RouteNode.MAX_JUMPS, node.jumpCount));
        if (step == Step.PREP) {
            if (!player.onGround() && stepTicks <= JUMP_GROUND_TIMEOUT) {
                return;
            }
            wantJump = true;
            jumpPresses++;
            jumpsDone++;
            if (jumpsDone == 1) {
                logActed(node, " (jump 1 of " + want + ")" + (player.onGround() ? ""
                        : " (not on the ground after " + JUMP_GROUND_TIMEOUT + " ticks)"));
            } else {
                LOGGER.info("[AutoRoutes] Node #{} JUMP: jump {} of {}{}", route.indexOf(node) + 1, jumpsDone, want,
                        player.onGround() ? "" : " (not on the ground after " + JUMP_GROUND_TIMEOUT + " ticks)");
            }
            step = Step.DO;
            stepTicks = 0;
            return;
        }
        // The tick after a press: clearMovement already let the key go before this ran.
        wantJump = false;
        if (jumpsDone < want) {
            step = Step.PREP; // the next press waits for him to land
            stepTicks = 0;
            return;
        }
        jumpCooldownUntil.put(node, System.currentTimeMillis() + JUMP_COOLDOWN_MS);
        finishAction();
    }

    /**
     * killer560 (2026-10-14): "if i make a jump node with 1, then make it so it jumps once then times out for 3s before it
     * will trigger that node again. Right now it stops jumping but then falls right back onto the node." The jump itself
     * takes him out of the ring (0.1 high by default), and being in no node clears the latch and the finished flag, so
     * landing armed the node again. A finished JUMP node's tile does not arm for this long (real time), whatever the
     * latch says; the route it belongs to carries on at once.
     */
    static final long JUMP_COOLDOWN_MS = 3000;
    private static final java.util.Map<RouteNode, Long> jumpCooldownUntil = new java.util.IdentityHashMap<>();

    /** Milliseconds left on a jump cooldown covering {@code inside}'s tile (any finished jump node on it), or 0. */
    static long jumpCooldownLeft(RouteNode inside) {
        if (inside == null || jumpCooldownUntil.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long left = 0;
        java.util.Iterator<java.util.Map.Entry<RouteNode, Long>> it = jumpCooldownUntil.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<RouteNode, Long> e = it.next();
            long l = e.getValue() - now;
            if (l <= 0) {
                it.remove();
            } else if (e.getKey() == inside || e.getKey().sameTile(inside)) {
                left = Math.max(left, l);
            }
        }
        return left;
    }

    /** Read by the testkit (96-ar-jumpcd). */
    public static long jumpCooldownMs() {
        return JUMP_COOLDOWN_MS;
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
    /**
     * A left click of his since the last tick, taken away from vanilla: every queued attack click is consumed and the
     * attack key let go, so {@code handleKeybinds} (later this same tick, javap 26.1.2 and 26.2) neither calls
     * {@code startAttack} for the click nor {@code continueAttack(true)} for the held button - no swing, no
     * START_DESTROY_BLOCK. The key stays up until he presses it again. Never under a screen: a click there is the
     * screen's, not an attack. A mob under the crosshair makes no difference (killer560, 2026-10-06: his left click
     * always skips the waiting node).
     * <p>
     * Only HIS clicks: the attack mapping's click count is raised by {@code KeyMapping.click}, which only the mouse and
     * keyboard handlers call for real input. Nothing in this mod clicks or holds the attack mapping - Breaker Aura,
     * Secret Aura, Auto Clear and the route's own nodes call {@code MultiPlayerGameMode} directly - so none of their
     * attacks or digs can reach this.
     */
    private static boolean takeSkipClick(Minecraft client) {
        if (McCompat.screen(client) != null) {
            return false;
        }
        boolean clicked = false;
        while (client.options.keyAttack.consumeClick()) {
            clicked = true;
        }
        if (clicked) {
            client.options.keyAttack.setDown(false);
        }
        return clicked;
    }

    /**
     * The route is moving him by teleport from one node to the next, so a left click of his has nothing else to do:
     * an etherwarp or path node whose use went out (in flight, or flying its hops), or the settle / stack between two
     * nodes. Not between nodes of a path-less route he walks himself - there a left click may be at a mob.
     */
    private static boolean teleportingBetweenAwaits() {
        if (!running) {
            return false;
        }
        if (activeNode != null) {
            return awaitPhaseDone && nodeActed
                    && (activeNode.type == RouteNode.Type.ETHERWARP || activeNode.type == RouteNode.Type.PATH);
        }
        return settleTicks > 0 || !stackQueue.isEmpty() || chainAfterLanding;
    }

    /** A left click held by {@link #tick} while the route warped him here, at most {@link #EARLY_SKIP_TICKS} old:
     *  true once, and it is used up. */
    private static boolean takeEarlySkip() {
        if (execTicks - earlySkipClickAt > EARLY_SKIP_TICKS) {
            return false;
        }
        earlySkipClickAt = Long.MIN_VALUE / 2;
        return true;
    }

    private static void tickAwait(Minecraft client, LocalPlayer player, RouteNode node) {
        boolean delay = node.awaitCondition == RouteNode.AwaitCondition.DELAY;
        if (step == Step.PREP) {
            awaitStartMs = System.currentTimeMillis();
            step = Step.CONFIRM;
        }
        boolean done;
        if (delay) {
            done = System.currentTimeMillis() - awaitStartMs >= node.awaitAmount;
        } else {
            // Only what HE did since the previous node finished counts (AwaitEvents): his clicks on secret blocks, his
            // pickups, a secret bat appearing next to him, a mimic of his dying. Never a crypt - that is a crypt
            // node's own count. The room's "x/y Secrets" bar is not read: a teammate's secret raises it too.
            int have = AwaitEvents.secrets() + awaitRemembered.size();
            done = have >= Math.max(1, node.awaitAmount);
            if (done) {
                LOGGER.info("[AutoRoutes] Node #{} {}: await {}/{} secrets met{}", route.indexOf(node) + 1, node.type,
                        have, Math.max(1, node.awaitAmount), awaitRemembered.isEmpty() ? ""
                                : " (" + awaitRemembered.size() + " remembered from earlier this run)");
                java.util.Set<String> used = AwaitEvents.windowSecrets();
                for (RouteRunMemory.Got g : awaitRemembered) {
                    used.add(g.key());
                }
                RouteRunMemory.met(awaitNodeKey, used);
            }
        }
        if (done) {
            awaitPhaseDone = true;
            actionFromTick = AwaitEvents.now();
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
            warpYaw = RouteCoords.toRealYaw(frame, node.yaw);
            warpPitch = node.pitch;
            warpSavedYaw = warpYaw;
            warpSavedPitch = warpPitch;
            if (node.hasLanding) {
                warpLanding = RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ);
                warpTarget = landingBlock(warpLanding);
                // The recorded look is right from the node's own spot and nowhere else. Fired on the landing tick of
                // the warp before (a chain), he is a twentieth of a block above it - on a long, shallow warp that is a
                // block of landing (96-ar-rotate, 2026-10-06: 16 blocks at ~4.5 degrees landed one long). Walked or
                // jumped onto the node, he is anywhere in its ring, up to 0.8 off sideways and in the air: killer560's
                // 2026-10-07 Museum #10 armed at 0.6 off and 0.27 up, fired the recorded look and landed a block wide
                // of #11, and the route stood there. So off the spot, by any axis, aim from HERE at the same block
                // ({@link #reaimIfMoved}), with the aim the Interactive Map uses (verified to land on it), as a path
                // hop does; the recorded look when no such ray exists.
                warpSpot = RouteCoords.toReal(frame, node.relativePos());
                warpAimFrom = warpSpot;
                warpCentreTicks = 0;
                reaimIfMoved(player, "node #" + (route.indexOf(node) + 1));
            } else {
                warpLanding = null;
                warpTarget = null;
                warpAimFrom = null;
                warpSpot = null;
            }
        }
        if (tickWarp(client, player, node)) {
            finishAction();
            noteLanding(node);
        }
    }

    /** The block a landing stands on: feet are 1.05 over a full block's top (0.55 over a slab's), so half a block
     *  below the feet is inside it either way. */
    private static BlockPos landingBlock(Vec3 landing) {
        return BlockPos.containing(landing.x, landing.y - 0.5, landing.z);
    }

    /**
     * A PATH node (killer560, 2026-10-05). The first of a pair flies the warps saved on it ({@link RouteNode#pathHops}),
     * planning them first only when there are none or they are stale ({@link RoutePathPlanner}); the second of a pair
     * is the arrival and does nothing itself - landing in it fires it, and its own await and whatever is stacked on
     * its tile, through the ordinary landing re-fire.
     */
    private static void tickPath(Minecraft client, LocalPlayer player, RouteNode node) {
        if (hopIndex < 0) {
            if (!route.isPathSource(node)) {
                logActed(node, " (path arrival)");
                finishAction();
                return;
            }
            RouteNode dest = route.pathDestination(node);
            if (dest == null) {
                AutoRoutesFeature.chatBad("Path #" + (route.indexOf(node) + 1)
                        + " has no later path node to go to - add one with /ar add path.");
                logActed(node, " (no destination path node)");
                finishAction();
                return;
            }
            wantSneak = forceSneak;
            if (RoutePathPlanner.isProving(node)) {
                // Path nodes only ever fly a route proved the fewest (killer560, 2026-10-11): a proof still running in
                // the background holds the pair here - any key of his still stops the route as usual.
                if (!provingSaid) {
                    provingSaid = true;
                    AutoRoutesFeature.chat(ModChat.text("Path #" + (route.indexOf(node) + 1)),
                            ModChat.dim(" waits for its proof (fewest warps) to finish..."));
                    LOGGER.info("[AutoRoutes] Node #{} PATH: holding until its background proof is in",
                            route.indexOf(node) + 1);
                }
                stepTicks = 0;
                return;
            }
            provingSaid = false;
            if (!RoutePathPlanner.valid(route, node)) {
                if (!planAsked) {
                    planAsked = true;
                    planOutcome = null;
                    stepTicks = 0;
                    LOGGER.info("[AutoRoutes] Node #{} PATH: no saved warps for this pair - planning them once",
                            route.indexOf(node) + 1);
                    RoutePathPlanner.plan(route, frame, node, why -> planOutcome = why == null ? "ok" : why);
                }
                String outcome = planOutcome;
                if (outcome == null) {
                    if (stepTicks > PLAN_TIMEOUT) {
                        stop("the path planner did not answer");
                    }
                    return;
                }
                if (!"ok".equals(outcome) || !RoutePathPlanner.valid(route, node)) {
                    stop("no etherwarp path from #" + (route.indexOf(node) + 1) + " to #" + (route.indexOf(dest) + 1)
                            + " (" + outcome + ")");
                    return;
                }
            }
            if (node.planBreakers != RoutePathPlanner.breakerStamp(route, node) && staleSaid != node) {
                // His choice (2026-10-11): re-planning after pick changes is manual. Say so, once a pair.
                staleSaid = node;
                AutoRoutesFeature.chatBad("Path #" + (route.indexOf(node) + 1) + " -> #" + (route.indexOf(dest) + 1)
                        + " was planned before its breaker blocks changed - /ar replan to plan it with them.");
            }
            LOGGER.info("[AutoRoutes] Node #{} PATH: flying {} saved warp(s) to #{}", route.indexOf(node) + 1,
                    node.pathHops.size(), route.indexOf(dest) + 1);
            loadHop(node, 0);
        }
        if (!tickWarp(client, player, node)) {
            return;
        }
        if (hopIndex + 1 < node.pathHops.size()) {
            // The next hop goes on THIS tick (killer560, 2026-10-06: "in theory I should be able to teleport 20 times a
            // second if there is no waiting"): tickWarp only returns true once the server's position packet has put him
            // on the landing, so the next warp already aims from where the server has him. It used to sit out the
            // interact delay first - 2 ticks per hop more than the landing itself.
            loadHop(node, hopIndex + 1);
            tickPath(client, player, node);
            return;
        }
        LOGGER.info("[AutoRoutes] Node #{} PATH: all {} warp(s) landed", route.indexOf(node) + 1, node.pathHops.size());
        finishAction();
        noteLanding(node);
    }

    /** Points the warp step at saved hop {@code i} of {@code node}. */
    private static void loadHop(RouteNode node, int i) {
        hopIndex = i;
        RouteNode.PathHop h = node.pathHops.get(i);
        warpYaw = RouteCoords.toRealYaw(frame, h.yaw());
        warpPitch = h.pitch();
        warpLanding = RouteCoords.toReal(frame, h.lx(), h.ly(), h.lz());
        warpTarget = RouteCoords.toRealBlock(frame, h.target());
        step = Step.PREP;
        stepTicks = 0;
        blockWaitTicks = 0;
        blockWaitSaid = false;
        pickWaitBlock = null;
        // Not standing where the plan stood (the first hop, fired from anywhere in the node's ring): aim from HERE at
        // the same block, with the aim the Interactive Map itself uses, if that ray really lands on it.
        LocalPlayer player = Minecraft.getInstance().player;
        warpSpot = RouteCoords.toReal(frame, h.ox(), h.oy(), h.oz());
        warpAimFrom = warpSpot;
        warpSavedYaw = warpYaw;
        warpSavedPitch = warpPitch;
        warpCentreTicks = 0;
        if (player != null) {
            reaimIfMoved(player, "path hop " + (i + 1));
        }
    }

    /** {@link #reaimIfMoved}: the look is right from where he stands (unchanged). */
    private static final int AIM_OK = 0;
    /** {@link #reaimIfMoved}: he moved and the look was worked out again from here. */
    private static final int AIM_CHANGED = 1;
    /** {@link #reaimIfMoved}: he is off the look's spot and no ray from here lands on the block. */
    private static final int AIM_NO_RAY = 2;

    /**
     * The warp's look is worked out for {@link #warpAimFrom}. If he is not there - more than a hundredth of a block off
     * by any axis - it is worked out again from where he is, at {@link #warpTarget}, with the Interactive Map's aim
     * (verified to land on that block from this eye).
     * <p>
     * Called when the warp is set up AND again on the tick the use goes out: the use is sent at the start of a tick,
     * so the server's position for it is exactly the one the client has now (the last movement packet's). Between the
     * two he may still be moving - a node armed mid-jump or mid-walk drifts a few tenths in the tick before the
     * sneak reaches the server - and an aim from the earlier spot misses the block from the later one.
     *
     * @return {@link #AIM_OK}, {@link #AIM_CHANGED}, or {@link #AIM_NO_RAY} (the look is left as it was)
     */
    private static int reaimIfMoved(LocalPlayer player, String what) {
        if (warpTarget == null || warpAimFrom == null) {
            return AIM_OK;
        }
        Vec3 at = player.position();
        if (at.distanceTo(warpAimFrom) <= REAIM_EPSILON) {
            return AIM_OK;
        }
        Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
        TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, warpTarget,
                com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange() + 1.0);
        if (rot == null) {
            return AIM_NO_RAY;
        }
        LOGGER.info("[AutoRoutes] Etherwarp ({}): re-aimed from {} ({} off {}) at {}: yaw {} pitch {}", what, fmt(at),
                String.format(Locale.US, "%.2f", at.distanceTo(warpAimFrom)), fmt(warpAimFrom), warpTarget.toShortString(),
                String.format(Locale.US, "%.2f", rot.yaw()), String.format(Locale.US, "%.2f", rot.pitch()));
        warpAimFrom = at;
        warpYaw = rot.yaw();
        warpPitch = rot.pitch();
        return AIM_CHANGED;
    }

    /**
     * No ray from where he stands lands on the warp's block. The ring reaches up to 0.8 either side of the node, and a
     * pillar or a slab edge can hide the block from part of it (Museum #10 from 0.45/0.45 off, 96-ar-398-offnode); the
     * saved look from there lands a block or more wide, which is what stranded killer560's 2026-10-07 run. So he steps
     * back onto the spot the look belongs to, by discrete key presses with the sneak held (slow and precise), and the
     * warp goes from there with that look. After {@link #CENTRE_TIMEOUT} ticks it goes with the saved look regardless.
     *
     * @return true while still stepping (nothing is sent this tick)
     */
    private static boolean stepOntoWarpSpot(LocalPlayer player, String what) {
        Vec3 pos = player.position();
        double dx = warpSpot.x - pos.x;
        double dz = warpSpot.z - pos.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        Vec3 v = player.getDeltaMovement();
        boolean settled = h <= CENTRE_TOLERANCE && player.onGround() && v.x * v.x + v.z * v.z < 0.0004;
        if (settled || warpCentreTicks > CENTRE_TIMEOUT) {
            clearMovement();
            LOGGER.info("[AutoRoutes] Etherwarp ({}): {} - firing the saved look from {}", what, settled
                    ? String.format(Locale.US, "back on its spot (%.2f off) after %d tick(s)", h, warpCentreTicks)
                    : String.format(Locale.US, "still %.2f off its spot after %d tick(s)", h, warpCentreTicks), fmt(pos));
            warpYaw = warpSavedYaw;
            warpPitch = warpSavedPitch;
            warpAimFrom = pos;
            return false;
        }
        if (warpCentreTicks == 0) {
            LOGGER.info("[AutoRoutes] Etherwarp ({}): no ray from {} lands on {} - stepping back onto its spot {} first",
                    what, fmt(pos), warpTarget.toShortString(), fmt(warpSpot));
        }
        warpCentreTicks++;
        wantForward = wantBackward = wantLeft = wantRight = false;
        if (h > CENTRE_TOLERANCE / 2) {
            double yr = Math.toRadians(player.getYRot());
            double fwd = (dx * -Math.sin(yr) + dz * Math.cos(yr)) / h;
            double lft = (dx * Math.cos(yr) + dz * Math.sin(yr)) / h;
            wantForward = fwd > 0.38;
            wantBackward = fwd < -0.38;
            wantLeft = lft > 0.38;
            wantRight = lft < -0.38;
        }
        wantJump = false;
        wantSprint = false;
        wantSneak = true;
        return true;
    }

    /** How close (blocks, horizontally) to a warp's spot counts as back on it when stepping there. */
    private static final double CENTRE_TOLERANCE = 0.12;
    /** Ticks to spend stepping back onto a warp's spot before firing the saved look regardless. */
    private static final int CENTRE_TIMEOUT = 40;
    /** The warp's saved look in world terms (a node's recorded one, a hop's planned one), for {@link #stepOntoWarpSpot}. */
    private static float warpSavedYaw;
    private static float warpSavedPitch;

    /** How far (blocks) from the spot a warp's look belongs to still counts as standing on it. */
    private static final double REAIM_EPSILON = 0.01;

    /**
     * One etherwarp, from {@link #warpYaw}/{@link #warpPitch} onto {@link #warpLanding}: an ew node's own, or one hop
     * of a path. Fire tick: slot, sneak and aim are all asked for at once. The use goes out as soon as the server has
     * the sneak - this very tick when the last input packet already carried shift, otherwise the next tick, after this
     * tick's input packet has carried it. A swap costs no tick: the held-item packet is sent before either.
     * <p>
     * If the block it lands on is not there (killer560, 2026-10-05: "let's say it needs to etherwarp to a block that
     * isn't there then have it wait till the block is back") nothing is sent: the route holds, sneak still down, says
     * so once in chat, and warps the tick the block is back - or stops after {@link #BLOCK_WAIT_TIMEOUT}.
     *
     * @return true on the tick the server-confirmed landing is seen
     */
    private static boolean tickWarp(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            warpPacedTicks = 0;
            warpTurnFirstTicks = 0;
            int slot = ItemIdentity.findEtherwarpSlot(player);
            if (slot < 0) {
                LOGGER.info("[AutoRoutes] Etherwarp: no hotbar item with ethermerge / ETHERWARP_CONDUIT");
                stop("no etherwarp item in the hotbar");
                return false;
            }
            select(client, player, slot);
            forceSneak = true;
            unsneakOverride = false;
            wantSneak = true;
            aimAt(warpYaw, warpPitch, node);
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            wantSneak = true;
            // A right click on a block may be waiting on the server for its second half (util/RightClick): the server
            // must hear this warp's look before the use, or the use is taken as that click's end and nothing happens.
            // Turned here, while the sneak is still on its way to the server, it costs no tick.
            if (!AutoRoutesConfig.getInstance().isLegitMode()
                    && com.killer560.hub.util.RightClick.mustTurnFirst(player, warpYaw, warpPitch, "[AutoRoutes] Etherwarp")) {
                turnBody(player, player.getYRot() + Mth.wrapDegrees(warpYaw - player.getYRot()),
                        Mth.clamp(warpPitch, -90f, 90f));
            }
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
                return false;
            }
            if (sneakReadyAge < 0) {
                sneakReadyAge = actionAge;
            }
            if (!landingBlockThere(client)) {
                return false;
            }
            if (pickStillStanding(client, player, node)) {
                return false;
            }
            if (!warpAimReady(player)) {
                return false;
            }
            // Etherwarps Per Second: not before the pace allows. Sneak stays held while it waits.
            if (execTicks < Math.ceil(nextWarpAt - 1e-9)) {
                warpPacedTicks++;
                return false;
            }
            // The use goes out now, from where the server has him now: aim from here if he moved since the aim was
            // worked out. Obvious mode puts the look in the use packet itself, so it simply goes; legit mode turns the
            // camera to it first (aimReady's settle, or its timeout).
            String what = hopIndex >= 0 ? "path hop " + (hopIndex + 1) : "node #" + (route.indexOf(node) + 1);
            int aim = reaimIfMoved(player, what);
            if (aim == AIM_NO_RAY && warpSpot != null && stepOntoWarpSpot(player, what)) {
                return false;
            }
            if (aim != AIM_OK && AutoRoutesConfig.getInstance().isLegitMode()) {
                aimAt(warpYaw, warpPitch, node);
                return false;
            }
            // Every warp sent a tick after a chest or lever click met its await got no landing on Hypixel (2026-10-14):
            // the server traced the use along the look Secret Aura clicked the chest with, met the chest, and took the
            // use as the click's second half (util/RightClick). So when a block click is unpaired the server hears this
            // look first - turned on the firing tick above when the sneak was not yet out, which costs nothing; only a
            // warp that would otherwise go on the click's own tick waits, one tick.
            if (turnFirst(player, warpYaw, warpPitch, "[AutoRoutes] Etherwarp")) {
                warpTurnFirstTicks++;
                return false;
            }
            if (hopIndex < 0 && newBlockInLine(client, player, node)) {
                return false;
            }
            notePacedWarp();
            actionOrigin = player.position();
            teleportPacketSeen = false;
            useHeldItem(client, player, warpYaw, warpPitch, false);
            logActed(node, (hopIndex >= 0 ? " (path warp " + (hopIndex + 1) + "/" + node.pathHops.size() + ")" : "")
                    + (warpPacedTicks > 0 ? " (paced " + warpPacedTicks + " tick(s) by Etherwarps Per Second)" : "")
                    + (warpTurnFirstTicks > 0 ? " (waited " + warpTurnFirstTicks + " tick(s) for the server to hear its "
                    + "look - a block click was unpaired)" : "")
                    + " (sneak " + (hopIndex > 0 ? "held from the last warp" : sneakReadyAge == awaitDoneAge ? "already held"
                    : "went out in the firing tick's input packet") + ", "
                    + ItemIdentity.skyblockId(player.getMainHandItem()) + ")");
            step = Step.CONFIRM;
            stepTicks = 0;
            cameraGraceTicks = LANDING_TIMEOUT + 5;
            return false;
        }
        if (step == Step.CONFIRM) {
            wantSneak = true;
            // A block click is unpaired on the server (util/RightClick): while this hop is in flight the body already turns
            // to the NEXT hop's look - its use has gone and its own look was reported last tick - so the server has heard
            // that look by the time he lands and the next hop goes on the landing tick, not one later.
            if (stepTicks >= 1 && hopIndex >= 0 && hopIndex + 1 < node.pathHops.size()
                    && !AutoRoutesConfig.getInstance().isLegitMode() && com.killer560.hub.util.RightClick.unpaired()) {
                RouteNode.PathHop next = node.pathHops.get(hopIndex + 1);
                float nextYaw = RouteCoords.toRealYaw(frame, next.yaw());
                if (!com.killer560.hub.util.RightClick.heard(player, nextYaw, next.pitch())) {
                    turnBody(player, player.getYRot() + Mth.wrapDegrees(nextYaw - player.getYRot()),
                            Mth.clamp(next.pitch(), -90f, 90f));
                }
            }
            if (landed(player, warpLanding)) {
                LOGGER.info("[AutoRoutes] Etherwarp: landed at {} {} tick(s) after the use ({} from firing)",
                        fmt(player.position()), stepTicks, actionAge);
                landingConfirmed = teleportPacketSeen;
                // Whether the sneak stays held for what comes next is planSneak's call, from finishAction.
                RouteRotation.rebase();
                cameraGraceTicks = 3;
                rejoinPathAfterTeleport(player, node);
                return true;
            } else if (stepTicks > LANDING_TIMEOUT) {
                LOGGER.info("[AutoRoutes] Etherwarp: no landing after {} ticks - player {} (moved {} from {}), "
                        + "recorded landing {}", stepTicks, fmt(player.position()),
                        String.format(Locale.US, "%.2f", actionOrigin == null ? 0.0 : player.position().distanceTo(actionOrigin)),
                        fmt(actionOrigin), fmt(warpLanding));
                // Not a stop (killer560's correction rule, 2026-10-06, applied here at the coordinator's request: the
                // server not putting him on the landing is treated like a correction): chat line + alarm, the node is
                // let go uncounted, and the route carries on - in its ring still, it fires again from where he is.
                ServerCorrections.reportEvent("Auto Routes", String.format(Locale.US, "etherwarp #%d did not land where"
                        + " it was recorded (%.1f blocks from the landing after %d ticks) - carrying on from here",
                        route.indexOf(node) + 1, warpLanding == null ? 0.0 : player.position().distanceTo(warpLanding),
                        stepTicks));
                letGoAfterCorrection(client, player);
            }
        }
        return false;
    }

    /** Ticks an etherwarp node has waited on a block standing in its line ({@link #newBlockInLine}); 0 when not. */
    private static int lineWaitTicks;
    /** The block it waits on, said once in chat per block. */
    private static BlockPos lineWaitBlock;
    /** Ticks spent waiting on a new block in an etherwarp's line since launch - read by the testkit (96-ar-659). */
    private static int lineWaitTicksTotal;

    public static int lineWaitTicksTotal() {
        return lineWaitTicksTotal;
    }

    /**
     * killer560, 2026-10-09: "if it needs to etherwarp somewhere but a block is in the way, have it just wait until that
     * block moves, if it is part of a route where the block wasn't there when the node was placed." The shot about to go
     * - this eye, sneaking, along {@link #warpYaw}/{@link #warpPitch} - is traced as the warp traces it
     * ({@code TeleportUtils.getEtherPos}). When the first block it meets is not the landing block, and that block stands on
     * the line the landing was seen along when the node was PLACED ({@link RouteNode#hasPlaceEye}: that line was clear
     * then, by construction), it is a block that was not there: nothing is sent this tick - no use, no stop, sneak held -
     * and the same test runs again next tick, so the warp goes on the first tick the block is gone. A block in the shot
     * that is NOT on the placed line (it was there, or the shot was re-aimed elsewhere) changes nothing: the warp goes as
     * it always did. A node saved before {@code placeEye} existed never waits.
     *
     * @return true while waiting (send nothing)
     */
    private static boolean newBlockInLine(Minecraft client, LocalPlayer player, RouteNode node) {
        if (node.type != RouteNode.Type.ETHERWARP || !node.hasPlaceEye || warpTarget == null || client.level == null) {
            return false;
        }
        Vec3 eye = new Vec3(player.getX(), player.getY() + TeleportUtils.eyeHeight(true), player.getZ());
        double range = Math.max(1.0, com.killer560.hub.pathfinding.EtherwarpHopper.range());
        TeleportUtils.RaycastResult shot = TeleportUtils.getEtherPos(eye, warpYaw, warpPitch, range);
        BlockPos in = shot.pos();
        boolean blocked = false;
        if (in != null && !in.equals(warpTarget)) {
            // The placed line: the recorded eye, along the recorded look, up to where it entered the landing block.
            Vec3 placeEye = RouteCoords.toReal(frame, node.placeEyeX, node.placeEyeY, node.placeEyeZ);
            Vec3 far = placeEye.add(TeleportUtils.getLook(RouteCoords.toRealYaw(frame, node.yaw), node.pitch).scale(range));
            AABB targetBox = new AABB(warpTarget.getX(), warpTarget.getY(), warpTarget.getZ(), warpTarget.getX() + 1,
                    warpTarget.getY() + 1, warpTarget.getZ() + 1);
            Vec3 end = targetBox.clip(placeEye, far).orElse(Vec3.atCenterOf(warpTarget));
            AABB inBox = new AABB(in.getX(), in.getY(), in.getZ(), in.getX() + 1, in.getY() + 1, in.getZ() + 1);
            blocked = inBox.clip(placeEye, end).isPresent() || inBox.contains(placeEye);
        }
        if (!blocked) {
            if (lineWaitTicks > 0) {
                LOGGER.info("[AutoRoutes] Etherwarp: the line to {} is clear again after {} tick(s) - warping",
                        warpTarget.toShortString(), lineWaitTicks);
            }
            lineWaitTicks = 0;
            lineWaitBlock = null;
            return false;
        }
        lineWaitTicks++;
        lineWaitTicksTotal++;
        if (!in.equals(lineWaitBlock)) {
            lineWaitBlock = in.immutable();
            LOGGER.info("[AutoRoutes] Etherwarp node #{}: {} ({}) stands in the line to {} and was not there when the node "
                    + "was placed - waiting for it to move", route.indexOf(node) + 1, in.toShortString(),
                    client.level.getBlockState(in), warpTarget.toShortString());
            AutoRoutesFeature.chat(ModChat.text("Waiting for the block at "), ModChat.value(in.toShortString()),
                    ModChat.text(" to move - it is in etherwarp #" + (route.indexOf(node) + 1) + "'s way."));
        }
        return true;
    }

    /** The Breaker Aura pick a path hop is waiting on, said once in chat per block; null when not waiting. */
    private static BlockPos pickWaitBlock;
    /** Ticks path hops have waited on a standing pick since launch - read by the testkit (637). */
    private static int pickWaitTicksTotal;

    public static int pickWaitTicksTotal() {
        return pickWaitTicksTotal;
    }

    /**
     * killer560 (2026-10-11): "Have it wait on the path." A path pair is planned with Breaker Aura's picks as already
     * broken (RoutePathPlanner), so a hop can go THROUGH one, or land under one. While such a pick still stands - in
     * the shot's line before it reaches the landing block, or in the landing's two blocks of standing room - nothing is
     * sent: no use, sneak held, the same test again next tick, so the warp goes on the first tick the block is gone. No
     * time limit; any key of his or /ar stop ends it as it ends any route. Chat names the block once. Only path hops,
     * and only while the shot would not land as planned: a pick that stands somewhere this hop does not need changes
     * nothing.
     *
     * @return true while waiting (send nothing)
     */
    private static boolean pickStillStanding(Minecraft client, LocalPlayer player, RouteNode node) {
        BlockPos target = warpTarget;
        if (hopIndex < 0 || target == null || client.level == null) {
            return false;
        }
        Vec3 eye = new Vec3(player.getX(), player.getY() + TeleportUtils.eyeHeight(true), player.getZ());
        double range = Math.max(1.0, com.killer560.hub.pathfinding.EtherwarpHopper.range());
        TeleportUtils.RaycastResult shot = TeleportUtils.getEtherPos(eye, warpYaw, warpPitch, range);
        BlockPos waitOn = null;
        if (!(shot.succeeded() && target.equals(shot.pos()))) {
            Vec3 far = eye.add(TeleportUtils.getLook(warpYaw, warpPitch).scale(range));
            AABB targetBox = new AABB(target.getX(), target.getY(), target.getZ(), target.getX() + 1, target.getY() + 1,
                    target.getZ() + 1);
            Vec3 end = targetBox.clip(eye, far).orElse(Vec3.atCenterOf(target));
            for (BlockPos p : RoutePathPlanner.breakerBlocksBefore(route, frame, node)) {
                if (client.level.getBlockState(p).isAir()) {
                    continue;   // already broken
                }
                AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
                boolean inLine = box.clip(eye, end).isPresent() || box.contains(eye);
                boolean overLanding = p.equals(target.above()) || p.equals(target.above(2));
                if (inLine || overLanding) {
                    waitOn = p;
                    break;
                }
            }
        }
        if (waitOn == null) {
            if (pickWaitBlock != null) {
                LOGGER.info("[AutoRoutes] Path hop {}: the breaker block at {} is gone - warping", hopIndex + 1,
                        pickWaitBlock.toShortString());
            }
            pickWaitBlock = null;
            return false;
        }
        pickWaitTicksTotal++;
        if (!waitOn.equals(pickWaitBlock)) {
            pickWaitBlock = waitOn.immutable();
            LOGGER.info("[AutoRoutes] Path hop {}: waiting for the breaker block at {} ({}) - the warp to {} goes"
                    + " through it", hopIndex + 1, waitOn.toShortString(), client.level.getBlockState(waitOn),
                    target.toShortString());
            AutoRoutesFeature.chat(ModChat.text("Waiting for the breaker block at "), ModChat.value(
                    waitOn.toShortString()), ModChat.text(" to go - path warp " + (hopIndex + 1) + " goes through it."));
        }
        return true;
    }

    /**
     * Whether the block the warp lands on is there, from the client's own world. A warp with no known landing, or one
     * whose block is in a chunk the client does not have, is not held back. Says so in chat once per wait.
     */
    private static boolean landingBlockThere(Minecraft client) {
        BlockPos target = warpTarget;
        if (target == null || client.level == null) {
            return true;
        }
        int y = Math.max(client.level.getMinY(), Math.min(client.level.getMaxY(), target.getY()));
        // "Not there" is the block itself having nothing to stand on (air, a broken or not-yet-placed block) - not the
        // stricter etherwarpable(), whose standing-room test could disagree with the landing the node was saved with
        // and hold a good warp forever.
        if (!client.level.isLoaded(new BlockPos(target.getX(), y, target.getZ()))
                || !client.level.getBlockState(target).getCollisionShape(client.level, target).isEmpty()) {
            if (blockWaitTicks > 0) {
                LOGGER.info("[AutoRoutes] Etherwarp: landing block {} is back after {} tick(s) - warping",
                        target.toShortString(), blockWaitTicks);
            }
            blockWaitTicks = 0;
            blockWaitSaid = false;
            return true;
        }
        blockWaitTicks++;
        if (!blockWaitSaid) {
            blockWaitSaid = true;
            LOGGER.info("[AutoRoutes] Etherwarp: landing block {} is not there ({}) - waiting for it",
                    target.toShortString(), client.level.getBlockState(target));
            AutoRoutesFeature.chat(ModChat.text("Waiting for the block at "), ModChat.value(target.toShortString()),
                    ModChat.text(" - the etherwarp lands there."));
        }
        if (blockWaitTicks > BLOCK_WAIT_TIMEOUT) {
            stop("the etherwarp's landing block at " + target.toShortString() + " never came back");
        }
        return false;
    }

    private static void tickUseItem(Minecraft client, LocalPlayer player, RouteNode node) {
        if (step == Step.PREP) {
            if (node.item == null) {
                // No item: "click the aimed block by hand" - a chest, a lever, a skull. An empty slot if there is one,
                // so the click is the empty-hand click that was recorded; otherwise whatever is held.
                int empty = ItemIdentity.findEmptyHotbarSlot(player);
                if (empty >= 0) {
                    select(client, player, empty);
                } else {
                    LOGGER.info("[AutoRoutes] Node #{} USE_ITEM (empty hand): no empty hotbar slot - clicking with slot {}"
                            + " held", route.indexOf(node) + 1, player.getInventory().getSelectedSlot() + 1);
                }
            } else {
                int slot = ItemIdentity.findHotbarSlot(player, node.item);
                if (slot < 0) {
                    stop(node.item + " is not in the hotbar");
                    return;
                }
                select(client, player, slot);
            }
            // A held etherwarp sneak lets go here: sneaking changes what a right click does (an AOTV etherwarps
            // instead of transmitting, a chest or lever is not opened with an item in hand).
            forceSneak = false;
            wantSneak = false;
            aimAt(node);
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            // The mirror of the etherwarp's rule: the use goes out once the last input packet said shift UP. planSneak
            // releases before a use node, so this only waits when one was reached still sneaking (no settle ticks).
            if (player.getLastSentInput().shift() && stepTicks <= SNEAK_TIMEOUT) {
                return;
            }
            if (!aimReady()) {
                return;
            }
            if (node.item == null) {
                clickBlockByHand(client, player, node);
                return;
            }
            // A block click may be unpaired on the server (util/RightClick): the use's look reaches it first.
            if (turnFirst(player, RouteCoords.toRealYaw(frame, node.yaw), node.pitch, "[AutoRoutes] Use node")) {
                return;
            }
            actionOrigin = player.position();
            teleportPacketSeen = false;
            useHeldItem(client, player, RouteCoords.toRealYaw(frame, node.yaw), node.pitch, true);
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
            if (landed(player, node.hasLanding ? RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ)
                    : null)) {
                LOGGER.info("[AutoRoutes] Use: landed {} tick(s) after the use", stepTicks);
                landingConfirmed = teleportPacketSeen;
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

    /** Kill Mimic may take the hands now: between nodes, or while a node only waits on its await. */
    static boolean mayYieldToMimic() {
        return !running || activeNode == null || !awaitPhaseDone;
    }

    /**
     * A CRYPT node (killer560, 2026-10-05: "it will do the same attacking thing till either a prince or crypt are
     * killed"): the Crypt Weapon setting's item, aimed straight down (it explodes, never teleports), with the use key HELD
     * (vanilla then uses it every 4 ticks, as for a held right click - {@link #holdUse}) until {@code await:N} (1 without
     * an await) crypt / prince kills of his have
     * been counted since the node before it finished ({@link AwaitEvents#crypts}) - never a secret. Crypt Attack Time
     * without a kill moves the route on to the next node.
     */
    private static void tickCrypt(Minecraft client, LocalPlayer player, RouteNode node) {
        int goal = node.awaitEnabled && node.awaitCondition != RouteNode.AwaitCondition.DELAY
                ? Math.max(1, node.awaitAmount) : 1;
        // A BAT node is this same node for secret bats (killer560, 2026-10-13: "just like crypts ... but is for bats"):
        // the Bat Weapon setting, Bat Attack Time, and bat kills of his (AwaitEvents#bats) instead of crypt kills.
        boolean bat = node.type == RouteNode.Type.BAT;
        String kind = bat ? "bat" : "crypt";
        String KIND = bat ? "BAT" : "CRYPT";
        AutoRoutesConfig.CryptWeapon weapon = bat ? AutoRoutesConfig.getInstance().getBatWeapon()
                : AutoRoutesConfig.getInstance().getCryptWeapon();
        if (step == Step.PREP && bat && RouteRunMemory.metThisRun(awaitNodeKey)) {
            LOGGER.info("[AutoRoutes] Node #{} BAT: its bat was already killed earlier this run - not attacking",
                    route.indexOf(node) + 1);
            finishAction();
            return;
        }
        if (step == Step.PREP) {
            int slot = ItemIdentity.findHotbarSlot(player, weapon.itemId());
            if (slot < 0) {
                stop("the " + kind + " node has no " + weapon.label() + " in the hotbar (" + (bat ? "Bat" : "Crypt")
                        + " Weapon setting)");
                return;
            }
            cryptSlotBefore = player.getInventory().getSelectedSlot();
            select(client, player, slot);
            forceSneak = false;
            wantSneak = false;
            // STRAIGHT DOWN, always (killer560, 2026-10-05: "looking straight down so that way it doesn't teleport
            // and just explodes"; 2026-10-11: "those nodes shouldn't matter my facing angle they should always be
            // straight down"). Pitch exactly 90.0 whatever the node recorded or the camera reads; HIS yaw, so nothing
            // turns sideways (the node's recorded yaw used to be turned to).
            aimAt(player.getYRot(), MimicKiller.STRAIGHT_DOWN, node);
            cryptDownTicks = 0;
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            if (player.getLastSentInput().shift() && stepTicks <= SNEAK_TIMEOUT) {
                return;
            }
            if (AutoRoutesConfig.getInstance().isLegitMode() && player.getXRot() != MimicKiller.STRAIGHT_DOWN) {
                // Legit: the camera must read exactly 90.0 before the first use - aimReady's timeout would fire it at
                // whatever it reads. Past CRYPT_AIM_GIVE_UP it moves on with nothing used.
                if (stepTicks > CRYPT_AIM_GIVE_UP) {
                    AutoRoutesFeature.chatBad((bat ? "Bat" : "Crypt") + " node: could not aim straight down - nothing used, moving on.");
                    LOGGER.info("[AutoRoutes] Node #{} {}: gave up aiming down (pitch {} after {} tick(s))",
                            route.indexOf(node) + 1, KIND, Float.toString(player.getXRot()), stepTicks);
                    restoreCryptSlot(client, player);
                    finishAction();
                    return;
                }
                if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                    RouteRotation.beginApproach(player.getYRot(), MimicKiller.STRAIGHT_DOWN, false, 0f, 0f);
                }
                return;
            }
            if (!aimReady()) {
                return;
            }
            // THE SERVER MUST ALREADY HAVE 90.0. A held use on the floor is a use-on-block packet, which carries no
            // rotation: the server uses the one the last movement packet reported, and vanilla sends this tick's use
            // BEFORE this tick's movement packet. So the body (obvious) or camera (legit) has to have read 90.0 at the
            // end of a previous tick first - else the first use went out at the look he armed with (30.0 in 96-ar-653,
            // 89.85-89.96 in legit).
            if (!AutoRoutesConfig.getInstance().isLegitMode()) {
                turnBody(player, player.getYRot(), MimicKiller.STRAIGHT_DOWN);
            }
            if (player.getXRot() == MimicKiller.STRAIGHT_DOWN) {
                cryptDownTicks++;
            } else {
                cryptDownTicks = 0;
            }
            if (cryptDownTicks < 2) {
                return;
            }
            // HOLD the use key, as a player holding right click does (killer560, 2026-10-06: "only have the crypt node
            // hold right click for its equivalent of attacking do not have it spam click"). Vanilla's handleKeybinds
            // runs later in this same tick and turns a held use key into startUseItem whenever its rightClickDelay is
            // 0 - so the first use goes on the firing tick and then one every 4 ticks, packet for packet what a held
            // right click sends (javap, 26.1.2 and 26.2: keyUse.isDown && rightClickDelay == 0 && !isUsingItem).
            // Under the Interactive Map (Run While Map Open) handleKeybinds does not run; HeldUsePickMixin takes
            // that step instead (heldUseUnderScreen).
            holdUse(client, player, node);
            logActed(node, " (" + weapon.label() + ", holding use until " + goal + (bat ? " bat kill(s))"
                    : " crypt/prince kill(s))"));
            step = Step.CONFIRM;
            stepTicks = 0;
            return;
        }
        if (step == Step.CONFIRM) {
            if (takeSkipClick(client)) {
                // His left click skips the wait for kills too (killer560, 2026-10-06), consumed like the await's.
                LOGGER.info("[AutoRoutes] Node #{} {}: left click skipped the kill wait ({} of {} kill(s)) after "
                        + "holding use {} tick(s)", route.indexOf(node) + 1, KIND, kills(bat), goal, stepTicks);
                releaseUse(client);
                restoreCryptSlot(client, player);
                finishAction();
                return;
            }
            if (kills(bat) >= goal) {
                LOGGER.info("[AutoRoutes] Node #{} {}: {} kill(s) after holding use {} tick(s)", route.indexOf(node) + 1,
                        KIND, kills(bat), stepTicks);
                if (bat) {
                    RouteRunMemory.met(awaitNodeKey, List.of());
                }
                releaseUse(client);
                restoreCryptSlot(client, player);
                finishAction();
                return;
            }
            int attackTicks = bat ? AutoRoutesConfig.getInstance().getBatAttackTicks()
                    : AutoRoutesConfig.getInstance().getCryptAttackTicks();
            if (stepTicks > attackTicks) {
                // Moves ON rather than stopping the route (Crypt Attack Time slider): an undead that walked out of
                // reach must not strand the rest of the route.
                LOGGER.info("[AutoRoutes] Node #{} {}: {} of {} kill(s) after holding use {} tick(s) - attack "
                        + "time ({} tick(s)) up, moving on", route.indexOf(node) + 1, KIND, kills(bat), goal,
                        stepTicks, attackTicks);
                AutoRoutesFeature.chatBad(String.format(java.util.Locale.US,
                        "%s node: no kill in %.1f s - moving on.", bat ? "Bat" : "Crypt", attackTicks / 20.0));
                releaseUse(client);
                restoreCryptSlot(client, player);
                finishAction();
                return;
            }
            holdUse(client, player, node);
        }
    }

    /** The kills a crypt node (crypt / prince) or a bat node (secret bats) counts. */
    private static int kills(boolean bat) {
        return bat ? AwaitEvents.bats() : AwaitEvents.crypts();
    }

    /**
     * One tick of a crypt node's held right click: the use key down (re-asserted every tick, as a mouse release event
     * or a focus change could have lifted it) and, in obvious mode, the body kept straight down. Each tick held counts
     * as a use of ours for the kill attribution ({@link AwaitEvents#onLocalWeaponUse}): in the sim a Hyperion's held use
     * goes out as a use-on-block (the floor), which the useItem hook never sees.
     */
    private static void holdUse(Minecraft client, LocalPlayer player, RouteNode node) {
        if (!AutoRoutesConfig.getInstance().isLegitMode()) {
            // The body straight down at HIS yaw (the running value, untouched): pitch 90.0 in every packet.
            turnBody(player, player.getYRot(), MimicKiller.STRAIGHT_DOWN);
        } else if (player.getXRot() != MimicKiller.STRAIGHT_DOWN) {
            // Legit: the key is only down while the camera reads exactly 90.0 - vanilla's held use fires at the
            // camera's rotation, so a drifted camera gets the key up and the aim back, never a use at another pitch.
            client.options.keyUse.setDown(false);
            if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                RouteRotation.beginApproach(player.getYRot(), MimicKiller.STRAIGHT_DOWN, false, 0f, 0f);
            }
            useHeld = true;
            return;
        }
        client.options.keyUse.setDown(true);
        useHeld = true;
        AwaitEvents.onLocalWeaponUse();
    }

    /** Lets go of a crypt node's held use key. Safe when nothing is held. */
    private static void releaseUse(Minecraft client) {
        if (!useHeld) {
            return;
        }
        useHeld = false;
        if (client != null) {
            client.options.keyUse.setDown(false);
        }
    }

    /**
     * While a crypt node holds the use key in obvious mode, the block vanilla's held use acts on: the one along the BODY's
     * look (straight down), not the held camera's. {@code Minecraft.pick} reads the camera's view rotation
     * ({@code Ap3ViewYawMixin} returns the held view from {@code getViewYRot}/{@code getViewXRot}), so without this the held
     * use would right-click whatever he is looking at - a chest, a lever - from a body facing the floor, which GrimAC's
     * RotationPlace refuses. Null when nothing needs overriding (legit mode turns the real camera). From
     * {@code mixin/HeldUsePickMixin}, at the end of {@code Minecraft.pick}.
     */
    public static HitResult heldUsePick(Minecraft client) {
        if (!useHeld || !running || client.player == null || client.level == null
                || AutoRoutesConfig.getInstance().isLegitMode()) {
            return null;
        }
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition();
        Vec3 look = TeleportUtils.getLook(player.getYRot(), player.getXRot()).scale(player.blockInteractionRange());
        return client.level.clip(new ClipContext(eye, eye.add(look), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
    }

    /**
     * Whether vanilla's held-use step has to be run by hand this tick: a crypt node holds the use key while a screen the
     * route runs under is open - the Interactive Map with Run While Map Open ({@link AutoRoutesFeature#screenBlocks} false).
     * {@code Minecraft.tick} skips {@code handleKeybinds} under any screen (javap 26.1.2 and 26.2), so the held key
     * would send nothing there; {@code mixin/HeldUsePickMixin} then calls {@code startUseItem} by vanilla's own rule
     * (key down, {@code rightClickDelay == 0}, not using an item) right after the tick's pick - a use every 4 ticks, as
     * with no screen (killer560, 2026-10-07: "the crypt node is not working while I have the interactive map menu open",
     * testkit 96-ar-405-crypt-mapopen). Any other screen holds the route and sends nothing, as vanilla would.
     */
    public static boolean heldUseUnderScreen(Minecraft client) {
        return useHeld && running && client.player != null && McCompat.screen(client) != null
                && !AutoRoutesFeature.screenBlocks(client);
    }

    private static void restoreCryptSlot(Minecraft client, LocalPlayer player) {
        if (cryptSlotBefore >= 0 && cryptSlotBefore <= 8) {
            select(client, player, cryptSlotBefore);
        }
        cryptSlotBefore = -1;
    }

    /**
     * An empty-hand use node: vanilla's right click on the block in sight - {@code gameMode.useItemOn} with the hit, the
     * call vanilla's {@code startUseItem} makes and every block click in the mod already uses (Secret Aura, the
     * triggerbot). No use-item packet into the air after it: vanilla sends none for an empty hand. The block is the one
     * the node looks at within 4.5 of the eye ({@link #blockInSight}: the live crosshair in legit mode, the recorded
     * look in obvious mode, where the camera does not turn). Done on the same tick, like any use with no landing.
     */
    private static void clickBlockByHand(Minecraft client, LocalPlayer player, RouteNode node) {
        BlockHitResult hit = blockInSight(client, player, node, 4.5);
        if (hit == null) {
            stop("the empty-hand use node has nothing to click (no block in sight within 4.5)");
            return;
        }
        if (!AutoRoutesConfig.getInstance().isLegitMode()) {
            // Look along the ray the click is made from first (camera held): a block click reported from another
            // facing is what GrimAC RotationPlace flags.
            turnBody(player, player.getYRot() + Mth.wrapDegrees(RouteCoords.toRealYaw(frame, node.yaw)
                    - player.getYRot()), node.pitch);
        }
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        logActed(node, " (empty hand: clicked " + client.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString()
                + " at " + hit.getBlockPos().toShortString() + ")");
        step = Step.CONFIRM;
        stepTicks = 0;
        finishAction();
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
                // Nothing in reach along the recorded look: on a stack of booms that is usually the wall an earlier
                // boom (or an earlier run) already blew. Nothing to blow is not a reason to strand the rest of the
                // stack (killer560, 2026-10-06: his Museum booms never got to the crypt node), so it completes.
                logActed(node, " (no block in sight within 4.5 - nothing left to blow, moving on)");
                step = Step.CONFIRM;
                stepTicks = 0;
                finishAction();
                return;
            }
            boomTarget = hit.getBlockPos();
            boomBefore.clear();
            for (int dx = -BOOM_WATCH_RADIUS; dx <= BOOM_WATCH_RADIUS; dx++) {
                for (int dy = -BOOM_WATCH_RADIUS; dy <= BOOM_WATCH_RADIUS; dy++) {
                    for (int dz = -BOOM_WATCH_RADIUS; dz <= BOOM_WATCH_RADIUS; dz++) {
                        BlockPos p = boomTarget.offset(dx, dy, dz);
                        boomBefore.put(p, client.level.getBlockState(p));
                    }
                }
            }
            // The same in the dungeon sim: its integrated server answers these packets the way Hypixel's does.
            if (AutoRoutesConfig.getInstance().isLegitMode()) {
                // A real left click: vanilla start + abort, the same packets a tap on an unbreakable block sends.
                client.gameMode.startDestroyBlock(boomTarget, hit.getDirection());
                client.gameMode.stopDestroyBlock();
            } else {
                // Packet for packet what that tap sends, aimed by turning the body to the node's look (GrimAC
                // RotationBreak): START through the block-prediction sequence with the face the ray struck, then
                // ABORT with face DOWN - vanilla's stopDestroyBlock always says DOWN (javap, 26.1.2). The ABORT used
                // to carry the struck face, and GrimAC's PositionBreakB then flagged every later dig until one came
                // from that face (2026-10-05, 62-argrim: a boom's EAST abort, then six breaker digs flagged).
                turnBody(player, player.getYRot() + Mth.wrapDegrees(RouteCoords.toRealYaw(frame, node.yaw)
                        - player.getYRot()), node.pitch);
                BlockPos target = boomTarget;
                Direction face = hit.getDirection();
                if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
                    invoker.killer560smod$invokeStartPrediction(client.level, sequence -> new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, face, sequence));
                } else {
                    player.connection.send(new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, face));
                }
                player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target, Direction.DOWN));
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
            } else if (stepTicks >= boomSettleTicks()) {
                // Nothing within reach of the hit changed once the server had a round trip to answer: the crypt or
                // wall is already open (the stack's earlier boom, or an earlier run), so there was nothing left to
                // blow. It COMPLETES - stopping here is what kept his stacked booms from reaching their crypt node
                // (killer560, 2026-10-06). A route that really needed the wall gone fails at its next node instead.
                LOGGER.info("[AutoRoutes] Boom: nothing changed within {} tick(s) of the click - nothing left to blow, "
                        + "moving on", stepTicks);
                finishAction();
            }
        }
    }

    /** Ticks a boom that changed nothing waits: the measured round trip ({@link
     *  com.killer560.hub.util.PingTracker#automationMs}) rounded up, plus a margin, bounded - 5 at a local 1-50 ms, 8 at
     *  200 ms, 20 (a second) above 750 ms. Until 2026-10-11 the round trip was the tab-list latency, about 1 ms on
     *  Hypixel, so a boom on a real connection waited 5 ticks whatever the ping. */
    static int boomSettleTicks() {
        int ping = 0;
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null && client.player != null) {
            ping = com.killer560.hub.util.PingTracker.automationMs();
        }
        return Mth.clamp(BOOM_SETTLE_MIN_TICKS + (ping + 49) / 50, BOOM_SETTLE_MIN_TICKS, BOOM_SETTLE_MAX_TICKS);
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
            // Everything this node owns, for the linger after it finishes (tickBreakerLinger).
            lingerNode = node;
            lingerBlocks.clear();
            lingerBlocks.addAll(breakerQueue);
            lingerLevel = client.level;
            lingerTicks = 0;
            lingerSentAt.clear();
            lingerSaid = false;
            int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
            if (slot < 0) {
                stop("no Dungeon Breaker in the hotbar");
                return;
            }
            // The item's lore, in the sim too: the sim's server keeps that line current as Hypixel's does. Read from
            // the hotbar slot, so a node that has to wait for charges sends nothing at all - not even the swap -
            // until it breaks.
            int[] charges = breakerCharges(player.getInventory().getItem(slot));
            LOGGER.info("[AutoRoutes] Breaker: {} block(s) queued, {} charge(s)", breakerQueue.size(),
                    charges == null ? "no" : String.valueOf(charges[0]));
            if (charges == null) {
                // No "Charges: N/M" line at all: not a breaker this can read. A low count is waited for (DO).
                stop("Dungeon Breaker has no charges");
                return;
            }
            breakerChargesLeft = charges[0];
            breakerReady = false;
            breakerWaitTicks = 0;
            breakerWaitSaid = false;
            step = Step.DO;
            stepTicks = 0;
        }
        if (step == Step.DO && !breakerReady) {
            // killer560, 2026-10-06: "if it steps on an ar breaker node and it doesn't have enough charges then it
            // should wait to break until it does." Needed = the picked blocks still standing (air needs no charge),
            // loaded and in reach - at most the breaker's maximum, or a node of more blocks than it can ever hold
            // would wait forever; it then breaks as many as it has, as before. Read every tick: Hypixel gives back
            // two a second (the sim the same). Sneak, position and look stay as the node holds them; nothing is sent.
            int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
            if (slot < 0) {
                stop("no Dungeon Breaker in the hotbar");
                return;
            }
            int[] charges = breakerCharges(player.getInventory().getItem(slot));
            if (charges == null) {
                stop("Dungeon Breaker has no charges");
                return;
            }
            Vec3 eye = player.getEyePosition();
            int needed = 0;
            for (BlockPos pos : breakerQueue) {
                if (client.level.isLoaded(pos) && !client.level.getBlockState(pos).isAir()
                        && com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos) <= BREAKER_RANGE_SQ) {
                    needed++;
                }
            }
            int required = Math.min(needed, charges[1]);
            if (charges[0] < required) {
                breakerWaitTicks++;
                if (!breakerWaitSaid) {
                    breakerWaitSaid = true;
                    LOGGER.info("[AutoRoutes] Breaker: {} block(s) standing in reach, {} charge(s) - waiting for {}",
                            needed, charges[0], required);
                    AutoRoutesFeature.chat(ModChat.text("Waiting for "), ModChat.value(String.valueOf(required)),
                            ModChat.text(" breaker charges (have "), ModChat.value(String.valueOf(charges[0])),
                            ModChat.text(")."));
                }
                if (breakerWaitTicks > BREAKER_WAIT_TIMEOUT) {
                    stop("the Dungeon Breaker never had " + required + " charges (has " + charges[0] + " after "
                            + BREAKER_WAIT_TIMEOUT / 20 + " s)");
                }
                return;
            }
            if (breakerWaitTicks > 0) {
                LOGGER.info("[AutoRoutes] Breaker: {} charge(s) after waiting {} tick(s) - breaking {}", charges[0],
                        breakerWaitTicks, needed);
            }
            breakerWaitTicks = 0;
            breakerReady = true;
            breakerChargesLeft = charges[0];
            select(client, player, slot);
            stepTicks = 0; // the first block goes now, on the tick the charges are there
        }
        if (step == Step.DO) {
            // Breaker Aura's own Multi Break setting decides (coordinator, 2026-10-05: a node of N blocks took N
            // ticks, against "each action in 1 tick"). ON: every queued block that is loaded, solid and in reach goes
            // out on the firing tick, as many as the breaker has charges, then ONE swing - Breaker Aura's burst,
            // packet for packet (start-prediction START_DESTROY_BLOCKs, then the swing, all at START_CLIENT_TICK,
            // ahead of the movement packet). OFF: QUOI's DungeonBreakerAction, one block per interact-delay tick.
            // Obvious mode turns the body (camera held) at the first block each tick and every dig carries the face
            // the eye sees, because GrimAC checks both (RotationBreak, PositionBreakA). The first block goes on
            // the firing tick (stepTicks is 0 there), right behind the held-item packet.
            boolean multi = com.killer560.hub.dungeonextras.DungeonExtrasConfig.getInstance().isBreakerAuraMultiBreak();
            int delay = Math.max(1, AutoRoutesConfig.getInstance().getInteractDelayTicks());
            if (!multi && stepTicks % delay != 0) {
                return;
            }
            Vec3 eye = player.getEyePosition();
            int sentNow = 0;
            while (!breakerQueue.isEmpty()) {
                BlockPos pos = breakerQueue.get(0);
                if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).isAir()) {
                    breakerQueue.remove(0); // air needs no charge, so it never counts as "not sent"
                    continue;
                }
                if (breakerChargesLeft <= 0) {
                    LOGGER.info("[AutoRoutes] Breaker: out of charges - {} block(s) not sent", breakerQueue.size());
                    breakerQueue.clear();
                    break;
                }
                breakerQueue.remove(0);
                // To the BOX, not the centre. The constant above was tightened to the real 4.5 and this
                // measure was left on distToCenterSqr, which reads up to sqrt(0.75) further - so the gate
                // its own javadoc describes was still off by that much, refusing blocks well inside reach.
                if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos) > BREAKER_RANGE_SQ) {
                    LOGGER.info("[AutoRoutes] Breaker block {} out of range - skipped", pos);
                    continue;
                }
                if (sentNow == 0 && !AutoRoutesConfig.getInstance().isLegitMode()) {
                    // Look at the (first) block this tick breaks, as a dig is always aimed (GrimAC RotationBreak).
                    turnBodyToward(player, Vec3.atCenterOf(pos));
                }
                sendBreak(client, player, pos);
                breakerChargesLeft--;
                sentNow++;
                if (breakerSent.isEmpty()) {
                    logActed(node, multi ? " (multi break)" : "");
                }
                breakerSent.add(pos);
                if (!multi) {
                    player.swing(InteractionHand.MAIN_HAND);
                    if (!breakerQueue.isEmpty()) {
                        return; // next block on the next delay tick
                    }
                    break;
                }
            }
            if (multi && sentNow > 0) {
                // One swing however many went out, as Breaker Aura: a hand swings once a tick.
                player.swing(InteractionHand.MAIN_HAND);
                LOGGER.info("[AutoRoutes] Breaker: {} block(s) on one tick (multi break)", sentNow);
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
        aimAt(RouteCoords.toRealYaw(frame, node.yaw), node.pitch, node);
    }

    /** {@link #aimAt(RouteNode)} at an explicit real-world look - a path hop's. */
    private static void aimAt(float yaw, float pitch, RouteNode node) {
        if (!AutoRoutesConfig.getInstance().isLegitMode()) {
            RouteRotation.clear();
            return;
        }
        RouteNode next = !stackQueue.isEmpty() ? stackQueue.peek()
                : nextNode + 1 < ordered.size() ? ordered.get(nextNode + 1) : null;
        boolean hasNext = next != null && next.isDiscreteAction();
        RouteRotation.beginApproach(yaw, pitch, hasNext, hasNext ? RouteCoords.toRealYaw(frame, next.yaw) : 0f,
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

    /** How close legit mode turns the camera before an etherwarp goes: the planner's own aim tolerance. */
    private static final float WARP_AIM_EXACT = com.killer560.hub.livemap.autoclear.EtherSearch.AIM_MARGIN;
    /** Ticks a legit warp's turn may take to settle that exactly before it goes on the old 1-degree rule. */
    private static final int WARP_AIM_GIVE_UP = AIM_TIMEOUT * 3;

    /**
     * {@link #aimReady} for an etherwarp. Legit mode clicks at the LIVE camera, and {@link #aimReady} let it go once the
     * camera was within 1 degree of the node's look - more than a warp's margin often is: 96-ar-644/651's warp seven
     * blocks out along the floor has 0.7 degrees, so about one legit warp in three landed a block short (2026-10-11).
     * Now the turn keeps easing in until it is within {@link #WARP_AIM_EXACT} of the look (the tolerance the planner
     * itself proves its aims to) - a few more frames of the same smooth approach, never a snap - so a legit warp lands
     * where the same node lands in obvious mode. Only a turn that has not got there after {@link #WARP_AIM_GIVE_UP}
     * ticks still goes, as before, and says so in the log.
     */
    private static boolean warpAimReady(LocalPlayer player) {
        if (!AutoRoutesConfig.getInstance().isLegitMode()) {
            return true;
        }
        if (RouteRotation.settled(WARP_AIM_EXACT) && stepTicks >= AutoRoutesConfig.getInstance().getInteractDelayTicks()) {
            return true;
        }
        if (stepTicks > WARP_AIM_GIVE_UP) {
            LOGGER.info("[AutoRoutes] Etherwarp: the legit turn did not settle within {} degrees in {} ticks ({} off) -"
                    + " warping anyway", WARP_AIM_EXACT, stepTicks, RouteRotation.error());
            return true;
        }
        return false;
    }

    /**
     * Selects the hotbar slot and tells the server NOW, through vanilla's own
     * {@code MultiPlayerGameMode.ensureHasSentCarriedItem}, so its {@code carriedIndex} agrees and its
     * {@code tick()} does not send the same slot again. (The old code sent the packet by hand and left
     * {@code carriedIndex} stale, so every route swap went out twice - a same-slot repeat a vanilla client never
     * sends.) The server handles the held-item packet before anything sent after it, so the use / click that follows
     * in the same tick is made with the new item: a swap costs no tick.
     */
    static void select(Minecraft client, LocalPlayer player, int slot) {
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

    // ------------------------------------------------------------------------------------------- breaker linger

    /**
     * killer560 (2026-10-13): "if i step on a dungeon breaker node until another node fires it should still be able to
     * mine so for instance if it mines the blocks out from under its feet and starts falling then it should keep mining
     * as it falls." A breaker node's blocks that were out of reach when it fired (or that it had no charges for) stay
     * its own after it finishes: every tick, any of them that is standing, loaded and now in reach goes out exactly as
     * the node's own break does - until all are gone, another node fires ({@link #beginAction}), the room or world
     * changes, or {@link #LINGER_MAX_TICKS} pass. Nothing is sent while a screen holds Auto Routes back.
     */
    private static RouteNode lingerNode;
    private static final List<BlockPos> lingerBlocks = new ArrayList<>();
    private static final java.util.Map<BlockPos, Integer> lingerSentAt = new java.util.HashMap<>();
    private static net.minecraft.client.multiplayer.ClientLevel lingerLevel;
    private static int lingerTicks;
    private static boolean lingerSaid;
    /** Longest a finished breaker node keeps mining what comes into reach: 10 s, longer than any fall in a room. */
    static final int LINGER_MAX_TICKS = 200;
    /** A block sent and still standing after this many ticks is sent again (the server refused it, or regrew it). */
    private static final int LINGER_RESEND_TICKS = 20;
    /** Blocks a breaker node broke after it had finished (read by the testkit, 96-ar-1013-breakerfall). */
    private static int lingerBroken;

    public static int lingerBroken() {
        return lingerBroken;
    }

    /** Whether a finished breaker node is still mining its blocks as they come into reach. */
    static boolean breakerLingering() {
        return lingerNode != null && !lingerBlocks.isEmpty() && !(running && activeNode == lingerNode);
    }

    static void clearBreakerLinger(String why) {
        if (lingerNode != null && !lingerBlocks.isEmpty() && lingerSaid) {
            LOGGER.info("[AutoRoutes] Breaker linger ended ({}): {} block(s) left", why, lingerBlocks.size());
        }
        lingerNode = null;
        lingerBlocks.clear();
        lingerSentAt.clear();
        lingerLevel = null;
    }

    /** Every client tick from the feature, whether a route runs or not. See the fields above. */
    static void tickBreakerLinger(Minecraft client) {
        if (lingerNode == null) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.level != lingerLevel) {
            clearBreakerLinger("world change");
            return;
        }
        if (running && activeNode == lingerNode) {
            return; // the node itself is still breaking
        }
        lingerBlocks.removeIf(p -> !client.level.isLoaded(p) || client.level.getBlockState(p).isAir());
        if (lingerBlocks.isEmpty()) {
            if (lingerSaid) {
                LOGGER.info("[AutoRoutes] Breaker linger: every block gone after {} tick(s)", lingerTicks);
            }
            clearBreakerLinger("done");
            return;
        }
        if (!lingerSaid) {
            lingerSaid = true;
            LOGGER.info("[AutoRoutes] Breaker linger: {} block(s) still standing - mining them as they come into reach"
                    + " until another node fires", lingerBlocks.size());
        }
        if (++lingerTicks > LINGER_MAX_TICKS) {
            clearBreakerLinger("timed out");
            return;
        }
        if (AutoRoutesFeature.screenBlocks(client)) {
            return;
        }
        boolean multi = com.killer560.hub.dungeonextras.DungeonExtrasConfig.getInstance().isBreakerAuraMultiBreak();
        int delay = Math.max(1, AutoRoutesConfig.getInstance().getInteractDelayTicks());
        if (!multi && lingerTicks % delay != 0) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        List<BlockPos> inReach = new ArrayList<>();
        for (BlockPos pos : lingerBlocks) {
            Integer sent = lingerSentAt.get(pos);
            if (sent != null && lingerTicks - sent < LINGER_RESEND_TICKS) {
                continue;
            }
            if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos) <= BREAKER_RANGE_SQ) {
                inReach.add(pos);
            }
        }
        if (inReach.isEmpty()) {
            return;
        }
        int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
        int[] charges = slot < 0 ? null : breakerCharges(player.getInventory().getItem(slot));
        if (charges == null || charges[0] <= 0) {
            return; // no breaker, or no charges yet: the blocks wait (Hypixel gives two back a second)
        }
        select(client, player, slot);
        int sentNow = 0;
        for (BlockPos pos : inReach) {
            if (sentNow >= charges[0]) {
                break;
            }
            if (sentNow == 0 && !AutoRoutesConfig.getInstance().isLegitMode()) {
                turnBodyToward(player, Vec3.atCenterOf(pos)); // a dig is always aimed (GrimAC RotationBreak)
            }
            sendBreak(client, player, pos);
            lingerSentAt.put(pos, lingerTicks);
            lingerBroken++;
            sentNow++;
            if (!multi) {
                break;
            }
        }
        player.swing(InteractionHand.MAIN_HAND);
        LOGGER.info("[AutoRoutes] Breaker linger: {} block(s) broken {} tick(s) after the node, eye y {}", sentNow,
                lingerTicks, String.format(java.util.Locale.US, "%.2f", eye.y));
    }

    /**
     * One START_DESTROY_BLOCK through vanilla's own block-prediction sequence ({@code startPrediction}) - Breaker Aura's
     * {@code breakBlock} without its zero-ping - so the packet carries the sequence number a vanilla dig does.
     */
    private static void sendBreak(Minecraft client, LocalPlayer player, BlockPos pos) {
        // The face the eye actually sees (Breaker Aura's clip, BlockHits). It was always UP, which no dig from below
        // a block's top can carry: GrimAC PositionBreakA flagged and cancelled every breaker block above the feet
        // (2026-10-05, 62-argrim), so those blocks never broke.
        Direction face = com.killer560.hub.util.BlockHits.surfaceOrCentre(client.level, pos, player.getEyePosition())
                .getDirection();
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level, sequence -> new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, sequence));
        } else {
            player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
        }
    }

    /** The per-node timing line: how many ticks the node's action took from firing (after any await), 0 = the
     *  firing tick itself. */
    private static void logActed(RouteNode node, String detail) {
        nodeActed = true;
        LOGGER.info("[AutoRoutes] Node #{} {} acted {} tick(s) after firing{}{}", route == null ? "?" : route.indexOf(node) + 1,
                node.type, actionAge - awaitDoneAge, nodeSwapped ? " (hotbar swap, same tick)" : "", detail);
    }

    /** Legit: a real right click at the live (already turned) rotation - for a use-item node the block in the
     *  crosshair first like vanilla (a recorded lever/chest click replays as one), then the item; an etherwarp is
     *  QUOI's plain {@code gameMode.useItem}. Obvious: the body turned to the target (camera held, see
     *  {@link #turnBody}) and the use packet sent with that same rotation. */
    static void useHeldItem(Minecraft client, LocalPlayer player, float targetYaw, float targetPitch,
                            boolean blockInteraction) {
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        // A use of ours: what a crypt / prince kill is attributed to (legit's gameMode.useItem is seen by the mixin too).
        AwaitEvents.onLocalWeaponUse();
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
                if (!blockInteraction) {
                    // An etherwarp: the block half of a vanilla right click first when the server needs it (util/RightClick).
                    com.killer560.hub.util.RightClick.beforeUse(client, player, player.getYRot(), player.getXRot(),
                            "[AutoRoutes] Use");
                }
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        // Same direction as the target, expressed relative to the running (unwrapped) yaw - never a wrapped absolute.
        float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitch = Mth.clamp(targetPitch, -90f, 90f);
        // The BODY turns to the use's rotation and stays there for this tick's movement packet; only the camera is
        // held still (ViewFreeze). A use packet whose rotation the client never reports is not something a vanilla
        // client can send: GrimAC's BadPacketsJ flagged every obvious-mode etherwarp and path warp for exactly that
        // (2026-10-05, 62-argrim), the same flag Auto Puzzles drew and fixed this way on 2026-09-27
        // (AutoPuzzleUtil.useItemRotated). The body is put back to the held view a tick after the last use
        // (tickView), so the camera ends where he left it.
        turnBody(player, yaw, pitch);
        // When a block click is unpaired on the server and this look (which the server already has - turnFirst) meets a
        // block within reach, the click on that block goes first, as a vanilla right click sends it (util/RightClick).
        com.killer560.hub.util.RightClick.beforeUse(client, player, yaw, pitch, "[AutoRoutes] Use");
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level,
                    sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        } else {
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * {@link com.killer560.hub.util.RightClick#mustTurnFirst} for a use at {@code yaw}/{@code pitch}: true = send nothing this
     * tick. Obvious mode turns the body to the look now, so this tick's movement packet tells the server; legit mode uses
     * at the live camera, which it reports itself, so it only waits for that.
     */
    static boolean turnFirst(LocalPlayer player, float yaw, float pitch, String who) {
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        if (!com.killer560.hub.util.RightClick.mustTurnFirst(player, legit ? player.getYRot() : yaw,
                legit ? player.getXRot() : pitch, who)) {
            return false;
        }
        if (!legit) {
            turnBody(player, player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()), Mth.clamp(pitch, -90f, 90f));
        }
        return true;
    }

    /** The route's body turns, camera held ({@link com.killer560.hub.util.BodyAim}, shared with the Interactive Map's
     *  executor). Every write it makes is our own, not his mouse, so the camera-turn detector is rebased after it. */
    private static final com.killer560.hub.util.BodyAim BODY = new com.killer560.hub.util.BodyAim(RouteRotation::rebase);

    /** How long after a chest click its window may still arrive and be waited under (2 s: a round trip, generously). */
    private static final int LATE_CHEST_WINDOW_TICKS = 40;
    /** The clicked chest's late window the route is waiting under, or null (see tick's screen branch). */
    private static net.minecraft.client.gui.screens.Screen lateChestWindow;

    /**
     * Obvious mode: turns the BODY (the rotation every packet reports) to {@code yaw}/{@code pitch} - a delta on the
     * running yaw, pitch within +-90 - while the camera stays where he had it. The next movement packet then carries
     * the rotation the action used, as it would after a real flick.
     */
    private static void turnBody(LocalPlayer player, float yaw, float pitch) {
        BODY.turn(player, yaw, pitch);
    }

    /** {@link #turnBody} toward a point (the centre of a block a breaker or boom node acts on). */
    private static void turnBodyToward(LocalPlayer player, Vec3 point) {
        TeleportUtils.Rotation r = TeleportUtils.getDirection(player.getEyePosition(), point);
        turnBody(player, player.getYRot() + Mth.wrapDegrees(r.yaw() - player.getYRot()), r.pitch());
    }

    /**
     * Every client tick, from the feature, before anything else: keeps the camera held while the route has turned his
     * body, and gives it back - the body turned back to the held view, his mouse movement included - once the route
     * is no longer acting (it stopped, or a walk node took over the facing) and at least one movement packet has
     * reported the last action's rotation.
     */
    static void tickView(Minecraft client) {
        // Held through a node, a stack, the settle between nodes and a pending landing re-fire (a ping-pong turns once
        // per warp, not there and back); given back the moment the route waits for HIM - he walks with his body's
        // yaw, so it must be the way he is looking.
        // A node waiting on its await is waiting for HIM too: he is clicking the secrets, and while the view is held the
        // crosshair follows the held view but every packet reports the body, so his own clicks would go out aimed one
        // way and reported another.
        boolean acting = MimicKiller.isBusy() || breakerLingering() || (running && walkHoldYaw == null && !(activeNode != null && awaitHeld)
                && (activeNode != null || !stackQueue.isEmpty() || settleTicks > 0 || landedFrom != null));
        BODY.tick(client.player, acting);
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
    private static boolean landed(LocalPlayer player, Vec3 landing) {
        Vec3 pos = player.position();
        if (landing != null) {
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

    /** The block a superboom or empty-hand click lands on: the live crosshair in legit mode (the camera was turned), the recorded
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

    /**
     * {@code dungeonbreaker/DungeonBreakerFeature.getBreakerCharges}: the count is only in the item's lore.
     *
     * @return {charges, maximum} from its "Charges: N/M" line, or null when the stack is not a breaker or has no such line
     */
    private static int[] breakerCharges(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !BREAKER_ID.equalsIgnoreCase(ItemIdentity.skyblockId(stack))) {
            return null;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (Component line : lore.lines()) {
            Matcher m = CHARGES.matcher(line.getString());
            if (m.find()) {
                try {
                    return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
