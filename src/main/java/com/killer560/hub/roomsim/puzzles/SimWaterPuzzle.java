package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimRoomPuzzles;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Practice version of the real dungeon "Water Board" puzzle, for the room sim.
 *
 * <p>The 7 lever positions ({@link Lever}'s x/y/z) are copied verbatim from
 * {@link com.killer560.hub.puzzlesolvers.WaterSolverFeature.LeverBlock} - the real, bundled-from-Odin relative
 * coordinates the live solver already highlights on Hypixel. The click TIMES in {@link #SEQUENCE} are one real
 * bundled combination read straight out of {@code data/killer560smod/puzzles/water-solutions.json}
 * ({@code ["false"]["0"]["012"]}) - a real wool-colour/pattern combo the live puzzle actually generates, not
 * invented. Because that combo happens not to need the COAL lever, this sim always builds all 7 so the wall
 * looks right, but only 6 of them are ever "due" - COAL sits there unused for this fixed practice combo, same
 * as it would be unused on the matching real room. The sim never scans wool or clay/rotation (there is no real
 * room here to scan), so it only ever practises this one combo - it is not a stand-in for every real combo.
 *
 * <p>What IS invented: the real solver ({@code WaterSolverFeature}) has no concept of a wrong click at all - it
 * only tracks which times have been consumed. So the fail rule here is built from the one piece of real logic
 * that DOES encode "too early": {@code AutoWater}'s own due-gate only allows a non-zero-time click once the
 * water lever has been opened ({@code remaining<=0 && (time==0 || opened!=-1)}). This file fails the same way:
 * clicking a lever whose next required time is non-zero before water has been opened, clicking a lever more
 * times than its list calls for, or opening water twice, all reset the run. There is no measured Hypixel fact
 * behind "you get flooded/reset for that" - it is the most direct read of the mod's own real gating rule, kept
 * consistent rather than invented from nothing.
 *
 * <p>Same safety story as the rest of {@code roomsim}: gated on {@link SimState#canAct}, and every world write
 * happens on the integrated server's own thread - see {@link com.killer560.hub.roomsim.SimDoors}'s class doc.
 */
public final class SimWaterPuzzle {

    /** Relative offsets copied from WaterSolverFeature.LeverBlock - see class doc. */
    private enum Lever {
        COAL(20, 61, 10),
        GOLD(20, 61, 15),
        QUARTZ(20, 61, 20),
        DIAMOND(10, 61, 20),
        EMERALD(10, 61, 15),
        CLAY(10, 61, 10),
        WATER(15, 60, 5);

        final int dx;
        final int dy;
        final int dz;

        Lever(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    /** One real bundled combo (water-solutions.json ["false"]["0"]["012"]) - see class doc. */
    private static final Map<Lever, double[]> SEQUENCE = new EnumMap<>(Lever.class);

    static {
        SEQUENCE.put(Lever.GOLD, new double[] {0.0});
        SEQUENCE.put(Lever.QUARTZ, new double[] {0.0, 10.0});
        SEQUENCE.put(Lever.DIAMOND, new double[] {0.0});
        SEQUENCE.put(Lever.CLAY, new double[] {0.0});
        SEQUENCE.put(Lever.WATER, new double[] {0.0});
        SEQUENCE.put(Lever.EMERALD, new double[] {7.9});
    }

    /** Real lever positions once built, and the reverse lookup a click resolves through - same pattern as
     *  SimDoors' DOORS/BLOCK_INDEX. */
    private static final Map<Lever, BlockPos> POSITIONS = new EnumMap<>(Lever.class);
    private static final Map<BlockPos, Lever> BLOCK_INDEX = new ConcurrentHashMap<>();

    /** How many of each lever's SEQUENCE entries have been accepted so far. */
    private static final Map<Lever, Integer> PROGRESS = new EnumMap<>(Lever.class);

    /** Non-null only once {@link #build} has placed the wall for this sim session. */
    private static volatile BlockPos builtOrigin = null;

    /** Ticks since {@link #build}, incremented while {@link SimState#canAct} - same bookkeeping style as
     *  WaterSolverFeature's own tickCounter/openedWaterTick. */
    private static long tickCounter = 0;
    private static long openedTick = -1;
    private static boolean complete = false;

    private SimWaterPuzzle() {
    }

    /** Hooks the lever right-click and a tick counter. Wiring: call once from mod init, alongside the other
     *  roomsim puzzle registrations. */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event also fires server-side; without the check a real click would be
            // evaluated twice, same double-fire guard SimDoors uses for its own UseBlockCallback registration.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player || builtOrigin == null) {
                return InteractionResult.PASS;
            }
            Lever lever = BLOCK_INDEX.get(hitResult.getBlockPos());
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
        if (builtOrigin == null || !SimState.canAct(client)) {
            return;
        }
        tickCounter++;
    }

    /** Places the lever wall relative to {@code origin}. Server thread only - see SimMobs/SimDoors class docs
     *  for why world writes happen there. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Map<Lever, BlockPos> positions = new EnumMap<>(Lever.class);
        for (Lever lever : Lever.values()) {
            positions.put(lever, origin.offset(lever.dx, lever.dy, lever.dz));
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (Map.Entry<Lever, BlockPos> entry : positions.entrySet()) {
                BlockPos pos = entry.getValue();
                // A lever needs a solid block to attach to - place it standing on a floor block below rather
                // than requiring a whole wall, same "make the minimum the mechanic needs" approach SimDoors
                // takes with its door blocks.
                level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                BlockState leverState = Blocks.LEVER.defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                        .setValue(LeverBlock.POWERED, Boolean.FALSE);
                leverState = setFace(leverState);
                level.setBlockAndUpdate(pos, leverState);
            }
        });
        POSITIONS.clear();
        POSITIONS.putAll(positions);
        BLOCK_INDEX.clear();
        for (Map.Entry<Lever, BlockPos> entry : positions.entrySet()) {
            BLOCK_INDEX.put(entry.getValue(), entry.getKey());
        }
        builtOrigin = origin;
        resetProgress();
    }

    /**
     * Arms this puzzle on a REAL captured Water Board room the sim has just pasted, instead of building a
     * lever wall from nothing.
     *
     * <p>The seven relative offsets above are {@code WaterSolverFeature.LeverBlock}'s own, and the capture
     * already holds a {@code lever} at every one of them - measured: 7 of 7 at database rotation 270 against
     * 4, 6 and 4 at the other three. So nothing is placed. The levers, the wool wall behind them and the
     * water channel in front are the room's own blocks; only the rules are attached.
     *
     * <p>{@link SimRoomPuzzles#bestAnchor} decides the rotation by scoring the levers rather than trusting
     * the recovered capture turn, and refuses to arm a room whose levers are not there.
     *
     * <p>Server thread only - this is called from {@code SimBuilder}'s post-build block, so it may touch
     * blocks directly the way {@code SimSecrets} does.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(net.minecraft.server.level.ServerLevel level, SimRoomPuzzles.Placement p) {
        java.util.List<int[]> rels = new java.util.ArrayList<>();
        for (Lever lever : Lever.values()) {
            rels.add(new int[]{lever.dx, lever.dy, lever.dz});
        }
        SimRoomPuzzles.Anchor anchor = SimRoomPuzzles.bestAnchor(level, p, rels,
                SimRoomPuzzles.is(Blocks.LEVER), new int[]{0}, 5);
        if (anchor == null) {
            return false;
        }
        Map<Lever, BlockPos> positions = new EnumMap<>(Lever.class);
        for (Lever lever : Lever.values()) {
            positions.put(lever, anchor.world(lever.dx, lever.dy, lever.dz));
        }
        POSITIONS.clear();
        POSITIONS.putAll(positions);
        BLOCK_INDEX.clear();
        for (Map.Entry<Lever, BlockPos> entry : positions.entrySet()) {
            BLOCK_INDEX.put(entry.getValue(), entry.getKey());
        }
        // The room may have been captured with a lever already flipped, and a flipped lever the puzzle has no
        // progress for reads as a puzzle that ignored a click. Put every one of them down.
        for (BlockPos pos : positions.values()) {
            BlockState current = level.getBlockState(pos);
            if (current.hasProperty(LeverBlock.POWERED) && current.getValue(LeverBlock.POWERED)) {
                level.setBlockAndUpdate(pos, current.setValue(LeverBlock.POWERED, Boolean.FALSE));
            }
        }
        builtOrigin = positions.get(Lever.WATER);
        resetProgress();
        return true;
    }

    /** Split out only because LeverBlock.FACE lives on the abstract FaceAttachedHorizontalDirectionalBlock
     *  parent, not on LeverBlock itself - javap-confirmed, 26.1.2. */
    private static BlockState setFace(BlockState state) {
        return state.setValue(net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE,
                AttachFace.FLOOR);
    }

    private static void onLeverClicked(Minecraft client, Lever lever) {
        if (complete) {
            return;
        }
        double[] times = SEQUENCE.get(lever);
        if (times == null) {
            // COAL for this combo - real, unused lever (see class doc). Flipping it does nothing either way
            // on the matching real room, so neither progress nor failure here.
            return;
        }
        int done = PROGRESS.getOrDefault(lever, 0);
        if (done >= times.length) {
            fail(client, "the " + lever + " lever again - it only needed " + times.length + " click(s)");
            return;
        }
        double requiredTime = times[done];
        if (lever == Lever.WATER) {
            if (openedTick != -1) {
                fail(client, "the water lever a second time");
                return;
            }
            openedTick = tickCounter;
        } else if (requiredTime != 0.0 && openedTick == -1) {
            // The one real due-gate AutoWater enforces: a timed click needs water opened first. See class doc.
            fail(client, "the " + lever + " lever before opening water");
            return;
        }
        PROGRESS.put(lever, done + 1);
        int totalNeeded = SEQUENCE.values().stream().mapToInt(a -> a.length).sum();
        int totalDone = PROGRESS.values().stream().mapToInt(Integer::intValue).sum();
        if (totalDone >= totalNeeded) {
            complete = true;
            ModChat.send("Sim", ModChat.good("Water Board"), ModChat.text(" solved."));
        }
    }

    private static void fail(Minecraft client, String what) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing
        // auto-get setting works in here the same as it does on Hypixel.
        SimPuzzles.reportFail("Water Board");
        ModChat.send("Sim", ModChat.bad("Water Board"), ModChat.text(" failed - clicked " + what + ". Resetting."));
        unpowerLevers(client);
        resetProgress();
    }

    private static void unpowerLevers(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || POSITIONS.isEmpty()) {
            return;
        }
        var positions = Map.copyOf(POSITIONS);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : positions.values()) {
                BlockState current = level.getBlockState(pos);
                if (current.hasProperty(LeverBlock.POWERED) && current.getValue(LeverBlock.POWERED)) {
                    level.setBlockAndUpdate(pos, current.setValue(LeverBlock.POWERED, Boolean.FALSE));
                }
            }
        });
    }

    private static void resetProgress() {
        PROGRESS.clear();
        openedTick = -1;
        tickCounter = 0;
        complete = false;
    }

    public static boolean isComplete() {
        return complete;
    }

    /** Resets progress (and un-powers any flipped levers) without needing a fresh {@link #build}. */
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
        POSITIONS.clear();
        BLOCK_INDEX.clear();
        builtOrigin = null;
        resetProgress();
    }

    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (builtOrigin != null && SimState.canAct(client)) {
            unpowerLevers(client);
        }
        resetProgress();
    }
}
