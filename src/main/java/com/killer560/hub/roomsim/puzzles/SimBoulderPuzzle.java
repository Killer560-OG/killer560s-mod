package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Practice version of the real dungeon "Boulder" puzzle, for the room sim.
 *
 * <p>The floor pattern and the button positions are one real bundled layout, read verbatim from
 * {@code data/killer560smod/puzzles/boulder-solutions.json} - the same file
 * {@link com.killer560.hub.puzzlesolvers.BoulderSolverFeature} loads on Hypixel. That solver's own class doc
 * spells out the geometry this copies: "a real 6x7 grid of pressure-plate-sized tiles at y=66 ... either solid
 * or air", and each solution entry is a real {@code [renderX, renderZ, clickX, clickZ]} quadruple at y=65 -
 * {@code renderX/Z} is the boulder tile, {@code clickX/Z} is the stone button actually clicked. This file uses
 * one fixed real pattern, key {@code "010000010111101001010011100000101110000111"}, whose real solution is two
 * buttons: {@code [22,15 -> 23,15]} and {@code [19,21 -> 20,21]}. Like the sim's Water Board, there is no live
 * scan here (no real room to scan) - it always practises this one real layout, not every one of the 8 bundled.
 *
 * <p>What IS invented: the real solver comment says plainly "the player must click each listed position in any
 * order" and tracks nothing about what a WRONG click does - there is no Hypixel fact anywhere in this codebase
 * about a Boulder failure state. So the fail rule below is generated for practice value, not measured: each
 * button is real and one-shot (matches the real solver, which removes a clicked position from its own list and
 * never re-adds it) - pressing the SAME button again after that, once its boulder has already rolled away, has
 * nothing left to press correctly and is treated here as a fumble that resets the whole board. That reset
 * behaviour is this file's own invention, called out here rather than implied to be real.
 *
 * <p>Same safety story as the rest of {@code roomsim}: gated on {@link SimState#canAct}, and every world write
 * happens on the integrated server's own thread - see {@link com.killer560.hub.roomsim.SimDoors}'s class doc.
 */
public final class SimBoulderPuzzle {

    /** Real floor-scan order (BoulderSolverFeature#scanFloor): z outer 24..9 step -3, x inner 24..6 step -3. */
    private static final int[] Z_VALUES = {24, 21, 18, 15, 12, 9};
    private static final int[] X_VALUES = {24, 21, 18, 15, 12, 9, 6};
    /** The y the ARRANGEMENT is sampled at - BoulderSolverFeature#scanFloor reads relative y 66. */
    private static final int FLOOR_Y = 66;
    /** The y the buttons are on, and the y the bundled solution's render/click pairs are both given at
     *  ({@code renderPos.y = 65}, {@code clickPos.y = 65} in the solver). The capture agrees: all 31 of this
     *  room's stone buttons are at y 65. */
    private static final int BUTTON_Y = 65;
    /** A boulder is a three-block column of planks, y 64..66 - measured off the capture, which holds 90
     *  jungle and 72 birch planks on each of those three layers and nothing on 67. */
    private static final int BOULDER_BOTTOM_Y = 64;

    /** One real bundled pattern - see class doc. */
    private static final String PATTERN_KEY = "010000010111101001010011100000101110000111";
    /** [renderX, renderZ, clickX, clickZ] - real, from the same bundled entry as PATTERN_KEY. */
    private static final int[][] SOLUTION = {
            {22, 15, 23, 15},
            {19, 21, 20, 21},
    };

    private record Button(BlockPos render, BlockPos click) {
    }

    /**
     * Room-relative to world, for the two places a board can live: bound to a captured room (an
     * {@link com.killer560.hub.roomsim.SimRoomPuzzles.Anchor}) or a standalone arena (a plain origin).
     * Both write the same three-layer boulders, so neither may own its own copy of the write loop.
     */
    private interface Grid {
        BlockPos at(int x, int y, int z);
    }

    /**
     * The block this room's boulders are made of, copied from one of them.
     *
     * <p>The capture has both jungle and birch planks on the board, so there is no single right literal -
     * and a literal is the thing CLAUDE.md says not to reach for. Whatever is standing on the grid is what a
     * rewritten boulder is made of; only an empty board falls back, and then to jungle because that is the
     * one the capture has more of.
     */
    private static BlockState boulderBlock(ServerLevel level, Grid grid) {
        for (int z : Z_VALUES) {
            for (int x : X_VALUES) {
                BlockState state = level.getBlockState(grid.at(x, BUTTON_Y, z));
                if (!state.isAir()) {
                    return state;
                }
            }
        }
        return Blocks.JUNGLE_PLANKS.defaultBlockState();
    }

    /** Writes {@link #PATTERN_KEY} onto the grid as three-block boulders. Server thread. */
    private static void writePattern(ServerLevel level, Grid grid, BlockState boulder) {
        int charIndex = 0;
        for (int z : Z_VALUES) {
            for (int x : X_VALUES) {
                boolean solid = PATTERN_KEY.charAt(charIndex++) == '1';
                for (int y = BOULDER_BOTTOM_Y; y <= FLOOR_Y; y++) {
                    level.setBlockAndUpdate(grid.at(x, y, z), solid
                            ? boulder : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** How far a pushed boulder may roll before it stops on its own - the board is seven wide. */
    private static final int BOULDER_MAX_ROLL = 7;

    /** The column's extent around {@link #BUTTON_Y}, which is the y every world position in here is taken at.
     *  Expressed as a distance, not as a y: {@code Anchor.world} is the only thing that knows where relative
     *  64..66 actually landed, and the offsets survive that translation where the raw numbers do not. */
    private static final int COLUMN_BELOW = BUTTON_Y - BOULDER_BOTTOM_Y;
    private static final int COLUMN_ABOVE = FLOOR_Y - BUTTON_Y;

    /** Whether a whole boulder could stand at {@code at} (given at {@link #BUTTON_Y}'s world height). */
    private static boolean columnIsClear(ServerLevel level, BlockPos at) {
        for (int dy = -COLUMN_BELOW; dy <= COLUMN_ABOVE; dy++) {
            if (!level.getBlockState(at.above(dy)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static final List<Button> BUTTONS = new ArrayList<>();
    private static final Map<BlockPos, Button> BLOCK_INDEX = new ConcurrentHashMap<>();
    /** Click positions already pressed once - a second press on one of these is the fail condition. */
    private static final Set<BlockPos> PRESSED = ConcurrentHashMap.newKeySet();

    /** Non-null only once {@link #build} has placed the board for this sim session. */
    private static volatile BlockPos builtOrigin = null;
    private static boolean complete = false;

    private SimBoulderPuzzle() {
    }

    /** Hooks the button right-click. Wiring: call once from mod init, alongside the other roomsim puzzle
     *  registrations. */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event also fires server-side; without the check a real click would be
            // evaluated twice, same double-fire guard SimDoors uses for its own UseBlockCallback registration.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player || builtOrigin == null) {
                return InteractionResult.PASS;
            }
            Button button = BLOCK_INDEX.get(hitResult.getBlockPos());
            if (button == null || complete) {
                return InteractionResult.PASS;
            }
            if (!PRESSED.add(button.click())) {
                // add() returned false: already pressed once - the fail condition (see class doc).
                fail(client, "the same button twice");
            } else {
                pressButton(client, button);
            }
            // PASS, not SUCCESS: vanilla's own button press (sound, redstone pulse, animation) is what makes
            // this read as a real button - we only observe the click, never fake or consume it.
            return InteractionResult.PASS;
        });
    }

    /** Places the floor pattern and both buttons relative to {@code origin}. Server thread only - see
     *  SimMobs/SimDoors class docs for why world writes happen there. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<Button> buttons = new ArrayList<>();
        for (int[] sol : SOLUTION) {
            BlockPos render = origin.offset(sol[0], BUTTON_Y, sol[1]);
            BlockPos click = origin.offset(sol[2], BUTTON_Y, sol[3]);
            buttons.add(new Button(render, click));
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            // Three-layer boulders, same as a bound board - see writePattern. The boulder IS the '1' cell;
            // there is no separate block placed on top of it (that was the "floating stone" killer560 saw).
            Grid grid = (x, y, z) -> origin.offset(x, y, z);
            writePattern(level, grid, Blocks.JUNGLE_PLANKS.defaultBlockState());
            for (Button button : buttons) {
                // The button needs a solid block to attach to, same reasoning SimWaterPuzzle's levers use.
                level.setBlockAndUpdate(button.click().below(), Blocks.STONE.defaultBlockState());
                BlockState buttonState = Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                        .setValue(ButtonBlock.POWERED, Boolean.FALSE)
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR);
                level.setBlockAndUpdate(button.click(), buttonState);
            }
        });
        BUTTONS.clear();
        BUTTONS.addAll(buttons);
        BLOCK_INDEX.clear();
        for (Button button : buttons) {
            BLOCK_INDEX.put(button.click(), button);
        }
        PRESSED.clear();
        builtOrigin = origin;
        boundAnchor = null;   // a standalone board, not a bind to a captured room
        complete = false;
    }

    /**
     * Arms this puzzle on a REAL captured Boulder room, on the room's own grid and buttons.
     *
     * <p>{@link #X_VALUES}, {@link #Z_VALUES}, {@link #FLOOR_Y} and {@link #BUTTON_Y} are
     * {@code BoulderSolverFeature}'s own room-relative scan grid, and the capture holds the push buttons:
     * a clean row of seven at relative {@code z=10}, {@code x} 6/9/12/15/18/21/24, which is {@code X_VALUES}
     * exactly. That row is what pins the rotation - it only reads as a row of seven along x at database
     * rotation 270; at rotation 0 the same blocks read as a column of seven along z, which is the wrong axis
     * because {@code Z_VALUES} has six entries, not seven.
     *
     * <p><b>Why this one writes the floor.</b> The capture's own boulder arrangement is NOT one of the eight
     * bundled patterns - read off the pasted room at all four rotations and all five nearby heights, the
     * closest bundled key is 10 of 42 tiles away - so there is no known solution for it and nothing could be
     * armed. So the live arrangement is read first, the way the real solver reads it, and used when it is
     * known; when it is not, {@link #PATTERN_KEY}'s real arrangement is written into the room's own 42 grid
     * positions and its real solution used. That is the puzzle's state, not a second arena: the 42 positions
     * written are exactly the 42 the real solver samples.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, com.killer560.hub.roomsim.SimRoomPuzzles.Placement p) {
        // The seven push buttons are the room's fingerprint, and the only thing here that can tell one
        // rotation from another - the boulder floor itself is stone at most positions at every rotation.
        List<int[]> buttonRow = new ArrayList<>();
        for (int x : X_VALUES) {
            buttonRow.add(new int[]{x, BUTTON_Y, 10});
        }
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor =
                com.killer560.hub.roomsim.SimRoomPuzzles.bestAnchor(level, p, buttonRow,
                        com.killer560.hub.roomsim.SimRoomPuzzles.is(Blocks.STONE_BUTTON), new int[]{0}, 5);
        if (anchor == null) {
            return false;
        }
        // Read the arrangement the way BoulderSolverFeature.scanFloor does, so a room that HAS a bundled
        // arrangement is played as it stands rather than overwritten.
        StringBuilder live = new StringBuilder(42);
        for (int z : Z_VALUES) {
            for (int x : X_VALUES) {
                live.append(level.getBlockState(anchor.world(x, FLOOR_Y, z)).isAir() ? '0' : '1');
            }
        }
        boolean known = PATTERN_KEY.contentEquals(live);
        if (!known) {
            // WHOLE BOULDERS, not a layer of stone. killer560 (2026-10-01): "boulder still has a bunch of
            // random floating stone blocks on the top layer of the wood boulders."
            //
            // That was exactly what this did. A boulder is three blocks of planks tall (y 64..66) and the
            // arrangement is sampled at its TOP, 66 - so writing Blocks.STONE at 66 dropped a stone cap onto
            // every real boulder, and writing AIR at 66 beheaded the ones the pattern did not want. Writing
            // all three layers, in the room's own plank, is the same statement made properly.
            //
            // It has to be written at all: this room's captured arrangement is
            // 011110001011000101100000010000101000001100, which is not one of the eight in
            // boulder-solutions.json, so there is no solution for the board as it stands. Rewriting it to a
            // bundled one is what gives both this class and BoulderSolverFeature something to solve.
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                    "Sim boulder: this room's arrangement ({}) is not one of the bundled eight, so the "
                            + "bundled pattern was written into its 42 grid positions", live);
            Grid grid = anchor::world;
            writePattern(level, grid, boulderBlock(level, grid));
        }
        // THE BOULDER IS AT FLOOR LEVEL, AND IT IS THE ROOM'S OWN BLOCK.
        //
        // killer560 (2026-10-01): "boulder is very broken looking. There are random stone blocks floating
        // everywhere." Three writes made that mess, and all three are gone:
        //
        //   - the boulder was placed at BUTTON_Y (65) while the grid the pattern is written to is FLOOR_Y
        //     (66), so every "boulder" was a cobblestone block hanging one under the floor;
        //   - it was placed at all, when the pattern written above already puts a solid block at every '1'
        //     cell - the boulder IS that block, and a second one on top of it is scenery;
        //   - a missing button got a STONE PEDESTAL under it, in mid-air, because the click row sits above
        //     nothing. A solution step with no button is now reported instead of propped up.
        // BOTH at BUTTON_Y, which is what the solver itself uses: renderPos.y = 65 and clickPos.y = 65 in
        // BoulderSolverFeature. An earlier "fix" moved render to FLOOR_Y on the reasoning that the grid is
        // sampled there - true of the ARRANGEMENT, not of the solution's coordinates.
        List<Button> buttons = new ArrayList<>();
        for (int[] sol : SOLUTION) {
            buttons.add(new Button(anchor.world(sol[0], BUTTON_Y, sol[1]),
                    anchor.world(sol[2], BUTTON_Y, sol[3])));
        }
        for (Button button : buttons) {
            if (!level.getBlockState(button.click()).is(Blocks.STONE_BUTTON)) {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                        "Sim boulder: the solution wants a button at {} and the room has {} there - that step"
                                + " cannot be pressed", button.click(),
                        level.getBlockState(button.click()).getBlock());
            }
        }
        BUTTONS.clear();
        BUTTONS.addAll(buttons);
        BLOCK_INDEX.clear();
        for (Button button : buttons) {
            BLOCK_INDEX.put(button.click(), button);
        }
        PRESSED.clear();
        builtOrigin = anchor.world(X_VALUES[0], BUTTON_Y, Z_VALUES[0]);
        boundAnchor = anchor;
        complete = false;
        return true;
    }

    /** Non-null while this puzzle is bound to a real captured room rather than a standalone arena. */
    private static volatile com.killer560.hub.roomsim.SimRoomPuzzles.Anchor boundAnchor = null;

    /**
     * Pushes the pressed button's boulder one square, and checks for completion.
     *
     * <p>killer560 (2026-10-01): "pressing the buttons doesnt move the boulders anywhere." It deleted the
     * boulder instead, which is not what a push looks like - on the real board the boulder rolls away from
     * the button along the row the button is on, and watching it move is the feedback that tells you whether
     * the press was the right one.
     *
     * <p>The direction is READ OFF THE SOLUTION, not chosen: every entry pairs a boulder with the click one
     * square away from it, so the push runs from the button towards the boulder and onward. The boulder rolls
     * until something stops it, which is what a boulder does; if the very first square is blocked it stays
     * put and says so.
     *
     * <p>killer560 (2026-10-01, second report): "the buttons still dont push them." A boulder is a
     * THREE-BLOCK column (y 64..66, {@link #BOULDER_BOTTOM_Y}..{@link #FLOOR_Y}) and this moved one block of
     * it, from y 65 - so the press carved the middle out of a boulder and left its top and bottom standing,
     * which from the floor looks like nothing moved. Worse, the clearance test only looked at y 65 too, so a
     * neighbouring boulder's own gap-free column still read as "air" at the only height being checked and the
     * roll walked straight through it. Clearance is now all three layers, and all three move.
     */
    private static void pressButton(Minecraft client, Button button) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            BlockPos from = button.render();
            int dx = Integer.signum(from.getX() - button.click().getX());
            int dz = Integer.signum(from.getZ() - button.click().getZ());
            server.execute(() -> {
                ServerLevel level = server.overworld();
                BlockPos at = from;
                BlockPos next = at.offset(dx, 0, dz);
                int rolled = 0;
                while (rolled < BOULDER_MAX_ROLL && columnIsClear(level, next)) {
                    at = next;
                    next = at.offset(dx, 0, dz);
                    rolled++;
                }
                if (rolled == 0) {
                    return;   // hard against something - nothing moves
                }
                // Read the column before clearing it, so the boulder arrives as whatever it actually was
                // (this room mixes jungle and birch planks).
                BlockState[] column = new BlockState[COLUMN_BELOW + COLUMN_ABOVE + 1];
                for (int i = 0; i < column.length; i++) {
                    BlockState state = level.getBlockState(from.above(i - COLUMN_BELOW));
                    column[i] = state.isAir() ? Blocks.JUNGLE_PLANKS.defaultBlockState() : state;
                }
                for (int i = 0; i < column.length; i++) {
                    level.setBlockAndUpdate(from.above(i - COLUMN_BELOW), Blocks.AIR.defaultBlockState());
                }
                for (int i = 0; i < column.length; i++) {
                    level.setBlockAndUpdate(at.above(i - COLUMN_BELOW), column[i]);
                }
            });
        }
        if (PRESSED.size() >= BUTTONS.size()) {
            complete = true;
            ModChat.send("Sim", ModChat.good("Boulder"), ModChat.text(" solved."));
        }
    }

    private static void fail(Minecraft client, String what) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing
        // auto-get setting works in here the same as it does on Hypixel.
        // Room name as well as puzzle name: a failed puzzle turns its room red on the map (SimRoomState),
        // and this puzzle only ever lives in the room of the same name.
        SimPuzzles.reportFail("Boulder", "Boulder");
        ModChat.send("Sim", ModChat.bad("Boulder"), ModChat.text(" failed - pressed " + what + ". Resetting."));
        if (boundAnchor != null && SimState.canAct(client)) {
            // Bound to a real room: put the boulders back and unpress, rather than rebuilding an arena the
            // room does not need. build() here would paste the standalone board inside the captured one.
            rearmBound(client);
            return;
        }
        if (builtOrigin != null && SimState.canAct(client)) {
            build(client, builtOrigin);
        }
    }

    /** Boulders back, buttons unpressed, for a board bound to a real captured room. */
    private static void rearmBound(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        PRESSED.clear();
        complete = false;
        if (server == null) {
            return;
        }
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor = boundAnchor;
        if (anchor == null) {
            return;
        }
        // THE WHOLE GRID, not just the two boulders the solution names. A push can roll a boulder several
        // squares and onto a cell the pattern wanted empty, so putting back only the two it started on leaves
        // the rest of the board as the last attempt left it. Re-writing all 42 is the same work bindAt does
        // and it is the only thing that actually restores the arrangement.
        server.execute(() -> {
            ServerLevel level = server.overworld();
            Grid grid = anchor::world;
            writePattern(level, grid, boulderBlock(level, grid));
        });
    }

    public static boolean isComplete() {
        return complete;
    }

    /** Rebuilds the board fresh (boulders back, buttons unpressed) without needing a caller-supplied origin. */
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
        BUTTONS.clear();
        BLOCK_INDEX.clear();
        PRESSED.clear();
        builtOrigin = null;
        boundAnchor = null;
        complete = false;
    }

    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (boundAnchor != null && SimState.canAct(client)) {
            rearmBound(client);
        } else if (builtOrigin != null && SimState.canAct(client)) {
            build(client, builtOrigin);
        } else {
            PRESSED.clear();
            complete = false;
        }
    }
}
