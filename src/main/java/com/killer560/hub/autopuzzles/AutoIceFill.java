package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.IceFillSolverConfig;
import com.killer560.hub.puzzlesolvers.IceFillSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Ice Fill - port of QUOI {@code IceFillSolver.kt}'s {@code auto}. QUOI automates it with Aspect of the
 * Void/End teleports: the solver path (QUOI uses the "easy" patterns, so this only runs with the solver's Optimized
 * Path OFF) gets QUOI's {@code stupidStairs} midpoints and {@code fillGaps} 1-block interpolation; while holding an
 * AOTV/AOTE and standing exactly on a path point, after "Ice Fill Delay" ticks it right-clicks aimed from that point's
 * eye position at the next point, stepping one block at a time. Done when relative (15,71,26) becomes packed ice.
 * With "Etherwarp Reposition" on and the player outside y 69.5..72.5 it warps onto the first still-unfilled ice tile
 * like QUOI. Safety addition: requires an AOTV/AOTE in hand (QUOI also passed on non-Skyblock items) and positions are
 * matched with a 1e-4 tolerance instead of exact double equality.
 *
 * <p>A BROKEN SECTION (2026-10-05): when a section's tiles go to air or his feet drop under the section he was on,
 * everything stops until every tile of that section is fresh ice again (Hypixel brings back only that section,
 * "two-ish seconds" later), then he is put back on that section's first tile - etherwarp reposition, or an
 * Interactive Map walk if there is no line - and the hops carry on from there. Finished sections are left alone.
 * See {@link #recover}.
 */
final class AutoIceFill {

    private static final String ROOM = "Ice Fill";
    private static final double EPS = 1e-4;

    private static final AutoGuard GUARD = new AutoGuard("Auto Ice Fill", "Ice Fill Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("IceFill");

    private static List<Vec3> sourcePath = List.of();
    private static List<Vec3> path = List.of();
    private static int lastIndex = -1;
    private static int ticks = 0;
    private static boolean done = false;
    private static boolean optimizedWarned = false;
    private static boolean wasInRoom = false;

    // ---- sections and break recovery (2026-10-05) ----------------------------------------------------------------
    //
    // killer560, on Hypixel: "once it is broken on the main server. It will typically regenerate the path broken
    // after two-ish seconds so if I complete the first of the three sections, then break the second then it would
    // regenerate only the second ... if it breaks it will sense that it is on the floor below it. It just needs to
    // pause everything that it is doing until it regenerates then it needs to make a teleport back onto the ice
    // fill starting position and continue."
    //
    // Before this the only reaction to a break was the "off the ice band" reposition onto the first path tile still
    // made of ICE - which, while the broken section is AIR, is the NEXT section's first tile, so it skipped the
    // broken one and finished a fill the room never counted.

    /** Per path point: which section (0..2) it belongs to - the order its height first appears in the path. */
    private static int[] sectionOf = new int[0];
    /** Per section: the ice tiles under its path points, in path order (stair midpoints excluded). */
    private static List<List<BlockPos>> sectionTiles = List.of();
    /** Per section: the path index of its first tile, which is where a recovery warps back to. */
    private static int[] sectionStart = new int[0];
    /** Stair midpoints added by {@link #stupidStairs}: they stand on the solid step, not on a section's ice. */
    private static final java.util.Set<Vec3> MIDPOINTS =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** The section being recovered, or -1 while solving normally. */
    private static int recovering = -1;
    /** True from the break until every tile of {@link #recovering} reads fresh ICE again. */
    private static boolean awaitingRegen = false;
    /** Ticks spent in the current recovery phase (waiting, then getting back on). */
    private static int recoveryTicks = 0;
    /** Breaks recovered from in this room; past {@link #MAX_RECOVERIES} it stops rather than loop. */
    private static int recoveries = 0;
    /** True when a recovery could not finish; the auto stays off until he leaves the room. */
    private static boolean gaveUp = false;
    /** True while an Interactive Map walk this class started is still under way. */
    private static boolean mapWalkOurs = false;
    /** The last path point he was really standing on, for the "fell below the section" test. -1 when none. */
    private static int lastStood = -1;

    /** Hypixel takes "two-ish seconds"; this is generous on purpose, and saying so in chat when it runs out. */
    private static final int REGEN_TIMEOUT_TICKS = 15 * 20;
    /** How long getting back onto the section's start may take once it is back. */
    private static final int RETURN_TIMEOUT_TICKS = 10 * 20;
    private static final int MAX_RECOVERIES = 5;
    /** Feet this far under the last tile stood on is "on the floor below it" (a section is one block thick). */
    private static final double FELL_BELOW = 1.5;

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autopuzzles");

    /**
     * The last thing {@link #note} logged, so a state that holds for many ticks is one line, not twenty a second.
     *
     * <p>killer560 (2026-10-04): "Ice fill still does that thing where it starts completing it then freezes part
     * way through on sim." His log had nothing to say where: every return in {@link #tick} was silent. Each one
     * now names itself the first tick it applies, and each hop is logged with where it aimed from and to.
     */
    private static String lastNote = "";

    private static void note(String key, String message, Object... args) {
        if (key.equals(lastNote)) {
            return;
        }
        lastNote = key;
        LOGGER.info("[AutoIceFill] " + message, args);
    }

    private AutoIceFill() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        List<Vec3> raw = IceFillSolverFeature.getCurrentPath();
        GUARD.observe(raw.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoIceFillEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(IceFillSolverConfig.getInstance().isEnabled()) || raw.isEmpty() || !GUARD.fresh()
                || McCompat.screen(client) != null || done) {
            return;
        }
        if (IceFillSolverConfig.getInstance().isOptimizedPath()) {
            if (!optimizedWarned) {
                optimizedWarned = true;
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Ice Fill needs the solver's "),
                        ModChat.value("Optimized Path"), ModChat.text(" turned off."));
            }
            return;
        }
        if (!raw.equals(sourcePath)) {
            sourcePath = raw;
            path = fillGaps(stupidStairs(raw));
            indexSections();
            lastIndex = -1;
            lastStood = -1;
            ticks = 0;
            lastNote = "";
            note("path", "path built: {} solver point(s) -> {} hop point(s), {} to {}; {} section(s), starts at {}",
                    raw.size(), path.size(), path.get(0), path.get(path.size() - 1), sectionTiles.size(),
                    java.util.Arrays.toString(sectionStart));
        }
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (cr == null) {
            note("noroom", "waiting: the live map has no clay corner/rotation for this room yet");
            return;
        }
        if (client.level.getBlockState(PuzzleCoords.real(15, 71, 26, cr)).is(Blocks.PACKED_ICE)) {
            note("done", "done: the finish tile {} is packed ice", PuzzleCoords.real(15, 71, 26, cr));
            done = true;
            REPOSITION.cancel(client);
            AutoReposition.releaseSneak(client);
            return;
        }
        if (gaveUp) {
            note("gaveup", "stopped: a break recovery did not finish - leave and re-enter the room to retry");
            return;
        }
        LocalPlayer player = client.player;
        // A BREAK COMES FIRST, before any reposition or hop: a section whose tiles have gone to air, or feet under
        // the section he was standing on. Everything else waits until that section is back.
        if (recovering < 0) {
            int broken = brokenSection(client);
            int fell = broken >= 0 ? -1 : fellFromSection(player);
            if (broken >= 0 || fell >= 0) {
                beginRecovery(client, broken >= 0 ? broken : fell,
                        broken >= 0 ? "its tiles are gone" : "you are on the floor below it");
            }
        }
        if (recovering >= 0) {
            recover(client, player, cfg);
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        // 69.5..72.5 is the ROOM-RELATIVE band of this room's three ice levels, and the sim shifts every room
        // vertically. Without the shift the test read "he is nowhere near the ice" on every sim floor and Auto
        // Ice Fill spent the room warping onto the first unfilled tile instead of walking the path. Zero on a
        // real run - the same DungeonLayout.simYOffset() every height in this mod goes through.
        // This is the way ONTO the fill, not back after a break: a broken section is caught above and recovered
        // on its own, so "the first tile still ICE" here can no longer be the next section's while one is air.
        double floor = com.killer560.hub.livemap.DungeonLayout.simYOffset();
        if (cfg.isEtherwarpReposition() && (player.getY() < 69.5 + floor || player.getY() > 72.5 + floor)) {
            if (AutoPuzzleUtil.isMoving(player)) {
                return;
            }
            for (Vec3 vec : path) {
                BlockPos below = BlockPos.containing(vec).below();
                if (client.level.getBlockState(below).is(Blocks.ICE)) {
                    note("repos" + below, "off the ice band at y {} (band {}..{}) - etherwarp reposition onto {}",
                            player.getY(), 69.5 + floor, 72.5 + floor, below);
                    REPOSITION.start(client, below, false, true, true);
                    return;
                }
            }
        }
        if (!AutoPuzzleUtil.isAotv(player.getMainHandItem())) {
            note("aotv", "waiting: no Aspect of the Void/End in the main hand");
            return;
        }
        int index = -1;
        for (int i = 0; i < path.size(); i++) {
            Vec3 p = path.get(i);
            if (Math.abs(player.getX() - p.x) < EPS && Math.abs(player.getY() + 0.1 - p.y) < EPS && Math.abs(player.getZ() - p.z) < EPS) {
                index = i;
                break;
            }
        }
        if (index == -1 || index >= path.size() - 1) {
            if (index == -1) {
                // The case that reads as "it froze": the last hop did not land on the next path point. Say where
                // he is, which point the auto expected, and the nearest one, so a stall names its own cause.
                int nearest = -1;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < path.size(); i++) {
                    double d = path.get(i).distanceToSqr(player.getX(), player.getY() + 0.1, player.getZ());
                    if (d < best) {
                        best = d;
                        nearest = i;
                    }
                }
                String expected = lastIndex >= 0 && lastIndex < path.size() ? String.valueOf(path.get(lastIndex)) : "none";
                note("off" + lastIndex, "stopped: standing at ({}, {}, {}), which is no path point - "
                                + "expected point {} {}, nearest is point {} at {} block(s)",
                        String.format(java.util.Locale.ROOT, "%.3f", player.getX()),
                        String.format(java.util.Locale.ROOT, "%.3f", player.getY()),
                        String.format(java.util.Locale.ROOT, "%.3f", player.getZ()),
                        lastIndex, expected, nearest,
                        String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(best)));
            } else {
                note("end", "at the last path point ({}) - nothing left to hop", index);
            }
            lastIndex = -1;
            return;
        }
        lastStood = index;
        if (lastIndex == -1 || index > lastIndex) {
            lastIndex = index;
            ticks = 0;
        }
        if (lastIndex >= path.size() - 1) {
            return;
        }
        // ticks is only advanced on a tick we did NOT warp on: >= (not ==) so that a tick the gate holds back simply
        // leaves the warp due, and the very next allowed tick takes it. The unblocked cadence is unchanged.
        if (cfg.isIceFillAdaptive()) {
            // Adaptive: no fixed delay - the next hop goes the moment the SERVER has taken the tile you stand on
            // (plain ice turns to packed ice when it registers you on it). A slow server just means a longer wait
            // here, never a hop off a tile it hasn't counted, so lag can't make it fail. Start / stair points are
            // not ice at all and go straight away.
            Vec3 here = path.get(lastIndex);
            if (client.level.getBlockState(BlockPos.containing(here).below()).is(Blocks.ICE)) {
                note("wait" + lastIndex, "adaptive: waiting for point {}'s tile to turn to packed ice", lastIndex);
                return;
            }
            ticks = Integer.MAX_VALUE - 1;
        }
        if (ticks + 1 >= cfg.getIceFillDelayTicks()) {
            Vec3 current = path.get(lastIndex);
            Vec3 next = path.get(lastIndex + 1);
            Vec3 from = new Vec3(current.x, current.y - 0.1 + player.getEyeHeight(), current.z);
            float[] dir = AutoPuzzleUtil.direction(from, next);
            if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
                note("gate" + lastIndex, "hop {} held back by the action gate this tick", lastIndex);
                return; // gate held this tick back - nothing warped, so lastIndex / ticks must not move
            }
            // "standing on" is the path point he is really on this tick. It differs from the hop's own start
            // only when the last hop has not landed yet, and then this hop is aimed from a point he is not at.
            note("hop" + lastIndex, "hop {} -> {}: from {} to {} (yaw {}, pitch {}), standing on point {}",
                    lastIndex, lastIndex + 1, current, next, String.format(java.util.Locale.ROOT, "%.1f", dir[0]),
                    String.format(java.util.Locale.ROOT, "%.1f", dir[1]), index);
            // This warp is our own, so waive the gate's teleport stand-down for the next hop - otherwise the
            // 6-tick teleport window would override the 2-tick Delay setting on every single step of the path.
            com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD);
            lastIndex++;
            ticks = 0;
        } else {
            ticks++;
        }
    }

    /**
     * Splits {@link #path} into its sections. A section is one of the room's three ice sheets, and they sit at three
     * different heights, so a point's section is the order its height first appears along the path. Stair midpoints
     * stand on the solid step between two sheets and are no section's tile.
     */
    private static void indexSections() {
        List<Long> heights = new ArrayList<>();
        sectionOf = new int[path.size()];
        List<List<BlockPos>> tiles = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Vec3 p = path.get(i);
            long key = Math.round(p.y * 10);
            int s = heights.indexOf(key);
            if (s < 0) {
                heights.add(key);
                s = heights.size() - 1;
                tiles.add(new ArrayList<>());
                starts.add(-1);
            }
            sectionOf[i] = s;
            if (MIDPOINTS.contains(p)) {
                continue;
            }
            BlockPos tile = BlockPos.containing(p).below();
            if (!tiles.get(s).contains(tile)) {
                tiles.get(s).add(tile);
            }
            if (starts.get(s) < 0) {
                starts.set(s, i);
            }
        }
        sectionTiles = tiles;
        sectionStart = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The first section with at least half its tiles gone to air - broken, waiting to come back - or -1. */
    private static int brokenSection(Minecraft client) {
        for (int s = 0; s < sectionTiles.size(); s++) {
            if (isBroken(client, s)) {
                return s;
            }
        }
        return -1;
    }

    private static boolean isBroken(Minecraft client, int s) {
        List<BlockPos> tiles = sectionTiles.get(s);
        int air = 0;
        for (BlockPos t : tiles) {
            if (client.level.getBlockState(t).isAir()) {
                air++;
            }
        }
        return !tiles.isEmpty() && air * 2 >= tiles.size();
    }

    /** How many of a section's tiles are fresh (unwalked) ice. All of them is "regenerated". */
    private static int freshTiles(Minecraft client, int s) {
        int fresh = 0;
        for (BlockPos t : sectionTiles.get(s)) {
            if (client.level.getBlockState(t).is(Blocks.ICE)) {
                fresh++;
            }
        }
        return fresh;
    }

    /** The section he was last standing on, if his feet are now well under it - "on the floor below it". */
    private static int fellFromSection(LocalPlayer player) {
        if (lastStood < 0 || lastStood >= path.size()) {
            return -1;
        }
        double feet = path.get(lastStood).y - 0.1;
        return player.getY() < feet - FELL_BELOW ? sectionOf[lastStood] : -1;
    }

    private static boolean onPoint(LocalPlayer player, Vec3 p) {
        return Math.abs(player.getX() - p.x) < EPS && Math.abs(player.getY() + 0.1 - p.y) < EPS
                && Math.abs(player.getZ() - p.z) < EPS;
    }

    /** Stops every hop, warp and walk this auto has going, and starts waiting for section {@code s} to come back. */
    private static void beginRecovery(Minecraft client, int s, String why) {
        stopMoving(client);
        lastIndex = -1;
        lastStood = -1;
        ticks = 0;
        recoveries++;
        if (recoveries > MAX_RECOVERIES) {
            giveUp(client, "section " + (s + 1) + " broke " + recoveries + " times in this room");
            return;
        }
        recovering = s;
        awaitingRegen = true;
        recoveryTicks = 0;
        LOGGER.info("[AutoIceFill] recovery: section {} broke ({}) - paused, waiting for it to regenerate "
                + "(break {} in this room)", s + 1, why, recoveries);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.bad("Auto Ice Fill: section " + (s + 1) + " broke"),
                ModChat.text(" - paused until it regenerates."));
    }

    /**
     * One tick of a recovery: wait (doing nothing at all) until every tile of the broken section is fresh ice again,
     * then get back onto that section's first tile the same way the auto gets onto the fill to begin with - an
     * etherwarp reposition, or an Interactive Map walk when no warp line exists - and hand back to the hops, which
     * carry on from that tile. Sections before it are finished and stay finished; nothing re-walks them.
     */
    private static void recover(Minecraft client, LocalPlayer player, AutoPuzzlesConfig cfg) {
        int s = recovering;
        recoveryTicks++;
        if (awaitingRegen) {
            int fresh = freshTiles(client, s);
            int total = sectionTiles.get(s).size();
            if (fresh < total) {
                if (recoveryTicks >= REGEN_TIMEOUT_TICKS) {
                    giveUp(client, "section " + (s + 1) + " did not regenerate in " + REGEN_TIMEOUT_TICKS / 20
                            + "s (" + fresh + " of " + total + " tiles back)");
                    return;
                }
                note("regen" + s, "recovery: paused, waiting for section {} to regenerate ({} of {} tile(s) "
                        + "fresh ice)", s + 1, fresh, total);
                return;
            }
            awaitingRegen = false;
            LOGGER.info("[AutoIceFill] recovery: section {} regenerated after {} tick(s) - getting back onto its "
                    + "start {}", s + 1, recoveryTicks, sectionTiles.get(s).get(0));
            recoveryTicks = 0;
        }
        if (isBroken(client, s)) {
            // Broke again before he was back on it (or the warp back went wrong) - wait for it all over again.
            stopMoving(client);
            awaitingRegen = true;
            recoveryTicks = 0;
            LOGGER.info("[AutoIceFill] recovery: section {} broke again before he was back on it - waiting", s + 1);
            return;
        }
        Vec3 start = path.get(sectionStart[s]);
        if (onPoint(player, start)) {
            LOGGER.info("[AutoIceFill] recovery: back on section {}'s start {} after {} tick(s) - continuing",
                    s + 1, start, recoveryTicks);
            recovering = -1;
            lastIndex = -1;
            ticks = 0;
            mapWalkOurs = false;
            lastNote = "";
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        if (mapWalkOurs) {
            if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
                return;
            }
            mapWalkOurs = false;
        }
        if (recoveryTicks >= RETURN_TIMEOUT_TICKS) {
            giveUp(client, "could not get back onto section " + (s + 1) + "'s start in "
                    + RETURN_TIMEOUT_TICKS / 20 + "s");
            return;
        }
        BlockPos tile = sectionTiles.get(s).get(0);
        if (!cfg.isEtherwarpReposition()) {
            note("retnorepos" + s, "recovery: section {} is back - Etherwarp Reposition is off, so stand on its "
                    + "start {} to carry on", s + 1, tile);
            return;
        }
        if (AutoPuzzleUtil.isMoving(player)) {
            return;
        }
        if (REPOSITION.start(client, tile, false, true, true)) {
            note("ret" + s, "recovery: etherwarping back onto section {}'s start {}", s + 1, tile);
            return;
        }
        if (AutoPuzzleUtil.pathIfMapOn(tile, null)) {
            mapWalkOurs = true;
            note("retwalk" + s, "recovery: no etherwarp line onto section {}'s start {} - walking there with the "
                    + "Interactive Map", s + 1, tile);
            return;
        }
        note("retwait" + s, "recovery: waiting - no etherwarp line onto section {}'s start {} from here, and "
                + "pathing / the Interactive Map is off", s + 1, tile);
    }

    /** Cancels this auto's own reposition and map walk and lets go of sneak. Never touches anything it did not start. */
    private static void stopMoving(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        if (mapWalkOurs) {
            mapWalkOurs = false;
            com.killer560.hub.livemap.autoclear.ClearExecutor.cancel();
        }
    }

    private static void giveUp(Minecraft client, String why) {
        stopMoving(client);
        recovering = -1;
        awaitingRegen = false;
        gaveUp = true;
        LOGGER.info("[AutoIceFill] recovery: gave up - {}", why);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.bad("Auto Ice Fill stopped: "), ModChat.text(why
                + ". Leave and re-enter the room to try again."));
    }

    /** QUOI fillGaps: insert 1-block interpolated points between consecutive path points. */
    private static List<Vec3> fillGaps(List<Vec3> points) {
        if (points.isEmpty()) {
            return points;
        }
        List<Vec3> updated = new ArrayList<>(points.size() * 2);
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 p1 = points.get(i);
            Vec3 p2 = points.get(i + 1);
            updated.add(p1);
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double dz = p2.z - p1.z;
            int steps = (int) Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            for (int s = 1; s < steps; s++) {
                double r = (double) s / steps;
                updated.add(new Vec3(p1.x + dx * r, p1.y + dy * r, p1.z + dz * r));
            }
        }
        updated.add(points.get(points.size() - 1));
        return updated;
    }

    /**
     * QUOI stupidStairs: add a midpoint before the first point at y 71.1 and before the first at y 72.1.
     *
     * <p>Both heights carry the sim's floor shift. 71.1 and 72.1 are Hypixel heights for the two steps up out
     * of this room's basin, and the path these are matched against is built from the solver's own real
     * positions - which in the sim are shifted by {@code SimAltitude.offset()} like everything else. Without
     * the shift neither comparison could ever be true in there, so both midpoints were silently dropped and the
     * walk tried to climb the steps without them.
     */
    private static List<Vec3> stupidStairs(List<Vec3> points) {
        if (points.isEmpty()) {
            return points;
        }
        double shift = com.killer560.hub.livemap.DungeonLayout.simYOffset();
        double first = 71.1 + shift;
        double second = 72.1 + shift;
        MIDPOINTS.clear();
        List<Vec3> updated = new ArrayList<>(points.size() + 2);
        Vec3 lastPoint = points.get(0);
        boolean added71 = false;
        boolean added72 = false;
        for (Vec3 point : points) {
            if (!added71 && Math.abs(point.y - first) < EPS) {
                Vec3 mid = new Vec3((lastPoint.x + point.x) / 2, first, (lastPoint.z + point.z) / 2);
                MIDPOINTS.add(mid);
                updated.add(mid);
                added71 = true;
            } else if (!added72 && Math.abs(point.y - second) < EPS) {
                Vec3 mid = new Vec3((lastPoint.x + point.x) / 2, second, (lastPoint.z + point.z) / 2);
                MIDPOINTS.add(mid);
                updated.add(mid);
                added72 = true;
            }
            updated.add(point);
            lastPoint = point;
        }
        return updated;
    }

    private static void reset(Minecraft client) {
        if (recovering >= 0) {
            LOGGER.info("[AutoIceFill] recovery of section {} abandoned - left the room or switched off", recovering + 1);
        }
        stopMoving(client);
        recovering = -1;
        awaitingRegen = false;
        recoveryTicks = 0;
        recoveries = 0;
        gaveUp = false;
        lastStood = -1;
        sectionOf = new int[0];
        sectionTiles = List.of();
        sectionStart = new int[0];
        sourcePath = List.of();
        path = List.of();
        lastIndex = -1;
        ticks = 0;
        done = false;
        optimizedWarned = false;
        lastNote = "";
    }
}
