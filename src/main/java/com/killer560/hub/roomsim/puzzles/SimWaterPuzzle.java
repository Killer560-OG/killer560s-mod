package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.puzzlesolvers.WaterSolverConfig;
import com.killer560.hub.puzzlesolvers.WaterSolverFeature;
import com.killer560.hub.puzzlesolvers.WaterSolverFeature.LeverBlock;
import com.killer560.hub.puzzlesolvers.WaterSolverFeature.WoolColor;
import com.killer560.hub.roomsim.SimRoomPuzzles;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dungeon "Water Board" puzzle, for the room sim - the real mechanic, not a click-order stand-in.
 *
 * <h2>The mechanic, as killer560 described it</h2>
 *
 * <p>2026-10-01: <i>"For water board there is no way to fail it. Pulling the lever moves the blocks on the back
 * of the wall in or out to let water flow down. if it gets into the column with that color it moves the wool in
 * or out. THe back lever starts or stops the water from flowing."</i>
 *
 * <p>So there are three visible things, and this file drives all three:
 *
 * <ol>
 *   <li><b>An ore lever moves blocks.</b> Each of the six ore levers owns a gate - the run of three blocks at
 *       the foot of its own plinth, at the level the water runs - and flipping it takes them out or puts them
 *       back. Every flip moves them, right or wrong, because that is what a lever does.</li>
 *   <li><b>The back lever starts or stops the water.</b> The room's own water column behind the entrance wall
 *       ({@code (15, 59..62, 4)}, fed by the source at {@code (15, 62, 3)}) is REMOVED when the flow is off and
 *       put back when it is on. The capture was taken with it running, so binding turns it off and he starts
 *       it himself, which is the real first click of every solution.</li>
 *   <li><b>Water reaching a colour's column moves that wool.</b> The five colours each own a column at
 *       {@code x=15}; the wool sits at {@code (15, 55, z)} and is pushed up to {@code (15, 56, z)}. That upper
 *       block is exactly what {@code WaterSolverFeature.scan} reads to decide which three are "extended", so
 *       the sim moving it between those two positions is the same event his solver watches for.</li>
 * </ol>
 *
 * <h2>What is real here</h2>
 *
 * <p>Everything about the board comes from the live solver rather than from a second copy in here: the seven
 * lever positions ({@link LeverBlock#relX()} and friends), the five colour columns ({@link WoolColor#relZ()}),
 * the four blocks that identify which physical board is active
 * ({@link WaterSolverFeature#IDENTIFIER_MARKERS}), and the timings themselves
 * ({@link WaterSolverFeature#bundledClickOrder}, straight out of the bundled
 * {@code water-solutions.json} Odin ships). It even reads his own <b>Optimized Path</b> setting, because that
 * switches the whole sequence - a sim that ignored it would disagree with the tracer on his screen.
 *
 * <p>That is also what makes his <b>solver work in here</b>, which he asked for. The solver needs three of the
 * five colours pushed out and a recognised marker block; a captured room has neither (every colour is retracted
 * in the capture). So binding picks a board the bundled file actually contains, pushes those three colours out,
 * and then drives itself from the same entry - the solver scans the room, finds the same three, keys into the
 * same solution and lights up the same levers the sim is scoring against.
 *
 * <h2>What the sim decides for itself</h2>
 *
 * <p>Two things, both stated rather than hidden:
 *
 * <ul>
 *   <li><b>Which colours open when.</b> The real room's redstone decides this; a capture is one frozen frame of
 *       it and cannot be replayed. So the colours open in the order the water would reach them - nearest the
 *       entrance first - spread evenly across the timed clicks. Three colours and two timed clicks means one
 *       opens per third of the sequence.</li>
 *   <li><b>How you fail.</b> He asked for a way to fail, and the mechanic gives an honest one: a click at the
 *       wrong moment sends the water down the wrong column, and a column whose wool is already in gets pushed
 *       back out. Three such mistakes and the puzzle is failed - chat, the room red on the map and the
 *       Architect's First Draft offer, the same as every other sim puzzle
 *       ({@link SimPuzzles#reportFail(String, String)}).</li>
 * </ul>
 *
 * <h2>Safety</h2>
 *
 * <p>Same as the rest of {@code roomsim}: gated on {@link SimState#canAct}, and every world write happens on
 * the integrated server's own thread - see {@code SimDoors}'s class doc. The writes use
 * {@link #WRITE_FLAGS}, which tells the client but schedules no neighbour or fluid updates: the room's water
 * is a sealed column and a cascade out of it would flood the floor.
 */
public final class SimWaterPuzzle {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /**
     * Block-write flags for this puzzle.
     *
     * <p>{@link Block#UPDATE_CLIENTS} because a player is already standing in this level and has to see the
     * change; {@link Block#UPDATE_SKIP_ALL_SIDEEFFECTS} so nothing schedules a fluid or neighbour tick off the
     * back of it. That second half is not tidiness: the water this puzzle places and removes is a sealed
     * column, and one scheduled fluid tick with a hole in the wrong place is a flooded floor.
     */
    private static final int WRITE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** How far off a required click time may be. A second each way: this is practice, not a frame-perfect run. */
    private static final double TOLERANCE_SECONDS = 1.0;

    /** Mistakes before the puzzle is failed. */
    private static final int MAX_MISTAKES = 3;


    /** The colour wool's two positions: retracted, and pushed up into the walkway where the solver reads it. */
    private static final int WOOL_IN_Y = 55;
    private static final int WOOL_OUT_Y = 56;

    /** The room's own water: a source and the column it feeds, all at {@code x=15} behind the entrance wall. */
    private static final int[] WATER_SOURCE = {15, 62, 3};
    private static final int[][] WATER_COLUMN = {{15, 62, 4}, {15, 61, 4}, {15, 60, 4}, {15, 59, 4}};

    // ------------------------------------------------------------------------------------- where it is bound

    /** Non-null while bound to a real captured room. */
    private static volatile SimRoomPuzzles.Anchor anchor = null;
    /** Non-null while standing in a {@code /simpuzzle water} arena instead. */
    private static volatile BlockPos standaloneOrigin = null;
    /** The bound room's name, for the chat label and the red square on the map. */
    private static volatile String boundRoom = null;

    private static final Map<LeverBlock, BlockPos> POSITIONS = new EnumMap<>(LeverBlock.class);
    private static final Map<BlockPos, LeverBlock> BLOCK_INDEX = new ConcurrentHashMap<>();

    /**
     * The BACK WALL, which is where the blocks a lever moves actually are.
     *
     * <p>killer560 (2026-10-01): "it should move those blocks at the very back in and out on that wall, not
     * right belowt he levers." The first version moved three blocks at the foot of each lever's plinth, which
     * was a guess; the real thing is the piston board filling the room's back wall, and the capture holds all
     * of it.
     *
     * <p>Twenty-seven sticky pistons stand at room-relative {@code z=28}. Each pushes an ORE BLOCK, and that
     * ore block is what says which lever owns it: {@code coal_block} is COAL's, {@code terracotta} is CLAY's,
     * and so on through the six. When the slot is RETRACTED the ore sits at {@code z=27}, visible on the wall
     * face; when it is EXTENDED the piston head is at {@code z=27} and the ore has been pushed back to
     * {@code z=26}. So the wall's pattern of ore blocks IS the board's state, and reading either of those two
     * cells tells you both whose slot it is and which way it is currently sitting.
     *
     * <p>Counted off the capture: coal 4, gold 3, quartz 5, diamond 6, emerald 4, clay 4, and one
     * {@code lapis_block} slot that belongs to no lever and is left alone. Thirteen of the twenty-seven start
     * extended, which is the "some amount of blocks need to start out" he asked for - it is the room's own
     * starting pattern, not one invented here.
     */
    private record Slot(BlockPos piston, BlockPos face, BlockPos behind, BlockState ore) {
    }

    private static final Map<LeverBlock, List<Slot>> GATES = new EnumMap<>(LeverBlock.class);
    /** Whether each slot is currently pushed out. */
    private static final Map<BlockPos, Boolean> SLOT_OUT = new ConcurrentHashMap<>();
    /** How the room had each slot when it was armed, so a reset restores the board rather than flattening it. */
    private static final Map<BlockPos, Boolean> STARTED_OUT = new ConcurrentHashMap<>();
    /** Copied from the room rather than built from a literal: a piston head and the two piston states. */
    private static volatile BlockState headState = null;
    private static volatile BlockState pistonOut = null;
    private static volatile BlockState pistonIn = null;
    private static final Set<LeverBlock> GATES_OPEN = EnumSet.noneOf(LeverBlock.class);

    /** Where the piston board stands, room-relative: the pistons, their faces and the cell behind. */
    private static final int BOARD_PISTON_Z = 28;
    private static final int BOARD_FACE_Z = 27;
    private static final int BOARD_BEHIND_Z = 26;
    private static final int BOARD_MIN_X = 4;
    private static final int BOARD_MAX_X = 28;
    private static final int BOARD_MIN_Y = 58;
    private static final int BOARD_MAX_Y = 90;

    /** Which lever an ore block on the board belongs to. The lapis slot is in no lever's list. */
    private static LeverBlock leverForOre(BlockState state) {
        if (state.is(Blocks.COAL_BLOCK)) {
            return LeverBlock.COAL;
        }
        if (state.is(Blocks.GOLD_BLOCK)) {
            return LeverBlock.GOLD;
        }
        if (state.is(Blocks.QUARTZ_BLOCK)) {
            return LeverBlock.QUARTZ;
        }
        if (state.is(Blocks.DIAMOND_BLOCK)) {
            return LeverBlock.DIAMOND;
        }
        if (state.is(Blocks.EMERALD_BLOCK)) {
            return LeverBlock.EMERALD;
        }
        if (state.is(Blocks.TERRACOTTA)) {
            return LeverBlock.CLAY;   // "hardened_clay" in the solution file's own spelling
        }
        return null;
    }

    /** Reads the room's piston board into {@link #GATES}. Server thread, at arm time. */
    private static void readBoard(ServerLevel level) {
        GATES.clear();
        SLOT_OUT.clear();
        headState = null;
        pistonOut = null;
        pistonIn = null;
        int found = 0;
        for (int x = BOARD_MIN_X; x <= BOARD_MAX_X; x++) {
            for (int y = BOARD_MIN_Y; y <= BOARD_MAX_Y; y++) {
                BlockPos piston = at(x, y, BOARD_PISTON_Z);
                if (piston == null || !level.getBlockState(piston).is(Blocks.STICKY_PISTON)) {
                    continue;
                }
                BlockPos face = at(x, y, BOARD_FACE_Z);
                BlockPos behind = at(x, y, BOARD_BEHIND_Z);
                BlockState atFace = level.getBlockState(face);
                boolean out = atFace.is(Blocks.PISTON_HEAD);
                BlockState ore = out ? level.getBlockState(behind) : atFace;
                LeverBlock lever = leverForOre(ore);
                if (lever == null) {
                    continue;   // the lapis slot, or a cell the capture never read
                }
                if (out && headState == null) {
                    headState = atFace;
                    pistonOut = level.getBlockState(piston);
                } else if (!out && pistonIn == null) {
                    pistonIn = level.getBlockState(piston);
                }
                GATES.computeIfAbsent(lever, k -> new ArrayList<>())
                        .add(new Slot(piston, face, behind, ore));
                SLOT_OUT.put(face, out);
                STARTED_OUT.put(face, out);
                found++;
            }
        }
        LOGGER.info("Sim Water Board: back wall read - {} slot(s) over {} lever(s), {} starting out",
                found, GATES.size(), SLOT_OUT.values().stream().filter(Boolean::booleanValue).count());
    }

    // ------------------------------------------------------------------------------------------- the board

    /** The three colours this board starts with pushed out, in the order the water reaches them. */
    private static volatile List<WoolColor> board = List.of();
    /** Those of {@link #board} currently retracted - the ones already solved. */
    private static final Set<WoolColor> openColours = EnumSet.noneOf(WoolColor.class);

    /** The zero-time clicks, one per ore lever, that set the gates before the water is let in. */
    private static final Set<LeverBlock> preFlow = EnumSet.noneOf(LeverBlock.class);
    private static final Set<LeverBlock> preFlowDone = EnumSet.noneOf(LeverBlock.class);

    /** The timed clicks, in the order his solver counts them down. */
    private static volatile List<Map.Entry<LeverBlock, Double>> timed = List.of();
    private static volatile int timedDone = 0;

    private static volatile int mistakes = 0;
    private static volatile boolean complete = false;
    private static volatile boolean flowing = false;

    /** Ticks since binding, and the tick the water was first let in - the same bookkeeping pair
     *  {@code WaterSolverFeature} keeps, because the bundled times are relative to that moment. */
    private static long tickCounter = 0;
    private static long openedTick = -1;

    private SimWaterPuzzle() {
    }

    /** Hooks the lever right-click and a tick counter. Wiring: call once from mod init, alongside the other
     *  roomsim puzzle registrations. */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event also fires server-side; without the check a real click would be
            // evaluated twice, same double-fire guard SimDoors uses for its own UseBlockCallback registration.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player || !isArmed()) {
                return InteractionResult.PASS;
            }
            LeverBlock lever = BLOCK_INDEX.get(hitResult.getBlockPos());
            if (lever == null) {
                return InteractionResult.PASS;
            }
            onLeverClicked(client, lever);
            // PASS, not SUCCESS: vanilla's own lever toggle (sound, redstone, animation) is what makes this
            // read as a real lever - we only observe the click, never fake or consume it.
            return InteractionResult.PASS;
        });
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimWaterPuzzle.tick", SimWaterPuzzle::tick));
    }

    private static void tick(Minecraft client) {
        if (!isArmed() || !SimState.canAct(client)) {
            return;
        }
        tickCounter++;
    }

    private static boolean isArmed() {
        return anchor != null || standaloneOrigin != null;
    }

    /**
     * A room-relative position in the world, however this puzzle is currently standing.
     *
     * <p>Bound: through the anchor, which is the only coordinate system the real offsets mean anything in.
     * Standalone: measured off the back lever, so {@code /simpuzzle water} puts the arena around him rather
     * than sixty blocks over his head, which is where raw offsets of {@code y=61} used to land it.
     */
    private static BlockPos at(int rx, int ry, int rz) {
        SimRoomPuzzles.Anchor a = anchor;
        if (a != null) {
            return a.world(rx, ry, rz);
        }
        BlockPos o = standaloneOrigin;
        return o == null ? null : o.offset(rx - LeverBlock.WATER.relX(), ry - LeverBlock.WATER.relY(),
                rz - LeverBlock.WATER.relZ());
    }

    // --------------------------------------------------------------------------------------------- binding

    /**
     * Arms this puzzle on a REAL captured Water Board room the sim has just pasted.
     *
     * <p>The seven relative offsets are the live solver's own and the capture holds a {@code lever} at every
     * one of them - measured: 7 of 7 at database rotation 270 against 4, 6 and 4 at the other three. So no
     * lever is placed. {@link SimRoomPuzzles#bestAnchor} decides the rotation by scoring them rather than
     * trusting the recovered capture turn, and refuses to arm a room whose levers are not there.
     *
     * <p>Server thread only - called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, SimRoomPuzzles.Placement p) {
        List<int[]> rels = new ArrayList<>();
        for (LeverBlock lever : LeverBlock.values()) {
            rels.add(new int[]{lever.relX(), lever.relY(), lever.relZ()});
        }
        SimRoomPuzzles.Anchor found = SimRoomPuzzles.bestAnchor(level, p, rels,
                SimRoomPuzzles.is(Blocks.LEVER), new int[]{0}, 5);
        if (found == null) {
            return false;
        }
        forget();
        anchor = found;
        boundRoom = p.room().name;
        return arm(level, true);
    }

    /**
     * Builds a practice arena where he is standing, for {@code /simpuzzle water}.
     *
     * <p>The levers, the five colour columns and the six gates are placed; the water column is not, because
     * out here there is no sealed shaft to hold it and a source block in the open would flood whatever the
     * arena was built on. The flow is still tracked and still has to be started - only its water is invisible,
     * which is said in chat so it does not read as broken.
     */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        BlockPos base = origin;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            forget();
            standaloneOrigin = base;
            // The levers need something to stand on, and the colour columns need a floor - the minimum the
            // mechanic needs, the same approach SimDoors takes with its door blocks.
            for (LeverBlock lever : LeverBlock.values()) {
                BlockPos pos = at(lever.relX(), lever.relY(), lever.relZ());
                level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), WRITE_FLAGS);
                BlockState leverState = Blocks.LEVER.defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                        .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, Boolean.FALSE);
                level.setBlock(pos, setFace(leverState), WRITE_FLAGS);
            }
            for (WoolColor colour : WoolColor.values()) {
                level.setBlock(at(15, WOOL_IN_Y - 1, colour.relZ()), Blocks.STONE.defaultBlockState(), WRITE_FLAGS);
            }
            // No gates in a standalone arena: the gates are the captured room's piston board, and a bare
            // arena has no back wall to put one on. The levers, the clock and the colour columns are all
            // here; only the thing they move is missing, which is said in chat below rather than faked with
            // a row of andesite under each lever (which is what this used to build, and what he saw as
            // blocks moving in the wrong place).
            if (!arm(level, false)) {
                ModChat.send("Sim", ModChat.bad("Water Board"),
                        ModChat.text(" could not be set up - no bundled board matched."));
            }
        });
    }

    /**
     * The shared half of binding and building: resolve every position, pick a board, set the room to its
     * starting state. Server thread only.
     *
     * @param withWater whether this arena has the room's own sealed water column to show
     */
    private static boolean arm(ServerLevel level, boolean withWater) {
        POSITIONS.clear();
        BLOCK_INDEX.clear();
        GATES_OPEN.clear();
        for (LeverBlock lever : LeverBlock.values()) {
            BlockPos pos = at(lever.relX(), lever.relY(), lever.relZ());
            POSITIONS.put(lever, pos);
            BLOCK_INDEX.put(pos, lever);
        }
        // The back wall IS the gates - see Slot. A standalone arena has no wall, so it reads nothing and the
        // levers simply have nothing to move; the rules still work.
        readBoard(level);

        int identifier = identifierAt(level);
        if (identifier < 0) {
            // The capture's own marker is the terracotta one, so board 0 is the honest fallback - and saying
            // so beats refusing to arm a room he can see is a Water Board.
            LOGGER.warn("Sim Water Board: none of the four identifier markers is in the room - assuming board 0");
            identifier = 0;
        }
        boolean optimized = WaterSolverConfig.getInstance().isOptimizedPath();
        String slots = pickBoard(optimized, identifier);
        if (slots == null) {
            LOGGER.warn("Sim Water Board: water-solutions.json has no board for identifier {} with optimizedPath"
                    + " {} - not armed", identifier, optimized);
            forget();
            return false;
        }
        List<Map.Entry<LeverBlock, Double>> order =
                WaterSolverFeature.bundledClickOrder(optimized, identifier, slots);
        preFlow.clear();
        preFlowDone.clear();
        List<Map.Entry<LeverBlock, Double>> timedOrder = new ArrayList<>();
        for (Map.Entry<LeverBlock, Double> click : order) {
            if (click.getValue() == 0.0 && click.getKey() != LeverBlock.WATER) {
                preFlow.add(click.getKey());
            } else if (click.getValue() != 0.0) {
                timedOrder.add(click);
            }
        }
        timed = List.copyOf(timedOrder);
        timedDone = 0;
        mistakes = 0;
        complete = false;
        openedTick = -1;
        tickCounter = 0;

        // The three colours this board starts with pushed out, ordered the way the water meets them: the
        // entrance is at the low-z end, so nearest first.
        List<WoolColor> chosen = new ArrayList<>();
        for (int i = 0; i < slots.length(); i++) {
            chosen.add(WoolColor.values()[slots.charAt(i) - '0']);
        }
        chosen.sort((a, b) -> Integer.compare(a.relZ(), b.relZ()));
        board = List.copyOf(chosen);
        openColours.clear();

        for (WoolColor colour : WoolColor.values()) {
            setWool(level, colour, board.contains(colour));
        }
        for (LeverBlock lever : LeverBlock.values()) {
            BlockState current = level.getBlockState(POSITIONS.get(lever));
            if (current.hasProperty(net.minecraft.world.level.block.LeverBlock.POWERED)
                    && current.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)) {
                // A room captured with a lever already flipped reads as a puzzle that ignored a click.
                level.setBlock(POSITIONS.get(lever),
                        current.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, Boolean.FALSE),
                        WRITE_FLAGS);
            }
        }
        hasWater = withWater;
        setFlowing(level, false);
        LOGGER.info("Sim Water Board: armed on board {} (identifier {}, optimizedPath {}) - {} gate click(s)"
                        + " then {} timed, colours out: {}",
                slots, identifier, optimized, preFlow.size(), timed.size(), board);
        return true;
    }

    /** Whether this arena has the room's own sealed water column to show - see {@link #build}. */
    private static volatile boolean hasWater = false;

    /**
     * Which three colours to push out: a board the bundled file actually has a solution for.
     *
     * <p>{@code "012"} first because it is the one every identifier carries, then every other three-of-five
     * combination in the file's own ascending-index spelling, so a board that is missing for one identifier
     * still arms on another.
     */
    private static String pickBoard(boolean optimized, int identifier) {
        List<String> candidates = new ArrayList<>();
        candidates.add("012");
        for (int a = 0; a < 5; a++) {
            for (int b = a + 1; b < 5; b++) {
                for (int c = b + 1; c < 5; c++) {
                    String key = "" + a + b + c;
                    if (!candidates.contains(key)) {
                        candidates.add(key);
                    }
                }
            }
        }
        for (String key : candidates) {
            List<Map.Entry<LeverBlock, Double>> order =
                    WaterSolverFeature.bundledClickOrder(optimized, identifier, key);
            if (order != null && !order.isEmpty()) {
                return key;
            }
        }
        return null;
    }

    /** The board identifier this room's own marker blocks say, or -1. The solver's list, read through our
     *  anchor - see {@link WaterSolverFeature#IDENTIFIER_MARKERS}. */
    private static int identifierAt(ServerLevel level) {
        for (WaterSolverFeature.IdentifierMarker marker : WaterSolverFeature.IDENTIFIER_MARKERS) {
            BlockPos pos = at(marker.x(), marker.y(), marker.z());
            if (pos != null && level.getBlockState(pos).is(marker.block())) {
                return marker.identifier();
            }
        }
        return -1;
    }

    /** Split out only because LeverBlock.FACE lives on the abstract FaceAttachedHorizontalDirectionalBlock
     *  parent, not on LeverBlock itself - javap-confirmed, 26.1.2. */
    private static BlockState setFace(BlockState state) {
        return state.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR);
    }

    // ---------------------------------------------------------------------------------------- the mechanic

    private static void onLeverClicked(Minecraft client, LeverBlock lever) {
        if (complete) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (lever == LeverBlock.WATER) {
            onWaterLever(server);
            return;
        }
        // The blocks move whatever the click turns out to be worth. That is the lever's job, and seeing them
        // move is how he knows which lever he just pulled.
        toggleGate(server, lever);

        if (!preFlowDone.containsAll(preFlow)) {
            // Still setting the gates. Order does not matter among these - they are all "time 0", i.e. before
            // the water is running at all - so any lever the board still wants is right.
            if (!preFlow.contains(lever)) {
                mistake(server, "the " + label(lever) + " lever - this board does not use it");
                return;
            }
            if (!preFlowDone.add(lever)) {
                mistake(server, "the " + label(lever) + " lever twice before opening the water");
                return;
            }
            int left = preFlow.size() - preFlowDone.size();
            ModChat.send("Sim", ModChat.text("Gate set. "), ModChat.dim(left == 0
                    ? "Now pull the back lever to start the water."
                    : left + " more gate(s), then the back lever."));
            return;
        }
        if (openedTick == -1) {
            mistake(server, "the " + label(lever) + " lever before the water was running");
            return;
        }
        if (timedDone >= timed.size()) {
            mistake(server, "the " + label(lever) + " lever - the board is already done with it");
            return;
        }
        Map.Entry<LeverBlock, Double> due = timed.get(timedDone);
        if (due.getKey() != lever) {
            mistake(server, "the " + label(lever) + " lever - the water is waiting on "
                    + label(due.getKey()));
            return;
        }
        double elapsed = (tickCounter - openedTick) / 20.0;
        double off = elapsed - due.getValue();
        if (off < -TOLERANCE_SECONDS) {
            mistake(server, "the " + label(lever) + " lever " + fmt(-off) + "s too early");
            return;
        }
        if (off > TOLERANCE_SECONDS) {
            mistake(server, "the " + label(lever) + " lever " + fmt(off) + "s too late");
            return;
        }
        timedDone++;
        syncWool(server);
    }

    /**
     * The back lever: starts or stops the water.
     *
     * <p>Its first flip is the solution's own start signal, and every bundled time is measured from it. A later
     * flip that the board asks for (several boards do ask for one) is scored like any other timed click and
     * stops the flow on the way through. A flip the board did not ask for is not a mistake - it is him deciding
     * to start over, which is what the lever is for - so the flow stops, the clock clears and the timed half of
     * the sequence goes back to the beginning with the gates left as they are.
     */
    private static void onWaterLever(MinecraftServer server) {
        if (!preFlowDone.containsAll(preFlow)) {
            mistake(server, "the back lever with " + (preFlow.size() - preFlowDone.size())
                    + " gate(s) still to set");
            return;
        }
        if (openedTick == -1) {
            openedTick = tickCounter;
            setFlowingOn(server, true);
            ModChat.send("Sim", ModChat.good("Water running"),
                    ModChat.dim(" - the clock starts now."));
            syncWool(server);
            return;
        }
        if (timedDone < timed.size() && timed.get(timedDone).getKey() == LeverBlock.WATER) {
            double elapsed = (tickCounter - openedTick) / 20.0;
            double off = elapsed - timed.get(timedDone).getValue();
            if (off < -TOLERANCE_SECONDS) {
                mistake(server, "the back lever " + fmt(-off) + "s too early");
                return;
            }
            if (off > TOLERANCE_SECONDS) {
                mistake(server, "the back lever " + fmt(off) + "s too late");
                return;
            }
            timedDone++;
            setFlowingOn(server, !flowing);
            syncWool(server);
            return;
        }
        // A restart, not a mistake.
        setFlowingOn(server, false);
        openedTick = -1;
        timedDone = 0;
        syncWool(server);
        ModChat.send("Sim", ModChat.text("Water stopped. "),
                ModChat.dim("Pull the back lever again to restart the timing."));
    }

    /**
     * Moves every slot this lever owns, in or out - "those blocks at the very back in and out on that wall".
     *
     * <p>A real piston move, both cells of it: out puts the head on the wall face and the ore behind it, in
     * puts the ore back on the face and clears the cell behind. The piston block itself is swapped between
     * the two states copied off the room at arm time, so it never shows a head with a retracted body - and
     * nothing here guesses a property name, which is the mistake that cost a build.
     *
     * <p>Each slot flips individually from where IT is, rather than all of a lever's slots being driven from
     * one flag: a board that starts half out stays meaningful that way, and a lever is a toggle, not a
     * setter.
     */
    private static void toggleGate(MinecraftServer server, LeverBlock lever) {
        List<Slot> gate = GATES.get(lever);
        if (gate == null || gate.isEmpty()) {
            return;
        }
        if (!GATES_OPEN.add(lever)) {
            GATES_OPEN.remove(lever);
        }
        List<Slot> slots = List.copyOf(gate);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (Slot slot : slots) {
                boolean wasOut = Boolean.TRUE.equals(SLOT_OUT.get(slot.face()));
                setSlot(level, slot, !wasOut);
            }
        });
    }

    /** One slot pushed out or pulled in. Server thread. */
    private static void setSlot(ServerLevel level, Slot slot, boolean out) {
        BlockState head = headState;
        BlockState pistonState = out ? pistonOut : pistonIn;
        if (head == null || pistonState == null) {
            return;   // the room showed neither an extended nor a retracted slot to copy from
        }
        level.setBlock(slot.face(), out ? head : slot.ore(), WRITE_FLAGS);
        level.setBlock(slot.behind(), out ? slot.ore() : Blocks.AIR.defaultBlockState(), WRITE_FLAGS);
        level.setBlock(slot.piston(), pistonState, WRITE_FLAGS);
        SLOT_OUT.put(slot.face(), out);
    }

    /** Starts or stops the room's own water column. */
    private static void setFlowingOn(MinecraftServer server, boolean on) {
        server.execute(() -> setFlowing(server.overworld(), on));
    }

    private static void setFlowing(ServerLevel level, boolean on) {
        flowing = on;
        if (!hasWater) {
            return;
        }
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos source = at(WATER_SOURCE[0], WATER_SOURCE[1], WATER_SOURCE[2]);
        if (source != null) {
            level.setBlock(source, on ? water : air, WRITE_FLAGS);
        }
        for (int[] rel : WATER_COLUMN) {
            BlockPos pos = at(rel[0], rel[1], rel[2]);
            if (pos != null) {
                level.setBlock(pos, on ? water : air, WRITE_FLAGS);
            }
        }
    }

    /**
     * Moves the colour wool to match how far through the sequence he is.
     *
     * <p>{@code timedDone} of {@code timed.size()} clicks done means that fraction of the board's colours have
     * had water in their column, nearest the entrance first. Recomputed from scratch every time rather than
     * stepped, so a restart or a pushed-back colour needs no separate undo.
     */
    private static void syncWool(MinecraftServer server) {
        List<WoolColor> colours = board;
        int total = timed.size();
        int shouldBeOpen = total == 0 ? colours.size()
                : (int) Math.floor((double) colours.size() * timedDone / total);
        Set<WoolColor> wanted = EnumSet.noneOf(WoolColor.class);
        for (int i = 0; i < shouldBeOpen && i < colours.size(); i++) {
            wanted.add(colours.get(i));
        }
        openColours.clear();
        openColours.addAll(wanted);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (WoolColor colour : colours) {
                setWool(level, colour, !wanted.contains(colour));
            }
        });
        checkComplete(server);
    }

    private static void checkComplete(MinecraftServer server) {
        if (complete || !preFlowDone.containsAll(preFlow) || timedDone < timed.size()) {
            return;
        }
        complete = true;
        setFlowingOn(server, false);
        ModChat.send("Sim", ModChat.good("Water Board"), ModChat.text(" solved."));
    }

    /**
     * Pushes one colour's wool out, or pulls it in.
     *
     * <p>Out is {@code (15, 56, z)} - the block {@code WaterSolverFeature.scan} tests - and in is one lower.
     * Only one of the two ever holds the wool, so the room reads the same way to the solver as a real one.
     */
    private static void setWool(ServerLevel level, WoolColor colour, boolean out) {
        BlockPos inPos = at(15, WOOL_IN_Y, colour.relZ());
        BlockPos outPos = at(15, WOOL_OUT_Y, colour.relZ());
        if (inPos == null || outPos == null) {
            return;
        }
        BlockState wool = woolFor(colour).defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        level.setBlock(outPos, out ? wool : air, WRITE_FLAGS);
        level.setBlock(inPos, out ? air : wool, WRITE_FLAGS);
    }

    /** The capture's own block for each colour - note the "green" slot is LIME wool in the real room. */
    private static Block woolFor(WoolColor colour) {
        return switch (colour) {
            case PURPLE -> Blocks.PURPLE_WOOL;
            case ORANGE -> Blocks.ORANGE_WOOL;
            case BLUE -> Blocks.BLUE_WOOL;
            case GREEN -> Blocks.LIME_WOOL;
            case RED -> Blocks.RED_WOOL;
        };
    }

    /** Lever names as he reads them on the board, not as the enum spells them. */
    private static String label(LeverBlock lever) {
        return switch (lever) {
            case COAL -> "coal";
            case GOLD -> "gold";
            case QUARTZ -> "quartz";
            case DIAMOND -> "diamond";
            case EMERALD -> "emerald";
            case CLAY -> "clay";
            case WATER -> "back";
        };
    }

    private static String fmt(double seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    /**
     * A click that sent the water somewhere it should not have gone.
     *
     * <p>The nearest column whose wool is already in gets pushed back out, which is the mechanic's own
     * punishment and visible from where he is standing. Three of them fails the puzzle.
     */
    private static void mistake(MinecraftServer server, String what) {
        mistakes++;
        if (!openColours.isEmpty()) {
            // The last one opened is the one the stream was pointed at, so it is the one that closes.
            WoolColor lost = null;
            for (WoolColor colour : board) {
                if (openColours.contains(colour)) {
                    lost = colour;
                }
            }
            if (lost != null) {
                openColours.remove(lost);
                timedDone = Math.max(0, timedDone - 1);
                final WoolColor pushedBack = lost;
                server.execute(() -> setWool(server.overworld(), pushedBack, true));
            }
        }
        if (mistakes >= MAX_MISTAKES) {
            fail(server, what);
            return;
        }
        ModChat.send("Sim", ModChat.bad("Wrong"), ModChat.text(" - " + what + ". "),
                ModChat.dim((MAX_MISTAKES - mistakes) + " mistake(s) left."));
    }

    private static void fail(MinecraftServer server, String what) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing auto-get setting works in
        // here the same as it does on Hypixel, and turns the room red on the map - see SimRoomState.
        SimPuzzles.reportFail("Water Board", boundRoom == null ? "Water Board" : boundRoom);
        ModChat.send("Sim", ModChat.bad("Water Board"),
                ModChat.text(" failed - " + what + ". Resetting."));
        restart(server);
    }

    /** Back to the starting state without rebuilding: levers down, gates closed, water off, wool out. */
    private static void restart(MinecraftServer server) {
        preFlowDone.clear();
        timedDone = 0;
        mistakes = 0;
        complete = false;
        openedTick = -1;
        tickCounter = 0;
        // A plain copy, not EnumSet.copyOf: that throws on an empty collection, and "no gate is open" is the
        // normal case the first time this runs.
        Set<LeverBlock> toClose = EnumSet.noneOf(LeverBlock.class);
        toClose.addAll(GATES_OPEN);
        GATES_OPEN.clear();
        List<WoolColor> colours = board;
        openColours.clear();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (LeverBlock lever : toClose) {
                for (Slot slot : GATES.getOrDefault(lever, List.of())) {
                    // Back to the way the room had it, which is what startedOut remembers - NOT all the way
                    // in. Half of the board starts out, and a reset that pushed everything in would hand him
                    // a different puzzle from the one he was given.
                    setSlot(level, slot, Boolean.TRUE.equals(STARTED_OUT.get(slot.face())));
                }
            }
            for (WoolColor colour : colours) {
                setWool(level, colour, true);
            }
            for (BlockPos pos : POSITIONS.values()) {
                BlockState current = level.getBlockState(pos);
                if (current.hasProperty(net.minecraft.world.level.block.LeverBlock.POWERED)
                        && current.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)) {
                    level.setBlock(pos,
                            current.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, Boolean.FALSE),
                            WRITE_FLAGS);
                }
            }
            setFlowing(level, false);
        });
    }

    public static boolean isComplete() {
        return complete;
    }

    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>{@link #reset} is the right thing while the arena is still standing: it puts blocks back, un-presses,
     * re-lights. It is the wrong thing when the floor those blocks belonged to no longer exists, which is
     * exactly the case {@code SimRoomPuzzles.armFloor} has to handle - the positions it holds are absolute and
     * the next floor is built over them, so a queued "set it back" lands inside the new floor and punches a
     * hole in it. Just as bad the other way: a stale click index left in place makes a click on some unrelated
     * block on the new floor count as a move in a puzzle that is not on it.
     */
    public static void forget() {
        anchor = null;
        standaloneOrigin = null;
        boundRoom = null;
        hasWater = false;
        flowing = false;
        POSITIONS.clear();
        BLOCK_INDEX.clear();
        GATES.clear();
        SLOT_OUT.clear();
        STARTED_OUT.clear();
        headState = null;
        pistonOut = null;
        pistonIn = null;
        GATES_OPEN.clear();
        board = List.of();
        openColours.clear();
        preFlow.clear();
        preFlowDone.clear();
        timed = List.of();
        timedDone = 0;
        mistakes = 0;
        complete = false;
        openedTick = -1;
        tickCounter = 0;
    }

    /** Puts the arena back to its starting state, for an Architect's First Draft or {@code /simpuzzle reset}. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (!isArmed() || !SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            restart(server);
        }
    }
}
