package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens a puzzle's reward chest once the auto has solved it - the step Auto Water Board, Auto Ice Path and Auto Ice Fill
 * never took (killer560, 2026-10-15: "Auto ice path and auto water board do not claim there chest once it finishes.
 * Same with ice fill").
 *
 * <p>Why nothing took it before: Secret Aura skips Ice Path and Ice Fill outright ({@code SKIP_ROOMS}), and in Water
 * Board it waits for the puzzle to read solved on the tab list or the room to read cleared on the map
 * ({@code CHESTS_AFTER_SOLVE_ROOMS}) - so each of these autos ended standing next to a chest nobody would click. Here
 * the auto clicks it itself, whatever Secret Aura is set to: the nearest chest block in the room to where the puzzle
 * puts its reward ({@code anchorRel}), opened from where he stands when it is in reach, else after one etherwarp onto
 * a block that has it in reach. Every block it finds that way is clicked once each (Ice Fill holds two).
 *
 * <p>Nothing here waits on a clock. The chest is looked for every tick until it is there (a reward chest appears on
 * the solve, a tick or two after the last move); a click is retried only when the chest did not open after it. The body
 * is turned onto the chest the tick it becomes clickable (a landing, or in reach) and the click goes the next tick.
 *
 * <p>A click worked when the server's chest WINDOW arrives ({@link com.killer560.hub.util.ChestWindows}), whether or not
 * a screen shows: with Auto Close Chest on the packet is canceled and answered with a close, so no screen opens and the
 * lid is open for a tick at most. Reading only the screen and the lid, Auto Ice Path took his claimed chest for an
 * unopened one, clicked twice more and then waited half a minute for a chest to "appear" (killer560, 2026-10-15, "it
 * gets stuck on ice fill after grabbing the chest"). A click with no answer is retried once the answer is overdue - his
 * measured round trip plus any server stall, and the server's ack of the click in, not a fixed wait - and once a chest
 * has been found, finding no other ends the step at once.
 */
final class RewardChest {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final double REACH = 4.4;
    /** Ticks to keep looking for a chest after the solve before saying there is none. */
    private static final int LOOK_TICKS = 200;
    /** Ticks past the measured round trip before a click with no window counts as unanswered: the tick the click
     *  goes out and the tick the answer is read. */
    private static final int ANSWER_SLACK_TICKS = 2;
    /** Round trips a click is given when the server never acks it (every vanilla server does). */
    private static final int NO_ACK_ROUND_TRIPS = 4;
    private static final int MAX_CLICKS = 3;

    private final String tag;
    private final FreeCam camera;
    private final AutoReposition warp;
    private final int[] anchorRel;
    private final int[] boxRel;
    private final int maxChests;

    private boolean active = false;
    private boolean finished = false;
    private int ticks = 0;
    private int sinceClick = 0;
    private int clicks = 0;
    private BlockPos target = null;
    private final Set<BlockPos> opened = new HashSet<>();
    private final Set<BlockPos> warpTried = new HashSet<>();
    private boolean clickedAwaitingScreen = false;
    /** {@link com.killer560.hub.util.ChestWindows#count} as the last click went out. */
    private int windowMark = 0;
    /** The last click's prediction sequence, which the server acks once it has handled it. */
    private int clickSeq = -1;
    /** A chest was found this run - so "no chest" from now on means none left, not "not there yet". */
    private boolean everFound = false;
    private int hops = 0;
    private String lastNote = "";
    /** The last {@link #pick} could not see the whole chest box: part of it is not loaded on the client. */
    private boolean unloaded = false;
    /** Logging only: the tick claiming began. */
    private long startTick = 0L;
    /** Logging only: the click count whose "clicking again" line went out. */
    private int retryLoggedFor = -1;

    private long elapsed() {
        return com.killer560.hub.util.SessionRecorderSink.tick() - startTick;
    }

    /**
     * @param anchorRel room-relative {x, y, z} of where the reward chest is (the nearest chest to it is taken first)
     * @param boxRel    room-relative {minX, minY, minZ, maxX, maxY, maxZ} searched for chests
     * @param maxChests chests to open, nearest the anchor first
     */
    RewardChest(String tag, int[] anchorRel, int[] boxRel, int maxChests) {
        this(tag, anchorRel, boxRel, maxChests, 0);
    }

    /**
     * Ticks between clicks on a chest that has not opened yet, with no cap on the number of clicks; 0 for the default
     * (a click retried only once its answer is overdue, at most {@link #MAX_CLICKS}). Water Board uses 4 (killer560,
     * 2026-10-19: "waterboard should keep clicking that secret every 4 ticks until it gets it") - its chest stays locked
     * until the water has run, so three answered clicks used to end the step with the chest still shut.
     */
    private final int retryEvery;

    RewardChest(String tag, int[] anchorRel, int[] boxRel, int maxChests, int retryEvery) {
        this.retryEvery = retryEvery;
        this.tag = tag;
        this.camera = new FreeCam(tag + " chest");
        this.warp = new AutoReposition(tag + " chest", camera);
        this.anchorRel = anchorRel;
        this.boxRel = boxRel;
        this.maxChests = maxChests;
    }

    boolean isActive() {
        return active && !finished;
    }

    boolean isFinished() {
        return finished;
    }

    /** The puzzle is solved: start claiming. Idempotent. */
    void start() {
        if (active || finished) {
            return;
        }
        active = true;
        ticks = 0;
        startTick = com.killer560.hub.util.SessionRecorderSink.tick();
        LOGGER.info("[AutoPuzzles] {}: solved - claiming the reward chest (max {} chest(s), retryEvery={}) tick={}", tag,
                maxChests, retryEvery, startTick);
    }

    void reset(Minecraft client) {
        if (warp.isActive()) {
            warp.cancel(client);
        }
        camera.release(client.player);
        active = false;
        finished = false;
        ticks = 0;
        sinceClick = 0;
        clicks = 0;
        target = null;
        opened.clear();
        warpTried.clear();
        clickedAwaitingScreen = false;
        windowMark = 0;
        everFound = false;
        hops = 0;
        lastNote = "";
        unloaded = false;
        retryLoggedFor = -1;
    }

    private void note(String what) {
        if (!what.equals(lastNote)) {
            lastNote = what;
            LOGGER.info("[AutoPuzzles] {}: {}", tag, what);
        }
    }

    /** One tick; call every tick while {@link #isActive()}. */
    void tick(Minecraft client) {
        if (!isActive()) {
            return;
        }
        LocalPlayer player = client.player;
        ticks++;
        sinceClick++;
        AutoPuzzleActivity.note(tag);
        if (clickedAwaitingScreen && target != null && com.killer560.hub.util.ChestWindows.since(windowMark)) {
            done(target, McCompat.screen(client) != null ? "its window opened" : "its window arrived (not shown)");
            return;
        }
        if (McCompat.screen(client) != null) {
            if (clickedAwaitingScreen && target != null) {
                done(target, "its window opened");
            } else {
                note("a screen is open - waiting");
            }
            return;
        }
        if (target != null && isOpen(client, target)) {
            done(target, "its lid is open");
            return;
        }
        camera.keep(player);
        boolean landed = false;
        if (warp.isActive()) {
            warp.tick(client);
            if (warp.isActive()) {
                return;
            }
            // Landed this tick (or the warp ended): the camera stays held while the body turns onto the chest below, so
            // the click goes the very next tick; it is handed back once the chest is open.
            landed = true;
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null || client.level == null) {
            note("the room's rotation is not known - waiting");
            return;
        }
        if (target == null) {
            target = pick(client, cr);
            if (target == null) {
                if (unloaded) {
                    // Unknown, not "none": a chunk the client does not have reads as air. Never give up on that.
                    note("part of the chest area is not loaded yet - waiting for it");
                } else if (everFound || !opened.isEmpty()) {
                    // A chest was there and is dealt with (opened, or given up on): nothing more will appear.
                    finish("no other reward chest");
                } else if (ticks > LOOK_TICKS) {
                    finish("no reward chest appeared in the room in " + LOOK_TICKS / 20 + " s");
                } else {
                    note("waiting for the reward chest to appear");
                }
                return;
            }
            everFound = true;
            retryLoggedFor = -1;
            clicks = 0;
            hops = 0;
            sinceClick = Integer.MAX_VALUE / 2;
            warpTried.clear();
            LOGGER.info("[AutoPuzzles] {}: reward chest at {} (relative {}) - the unopened chest nearest the anchor"
                            + " (relative {}); {} already dealt with; boxDist={} elapsedTicks={}", tag,
                    AutoPuzzleUtil.fmt(target), AutoPuzzleUtil.fmt(PuzzleCoords.relative(target, cr)),
                    anchorRel[0] + ", " + anchorRel[1] + ", " + anchorRel[2], opened.size(),
                    String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(com.killer560.hub.util.BlockHits
                            .boxDistanceSq(player.getEyePosition(), target))), elapsed());
        }
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (distSq > AutoPuzzleUtil.BLOCK_REACH_SQ) {
            if (!AutoPuzzlesConfig.getInstance().isAutoPuzzlePathingEnabled()) {
                note("the reward chest is out of reach and auto-puzzle pathing is off");
                return;
            }
            for (BlockPos spot : AutoPuzzleUtil.reachSpots(client, List.of(target), REACH, false, player.position())) {
                if (warpTried.contains(spot) || AutoPuzzleUtil.etherwarpAim(client.level, player, spot) == null) {
                    continue;
                }
                warpTried.add(spot);
                if (warp.start(client, spot, false, false, false)) {
                    note("warping onto " + AutoPuzzleUtil.fmt(spot) + " for the reward chest");
                    return;
                }
            }
            if (hops < 2) {
                BlockPos hop = AutoPuzzleUtil.hopToward(client, player,
                        AutoPuzzleUtil.reachSpots(client, List.of(target), REACH, false, player.position()));
                if (hop != null && !AutoPuzzleUtil.at(player, hop) && warp.start(client, hop, false, false, false)) {
                    hops++;
                    note("no warp line onto a spot by the reward chest - warping to " + AutoPuzzleUtil.fmt(hop) + " first");
                    return;
                }
            }
            if (!com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
                List<BlockPos> spots = AutoPuzzleUtil.reachSpots(client, List.of(target), REACH, false, player.position());
                if (!spots.isEmpty() && AutoPuzzleUtil.pathIfMapOn(spots.get(0), null, true)) {
                    note("walking to " + AutoPuzzleUtil.fmt(spots.get(0)) + " for the reward chest");
                    return;
                }
            }
            note("the reward chest is out of reach and no spot in reach of it can be got to");
            return;
        }
        if (retryEvery > 0) {
            if (clicks > 0 && sinceClick < retryEvery) {
                LOGGER.debug("[AutoPuzzles] {}: next click in {} tick(s)", tag, retryEvery - sinceClick);
                return; // the next click goes exactly retryEvery ticks after the last; the window or the lid ends it
            }
        } else if (clicks > 0 && !unanswered()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[AutoPuzzles] {}: click {} answer not due yet ({} tick(s) since, due after {})", tag,
                        clicks, sinceClick, answerTicks());
            }
            return; // a click went out and its answer is not due yet; the window or the lid is what moves this on
        }
        if (clicks > 0 && retryLoggedFor != clicks) {
            retryLoggedFor = clicks;
            LOGGER.info("[AutoPuzzles] {}: click {} on {} unanswered after {} tick(s) (answer due {}, acked={}) -"
                            + " clicking again", tag, clicks, AutoPuzzleUtil.fmt(target), sinceClick, answerTicks(),
                    com.killer560.hub.util.ChestWindows.acked(clickSeq));
        }
        // No turn tick of the click's own and no camera wait (killer560, 2026-10-15: no fixed waits): the body is turned
        // onto the chest in the tick it becomes clickable - the landing, or the first tick it is in reach - under this
        // step's free camera, and the click goes the next tick, once the movement packet has reported the look and the
        // landing's sneak is let go. Before, the landing waited 2 ticks for the sneak and then TurnFirst turned it.
        if (landed || !AutoPuzzleUtil.aimedAt(player, target)) {
            if (AutoPuzzleUtil.aimBodyAt(player, target, camera) || landed) {
                if (player.isShiftKeyDown()) {
                    AutoReposition.releaseSneak(client);
                }
                return;
            }
        }
        if (retryEvery <= 0 && clicks >= MAX_CLICKS) {
            LOGGER.info("[AutoPuzzles] {}: the reward chest at {} did not open after {} click(s) - giving up on it"
                    + " elapsedTicks={}", tag, AutoPuzzleUtil.fmt(target), clicks, elapsed());
            opened.add(target); // not again
            target = null;
            return;
        }
        if (player.isShiftKeyDown()) {
            AutoReposition.releaseSneak(client);
            if (client.options.keyShift.isDown()) {
                note("sneaking - a sneak-click would use the held item, not open the chest");
            } else {
                note("sneak let go before the click (click next tick)");
            }
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, target)) {
            return;
        }
        // Marked BEFORE the click goes: the integrated server can answer on its own thread before the next line runs.
        int mark = com.killer560.hub.util.ChestWindows.count();
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.info("[AutoPuzzles] {}: the reward chest at {} has no clickable shape - skipped elapsedTicks={}", tag,
                    AutoPuzzleUtil.fmt(target), elapsed());
            opened.add(target);
            target = null;
            return;
        }
        clicks++;
        sinceClick = 0;
        clickedAwaitingScreen = true;
        windowMark = mark;
        clickSeq = com.killer560.hub.util.RightClick.lastUseOnSequence();
        LOGGER.info("[AutoPuzzles] {}: clicked the reward chest at {} ({} from his eye, click {}{}) seq={}"
                        + " elapsedTicks={}", tag, AutoPuzzleUtil.fmt(target),
                String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)), clicks,
                retryEvery > 0 ? ", next in " + retryEvery + " tick(s) until it opens" : "", clickSeq, elapsed());
    }

    /**
     * The last click went unanswered: its round trip is over ({@link #answerTicks}) AND the server has acked it with no
     * window since. The ack is what keeps a busy server from being clicked twice: a server handles a click and sends its
     * window before it acks it, so while the ack is not in the server simply has not got to the click yet - a server
     * stalled past his ping is waited for rather than clicked twice. A server that sends no ack at all is given
     * {@link #NO_ACK_ROUND_TRIPS} round trips.
     */
    private boolean unanswered() {
        int due = answerTicks();
        if (sinceClick <= due) {
            return false;
        }
        return com.killer560.hub.util.ChestWindows.acked(clickSeq) || sinceClick > due * NO_ACK_ROUND_TRIPS;
    }

    /** Ticks a click's window takes to come back: his measured round trip, any server stall in progress, and the slack. */
    private static int answerTicks() {
        int ping = Math.max(0, com.killer560.hub.util.PingTracker.automationMs());
        return (ping + 49) / 50 + com.killer560.hub.util.ServerLag.expectedDelayTicks() + ANSWER_SLACK_TICKS;
    }

    private void done(BlockPos chest, String why) {
        camera.release(Minecraft.getInstance().player);
        opened.add(chest);
        clickedAwaitingScreen = false;
        LOGGER.info("[AutoPuzzles] {}: reward chest at {} opened ({}) after {} click(s), {} tick(s) since the last;"
                        + " {} of {} done elapsedTicks={}", tag, AutoPuzzleUtil.fmt(chest), why, clicks, sinceClick,
                opened.size(), maxChests, elapsed());
        target = null;
        if (opened.size() >= maxChests) {
            finish("done");
        }
    }

    private void finish(String why) {
        finished = true;
        Minecraft client = Minecraft.getInstance();
        camera.release(client.player);
        AutoPuzzleActivity.ended();
        LOGGER.info("[AutoPuzzles] {}: reward chest step over - {} ({} opened) elapsedTicks={}", tag, why,
                opened.size(), elapsed());
    }

    private static boolean isOpen(Minecraft client, BlockPos pos) {
        if (com.killer560.hub.cheatutils.SecretAuraFeature.isDone(pos)) {
            return true;
        }
        BlockEntity be = client.level.getBlockEntity(pos);
        return be instanceof ChestBlockEntity chest && chest.getOpenNess(0f) > 0f;
    }

    /** The unopened chest nearest the anchor, or null. */
    private BlockPos pick(Minecraft client, int[] cr) {
        List<BlockPos> found = AutoPuzzleUtil.chestsInRoom(client.level, cr, boxRel[0], boxRel[1],
                boxRel[2], boxRel[3], boxRel[4], boxRel[5]);
        if (found == null) {
            unloaded = true;
            return null;
        }
        unloaded = false;
        List<BlockPos> chests = new ArrayList<>(found);
        Vec3 anchor = Vec3.atCenterOf(PuzzleCoords.real(anchorRel[0], anchorRel[1], anchorRel[2], cr));
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : chests) {
            if (opened.contains(c) || isOpen(client, c)) {
                continue;
            }
            double d = Vec3.atCenterOf(c).distanceToSqr(anchor);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }
}
