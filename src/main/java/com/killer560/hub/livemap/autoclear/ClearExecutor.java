package com.killer560.hub.livemap.autoclear;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.util.ModChat;
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
        return t;
    });

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

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ClearExecutor.onTickStart", ClearExecutor::onTickStart));
        // START: this sends the interactions at the end of a walk leg. Named onTickEnd from when it ran at
        // the end of the tick; the name is left alone so every reference to it keeps working.
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ClearExecutor.onTickEnd", ClearExecutor::onTickEnd));
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
        etherPath(to, -1, complete);
    }

    /**
     * A map click on a tile: fewest warps to ANY landing in that tile at its floor height (see
     * {@link EtherwarpPathfinder#findDungeonPathToTile}); {@code to} is the tile's own block, used if none of
     * them can be reached.
     */
    public static void etherPathToTile(BlockPos to, int tileIdx, Runnable complete) {
        etherPath(to, tileIdx, complete);
    }

    private static void etherPath(BlockPos to, int tileIdx, Runnable complete) {
        if (pathPending || replanPending) {
            return;
        }
        replans = 0;
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
        goalTo = to;
        goalTile = tileIdx;
        goalComplete = complete;
        Vec3 from = player.position();
        DungeonLayout layout = DungeonLayout.capture();
        EtherwarpPathfinder.PathConfig cfg = pathConfig();
        // The planner's hop range. One number in the sim and on Hypixel: the sim's server gives an etherwarp
        // 57 blocks plus one per Transmission Tuner, as Hypixel does - see hopRange().
        double hopRange = hopRange();
        int gen = generation;
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
                        : EtherwarpPathfinder.findDungeonPath(from, to, cfg, hopRange, layout);
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
                LOGGER.info("[Path] running {} warp(s) from {} to {}{}", list.size(), fmt(from), to,
                        replans > 0 ? " (planned again, " + replans + "/" + MAX_REPLANS + ")" : "");
                startQueue(list, complete);
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
        replanPending = false;
        startQueue(path, complete);
    }

    private static void startQueue(List<ClearNode> path, Runnable complete) {
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

    /** Lets another feature run its own hop queue here while the Interactive Map's own toggles are off. */
    public static void setExternalOwner(boolean value) {
        externalOwner = value;
    }

    public static void queueInteract(float yaw, float pitch) {
        pendingInteract = new float[]{yaw, pitch};
    }

    public static void cancel() {
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
        positionPacketSeen = true;
        positionPackets++;
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
        hasSwappedThisTick = false;
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
            EtherwarpPathfinder.tickWarm(PLANNER, () -> pathPending, hopRange());
        }
        // Before this tick's hop: the body is given back once the path has no hop left to send.
        BODY.tick(client.player, aiming());
        doInteract(client);
        updateDelays();
        applySneakFallback(client);
        tickPathSearch();
        tickReplan(client);
        if (!checkLandings(client)) {
            return;
        }
        if (!canNext()) {
            return;
        }
        if (issued.size() - confirmed >= MAX_LEAD) {
            waitReason = "the server did not answer " + (issued.size() - confirmed) + " warp(s)";
            return;
        }
        if (position == null) {
            Vec3 p = client.player.position();
            position = new double[]{p.x, p.y, p.z};
        }
        handleQueue(position, nodes);
    }

    /** QUOI's server-tick handler, on client ticks: after the position packet, wait ~8 ticks, then complete. */
    private static void onTickEnd(Minecraft client) {
        // Every warp of ours answered (checkLandings), or with none issued the old position-packet signal. The
        // 40-tick fallback stays for a server that sends nothing at all; checkLandings gives up on an unanswered
        // warp after STALL_TICKS, well before it.
        boolean answered = issued.isEmpty() ? positionPacketSeen : confirmed >= issued.size();
        if (syncDelay == 1 && (answered || ++syncWaitTicks > 40)) {
            syncDelay = 2;
            syncWaitTicks = 0;
        }
        if (syncDelay < 2) {
            return;
        }
        if (syncDelay++ > 9) {
            syncDelay = 0;
            Runnable callback = pendingCompletion;
            pendingCompletion = null;
            Vec3 expected = issued.isEmpty() ? null : issued.get(issued.size() - 1);
            Vec3 at = client.player == null ? null : client.player.position();
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
            if (expected != null) {
                arrivalSeq++;
                arrivalMs = System.currentTimeMillis();
                arrivalPos = at;
            }
            if (callback != null) {
                callback.run();
            }
        }
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
        boolean running = (nodes != null && !nodes.isEmpty()) || (syncDelay == 1 && confirmed < issued.size());
        if (!running || client.player == null) {
            positionPacketsSeen = positionPackets;
            return true;
        }
        Vec3 at = client.player.position();
        int packets = positionPackets;
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
        ticksSinceProgress++;
        boolean offStart = issued.isEmpty() && waitReason != null && waitReason.startsWith("not on");
        if (offStart && ticksSinceProgress > OFF_START_TICKS && client.player.onGround()) {
            offPath(waitReason, null);
            return false;
        }
        if (ticksSinceProgress > STALL_TICKS) {
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
        if (++pathPendingTicks > PATH_SEARCH_LIMIT_TICKS) {
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
        nodes = null;
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
        if (node.execute(playerPos)) {
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
        pendingInteract = null;
        if (interact == null || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }
        LocalPlayer player = client.player;
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
        // Same direction as the target, expressed relative to the running (unwrapped) yaw.
        float yaw = player.getYRot() + Mth.wrapDegrees(interact[0] - player.getYRot());
        float pitch = Mth.clamp(interact[1], -90f, 90f);
        // The body faces the hop for this tick's movement packet; only the camera stays still (see BODY).
        BODY.turn(player, yaw, pitch);
        // The same packet in the dungeon sim: its integrated server answers a use packet the way Hypixel's does
        // (roomsim.SimAbilities), resolving the hop from its own copy of him, so hops chain from the prediction
        // one a tick there too.
        if (client.gameMode instanceof MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeStartPrediction(client.level,
                    sequence -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
        } else {
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
    }

    private static void updateDelays() {
        if (compDelay > 0 && --compDelay == 0) {
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
