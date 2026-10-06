package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.compat.McBlocks;
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
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.PistonType;

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
 *   <li><b>An ore lever moves blocks.</b> Each of the six ore levers owns its slots on the piston board at
 *       the back wall, and flipping it pushes them into the water's plane or pulls them back. Every flip moves
 *       them, right or wrong, because that is what a lever does.</li>
 *   <li><b>The back lever starts or stops the water.</b> It moves the board's lapis slot, the one block the
 *       always-running top water rests on - see {@link #WATER_GATE}. Open, the water falls into the maze and
 *       vanilla fluid ticks carry it down; shut, it drains. The column right behind the lever
 *       ({@code (15, 59..62, 4)}) is never touched; it runs on Hypixel too.</li>
 *   <li><b>Water reaching a colour's column moves that wool.</b> The five colours each own a column at
 *       {@code x=15}; the wool sits at {@code (15, 55, z)} and is pushed up to {@code (15, 56, z)}. That upper
 *       block is exactly what {@code WaterSolverFeature.scan} reads to decide which three are "extended", so
 *       the sim moving it between those two positions is the same event his solver watches for. Since 2026-10-06
 *       the whole LAYER moves, all five of its pistons - see {@link LayerPiston}.</li>
 * </ol>
 *
 * <p>Solving it places the reward chest at the end of the walkway under the glass - see {@link #CHEST_SPOT}.
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
 *   <li><b>It cannot be failed.</b> killer560 (2026-10-04): "should not be failable". A click the board did
 *       not ask for still moves its blocks - that is the lever's job - and is named in chat, but nothing is
 *       counted, nothing is pushed back and the room never goes red. (It used to fail after three, and the
 *       back lever refused to start the water until every gate was set - the other half of that report.)</li>
 * </ul>
 *
 * <h2>Safety</h2>
 *
 * <p>Same as the rest of {@code roomsim}: gated on {@link SimState#canAct}, and every world write happens on
 * the integrated server's own thread - see {@code SimDoors}'s class doc. Piston, power and wall-face writes use
 * {@link #WRITE_FLAGS} (no neighbour updates); only the cell IN the water's plane is written with
 * {@link Block#UPDATE_ALL}, so vanilla water reacts to it - see {@link #setSlot}.
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



    /** The colour wool's two positions: retracted, and pushed up into the walkway where the solver reads it. */
    private static final int WOOL_IN_Y = 55;
    private static final int WOOL_OUT_Y = 56;

    /**
     * THE BOTTOM PATH: a colour that is out fills its whole layer, not just the middle block.
     *
     * <p>killer560 (2026-10-06): "make it so the bottom path isn't just the one bottom middle block up for the ones
     * it needs to fix it should be that entire layer is out". Decoded from {@code Water_Board.json} (all three
     * capture sets agree): under the glass floor a walkway runs from the stairs at relative z 9..11 to z 23, three
     * wide (x 14..16), and each colour owns one layer of it at its own z (red 15 .. purple 19). Every layer has FIVE
     * sticky pistons, not one: the up-facing one under the middle at {@code (15, 54, z)}, and two facing in from
     * each side at {@code (12, 56|57, z)} and {@code (18, 56|57, z)}, each with its colour's wool in front of it
     * ({@code (13|17, 56|57, z)}). The capture is a retracted frame, so the walkway is open; out, all five fire and
     * the layer is wool from x 14 to 16 - which is what blocks the path to the reward chest until the water has
     * reached that colour. The sim used to move only the middle wool, which is the solver's read cell
     * {@code (15, 56, z)} and still is.
     *
     * <p>The pistons are read off the room at arm time, facing and all, so no rotation is guessed. Each has a power
     * cell behind it, swapped to a redstone block while it is out - the board's own rule (see {@link #setSlot}): a
     * piston whose power agrees with its state stays put if an update ever reaches it.
     */
    private record LayerPiston(BlockPos piston, BlockPos head, BlockPos push, BlockPos power,
                               BlockState retracted, BlockState extended, BlockState headState, BlockState wool,
                               BlockState powerOff) {
    }

    /** Room-relative cells that may hold one of a colour layer's pistons; z is the colour's own. */
    private static final int[][] LAYER_PISTONS = {{15, 54}, {12, 56}, {12, 57}, {18, 56}, {18, 57}};

    /** Each colour's layer pistons, read at arm time. Empty for a standalone arena, which falls back to the middle. */
    private static final Map<WoolColor, List<LayerPiston>> LAYERS = new ConcurrentHashMap<>();

    /**
     * THE REWARD CHEST, placed when the board is solved.
     *
     * <p>killer560 (2026-10-06): "Waterboard needs a chest that spawns in once I complete the puzzle, it should be
     * in between those carpets down low but closer to the exit between them not touching the wall." Decoded: the far
     * end of the walkway under the glass has gray carpet at {@code (13|14|16|17, 56, 22)} and {@code (14|16, 56, 23)},
     * the walkway's end wall at z 24. So the middle of the carpets is x 15; z 23 touches the end wall, z 22 does not
     * and is the one nearer the way out (the stairs at the low-z end). The chest stands on the walkway floor at
     * {@code (15, 56, 22)}, facing back up the walkway. That is also the block straight under QUOI's own chest spot
     * {@code (15, 58, 22)}, which {@code AutoWater} warps onto when the board is done - so Secret Aura reaches it from
     * there, as on Hypixel.
     *
     * <p>Not a secret: the room database lists none for Water Board, so opening it is the puzzle's reward and does
     * not count toward the secret total ({@code SimMimic.markPuzzleReward}).
     */
    private static final int[] CHEST_SPOT = {15, 56, 22};
    private static final int[] CHEST_FRONT = {15, 56, 21};
    private static volatile BlockPos rewardChest = null;
    private static volatile boolean rewardOpened = false;

    /**
     * THE BACK LEVER IS THE LAPIS SLOT.
     *
     * <p>killer560 (2026-10-04): "flipping the lever by water does not actually release water up top of the maze
     * part." Decoding {@code Water_Board.json} (capture x,z = room-relative z+1, x+1) settles what it does. The
     * top water runs all the time - sources feeding a fall down the board's water plane (relative {@code z 26})
     * at {@code x 14..16} - and it stops on ONE block: a {@code lapis_block} at relative {@code (15, 82, 26)},
     * pushed there by an extended sticky piston at {@code (15, 82, 28)} powered by a redstone block behind it.
     * It is the one board slot no ore lever owns. Pull it back and the water falls through the one-wide shaft at
     * {@code x 15, y 78..81} into the maze. So that is what the lever moves now. The 2026-10-02 version removed
     * and restored the top water blocks with no fluid updates, so the top water blinked in and out and not one
     * drop ever went down the maze.
     */
    private static final List<Slot> WATER_GATE = new ArrayList<>();

    /** The room-relative z of the block that powers each slot's piston - a redstone block when it is out. */
    private static final int BOARD_POWER_Z = 29;
    /** The power cell's two states, copied off the room: behind an extended piston, and behind a retracted one. */
    private static volatile BlockState powerOn = null;
    private static volatile BlockState powerOff = null;

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
    private record Slot(BlockPos piston, BlockPos face, BlockPos behind, BlockPos power, BlockState ore) {
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
        WATER_GATE.clear();
        SLOT_OUT.clear();
        STARTED_OUT.clear();
        headState = null;
        pistonOut = null;
        pistonIn = null;
        powerOn = null;
        powerOff = null;
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
                BlockPos power = at(x, y, BOARD_POWER_Z);
                LeverBlock lever = leverForOre(ore);
                boolean waterGate = lever == null && ore.is(Blocks.LAPIS_BLOCK);
                if (lever == null && !waterGate) {
                    continue;   // a cell the capture never read
                }
                if (out && headState == null) {
                    headState = atFace;
                    pistonOut = level.getBlockState(piston);
                } else if (!out && pistonIn == null) {
                    pistonIn = level.getBlockState(piston);
                }
                if (out && powerOn == null) {
                    powerOn = level.getBlockState(power);
                } else if (!out && powerOff == null) {
                    powerOff = level.getBlockState(power);
                }
                Slot slot = new Slot(piston, face, behind, power, ore);
                SLOT_OUT.put(face, out);
                STARTED_OUT.put(face, out);
                if (waterGate) {
                    WATER_GATE.add(slot);
                    continue;
                }
                GATES.computeIfAbsent(lever, k -> new ArrayList<>()).add(slot);
                found++;
            }
        }
        LOGGER.info("Sim Water Board: back wall read - {} slot(s) over {} lever(s), {} starting out; {} water"
                        + " gate (lapis) slot(s), power {} / {}", found, GATES.size(),
                SLOT_OUT.values().stream().filter(Boolean::booleanValue).count(), WATER_GATE.size(),
                powerOn == null ? "none" : powerOn.getBlock(), powerOff == null ? "none" : powerOff.getBlock());
    }

    /**
     * Reads every colour layer's pistons into {@link #LAYERS} - see {@link LayerPiston}. Server thread, at arm time.
     * A cell that is not a sticky piston with this colour's wool in front of it is skipped and the count is logged,
     * so a re-captured room that moved them says so instead of half-filling a layer.
     */
    private static void readLayers(ServerLevel level) {
        LAYERS.clear();
        int found = 0;
        for (WoolColor colour : WoolColor.values()) {
            BlockState wool = woolFor(colour).defaultBlockState();
            List<LayerPiston> list = new ArrayList<>();
            for (int[] xy : LAYER_PISTONS) {
                BlockPos piston = at(xy[0], xy[1], colour.relZ());
                if (piston == null) {
                    continue;
                }
                BlockState state = level.getBlockState(piston);
                if (!state.is(Blocks.STICKY_PISTON)) {
                    continue;
                }
                Direction facing = state.getValue(DirectionalBlock.FACING);
                BlockPos head = piston.relative(facing);
                BlockPos push = head.relative(facing);
                BlockPos power = piston.relative(facing.getOpposite());
                boolean out = state.getValue(PistonBaseBlock.EXTENDED);
                if (!level.getBlockState(out ? push : head).is(wool.getBlock())) {
                    continue;   // not this colour's piston
                }
                BlockState powerOff = level.getBlockState(power);
                if (powerOff.is(Blocks.REDSTONE_BLOCK)) {
                    powerOff = Blocks.STONE.defaultBlockState();
                }
                BlockState headState = Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(DirectionalBlock.FACING, facing)
                        .setValue(PistonHeadBlock.TYPE, PistonType.STICKY);
                list.add(new LayerPiston(piston, head, push, power,
                        state.setValue(PistonBaseBlock.EXTENDED, Boolean.FALSE),
                        state.setValue(PistonBaseBlock.EXTENDED, Boolean.TRUE), headState, wool, powerOff));
            }
            if (!list.isEmpty()) {
                LAYERS.put(colour, List.copyOf(list));
                found += list.size();
            }
        }
        LOGGER.info("Sim Water Board: bottom path read - {} layer piston(s) over {} colour(s)", found, LAYERS.size());
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
        // The reward chest is opened on the SERVER, from the use-on packet - a real click, Secret Aura's useItemOn
        // and a raw packet all land here (SimBoulderPuzzle's reward chest, the same way). PASS: the chest opens.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            BlockPos chest = rewardChest;
            if (level.isClientSide() || chest == null || !SimState.isActive()
                    || !chest.equals(hitResult.getBlockPos())) {
                return InteractionResult.PASS;
            }
            if (!rewardOpened) {
                rewardOpened = true;
                LOGGER.info("Sim Water Board: reward chest {} opened", chest);
            }
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
        readLayers(level);
        removeRewardChest(level);

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
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (lever == LeverBlock.WATER) {
            onWaterLever(server);
            return;
        }
        // The blocks move whatever the click turns out to be worth - also after the board is solved. That is the
        // lever's job, and seeing them move is how he knows which lever he just pulled.
        toggleGate(server, lever);
        if (complete) {
            return;
        }

        if (!preFlowDone.containsAll(preFlow)) {
            // Still setting the gates. Order does not matter among these - they are all "time 0", i.e. before
            // the water is running at all - so any lever the board still wants is right.
            if (!preFlow.contains(lever)) {
                offScript("the " + label(lever) + " lever - this board does not use it");
                return;
            }
            if (!preFlowDone.add(lever)) {
                offScript("the " + label(lever) + " lever twice before opening the water");
                return;
            }
            int left = preFlow.size() - preFlowDone.size();
            if (left == 0 && flowing && openedTick == -1) {
                // The water was already let in before the last gate went - fine, it cannot be failed; the clock
                // starts from the moment the board is complete instead.
                openedTick = tickCounter;
                ModChat.send("Sim", ModChat.text("Gates set. "), ModChat.good("Water running"),
                        ModChat.dim(" - the clock starts now."));
                syncWool(server);
                return;
            }
            ModChat.send("Sim", ModChat.text("Gate set. "), ModChat.dim(left == 0
                    ? "Now pull the back lever to start the water."
                    : left + " more gate(s), then the back lever."));
            return;
        }
        if (openedTick == -1) {
            offScript("the " + label(lever) + " lever before the water was running");
            return;
        }
        if (timedDone >= timed.size()) {
            offScript("the " + label(lever) + " lever - the board is already done with it");
            return;
        }
        Map.Entry<LeverBlock, Double> due = timed.get(timedDone);
        if (due.getKey() != lever) {
            offScript("the " + label(lever) + " lever - the water is waiting on " + label(due.getKey()));
            return;
        }
        double elapsed = (tickCounter - openedTick) / 20.0;
        double off = elapsed - due.getValue();
        if (off < -TOLERANCE_SECONDS) {
            offScript("the " + label(lever) + " lever " + fmt(-off) + "s too early");
            return;
        }
        if (off > TOLERANCE_SECONDS) {
            offScript("the " + label(lever) + " lever " + fmt(off) + "s too late");
            return;
        }
        timedDone++;
        syncWool(server);
    }

    /**
     * The back lever: starts or stops the water, every time, whatever else is going on.
     *
     * <p>It used to refuse - "Wrong - the back lever with 3 gate(s) still to set" in his 2026-10-04 log - which
     * from the room is a lever that does nothing. It now always moves the lapis gate. Its first start once the
     * gates are set is the solution's own start signal, and every bundled time is measured from it. A later
     * flip the board asks for (several boards do) is scored like any other timed click. A flip the board did not
     * ask for is him deciding to start over: the flow stops, the clock clears and the timed half of the sequence
     * goes back to the beginning with the gates left as they are.
     */
    private static void onWaterLever(MinecraftServer server) {
        boolean turnOn = !flowing;
        // Flipped HERE, on the click's thread, not only when the server gets round to setFlowing: a second pull
        // before the server drained its queue read the old value and asked for "on" twice.
        flowing = turnOn;
        setFlowingOn(server, turnOn);
        if (complete) {
            return;
        }
        if (!preFlowDone.containsAll(preFlow)) {
            ModChat.send("Sim", ModChat.text(turnOn ? "Water running. " : "Water stopped. "),
                    ModChat.dim((preFlow.size() - preFlowDone.size())
                            + " gate(s) still to set before the clock starts."));
            return;
        }
        if (openedTick == -1) {
            if (turnOn) {
                openedTick = tickCounter;
                ModChat.send("Sim", ModChat.good("Water running"), ModChat.dim(" - the clock starts now."));
                syncWool(server);
            }
            return;
        }
        if (timedDone < timed.size() && timed.get(timedDone).getKey() == LeverBlock.WATER) {
            double elapsed = (tickCounter - openedTick) / 20.0;
            double off = elapsed - timed.get(timedDone).getValue();
            if (Math.abs(off) <= TOLERANCE_SECONDS) {
                timedDone++;
                syncWool(server);
                return;
            }
            offScript("the back lever " + fmt(Math.abs(off)) + "s too " + (off < 0 ? "early" : "late"));
            return;
        }
        if (!turnOn) {
            // A restart, not a mistake.
            openedTick = -1;
            timedDone = 0;
            syncWool(server);
            ModChat.send("Sim", ModChat.text("Water stopped. "),
                    ModChat.dim("Pull the back lever again to restart the timing."));
        }
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

    /**
     * One slot pushed out or pulled in. Server thread.
     *
     * <p><b>The power goes with it.</b> Every extended piston in the capture has a redstone block behind it and
     * every retracted one has polished andesite, so the block at {@link #BOARD_POWER_Z} is swapped to match.
     * That matters now that water moves: a piston head beside flowing water forwards each neighbour update to
     * its base, and a base whose power disagreed with its state would move itself and undo the lever. Checked
     * on the capture: no piston sits directly under another, so a redstone block cannot quasi-power a neighbour.
     *
     * <p><b>Only the cell in the water's plane is written with updates</b> ({@link Block#UPDATE_ALL}), and it is
     * written last, so the water beside it re-ticks and flows into a slot that opened or drains below one that
     * closed - which is the whole maze. Everything else keeps {@link #WRITE_FLAGS}, so a write cannot set a
     * piston off. A standalone arena has no board, so this is never reached there.
     */
    private static void setSlot(ServerLevel level, Slot slot, boolean out) {
        BlockState head = headState;
        BlockState pistonState = out ? pistonOut : pistonIn;
        if (head == null || pistonState == null) {
            return;   // the room showed neither an extended nor a retracted slot to copy from
        }
        BlockState power = out ? powerOn : powerOff;
        if (power != null) {
            level.setBlock(slot.power(), power, WRITE_FLAGS);
        }
        level.setBlock(slot.piston(), pistonState, WRITE_FLAGS);
        level.setBlock(slot.face(), out ? head : slot.ore(), WRITE_FLAGS);
        level.setBlock(slot.behind(), out ? slot.ore() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        SLOT_OUT.put(slot.face(), out);
    }

    /** Starts or stops the room's own water column. */
    private static void setFlowingOn(MinecraftServer server, boolean on) {
        server.execute(() -> setFlowing(server.overworld(), on));
    }

    /**
     * Opens or shuts the lapis gate - see {@link #WATER_GATE}. Open lets the top water fall into the maze, and
     * vanilla water does the rest: it runs down every open channel, is turned by every ore pushed into its plane,
     * and drains away from the gate down when its supply is cut.
     */
    private static void setFlowing(ServerLevel level, boolean on) {
        flowing = on;
        if (!hasWater) {
            return;
        }
        for (Slot slot : WATER_GATE) {
            setSlot(level, slot, !on);   // the lapis OUT is the water stopped
        }
        LOGGER.info("Sim Water Board: water {} ({} gate slot(s) moved)", on ? "released" : "stopped",
                WATER_GATE.size());
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
        ModChat.send("Sim", ModChat.good("Water Board"), ModChat.text(" solved."));
        // Queued after syncWool's own server task, so the last layer is already down when the chest appears.
        server.execute(() -> placeRewardChest(server.overworld()));
    }

    /**
     * Places the reward chest - see {@link #CHEST_SPOT}. Server thread. Only in a captured room (a standalone arena
     * has no walkway), only into air, and the outcome is logged either way: a re-captured room that moved the
     * walkway reports it instead of burying a chest in a wall (Boulder's and Ice Fill's rule).
     */
    private static void placeRewardChest(ServerLevel level) {
        SimRoomPuzzles.Anchor a = anchor;
        if (a == null || rewardChest != null || !complete) {
            return;
        }
        BlockPos spot = a.world(CHEST_SPOT);
        BlockPos front = a.world(CHEST_FRONT);
        Direction facing = null;
        for (Direction d : Direction.values()) {
            if (d.getStepY() == 0 && spot.relative(d).equals(front)) {
                facing = d;
            }
        }
        if (!level.getBlockState(spot).isAir() || facing == null) {
            LOGGER.warn("Sim Water Board: no reward chest - the spot {} (relative 15,56,22) holds {}. If Water Board"
                    + " has been re-captured that coordinate needs re-measuring.", spot,
                    level.getBlockState(spot).getBlock());
            return;
        }
        level.setBlockAndUpdate(spot, Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing));
        rewardChest = spot.immutable();
        rewardOpened = false;
        com.killer560.hub.roomsim.SimMimic.markPuzzleReward(rewardChest);
        LOGGER.info("Sim Water Board: reward chest at {} facing {} (relative 15,56,22, database rotation {})",
                spot, facing, a.rotation());
    }

    /** Takes the reward chest away again, for a reset. Server thread; only ever removes a chest it placed. */
    private static void removeRewardChest(ServerLevel level) {
        BlockPos chest = rewardChest;
        rewardChest = null;
        rewardOpened = false;
        if (chest == null) {
            return;
        }
        com.killer560.hub.roomsim.SimMimic.unmarkPuzzleReward(chest);
        if (level.getBlockState(chest).is(Blocks.CHEST)) {
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
        }
        LOGGER.info("Sim Water Board: reward chest at {} removed", chest);
    }

    /**
     * Pushes one colour's wool out, or pulls it in.
     *
     * <p>Out is {@code (15, 56, z)} - the block {@code WaterSolverFeature.scan} tests - and in is one lower.
     * Only one of the two ever holds the wool, so the room reads the same way to the solver as a real one.
     */
    private static void setWool(ServerLevel level, WoolColor colour, boolean out) {
        List<LayerPiston> layer = LAYERS.get(colour);
        if (layer != null && !layer.isEmpty()) {
            // The whole layer - see LayerPiston. The middle piston is one of them, so (15, 56, z) still holds the
            // wool exactly when the colour is out, which is all the solver reads.
            for (LayerPiston p : layer) {
                level.setBlock(p.power(), out ? Blocks.REDSTONE_BLOCK.defaultBlockState() : p.powerOff(), WRITE_FLAGS);
                level.setBlock(p.piston(), out ? p.extended() : p.retracted(), WRITE_FLAGS);
                level.setBlock(p.head(), out ? p.headState() : p.wool(), WRITE_FLAGS);
                level.setBlock(p.push(), out ? p.wool() : Blocks.AIR.defaultBlockState(), WRITE_FLAGS);
            }
            // The five pistons leave the top-middle cell (15, 57, z) open; a real blocker is solid there too
            // (killer560, 2026-10-06: "missing that top middle block in the blockers"). Fill it with the wool while
            // out, and only clear it again if it is still that wool, so nothing else in the room is ever removed.
            BlockPos topMiddle = at(15, WOOL_OUT_Y + 1, colour.relZ());
            if (topMiddle != null) {
                BlockState wool = layer.get(0).wool();
                if (out) {
                    if (level.getBlockState(topMiddle).isAir()) {
                        level.setBlock(topMiddle, wool, WRITE_FLAGS);
                    }
                } else if (level.getBlockState(topMiddle).is(wool.getBlock())) {
                    level.setBlock(topMiddle, Blocks.AIR.defaultBlockState(), WRITE_FLAGS);
                }
            }
            return;
        }
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
            case PURPLE -> McBlocks.PURPLE_WOOL;
            case ORANGE -> McBlocks.ORANGE_WOOL;
            case BLUE -> McBlocks.BLUE_WOOL;
            case GREEN -> McBlocks.LIME_WOOL;
            case RED -> McBlocks.RED_WOOL;
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
     * A click the board did not ask for. Named, and nothing else: the puzzle cannot be failed (killer560,
     * 2026-10-04), so no count, no wool pushed back and no red room.
     */
    private static void offScript(String what) {
        ModChat.send("Sim", ModChat.dim("Not in this board's solution: " + what + "."));
    }

    /** Back to the starting state without rebuilding: levers down, gates closed, water off, wool out. */
    private static void restart(MinecraftServer server) {
        preFlowDone.clear();
        timedDone = 0;
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
            removeRewardChest(level);
        });
    }

    public static boolean isComplete() {
        return complete;
    }

    /** Where the reward chest stands, or null before the board is solved. For the testkit's 93-solve-waterboard. */
    public static BlockPos rewardChestPos() {
        return rewardChest;
    }

    /** Whether the reward chest has been opened (right-clicked) since it appeared. */
    public static boolean isRewardChestOpened() {
        return rewardOpened;
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
        WATER_GATE.clear();
        powerOn = null;
        powerOff = null;
        POSITIONS.clear();
        BLOCK_INDEX.clear();
        GATES.clear();
        SLOT_OUT.clear();
        STARTED_OUT.clear();
        headState = null;
        pistonOut = null;
        pistonIn = null;
        GATES_OPEN.clear();
        LAYERS.clear();
        BlockPos chest = rewardChest;
        if (chest != null) {
            com.killer560.hub.roomsim.SimMimic.unmarkPuzzleReward(chest);
        }
        rewardChest = null;
        rewardOpened = false;
        board = List.of();
        openColours.clear();
        preFlow.clear();
        preFlowDone.clear();
        timed = List.of();
        timedDone = 0;
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
