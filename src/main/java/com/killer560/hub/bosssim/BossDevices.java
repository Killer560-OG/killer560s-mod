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
 * P3's four devices, at the coordinates the mod's own device features read them from:
 * <ul>
 * <li><b>Simon Says</b> (S1): sea lanterns on the obsidian wall x 111, stone buttons x 110, y 120-123, z 92-95, start button
 * (110, 121, 91). Four rounds since Hypixel's 2026-10-06 update; each round lights its steps one at a time, then shows the
 * 16 buttons; a wrong press clears them. Ported from the testkit's server device ({@code hx/boss/HxSimonSays}).</li>
 * <li><b>Lights</b> (S2): the six levers at z 142 (x 58-62, y 133-136); some start off; all six on is the device.</li>
 * <li><b>Arrows</b> (S3): a 5x5 wall of item frames at x -2, y 120-124, z 75-79, each arrow turned at random off one of
 * Hypixel's layouts (the mod's own Arrow Align table); every frame on its layout's rotation is the device.</li>
 * <li><b>Target</b> (S4): the 5x5 target wall at z 50, x 64-68, y 126-130; one block at a time is lit emerald, an arrow in
 * it turns it to blue terracotta (what the mod's I4 solver watches for); nine hits is the device.</li>
 * </ul>
 */
public final class BossDevices {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");
    private static final Random RNG = new Random();

    // ---- Simon Says ------------------------------------------------------------------------------------------------
    static final BlockPos SS_START = new BlockPos(110, 121, 91);
    private static final int SS_ROUNDS = 4;
    private static final int SS_REVEAL = 4, SS_GAP = 2;

    private enum Ss { OFF, IDLE, REVEAL, INPUT, ROUND_DONE, DONE, BROKEN }

    private static Ss ss = Ss.OFF;
    private static final List<BlockPos> sequence = new ArrayList<>();
    private static int ssRound, ssExpected, ssTimer, ssRevealIndex;
    private static boolean ssRevealLit, ssStartWasPowered;
    private static final boolean[] ssWasPowered = new boolean[16];

    // ---- Lights ----------------------------------------------------------------------------------------------------
    static final List<BlockPos> LIGHTS = List.of(
            new BlockPos(62, 136, 142), new BlockPos(58, 136, 142), new BlockPos(60, 135, 142),
            new BlockPos(60, 134, 142), new BlockPos(62, 133, 142), new BlockPos(58, 133, 142));
    private static boolean lightsDone;

    // ---- Arrows ----------------------------------------------------------------------------------------------------
    private static final int ARROW_X = -2, ARROW_Y = 120, ARROW_Z = 75;
    private static final ItemFrame[] frames = new ItemFrame[25];
    private static int[] solution;
    private static boolean arrowsDone;

    // ---- Target ----------------------------------------------------------------------------------------------------
    private static final int TARGET_Z = 50;
    private static final int TARGET_HITS = 9;
    private static BlockPos lit;
    private static int targetHits;
    private static boolean targetDone;

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

    /** The blocks a fight changes, put back by every arena build (a capture's own copy is overwritten too). */
    static void resetBlocks(List<BossArena.Fill> out) {
        out.add(new BossArena.Fill(111, 119, 89, 111, 124, 97, BossBlocks.of("minecraft:obsidian")));
        out.add(new BossArena.Fill(110, 120, 92, 110, 123, 95, BossBlocks.AIR));
        out.add(new BossArena.Fill(110, 121, 91, 110, 121, 91, ssButton()));
        for (BlockPos p : LIGHTS) {
            out.add(new BossArena.Fill(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), lever(false)));
        }
        out.add(new BossArena.Fill(64, 126, TARGET_Z, 68, 130, TARGET_Z, BossBlocks.of("minecraft:blue_terracotta")));
    }

    /** P3 begins (devices of sections before {@code fromSection} start done). */
    static void start(ServerLevel level, int fromSection) {
        stop();
        // Simon Says
        sequence.clear();
        ssRound = ssExpected = ssTimer = 0;
        ssStartWasPowered = false;
        java.util.Arrays.fill(ssWasPowered, false);
        ss = fromSection > 1 ? Ss.DONE : Ss.IDLE;
        // Lights: two to four start off, the rest on (all six on is done).
        lightsDone = fromSection > 2;
        List<BlockPos> order = new ArrayList<>(LIGHTS);
        Collections.shuffle(order, RNG);
        int off = lightsDone ? 0 : 2 + RNG.nextInt(3);
        for (int i = 0; i < order.size(); i++) {
            BossBlocks.set(level, order.get(i), lever(i >= off));
        }
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
        tickSimonSays(level);
        if (!lightsDone) {
            boolean all = true;
            for (BlockPos p : LIGHTS) {
                BlockState s = level.getBlockState(p);
                all &= s.hasProperty(BlockStateProperties.POWERED) && s.getValue(BlockStateProperties.POWERED);
            }
            if (all) {
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
            if (all) {
                arrowsDone = true;
                done(level, 3);
            }
        }
        tickTarget(level);
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

    private static void beginRound(int n) {
        ssRound = n;
        ssExpected = 0;
        while (sequence.size() < n) {
            List<BlockPos> free = new ArrayList<>();
            for (BlockPos p : grid(111)) {
                if (!sequence.contains(p)) {
                    free.add(p);
                }
            }
            sequence.add(free.get(RNG.nextInt(free.size())));
        }
        ssRevealIndex = 0;
        ssRevealLit = false;
        ssTimer = SS_GAP;
        ss = Ss.REVEAL;
    }

    private static void tickSimonSays(ServerLevel level) {
        if (ss == Ss.OFF) {
            return;
        }
        boolean startNow = powered(level.getBlockState(SS_START));
        if (startNow && !ssStartWasPowered && (ss == Ss.IDLE || ss == Ss.BROKEN)) {
            sequence.clear();
            setButtons(level, false);
            beginRound(1);
            LOGGER.info("[BossSim] Simon Says started");
        }
        ssStartWasPowered = startNow;
        switch (ss) {
            case REVEAL -> {
                if (--ssTimer > 0) {
                    return;
                }
                if (ssRevealIndex >= ssRound) {
                    setButtons(level, true);
                    ss = Ss.INPUT;
                    return;
                }
                BlockPos lantern = sequence.get(ssRevealIndex);
                if (!ssRevealLit) {
                    level.setBlockAndUpdate(lantern, BossBlocks.of("minecraft:sea_lantern"));
                    ssRevealLit = true;
                    ssTimer = SS_REVEAL;
                } else {
                    level.setBlockAndUpdate(lantern, BossBlocks.of("minecraft:obsidian"));
                    ssRevealLit = false;
                    ssRevealIndex++;
                    ssTimer = SS_GAP;
                }
            }
            case INPUT -> {
                List<BlockPos> buttons = grid(110);
                for (int i = 0; i < buttons.size(); i++) {
                    boolean now = powered(level.getBlockState(buttons.get(i)));
                    if (now && !ssWasPowered[i]) {
                        boolean correct = buttons.get(i).east().equals(sequence.get(ssExpected));
                        if (!correct) {
                            setButtons(level, false);
                            ss = Ss.BROKEN;
                            LOGGER.info("[BossSim] Simon Says broken in round {}", ssRound);
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
                    beginRound(ssRound + 1);
                    ssTimer = 10;
                }
            }
            default -> {
            }
        }
    }

    // ---- Target ----------------------------------------------------------------------------------------------------

    private static void tickTarget(ServerLevel level) {
        if (targetDone) {
            return;
        }
        if (lit == null) {
            BlockPos next;
            do {
                next = new BlockPos(64 + RNG.nextInt(5), 126 + RNG.nextInt(5), TARGET_Z);
            } while (next.equals(lit));
            lit = next;
            BossBlocks.set(level, lit, BossBlocks.of("minecraft:emerald_block"));
            return;
        }
        AABB box = new AABB(lit.getX() - 0.3, lit.getY() - 0.3, lit.getZ() - 0.6, lit.getX() + 1.3, lit.getY() + 1.3, lit.getZ() + 0.3);
        List<AbstractArrow> hits = level.getEntitiesOfClass(AbstractArrow.class, box, a -> !a.isRemoved());
        if (hits.isEmpty()) {
            return;
        }
        hits.forEach(AbstractArrow::discard);
        BossBlocks.set(level, lit, BossBlocks.of("minecraft:blue_terracotta"));
        targetHits++;
        LOGGER.info("[BossSim] Target hit {} at {}", targetHits, lit.toShortString());
        lit = null;
        if (targetHits >= TARGET_HITS) {
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
        for (BlockPos p : sequence) {
            out.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        return out;
    }

    public static boolean ssInput() {
        return ss == Ss.INPUT;
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
