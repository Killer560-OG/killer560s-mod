package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverConfig;
import com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Auto Teleport Maze - port of QUOI {@code TeleportMazeSolver.kt}'s {@code auto}, which IS movement automation: once
 * the first maze teleport has happened (you step on the start pad yourself), one tick after every maze teleport it
 * picks the pad to use in the current cell (QUOI {@code getPad}: the solver's best pad if unvisited, else the single
 * remaining candidate if it's in this cell, else an unvisited pad diagonal to the current one, else the farthest
 * unvisited), turns the camera to face that pad's centre and holds the forward key until the next teleport. Nothing
 * beyond QUOI: no pathing, jumping or strafing. Stops when a screen opens, when there is no pad to go to (the end),
 * when you leave the room, and (safety addition) after 3 seconds of walking without a teleport.
 * <p>
 * killer560, 2026-09-27: "For auto tp maze make it also aura the chest once it detects it stepped on the final
 * correct pad, then have it walk to the exit pad and walk out of the room as well." {@link TeleportMazeSolverFeature}
 * tracks exactly two pads outside every cell - "end" (relative 15,69,14) and "start" (relative 15,69,12), the same
 * fixed layout QUOI's own solver uses - so "the final correct pad" is detected the moment {@code getPad} would
 * normally report "no pad to go to" (the existing stop condition) AND the landing pad is that "end" pad specifically.
 * From there this walks (plain forward-key + camera turn, same primitive already used above for pad-to-pad hops -
 * both "end" and "start" sit on the maze's own starting platform, not across a void gap, so no etherwarp/pathfinder
 * is needed or appropriate here - {@code AutoClearUtils.canPath} already refuses to path ANY "Maze" room for exactly
 * that reason) onto the "start" pad (the room's exit pad), auras the nearest secret chest there with its own
 * deliberate interact (not {@code SecretAuraFeature} - works even with Secret Aura off), then keeps walking to this
 * room's own doorway-side spot ({@link AutoClearUtils#roomOverride}, the same one "Interactive Map" uses to walk a
 * player INTO this room) so an etherwarp can be used again right after.
 */
final class AutoTeleportMaze {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Teleport Maze";
    private static final long WALK_TIMEOUT_MS = 3000L;
    private static final long FINISH_WALK_TIMEOUT_MS = 5000L;
    /** The measured block reach, squared - was 36.0 (6.0 blocks) measured to the centre. */
    private static final double AURA_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final double ARRIVE_SQ = 2.25; // 1.5 blocks - "close enough", same feel as QUOI's own pad hops
    private static final int MAX_AURA_ATTEMPTS = 3;
    // Same fixed relative layout TeleportMazeSolverFeature's own PADS array carries ("end"/"start", the two pads
    // outside every cell) - copied, not re-derived, so this can never disagree with what that solver detected.
    private static final int[] END_PAD_RELATIVE = {15, 69, 14};
    private static final int[] START_PAD_RELATIVE = {15, 69, 12};

    private static final AutoGuard GUARD = new AutoGuard("Auto Teleport Maze", "Teleport Maze Solver");

    private static int lastSeq = -1;
    private static int pendingTicks = -1;
    private static boolean walking = false;
    private static long walkStartMs = 0L;
    private static boolean wasInRoom = false;

    private enum FinishStage { NONE, AURA_CHEST, WALK_TO_EXIT_PAD, WALK_OUT, DONE }

    private static FinishStage finishStage = FinishStage.NONE;
    private static long finishStageStartMs = 0L;
    private static int auraAttempts = 0;

    private AutoTeleportMaze() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        Set<BlockPos> visited = TeleportMazeSolverFeature.getVisited();
        GUARD.observe(visited.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoTeleportMazeEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        if (!wasInRoom) {
            lastSeq = TeleportMazeSolverFeature.getTeleportSeq(); // only react to teleports made while in the room
        }
        wasInRoom = true;

        if (finishStage != FinishStage.NONE) {
            tickFinish(client);
            return;
        }

        if (!GUARD.solverOn(TeleportMazeSolverConfig.getInstance().isEnabled()) || !GUARD.fresh() || visited.isEmpty()) {
            stop(client);
            return;
        }
        if (client.screen != null) {
            stop(client);
            pendingTicks = -1;
            return;
        }
        int seq = TeleportMazeSolverFeature.getTeleportSeq();
        if (seq != lastSeq) {
            lastSeq = seq;
            stop(client);
            pendingTicks = 1; // QUOI: Scheduler.scheduleTask { nextMove = true }
            return;
        }
        LocalPlayer player = client.player;
        if (pendingTicks > 0 && --pendingTicks == 0) {
            pendingTicks = -1;
            BlockPos target = getPad(player.position());
            if (target != null) {
                float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(target));
                AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
                client.options.keyUp.setDown(true);
                walking = true;
                walkStartMs = System.currentTimeMillis();
                LOGGER.info("[AutoPuzzles] TeleportMaze: walking to pad {}", target);
            } else {
                stop(client);
                if (isAtEndPad(player.position())) {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: landed on the end pad - maze solved, starting finish sequence");
                    finishStage = FinishStage.AURA_CHEST;
                    finishStageStartMs = System.currentTimeMillis();
                    auraAttempts = 0;
                } else {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: no pad to walk to - stopped");
                }
            }
        } else if (walking) {
            if (System.currentTimeMillis() - walkStartMs > WALK_TIMEOUT_MS) {
                LOGGER.info("[AutoPuzzles] TeleportMaze: no teleport after {}ms of walking - stopped", WALK_TIMEOUT_MS);
                stop(client);
            } else {
                client.options.keyUp.setDown(true);
            }
        }
    }

    private static boolean isAtEndPad(Vec3 pos) {
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            return false;
        }
        BlockPos endReal = PuzzleCoords.real(END_PAD_RELATIVE[0], END_PAD_RELATIVE[1], END_PAD_RELATIVE[2], cr);
        BlockPos landed = TeleportMazeSolverFeature.nearestPad(pos);
        return endReal.equals(landed);
    }

    /** Aura the chest, walk to the exit pad, then walk out - see this class's own doc for why a plain walk (not
     *  an etherwarp path) is both safe and correct here. */
    private static void tickFinish(Minecraft client) {
        if (client.screen != null) {
            return;
        }
        LocalPlayer player = client.player;
        switch (finishStage) {
            case AURA_CHEST -> auraChest(client, player);
            case WALK_TO_EXIT_PAD -> {
                int[] cr = LiveMapFeature.currentRoomClayAndRotation();
                BlockPos exitPad = cr == null ? null
                        : PuzzleCoords.real(START_PAD_RELATIVE[0], START_PAD_RELATIVE[1], START_PAD_RELATIVE[2], cr);
                walkFinishLeg(client, player, exitPad, FinishStage.WALK_OUT, "the exit pad");
            }
            case WALK_OUT -> {
                int[] cr = LiveMapFeature.currentRoomClayAndRotation();
                int[] outsideRel = AutoClearUtils.roomOverride(ROOM);
                BlockPos outside = cr == null || outsideRel == null ? null
                        : PuzzleCoords.real(outsideRel[0], outsideRel[1], outsideRel[2], cr);
                boolean done = walkFinishLeg(client, player, outside, FinishStage.DONE, "the door");
                if (done && finishStage == FinishStage.DONE) {
                    LOGGER.info("[AutoPuzzles] TeleportMaze: walked out of the room - done");
                    ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Teleport Maze: "), ModChat.good("done"),
                            ModChat.text("."));
                }
            }
            default -> {
            }
        }
    }

    private static void auraChest(Minecraft client, LocalPlayer player) {
        if (auraAttempts >= MAX_AURA_ATTEMPTS) {
            LOGGER.info("[AutoPuzzles] TeleportMaze: no chest found near the end pad after {} attempts - continuing",
                    MAX_AURA_ATTEMPTS);
            finishStage = FinishStage.WALK_TO_EXIT_PAD;
            finishStageStartMs = System.currentTimeMillis();
            return;
        }
        BlockPos target = AutoPuzzleUtil.nearestChest(client, player, AURA_REACH_SQ);
        if (target == null) {
            auraAttempts++;
            return;
        }
        // To the box, like the picker above - measuring the gate one way and the choice another is how a
        // module ends up clicking at something it cannot reach.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (player.isShiftKeyDown() || distSq > AURA_REACH_SQ) {
            auraAttempts++;
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - not burnt, retried next tick
        }
        auraAttempts++;
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.warn("[AutoPuzzles] TeleportMaze: no clickable shape at {} (attempt {}/{})", target, auraAttempts, MAX_AURA_ATTEMPTS);
            return;
        }
        LOGGER.info("[AutoPuzzles] TeleportMaze: aura'd chest at {}", target);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Teleport Maze: aura'd the "), ModChat.good("secret chest"),
                ModChat.text("."));
        finishStage = FinishStage.WALK_TO_EXIT_PAD;
        finishStageStartMs = System.currentTimeMillis();
    }

    /** Rotates the camera towards {@code target} and holds forward until close, same primitive as the normal
     *  pad-to-pad walk above. @return true once this leg is over (arrived, no known target, or timed out) -
     *  callers must check {@code finishStage} afterward since a timeout still advances it. */
    private static boolean walkFinishLeg(Minecraft client, LocalPlayer player, BlockPos target, FinishStage nextStage, String label) {
        if (target == null) {
            finishStage = nextStage;
            finishStageStartMs = System.currentTimeMillis();
            client.options.keyUp.setDown(false);
            walking = false;
            return true;
        }
        double distSq = player.position().distanceToSqr(Vec3.atCenterOf(target));
        if (distSq < ARRIVE_SQ) {
            client.options.keyUp.setDown(false);
            walking = false;
            finishStage = nextStage;
            finishStageStartMs = System.currentTimeMillis();
            return true;
        }
        if (System.currentTimeMillis() - finishStageStartMs > FINISH_WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] TeleportMaze: walk to {} timed out - continuing anyway", label);
            client.options.keyUp.setDown(false);
            walking = false;
            finishStage = nextStage;
            finishStageStartMs = System.currentTimeMillis();
            return true;
        }
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), Vec3.atCenterOf(target));
        AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
        client.options.keyUp.setDown(true);
        walking = true;
        return false;
    }

    private static BlockPos getPad(Vec3 pos) {
        BlockPos currentPad = TeleportMazeSolverFeature.nearestPad(pos);
        Set<BlockPos> currentCell = TeleportMazeSolverFeature.cellOf(currentPad);
        if (currentCell == null) {
            return null;
        }
        Set<BlockPos> visited = TeleportMazeSolverFeature.getVisited();
        BlockPos best = TeleportMazeSolverFeature.getBest();
        if (best != null && currentCell.contains(best) && !visited.contains(best)) {
            return best;
        }
        Set<BlockPos> correct = TeleportMazeSolverFeature.getCorrectPortals();
        if (correct.size() == 1) {
            BlockPos only = correct.iterator().next();
            if (currentCell.contains(only)) {
                return only;
            }
        }
        List<BlockPos> unvisited = new ArrayList<>();
        for (BlockPos p : currentCell) {
            if (!visited.contains(p)) {
                unvisited.add(p);
            }
        }
        for (BlockPos p : unvisited) {
            if (p.getX() != currentPad.getX() && p.getZ() != currentPad.getZ()) {
                return p;
            }
        }
        BlockPos farthest = null;
        double bestDist = -1;
        for (BlockPos p : unvisited) {
            double d = pos.distanceToSqr(Vec3.atCenterOf(p));
            if (d > bestDist) {
                bestDist = d;
                farthest = p;
            }
        }
        return farthest;
    }

    private static void stop(Minecraft client) {
        if (walking) {
            client.options.keyUp.setDown(false);
            walking = false;
        }
    }

    private static void reset(Minecraft client) {
        stop(client);
        pendingTicks = -1;
        lastSeq = -1;
        finishStage = FinishStage.NONE;
        finishStageStartMs = 0L;
        auraAttempts = 0;
    }
}
