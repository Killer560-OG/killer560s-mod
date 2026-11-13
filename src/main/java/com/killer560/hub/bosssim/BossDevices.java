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
 * correct press. The start button toggles: a press while it runs resets it, but a round already queued still shows (the
 * skip). A wrong press stops it (back to round 0; the buttons go 3 ticks later) and a round 2 show of a new pattern follows.</li>
 * <li><b>Lights</b> (S2): twenty levers at z 142 (x 58-62, y 133-136), a redstone lamp behind each at z 143. A lamp is lit
 * while its own lever or one of its four neighbours is on; every lamp lit is the device. The device starts with the four corners
 * and the middle two on (killer560, 2026-11-09); the first pull of any lever completes it. A pull is answered here ({@link #pullLight}) with no redstone updates, so vanilla never
 * lights or dims a lamp by its own rule.</li>
 * <li><b>Arrows</b> (S3): a 5x5 wall of item frames at x -2, y 120-124, z 75-79, each arrow turned at random off one of
 * Hypixel's layouts (the mod's own Arrow Align table); every frame on its layout's rotation is the device. The frames
 * hang from the fight's start, as Hypixel's do (a capture holds blocks, not entities).</li>
 * <li><b>Target</b> (S4): nine targets at x 64/66/68, y 126/128/130, z 50, blue terracotta; while someone stands on the
 * plate one at a time is lit emerald (on a 10-tick grid, in a random order per attempt), an arrow in it turns it back
 * blue; nine hits is the device. Off the plate, the lit one goes blue at the next grid tick and the attempt starts over.</li>
 * </ul>
 */
public final class BossDevices {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");
    private static final Random RNG = new Random();

    static final BlockPos SS_START = new BlockPos(110, 121, 91);
    private static final int SS_ROUNDS = 4;

    /** Simon Says: on (P3 running), the start toggle, done, presses counting, stopped by a wrong press, a round put in. */
    private static boolean ssOn, ssStarted, ssDone, ssAccepting, ssBroken, ssRoundDone, ssStartWasPowered;
    /** The pattern, as grid cells 0-15 (row from y 120, column from z 92); a start press redraws it. */
    private static final int[] ssSeq = new int[SS_ROUNDS];
    /** Rounds shown since the last reset (the round being put in), and how many of it are pressed. */
    private static int ssRound, ssNext, ssGen;
    private static final boolean[] ssLit = new boolean[16], ssUp = new boolean[16], ssWasPowered = new boolean[16];
    /** Hypixel's round tasks and show steps: a reset never cancels them, only a new device or its completion. */
    private record SsJob(int at, int gen, Runnable run) {
    }
    private static final List<SsJob> ssJobs = new ArrayList<>();
    private static final int[] SS_STRAY_GAPS = {4, 5, 5, 6, 6, 6, 6, 7, 7, 8};
    /** The per-tick button read's position (server thread only). */
    private static final BlockPos.MutableBlockPos SS_AT = new BlockPos.MutableBlockPos();

    /** Hypixel's known solution: these six on light all twenty lamps. */
    static final List<BlockPos> LIGHTS = List.of(
            new BlockPos(62, 136, 142), new BlockPos(58, 136, 142), new BlockPos(60, 135, 142),
            new BlockPos(60, 134, 142), new BlockPos(62, 133, 142), new BlockPos(58, 133, 142));
    private static boolean lightsDone;
    private static int lightsCreditAt = -1;
    private static boolean lightsPulled;

    private static final int ARROW_X = -2, ARROW_Y = 120, ARROW_Z = 75;
    private static final ItemFrame[] frames = new ItemFrame[25];
    private static int[] solution;
    private static boolean arrowsDone;
    /**
     * The wall behind the frames (x -3): the arena capture froze its sea lanterns wherever they were lit when it was
     * taken, so the sim owns those 25 blocks and lights one only behind an arrow on its solution (killer560, 2026-11-11:
     * "lights should only go behind completed arrows not randomly"). {@code wallBase} is the wall's own block, read once.
     */
    private static BlockState wallBase;
    private static final BlockState WALL_LIT = BossBlocks.of("minecraft:sea_lantern");
    private static final BlockPos.MutableBlockPos WALL_AT = new BlockPos.MutableBlockPos();

    private static final int TARGET_Z = 50;
    private static final int TARGET_HITS = 9;
    /** The plate: a light weighted pressure plate at (63, 127, 35) on blue terracotta, as the capture of Hypixel's room has it. */
    static final BlockPos TARGET_PLATE = new BlockPos(63, 127, 35);
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
        out.add(new BossArena.Fill(58, 133, 143, 62, 136, 143, BossBlocks.of("minecraft:redstone_lamp[lit=false]")));
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
                TARGET_PLATE.getY(), TARGET_PLATE.getZ(), BossBlocks.of("minecraft:light_weighted_pressure_plate")));
    }

    /** P3 begins (devices of sections before {@code fromSection} start done). */
    static void start(ServerLevel level, int fromSection) {
        tick = 0;
        // Simon Says
        ssGen++;
        ssJobs.clear();
        ssOn = true;
        ssDone = fromSection > 1;
        ssStarted = ssAccepting = ssBroken = ssRoundDone = ssStartWasPowered = false;
        ssRound = ssNext = 0;
        java.util.Arrays.fill(ssLit, false);
        java.util.Arrays.fill(ssUp, false);
        java.util.Arrays.fill(ssWasPowered, false);
        // Lights (killer560, 2026-11-09): the four corners and the middle two start on, every other lever off; the first
        // pull of any lever completes the device.
        lightsDone = fromSection > 2;
        lightsCreditAt = -1;
        lightsPulled = false;
        for (BlockPos p : lightLevers()) {
            BossBlocks.set(level, p, lever(false));
        }
        for (BlockPos p : LIGHTS) {
            BossBlocks.set(level, p, lever(true));
        }
        lamps(level);
        // The frames hung at the fight's start keep their layout into P3 (and any turns made since).
        boolean up = false;
        for (ItemFrame fr : frames) {
            up |= fr != null && !fr.isRemoved();
        }
        if (up && fromSection <= 3) {
            arrowsDone = false;
        } else {
            arrows(level, fromSection > 3);
        }
        // Target
        targetDone = fromSection > 4;
        targetHits = 0;
        lit = null;
        targetGrid = -1;
        plateUntil = -1;
        LOGGER.info("[BossSim] devices up (SS {}, Lights {}, Arrows {}, Target {})", ssState(), lightsDone ? "done" : "corners+middle on",
                arrowsDone ? "done" : "layout", targetDone ? "done" : "armed");
    }

    /** Hangs the Arrows frames (a new layout; on its solution when {@code done}), replacing any already up. */
    static void arrows(ServerLevel level, boolean done) {
        takeDownFrames();
        arrowsDone = done;
        int[][] all = com.killer560.hub.arrowalign.ArrowAlignFeature.solutions();
        solution = all[RNG.nextInt(all.length)];
        for (int i = 0; i < 25; i++) {
            if (solution[i] >= 0) {
                hang(level, i, Items.ARROW, arrowsDone ? solution[i] : RNG.nextInt(8));
            }
        }
        // Hypixel marks the path with wool in two more frames: lime where it starts, red where it ends (killer560,
        // 2026-11-05: "wherever it starts is the green where it ends should be red"). The ends are the empty cells an
        // arrow points into; a start is the empty cell behind an arrow no other arrow points into (else a free neighbor).
        for (int i = 0; i < 25; i++) {
            if (solution[i] < 0) {
                continue;
            }
            int to = arrowStep(i, solution[i], 1);
            if (solution[to] < 0 && frames[to] == null) {
                hang(level, to, com.killer560.hub.compat.McBlocks.RED_WOOL, 0);
            }
        }
        for (int i = 0; i < 25; i++) {
            if (solution[i] < 0 || pointedAt(i)) {
                continue;
            }
            int from = arrowStep(i, solution[i], -1);
            for (int k = 0; (from < 0 || solution[from] >= 0) && k < 4; k++) {
                from = arrowStep(i, 1 + 2 * k, 1);
            }
            if (from >= 0 && solution[from] < 0 && frames[from] == null) {
                hang(level, from, com.killer560.hub.compat.McBlocks.LIME_WOOL, 0);
            }
        }
    }

    private static void hang(ServerLevel level, int i, net.minecraft.world.level.ItemLike item, int rotation) {
        ItemFrame f = new ItemFrame(level, new BlockPos(ARROW_X, ARROW_Y + i % 5, ARROW_Z + i / 5), Direction.EAST) {
            @Override
            public boolean shouldBeSaved() {
                return false;
            }
        };
        f.setItem(new ItemStack(item), false);
        f.setRotation(rotation);
        level.addFreshEntity(f);
        frames[i] = f;
    }

    /** The cell an arrow at {@code i} turned to {@code rot} points into ({@code sign} -1: the one behind it), or -1 off the wall. */
    static int arrowStep(int i, int rot, int sign) {
        int dy = i % 5 + sign * (rot == 7 ? 1 : rot == 3 ? -1 : 0), dz = i / 5 + sign * (rot == 5 ? 1 : rot == 1 ? -1 : 0);
        return dy < 0 || dy > 4 || dz < 0 || dz > 4 ? -1 : dy + 5 * dz;
    }

    private static boolean pointedAt(int cell) {
        for (int j = 0; j < 25; j++) {
            if (solution[j] >= 0 && arrowStep(j, solution[j], 1) == cell) {
                return true;
            }
        }
        return false;
    }

    /** One of the wool frames that mark where the Arrows path starts and ends: never turned. */
    static boolean isArrowMark(net.minecraft.world.entity.Entity e) {
        for (int i = 0; solution != null && i < 25; i++) {
            if (solution[i] < 0 && frames[i] == e) {
                return true;
            }
        }
        return false;
    }

    /** Lights the wall block behind each arrow on its solution and puts the wall's own block behind every other cell. */
    private static void arrowWall(ServerLevel level) {
        if (wallBase == null) {
            java.util.Map<BlockState, Integer> seen = new java.util.HashMap<>();
            for (int i = 0; i < 25; i++) {
                BlockState st = level.getBlockState(new BlockPos(ARROW_X - 1, ARROW_Y + i % 5, ARROW_Z + i / 5));
                if (st != WALL_LIT && !st.isAir()) {
                    seen.merge(st, 1, Integer::sum);
                }
            }
            wallBase = seen.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey).orElse(BossBlocks.of("minecraft:obsidian"));
        }
        for (int i = 0; i < 25; i++) {
            boolean on = solution[i] >= 0 && frames[i] != null && !frames[i].isRemoved()
                    && (frames[i].getRotation() & 7) == solution[i];
            BossBlocks.set(level, WALL_AT.set(ARROW_X - 1, ARROW_Y + i % 5, ARROW_Z + i / 5), on ? WALL_LIT : wallBase);
        }
    }

    static void stop() {
        wallBase = null;
        java.util.Arrays.fill(lastBy, null);
        ssOn = false;
        ssGen++;
        ssJobs.clear();
        takeDownFrames();
    }

    private static void takeDownFrames() {
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
            lamps(level);
            if (lightsPulled && lightsCreditAt < 0) {
                lightsCreditAt = tick + 2;
            }
            if (lightsCreditAt >= 0 && tick >= lightsCreditAt && BossFight.section() <= 2) {
                lightsDone = true;
                done(level, 2);
            }
        }
        if (solution != null) {
            arrowWall(level);
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
            BlockPos lamp = p.south();
            BlockState was = level.getBlockState(lamp);
            if (!was.is(net.minecraft.world.level.block.Blocks.REDSTONE_LAMP) || was.getValue(BlockStateProperties.LIT) != on) {
                level.setBlock(lamp, BossBlocks.of("minecraft:redstone_lamp[lit=" + on + "]"), BossBlocks.FLAGS);
            }
            all &= on;
        }
        return all;
    }

    /**
     * A pull on a Lights lever: flipped with no redstone updates (vanilla's would light the lamp it hangs on and its
     * neighbours by vanilla's rule, then dim them on a 4-tick timer); {@link #lamps} lights them by Hypixel's.
     */
    static boolean pullLight(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (p.getZ() != 142 || p.getX() < 58 || p.getX() > 62 || p.getY() < 133 || p.getY() > 136
                || !s.is(net.minecraft.world.level.block.Blocks.LEVER)) {
            return false;
        }
        boolean on = !s.getValue(BlockStateProperties.POWERED);
        lightsPulled = true;
        level.setBlock(p, s.setValue(BlockStateProperties.POWERED, on), BossBlocks.FLAGS);
        level.playSound(null, p, net.minecraft.sounds.SoundEvents.LEVER_CLICK, net.minecraft.sounds.SoundSource.BLOCKS, 0.3f,
                on ? 0.6f : 0.5f);
        return true;
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
        java.util.UUID u = lastBy[section];
        ServerPlayer by = u == null ? null : level.getServer().getPlayerList().getPlayer(u);
        if (by == null) {
            by = level.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
        }
        BossStations.complete(level, BossStations.device(section), by);
    }

    /**
     * Who last worked each device (1 Simon Says, 2 Lights, 3 Arrows, 4 Target), so its completion line names the player
     * who finished it, not the host (coopsim-1109). A UUID, not the entity: a player who left is no one's credit.
     */
    private static final java.util.UUID[] lastBy = new java.util.UUID[5];

    /** Testkit: who last worked device {@code section} (1-4), or null. */
    public static java.util.UUID lastByForTest(int section) { return lastBy[section]; }

    static void noteActor(int section, ServerPlayer sp) {
        if (sp != null && section >= 1 && section <= 4) {
            lastBy[section] = sp.getUUID();
        }
    }

    /** A block use in the fight: a Simon Says button or a Lights lever names its device's worker. */
    static void noteBlockUse(ServerPlayer sp, BlockPos p) {
        if (p.getX() == 110 && p.getY() >= 120 && p.getY() <= 123 && p.getZ() >= 91 && p.getZ() <= 95) {
            noteActor(1, sp);
        } else if (p.getZ() == 142 && p.getX() >= 58 && p.getX() <= 62 && p.getY() >= 133 && p.getY() <= 136) {
            noteActor(2, sp);
        }
    }

    private static boolean powered(BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.STONE_BUTTON) && s.getValue(BlockStateProperties.POWERED);
    }

    private static BlockPos ssCell(int x, int cell) {
        return new BlockPos(x, 120 + cell / 4, 92 + cell % 4);
    }

    private static void ssButton(ServerLevel level, int cell, boolean up) {
        if (ssUp[cell] != up) {
            ssUp[cell] = up;
            level.setBlock(ssCell(110, cell), up ? ssButton() : BossBlocks.AIR, BossBlocks.FLAGS);
        }
    }

    private static void ssLamp(ServerLevel level, int cell, boolean on) {
        if (ssLit[cell] != on) {
            ssLit[cell] = on;
            level.setBlock(ssCell(111, cell), BossBlocks.of(on ? "minecraft:sea_lantern" : "minecraft:obsidian"), BossBlocks.FLAGS);
        }
    }

    private static void ssAllOff(ServerLevel level) {
        for (int c = 0; c < 16; c++) {
            ssLamp(level, c, false);
            ssButton(level, c, false);
        }
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

    private static void ssAfter(int ticks, Runnable r) {
        ssJobs.add(new SsJob(tick + Math.max(0, ticks), ssGen, r));
    }

    private static void ssNewPattern() {
        List<Integer> cells = new ArrayList<>(16);
        for (int c = 0; c < 16; c++) {
            cells.add(c);
        }
        Collections.shuffle(cells, RNG);
        for (int i = 0; i < SS_ROUNDS; i++) {
            ssSeq[i] = cells.get(i);
        }
    }

    /**
     * Simon Says as Hypixel runs it (ADM's measured device, facts only). The start button toggles: a press while stopped
     * draws a pattern and queues a round task 4 ticks on; a press while started resets it (round 0, lamps off, buttons
     * gone) but never cancels a round task already queued nor a show already running. That is the skip: start, reset 3
     * ticks later, start 3 ticks after that, and the first start's task shows round 1 while the second's adds round 2.
     */
    private static void tickSimonSays(ServerLevel level) {
        if (!ssOn) {
            return;
        }
        boolean startNow = powered(level.getBlockState(SS_START));
        if (startNow && !ssStartWasPowered) {
            popUp(level, SS_START, 2);
            if (!ssDone && BossFight.section() == 1) {
                ssStart(level);
            }
        }
        ssStartWasPowered = startNow;
        for (int c = 0; c < 16; c++) {
            boolean now = ssUp[c] && powered(level.getBlockState(SS_AT.set(110, 120 + c / 4, 92 + c % 4)));
            if (now && !ssWasPowered[c]) {
                popUp(level, ssCell(110, c), 3);
                ssPress(level, c);
            }
            ssWasPowered[c] = now;
        }
        // This tick's tasks, in the order queued (a task may queue more for this same tick).
        for (int i = 0; i < ssJobs.size(); ) {
            SsJob j = ssJobs.get(i);
            if (j.at() > tick) {
                i++;
                continue;
            }
            ssJobs.remove(i);
            if (j.gen() == ssGen) {
                j.run().run();
            }
            i = 0;
        }
    }

    private static void ssStart(ServerLevel level) {
        if (ssStarted) {
            ssStarted = ssAccepting = ssRoundDone = false;
            ssRound = ssNext = 0;
            ssAllOff(level);
            LOGGER.info("[BossSim] Simon Says reset by the start button");
            return;
        }
        ssStarted = true;
        ssBroken = false;
        ssNewPattern();
        ssAfter(4, () -> ssNextRound(level));
        LOGGER.info("[BossSim] Simon Says started");
    }

    /** A round task: one round more and its show, or past the last round the device. */
    private static void ssNextRound(ServerLevel level) {
        if (ssDone) {
            return;
        }
        ssRoundDone = false;
        if (++ssRound > SS_ROUNDS) {
            ssDone = true;
            ssAccepting = false;
            ssGen++;
            ssJobs.clear();
            ssAllOff(level);
            done(level, 1);
            return;
        }
        // Every lamp off and every button gone; lamp j lights at 8j (the pattern as it is then), takes its button and
        // goes out 8 ticks later; the buttons come back 10 ticks after the last lamp's slot, except under lit lamps.
        ssAllOff(level);
        ssAccepting = ssBroken = false;
        ssNext = 0;
        int n = ssRound;
        for (int j = 0; j < n; j++) {
            int jj = j;
            Runnable light = () -> {
                int c = ssSeq[jj];
                ssLamp(level, c, true);
                ssButton(level, c, false);
                ssAfter(8, () -> ssLamp(level, c, false));
            };
            if (j == 0) {
                light.run();
            } else {
                ssAfter(8 * j, light);
            }
        }
        ssAfter(8 * n + 10, () -> ssButtonsBack(level, true));
    }

    /** Every button up but under lit lamps; {@code accept}: the round shown is put in from now. */
    private static void ssButtonsBack(ServerLevel level, boolean accept) {
        for (int c = 0; c < 16; c++) {
            if (!ssLit[c]) {
                ssButton(level, c, true);
            }
        }
        if (accept && ssRound >= 1 && !ssAccepting && !ssDone) {
            ssAccepting = true;
        }
    }

    /** A round shown with no start press (after a wrong press): a new pattern, round {@code n} at once. */
    private static void ssShowRound(ServerLevel level, int n) {
        if (ssDone) {
            return;
        }
        ssNewPattern();
        ssRound = n - 1;
        ssNextRound(level);
    }

    private static void ssPress(ServerLevel level, int c) {
        if (!ssAccepting || ssDone) {
            return;
        }
        if (c == ssSeq[ssNext]) {
            if (++ssNext < ssRound) {
                return;
            }
            ssAccepting = false;
            ssRoundDone = true;
            ssAfter(6, () -> ssNextRound(level));
            return;
        }
        // Wrong: back to round 0 and stopped, the buttons gone 3 ticks later; a new show of round 2 follows on its own 26
        // ticks after that, or a stray round 1 a few ticks before a round 2 at 34. The old buttons come back 1-4 ticks
        // into each show (stale: they press, nothing counts).
        LOGGER.info("[BossSim] Simon Says broken in round {}", ssRound);
        ssAccepting = ssStarted = false;
        ssBroken = true;
        ssRound = ssNext = 0;
        ssAfter(3, () -> {
            for (int b = 0; b < 16; b++) {
                ssButton(level, b, false);
            }
        });
        Runnable stale = () -> ssAfter(1 + RNG.nextInt(4), () -> ssButtonsBack(level, false));
        if (RNG.nextBoolean()) {
            ssAfter(3 + 26, () -> {
                ssShowRound(level, 2);
                stale.run();
            });
        } else {
            ssAfter(3 + 34 - SS_STRAY_GAPS[RNG.nextInt(SS_STRAY_GAPS.length)], () -> {
                ssShowRound(level, 1);
                stale.run();
            });
            ssAfter(3 + 34, () -> {
                ssShowRound(level, 2);
                stale.run();
            });
        }
    }

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
        if (hits.get(0).getOwner() instanceof ServerPlayer shooter) {
            noteActor(4, shooter);
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
        return "ss=" + ssState() + " round=" + ssRound + " lights=" + lightsDone + " arrows=" + arrowsDone + " target=" + targetHits + "/"
                + TARGET_HITS;
    }

    /** Debug and testkit: the Simon Says steps of the round in play, as x,y,z of each lantern. */
    public static List<int[]> ssSequence() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(ssRound, SS_ROUNDS); i++) {
            out.add(new int[]{111, 120 + ssSeq[i] / 4, 92 + ssSeq[i] % 4});
        }
        return out;
    }

    public static boolean ssInput() {
        return ssAccepting;
    }

    /** Debug and testkit: the Simon Says state's name (OFF, IDLE, SHOW, INPUT, ROUND_DONE, DONE, BROKEN). */
    public static String ssState() {
        return !ssOn ? "OFF" : ssDone ? "DONE" : ssAccepting ? "INPUT" : ssBroken ? "BROKEN" : ssRoundDone ? "ROUND_DONE"
                : ssRound > 0 ? "SHOW" : "IDLE";
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
