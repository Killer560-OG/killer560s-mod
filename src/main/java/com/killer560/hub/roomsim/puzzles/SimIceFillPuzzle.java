package com.killer560.hub.roomsim.puzzles;

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
    public static void reset() {
        clearBlocks(Minecraft.getInstance());
        TILE_INDEX.clear();
        nextRequired = 0;
        complete = false;
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
        BlockPos origin = storedOrigin;
        if (origin != null) {
            client.execute(() -> build(client, origin));
        }
    }
}
