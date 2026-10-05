package com.killer560.hub.roomsim.puzzles;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The dungeon "Boulder" puzzle, for the room sim: a random one of the eight known arrangements of 3x3x3 plank
 * boxes, with a button on every face that can be pushed, and a press that really moves the box.
 *
 * <h2>The rule, and how it was settled</h2>
 *
 * <p>killer560 (2026-10-02): "Boulder is still very wrong. It should have those boulders randomly generated, then
 * by pushing the buttons they move in that direction." The wiki (hypixelskyblock.minecraft.wiki, Catacombs
 * Puzzle Rooms) gives the shape: the room "is filled with 'boxes' (3x3x3 blocks of wood planks with buttons)",
 * the buttons move a box in the opposite direction from where it was pressed, and "if the box is at the very
 * back and you press the button, the box will disappear."
 *
 * <p>The bundled {@code boulder-solutions.json} pins down the rest, and it was checked against all of it rather
 * than read off the wiki alone. Model: a press moves its box ONE cell away from the button; if that cell is off
 * the grid or already holds a box, the box disappears instead. Played out over all eight patterns, every one of
 * the 18 solution steps finds its box where the step says, its button in an empty cell, and each pattern goes
 * from no path of empty cells between grid rows z=9 and z=24 to an open one. Pure "push one cell" fails 6 of the
 * 18 steps (the destination is occupied) and "pull towards the button" fails 4, so the disappearing box is not a
 * corner case: it is how half the solutions open the path.
 *
 * <h2>Geometry</h2>
 *
 * <p>The grid is {@code BoulderSolverFeature}'s own: cell centres at x 24..6 and z 24..9 in steps of 3, the
 * arrangement sampled at y 66. Decoding {@code Boulder.json} at database rotation 270 shows each box is the full
 * 3x3 around its centre on y 64, 65 and 66, and each button is a wall button at y 65 on a face centre, one block
 * out into the neighbouring cell - which is exactly the {@code [renderX, renderZ, clickX, clickZ]} pair the
 * solver's data gives (render = the face, click = the button). The capture's 31 buttons are the faces whose
 * neighbouring cell is on the grid and empty, so that is the rule the buttons are placed by, and they are placed
 * again after every move.
 *
 * <p>The old version wrote a one-block-wide column per cell (a box is three wide), always used the same pattern,
 * rolled a pushed column until it hit something, and failed the puzzle on a second press of a button - none of
 * which is the real room. The puzzle cannot be failed now: a box that disappears is the mechanic, not a mistake.
 *
 * <p>Same safety story as the rest of {@code roomsim}: gated on {@link SimState#canAct}, and every world write
 * happens on the integrated server's own thread.
 */
public final class SimBoulderPuzzle {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** {@code BoulderSolverFeature.scanFloor}'s order: z outer 24..9, x inner 24..6, both step -3. */
    private static final int[] Z_VALUES = {24, 21, 18, 15, 12, 9};
    private static final int[] X_VALUES = {24, 21, 18, 15, 12, 9, 6};
    private static final int COLS = X_VALUES.length;
    private static final int ROWS = Z_VALUES.length;

    /** A box is three blocks tall, y 64..66; the solver samples 66 and every button sits at 65. */
    private static final int BOX_BOTTOM_Y = 64;
    private static final int BOX_TOP_Y = 66;
    private static final int BUTTON_Y = 65;

    /** Writes tell the client and nothing else, so clearing a box never pops its buttons off as items. */
    private static final int WRITE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** Room-relative to world. A bound room uses its anchor; a standalone board an origin. */
    private interface Grid {
        BlockPos at(int x, int y, int z);
    }

    /** The eight bundled arrangements, as 42-character keys. */
    private static final List<String> PATTERNS = loadPatterns();

    /** The four push directions in room-relative steps: {dx, dz}. */
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Per cell, row-major over {@link #Z_VALUES} x {@link #X_VALUES}: the box's block, or null when empty. */
    private static volatile BlockState[] cells = new BlockState[0];
    /** The arrangement this board was generated with, so a reset puts back the same one. */
    private static volatile String pattern = null;
    /** The material each starting box had, so a reset does not reshuffle jungle and birch. */
    private static volatile BlockState[] startCells = new BlockState[0];
    private static volatile Grid grid = null;
    private static volatile boolean bound = false;

    /** World button position -> {cell index, direction index}: the box it is on and the side it is on. */
    private static final Map<BlockPos, int[]> BUTTONS = new ConcurrentHashMap<>();
    /** Every button position this board has written, so a re-layout can take the old ones away. */
    private static final Set<BlockPos> PLACED_BUTTONS = ConcurrentHashMap.newKeySet();

    private static volatile boolean complete = false;
    private static boolean registered = false;

    private SimBoulderPuzzle() {
    }

    /** Hooks the button right-click. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // THE SERVER presses the box, from the use-on packet, like Hypixel's. Until 2026-10-04 this listened on the
        // CLIENT copy of the callback, which only a client-side useItemOn reaches; Fabric's server copy fires inside
        // ServerPlayerGameMode.useItemOn for the ServerPlayer, so a real click, an auto's useItemOn and a raw packet
        // all push the box (docs/SIM.md, "The sim answers packets").
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(level instanceof ServerLevel server) || grid == null
                    || !SimState.isActive()) {
                return InteractionResult.PASS;
            }
            BlockPos clicked = hitResult.getBlockPos();
            if (clicked.equals(rewardChest)) {
                if (!rewardOpened) {
                    rewardOpened = true;
                    LOGGER.info("Sim boulder: reward chest {} opened (path open: {})", clicked, complete);
                }
                return InteractionResult.PASS;
            }
            int[] hit = BUTTONS.get(clicked);
            if (hit == null) {
                return InteractionResult.PASS;
            }
            press(server, hit[0], hit[1]);
            // PASS: vanilla's own press (click, animation) is what makes it read as a button.
            return InteractionResult.PASS;
        });
    }

    // ------------------------------------------------------------------------------------------- binding

    /**
     * Arms the puzzle on a REAL captured Boulder room: identifies the room by its own button row, clears the
     * captured boxes and buttons off the 42-cell grid, and lays a random bundled arrangement in their place.
     *
     * <p>The capture's own arrangement is not one of the eight, so it has no known solution - and he asked for a
     * random one each time anyway.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     */
    public static boolean bindAt(ServerLevel level, com.killer560.hub.roomsim.SimRoomPuzzles.Placement p) {
        // THE ROOM'S FIXED GEOMETRY is the fingerprint, never its boxes. This used to be the seven buttons along
        // one row of the capture's own arrangement - but every Boulder capture holds whatever arrangement Hypixel
        // dealt that run, so another capture of the same room failed it outright: Mod Only Test's (turned a
        // quarter, a different arrangement, 15 buttons) scored "best 1 of 7" at every rotation and the room was
        // never armed (93-solve, 2026-10-04). Decoded, both captures agree on what does not move: the diorite
        // squares under every other grid cell (a checkerboard - 21 of the 42 cell centres at y 63) and the far
        // staircase under the reward alcove (stone brick stairs at (13..17, 64, 27), the barrier over it at
        // y 68). A wrong rotation puts stone under half the diorite and the stairs into a wall.
        List<int[]> fixed = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if ((row + col) % 2 == 1) {
                    fixed.add(new int[]{X_VALUES[col], BOX_BOTTOM_Y - 1, Z_VALUES[row]});
                }
            }
        }
        for (int x = 13; x <= 17; x++) {
            fixed.add(new int[]{x, BOX_BOTTOM_Y, 27});
            fixed.add(new int[]{x, 68, 27});
        }
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor =
                com.killer560.hub.roomsim.SimRoomPuzzles.bestAnchor(level, p, fixed,
                        com.killer560.hub.roomsim.SimRoomPuzzles.is(Blocks.DIORITE, Blocks.STONE_BRICK_STAIRS,
                                Blocks.BARRIER), new int[]{0}, fixed.size() - 4);
        if (anchor == null || PATTERNS.isEmpty()) {
            return false;
        }
        forget();
        bound = true;
        grid = anchor::world;
        // The capture's own plank, for the boxes - it mixes jungle and birch, so both are offered.
        generate(level, PATTERNS.get(ThreadLocalRandom.current().nextInt(PATTERNS.size())));
        placeRewardChest(level, anchor);
        return true;
    }

    /**
     * Where the reward chest goes, ROOM-RELATIVE: the back middle of the little staircase past the far edge of the
     * box grid. killer560 (2026-10-04): "Boulder is now great just missing the chest. It should go kind of in the
     * back middle of that staircase on the opposite side of the entrance."
     *
     * <p>Decoded from {@code Boulder.json}: three steps at relative z 27..29 across x 13..17 (stairs at y 64 and 65,
     * stone bricks at 65 against the back wall at z 30), air over them up to the barrier at y 68 - so "back middle"
     * is standing on the top step, relative (15, 66, 29). This used to be the capture-local (30, 66, 16), which is
     * only that spot in a capture taken at the shipped turn; Mod Only Test's is turned a quarter, where (30, 66, 16)
     * is somewhere else. Through the bind's own anchor it is the same block in every capture. The chest faces one
     * block toward the room, relative (15, 66, 28).
     */
    private static final int[] CHEST_SPOT = {15, 66, 29};
    private static final int[] CHEST_FRONT = {15, 66, 28};

    /** The reward chest's world position once placed, for the opened-chest signal. */
    private static volatile BlockPos rewardChest = null;
    private static volatile boolean rewardOpened = false;

    /**
     * Places the reward chest, turned with the room. Server thread, at bind. Only written into air, and the outcome
     * is logged either way - a re-captured Boulder that moved the staircase reports it instead of burying a chest in
     * a wall (the Ice Fill reward chests' rule).
     */
    private static void placeRewardChest(ServerLevel level, com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor) {
        BlockPos spot = anchor.world(CHEST_SPOT);
        BlockPos front = anchor.world(CHEST_FRONT);
        Direction facing = horizontal(front.getX() - spot.getX(), front.getZ() - spot.getZ());
        if (!level.getBlockState(spot).isAir() || facing == null) {
            LOGGER.warn("Sim boulder: no reward chest - the spot {} (relative 15,66,29) holds {}. If Boulder has "
                    + "been re-captured that coordinate needs re-measuring.", spot, level.getBlockState(spot).getBlock());
            return;
        }
        level.setBlockAndUpdate(spot, Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing));
        rewardChest = spot.immutable();
        LOGGER.info("Sim boulder: reward chest at {} facing {} (database rotation {})", spot, facing,
                anchor.rotation());
    }

    /** A standalone board in front of him, for {@code /simpuzzle boulder}: the same grid on a stone floor. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null || PATTERNS.isEmpty()) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Relative (15, 64, 7) - the middle of the grid's near edge - lands on the block in front of him.
        BlockPos base = origin.immutable();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            forget();
            bound = false;
            grid = (x, y, z) -> base.offset(x - 15, y - BOX_BOTTOM_Y, z - 7);
            for (int x = 4; x <= 26; x++) {
                for (int z = 7; z <= 26; z++) {
                    level.setBlock(grid.at(x, BOX_BOTTOM_Y - 1, z), Blocks.STONE.defaultBlockState(), WRITE_FLAGS);
                }
            }
            generate(level, PATTERNS.get(ThreadLocalRandom.current().nextInt(PATTERNS.size())));
        });
    }

    /** Clears the grid and lays {@code key}, each box in a random one of the room's two planks. Server thread. */
    private static void generate(ServerLevel level, String key) {
        BlockState[] start = new BlockState[ROWS * COLS];
        for (int i = 0; i < start.length; i++) {
            if (key.charAt(i) == '1') {
                start[i] = ThreadLocalRandom.current().nextBoolean()
                        ? Blocks.JUNGLE_PLANKS.defaultBlockState() : Blocks.BIRCH_PLANKS.defaultBlockState();
            }
        }
        pattern = key;
        startCells = start;
        lay(level, start.clone());
        LOGGER.info("Sim boulder: laid bundled arrangement {} - {} box(es), {} button(s)", key,
                countBoxes(start), BUTTONS.size());
    }

    /** Puts {@code layout} into the world over whatever is there, and re-places every button. Server thread. */
    private static void lay(ServerLevel level, BlockState[] layout) {
        Grid g = grid;
        if (g == null) {
            return;
        }
        // Every block of every cell, top to bottom, so the captured boxes and the captured buttons both go.
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < layout.length; i++) {
            int cx = X_VALUES[i % COLS];
            int cz = Z_VALUES[i / COLS];
            BlockState want = layout[i] == null ? air : layout[i];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = BOX_BOTTOM_Y; y <= BOX_TOP_Y; y++) {
                        BlockPos pos = g.at(cx + dx, y, cz + dz);
                        if (!level.getBlockState(pos).equals(want)) {
                            level.setBlock(pos, want, WRITE_FLAGS);
                        }
                    }
                }
            }
        }
        cells = layout;
        placeButtons(level);
    }

    /**
     * A wall button on the middle of every box face whose neighbouring cell is on the grid and empty - the rule
     * the capture's own 31 buttons follow. Old buttons that no longer qualify are removed first.
     */
    private static void placeButtons(ServerLevel level) {
        Grid g = grid;
        BlockState[] c = cells;
        Map<BlockPos, int[]> fresh = new java.util.HashMap<>();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null) {
                continue;
            }
            int col = i % COLS;
            int row = i / COLS;
            for (int d = 0; d < DIRS.length; d++) {
                int n = neighbour(col, row, d);
                if (n < 0 || c[n] != null) {
                    continue;
                }
                int fx = X_VALUES[col] + DIRS[d][0];
                int fz = Z_VALUES[row] + DIRS[d][1];
                BlockPos face = g.at(fx, BUTTON_Y, fz);
                BlockPos button = g.at(fx + DIRS[d][0], BUTTON_Y, fz + DIRS[d][1]);
                fresh.put(button.immutable(), new int[]{i, d});
                Direction facing = horizontal(button.getX() - face.getX(), button.getZ() - face.getZ());
                if (facing == null) {
                    continue;
                }
                BlockState state = Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                        .setValue(HorizontalDirectionalBlock.FACING, facing)
                        .setValue(ButtonBlock.POWERED, Boolean.FALSE);
                level.setBlock(button, state, WRITE_FLAGS);
            }
        }
        for (BlockPos old : PLACED_BUTTONS) {
            if (!fresh.containsKey(old) && level.getBlockState(old).is(Blocks.STONE_BUTTON)) {
                level.setBlock(old, Blocks.AIR.defaultBlockState(), WRITE_FLAGS);
            }
        }
        PLACED_BUTTONS.clear();
        PLACED_BUTTONS.addAll(fresh.keySet());
        BUTTONS.clear();
        BUTTONS.putAll(fresh);
    }

    /** The world direction of a one-block step, or null when it is not a single horizontal step. */
    private static Direction horizontal(int dx, int dz) {
        if (dx == 1 && dz == 0) {
            return Direction.EAST;
        }
        if (dx == -1 && dz == 0) {
            return Direction.WEST;
        }
        if (dx == 0 && dz == 1) {
            return Direction.SOUTH;
        }
        if (dx == 0 && dz == -1) {
            return Direction.NORTH;
        }
        return null;
    }

    /** The cell one step from (col, row) in direction {@code d}, or -1 off the grid. */
    private static int neighbour(int col, int row, int d) {
        int x = X_VALUES[col] + 3 * DIRS[d][0];
        int z = Z_VALUES[row] + 3 * DIRS[d][1];
        int nc = indexOf(X_VALUES, x);
        int nr = indexOf(Z_VALUES, z);
        return nc < 0 || nr < 0 ? -1 : nr * COLS + nc;
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------ pressing

    /**
     * A button on side {@code d} of box {@code cell} was pressed: the box goes one cell the other way, or
     * disappears when that cell is off the grid or taken. Server thread.
     */
    private static void press(ServerLevel level, int cell, int d) {
        BlockState[] c = cells;
        if (cell < 0 || cell >= c.length || c[cell] == null || complete) {
            return;
        }
        int away = d ^ 1;   // DIRS pairs opposites: 0/1 and 2/3
        int dest = neighbour(cell % COLS, cell / COLS, away);
        BlockState[] next = c.clone();
        BlockState box = next[cell];
        next[cell] = null;
        boolean moved = dest >= 0 && next[dest] == null;
        if (moved) {
            next[dest] = box;
        }
        lay(level, next);
        LOGGER.info("Sim boulder: button pressed - box {} {} ({} box(es) left)", cell,
                moved ? "moved one cell" : "disappeared", countBoxes(next));
        if (pathOpen(next)) {
            complete = true;
            LOGGER.info("Sim boulder: solved - the way through is open");
            Minecraft.getInstance().execute(() ->
                    ModChat.send("Sim", ModChat.good("Boulder"), ModChat.text(" solved - the way through is open.")));
        }
    }

    /**
     * Whether empty cells join grid row z=9 to row z=24. Measured against the bundled data: all eight patterns
     * are closed before their solution and open after it, so this is the solved test.
     */
    private static boolean pathOpen(BlockState[] c) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        int nearRow = ROWS - 1;   // z = 9
        for (int col = 0; col < COLS; col++) {
            int i = nearRow * COLS + col;
            if (c[i] == null) {
                q.add(i);
                seen.add(i);
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            if (i / COLS == 0) {   // z = 24
                return true;
            }
            for (int d = 0; d < DIRS.length; d++) {
                int n = neighbour(i % COLS, i / COLS, d);
                if (n >= 0 && c[n] == null && seen.add(n)) {
                    q.add(n);
                }
            }
        }
        return false;
    }

    private static int countBoxes(BlockState[] c) {
        int n = 0;
        for (BlockState s : c) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    /** Whether the boxes have been pushed so a way runs from the near row to the far one - the puzzle solved. */
    public static boolean isComplete() {
        return complete;
    }

    /** Whether the reward chest has been opened (right-clicked) since the board was laid. */
    public static boolean isRewardChestOpened() {
        return rewardOpened;
    }

    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world - for a floor about to be rebuilt, where the
     * positions held here are absolute and would land inside the new floor.
     */
    public static void forget() {
        rewardChest = null;
        rewardOpened = false;
        BUTTONS.clear();
        PLACED_BUTTONS.clear();
        cells = new BlockState[0];
        startCells = new BlockState[0];
        pattern = null;
        grid = null;
        bound = false;
        complete = false;
    }

    /** The same arrangement back, boxes and buttons, for an Architect's First Draft or {@code /simpuzzle reset}. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (grid == null || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        BlockState[] start = startCells.clone();
        server.execute(() -> {
            complete = false;
            lay(server.overworld(), start);
        });
    }

    /** The keys of {@code boulder-solutions.json} - the same file and the same loading as the solver. */
    private static List<String> loadPatterns() {
        try (InputStream stream = SimBoulderPuzzle.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/boulder-solutions.json")) {
            if (stream == null) {
                return List.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, List<List<Integer>>>>() {
                }.getType();
                Map<String, List<List<Integer>>> parsed = new Gson().fromJson(reader, type);
                if (parsed == null) {
                    return List.of();
                }
                List<String> out = new ArrayList<>();
                for (String key : parsed.keySet()) {
                    if (key.length() == ROWS * COLS) {
                        out.add(key);
                    }
                }
                return List.copyOf(out);
            }
        } catch (Exception e) {
            LOGGER.warn("[SimBoulderPuzzle] Failed to load boulder-solutions.json", e);
            return List.of();
        }
    }
}
