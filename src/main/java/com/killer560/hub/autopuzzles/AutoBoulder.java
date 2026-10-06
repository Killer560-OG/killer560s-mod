package com.killer560.hub.autopuzzles;

import com.killer560.hub.cheatutils.CheatUtilsConfig;
import com.killer560.hub.cheatutils.SecretAuraFeature;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.puzzlesolvers.BoulderSolverConfig;
import com.killer560.hub.puzzlesolvers.BoulderSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.BodyAim;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Auto Boulder: get the Boulder room's reward chest, then walk back out so he can etherwarp again.
 *
 * <p>killer560 (2026-10-06): "first detect if i have secret aura on or not, if i do then once I interactive map to the
 * room or walk into it then it should walk straight forward towards those iron bars at the back ... it can just run on
 * the barrier blocks and it should be able to reach the chest if it runs all the way forward ... If i dont have secret
 * auras on then it needs to pathfind to the buttons that it actually needs ... that pathfinding needs to look legit ...
 * after it gets both it needs to run back out of the room such that I am able to etherwarp again."
 *
 * <h2>Starting</h2>
 * It starts when he walks into the room (the live map names it), or when an Interactive Map path ends at Boulder's
 * doorway spot (relative 15,68,-2, which the map files under the tile next door) - the {@code ClearExecutor.arrivalSeq}
 * trigger Auto Teleport Maze uses. Never while the map is still moving him.
 *
 * <h2>Secret Aura ON (checked once, at the start, with {@code CheatUtilsConfig.isSecretAuraEnabled})</h2>
 * The doorway opens at roof height: the box grid is roofed with barrier at relative y 68, and at the far end the roof
 * meets a block wall topped by iron bars (relative z 27), directly over the alcove holding the chest (15,66,29). Standing
 * against that wall his eye is about 4.4 from the chest's box (Map Logger capture, decoded), inside the 4.5 reach. So he
 * turns smoothly to face along the room towards the chest's column, holds forward and sprint, and lets go of both the
 * moment the chest is inside Secret Aura's range - Secret Aura does the click. No button is pressed. If he is not on
 * the roof (he fell, or came in some other way) the room is played as with Secret Aura off.
 *
 * <h2>Secret Aura OFF</h2>
 * The buttons {@link BoulderSolverFeature} names - only those, one at a time, each once its button exists (it is laid
 * after the box before it moves) - then the chest. Each is reached by {@link BoulderPath}, which plans across heights,
 * so the walk from the doorway takes the stairs down instead of dropping through a hole in the roof; it never stands on
 * the barrier roof. The walk is smooth: {@link HumanLook} turns (eased, capped, on the mouse grid), forward held only
 * while roughly facing the way, sprint only on a long straight. At a button he turns to look at it and presses only
 * when the crosshair ray actually hits it, on a tick where he did not turn - the use packet carries what the previous
 * movement packet already reported. The chest is opened the same way.
 *
 * <h2>Out</h2>
 * Then he walks back to the doorway and on out until the live map no longer files him under Boulder (where etherwarp
 * is refused) - up the stairs in the legit mode, back along the roof in the aura one.
 *
 * <h2>Safety</h2>
 * Movement is the forward and sprint keys and rotation only - nothing written to position or velocity. The body turns
 * through {@link BodyAim} with the camera held by {@link ViewFreeze}, and after the room the body is turned back to his
 * view at the same human pace before the camera is released (no snap). A server position correction while walking, any
 * of S/A/D/jump pressed after it started, or a screen, stops or pauses it. Every stage change and refusal is an INFO line.
 */
public final class AutoBoulder {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Boulder";

    /** Relative heights, from the decoded capture: feet on the doorway platform / roof, feet on the box floor. */
    private static final int ROOF_FEET_REL = 69;
    private static final int FLOOR_FEET_REL = 64;
    /** The iron-bars wall the aura run heads for, relative z. */
    private static final int BARS_Z_REL = 27;

    /** Press / open from a standing eye within this of the box when there is such a spot; else {@link #REACH_LOOSE}. */
    private static final double REACH_SNUG = 3.3;
    private static final double REACH_LOOSE = 4.0;
    private static final long PRESS_GAP_MS = 350L;
    private static final long BUTTON_WAIT_MS = 3000L;
    private static final long SOLVER_WAIT_MS = 4000L;
    /** Secret Aura's wait, plus the "Boulder Chest Wait" slider, before he opens the chest himself. */
    private static final long AURA_WAIT_BASE_MS = 1000L;
    private static final long ROOM_TIMEOUT_MS = 120_000L;
    private static final int MAX_REPLANS = 3;
    /** Eye-to-box distance to the chest from against the bars, measured 4.29-4.42 (93-solve-boulder-aura, 2026-10-06):
     *  an aura range under this cannot take it from there. */
    private static final double AURA_CHEST_REACH = 4.4;
    private static final long SETTLE_AFTER_PRESS_MS = 400L;
    private static final long PLAN_RETRY_MS = 250L;
    private static final long PLAN_GIVE_UP_MS = 2500L;
    private static long lastPlanFailMs = 0L;
    private static long planFailSinceMs = 0L;
    private static final int AIM_MAX_TICKS = 60;

    private enum Mode { AURA, BUTTONS }

    private enum Stage { NEED_CHEST, RUN_TO_BARS, AWAIT_AURA, PUSH, TO_CHEST, EXIT, DONE }

    private static final BodyAim BODY = new BodyAim(() -> { });
    private static final HumanLook LOOK = new HumanLook();
    /** Decided once per room: Secret Aura off turns his real camera; the aura run turns only the body. */
    private static boolean realCamera = false;
    private static long aimStartMs = 0L;
    private static BlockPos aimPointFor = null;
    private static Vec3 aimPt = null;
    private static long chestAtMs = 0L;
    /** This walk is the Secret Aura run back out: sprint every tick once facing the way. */
    private static boolean sprintAll = false;
    private static boolean sprintStarted = false;

    private static boolean engaged = false;
    private static Stage stage = Stage.NEED_CHEST;
    private static Mode mode = Mode.BUTTONS;
    private static int[] cr = null;
    private static BlockPos chestReal = null;
    /** The chest already taken in this world, so walking back in does not run the room again. */
    private static BlockPos doneChest = null;
    private static long engagedMs = 0L;
    private static long stageStartMs = 0L;
    private static String loggedWait = null;
    private static int consumedArrival = Integer.MIN_VALUE;
    private static boolean noChestWarned = false;
    private static int scanCooldown = 0;
    private static boolean sawContainer = false;
    private static int presses = 0;
    private static long lastPressMs = 0L;
    private static long buttonMissingSinceMs = 0L;
    private static long pushStartMs = 0L;
    private static boolean sawSolution = false;
    /** Physical S/A/D/jump: armed once seen up, so a key already held when it started is not a takeover. */
    private static final boolean[] keyArmed = new boolean[4];
    private static volatile boolean correction = false;

    // walk
    private static BoulderPath path = null;
    private static List<Vec3> pts = List.of();
    private static int ptIdx = 0;
    private static boolean walking = false;
    private static String walkLabel = null;
    private static long walkStartMs = 0L;
    private static long walkTimeoutMs = 0L;
    private static double bestRemaining = Double.MAX_VALUE;
    private static long bestAtMs = 0L;
    private static int replans = 0;
    private static BoulderPath.GoalTest walkGoal = null;
    private static boolean walkAvoidBarrier = true;
    /** The aim at a button or the chest: ticks spent turning onto it. */
    private static int aimTicks = 0;
    private static BlockPos aimFor = null;
    /** RUN_TO_BARS: ticks in a row with forward held and no headway. */
    private static int stalledTicks = 0;

    private AutoBoulder() {
    }

    static void levelChanged() {
        disengage(Minecraft.getInstance(), null);
        doneChest = null;
        consumedArrival = ClearExecutor.arrivalSeq();
    }

    /** From the position-packet hook: a server correction stops a walk. */
    public static void onServerPosition() {
        if (engaged && (walking || stage == Stage.RUN_TO_BARS)) {
            correction = true;
        }
    }

    static void tick(Minecraft client, String roomName) {
        LocalPlayer player = client.player;
        long t0 = System.nanoTime();
        if (lastTickStartNanos != 0L) {
            long d = Math.max(20_000_000L, Math.min(150_000_000L, t0 - lastTickStartNanos));
            tickNanos = (long) (tickNanos * 0.7 + d * 0.3);
        }
        lastTickStartNanos = t0;
        finishStep(player);
        boolean acting = false;
        try {
            acting = run(client, player, roomName);
        } finally {
            if (!acting) {
                settleBody(client, player);
            } else {
                BODY.tick(player, true);
            }
            if (player != null) {
                sentYaw = player.getYRot();
                sentPitch = player.getXRot();
            }
        }
    }

    /** @return true while it is driving his body this tick */
    private static boolean run(Minecraft client, LocalPlayer player, String roomName) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoBoulderEnabled()) {
            if (engaged) {
                disengage(client, "Auto Boulder was switched off");
            }
            return false;
        }
        boolean inRoom = ROOM.equals(roomName);
        if (!engaged) {
            if (inRoom) {
                int[] c = LiveMapFeature.currentRoomClayAndRotation();
                if (c == null) {
                    waitFor("the live map has no rotation for the room yet");
                    return false;
                }
                if (ClearExecutor.isBusy()) {
                    waitFor("the Interactive Map is moving him");
                    return false;
                }
                engage(client, c, "he is in the room");
            } else {
                int[] c = mapArrivalAtDoor(player);
                if (c == null) {
                    return false;
                }
                engage(client, c, "the Interactive Map put him at the doorway");
            }
        }
        if (stage == Stage.DONE) {
            if (!inRoom && !nearDoor(player)) {
                disengage(client, null);
            }
            return false;
        }
        if (stage == Stage.EXIT && !inRoom && outsideRoom(player)) {
            finish(client, "out of the room - the live map no longer files him under Boulder, etherwarp is free");
            return false;
        }
        if (!inRoom && stage != Stage.EXIT && !nearDoor(player)) {
            disengage(client, "he left the room");
            return false;
        }
        if (System.currentTimeMillis() - engagedMs > ROOM_TIMEOUT_MS) {
            giveUp(client, "still not done after " + ROOM_TIMEOUT_MS / 1000 + " s");
            return false;
        }
        if (McCompat.screen(client) != null) {
            if (stage == Stage.AWAIT_AURA || stage == Stage.TO_CHEST) {
                sawContainer = true;
            }
            releaseKeys(client);
            return true;
        }
        if (ClearExecutor.isBusy()) {
            releaseKeys(client);
            waitFor("the Interactive Map is moving him");
            return true;
        }
        String takeover = takeover(client);
        if (takeover != null) {
            giveUp(client, "you pressed " + takeover);
            return false;
        }
        if (correction) {
            correction = false;
            giveUp(client, "the server corrected his position while walking");
            return false;
        }
        switch (stage) {
            case NEED_CHEST -> findChest(client, player);
            case RUN_TO_BARS -> runToBars(client, player);
            case AWAIT_AURA -> awaitAura(client, player);
            case PUSH -> push(client, player);
            case TO_CHEST -> toChest(client, player);
            case EXIT -> exit(client, player);
            default -> {
            }
        }
        return engaged && stage != Stage.DONE;
    }

    // ------------------------------------------------------------------ starting

    private static void engage(Minecraft client, int[] c, String why) {
        engaged = true;
        cr = c;
        engagedMs = System.currentTimeMillis();
        stage = Stage.NEED_CHEST;
        stageStartMs = engagedMs;
        correction = false;
        KeyMapping[] keys = ownKeys(client);
        for (int i = 0; i < keys.length; i++) {
            keyArmed[i] = !keys[i].isDown();
        }
        LOGGER.info("[AutoPuzzles] Boulder: started - {}", why);
    }

    /** Boulder's clay/rotation when an Interactive Map path has just ended with him at its doorway spot, else null. */
    private static int[] mapArrivalAtDoor(LocalPlayer player) {
        int arrival = ClearExecutor.arrivalSeq();
        if (arrival == consumedArrival) {
            return null;
        }
        if (System.currentTimeMillis() - ClearExecutor.arrivalMs() > 3000L) {
            consumedArrival = arrival; // too old to act on
            return null;
        }
        if (ClearExecutor.isBusy() || !player.onGround()) {
            return null; // not settled yet - the 3 s window allows for it
        }
        consumedArrival = arrival;
        int[] c = boulderClayRotation();
        if (c == null) {
            return null;
        }
        BlockPos door = PuzzleCoords.real(15, ROOF_FEET_REL, -1, c);
        BlockPos feet = player.blockPosition();
        if (Math.abs(feet.getX() - door.getX()) > 4 || Math.abs(feet.getZ() - door.getZ()) > 4
                || Math.abs(feet.getY() - door.getY()) > 2) {
            return null;
        }
        return c;
    }

    private static int[] boulderClayRotation() {
        com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
        if (layout == null) {
            return null;
        }
        for (int r = 0; r < layout.roomCount(); r++) {
            if (ROOM.equals(layout.name(r))) {
                return layout.clayRotation(r);
            }
        }
        return null;
    }

    private static BlockPos rel(LocalPlayer player) {
        return PuzzleCoords.relative(player.blockPosition(), cr);
    }

    /** Within a few blocks outside the doorway (the map's spot is two out). */
    private static boolean nearDoor(LocalPlayer player) {
        if (cr == null) {
            return false;
        }
        BlockPos r = rel(player);
        return r.getZ() >= -5 && r.getZ() <= 1 && Math.abs(r.getX() - 15) <= 5;
    }

    /** Past the doorway gap, into the next tile. */
    private static boolean outsideRoom(LocalPlayer player) {
        return cr != null && rel(player).getZ() <= -1;
    }

    // ------------------------------------------------------------------ the chest

    private static void findChest(Minecraft client, LocalPlayer player) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        RoomEntry.Pos chestRel = null;
        String source = null;
        var chests = entry == null || !ROOM.equals(entry.name) || entry.secretCoords == null ? null
                : entry.secretCoords.chest;
        if (chests != null && !chests.isEmpty()) {
            chestRel = chests.get(0);
            for (RoomEntry.Pos p : chests) {
                if (p.z > chestRel.z) {
                    chestRel = p;
                }
            }
            source = "room database";
        } else {
            if (scanCooldown > 0) {
                scanCooldown--;
                return;
            }
            scanCooldown = 20;
            BlockPos bestRel = null;
            for (BlockPos real : AutoPuzzleUtil.chestsInRoom(client.level, cr, -1, 60, -1, 31, 75, 31)) {
                BlockPos r = PuzzleCoords.relative(real, cr);
                if (bestRel == null || r.getZ() > bestRel.getZ()) {
                    bestRel = r;
                }
            }
            if (bestRel == null) {
                if (!noChestWarned) {
                    noChestWarned = true;
                    LOGGER.info("[AutoPuzzles] Boulder: no chest secret in the room database and no chest block in "
                            + "the room yet - looking again every 20 ticks");
                }
                return;
            }
            chestRel = new RoomEntry.Pos();
            chestRel.x = bestRel.getX();
            chestRel.y = bestRel.getY();
            chestRel.z = bestRel.getZ();
            source = "chest block found in the room";
        }
        chestReal = PuzzleCoords.real(chestRel, cr);
        if (chestReal.equals(doneChest)) {
            advance(Stage.DONE, "this room's chest was already taken");
            return;
        }
        // Whether Secret Aura will actually OPEN this chest, not just whether it is switched on (killer560,
        // 2026-10-06: "if I have chest aura off as well then it needs to go and press the buttons").
        String refusal = SecretAuraFeature.chestRefusal(client, AURA_CHEST_REACH);
        boolean aura = refusal == null;
        double roofY = PuzzleCoords.real(15, ROOF_FEET_REL, 0, cr).getY();
        boolean onRoof = player.getY() > roofY - 0.6;
        LOGGER.info("[AutoPuzzles] Boulder: chest at {} (relative {}, {}, {}) from the {}; Secret Aura {}; feet y {} "
                        + "(roof {})", AutoPuzzleUtil.fmt(chestReal), chestRel.x, chestRel.y, chestRel.z, source,
                aura ? "will take chests" : "will not take it (" + refusal + ")", fmt(player.getY()), (int) roofY);
        realCamera = !(aura && onRoof);
        if (aura && onRoof) {
            mode = Mode.AURA;
            advance(Stage.RUN_TO_BARS, "Secret Aura will take the chest - running along the roof to the bars");
        } else {
            mode = Mode.BUTTONS;
            advance(Stage.PUSH, aura ? "Secret Aura would take the chest but he is below the roof - pressing the buttons"
                    : refusal + " - pressing the solver's buttons");
        }
    }

    private static boolean chestTaken(Minecraft client) {
        if (chestReal == null) {
            return false;
        }
        if (sawContainer || SecretAuraFeature.isDone(chestReal)) {
            return true;
        }
        BlockEntity be = client.level.getBlockEntity(chestReal);
        return be instanceof ChestBlockEntity chest && chest.getOpenNess(0f) > 0f;
    }

    // ------------------------------------------------------------------ Secret Aura on: along the roof

    private static void runToBars(Minecraft client, LocalPlayer player) {
        double reach = Math.min(CheatUtilsConfig.getInstance().getAuraRange(), CheatUtilsConfig.MEASURED_MAX_REACH)
                - 0.08;
        double dist = Math.sqrt(BlockHits.boxDistanceSq(player.getEyePosition(), chestReal));
        if (chestTaken(client)) {
            releaseKeys(client);
            chestDone("Secret Aura took the chest on the way");
            return;
        }
        if (dist <= reach) {
            releaseKeys(client);
            advance(Stage.AWAIT_AURA, "the chest is " + fmt(dist) + " from his eye, inside Secret Aura's " + fmt(reach));
            return;
        }
        double roofY = PuzzleCoords.real(15, ROOF_FEET_REL, 0, cr).getY();
        if (player.onGround() && player.getY() < roofY - 1.0) {
            releaseKeys(client);
            mode = Mode.BUTTONS;
            advance(Stage.PUSH, "he is off the roof (feet y " + fmt(player.getY()) + ") - pressing the buttons");
            return;
        }
        if (System.currentTimeMillis() - stageStartMs > 10_000L) {
            giveUp(client, "the run to the bars took over 10 s (chest still " + fmt(dist) + " away)");
            return;
        }
        // Straight along the room at the chest's column, towards the bars.
        BlockPos bars = PuzzleCoords.real(15, ROOF_FEET_REL, BARS_Z_REL, cr);
        Vec3 aim = new Vec3(chestReal.getX() + 0.5, player.getEyeY(), chestReal.getZ() + 0.5);
        if (Math.hypot(aim.x - player.getX(), aim.z - player.getZ()) < 1.0) {
            aim = new Vec3(bars.getX() + 0.5, player.getEyeY(), bars.getZ() + 0.5);
        }
        float yaw = AutoPuzzleUtil.direction(player.getEyePosition(), aim)[0];
        LOOK.begin("bars");
        turnToward(client, player, yaw, 10f);
        // Forward AND sprint from the very first tick to the bars (killer560, 2026-10-06: sprinting the whole time).
        // He is already roughly facing down the room on the way in; the turn finishes while he runs.
        boolean forward = true;
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
        double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        if (forward && player.onGround() && speed < 0.02 && System.currentTimeMillis() - stageStartMs > 600L) {
            if (++stalledTicks >= 10) {
                releaseKeys(client);
                double auraReach = Math.min(CheatUtilsConfig.getInstance().getAuraRange(),
                        CheatUtilsConfig.MEASURED_MAX_REACH);
                if (dist <= auraReach) {
                    advance(Stage.AWAIT_AURA, "against the bars, the chest " + fmt(dist) + " from his eye");
                } else {
                    giveUp(client, "ran into something at " + AutoPuzzleUtil.fmt(player.blockPosition())
                            + " with the chest " + fmt(dist) + " away - past Secret Aura's " + fmt(auraReach));
                }
            }
        } else {
            stalledTicks = 0;
        }
    }

    private static void awaitAura(Minecraft client, LocalPlayer player) {
        releaseKeys(client);
        if (chestTaken(client)) {
            chestDone("Secret Aura took the chest");
            return;
        }
        long wait = AURA_WAIT_BASE_MS + AutoPuzzlesConfig.getInstance().getBoulderDelayMs();
        if (System.currentTimeMillis() - stageStartMs > wait) {
            LOGGER.info("[AutoPuzzles] Boulder: Secret Aura has not taken the chest in {} ms - opening it by looking at it",
                    wait);
            advance(Stage.TO_CHEST, "opening it himself");
        }
    }

    private static void chestDone(String why) {
        doneChest = chestReal;
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Boulder: "), ModChat.good("reward chest"),
                ModChat.text(" taken - walking out."));
        chestAtMs = System.currentTimeMillis();
        LOGGER.info("[AutoPuzzles] Boulder: chest {} taken ({}) after {} press(es) - solve time {} s from the start",
                AutoPuzzleUtil.fmt(chestReal), why, presses, fmt((chestAtMs - engagedMs) / 1000.0));
        stopWalk(Minecraft.getInstance(), null);
        advance(Stage.EXIT, why);
    }

    // ------------------------------------------------------------------ Secret Aura off: the buttons

    private static void push(Minecraft client, LocalPlayer player) {
        if (pushStartMs == 0L) {
            pushStartMs = System.currentTimeMillis();
        }
        BlockPos next = BoulderSolverFeature.getNextClick();
        if (next != null) {
            sawSolution = true;
        } else {
            if (!BoulderSolverConfig.getInstance().isEnabled()) {
                waitFor("Boulder Solver is off - it names the buttons; going for the chest as the boxes stand");
                advance(Stage.TO_CHEST, "no solver");
                return;
            }
            if (sawSolution || System.currentTimeMillis() - pushStartMs > SOLVER_WAIT_MS) {
                stopWalk(client, null);
                advance(Stage.TO_CHEST, sawSolution ? "every box the solver named was pushed (" + presses
                        + " press(es))" : "the solver read no known arrangement in " + SOLVER_WAIT_MS + " ms");
                return;
            }
            releaseKeys(client);
            waitFor("the solver has not read the arrangement yet");
            return;
        }
        if (!(client.level.getBlockState(next).getBlock() instanceof ButtonBlock)) {
            releaseKeys(client);
            if (buttonMissingSinceMs == 0L) {
                buttonMissingSinceMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - buttonMissingSinceMs > BUTTON_WAIT_MS) {
                giveUp(client, "the button at " + AutoPuzzleUtil.fmt(next) + " never appeared ("
                        + client.level.getBlockState(next).getBlock() + " there)");
                return;
            }
            waitFor("the button at " + AutoPuzzleUtil.fmt(next) + " is not there yet");
            return;
        }
        buttonMissingSinceMs = 0L;
        if (System.currentTimeMillis() - lastPressMs < SETTLE_AFTER_PRESS_MS) {
            // The box the last press moved reaches the client a few ticks later; a walk planned before that plans
            // round where it used to be (2026-10-06 run: "no walk", 223 nodes, the very tick of the press).
            releaseKeys(client);
            return;
        }
        if (approachAndClick(client, player, next, "button " + AutoPuzzleUtil.fmt(next))) {
            presses++;
            LOGGER.info("[AutoPuzzles] Boulder: pressed {} ({} left)", AutoPuzzleUtil.fmt(next),
                    BoulderSolverFeature.getRemainingClicks());
        }
    }

    private static void toChest(Minecraft client, LocalPlayer player) {
        if (chestTaken(client)) {
            chestDone(sawContainer ? "the chest opened" : "the chest is open");
            return;
        }
        if (approachAndClick(client, player, chestReal, "the reward chest")) {
            LOGGER.info("[AutoPuzzles] Boulder: opened the chest at {} looking at it, after {} press(es)",
                    AutoPuzzleUtil.fmt(chestReal), presses);
            chestDone("opened it looking at it");
        }
    }

    /**
     * Walks to a spot with {@code target} in reach and in sight, turns to look at it, and right-clicks it when the
     * crosshair is on it.
     *
     * @return true on the tick the click was sent
     */
    private static boolean approachAndClick(Minecraft client, LocalPlayer player, BlockPos target, String label) {
        Vec3 eye = player.getEyePosition();
        boolean inSpot = BlockHits.boxDistanceSq(eye, target) <= REACH_LOOSE * REACH_LOOSE
                && sees(client, eye, target) && player.onGround();
        if (walking && !target.equals(aimFor)) {
            // a walk for something else
            stopWalk(client, null);
        }
        if (!inSpot || walking) {
            if (walking) {
                if (BlockHits.boxDistanceSq(eye, target) <= REACH_SNUG * REACH_SNUG && sees(client, eye, target)
                        && player.onGround()) {
                    stopWalk(client, label + " is in reach");
                } else {
                    tickWalk(client, player);
                    return false;
                }
            } else {
                if (!player.onGround()) {
                    releaseKeys(client);
                    return false;
                }
                aimFor = target;
                long now = System.currentTimeMillis();
                if (now - lastPlanFailMs < PLAN_RETRY_MS) {
                    return false; // the world may still be catching up with a box that moved
                }
                if (!startWalk(client, player, spotGoal(client, target, REACH_SNUG), mode == Mode.BUTTONS, label)
                        && !startWalk(client, player, spotGoal(client, target, REACH_LOOSE), mode == Mode.BUTTONS,
                        label)) {
                    lastPlanFailMs = now;
                    if (planFailSinceMs == 0L) {
                        planFailSinceMs = now;
                    } else if (now - planFailSinceMs > PLAN_GIVE_UP_MS) {
                        giveUp(client, "no walkable spot has " + label + " in reach and in sight");
                    }
                } else {
                    planFailSinceMs = 0L;
                }
                return false;
            }
        }
        releaseKeys(client);
        long now = System.currentTimeMillis();
        if (!target.equals(aimFor) || aimStartMs == 0L) {
            aimFor = target;
            aimTicks = 0;
            aimStartMs = now;
            aimPointFor = null;
        }
        if (aimPointFor == null || !aimPointFor.equals(target)) {
            aimPointFor = target;
            aimPt = humanAimPoint(client, target);
            LOOK.begin(target);
        }
        // FIRST, before any turn this tick: is his crosshair on it? The rotation he has now is the one the last
        // movement packet reported, so a click now carries what the server already has (BadPacketsJ's rule).
        BlockHitResult hit = crosshairOn(client, player, target);
        if (hit != null) {
            if (player.isShiftKeyDown()) {
                waitFor("sneaking - a sneak-click would place the held item");
                return false;
            }
            if (now - lastPressMs < PRESS_GAP_MS || !AutoPuzzleUtil.gateWorldClick()) {
                return false;
            }
            long sincePrev = lastPressMs == 0L ? now - engagedMs : now - lastPressMs;
            lastPressMs = now;
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            LOGGER.info("[AutoPuzzles] Boulder: click on {} - crosshair on it: yes ({} hit {} face {}), turn {} tick(s) "
                            + "/ {} ms after the walk, {} ms since the previous click", label,
                    realCamera ? "camera and last-sent rotation" : "body ray", AutoPuzzleUtil.fmt(hit.getBlockPos()),
                    hit.getDirection().getName(), aimTicks, now - aimStartMs, sincePrev);
            aimFor = null;
            aimTicks = 0;
            aimStartMs = 0L;
            aimPointFor = null;
            return true;
        }
        if (++aimTicks > AIM_MAX_TICKS) {
            giveUp(client, "could not get the crosshair onto " + label + " in " + AIM_MAX_TICKS + " ticks");
            return false;
        }
        float[] dir = AutoPuzzleUtil.direction(player.getEyePosition(), aimPt);
        turnToward(client, player, dir[0], dir[1]);
        return false;
    }

    /**
     * The block his crosshair is on, if it is {@code target}. With his real camera that is the client's own pick
     * ({@code Minecraft.hitResult}, what vanilla right-clicks); with the camera held (the Secret Aura run's fallback) it
     * is the same ray cast from his body's rotation.
     */
    private static BlockHitResult crosshairOn(Minecraft client, LocalPlayer player, BlockPos target) {
        if (Float.isNaN(sentYaw)) {
            return null;
        }
        // The rotation now on screen (the step just finished) AND the one the server last heard must both be on it;
        // the click is built from the latter, so the server sees a use where it already knows he is looking.
        BlockHitResult now = ray(client, player, player.getYRot(), player.getXRot(), target);
        return now == null ? null : ray(client, player, sentYaw, sentPitch, target);
    }

    private static BlockHitResult ray(Minecraft client, LocalPlayer player, float yaw, float pitch, BlockPos target) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = AutoPuzzleUtil.look(yaw, pitch);
        HitResult hr = client.level.clip(new ClipContext(eye, eye.add(look.scale(CheatUtilsConfig.MEASURED_MAX_REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().equals(target) ? bh : null;
    }

    /** A point inside the middle 60% of the target's outline, picked once per target - nobody aims at the exact centre. */
    private static Vec3 humanAimPoint(Minecraft client, BlockPos target) {
        VoxelShape shape = client.level.getBlockState(target).getShape(client.level, target);
        if (shape.isEmpty()) {
            return Vec3.atCenterOf(target);
        }
        var b = shape.bounds();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        double fx = 0.2 + 0.6 * r.nextDouble();
        double fy = 0.2 + 0.6 * r.nextDouble();
        double fz = 0.2 + 0.6 * r.nextDouble();
        return new Vec3(target.getX() + b.minX + (b.maxX - b.minX) * fx, target.getY() + b.minY + (b.maxY - b.minY) * fy,
                target.getZ() + b.minZ + (b.maxZ - b.minZ) * fz);
    }

    private static BoulderPath.GoalTest spotGoal(Minecraft client, BlockPos target, double reach) {
        return BoulderPath.eye(e -> BlockHits.boxDistanceSq(e, target) <= reach * reach && sees(client, e, target));
    }

    /** The middle of the block's outline (a button is a small box on a face). */
    private static Vec3 aimPoint(Minecraft client, BlockPos target) {
        VoxelShape shape = client.level.getBlockState(target).getShape(client.level, target);
        if (shape.isEmpty()) {
            return Vec3.atCenterOf(target);
        }
        return shape.bounds().getCenter().add(target.getX(), target.getY(), target.getZ());
    }

    /** Whether a ray from {@code eye} to the middle of {@code target}'s outline hits {@code target} first. */
    private static boolean sees(Minecraft client, Vec3 eye, BlockPos target) {
        Vec3 point = aimPoint(client, target);
        HitResult hr = client.level.clip(new ClipContext(eye, point.add(point.subtract(eye).normalize().scale(0.3)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return hr instanceof BlockHitResult hit && hr.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    // ------------------------------------------------------------------ out

    private static void exit(Minecraft client, LocalPlayer player) {
        if (walking) {
            tickWalk(client, player);
            if (walking) {
                return;
            }
            if (stage != Stage.EXIT) {
                return;
            }
            BlockPos r = rel(player);
            if (r.getZ() <= 0) {
                finish(client, "walked out to relative z " + r.getZ() + " - as far as there is floor");
                return;
            }
        }
        if (!player.onGround()) {
            releaseKeys(client);
            return;
        }
        if (replans > MAX_REPLANS) {
            giveUp(client, "could not walk out of the room");
            return;
        }
        // As far out as there is floor: the next tile first (where the live map stops calling it Boulder), then the
        // doorway gap, then the doorway itself.
        boolean avoid = mode == Mode.BUTTONS;
        for (int z : new int[]{-3, -2, -1, 0}) {
            final int limit = z;
            BoulderPath.GoalTest out = (feet, s) -> {
                BlockPos r = PuzzleCoords.relative(feet, cr);
                return r.getZ() <= limit && Math.abs(r.getX() - 15) <= 2;
            };
            if (startWalk(client, player, out, avoid, "the way out (relative z <= " + z + ")")) {
                return;
            }
        }
        giveUp(client, "no walkable way back to the doorway");
    }

    // ------------------------------------------------------------------ walking

    /** Plans with a fresh grid (the boxes move) and starts following it. @return false if no path */
    private static boolean startWalk(Minecraft client, LocalPlayer player, BoulderPath.GoalTest goal,
                                     boolean avoidBarrier, String label) {
        BlockPos a = PuzzleCoords.real(-3, FLOOR_FEET_REL - 4, -5, cr);
        BlockPos b = PuzzleCoords.real(33, ROOF_FEET_REL + 5, 33, cr);
        BoulderPath p = new BoulderPath(client.level, a, b, avoidBarrier);
        if (!p.plan(player.position(), goal)) {
            LOGGER.info("[AutoPuzzles] Boulder: no walk to {} ({} nodes searched{})", label, p.expanded(),
                    avoidBarrier ? ", barrier roof excluded" : "");
            return false;
        }
        path = p;
        pts = p.points();
        ptIdx = 0;
        walking = true;
        walkLabel = label;
        walkGoal = goal;
        walkAvoidBarrier = avoidBarrier;
        walkStartMs = System.currentTimeMillis();
        aimTicks = 0;
        sprintAll = !realCamera && stage == Stage.EXIT;
        sprintStarted = false;
        double len = p.length(player.position());
        walkTimeoutMs = 3000L + (long) (len / 3.0 * 1000.0);
        bestRemaining = Double.MAX_VALUE;
        bestAtMs = walkStartMs;
        int drops = 0;
        double lowest = Double.MAX_VALUE;
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i).y < pts.get(i - 1).y - 0.6) {
                drops++;
            }
            lowest = Math.min(lowest, pts.get(i).y);
        }
        LOGGER.info("[AutoPuzzles] Boulder: walking to {} at {} - {} step(s), {} blocks, {} step(s) down, lowest feet y {}",
                label, AutoPuzzleUtil.fmt(p.goal()), pts.size(), fmt(len), drops, pts.isEmpty() ? "-" : fmt(lowest));
        return true;
    }

    /** One tick of following the planned path: turn smoothly towards a point a little ahead and hold forward. */
    private static void tickWalk(Minecraft client, LocalPlayer player) {
        if (!walking) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - walkStartMs > walkTimeoutMs) {
            stopWalk(client, "walk timed out after " + walkTimeoutMs + " ms");
            replanOrFail(client, player);
            return;
        }
        Vec3 p = player.position();
        // Advance past points he has reached; skip ahead to a nearer later one (a corner he cut).
        while (ptIdx < pts.size() - 1 && hdist(p, pts.get(ptIdx)) < 0.75) {
            ptIdx++;
        }
        for (int j = ptIdx + 1; j < Math.min(pts.size(), ptIdx + 4); j++) {
            if (hdist(p, pts.get(j)) < hdist(p, pts.get(ptIdx)) && Math.abs(pts.get(j).y - p.y) < 0.7) {
                ptIdx = j;
            }
        }
        Vec3 last = pts.get(pts.size() - 1);
        double remaining = hdist(p, pts.get(ptIdx));
        for (int i = ptIdx + 1; i < pts.size(); i++) {
            remaining += hdist(pts.get(i - 1), pts.get(i));
        }
        if (ptIdx == pts.size() - 1 && hdist(p, last) < 0.35 && Math.abs(p.y - last.y) < 0.7) {
            stopWalk(client, "arrived");
            return;
        }
        if (player.onGround() && ptIdx > 0 && offSegment(p, pts.get(ptIdx - 1), pts.get(ptIdx)) > 1.6) {
            stopWalk(client, "pushed off the path");
            replanOrFail(client, player);
            return;
        }
        if (remaining < bestRemaining - 0.25) {
            bestRemaining = remaining;
            bestAtMs = now;
        } else if (now - bestAtMs > 1500L) {
            stopWalk(client, "no headway for 1.5 s");
            replanOrFail(client, player);
            return;
        }
        // Pure pursuit: a carrot 1.2 blocks along the path from the point he is heading for.
        Vec3 carrot = pts.get(ptIdx);
        double budget = 1.2 - hdist(p, carrot);
        for (int i = ptIdx + 1; i < pts.size() && budget > 0; i++) {
            Vec3 a = pts.get(i - 1);
            Vec3 b = pts.get(i);
            if (Math.abs(b.y - a.y) > 0.6) {
                break; // never aim past a change of height - take the step square on
            }
            double seg = hdist(a, b);
            if (seg >= budget) {
                carrot = new Vec3(a.x + (b.x - a.x) * budget / seg, b.y, a.z + (b.z - a.z) * budget / seg);
                budget = 0;
            } else {
                carrot = b;
                budget -= seg;
            }
        }
        // Eyes on the ground a few blocks ahead, as a walker's are.
        Vec3 ahead = pts.get(Math.min(pts.size() - 1, ptIdx + 3));
        double aheadDist = Math.max(2.0, hdist(p, ahead));
        float pitch = (float) Mth.clamp(Math.toDegrees(Math.atan2(player.getEyeY() - (ahead.y + 0.3), aheadDist)),
                -5.0, 40.0);
        Vec3 eye = player.getEyePosition();
        float yaw = AutoPuzzleUtil.direction(eye, new Vec3(carrot.x, eye.y, carrot.z))[0];
        if (aimFor != null && remaining < 3.0) {
            // The last few blocks: eyes already coming onto what he is walking to (pitch only - the yaw steers).
            pitch = AutoPuzzleUtil.direction(eye, aimPoint(client, aimFor))[1];
        }
        LOOK.begin("walk " + walkLabel);
        turnToward(client, player, yaw, pitch);
        float err = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        if (sprintAll) {
            // The Secret Aura run back out: turn round where he stands (W held against the bars would end a sprint
            // on the collision), then forward and sprint every tick until he is out.
            if (!sprintStarted && err > 20f) {
                releaseKeys(client);
                bestAtMs = now;
                return;
            }
            sprintStarted = true;
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }
        boolean forward = err < (remaining < 0.8 ? 20f : 55f);
        client.options.keyUp.setDown(forward);
        client.options.keySprint.setDown(forward && err < 12f && remaining > 4.5);
    }

    private static void replanOrFail(Minecraft client, LocalPlayer player) {
        if (++replans > MAX_REPLANS) {
            giveUp(client, "could not walk to " + walkLabel + " after " + MAX_REPLANS + " re-plan(s)");
            return;
        }
        if (walkGoal != null && player.onGround()) {
            startWalk(client, player, walkGoal, walkAvoidBarrier, walkLabel);
        }
    }

    private static void stopWalk(Minecraft client, String why) {
        if (!walking) {
            return;
        }
        walking = false;
        releaseKeys(client);
        if (why != null) {
            LOGGER.info("[AutoPuzzles] Boulder: stopped walking to {} - {}", walkLabel, why);
        }
        if ("arrived".equals(why) || (why != null && why.endsWith("in reach"))) {
            replans = 0;
        }
    }

    private static double hdist(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static double offSegment(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-6) {
            return hdist(p, a);
        }
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2));
        return Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t));
    }

    // ------------------------------------------------------------------ rotation and keys

    /**
     * One human step towards (yaw, pitch). Secret Aura off: his REAL rotation, camera and all - he actually looks
     * where he walks and at what he clicks (killer560: "it needs to have the player actually looking at the button").
     * Secret Aura's run: the body through {@link BodyAim}, camera held.
     */
    private static void turnToward(Minecraft client, LocalPlayer player, float yaw, float pitch) {
        float[] next = LOOK.step(client, player.getYRot(), player.getXRot(), yaw, pitch);
        beginStep(player, next[0], next[1], !realCamera);
    }

    // ------------------------------------------------------------------ the rotation, drawn per frame
    //
    // killer560 (2026-10-06): "The boulder turning is really choppy." Each tick's turn (up to 28 degrees) used to be
    // written at once, so at a high frame rate the camera sat still for several frames and then jumped. Now a tick's
    // step is only PLANNED in the tick (beginStep); every render frame (frame) draws the rotation part-way from where
    // the step started to where it ends, by how far through the tick that frame is; and the next tick first finishes
    // the step exactly (finishStep). So the camera moves every frame by one frame's share of a step; each tick's
    // movement packet carries a rotation that was fully drawn, a whole number of mouse counts on from the one before
    // (HumanLook); and a click - sent at the START of a tick, before that tick's movement packet - goes out only when
    // the ray from the rotation the LAST packet reported hits the target as well as the one now on screen.

    private static boolean stepping = false;
    private static boolean stepBody = false;
    private static float fromYaw;
    private static float fromPitch;
    private static float toYaw;
    private static float toPitch;
    private static long stepStartNanos = 0L;
    /** His rotation when this hook ended last tick - what that tick's movement packet reported. */
    private static float sentYaw = Float.NaN;
    private static float sentPitch = Float.NaN;
    /** Render frames that drew a rotation part-way through a step (for the testkit's smoothness check). */
    private static long partialFrames = 0L;
    /** How long a client tick has been taking (smoothed), so a step is drawn over the tick it really spans. */
    private static long tickNanos = 50_000_000L;
    private static long lastTickStartNanos = 0L;
    private static final double DRAW_FRACTION = 0.8;

    private static void beginStep(LocalPlayer player, float yaw, float pitch, boolean body) {
        if (yaw == player.getYRot() && pitch == player.getXRot()) {
            return;
        }
        if (body) {
            BODY.turn(player, player.getYRot(), player.getXRot()); // camera held from here; nothing moves yet
        }
        fromYaw = player.getYRot();
        fromPitch = player.getXRot();
        toYaw = yaw;
        toPitch = Mth.clamp(pitch, -90f, 90f);
        stepBody = body;
        stepStartNanos = System.nanoTime();
        stepping = true;
    }

    /** Start of a tick: land the step the frames have been drawing, exactly where it was planned to end. */
    private static void finishStep(LocalPlayer player) {
        if (!stepping) {
            return;
        }
        stepping = false;
        if (player == null) {
            return;
        }
        if (stepBody) {
            BODY.turn(player, toYaw, toPitch);
        } else {
            player.setYRot(toYaw);
            player.setYHeadRot(toYaw);
            player.setXRot(toPitch);
        }
    }

    /** The rotation {@link #frame} draws {@code nanos} into the current step, or null when not stepping. */
    public static float[] rotationAt(long nanos) {
        if (!stepping) {
            return null;
        }
        // Drawn over 80% of a tick: a tick that comes a little early then finds the step already drawn, rather than
        // landing the rest of it in one frame (a late one just holds the end for a frame or two).
        double p = Mth.clamp((nanos - stepStartNanos) / (tickNanos * DRAW_FRACTION), 0.0, 1.0);
        return new float[]{(float) (fromYaw + (toYaw - fromYaw) * p), (float) (fromPitch + (toPitch - fromPitch) * p)};
    }

    /** Every render frame (AutoPuzzlesFeature's level-render hook). */
    public static void frame() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!stepping || player == null) {
            return;
        }
        long now = System.nanoTime();
        float[] r = rotationAt(now);
        player.setYRot(r[0]);
        player.setYHeadRot(r[0]);
        player.setXRot(r[1]);
        long into = now - stepStartNanos;
        if (into > 0L && into < tickNanos * DRAW_FRACTION) {
            partialFrames++;
        }
    }

    public static long partialFrames() {
        return partialFrames;
    }

    /** The step being drawn, {fromYaw, fromPitch, toYaw, toPitch, startNanos, drawNanos}, or null - for the testkit. */
    public static double[] currentStep() {
        return stepping ? new double[]{fromYaw, fromPitch, toYaw, toPitch, stepStartNanos, tickNanos * DRAW_FRACTION}
                : null;
    }

    /**
     * When nothing is driving him: if the body is still turned away from his held view, turn it back at the same
     * human pace, and release the camera once they agree - so the end of a room is not a snap.
     */
    private static void settleBody(Minecraft client, LocalPlayer player) {
        if (player == null || !BODY.isHeld()) {
            return;
        }
        float vy = ViewFreeze.viewYaw();
        float vp = ViewFreeze.viewPitch();
        if (Float.isNaN(vy)) {
            BODY.tick(player, false);
            return;
        }
        float dy = Math.abs(Mth.wrapDegrees(vy - player.getYRot()));
        float dp = Math.abs(vp - player.getXRot());
        if (dy <= 1.5f && dp <= 1.5f) {
            BODY.tick(player, false);
            return;
        }
        LOOK.begin("settle");
        float[] next = LOOK.step(client, player.getYRot(), player.getXRot(), vy, vp);
        BODY.tick(player, true);
        beginStep(player, next[0], next[1], true);
    }

    private static void releaseKeys(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
    }

    private static KeyMapping[] ownKeys(Minecraft client) {
        return new KeyMapping[]{client.options.keyDown, client.options.keyLeft, client.options.keyRight,
                client.options.keyJump};
    }

    /** The name of a movement key he pressed since it started (one already held then counts once released). */
    private static String takeover(Minecraft client) {
        KeyMapping[] keys = ownKeys(client);
        String[] names = {"back", "left", "right", "jump"};
        for (int i = 0; i < keys.length; i++) {
            boolean down = keys[i].isDown();
            if (!down) {
                keyArmed[i] = true;
            } else if (keyArmed[i]) {
                return names[i];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ bookkeeping

    private static void finish(Minecraft client, String why) {
        stopWalk(client, null);
        releaseKeys(client);
        LOGGER.info("[AutoPuzzles] Boulder: finished - {}", why);
        stage = Stage.DONE;
    }

    private static void advance(Stage next, String why) {
        LOGGER.info("[AutoPuzzles] Boulder: {} -> {} ({})", stage, next, why);
        stage = next;
        stageStartMs = System.currentTimeMillis();
        loggedWait = null;
        replans = 0;
        stalledTicks = 0;
    }

    private static void giveUp(Minecraft client, String why) {
        stopWalk(client, null);
        releaseKeys(client);
        LOGGER.info("[AutoPuzzles] Boulder: stopped for this room - {}", why);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Boulder: "), ModChat.bad(why), ModChat.text("."));
        stage = Stage.DONE;
    }

    private static void waitFor(String why) {
        if (!why.equals(loggedWait)) {
            loggedWait = why;
            LOGGER.info("[AutoPuzzles] Boulder: waiting - {}", why);
        }
    }

    /** Back to idle. {@code why} null: silently. */
    private static void disengage(Minecraft client, String why) {
        if (engaged && why != null) {
            LOGGER.info("[AutoPuzzles] Boulder: stopped - {}", why);
        }
        if (walking || engaged) {
            walking = false;
            if (client != null && client.options != null) {
                releaseKeys(client);
            }
        }
        engaged = false;
        stage = Stage.NEED_CHEST;
        mode = Mode.BUTTONS;
        cr = null;
        chestReal = null;
        stageStartMs = 0L;
        loggedWait = null;
        noChestWarned = false;
        scanCooldown = 0;
        sawContainer = false;
        presses = 0;
        lastPressMs = 0L;
        buttonMissingSinceMs = 0L;
        pushStartMs = 0L;
        sawSolution = false;
        correction = false;
        path = null;
        pts = List.of();
        ptIdx = 0;
        walkLabel = null;
        walkGoal = null;
        replans = 0;
        aimTicks = 0;
        aimFor = null;
        stalledTicks = 0;
        lastPlanFailMs = 0L;
        planFailSinceMs = 0L;
        realCamera = false;
        aimStartMs = 0L;
        aimPointFor = null;
        aimPt = null;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
