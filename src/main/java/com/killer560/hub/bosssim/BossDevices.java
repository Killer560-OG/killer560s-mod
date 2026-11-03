package com.killer560.hub.bosssim;

import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * P3's four devices, at the coordinates the mod's own device features read them from, with Hypixel's rules as ADM
 * measured them (Devices.kt, after the 2026-10-05/06 boss update):
 * <ul>
 * <li><b>Simon Says</b> (S1): sea lanterns on the obsidian wall x 111, stone buttons x 110, y 120-123, z 92-95, start button
 * (110, 121, 91). Four rounds of four distinct cells; the first round shows 4 ticks after the start press, lamp j lights at
 * 8j for 8 ticks, the buttons come back 10 ticks after the last lamp, the next round shows 6 ticks after the round's last
 * correct press. The start button toggles: a press while it runs resets it. A wrong press stops it (back to round 0; the
 * buttons go 3 ticks later) and a stray show of a new pattern follows on its own, whose buttons count for nothing.</li>
 * <li><b>Lights</b> (S2): twenty levers at z 142 (x 58-62, y 133-136), a lamp behind each at z 143. A lamp is lit while its
 * own lever or one of its four neighbours is on; every lamp lit is the device. The six of Hypixel's known solution
 * start partly off.</li>
 * <li><b>Arrows</b> (S3): a 5x5 wall of item frames at x -2, y 120-124, z 75-79, each arrow turned at random off one of
 * Hypixel's layouts (the mod's own Arrow Align table); every frame on its layout's rotation is the device.</li>
 * <li><b>Target</b> (S4): nine targets at x 64/66/68, y 126/128/130, z 50, blue terracotta; while someone stands on the
 * plate one at a time is lit emerald (on a 10-tick grid, in a random order per attempt), an arrow in it turns it back
 * blue; nine hits is the device. Off the plate, the lit one goes blue at the next grid tick and the attempt starts over.</li>
 * </ul>
 */
public final class BossDevices {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");
    private static final Random RNG = new Random();

    // ---- Simon Says ------------------------------------------------------------------------------------------------
    static final BlockPos SS_START = new BlockPos(110, 121, 91);
    private static final int SS_ROUNDS = 4;

    private enum Ss { OFF, IDLE, SHOW, INPUT, ROUND_DONE, DONE, BROKEN }

    private static Ss ss = Ss.OFF;
    private static final List<BlockPos> sequence = new ArrayList<>();
    private static int ssRound, ssExpected, ssTimer, ssShowTick;
    /** A show after a wrong press: lamps of a new pattern and stale buttons, nothing counts. */
    private static final List<BlockPos> stray = new ArrayList<>();
    private static int strayTick = -1;
    private static boolean ssStartWasPowered;
    private static final boolean[] ssWasPowered = new boolean[16];

    // ---- Lights ----------------------------------------------------------------------------------------------------
    /** Hypixel's known solution: these six on light all twenty lamps. */
    static final List<BlockPos> LIGHTS = List.of(
            new BlockPos(62, 136, 142), new BlockPos(58, 136, 142), new BlockPos(60, 135, 142),
            new BlockPos(60, 134, 142), new BlockPos(62, 133, 142), new BlockPos(58, 133, 142));
    private static boolean lightsDone;
    private static int lightsCreditAt = -1;

    // ---- Arrows ----------------------------------------------------------------------------------------------------
    private static final int ARROW_X = -2, ARROW_Y = 120, ARROW_Z = 75;
    private static final ItemFrame[] frames = new ItemFrame[25];
    private static int[] solution;
    private static boolean arrowsDone;

    // ---- Target ----------------------------------------------------------------------------------------------------
    private static final int TARGET_Z = 50;
    private static final int TARGET_HITS = 9;
    /** The plate (ADM: 63, 127, 35 on Hypixel's floor; here on the built-in plan's device floor at 125). */
    static final BlockPos TARGET_PLATE = new BlockPos(63, 126, 35);
    private static final List<Integer> order = new ArrayList<>();
    private static BlockPos lit;
    private static int targetHits, targetGrid = -1, plateUntil = -1, targetShowAt = -1;
    private static boolean targetFirstShown, targetDone;
    private static int tick;

    private BossDevices() {
    }

    private static BlockState ssButton() {
        return BossBlocks.of("minecraft:stone_button[face=wall,facing=west]");
    }

    private static BlockState lever(boolean on) {
        return BossBlocks.of("minecraft:lever[face=wall,facing=north,powered=" + on + "]");
    }

    static List<BlockPos> grid(int x) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 120; y <= 123; y++) {
            for (int z = 92; z <= 95; z++) {
                out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    /** The twenty Lights levers (x 58-62, y 133-136, z 142). */
    static List<BlockPos> lightLevers() {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 133; y <= 136; y++) {
            for (int x = 58; x <= 62; x++) {
                out.add(new BlockPos(x, y, 142));
            }
        }
        return out;
    }

    static BlockPos targetCell(int i) {
        return new BlockPos(64 + 2 * (i % 3), 126 + 2 * (i / 3), TARGET_Z);
    }

    /** The blocks a fight changes, put back by every arena build (a capture's own copy is overwritten too). */
    static void resetBlocks(List<BossArena.Fill> out) {
        out.add(new BossArena.Fill(111, 119, 89, 111, 124, 97, BossBlocks.of("minecraft:obsidian")));
        out.add(new BossArena.Fill(110, 120, 92, 110, 123, 95, BossBlocks.AIR));
        out.add(new BossArena.Fill(110, 121, 91, 110, 121, 91, ssButton()));
        out.add(new BossArena.Fill(58, 133, 143, 62, 136, 143, BossBlocks.of("minecraft:gray_concrete")));
        for (BlockPos p : lightLevers()) {
            out.add(new BossArena.Fill(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), lever(false)));
        }
        out.add(new BossArena.Fill(64, 126, TARGET_Z, 68, 130, TARGET_Z, BossBlocks.of("minecraft:stone_bricks")));
        for (int i = 0; i < 9; i++) {
            BlockPos c = targetCell(i);
            out.add(new BossArena.Fill(c.getX(), c.getY(), c.getZ(), c.getX(), c.getY(), c.getZ(),
                    BossBlocks.of("minecraft:blue_terracotta")));
        }
        out.add(new BossArena.Fill(TARGET_PLATE.getX(), TARGET_PLATE.getY(), TARGET_PLATE.getZ(), TARGET_PLATE.getX(),
                TARGET_PLATE.getY(), TARGET_PLATE.getZ(), BossBlocks.of("minecraft:stone_pressure_plate")));
    }

    /** P3 begins (devices of sections before {@code fromSection} start done). */
    static void start(ServerLevel level, int fromSection) {
        stop();
        tick = 0;
        // Simon Says
        sequence.clear();
        stray.clear();
        strayTick = -1;
        ssRound = ssExpected = ssTimer = 0;
        ssStartWasPowered = false;
        java.util.Arrays.fill(ssWasPowered, false);
        ss = fromSection > 1 ? Ss.DONE : Ss.IDLE;
        // Lights: two to four of the solution's six start off, the rest on; every other lever off.
        lightsDone = fromSection > 2;
        lightsCreditAt = -1;
        for (BlockPos p : lightLevers()) {
            BossBlocks.set(level, p, lever(false));
        }
        List<BlockPos> sol = new ArrayList<>(LIGHTS);
        Collections.shuffle(sol, RNG);
        int off = lightsDone ? 0 : 2 + RNG.nextInt(3);
        for (int i = 0; i < sol.size(); i++) {
            BossBlocks.set(level, sol.get(i), lever(i >= off));
        }
        lamps(level);
        // Arrows
        arrowsDone = fromSection > 3;
        int[][] all = com.killer560.hub.arrowalign.ArrowAlignFeature.solutions();
        solution = all[RNG.nextInt(all.length)];
        for (int i = 0; i < 25; i++) {
            if (solution[i] < 0) {
                continue;
            }
            int dy = i % 5, dz = i / 5;
            ItemFrame f = new ItemFrame(level, new BlockPos(ARROW_X, ARROW_Y + dy, ARROW_Z + dz), Direction.EAST) {
                @Override
                public boolean shouldBeSaved() {
                    return false;
                }
            };
            f.setItem(new ItemStack(Items.ARROW), false);
            f.setRotation(arrowsDone ? solution[i] : RNG.nextInt(8));
            level.addFreshEntity(f);
            frames[i] = f;
        }
        // Target
        targetDone = fromSection > 4;
        targetHits = 0;
        lit = null;
        targetGrid = -1;
        plateUntil = -1;
        LOGGER.info("[BossSim] devices up (SS {}, Lights {}, Arrows {}, Target {})", ss, lightsDone ? "done" : off + " off",
                arrowsDone ? "done" : "layout", targetDone ? "done" : "armed");
    }

    static void stop() {
        ss = Ss.OFF;
        for (int i = 0; i < 25; i++) {
            if (frames[i] != null && !frames[i].isRemoved()) {
                frames[i].discard();
            }
            frames[i] = null;
        }
    }

    static boolean isArrowFrame(net.minecraft.world.entity.Entity e) {
        for (ItemFrame f : frames) {
            if (f != null && f == e) {
                return true;
            }
        }
        return false;
    }

    static void tick(ServerLevel level) {
        tick++;
        tickSimonSays(level);
        if (!lightsDone) {
            boolean all = lamps(level);
            if (all && lightsCreditAt < 0) {
                lightsCreditAt = tick + 2;
            } else if (!all) {
                lightsCreditAt = -1;
            }
            if (lightsCreditAt >= 0 && tick >= lightsCreditAt && BossFight.section() <= 2) {
                lightsDone = true;
                done(level, 2);
            }
        }
        if (!arrowsDone && solution != null) {
            boolean all = true;
            for (int i = 0; i < 25; i++) {
                if (solution[i] >= 0) {
                    all &= frames[i] != null && (frames[i].getRotation() & 7) == solution[i];
                }
            }
            if (all && BossFight.section() <= 3) {
                arrowsDone = true;
                done(level, 3);
            }
        }
        tickTarget(level);
    }

    /** Lights each lamp whose lever or a neighbour's is on; true when all twenty are lit. */
    private static boolean lamps(ServerLevel level) {
        boolean all = true;
        for (BlockPos p : lightLevers()) {
            boolean on = isOn(level, p) || isOn(level, p.east()) || isOn(level, p.west()) || isOn(level, p.above())
                    || isOn(level, p.below());
            // Not a redstone lamp: a lever on one would light it by vanilla's own redstone, which is not Hypixel's rule.
            BlockPos lamp = p.south();
            boolean shown = level.getBlockState(lamp).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN);
            if (shown != on) {
                level.setBlock(lamp, BossBlocks.of(on ? "minecraft:sea_lantern" : "minecraft:gray_concrete"), 2);
            }
            all &= on;
        }
        return all;
    }

    private static boolean isOn(ServerLevel level, BlockPos p) {
        if (p.getX() < 58 || p.getX() > 62 || p.getY() < 133 || p.getY() > 136) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        return s.hasProperty(BlockStateProperties.POWERED) && s.getValue(BlockStateProperties.POWERED)
                && s.is(net.minecraft.world.level.block.Blocks.LEVER);
    }

    private static void done(ServerLevel level, int section) {
        ServerPlayer by = level.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
        BossStations.complete(level, BossStations.device(section), by);
    }

    // ---- Simon Says ------------------------------------------------------------------------------------------------

    private static boolean powered(BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.STONE_BUTTON) && s.getValue(BlockStateProperties.POWERED);
    }

    private static void setButtons(ServerLevel level, boolean on) {
        for (BlockPos p : grid(110)) {
            level.setBlockAndUpdate(p, on ? ssButton() : BossBlocks.AIR);
        }
        java.util.Arrays.fill(ssWasPowered, false);
    }

    /**
     * Hypixel's buttons come back up fast (ADM: the start button 2 ticks after a press, a grid button 3), not after a
     * vanilla stone button's 20, so a quick second press (a reset, the next step) is a new press.
     */
    private static void popUp(ServerLevel level, BlockPos pos, int ticks) {
        BossFight.later(ticks, "ss button up", () -> {
            BlockState s = level.getBlockState(pos);
            if (s.is(net.minecraft.world.level.block.Blocks.STONE_BUTTON) && s.getValue(BlockStateProperties.POWERED)) {
                level.setBlock(pos, s.setValue(BlockStateProperties.POWERED, false), 3);
            }
        });
    }

    private static List<BlockPos> newPattern() {
        List<BlockPos> cells = grid(111);
        Collections.shuffle(cells, RNG);
        return new ArrayList<>(cells.subList(0, SS_ROUNDS));
    }

    /** Round {@code n} shows from {@code delay} ticks on: lamp j lights at 8j for 8 ticks, buttons back 10 after the last. */
    private static void beginRound(int n, int delay) {
        ssRound = n;
        ssExpected = 0;
        ssShowTick = tick + delay;
        ss = Ss.SHOW;
    }

    private static void lamp(ServerLevel level, BlockPos p, boolean on) {
        level.setBlockAndUpdate(p, BossBlocks.of(on ? "minecraft:sea_lantern" : "minecraft:obsidian"));
    }

    private static void tickSimonSays(ServerLevel level) {
        if (ss == Ss.OFF) {
            return;
        }
        boolean startNow = powered(level.getBlockState(SS_START));
        if (startNow && !ssStartWasPowered && ss != Ss.DONE && BossFight.section() == 1) {
            if (ss == Ss.IDLE || ss == Ss.BROKEN) {
                sequence.clear();
                sequence.addAll(newPattern());
                stray.clear();
                strayTick = -1;
                setButtons(level, false);
                beginRound(1, 4);
                LOGGER.info("[BossSim] Simon Says started");
            } else {
                // A press while it runs resets it.
                for (BlockPos p : grid(111)) {
                    lamp(level, p, false);
                }
                setButtons(level, false);
                ss = Ss.IDLE;
                LOGGER.info("[BossSim] Simon Says reset by the start button");
            }
        }
        if (startNow && !ssStartWasPowered) {
            popUp(level, SS_START, 2);
        }
        ssStartWasPowered = startNow;
        tickStray(level);
        switch (ss) {
            case SHOW -> {
                int k = tick - ssShowTick;
                if (k < 0) {
                    return;
                }
                for (int j = 0; j < ssRound; j++) {
                    if (k == 8 * j) {
                        lamp(level, sequence.get(j), true);
                    } else if (k == 8 * j + 8) {
                        lamp(level, sequence.get(j), false);
                    }
                }
                if (k == 8 * (ssRound - 1) + 10) {
                    setButtons(level, true);
                    ss = Ss.INPUT;
                }
            }
            case INPUT -> {
                List<BlockPos> buttons = grid(110);
                for (int i = 0; i < buttons.size(); i++) {
                    boolean now = powered(level.getBlockState(buttons.get(i)));
                    if (now && !ssWasPowered[i]) {
                        popUp(level, buttons.get(i), 3);
                        boolean correct = buttons.get(i).east().equals(sequence.get(ssExpected));
                        if (!correct) {
                            wrongPress(level);
                            return;
                        }
                        ssExpected++;
                        if (ssExpected >= ssRound) {
                            ss = Ss.ROUND_DONE;
                            ssTimer = 6;
                            if (ssRound >= SS_ROUNDS) {
                                done(level, 1);
                            }
                            return;
                        }
                    }
                    ssWasPowered[i] = now;
                }
            }
            case ROUND_DONE -> {
                if (--ssTimer > 0) {
                    return;
                }
                setButtons(level, false);
                if (ssRound >= SS_ROUNDS) {
                    ss = Ss.DONE;
                } else {
                    beginRound(ssRound + 1, 0);
                }
            }
            default -> {
            }
        }
    }

    /** Back to round 0, stopped; the buttons go 3 ticks later, then a stray show of a new pattern (26 or 34 ticks on). */
    private static void wrongPress(ServerLevel level) {
        ss = Ss.BROKEN;
        LOGGER.info("[BossSim] Simon Says broken in round {}", ssRound);
        ssRound = 0;
        BossFight.later(3, "ss buttons gone", () -> {
            if (ss == Ss.BROKEN) {
                setButtons(level, false);
            }
        });
        stray.clear();
        stray.addAll(newPattern().subList(0, 2));
        strayTick = tick + 3 + (RNG.nextBoolean() ? 26 : 34);
    }

    /** The stray show: two lamps 8 apart, stale buttons back 1-4 ticks in (their presses count for nothing). */
    private static void tickStray(ServerLevel level) {
        if (strayTick < 0 || ss != Ss.BROKEN) {
            return;
        }
        int k = tick - strayTick;
        if (k == 0 || k == 8) {
            lamp(level, stray.get(k / 8), true);
        }
        if (k == 8 || k == 16) {
            lamp(level, stray.get(k / 8 - 1), false);
        }
        if (k == 2) {
            setButtons(level, true);
        }
        if (k > 16) {
            strayTick = -1;
        }
    }

    // ---- Target ----------------------------------------------------------------------------------------------------

    private static boolean onPlate(ServerLevel level) {
        AABB plate = new AABB(TARGET_PLATE.getX(), TARGET_PLATE.getY(), TARGET_PLATE.getZ(), TARGET_PLATE.getX() + 1,
                TARGET_PLATE.getY() + 0.6, TARGET_PLATE.getZ() + 1);
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p.getBoundingBox().intersects(plate)) {
                return true;
            }
        }
        return false;
    }

    private static void tickTarget(ServerLevel level) {
        if (targetDone) {
            return;
        }
        boolean on = onPlate(level);
        if (on) {
            plateUntil = -2;
        } else if (plateUntil == -2) {
            // The plate stays down 1-10 ticks after he steps off.
            plateUntil = tick + 1 + RNG.nextInt(10);
        }
        boolean pressed = on || (plateUntil >= 0 && tick < plateUntil);
        if (pressed && targetGrid < 0) {
            // A new attempt: its own 10-tick grid and a fresh order of the nine.
            targetGrid = tick;
            order.clear();
            for (int i = 0; i < 9; i++) {
                order.add(i);
            }
            Collections.shuffle(order, RNG);
            targetHits = 0;
            targetFirstShown = false;
            targetShowAt = tick;
        }
        if (targetGrid >= 0 && (tick - targetGrid) % 10 == 0) {
            if (!pressed) {
                if (lit != null) {
                    BossBlocks.set(level, lit, BossBlocks.of("minecraft:blue_terracotta"));
                    lit = null;
                }
                targetGrid = -1;
                LOGGER.info("[BossSim] Target: off the plate, attempt reset after {} hit(s)", targetHits);
                targetHits = 0;
                return;
            }
            if (lit == null && targetHits < TARGET_HITS && tick >= targetShowAt) {
                lit = targetCell(order.get(targetHits));
                targetFirstShown = true;
                BossBlocks.set(level, lit, BossBlocks.of("minecraft:emerald_block"));
            }
        }
        if (targetGrid < 0 || !targetFirstShown || targetHits >= TARGET_HITS) {
            return;
        }
        // A hit on the current target counts even before it shows (but not before the attempt's first emerald).
        BlockPos cur = targetCell(order.get(targetHits));
        AABB box = new AABB(cur.getX() - 0.3, cur.getY() - 0.3, cur.getZ() - 0.6, cur.getX() + 1.3, cur.getY() + 1.3, cur.getZ() + 0.3);
        List<AbstractArrow> hits = level.getEntitiesOfClass(AbstractArrow.class, box, a -> !a.isRemoved());
        if (hits.isEmpty()) {
            return;
        }
        hits.forEach(AbstractArrow::discard);
        if (cur.equals(lit)) {
            BossBlocks.set(level, lit, BossBlocks.of("minecraft:blue_terracotta"));
            lit = null;
        }
        targetHits++;
        targetShowAt = tick + 1;
        LOGGER.info("[BossSim] Target hit {} at {}", targetHits, cur.toShortString());
        if (targetHits >= TARGET_HITS && BossFight.section() <= 4) {
            targetDone = true;
            done(level, 4);
        }
    }

    /** Debug and testkit: "ss=IDLE lights=false arrows=false target=0/9". */
    public static String describe() {
        return "ss=" + ss + " round=" + ssRound + " lights=" + lightsDone + " arrows=" + arrowsDone + " target=" + targetHits + "/"
                + TARGET_HITS;
    }

    /** Debug and testkit: the Simon Says steps of the round in play, as x,y,z of each lantern. */
    public static List<int[]> ssSequence() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(ssRound, sequence.size()); i++) {
            BlockPos p = sequence.get(i);
            out.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        return out;
    }

    public static boolean ssInput() {
        return ss == Ss.INPUT;
    }

    /** Debug and testkit: the Simon Says state's name (IDLE, SHOW, INPUT, ROUND_DONE, DONE, BROKEN). */
    public static String ssState() {
        return ss.name();
    }

    /** Debug and testkit: the lit target block, or null. */
    public static int[] litTarget() {
        return lit == null ? null : new int[]{lit.getX(), lit.getY(), lit.getZ()};
    }

    /** Debug and testkit: the arrow layout's rotation for frame {@code i} (-1: no frame). */
    public static int arrowSolution(int i) {
        return solution == null ? -1 : solution[i];
    }
}
