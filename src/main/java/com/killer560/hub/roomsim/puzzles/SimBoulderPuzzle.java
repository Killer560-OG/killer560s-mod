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
        complete = false;
    }

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
        ModChat.send("Sim", ModChat.bad("Boulder"), ModChat.text(" failed - pressed " + what + ". Resetting."));
        if (builtOrigin != null && SimState.canAct(client)) {
            build(client, builtOrigin);
        }
    }

    public static boolean isComplete() {
        return complete;
    }

    /** Rebuilds the board fresh (boulders back, buttons unpressed) without needing a caller-supplied origin. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (builtOrigin != null && SimState.canAct(client)) {
            build(client, builtOrigin);
        } else {
            PRESSED.clear();
            complete = false;
        }
    }
}
