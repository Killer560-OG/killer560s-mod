package com.killer560.hub.autoroutes;

import com.killer560.hub.ap3.Ap3TrapWalk;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
    /** {@link com.killer560.hub.util.ServerLag#mark()} on the step's first tick: every wait for the server's answer
     *  (a landing, a broken block, a boom, a kill) gets {@link #serverLagTicks()} more ticks, so a lagging server is
     *  never read as the action failing; 0 while the server keeps time (killer560, 2026-10-15). */
    private static long stepLag;
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
    /** The warp's use went out as one util/RightClick judged the server would take as an unpaired click's second half:
     *  with no landing inside {@code RightClick.eatenRetryTicks} it goes again (the click is paired by then). */
    private static boolean warpEaten;
    /** Warps sent again after an eaten use - read by the testkit. */
    private static int eatenResends;

    public static int eatenResends() {
        return eatenResends;
    }

    /** Times the warp under way was sent again after no landing came (see {@link #retryAim}). */
    private static int warpRetries;
    /** A refused warp goes again at most this many times from the same spot; past that the landing timeout and
     *  {@link #refireHoldAt} take over (a server refusing it every time - a block only it has - is not fought). */
    private static final int MAX_REFUSED_RETRIES = 2;
    /** His left click released a hold on the warp under way (landing block missing, a breaker pick or a new block in its
     *  line): those checks are skipped until the next warp's PREP. */
    private static boolean warpForced;
    /** The "still no landing" line went out for the warp under way. */
    private static boolean warpSlowSaid;
    /**
     * Hypixel acks block clicks but not a bare item use (his Mod Only Test log, 2026-10-19 15:06-15:08: the refused-warp
     * retry below fired only 44-58 ticks on, right after one of HIS block clicks brought an ack past the warp's sequence,
     * and never in the six Slime #9 refusals, each of which sat out the whole 60-tick timeout). So a use with no ack after
     * this many round trips counts as handled - the same rule as {@code RewardChest.NO_ACK_ROUND_TRIPS}.
     */
    private static final int NO_ACK_ROUND_TRIPS = 3;
    /**
     * Where he stood when a warp from there never landed (the server sent no teleport and he had not moved). The node he
     * stands in is NOT fired again by itself from here: on 2026-10-19 Slime #5's stack (two booms, a crypt, then path #9)
     * re-fired from the same spot after every timeout, five times in 35 s, each warp refused the same way - the "freeze".
     * The route keeps running; a left click (or moving off the spot) fires it again. Null when nothing is held.
     */
    private static Vec3 refireHoldAt;
    private static boolean refireHoldSaid;
    /** The prediction sequence of the warp's use, or -1: a refused warp is only called refused once the server has
     *  acked it (handled it) with no teleport before the ack. */
    private static int warpUseSeq = -1;
    private static int refusedRetries;

    /** Warps aimed again and sent once more after a refused one - read by the testkit. */
    public static int refusedRetries() {
        return refusedRetries;
    }

    /** Use-to-landing ticks of the last few warps that landed (a ring), for {@link #refusedRetryTicks}. */
    private static final int[] recentLandings = new int[5];
    private static int recentLandingCount;
    private static int recentLandingNext;

    private static void noteLandingTicks(int ticks) {
        recentLandings[recentLandingNext] = ticks;
        recentLandingNext = (recentLandingNext + 1) % recentLandings.length;
        recentLandingCount = Math.min(recentLandings.length, recentLandingCount + 1);
    }

    /**
     * Ticks after a use with no landing (and no move) before the warp counts as refused and goes again: twice the slowest
     * of the last few measured landings and two (4 before any), or {@code RightClick.eatenRetryTicks} when longer - the
     * rule AutoReposition already uses. Hypixel never acks a bare use, so this used to be three of those round trips:
     * his Mod Only Test log of 2026-10-19 16:17-16:18 has six warps a tick after a Secret Aura lever or chest click that
     * never landed, each sent again only 13-22 ticks later while every landing in that run took 2-4 ticks - the route
     * "really slow after collecting secrets". Callers add server lag on top.
     */
    static int refusedRetryTicks() {
        int travel = recentLandingCount > 0 ? slowestRecentLanding() : 4;
        return Math.max(com.killer560.hub.util.RightClick.eatenRetryTicks(), 2 * travel + 2);
    }

    private static int slowestRecentLanding() {
        int travel = 0;
        for (int i = 0; i < recentLandingCount; i++) {
            travel = Math.max(travel, recentLandings[i]);
        }
        return travel;
    }

    /**
     * The secret gap (killer560, 2026-10-19: "auto routes is really slow after collecting secrets a lot of the time"):
     * {@link #execTicks} when a node's secret await was met, that node, and what held the route until the next node fired.
     * One INFO line ("secret gap: ...") when the next node fires or the route ends. -1 when nothing is pending.
     */
    private static long secretMetAt = -1;
    private static RouteNode secretMetNode;
    private static final StringBuilder secretGapWhy = new StringBuilder();
    /** The Kill Mimic hold was noted for the open gap. */
    private static boolean mimicGapSaid;

    /** A node's await was met by what it waited for (not a skip): the gap to the next node starts now. */
    private static void startSecretGap(RouteNode node) {
        if (node == null || node.awaitCondition == RouteNode.AwaitCondition.DELAY) {
            return;
        }
        secretMetAt = execTicks;
        secretMetNode = node;
        secretGapWhy.setLength(0);
        mimicGapSaid = false;
    }

    /** Something that held or moved the route while a secret gap is open, with its offset from the await being met. */
    private static void noteSecretGap(String what) {
        if (secretMetAt < 0) {
            return;
        }
        if (secretGapWhy.length() > 0) {
            secretGapWhy.append("; ");
        }
        secretGapWhy.append('+').append(execTicks - secretMetAt).append(' ').append(what);
    }

    /** The gap closes: {@code next} is what happened ("node #5 PATH fired", "the route ended"). */
    private static void endSecretGap(String next) {
        if (secretMetAt < 0) {
            return;
        }
        LOGGER.info("[AutoRoutes] secret gap: {} {} tick(s) after node #{} {}'s await was met ({})", next,
                execTicks - secretMetAt, route == null || secretMetNode == null ? "?" : route.indexOf(secretMetNode) + 1,
                secretMetNode == null ? "?" : secretMetNode.type,
                secretGapWhy.length() == 0 ? "nothing held it" : secretGapWhy);
        secretMetAt = -1;
        secretMetNode = null;
        secretGapWhy.setLength(0);
        mimicGapSaid = false;
    }

    // ---- logging only (no behavior reads these) ----
    /** The last "held:" reason logged and the {@link #execTicks} it went out, for {@link #logHold}'s rate limit. */
    private static String lastHoldKey;
    private static long lastHoldTick;

    /** A wait that can repeat every tick: logged when its key changes, else at most once per 20 route ticks. */
    private static void logHold(String key, String format, Object... args) {
        if (key.equals(lastHoldKey) && execTicks - lastHoldTick < 20 && execTicks >= lastHoldTick) {
            return;
        }
        lastHoldKey = key;
        lastHoldTick = execTicks;
        LOGGER.info("[AutoRoutes] held: " + format, args);
    }

    /** "#5 ETHERWARP" for a log line, or "-" with no node. */
    private static String nodeTag(RouteNode node) {
        if (node == null) {
            return "-";
        }
        return "#" + (route == null ? "?" : String.valueOf(route.indexOf(node) + 1)) + " " + node.type;
    }

    /** Who called {@link #stop}: the first frame outside it, as Class.method:line (pure, logging only). */
    private static String stopCaller() {
        try {
            return StackWalker.getInstance().walk(frames -> frames
                    .filter(f -> !(f.getClassName().equals(RouteExecutor.class.getName())
                            && (f.getMethodName().equals("stop") || f.getMethodName().equals("stopCaller"))))
                    .findFirst()
                    .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "."
                            + f.getMethodName() + ":" + f.getLineNumber())
                    .orElse("?"));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** A look onto {@link #warpTarget} from his eye that holds within the planner's margin (it may be the look already
     *  sent, if what stopped the first went away). When there is none the look already sent goes again: until
     *  2026-10-19 this returned false and nothing was sent, so the warp sat out the 61-tick landing timeout or his left
     *  click (his Mod Only Test log 16:15:55, Pedestal #3 hop 1: "still no landing", released by his click at 31 ticks,
     *  and the SAME look landed 1 tick after that resend). */
    private static boolean retryAim(LocalPlayer player) {
        Vec3 at = player.position();
        Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
        double range = com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange() + 1.0;
        TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, warpTarget, range);
        if (rot == null || !TeleportUtils.holdsOn(eye, rot.yaw(), rot.pitch(), range, warpTarget)) {
            LOGGER.info("[AutoRoutes] Etherwarp: no landing {} tick(s) after the use and he has not moved - the server "
                            + "refused it; no other look from {} holds on {}, so the same look ({} / {}) goes once more "
                            + "(refusal called past {} tick(s), server lag {})", stepTicks, fmt(at),
                    warpTarget.toShortString(), String.format(Locale.US, "%.2f", Mth.wrapDegrees(warpYaw)),
                    String.format(Locale.US, "%.2f", warpPitch), refusedRetryTicks(), serverLagTicks());
            noteSecretGap("no landing " + stepTicks + " tick(s) after the use, server refused it - same look sent again");
            warpAimFrom = at;
            refusedRetries++;
            return true;
        }
        float yaw = player.getYRot() + Mth.wrapDegrees(rot.yaw() - player.getYRot());
        LOGGER.info("[AutoRoutes] Etherwarp: no landing {} tick(s) after the use and he has not moved - the server "
                        + "refused it; aimed again from {} at {} ({} / {} -> {} / {}), sending it once more (refusal "
                        + "called past {} tick(s): recent landings took up to {}, server lag {})", stepTicks,
                fmt(at), warpTarget.toShortString(), String.format(Locale.US, "%.2f", Mth.wrapDegrees(warpYaw)),
                String.format(Locale.US, "%.2f", warpPitch), String.format(Locale.US, "%.2f", rot.yaw()),
                String.format(Locale.US, "%.2f", rot.pitch()), refusedRetryTicks(),
                recentLandingCount == 0 ? "? (none measured yet)" : String.valueOf(slowestRecentLanding()),
                serverLagTicks());
        noteSecretGap("no landing " + stepTicks + " tick(s) after the use, server refused it - sent again");
        warpYaw = yaw;
        warpPitch = rot.pitch();
        warpAimFrom = at;
        refusedRetries++;
        return true;
    }

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
    /** Ticks a breaker node has waited for his stand to reach the server (a block in reach only standing). */
    private static int breakerStandTicks;
    /** Vanilla's standing eye height, both versions. */
    private static final double STANDING_EYE = 1.62;
    /** How long a breaker node waits for its charges before the route gives up (30 s; 2 come back a second). */
    private static final int BREAKER_WAIT_TIMEOUT = 600;
    private static final Set<BlockPos> breakerSent = new HashSet<>();
    /** A breaker node's obsidian blocks, dug one at a time after its burst (killer560, 2026-10-19: obsidian takes time). */
    private static final List<BlockPos> breakerObsidian = new ArrayList<>();
    /** The obsidian block the node itself is digging, or null (a linger dig is not the node's). */
    private static BlockPos obsidianDigPos;
    /** The one obsidian dig under way, the node's or the linger's - never both, never beside another dig. */
    private static final com.killer560.hub.dungeonbreaker.ObsidianDig OBSIDIAN =
            new com.killer560.hub.dungeonbreaker.ObsidianDig(LOGGER, "[AutoRoutes] Breaker:");

    /** Obvious mode turns the body to the obsidian before each of its dig packets (GrimAC RotationBreak); legit, nothing. */
    private static java.util.function.Consumer<BlockPos> obsidianAim(LocalPlayer player) {
        return AutoRoutesConfig.getInstance().isLegitMode() ? null : p -> turnBodyToward(player, Vec3.atCenterOf(p));
    }
    private static BlockPos boomTarget;
    /** The block (and face) a boom node's recorded look meets from the node's own standing eye - the block it was recorded
     *  on - or null when that look meets nothing in reach. Set in PREP, aimed at from wherever the eye is on each send. */
    private static BlockPos boomPlanned;
    private static Direction boomPlannedFace;
    private static Vec3 boomPlannedHit;
    /** The look the last boom send went out along (the recorded one, or one from this eye onto {@link #boomPlanned}). */
    private static float boomAimYaw;
    private static float boomAimPitch;
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
    /** Bat node with a Spirit Sceptre: the first uses go straight down, then it aims at the nearest secret bat. */
    public static final int BAT_DOWN_USES = 2;
    /** Ticks between two aimed sceptre uses: what a held right click does. */
    private static final int BAT_USE_GAP = 4;
    /** Legit: how close (degrees) the camera must be to the bat before a use goes. */
    private static final float BAT_AIM_TOLERANCE = 6f;
    /** {@link AwaitEvents#weaponUses} when the node started holding use; the uses since are the node's. */
    private static int batUseBase;
    /** The node tick (stepTicks) of the last aimed use. */
    private static int batLastUse;
    private static boolean batSeeking;
    /** Spirit Sceptre CRYPT node: aimed uses given to each crypt mob before it moves on to the next (killer560,
     *  2026-10-15: "If there are two or more that spawned have it do 2 bats at each before moving on"). */
    public static final int CRYPT_SHOTS_EACH = 2;
    /** How far from him a crypt mob is looked for (the sceptre's planning range, {@code WeaponReach.SCEPTRE_PLAN_RANGE}). */
    private static final double CRYPT_SEEK_RANGE = 24.0;
    /** Crypt mobs this crypt node has seen (entity id) and the aimed uses each has had, in the order first seen. */
    private static final Map<Integer, Integer> cryptShots = new java.util.LinkedHashMap<>();
    /** The crypt mob the node is shooting now, or -1: it keeps it until it is dead or has had its uses. */
    private static int cryptTarget = -1;
    /** Spirit Sceptre bats (non-secret bats) first seen since the node started holding use: its own shots in flight. */
    private static final Set<Integer> cryptBats = new HashSet<>();
    private static final Set<Integer> cryptBatsSeen = new HashSet<>();
    /** The node has given some crypt mob a use, or seen one it was tracking die. */
    private static boolean cryptHandled;
    private static final Map<BlockPos, BlockState> boomBefore = new HashMap<>();

    // ---- await ----
    private static long awaitStartMs;
    /** The last whole second of the open await that {@link #tickAwait} logged its wait for. */
    private static int awaitLogSecond;
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
    /** {@link AwaitEvents#now()} when the active node's own action began (its await met or skipped, or it had none). */
    private static int actionFromTick;
    /** Set by {@link #finishAction}: the next node's await also counts remembered secrets got at or after this tick -
     *  the finished node's window start, or the carry it began with if that is earlier. {@code Integer.MAX_VALUE} when
     *  there is no node before it (the route's start has its own lookback). */
    private static int carryFrom = Integer.MAX_VALUE;
    /** The carry the active node began with. It is passed on whether or not the node has an await of its own: a node
     *  claims only the secrets its await met (RouteRunMemory.met), so what it did not use is still the next await's,
     *  however many nodes come between (fullclear-1016, his Double Diamond; 2026-10-18, a PATH that acted 67 ticks). */
    private static int nodeCarry = Integer.MAX_VALUE;
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

    /**
     * A trap room's route walking him out, which Auto Trap ends itself once he is past the room's edge by its margin
     * ({@code AutoTrap.LEAVE_MARGIN}, AutoTrap.tick): the map's room change is not the end of it. The map gives the
     * doorway's outer half-block to the next room while the server still has him in the trap, so a stop there left him
     * standing in the doorway, every warp out refused ("No abilities in a trap room") and Dungeon Autopilot out of rooms
     * it could reach (142-sim-autopilot2, 2026-10-20: stopped at -72.87 on the map's edge -72.5, the server's -73).
     */
    public static boolean trapExitPending() {
        Route r = route;
        return running && r != null && com.killer560.hub.autotrap.AutoTrap.isTrapRoom(r.roomName())
                && com.killer560.hub.autotrap.AutoTrap.isEnabled()
                && r.roomName().equals(com.killer560.hub.autotrap.AutoTrap.inTrapRoom());
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
            LOGGER.info("[AutoRoutes] /ar stop: nothing running - nothing to stop");
            return false;
        }
        stop(STOPPED_BY_COMMAND);
        return true;
    }

    public static void stop(String reason) {
        boolean wasRunning = running;
        if (wasRunning) {
            LOGGER.info("[AutoRoutes] stop called: reason={} by={} room={} node={} step={} stepTicks={} sinceFiring={} "
                            + "awaitDone={} run={} tick(s) next={}/{} stackLeft={} settle={} sneakHeld={} useHeld={} user={}",
                    reason, stopCaller(), route == null ? "?" : route.roomName(), nodeTag(activeNode), step, stepTicks,
                    actionAge, awaitPhaseDone, execTicks, nextNode, ordered.size(), stackQueue.size(), settleTicks,
                    forceSneak, useHeld, reason != null && USER_STOP_REASONS.contains(reason));
            endSecretGap("the route stopped (" + reason + ")");
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[AutoRoutes] stop({}) while idle - nothing to do (by {})", reason, stopCaller());
        }
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
        if (wasRunning && activeNode != null && !awaitPhaseDone && route != null) {
            LOGGER.info("[AutoRoutes] secret wait aborted: node #{} {} stopped while waiting {}/{} after {} tick(s) - {}",
                    route.indexOf(activeNode) + 1, activeNode.type, AwaitEvents.secrets() + awaitRemembered.size(),
                    Math.max(1, activeNode.awaitAmount), (System.currentTimeMillis() - awaitStartMs) / 50L, reason);
        }
        if (trapWalk) {
            Ap3TrapWalk.cancel("the route stopped (" + reason + ")");
            trapWalk = false;
        }
        stackQueue.clear();
        stackTrigger = null;
        activeNode = null;
        step = null;
        refireHoldAt = null;
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
            LOGGER.info("[AutoRoutes] start refused: player={} route={} frame={} startNode={} - something it needs is missing",
                    player != null, r == null ? "null" : r.roomName(), f != null, startNode == null ? "null" : startNode.type);
            return false;
        }
        if (running) {
            LOGGER.info("[AutoRoutes] start: \"{}\" still running ({}) - stopping it to start \"{}\"",
                    route == null ? "?" : route.roomName(), nodeTag(activeNode), r.roomName());
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
        // A route most often starts on the map's warp landing (the start node arms on that tick, no hold): a boom there
        // gets the landing-tick retry too.
        lastLandingAt = 0;
        boomSentAt = Long.MIN_VALUE / 2;
        secretMetAt = -1;
        secretMetNode = null;
        secretGapWhy.setLength(0);
        positionPacketsHandled = positionPackets;
        ownTeleportUntil = 0;
        manualUseAt = Long.MIN_VALUE / 2;
        nextWarpAt = Double.NEGATIVE_INFINITY;
        forceSneak = false;
        unsneakOverride = false;
        awaitPhaseDone = true;
        carryFrom = Integer.MAX_VALUE;
        nodeCarry = Integer.MAX_VALUE;
        earlySkipClickAt = Long.MIN_VALUE / 2;
        refireHoldAt = null;
        secretLeftThisRun = false;
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
        trapExitSaid = false;
        RouteRunMemory.noteProgress(r.roomName(), nextNode, ordered.size());
        LOGGER.info("[AutoRoutes] Started \"{}\" from node {} ({}) at sample {} of {}", r.roomName(),
                r.indexOf(startNode), startNode.type, cursor, r.path().size());
        lastHoldKey = null;
        lastHoldTick = 0;
        AutoRoutesConfig startCfg = AutoRoutesConfig.getInstance();
        LOGGER.info("[AutoRoutes] start state: player {} | {} node(s) in order, first #{} | {} | {} mode | etherwarps/s {} "
                        + "| interact delay {} | input mixin {} | refused retry after {} tick(s)", fmt(player.position()),
                ordered.size(), r.indexOf(startNode) + 1, r.path().isEmpty() ? "path-less" : "recorded path",
                startCfg.isLegitMode() ? "legit" : "obvious", startCfg.getEtherwarpsPerSecond(),
                startCfg.getInteractDelayTicks(), mixinApplied, refusedRetryTicks());
        // No chat line: one route per room is progress, not a start he made (killer560, 2026-10-16).
        // The node he stepped onto goes off NOW (killer560, 2026-10-04: "If I do /ar add ew start, that means that
        // the second I hit that node it should go off"). This used to wait for the next tick's walk step to find
        // him inside the ring again, and on a path-less route a player still walking could be out of it by then.
        // Every node stacked on the start node's tile goes with it.
        beginStack(startNode);
        // tick() does this for every later tick; the firing tick runs from the arming code instead.
        applyFallbackKeys(client);
        return true;
    }

    /** Routes run to their end, ever (only goes up), and the room of the last one: Auto Secret notices a route that
     *  completed on the very tick it armed (a one-node route) by this, as it never sees it running. */
    private static int completions;
    private static String lastCompletedRoom;
    /** {@link #execTicks} is per run; this is the client tick ({@code ClearExecutor.clientTick()}) of the last completion. */
    private static long lastCompletedTick = -1;

    public static int completions() {
        return completions;
    }

    /**
     * Where a run of {@code r} will leave him (feet, real world): the landing of the last teleport of the last node's
     * stack, else the last node's own spot - or, on a recorded route that walks on past its last node, the path's end.
     * Auto Secret plans the trip to the next room from here while the route still runs. Null for an empty route.
     */
    public static Vec3 predictedEnd(Route r, RouteCoords.Frame f) {
        if (r == null || f == null) {
            return null;
        }
        List<RouteNode> order = r.nodesInPathOrder();
        RoutePath path = r.path();
        Vec3 rel = null;
        int lastIndex = -1;
        if (!order.isEmpty()) {
            RouteNode last = order.get(order.size() - 1);
            lastIndex = last.pathIndex;
            rel = last.relativePos();
            for (RouteNode n : r.stackOf(last)) {
                if (n.hasLanding && (n.type == RouteNode.Type.ETHERWARP || n.type == RouteNode.Type.USE_ITEM)) {
                    rel = new Vec3(n.landingX, n.landingY, n.landingZ);
                }
            }
        }
        if (!path.isEmpty() && (rel == null || lastIndex < path.size() - 1)) {
            RoutePath.Sample s = path.get(path.size() - 1);
            rel = new Vec3(s.x(), s.y(), s.z());
        }
        return rel == null ? null : RouteCoords.toReal(f, rel);
    }

    public static String lastCompletedRoom() {
        return lastCompletedRoom;
    }

    public static long lastCompletedTick() {
        return lastCompletedTick;
    }

    private static void complete() {
        endSecretGap("the route completed");
        justFinished = true;
        RouteRunMemory.noteProgress(route.roomName(), ordered.size(), ordered.size());
        completions++;
        lastCompletedRoom = route.roomName();
        lastCompletedTick = com.killer560.hub.livemap.autoclear.ClearExecutor.clientTick();
        if (secretLeftThisRun) {
            // An await was let go on a refused click (refusedClickLetGo): the room is NOT fully secreted, so Auto Secret
            // and Autopilot must not count it so.
            LOGGER.info("[AutoRoutes] run memory: {}'s route ran to the end with a secret left (a refused click) - not "
                    + "counted as fully secreted", route.roomName());
        } else {
            RouteRunMemory.routeCompleted(route.roomName());
        }
        LOGGER.info("[AutoRoutes] Route \"{}\" complete", route.roomName());
        stop(null); // logged above, never said (killer560, 2026-10-16: "remove the auto routes ... complete lines")
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
        if (running && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[AutoRoutes] server position packet #{} at route tick {} (step {})", positionPackets,
                    execTicks, step);
        }
    }

    /** {@link #execTicks} of the last use packet HIS right click sent while a route ran (see {@link #onUseSent}). */
    private static long manualUseAt = Long.MIN_VALUE / 2;
    /** True while {@link #useHeldItem} sends the route's own use, so the send tap never takes it for his. */
    private static boolean routeUsing;

    /**
     * From the send tap ({@code RightClickSendMixin}) for every use / use-on packet as it leaves the client. One sent
     * while the physical use button is down, and not by {@link #useHeldItem}, is his own right click - a manual etherwarp
     * or transmission mid-route (killer560, 2026-10-19: "make sure stuff like etherwarping through mobs and things wont
     * block the auto routes"). A position packet a round trip or so after it is his own teleport, not a server correction:
     * {@link #checkCorrections} then carries on exactly as before but without the correction alarm.
     */
    /** {@link com.killer560.hub.util.ActionGate#currentTick} of the last dig, swap, use, block or entity click or swing
     *  that left this client (anyone's): see {@link #quietThisTick}. */
    private static volatile long lastActionPacketTick = Long.MIN_VALUE;

    /** From the send tap ({@code RightClickSendMixin}): a packet that acts on the world is leaving. */
    public static void onActionPacket() {
        lastActionPacketTick = com.killer560.hub.util.ActionGate.currentTick();
    }

    /** Nothing that acts on the world has left this client in this client tick so far - so a node can start now without
     *  putting a second swap, use or dig beside one already sent this tick (one item use per tick, GrimAC BadPacketsA/J). */
    static boolean quietThisTick() {
        return lastActionPacketTick != com.killer560.hub.util.ActionGate.currentTick();
    }

    public static void onUseSent() {
        if (!running || routeUsing) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (useButtonPhysicallyDown(mc)) {
            if (execTicks - manualUseAt > 4) {
                LOGGER.info("[AutoRoutes] your own right click went out mid-route at tick {} (node {}) - a teleport in the "
                        + "next {} tick(s) is yours, not a correction", execTicks, nodeTag(activeNode),
                        warpRoundTripTicks() * NO_ACK_ROUND_TRIPS);
            }
            manualUseAt = execTicks;
        }
    }

    /** The physical key or mouse button bound to use, ignoring the mapping's own down-flag (a crypt node holds it). */
    private static boolean useButtonPhysicallyDown(Minecraft client) {
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key =
                    ((com.killer560.hub.autoroutes.mixin.KeyMappingKeyAccessor) (Object) client.options.keyUse)
                            .killer560smod$getKey();
            if (key == null) {
                return false;
            }
            if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
                return org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue())
                        == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            }
            return key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                    && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), key.getValue());
        } catch (Throwable t) {
            return false;
        }
    }

    /** His own right click went out within a few round trips: a teleport now is his, not the server's. */
    private static boolean manualTeleportLikely() {
        return execTicks - manualUseAt <= (long) warpRoundTripTicks() * NO_ACK_ROUND_TRIPS;
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
            LOGGER.debug("[AutoRoutes] position packet moved {} (< {}) - rotation-only, not a correction",
                    ServerCorrections.lastMoveDistance(), ServerCorrections.MIN_MOVE_BLOCKS);
            return; // rotation-only: he is where he was
        }
        RouteNode node = activeNode;
        if (node != null && node.type == RouteNode.Type.PATH && trapWalk) {
            // A walked trap-room pair has no teleport of its own: this is the server putting him back. Honoured, never
            // fought, never a stop - AP3's walk planner plans the walk again from where he now is. Checked BEFORE the
            // warp branches below, so a correction mid-walk can never re-fly a hop saved on the pair (killer560,
            // 2026-10-20: a trap-room re-plan only ever walks).
            reportCorrection(String.format(Locale.US, "during path #%d's walk in a trap room - walking on from here",
                    route.indexOf(node) + 1));
            Ap3TrapWalk.onCorrection(client, player);
            return;
        }
        // The WARP's confirm, not the await's: tickAwait runs its own PREP/CONFIRM cycle on the same step field before
        // the node acts, and a packet then was judged against warpLanding - unset for this node, so another warp's - and
        // let a start node go that had sent nothing (killer560, 2026-10-07: "the interactive map was off when it tried
        // to take me to the start node", testkit 96-ar-405-startwarp).
        boolean warping = node != null && awaitPhaseDone && step == Step.CONFIRM
                && (node.type == RouteNode.Type.ETHERWARP || node.type == RouteNode.Type.PATH);
        if (warping) {
            if (warpLanding == null || player.position().distanceTo(warpLanding) <= LANDING_TOLERANCE) {
                LOGGER.info("[AutoRoutes] position packet during {}'s warp{}: {} - its own landing, not a correction "
                                + "(moved {}, {} tick(s) after the use)", nodeTag(node),
                        hopIndex >= 0 ? " hop " + (hopIndex + 1) : "", warpLanding == null ? "no landing recorded"
                                : String.format(Locale.US, "%.2f from the landing", player.position().distanceTo(warpLanding)),
                        String.format(Locale.US, "%.2f", ServerCorrections.lastMoveDistance()), stepTicks);
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
            LOGGER.info("[AutoRoutes] position packet during node {} (step {}, await done {}) moved {} - presumed the "
                            + "node's own teleport, not a correction", nodeTag(node), step, awaitPhaseDone,
                    String.format(Locale.US, "%.2f", ServerCorrections.lastMoveDistance()));
            return; // the node's own teleport, or a warp not yet sent / already confirmed
        }
        if (execTicks <= ownTeleportUntil) {
            LOGGER.info("[AutoRoutes] position packet moved {} within the last node's teleport grace ({} tick(s) left) - "
                            + "presumed its own teleport, not a correction",
                    String.format(Locale.US, "%.2f", ServerCorrections.lastMoveDistance()), ownTeleportUntil - execTicks);
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
        LocalPlayer self = Minecraft.getInstance().player;
        if (manualTeleportLikely()) {
            // His own right click (a manual etherwarp, a transmission) teleported him: the route handles the move exactly
            // as it would a correction, but no alarm and no correction line (onUseSent).
            LOGGER.info("[AutoRoutes] Manual teleport tolerated: your own right click {} tick(s) ago moved you {} blocks {}"
                    + " - not a server correction, no alarm; the route carries on", execTicks - manualUseAt,
                    String.format(Locale.US, "%.1f", ServerCorrections.lastMoveDistance()), what);
            manualUseAt = Long.MIN_VALUE / 2;
            RouteRotation.rebase();
            cameraGraceTicks = Math.max(cameraGraceTicks, 3);
            return;
        }
        RouteTrace.correction(traceIndex(activeNode), activeNode == null ? "-" : traceWhat(activeNode),
                self == null ? null : self.position(), ServerCorrections.lastMoveDistance(), what);
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
        int before = cursor;
        cursor = path.nearest(0, cursor + SEEK_WINDOW, rel.x, rel.y, rel.z);
        LOGGER.info("[AutoRoutes] path cursor relocated after a move: sample {} -> {} of {} (player {})", before, cursor,
                path.size(), fmt(player.position()));
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
    }

    /**
     * A warp the correction took him away from: the node is let go WITHOUT counting as done - on a path-less route the
     * walk step fires whichever node he stands in next (this one again if he walks back into it), on a recorded one the
     * cursor rejoins the path where he is. The rest of the stack goes with it; nothing stops.
     */
    private static void letGoAfterCorrection(Minecraft client, LocalPlayer player) {
        LOGGER.info("[AutoRoutes] node {} let go uncounted after {} tick(s) in step {}: {} stacked node(s) dropped, settle "
                        + "{} tick(s), player {} - the route carries on from here", nodeTag(activeNode), stepTicks, step,
                stackQueue.size(), AutoRoutesConfig.getInstance().getInteractDelayTicks(), fmt(player.position()));
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
        if (!mixinApplied) {
            LOGGER.info("[AutoRoutes] input mixin applied - the route drives through the Input record");
        }
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
            if (handsLatched) {
                LOGGER.info("[AutoRoutes] your movement keys are all up at tick {} - a fresh press from now counts as "
                        + "taking over", execTicks);
            }
            handsLatched = false;
            return isDriving();
        }
        // Between two nodes of one stack the route still owns the input: the stack is one unit.
        boolean acting = activeNode != null || !stackQueue.isEmpty();
        if (handsLatched) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[AutoRoutes] movement key held since the route took the input - overridden (driven {}, "
                        + "acting {}, walk hold {})", driven, acting, walkHoldYaw != null);
            }
            return driven || acting || walkHoldYaw != null;
        }
        if (arrivalChain) {
            LOGGER.info("[AutoRoutes] fresh movement key during a landing loop (node {}) - stopping", nodeTag(activeNode));
            stop("you moved");
            return false;
        }
        if (driven) {
            LOGGER.info("[AutoRoutes] fresh movement key on a recorded route (node {}, sample {}) - stopping",
                    nodeTag(activeNode), cursor);
            stop("you moved");
            return false;
        }
        if (walkHoldYaw != null) {
            LOGGER.info("[AutoRoutes] fresh movement key - the held walk sprint is handed back to you (route keeps running)");
            endWalkHold();
            RouteRotation.clear();
            return false;
        }
        if (trapWalk && activeNode != null && activeNode.type == RouteNode.Type.PATH) {
            // A trap-room path walk drives him for as long as the walk takes, like a recorded route: a fresh press is him
            // taking over, not something to override.
            LOGGER.info("[AutoRoutes] fresh movement key during node {}'s trap-room walk - stopping", nodeTag(activeNode));
            stop("you moved");
            return false;
        }
        if (breakerWaiting()) {
            // A breaker node waiting for charges can wait up to 30 s: a fresh movement key is him taking over, on a
            // path-less route too (a node's own few ticks of action override it; this is not a few ticks).
            LOGGER.info("[AutoRoutes] fresh movement key while breaker node {} waited {} tick(s) for charges - stopping",
                    nodeTag(activeNode), breakerWaitTicks);
            stop("you moved");
            return false;
        }
        if (acting) {
            logHold("keys-override", "fresh movement key during node {} (path-less) - overridden for its action",
                    nodeTag(activeNode));
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
        int bits = (wantForward ? 1 : 0) | (wantBackward ? 2 : 0) | (wantLeft ? 4 : 0) | (wantRight ? 8 : 0)
                | (wantJump ? 16 : 0) | (wantSneak ? 32 : 0) | (wantSprint ? 64 : 0);
        if (bits != lastInputBits) {
            boolean sneakChanged = ((bits ^ lastInputBits) & 32) != 0 || lastInputBits < 0;
            lastInputBits = bits;
            if (sneakChanged) {
                LOGGER.info("[AutoRoutes] input installed at tick {}: {} (node {})", execTicks, inputString(bits),
                        nodeTag(activeNode));
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[AutoRoutes] input installed at tick {}: {}", execTicks, inputString(bits));
            }
        }
        return new Input(wantForward, wantBackward, wantLeft, wantRight, wantJump, wantSneak, wantSprint);
    }

    /** The last input {@link #drivenInput} installed (logging only), -1 before any. */
    private static int lastInputBits = -1;

    private static String inputString(int bits) {
        StringBuilder sb = new StringBuilder();
        String[] names = {"W", "S", "A", "D", "jump", "sneak", "sprint"};
        for (int i = 0; i < names.length; i++) {
            if ((bits & (1 << i)) != 0) {
                sb.append(sb.length() == 0 ? "" : "+").append(names[i]);
            }
        }
        return sb.length() == 0 ? "no keys" : sb.toString();
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
            LOGGER.info("[AutoRoutes] world change at tick {}: player {} level {} same level as the start {}", execTicks,
                    player != null, client.level != null, client.level == lastLevel);
            stop("world change");
            return;
        }
        RouteRunMemory.noteProgress(route.roomName(), nextNode, ordered.size());
        checkCorrections(client, player);
        if (AutoRoutesFeature.screenBlocks(client)) {
            if (activeNode != null && (awaitHeld || (activeNode.awaitEnabled && !nodeActed))) {
                // Waiting on secrets: the screen is almost always the secret itself - a chest's own window
                // (killer560's await:2 waits for exactly that). The wait carries on under it, the keys stay off,
                // and the node fires on the first tick the screen is closed. Anything else still stops the route.
                clearMovement();
                wantSneak = false;
                applyFallbackKeys(client);
                logHold("screen-await:" + traceIndex(activeNode), "node {} waits under a screen ({}) - await done {}, "
                                + "{} tick(s) since firing; keys off", nodeTag(activeNode),
                        McCompat.screen(client) == null ? "none" : McCompat.screen(client).getClass().getSimpleName(),
                        awaitPhaseDone, actionAge);
                if (awaitPhaseDone) {
                    // The await was met a tick before the chest's window arrived (the secret count and the window
                    // come in either order): the action had started but sent nothing, so it starts over, cleanly,
                    // when the window closes - sneak, aim and all.
                    if (step != Step.PREP || stepTicks != 0) {
                        LOGGER.info("[AutoRoutes] Node #{} {}: the clicked chest's window arrived after the await was met "
                                + "- the action starts over when it closes", route.indexOf(activeNode) + 1, activeNode.type);
                    }
                    step = Step.PREP;
                    stepTicks = 0;
                } else {
                    stepTicks++;
                    actionAge++;
                    tickAwait(client, player, activeNode);
                    if (awaitPhaseDone) {
                        awaitDoneAge = actionAge;
                        startSecretGap(activeNode);
                        noteSecretGap("met under a screen - fires when it closes");
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
                    noteSecretGap("the clicked chest's window opened - waits under it until it closes");
                    LOGGER.info("[AutoRoutes] The clicked chest's window arrived after the route moved on - waiting "
                            + "under it, carries on when it closes");
                }
                lateChestWindow = screen;
                clearMovement();
                wantSneak = false;
                applyFallbackKeys(client);
                return;
            }
            LOGGER.info("[AutoRoutes] screen {} opened during node {} (step {}, await held {}) - not a chest window of a "
                            + "click in the last {} tick(s), so the route stops",
                    screen == null ? "null" : screen.getClass().getSimpleName(), nodeTag(activeNode), step, awaitHeld,
                    LATE_CHEST_WINDOW_TICKS);
            stop("a screen opened");
            return;
        }
        if (lateChestWindow != null) {
            LOGGER.info("[AutoRoutes] the late chest window closed - carrying on (tick {})", execTicks);
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
            // Held while the server is stalled: the grace covers a landing still to come, and a stall delays it.
            if (!com.killer560.hub.util.ServerLag.stalled()) {
                cameraGraceTicks--;
            }
        } else if (RouteRotation.userMovedCamera(player)) {
            if (activeNode != null && awaitHeld) {
                // Waiting on its await, the node is waiting for HIM: turning to click the chest it waits for is not
                // taking the route back (found 2026-10-05, 62-argrim: the await stopped as "you moved the camera").
                logHold("camera-await", "you turned the camera while node {} waits on its await - not a takeover, "
                        + "the camera ease is dropped", nodeTag(activeNode));
                RouteRotation.clear();
            } else if (!route.path().isEmpty() || activeNode != null || !stackQueue.isEmpty()) {
                LOGGER.info("[AutoRoutes] camera moved by you during node {} (step {}, {} stacked, recorded path {}) - "
                        + "stopping", nodeTag(activeNode), step, stackQueue.size(), !route.path().isEmpty());
                stop("you moved the camera");
                return;
            }
            // A path-less route between nodes: only a held walk was turning the camera - hand it back.
            if (walkHoldYaw != null) {
                LOGGER.info("[AutoRoutes] camera moved by you between nodes - the held walk is handed back to you");
            }
            endWalkHold();
            RouteRotation.clear();
        }
        // No click check (killer560, 2026-10-04: "Auto routes should not stop if I click"). A click used to stop a
        // driven route outright and skip an await on any route, so clicking the chest an await:2 was waiting on
        // fired the node early.
        RouteCoords.Frame now = RouteCoords.Frame.current();
        if ((now == null || !now.sameRoom(frame)) && trapExitPending()) {
            if (!trapExitSaid) {
                trapExitSaid = true;
                LOGGER.info("[AutoRoutes] the map has him in {} at {}, but Auto Trap still has him in {} - the trap route"
                        + " goes on until Auto Trap sees him out", now == null ? "no room" : now.roomName(),
                        fmt(player.position()), frame.roomName());
            }
        } else if (now == null || !now.sameRoom(frame)) {
            LOGGER.info("[AutoRoutes] room check failed at tick {}: now {} vs the route's {} (player {})", execTicks,
                    now == null ? "no room" : now.roomName(), frame.roomName(), fmt(player.position()));
            stop("left the room");
            return;
        }
        for (String line : AwaitEvents.drain()) {
            RouteNode waiting = activeNode != null && !awaitPhaseDone ? activeNode : null;
            LOGGER.info("[AutoRoutes] {}{}", line, waiting == null ? ""
                    : " - node #" + (route.indexOf(waiting) + 1) + " waits for " + Math.max(1, waiting.awaitAmount));
        }
        // A warp that is WAITING (held before its use, or a round trip on with no landing) takes the click itself in
        // tickWarp and goes off with it; only a click while one is really in flight is held for the next await.
        if (teleportingBetweenAwaits() && !warpClickReleases() && takeSkipClick(client)) {
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
            logHold("mimic", "Kill Mimic has the hands - route paused at node {} (await done {}), sneak let go",
                    nodeTag(activeNode), awaitPhaseDone);
            clearMovement();
            forceSneak = false;
            wantSneak = false;
            if (activeNode != null && !awaitPhaseDone) {
                stepTicks++;
                actionAge++;
                tickAwait(client, player, activeNode);
                if (awaitPhaseDone) {
                    awaitDoneAge = actionAge;
                    startSecretGap(activeNode);
                    noteSecretGap("met while Kill Mimic had the hands - waits for it");
                }
            } else if (secretMetAt >= 0 && !mimicGapSaid) {
                mimicGapSaid = true;
                noteSecretGap("Kill Mimic has the hands - waiting for it");
            }
            applyFallbackKeys(client);
            return;
        }
        if (settleTicks > 0) {
            settleTicks--;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[AutoRoutes] settle: {} tick(s) left before the next node ({} stacked)", settleTicks,
                        stackQueue.size());
            }
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
            // A node finished this tick and the next may go NOW (settleAfter): after a server-confirmed landing (handled
            // before this tick began, and vanilla answered it with its teleport accept and a position packet of its own),
            // or - obvious mode, killer560 2026-10-19: "zero delay ever" - after any node that sent nothing this tick (a
            // breaker or boom whose block update just came, a path arrival, a kill counted). The rest of the stack, else
            // whatever the walk step fires: the landing re-fire's node, or the next node he stands in. Bounded: a stack of
            // nodes that each finish at once (arrivals, turns) runs through in one tick, but never more than CHAIN_MAX.
            for (int chained = 0; running && activeNode == null && chainAfterLanding && chained < CHAIN_MAX; chained++) {
                chainAfterLanding = false;
                clearMovement();
                LOGGER.info("[AutoRoutes] {} - the next node goes this same tick ({}) at tick {}", chainWhy,
                        !stackQueue.isEmpty() ? "stacked " + nodeTag(stackQueue.peek()) : "walk step", execTicks);
                if (!stackQueue.isEmpty()) {
                    beginAction(stackQueue.poll());
                } else {
                    tickWalk(client, player);
                }
            }
            chainAfterLanding = false;
        } catch (Exception e) {
            LOGGER.error("[AutoRoutes] Playback error at tick {} in node {} step {} ({} tick(s) in step)", execTicks,
                    nodeTag(activeNode), step, stepTicks, e);
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
                LOGGER.info("[AutoRoutes] landed from node #{} at {} - inside no other node, the walk step goes on",
                        route.indexOf(from) + 1, fmt(pos));
            }
            if (nextNode >= ordered.size()) {
                if (walkHoldYaw == null) {
                    LOGGER.info("[AutoRoutes] walk step: every node in order has fired ({} of {}) - completing",
                            nextNode, ordered.size());
                    complete(); // a walk node at the end keeps going until it runs into something
                } else {
                    logHold("walk-end", "last node was a walk - sprinting on until it runs into something");
                }
                return;
            }
            RouteNode node = ordered.get(nextNode);
            Vec3 nodeAt = RouteCoords.toReal(frame, node.relativePos());
            if (node.contains(nodeAt, cfg.getHeight(), player.getBoundingBox())) {
                if (refireHeld(client, player, node)) {
                    return;
                }
                beginStack(node);
            } else if (++idleTicks == IDLE_SAY_TICKS && walkHoldYaw == null && !idleSaid) {
                // Normal while he walks to it himself; after a warp it names a landing that missed the next ring.
                idleSaid = true;
                RouteTrace.idle(traceIndex(node), node.type, idleTicks, pos, nodeAt, pos.distanceTo(nodeAt));
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
                LOGGER.info("[AutoRoutes] path: rubber-banded back from sample {} to {} (player {}) - resuming from there",
                        cursor, back, fmt(pos));
                cursor = back;
                noProgressTicks = 0;
                bestTargetDistance = Double.MAX_VALUE;
            }
        }
        if (nextNode < ordered.size() && cursor >= ordered.get(nextNode).pathIndex) {
            clearMovement();
            if (refireHeld(client, player, ordered.get(nextNode))) {
                return;
            }
            LOGGER.info("[AutoRoutes] path: reached sample {} (node {} anchored at {}) - firing it", cursor,
                    nodeTag(ordered.get(nextNode)), ordered.get(nextNode).pathIndex);
            beginStack(ordered.get(nextNode));
            return;
        }
        if (cursor >= path.size() - 1) {
            if (nextNode >= ordered.size()) {
                LOGGER.info("[AutoRoutes] path: end of the recorded path (sample {}) with every node fired - completing",
                        cursor);
                complete();
            } else {
                // Nodes anchored past the end of the path (hand-edited file) - run them where we stand.
                LOGGER.info("[AutoRoutes] path: end of the recorded path, node {} anchored past it ({}) - firing it here",
                        nodeTag(ordered.get(nextNode)), ordered.get(nextNode).pathIndex);
                clearMovement();
                beginStack(ordered.get(nextNode));
            }
            return;
        }

        RoutePath.Sample at = path.get(cursor);
        double drift = at.distance(rel.x, rel.y, rel.z);
        if (drift > cfg.getMaxDriftDistance()) {
            LOGGER.info("[AutoRoutes] path: {} blocks off sample {} (max drift {}) - player {} - stopping",
                    String.format(Locale.US, "%.2f", drift), cursor, cfg.getMaxDriftDistance(), fmt(pos));
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
            LOGGER.info("[AutoRoutes] path: no progress toward sample {} for {} tick(s) (limit {}, {} blocks off, best {}) - "
                            + "player {} - stopping", targetIdx, noProgressTicks, cfg.getReachTimeoutTicks(),
                    String.format(Locale.US, "%.2f", targetDistance), String.format(Locale.US, "%.2f", bestTargetDistance),
                    fmt(pos));
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
            LOGGER.info("[AutoRoutes] walk hold: no movement for {} tick(s) at {} - it hit something, letting go",
                    walkHoldStallTicks, fmt(pos));
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
        LOGGER.info("[AutoRoutes] fallback key mappings released (input mixin not applied)");
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
        if (refireHoldAt != null) {
            LOGGER.info("[AutoRoutes] the hold after an unlanded warp ends - node {} fires", nodeTag(trigger));
        }
        refireHoldAt = null; // any stack firing (a landing elsewhere, his click) ends the hold
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
        endSecretGap("node #" + (route == null ? "?" : String.valueOf(route.indexOf(node) + 1)) + " " + node.type + " fired");
        // Another node fired: a breaker node still mining what was out of reach stops here (see tickBreakerLinger). A path
        // arrival sends nothing (tickPath finishes it at once), so it leaves the linger running - killer560, 2026-10-19:
        // a zero-hop arrival ended the linger and the column below a shaft was never dug as he fell.
        boolean sendsNothing = node.type == RouteNode.Type.PATH && (route == null || !route.isPathSource(node));
        if (!sendsNothing) {
            clearBreakerLinger(node.type == RouteNode.Type.DUNGEON_BREAKER ? "the breaker fired again" : "another node fired");
        }
        LocalPlayer self = Minecraft.getInstance().player;
        if (node.type == RouteNode.Type.ETHERWARP) {
            Vec3 at = RouteCoords.toReal(frame, node.relativePos());
            LOGGER.info("[AutoRoutes] Node #{} ETHERWARP begins: player {} node {} landing {} (room {}, sim={}, {} mode)",
                    route.indexOf(node) + 1, fmt(self.position()), fmt(at),
                    node.hasLanding ? fmt(RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ))
                            : "not recorded",
                    frame.roomName(), com.killer560.hub.roomsim.SimState.canAct(Minecraft.getInstance()),
                    AutoRoutesConfig.getInstance().isLegitMode() ? "legit" : "obvious");
        } else {
            LOGGER.info("[AutoRoutes] Node #{} {} begins: player {} node {} | await {} | {} stacked after it | sneak held {} "
                            + "| run tick {}", route.indexOf(node) + 1, node.type, fmt(self.position()),
                    fmt(RouteCoords.toReal(frame, node.relativePos())), traceAwait(node), stackQueue.size(), forceSneak,
                    execTicks);
        }
        lastHoldKey = null; // a new node: its first wait logs at once
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
        nodeCarry = carry;
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
                    // tick, or while it acted - arrives before this node's window opens. It is this node's.
                    java.util.Set<String> seen = new java.util.HashSet<>(window);
                    remembered.forEach(g -> seen.add(g.key()));
                    for (RouteRunMemory.Got g : RouteRunMemory.unclaimedNearSince(nodeAt, carry, seen)) {
                        remembered.add(g);
                        LOGGER.info("[AutoRoutes] Node #{} {}: await counts {} - got {} tick(s) ago, while the node "
                                + "before it was still acting", route.indexOf(node) + 1, node.type,
                                g.what(), Math.max(0, AwaitEvents.now() - g.tick()));
                    }
                }
                // A Redstone Key spot in reach of this node that the key never spawned at: one secret prefilled for it
                // (killer560, 2026-10-18 - a room with two key spots needs an await at each, and the empty one used to
                // wait for a skull that was never there). KeySpots says how a spot is judged empty.
                if (node.awaitCondition == RouteNode.AwaitCondition.SECRET) {
                    for (BlockPos spot : KeySpots.emptyInReach(Minecraft.getInstance(), frame, nodeAt)) {
                        String what = "redstone key spot at " + spot.toShortString() + " (the key never spawned there)";
                        remembered.add(new RouteRunMemory.Got("keyspot " + spot.asLong(), Vec3.atCenterOf(spot),
                                AwaitEvents.now(), what));
                        LOGGER.info("[AutoRoutes] Node #{} {}: await prefilled 1 - {}", route.indexOf(node) + 1, node.type,
                                what);
                    }
                }
                awaitRemembered = remembered;
                int already = AwaitEvents.secrets() + remembered.size();
                if (!remembered.isEmpty() && already >= Math.max(1, node.awaitAmount)) {
                    // killer560, 2026-10-18: a secret he already picked up satisfies the await now - the route moves on
                    // on the next tick (tickAwait), it does not wait for a second pickup. Logged so the log shows it.
                    LOGGER.info("[AutoRoutes] Node #{} {}: await satisfied at once by {}/{} already-collected secret(s) - "
                            + "not waiting for another", route.indexOf(node) + 1, node.type, already,
                            Math.max(1, node.awaitAmount));
                }
            }
        }
        awaitHeld = !awaitPhaseDone;
        nodeActed = false;
        breakerQueue = new ArrayList<>();
        breakerSent.clear();
        breakerSentAt.clear();
        boomRetried = false;
        breakerObsidian.clear();
        obsidianDigPos = null;
        breakerReady = false;
        breakerWaitTicks = 0;
        breakerWaitSaid = false;
        hopIndex = -1;
        if (trapWalk) {
            Ap3TrapWalk.cancel("another node fired");
        }
        trapWalk = false;
        // The last warp's landing and aim belong to that warp. Left over, checkCorrections measured a packet during
        // this node's await from them - in killer560's 2026-10-07 log from the landing of #6 of a run stopped two
        // minutes earlier, "47.4 from its landing" - and let the start node go (see checkCorrections).
        warpLanding = null;
        warpTarget = null;
        warpSpot = null;
        warpAimFrom = null;
        lineWaitTicks = 0;
        lineWaitBlock = null;
        useBorderHoldSinceMs = -1;
        jumpsDone = 0;
        landingConfirmed = false;
        planAsked = false;
        planOutcome = null;
        blockWaitTicks = 0;
        blockWaitSaid = false;
        boomTarget = null;
        boomPlanned = null;
        boomPlannedFace = null;
        boomPlannedHit = null;
        boomBefore.clear();
        cryptSlotBefore = -1;
        releaseUse(Minecraft.getInstance());
        awaitWaitWarned = false;
        idleTicks = 0;
        idleSaid = false;
        BlockPos traceAt = traceTarget(node);
        RouteTrace.start(traceIndex(node), node.type, self.position(), traceAt, blockName(traceAt), traceAwait(node));
        // The node acts NOW, on the tick it fired - not on the next tick's pass through tick(). Waiting for that pass
        // was one of the dead ticks in his 2026-10-04 log (an etherwarp took 3-4 ticks from "begins" to the use).
        Minecraft client = Minecraft.getInstance();
        try {
            tickAction(client, self);
        } catch (Exception e) {
            LOGGER.error("[AutoRoutes] Playback error on node {}'s firing tick (step {})", nodeTag(node), step, e);
            stop("internal error (see log)");
        }
    }

    // ------------------------------------------------------------------------------------------- trace (RouteTrace)

    /** The await-waiting WARN was said for the node acting now (once per node). */
    private static boolean awaitWaitWarned;
    /** Ticks a path-less route has stood with no node firing, and whether that gap was said. */
    private static int idleTicks;
    private static boolean idleSaid;
    /** Whether this run already logged that a trap route goes on past the map's room edge (trapExitPending). */
    private static boolean trapExitSaid;
    /** A path-less route idle this long (he stands in no next node's ring) says where the next node is, once. */
    private static final int IDLE_SAY_TICKS = 60;
    /** An await waiting this long says what it has counted, once (a WARN: the route has gone nowhere for 10 s). */
    private static final long AWAIT_WARN_MS = 10_000L;

    private static int traceIndex(RouteNode node) {
        return route == null || node == null ? 0 : route.indexOf(node) + 1;
    }

    /** The block a node aims at: the block its recorded landing stands on, else the block under the node itself. */
    private static BlockPos traceTarget(RouteNode node) {
        if (frame == null || node == null) {
            return null;
        }
        if (node.hasLanding) {
            return landingBlock(RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ));
        }
        Vec3 at = RouteCoords.toReal(frame, node.relativePos());
        return BlockPos.containing(at.x, at.y - 0.5, at.z);
    }

    private static String blockName(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (pos == null || client.level == null || !client.level.isLoaded(pos)) {
            return null;
        }
        return client.level.getBlockState(pos).getBlock().getName().getString();
    }

    private static String traceAwait(RouteNode node) {
        if (!node.awaitEnabled) {
            return "none";
        }
        // "1 secret", never "secret 1": AwaitEvents' own count lines read "await secret 1: ..." and log readers (the
        // testkit included) find them by that text.
        return Math.max(1, node.awaitAmount) + " "
                + (node.awaitCondition == null ? "?" : node.awaitCondition.name().toLowerCase(Locale.ROOT));
    }

    private static void finishAction() {
        RouteNode finished = activeNode;
        if (finished != null && (finished.type == RouteNode.Type.USE_ITEM || finished.type == RouteNode.Type.COMMAND)) {
            ownTeleportUntil = execTicks + OWN_TELEPORT_GRACE_TICKS; // its teleport, if it has one, may still be coming
        } else if (finished != null && (finished.type == RouteNode.Type.ETHERWARP
                || (finished.type == RouteNode.Type.PATH && !trapWalk))) {
            ownTeleportUntil = Math.max(ownTeleportUntil, execTicks + 3); // a late duplicate of the landing packet
        }
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        // Release the camera as soon as the node is done. Without this a QUOI-style path-less route (nodes
        // only, no recorded walk) kept pulling the view back to the finished node's yaw every frame while
        // the player tried to walk to the next one themselves (2026-09-16 review).
        RouteRotation.clear();
        activeNode = null;
        step = null;
        // The next node's await counts what this node's window got (everything since it opened: his pickups while this
        // node acted, Hypixel's pickup packet in the same batch as a warp's landing, a secret taken while the node waited)
        // that no met await used - plus whatever the node before it carried, if that is older. Anything a met await used
        // is claimed (RouteRunMemory.met) and never counted twice. The old bound (the last CARRY_TICKS before this finish)
        // let a secret taken early in a long action fall out, and the next await sat waiting for it (2026-10-18).
        carryFrom = Math.min(AwaitEvents.windowStart(), nodeCarry);
        nodeCarry = Integer.MAX_VALUE;
        AwaitEvents.window(Minecraft.getInstance());
        boolean landed = landingConfirmed;
        landingConfirmed = false;
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
        if (!stackQueue.isEmpty()) {
            planSneak(finished);
            settleAfter(landed, cfg);
            logFinished(finished, landed);
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
        logFinished(finished, landed);
        if (nothingFollows()) {
            LOGGER.info("[AutoRoutes] nothing follows node {} - the route completes now (no interact delay)",
                    nodeTag(finished));
            // killer560, 2026-10-15: "it needs to predict when it is going to finish auto routing a room that way the very
            // next tick it starts etherwarping to the next room". The interact delay is the wait before the NEXT node; with
            // none, the route is over now - it used to complete only after that delay and a tick of walk step, and Auto
            // Secret's next warp waited for it.
            settleTicks = 0;
            chainAfterLanding = false;
            complete();
        }
    }

    /** One line per finished node: how long it took, what comes next, and the wait before it (logging only). */
    private static void logFinished(RouteNode finished, boolean landed) {
        if (finished == null) {
            return;
        }
        LOGGER.info("[AutoRoutes] Node {} finished {} tick(s) after firing ({} after its await) | server-confirmed landing "
                        + "{} | next {}{} | settle {} tick(s) | chain same tick {} | {} stacked left | next in order {}/{}",
                nodeTag(finished), actionAge, actionAge - awaitDoneAge, landed, nodeTag(plannedNext),
                plannedNext == null ? "" : plannedInPlace ? " (fires where you stand)" : " (walk to it)", settleTicks,
                chainAfterLanding, stackQueue.size(), nextNode, ordered.size());
    }

    /**
     * Nothing is left for this run to fire after the node just finished: no stack, no node he landed in, no next node in
     * order, no held walk, and on a recorded route no walk left to the end of the path.
     */
    private static boolean nothingFollows() {
        if (!running || route == null || !stackQueue.isEmpty() || plannedNext != null || walkHoldYaw != null
                || nextNode < ordered.size()) {
            return false;
        }
        RoutePath path = route.path();
        return path.isEmpty() || cursor >= path.size() - 1;
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
        if (!cfg.isLegitMode()) {
            // killer560, 2026-10-19: "It never waits after a chest or after an item for any reason ... Zero delay ever."
            // The Interact Delay held every transition but warp-into-warp 2 ticks (494 times in his 2026-10-19 logs, 988
            // ticks), though every node already waits for the server's own answer before it finishes (a landing, the
            // broken blocks, the boom's change, the kill). The next node goes on this same tick when nothing acting on the
            // world has left this tick (one swap / use / dig set per tick), else on the next one. The old reason for it - a
            // breaker fired on a landing tick broke nothing on the dedicated server (62-argrim-play, 2026-10-06, never
            // traced) - is now covered where it can go wrong: the breaker digs again whatever is still standing a round
            // trip on (tickBreaker), and a boom that changed nothing digs once more before it calls the wall open.
            settleTicks = 0;
            chainAfterLanding = warpNext || quietThisTick();
            chainWhy = warpNext ? "server-confirmed landing" : "node done with nothing sent this tick";
            return;
        }
        chainAfterLanding = warpNext;
        chainWhy = "server-confirmed landing";
        settleTicks = warpNext ? 0 : cfg.getInteractDelayTicks();
        if (settleTicks > 0) {
            noteSecretGap("Interact Delay setting " + settleTicks + " tick(s) before the next node (legit mode)");
        }
    }

    /** Most nodes {@link #tick} starts in one tick after the one it was ticking finishes. */
    private static final int CHAIN_MAX = 8;
    /** Why the chain in {@link #tick} goes on this tick (for its log line). */
    private static String chainWhy = "";

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
                case ETHERWARP -> inPlace || (was && pathless);
                // A path pair in a trap room walks (tickTrapWalk): nothing to sneak for.
                case PATH -> !inTrapRoom() && (inPlace || (was && pathless));
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
        if (++stepTicks == 1) {
            stepLag = com.killer560.hub.util.ServerLag.mark();
        }
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
            boolean skipped = false;
            if (!awaitPhaseDone && activeNode == node && (takeSkipClick(client) || (early = takeEarlySkip()))) {
                skipped = true;
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
            if (!skipped) {
                startSecretGap(node);
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
                    // The first word only: the rest of a hand-written command is his and may be anything.
                    int sp = cmd.indexOf(' ');
                    LOGGER.info("[AutoRoutes] Node #{} COMMAND: sending {} \"{}\" ({} chars) at tick {}",
                            route.indexOf(node) + 1, cmd.startsWith("/") ? "command" : "chat line",
                            sp < 0 ? cmd : cmd.substring(0, sp) + " ...", cmd.length(), execTicks);
                    if (cmd.startsWith("/")) {
                        player.connection.sendCommand(cmd.substring(1));
                    } else {
                        player.connection.sendChat(cmd);
                    }
                } else {
                    LOGGER.info("[AutoRoutes] Node #{} COMMAND: no command text - nothing sent", route.indexOf(node) + 1);
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
                logHold("jump-ground:" + traceIndex(node), "node {} JUMP: jump {} of {} waits for the ground ({} of {} "
                        + "tick(s))", nodeTag(node), jumpsDone + 1, want, stepTicks, JUMP_GROUND_TIMEOUT);
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
        LOGGER.info("[AutoRoutes] Node #{} JUMP: all {} jump(s) done {} tick(s) after firing - its tile will not arm for {} ms",
                route.indexOf(node) + 1, jumpsDone, actionAge, JUMP_COOLDOWN_MS);
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
            LOGGER.info("[AutoRoutes] Node #{} ROTATE: turning to yaw {} pitch {} ({})", route.indexOf(node) + 1,
                    String.format(Locale.ROOT, "%.2f", RouteCoords.toRealYaw(frame, node.yaw)),
                    String.format(Locale.ROOT, "%.2f", node.pitch),
                    AutoRoutesConfig.getInstance().isLegitMode() ? "legit camera turn" : "obvious - nothing to turn");
            aimAt(node);
            step = Step.AIM;
            stepTicks = 0;
        }
        // !isActive() covers obvious mode, where aimAt() snaps and clears the controller instead of
        // running an approach - settled() is false forever in that case, so the node used to sit out the
        // whole AIM_TIMEOUT before moving on (2026-09-16 review). It now finishes on the tick it fired.
        if (!RouteRotation.isActive() || RouteRotation.settled(1.5f) || stepTicks > AIM_TIMEOUT) {
            if (RouteRotation.isActive() && !RouteRotation.settled(1.5f)) {
                LocalPlayer p = Minecraft.getInstance().player;
                RouteTrace.timeout(traceIndex(node), node.type.name(), "camera turn never settled within 1.5 degrees - "
                        + "going on anyway", stepTicks, p == null ? null : p.position());
            }
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
            LOGGER.info("[AutoRoutes] your left click taken from vanilla at tick {} (node {}, step {}, {} tick(s) in step)",
                    execTicks, nodeTag(activeNode), step, stepTicks);
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

    /**
     * The warp under way is waiting rather than in flight, so his left click is its trigger, taken by {@link #tickWarp}
     * (killer560, 2026-10-19: "left-clicking a node that is waiting for something does not make it go off" - his clicks
     * while Slime #9's warp sat 60 ticks with no landing were all held for an await that never came). Waiting = held
     * before the use (landing block missing, a breaker pick or a new block in its line), or the use went out a round
     * trip ago and no teleport has come.
     */
    private static boolean warpClickReleases() {
        RouteNode n = activeNode;
        if (!running || n == null || !awaitPhaseDone
                || (n.type != RouteNode.Type.ETHERWARP && n.type != RouteNode.Type.PATH)) {
            return false;
        }
        if (step == Step.AIM) {
            return blockWaitTicks > 0 || pickWaitBlock != null || lineWaitTicks > 0 || warpAimHoldTicks > 0;
        }
        return step == Step.CONFIRM && !teleportPacketSeen && stepTicks > warpRoundTripTicks();
    }

    /** A round trip for a warp's use, in ticks: the eaten-use retry wait plus any server stall. */
    private static int warpRoundTripTicks() {
        return com.killer560.hub.util.RightClick.eatenRetryTicks() + serverLagTicks();
    }

    /** His left click let the warp under way past what held it; logged at INFO so he can find it in latest.log. */
    private static void forceWarpPastHold(RouteNode node, String held, int heldTicks) {
        warpForced = true;
        LOGGER.info("[AutoRoutes] Left click on node #{} {}: released its wait ({}, {} tick(s)) - warping now",
                route.indexOf(node) + 1, node.type, held, heldTicks);
        blockWaitTicks = 0;
        blockWaitSaid = false;
        pickWaitBlock = null;
        lineWaitTicks = 0;
        lineWaitBlock = null;
    }

    /**
     * {@link #refireHoldAt}: true while the node he stands in is held after a warp from here never landed. Moving off the
     * spot or a left click lets it fire again (both logged at INFO).
     */
    private static boolean refireHeld(Minecraft client, LocalPlayer player, RouteNode node) {
        if (refireHoldAt == null) {
            return false;
        }
        double moved = player.position().distanceTo(refireHoldAt);
        if (moved > 0.5) {
            LOGGER.info("[AutoRoutes] Node #{} {}: hold after the unlanded warp cleared - you moved {} blocks",
                    route.indexOf(node) + 1, node.type, String.format(Locale.US, "%.2f", moved));
            refireHoldAt = null;
            return false;
        }
        if (takeSkipClick(client)) {
            LOGGER.info("[AutoRoutes] Left click on node #{} {}: released the hold after the unlanded warp - firing it "
                    + "again", route.indexOf(node) + 1, node.type);
            refireHoldAt = null;
            return false;
        }
        if (!refireHoldSaid) {
            refireHoldSaid = true;
            LOGGER.info("[AutoRoutes] Node #{} {}: held - its warp from here never landed; left click fires it again, "
                    + "or move off the spot", route.indexOf(node) + 1, node.type);
            AutoRoutesFeature.note(ModChat.text("Node #" + (route.indexOf(node) + 1)),
                    ModChat.dim(" held: its etherwarp never landed. Left click to fire it again."));
        }
        return true;
    }

    /** A left click held by {@link #tick} while the route warped him here, at most {@link #EARLY_SKIP_TICKS} old:
     *  true once, and it is used up. */
    private static boolean takeEarlySkip() {
        if (execTicks - earlySkipClickAt > EARLY_SKIP_TICKS) {
            return false;
        }
        LOGGER.info("[AutoRoutes] the left click held {} tick(s) ago is used now (node {})", execTicks - earlySkipClickAt,
                nodeTag(activeNode));
        earlySkipClickAt = Long.MIN_VALUE / 2;
        return true;
    }

    private static void tickAwait(Minecraft client, LocalPlayer player, RouteNode node) {
        boolean delay = node.awaitCondition == RouteNode.AwaitCondition.DELAY;
        if (step == Step.PREP) {
            awaitStartMs = System.currentTimeMillis();
            awaitLogSecond = 0;
            awaitGaveUpBase = com.killer560.hub.cheatutils.SecretAuraFeature.gaveUpCount();
            step = Step.CONFIRM;
            LOGGER.info("[AutoRoutes] secret wait start: node #{} {} waits for {} {} (window {} so far, {} remembered{}) - "
                            + "counts your chest/lever/skull clicks, your item pickups, a secret bat appearing within {} "
                            + "or killed by you, your mimic; a left click skips it", route.indexOf(node) + 1, node.type,
                    delay ? node.awaitAmount + " ms" : String.valueOf(Math.max(1, node.awaitAmount)),
                    delay ? "delay" : "secret(s)", AwaitEvents.secrets(), awaitRemembered.size(),
                    awaitRemembered.isEmpty() ? "" : ": " + awaitRemembered.stream().map(RouteRunMemory.Got::what).toList(),
                    AwaitEvents.BAT_SPAWN_RANGE);
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
                RouteTrace.awaitMet(traceIndex(node), node.type, have, Math.max(1, node.awaitAmount),
                        awaitRemembered.size(), (int) ((System.currentTimeMillis() - awaitStartMs) / 50L));
                java.util.Set<String> used = AwaitEvents.windowSecrets();
                for (RouteRunMemory.Got g : awaitRemembered) {
                    used.add(g.key());
                }
                RouteRunMemory.met(awaitNodeKey, used);
            } else if (refusedClickLetGo(client, node, have)) {
                // Let go uncounted: no RouteRunMemory.met, nothing claimed - the secret stays not collected.
                done = true;
            }
            long waitedMs = System.currentTimeMillis() - awaitStartMs;
            if (!done && waitedMs / 1000L > awaitLogSecond) {
                // Once a second while it waits: what it has, what it needs, and where he is (killer560, 2026-10-19:
                // "add a ton of loggers so it can find the cause" of Auto Routes sticking on secrets).
                awaitLogSecond = (int) (waitedMs / 1000L);
                LOGGER.info("[AutoRoutes] secret wait: node #{} {} still waiting {}/{} after {} tick(s) | window since tick "
                                + "{}: {} | remembered: {} | player {} | screen {} | stays: not enough secrets of yours "
                                + "seen yet", route.indexOf(node) + 1, node.type, have, Math.max(1, node.awaitAmount),
                        waitedMs / 50L, AwaitEvents.windowStart(), AwaitEvents.windowSecrets(),
                        awaitRemembered.stream().map(RouteRunMemory.Got::what).toList(),
                        String.format(Locale.US, "%.2f, %.2f, %.2f", player.getX(), player.getY(), player.getZ()),
                        com.killer560.hub.compat.McCompat.screen(client) == null ? "none"
                                : com.killer560.hub.compat.McCompat.screen(client).getClass().getSimpleName());
            }
            if (!done && !awaitWaitWarned && System.currentTimeMillis() - awaitStartMs >= AWAIT_WARN_MS) {
                awaitWaitWarned = true;
                RouteTrace.awaitWaiting(traceIndex(node), node.type, node.awaitCondition == null ? "?"
                                : node.awaitCondition.name().toLowerCase(Locale.ROOT), have, Math.max(1, node.awaitAmount),
                        awaitRemembered.size(), (int) ((System.currentTimeMillis() - awaitStartMs) / 1000L), player.position());
            }
        }
        if (done) {
            if (delay) {
                LOGGER.info("[AutoRoutes] Node #{} {}: await delay of {} ms over ({} ms waited) - acting now",
                        route.indexOf(node) + 1, node.type, node.awaitAmount, System.currentTimeMillis() - awaitStartMs);
            }
            awaitPhaseDone = true;
            actionFromTick = AwaitEvents.now();
            step = Step.PREP;
            stepTicks = 0;
        }
    }

    /** When an empty-hand use node began holding its click for the world border to lift (ms), or -1. */
    private static long useBorderHoldSinceMs = -1;
    /** An await of this route run was let go on a refused click: its completion does not mark the room fully secreted. */
    private static boolean secretLeftThisRun;
    /** Secret Aura's give-up count when the active await began (its give-ups since are this await's). */
    private static int awaitGaveUpBase;
    /** His own refused secret clicks (no Secret Aura retry coming) after which a waiting await lets go. */
    static final int REFUSED_CLICKS_LET_GO = 3;

    /**
     * killer560, 2026-10-20: "if I am trying to grab a chest when it leaps to me then it'll sit there and the auto route
     * freezes for forever because it couldn't click when it tried." A Shadow Assassin's leap puts the blocks round him
     * outside the client's world border, and vanilla refuses every click on them before sending it (util/
     * ShadowAssassinLeap). Secret Aura now holds its click until the border lifts and clicks then - the await meets on
     * that click as on any other. This is the bound for when it never registers: the await lets go, UNCOUNTED (no secret
     * added, nothing claimed in RouteRunMemory), when a click was refused and no click is still coming - Secret Aura gave
     * the secret up (its MAX_ATTEMPTS spent, refused each time) and has nothing else to take in reach, or with the aura
     * off, {@link #REFUSED_CLICKS_LET_GO} of his own clicks were refused. Every count here is a real event, never a timer.
     * Says so in chat (a real failure: a secret was left).
     */
    private static boolean refusedClickLetGo(Minecraft client, RouteNode node, int have) {
        int gaveUp = com.killer560.hub.cheatutils.SecretAuraFeature.gaveUpCount() - awaitGaveUpBase;
        int refused = AwaitEvents.refused();
        if (gaveUp <= 0 && refused < REFUSED_CLICKS_LET_GO) {
            return false;
        }
        String coming = com.killer560.hub.cheatutils.SecretAuraFeature.takingInReach(client, false);
        if (coming != null) {
            return false; // a click of Secret Aura's is still to come (held for the border, or its retry)
        }
        int index = route.indexOf(node) + 1;
        String what = gaveUp > 0 ? "Secret Aura gave up on the secret at "
                + com.killer560.hub.cheatutils.SecretAuraFeature.lastGaveUp() + " after its clicks were refused"
                : refused + " secret clicks were refused";
        String leap = com.killer560.hub.util.ShadowAssassinLeap.describe(client);
        LOGGER.info("[AutoRoutes] Node #{} {}: await let go at {}/{} secrets - {} ({} refused client-side this await, {}) - "
                        + "the secret is left uncollected", index, node.type, have, Math.max(1, node.awaitAmount), what, refused,
                leap);
        AutoRoutesFeature.chatBad("Node #" + index + ": a secret click never registered (" + what + "; " + leap
                + ") - going on without it, " + have + "/" + Math.max(1, node.awaitAmount) + " secrets.");
        secretLeftThisRun = true;
        return true;
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
            if (landedFrom != null) {
                LOGGER.info("[AutoRoutes] Node #{} ETHERWARP: landing re-fire armed (path-less, server teleport seen)",
                        route.indexOf(node) + 1);
            }
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
        if (trapWalk) {
            tickTrapWalk(client, player, node);
            return;
        }
        if (hopIndex >= 0 && inTrapRoom()) {
            // Never a saved hop in a trap room, however it got loaded: the pair starts over as a walk below.
            LOGGER.info("[AutoRoutes] Node #{} PATH: hop {} loaded in a trap room - walking the pair instead",
                    route.indexOf(node) + 1, hopIndex + 1);
            hopIndex = -1;
        }
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
            if (inTrapRoom()) {
                // killer560, 2026-10-19: in a trap room no ability works, so a path pair WALKS from its first node to its
                // second - AP3's path planner and driver (ap3/Ap3TrapWalk), no etherwarp. Other rooms fly as below.
                trapWalk = true;
                forceSneak = false;
                wantSneak = false;
                Vec3 to = RouteCoords.toReal(frame, dest.relativePos());
                // Arrive with the feet well inside the second node's ring, so the walk step finds him in it and fires it.
                double half = Math.max(0.1, dest.radius) / 2.0 * 0.8;
                final Route walkRoute = route;
                final RouteCoords.Frame walkFrame = frame;
                Ap3TrapWalk.begin(client, player, to, half, "Path #" + (route.indexOf(node) + 1) + " -> #"
                        + (route.indexOf(dest) + 1) + " in " + frame.roomName(), savedWalk(node, dest),
                        w -> saveWalk(walkRoute, walkFrame, node, dest, w));
                step = Step.DO;
                stepTicks = 0;
                tickTrapWalk(client, player, node);
                return;
            }
            wantSneak = forceSneak;
            if (RoutePathPlanner.isProving(node)) {
                // Path nodes only ever fly a route proved the fewest (killer560, 2026-10-11): a proof still running in
                // the background holds the pair here - any key of his still stops the route as usual.
                if (!provingSaid) {
                    provingSaid = true;
                    AutoRoutesFeature.note(ModChat.text("Path #" + (route.indexOf(node) + 1)),
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
                        RouteTrace.timeout(traceIndex(node), "PATH", "the path planner never answered", stepTicks,
                                player.position());
                        stop("the path planner did not answer");
                    } else {
                        logHold("plan:" + traceIndex(node), "node {} PATH: waiting for the planner ({} of {} tick(s))",
                                nodeTag(node), stepTicks, PLAN_TIMEOUT);
                    }
                    return;
                }
                LOGGER.info("[AutoRoutes] Node #{} PATH: planner answered \"{}\" after {} tick(s), saved warps valid {}",
                        route.indexOf(node) + 1, outcome, stepTicks, RoutePathPlanner.valid(route, node));
                if (!"ok".equals(outcome) || !RoutePathPlanner.valid(route, node)) {
                    stop("no etherwarp path from #" + (route.indexOf(node) + 1) + " to #" + (route.indexOf(dest) + 1)
                            + " (" + outcome + ")");
                    return;
                }
            }
            if (node.planBreakers != RoutePathPlanner.breakerStamp(route, node) && staleSaid != node) {
                // His choice (2026-10-11): re-planning after pick changes is manual. Say so, once a pair.
                staleSaid = node;
                LOGGER.info("[AutoRoutes] Node #{} PATH: planned before its breaker blocks changed (stamp {} vs {}) - flying "
                                + "the old plan; /ar replan to plan again", route.indexOf(node) + 1, node.planBreakers,
                        RoutePathPlanner.breakerStamp(route, node));
                AutoRoutesFeature.chatBad("Path #" + (route.indexOf(node) + 1) + " -> #" + (route.indexOf(dest) + 1)
                        + " was planned before its breaker blocks changed - /ar replan to plan it with them.");
            }
            LOGGER.info("[AutoRoutes] Node #{} PATH: flying {} saved warp(s) to #{}", route.indexOf(node) + 1,
                    node.pathHops.size(), route.indexOf(dest) + 1);
            pathOffBlock = 0;
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
        // "landed" is any landing within LANDING_TOLERANCE of the planned feet; checkLandingBlock judged each by its block.
        LOGGER.info("[AutoRoutes] Node #{} PATH: all {} warp(s) landed, {}", route.indexOf(node) + 1, node.pathHops.size(),
                pathOffBlock == 0 ? "each on its planned block" : pathOffBlock + " of them OFF the planned block");
        finishAction();
        noteLanding(node);
    }

    /** The active PATH node is being walked by AP3 ({@link #tickTrapWalk}) rather than flown; reset by beginAction. */
    private static boolean trapWalk;

    /**
     * The route's room is a trap room (the room database's two TRAP rooms, {@code AutoTrap.isTrapRoom}): no ability works
     * there, so a path pair walks - first plan and every re-plan by AP3's walk planner, never an etherwarp.
     */
    private static boolean inTrapRoom() {
        return com.killer560.hub.autotrap.AutoTrap.isTrapRoom(frame == null ? null : frame.roomName());
    }

    /**
     * The walk saved on {@code src} (RouteNode#walkSteps), in real coordinates, or null when there is none or it is stale:
     * either node has moved since it was planned (the same rule as a path's saved warps, RoutePathPlanner.valid).
     */
    private static Ap3TrapWalk.SavedWalk savedWalk(RouteNode src, RouteNode dest) {
        if (src.walkSteps.isEmpty()) {
            return null;
        }
        if (!(nearSaved(src.walkSrcX, src.x) && nearSaved(src.walkSrcY, src.y) && nearSaved(src.walkSrcZ, src.z)
                && nearSaved(src.walkToX, dest.x) && nearSaved(src.walkToY, dest.y) && nearSaved(src.walkToZ, dest.z))) {
            LOGGER.info("[AutoRoutes] Node #{} PATH: its saved trap walk is stale (a node moved since) - planning afresh",
                    route.indexOf(src) + 1);
            return null;
        }
        Vec3 at = RouteCoords.toReal(frame, src.walkFromX, src.walkFromY, src.walkFromZ);
        // A velocity is a direction: the real end of (from + v) less the real start.
        Vec3 tip = RouteCoords.toReal(frame, src.walkFromX + src.walkFromVX, src.walkFromY + src.walkFromVY,
                src.walkFromZ + src.walkFromVZ);
        List<Ap3TrapWalk.SavedStep> steps = new ArrayList<>(src.walkSteps.size());
        for (RouteNode.WalkStep w : src.walkSteps) {
            steps.add(new Ap3TrapWalk.SavedStep(w.fw(), w.st(), w.sneak(), RouteCoords.toRealYaw(frame, w.yaw()),
                    w.jump(), w.sprint()));
        }
        return new Ap3TrapWalk.SavedWalk(at.x, at.y, at.z, tip.x - at.x, tip.y - at.y, tip.z - at.z,
                src.walkFromGround, steps);
    }

    private static boolean nearSaved(double a, double b) {
        return Math.abs(a - b) < 0.002;
    }

    /**
     * Keeps a fresh first plan of a trap walk on the pair's first node, room-relative, and saves the routes file - like a
     * path's warps (RoutePathPlanner). An Auto Trap route is saved through Auto Trap, whose captures it lives in.
     */
    private static void saveWalk(Route r, RouteCoords.Frame f, RouteNode src, RouteNode dest, Ap3TrapWalk.SavedWalk w) {
        if (r == null || f == null || w == null || w.steps().isEmpty()
                || w.steps().size() > RouteStore.MAX_WALK_STEPS) {
            return;
        }
        Vec3 rel = RouteCoords.toRelative(f, new Vec3(w.x(), w.y(), w.z()));
        Vec3 tip = RouteCoords.toRelative(f, new Vec3(w.x() + w.vx(), w.y() + w.vy(), w.z() + w.vz()));
        src.walkSteps.clear();
        for (Ap3TrapWalk.SavedStep s : w.steps()) {
            src.walkSteps.add(new RouteNode.WalkStep(s.fw(), s.st(), s.sneak(),
                    (float) RouteNode.roundSaved(RouteCoords.toRelativeYaw(f, s.yaw())), s.jump(), s.sprint()));
        }
        src.walkFromX = RouteNode.roundSaved(rel.x);
        src.walkFromY = RouteNode.roundSaved(rel.y);
        src.walkFromZ = RouteNode.roundSaved(rel.z);
        src.walkFromVX = RouteNode.roundSaved(tip.x - rel.x);
        src.walkFromVY = RouteNode.roundSaved(tip.y - rel.y);
        src.walkFromVZ = RouteNode.roundSaved(tip.z - rel.z);
        src.walkFromGround = w.onGround();
        src.walkSrcX = src.x;
        src.walkSrcY = src.y;
        src.walkSrcZ = src.z;
        src.walkToX = dest.x;
        src.walkToY = dest.y;
        src.walkToZ = dest.z;
        RouteStore.getInstance().save();
        boolean trap = com.killer560.hub.autotrap.AutoTrap.saveIfOurs(r);
        LOGGER.info("[AutoRoutes] Node #{} PATH: trap walk saved with the route ({} tick(s)){}", r.indexOf(src) + 1,
                src.walkSteps.size(), trap ? " - in Auto Trap's captures" : "");
    }

    /**
     * One tick of a trap-room path walk: AP3's planned keys for this tick go in through the input mixin (discrete keys,
     * the body turned to the step's yaw with the camera held, the yaw a delta on the running yaw, pitch untouched), until
     * AP3 says he stands on the second node - then the node finishes and the walk step fires the second node, which he is
     * standing in, as the arrival. A refusal (no walkable way, nowhere to stand) stops the route and says why.
     */
    private static void tickTrapWalk(Minecraft client, LocalPlayer player, RouteNode node) {
        Ap3TrapWalk.Drive d = Ap3TrapWalk.tick(client, player);
        Ap3TrapWalk.Phase phase = Ap3TrapWalk.phase();
        RouteNode dest = route.pathDestination(node);
        String pair = "Path #" + (route.indexOf(node) + 1) + " -> #" + (dest == null ? "?" : route.indexOf(dest) + 1);
        if (phase == Ap3TrapWalk.Phase.REFUSED) {
            String why = Ap3TrapWalk.refusal();
            LOGGER.info("[AutoRoutes] Node #{} PATH: trap-room walk refused - {}", route.indexOf(node) + 1, why);
            AutoRoutesFeature.chatBad(pair + ": can't walk it in a trap room - " + why + ".");
            stop(pair + " can't be walked in a trap room");
            return;
        }
        if (phase == Ap3TrapWalk.Phase.ARRIVED) {
            logActed(node, " (walked by AP3 in a trap room, no etherwarp)");
            finishAction();
            if (running && route.path().isEmpty()) {
                // The arrival fires the node he walked into, as a flown pair's landing re-fire does (nodeLandedIn).
                landedFrom = node;
            }
            return;
        }
        wantSneak = false;
        if (d == null) {
            return; // planning, or holding still while a re-plan lands: no keys this tick
        }
        float delta = Mth.wrapDegrees(d.yaw() - player.getYRot());
        if (Math.abs(delta) > 1e-4f) {
            turnBody(player, player.getYRot() + delta, player.getXRot());
        }
        wantForward = d.forward();
        wantBackward = d.backward();
        wantLeft = d.left();
        wantRight = d.right();
        wantJump = d.jump();
        wantSneak = d.sneak();
        wantSprint = d.sprint() && !player.isInWater();
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

    /** Path hops whose look was turned to inside the landing's own teleport answer - read by the testkit. */
    private static int answerTurns;

    public static int answerTurns() {
        return answerTurns;
    }

    /**
     * From {@code mixin/RightClickTeleportMixin}, on the client thread, as a server position packet is about to be applied
     * - before vanilla answers it with its accept and a position-and-look packet. While a path hop is in flight with a
     * block click unpaired (util/RightClick), the next hop's use must go along a look the server has heard, and the
     * in-flight turn in {@link #tickWarp} only gets to run on a tick between the use and the landing: a landing one tick
     * after its use (most of them on Hypixel) came back before it, so the next hop sat a turn tick ({@code waited 1
     * tick(s) for the server to hear its look}: 25 times in his 17:43 log). Turning the body here, to exactly the look
     * {@link #loadHop} will work out from this landing, puts that look in the teleport answer itself, which GrimAC takes as
     * no tick packet (it checks nothing there, testkit UseRotationWatch) and a 1.8 server takes as his look - so the hop goes
     * on the landing tick and its use, its look and the tick's movement packet all agree. Only for this hop's own landing
     * (within {@link #LANDING_TOLERANCE} of the planned feet), obvious mode, a next hop to fly.
     */
    public static void beforeTeleportAnswer(LocalPlayer player, Vec3 target) {
        RouteNode node = activeNode;
        if (!running || node == null || node.type != RouteNode.Type.PATH || step != Step.CONFIRM || player == null
                || target == null || warpLanding == null || node.pathHops == null || hopIndex < 0
                || hopIndex + 1 >= node.pathHops.size() || AutoRoutesConfig.getInstance().isLegitMode()
                || !com.killer560.hub.util.RightClick.unpaired() || target.distanceTo(warpLanding) > LANDING_TOLERANCE) {
            return;
        }
        RouteNode.PathHop h = node.pathHops.get(hopIndex + 1);
        float yaw = RouteCoords.toRealYaw(frame, h.yaw());
        float pitch = h.pitch();
        Vec3 origin = RouteCoords.toReal(frame, h.ox(), h.oy(), h.oz());
        BlockPos hopTarget = RouteCoords.toRealBlock(frame, h.target());
        Vec3 eye = new Vec3(target.x, target.y + TeleportUtils.eyeHeight(true), target.z);
        double range = com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange() + 1.0;
        // reaimIfMoved's own choice, from the spot the landing puts him on.
        if (!(target.distanceTo(origin) <= REAIM_EPSILON
                && TeleportUtils.holdsOn(eye, yaw, Mth.clamp(pitch, -90f, 90f), range, hopTarget))) {
            TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, hopTarget, range);
            if (rot == null) {
                rot = TeleportUtils.exactEtherwarpDirection(eye, hopTarget, range);
            }
            if (rot != null) {
                yaw = rot.yaw();
                pitch = rot.pitch();
            }
        }
        if (com.killer560.hub.util.RightClick.heard(player, yaw, pitch)) {
            return;
        }
        turnBody(player, player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()), Mth.clamp(pitch, -90f, 90f));
        answerTurns++;
        LOGGER.info("[AutoRoutes] path hop {} landing: body turned to hop {}'s look ({} / {}) inside the teleport answer - "
                        + "a block click is unpaired, so the server hears that look before hop {}'s use", hopIndex + 1,
                hopIndex + 2, String.format(Locale.US, "%.2f", Mth.wrapDegrees(yaw)), String.format(Locale.US, "%.2f", pitch),
                hopIndex + 2);
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
        Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
        double range = com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange() + 1.0;
        if (at.distanceTo(warpAimFrom) <= REAIM_EPSILON) {
            // On its spot: the saved look goes - unless it lands only for its exact float values. A path saved before
            // the planner proved its hops within EtherSearch.AIM_MARGIN can graze a block edge, and the server's reading
            // of the running yaw is a few bits off: his Doors #13 hop 3 (yaw -895.91) was refused at the jungle log at
            // -26, 244, -85 and the route stood 20 s until Route Stall (fullclear-1016 run 019). Such a look is aimed again.
            if (TeleportUtils.holdsOn(eye, warpYaw, Mth.clamp(warpPitch, -90f, 90f), range, warpTarget)) {
                return AIM_OK;
            }
        }
        TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, warpTarget, range);
        if (rot == null) {
            // None of the sample points: every point of every face the planner would try (path-wrongblock-1015). A hop
            // planned from the settled eye and fired 0.05 above it can lose all of them to a lip it grazed.
            rot = TeleportUtils.exactEtherwarpDirection(eye, warpTarget, range);
        }
        if (rot == null) {
            logHold("noray:" + what, "Etherwarp ({}): no ray from {} ({} off the look's spot {}) lands on {} - the look "
                            + "is left as it was (yaw {} pitch {})", what, fmt(at),
                    String.format(Locale.ROOT, "%.2f", at.distanceTo(warpAimFrom)), fmt(warpAimFrom),
                    warpTarget.toShortString(), String.format(Locale.ROOT, "%.2f", warpYaw),
                    String.format(Locale.ROOT, "%.2f", warpPitch));
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
        // The saved look belongs to the spot's EYE too: on a landing tick he stands 0.05 over it, and the saved look fired
        // from there went over a slab lip at the sim's -26, 259, -128 and was refused (1015-sim-pathland, Quartz Knight). He
        // drops onto the spot the next tick by himself (gravity, not a wait), so "on it" includes the height.
        boolean settled = h <= CENTRE_TOLERANCE && player.onGround() && v.x * v.x + v.z * v.z < 0.0004
                && Math.abs(pos.y - warpSpot.y) < REAIM_EPSILON;
        if (settled || warpCentreTicks > CENTRE_TIMEOUT) {
            clearMovement();
            LOGGER.info("[AutoRoutes] Etherwarp ({}): {} - firing the saved look from {}", what, settled
                    ? String.format(Locale.US, "back on its spot (%.2f off) after %d tick(s)", h, warpCentreTicks)
                    : String.format(Locale.US, "still %.2f off its spot after %d tick(s)", h, warpCentreTicks), fmt(pos));
            if (!settled && activeNode != null) {
                RouteTrace.timeout(traceIndex(activeNode), what, String.format(Locale.US, "never back on its spot %s "
                        + "(%.2f off) - firing the saved look from here, which may land elsewhere", fmt(warpSpot), h),
                        warpCentreTicks, pos);
            }
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
            warpClickGapTicks = 0;
            clickGapSaid = false;
            warpAimHoldTicks = 0;
            warpRetries = 0;
            warpForced = false;
            int slot = ItemIdentity.findEtherwarpSlot(player);
            if (slot < 0) {
                LOGGER.info("[AutoRoutes] Etherwarp: no hotbar item with ethermerge / ETHERWARP_CONDUIT");
                stop("no etherwarp item in the hotbar");
                return false;
            }
            select(client, player, slot);
            warpSpentFromSlot = -1;
            LOGGER.info("[AutoRoutes] Etherwarp PREP ({}{}): slot {} {}, sneak {} at the server, look {} / {}, target {}, "
                            + "landing {}, pace next at {} (now {})", nodeTag(node),
                    hopIndex >= 0 ? " hop " + (hopIndex + 1) + "/" + node.pathHops.size() : "", slot + 1,
                    ItemIdentity.skyblockId(player.getInventory().getItem(slot)),
                    player.getLastSentInput().shift() ? "already" : "not yet",
                    String.format(Locale.ROOT, "%.2f", warpYaw), String.format(Locale.ROOT, "%.2f", warpPitch),
                    warpTarget == null ? "none" : warpTarget.toShortString(), fmt(warpLanding),
                    nextWarpAt == Double.NEGATIVE_INFINITY ? "-" : String.format(Locale.ROOT, "%.1f", nextWarpAt), execTicks);
            forceSneak = true;
            unsneakOverride = false;
            wantSneak = true;
            aimAt(warpYaw, warpPitch, node);
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            wantSneak = true;
            if (warpSpentFromSlot >= 0) {
                LOGGER.info("[AutoRoutes] Etherwarp: the breaker use paired the stored click - etherwarp item back (slot {})",
                        warpSpentFromSlot + 1);
                // Last tick a Dungeon Breaker use paired the stored block click (spendStaleClickFirst): the etherwarp item
                // goes back in hand now, and the use below goes with it in this same tick.
                select(client, player, warpSpentFromSlot);
                warpSpentFromSlot = -1;
            } else if (!ItemIdentity.isEtherwarpItem(player.getMainHandItem())) {
                // Back in AIM for a second send (refused, eaten, or his click) after he changed slots while it waited:
                // PREP's select is behind it, so the resend went out with whatever he held - BAT_WAND and
                // ITEM_SPIRIT_BOW on 2026-10-19 15:07:37 / 15:08:30, "sending it once more" with no warp at all.
                int slot = ItemIdentity.findEtherwarpSlot(player);
                if (slot >= 0) {
                    LOGGER.info("[AutoRoutes] Etherwarp: {} in hand at the send - selecting the etherwarp item (slot {})",
                            ItemIdentity.skyblockId(player.getMainHandItem()), slot + 1);
                    select(client, player, slot);
                } else {
                    logHold("noether:" + traceIndex(node), "Etherwarp: {} in hand at the send and no etherwarp item in "
                            + "the hotbar now", ItemIdentity.skyblockId(player.getMainHandItem()));
                }
            }
            // A right click on a block may be waiting on the server for its second half (util/RightClick): the server
            // must hear this warp's look before the use, or the use is taken as that click's end and nothing happens.
            // Turned here, while the sneak is still on its way to the server, it costs no tick.
            if (!AutoRoutesConfig.getInstance().isLegitMode()
                    && com.killer560.hub.util.RightClick.mustTurnFirst(player, warpYaw, warpPitch, "[AutoRoutes] Etherwarp")) {
                logHold("turnearly:" + traceIndex(node), "Etherwarp ({}): a block click is unpaired - turning the body to "
                        + "the warp's look while the sneak is on its way ({} tick(s) in AIM)", nodeTag(node), stepTicks);
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
                    RouteTrace.timeout(traceIndex(node), traceWhat(node), "sneak never reached the server (no shift in "
                            + "the last input packet)", stepTicks, player.position());
                    stop("couldn't start sneaking for the etherwarp");
                } else {
                    logHold("sneak:" + traceIndex(node), "Etherwarp ({}): waiting for the sneak to reach the server ({} of "
                            + "{} tick(s), client shift {})", nodeTag(node), stepTicks, SNEAK_TIMEOUT,
                            player.isShiftKeyDown());
                }
                return false;
            }
            if (sneakReadyAge < 0) {
                sneakReadyAge = actionAge;
                LOGGER.debug("[AutoRoutes] Etherwarp: the server has the sneak {} tick(s) after firing", actionAge);
            }
            if (!warpForced && (!landingBlockThere(client) || pickStillStanding(client, player, node))) {
                // Held with no time limit of its own (a pick, up to 60 s for a missing block): his left click goes off.
                if (!running || activeNode != node || !takeSkipClick(client)) {
                    return false;
                }
                forceWarpPastHold(node, blockWaitTicks > 0 ? "landing block " + warpTarget.toShortString() + " missing"
                        : "breaker block " + (pickWaitBlock == null ? "?" : pickWaitBlock.toShortString())
                        + " in the way", blockWaitTicks > 0 ? blockWaitTicks : stepTicks);
            }
            if (!warpAimReady(player)) {
                logHold("legitaim:" + traceIndex(node), "Etherwarp ({}): legit camera turn still settling ({} off, {} "
                        + "tick(s), gives up past {})", nodeTag(node), RouteRotation.error(), stepTicks, WARP_AIM_GIVE_UP);
                return false;
            }
            // Etherwarps Per Second: not before the pace allows. Sneak stays held while it waits.
            if (execTicks < Math.ceil(nextWarpAt - 1e-9)) {
                warpPacedTicks++;
                logHold("pace:" + traceIndex(node), "Etherwarp ({}): paced by Etherwarps Per Second - next warp at tick {}, "
                        + "now {}", nodeTag(node), String.format(Locale.ROOT, "%.1f", nextWarpAt), execTicks);
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
                logHold("legitreaim:" + traceIndex(node), "Etherwarp ({}): look changed at the send ({}) - legit camera "
                        + "turns to it first", what, aim == AIM_CHANGED ? "re-aimed from here" : "no ray from here");
                aimAt(warpYaw, warpPitch, node);
                return false;
            }
            // Every warp sent a tick after a chest or lever click met its await got no landing on Hypixel (2026-10-14):
            // the server traced the use along the look Secret Aura clicked the chest with, met the chest, and took the
            // use as the click's second half (util/RightClick). So when a block click is unpaired the server hears this
            // look first - turned on the firing tick above when the sneak was not yet out, which costs nothing; only a
            // warp that would otherwise go on the click's own tick waits, one tick.
            // The server's ray along this look (from the 1.8 eye) meets a block this client's look cannot reach, so no
            // click could pair the use and the server would drop it: aim at the same block along a look whose rays agree.
            // turnFirst below then turns to it - the one tick, only on that mismatch (killer560, 2026-10-15).
            if (!AutoRoutesConfig.getInstance().isLegitMode() && warpTarget != null) {
                Vec3 at = player.position();
                float[] re = com.killer560.hub.util.RightClick.reaimIfUnpairable(client, player, null,
                        new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z), warpTarget,
                        com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange() + 1.0,
                        player.getYRot() + Mth.wrapDegrees(warpYaw - player.getYRot()), Mth.clamp(warpPitch, -90f, 90f),
                        "[AutoRoutes] Etherwarp");
                if (re != null) {
                    LOGGER.info("[AutoRoutes] Etherwarp ({}): server and client rays disagree - re-aimed at {}: {} / {} -> "
                                    + "{} / {}", what, warpTarget.toShortString(),
                            String.format(Locale.ROOT, "%.2f", warpYaw), String.format(Locale.ROOT, "%.2f", warpPitch),
                            String.format(Locale.ROOT, "%.2f", re[0]), String.format(Locale.ROOT, "%.2f", re[1]));
                    warpYaw = re[0];
                    warpPitch = re[1];
                    warpAimFrom = at;
                }
            }
            // A block that was not there when the node was placed, standing in its line: his rule is to wait for it to
            // move (newBlockInLine), not to aim round it - so this goes before the landing check below.
            if (hopIndex < 0 && !warpForced && newBlockInLine(client, player, node)) {
                if (!takeSkipClick(client)) {
                    return false; // no time limit (his rule): only his click sends it past the block
                }
                forceWarpPastHold(node, "block " + (lineWaitBlock == null ? "?" : lineWaitBlock.toShortString())
                        + " in its line", lineWaitTicks);
            }
            if (!warpForced && !aimLandsOnTarget(client, player, node, what)) {
                // killer560, 2026-10-19: "No node may ever Etherwarp wrong." The look about to go does not land on the
                // planned block from where he stands, and none that does could be found: nothing is sent (sneak held). The
                // same test runs every tick, so it goes the tick a look lands (he settles, a block moves); his left click
                // sends it as it is. Before turnFirst, so a look replaced here is the one the server is told.
                if (!running || activeNode != node || !takeSkipClick(client)) {
                    return false;
                }
                forceWarpPastHold(node, "no look from here lands on " + warpTarget.toShortString(), warpAimHoldTicks);
            }
            if (turnFirst(player, warpYaw, warpPitch, "[AutoRoutes] Etherwarp")) {
                warpTurnFirstTicks++;
                logHold("turnfirst:" + traceIndex(node), "Etherwarp ({}): waiting for the server to hear the look first "
                        + "(a block click is unpaired), {} tick(s) so far", what, warpTurnFirstTicks);
                return false;
            }
            if (com.killer560.hub.playerstats.PlayerStatsFeature.manaTooLowForAotv()) {
                logHold("mana:" + traceIndex(node), "Etherwarp ({}): held - not enough mana for Aspect of the Void (the "
                        + "action bar says so); the use goes once mana is back", what);
                return false;
            }
            if (clickGapHolds(player, node, what)) {
                return false;
            }
            if (spendStaleClickFirst(client, player)) {
                warpTurnFirstTicks++;
                return false;
            }
            notePacedWarp();
            actionOrigin = player.position();
            teleportPacketSeen = false;
            int eatenBefore = com.killer560.hub.util.RightClick.barePairedUses();
            int seqBefore = com.killer560.hub.util.RightClick.lastUseSequence();
            useHeldItem(client, player, warpYaw, warpPitch, false);
            warpEaten = com.killer560.hub.util.RightClick.barePairedUses() > eatenBefore;
            int seqAfter = com.killer560.hub.util.RightClick.lastUseSequence();
            warpUseSeq = seqAfter != seqBefore ? seqAfter : -1;
            LOGGER.info("[AutoRoutes] Etherwarp use sent ({}{}) at tick {}: from {} look {} / {} | seq {} | taken as a click's "
                            + "second half {} | retry {} of {} | refusal called past {} tick(s) | server lag {}",
                    nodeTag(node), hopIndex >= 0 ? " hop " + (hopIndex + 1) : "", execTicks, fmt(actionOrigin),
                    String.format(Locale.ROOT, "%.2f", warpYaw), String.format(Locale.ROOT, "%.2f", warpPitch), warpUseSeq,
                    warpEaten, warpRetries, MAX_REFUSED_RETRIES, refusedRetryTicks(), serverLagTicks());
            RouteTrace.sent(traceIndex(node), traceWhat(node), actionOrigin, warpTarget, blockName(warpTarget), warpYaw,
                    warpPitch);
            logActed(node, (hopIndex >= 0 ? " (path warp " + (hopIndex + 1) + "/" + node.pathHops.size() + ")" : "")
                    + (warpPacedTicks > 0 ? " (paced " + warpPacedTicks + " tick(s) by Etherwarps Per Second)" : "")
                    + (warpTurnFirstTicks > 0 ? " (waited " + warpTurnFirstTicks + " tick(s) for the server to hear its "
                    + "look - a block click was unpaired)" : "")
                    + (warpClickGapTicks > 0 ? " (click-gap hold " + warpClickGapTicks + " tick(s))" : "")
                    + " (sneak " + (hopIndex > 0 ? "held from the last warp" : sneakReadyAge == awaitDoneAge ? "already held"
                    : "went out in the firing tick's input packet") + ", "
                    + ItemIdentity.skyblockId(player.getMainHandItem()) + ")");
            step = Step.CONFIRM;
            stepTicks = 0;
            warpSlowSaid = false;
            cameraGraceTicks = LANDING_TIMEOUT + 5;
            return false;
        }
        if (step == Step.CONFIRM) {
            wantSneak = true;
            // A block click is unpaired on the server (util/RightClick): while this hop is in flight the body already turns
            // to the NEXT hop's look - its use has gone and its own look was reported last tick - so the server has heard
            // that look by the time he lands and the next hop goes on the landing tick, not one later.
            boolean landedNow = landed(player, warpLanding);
            if (!landedNow && stepTicks >= 1 && hopIndex >= 0 && hopIndex + 1 < node.pathHops.size()
                    && !AutoRoutesConfig.getInstance().isLegitMode() && com.killer560.hub.util.RightClick.unpaired()) {
                RouteNode.PathHop next = node.pathHops.get(hopIndex + 1);
                float nextYaw = RouteCoords.toRealYaw(frame, next.yaw());
                if (!com.killer560.hub.util.RightClick.heard(player, nextYaw, next.pitch())) {
                    logHold("prehop:" + traceIndex(node), "path hop {} in flight - body turned to hop {}'s look early "
                            + "(a block click is unpaired)", hopIndex + 1, hopIndex + 2);
                    turnBody(player, player.getYRot() + Mth.wrapDegrees(nextYaw - player.getYRot()),
                            Mth.clamp(next.pitch(), -90f, 90f));
                }
            }
            if (landedNow) {
                LOGGER.info("[AutoRoutes] Etherwarp: landed at {} {} tick(s) after the use ({} from firing)",
                        fmt(player.position()), stepTicks, actionAge);
                if (teleportPacketSeen) {
                    noteLandingTicks(stepTicks);
                    lastLandingAt = execTicks;
                }
                noteSecretGap((hopIndex >= 0 ? "hop " + (hopIndex + 1) + " " : "") + "landed " + stepTicks
                        + " tick(s) after its use");
                boolean onTarget = checkLandingBlock(client, player, node);
                RouteTrace.landed(traceIndex(node), traceWhat(node), actionOrigin, player.position(), warpTarget, onTarget,
                        teleportPacketSeen, stepTicks);
                landingConfirmed = teleportPacketSeen;
                // Whether the sneak stays held for what comes next is planSneak's call, from finishAction.
                RouteRotation.rebase();
                cameraGraceTicks = 3;
                rejoinPathAfterTeleport(player, node);
                return true;
            } else if (!teleportPacketSeen && stepTicks > warpRoundTripTicks() && takeSkipClick(client)) {
                // killer560, 2026-10-19: a left click on a node that is waiting makes it go off. A round trip on with no
                // teleport this warp is waiting, not flying: his click sends it again now, as his own second right
                // click would, instead of sitting out the 60-tick timeout below.
                LOGGER.info("[AutoRoutes] Left click on node #{} {}: released its wait for the landing ({} tick(s), no "
                        + "teleport, ack {}) - sending the warp again", route.indexOf(node) + 1, node.type, stepTicks,
                        com.killer560.hub.util.ChestWindows.acked(warpUseSeq) ? "in" : "not in");
                noteSecretGap("no landing " + stepTicks + " tick(s) after the use - your left click sent it again");
                warpEaten = false;
                step = Step.AIM;
                stepTicks = 0;
                return false;
            } else if (warpEaten && !teleportPacketSeen
                    && stepTicks > com.killer560.hub.util.RightClick.eatenRetryTicks() + serverLagTicks()) {
                // The use went out as the second half of an unpaired block click (util/RightClick: the server's ray met a
                // block this client could not click, and no other look landed with rays that agree). That use cleared the
                // server's stored click whatever became of it, so the same warp sent again is an ordinary right click and
                // fires - one round trip lost, where the 61-tick timeout below left him off every node's ring until Route
                // Stall stopped the route (fullclear-1016: Crypt #5, Cathedral #15).
                LOGGER.info("[AutoRoutes] Etherwarp: no landing {} tick(s) after a use the server took as the block "
                        + "click's second half - the click is paired now, sending the warp again", stepTicks);
                noteSecretGap("no landing " + stepTicks + " tick(s) after a use taken as the click's second half - sent again");
                warpEaten = false;
                eatenResends++;
                step = Step.AIM;
                stepTicks = 0;
                return false;
            } else if (warpRetries < MAX_REFUSED_RETRIES && !teleportPacketSeen && warpTarget != null && actionOrigin != null
                    && player.position().distanceTo(actionOrigin) < 0.3
                    && stepTicks > com.killer560.hub.util.RightClick.eatenRetryTicks() + serverLagTicks()
                    // The server has HANDLED the use (its ack is in) and no teleport came before the ack: refused, not
                    // slow. Without it a landing 5 ticks late was taken for a refusal and the warp went twice, 8 blocks
                    // past the node (testkit 96-ar-1015-earlyskip, failing since f1226757).
                    && (com.killer560.hub.util.ChestWindows.acked(warpUseSeq)
                    // Hypixel never acks a bare use (see NO_ACK_ROUND_TRIPS): without this the retry waited for one of
                    // his own block clicks to bring an ack in, and otherwise never came. Past twice the slowest recent
                    // landing it is refused, not slow (refusedRetryTicks); three round trips cost 13-22 ticks after
                    // every secret click on Hypixel (his 2026-10-19 16:17 log).
                    || stepTicks > refusedRetryTicks() + serverLagTicks())
                    && retryAim(player)) {
                // No landing a round trip on, and he has not moved: the server refused the warp (its ray stopped short -
                // his Doors #13 hop 3 on a jungle log, fullclear-1016 run 019). Aimed again from where he stands with a
                // look that holds, it goes once more; the 61-tick wait below then let the node go off every ring and the
                // route stood until Route Stall.
                warpRetries++;
                step = Step.AIM;
                stepTicks = 0;
                return false;
            } else if (stepTicks > LANDING_TIMEOUT + serverLagTicks()) {
                LOGGER.info("[AutoRoutes] Etherwarp: no landing after {} ticks - player {} (moved {} from {}), "
                        + "recorded landing {}", stepTicks, fmt(player.position()),
                        String.format(Locale.US, "%.2f", actionOrigin == null ? 0.0 : player.position().distanceTo(actionOrigin)),
                        fmt(actionOrigin), fmt(warpLanding));
                noteSecretGap("no landing after " + stepTicks + " tick(s) - landing timeout, node let go");
                RouteTrace.timeout(traceIndex(node), traceWhat(node), "no landing at the recorded spot (server teleport "
                        + (teleportPacketSeen ? "came, elsewhere" : "never came") + "; target "
                        + (warpTarget == null ? "none" : warpTarget.toShortString()) + ")", stepTicks, player.position());
                // Not a stop (killer560's correction rule, 2026-10-06, applied here at the coordinator's request: the
                // server not putting him on the landing is treated like a correction): chat line + alarm, the node is
                // let go uncounted, and the route carries on - in its ring still, it fires again from where he is.
                ServerCorrections.reportEvent("Auto Routes", String.format(Locale.US, "etherwarp #%d did not land where"
                        + " it was recorded (%.1f blocks from the landing after %d ticks) - carrying on from here",
                        route.indexOf(node) + 1, warpLanding == null ? 0.0 : player.position().distanceTo(warpLanding),
                        stepTicks));
                boolean unmoved = !teleportPacketSeen && actionOrigin != null
                        && player.position().distanceTo(actionOrigin) < 0.3;
                letGoAfterCorrection(client, player);
                if (unmoved) {
                    // The server never moved him: firing the same stack again from the same spot re-ran its booms and
                    // crypt and sent the same refused warp, every 6 s (Slime #5, 2026-10-19). The node waits for him.
                    refireHoldAt = player.position();
                    refireHoldSaid = false;
                    LOGGER.info("[AutoRoutes] Etherwarp ({}): never moved after {} retry(ies) - the node he stands in is "
                            + "held at {} until a left click or he moves off it", nodeTag(node), warpRetries,
                            fmt(refireHoldAt));
                }
            } else if (!warpSlowSaid && !teleportPacketSeen && stepTicks > warpRoundTripTicks() * NO_ACK_ROUND_TRIPS) {
                warpSlowSaid = true;
                noteSecretGap("still no landing " + stepTicks + " tick(s) after the use");
                LOGGER.info("[AutoRoutes] Node #{} {}: still no landing {} tick(s) after the use (ack {}, moved {}) - "
                                + "waiting; a left click sends it again", route.indexOf(node) + 1, node.type, stepTicks,
                        com.killer560.hub.util.ChestWindows.acked(warpUseSeq) ? "in" : "not in",
                        String.format(Locale.US, "%.2f", actionOrigin == null ? 0.0
                                : player.position().distanceTo(actionOrigin)));
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[AutoRoutes] Etherwarp ({}): in flight {} tick(s) after the use, teleport seen {}, {} from the "
                                + "landing", nodeTag(node), stepTicks, teleportPacketSeen,
                        warpLanding == null ? "?" : String.format(Locale.ROOT, "%.2f",
                                player.position().distanceTo(warpLanding)));
            }
        }
        return false;
    }

    /**
     * Ticks between a block click made with the item a warp uses and that warp's use: at least this many (2 = the click at
     * tick N, the use at N+2). Measured on Hypixel (Mod Only Test and Untested Cheat, 2026-10-19, every warp with an unpaired
     * AOTV click before it): one tick after the click 45 of 69 warps got no teleport at all, two ticks after 1 of 7, three or
     * more 0 of about 250; after a click made with a different item (Dungeon Breaker, Superboom) 3 of 3 landed one tick on.
     * Each refusal then cost the refused-warp wait (7-10 ticks with this jar, up to 22 before). QUOI holds every node 2 ticks
     * after a secret click ({@code AutoRoutes.kt:148-151, 217}) and RSA resumes at N+2 ({@code AutoRoutes.java:183, 393,
     * 472}). So the second tick is the first one Hypixel reliably takes: "the first physically possible tick".
     */
    static final long CLICK_TO_WARP_TICKS = 2;
    /** Warps held for {@link #CLICK_TO_WARP_TICKS} since launch, and the ticks they spent - read by the testkit. */
    private static int clickGapHolds;
    private static int clickGapHeldTicks;
    /** The warp under way has already logged its click-gap hold, and the ticks it was held. */
    private static boolean clickGapSaid;
    private static int warpClickGapTicks;

    /**
     * For the other etherwarp runner, the Interactive Map and Auto Clear path ({@code ClearExecutor}): true while a block
     * click made with the item in hand is still inside the click-to-warp gap, so its warp waits the same two ticks. The
     * map's first warp went one tick after its own click, Hypixel dropped it, and the path failed (2026-10-19 log).
     */
    public static boolean clickGapPending(LocalPlayer player) {
        String clicked = itemClickKey;
        long since = (long) tickEndsSent - itemClickAtTickEnds;
        return clicked != null && since < CLICK_TO_WARP_TICKS && clicked.equals(heldItemKey(player));
    }

    public static int clickGapHolds() {
        return clickGapHolds;
    }

    public static int clickGapHeldTicks() {
        return clickGapHeldTicks;
    }

    /**
     * True (send nothing this tick) while the unpaired block click before this warp was made with the same item the warp
     * uses less than {@link #CLICK_TO_WARP_TICKS} ago. Sneak and body turn carry on meanwhile, so the hold costs only the
     * tick itself. Obvious and legit mode alike: the server's answer does not depend on the camera.
     */
    private static boolean clickGapHolds(LocalPlayer player, RouteNode node, String what) {
        String clicked = itemClickKey;
        long since = (long) tickEndsSent - itemClickAtTickEnds;
        if (clicked == null || since >= CLICK_TO_WARP_TICKS) {
            return false;
        }
        String held = heldItemKey(player);
        if (clicked == null || !clicked.equals(held)) {
            return false;
        }
        clickGapHeldTicks++;
        warpClickGapTicks++;
        if (!clickGapSaid) {
            clickGapSaid = true;
            clickGapHolds++;
            LOGGER.info("[AutoRoutes] Etherwarp ({}): held {} tick(s) - a block click with {} went {} tick(s) ago, and on "
                            + "Hypixel a warp one tick after a click with the same item gets no teleport (45 of 69 on "
                            + "2026-10-19); the use goes {} ticks after the click (click-gap hold)", what,
                    CLICK_TO_WARP_TICKS - since, held, since, CLICK_TO_WARP_TICKS);
        }
        noteSecretGap("click-gap hold: " + since + " tick(s) since a click with " + held);
        return true;
    }

    /** Ticks the warp under way has been held because no look from his spot lands on its block; warps so held, ever. */
    private static int warpAimHoldTicks;
    private static int aimHolds;
    /** Looks replaced at the send because the chosen one would have landed elsewhere - read by the testkit. */
    private static int aimFixes;

    public static int aimHolds() {
        return aimHolds;
    }

    public static int aimFixes() {
        return aimFixes;
    }

    /**
     * The last check before an etherwarp's use (obvious mode, where the look is the packet's own): the client's own
     * etherwarp trace ({@code TeleportUtils.getEtherPos}, the one the planner proves hops with) from his sneaking eye along
     * {@link #warpYaw}/{@link #warpPitch} must stop on {@link #warpTarget}. If it does not - the saved look fired after
     * {@link #stepOntoWarpSpot} gave up, a legit look, a resend after he moved - a look from here that lands (and holds
     * within the planner's margin, else lands exactly) replaces it; with none, false: the warp is held. A warp with no
     * planned block, legit mode, or a target in a chunk the client does not have is not judged here (true).
     */
    private static boolean aimLandsOnTarget(Minecraft client, LocalPlayer player, RouteNode node, String what) {
        if (warpTarget == null || client.level == null || AutoRoutesConfig.getInstance().isLegitMode()
                || !client.level.isLoaded(warpTarget)) {
            return true;
        }
        Vec3 at = player.position();
        Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
        double range = com.killer560.hub.livemap.autoclear.ClearExecutor.hopRange() + 1.0;
        TeleportUtils.RaycastResult shot = TeleportUtils.getEtherPos(eye, warpYaw, Mth.clamp(warpPitch, -90f, 90f), range);
        if (landsOnPlanned(shot)) {
            if (warpAimHoldTicks > 0) {
                LOGGER.info("[AutoRoutes] Etherwarp ({}): a look from {} lands on {} again after {} tick(s) held - warping",
                        what, fmt(at), warpTarget.toShortString(), warpAimHoldTicks);
            }
            warpAimHoldTicks = 0;
            return true;
        }
        TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, warpTarget, range);
        if (rot == null) {
            rot = TeleportUtils.exactEtherwarpDirection(eye, warpTarget, range);
        }
        if (rot != null) {
            TeleportUtils.RaycastResult check = TeleportUtils.getEtherPos(eye, rot.yaw(), rot.pitch(), range);
            if (landsOnPlanned(check)) {
                LOGGER.warn("[AutoRoutes] Etherwarp ({}): the look {} / {} from {} would land {} - not on {}; sending {} / {} "
                                + "instead, which does", what, String.format(Locale.US, "%.2f", Mth.wrapDegrees(warpYaw)),
                        String.format(Locale.US, "%.2f", warpPitch), fmt(at),
                        shot.pos() == null ? "nowhere" : "on " + shot.pos().toShortString(), warpTarget.toShortString(),
                        String.format(Locale.US, "%.2f", rot.yaw()), String.format(Locale.US, "%.2f", rot.pitch()));
                warpYaw = player.getYRot() + Mth.wrapDegrees(rot.yaw() - player.getYRot());
                warpPitch = rot.pitch();
                warpAimFrom = at;
                aimFixes++;
                warpAimHoldTicks = 0;
                return true;
            }
        }
        if (warpAimHoldTicks++ == 0) {
            aimHolds++;
            LOGGER.warn("[AutoRoutes] Etherwarp ({}): no look from {} lands on {} (the planned one would land {}) - held, "
                            + "sneak down, until one does; a left click sends it as it is", what, fmt(at),
                    warpTarget.toShortString(), shot.pos() == null ? "nowhere" : "on " + shot.pos().toShortString());
            AutoRoutesFeature.note(ModChat.text("Etherwarp held: "), ModChat.dim("no look from here lands on "
                    + warpTarget.toShortString() + ". Left click to send it anyway."));
        }
        noteSecretGap("held: no look from here lands on the planned block");
        return false;
    }

    /**
     * The click-gap clock, in the server's own terms: client tick ends ({@code client_tick_end} packets) that have left this
     * client, and the count when the last block click made with an item in hand left, with that item. A click sent at the
     * start of tick N, or between N-1 and N, or from N-1's end handlers, all reach the server inside tick N; the use is
     * judged by the tick ends between them. The first use of the same item after the click clears it (that use is the one
     * the click could cost). From the send tap ({@code RightClickSendMixin}), client thread.
     */
    private static volatile int tickEndsSent;
    private static volatile int itemClickAtTickEnds = Integer.MIN_VALUE / 2;
    private static volatile String itemClickKey;

    public static void onTickEndSent() {
        tickEndsSent++;
    }

    public static void onBlockClickSent(LocalPlayer player, InteractionHand hand) {
        if (player == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        itemClickKey = stack.isEmpty() ? null : itemKey(stack);
        itemClickAtTickEnds = tickEndsSent;
    }

    public static void onItemUseSent(LocalPlayer player, InteractionHand hand) {
        if (player != null && itemClickKey != null && itemClickKey.equals(itemKey(player.getItemInHand(hand)))) {
            itemClickKey = null;
        }
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) {
            return "an empty hand";
        }
        String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(stack);
        return id != null ? id : stack.getItem().toString();
    }

    /** The client's etherwarp trace stops on the planned block - or on the block standing in it (a carpet, snow, a slab
     *  half: the recorded feet put {@link #warpTarget} under it), which lands him on the same spot. */
    private static boolean landsOnPlanned(TeleportUtils.RaycastResult shot) {
        BlockPos p = shot == null || !shot.succeeded() ? null : shot.pos();
        if (p == null || p.getX() != warpTarget.getX() || p.getZ() != warpTarget.getZ()) {
            return false;
        }
        if (p.getY() == warpTarget.getY()) {
            return true;
        }
        var level = Minecraft.getInstance().level;
        return p.getY() == warpTarget.getY() + 1 && level != null
                && !level.getBlockState(p).isCollisionShapeFullBlock(level, p);
    }

    /** What {@code util/RightClick} names the held item by (its Skyblock id, else the vanilla item, else empty hand). */
    private static String heldItemKey(LocalPlayer player) {
        return itemKey(player.getMainHandItem());
    }

    /** Landings (path hops and etherwarp nodes) that put him on a block other than the planned one - read by the testkit. */
    private static int offBlockLandings;
    /** Off-block landings of the path pair flying now, for its "all N warp(s) landed" line. */
    private static int pathOffBlock;

    public static int offBlockLandings() {
        return offBlockLandings;
    }

    /**
     * A landing within {@link #LANDING_TOLERANCE} of the planned feet counts as landed - that is what lets the route
     * carry on - but two blocks is room for the block next to the plan's (killer560, 2026-10-15: "sometimes the path nodes
     * are still taking me to the wrong block"). Here the landing is judged by its BLOCK: the feet over {@link #warpTarget}
     * (its column, at its standing height). A miss is logged with the planned and the actual block and counted; the route
     * goes on from where he is, as before (the next warp is aimed from here).
     */
    private static boolean checkLandingBlock(Minecraft client, LocalPlayer player, RouteNode node) {
        if (warpTarget == null || client.level == null) {
            return true;
        }
        Vec3 at = player.position();
        double standY = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.standYOn(client.level, warpTarget);
        boolean on = Mth.floor(at.x) == warpTarget.getX() && Mth.floor(at.z) == warpTarget.getZ()
                && Math.abs(at.y - (standY + 0.05)) < 0.3;
        if (on) {
            return true;
        }
        offBlockLandings++;
        if (hopIndex >= 0) {
            pathOffBlock++;
        }
        BlockPos under = BlockPos.containing(at.x, at.y - 0.05 - 1e-3, at.z);
        if (client.level.getBlockState(under).getCollisionShape(client.level, under).isEmpty()) {
            under = under.below();
        }
        String what = hopIndex >= 0 ? "path #" + (route.indexOf(node) + 1) + " warp " + (hopIndex + 1) + "/"
                + node.pathHops.size() : "etherwarp #" + (route.indexOf(node) + 1);
        LOGGER.warn("[AutoRoutes] Etherwarp ({}): landed OFF its planned block - planned {} ({}, feet {}), landed at {} on {}"
                        + " ({}), {} blocks from the planned feet; aimed {} / {} from {}", what, warpTarget.toShortString(),
                client.level.getBlockState(warpTarget).getBlock().getName().getString(),
                fmt(new Vec3(warpTarget.getX() + 0.5, standY + 0.05, warpTarget.getZ() + 0.5)), fmt(at),
                under.toShortString(), client.level.getBlockState(under).getBlock().getName().getString(),
                String.format(Locale.US, "%.2f", at.distanceTo(new Vec3(warpTarget.getX() + 0.5, standY + 0.05,
                        warpTarget.getZ() + 0.5))), String.format(Locale.US, "%.4f", Mth.wrapDegrees(warpYaw)),
                String.format(Locale.US, "%.4f", warpPitch), warpAimFrom == null ? "?" : fmt(warpAimFrom));
        return false;
    }

    /** "ETHERWARP", or "PATH hop 2/5" while a path pair flies. */
    private static String traceWhat(RouteNode node) {
        if (node.type == RouteNode.Type.PATH && hopIndex >= 0 && node.pathHops != null) {
            return "PATH hop " + (hopIndex + 1) + "/" + node.pathHops.size();
        }
        return node.type.name();
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
        if (lineWaitTicks == IDLE_SAY_TICKS * 4) {
            // No time limit on this wait (killer560's rule), so it is said once it has held the warp 12 s.
            RouteTrace.timeout(traceIndex(node), traceWhat(node), "still waiting for " + in.toShortString() + " ("
                    + client.level.getBlockState(in).getBlock().getName().getString() + ") to leave the line to "
                    + warpTarget.toShortString() + " - the warp is held, not given up", lineWaitTicks, player.position());
        }
        if (!in.equals(lineWaitBlock)) {
            lineWaitBlock = in.immutable();
            LOGGER.info("[AutoRoutes] Etherwarp node #{}: {} ({}) stands in the line to {} and was not there when the node "
                    + "was placed - waiting for it to move", route.indexOf(node) + 1, in.toShortString(),
                    client.level.getBlockState(in), warpTarget.toShortString());
            AutoRoutesFeature.note(ModChat.text("Waiting for the block at "), ModChat.value(in.toShortString()),
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
            AutoRoutesFeature.note(ModChat.text("Waiting for the breaker block at "), ModChat.value(
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
            AutoRoutesFeature.note(ModChat.text("Waiting for the block at "), ModChat.value(target.toShortString()),
                    ModChat.text(" - the etherwarp lands there."));
        }
        if (blockWaitTicks > BLOCK_WAIT_TIMEOUT) {
            RouteNode n = activeNode;
            RouteTrace.timeout(traceIndex(n), n == null ? "?" : traceWhat(n), "landing block " + target.toShortString()
                    + " never there to stand on (" + client.level.getBlockState(target) + ")", blockWaitTicks,
                    client.player == null ? null : client.player.position());
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
                logHold("unsneak:" + traceIndex(node), "node {} USE_ITEM: waiting for the server to have shift up ({} of {} "
                        + "tick(s))", nodeTag(node), stepTicks, SNEAK_TIMEOUT);
                return;
            }
            if (!aimReady()) {
                logHold("aim:" + traceIndex(node), "node {} USE_ITEM: legit camera turn settling ({} tick(s))",
                        nodeTag(node), stepTicks);
                return;
            }
            if (node.item == null) {
                clickBlockByHand(client, player, node);
                return;
            }
            // A block click may be unpaired on the server (util/RightClick): the use's look reaches it first.
            if (turnFirst(player, RouteCoords.toRealYaw(frame, node.yaw), node.pitch, "[AutoRoutes] Use node")) {
                logHold("useturn:" + traceIndex(node), "node {} USE_ITEM: waiting for the server to hear the look first "
                        + "(a block click is unpaired)", nodeTag(node));
                return;
            }
            actionOrigin = player.position();
            teleportPacketSeen = false;
            useHeldItem(client, player, RouteCoords.toRealYaw(frame, node.yaw), node.pitch, true);
            BlockPos useAt = traceTarget(node);
            RouteTrace.sent(traceIndex(node), "USE_ITEM " + node.item, actionOrigin, node.hasLanding ? useAt : null,
                    node.hasLanding ? blockName(useAt) : null, RouteCoords.toRealYaw(frame, node.yaw), node.pitch);
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
                BlockPos useAt = traceTarget(node);
                Vec3 pos = player.position();
                double standY = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.standYOn(client.level, useAt);
                RouteTrace.landed(traceIndex(node), "USE_ITEM " + node.item, actionOrigin, pos, useAt,
                        Mth.floor(pos.x) == useAt.getX() && Mth.floor(pos.z) == useAt.getZ()
                                && Math.abs(pos.y - (standY + 0.05)) < 0.3, teleportPacketSeen, stepTicks);
                landingConfirmed = teleportPacketSeen;
                RouteRotation.rebase();
                cameraGraceTicks = 3;
                rejoinPathAfterTeleport(player, node);
                finishAction();
                noteLanding(node);
            } else if (stepTicks > LANDING_TIMEOUT + serverLagTicks()) {
                LOGGER.info("[AutoRoutes] Use ({}): no landing after {} tick(s) (limit {} + lag {}) - player {} moved {} from {}",
                        node.item, stepTicks, LANDING_TIMEOUT, serverLagTicks(), fmt(player.position()),
                        String.format(Locale.ROOT, "%.2f", actionOrigin == null ? 0.0
                                : player.position().distanceTo(actionOrigin)), fmt(actionOrigin));
                RouteTrace.timeout(traceIndex(node), "USE_ITEM " + node.item, "no landing at the recorded spot (server "
                        + "teleport " + (teleportPacketSeen ? "came, elsewhere" : "never came") + ")", stepTicks,
                        player.position());
                stop(node.item + " didn't teleport where it was recorded");
            }
        }
    }

    /**
     * Whether Secret Aura may click a secret block now (killer560, 2026-10-16: "never have secret aura grab secrets while
     * it is auto routing unless it is on an await node"). True with no route running - between routes, an Interactive
     * Map trip, Auto Secret's last-node wait after the route completed - and, while one runs, only when the node being
     * performed is waiting on a SECRET await it has not met yet (a legacy AWAIT node included). False everywhere else
     * in a run: a node acting (a warp in flight included), the settle or stack between two nodes, a walk to the next
     * node, a DELAY await, a crypt or bat node (their await counts kills), and an await already met (its action goes
     * this tick, and a click beside the warp's use would be left unpaired - util/RightClick).
     * Read at the START of the tick, before the route's own tick: the state the route left last tick.
     */
    public static boolean secretAuraMayClick() {
        if (!running) {
            return true;
        }
        RouteNode node = activeNode;
        if (node == null || awaitPhaseDone || node.awaitCondition == RouteNode.AwaitCondition.DELAY) {
            return false;
        }
        return AwaitEvents.secrets() + awaitRemembered.size() < Math.max(1, node.awaitAmount);
    }

    /** The etherwarp item's slot while a Dungeon Breaker use pairs the stored block click for one tick, else -1. */
    private static int warpSpentFromSlot = -1;

    /**
     * killer560's Bridges (2026-10-17, "after the second dungeon breaker I cannot get it to make that etherwarp ... if it
     * stalls out then it works"): Secret Aura flipped node #1's lever with the Aspect of the Void in hand, and #1's warp
     * went as a right click in the air, so that click stayed unpaired on the server. #2's breaker then opened the hole
     * the next warp drops through, and that warp's look passes 0.27 under the hole's rim (Stone Bricks one block over
     * the broken ones) from this client's 1.27 eye while the 1.8 server's 1.54 ray meets it: no block half could be sent,
     * no other look onto the landing had rays that agree ({@code RightClick.reaimIfUnpairable}), so the warp went out
     * to be taken as the lever click's second half and dropped - "no landing after 61 ticks", then the retry landed
     * (the drop had paired the click). The 2026-10-16 resend made that a round trip; this pairs the click first with a
     * use of the Dungeon Breaker (no right-click ability; a bare use is what a vanilla right click sends along a look
     * that reaches no block), and the warp goes the next tick with nothing stored. Only when the warp's look is the
     * one the server has heard and {@code RightClick.unpairable} holds; with no breaker in the hotbar the old send (and
     * its resend) stays. True when the use went (the caller waits one tick).
     */
    private static boolean spendStaleClickFirst(Minecraft client, LocalPlayer player) {
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        float yaw = legit ? player.getYRot() : player.getYRot() + Mth.wrapDegrees(warpYaw - player.getYRot());
        float pitch = legit ? player.getXRot() : Mth.clamp(warpPitch, -90f, 90f);
        if (!com.killer560.hub.util.RightClick.unpaired()) {
            return false;
        }
        // A block click left unpaired (Secret Aura's click on a Wither Essence skull with the Aspect of the Void in hand,
        // say) is taken by Hypixel as the first half of the warp's use: his Mod Only Test log 2026-10-19 15:47:15 - the
        // essence click at -61, 60, -78, the path warp 1 tick later moved him 1.00 toward the skull ("There are blocks in
        // the way!") and the route flew warp 1 again. Every reason the click is NOT spent first is logged, so the next log
        // says which one let a use pair with it.
        boolean heard = com.killer560.hub.util.RightClick.heard(player, yaw, pitch);
        boolean unpairable = heard && com.killer560.hub.util.RightClick.unpairable(client, player, null, yaw, pitch);
        int breaker = ItemIdentity.findHotbarSlotById(player, ItemIdentity.BREAKER_ID);
        if (!heard || !unpairable || breaker < 0) {
            LOGGER.info("[AutoRoutes] secret click unpaired before the warp's use, not spent first: {} - the use may be "
                            + "taken as that click's second half (look {} / {})",
                    !heard ? "the server has not heard the warp's look yet" : !unpairable
                            ? "the warp's look reaches a block from the client eye (a bare use cannot be sent)"
                            : "no Dungeon Breaker in the hotbar to spend it with",
                    String.format(Locale.US, "%.2f", yaw), String.format(Locale.US, "%.2f", pitch));
            return false;
        }
        int ether = player.getInventory().getSelectedSlot();
        select(client, player, breaker);
        if (!com.killer560.hub.util.RightClick.spendStoredClick(client, player, "[AutoRoutes] Etherwarp")) {
            LOGGER.info("[AutoRoutes] secret click unpaired: the Dungeon Breaker use to pair it was not sent - etherwarp "
                    + "item back (slot {}), the warp goes as it is", ether + 1);
            select(client, player, ether);
            return false;
        }
        LOGGER.info("[AutoRoutes] secret click unpaired: paired it with a Dungeon Breaker use (slot {}) at tick {} - the "
                + "warp goes next tick from slot {}", breaker + 1, execTicks, ether + 1);
        warpSpentFromSlot = ether;
        return true;
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
                LOGGER.info("[AutoRoutes] Node #{} {}: no {} ({}) in the hotbar - stopping", route.indexOf(node) + 1, KIND,
                        weapon.label(), weapon.itemId());
                stop("the " + kind + " node has no " + weapon.label() + " in the hotbar (" + (bat ? "Bat" : "Crypt")
                        + " Weapon setting)");
                return;
            }
            cryptSlotBefore = player.getInventory().getSelectedSlot();
            LOGGER.info("[AutoRoutes] Node #{} {} PREP: {} in slot {} (was slot {}), goal {} kill(s), {} kill(s) counted so "
                            + "far, aiming straight down, attack time {} tick(s)", route.indexOf(node) + 1, KIND,
                    weapon.label(), slot + 1, cryptSlotBefore + 1, goal, kills(bat),
                    bat ? AutoRoutesConfig.getInstance().getBatAttackTicks()
                            : AutoRoutesConfig.getInstance().getCryptAttackTicks());
            select(client, player, slot);
            forceSneak = false;
            wantSneak = false;
            // STRAIGHT DOWN, always (killer560, 2026-10-05: "looking straight down so that way it doesn't teleport
            // and just explodes"; 2026-10-11: "those nodes shouldn't matter my facing angle they should always be
            // straight down"). Pitch exactly 90.0 whatever the node recorded or the camera reads; HIS yaw, so nothing
            // turns sideways (the node's recorded yaw used to be turned to).
            aimAt(player.getYRot(), MimicKiller.STRAIGHT_DOWN, node);
            cryptDownTicks = 0;
            batSeeking = false;
            cryptShots.clear();
            cryptTarget = -1;
            cryptBats.clear();
            cryptBatsSeen.clear();
            cryptHandled = false;
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            if (player.getLastSentInput().shift() && stepTicks <= SNEAK_TIMEOUT) {
                logHold("cryptunsneak:" + traceIndex(node), "node {}: waiting for the server to have shift up ({} of {} "
                        + "tick(s))", nodeTag(node), stepTicks, SNEAK_TIMEOUT);
                return;
            }
            if (AutoRoutesConfig.getInstance().isLegitMode() && player.getXRot() != MimicKiller.STRAIGHT_DOWN) {
                logHold("cryptlegitaim:" + traceIndex(node), "node {}: legit camera turning straight down (pitch {}, {} of "
                        + "{} tick(s))", nodeTag(node), player.getXRot(), stepTicks, CRYPT_AIM_GIVE_UP);
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
                logHold("cryptaim:" + traceIndex(node), "node {}: legit aim settling ({} tick(s))", nodeTag(node),
                        stepTicks);
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
                logHold("cryptdown:" + traceIndex(node), "node {}: look at pitch 90.0 for {} of 2 tick(s) before holding "
                        + "use (pitch now {})", nodeTag(node), cryptDownTicks, player.getXRot());
                return;
            }
            // HOLD the use key, as a player holding right click does (killer560, 2026-10-06: "only have the crypt node
            // hold right click for its equivalent of attacking do not have it spam click"). Vanilla's handleKeybinds
            // runs later in this same tick and turns a held use key into startUseItem whenever its rightClickDelay is
            // 0 - so the first use goes on the firing tick and then one every 4 ticks, packet for packet what a held
            // right click sends (javap, 26.1.2 and 26.2: keyUse.isDown && rightClickDelay == 0 && !isUsingItem).
            // Under the Interactive Map (Run While Map Open) handleKeybinds does not run; HeldUsePickMixin takes
            // that step instead (heldUseUnderScreen).
            batUseBase = AwaitEvents.weaponUses();
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
            // killer560, 2026-10-15: "make the crypt node follow the same logic as bat where it will try to look at a
            // crypt as well. If there are two or more that spawned have it do 2 bats at each before moving on." A Spirit
            // Sceptre crypt node goes on until every crypt mob it has seen is dead or has had its CRYPT_SHOTS_EACH aimed
            // uses (and their bats have landed) - not merely until the kill count, which would leave the others alive.
            boolean sceptreCrypt = !bat && weapon == AutoRoutesConfig.CryptWeapon.SPIRIT_SCEPTRE;
            CryptSeek seek = sceptreCrypt ? cryptSeekState(client, player, goal) : CryptSeek.NONE;
            if (seek == CryptSeek.DONE) {
                int dead = 0;
                int shotOut = 0;
                for (Map.Entry<Integer, Integer> e : cryptShots.entrySet()) {
                    if (cryptMobGone(client, e.getKey())) {
                        dead++;
                    } else if (e.getValue() >= CRYPT_SHOTS_EACH) {
                        shotOut++;
                    }
                }
                LOGGER.info("[AutoRoutes] Node #{} CRYPT: every crypt mob dead or given its {} uses ({} seen, {} dead, {} "
                                + "alive after its uses, {} kill(s) counted) after holding use {} tick(s) - moving on",
                        route.indexOf(node) + 1, CRYPT_SHOTS_EACH, cryptShots.size(), dead, shotOut, kills(false),
                        stepTicks);
                releaseUse(client);
                restoreCryptSlot(client, player);
                finishAction();
                return;
            }
            if (kills(bat) >= goal && seek == CryptSeek.NONE) {
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
            if (stepTicks > attackTicks + serverLagTicks()) {
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
            // killer560, 2026-10-15: "If I have the spirit scepter set to bat mode have the first 2 bats go straight down
            // after that have it look right at the bat to use the rest of them till it times out. If using hype have it
            // keep looking down." The Hyperion (and a sceptre with no bat in sight) keeps the held right click down.
            if (bat && weapon == AutoRoutesConfig.CryptWeapon.SPIRIT_SCEPTRE
                    && AwaitEvents.weaponUses() - batUseBase >= BAT_DOWN_USES && tickBatSeek(client, player, node)) {
                return;
            }
            // The crypt node the same way: its first BAT_DOWN_USES go straight down (they blow up what is under him),
            // then the sceptre is used AT each crypt mob still alive, CRYPT_SHOTS_EACH apiece.
            if (seek == CryptSeek.PENDING && AwaitEvents.weaponUses() - batUseBase >= BAT_DOWN_USES
                    && tickCryptSeek(client, player, node)) {
                return;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[AutoRoutes] Node #{} {}: holding use {} of {} tick(s), {} of {} kill(s), {} weapon use(s), "
                                + "seek {}", route.indexOf(node) + 1, KIND, stepTicks, attackTicks, kills(bat), goal,
                        AwaitEvents.weaponUses() - batUseBase, seek);
            }
            holdUse(client, player, node);
        }
    }

    /** Where a Spirit Sceptre crypt node is with the crypt mobs it has seen. */
    private enum CryptSeek {
        /** None seen (or only ones its bats cannot reach, none handled): the node behaves as it always did. */
        NONE,
        /** One still needs a use, or its last bats are still flying. */
        PENDING,
        /** Every one seen is dead, or has had its uses and their bats have landed. */
        DONE
    }

    /** A crypt mob the node tracked is dead or no longer in his world. */
    private static boolean cryptMobGone(Minecraft client, int id) {
        Entity e = client.level == null ? null : client.level.getEntity(id);
        return !(e instanceof net.minecraft.world.entity.LivingEntity l) || l.isRemoved() || l.isDeadOrDying();
    }

    /** The tracked crypt mob still owed a use (alive, under its uses, its bat can reach it), else null. */
    private static net.minecraft.world.entity.LivingEntity cryptMobOwed(Minecraft client, LocalPlayer player, int id) {
        if (cryptMobGone(client, id) || cryptShots.getOrDefault(id, 0) >= CRYPT_SHOTS_EACH) {
            return null;
        }
        var mob = (net.minecraft.world.entity.LivingEntity) client.level.getEntity(id);
        if (mob.distanceTo(player) > CRYPT_SEEK_RANGE) {
            return null;
        }
        // Reach and line of sight in the sceptre's own terms: the bat flown from his eye at the mob's centre explodes on
        // the first block or mob it meets, and the 6-block blast must take in the mob's box (com.killer560.hub.autoclear
        // .WeaponReach, the numbers Auto Clear plans with). A crypt mob behind a wall the blast cannot reach is skipped.
        // Blocks only: WeaponReach.batImpact also stops at any living entity on the line, and the node's own sceptre bat
        // in flight is one - it made the mob read "out of reach" for a tick and the node turned to the next mob between
        // a mob's two uses (crypt-aim-2-1016, 96-ar-1015-cryptaim "2 mobs": 14, 13, 14).
        return sceptreReaches(client, player, mob) ? mob : null;
    }

    /**
     * Whether a Spirit Sceptre bat flown from his eye at {@code mob}'s centre takes it in: the bat explodes on the first
     * block it meets (blocks only - see {@link #cryptMobOwed}) and its blast must reach the mob's box
     * ({@link com.killer560.hub.autoclear.WeaponReach}). Shared by the crypt node and Kill Mimic's aimed uses.
     */
    static boolean sceptreReaches(Minecraft client, LocalPlayer player, net.minecraft.world.entity.LivingEntity mob) {
        Vec3 eye = player.getEyePosition();
        AABB box = mob.getBoundingBox();
        Vec3 end = eye.add(box.getCenter().subtract(eye).normalize().scale(com.killer560.hub.autoclear.WeaponReach.SCEPTRE_PLAN_RANGE));
        BlockHitResult wall = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
        Vec3 impact = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
        java.util.Optional<Vec3> onMob = box.clip(eye, impact);
        return onMob.isPresent() || com.killer560.hub.autoclear.WeaponReach.sceptreHits(impact, box);
    }

    /**
     * One tick of a Spirit Sceptre crypt node's bookkeeping: takes in every crypt mob near him (one that spawns a tick
     * late is taken the tick it is seen), notes its own sceptre bats in flight, and says where the node stands.
     */
    private static CryptSeek cryptSeekState(Minecraft client, LocalPlayer player, int goal) {
        if (client.level == null) {
            return CryptSeek.NONE;
        }
        for (var mob : CryptMobs.near(client, player, CRYPT_SEEK_RANGE)) {
            cryptShots.putIfAbsent(mob.getId(), 0);
        }
        // Its own bats: a non-secret bat first seen by his eye once the node holds use. Gone (exploded) = landed.
        Vec3 eye = player.getEyePosition();
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.ambient.Bat b && !AwaitEvents.isSecretBat(b)
                    && cryptBatsSeen.add(b.getId()) && b.position().distanceTo(eye) <= 4.0) {
                cryptBats.add(b.getId());
            }
        }
        cryptBats.removeIf(id -> {
            Entity e = client.level.getEntity(id);
            return e == null || e.isRemoved() || !e.isAlive();
        });
        if (cryptShots.isEmpty()) {
            return CryptSeek.NONE;
        }
        boolean allGone = true;
        int dead = 0;
        for (int id : cryptShots.keySet()) {
            if (cryptMobGone(client, id)) {
                cryptHandled = true;
                dead++;
            } else {
                allGone = false;
            }
        }
        if (allGone) {
            // Every one it saw is dead: done the tick it sees it, without the tab list's count a second later - unless
            // its await asks for more kills than it saw die, when it goes on as it always did (down, for the rest).
            return dead >= goal ? CryptSeek.DONE : CryptSeek.NONE;
        }
        for (int id : cryptShots.keySet()) {
            if (cryptMobOwed(client, player, id) != null) {
                return CryptSeek.PENDING;
            }
        }
        if (!cryptHandled) {
            return CryptSeek.NONE;
        }
        return cryptBats.isEmpty() ? CryptSeek.DONE : CryptSeek.PENDING;
    }

    /**
     * A Spirit Sceptre CRYPT node past its {@link #BAT_DOWN_USES} downward uses: the sceptre is used AT one crypt mob at a
     * time, {@link #CRYPT_SHOTS_EACH} uses apiece one {@link #BAT_USE_GAP} apart (the held key's pace), the nearest owed
     * one first, exactly as {@link #tickBatSeek} aims at a bat. With nothing owed it waits, key up, for its bats to land.
     * Always true: the caller only comes here while {@link CryptSeek#PENDING}.
     */
    private static boolean tickCryptSeek(Minecraft client, LocalPlayer player, RouteNode node) {
        releaseUse(client);
        net.minecraft.world.entity.LivingEntity target = cryptTarget >= 0 ? cryptMobOwed(client, player, cryptTarget) : null;
        if (target == null) {
            double best = Double.MAX_VALUE;
            for (int id : cryptShots.keySet()) {
                var mob = cryptMobOwed(client, player, id);
                if (mob != null && mob.distanceToSqr(player) < best) {
                    best = mob.distanceToSqr(player);
                    target = mob;
                }
            }
            if (target == null) {
                logHold("cryptbats:" + traceIndex(node), "node {} CRYPT: no crypt mob owed a use - waiting for {} bat(s) "
                        + "in flight to land ({} mob(s) seen)", nodeTag(node), cryptBats.size(), cryptShots.size());
                return true; // its last bats are still flying
            }
        }
        if (!batSeeking) {
            batSeeking = true;
            batLastUse = stepTicks - 1;
            LOGGER.info("[AutoRoutes] Node #{} CRYPT: {} uses went down - now aiming the Spirit Sceptre at {} crypt mob(s)",
                    route.indexOf(node) + 1, BAT_DOWN_USES, cryptShots.size());
        }
        if (cryptTarget != target.getId()) {
            cryptTarget = target.getId();
            LOGGER.info("[AutoRoutes] Node #{} CRYPT: aiming at crypt mob {} ({}) at {}, {} blocks", route.indexOf(node) + 1,
                    target.getId(), target.getName().getString(), fmt(target.position()),
                    String.format(Locale.US, "%.1f", (double) target.distanceTo(player)));
        }
        if (sceptreUseAt(client, player, target.getBoundingBox().getCenter())) {
            int n = cryptShots.merge(target.getId(), 1, Integer::sum);
            cryptHandled = true;
            LOGGER.info("[AutoRoutes] Node #{} CRYPT: Spirit Sceptre use {}/{} at crypt mob {} (yaw {}, pitch {}, {} blocks)",
                    route.indexOf(node) + 1, n, CRYPT_SHOTS_EACH, target.getId(), Float.toString(lastSceptreYaw),
                    Float.toString(lastSceptrePitch), String.format(Locale.US, "%.1f", (double) target.distanceTo(player)));
        }
        return true;
    }

    /** The rotation of the last {@link #sceptreUseAt} use, for its caller's log line. */
    private static float lastSceptreYaw;
    private static float lastSceptrePitch;

    /**
     * The bat node's aimed Spirit Sceptre use, shared by the crypt node: the look from his eye to {@code at} (pitch within
     * +-90, yaw relative to his running value, never wrapped); obvious mode turns the body as every route use does
     * ({@link #useHeldItem}), legit mode eases the camera and goes once within {@link #BAT_AIM_TOLERANCE}. A use goes at
     * most every {@link #BAT_USE_GAP} ticks. True when one went this tick.
     */
    private static boolean sceptreUseAt(Minecraft client, LocalPlayer player, Vec3 at) {
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        TeleportUtils.Rotation r = TeleportUtils.getDirection(player.getEyePosition(), at);
        float dYaw = Mth.wrapDegrees(r.yaw() - player.getYRot());
        float pitch = Mth.clamp(r.pitch(), -90f, 90f);
        float yaw = player.getYRot() + dYaw;
        if (legit) {
            if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                RouteRotation.beginApproach(yaw, pitch, false, 0f, 0f);
            }
        }
        float error = (float) Math.hypot(dYaw, pitch - player.getXRot());
        if (stepTicks - batLastUse >= BAT_USE_GAP && (!legit || error <= BAT_AIM_TOLERANCE)) {
            batLastUse = stepTicks;
            lastSceptreYaw = yaw;
            lastSceptrePitch = pitch;
            useHeldItem(client, player, yaw, pitch, false);
            return true;
        }
        return false;
    }

    /** The secret bat nearest him (alive, Skyblock health), or null: what a sceptre BAT node aims at after its first uses. */
    private static net.minecraft.world.entity.ambient.Bat nearestSecretBat(Minecraft client, LocalPlayer player) {
        net.minecraft.world.entity.ambient.Bat best = null;
        double bestD = 40.0 * 40.0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.ambient.Bat b && !b.isRemoved() && !b.isDeadOrDying()
                    && AwaitEvents.isSecretBat(b)) {
                double d = b.distanceToSqr(player);
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
        }
        return best;
    }

    /**
     * A Spirit Sceptre BAT node past its {@link #BAT_DOWN_USES} downward uses: the sceptre is used AT the nearest secret
     * bat, one use per {@link #BAT_USE_GAP} ticks (the held key's pace), until the kill or Bat Attack Time. The key is
     * let go - a held use acts on the block along the look, and a look at a bat can have a chest or lever on it - and each use is
     * a plain item use (no block) at the bat's centre: obvious mode turns the body as every route use does
     * ({@link #useHeldItem}), legit mode eases the camera and goes once it is within {@link #BAT_AIM_TOLERANCE}.
     * Returns false when no secret bat is there, and the node falls back to looking down.
     */
    private static boolean tickBatSeek(Minecraft client, LocalPlayer player, RouteNode node) {
        net.minecraft.world.entity.ambient.Bat target = nearestSecretBat(client, player);
        if (target == null) {
            if (batSeeking) {
                LOGGER.info("[AutoRoutes] Node #{} BAT: no secret bat in sight any more - back to holding use straight "
                        + "down ({} tick(s) in)", route.indexOf(node) + 1, stepTicks);
            }
            batSeeking = false;
            return false;
        }
        releaseUse(client);
        if (!batSeeking) {
            batSeeking = true;
            batLastUse = stepTicks - 1;
            LOGGER.info("[AutoRoutes] Node #{} BAT: {} uses went down - now aiming the Spirit Sceptre at the secret bat",
                    route.indexOf(node) + 1, BAT_DOWN_USES);
        }
        if (sceptreUseAt(client, player, target.getBoundingBox().getCenter())) {
            LOGGER.info("[AutoRoutes] Node #{} BAT: Spirit Sceptre use at the bat (yaw {}, pitch {}, {} blocks)",
                    route.indexOf(node) + 1, Float.toString(lastSceptreYaw), Float.toString(lastSceptrePitch),
                    String.format(Locale.US, "%.1f", (double) target.distanceTo(player)));
        }
        return true;
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
            logHold("usedrift:" + traceIndex(node), "node {}: legit camera drifted off pitch 90.0 ({}) - use key up "
                    + "while it turns back", nodeTag(node), player.getXRot());
            client.options.keyUse.setDown(false);
            if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                RouteRotation.beginApproach(player.getYRot(), MimicKiller.STRAIGHT_DOWN, false, 0f, 0f);
            }
            useHeld = true;
            return;
        }
        if (!useHeld || !client.options.keyUse.isDown()) {
            LOGGER.info("[AutoRoutes] node {}: use key held down at tick {} (slot {}, pitch {})", nodeTag(node), execTicks,
                    player.getInventory().getSelectedSlot() + 1, player.getXRot());
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
        LOGGER.info("[AutoRoutes] use key released at tick {} (node {}, step {}, {} tick(s) in step)", execTicks,
                nodeTag(activeNode), step, stepTicks);
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
            RouteTrace.timeout(traceIndex(node), "USE_ITEM click", "no block in sight within 4.5 along the recorded look",
                    stepTicks, player.position());
            stop("the empty-hand use node has nothing to click (no block in sight within 4.5)");
            return;
        }
        if (com.killer560.hub.util.ShadowAssassinLeap.refuses(client, hit.getBlockPos())) {
            // A Shadow Assassin's leap (util/ShadowAssassinLeap): vanilla would refuse this click before sending it, and
            // the node used to finish on the lost click. Held in AIM until the border lifts - the click goes on that
            // tick - with HOLD_MAX_MS as the ceiling, past which it clicks as before and moves on.
            long nowMs = System.currentTimeMillis();
            if (useBorderHoldSinceMs < 0) {
                useBorderHoldSinceMs = nowMs;
                LOGGER.info("[AutoRoutes] Node #{} USE_ITEM (empty hand): {} at {} is outside the world border ({}) - "
                                + "holding the click until it lifts", route.indexOf(node) + 1,
                        blockName(hit.getBlockPos()), hit.getBlockPos().toShortString(),
                        com.killer560.hub.util.ShadowAssassinLeap.describe(client));
            }
            if (nowMs - useBorderHoldSinceMs < com.killer560.hub.util.ShadowAssassinLeap.HOLD_MAX_MS) {
                return;
            }
            LOGGER.info("[AutoRoutes] Node #{} USE_ITEM (empty hand): the border still refuses the click after {} ms - "
                    + "clicking as usual", route.indexOf(node) + 1, nowMs - useBorderHoldSinceMs);
            AutoRoutesFeature.chatBad("Node #" + (route.indexOf(node) + 1) + ": its click is outside the world border ("
                    + com.killer560.hub.util.ShadowAssassinLeap.describe(client) + ") - it will not register; going on.");
        } else if (useBorderHoldSinceMs >= 0) {
            LOGGER.info("[AutoRoutes] Node #{} USE_ITEM (empty hand): the world border lifted after {} ms - clicking now",
                    route.indexOf(node) + 1, System.currentTimeMillis() - useBorderHoldSinceMs);
        }
        useBorderHoldSinceMs = -1;
        RouteTrace.sent(traceIndex(node), "USE_ITEM click", player.position(), hit.getBlockPos(),
                blockName(hit.getBlockPos()), RouteCoords.toRealYaw(frame, node.yaw), node.pitch);
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
        if (step == Step.PREP && RouteRunMemory.boomedThisRun(RouteRunMemory.boomKey(route, node))) {
            // It blew its wall earlier this run, and a blown crypt or wall stays open: sending it again only waited a
            // round trip (and, on a landing tick, a second one) to read "nothing changed" (his Mod Only Test 2026-10-20
            // 09:56:46 and 09:56:51: Slime's stack run twice more, 14-15 ticks for #6 each time).
            logActed(node, " (it blew its wall earlier this run - nothing left to blow, moving on)");
            step = Step.CONFIRM;
            stepTicks = 0;
            finishAction();
            return;
        }
        if (step == Step.PREP) {
            int slot = ItemIdentity.findHotbarSlotById(player, BOOM_IDS);
            if (slot < 0) {
                LOGGER.info("[AutoRoutes] Node #{} BOOM: no INFINITE_SUPERBOOM_TNT / SUPERBOOM_TNT in the hotbar - stopping",
                        route.indexOf(node) + 1);
                stop("no Superboom in the hotbar");
                return;
            }
            LOGGER.info("[AutoRoutes] Node #{} BOOM PREP: {} in slot {}, look {} / {} ({} mode)", route.indexOf(node) + 1,
                    ItemIdentity.skyblockId(player.getInventory().getItem(slot)), slot + 1,
                    String.format(Locale.ROOT, "%.2f", RouteCoords.toRealYaw(frame, node.yaw)),
                    String.format(Locale.ROOT, "%.2f", node.pitch),
                    AutoRoutesConfig.getInstance().isLegitMode() ? "legit" : "obvious");
            select(client, player, slot);
            planBoom(client, player, node);
            if (boomHitFromHere(client, player, node) != null) {
                aimAt(boomAimYaw, boomAimPitch, node);
            } else {
                aimAt(node);
            }
            step = Step.AIM;
            stepTicks = 0;
        }
        if (step == Step.AIM) {
            if (!aimReady()) {
                logHold("boomaim:" + traceIndex(node), "node {} BOOM: legit camera turn settling ({} tick(s))",
                        nodeTag(node), stepTicks);
                return;
            }
            BlockHitResult hit = AutoRoutesConfig.getInstance().isLegitMode() ? blockInSight(client, player, node, 4.5)
                    : boomHitFromHere(client, player, node);
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
                turnBody(player, player.getYRot() + Mth.wrapDegrees(boomAimYaw - player.getYRot()),
                        Mth.clamp(boomAimPitch, -90f, 90f));
                sendBoomTap(client, player, boomTarget, hit.getDirection());
            }
            player.swing(InteractionHand.MAIN_HAND);
            boomSentAt = execTicks;
            LOGGER.info("[AutoRoutes] Node #{} BOOM: START+ABORT dig sent at {} ({}) face {} from {} at tick {} - watching "
                            + "{} block(s) within {} for a change", route.indexOf(node) + 1, boomTarget.toShortString(),
                    client.level.getBlockState(boomTarget).getBlock().getName().getString(), hit.getDirection(),
                    fmt(player.getEyePosition()), execTicks, boomBefore.size(), BOOM_WATCH_RADIUS);
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
                int changedCount = 0;
                for (Map.Entry<BlockPos, BlockState> e : boomBefore.entrySet()) {
                    if (client.level.getBlockState(e.getKey()) != e.getValue()) {
                        changedCount++;
                    }
                }
                LOGGER.info("[AutoRoutes] Boom: blocks changed {} tick(s) after the click", stepTicks);
                LOGGER.info("[AutoRoutes] Boom ({}): {} of {} watched block(s) changed around {} (settle limit {} + lag {})",
                        nodeTag(node), changedCount, boomBefore.size(),
                        boomTarget == null ? "?" : boomTarget.toShortString(), boomSettleTicks(), serverLagTicks());
                RouteRunMemory.boomed(RouteRunMemory.boomKey(route, node));
                finishAction();
            } else if (stepTicks >= boomSettleTicks() + serverLagTicks() && !boomRetried
                    && boomSentAt - lastLandingAt <= BOOM_LANDING_TICKS) {
                // Sent on (or right after) a landing tick, now that no settle holds a node after a landing: if the server
                // did not take it, "nothing changed" would read as "nothing left to blow" and the route would go on past a
                // shut wall. So once, it goes again from here before that is believed. 38 of 92 booms changed nothing in
                // his 2026-10-19 logs (earlier booms of a stack), so this is only for the landing-tick case.
                boomRetried = true;
                LOGGER.info("[AutoRoutes] Boom ({}): nothing changed {} tick(s) after a boom sent {} tick(s) after a landing - "
                        + "sending it once more before calling the wall open", nodeTag(node), stepTicks,
                        boomSentAt - lastLandingAt);
                noteSecretGap("boom changed nothing - sent once more (it went on a landing tick)");
                step = Step.AIM;
                stepTicks = 0;
            } else if (stepTicks >= boomSettleTicks() + serverLagTicks()) {
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

    /**
     * A Superboom's left click on {@code target}, packet for packet what a vanilla tap on it sends: START through the
     * block-prediction sequence with the face the ray struck, then ABORT with face DOWN (vanilla's stopDestroyBlock always
     * says DOWN, javap 26.1.2 - an ABORT carrying the struck face made GrimAC's PositionBreakB flag every later dig,
     * 2026-10-05). The caller turns the body to the block first, so GrimAC's RotationBreak sees a look that strikes it.
     * Shared by the boom node and Auto Clear's King Midas altar; the caller swings.
     */
    public static void sendBoomTap(Minecraft client, LocalPlayer player, BlockPos target, Direction face) {
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

    /** The hotbar slot of a Superboom (infinite or plain), or -1 - the boom node's own lookup. */
    public static int superboomSlot(LocalPlayer player) {
        return ItemIdentity.findHotbarSlotById(player, BOOM_IDS);
    }

    /**
     * The block a boom node was recorded on: its recorded look from the node's own STANDING eye (a node is added with
     * {@code /ar add boom}, typed with the chat open, so never sneaking). The superboom acts on the block the dig names,
     * and Hypixel blows what lies within 3 of THAT block, so the block must not depend on how he happens to stand.
     * Until 2026-10-20 the look was cast from the live eye: sneaking (the sneak held from the warp before, 0.35 lower)
     * or 0.05 up on a landing tick, a shallow look met the floor a block short. His Museum #11 (27 degrees down, killer560
     * Mod Only Test 2026-10-20 09:55:55): from the sneaking eye -167, 68, -163 and nothing blew; fired standing at 09:56:14,
     * -166, 68, -163 and 30 blocks went. The second boom of a stack is the one that fires with the warp's sneak still held.
     */
    private static void planBoom(Minecraft client, LocalPlayer player, RouteNode node) {
        boomPlanned = null;
        boomPlannedFace = null;
        boomPlannedHit = null;
        Vec3 at = RouteCoords.toReal(frame, node.relativePos());
        Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(false), at.z);
        BlockHitResult b = clipAlong(client, player, eye, RouteCoords.toRealYaw(frame, node.yaw), node.pitch);
        if (b != null) {
            boomPlanned = b.getBlockPos().immutable();
            boomPlannedFace = b.getDirection();
            boomPlannedHit = b.getLocation();
        }
        Vec3 live = player.getEyePosition();
        LOGGER.info("[AutoRoutes] Node #{} BOOM: recorded look from the node's standing eye {} meets {} - the live eye {} is "
                        + "{} above it", route.indexOf(node) + 1, fmt(eye),
                boomPlanned == null ? "nothing within 4.5" : boomPlanned.toShortString() + " ("
                        + client.level.getBlockState(boomPlanned).getBlock().getName().getString() + ", face "
                        + boomPlannedFace + ")", fmt(live), String.format(Locale.ROOT, "%.2f", live.y - eye.y));
    }

    /**
     * Where a boom goes from the eye he has now, and the look it goes along ({@link #boomAimYaw}/{@link #boomAimPitch}):
     * the recorded look when from here it still meets {@link #boomPlanned}; else a look from here onto that block (the
     * recorded hit point, then its face's centre and corners); else, when nothing from here meets it, the dig still names
     * that block with the recorded face (a dig is not judged by line of sight: testkit 54). With no planned block it is
     * the recorded look from here, as before.
     */
    private static BlockHitResult boomHitFromHere(Minecraft client, LocalPlayer player, RouteNode node) {
        float recYaw = RouteCoords.toRealYaw(frame, node.yaw);
        boomAimYaw = recYaw;
        boomAimPitch = node.pitch;
        Vec3 eye = player.getEyePosition();
        BlockHitResult along = clipAlong(client, player, eye, recYaw, node.pitch);
        if (boomPlanned == null || (along != null && along.getBlockPos().equals(boomPlanned))) {
            return along;
        }
        List<Vec3> points = new ArrayList<>();
        if (boomPlannedHit != null) {
            points.add(boomPlannedHit);
        }
        Vec3 c = Vec3.atCenterOf(boomPlanned);
        Vec3 n = new Vec3(boomPlannedFace.getStepX(), boomPlannedFace.getStepY(), boomPlannedFace.getStepZ());
        Vec3 faceC = c.add(n.scale(0.5));
        points.add(faceC);
        for (double u : new double[]{-0.35, 0.35}) {
            for (double v : new double[]{-0.35, 0.35}) {
                Vec3 off = n.x != 0 ? new Vec3(0, u, v) : n.y != 0 ? new Vec3(u, 0, v) : new Vec3(u, v, 0);
                points.add(faceC.add(off));
            }
        }
        for (Vec3 p : points) {
            TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, p.subtract(n.scale(0.01)));
            BlockHitResult h = clipAlong(client, player, eye, r.yaw(), r.pitch());
            if (h != null && h.getBlockPos().equals(boomPlanned)) {
                boomAimYaw = r.yaw();
                boomAimPitch = r.pitch();
                return h;
            }
        }
        TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, faceC);
        boomAimYaw = r.yaw();
        boomAimPitch = r.pitch();
        return new BlockHitResult(faceC, boomPlannedFace, boomPlanned, false);
    }

    private static BlockHitResult clipAlong(Minecraft client, LocalPlayer player, Vec3 eye, float yaw, float pitch) {
        Vec3 look = TeleportUtils.getLook(yaw, pitch).scale(4.5);
        HitResult hit = client.level.clip(new ClipContext(eye, eye.add(look), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b : null;
    }

    /** Ticks a boom that changed nothing waits: the measured round trip ({@link
     *  com.killer560.hub.util.PingTracker#automationMs}) rounded up, plus a margin, bounded - 5 at a local 1-50 ms, 8 at
     *  200 ms, 20 (a second) above 750 ms. Until 2026-10-11 the round trip was the tab-list latency, about 1 ms on
     *  Hypixel, so a boom on a real connection waited 5 ticks whatever the ping. */
    /** Ticks the server has stalled since this step began (util/ServerLag): added to every deadline that waits on it. */
    private static int serverLagTicks() {
        return com.killer560.hub.util.ServerLag.ticksSince(stepLag);
    }

    public static int boomSettleTicks() {
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
                LOGGER.info("[AutoRoutes] Node #{} DUNGEON_BREAKER: no Dungeon Breaker in the hotbar - stopping ({} block(s) "
                        + "queued)", route.indexOf(node) + 1, breakerQueue.size());
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
            breakerStandTicks = 0;
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
            BlockPos onlyStanding = null;
            Vec3 standEye = player.position().add(0, STANDING_EYE, 0);
            for (BlockPos pos : breakerQueue) {
                if (client.level.isLoaded(pos) && !client.level.getBlockState(pos).isAir()
                        && !com.killer560.hub.dungeonbreaker.BreakerGuard.protects(client.level, pos)) {
                    if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos) <= BREAKER_RANGE_SQ) {
                        needed++;
                    } else if (onlyStanding == null
                            && com.killer560.hub.util.BlockHits.boxDistanceSq(standEye, pos) <= BREAKER_RANGE_SQ) {
                        onlyStanding = pos;
                    }
                }
            }
            // His Wizard #1 (fullclear acplan-1020): the start-node warp's sneak was still at the server when the breaker
            // fired, so its third block (4.71 from the 1.27 crouched eye, 4.36 from the standing one) was "left to the
            // linger", #2's stacked etherwarp put the sneak back down, the linger never reached it, and #2's landing stayed
            // shut - 20 s held. A node that does not sneak breaks from his standing eye: while a block is in reach only
            // standing, it waits for the server to have shift up and the pose to stand (one or two ticks), as a use node
            // waits for shift up, at most SNEAK_TIMEOUT (a ceiling that keeps him crouched never lets him stand).
            if (onlyStanding != null && !wantSneak && (player.getLastSentInput().shift() || player.isCrouching())
                    && breakerStandTicks < SNEAK_TIMEOUT) {
                if (breakerStandTicks++ == 0) {
                    LOGGER.info("[AutoRoutes] Breaker: {} is in reach only from the standing eye ({} crouched) - waiting "
                                    + "for the stand to reach the server (shift at the server {}, crouching {})",
                            onlyStanding.toShortString(), String.format(Locale.ROOT, "%.2f",
                                    Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, onlyStanding))),
                            player.getLastSentInput().shift(), player.isCrouching());
                }
                return;
            }
            if (breakerStandTicks > 0) {
                LOGGER.info("[AutoRoutes] Breaker: standing after {} tick(s) - breaking", breakerStandTicks);
                breakerStandTicks = 0;
            }
            int required = Math.min(needed, charges[1]);
            if (charges[0] < required) {
                logHold("charges:" + traceIndex(node), "node {} DUNGEON_BREAKER: {} of {} charge(s) needed ({} standing in "
                                + "reach, max {}), waited {} of {} tick(s)", nodeTag(node), charges[0], required, needed,
                        charges[1], breakerWaitTicks, BREAKER_WAIT_TIMEOUT);
                breakerWaitTicks++;
                if (!breakerWaitSaid) {
                    breakerWaitSaid = true;
                    LOGGER.info("[AutoRoutes] Breaker: {} block(s) standing in reach, {} charge(s) - waiting for {}",
                            needed, charges[0], required);
                    AutoRoutesFeature.note(ModChat.text("Waiting for "), ModChat.value(String.valueOf(required)),
                            ModChat.text(" breaker charges (have "), ModChat.value(String.valueOf(charges[0])),
                            ModChat.text(")."));
                }
                if (breakerWaitTicks > BREAKER_WAIT_TIMEOUT) {
                    RouteTrace.timeout(traceIndex(node), "DUNGEON_BREAKER", "never " + required + " charges (has "
                            + charges[0] + ")", breakerWaitTicks, player.position());
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
            LOGGER.info("[AutoRoutes] Node #{} DUNGEON_BREAKER: ready - {} charge(s) of {}, {} block(s) standing in reach of "
                            + "{} queued, slot {}", route.indexOf(node) + 1, charges[0], charges[1], needed,
                    breakerQueue.size(), slot + 1);
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
            // Multi Break off: one block a tick in obvious mode (QUOI's and RSA's own pace, BreakerNode.kt / BreakNode.java),
            // one per Interact Delay in legit mode as before.
            int delay = AutoRoutesConfig.getInstance().isLegitMode()
                    ? Math.max(1, AutoRoutesConfig.getInstance().getInteractDelayTicks()) : 1;
            if (!multi && stepTicks % delay != 0) {
                return;
            }
            Vec3 eye = player.getEyePosition();
            int sentNow = 0;
            while (!breakerQueue.isEmpty()) {
                BlockPos pos = breakerQueue.get(0);
                if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).isAir()) {
                    LOGGER.info("[AutoRoutes] Breaker block {} skipped: {}", pos.toShortString(),
                            client.level.isLoaded(pos) ? "already air" : "chunk not loaded");
                    breakerQueue.remove(0); // air needs no charge, so it never counts as "not sent"
                    continue;
                }
                if (com.killer560.hub.dungeonbreaker.BreakerGuard.protects(client.level, pos)) {
                    LOGGER.info("[AutoRoutes] Breaker block {} skipped: protected (Fairy room or a guarded secret)",
                            pos.toShortString());
                    breakerQueue.remove(0); // a Fairy room, or a secret with "Don't break secrets" on: never dug
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
                    LOGGER.info("[AutoRoutes] Breaker block {} out of range - skipped ({} to the box, reach {}; left to the "
                                    + "linger)", pos,
                            String.format(Locale.ROOT, "%.2f", Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos))),
                            String.format(Locale.ROOT, "%.2f", Math.sqrt(BREAKER_RANGE_SQ)));
                    continue;
                }
                if (com.killer560.hub.dungeonbreaker.ObsidianDig.isObsidian(client.level.getBlockState(pos))) {
                    // Obsidian takes time on the breaker (killer560, 2026-10-19), so it is not part of the burst: it
                    // is dug alone after the others, held until the server breaks it (CONFIRM, ObsidianDig). Its
                    // charge is set aside now so the burst cannot spend it.
                    breakerObsidian.add(pos);
                    breakerChargesLeft--;
                    LOGGER.info("[AutoRoutes] Breaker: obsidian {} queued - dug alone after the burst, held until the"
                            + " server breaks it", pos.toShortString());
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
                breakerSentAt.put(pos.immutable(), execTicks);
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
            // Obsidian: one dig at a time, held until the server's block update (ObsidianDig). The node moves on the
            // tick the server breaks it - no fixed wait - and the confirm timeout below only counts from the dig's end.
            if (OBSIDIAN.active()) {
                com.killer560.hub.dungeonbreaker.ObsidianDig.Result r = OBSIDIAN.tick(client, player, BREAKER_RANGE_SQ,
                        () -> true, obsidianAim(player));
                if (r == com.killer560.hub.dungeonbreaker.ObsidianDig.Result.RUNNING) {
                    logHold("obsidian:" + traceIndex(node), "node {} DUNGEON_BREAKER: obsidian dig at {} running ({} tick(s) "
                                    + "in CONFIRM)", nodeTag(node),
                            obsidianDigPos == null ? "?" : obsidianDigPos.toShortString(), stepTicks);
                    return;
                }
                LOGGER.info("[AutoRoutes] Node #{} DUNGEON_BREAKER: obsidian dig at {} ended: {} ({} tick(s))",
                        route.indexOf(node) + 1, obsidianDigPos == null ? "?" : obsidianDigPos.toShortString(), r, stepTicks);
                if (r == com.killer560.hub.dungeonbreaker.ObsidianDig.Result.ABANDONED && obsidianDigPos != null) {
                    // Left to the linger (still one of this node's blocks), which digs it again once it is in reach.
                    breakerSent.remove(obsidianDigPos);
                }
                obsidianDigPos = null;
                stepTicks = 0;
            }
            while (!breakerObsidian.isEmpty()) {
                BlockPos pos = breakerObsidian.remove(0);
                if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).isAir()
                        || !com.killer560.hub.dungeonbreaker.ObsidianDig.isObsidian(client.level.getBlockState(pos))) {
                    continue;
                }
                if (com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), pos) > BREAKER_RANGE_SQ) {
                    LOGGER.info("[AutoRoutes] Breaker: obsidian {} out of range by its turn - left to the linger",
                            pos.toShortString());
                    continue;
                }
                int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
                if (slot < 0) {
                    LOGGER.info("[AutoRoutes] Breaker: obsidian {} not dug - no Dungeon Breaker in the hotbar",
                            pos.toShortString());
                    breakerObsidian.clear();
                    break;
                }
                select(client, player, slot);
                Direction face = com.killer560.hub.util.BlockHits.surfaceOrCentre(client.level, pos,
                        player.getEyePosition()).getDirection();
                LOGGER.info("[AutoRoutes] Node #{} DUNGEON_BREAKER: obsidian dig starts at {} face {} at tick {} ({} more "
                        + "obsidian queued)", route.indexOf(node) + 1, pos.toShortString(), face, execTicks,
                        breakerObsidian.size());
                OBSIDIAN.start(client, player, pos, face, obsidianAim(player));
                player.swing(InteractionHand.MAIN_HAND);
                if (breakerSent.isEmpty()) {
                    logActed(node, " (obsidian)");
                }
                breakerSent.add(pos);
                obsidianDigPos = pos;
                return;
            }
            if (breakerSent.isEmpty()) {
                logActed(node, " (nothing in range to break)");
                finishAction();
                return;
            }
            int gone = 0;
            int awayNow = 0;
            Vec3 confirmEye = player.getEyePosition();
            for (BlockPos pos : breakerSent) {
                if (client.level.getBlockState(pos).isAir()) {
                    gone++;
                } else if (com.killer560.hub.util.BlockHits.boxDistanceSq(confirmEye, pos) > BREAKER_RANGE_SQ) {
                    awayNow++; // he moved out of its reach (a fall): the linger digs it as it comes back into reach
                }
            }
            if (gone + awayNow == breakerSent.size()) {
                LOGGER.info("[AutoRoutes] Breaker: all {} block(s) gone {} tick(s) after the last break{}", gone, stepTicks,
                        awayNow == 0 ? "" : " (" + awayNow + " more out of reach now - left to the linger)");
                finishAction();
            } else if (redigStanding(client, player, node)) {
                // A dig the server did not take (a block still standing a round trip after its dig): sent again. The
                // route never moves on past a standing block (killer560, 2026-10-19: "No Dungeon Breaker block may ever
                // be left unbroken"); RSA's BreakNode re-mines whatever still stands every tick (BreakNode.java:100).
                logHold("breakredig:" + traceIndex(node), "node {} DUNGEON_BREAKER: re-dug a block still standing a round "
                        + "trip after its dig ({} of {} gone, {} tick(s))", nodeTag(node), gone, breakerSent.size(), stepTicks);
            } else if (stepTicks > BREAKER_TIMEOUT + serverLagTicks()) {
                StringBuilder standing = new StringBuilder();
                for (BlockPos pos : breakerSent) {
                    if (!client.level.getBlockState(pos).isAir()) {
                        standing.append(standing.length() == 0 ? "" : ", ").append(pos.toShortString());
                    }
                }
                LOGGER.info("[AutoRoutes] Breaker: {} of {} sent block(s) gone after {} tick(s) (limit {} + lag {}); still "
                        + "standing: {}", gone, breakerSent.size(), stepTicks, BREAKER_TIMEOUT, serverLagTicks(), standing);
                RouteTrace.timeout(traceIndex(node), "DUNGEON_BREAKER", gone + " of " + breakerSent.size()
                        + " sent block(s) gone", stepTicks, player.position());
                // Never moves on with a block of its own still standing in reach (it used to "continue" once one had
                // broken): a stop names the block, so the route can be fixed, where carrying on left it in the way.
                stop(gone == 0 ? "dungeon breaker didn't break the blocks"
                        : "dungeon breaker left " + (breakerSent.size() - gone - awayNow) + " block(s) standing: " + standing);
            } else {
                logHold("breakconfirm:" + traceIndex(node), "node {} DUNGEON_BREAKER: {} of {} sent block(s) gone, {} of {} "
                        + "tick(s)", nodeTag(node), gone, breakerSent.size(), stepTicks, BREAKER_TIMEOUT);
            }
        }
    }

    /** {@link #execTicks} of the last server-confirmed landing and of the boom node's last send; the boom node has sent
     *  its one retry. */
    private static long lastLandingAt = Long.MIN_VALUE / 2;
    private static long boomSentAt = Long.MIN_VALUE / 2;
    private static boolean boomRetried;
    /** A boom sent within this many ticks of a landing is sent once more if it changed nothing. */
    private static final int BOOM_LANDING_TICKS = 2;

    /** When each block of the breaker node was last dug ({@link #execTicks}), for {@link #redigStanding}. */
    private static final Map<BlockPos, Long> breakerSentAt = new HashMap<>();
    /** Digs sent again because the block still stood a round trip on - read by the testkit. */
    private static int breakerRedigs;

    public static int breakerRedigs() {
        return breakerRedigs;
    }

    /**
     * The breaker node's sent blocks that still stand, in reach, a round trip after their dig: dug again (the server's
     * answer is the broken block, not a timer - a dig it never took leaves the block there, as a dig sent on a landing tick
     * did on the dedicated server in 2026-10-06's 62-argrim-play). The wait is the eaten-use round trip
     * ({@code RightClick.eatenRetryTicks}: 1.5 measured round trips + 2) plus any server stall, so a dig the server is
     * still answering is never sent twice. Obsidian is left to its own dig ({@code ObsidianDig}).
     *
     * @return true when a dig went this tick
     */
    private static boolean redigStanding(Minecraft client, LocalPlayer player, RouteNode node) {
        long wait = com.killer560.hub.util.RightClick.eatenRetryTicks() + serverLagTicks();
        Vec3 eye = player.getEyePosition();
        List<BlockPos> again = new ArrayList<>();
        for (BlockPos pos : breakerSent) {
            Long at = breakerSentAt.get(pos);
            if (at == null || execTicks - at <= wait || !client.level.isLoaded(pos)
                    || client.level.getBlockState(pos).isAir()
                    || com.killer560.hub.dungeonbreaker.BreakerGuard.protects(client.level, pos)
                    || com.killer560.hub.dungeonbreaker.ObsidianDig.isObsidian(client.level.getBlockState(pos))
                    || com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos) > BREAKER_RANGE_SQ) {
                continue;
            }
            again.add(pos);
        }
        if (again.isEmpty()) {
            return false;
        }
        int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
        int[] charges = slot < 0 ? null : breakerCharges(player.getInventory().getItem(slot));
        if (charges == null || charges[0] <= 0) {
            logHold("redigcharges:" + traceIndex(node), "node {} DUNGEON_BREAKER: {} block(s) still standing but {} - "
                    + "waiting", nodeTag(node), again.size(), slot < 0 ? "no Dungeon Breaker in the hotbar" : "no charges");
            return false;
        }
        select(client, player, slot);
        boolean multi = com.killer560.hub.dungeonextras.DungeonExtrasConfig.getInstance().isBreakerAuraMultiBreak();
        int sent = 0;
        for (BlockPos pos : again) {
            if (sent >= charges[0] || (!multi && sent > 0)) {
                break;
            }
            if (sent == 0 && !AutoRoutesConfig.getInstance().isLegitMode()) {
                turnBodyToward(player, Vec3.atCenterOf(pos));
            }
            LOGGER.info("[AutoRoutes] Breaker: {} ({}) still standing {} tick(s) after its dig (round trip {}) - digging it "
                            + "again", pos.toShortString(), client.level.getBlockState(pos).getBlock().getName().getString(),
                    execTicks - breakerSentAt.get(pos), wait);
            sendBreak(client, player, pos);
            breakerSentAt.put(pos, execTicks);
            breakerRedigs++;
            sent++;
        }
        player.swing(InteractionHand.MAIN_HAND);
        noteSecretGap("re-dug " + sent + " breaker block(s) the server had not broken");
        return true;
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
        if (!RouteRotation.isActive()) {
            LOGGER.info("[AutoRoutes] legit camera turn starts for node {}: to {} / {} from {} / {} (feint toward {})",
                    nodeTag(node), String.format(Locale.ROOT, "%.2f", yaw), String.format(Locale.ROOT, "%.2f", pitch),
                    String.format(Locale.ROOT, "%.2f", Minecraft.getInstance().player == null ? 0f
                            : Minecraft.getInstance().player.getYRot()),
                    String.format(Locale.ROOT, "%.2f", Minecraft.getInstance().player == null ? 0f
                            : Minecraft.getInstance().player.getXRot()), hasNext ? nodeTag(next) : "nothing");
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[AutoRoutes] legit camera turn re-targeted for node {}: {} / {}", nodeTag(node), yaw, pitch);
        }
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
            if (activeNode != null) {
                RouteTrace.timeout(traceIndex(activeNode), traceWhat(activeNode), "legit turn never within " + WARP_AIM_EXACT
                        + " degrees (" + RouteRotation.error() + " off) - warping anyway, may land elsewhere", stepTicks,
                        player.position());
            }
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
     * in the same tick is made with the new item: a swap costs no tick. Called outside a tick (a stop from an END
     * handler restoring his slot) it only sets the slot and vanilla sends it next tick: util/HotbarSelect.
     */
    static void select(Minecraft client, LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() == slot) {
            return;
        }
        LOGGER.info("[AutoRoutes] hotbar swap: slot {} ({}) -> {} ({}) at tick {} (node {})",
                player.getInventory().getSelectedSlot() + 1, ItemIdentity.skyblockId(player.getMainHandItem()), slot + 1,
                slot >= 0 && slot <= 8 ? ItemIdentity.skyblockId(player.getInventory().getItem(slot)) : "?", execTicks,
                nodeTag(activeNode));
        nodeSwapped = true;
        com.killer560.hub.util.HotbarSelect.select(client, player, slot);
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
        // An obsidian dig, the node's or the linger's, belongs to this breaker node: it lets go with it.
        OBSIDIAN.abandon(Minecraft.getInstance(), "breaker node over: " + why);
        obsidianDigPos = null;
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
        lingerBlocks.removeIf(p -> !client.level.isLoaded(p) || client.level.getBlockState(p).isAir()
                || com.killer560.hub.dungeonbreaker.BreakerGuard.protects(client.level, p));
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
        // A running obsidian dig is let finish on the server's answer; the linger's own limit applies between digs.
        if (++lingerTicks > LINGER_MAX_TICKS && !OBSIDIAN.active()) {
            LOGGER.info("[AutoRoutes] Breaker linger: {} tick(s) up with {} block(s) still standing", LINGER_MAX_TICKS,
                    lingerBlocks.size());
            clearBreakerLinger("timed out");
            return;
        }
        if (AutoRoutesFeature.screenBlocks(client)) {
            LOGGER.debug("[AutoRoutes] Breaker linger: a screen holds it ({} tick(s) in)", lingerTicks);
            return;
        }
        if (OBSIDIAN.active()) {
            // Held every tick until the server breaks it; nothing else is dug meanwhile (ObsidianDig).
            if (OBSIDIAN.tick(client, player, BREAKER_RANGE_SQ, () -> true, obsidianAim(player))
                    == com.killer560.hub.dungeonbreaker.ObsidianDig.Result.RUNNING) {
                return;
            }
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
            LOGGER.debug("[AutoRoutes] Breaker linger: none of {} block(s) in reach yet ({} tick(s) in)",
                    lingerBlocks.size(), lingerTicks);
            return;
        }
        int slot = ItemIdentity.findHotbarSlotById(player, BREAKER_ID);
        int[] charges = slot < 0 ? null : breakerCharges(player.getInventory().getItem(slot));
        if (charges == null || charges[0] <= 0) {
            if (lingerTicks % 20 == 0) {
                LOGGER.info("[AutoRoutes] Breaker linger: {} block(s) in reach but {} - waiting ({} tick(s) in)",
                        inReach.size(), slot < 0 ? "no Dungeon Breaker in the hotbar" : "no charges", lingerTicks);
            }
            return; // no breaker, or no charges yet: the blocks wait (Hypixel gives two back a second)
        }
        select(client, player, slot);
        int sentNow = 0;
        for (BlockPos pos : inReach) {
            if (sentNow >= charges[0]) {
                break;
            }
            if (com.killer560.hub.dungeonbreaker.ObsidianDig.isObsidian(client.level.getBlockState(pos))) {
                // Obsidian is dug alone and held until the server breaks it - never re-STARTed on a timer, which
                // restarts the server's dig. Left for a tick with nothing else sent.
                if (sentNow > 0) {
                    continue;
                }
                Direction face = com.killer560.hub.util.BlockHits.surfaceOrCentre(client.level, pos, eye).getDirection();
                LOGGER.info("[AutoRoutes] Breaker linger: obsidian dig starts at {} face {} ({} tick(s) after the node)",
                        pos.toShortString(), face, lingerTicks);
                OBSIDIAN.start(client, player, pos, face, obsidianAim(player));
                lingerSentAt.put(pos, lingerTicks);
                lingerBroken++;
                sentNow++;
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
        if (com.killer560.hub.dungeonbreaker.BreakerGuard.protects(client.level, pos)) {
            LOGGER.info("[AutoRoutes] Breaker dig refused at {}: protected (Fairy room or a guarded secret)",
                    pos.toShortString());
            return; // the one place a breaker node's dig goes out: never a Fairy room or a guarded secret
        }
        // The face the eye actually sees (Breaker Aura's clip, BlockHits). It was always UP, which no dig from below
        // a block's top can carry: GrimAC PositionBreakA flagged and cancelled every breaker block above the feet
        // (2026-10-05, 62-argrim), so those blocks never broke.
        Direction face = com.killer560.hub.util.BlockHits.surfaceOrCentre(client.level, pos, player.getEyePosition())
                .getDirection();
        LOGGER.info("[AutoRoutes] Breaker dig sent: START_DESTROY_BLOCK {} ({}) face {} at tick {}, {} charge(s) left",
                pos.toShortString(), client.level.getBlockState(pos).getBlock().getName().getString(), face, execTicks,
                breakerChargesLeft);
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
        noteSecretGap("#" + (route == null ? "?" : String.valueOf(route.indexOf(node) + 1)) + " " + node.type + " acted"
                + detail);
    }

    /** Legit: a real right click at the live (already turned) rotation - for a use-item node the block in the
     *  crosshair first like vanilla (a recorded lever/chest click replays as one), then the item; an etherwarp is
     *  QUOI's plain {@code gameMode.useItem}. Obvious: the body turned to the target (camera held, see
     *  {@link #turnBody}) and the use packet sent with that same rotation. */
    static void useHeldItem(Minecraft client, LocalPlayer player, float targetYaw, float targetPitch,
                            boolean blockInteraction) {
        // The route's own use: the send tap must not take it for his right click (onUseSent), even with his button down.
        routeUsing = true;
        try {
            sendHeldItemUse(client, player, targetYaw, targetPitch, blockInteraction);
        } finally {
            routeUsing = false;
        }
    }

    private static void sendHeldItemUse(Minecraft client, LocalPlayer player, float targetYaw, float targetPitch,
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
            LOGGER.info("[AutoRoutes] use sent (legit) at tick {}: {} at the live look {} / {} (target {} / {}), block click "
                            + "{} -> {}, item use {}", execTicks, ItemIdentity.skyblockId(player.getMainHandItem()),
                    String.format(Locale.ROOT, "%.2f", player.getYRot()), String.format(Locale.ROOT, "%.2f", player.getXRot()),
                    String.format(Locale.ROOT, "%.2f", targetYaw), String.format(Locale.ROOT, "%.2f", targetPitch),
                    blockInteraction, result, !result.consumesAction());
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
        LOGGER.info("[AutoRoutes] use sent (obvious) at tick {}: {} with body look {} / {} from {}, prediction {}", execTicks,
                ItemIdentity.skyblockId(player.getMainHandItem()), String.format(Locale.ROOT, "%.2f", yaw),
                String.format(Locale.ROOT, "%.2f", pitch), fmt(player.position()),
                client.gameMode instanceof MultiPlayerGameModeInvoker ? "sequenced" : "vanilla useItem");
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[AutoRoutes] body turn to {} / {} (from {} / {}), camera held", yaw, pitch, player.getYRot(),
                    player.getXRot());
        }
        BODY.turn(player, yaw, pitch);
    }

    /** {@link #turnBody} for Kill Mimic's aimed uses: the body turned, camera held, no packet sent. */
    static void aimBody(LocalPlayer player, float yaw, float pitch) {
        turnBody(player, yaw, pitch);
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
