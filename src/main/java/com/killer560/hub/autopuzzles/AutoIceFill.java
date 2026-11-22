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
 * Auto Ice Fill - port of 's {@code auto}. The reference automates it with Aspect of the
 * Void/End teleports: the solver path (the reference uses the "easy" patterns, so this only runs with the solver's Optimized
 * Path OFF) gets the {@code stupidStairs} midpoints and {@code fillGaps} 1-block interpolation; while holding an
 * AOTV/AOTE and standing on a path point (an etherwarp landing's hover counts), once the last hop has LANDED and "Ice
 * Fill Delay" ticks have passed since, it right-clicks aimed from that point's
 * eye position at the next point, stepping one block at a time. Done when relative (15,71,26) becomes packed ice.
 * With "Etherwarp Reposition" on and the player outside y 69.5..72.5 it warps onto the first still-unfilled ice tile
 * like the reference. Safety addition: requires an AOTV/AOTE in hand (the reference also passed on non-Skyblock items) and positions are
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
    /** Held from the first warp until the finish tile is packed ice (see {@link FreeCam}). */
    private static final FreeCam CAMERA = new FreeCam("IceFill");
    private static final AutoReposition REPOSITION = new AutoReposition("IceFill", CAMERA);
    /** The reward chests at the end of the fill, past the finish tile (15, 71, 26); see {@link RewardChest}. */
    private static final RewardChest CHEST = new RewardChest("Ice Fill", new int[]{15, 72, 28},
            new int[]{0, 66, 0, 31, 82, 31}, 2);

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
    /** util/ServerLag on a recovery's first tick: a server stall extends the regenerate/return timeouts by its length. */
    private static long recoveryLag = 0L;
    /** Breaks recovered from in this room; past {@link #MAX_RECOVERIES} it stops rather than loop. */
    private static int recoveries = 0;
    /** True when a recovery could not finish; the auto stays off until he leaves the room. */
    private static boolean gaveUp = false;
    /** True while an Interactive Map walk this class started is still under way. */
    private static boolean mapWalkOurs = false;
    /** The last path point he was really standing on, for the "fell below the section" test. -1 when none. */
    private static int lastStood = -1;
    /** Ticks since the last hop went with no landing on its point yet, and util/ServerLag when it went. */
    private static int landWait = 0;
    private static long hopLag = 0L;
    /** A hop with no landing in this long (plus any server stall since) was dropped: it goes again. */
    private static final int LANDING_RESEND_TICKS = 20;
    /** An etherwarp lands him 0.05 over the block and gravity sets him down a tick later: both count as on the point. */
    private static final double HOVER = 0.06;
    /** The eye heights Hypixel's etherwarp was seen casting from (1.27 modern sneak, 1.54 1.8 sneak, 1.62 standing). */
    private static final double[] SERVER_EYES = {1.27, 1.54, 1.62};
    /** The block the recovery warp under way was aimed onto (the section's first tile or the stair step before it). */
    private static BlockPos warpTarget = null;

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
        LOGGER.info("[AutoIceFill] " + message + " (tick " + com.killer560.hub.util.SessionRecorderSink.tick() + ")",
                args);
    }

    /** Tick of the first hop in this room, for elapsed-tick lines; -1 before it. */
    private static long firstHopTick = -1L;
    /** Hops sent in this room. */
    private static int hopsSent = 0;

    private static long sinceFirstHop() {
        return firstHopTick < 0 ? -1 : com.killer560.hub.util.SessionRecorderSink.tick() - firstHopTick;
    }

    private AutoIceFill() {
    }

    /** True once the finish tile was seen packed ice while he was in the room (reset when he leaves it). */
    static boolean isDone() {
        return done;
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        CAMERA.drop();
        reset(client);
    }

    static void tick(Minecraft client, String roomName) {
        // getCurrentPath() copies the whole path: only while the guard can still learn from it or the auto runs.
        List<Vec3> raw = GUARD.observing() ? IceFillSolverFeature.getCurrentPath() : null;
        if (raw != null) {
            GUARD.observe(raw.isEmpty());
        }
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoIceFillEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            return;
        }
        if (raw == null) {
            raw = IceFillSolverFeature.getCurrentPath();
        }
        wasInRoom = true;
        CAMERA.keep(client.player);
        if (!IceFillSolverConfig.getInstance().isEnabled()) {
            CAMERA.release(client.player);
        }
        if (done) {
            // Secret Aura skips Ice Fill entirely, so the chests at the end were never opened (killer560, 2026-10-15).
            CHEST.start();
            CHEST.tick(client);
            return;
        }
        if (!GUARD.solverOn(IceFillSolverConfig.getInstance().isEnabled()) || raw.isEmpty() || !GUARD.fresh()
                || McCompat.screen(client) != null) {
            // Pure reads only, to name which of the four held it (the guard calls above have already run).
            if (!IceFillSolverConfig.getInstance().isEnabled()) {
                note("g-solver", "waiting: Ice Fill Solver is off");
            } else if (raw.isEmpty()) {
                note("g-empty", "waiting: the solver has no path yet");
            } else if (McCompat.screen(client) != null) {
                note("g-screen", "waiting: a screen is open");
            } else {
                note("g-guard", "waiting: the guard holds it (solver data predates this world, or it is not trusted yet)");
            }
            return;
        }
        if (IceFillSolverConfig.getInstance().isOptimizedPath()) {
            if (!optimizedWarned) {
                optimizedWarned = true;
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Ice Fill needs the solver's "),
                        ModChat.value("Alternate Path"), ModChat.text(" turned off."));
            }
            note("optimized", "refused: the solver's Optimized Path is on - Auto Ice Fill needs it off");
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
            note("done", "done: the finish tile {} is packed ice - {} hop(s) sent, {} tick(s) since the first hop,"
                    + " {} break recovery(ies)", PuzzleCoords.real(15, 71, 26, cr), hopsSent, sinceFirstHop(), recoveries);
            done = true;
            REPOSITION.cancel(client);
            AutoReposition.releaseSneak(client);
            CAMERA.release(client.player);
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
            note("reposactive", "waiting: etherwarp reposition under way");
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
                note("reposmoving", "off the ice band at y {} - waiting for him to stop moving before a reposition",
                        player.getY());
                return;
            }
            for (Vec3 vec : path) {
                BlockPos below = BlockPos.containing(vec).below();
                if (client.level.getBlockState(below).is(Blocks.ICE)) {
                    note("repos" + below, "off the ice band at y {} (band {}..{}) - etherwarp reposition onto {}",
                            player.getY(), 69.5 + floor, 72.5 + floor, below);
                    boolean started = REPOSITION.start(client, below, false, true, true);
                    if (!started) {
                        note("reposno" + below, "reposition onto {} refused (no etherwarp line from here)", below);
                    }
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
            if (onPoint(player, path.get(i))) {
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
                note("off" + lastIndex, "waiting: standing at ({}, {}, {}), which is no path point - "
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
        // A HOP GOES ONLY FROM THE POINT HE IS STANDING ON (icefillbugs-1118). With Delay 1 and Adaptive off a hop went
        // every tick whatever he stood on ("hop 2 -> 3 ... standing on point 0"), so on his 2026-11-18 Hypixel run two
        // uses reached the server in one of its ticks, it saw him move two tiles at once ("Don't move diagonally! Bad!",
        // pos ids 11 and 12 in one tick) and section 1, then 2, broke five times. So the next hop waits for the last
        // one's landing, the stair step's too: two stair hops handled in one server tick are a two-block move from the
        // exit tile to the next section's entry. A landing that never comes (a dropped use) is hopped again from where
        // he stands.
        if (index != lastIndex) {
            if (++landWait >= LANDING_RESEND_TICKS + com.killer560.hub.util.ServerLag.ticksSince(hopLag)) {
                note("relanding" + lastIndex, "no landing on point {} {} tick(s) after its hop - hopping again from"
                        + " point {}, where he stands", lastIndex, landWait, index);
                lastIndex = index;
                ticks = 0;
            } else {
                // The landing is the server position packet that sets the gate's 1-tick teleport settle; one arriving
                // after the hop's 3-tick waiver ran out denied the next hop and cost ~3 ticks (acpuzzles-1121).
                com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD, 2);
                note("landing" + lastIndex, "waiting: hop to point {} not landed yet (standing on point {})",
                        lastIndex, index);
            }
            return;
        }
        if (player.isShiftKeyDown() || client.options.keyShift.isDown()) {
            // A use while sneaking is an etherwarp, not the one-block hop (an etherwarp reposition lets go of its
            // sneak in its landing tick; the server hears that a tick later).
            note("sneak" + lastIndex, "waiting: sneak is held - a hop now would be an etherwarp");
            return;
        }
        // EACH SECTION'S FIRST TILE IS WAITED FOR, adaptive or not (killer560, 2026-10-15: "it needs to wait for the
        // first block to register for each section before it can move on to using the normal etherwarping tick
        // ratio"). The tile under it turns packed ice when the server has him on it; until then nothing hops on. No
        // clock: the hop goes the tick the tile reads packed, the delay already counted out meanwhile.
        int section = sectionStartOf(lastIndex);
        if (section >= 0 && client.level.getBlockState(BlockPos.containing(path.get(lastIndex)).below())
                .is(Blocks.ICE)) {
            ticks = Math.min(ticks + 1, Integer.MAX_VALUE - 1);
            note("first" + lastIndex, "section {}: waiting for its first tile (point {}) to register before hopping on",
                    section + 1, lastIndex);
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
            float[] dir = sendHop(client, player, current, next);
            if (dir == null) {
                note("gate" + lastIndex, "hop {} held back by the action gate this tick", lastIndex);
                return; // gate held this tick back - nothing warped, so lastIndex / ticks must not move
            }
            // "standing on" is the path point he is really on this tick. It differs from the hop's own start
            // only when the last hop has not landed yet, and then this hop is aimed from a point he is not at.
            if (firstHopTick < 0) {
                firstHopTick = com.killer560.hub.util.SessionRecorderSink.tick();
            }
            hopsSent++;
            note("hop" + lastIndex, "hop {} -> {}: from {} to {} (yaw {}, pitch {}), standing on point {}, item {},"
                            + " section {}, delay {} tick(s){}, hop #{} at {} tick(s) since the first",
                    lastIndex, lastIndex + 1, current, next, String.format(java.util.Locale.ROOT, "%.1f", dir[0]),
                    String.format(java.util.Locale.ROOT, "%.1f", dir[1]), index,
                    player.getMainHandItem().getHoverName().getString(),
                    lastIndex < sectionOf.length ? sectionOf[lastIndex] + 1 : -1, cfg.getIceFillDelayTicks(),
                    cfg.isIceFillAdaptive() ? " (adaptive)" : "", hopsSent, sinceFirstHop());
            lastIndex++;
            ticks = 0;
            landWait = 0;
            hopLag = com.killer560.hub.util.ServerLag.mark();
        } else {
            LOGGER.debug("[AutoIceFill] point {}: delay tick {} of {}", lastIndex, ticks + 1, cfg.getIceFillDelayTicks());
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

    /** The section whose first tile is path point {@code index}, or -1. */
    private static int sectionStartOf(int index) {
        for (int s = 0; s < sectionStart.length; s++) {
            if (sectionStart[s] == index) {
                return s;
            }
        }
        return -1;
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

    /**
     * Standing on path point {@code p} (feet 0.1 under it), or hovering {@link #HOVER} over it the tick an etherwarp
     * landed him there. Exact Y alone read the landing tick as "no path point", so on his 2026-11-18 run a recovery
     * warp that had arrived (y 71.05) sent a second etherwarp straight down, and the first hop went before that one
     * landed.
     */
    private static boolean onPoint(LocalPlayer player, Vec3 p) {
        double over = player.getY() + 0.1 - p.y;
        return Math.abs(player.getX() - p.x) < EPS && Math.abs(player.getZ() - p.z) < EPS && over > -EPS
                && over < HOVER;
    }

    /**
     * A look that etherwarps him onto {@code tile} from where he stands whichever eye height the server casts from, or
     * null. His 2026-11-18 run: from (-22.5, 67.5, -27.5) the same look (yaw 90, pitch -35.0) landed on the section's
     * first tile once and on its neighbor four seconds later, and from (-26.5, 66, -24.5) a look that met the tile's
     * underside 0.04 block from the neighbor's (1.27 eye) landed on the neighbor, where a 1.54 or 1.62 eye puts it. A
     * landing on another tile of the section spends that tile, and the walk then breaks on it ("Oops! You stepped on
     * the wrong block!"). So every face point is tried and a look is taken only when it holds (TeleportUtils.holdsOn,
     * the planner's margin) from all three eyes.
     */
    static float[] firmAim(LocalPlayer player, BlockPos tile) {
        Vec3 feet = player.position();
        Vec3 eye = feet.add(0, AutoPuzzleUtil.EYE_SNEAKING, 0);
        double[] grid = {0.5, 0.3, 0.7, 0.15, 0.85};
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
            for (double u : grid) {
                for (double v : grid) {
                    Vec3 point = facePoint(tile, face, u, v);
                    var r = com.killer560.hub.livemap.autoclear.TeleportUtils.getDirection(eye, point);
                    if (holdsFromEveryEye(feet, r.yaw(), r.pitch(), tile)) {
                        return new float[]{r.yaw(), r.pitch()};
                    }
                }
            }
        }
        return null;
    }

    static boolean holdsFromEveryEye(Vec3 feet, float yaw, float pitch, BlockPos tile) {
        for (double h : SERVER_EYES) {
            if (!com.killer560.hub.livemap.autoclear.TeleportUtils.holdsOn(feet.add(0, h, 0), yaw, pitch, 57.0, tile)) {
                return false;
            }
        }
        return true;
    }

    private static Vec3 facePoint(BlockPos b, net.minecraft.core.Direction face, double u, double v) {
        double x = b.getX(), y = b.getY(), z = b.getZ();
        return switch (face.getAxis()) {
            case X -> new Vec3(x + (face.getStepX() > 0 ? 1 : 0), y + u, z + v);
            case Y -> new Vec3(x + u, y + (face.getStepY() > 0 ? 1 : 0), z + v);
            case Z -> new Vec3(x + u, y + v, z + (face.getStepZ() > 0 ? 1 : 0));
        };
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
        if (++recoveryTicks == 1) {
            recoveryLag = com.killer560.hub.util.ServerLag.mark();
        }
        if (awaitingRegen) {
            int fresh = freshTiles(client, s);
            int total = sectionTiles.get(s).size();
            if (fresh < total) {
                if (recoveryTicks >= REGEN_TIMEOUT_TICKS + com.killer560.hub.util.ServerLag.ticksSince(recoveryLag)) {
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
        Vec3 stair = sectionStart[s] > 0 && MIDPOINTS.contains(path.get(sectionStart[s] - 1))
                ? path.get(sectionStart[s] - 1) : null;
        if (onPoint(player, start) || stair != null && onPoint(player, stair)) {
            LOGGER.info("[AutoIceFill] recovery: back on section {}'s {} ({}, {}, {}) after {} tick(s) - continuing",
                    s + 1, onPoint(player, start) ? "start" : "stair step",
                    String.format(java.util.Locale.ROOT, "%.2f", player.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", player.getY()),
                    String.format(java.util.Locale.ROOT, "%.2f", player.getZ()), recoveryTicks);
            recovering = -1;
            lastIndex = -1;
            ticks = 0;
            mapWalkOurs = false;
            warpTarget = null;
            lastNote = "";
            return;
        }
        if (REPOSITION.isActive()) {
            Vec3 used = REPOSITION.usePos();
            if (REPOSITION.inFlight() && used != null && warpTarget != null
                    && player.position().distanceToSqr(used) > 0.25 && !AutoPuzzleUtil.at(player, warpTarget)) {
                // Landed, but not on the block it was aimed at: say so now, not after the 40-tick arrival timeout (his
                // 2026-11-18 run waited 42 and 91 ticks on two such landings), and warp again from here.
                LOGGER.info("[AutoIceFill] recovery: the warp onto {} landed at ({}, {}, {}) instead - warping again",
                        AutoPuzzleUtil.fmt(warpTarget), String.format(java.util.Locale.ROOT, "%.2f", player.getX()),
                        String.format(java.util.Locale.ROOT, "%.2f", player.getY()),
                        String.format(java.util.Locale.ROOT, "%.2f", player.getZ()));
                REPOSITION.cancel(client);
                warpTarget = null;
                return;
            }
            REPOSITION.tick(client);
            note("retrepos" + s, "recovery: etherwarp back onto section {}'s start under way", s + 1);
            return;
        }
        if (mapWalkOurs) {
            if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
                note("retwalking" + s, "recovery: map walk back to section {}'s start under way", s + 1);
                return;
            }
            LOGGER.info("[AutoIceFill] recovery: map walk to section {}'s start ended after {} tick(s), player at"
                            + " ({}, {}, {})", s + 1, recoveryTicks,
                    String.format(java.util.Locale.ROOT, "%.2f", player.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", player.getY()),
                    String.format(java.util.Locale.ROOT, "%.2f", player.getZ()));
            mapWalkOurs = false;
        }
        if (recoveryTicks >= RETURN_TIMEOUT_TICKS + com.killer560.hub.util.ServerLag.ticksSince(recoveryLag)) {
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
            note("retmoving" + s, "recovery: waiting for him to stop moving before warping back to {}", tile);
            return;
        }
        // The section's first tile with a look that lands on it from every eye height; else the stair step before it
        // (solid, no tile of any section, so a near miss spends nothing); else the usual aim onto the tile, as before.
        BlockPos target = tile;
        float[] aim = AutoPuzzleUtil.etherwarpAim(client.level, player, tile) == null ? null : firmAim(player, tile);
        if (aim == null && stair != null) {
            BlockPos step = BlockPos.containing(stair).below();
            float[] stepAim = firmAim(player, step);
            if (stepAim != null) {
                target = step;
                aim = stepAim;
            }
        }
        if (REPOSITION.start(client, target, false, true, true, aim)) {
            warpTarget = target;
            note("ret" + s, "recovery: etherwarping back onto section {}'s {} {} ({})", s + 1,
                    target == tile ? "start" : "stair step", target,
                    aim == null ? "no look lands on it from every eye height - the usual aim" : "a look that lands from"
                            + " every eye height");
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

    /** One hop: a use aimed from {@code current}'s eye at {@code next}, under the free camera. Its look, or null when the
     *  gate held it back this tick (nothing sent). */
    private static float[] sendHop(Minecraft client, LocalPlayer player, Vec3 current, Vec3 next) {
        float[] dir = AutoPuzzleUtil.direction(new Vec3(current.x, current.y - 0.1 + player.getEyeHeight(), current.z), next);
        CAMERA.engage(player); // before the aim turns him
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            return null;
        }
        // This warp is our own, so waive the gate's teleport stand-down for the next hop - otherwise the 6-tick teleport
        // window would override the 2-tick Delay setting on every single step of the path.
        com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD);
        return dir;
    }

    /** Testkit 1118-grim-icefill (GrimAC has no Ice Fill room): one hop exactly as the auto sends it, true when sent. */
    static boolean hopForTest(Minecraft client, Vec3 current, Vec3 next) {
        return client.player != null && sendHop(client, client.player, current, next) != null;
    }

    /** Testkit 1118-grim-icefill: the run is over, the camera goes back. */
    static void endHopsForTest(Minecraft client) {
        CAMERA.release(client.player);
    }

    private static void giveUp(Minecraft client, String why) {
        stopMoving(client);
        recovering = -1;
        awaitingRegen = false;
        gaveUp = true;
        // A stopped auto holds nothing: the camera stayed held 16 s after the give-up on his 2026-11-18 run.
        CAMERA.release(client.player);
        LOGGER.info("[AutoIceFill] recovery: gave up - {}", why);
        ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.bad("Auto Ice Fill stopped: "), ModChat.text(why
                + ". Leave and re-enter the room to try again."));
    }

    /** The reference fillGaps: insert 1-block interpolated points between consecutive path points. */
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
     * The reference stupidStairs: add a midpoint before the first point at y 71.1 and before the first at y 72.1.
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
        if (hopsSent > 0 || !path.isEmpty()) {
            LOGGER.info("[AutoIceFill] reset: left the room, switched off or changed world - {} hop(s) sent, last"
                            + " point {} of {}, done={}, gaveUp={}, {} tick(s) since the first hop (tick {})",
                    hopsSent, lastIndex, path.size(), done, gaveUp, sinceFirstHop(),
                    com.killer560.hub.util.SessionRecorderSink.tick());
        }
        hopsSent = 0;
        firstHopTick = -1L;
        CAMERA.release(client.player);
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
        landWait = 0;
        hopLag = 0L;
        warpTarget = null;
        sectionOf = new int[0];
        sectionTiles = List.of();
        sectionStart = new int[0];
        sourcePath = List.of();
        path = List.of();
        lastIndex = -1;
        ticks = 0;
        done = false;
        CHEST.reset(client);
        optimizedWarned = false;
        lastNote = "";
    }
}
