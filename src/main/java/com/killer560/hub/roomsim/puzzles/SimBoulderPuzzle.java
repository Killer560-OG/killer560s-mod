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
    private static final int FLOOR_Y = 66;
    private static final int BUTTON_Y = 65;

    /** One real bundled pattern - see class doc. */
    private static final String PATTERN_KEY = "010000010111101001010011100000101110000111";
    /** [renderX, renderZ, clickX, clickZ] - real, from the same bundled entry as PATTERN_KEY. */
    private static final int[][] SOLUTION = {
            {22, 15, 23, 15},
            {19, 21, 20, 21},
    };

    private record Button(BlockPos render, BlockPos click) {
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
            int charIndex = 0;
            for (int z : Z_VALUES) {
                for (int x : X_VALUES) {
                    BlockPos tile = origin.offset(x, FLOOR_Y, z);
                    boolean solid = PATTERN_KEY.charAt(charIndex++) == '1';
                    level.setBlockAndUpdate(tile, solid
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
            for (Button button : buttons) {
                // The boulder itself - COBBLESTONE is a placeholder like SimDoors' TRIPWIRE_HOOK key stack,
                // not a claim about the real block. It sits back down on every (re)build, including a fail
                // reset, so "the boulder rolled away" always has something to roll away again.
                level.setBlockAndUpdate(button.render(), Blocks.COBBLESTONE.defaultBlockState());
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
            // Not this room's own arrangement: write the bundled one in. Said out loud, because a room whose
            // floor was rewritten is not the room he walked.
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                    "Sim boulder: this room's arrangement is not the bundled one ({}), so the bundled "
                            + "pattern was written into its 42 grid positions", live);
            int charIndex = 0;
            for (int z : Z_VALUES) {
                for (int x : X_VALUES) {
                    boolean solid = PATTERN_KEY.charAt(charIndex++) == '1';
                    level.setBlockAndUpdate(anchor.world(x, FLOOR_Y, z), solid
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
        List<Button> buttons = new ArrayList<>();
        for (int[] sol : SOLUTION) {
            buttons.add(new Button(anchor.world(sol[0], BUTTON_Y, sol[1]),
                    anchor.world(sol[2], BUTTON_Y, sol[3])));
        }
        for (Button button : buttons) {
            // The boulder to push. Cobblestone is the same placeholder build() uses - see its comment.
            level.setBlockAndUpdate(button.render(), Blocks.COBBLESTONE.defaultBlockState());
            // Most of the solution's click positions already hold a real stone button in the capture; the ones
            // that do not get one, because a solution step with nothing to press is a puzzle that cannot be
            // finished. Only ever added, never moved.
            if (!level.getBlockState(button.click()).is(Blocks.STONE_BUTTON)) {
                if (level.getBlockState(button.click().below()).isAir()) {
                    level.setBlockAndUpdate(button.click().below(), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(button.click(), Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                        .setValue(ButtonBlock.POWERED, Boolean.FALSE)
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR));
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

    /** Removes the pressed button's boulder ("it rolled away") and checks for completion. */
    private static void pressButton(Minecraft client, Button button) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> server.overworld().setBlockAndUpdate(button.render(),
                    Blocks.AIR.defaultBlockState()));
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
        List<Button> buttons = List.copyOf(BUTTONS);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (Button button : buttons) {
                level.setBlockAndUpdate(button.render(), Blocks.COBBLESTONE.defaultBlockState());
            }
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
