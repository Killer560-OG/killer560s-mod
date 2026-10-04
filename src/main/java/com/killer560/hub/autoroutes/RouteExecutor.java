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
    private static final int SWAP_TIMEOUT = 10;
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

    private enum Step { PREP, SWAP, AIM, DO, CONFIRM, SETTLE }

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
    private static boolean swapSent;
    /** Bumped whenever a node begins or the route stops, so a sim etherwarp's late result for an action that is
     *  already over is dropped instead of stopping whatever runs now. */
    private static int actionGeneration;
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
    /** A path-less route's WALK node: the real-world yaw it holds a sprint along until the next node fires, or null. */
    private static Float walkHoldYaw;
    private static Vec3 walkHoldLastPos;
    private static int walkHoldStallTicks;
    /** A held walk that has not moved for this many ticks has hit something and lets go. */
    private static final int WALK_HOLD_STALL_TICKS = 10;

    private RouteExecutor() {
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isRunning() {
        return running;
    }

    /** Stops playback and tells the user why (chat, when chat feedback is on). Safe to call when idle. */
    /** Reasons that mean "the player took over" rather than "the route ended or the world changed". After
     *  one of these the feature must not re-arm until they have walked clear of every node (2026-09-16
     *  review: tapping W stopped a route and releasing W restarted it, because the player was still
     *  standing inside the ring the bot had just walked them through). */
    private static final java.util.Set<String> USER_STOP_REASONS =
            java.util.Set.of("you moved", "you moved the camera");

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

    public static void stop(String reason) {
        boolean wasRunning = running;
        if (wasRunning && reason != null && USER_STOP_REASONS.contains(reason)) {
            stoppedByUser = true;
        }
        running = false;
        stopReason = reason;
        actionGeneration++;
        activeNode = null;
        step = null;
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
        beginAction(startNode);
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
        if (handsLatched) {
            return driven || activeNode != null || walkHoldYaw != null;
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
        return activeNode != null;
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

    /** Call at the END of every client tick while {@link #isRunning()}; the feature has already applied its own
     *  interlocks (map open, Blood Rush, room known) before this runs. */
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
            if (!route.path().isEmpty() || activeNode != null) {
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
            if (nextNode >= ordered.size()) {
                if (walkHoldYaw == null) {
                    complete(); // a walk node at the end keeps going until it runs into something
                }
                return;
            }
            RouteNode node = ordered.get(nextNode);
            if (node.contains(RouteCoords.toReal(frame, node.relativePos()), cfg.getHeight(), player.getBoundingBox())) {
                beginAction(node);
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
            beginAction(ordered.get(nextNode));
            return;
        }
        if (cursor >= path.size() - 1) {
            if (nextNode >= ordered.size()) {
                complete();
            } else {
                // Nodes anchored past the end of the path (hand-edited file) - run them where we stand.
                clearMovement();
                beginAction(ordered.get(nextNode));
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

    private static void beginAction(RouteNode node) {
        actionGeneration++;
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
        swapSent = false;
        actionOrigin = Minecraft.getInstance().player.position();
        // Any node firing ends a held walk ("keep me walking until I hit a different node", AP3's rule), and the
        // node owns the input from here: keys he is already holding are overridden, not read as a takeover.
        endWalkHold();
        clearMovement();
        handsLatched = true;
        // The await modifier (if this node has one) runs FIRST, as its own PREP/CONFIRM cycle through
        // tickAwait - see tickAction. Nothing else about the node starts until that gate opens.
        awaitPhaseDone = !node.awaitEnabled;
        breakerQueue = new ArrayList<>();
        breakerSent.clear();
        boomTarget = null;
        boomBefore.clear();
    }

    private static void finishAction() {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        // Release the camera as soon as the node is done. Without this a QUOI-style path-less route (nodes
        // only, no recorded walk) kept pulling the view back to the finished node's yaw every frame while
        // the player tried to walk to the next one themselves (2026-09-16 review).
        RouteRotation.clear();
        activeNode = null;
        step = null;
        nextNode++;
        settleTicks = cfg.getInteractDelayTicks();
        bestTargetDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
    }

    private static void tickAction(Minecraft client, LocalPlayer player) {
        RouteNode node = activeNode;
        stepTicks++;
        if (!awaitPhaseDone) {
            // The node's own action (etherwarp, use, boom, ...) doesn't start until this clears - see
            // tickAwait, which is this gate for any node now that AWAIT isn't its own type any more.
            tickAwait(client, player, node);
            return;
        }
        switch (node.type) {
            case START -> finishAction();
            case WALK -> {
                // A recorded route's walking is the path's job, so there a walk node is just a marker. On a
                // path-less (/ar add) route it is the sprint.
                if (route.path().isEmpty()) {
                    walkHoldYaw = RouteCoords.toRealYaw(frame, node.yaw);
                    walkHoldLastPos = null;
                    walkHoldStallTicks = 0;
                }
                finishAction();
            }
            case UNSNEAK -> {
                unsneakOverride = true;
                forceSneak = false;
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
            return;
        }
        // !isActive() covers obvious mode, where aimAt() snaps and clears the controller instead of
        // running an approach - settled() is false forever in that case, so the node used to sit out the
        // whole AIM_TIMEOUT before moving on (2026-09-16 review).
        if (!RouteRotation.isActive() || RouteRotation.settled(1.5f) || stepTicks > AIM_TIMEOUT) {
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

    private static void tickEtherwarp(Minecraft client, LocalPlayer player, RouteNode node) {
        switch (step) {
            case PREP -> {
                forceSneak = true;
                unsneakOverride = false;
                wantSneak = true;
                // QUOI ClearExecutor: don't warp until the SERVER has seen the sneak, or the warp is a plain AOTV hop.
                if (player.getLastSentInput().shift()) {
                    LOGGER.info("[AutoRoutes] Etherwarp: server has the sneak after {} tick(s)", stepTicks);
                    step = Step.SWAP;
                    stepTicks = 0;
                } else if (stepTicks > SNEAK_TIMEOUT) {
                    LOGGER.info("[AutoRoutes] Etherwarp: sneak never reached the server (client shift={}, input mixin={})",
                            player.isShiftKeyDown(), mixinApplied);
                    stop("couldn't start sneaking for the etherwarp");
                }
            }
            case SWAP -> {
                wantSneak = true;
                int slot = ItemIdentity.findEtherwarpSlot(player);
                if (slot < 0) {
                    LOGGER.info("[AutoRoutes] Etherwarp: no hotbar item with ethermerge / ETHERWARP_CONDUIT");
                    stop("no etherwarp item in the hotbar");
                    return;
                }
                if (ensureSelected(player, slot)) {
                    LOGGER.info("[AutoRoutes] Etherwarp: slot {} selected ({})", slot,
                            ItemIdentity.skyblockId(player.getInventory().getItem(slot)));
                    step = Step.AIM;
                    stepTicks = 0;
                    aimAt(node);
                } else if (stepTicks > SWAP_TIMEOUT) {
                    stop("couldn't switch to the etherwarp item");
                }
            }
            case AIM -> {
                wantSneak = true;
                if (aimReady()) {
                    step = Step.DO;
                    stepTicks = 0;
                }
            }
            case DO -> {
                wantSneak = true;
                actionOrigin = player.position();
                teleportPacketSeen = false;
                useHeldItem(client, player, node, false);
                step = Step.CONFIRM;
                stepTicks = 0;
                cameraGraceTicks = LANDING_TIMEOUT + 5;
            }
            case CONFIRM -> {
                wantSneak = true;
                if (landed(player, node)) {
                    LOGGER.info("[AutoRoutes] Etherwarp: landed at {} after {} tick(s)", fmt(player.position()), stepTicks);
                    forceSneak = false;
                    RouteRotation.rebase();
                    cameraGraceTicks = 3;
                    rejoinPathAfterTeleport(player, node);
                    finishAction();
                } else if (stepTicks > LANDING_TIMEOUT) {
                    LOGGER.info("[AutoRoutes] Etherwarp: no landing after {} ticks - player {} (moved {} from {}), "
                            + "recorded landing {}", stepTicks, fmt(player.position()),
                            String.format(Locale.US, "%.2f", actionOrigin == null ? 0.0 : player.position().distanceTo(actionOrigin)),
                            fmt(actionOrigin),
                            node.hasLanding ? fmt(RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ)) : "none");
                    stop("etherwarp didn't land where it was recorded");
                }
            }
            default -> finishAction();
        }
    }

    private static void tickUseItem(Minecraft client, LocalPlayer player, RouteNode node) {
        switch (step) {
            case PREP -> {
                if (node.item == null) {
                    stop("use-item node has no item");
                    return;
                }
                step = Step.SWAP;
                stepTicks = 0;
            }
            case SWAP -> {
                int slot = ItemIdentity.findHotbarSlot(player, node.item);
                if (slot < 0) {
                    stop(node.item + " is not in the hotbar");
                    return;
                }
                if (ensureSelected(player, slot)) {
                    step = Step.AIM;
                    stepTicks = 0;
                    aimAt(node);
                } else if (stepTicks > SWAP_TIMEOUT) {
                    stop("couldn't switch to " + node.item);
                }
            }
            case AIM -> {
                if (aimReady()) {
                    step = Step.DO;
                    stepTicks = 0;
                }
            }
            case DO -> {
                actionOrigin = player.position();
                teleportPacketSeen = false;
                useHeldItem(client, player, node, true);
                step = Step.CONFIRM;
                stepTicks = 0;
                if (node.hasLanding) {
                    cameraGraceTicks = LANDING_TIMEOUT + 5;
                }
            }
            case CONFIRM -> {
                if (!node.hasLanding) {
                    // Nothing generic to wait for from the server for an arbitrary item: the use call itself, with
                    // the right item confirmed in hand, is the confirmation; the interact delay follows.
                    finishAction();
                } else if (landed(player, node)) {
                    RouteRotation.rebase();
                    cameraGraceTicks = 3;
                    rejoinPathAfterTeleport(player, node);
                    finishAction();
                } else if (stepTicks > LANDING_TIMEOUT) {
                    stop(node.item + " didn't teleport where it was recorded");
                }
            }
            default -> finishAction();
        }
    }

    private static void tickBoom(Minecraft client, LocalPlayer player, RouteNode node) {
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
                    step = Step.AIM;
                    stepTicks = 0;
                    aimAt(node);
                } else if (stepTicks > SWAP_TIMEOUT) {
                    stop("couldn't switch to the Superboom");
                }
            }
            case AIM -> {
                if (aimReady()) {
                    step = Step.DO;
                    stepTicks = 0;
                }
            }
            case DO -> {
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
                if (com.killer560.hub.roomsim.SimState.canAct(client)) {
                    // The sim has no server-side Superboom: raw START/ABORT packets (and a client
                    // startDestroyBlock aimed by a look the camera doesn't have) blew nothing up in here. The sim's
                    // own entry point takes the block and face directly, in both modes.
                    boolean sent = com.killer560.hub.roomsim.SimItems.superboomAt(client, boomTarget, hit.getDirection());
                    LOGGER.info("[AutoRoutes] Superboom {} (sim) at {} face {}", sent ? "detonated" : "refused",
                            boomTarget.toShortString(), hit.getDirection());
                } else if (AutoRoutesConfig.getInstance().isLegitMode()) {
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
                    finishAction();
                } else if (stepTicks > BOOM_TIMEOUT) {
                    stop("superboom didn't break anything");
                }
            }
            default -> finishAction();
        }
    }

    private static void tickBreaker(Minecraft client, LocalPlayer player, RouteNode node) {
        switch (step) {
            case PREP -> {
                if (node.breakerBlocks.isEmpty()) {
                    finishAction(); // nothing to break (a fresh node before /ar edit db)
                    return;
                }
                breakerQueue = new ArrayList<>();
                for (BlockPos rel : node.breakerBlocks) {
                    breakerQueue.add(RouteCoords.toRealBlock(frame, rel));
                }
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
                    // In the sim the charges live in SimBreakerState; the item's lore is a static tooltip.
                    int charges = com.killer560.hub.roomsim.SimState.canAct(client)
                            ? com.killer560.hub.roomsim.SimBreakerState.charges()
                            : breakerCharges(player.getMainHandItem());
                    LOGGER.info("[AutoRoutes] Breaker: {} block(s) queued, {} charge(s)", breakerQueue.size(), charges);
                    if (charges <= 0) {
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
                // QUOI DungeonBreakerAction: one START_DESTROY_BLOCK per block, interact-delay ticks apart, no
                // rotation (block breaking is range-checked, not look-checked). Air / unloaded / far blocks skip.
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
                    if (com.killer560.hub.roomsim.SimState.canAct(client)) {
                        // The sim has no server-side Dungeonbreaker - a raw START_DESTROY_BLOCK broke nothing here.
                        if (!com.killer560.hub.roomsim.SimItems.dungeonBreakAt(client, pos)) {
                            LOGGER.info("[AutoRoutes] Breaker block {} refused by the sim (puzzle room, secret, "
                                    + "floor not started or no charges) - skipped", pos.toShortString());
                            continue;
                        }
                        LOGGER.info("[AutoRoutes] Breaker block {} sent (sim)", pos.toShortString());
                    } else {
                        player.connection.send(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
                    }
                    player.swing(InteractionHand.MAIN_HAND);
                    breakerSent.add(pos);
                    return; // next block on the next delay tick
                }
                step = Step.CONFIRM;
                stepTicks = 0;
            }
            case CONFIRM -> {
                if (breakerSent.isEmpty()) {
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
            default -> finishAction();
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
        RouteNode next = nextNode + 1 < ordered.size() ? ordered.get(nextNode + 1) : null;
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

    /** Selects the hotbar slot (client + {@code ServerboundSetCarriedItemPacket}) and reports true once it is the
     *  selected slot - at most one swap per action, the tick after it the server has seen it. */
    private static boolean ensureSelected(LocalPlayer player, int slot) {
        if (player.getInventory().getSelectedSlot() == slot) {
            return swapSent ? stepTicks >= 2 : true;
        }
        if (!swapSent) {
            player.getInventory().setSelectedSlot(slot);
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            swapSent = true;
            stepTicks = 0;
        }
        return false;
    }

    /** Legit: a real right click at the live (already turned) rotation - for a use-item node the block in the
     *  crosshair first like vanilla (a recorded lever/chest click replays as one), then the item; an etherwarp is
     *  QUOI's plain {@code gameMode.useItem}. Obvious: the rotated use packet without touching the camera
     *  ({@code ClearExecutor.doInteract}). */
    private static void useHeldItem(Minecraft client, LocalPlayer player, RouteNode node, boolean blockInteraction) {
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        if (com.killer560.hub.roomsim.SimState.canAct(client)) {
            useHeldItemInSim(client, player, node, blockInteraction, legit);
            return;
        }
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
     * THE SIM HAS NO SERVER-SIDE ETHERWARP, so the raw use packet above teleports nobody there.
     * <p>
     * The obvious-mode branch hands a {@code ServerboundUseItemPacket} to the connection. On Hypixel that packet IS
     * the ability. The sim's abilities live in {@code SimAbilities} behind Fabric's {@code UseItemCallback}, which
     * fires on the CLIENT's {@code gameMode.useItem} and refuses the integrated server's copy of the player - so the
     * packet was sent, accepted and did nothing, and every etherwarp node sat in CONFIRM until "etherwarp didn't land
     * where it was recorded" (his 2026-10-04 13:59 log, twice). {@code ClearExecutor.doInteract} hit exactly this on
     * 2026-10-01 and was fixed; this copy of the same send was not.
     * <p>
     * An etherwarp goes through {@code SimAbilities.etherwarpAlong}, which resolves the hop from the server's copy of
     * him along the given yaw/pitch exactly as Hypixel does - the node's recorded look in obvious mode (no camera
     * turn, same as on Hypixel), the live camera in legit mode. Anything else (or an etherwarp the sim refuses, e.g.
     * not sneaking yet) takes the client-side {@code gameMode.useItem} with the rotation set for the call, which is
     * what {@code ClearExecutor} and {@code AutoPuzzleUtil.useItemRotated} do in here.
     */
    private static void useHeldItemInSim(Minecraft client, LocalPlayer player, RouteNode node, boolean blockInteraction,
                                         boolean legit) {
        float targetYaw = legit ? player.getYRot() : RouteCoords.toRealYaw(frame, node.yaw);
        float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitch = Mth.clamp(legit ? player.getXRot() : node.pitch, -90f, 90f);
        if (node.type == RouteNode.Type.ETHERWARP) {
            BlockPos expected = null;
            if (node.hasLanding) {
                Vec3 l = RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ);
                expected = BlockPos.containing(l.x, l.y - 0.5, l.z);
            }
            int gen = actionGeneration;
            BlockPos want = expected;
            if (com.killer560.hub.roomsim.SimAbilities.etherwarpAlong(client, yaw, pitch, expected,
                    result -> onSimEtherwarp(gen, result, want))) {
                LOGGER.info("[AutoRoutes] Etherwarp sent (sim, server-side hop): yaw {} pitch {} expecting block {}",
                        String.format(Locale.US, "%.2f", yaw), String.format(Locale.US, "%.2f", pitch),
                        expected == null ? "any" : expected.toShortString());
                player.swing(InteractionHand.MAIN_HAND);
                return;
            }
            LOGGER.info("[AutoRoutes] Etherwarp: sim refused the hop (held {}, client shift={}) - plain use instead",
                    ItemIdentity.skyblockId(player.getMainHandItem()), player.isShiftKeyDown());
        }
        InteractionResult result = InteractionResult.PASS;
        if (legit && blockInteraction) {
            HitResult hit = player.pick(4.5, 1f, false);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
            }
        }
        if (!result.consumesAction()) {
            float oldYaw = player.getYRot();
            float oldPitch = player.getXRot();
            player.setYRot(yaw);
            player.setXRot(pitch);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        LOGGER.info("[AutoRoutes] {} used (sim, client-side use): yaw {} pitch {}", node.type,
                String.format(Locale.US, "%.2f", yaw), String.format(Locale.US, "%.2f", pitch));
        player.swing(InteractionHand.MAIN_HAND);
    }

    /** {@code SimAbilities.etherwarpAlong}'s verdict, on the client thread. A hop with no target stops the route at
     *  once instead of sitting out the three-second landing timeout. */
    private static void onSimEtherwarp(int gen, com.killer560.hub.roomsim.SimAbilities.HopResult result, BlockPos expected) {
        if (gen != actionGeneration || !running) {
            return;
        }
        switch (result) {
            case LANDED -> LOGGER.info("[AutoRoutes] Sim etherwarp: landed on the expected block");
            case LANDED_ELSEWHERE -> LOGGER.info("[AutoRoutes] Sim etherwarp: landed, but not on {} - the landing "
                    + "check decides", expected == null ? "?" : expected.toShortString());
            case NO_TARGET -> {
                LOGGER.info("[AutoRoutes] Sim etherwarp: no etherwarpable block along the node's look");
                stop("etherwarp found no target");
            }
        }
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
