package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto Boulder - REDONE ENTIRELY (killer560, 2026-09-27): "redo auto boulder in its entirety. It should instead be
 * such that it pathfinds to above the chest essentially, then auras the chest." No longer clicks the floor buttons
 * {@link com.killer560.hub.puzzlesolvers.BoulderSolverFeature} highlights (that solver still draws its own
 * highlights for you to click by hand) - this is now purely "grab the room's secret chest and get back out":
 * <ol>
 *   <li>On entering the room, find its chest secret from the real room database
 *   ({@link RoomEntry#secretCoords}, the same per-room data {@code SecretWaypointsFeature} already draws from -
 *   NOT a guessed coordinate).</li>
 *   <li>Etherwarp-path (via {@link AutoPuzzleUtil#pathIfMapOn}, so this only runs while Interactive Map is on) to
 *   the standing spot killer560 gave: "The relative spot is 3 blocks towards the entrance and 3 blocks up from
 *   the chest" - relative -z (this room's own doorway-side override is at negative z; see
 *   {@link AutoClearUtils#roomOverride}) and +y from the chest's own relative position. That spot is "run up
 *   against the oak logs at the back middle of the screen" per his reference screenshot - the mantle beam above
 *   the chest blocks a direct approach, so you stop just short of and above it instead of walking into the wall.</li>
 *   <li>Waits there (Boulder Chest Wait - repurposed, see {@link AutoPuzzlesConfig#getBoulderDelayMs()}), then
 *   auras the chest - its own single, deliberate {@link AutoPuzzleUtil#interactBlock} (through the shared
 *   {@link AutoPuzzleUtil#gateWorldClick()}), never {@code SecretAuraFeature} - so this works "even if secret aura
 *   is off but the auto boulder is on" exactly as asked, and is unaffected by Secret Aura standing down for
 *   {@code ClearExecutor.isBusy()} while this walks.</li>
 *   <li>Walks back to the room's doorway-side spot ("on auto boulder make it walk back to the exit so it can
 *   etherwarp again once it finishes") - the exact same {@link AutoClearUtils#roomOverride} spot used to walk in.</li>
 * </ol>
 * Never writes position/velocity and never sends fractional movement - every leg is
 * {@code livemap.autoclear.ClearExecutor}'s own discrete-key/etherwarp execution, the same engine "Interactive Map"
 * itself already uses to walk a player between rooms.
 */
final class AutoBoulder {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Boulder";
    /** The measured block reach, squared - was 36.0 (6.0 blocks) measured to the centre. */
    private static final double AURA_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    private static final long WALK_TIMEOUT_MS = 15_000L;
    private static final int MAX_AURA_ATTEMPTS = 3;

    private enum Stage { NEED_CHEST, WALK_TO_STAND, WAIT_AT_STAND, AURA, WALK_TO_EXIT, DONE }

    private static Stage stage = Stage.NEED_CHEST;
    private static BlockPos chestReal = null;
    private static BlockPos standReal = null;
    private static BlockPos exitReal = null;
    private static int auraAttempts = 0;
    private static long legStartMs = 0L;
    private static long waitStartMs = 0L;
    private static boolean noChestWarned = false;
    private static boolean mapOffWarned = false;
    private static boolean wasInRoom = false;

    private AutoBoulder() {
    }

    static void levelChanged() {
        reset();
    }

    static void tick(Minecraft client, String roomName) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoBoulderEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (client.screen != null || stage == Stage.DONE) {
            return;
        }
        LocalPlayer player = client.player;
        switch (stage) {
            case NEED_CHEST -> findChestAndSpots(client);
            case WALK_TO_STAND -> walkLeg(client, standReal, Stage.WAIT_AT_STAND, "the standing spot above the chest");
            case WAIT_AT_STAND -> {
                if (System.currentTimeMillis() - waitStartMs >= cfg.getBoulderDelayMs()) {
                    stage = Stage.AURA;
                }
            }
            case AURA -> aura(client, player);
            case WALK_TO_EXIT -> walkLeg(client, exitReal, Stage.DONE, "the exit");
            default -> {
            }
        }
    }

    private static void findChestAndSpots(Minecraft client) {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (entry == null || cr == null) {
            return; // room identity/rotation not known yet - retry next tick
        }
        var chests = entry.secretCoords == null ? null : entry.secretCoords.chest;
        if (chests == null || chests.isEmpty()) {
            if (!noChestWarned) {
                noChestWarned = true;
                LOGGER.warn("[AutoPuzzles] Boulder: no chest secret coordinates known for this room in the room "
                        + "database - Auto Boulder can't find the chest, stopping for this room visit");
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Boulder: "),
                        ModChat.bad("no chest position known"), ModChat.text(" for this room."));
            }
            stage = Stage.DONE;
            return;
        }
        // Back-most known chest (largest relative z) - the doorway override below sits at negative z, so "back"
        // (away from the door) is the larger z, same axis convention every fixed puzzle coordinate in this room
        // already uses (see BoulderSolverFeature's own floor grid, z 9..24).
        RoomEntry.Pos chestRel = chests.get(0);
        for (RoomEntry.Pos p : chests) {
            if (p.z > chestRel.z) {
                chestRel = p;
            }
        }
        RoomEntry.Pos standRel = new RoomEntry.Pos();
        standRel.x = chestRel.x;
        standRel.y = chestRel.y + 3;
        standRel.z = chestRel.z - 3;
        chestReal = RoomDatabase.toRealCoord(chestRel, cr[0], cr[1], cr[2]);
        standReal = RoomDatabase.toRealCoord(standRel, cr[0], cr[1], cr[2]);
        int[] exitRel = AutoClearUtils.roomOverride(ROOM);
        exitReal = exitRel == null ? null : PuzzleCoords.real(exitRel[0], exitRel[1], exitRel[2], cr);
        LOGGER.info("[AutoPuzzles] Boulder: chest at {} (relative {},{},{}) - standing spot {}, exit {}",
                chestReal, chestRel.x, chestRel.y, chestRel.z, standReal, exitReal);
        legStartMs = System.currentTimeMillis();
        stage = Stage.WALK_TO_STAND;
    }

    /** Walks (Interactive-Map-gated) to {@code target}, advancing to {@code nextStage} on arrival. A null target
     *  (no known exit override) or a timed-out walk simply moves on rather than getting stuck forever. */
    private static void walkLeg(Minecraft client, BlockPos target, Stage nextStage, String label) {
        if (target == null) {
            stage = nextStage;
            waitStartMs = System.currentTimeMillis();
            legStartMs = waitStartMs;
            return;
        }
        LocalPlayer player = client.player;
        if (AutoPuzzleUtil.at(player, target)) {
            mapOffWarned = false;
            stage = nextStage;
            waitStartMs = System.currentTimeMillis();
            legStartMs = waitStartMs;
            return;
        }
        if (ClearExecutor.isBusy()) {
            return; // already walking there (ours, or someone else's turn on the executor)
        }
        if (System.currentTimeMillis() - legStartMs > WALK_TIMEOUT_MS) {
            LOGGER.warn("[AutoPuzzles] Boulder: walk to {} ({}) timed out - stopping for this room", target, label);
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Boulder: walk to "), ModChat.bad(label),
                    ModChat.text(" timed out."));
            stage = Stage.DONE;
            return;
        }
        if (!AutoPuzzleUtil.pathIfMapOn(target, null) && !mapOffWarned) {
            mapOffWarned = true;
            LOGGER.info("[AutoPuzzles] Boulder: Interactive Map is off - can't walk to {}", label);
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Boulder needs "), ModChat.value("Interactive Map"),
                    ModChat.text(" on to walk to " + label + "."));
        }
    }

    /** Its own single, deliberate chest interact - never {@code SecretAuraFeature} (which stands down for
     *  {@code ClearExecutor.isBusy()} anyway) - so this fires "even if secret aura is off". */
    private static void aura(Minecraft client, LocalPlayer player) {
        if (auraAttempts >= MAX_AURA_ATTEMPTS) {
            LOGGER.warn("[AutoPuzzles] Boulder: gave up auraing the chest after {} attempts - walking to the exit anyway",
                    MAX_AURA_ATTEMPTS);
            stage = Stage.WALK_TO_EXIT;
            legStartMs = System.currentTimeMillis();
            return;
        }
        BlockPos target = AutoPuzzleUtil.nearestChest(client, player, AURA_REACH_SQ);
        if (target == null) {
            target = chestReal; // fall back to the known database position itself
        }
        // To the box, like the picker above - measuring the gate one way and the choice another is how a
        // module ends up clicking at something it cannot reach.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), target);
        if (player.isShiftKeyDown() || distSq > AURA_REACH_SQ) {
            // Say WHY, with the number.
            //
            // This burned an attempt silently, so "Auto Boulder walked over and then did nothing" was
            // indistinguishable from "it decided not to". The standing spot is chest + (0,+3,-3), which works
            // out at somewhere between 4.4 and 5.25 blocks to the chest's box depending on whether that
            // coordinate is the floor block or the feet - straddling the 4.5 the server enforces. One real
            // run with this line in it settles which, instead of another round of arithmetic.
            if (!player.isShiftKeyDown()) {
                LOGGER.info("[AutoPuzzles] Boulder: chest aura blocked, {} blocks to the box (limit {}) "
                                + "- attempt {} of {}",
                        String.format(java.util.Locale.US, "%.2f", Math.sqrt(distSq)),
                        String.format(java.util.Locale.US, "%.2f", Math.sqrt(AURA_REACH_SQ)),
                        auraAttempts + 1, MAX_AURA_ATTEMPTS);
            }
            auraAttempts++;
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - not burnt, retried next tick
        }
        auraAttempts++;
        if (!AutoPuzzleUtil.interactBlock(client, target)) {
            LOGGER.warn("[AutoPuzzles] Boulder: no clickable shape at {} (attempt {}/{})", target, auraAttempts, MAX_AURA_ATTEMPTS);
            return;
        }
        LOGGER.info("[AutoPuzzles] Boulder: aura'd chest at {}", target);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Boulder: aura'd the "), ModChat.good("secret chest"),
                ModChat.text("."));
        stage = Stage.WALK_TO_EXIT;
        legStartMs = System.currentTimeMillis();
    }

    private static void reset() {
        stage = Stage.NEED_CHEST;
        chestReal = null;
        standReal = null;
        exitReal = null;
        auraAttempts = 0;
        legStartMs = 0L;
        waitStartMs = 0L;
        noChestWarned = false;
        mapOffWarned = false;
    }
}
