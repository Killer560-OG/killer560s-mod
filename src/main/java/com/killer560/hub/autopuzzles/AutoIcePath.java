package com.killer560.hub.autopuzzles;

import com.killer560.hub.puzzlesolvers.IcePathSolverConfig;
import com.killer560.hub.puzzlesolvers.IcePathSolverFeature;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Ice Path - port of QUOI {@code IcePathSolver.kt}'s {@code auto(...)} on top of {@link IcePathSolverFeature}.
 * Standing on the silverfish's cell (x/z of the silverfish, the ice's y), it shoots straight down (pitch 90) with the
 * yaw of the board direction towards the next stop, so the arrow knocks the silverfish that way, paced by the Shoot
 * cooldown. While the silverfish slides it waits. With "Etherwarp Reposition" on it also warps onto the silverfish's
 * cell (and, while it slides, onto the next stop) like QUOI; with it off you stand there yourself.
 * Safety addition: only fires while holding a shortbow (QUOI relied on its reposition having swapped to one).
 * <p>
 * Miss cooldown removed (killer560, 2026-09-27: "For auto puzzles remove the miss cooldown."): it used to hold
 * every shot for an extra {@code missCooldownMs} on top of the Shoot cooldown in case that shot missed and the
 * silverfish never started sliding. Now the Shoot cooldown alone paces the next attempt, hit or miss.
 * <p>
 * <b>Every decline is logged</b> (2026-10-04, "auto ice path isn't working" with not one line from this class in
 * his log): each gate names itself once per change of reason, and every shot is logged with its yaw and target.
 */
final class AutoIcePath {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String ROOM = "Ice Path";
    /** Shots at one resting cell before it is worth a WARN that the silverfish is not moving. */
    private static final int STUCK_SHOTS = 3;

    private static final AutoGuard GUARD = new AutoGuard("Auto Ice Path", "Ice Path Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("IcePath");

    private static long lastShotTime = 0L;
    private static boolean wasInRoom = false;
    private static String lastSaid = null;
    private static BlockPos shotCell = null;
    private static int shotsAtCell = 0;

    private AutoIcePath() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    /** One INFO line per change of state, so a per-tick decline is one line, not twenty a second. */
    private static void say(String what) {
        if (what.equals(lastSaid)) {
            return;
        }
        lastSaid = what;
        LOGGER.info("[AutoIcePath] {}", what);
    }

    static void tick(Minecraft client, String roomName) {
        List<Vec3> path = IcePathSolverFeature.getPath();
        GUARD.observe(path.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            lastSaid = null;
            return;
        }
        if (!cfg.isAutoIcePathEnabled()) {
            if (wasInRoom) {
                reset(client);
            }
            wasInRoom = false;
            if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                say("in Ice Path - Auto Ice Path is off in settings");
            }
            return;
        }
        if (!wasInRoom) {
            say("in Ice Path - auto on, reposition " + (cfg.isEtherwarpReposition() ? "on" : "off"));
        }
        wasInRoom = true;
        if (!GUARD.solverOn(IcePathSolverConfig.getInstance().isEnabled())) {
            say("waiting: Ice Path Solver is off");
            return;
        }
        Silverfish fish = IcePathSolverFeature.getSilverfish();
        LocalPlayer player = client.player;
        if (!GUARD.fresh()) {
            say("waiting: solver data predates this world");
            return;
        }
        if (McCompat.screen(client) != null) {
            say("waiting: a screen is open");
            return;
        }
        if (fish == null) {
            say("waiting: the solver has no silverfish");
            return;
        }
        if (path.size() < 2) {
            say(path.isEmpty() ? "waiting: the solver has no path yet" : "done: the silverfish is at the exit");
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        long now = System.currentTimeMillis();
        BlockPos nextSpot = BlockPos.containing(path.get(1));

        if (IcePathSolverFeature.isSilverfishMoving()) {
            say("waiting: the silverfish is sliding");
            if (reposition && !AutoPuzzleUtil.at(player, nextSpot)) {
                REPOSITION.start(client, nextSpot, true, false, false);
            }
            return;
        }
        if (now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        BlockPos fishPos = fish.blockPosition();
        // THE SOLVER'S OWN HEIGHT, not a literal 66 plus a correction.
        //
        // Every point in `path` is the solver's `PuzzleCoords.real(col, 66, row)`, so its y already IS the ice's
        // world height - whole-floor shift, per-room nudge and all. Re-deriving it here from the literal 66 meant
        // two expressions that had to stay equal, and they had already drifted apart once (the sim's floor shift
        // was missing) and would have drifted again the moment a room needed a nudge. `path.size() < 2` has
        // already been checked above, so element 0 exists.
        BlockPos currSpot = new BlockPos(fishPos.getX(), (int) Math.floor(path.get(0).y), fishPos.getZ());
        if (!AutoPuzzleUtil.at(player, currSpot)) {
            // The etherwarp-visibility check used to sit in front of this for everyone, and returned silently -
            // it only matters when warping, and AutoReposition does it (and now says so) itself.
            if (reposition) {
                say("repositioning onto the silverfish's cell " + AutoPuzzleUtil.fmt(currSpot));
                REPOSITION.start(client, currSpot, true, false, false);
            } else {
                say("waiting: stand on the silverfish's cell " + AutoPuzzleUtil.fmt(currSpot)
                        + " (Etherwarp Reposition is off)");
            }
            return;
        }
        if (!AutoPuzzleUtil.isShortbow(player.getMainHandItem())) {
            if (!AutoPuzzleUtil.swapTo(client, player, AutoPuzzleUtil::isShortbow)) {
                say("waiting: no shortbow (\"" + AutoPuzzleUtil.SHORTBOW_LORE + "\" in its lore) in the hotbar");
            }
            return; // fire on a later tick, once the swap has gone through
        }
        // The yaw of the BOARD direction, cell centre to cell centre - exactly along a row or a column.
        //
        // This was the yaw of `etherwarpDirection(nextSpot)`, the aim at whatever point on the next stop's block an
        // etherwarp ray could reach. From on top of the board the top-face centre never matches (it is filed under
        // the air above) and the point used is one 0.001 from an EDGE of the next block, so the yaw was off the
        // board axis by up to 45 degrees for a stop one cell away - a coin toss between two directions on Hypixel
        // too - and the shot was refused outright whenever no such point was visible. The direction of a shove is a
        // board direction; no ray is needed to know it.
        float yaw = AutoPuzzleUtil.direction(Vec3.atCenterOf(currSpot), Vec3.atCenterOf(nextSpot))[0];
        if (!AutoPuzzleUtil.useItemRotated(client, player, yaw, 90f)) {
            return; // gate held this tick back - no shot, so lastShotTime must not move
        }
        lastShotTime = now;
        if (!currSpot.equals(shotCell)) {
            shotCell = currSpot;
            shotsAtCell = 0;
        }
        shotsAtCell++;
        lastSaid = null; // whatever it waits on after a shot is news again
        LOGGER.info("[AutoIcePath] shot {} from {} toward the next stop {} (yaw {}), {} stop(s) left",
                shotsAtCell, AutoPuzzleUtil.fmt(currSpot), AutoPuzzleUtil.fmt(nextSpot),
                String.format(java.util.Locale.US, "%.1f", yaw), path.size() - 1);
        if (shotsAtCell == STUCK_SHOTS) {
            LOGGER.warn("[AutoIcePath] {} shots from {} and the silverfish has not moved - the shots are landing"
                    + " (or not) without shoving it", STUCK_SHOTS, AutoPuzzleUtil.fmt(currSpot));
        }
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        lastShotTime = 0L;
        shotCell = null;
        shotsAtCell = 0;
    }
}
