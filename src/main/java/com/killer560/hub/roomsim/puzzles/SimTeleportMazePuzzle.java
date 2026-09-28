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
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small standalone practice arena for the real Hypixel dungeon "Teleport Maze" puzzle, playable inside the
 * room sim: stand on the right pad, in sequence, cell after cell, to cross.
 *
 * <p><b>What is real, ported from {@link com.killer560.hub.puzzlesolvers.TeleportMazeSolverFeature}'s own class
 * doc</b>: "The room has 7 cells of 4 end-portal-frame pads plus a start and end pad." So {@link #CELL_COUNT}
 * (7) and {@link #PADS_PER_CELL} (4) are a real, cited fact from this codebase's own real solver, not invented -
 * and the core rule this arena drills, that only one of a cell's pads is the correct one and the other three do
 * not advance you, is the same rule that class exists to detect live off real teleport packets.
 *
 * <p><b>What is this file's own invention</b>, same category as {@link SimBlazePuzzle}'s stand positions: the
 * actual spatial layout - a short line of small walkable platforms spaced out from {@code origin}, instead of
 * the real room's own far-apart fixed 30-coordinate layout (only reachable there by a real teleport packet, not
 * by walking, which is exactly why the real thing needs solving rather than crossing on foot) - and, freshly
 * every time {@link #build} runs, WHICH of a cell's 4 pads is the correct one. TeleportMazeSolverFeature's own
 * doc records that on Hypixel this is only ever found live, by ray-testing the landing rotation after a real
 * teleport; there is no static "pad 2 of cell 5 is always right" table anywhere in this codebase to port,
 * because the real game decides it fresh per room instance.
 *
 * <p>Stepping on any pad other than the current cell's correct one is the one thing that must not just do
 * nothing: it wipes and rebuilds the whole arena (fresh random correct pads, back at the start), the same
 * "getting it wrong costs the whole attempt" feel {@code SimBlazePuzzle#failAndRebuild} gives an out-of-order
 * kill - and a harder consequence than the real room's own "wrong pad just re-teleports you inside the same
 * cell" (see the class doc above). That is a deliberate strengthening for practice, not a claim about how
 * severe the real Hypixel room is.
 *
 * <p>Gated on {@link SimState#canAct} throughout. Every write to the world or the player happens on the
 * integrated server via {@code server.execute(...)}, never the client thread - same rule as the rest of
 * {@code roomsim}, see {@code SimDoors}' class doc; the teleport itself is the server-authoritative pattern
 * {@code SimAbilities#teleport} uses, moving the SERVER's player rather than the client entity, which is why a
 * teleport is safe to do here and nowhere else this mod touches Hypixel.
 */
public final class SimTeleportMazePuzzle {

    private static final int CELL_COUNT = 7; // real fact - see class doc
    private static final int PADS_PER_CELL = 4; // real fact - see class doc
    /** Invented spacing between cells - just far enough that no cell's platform touches its neighbour's. */
    private static final int CELL_SPACING = 6;

    /** Pad offsets within a cell, relative to that cell's own entry tile - this file's own invented layout. */
    private static final int[][] PAD_OFFSETS = {{-1, 0, 3}, {1, 0, 3}, {-1, 0, 4}, {1, 0, 4}};

    /** Every (dx, 0, dz) tile that makes up one cell's walkable floor - the entry plus the run up to the pads. */
    private static final int[][] FLOOR_OFFSETS = buildFloorOffsets();

    private static int[][] buildFloorOffsets() {
        List<int[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 0; dz <= 4; dz++) {
                out.add(new int[]{dx, 0, dz});
            }
        }
        return out.toArray(new int[0][]);
    }

    /** Which pad (0-3) is correct for each cell, chosen fresh every {@link #build}. */
    private static volatile int[] correctPad = new int[0];

    /** Feet-level position -> {cell, padIndex}, rebuilt every {@link #build}. */
    private static final Map<BlockPos, int[]> PAD_INDEX = new HashMap<>();

    /** Every block position this session has placed, for {@link #reset} to clear. */
    private static volatile List<BlockPos> builtBlocks = List.of();

    /** Anchor for cell {@code c}'s floor/pads: the block one below where the player stands entering that cell. */
    private static volatile BlockPos[] cellAnchor = new BlockPos[0];

    private static volatile int currentCell = 0;
    private static volatile boolean complete = false;
    private static volatile boolean built = false;
    private static volatile BlockPos storedOrigin = null;

    private static boolean registered = false;

    private SimTeleportMazePuzzle() {
    }

    /** Registers the progress-polling tick hook. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.START_CLIENT_TICK.register(
                FeatureGuard.start("SimTeleportMazePuzzle.tick", SimTeleportMazePuzzle::tick));
    }

    /** Clears any previous arena and builds a fresh one, with fresh random correct pads, at {@code origin}. */
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
        BlockPos anchor = origin.below();

        BlockPos[] anchors = new BlockPos[CELL_COUNT + 1]; // +1: the final landing spot past the last cell
        for (int c = 0; c <= CELL_COUNT; c++) {
            anchors[c] = anchor.offset(0, 0, c * CELL_SPACING);
        }
        cellAnchor = anchors;

        int[] chosen = new int[CELL_COUNT];
        for (int c = 0; c < CELL_COUNT; c++) {
            chosen[c] = ThreadLocalRandom.current().nextInt(PADS_PER_CELL);
        }
        correctPad = chosen;

        PAD_INDEX.clear();
        List<BlockPos> toPlace = new ArrayList<>();
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> painted = new HashMap<>();
        for (int c = 0; c <= CELL_COUNT; c++) {
            BlockPos cellAnchorPos = anchors[c];
            for (int[] off : FLOOR_OFFSETS) {
                BlockPos pos = cellAnchorPos.offset(off[0], off[1], off[2]).immutable();
                toPlace.add(pos);
                painted.put(pos, Blocks.SMOOTH_STONE.defaultBlockState());
            }
            if (c == CELL_COUNT) {
                // The landing platform past the last cell has no pads to choose - just a marker to stand on.
                BlockPos endMarker = cellAnchorPos.offset(0, 0, 1).immutable();
                painted.put(endMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                continue;
            }
            for (int p = 0; p < PADS_PER_CELL; p++) {
                int[] off = PAD_OFFSETS[p];
                BlockPos padPos = cellAnchorPos.offset(off[0], off[1], off[2]).immutable();
                painted.put(padPos, Blocks.GOLD_BLOCK.defaultBlockState());
                PAD_INDEX.put(padPos.above(), new int[]{c, p});
            }
        }
        builtBlocks = List.copyOf(toPlace);
        currentCell = 0;
        complete = false;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> e : painted.entrySet()) {
                level.setBlockAndUpdate(e.getKey(), e.getValue());
            }
        });
        built = true;
        ModChat.send("Sim", ModChat.text("Teleport Maze built - find the right pad, "),
                ModChat.value(CELL_COUNT + " cells"), ModChat.text(" to cross."));
    }

    /** True once the last cell's correct pad has sent the player to the final landing spot. */
    public static boolean isComplete() {
        return complete;
    }

    /** Clears the arena and its bookkeeping. Safe with nothing built. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        clearBlocks(client);
        cellAnchor = new BlockPos[0];
        correctPad = new int[0];
        PAD_INDEX.clear();
        currentCell = 0;
        complete = false;
        built = false;
    }

    private static void clearBlocks(Minecraft client) {
        List<BlockPos> old = builtBlocks;
        builtBlocks = List.of();
        if (old.isEmpty() || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : old) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client) || !built || complete) {
            return;
        }
        BlockPos feet = client.player.blockPosition();
        int[] hit = PAD_INDEX.get(feet);
        if (hit == null) {
            return;
        }
        int cell = hit[0];
        int padIndex = hit[1];
        if (cell != currentCell) {
            // Only reachable by standing on a pad from a cell the player has no business being on yet/again.
            failAndRebuild(client);
            return;
        }
        // Remove immediately so standing still on the same pad for more than one tick can't re-fire this.
        PAD_INDEX.remove(feet);
        if (padIndex == correctPad[cell]) {
            advanceToNextCell(client);
        } else {
            failAndRebuild(client);
        }
    }

    private static void advanceToNextCell(Minecraft client) {
        int next = currentCell + 1;
        currentCell = next;
        BlockPos landing = cellAnchor[next];
        teleport(client, landing.getX() + 0.5, landing.getY() + 1, landing.getZ() + 0.5);
        if (next >= CELL_COUNT) {
            complete = true;
            ModChat.send("Sim", ModChat.good("Teleport Maze crossed!"));
        } else {
            ModChat.send("Sim", ModChat.text("Correct pad - cell " + (next + 1) + "."));
        }
    }

    private static void failAndRebuild(Minecraft client) {
        ModChat.send("Sim", ModChat.bad("Wrong pad - resetting the maze."));
        BlockPos origin = storedOrigin;
        if (origin != null) {
            client.execute(() -> build(client, origin));
        }
    }

    /**
     * Server-authoritative teleport, same shape as {@code SimAbilities#teleport}: moves the SERVER's player,
     * never {@code client.player.setPos}, so the integrated server (the position source of truth) never
     * disagrees with the client on the very next tick. Relative set is empty so yaw/pitch are kept.
     */
    private static void teleport(Minecraft client, double x, double y, double z) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        UUID uuid = client.player.getUUID();
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            // level() rather than serverLevel(): ServerPlayer has no serverLevel() accessor in 26.1.2.
            sp.teleportTo((ServerLevel) sp.level(), x, y, z, Set.<Relative>of(), yaw, pitch, false);
        });
    }
}
