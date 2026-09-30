package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimRoomPuzzles;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A small standalone practice arena for the real Hypixel dungeon "Ice Fill" puzzle, playable inside the room
 * sim: cross the ice by tracing the correct path without repeating a tile.
 *
 * <p><b>What is real data, not invented:</b> the waypoints in {@link #FLOOR_0}, {@link #FLOOR_1} and
 * {@link #FLOOR_2} are copied verbatim (same x/y/z, same order) from the "easy" path, pattern index 0, for
 * floors 0/1/2 of the bundled {@code data/killer560smod/puzzles/ice-fill-floors.json} - the exact resource
 * {@code puzzlesolvers.IceFillSolverFeature} reads to draw the real solver's line. That file has several
 * patterns per floor because the real room randomly generates one of several ice layouts and identifies which
 * one live from two fixed block checks; this arena has no real block state to identify from (it is not a
 * captured room), so it always builds pattern 0 for every floor rather than picking one at random, which would
 * not correspond to anything real. The three floors keep their real relative Y (70/71/72), so the whole climb
 * is the real shape, just re-based onto {@code origin} instead of the real room's own coordinates.
 *
 * <p><b>What is this file's own invention:</b> the bundled waypoints are corner-to-corner turning points on a
 * straight line (as {@code IceFillSolverFeature} itself draws them: "drawn as a connected line"), not
 * individually adjacent tiles - {@link #buildPath} fills every unit step between one waypoint and the next
 * (walking Y, then X, then Z) so the arena has an actual walkable tile at every block of the real line,
 * including the step up between floors, which the bundled data does not itself specify a tile for. Which
 * exact single tile bridges "top of floor 0" to "bottom of floor 1" is this file's choice, not bundled data.
 *
 * <p><b>The fail rule is the real one, not invented:</b> Ice Fill's ice is gone once you have crossed it, so
 * doubling back drops you through nothing rather than solid ground. {@link #tick} reproduces exactly that:
 * the tile you just left is melted back to air (removing it from {@link #TILE_INDEX} too) the moment you land
 * on the next one, so repeating a tile is not just against the rules, it is physically impossible - you fall.
 * Landing on any tracked tile out of order (only reachable by jumping a gap) rebuilds the whole arena the same
 * way {@code SimBlazePuzzle#failAndRebuild} does for an out-of-order kill; falling below where the path
 * currently is (nothing left underneath, since only the path itself is solid) does the same.
 *
 * <p>Gated on {@link SimState#canAct} throughout. Every write to the world or the player happens on the
 * integrated server via {@code server.execute(...)}, never the client thread - same rule as the rest of
 * {@code roomsim}, see {@code SimDoors}' class doc.
 */
public final class SimIceFillPuzzle {

    private record Pt(int x, int y, int z) {
    }

    // Copied verbatim from ice-fill-floors.json: easy[floor][pattern 0]. See class doc.
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

    /** The full ordered, unit-step tile path for the current build, real-world positions. Empty until built. */
    private static volatile List<BlockPos> pathTiles = List.of();

    /** Feet-level position -> index into {@link #pathTiles}. Entries are removed as their tile melts. */
    private static final Map<BlockPos, Integer> TILE_INDEX = new HashMap<>();

    private static volatile int nextRequired = 0;
    private static volatile boolean complete = false;
    private static volatile BlockPos storedOrigin = null;

    private static boolean registered = false;

    private SimIceFillPuzzle() {
    }

    /** Registers the progress-polling tick hook. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimIceFillPuzzle.tick", SimIceFillPuzzle::tick));
    }

    /** Clears any previous arena and lays a fresh ice path anchored so {@code origin} is the first tile. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        clearBlocks(client);
        storedOrigin = origin;
        boundAnchor = null;   // a standalone arena, not a bind to a captured room
        BlockPos anchorBlock = origin.below(); // player's feet sit at origin, the ice tile sits below

        List<BlockPos> path = buildPath(anchorBlock);
        pathTiles = path;
        TILE_INDEX.clear();
        for (int i = 0; i < path.size(); i++) {
            TILE_INDEX.put(path.get(i).above(), i);
        }
        nextRequired = 0;
        complete = false;

        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos tile : path) {
                level.setBlockAndUpdate(tile, Blocks.PACKED_ICE.defaultBlockState());
            }
        });
        ModChat.send("Sim", ModChat.text("Ice Fill built - "), ModChat.value(path.size() + " tiles"),
                ModChat.text(", don't repeat one."));
    }

    /**
     * Arms this puzzle on a REAL captured Ice Fill room, on the room's own ice.
     *
     * <p>The waypoints above are {@code ice-fill-floors.json}'s own, in room-relative coordinates, and the
     * capture holds ice at every one of them - measured by trying all four rotations against all five nearby
     * heights: at database rotation 270 and <b>one block lower</b>, every pattern of all three bundled floors
     * lands entirely on ice. (Every pattern, because the unsolved room is a solid slab per floor and a pattern
     * is a route across it.) That one-block drop is real and is a property of THIS capture - the other five
     * bound puzzles need no nudge at all - so it is searched for by {@link SimRoomPuzzles#bestAnchor} and
     * logged when it is used, rather than written in as a constant nobody could check.
     *
     * <p><b>Why this one writes.</b> The room's three floors are solid slabs of ice, and the fail rule this
     * class exists to drill - "the tile you left is gone, so you cannot double back" - cannot bite while there
     * is solid ice either side of the route. So the slab is carved down to the bundled route: ice inside each
     * floor's own bounding box that is not on the route is melted, and the route itself is laid as packed ice.
     * Nothing outside those three boxes is touched, and no block that was not already ice is removed.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, SimRoomPuzzles.Placement p) {
        List<int[]> rels = new ArrayList<>(WAYPOINTS.length);
        for (Pt pt : WAYPOINTS) {
            rels.add(new int[]{pt.x(), pt.y(), pt.z()});
        }
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> isIce =
                SimRoomPuzzles.is(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE);
        SimRoomPuzzles.Anchor anchor = SimRoomPuzzles.bestAnchor(level, p, rels, isIce,
                new int[]{0, -1, 1, -2, 2}, rels.size() * 3 / 4);
        if (anchor == null) {
            return false;
        }
        // In-memory only, NOT clearBlocks(): that queues air writes at the PREVIOUS path's positions for the
        // next server tick, and if this is the same room being rebuilt those are the positions about to be
        // laid - it would air out the ice this method just placed. The previous floor's blocks are gone with
        // the build's own wipe.
        pathTiles = List.of();
        List<int[]> relPath = relativePath();
        List<BlockPos> path = new ArrayList<>(relPath.size());
        Set<BlockPos> onPath = new java.util.HashSet<>();
        for (int[] rel : relPath) {
            BlockPos pos = anchor.world(rel);
            path.add(pos);
            onPath.add(pos);
        }
        // Melt the rest of each floor's slab, inside that floor's own bounding box and nowhere else.
        for (Pt[] floor : new Pt[][]{FLOOR_0, FLOOR_1, FLOOR_2}) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int y = floor[0].y();
            for (Pt pt : floor) {
                minX = Math.min(minX, pt.x());
                maxX = Math.max(maxX, pt.x());
                minZ = Math.min(minZ, pt.z());
                maxZ = Math.max(maxZ, pt.z());
            }
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = anchor.world(x, y, z);
                    if (!onPath.contains(pos) && isIce.test(level.getBlockState(pos))) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        for (BlockPos tile : path) {
            level.setBlockAndUpdate(tile, Blocks.PACKED_ICE.defaultBlockState());
        }
        pathTiles = List.copyOf(path);
        TILE_INDEX.clear();
        for (int i = 0; i < path.size(); i++) {
            TILE_INDEX.put(path.get(i).above(), i);
        }
        nextRequired = 0;
        complete = false;
        storedOrigin = path.get(0).above();
        boundAnchor = anchor;
        return true;
    }

    /** Non-null while this puzzle is bound to a real captured room rather than a standalone arena. */
    private static volatile SimRoomPuzzles.Anchor boundAnchor = null;

    /** The same walk {@link #buildPath} does, in ROOM-RELATIVE coordinates, for {@link #bindAt} to transform. */
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

    /** Walks every waypoint pair one axis at a time (Y, then X, then Z) into a full unit-step tile list. */
    private static List<BlockPos> buildPath(BlockPos anchorBlock) {
        List<BlockPos> path = new ArrayList<>();
        path.add(anchorBlock);
        int curX = ANCHOR.x(), curY = ANCHOR.y(), curZ = ANCHOR.z();
        for (int i = 1; i < WAYPOINTS.length; i++) {
            Pt target = WAYPOINTS[i];
            while (curY != target.y()) {
                curY += Integer.signum(target.y() - curY);
                path.add(offsetFromAnchor(anchorBlock, curX, curY, curZ));
            }
            while (curX != target.x()) {
                curX += Integer.signum(target.x() - curX);
                path.add(offsetFromAnchor(anchorBlock, curX, curY, curZ));
            }
            while (curZ != target.z()) {
                curZ += Integer.signum(target.z() - curZ);
                path.add(offsetFromAnchor(anchorBlock, curX, curY, curZ));
            }
        }
        return path;
    }

    private static BlockPos offsetFromAnchor(BlockPos anchorBlock, int x, int y, int z) {
        return anchorBlock.offset(x - ANCHOR.x(), y - ANCHOR.y(), z - ANCHOR.z()).immutable();
    }

    /** True once every tile has been crossed in order. */
    public static boolean isComplete() {
        return complete;
    }

    /** Clears the ice path and its bookkeeping. Safe with nothing built. */
    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>{@link #reset} is the right thing while the arena is still standing: it puts blocks back, un-presses,
     * re-lights. It is the wrong thing when the floor those blocks belonged to no longer exists, which is
     * exactly the case {@code SimRoomPuzzles.armFloor} has to handle - the positions it holds are absolute and
     * the next floor is built over them, so a queued "set it back to air" lands inside the new floor and
     * punches a hole in it. Just as bad the other way: a stale click index left in place makes a click on some
     * unrelated block on the new floor count as a move in a puzzle that is not on it.
     */
    public static void forget() {
        pathTiles = List.of();
        TILE_INDEX.clear();
        nextRequired = 0;
        complete = false;
        storedOrigin = null;
        boundAnchor = null;
    }

    public static void reset() {
        clearBlocks(Minecraft.getInstance());
        TILE_INDEX.clear();
        nextRequired = 0;
        complete = false;
        boundAnchor = null;
    }

    private static void clearBlocks(Minecraft client) {
        List<BlockPos> old = pathTiles;
        pathTiles = List.of();
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
        List<BlockPos> path = pathTiles;
        if (!SimState.canAct(client) || path.isEmpty() || complete) {
            return;
        }
        BlockPos feet = client.player.blockPosition();
        Integer idx = TILE_INDEX.get(feet);
        if (idx != null) {
            if (idx == nextRequired) {
                advance(client, path);
            } else {
                // Only reachable by jumping over the gap left by an already-melted tile.
                failAndRebuild(client);
            }
            return;
        }
        // Not standing on any tracked tile - if that's because they fell through, the required tile's own
        // floor is the only thing that was ever solid there, so a real drop below it means nothing caught them.
        BlockPos required = path.get(Math.min(nextRequired, path.size() - 1));
        if (client.player.getY() < required.getY() - 1.5) {
            failAndRebuild(client);
        }
    }

    private static void advance(Minecraft client, List<BlockPos> path) {
        int landed = nextRequired;
        TILE_INDEX.remove(path.get(landed).above()); // stop standing still here from re-firing this method
        if (landed > 0) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                BlockPos melt = path.get(landed - 1);
                server.execute(() -> server.overworld().setBlockAndUpdate(melt, Blocks.AIR.defaultBlockState()));
            }
        }
        nextRequired = landed + 1;
        if (nextRequired >= path.size()) {
            complete = true;
            ModChat.send("Sim", ModChat.good("Ice Fill crossed!"));
        }
    }

    private static void failAndRebuild(Minecraft client) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing
        // auto-get setting works in here the same as it does on Hypixel.
        SimPuzzles.reportFail("Ice Fill");
        ModChat.send("Sim", ModChat.bad("Fell through the ice - resetting."));
        SimRoomPuzzles.Anchor bound = boundAnchor;
        if (bound != null) {
            // Bound to a real room: re-lay the same route in place and put him back at its first tile.
            // build() here would lay the standalone arena inside the captured room.
            rearmBound(client, bound);
            return;
        }
        BlockPos origin = storedOrigin;
        if (origin != null) {
            client.execute(() -> build(client, origin));
        }
    }

    /** Re-lays the route of a bound room and starts it over. */
    private static void rearmBound(Minecraft client, SimRoomPuzzles.Anchor bound) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<int[]> relPath = relativePath();
        List<BlockPos> path = new ArrayList<>(relPath.size());
        for (int[] rel : relPath) {
            path.add(bound.world(rel));
        }
        pathTiles = List.copyOf(path);
        TILE_INDEX.clear();
        for (int i = 0; i < path.size(); i++) {
            TILE_INDEX.put(path.get(i).above(), i);
        }
        nextRequired = 0;
        complete = false;
        BlockPos start = path.get(0);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos tile : path) {
                level.setBlockAndUpdate(tile, Blocks.PACKED_ICE.defaultBlockState());
            }
            ServerPlayer sp = server.getPlayerList().getPlayer(client.player.getUUID());
            if (sp != null) {
                sp.teleportTo((ServerLevel) sp.level(), start.getX() + 0.5, start.getY() + 1,
                        start.getZ() + 0.5, Set.<Relative>of(), sp.getYRot(), sp.getXRot(), false);
            }
        });
    }
}
