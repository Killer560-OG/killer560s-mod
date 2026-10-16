package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.util.ModChat;
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
 */
final class RewardChest {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final double REACH = 4.4;
    /** Ticks to keep looking for a chest after the solve before saying there is none. */
    private static final int LOOK_TICKS = 200;
    /** Ticks after a click with no chest opening before it is clicked again. Server round trip, not a safety wait. */
    private static final int RETRY_TICKS = 10;
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
    private int hops = 0;
    private String lastNote = "";
    /** The last {@link #pick} could not see the whole chest box: part of it is not loaded on the client. */
    private boolean unloaded = false;

    /**
     * @param anchorRel room-relative {x, y, z} of where the reward chest is (the nearest chest to it is taken first)
     * @param boxRel    room-relative {minX, minY, minZ, maxX, maxY, maxZ} searched for chests
     * @param maxChests chests to open, nearest the anchor first
     */
    RewardChest(String tag, int[] anchorRel, int[] boxRel, int maxChests) {
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

    /** How many reward chests this run opened. */
    int openedCount() {
        return opened.size();
    }

    /** The puzzle is solved: start claiming. Idempotent. */
    void start() {
        if (active || finished) {
            return;
        }
        active = true;
        ticks = 0;
        LOGGER.info("[AutoPuzzles] {}: solved - claiming the reward chest", tag);
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
        hops = 0;
        lastNote = "";
        unloaded = false;
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
        if (McCompat.screen(client) != null) {
            if (clickedAwaitingScreen && target != null) {
                done(target, "its window opened");
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
            return;
        }
        if (target == null) {
            target = pick(client, cr);
            if (target == null) {
                if (unloaded) {
                    // Unknown, not "none": a chunk the client does not have reads as air. Never give up on that.
                    note("part of the chest area is not loaded yet - waiting for it");
                } else if (ticks > LOOK_TICKS) {
                    finish(opened.isEmpty() ? "no reward chest appeared in the room in " + LOOK_TICKS / 20 + " s"
                            : "no other reward chest");
                } else {
                    note("waiting for the reward chest to appear");
                }
                return;
            }
            clicks = 0;
            hops = 0;
            sinceClick = RETRY_TICKS;
            warpTried.clear();
            LOGGER.info("[AutoPuzzles] {}: reward chest at {} (relative {})", tag, AutoPuzzleUtil.fmt(target),
                    AutoPuzzleUtil.fmt(PuzzleCoords.relative(target, cr)));
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
        if (sinceClick < RETRY_TICKS) {
            return; // a click went out; the open lid or the window is what moves this on
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
        if (clicks >= MAX_CLICKS) {
            LOGGER.info("[AutoPuzzles] {}: the reward chest at {} did not open after {} click(s)", tag,
                    AutoPuzzleUtil.fmt(target), clicks);
            opened.add(target); // not again
            target = null;
            return;
        }
        if (player.isShiftKeyDown()) {
            AutoReposition.releaseSneak(client);
            if (client.options.keyShift.isDown()) {
                note("sneaking - a sneak-click would use the held item, not open the chest");
            }
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, target)) {
            return;
        }
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            opened.add(target);
            target = null;
            return;
        }
        clicks++;
        sinceClick = 0;
        clickedAwaitingScreen = true;
        LOGGER.info("[AutoPuzzles] {}: clicked the reward chest at {} ({} from his eye, click {})", tag,
                AutoPuzzleUtil.fmt(target), String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)), clicks);
    }

    private void done(BlockPos chest, String why) {
        camera.release(Minecraft.getInstance().player);
        opened.add(chest);
        clickedAwaitingScreen = false;
        LOGGER.info("[AutoPuzzles] {}: reward chest at {} opened ({})", tag, AutoPuzzleUtil.fmt(chest), why);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text(tag + ": "), ModChat.good("reward chest"),
                ModChat.text(" opened."));
        target = null;
        if (opened.size() >= maxChests) {
            finish("done");
        }
    }

    private void finish(String why) {
        finished = true;
        Minecraft client = Minecraft.getInstance();
        camera.release(client.player);
        LOGGER.info("[AutoPuzzles] {}: reward chest step over - {}", tag, why);
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
