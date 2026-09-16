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
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * a superboom when the block it hit changed, an await when its condition is met. Anything that cannot be confirmed
 * within a generous timeout, any drift off the recorded path, any user input, and any screen opening
 * <b>stops the route with a chat message</b> - "a stuck bot in a real run is worse than a stopped one".
 */
public final class RouteExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoroutes");
    private static final String CHAT = "Auto Routes";

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
    private static final double BREAKER_RANGE_SQ = 30.0;
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
    private static boolean awaitSkip;

    // ---- input ----
    private static boolean mixinApplied;
    private static boolean fallbackKeysHeld;
    private static boolean wantForward;
    private static boolean wantBackward;
    private static boolean wantLeft;
    private static boolean wantRight;
    private static boolean wantJump;
    private static boolean wantSneak;
    private static boolean wantSprint;

    private RouteExecutor() {
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isRunning() {
        return running;
    }

    /** Stops playback and tells the user why (chat, when chat feedback is on). Safe to call when idle. */
    public static void stop(String reason) {
        boolean wasRunning = running;
        running = false;
        stopReason = reason;
        activeNode = null;
        step = null;
        forceSneak = false;
        unsneakOverride = false;
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
        RouteRotation.clear();
        running = true;
        LOGGER.info("[AutoRoutes] Started \"{}\" from node {} ({}) at sample {} of {}", r.roomName(),
                r.indexOf(startNode), startNode.type, cursor, r.path().size());
        if (AutoRoutesConfig.getInstance().isChatFeedback()) {
            AutoRoutesFeature.chat(com.killer560.hub.util.ModChat.good("Started"),
                    com.killer560.hub.util.ModChat.dim(" - " + r.roomName()));
        }
        return true;
    }

    private static void complete() {
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

    public static void onMixinApplied() {
        mixinApplied = true;
    }

    /** From the input mixin: the player pressed a movement key themselves. A route with no recorded path is
     *  QUOI-style (the player walks between nodes), so only a driven route stops on it. */
    public static void onUserMovementInput() {
        if (running && route != null && !route.path().isEmpty()) {
            stop("you moved");
        }
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
        if (client.screen != null) {
            stop("a screen opened");
            return;
        }
        if (cameraGraceTicks > 0) {
            cameraGraceTicks--;
        } else if (RouteRotation.userMovedCamera(player)) {
            stop("you moved the camera");
            return;
        }
        boolean attack = client.options.keyAttack.isDown();
        boolean driven = !route.path().isEmpty();
        if (attack && activeNode != null && activeNode.type == RouteNode.Type.AWAIT) {
            awaitSkip = true; // QUOI: a click while waiting skips the await
        } else if ((attack || client.options.keyUse.isDown()) && (driven || activeNode != null)) {
            // A driven route is the bot's alone; a QUOI-style path-less route only minds clicks mid-action.
            stop("you clicked");
            return;
        }
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
            if (nextNode >= ordered.size()) {
                complete();
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
        wantJump = player.onGround() && (target.jump() || at.jump() || climb || player.horizontalCollision)
                || (player.isInWater() && climb);
        wantSneak = forceSneak || (!unsneakOverride && (at.sneak() || target.sneak()));
        wantSprint = !wantSneak && wantForward && (at.sprint() || target.sprint()) && !player.isInWater();
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
        activeNode = node;
        step = Step.PREP;
        stepTicks = 0;
        swapSent = false;
        actionOrigin = Minecraft.getInstance().player.position();
        awaitSkip = false;
        breakerQueue = new ArrayList<>();
        breakerSent.clear();
        boomTarget = null;
        boomBefore.clear();
        LOGGER.info("[AutoRoutes] Node {} ({}) at sample {}", route.indexOf(node), node.type, cursor);
    }

    private static void finishAction() {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
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
        switch (node.type) {
            case START, WALK -> finishAction();
            case UNSNEAK -> {
                unsneakOverride = true;
                forceSneak = false;
                finishAction();
            }
            case COMMAND -> {
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
            case AWAIT -> tickAwait(client, player, node);
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
        if (RouteRotation.settled(1.5f) || stepTicks > AIM_TIMEOUT) {
            finishAction();
        }
    }

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
        if (done || awaitSkip) {
            finishAction();
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
                    step = Step.SWAP;
                    stepTicks = 0;
                } else if (stepTicks > SNEAK_TIMEOUT) {
                    stop("couldn't start sneaking for the etherwarp");
                }
            }
            case SWAP -> {
                wantSneak = true;
                int slot = ItemIdentity.findEtherwarpSlot(player);
                if (slot < 0) {
                    stop("no etherwarp item in the hotbar");
                    return;
                }
                if (ensureSelected(player, slot)) {
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
                useHeldItem(client, player, node, false);
                step = Step.CONFIRM;
                stepTicks = 0;
                cameraGraceTicks = LANDING_TIMEOUT + 5;
            }
            case CONFIRM -> {
                wantSneak = true;
                if (landed(player, node)) {
                    forceSneak = false;
                    RouteRotation.rebase();
                    cameraGraceTicks = 3;
                    rejoinPathAfterTeleport(player, node);
                    finishAction();
                } else if (stepTicks > LANDING_TIMEOUT) {
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
                    if (pos.distToCenterSqr(eye.x, eye.y, eye.z) > BREAKER_RANGE_SQ) {
                        LOGGER.info("[AutoRoutes] Breaker block {} out of range - skipped", pos);
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
        if (AutoRoutesConfig.getInstance().isLegitMode()) {
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

    /** Real confirmation of a teleport: at the recorded landing, or (no landing recorded) clearly moved. */
    private static boolean landed(LocalPlayer player, RouteNode node) {
        Vec3 pos = player.position();
        if (node.hasLanding) {
            Vec3 landing = RouteCoords.toReal(frame, node.landingX, node.landingY, node.landingZ);
            return pos.distanceTo(landing) <= LANDING_TOLERANCE;
        }
        return actionOrigin != null && pos.distanceTo(actionOrigin) > 3.0;
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
