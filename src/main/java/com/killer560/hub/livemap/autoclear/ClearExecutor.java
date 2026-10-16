package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCorrections;
import com.killer560.hub.util.ServerLag;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Port of QUOI's {@code ClearExecutor} (+ the one-swap-per-tick guard of {@code SwapManager} and
 * {@code PlayerUtils.useItem(yaw, pitch)}): runs a list of {@link ClearNode}s.
 * <ul>
 * <li>Client tick start: send the queued rotated item use, tick the delays, then execute whichever node the predicted
 * position sits on (one hop per tick, positions predicted from the raycast, not waited on).
 * <li>While an etherwarp node is current or next, sneak is forced (QUOI {@code KeyEvent.Input} shift = true) through
 * {@code livemap.mixin.LiveMapKeyboardInputMixin}.
 * <li>After the last node: wait for the server's position packet ({@code LiveMapPacketListenerMixin}), then ~8 more
 * ticks, then run the completion callback (e.g. "Face door on arrival").
 * </ul>
 * The item use sends the target yaw as an equivalent of the player's current running yaw (never wrapped to 0-360),
 * see the Rotation 360 rule.
 */
public final class ClearExecutor {

    static final String CHAT = "Interactive Map";
    private static final Logger LOGGER = ModLog.get("killer560smod-interactivemap");
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-etherplanner");
        t.setDaemon(true);
        EtherwarpPathfinder.bindPlannerThread(t);
        return t;
    });

    /** Plans other features queued or running on {@link #PLANNER} - the warm-up gives way to them as to a click. */
    private static final java.util.concurrent.atomic.AtomicInteger externalPlans =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Runs {@code task} on the Interactive Map's planner thread, the only thread the floor graph may be used from
     * ({@link EtherwarpPathfinder#findDungeonPath} and the warm-up share one graph, which is not thread-safe). The
     * warm-up yields to it at its next slice, as it does to a map click.
     */
    public static void onPlanner(Runnable task) {
        externalPlans.incrementAndGet();
        try {
            PLANNER.submit(() -> {
                try {
                    task.run();
                } finally {
                    externalPlans.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            externalPlans.decrementAndGet();
            throw e;
        }
    }

    /**
     * Runs {@code task} on the planner thread as background work: it does not count as a plan somebody is waiting for,
     * so the warm-up keeps its turns (TripCache's precomputing, a few milliseconds a task, interleaves with it). The task
     * itself must give way to {@link #tripSearchWaiting}.
     */
    static void onPlannerBackground(Runnable task) {
        PLANNER.submit(task);
    }

    private static List<ClearNode> nodes = null;
    private static int syncDelay = 0;
    private static int compDelay = 0;
    private static int globalDelay = 0;
    private static int hypeDelay = 0;
    private static boolean active = false;
    private static float[] pendingInteract = null;
    private static double[] position = null;
    private static ClearNode activeNode = null;
    private static Runnable onComplete = null;
    private static Runnable pendingCompletion = null;
    private static volatile boolean positionPacketSeen = false;
    private static int syncWaitTicks = 0;
    private static volatile boolean pathPending = false;
    private static volatile boolean lastPathFailed = false;
    private static int generation = 0;
    private static boolean hasSwappedThisTick = false;
    private static Object lastLevel = null;
    private static volatile boolean sneakMixinApplied = false;
    private static boolean forcedSneakKey = false;
    // 2026-09-15: set while another feature (Pathfinding's Auto Fairy Souls) owns the queue, so its hops are not
    // cancelled just because the Interactive Map's own "Teleport Pathing"/"Auto Blood Rush" toggles are off.
    // Nothing else changes: when it is false this class behaves exactly as before.
    private static volatile boolean externalOwner = false;

    // ---- keeping a path honest (killer560, 2026-10-04: "just sometimes it is getting stuck and breaking") ----
    // The queue used to trust its own prediction completely: a hop was "done" the tick its use packet went out, the
    // next one was cast from where the prediction said he now stood, and nothing compared that with where the
    // server actually put him. So a hop the server refused sent every later hop from the wrong spot (his log: nine
    // "no etherwarp target there" in one second), and a path whose first spot he was no longer on when it arrived
    // sat in the queue for ever - isBusy() true, Auto Routes inert, nothing moving, nothing said. Now every landing
    // is checked against the server's position packets, nothing waits more than a second without progress, and a
    // path that goes wrong is planned again from where he really is (or stops, saying why).

    /** Where each hop issued on this path should land, in order. */
    private static final List<Vec3> issued = new ArrayList<>();
    /** How many of {@link #issued} the server has put him on. */
    private static int confirmed = 0;
    /** The spot the path started from, or the last confirmed landing - a packet leaving him there is ignored. */
    private static Vec3 lastGood = null;
    private static volatile int positionPackets = 0;
    private static int positionPacketsSeen = 0;
    private static int ticksSinceProgress = 0;
    /** {@link ServerLag#mark()} when the queue last made progress (and when the arrival sync began): a server stall
     *  since then extends {@link #STALL_TICKS} (and the sync fallback) by its length, so a lagging server is never
     *  read as "warp N never landed" (killer560, 2026-10-15). */
    private static long progressLag = 0L;
    private static long syncLag = 0L;
    private static long strayLag = 0L;
    /** Why the queue is not moving this tick, for the message when it gives up. */
    private static String waitReason = null;
    /** The goal of the Interactive Map path being run, so a path that went wrong can be planned again. */
    private static BlockPos goalTo = null;
    private static int goalTile = -1;
    private static Runnable goalComplete = null;
    private static int replans = 0;
    private static boolean replanPending = false;
    private static int replanWaitTicks = 0;
    private static int pathPendingTicks = 0;
    /** The generation of the search that set {@link #pathPending}. */
    private static int pendingGen = -1;
    /** Bumped each time a path ends with him where it planned to put him. */
    private static int arrivalSeq = 0;
    private static long arrivalMs = 0L;
    private static Vec3 arrivalPos = null;
    private static long lastAlreadyThereMs = 0L;
    private static BlockPos lastAlreadyThereGoal = null;
    /** Where he stood before the first server position packet since the last tick looked (null: none since). */
    private static Vec3 moveFrom = null;
    /** The generation of a search the server moved him during: its plan starts where he no longer is. */
    private static int searchMovedGen = Integer.MIN_VALUE;
    /** Position packets seen, and where he stood, when the latest search started: older packets are not its business
     *  (an Auto Clear hop landing drained in the same frame as the trip it led to). */
    private static int planPackets = 0;
    private static Vec3 planFrom = null;
    /** Warps a dropped queue sent that the server had not answered yet: their landings may still arrive. */
    private static int strayWarps = 0;
    private static long strayUntilMs = 0L;
    /** A position packet moving him less than this left him where he was (Auto Clear's rule too). */
    private static final double CORRECTION_MIN_MOVE = 0.3;

    /** Hops in flight the server has not answered yet before the queue waits for it. */
    private static final int MAX_LEAD = 6;
    /** Ticks without progress before a path is given up on (and planned again). */
    private static final int STALL_TICKS = 20;
    /** Ticks he may stand off the path's first spot before it is planned again from where he is. */
    private static final int OFF_START_TICKS = 5;
    private static final int MAX_REPLANS = 2;
    private static final int REPLAN_WAIT_TICKS = 60;
    private static final int PATH_SEARCH_LIMIT_TICKS = 200;
    /** A landing counts as reached within this horizontally, and this vertically (he starts falling at once). */
    private static final double LAND_XZ = 0.6;
    private static final double LAND_Y = 1.3;

    private ClearExecutor() {
    }

    /** The client tick is counted first of all START handlers, so every feature reads the same tick number in it. */
    private static final net.minecraft.resources.Identifier TICK_COUNT_PHASE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "client_tick_count");

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(TICK_COUNT_PHASE, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        ClientTickEvents.START_CLIENT_TICK.register(TICK_COUNT_PHASE, client -> clientTicks++);
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ClearExecutor.onTickStart", ClearExecutor::onTickStart));
        // START: this sends the interactions at the end of a walk leg. Named onTickEnd from when it ran at
        // the end of the tick; the name is left alone so every reference to it keeps working.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ClearExecutor.onTickEnd", ClearExecutor::onTickEnd));
        TripCache.register();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> {
            List<ClearNode> current = nodes;
            if (current == null || current.isEmpty()) {
                return;
            }
            for (ClearNode node : new ArrayList<>(current)) {
                node.render(ctx);
            }
        });
    }

    // ------------------------------------------------------------------------------------------- public API

    public static boolean isActive() {
        return active;
    }

    /** True while a path is being searched, queued, executed or waiting for its completion sync. */
    public static boolean isBusy() {
        return pathPending || (nodes != null && !nodes.isEmpty()) || syncDelay != 0 || pendingCompletion != null
                || replanPending;
    }

    /**
     * Bumped every time a path finishes with him standing where it planned to put him (after the arrival sync).
     * Lets a feature react to "the Interactive Map just brought me here" - Auto Teleport Maze walks onto the start
     * pad on it.
     */
    public static int arrivalSeq() {
        return arrivalSeq;
    }

    /** When {@link #arrivalSeq} last moved, in {@code System.currentTimeMillis()}. */
    public static long arrivalMs() {
        return arrivalMs;
    }

    /** Where the path behind {@link #arrivalSeq} put him, or null. */
    public static Vec3 arrivalPos() {
        return arrivalPos;
    }

    public static boolean lastPathFailed() {
        return lastPathFailed;
    }

    /** Server position packets that put him somewhere a running path did not plan (a correction), ever. Only goes
     *  up, so a caller compares it with the value it last saw - Auto Secret reports each one. */
    private static int serverCorrections = 0;

    public static int serverCorrections() {
        return serverCorrections;
    }

    /** Every server position packet seen, ever (only goes up): lets a feature notice one while no path runs. */
    public static int positionPackets() {
        return positionPackets;
    }

    public static EtherwarpPathfinder.PathConfig pathConfig() {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        return new EtherwarpPathfinder.PathConfig(cfg.getYawStep(), cfg.getPitchStep(), cfg.getHWeight(),
                cfg.getTimeoutMs());
    }

    /** killer560: "make it so ... it shows a small circle at each etherwarp spot ... and a line from one spot
     *  to another" - the world positions of the currently queued hops, in order, for
     *  {@link com.killer560.hub.livemap.MapPainter} to draw on the Interactive Map. Snapshotted (not the live
     *  list) since it is read from the render thread while {@link #nodes} can be mutated by the tick handler. */
    public static List<Vec3> plannedHopPositions() {
        List<ClearNode> current = nodes;
        if (current == null || current.isEmpty()) {
            return List.of();
        }
        List<Vec3> out = new ArrayList<>(current.size());
        for (ClearNode node : current) {
            out.add(node.pos);
        }
        return out;
    }

    /**
     * The planner's hop range: the held etherwarp item's own reach (57 plus one per Transmission Tuner, read the
     * way {@link com.killer560.hub.pathfinding.EtherwarpHopper#range} reads it) less one block of margin, and never
     * more than 60. It used to be 60 for everyone, which is 61 - 1 for a fully tuned item; with fewer tuners a
     * 58-60 block hop was planned that Hypixel refuses, and the floor-wide planner reaches for long hops far more
     * often than the room-by-room one did. The sim's server applies the same 57 + tuners rule.
     */
    public static double hopRange() {
        double item = com.killer560.hub.pathfinding.EtherwarpHopper.range();
        return item > 0 ? Math.min(60.0, item - 1.0) : 56.0;
    }

    /** QUOI {@code etherPath}: search on a background thread, then run the smoothed path. */
    public static void etherPath(BlockPos to, Runnable complete) {
        goalExact = false;
        goalFastArrival = false;
        etherPath(to, -1, complete);
    }

    /**
     * {@link #etherPath} for a caller that needs THAT block and gains nothing from the planner's "near" landing (up to
     * five blocks off): when the floor graph can only land near it, the room-by-room planner is asked for the block
     * itself before the near landing is settled for. Auto Blaze's walk to a block within aura reach of a ledge chest,
     * where a near landing is out of reach and costs a second plan (2026-10-06).
     */
    public static void etherPathExact(BlockPos to, Runnable complete) {
        goalExact = true;
        goalFastArrival = false;
        etherPath(to, -1, complete);
    }

    /**
     * {@link #etherPathExact} onto a route's start node (Auto Routes' {@code warpToStartNode}): the trip is over - its
     * completion runs and {@link #isBusy} goes false - on the tick the server's position packet confirms the last
     * landing ({@link #finishArrivalEarly}), not after the ~9-tick arrival settle. killer560, 2026-10-15: routes must go
     * as fast as possible; that settle was the route waiting on the map for nothing once the landing was the server's.
     */
    public static void etherPathArrive(BlockPos to, Runnable complete) {
        goalExact = true;
        goalFastArrival = true;
        etherPath(to, -1, complete);
    }

    /** Set by {@link #etherPathExact}, read by every {@link #plan} for that goal (replans too). */
    private static boolean goalExact = false;
    /** Set by {@link #etherPathArrive}: this goal ends on its confirmed landing, with no arrival settle. */
    private static boolean goalFastArrival = false;

    /**
     * A map click on a tile: fewest warps to ANY landing in that tile at its floor height (see
     * {@link EtherwarpPathfinder#findDungeonPathToTile}); {@code to} is the tile's own block, used if none of
     * them can be reached.
     */
    public static void etherPathToTile(BlockPos to, int tileIdx, Runnable complete) {
        goalExact = false;
        goalFastArrival = false;
        etherPath(to, tileIdx, complete);
    }

    private static void etherPath(BlockPos to, int tileIdx, Runnable complete) {
        if (pathPending || replanPending) {
            return;
        }
        replans = 0;
        heldReplans = 0;
        plan(to, tileIdx, complete);
    }

    /** One search towards a goal: {@link #etherPath} for a new goal, {@link #tickReplan} for the same one again. */
    private static void plan(BlockPos to, int tileIdx, Runnable complete) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        if (BlockPos.containing(player.position()).equals(to)) {
            if (complete != null) {
                complete.run();
            }
            alreadyThere(to);
            return;
        }
        if (pathPending) {
            return;
        }
        if (adoptPreplan(to, tileIdx, complete, player)) {
            return;
        }
        if (adoptCached(to, tileIdx, complete, player)) {
            return;
        }
        Inflight f = inflight;
        if (f != null && awaiting == null && (tileIdx >= 0 ? f.tileIdx() == tileIdx
                : f.tileIdx() < 0 && f.to().equals(to) && f.exact() == goalExact)
                && player.position().distanceTo(f.from()) <= 3.0) {
            // Its path is being searched ahead on the planner thread right now: a second search would only queue
            // behind it. This trip takes that path the moment it comes (offerPreplan).
            awaiting = new Awaiting(to, tileIdx, goalExact, complete, generation);
            goalTo = to;
            goalTile = tileIdx;
            goalComplete = complete;
            pathPending = true;
            pendingGen = generation;
            pathPendingTicks = 0;
            lastPathFailed = false;
            LOGGER.info("[Path] the path to {} is being planned ahead - this trip waits for it", to.toShortString());
            return;
        }
        goalTo = to;
        goalTile = tileIdx;
        goalComplete = complete;
        pressNanos = System.nanoTime();
        Vec3 from = player.position();
        int packetsAtPlan = positionPackets;
        planPackets = packetsAtPlan;
        planFrom = from;
        DungeonLayout layout = DungeonLayout.capture();
        EtherwarpPathfinder.PathConfig cfg = pathConfig();
        // The planner's hop range. One number in the sim and on Hypixel: the sim's server gives an etherwarp
        // 57 blocks plus one per Transmission Tuner, as Hypixel does - see hopRange().
        double hopRange = hopRange();
        int gen = generation;
        boolean exact = goalExact;
        pathPending = true;
        pendingGen = gen;
        pathPendingTicks = 0;
        lastPathFailed = false;
        PLANNER.submit(() -> {
            long start = System.currentTimeMillis();
            List<EtherwarpPathfinder.Node> path = null;
            try {
                // Landings are planned at block top + 1.05, QUOI's figure for Hypixel - and the sim's server now
                // lands an etherwarp there too (SimAbilities.ETHERWARP_LANDING_OFFSET), so there is one value.
                path = tileIdx >= 0
                        ? EtherwarpPathfinder.findDungeonPathToTile(from, to, tileIdx, cfg, hopRange, layout)
                        : EtherwarpPathfinder.findDungeonPath(from, to, cfg, hopRange, layout, exact);
            } catch (Throwable e) {
                // Throwable, not RuntimeException: anything else escaping here skipped the hand-back below, which
                // left pathPending set for good and every later click silently refused.
                LOGGER.warn("[InteractiveMap] Path search failed: {}", e.toString());
            }
            long took = System.currentTimeMillis() - start;
            List<EtherwarpPathfinder.Node> result = path;
            client.execute(() -> {
                if (gen == pendingGen) {
                    pathPending = false; // only the search that set it clears it
                }
                if (gen != generation) {
                    return;
                }
                LocalPlayer now = client.player;
                int unjudged = positionPackets - Math.max(positionPacketsSeen, packetsAtPlan);
                if (unjudged > 0 && now != null) {
                    // A position packet drained in the same batch as this result, before any tick looked at it -
                    // testkit 131-sim-auto-clear, 2026-10-07: the search took 0.17 ms, so the server's teleport and the plan
                    // arrived together, and startQueue would have taken the packet as already seen.
                    moveFrom = null;
                    positionPacketsSeen = positionPackets;
                    if (movedWhilePlanning(unjudged, now.position(), from, true)) {
                        searchMovedGen = gen;
                    }
                }
                if (searchMovedGen == gen) {
                    // The server moved him while this was searched (movedWhilePlanning said so): the plan starts
                    // where he no longer is. Plan the same goal again from here - not a failed try.
                    searchMovedGen = Integer.MIN_VALUE;
                    LOGGER.info("[Path] the plan from {} is stale (the server moved you) - planning again from {}",
                            fmt(from), now == null ? "?" : fmt(now.position()));
                    goalTo = to;
                    goalTile = tileIdx;
                    goalComplete = complete;
                    replanPending = true;
                    replanWaitTicks = 0;
                    return;
                }
                if (result != null && result.isEmpty()) {
                    // Already standing where the click asked for (in the clicked tile, or on the block).
                    alreadyThere(to);
                    goalTo = null;
                    goalComplete = null;
                    if (complete != null) {
                        complete.run();
                    }
                    return;
                }
                if (result == null) {
                    lastPathFailed = true;
                    goalTo = null;
                    goalComplete = null;
                    ModChat.send(CHAT, ModChat.bad("Failed"), ModChat.dim(" after "), ModChat.value(took + "ms"));
                    return;
                }
                List<ClearNode> list = new ArrayList<>();
                for (EtherwarpPathfinder.Node n : result) {
                    list.add(ClearNode.toEther(n));
                }
                String impossible = implausible(from, list, hopRange);
                if (impossible != null) {
                    // Never run a plan that cannot happen: plan again from here, or stop and say why.
                    LOGGER.warn("[Path] refused an impossible plan of {} warp(s) from {} to {}: {}", list.size(),
                            fmt(from), to, impossible);
                    goalTo = to;
                    goalTile = tileIdx;
                    goalComplete = complete;
                    offPath("the plan was impossible (" + impossible + ")", complete);
                    return;
                }
                ModChat.send(CHAT, ModChat.text("Found path in "), ModChat.value(took + "ms"), ModChat.dim(" ("
                        + result.size() + " warps)"));
                LOGGER.info("[Path] running {} warp(s) from {} to {}{} - {} ms from the press to the first warp's turn",
                        list.size(), fmt(from), to, replans > 0 ? " (planned again, " + replans + "/" + MAX_REPLANS + ")"
                                : "", String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - pressNanos) / 1e6));
                startQueue(list, complete);
                improveInBackground(list, to, tileIdx, layout);
            });
        });
    }

    /**
     * Why a planned path cannot happen as planned, or null. Each hop is cast exactly as {@link ClearNode.EtherNode}
     * will cast it (its stand, sneaking eye, float yaw and pitch): it must land, within the hop range (the block's
     * centre at most {@code range + 1} from the eye - the server refuses anything past its own reach), and on the
     * next hop's stand. The first hop must start where he is. Together that also bounds the warps against the
     * distance: no N-warp plan reaches further than N hop ranges. Client thread.
     */
    static String implausible(Vec3 from, List<ClearNode> path, double range) {
        if (path.isEmpty()) {
            return null;
        }
        if (path.get(0).pos.distanceTo(from) > 1.5) {
            return String.format(java.util.Locale.US, "its first warp starts %.1f blocks from him",
                    path.get(0).pos.distanceTo(from));
        }
        for (int i = 0; i < path.size(); i++) {
            ClearNode n = path.get(i);
            Vec3 eye = new Vec3(n.pos.x, n.pos.y + TeleportUtils.eyeHeight(true), n.pos.z);
            TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye, n.yaw, n.pitch, 61.0);
            if (!hit.succeeded() || hit.pos() == null) {
                return "warp " + (i + 1) + " lands nowhere";
            }
            double reach = eye.distanceTo(Vec3.atCenterOf(hit.pos()));
            if (reach > range + 1.0) {
                return String.format(java.util.Locale.US, "warp %d reaches %.1f blocks, past the %.0f-block range",
                        i + 1, reach, range);
            }
            if (i + 1 < path.size()) {
                Vec3 next = path.get(i + 1).pos;
                Vec3 land = new Vec3(hit.pos().getX() + 0.5, hit.pos().getY() + 1.05, hit.pos().getZ() + 0.5);
                if (!landedOn(land, next, LAND_XZ, LAND_Y)) {
                    return String.format(java.util.Locale.US, "warp %d lands %.1f blocks from where warp %d starts",
                            i + 1, land.distanceTo(next), i + 2);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- planned ahead

    /**
     * A path searched before it is asked for (killer560, 2026-10-15: "it needs to predict when it is going to finish auto
     * routing a room that way the very next tick it starts etherwarping to the next room"). Auto Secret searches the trip
     * to the next room from where the route it is running will leave him, while the route runs; when that trip is then
     * asked for, {@link #plan} runs it at once instead of searching again.
     *
     * @param to      the goal block ({@link #etherPath}/{@link #etherPathExact}), or the tile's fallback block
     * @param tileIdx the tile of a tile trip ({@link #etherPathToTile}), -1 for a block
     * @param from    where the plan starts (the feet position it was searched from)
     */
    private record Preplan(BlockPos to, int tileIdx, boolean exact, Vec3 from, List<EtherwarpPathfinder.Node> path,
                           Object level, long madeMs) {
    }

    private static volatile Preplan preplan;
    /** The queue now running was planned ahead: each hop goes out on the tick it is worked out (see {@link #onTickStart}). */
    private static boolean sendNow = false;
    /** The tick a pre-planned queue swapped to its item on: no use goes out on that tick (held-item first, use next). */
    private static long swappedOnTick = Long.MIN_VALUE;
    /** A pre-planned trip may wait at most this long for the request that matches it. */
    private static final long PREPLAN_MAX_AGE_MS = 60_000L;

    /** The client tick, counted before any other START handler runs: the testkit and Auto Secret compare ticks with it. */
    public static long clientTick() {
        return clientTicks;
    }

    /**
     * Keeps {@code path} (searched on the planner thread from {@code from}) for the next {@link #plan} of the same goal.
     * Client thread. A newer offer replaces an older one.
     */
    public static void offerPreplan(Vec3 from, BlockPos to, int tileIdx, boolean exact, List<EtherwarpPathfinder.Node> path) {
        Minecraft client = Minecraft.getInstance();
        if (path == null || path.isEmpty() || client.level == null) {
            inflight = null;
            if (awaiting != null) {
                releaseAwaiting("the search planning ahead found nothing");
            }
            return;
        }
        preplan = new Preplan(to, tileIdx, exact, from, List.copyOf(path), client.level, System.currentTimeMillis());
        inflight = null;
        LOGGER.info("[Path] planned ahead: {} warp(s) from {} to {}{}", path.size(), fmt(from), to.toShortString(),
                tileIdx >= 0 ? " (tile " + tileIdx + ")" : "");
        if (awaiting != null) {
            Awaiting a = awaiting;
            awaiting = null;
            if (a.gen() == pendingGen) {
                pathPending = false;
            }
            if (a.gen() == generation && client.player != null) {
                goalExact = a.exact();
                if (!adoptPreplan(a.to(), a.tileIdx(), a.complete(), client.player)) {
                    plan(a.to(), a.tileIdx(), a.complete());
                }
            }
        }
    }

    /** Drops a path planned ahead (its goal is no longer wanted). */
    public static void dropPreplan() {
        preplan = null;
        inflight = null;
        if (awaiting != null) {
            releaseAwaiting("the path planned ahead was dropped");
        }
    }

    /** A goal whose path is being planned ahead right now (on the planner thread), from where. */
    private record Inflight(BlockPos to, int tileIdx, boolean exact, Vec3 from) {
    }

    private static volatile Inflight inflight;

    /** A trip asked for while the path planned ahead for it was still being searched: it takes that path when it comes. */
    private record Awaiting(BlockPos to, int tileIdx, boolean exact, Runnable complete, int gen) {
    }

    private static Awaiting awaiting;

    /**
     * The planner thread has picked the goal it is planning ahead for: a trip to it asked for before the search is done
     * waits for that search instead of queueing a second one behind it. Any thread (posted to the client thread).
     */
    public static void preplanStarted(Vec3 from, BlockPos to, int tileIdx, boolean exact) {
        Minecraft.getInstance().execute(() -> inflight = new Inflight(to, tileIdx, exact, from));
    }

    /** The search planning ahead found nothing (or was abandoned): a trip waiting on it searches for itself. */
    public static void preplanFailed() {
        Minecraft.getInstance().execute(() -> {
            inflight = null;
            if (awaiting != null) {
                releaseAwaiting("the search planning ahead found nothing");
            }
        });
    }

    /** The trip that was waiting on a search planning ahead plans for itself after all. Client thread. */
    private static void releaseAwaiting(String why) {
        Awaiting a = awaiting;
        awaiting = null;
        if (a == null) {
            return;
        }
        if (a.gen() == pendingGen) {
            pathPending = false;
        }
        if (a.gen() != generation) {
            return;
        }
        LOGGER.info("[Path] {} - searching", why);
        goalExact = a.exact();
        plan(a.to(), a.tileIdx(), a.complete());
    }

    /** Whether a trip search of the map is queued or running ({@link #etherPath} and its kin): searches planning ahead
     *  for later give way to it. Any thread. */
    public static boolean tripSearchWaiting() {
        return pathPending && awaiting == null;
    }

    /** Whether a path planned ahead is waiting for {@code to}. */
    public static boolean hasPreplanFor(BlockPos to, int tileIdx) {
        Preplan p = preplan;
        return p != null && (tileIdx >= 0 ? p.tileIdx() == tileIdx : p.tileIdx() < 0 && p.to().equals(to));
    }

    /**
     * Runs the path planned ahead for this goal, if there is one and it still starts where he stands: within a third of a
     * block of its start as planned, or else with its first warp aimed again from where he really is at the same landing
     * block (a route's last node leaves him anywhere in its ring). Checked like any plan ({@link #implausible}).
     * @return true when the queue started
     */
    private static boolean adoptPreplan(BlockPos to, int tileIdx, Runnable complete, LocalPlayer player) {
        Preplan p = preplan;
        if (p == null) {
            return false;
        }
        boolean same = tileIdx >= 0 ? p.tileIdx() == tileIdx : p.tileIdx() < 0 && p.to().equals(to) && p.exact() == goalExact;
        Minecraft client = Minecraft.getInstance();
        if (!same || p.level() != client.level || System.currentTimeMillis() - p.madeMs() > PREPLAN_MAX_AGE_MS) {
            if (!same) {
                LOGGER.info("[Path] the path planned ahead was for {} - this trip goes to {}: searching", p.to().toShortString(),
                        to.toShortString());
            }
            preplan = null;
            return false;
        }
        preplan = null;
        return runPlanned(p.path(), p.from(), to, tileIdx, complete, player, "planned ahead", null);
    }

    /** Why the last {@link #runPlanned} would not run its path (for the cache's drop line). */
    private static String plannedRefusal = "";

    /**
     * Runs a path searched before it was asked for (a preplan, a precomputed trip) if it still holds from where he stands:
     * within a third of a block of its start as planned, or else with its first warp aimed again from where he really is
     * at the same landing block (a route's last node leaves him anywhere in its ring). Checked like any plan
     * ({@link #implausible}); with {@code lastLanding}, its last hop must still land on that block.
     * @return true when the queue started
     */
    private static boolean runPlanned(List<EtherwarpPathfinder.Node> path, Vec3 from, BlockPos to, int tileIdx,
                                      Runnable complete, LocalPlayer player, String source, BlockPos lastLanding) {
        Vec3 at = player.position();
        List<ClearNode> list = new ArrayList<>();
        for (EtherwarpPathfinder.Node n : path) {
            list.add(ClearNode.toEther(n));
        }
        boolean reaimed = false;
        if (at.distanceTo(from) > 0.3) {
            ClearNode first = list.get(0);
            Vec3 eye0 = new Vec3(first.pos.x, first.pos.y + TeleportUtils.eyeHeight(true), first.pos.z);
            TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye0, first.yaw, first.pitch, 61.0);
            if (!hit.succeeded() || hit.pos() == null) {
                plannedRefusal = "its first warp lands nowhere from its own start";
                LOGGER.info("[Path] the path {} lands nowhere from its own start - searching", source);
                return false;
            }
            Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
            TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, hit.pos(), hopRange() + 1.0);
            if (rot == null) {
                plannedRefusal = "its first landing cannot be seen from where he stands";
                LOGGER.info("[Path] the path {} started {} blocks from where you are and its first landing {}"
                        + " cannot be seen from here - searching", source, String.format(java.util.Locale.ROOT, "%.1f",
                        at.distanceTo(from)), hit.pos().toShortString());
                return false;
            }
            Vec3 look = TeleportUtils.getLook(rot.yaw(), rot.pitch()).scale(60.0);
            list.set(0, new ClearNode.EtherNode(at, rot.yaw(), rot.pitch(), 0xFFA500, List.of(eye, eye.add(look))));
            reaimed = true;
        }
        String impossible = implausible(at, list, hopRange());
        if (impossible == null && lastLanding != null) {
            ClearNode last = list.get(list.size() - 1);
            Vec3 eye = new Vec3(last.pos.x, last.pos.y + TeleportUtils.eyeHeight(true), last.pos.z);
            TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye, last.yaw, last.pitch, 61.0);
            if (!hit.succeeded() || !lastLanding.equals(hit.pos())) {
                impossible = "its last warp lands on " + (hit.pos() == null ? "nothing" : hit.pos().toShortString())
                        + ", not " + lastLanding.toShortString();
            }
        }
        if (impossible != null) {
            plannedRefusal = impossible;
            LOGGER.info("[Path] the path {} does not hold from here ({}) - searching", source, impossible);
            return false;
        }
        goalTo = to;
        goalTile = tileIdx;
        goalComplete = complete;
        pressNanos = System.nanoTime();
        lastPathFailed = false;
        LOGGER.info("[Path] running {} warp(s) {} from {} to {}{} - no search", list.size(), source, fmt(at), to,
                reaimed ? String.format(java.util.Locale.ROOT, " (first warp aimed again: he stands %.2f from its planned"
                        + " start)", at.distanceTo(from)) : "");
        ModChat.send(CHAT, ModChat.text(source.startsWith("planned ahead") ? "Path planned ahead" : "Path precomputed"),
                ModChat.dim(" (" + list.size() + " warps)"));
        startQueue(list, complete);
        sendNow = true;
        compDelay = 0;
        // The item now, on the tick the queue starts: the first warp then waits only for the sneak.
        if (list.get(0) instanceof ClearNode.EtherNode && !holdingAny(AOTV) && swapById(AOTV)) {
            swappedOnTick = clientTicks;   // asked for from a START handler: no use goes out this same tick
        }
        improveInBackground(list, to, tileIdx, DungeonLayout.capture());
        return true;
    }

    /** The precomputed trip the running queue came from (TripCache), dropped if the queue goes wrong. */
    private static TripCache.Entry cachedRun;
    /** The client tick the last queue started on (a press answered at once starts it on the press's own tick). */
    private static long lastPressTick = -1;

    public static long lastPressTick() {
        return lastPressTick;
    }

    /**
     * Runs the precomputed trip for this goal from where he stands, if there is one and it holds now (TripCache): the
     * same checks as a path planned ahead, and its last warp must still land where it was planned. One that does not hold
     * is dropped and the trip is searched as usual, at once.
     */
    private static boolean adoptCached(BlockPos to, int tileIdx, Runnable complete, LocalPlayer player) {
        TripCache.Entry e = TripCache.find(player.position(), to, tileIdx, goalExact);
        if (e == null) {
            if (TripCache.counting()) {
                TripCache.miss();
            }
            return false;
        }
        String source = String.format(java.util.Locale.ROOT, "precomputed (phase %d, %.1f s old%s)", e.phase(),
                e.ageMs() / 1000.0, e.stale() ? ", to be planned again" : "");
        if (!runPlanned(e.path(), e.from(), to, tileIdx, complete, player, source, e.lastLanding())) {
            TripCache.reject(e, plannedRefusal);
            return false;
        }
        TripCache.hit(e);
        cachedRun = e;
        return true;
    }

    private static final String[] AOTV = {"ASPECT_OF_THE_VOID"};

    /**
     * A queue planned ahead can be asked for on the very tick a warp landed him (the start node's route ended there at
     * once), standing 0.05 above the block, and a marginal look planned from the block's own height did not land from
     * there (2026-10-15, 144: "Failed from ..." and a fresh search) - as a route's own warp fired on its landing tick
     * re-aims (LESSONS-AUTOMATION, chain timing). So, about to send its first warp: that warp aimed again from exactly
     * where he is now at the landing block it was planned for (a look planned from a spot a few hundredths off can miss).
     */
    private static void reaimFirstHop(Minecraft client) {
        List<ClearNode> current = nodes;
        if (current == null || current.isEmpty() || !(current.get(0) instanceof ClearNode.EtherNode first)
                || client.player == null) {
            return;
        }
        Vec3 at = client.player.position();
        if (at.distanceTo(first.pos) < 0.001) {
            return;
        }
        Vec3 eye0 = new Vec3(first.pos.x, first.pos.y + TeleportUtils.eyeHeight(true), first.pos.z);
        TeleportUtils.RaycastResult hit = TeleportUtils.getEtherPos(eye0, first.yaw, first.pitch, 61.0);
        if (!hit.succeeded() || hit.pos() == null) {
            return;
        }
        Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
        TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, hit.pos(), hopRange() + 1.0);
        if (rot == null) {
            return;
        }
        Vec3 look = TeleportUtils.getLook(rot.yaw(), rot.pitch()).scale(60.0);
        List<ClearNode> swapped = new ArrayList<>(current);
        swapped.set(0, new ClearNode.EtherNode(at, rot.yaw(), rot.pitch(), 0xFFA500, List.of(eye, eye.add(look))));
        nodes = swapped;
        position = null;
    }

    // ------------------------------------------------------------------------------------------- held at the start

    /**
     * Auto Secret's last-node wait (killer560, 2026-10-15: "It needs a wait timeout at the last node of 6 ticks at max if
     * all secrets are not collected by the time it hits that node. If during those 6 ticks the room is fully cleared then
     * it should leave the next tick"). While held, a queue that has not sent its first warp waits with the sneak down and
     * the item in hand, so the warp goes on the very tick it is let go. Released by {@link #setHold}(false), by any cancel,
     * or on its own after {@link #HOLD_MAX_TICKS}.
     */
    private static boolean hold = false;
    private static int holdTicks = 0;
    private static final int HOLD_MAX_TICKS = 40;

    public static void setHold(boolean value) {
        hold = value;
        holdTicks = 0;
    }

    public static boolean isHeld() {
        return hold;
    }

    /** @return true while the queue is held this tick (nothing else of it runs) */
    private static boolean tickHold(Minecraft client) {
        if (!hold) {
            return false;
        }
        if (++holdTicks > HOLD_MAX_TICKS) {
            LOGGER.info("[Path] a held queue was never let go - going after {} ticks", HOLD_MAX_TICKS);
            hold = false;
            return false;
        }
        List<ClearNode> current = nodes;
        if (current == null || current.isEmpty() || !issued.isEmpty() || syncDelay != 0) {
            return false;   // nothing waiting to start (or it already has): the hold only keeps a first warp back
        }
        ticksSinceProgress = 0;
        waitReason = "held for the room's last secrets";
        boolean screen = com.killer560.hub.compat.McCompat.screen(client) != null;
        active = current.get(0) instanceof ClearNode.EtherNode && !screen;   // the sneak, never under a container
        if (active && !holdingAny(AOTV) && !hasSwappedThisTick && swapById(AOTV)) {
            swappedOnTick = clientTicks;
        }
        return true;
    }

    /**
     * "Already there", said once per goal every couple of seconds. An auto that keeps asking for a block it can
     * never quite reach (Auto Tic Tac Toe's chest trip, 2026-10-04: a hundred of these in four seconds) no longer
     * floods the chat; the request is still answered every time.
     */
    private static void alreadyThere(BlockPos to) {
        long now = System.currentTimeMillis();
        if (to.equals(lastAlreadyThereGoal) && now - lastAlreadyThereMs < 2000L) {
            return;
        }
        lastAlreadyThereGoal = to;
        lastAlreadyThereMs = now;
        ModChat.send(CHAT, ModChat.text("Already there"));
    }

    /** Runs a hop list another feature built itself (Etherwarp Hopper, Auto Fairy Souls). There is no goal to plan
     *  again to, so if it goes wrong it stops and says why. */
    public static void clearPath(List<ClearNode> path, Runnable complete) {
        goalTo = null;
        goalTile = -1;
        goalComplete = null;
        replans = 0;
        heldReplans = 0;
        replanPending = false;
        startQueue(path, complete);
    }

    // ------------------------------------------------------------------------------------------- improving as he flies

    /** When the plan now running was asked for (the press, or the replan), for the press-to-first-warp line. */
    private static long pressNanos;
    /** Bumped whenever a queue starts or stops: an improvement for an older one is thrown away. */
    private static volatile int improveGen;
    private static volatile EtherwarpPathfinder.Prover improving;
    /** How long one try at the rest of the trip may run before it starts again from further along. */
    private static final long IMPROVE_SLICE_NANOS = 1_500_000_000L;
    private static final ExecutorService IMPROVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-mapimprove");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static void stopImproving() {
        improveGen++;
        EtherwarpPathfinder.Prover p = improving;
        if (p != null) {
            p.cancel();
        }
        improving = null;
    }

    /**
     * killer560 (2026-10-11): "The interactive map must START moving near-instantly on a click [...] get it as close as
     * physically possible within that instant start." The press flies the floor graph's plan at once; meanwhile this
     * looks, off every game thread, for the rest of the trip in fewer warps - from the start of a warp he has not made
     * yet to the goal, with the exact search (EtherwarpPathfinder.remainderProver) - and swaps the rest of the queue
     * for it on the client thread if he has not reached that warp yet. A try that is not done in
     * {@link #IMPROVE_SLICE_NANOS} starts again from a warp further along (a smaller problem); a try that proves the rest
     * already the fewest ends it. Client thread.
     */
    private static void improveInBackground(List<ClearNode> plan, BlockPos to, int tileIdx, DungeonLayout layout) {
        if (plan.size() < 3) {
            return;   // two warps: the first is already going, and the second is one warp - it cannot get shorter
        }
        int gen = improveGen;
        List<Vec3> starts = new ArrayList<>();
        for (ClearNode n : plan) {
            starts.add(n.pos);
        }
        long t0 = System.nanoTime();
        IMPROVER.submit(() -> {
            int k = 1;
            while (gen == improveGen) {
                int left = starts.size() - k;
                if (left < 2) {
                    return;
                }
                EtherwarpPathfinder.Prover p;
                try {
                    p = EtherwarpPathfinder.remainderProver(starts.get(k), to, tileIdx, left, layout);
                } catch (RuntimeException e) {
                    LOGGER.warn("[Path] improving the trip failed: {}", e.toString());
                    return;
                }
                if (p == null) {
                    return;
                }
                improving = p;
                if (gen != improveGen) {
                    p.cancel();
                    return;
                }
                List<EtherwarpPathfinder.Node> better;
                try {
                    better = p.run(System.nanoTime() + IMPROVE_SLICE_NANOS);
                } catch (RuntimeException e) {
                    LOGGER.warn("[Path] improving the trip failed: {}", e.toString());
                    return;
                }
                if (better != null) {
                    int from = k;
                    double ms = (System.nanoTime() - t0) / 1e6;
                    Minecraft.getInstance().execute(() -> splice(gen, starts.get(from), better, from, left, ms));
                    return;
                }
                if (p.proven()) {
                    LOGGER.info("[Path] the rest of the trip from warp {} ({} warp(s)) is already the fewest (proved"
                            + " in {} ms)", k + 1, left, String.format(java.util.Locale.ROOT, "%.0f",
                            (System.nanoTime() - t0) / 1e6));
                    return;
                }
                // Not done in its slice: again from the next warp he has not made, at least one further on.
                List<ClearNode> now = nodes;
                int remaining = now == null ? 0 : now.size();
                k = Math.max(k + 1, starts.size() - remaining + 1);
            }
        });
    }

    /** Client thread: the rest of the queue from the warp starting at {@code at} becomes {@code better}. */
    private static void splice(int gen, Vec3 at, List<EtherwarpPathfinder.Node> better, int k, int left, double ms) {
        List<ClearNode> current = nodes;
        if (gen != improveGen || current == null) {
            return;
        }
        int i = -1;
        for (int j = 0; j < current.size(); j++) {
            if (current.get(j).pos.distanceTo(at) < 0.01) {
                i = j;
                break;
            }
        }
        if (i < 0 || current.get(i) == activeNode) {
            LOGGER.info("[Path] a shorter rest of the trip ({} warp(s) for {}) came after he had passed warp {}", better
                    .size(), left, k + 1);
            return;
        }
        List<ClearNode> swapped = new ArrayList<>(current.subList(0, i));
        for (EtherwarpPathfinder.Node n : better) {
            swapped.add(ClearNode.toEther(n));
        }
        nodes = swapped;
        LOGGER.info("[Path] improved the rest of the trip in flight: from warp {}, {} warp(s) instead of {} (found in {}"
                + " ms)", k + 1, better.size(), left, String.format(java.util.Locale.ROOT, "%.0f", ms));
    }

    private static void startQueue(List<ClearNode> path, Runnable complete) {
        cachedRun = null;
        lastPressTick = clientTicks;
        stopImproving();
        sendNow = false;
        if (syncDelay != 0) {
            finishSettleNow();
        }
        nodes = new ArrayList<>(path);
        position = null;
        pendingInteract = null;
        onComplete = complete;
        pendingCompletion = null;
        issued.clear();
        confirmed = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        lastGood = player == null ? null : player.position();
        positionPacketsSeen = positionPackets;
        ticksSinceProgress = 0;
        waitReason = null;
    }

    /**
     * A new queue starts while the last trip still waits out its arrival settle (Auto Secret's next trip, asked for the
     * tick the route on that trip's landing ended): that trip is over - its arrival counts and its completion runs now,
     * instead of the new queue waiting behind the settle and the old completion being dropped by it.
     */
    private static void finishSettleNow() {
        Runnable callback = pendingCompletion;
        boolean owner = externalOwner;
        Vec3 expected = issued.isEmpty() ? null : issued.get(issued.size() - 1);
        syncDelay = 0;
        syncWaitTicks = 0;
        pendingCompletion = null;
        issued.clear();
        confirmed = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        if (expected != null && player != null && landedOn(player.position(), expected, 1.5, 3.0)) {
            arrivalSeq++;
            arrivalMs = System.currentTimeMillis();
            arrivalPos = player.position();
        }
        LOGGER.info("[Path] the last trip's arrival settle was cut short by the next trip");
        if (callback != null) {
            callback.run();
        }
        externalOwner = owner;
    }

    /** Lets another feature run its own hop queue here while the Interactive Map's own toggles are off. */
    public static void setExternalOwner(boolean value) {
        externalOwner = value;
    }

    /**
     * Queues the next hop's use. {@code from} is the feet position the look was worked out from and {@code block} the
     * etherwarp block it lands on (null for a non-sneaking teleport): a hop held back by util/RightClick is aimed again
     * from where he stands when it goes (he falls while he waits - the hop is planned from a landing 1.05 up), the way Auto
     * Routes' {@code reaimIfMoved} does.
     */
    public static void queueInteract(float yaw, float pitch, Vec3 from, BlockPos block) {
        pendingInteract = new float[]{yaw, pitch};
        pendingFrom = from;
        pendingBlock = block;
        pendingHeldTicks = 0;
    }

    private static Vec3 pendingFrom;
    private static BlockPos pendingBlock;
    /** Quiet re-plans this goal has made for a held hop that had no ray left ({@link #MAX_HELD_REPLANS} at most). */
    private static int heldReplans;
    private static final int MAX_HELD_REPLANS = 2;

    /**
     * The hop held back by util/RightClick has no ray to its block from where he rests now (he stands 0.05 lower than
     * the landing it was planned from, and a marginal aim does not survive that). Nothing was sent for it, so this is not a
     * correction: the goal is planned again from here, no alarm and no chat line - at most {@link #MAX_HELD_REPLANS} times a
     * goal, then the planned look goes as it stands. False = no goal to plan again (a list another feature built), or the
     * cap: the caller sends the planned look.
     */
    private static boolean replanHeldHop(Vec3 at) {
        if (goalTo == null || heldReplans >= MAX_HELD_REPLANS) {
            return false;
        }
        heldReplans++;
        LOGGER.info("[Path] Warp: held {} tick(s), no ray from {} lands on {} - planning the goal again from here ({}/{})",
                pendingHeldTicks, fmt(at), pendingBlock.toShortString(), heldReplans, MAX_HELD_REPLANS);
        if (!issued.isEmpty()) {
            issued.remove(issued.size() - 1);   // the hop that never went out: its landing is not coming
        }
        BlockPos to = goalTo;
        int tile = goalTile;
        Runnable complete = goalComplete;
        dropQueue();
        goalTo = to;
        goalTile = tile;
        goalComplete = complete;
        replanPending = true;
        replanWaitTicks = 0;
        return true;
    }
    /** Ticks the queued hop has waited for the server to hear its look (util/RightClick). */
    private static int pendingHeldTicks;

    public static void cancel() {
        goalFastArrival = false;
        if (awaiting != null) {
            awaiting = null;
            pathPending = false;
        }
        stopImproving();
        noteStrayWarps();
        sendNow = false;
        hold = false;
        goalTo = null;
        goalTile = -1;
        goalComplete = null;
        replanPending = false;
        issued.clear();
        confirmed = 0;
        nodes = null;
        position = null;
        compDelay = 2;
        onComplete = null;
        pendingCompletion = null;
        // The arrival sync belongs to the path being cancelled, so drop it too. Left running it kept
        // isBusy() true for up to 49 more ticks (the 40-tick position-packet wait plus the 9-tick settle)
        // with no completion callback left to run - long enough that the Interactive Map's retarget
        // (killer560, 2026-09-29: "If I click a different room mid path...") would look like it did nothing.
        syncDelay = 0;
        syncWaitTicks = 0;
        generation++;
    }

    /** Called from the position-packet mixin (network and client thread). */
    public static void onServerPositionPacket() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (moveFrom == null && player != null) {
            moveFrom = player.position();   // before vanilla applies it (the mixin sits ahead of that)
        }
        positionPacketSeen = true;
        positionPackets++;
    }

    /** The unanswered warps of a queue being dropped: their landings are ours, not corrections, for a couple of s. */
    private static void noteStrayWarps() {
        int unanswered = issued.size() - confirmed;
        if (unanswered > 0) {
            strayWarps = unanswered;
            strayUntilMs = System.currentTimeMillis() + 2000L;
            strayLag = ServerLag.mark();
        }
    }

    /** Uses this class sent itself (its hops, a node's click), counted from {@link com.killer560.hub.util.RightClick#usesSent}. */
    private static int ownUses;
    /** Uses another feature sent, as last counted, and how many of their landings may still be on the way. */
    private static int foreignSeen;
    private static int foreignCredit;
    private static long foreignUntilMs;
    private static long foreignLag;
    /** Position packets read as another feature's landing rather than a correction, ever - the testkit reads it. */
    private static int foreignLandings;

    public static int foreignLandings() {
        return foreignLandings;
    }

    /** Counts the uses other features (Auto Ice Fill's hops, a puzzle's chest warp, Auto Routes) sent since the last look. */
    private static void collectForeignUses() {
        int foreign = com.killer560.hub.util.RightClick.usesSent() - ownUses;
        if (foreign > foreignSeen) {
            foreignCredit = Math.min(foreignCredit + foreign - foreignSeen, 8);
            foreignUntilMs = System.currentTimeMillis() + 2000L;
            foreignLag = ServerLag.mark();
        }
        foreignSeen = foreign;
    }

    /**
     * True (and one credit spent) when a use another feature of ours sent in the last ~2 s can account for this position
     * packet: its own warp landing, which that feature judges, not a server correction of the map's trip. His Mod Only Test
     * log (2026-10-15 15:54:08): a trip to Ice Fill had every landing answered and was in its arrival settle when Auto Ice
     * Fill began hopping; each 1-block hop read as "the server moved you off the last landing", then "while waiting to plan
     * again", 88 alarms in one crossing, and the replan of the old goal kept warping him back to the start while the chest
     * claim warped him to the chest. A server move with no use of ours behind it still reports.
     */
    private static boolean anotherFeaturesLanding() {
        collectForeignUses();
        if (foreignCredit > 0 && System.currentTimeMillis() <= foreignUntilMs + ServerLag.msSince(foreignLag)) {
            foreignCredit--;
            foreignLandings++;
            return true;
        }
        foreignCredit = 0;
        return false;
    }

    /**
     * Server position packets while a path is being SEARCHED or waits to be planned again, with no queue of ours
     * running: a correction (chat line + alarm, util/ServerCorrections) unless it is a late landing of the queue just
     * dropped. The search started from where he stood, so its plan is thrown away and the goal planned again from where
     * the server put him - never counted against {@link #MAX_REPLANS} (mod rule 2026-10-06: a correction never stops
     * anything). Until 2026-10-07 these packets were not looked at at all: the plan then started "not on the path's
     * first spot", which replanned as an ordinary failure with no alarm, and a second one in a click stopped the path
     * (testkit 131-sim-auto-clear, whose server teleport landed while Auto Clear's trip was being planned).
     */
    private static boolean movedWhilePlanning(int packets, Vec3 at, Vec3 before, boolean searching) {
        double moved = before == null ? ServerCorrections.lastMoveDistance() : at.distanceTo(before);
        if (moved < CORRECTION_MIN_MOVE) {
            return false;
        }
        if (strayWarps > 0 && System.currentTimeMillis() <= strayUntilMs + ServerLag.msSince(strayLag)) {
            int ours = Math.min(strayWarps, packets);
            strayWarps -= ours;
            LOGGER.info("[Path] {} landing(s) of the dropped path arrived while planning - not a correction", ours);
            if (ours >= packets) {
                return true;
            }
        }
        if (anotherFeaturesLanding()) {
            LOGGER.info("[Path] moved {} blocks {} by another feature's own warp - not a correction",
                    String.format(java.util.Locale.US, "%.1f", moved), searching ? "while planning" : "while waiting to plan again");
            return true;
        }
        serverCorrections++;
        String when = searching ? "while the path was being planned" : "while waiting to plan again";
        LOGGER.warn("[Path] the server moved you {} blocks {} - planning again from {}",
                String.format(java.util.Locale.US, "%.1f", moved), when, fmt(at));
        ServerCorrections.report("Interactive Map", "(" + when + ") - planning again from here", moved);
        return true;
    }

    /** Called from the keyboard-input mixin: QUOI forces shift while an etherwarp node is current or next. */
    public static boolean shouldForceSneak() {
        sneakMixinApplied = true;
        return forceSneakNow();
    }

    private static boolean forceSneakNow() {
        if (!active) {
            return false;
        }
        List<ClearNode> current = nodes;
        return activeNode instanceof ClearNode.EtherNode
                || (current != null && !current.isEmpty() && current.get(0) instanceof ClearNode.EtherNode);
    }

    // ------------------------------------------------------------------------------------------- ticking

    private static void onTickStart(Minecraft client) {
        // Every tick, so another feature's use is dated by when it went out, not by when a packet first asks about it.
        collectForeignUses();
        hasSwappedThisTick = swappedOnTick == clientTicks;
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.player == null) {
            return;
        }
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        // killer560: "the entire portion of interactive map is the teleport pathing" - there is no separate
        // pathing toggle any more, so Interactive Map itself being on is what keeps a queued path alive.
        if (!externalOwner && !cfg.isInteractiveMapEnabled() && !cfg.isBloodRushEnabled() && (nodes != null || pathPending)) {
            cancel();
        }
        // Keep the floor-wide etherwarp graph warm while he is in a dungeon, so a click only has to search it.
        if (cfg.isInteractiveMapEnabled() && client.level != null && DungeonLayout.current().roomCount() > 0) {
            EtherwarpPathfinder.tickWarm(PLANNER, () -> pathPending || externalPlans.get() > 0, hopRange());
        }
        // Before this tick's hop: the body is given back once the path has no hop left to send.
        BODY.tick(client.player, aiming());
        doInteract(client);
        updateDelays();
        applySneakFallback(client);
        tickPathSearch();
        tickReplan(client);
        if (!checkLandingsOnce(client)) {
            return;
        }
        if (tickHold(client)) {
            return;
        }
        if (!canNext()) {
            return;
        }
        if (issued.size() - confirmed >= MAX_LEAD) {
            waitReason = "the server did not answer " + (issued.size() - confirmed) + " warp(s)";
            return;
        }
        if (sendNow && issued.isEmpty()) {
            reaimFirstHop(client);
        }
        if (position == null) {
            Vec3 p = client.player.position();
            position = new double[]{p.x, p.y, p.z};
        }
        handleQueue(position, nodes);
        if (sendNow && pendingInteract != null && !hasSwappedThisTick) {
            // A queue planned ahead sends each hop on the tick it is worked out rather than the next: the same START slot,
            // ahead of this tick's movement packet, as the hop that would have gone next tick - one tick sooner, and the
            // pace between hops unchanged. Not on a tick that swapped the item (held-item first, the use a tick later).
            doInteract(client);
        }
    }

    /** QUOI's server-tick handler, on client ticks: after the position packet, wait ~8 ticks, then complete. */
    private static void onTickEnd(Minecraft client) {
        // Every warp of ours answered (checkLandings), or with none issued the old position-packet signal. The
        // 40-tick fallback stays for a server that sends nothing at all; checkLandings gives up on an unanswered
        // warp after STALL_TICKS, well before it.
        boolean answered = issued.isEmpty() ? positionPacketSeen : confirmed >= issued.size();
        if (syncDelay == 1 && syncWaitTicks == 0) {
            syncLag = ServerLag.mark();
        }
        if (syncDelay == 1 && (answered || ++syncWaitTicks > 40 + ServerLag.ticksSince(syncLag))) {
            syncDelay = 2;
            syncWaitTicks = 0;
        }
        if (syncDelay < 2) {
            return;
        }
        if (syncDelay++ > 9) {
            completeArrival(client);
        }
    }

    /** The trip is over: its arrival counts and its completion runs - or, he is not on its last landing, it plans again. */
    private static void completeArrival(Minecraft client) {
        completeArrival(client, false);
    }

    /** @param confirmedThere every landing was answered on the plan: he arrived, wherever another feature has put him since */
    private static void completeArrival(Minecraft client, boolean confirmedThere) {
        syncDelay = 0;
        syncWaitTicks = 0;
        Runnable callback = pendingCompletion;
        pendingCompletion = null;
        Vec3 expected = issued.isEmpty() ? null : issued.get(issued.size() - 1);
        Vec3 at = client.player == null ? null : client.player.position();
        if (confirmedThere && expected != null) {
            at = expected;
        }
        if (expected != null && at != null && !landedOn(at, expected, 1.5, 3.0)) {
            // The last landing was answered, but he is not there now: the goal is not reached, so the
            // "arrived" behaviour (face the door, an auto's next step) must not run.
            offPath(String.format(java.util.Locale.US, "ended %.1f blocks from the planned landing",
                    horizontal(at, expected)), callback);
            return;
        }
        issued.clear();
        confirmed = 0;
        goalTo = null;
        goalComplete = null;
        goalFastArrival = false;
        if (expected != null) {
            arrivalSeq++;
            arrivalMs = System.currentTimeMillis();
            arrivalPos = at;
        }
        if (callback != null) {
            callback.run();
        }
    }

    /** The tick {@link #checkLandings} last ran on, and what it said: it runs once a tick, whoever asks first. */
    private static long landingsCheckedAt = Long.MIN_VALUE;
    private static boolean landingsResult = true;

    private static boolean checkLandingsOnce(Minecraft client) {
        if (landingsCheckedAt != clientTicks) {
            landingsCheckedAt = clientTicks;
            landingsResult = checkLandings(client);
        }
        return landingsResult;
    }

    /**
     * A trip of {@link #etherPathArrive} whose every warp has gone out ends NOW if the server has confirmed its last
     * landing: the same landing check as every tick ({@link #checkLandings} - a packet that put him anywhere else is a
     * correction and plans again, as always), then he must stand on that landing, then the completion runs. Auto Routes
     * asks this at the start of its tick, so the start node he landed on arms on the very tick the landing is known -
     * from the position the server put him at, never a predicted one. Client thread.
     * @return true when the trip ended here
     */
    public static boolean finishArrivalEarly(Minecraft client) {
        if (!goalFastArrival || syncDelay == 0 || client.player == null || pendingInteract != null
                || (nodes != null && !nodes.isEmpty()) || issued.isEmpty()) {
            return false;
        }
        if (!checkLandingsOnce(client) || confirmed < issued.size()) {
            return false;
        }
        Vec3 expected = issued.get(issued.size() - 1);
        Vec3 at = client.player.position();
        if (!landedOn(at, expected, LAND_XZ, LAND_Y)) {
            return false;
        }
        LOGGER.info("[Path] arrived: the server confirmed the last landing {} - no arrival settle", fmt(at));
        lastFastArrivalTick = clientTicks;
        // The hops' body turn goes back to his view now, before the route that arms next turns the body for its own
        // first action this same tick. Left to this class's own tick (later in START) it undid the route's turn, and the
        // route's warp went out with a rotation no movement packet reported: GrimAC BadPacketsJ (62-argrim-imwarp-run,
        // 2026-10-15).
        BODY.tick(client.player, false);
        completeArrival(client);
        return true;
    }

    /** {@link #clientTick} of the last trip {@link #finishArrivalEarly} ended, for the testkit. */
    private static long lastFastArrivalTick = -1;

    public static long lastFastArrivalTick() {
        return lastFastArrivalTick;
    }

    // ------------------------------------------------------------------------------------------- keeping it honest

    /**
     * Matches the server's position packets against the landings issued so far. A packet that leaves him on the
     * next expected landing (or a later one - several can arrive between two ticks) confirms it; one that leaves
     * him where he already was (a rotation-only correction) is ignored; anything else means the server put him
     * somewhere the plan does not know about: a correction - chat line + alarm (util/ServerCorrections), the queue is
     * dropped and the same goal planned again from where he really is, every time (mod rule 2026-10-06: a correction
     * never stops anything, so it does not count against MAX_REPLANS).
     * Also gives up on a queue that has made no progress for {@link #STALL_TICKS}.
     *
     * @return false when the path was given up on this tick
     */
    private static boolean checkLandings(Minecraft client) {
        // A queue in flight or awaiting its last landing: progress is judged, and a stall gives up.
        boolean running = (nodes != null && !nodes.isEmpty()) || (syncDelay == 1 && confirmed < issued.size());
        // Every landing answered, the arrival settle still to run: a packet now is not one of ours either (until
        // 2026-10-07 it was ignored here and became "ended N blocks from the planned landing" - an ordinary replan
        // with no alarm, which a second time in a click stopped the path).
        boolean settling = !running && syncDelay != 0;
        boolean planning = !running && !settling && (pathPending || replanPending);
        Vec3 before = moveFrom;
        moveFrom = null;
        if ((!running && !settling && !planning) || client.player == null) {
            positionPacketsSeen = positionPackets;
            return true;
        }
        Vec3 at = client.player.position();
        int packets = positionPackets;
        if (planning) {
            if (packets != positionPacketsSeen) {
                boolean olderUnseen = pathPending && positionPacketsSeen < planPackets;
                int arrived = packets - (pathPending ? Math.max(positionPacketsSeen, planPackets) : positionPacketsSeen);
                positionPacketsSeen = packets;
                if (olderUnseen) {
                    before = planFrom;   // moveFrom predates the search
                }
                if (arrived > 0 && movedWhilePlanning(arrived, at, before, pathPending) && pathPending) {
                    searchMovedGen = pendingGen;
                }
            }
            return true;
        }
        if (settling) {
            if (packets != positionPacketsSeen) {
                positionPacketsSeen = packets;
                if (lastGood != null && !landedOn(at, lastGood, LAND_XZ, LAND_Y)
                        && (before == null || at.distanceTo(before) >= CORRECTION_MIN_MOVE)) {
                    if (confirmed >= issued.size() && anotherFeaturesLanding()) {
                        // Every landing was answered: the trip got there. What moved him since is another feature's own
                        // warp (Auto Ice Fill's first hop, a chest claim) - the trip ends as arrived, and nothing plans
                        // its goal again over the top of that feature.
                        LOGGER.info("[Path] arrived: every landing answered, then another feature's warp moved him {} - "
                                + "not a correction", fmt(at));
                        completeArrival(client, true);
                        return false;
                    }
                    serverCorrections++;
                    offPath(String.format(java.util.Locale.US, "the server moved you %.1f blocks off the last landing",
                            horizontal(at, lastGood)), null, true);
                    return false;
                }
            }
            return true;
        }
        if (packets != positionPacketsSeen) {
            positionPacketsSeen = packets;
            int match = -1;
            for (int i = confirmed; i < issued.size(); i++) {
                if (landedOn(at, issued.get(i), LAND_XZ, LAND_Y)) {
                    match = i;
                }
            }
            if (match >= 0) {
                confirmed = match + 1;
                lastGood = issued.get(match);
                ticksSinceProgress = 0;
            } else if (lastGood == null || !landedOn(at, lastGood, LAND_XZ, LAND_Y)) {
                serverCorrections++;
                Vec3 want = confirmed < issued.size() ? issued.get(confirmed) : null;
                offPath(want == null ? "the server moved you off the path"
                        : String.format(java.util.Locale.US, "warp %d put you %.1f blocks from where it was aimed",
                        confirmed + 1, horizontal(at, want)), null, true);
                return false;
            }
        }
        if (ticksSinceProgress++ == 0) {
            progressLag = ServerLag.mark();
        }
        boolean offStart = issued.isEmpty() && waitReason != null && waitReason.startsWith("not on");
        if (offStart && ticksSinceProgress > OFF_START_TICKS && client.player.onGround()) {
            offPath(waitReason, null);
            return false;
        }
        // A server stall since the last progress is not the warp failing: the deadline grows by the stall (0 when
        // the server keeps time, so the normal case is exactly STALL_TICKS).
        if (ticksSinceProgress > STALL_TICKS + ServerLag.ticksSince(progressLag)) {
            offPath(confirmed < issued.size() ? "warp " + (confirmed + 1) + " never landed"
                    : waitReason != null ? waitReason : "nothing moved for a second", null);
            return false;
        }
        return true;
    }

    /**
     * The path went wrong: drop it and plan the same goal again from where he really is (at most
     * {@link #MAX_REPLANS} times a click), or stop and say why.
     */
    private static void offPath(String why, Runnable completion) {
        offPath(why, completion, false);
    }

    /** @param correction the server's position packet put him off the plan: reported as a correction, and planned
     *                    again whatever {@link #replans} says (a correction never stops the map's runner). */
    private static void offPath(String why, Runnable completion, boolean correction) {
        BlockPos to = goalTo;
        int tile = goalTile;
        Runnable complete = goalComplete != null ? goalComplete : completion;
        boolean canReplan = to != null && (correction || replans < MAX_REPLANS);
        if (cachedRun != null && !correction) {
            TripCache.reject(cachedRun, "it went wrong in flight (" + why + ")");
        }
        cachedRun = null;
        LOGGER.warn("[Path] off the plan: {} ({} of {} warp(s) answered) - {}", why, confirmed, issued.size(),
                canReplan ? "planning again from here" : correction ? "no goal to plan again" : "stopping");
        dropQueue();
        if (correction) {
            com.killer560.hub.util.ServerCorrections.report("Interactive Map", "(" + why + ") - "
                    + (canReplan ? "planning again from here" : "nothing left to plan"),
                    com.killer560.hub.util.ServerCorrections.lastMoveDistance());
        }
        if (canReplan) {
            if (!correction) {
                replans++;
            }
            goalTo = to;
            goalTile = tile;
            goalComplete = complete;
            replanPending = true;
            replanWaitTicks = 0;
            if (!correction) {
                ModChat.send(CHAT, ModChat.bad(capitalise(why)), ModChat.dim(" - planning again from here ("
                        + replans + "/" + MAX_REPLANS + ")"));
            }
            return;
        }
        if (correction) {
            // No goal behind the queue (nothing to plan again): the correction is reported; the flags below still go
            // so a caller waiting on this path is released instead of waiting for a queue that no longer exists.
            lastPathFailed = true;
            externalOwner = false;
            return;
        }
        lastPathFailed = true;
        externalOwner = false;
        ModChat.send(CHAT, ModChat.bad("Stopped: "), ModChat.text(why));
    }

    /** Plans a goal {@link #offPath} kept again, once he stands somewhere a path can start from. */
    private static void tickReplan(Minecraft client) {
        if (!replanPending) {
            return;
        }
        if (client.player == null) {
            replanPending = false;
            return;
        }
        if (client.player.onGround() && !pathPending && AutoClearUtils.canPath(DungeonLayout.capture())) {
            replanPending = false;
            BlockPos to = goalTo;
            if (to != null) {
                plan(to, goalTile, goalComplete);
            }
            return;
        }
        if (++replanWaitTicks > REPLAN_WAIT_TICKS) {
            replanPending = false;
            goalTo = null;
            goalComplete = null;
            lastPathFailed = true;
            LOGGER.warn("[Path] could not plan again: not on the ground, or in a room a path cannot start from");
            ModChat.send(CHAT, ModChat.bad("Stopped: "), ModChat.text("could not plan again from here"));
        }
    }

    /** A search that never hands back would hold every later click off for good: give up on it after 10 s. */
    private static void tickPathSearch() {
        if (!pathPending) {
            pathPendingTicks = 0;
            return;
        }
        if (awaiting != null && ++pathPendingTicks > 60) {
            releaseAwaiting("the search planning ahead took over 3 s");
            return;
        }
        if (awaiting == null && ++pathPendingTicks > PATH_SEARCH_LIMIT_TICKS) {
            LOGGER.warn("[Path] the path search did not answer in {} ticks - dropped", PATH_SEARCH_LIMIT_TICKS);
            generation++;
            pathPending = false;
            pathPendingTicks = 0;
            goalTo = null;
            goalComplete = null;
            lastPathFailed = true;
            ModChat.send(CHAT, ModChat.bad("Stopped: "), ModChat.text("the path search did not answer"));
        }
    }

    /** Everything about the running queue except the goal (callers keep or drop that themselves). */
    private static void dropQueue() {
        noteStrayWarps();
        sendNow = false;
        hold = false;
        nodes = null;
        position = null;
        pendingInteract = null;
        onComplete = null;
        pendingCompletion = null;
        compDelay = 2;
        syncDelay = 0;
        syncWaitTicks = 0;
        issued.clear();
        confirmed = 0;
        ticksSinceProgress = 0;
        waitReason = null;
        goalTo = null;
        goalComplete = null;
        generation++;
    }

    private static boolean landedOn(Vec3 at, Vec3 landing, double xz, double y) {
        return horizontal(at, landing) <= xz && Math.abs(at.y - landing.y) <= y;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static void reset() {
        awaiting = null;
        inflight = null;
        nodes = null;
        sendNow = false;
        hold = false;
        preplan = null;
        syncDelay = 0;
        compDelay = 0;
        globalDelay = 0;
        hypeDelay = 0;
        position = null;
        active = false;
        activeNode = null;
        onComplete = null;
        pendingCompletion = null;
        pendingInteract = null;
        issued.clear();
        confirmed = 0;
        goalTo = null;
        goalComplete = null;
        replanPending = false;
        pathPending = false;
        // A world change drops the queue and its completion callback, so no external feature owns it any more.
        // (Already false whenever nothing external is running, so this changes nothing for the Interactive Map.)
        externalOwner = false;
        generation++;
    }

    private static void handleQueue(double[] playerPos, List<ClearNode> clearNodes) {
        ClearNode node = null;
        for (ClearNode n : clearNodes) {
            if (n.inside(playerPos) && (node == null || n.priority() > node.priority())) {
                node = n;
            }
        }
        if (node == null) {
            if (issued.isEmpty() && !clearNodes.isEmpty()) {
                LocalPlayer player = Minecraft.getInstance().player;
                waitReason = player == null ? "no player"
                        : String.format(java.util.Locale.US, "not on the path's first spot (%.1f blocks off)",
                        player.position().distanceTo(clearNodes.get(0).pos));
            } else {
                waitReason = "not on any spot of the path";
            }
            position = null;
            return;
        }
        active = true;
        activeNode = node;
        if (node instanceof ClearNode.HypeNode && hypeDelay > 0) {
            return;
        }
        double bx = playerPos[0];
        double by = playerPos[1];
        double bz = playerPos[2];
        waitReason = "the sneak never reached the server";
        int usesBefore = com.killer560.hub.util.RightClick.usesSent();
        boolean executed;
        try {
            executed = node.execute(playerPos);
        } finally {
            ownUses += com.killer560.hub.util.RightClick.usesSent() - usesBefore;
        }
        if (executed) {
            if (nodes == null) {
                return; // cancelled inside execute
            }
            if (bx != playerPos[0] || by != playerPos[1] || bz != playerPos[2]) {
                issued.add(new Vec3(playerPos[0], playerPos[1], playerPos[2]));
            }
            ticksSinceProgress = 0;
            waitReason = null;
            clearNodes.remove(node);
            if (node instanceof ClearNode.HypeNode) {
                hypeDelay = 3;
            }
            if (node instanceof ClearNode.EtherNode) {
                // Ticks Between Etherwarps: the next hop goes that many ticks after this one (1 = the next tick).
                // updateDelays counts it down at the start of every tick before canNext reads it, so N here is N ticks.
                globalDelay = LiveMapConfig.getInstance().getEtherwarpTickGap();
            }
            if (clearNodes.isEmpty()) {
                nodes = null;
                position = null;
                compDelay = 2;
                pendingCompletion = onComplete;
                onComplete = null;
                positionPacketSeen = false;
                syncWaitTicks = 0;
                syncDelay = 1;
            }
        }
    }

    /**
     * The hops' body turns, camera held - the helper Auto Routes uses ({@code util/BodyAim}). Every hop's use packet
     * used to carry a rotation the client never reported: the body stayed put and only the packet was rotated, which
     * GrimAC's BadPacketsJ flags on every warp (2026-10-05, 62-argrim-imwarp), the same flag Auto Routes and Auto
     * Puzzles drew and fixed this way. Now the body turns to each hop's look on the hop's own tick (START, ahead of the
     * movement packet that reports it) and is given back to his view the tick after the last hop went out.
     */
    private static final com.killer560.hub.util.BodyAim BODY = new com.killer560.hub.util.BodyAim(() -> { });

    /** True while a hop is queued or about to be: the body stays on the warps' looks until the last use went out. */
    private static boolean aiming() {
        List<ClearNode> current = nodes;
        return pendingInteract != null || (current != null && !current.isEmpty());
    }

    private static void doInteract(Minecraft client) {
        float[] interact = pendingInteract;
        if (interact == null || client.player == null || client.level == null || client.gameMode == null) {
            pendingInteract = null;
            return;
        }
        LocalPlayer player = client.player;
        // A hop that waited a tick is aimed again from where he stands when it goes: it is planned from a landing 1.05 up
        // and he rests 0.05 lower a few ticks later (Auto Routes' reaimIfMoved does the same).
        if (pendingHeldTicks > 0 && pendingBlock != null && pendingFrom != null) {
            Vec3 at = player.position();
            // Only once he is ON the planned spot (resting a little off it). Further off he has not landed client-side
            // yet - a hop goes on from the prediction while the last one is in flight - and the server already has him
            // there, so the planned look is the right one (aimed from the old spot it sent 1015-sim-clickgap's hops off
            // the plan, 2026-10-15).
            if (at.distanceTo(pendingFrom) > 0.05 && at.distanceTo(pendingFrom) < 1.0) {
                Vec3 eye = new Vec3(at.x, at.y + TeleportUtils.eyeHeight(true), at.z);
                TeleportUtils.Rotation rot = TeleportUtils.getEtherwarpDirection(eye, pendingBlock, hopRange() + 1.0);
                if (rot != null) {
                    LOGGER.info("[Path] Warp: held {} tick(s), aimed again from {} ({} off the planned spot) at {}",
                            pendingHeldTicks, fmt(at), String.format(java.util.Locale.ROOT, "%.2f", at.distanceTo(pendingFrom)),
                            pendingBlock.toShortString());
                    interact = new float[]{rot.yaw(), rot.pitch()};
                    pendingInteract = interact;
                    pendingFrom = at;
                } else if (replanHeldHop(at)) {
                    pendingInteract = null;
                    return;
                } else {
                    LOGGER.info("[Path] Warp: held {} tick(s), no ray from {} lands on {} - sending the planned look",
                            pendingHeldTicks, fmt(at), pendingBlock.toShortString());
                    pendingFrom = at;
                }
            }
        }
        // Same direction as the target, expressed relative to the running (unwrapped) yaw.
        float yaw0 = player.getYRot() + Mth.wrapDegrees(interact[0] - player.getYRot());
        float pitch0 = Mth.clamp(interact[1], -90f, 90f);
        // A right click on a block may be waiting on the server for its second half, and the server's look meets a block
        // (util/RightClick): the hop would be taken as that click's end and not warp. The body faces the hop now, so this
        // tick's movement packet tells the server, and it goes next tick; canNext queues nothing behind it meanwhile.
        // From where the server has him: the planned spot, which the client may not have seen him land on yet.
        Vec3 serverFeet = pendingFrom != null && player.position().distanceTo(pendingFrom) >= 1.0 ? pendingFrom : null;
        // The server's ray along this look (from the 1.8 eye) meets a block this client's look cannot reach, so no click
        // could pair the use and it would be dropped: aim at the same block along a look whose rays agree. The turn to it
        // below costs the one tick (killer560, 2026-10-15: "nothing that flags or breaks"); rays that agree cost nothing.
        Vec3 aimFeet = serverFeet != null ? serverFeet : player.position();
        float[] reaim = pendingBlock == null ? null : com.killer560.hub.util.RightClick.reaimIfUnpairable(client, player,
                serverFeet, new Vec3(aimFeet.x, aimFeet.y + TeleportUtils.eyeHeight(true), aimFeet.z), pendingBlock,
                hopRange() + 1.0, yaw0, pitch0, "[Path] Warp");
        if (reaim != null) {
            interact = new float[]{Mth.wrapDegrees(reaim[0]), reaim[1]};
            pendingInteract = interact;
        }
        final float yaw = reaim != null ? reaim[0] : yaw0;
        final float pitch = reaim != null ? reaim[1] : pitch0;
        if (com.killer560.hub.util.RightClick.mustTurnFirst(player, serverFeet, yaw, pitch, "[Path] Warp")) {
            BODY.turn(player, yaw, pitch);
            waitReason = "the server hearing the hop's look first (a block click is unpaired)";
            pendingHeldTicks++;
            return;
        }
        pendingInteract = null;
        pendingHeldTicks = 0;
        // Never a hop from inside a trap room (killer560, 2026-10-06: "it shouldn't be able to etherwarp in trap - just
        // to enter it"): a path may land him in one, but the next hop does not go out from there.
        DungeonLayout layout = DungeonLayout.current();
        int room = layout.roomAtWorld(player.getX(), player.getZ());
        if (room >= 0 && AutoClearUtils.isTrap(layout, room)) {
            LOGGER.warn("[Path] not warping from inside {} - no etherwarp in a trap room", layout.name(room));
            cancel();
            lastPathFailed = true;
            ModChat.send(CHAT, ModChat.bad("Stopped: "), ModChat.text("in a trap room - no etherwarp from inside one"));
            return;
        }
        // The body faces the hop for this tick's movement packet; only the camera stays still (see BODY).
        BODY.turn(player, yaw, pitch);
        int usesBefore = com.killer560.hub.util.RightClick.usesSent();
        try {
            sendHop(client, player, yaw, pitch);
        } finally {
            ownUses += com.killer560.hub.util.RightClick.usesSent() - usesBefore;
        }
    }

    /** The hop's right click itself: the block half when its look meets a block in reach, then the use. */
    private static void sendHop(Minecraft client, LocalPlayer player, float yaw, float pitch) {
        com.killer560.hub.util.RightClick.beforeUse(client, player, yaw, pitch, "[Path] Warp");
        // The same packet in the dungeon sim: its integrated server answers a use packet the way Hypixel's does
        // (roomsim.SimAbilities), resolving the hop from its own copy of him, so hops chain from the prediction
        // one a tick there too.
        hopSendTicks.add(clientTicks);
        if (hopSendTicks.size() > 200) {
            hopSendTicks.remove(0);
        }
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level,
                    sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        } else {
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
    }

    /** The client tick (this class's own count) each hop's use packet went out on, newest last - the testkit measures
     *  the pace with it, beside the packets themselves. */
    private static final List<Long> hopSendTicks = new java.util.ArrayList<>();
    private static long clientTicks;

    public static List<Long> hopSendTicks() {
        return List.copyOf(hopSendTicks);
    }

    private static void updateDelays() {
        // Not while the last hop is still waiting to be sent (util/RightClick): letting go of the forced sneak under it
        // sent the hop as an Instant Transmission, 24 blocks off the plan.
        if (compDelay > 0 && pendingInteract == null && --compDelay == 0) {
            active = false;
            activeNode = null;
        }
        if (globalDelay > 0) {
            globalDelay--;
        }
        if (hypeDelay > 0) {
            hypeDelay--;
        }
    }

    private static boolean canNext() {
        if (syncDelay != 0 || globalDelay > 0 || nodes == null || nodes.isEmpty()) {
            return false;
        }
        if (pendingInteract != null) {
            return false;   // a hop is still waiting to be sent (util/RightClick): never queue another behind it
        }
        if (compDelay == 0) {
            active = false;
        }
        return true;
    }

    /** Without the keyboard-input mixin (mixin config not loaded) hold the sneak key mapping instead. */
    private static void applySneakFallback(Minecraft client) {
        if (sneakMixinApplied) {
            return;
        }
        boolean want = forceSneakNow() || (nodes != null && !nodes.isEmpty() && nodes.get(0) instanceof ClearNode.EtherNode);
        if (want) {
            client.options.keyShift.setDown(true);
            forcedSneakKey = true;
        } else if (forcedSneakKey) {
            forcedSneakKey = false;
            client.options.keyShift.setDown(false);
        }
    }

    // ------------------------------------------------------------------------------------------- items

    static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.getStringOr("id", null);
    }

    static boolean holdingAny(String[] ids) {
        LocalPlayer player = Minecraft.getInstance().player;
        String id = player == null ? null : skyblockId(player.getMainHandItem());
        if (id == null) {
            return false;
        }
        for (String s : ids) {
            if (s.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** QUOI {@code SwapManager.swapById}: hotbar only, at most one swap per tick. */
    static boolean swapById(String[] ids) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        for (int i = 0; i <= 8; i++) {
            String id = skyblockId(player.getInventory().getItem(i));
            if (id == null) {
                continue;
            }
            for (String s : ids) {
                if (s.equalsIgnoreCase(id)) {
                    if (player.getInventory().getSelectedSlot() == i) {
                        return true;
                    }
                    if (hasSwappedThisTick) {
                        return false;
                    }
                    player.getInventory().setSelectedSlot(i);
                    // Through vanilla's own ensureHasSentCarriedItem, as Auto Routes' RouteExecutor.select does, so
                    // the game mode's carriedIndex agrees and its tick() does not send the same slot a second time.
                    // Sent by hand, every swap went out twice - a same-slot repeat no vanilla client sends.
                    if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
                        invoker.killer560smod$invokeEnsureHasSentCarriedItem();
                    } else {
                        player.connection.send(new ServerboundSetCarriedItemPacket(i));
                    }
                    hasSwappedThisTick = true;
                    return true;
                }
            }
        }
        ModChat.send(CHAT, ModChat.bad("Could not find "), ModChat.value(String.join(", ", ids)));
        return false;
    }
}
