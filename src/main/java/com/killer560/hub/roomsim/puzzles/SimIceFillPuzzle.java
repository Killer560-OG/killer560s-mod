package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimRoomPuzzles;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real Hypixel dungeon "Ice Fill" puzzle, playable inside the room sim: cross the three sheets of ice
 * without stepping on a block you have already used.
 *
 * <h2>The rules, as killer560 described them on 2026-09-30</h2>
 *
 * "For icefill it shouldnt teleport me. Those blocks for the path should be regular ice. ONce i walk on them
 * they go to packed ice. If i step on packed ice again it breaks that section of the ice fill and it
 * regenerates 2 seconds later for me to try again no teleporting."
 *
 * So: every tile of the fill starts as {@code minecraft:ice}; standing on one turns it to
 * {@code minecraft:packed_ice}, which is the "you have used this one" mark; standing on a packed tile is the
 * failure, and the failure breaks THAT SECTION - every one of its blocks goes to air - and lays it back as
 * fresh regular ice {@link #REGEN_TICKS} ticks later. <b>Nothing teleports the player, ever.</b>
 *
 * <h2>The rules he added on 2026-10-01</h2>
 *
 * <ul>
 *   <li><b>One section is live at a time.</b> "If i complete the first section then the second section should
 *       start working and the first section should no longer be able to be broken." {@link #activeSection} is
 *       that section; one below it is finished and inert, one above it is not live yet. Only the live one can
 *       fail, which is what lets him walk back over section 1's packed ice to reach section 2.</li>
 *   <li><b>Jumping fails it.</b> "If i leave the block that I am on by jumping or teleporting then it should
 *       break that section." {@link #AIRBORNE_FAIL_TICKS} consecutive airborne ticks on the live section is a
 *       jump.</li>
 *   <li><b>A short etherwarp does not.</b> "If i etherwarp to a block directly touching the block I am
 *       currently on thought (ice block) then it is ok." So the test is adjacency, not whether a teleport
 *       happened - and the same {@link #touching} test covers a walked step, a one-block warp and a warp right
 *       off the fill, because those are not three rules, they are one.</li>
 *   <li><b>Only the failed section regenerates</b>, not all three, now that the earlier ones are inert.</li>
 *   <li><b>The water goes</b> at arm time, and <b>the iron bars go</b> when the fill is crossed - see
 *       {@link #bindAt} and {@link #openChests}.</li>
 * </ul>
 *
 * <h2>What a "section" is, and why</h2>
 *
 * A section is one of the three bundled FLOORS, which is also one physical slab of ice in the room. That is
 * not a reading of the sentence, it is what the room and the data both are:
 *
 * <ul>
 *   <li>{@code data/killer560smod/puzzles/ice-fill-floors.json}'s own top-level shape is
 *       {@code [floor][pattern][waypoint]} - three floors, each with several possible layouts.
 *       {@code IceFillSolverFeature}'s class doc says the same in words: "3 real floors, each with several
 *       possible real ice layouts".</li>
 *   <li>Every pattern of a given floor starts and ends on the same tile - floor 0 (15,70,7) to (15,70,10),
 *       floor 1 (15,71,12) to (15,71,17), floor 2 (15,72,19) to (15,72,26) - so a floor's entry and exit are
 *       fixed properties of the room and only the route between them varies.</li>
 *   <li>Decoding the shipped {@code Ice_Path}-style capture {@code assets/killer560smod/rooms/Ice_Fill.json}
 *       shows exactly three separate slabs of {@code minecraft:ice}, one per floor, at three different
 *       heights, and each slab's footprint is exactly the bounding box of one floor's waypoints (3x4 / 5x6 /
 *       7x8, rotated). There is a solid non-ice step between one slab and the next.</li>
 * </ul>
 *
 * A section is therefore the unit the room is physically built out of, and "that section breaks" means that
 * slab disappears - not the whole fill, and not one tile.
 *
 * <h2>What changed from the first version of this class, and why</h2>
 *
 * The first version laid the bundled route as packed ice, melted the rest of each slab so only the route was
 * left, and on a failure teleported the player back to the first tile. All three are gone:
 *
 * <ul>
 *   <li><b>The teleport was the "walk off any ledge and end up in Ice Fill" bug.</b> Its fall check was
 *       {@code if (player.getY() < requiredTile.getY() - 1.5) fail()} with no test that the player was
 *       anywhere near this room, so any drop of about two blocks anywhere on the floor - any room, any ledge -
 *       failed the Ice Fill and teleported him onto its first tile. There is no fall check at all now: the only
 *       thing that can fire a rule is the block under the player's feet being one of this fill's own blocks,
 *       which cannot happen outside the room.</li>
 *   <li><b>Melting the slab down to the route</b> was needed only because the old fail rule ("the tile you
 *       left is gone") cannot bite while there is solid ice either side of the route. The new rule marks tiles
 *       instead of removing them, so the slabs are left exactly as captured - which is also the real puzzle:
 *       a full sheet you have to cross without repeating yourself, rather than a corridor with the answer
 *       already carved into it.</li>
 *   <li><b>The route is no longer written into a bound room at all.</b> Arming only resets the slabs to
 *       regular ice, so a capture taken part-walked starts fresh.</li>
 * </ul>
 *
 * <p>The bundled waypoints are still real data and still used, for two things: identifying the anchor (every
 * pattern of all three floors lands on ice at database rotation 270 and one block lower - see
 * {@link SimRoomPuzzles#bestAnchor}) and giving each section its bounding box and its exit tile.
 *
 * <p>Gated on {@link SimState#canAct} throughout. Every write to the world happens on the integrated server
 * via {@code server.execute(...)}, never the client thread - same rule as the rest of {@code roomsim}, see
 * {@code SimDoors}' class doc.
 */
public final class SimIceFillPuzzle {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** How long a broken section stays broken. killer560: "regenerates 2 seconds later" - 2s at 20 tps. */
    private static final int REGEN_TICKS = 40;

    /**
     * How many consecutive airborne ticks on the active section count as a jump.
     *
     * <p>Not one. A jump is airborne for ten or more ticks, so four is unambiguous, while a single-tick blip -
     * which a step up onto the next slab or the frame after an etherwarp can produce - is not punished. Chosen
     * to be wrong in the forgiving direction: a missed jump costs him nothing, and a false fail on a legal move
     * is the bug that makes a puzzle feel broken.
     */
    private static final int AIRBORNE_FAIL_TICKS = 4;

    private record Pt(int x, int y, int z) {
    }

    // Copied verbatim from ice-fill-floors.json: easy[floor][pattern 0]. Pattern 0 of each floor, because the
    // real room generates one of several layouts and identifies which live from two fixed block checks; a bind
    // has no need to pick one at all now (the whole slab is live), and the standalone arena has nothing to
    // identify from, so it lays pattern 0 rather than a layout that corresponds to nothing.
    private static final Pt[] FLOOR_0 = {
            new Pt(15, 70, 7), new Pt(16, 70, 7), new Pt(16, 70, 8), new Pt(14, 70, 8),
            new Pt(14, 70, 9), new Pt(15, 70, 9), new Pt(15, 70, 10),
    };
    private static final Pt[] FLOOR_1 = {
            new Pt(15, 71, 12), new Pt(17, 71, 12), new Pt(17, 71, 15), new Pt(16, 71, 15),
            new Pt(16, 71, 14), new Pt(15, 71, 14), new Pt(15, 71, 13), new Pt(14, 71, 13),
            new Pt(14, 71, 12), new Pt(13, 71, 12), new Pt(13, 71, 16), new Pt(14, 71, 16),
            new Pt(14, 71, 15), new Pt(15, 71, 15), new Pt(15, 71, 17),
    };
    private static final Pt[] FLOOR_2 = {
            new Pt(15, 72, 19), new Pt(15, 72, 22), new Pt(16, 72, 22), new Pt(16, 72, 19),
            new Pt(17, 72, 19), new Pt(17, 72, 20), new Pt(18, 72, 20), new Pt(18, 72, 21),
            new Pt(17, 72, 21), new Pt(17, 72, 22), new Pt(18, 72, 22), new Pt(18, 72, 25),
            new Pt(17, 72, 25), new Pt(17, 72, 23), new Pt(16, 72, 23), new Pt(16, 72, 24),
            new Pt(13, 72, 24), new Pt(13, 72, 22), new Pt(14, 72, 22), new Pt(14, 72, 19),
            new Pt(12, 72, 19), new Pt(12, 72, 20), new Pt(13, 72, 20), new Pt(13, 72, 21),
            new Pt(12, 72, 21), new Pt(12, 72, 25), new Pt(15, 72, 25), new Pt(15, 72, 26),
    };

    /** The three floors, in crossing order. One entry here is one SECTION - see the class doc. */
    private static final Pt[][] FLOORS = {FLOOR_0, FLOOR_1, FLOOR_2};

    /** All three floors' waypoints in real order - the anchor ({@link #ANCHOR}) is the very first one. */
    private static final Pt[] WAYPOINTS = concat(FLOOR_0, FLOOR_1, FLOOR_2);
    private static final Pt ANCHOR = WAYPOINTS[0];

    private static Pt[] concat(Pt[] a, Pt[] b, Pt[] c) {
        List<Pt> out = new ArrayList<>(a.length + b.length + c.length);
        out.addAll(List.of(a));
        out.addAll(List.of(b));
        out.addAll(List.of(c));
        return out.toArray(new Pt[0]);
    }

    /** Each section's own blocks, world positions, in crossing order. Empty until built or bound. */
    private static volatile List<Set<BlockPos>> sections = List.of();

    /** Which section a tile belongs to. Written on the server thread, read on the client thread. */
    private static final Map<BlockPos, Integer> TILE_SECTION = new ConcurrentHashMap<>();

    /** Each section's exit tile - the last waypoint of that bundled floor, which every pattern shares. */
    private static volatile List<BlockPos> exitTiles = List.of();

    /** Blocks this class PLACED, for a standalone arena's {@link #reset} to take away again. */
    private static volatile List<BlockPos> placedBlocks = List.of();

    /** The iron bars the capture holds, so finishing the fill can take them away. World positions. */
    private static volatile List<BlockPos> ironBars = List.of();

    /** The tile the player is already standing on, so marking it does not immediately read as a repeat. */
    private static volatile BlockPos currentTile = null;

    /** Which section {@link #currentTile} belongs to, or -1 when the player is not on the fill. */
    private static volatile int currentSection = -1;

    /**
     * The one section that is live.
     *
     * <p>killer560 (2026-10-01): "if i complete the first section then the second section should start working
     * and the first section should no longer be able to be broken." So exactly one section can fail at a time.
     * A section below this one is FINISHED and inert - its packed ice is walked back over on the way to the next
     * one, and stepping on it must not read as a repeat. A section above it is not live yet.
     */
    private static volatile int activeSection = 0;

    /** Consecutive ticks the player has been off the ground while on the active section - see {@link #tick}. */
    private static volatile int airborneTicks = 0;

    private static volatile boolean complete = false;
    private static volatile int brokenSection = -1;
    private static volatile int regenCountdown = 0;
    private static volatile BlockPos storedOrigin = null;

    /** Non-null while this puzzle is bound to a real captured room rather than a standalone arena. */
    private static volatile SimRoomPuzzles.Anchor boundAnchor = null;

    private static boolean registered = false;

    private SimIceFillPuzzle() {
    }

    /** Registers the progress-polling tick hook. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimIceFillPuzzle.tick", SimIceFillPuzzle::tick));
    }

    /**
     * Clears any previous arena and lays a fresh ice path anchored so {@code origin} is the first tile.
     *
     * <p>A standalone arena is the bundled route and nothing else, so there is nowhere to step wrong except
     * backwards - which is exactly the mistake this puzzle is about. Same rules as a bound room: regular ice,
     * packed once walked, and a repeat breaks that section for {@link #REGEN_TICKS} ticks.
     */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        clearPlaced(client);
        forget();
        storedOrigin = origin;
        BlockPos anchorBlock = origin.below(); // the player's feet sit at origin, the ice tile sits below

        List<int[]> rel = relativePath();
        List<Set<BlockPos>> built = newSectionList();
        // Sized and pre-filled so a section's exit can be overwritten as the walk passes through it; the last
        // tile of a section in path order IS that section's exit. relativePath() covers all three floors, so
        // none of these stays at its placeholder.
        List<BlockPos> exits = new ArrayList<>(FLOORS.length);
        for (int i = 0; i < FLOORS.length; i++) {
            exits.add(anchorBlock);
        }
        List<BlockPos> all = new ArrayList<>(rel.size());
        for (int[] step : rel) {
            BlockPos pos = offsetFromAnchor(anchorBlock, step[0], step[1], step[2]);
            int section = sectionOfRelativeY(step[1]);
            built.get(section).add(pos);
            TILE_SECTION.put(pos, section);
            exits.set(section, pos);   // the last tile of a section, in path order, is that section's exit
            all.add(pos);
        }
        sections = List.copyOf(built);
        exitTiles = List.copyOf(exits);
        placedBlocks = List.copyOf(all);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos tile : all) {
                level.setBlockAndUpdate(tile, Blocks.ICE.defaultBlockState());
            }
        });
        ModChat.send("Sim", ModChat.text("Ice Fill built - "), ModChat.value(all.size() + " tiles"),
                ModChat.text(", don't step on ice you have already used."));
    }

    /**
     * Arms this puzzle on a REAL captured Ice Fill room, on the room's own ice.
     *
     * <p>The anchor is found the way it always was: the bundled waypoints are the room database's own
     * coordinates and the capture holds ice at every one of them, measured at database rotation 270 and
     * <b>one block lower</b> than the solver's y. That one-block drop is a property of THIS capture - the
     * other bound puzzles need no nudge - so it is searched for by {@link SimRoomPuzzles#bestAnchor} and
     * logged when it is used, rather than written in as a constant nobody could check.
     *
     * <p><b>Nothing is carved.</b> Each section is every ice block inside that floor's own bounding box (grown
     * a block, so a slab slightly wider than its route is caught whole) at that floor's own height, and the
     * only write is setting them all back to plain ice so a part-walked capture starts fresh. Nothing outside
     * those three boxes is read or written, and no block that was not already ice is touched.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, SimRoomPuzzles.Placement p) {
        List<int[]> rels = new ArrayList<>(WAYPOINTS.length);
        // THE ICE IS UNDER THE WAYPOINT. The bundled path is FEET positions - decoded 2026-10-01, all 45 tiles of
        // the identified layout are air with ice directly beneath at no nudge, and identifier pairs match all three
        // floors only at no nudge. Testing for ice AT the waypoint is what found "dy = -1", and publishing that
        // nudge moved IceFillSolverFeature's identifier lookups a block down, where none of them match - which is
        // "the ice fill solver still isnt working in ice fill" (killer560, 2026-10-01).
        for (Pt pt : WAYPOINTS) {
            rels.add(new int[]{pt.x(), pt.y() - 1, pt.z()});
        }
        java.util.function.Predicate<BlockState> isIce =
                SimRoomPuzzles.is(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE);
        SimRoomPuzzles.Anchor anchor = SimRoomPuzzles.bestAnchor(level, p, rels, isIce,
                new int[]{0, -1, 1, -2, 2}, rels.size() * 3 / 4);
        if (anchor == null) {
            return false;
        }
        // In-memory only. clearPlaced() would queue air writes at the PREVIOUS arena's positions for the next
        // server tick, and on a rebuild of the same room those are inside the room just pasted.
        placedBlocks = List.of();
        forget();

        List<Set<BlockPos>> found = newSectionList();
        List<BlockPos> exits = new ArrayList<>(FLOORS.length);
        for (int f = 0; f < FLOORS.length; f++) {
            Pt[] floor = FLOORS[f];
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int y = floor[0].y() - 1;   // the ice, one under the feet - see above
            for (Pt pt : floor) {
                minX = Math.min(minX, pt.x());
                maxX = Math.max(maxX, pt.x());
                minZ = Math.min(minZ, pt.z());
                maxZ = Math.max(maxZ, pt.z());
            }
            Set<BlockPos> tiles = found.get(f);
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = anchor.world(x, y, z);
                    if (isIce.test(level.getBlockState(pos))) {
                        tiles.add(pos);
                    }
                }
            }
            if (tiles.isEmpty()) {
                LOGGER.warn("Sim ice fill: section {} of {} holds no ice at the chosen anchor - not armed",
                        f, p.room().name);
                forget();
                return false;
            }
            Pt last = floor[floor.length - 1];
            exits.add(anchor.world(last.x(), last.y() - 1, last.z()));
        }
        for (Set<BlockPos> tiles : found) {
            for (BlockPos pos : tiles) {
                level.setBlockAndUpdate(pos, Blocks.ICE.defaultBlockState());
            }
        }
        for (int f = 0; f < found.size(); f++) {
            for (BlockPos pos : found.get(f)) {
                TILE_SECTION.put(pos, f);
            }
        }
        sections = List.copyOf(found);
        exitTiles = List.copyOf(exits);
        storedOrigin = null;
        boundAnchor = anchor;
        activeSection = 0;

        // THE WATER GOES. killer560 (2026-10-01): "there is a bunch of water still in it."
        //
        // The capture really does hold it - 143 water blocks, 103 of them at captured y 66, sitting directly
        // under the fill's lowest slab, plus a shallow spill up to y 70 inside the same x 8..27 / z 11..22
        // footprint as the slabs. On a floor it reads as the fill being flooded.
        //
        // Read out of the CAPTURE rather than scanned out of the world, through the same
        // {@link SimRoomPuzzles#capturedBlocks} the other binds use, so only the room's own water is touched and
        // nothing of a neighbour's. A waterlogged stair or fence is a different block state and is left alone -
        // worth saying, because if any of the flooding survives that is where the rest of it is.
        int drained = 0;
        for (BlockPos pos : SimRoomPuzzles.capturedBlocks(p, Blocks.WATER)) {
            if (level.getBlockState(pos).is(Blocks.WATER)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                drained++;
            }
        }

        // The bars in front of the reward chests, remembered now and removed when the fill is crossed - see
        // openChests. Taken at arm time because by completion the Placement is long gone.
        ironBars = List.copyOf(SimRoomPuzzles.capturedBlocks(p, Blocks.IRON_BARS));

        // THE TWO REWARD CHESTS. killer560 (2026-10-01): "In ice fill there needs to be two chests inside the
        // torches on the andesite blocks."
        //
        // The capture holds the alcove but not the chests - decoding it finds no chest anywhere in the room.
        // Behind the iron-bars wall (capture x 28, z 14-18) sits a pocket whose far side is capture x 30: two
        // polished_andesite pedestals at (30, 74, 15) and (30, 74, 17), air directly above each, and the two
        // torches at (30, 75, 14) and (30, 75, 18) standing on cobblestone walls either side of them. So "inside
        // the torches, on the andesite blocks" is (30, 75, 15) and (30, 75, 17), and that is where these go.
        //
        // These coordinates are read off THIS capture of Ice Fill, so they are the one thing here that a
        // re-walk of the room could move. Each is written only if that spot is currently air, and the count is
        // logged, so a capture that has shifted reports zero chests rather than burying two in a wall.
        int rewards = 0;
        for (int cz : new int[]{15, 17}) {
            BlockPos spot = SimRoomPuzzles.capturedPos(p, 30, 75, cz);
            if (level.getBlockState(spot).isAir()) {
                level.setBlockAndUpdate(spot, Blocks.CHEST.defaultBlockState());
                rewards++;
            } else {
                LOGGER.warn("Sim ice fill: no air at the reward-chest spot {} (capture 30,75,{}) - holds {}. "
                                + "If Ice Fill has been re-captured these two coordinates need re-measuring.",
                        spot, cz, level.getBlockState(spot).getBlock());
            }
        }

        LOGGER.info("Sim ice fill: armed in {} - section sizes {}/{}/{}, exits {}, {} water block(s) drained, "
                        + "{} iron bar(s) held for the finish, {} of 2 reward chest(s) placed", p.room().name,
                found.get(0).size(), found.get(1).size(), found.get(2).size(), exits, drained, ironBars.size(),
                rewards);
        return true;
    }

    private static List<Set<BlockPos>> newSectionList() {
        List<Set<BlockPos>> out = new ArrayList<>(FLOORS.length);
        for (int i = 0; i < FLOORS.length; i++) {
            out.add(new LinkedHashSet<>());
        }
        return out;
    }

    /** Which section a bundled relative Y belongs to. The three floors are at three distinct heights. */
    private static int sectionOfRelativeY(int relY) {
        for (int f = FLOORS.length - 1; f >= 0; f--) {
            if (relY >= FLOORS[f][0].y()) {
                return f;
            }
        }
        return 0;
    }

    /** The unit-step tile path of the bundled route, in ROOM-RELATIVE coordinates. Standalone arena only. */
    private static List<int[]> relativePath() {
        List<int[]> path = new ArrayList<>();
        int curX = ANCHOR.x();
        int curY = ANCHOR.y();
        int curZ = ANCHOR.z();
        path.add(new int[]{curX, curY, curZ});
        for (int i = 1; i < WAYPOINTS.length; i++) {
            Pt target = WAYPOINTS[i];
            while (curY != target.y()) {
                curY += Integer.signum(target.y() - curY);
                path.add(new int[]{curX, curY, curZ});
            }
            while (curX != target.x()) {
                curX += Integer.signum(target.x() - curX);
                path.add(new int[]{curX, curY, curZ});
            }
            while (curZ != target.z()) {
                curZ += Integer.signum(target.z() - curZ);
                path.add(new int[]{curX, curY, curZ});
            }
        }
        return path;
    }

    private static BlockPos offsetFromAnchor(BlockPos anchorBlock, int x, int y, int z) {
        return anchorBlock.offset(x - ANCHOR.x(), y - ANCHOR.y(), z - ANCHOR.z()).immutable();
    }

    /** True once the last section's exit tile has been reached. */
    public static boolean isComplete() {
        return complete;
    }

    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>{@link #reset} is the right thing while the arena is still standing. It is the wrong thing when the
     * floor those blocks belonged to no longer exists, which is exactly the case
     * {@code SimRoomPuzzles.armFloor} has to handle - the positions it holds are absolute and the next floor
     * is built over them, so a queued write lands inside the new floor. Just as bad the other way: a stale
     * tile index left in place makes a step on some unrelated block on the new floor count as a move in a
     * puzzle that is not on it.
     *
     * <p>{@link #placedBlocks} is dropped here for the same reason, which it was not before: it holds the
     * absolute positions a STANDALONE arena wrote, and {@code armFloor} calls this for every puzzle a new floor
     * does not hold - so keeping the list left a later {@code /simpuzzle reset} queueing air inside a freshly
     * built floor. Every caller that still wants those blocks taken away calls {@link #clearPlaced} first,
     * which queues the writes and empties the list itself. Same change in {@code SimIcePathPuzzle}, which is
     * the only other puzzle that places its own arena.
     */
    public static void forget() {
        placedBlocks = List.of();
        sections = List.of();
        exitTiles = List.of();
        TILE_SECTION.clear();
        ironBars = List.of();
        currentTile = null;
        currentSection = -1;
        activeSection = 0;
        airborneTicks = 0;
        complete = false;
        brokenSection = -1;
        regenCountdown = 0;
        storedOrigin = null;
        boundAnchor = null;
    }

    /** Puts the fill back the way arming left it - all plain ice - and clears progress. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        clearPlaced(client);
        restoreAll(client);
        sections = List.of();
        exitTiles = List.of();
        TILE_SECTION.clear();
        ironBars = List.of();
        currentTile = null;
        currentSection = -1;
        activeSection = 0;
        airborneTicks = 0;
        complete = false;
        brokenSection = -1;
        regenCountdown = 0;
        boundAnchor = null;
    }

    /** Takes away the blocks a STANDALONE arena placed. A bound room's ice is the room's, and is left alone. */
    private static void clearPlaced(Minecraft client) {
        List<BlockPos> old = placedBlocks;
        placedBlocks = List.of();
        if (old.isEmpty() || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos tile : old) {
                level.setBlockAndUpdate(tile, Blocks.AIR.defaultBlockState());
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        if (regenCountdown > 0) {
            regenCountdown--;
            if (regenCountdown == 0) {
                regenerate(client);
            }
            return;
        }
        if (TILE_SECTION.isEmpty() || complete) {
            return;
        }
        // Are we standing on the section that is actually live? Every rule below applies only there, which is
        // what makes a finished section inert: walking back across section 1's packed ice on the way to
        // section 2 has to be free, and so does stepping off it onto the solid step between the slabs.
        boolean onActive = currentTile != null && currentSection == activeSection;

        // A JUMP IS A FAIL. killer560 (2026-10-01): "if i leave the block that I am on by jumping or
        // teleporting then it should break that section."
        if (onActive && !client.player.onGround()) {
            airborneTicks++;
            if (airborneTicks >= AIRBORNE_FAIL_TICKS) {
                breakSection(client, activeSection, "jumped off the ice");
            }
            return;
        }
        airborneTicks = 0;

        // The ONLY trigger: the block the player is standing on is one of this fill's own blocks. That is what
        // confines this puzzle to its own room - see the class doc on the fall-teleport bug this replaced.
        BlockPos tile = client.player.blockPosition().below();
        Integer section = TILE_SECTION.get(tile);
        if (section == null) {
            // Off the fill. Walking a single block off the edge is allowed - it is a dead end and nothing more.
            // Arriving somewhere that is NOT touching the tile just left is a teleport, and that fails.
            if (onActive && !touching(currentTile, tile)) {
                breakSection(client, activeSection, "teleported off the ice");
                return;
            }
            currentTile = null;
            currentSection = -1;
            return;
        }
        if (tile.equals(currentTile)) {
            return;   // already judged; marking it packed must not read as stepping on packed ice
        }
        // THE ETHERWARP EXCEPTION. killer560: "If i etherwarp to a block directly touching the block I am
        // currently on thought (ice block) then it is ok." Adjacency is the whole test, so one rule covers a
        // walked step, a short etherwarp and a long one - and it is deliberately the SAME rule as the branch
        // above, because "teleported to another ice tile" and "teleported off the ice" are one mistake.
        // Touching includes the diagonals and one block of height, which is what the slabs between sections
        // need: section 1's exit and section 2's entry are a block apart vertically.
        if (onActive && !touching(currentTile, tile)) {
            breakSection(client, activeSection, "teleported off the ice");
            return;
        }
        currentTile = tile;
        currentSection = section;
        if (section != activeSection) {
            // A finished section cannot be broken, and one not reached yet is not live. Either way: nothing.
            return;
        }
        BlockState state = client.level.getBlockState(tile);
        if (state.is(Blocks.PACKED_ICE)) {
            breakSection(client, section, "stepped on ice you had already used");
            return;
        }
        if (!state.is(Blocks.ICE)) {
            return;   // air (a section mid-break) or the solid step between two slabs
        }
        mark(client, tile);
        List<BlockPos> exits = exitTiles;
        if (exits.size() == FLOORS.length && tile.equals(exits.get(section))) {
            if (section + 1 < FLOORS.length) {
                activeSection = section + 1;
                ModChat.send("Sim", ModChat.good("Ice Fill - section " + (section + 1) + " done, "),
                        ModChat.text("section "), ModChat.value((section + 2) + " of " + FLOORS.length),
                        ModChat.text(" is live."));
            } else {
                complete = true;
                ModChat.send("Sim", ModChat.good("Ice Fill crossed!"));
                openChests(client);
            }
        }
    }

    /** Whether two tiles touch - the eight neighbours and one block of height, as a walked step does. */
    private static boolean touching(BlockPos a, BlockPos b) {
        return a != null && b != null
                && Math.abs(a.getX() - b.getX()) <= 1
                && Math.abs(a.getY() - b.getY()) <= 1
                && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    /**
     * Takes away the iron bars the capture holds in front of the reward chests.
     *
     * <p>killer560 (2026-10-01): "Once all sections are done delete the iron bars blocking the two chests at
     * the end." The capture holds 43 of them in one flat wall, and the chests behind it are secret chests the
     * database places rather than anything in the capture - which is why the capture itself contains no chest at
     * all. The positions were taken at arm time from the capture rather than scanned for now, so a bar somewhere
     * else in the room is not swept up by accident.
     */
    private static void openChests(Minecraft client) {
        List<BlockPos> bars = ironBars;
        if (bars.isEmpty()) {
            return;   // a standalone arena has none, and that is not a fault
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int removed = 0;
            for (BlockPos pos : bars) {
                if (level.getBlockState(pos).is(Blocks.IRON_BARS)) {
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    removed++;
                }
            }
            LOGGER.info("Sim ice fill: fill crossed - {} of {} iron bar(s) cleared from the chests",
                    removed, bars.size());
        });
    }

    /** The "you have used this one" mark: regular ice becomes packed ice under the player's feet. */
    private static void mark(Minecraft client, BlockPos tile) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> server.overworld().setBlockAndUpdate(tile, Blocks.PACKED_ICE.defaultBlockState()));
    }

    /**
     * Stepped on ice already used: that section breaks now and comes back in {@link #REGEN_TICKS} ticks.
     *
     * <p>Only the failed section is aired out, and - unlike the first version of this - only the failed section
     * comes back. That used to restore ALL THREE, because leaving the earlier sections packed would have made
     * the first step back onto section 1 a second instant failure. Sequential sections remove the need:
     * a section below {@link #activeSection} is finished and inert, so its packed ice can be walked back over
     * freely and there is nothing to undo. Resetting it would instead throw away work he had already done.
     *
     * <p>{@code why} is the mistake, in his words, because "section 2 breaks" on its own does not say whether
     * he repeated a tile, jumped, or warped too far - and those are three different things to stop doing.
     */
    private static void breakSection(Minecraft client, int section, String why) {
        List<Set<BlockPos>> all = sections;
        if (section < 0 || section >= all.size()) {
            return;
        }
        // NOT a puzzle failure, and so no SimPuzzles.reportFail. killer560 (2026-10-02): "ice fill should never be
        // red on the map." A broken section regenerates in two seconds and he tries again - nothing is lost, so
        // there is nothing for the map to mark and nothing for an Architect's First Draft to fix. This call is
        // what turned the room red (SimRoomState.markFailed) on every slip, including the overshooting hops that
        // broke Auto Ice Fill - see SimAbilities.dashTarget.
        ModChat.send("Sim", ModChat.bad("Ice Fill - " + why + ": section "),
                ModChat.value(String.valueOf(section + 1)), ModChat.bad(" breaks, back in 2s."));
        brokenSection = section;
        regenCountdown = REGEN_TICKS;
        currentTile = null;
        currentSection = -1;
        airborneTicks = 0;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Set<BlockPos> broken = Set.copyOf(all.get(section));
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : broken) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        });
    }

    /**
     * The broken section comes back as fresh ice. Only that one - see {@link #breakSection}.
     *
     * <p>{@link #activeSection} is deliberately NOT moved: the section he failed is the section he retries.
     */
    private static void regenerate(Minecraft client) {
        int section = brokenSection;
        brokenSection = -1;
        currentTile = null;
        currentSection = -1;
        airborneTicks = 0;
        complete = false;
        restoreSection(client, section);
        ModChat.send("Sim", ModChat.text("Ice Fill - section "),
                ModChat.value(String.valueOf(section + 1)), ModChat.text(" regenerated, try again."));
    }

    /** One section back to plain, unwalked ice. */
    private static void restoreSection(Minecraft client, int section) {
        List<Set<BlockPos>> all = sections;
        if (section < 0 || section >= all.size() || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Set<BlockPos> tiles = Set.copyOf(all.get(section));
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : tiles) {
                level.setBlockAndUpdate(pos, Blocks.ICE.defaultBlockState());
            }
        });
    }

    /** Every section back to plain, unwalked ice. */
    private static void restoreAll(Minecraft client) {
        List<Set<BlockPos>> all = sections;
        if (all.isEmpty() || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<BlockPos> every = new ArrayList<>();
        for (Set<BlockPos> tiles : all) {
            every.addAll(tiles);
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : every) {
                level.setBlockAndUpdate(pos, Blocks.ICE.defaultBlockState());
            }
        });
    }
}
