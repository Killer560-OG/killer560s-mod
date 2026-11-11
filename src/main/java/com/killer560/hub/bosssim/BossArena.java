package com.killer560.hub.bosssim;

import com.killer560.hub.roomsim.RoomPlacer;
import com.killer560.hub.roomsim.SimBuildQueue;
import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The F7 boss room in the sim world, at Hypixel's own coordinates (the boss lives at x -8..144, y 0..255, z -8..160, on the
 * positive side of the dungeon's grid; the sim's clear is on the negative side, so both fit in one world).
 *
 * <p>Two sources, best first:
 * <ol>
 * <li><b>His capture of the real (reskinned) room</b>, bundled in the jar ({@code assets/killer560smod/sim-boss/}, P1 and
 * P3) and always used, for every player (killer560, 2026-11-10: "the one i have is the right one any other one is wrong
 * ... pushed to everyone with no way to overwrite it"). No file on disk is read. Block for block, so every route and
 * landing matches.</li>
 * <li><b>The built-in plan</b> ({@link #plan}), only when the bundled capture cannot be read (logged as an ERROR): floors, walls, the P3 ring and core, gates, the terminal, lever and device
 * spots, the P4 and P5 floors, built from the coordinates this mod already reads Hypixel's boss by (the terminal, lever
 * and device positions, the section boxes, the gates, the Storm pillars and pads, the Necron platforms, the dragon and
 * relic spots). An approximation: every station and device sits exactly where Hypixel's does, the rooms around them are
 * plain boxes.</li>
 * </ol>
 * Reference mod ADM (undonecoffee/adm) ships its own capture of the room; it is All-Rights-Reserved and is NOT used here -
 * only facts about Hypixel's boss (coordinates, lines, timings) were read from it.
 *
 * <p>Blocks are written a slice per server tick (the {@link SimBuildQueue} job {@code ArenaJob}), so the integrated server keeps
 * ticking while it builds.
 */
public final class BossArena {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    /** The capture's box (the same box ADM measured the room in: x -8..143, z -8..159, y 0..255). */
    public static final int X0 = -8, Y0 = 0, Z0 = -8, W = 152, H = 256, D = 168;

    /** P1's floor top block (Maxor), P2's (Storm), P3's ring (Goldor), P4's (Necron), P5's (the Wither King). */
    static final int P1_FLOOR = 220, P2_FLOOR = 168, P3_FLOOR = 106, P4_FLOOR = 62, P5_FLOOR = 4;

    /** One fill of the plan: an inclusive box of one block state. */
    record Fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        long volume() {
            return (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        }
    }

    private static List<Fill> jobs = List.of();
    private static int jobIndex;
    private static long jobCursor;
    private static Runnable onDone;
    private static Captured captured;
    private static long capturedCursor;
    /** The P3 capture's cells (index, state) in P3's band that differ from the P1 capture: what Hypixel changes when P3 starts. */
    private static int[] p3Cells = new int[0];
    private static BlockState[] p3States = new BlockState[0];
    /**
     * Maxor's and Storm's holes over a capture: the cells of y 214-224 (P1's floor) and 160-170 (P2's) that the P3 capture
     * has as air and the P1 one as a solid block (bossfloor-1104: the plan's holes cut over bedrock there and nobody fell).
     */
    private static final int[][] HOLE_BANDS = {{214, 224}, {160, 170}};
    private static int[][] holeCells = {new int[0], new int[0]};
    /** P3's band in the capture box: the ring, its gates and the core (P2's floor is at 168, P4's at 62). */
    private static final int P3_Y1 = 100, P3_Y2 = 145;
    private static String source = "none";

    private BossArena() {
    }

    /** "capture" or "plan": what the last build was made from (the testkit and the report read it). */
    public static String source() {
        return source;
    }

    static boolean building() {
        return onDone != null;
    }

    /**
     * Starts a build; {@code done} runs on the server thread once every block is written and lit.
     *
     * <p>A {@link SimBuildQueue} job since simspawn-1104, written straight into the chunk sections like a room paste: it
     * was {@code level.setBlock} per block (lighting, heightmaps and a client update each) at 60k a tick on its own
     * ticker, the loading screen's bar read the room queue and sat at 0% the whole time, and he stood inside the box
     * being built.
     */
    static void build(ServerLevel level, Runnable done) {
        captured = readCapture();
        capturedCursor = 0;
        p3Diff(captured, readCapture("P3"));
        jobs = plan(captured != null);
        jobIndex = 0;
        jobCursor = 0;
        onDone = done;
        source = captured != null ? "capture" : "plan";
        LOGGER.info("[BossSim] arena build from the {} ({} plan fill(s))", source, jobs.size());
        SimBuildQueue.submitJob(new ArenaJob(level));
        SimBuildQueue.submitRelight(level.getServer(), X0, Z0, X0 + W - 1, Z0 + D - 1);
        SimBuildQueue.whenDone(BossArena::finish);
    }

    private static void finish() {
        Runnable done = onDone;
        onDone = null;
        captured = null;
        if (done != null) {
            done.run();
        }
    }

    /** P3's start as Hypixel's P3 capture has it (no-op without both captures); idempotent. */
    static void applyP3(ServerLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int k = 0; k < p3Cells.length; k++) {
            int i = p3Cells[k];
            pos.set(X0 + i % W, Y0 + i / (W * D), Z0 + (i / W) % D);
            if (level.getBlockState(pos) != p3States[k]) {
                level.setBlock(pos, p3States[k], BossBlocks.FLAGS | Block.UPDATE_SKIP_ON_PLACE);
            }
        }
    }

    private static void p3Diff(Captured p1, Captured p3) {
        if (p1 == null || p3 == null || p1.w != p3.w || p1.h != p3.h || p1.d != p3.d || p1.x0 != p3.x0 || p1.y0 != p3.y0
                || p1.z0 != p3.z0) {
            p3Cells = new int[0];
            p3States = new BlockState[0];
            holeCells = new int[][]{new int[0], new int[0]};
            return;
        }
        p3Cells = differing(p1, p3, P3_Y1, P3_Y2, false);
        p3States = new BlockState[p3Cells.length];
        for (int k = 0; k < p3Cells.length; k++) {
            p3States[k] = p3.states[p3.cells[p3Cells[k]]];
        }
        for (int h = 0; h < HOLE_BANDS.length; h++) {
            holeCells[h] = differing(p1, p3, HOLE_BANDS[h][0], HOLE_BANDS[h][1], true);
        }
        LOGGER.info("[BossSim] P3 capture: {} block(s) of P3 differ from the P1 capture; holes {} and {}", p3Cells.length,
                holeCells[0].length, holeCells[1].length);
    }

    /** The cells of y {@code y1..y2} where the two captures differ (only solid-to-air ones when {@code holes}). */
    private static int[] differing(Captured p1, Captured p3, int y1, int y2, boolean holes) {
        int from = Math.max(0, (y1 - p1.y0) * p1.w * p1.d), to = Math.min(p1.cells.length, (y2 - p1.y0 + 1) * p1.w * p1.d);
        int n = 0;
        int[] cells = new int[1024];
        for (int i = from; i < to; i++) {
            BlockState a = p1.states[p1.cells[i]], b = p3.states[p3.cells[i]];
            if (a != b && (!holes || b.isAir() && !a.isAir() && a.getFluidState().isEmpty())) {
                if (n == cells.length) {
                    cells = java.util.Arrays.copyOf(cells, n * 2);
                }
                cells[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(cells, n);
    }

    /** Opens Maxor's (0) or Storm's (1) hole as the P3 capture shows it; false when there is none to read it from. */
    static boolean openCapturedHole(ServerLevel level, int which) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i : holeCells[which]) {
            level.setBlock(pos.set(X0 + i % W, Y0 + i / (W * D), Z0 + (i / W) % D), BossBlocks.AIR, BossBlocks.FLAGS);
        }
        return holeCells[which].length > 0;
    }

    /**
     * Where a player stands on (x, z) of P3's ring: the top of the first block from the plan's ring up with two free
     * blocks over it. The capture's ground is at 113-116 (bossfloor-1104), the plan's at {@link #P3_FLOOR}.
     */
    static double standY(ServerLevel level, double x, double z) {
        return standY(level, x, z, P3_FLOOR, P3_Y2);
    }

    /** The same from {@code from} up to {@code to}: P4's capture platform is at 63, the plan's at {@link #P4_FLOOR}. */
    static double standY(ServerLevel level, double x, double z, int from, int to) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = net.minecraft.util.Mth.floor(x), bz = net.minecraft.util.Mth.floor(z);
        for (int y = from; y < to; y++) {
            var shape = level.getBlockState(p.set(bx, y, bz)).getCollisionShape(level, p);
            if (!shape.isEmpty() && free(level, p.set(bx, y + 1, bz)) && free(level, p.set(bx, y + 2, bz))) {
                return y + shape.max(net.minecraft.core.Direction.Axis.Y);
            }
        }
        return from + 1;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Positions one tick may look at; reads from the section are cheap, so this is well over the write budget. */
    private static final int SCAN_PER_TICK = 600_000;

    /** The capture, then the plan's fills, a slice per tick through the room paste's section writer. */
    private static final class ArenaJob implements SimBuildQueue.Job {

        private final RoomPlacer.SectionWriter writer;
        private final long total;
        private boolean finished;

        ArenaJob(ServerLevel level) {
            this.writer = new RoomPlacer.SectionWriter(level, false);
            long t = captured == null ? 0 : (long) captured.w * captured.h * captured.d;
            for (Fill f : jobs) {
                t += f.volume();
            }
            this.total = Math.max(1, t);
        }

        @Override
        public boolean isDone() { return finished; }

        @Override
        public long totalWork() { return total; }

        @Override
        public long doneWork() {
            long d = captured == null ? 0 : capturedCursor;
            for (int i = 0; i < jobIndex && i < jobs.size(); i++) {
                d += jobs.get(i).volume();
            }
            return Math.min(total, d + jobCursor);
        }

        @Override
        public String stage() { return "Building the F7 boss arena"; }

        @Override
        public int step(int budget) {
            int written = 0;
            int scanned = 0;
            if (captured != null) {
                long all = (long) captured.w * captured.h * captured.d;
                while (written < budget && scanned < SCAN_PER_TICK && capturedCursor < all) {
                    long i = capturedCursor++;
                    scanned++;
                    int x = captured.x0 + (int) (i % captured.w);
                    int z = captured.z0 + (int) ((i / captured.w) % captured.d);
                    int y = captured.y0 + (int) (i / ((long) captured.w * captured.d));
                    if (put(x, y, z, captured.states[captured.cells[(int) i]])) written++;
                }
                if (capturedCursor < all) {
                    return written;
                }
            }
            while (written < budget && scanned < SCAN_PER_TICK && jobIndex < jobs.size()) {
                Fill f = jobs.get(jobIndex);
                int minX = Math.min(f.x1, f.x2), minY = Math.min(f.y1, f.y2), minZ = Math.min(f.z1, f.z2);
                int sx = Math.abs(f.x2 - f.x1) + 1, sz = Math.abs(f.z2 - f.z1) + 1;
                long vol = f.volume();
                while (written < budget && scanned < SCAN_PER_TICK && jobCursor < vol) {
                    long i = jobCursor++;
                    scanned++;
                    if (put(minX + (int) (i % sx), minY + (int) (i / ((long) sx * sz)), minZ + (int) ((i / sx) % sz),
                            f.state)) written++;
                }
                if (jobCursor >= vol) {
                    jobIndex++;
                    jobCursor = 0;
                }
            }
            finished = jobIndex >= jobs.size();
            return written;
        }

        private boolean put(int x, int y, int z, BlockState s) {
            BlockState old = writer.get(x, y, z);
            return old != null && old != s && writer.set(x, y, z, s);
        }
    }

    private static void box(List<Fill> out, int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        out.add(new Fill(x1, y1, z1, x2, y2, z2, BossBlocks.of(state)));
    }

    /** Four walls (no floor or roof) of the box between the two corners. */
    private static void walls(List<Fill> out, int x1, int y1, int z1, int x2, int y2, int z2, String state) {
        box(out, x1, y1, z1, x2, y2, z1, state);
        box(out, x1, y1, z2, x2, y2, z2, state);
        box(out, x1, y1, z1, x1, y2, z2, state);
        box(out, x2, y1, z1, x2, y2, z2, state);
    }

    /**
     * The built-in room, or nothing over a capture (the capture resets every block a fight changed).
     */
    static List<Fill> plan(boolean overCapture) {
        List<Fill> out = new ArrayList<>();
        if (!overCapture) {
            // Clear the whole box first: a rebuild must not keep blocks a fight changed or a player placed.
            for (int y = Y0; y < Y0 + H; y += 16) {
                box(out, X0, y, Z0, X0 + W - 1, Math.min(Y0 + H - 1, y + 15), Z0 + D - 1, "minecraft:air");
            }
            // ---- P1, Maxor (floor 220) -------------------------------------------------------------------------
            box(out, 36, P1_FLOOR, 4, 110, P1_FLOOR, 100, "minecraft:quartz_block");
            walls(out, 35, P1_FLOOR, 3, 111, P1_FLOOR + 18, 101, "minecraft:stone_bricks");
            // The two crystal towers (crystals on top at 64.5/82.5, 238, 50.5), a ladder up the south face of each.
            for (int cx : new int[]{64, 82}) {
                box(out, cx - 1, P1_FLOOR + 1, 49, cx + 1, 237, 51, "minecraft:stone_bricks");
                box(out, cx, P1_FLOOR + 1, 52, cx, 237, 52, "minecraft:ladder[facing=south]");
            }
            // The two pylons the crystals go on (52.5/94.5, 224, 41.5).
            for (int px : new int[]{52, 94}) {
                box(out, px - 1, P1_FLOOR + 1, 40, px + 1, 223, 42, "minecraft:polished_andesite");
            }
            // The strip the beam crosses (ADM: rows at y 224, z 70..76): where a lure stands to bring Maxor into the beam.
            box(out, 36, 224, 70, 110, 224, 76, "minecraft:light_gray_wool");
            // ---- P2, Storm (floor 168) -------------------------------------------------------------------------
            box(out, 20, P2_FLOOR, 5, 126, P2_FLOOR, 110, "minecraft:polished_andesite");
            walls(out, 19, P2_FLOOR, 4, 127, P2_FLOOR + 22, 111, "minecraft:stone_bricks");
            for (BossFight.Pillar p : BossFight.PILLARS) {
                // A diorite pillar hangs over its 7x7 square; the crush drops it to the floor.
                pillar(out, p, p.restBottom(), BossFight.PILLAR_TOP, "minecraft:polished_diorite");
                if (p.pad() != null) {
                    int[] pad = p.pad();
                    box(out, pad[0], P2_FLOOR + 1, pad[1], pad[0] + 6, P2_FLOOR + 1, pad[1] + 6, p.padBlock());
                }
            }
            // ---- P3, Goldor (floor 106) ------------------------------------------------------------------------
            // Lava under and around the core, a bedrock bed below it.
            box(out, -7, P3_FLOOR - 1, 26, 114, P3_FLOOR - 1, 146, "minecraft:bedrock");
            box(out, 20, P3_FLOOR, 52, 88, P3_FLOOR, 120, "minecraft:lava");
            // The ring: each section's box (Floor7Tracker's) as floor.
            box(out, 89, P3_FLOOR, 30, 113, P3_FLOOR, 122, "minecraft:stone_bricks");   // S1
            box(out, 19, P3_FLOOR, 121, 113, P3_FLOOR, 145, "minecraft:stone_bricks");  // S2
            box(out, -6, P3_FLOOR, 51, 18, P3_FLOOR, 145, "minecraft:stone_bricks");    // S3
            box(out, -6, P3_FLOOR, 27, 88, P3_FLOOR, 50, "minecraft:stone_bricks");     // S4
            walls(out, -7, P3_FLOOR + 1, 26, 114, 140, 146, "minecraft:stone_bricks");
            // Inner edges of the ring, low walls so the lava is seen and can be jumped into.
            box(out, 20, P3_FLOOR + 1, 51, 88, P3_FLOOR + 1, 51, "minecraft:stone_brick_wall");
            box(out, 88, P3_FLOOR + 1, 52, 88, P3_FLOOR + 1, 120, "minecraft:stone_brick_wall");
            box(out, 20, P3_FLOOR + 1, 120, 88, P3_FLOOR + 1, 120, "minecraft:stone_brick_wall");
            box(out, 19, P3_FLOOR + 1, 52, 19, P3_FLOOR + 1, 120, "minecraft:stone_brick_wall");
            // S4 ends at S1: the core bridge is its way on.
            box(out, 88, P3_FLOOR + 1, 27, 88, 140, 50, "minecraft:stone_bricks");
            // The core.
            box(out, 41, P3_FLOOR, 59, 68, P3_FLOOR, 117, "minecraft:polished_blackstone_bricks");
            walls(out, 40, P3_FLOOR + 1, 58, 69, P3_FLOOR + 20, 118, "minecraft:polished_blackstone_bricks");
            box(out, 52, P3_FLOOR, 51, 57, P3_FLOOR, 57, "minecraft:stone_bricks");       // the bridge from S4
            box(out, 52, P3_FLOOR + 1, 51, 57, P3_FLOOR + 1, 51, "minecraft:air");        // through the ring's edge
            stations(out);
            devices(out);
            // ---- P4, Necron (floor 62) -------------------------------------------------------------------------
            box(out, 10, P4_FLOOR - 1, 28, 100, P4_FLOOR - 1, 132, "minecraft:bedrock");
            box(out, 10, P4_FLOOR, 28, 100, P4_FLOOR, 132, "minecraft:lava");
            walls(out, 9, P4_FLOOR, 27, 101, 100, 133, "minecraft:obsidian");
            box(out, 44, P4_FLOOR, 66, 64, P4_FLOOR, 86, "minecraft:obsidian");          // the middle (54, 76)
            // The south platform, where the core's floor drops everyone (never broken).
            int[] south = BossFight.NECRON_SOUTH;
            box(out, south[0], P4_FLOOR, south[2], south[1], P4_FLOOR, south[3], "minecraft:obsidian");
            // ---- P5, the Wither King (floor 4) -----------------------------------------------------------------
            box(out, 4, P5_FLOOR, 28, 104, P5_FLOOR, 140, "minecraft:stone_bricks");
            walls(out, 3, P5_FLOOR, 27, 105, 44, 141, "minecraft:stone_bricks");
            for (int[] st : BossFight.DRAGON_STATUES) {
                box(out, st[0] - 1, P5_FLOOR + 1, st[2] - 1, st[0] + 1, st[1], st[2] + 1, "minecraft:stone_bricks");
            }
        }
        if (overCapture) {
            // The capture IS the room: its own copy of every block a fight changes is already written, so nothing of the
            // plan goes over it (bossfloor-1104: the plan's gates, holes, pillars and device walls sat on the real room).
            return out;
        }
        // ---- what every fight rewrites --------------------------------------------------------------------------
        // Maxor's floor over Storm: whole again (Maxor's death opens it).
        box(out, BossFight.P1_HOLE[0], P1_FLOOR, BossFight.P1_HOLE[1], BossFight.P1_HOLE[2], P1_FLOOR, BossFight.P1_HOLE[3],
                "minecraft:quartz_block");
        // Storm's floor over S1: whole again (Storm's death opens it).
        box(out, BossFight.P2_HOLE[0], P2_FLOOR, BossFight.P2_HOLE[1], BossFight.P2_HOLE[2], P2_FLOOR, BossFight.P2_HOLE[3],
                "minecraft:polished_andesite");
        for (BossFight.Pillar p : BossFight.PILLARS) {
            box(out, p.minX(), P2_FLOOR + 1, p.minZ(), p.minX() + 6, p.restBottom() - 1, p.minZ() + 6, "minecraft:air");
            pillar(out, p, p.restBottom(), BossFight.PILLAR_TOP, "minecraft:polished_diorite");
        }
        // Maxor's beam: the two blocks the pylons' opening clears, black glass above (ADM: 73, 221-224, 73).
        box(out, 73, P1_FLOOR + 1, 73, 73, P1_FLOOR + 2, 73, "minecraft:polished_andesite");
        box(out, 73, P1_FLOOR + 3, 73, 73, P1_FLOOR + 4, 73, "minecraft:black_stained_glass");
        // Necron's side platforms (one goes to lava each fight), at ADM's extents.
        for (int[] pl : BossFight.NECRON_PLATFORMS) {
            box(out, pl[2], P4_FLOOR, pl[4], pl[3], P4_FLOOR, pl[5], "minecraft:obsidian");
        }
        for (int[] g : BossStations.GATES) {
            box(out, g[0], g[1], g[2], g[3], g[4], g[5], "minecraft:iron_block");
        }
        box(out, 52, P3_FLOOR + 1, 58, 57, P3_FLOOR + 6, 58, "minecraft:gold_block");  // the core entrance
        box(out, BossFight.CORE_HOLE[0], P3_FLOOR - 1, BossFight.CORE_HOLE[1], BossFight.CORE_HOLE[2], P3_FLOOR - 1,
                BossFight.CORE_HOLE[3], "minecraft:bedrock");
        box(out, BossFight.CORE_HOLE[0], P3_FLOOR, BossFight.CORE_HOLE[1], BossFight.CORE_HOLE[2], P3_FLOOR,
                BossFight.CORE_HOLE[3], "minecraft:polished_blackstone_bricks");
        box(out, BossFight.P4_HOLE[0], P4_FLOOR - 1, BossFight.P4_HOLE[1], BossFight.P4_HOLE[2], P4_FLOOR - 1,
                BossFight.P4_HOLE[3], "minecraft:bedrock");
        box(out, BossFight.P4_HOLE[0], P4_FLOOR, BossFight.P4_HOLE[1], BossFight.P4_HOLE[2], P4_FLOOR, BossFight.P4_HOLE[3],
                "minecraft:obsidian");
        for (BossStations.Station s : BossStations.all()) {
            if (s.kind() == BossStations.Kind.LEVER) {
                box(out, s.lever().getX(), s.lever().getY(), s.lever().getZ(), s.lever().getX(), s.lever().getY(),
                        s.lever().getZ(), "minecraft:lever[face=floor,facing=north,powered=false]");
            }
        }
        BossDevices.resetBlocks(out);
        return out;
    }

    /** A Storm pillar's layers y1..y2 over its footprint (7x7 less its corners, as Hypixel's). */
    private static void pillar(List<Fill> out, BossFight.Pillar p, int y1, int y2, String state) {
        for (int dx = 0; dx <= 6; dx++) {
            for (int dz = 0; dz <= 6; dz++) {
                if (BossFight.inFootprint(dx, dz)) {
                    box(out, p.minX() + dx, y1, p.minZ() + dz, p.minX() + dx, y2, p.minZ() + dz, state);
                }
            }
        }
    }

    /**
     * A station's stand of blocks: a pillar from the floor to just under it, a 3x3 top, and a two-wide stair down to the
     * floor toward the section's middle, so every terminal and lever can be walked to.
     */
    private static void stations(List<Fill> out) {
        for (BossStations.Station s : BossStations.all()) {
            int x = (int) Math.floor(s.at().x), z = (int) Math.floor(s.at().z);
            int top = s.kind() == BossStations.Kind.LEVER ? s.lever().getY() - 1 : (int) Math.floor(s.at().y) - 1;
            if (s.kind() == BossStations.Kind.DEVICE) {
                continue;   // the devices have their own platforms
            }
            platform(out, x, top, z, s.section(), 1);
            if (s.kind() == BossStations.Kind.TERMINAL) {
                // Hypixel's terminal stands on a command block.
                box(out, x, top, z, x, top, z, "minecraft:command_block");
            }
        }
    }

    /** A (2r+1)-square top at {@code top} over a pillar, and a stair from it down to the P3 floor. */
    static void platform(List<Fill> out, int x, int top, int z, int section, int r) {
        if (top <= P3_FLOOR) {
            return;
        }
        box(out, x - r, P3_FLOOR + 1, z - r, x + r, top, z + r, "minecraft:stone_bricks");
        int[] dir = inward(section);
        int rise = top - P3_FLOOR;
        for (int i = 1; i <= rise; i++) {
            int y = top - i + 1;
            int sx = x + dir[0] * (r + i), sz = z + dir[1] * (r + i);
            if (dir[0] != 0) {
                box(out, sx, P3_FLOOR + 1, z - 1, sx, y - 1 < P3_FLOOR + 1 ? P3_FLOOR : y - 1, z, "minecraft:stone_bricks");
                box(out, sx, y, z - 1, sx, y, z, "minecraft:stone_brick_slab[type=top]");
            } else {
                box(out, x - 1, P3_FLOOR + 1, sz, x, y - 1 < P3_FLOOR + 1 ? P3_FLOOR : y - 1, sz, "minecraft:stone_bricks");
                box(out, x - 1, y, sz, x, y, sz, "minecraft:stone_brick_slab[type=top]");
            }
        }
    }

    /** The way along a section toward its middle line, which is clear of the walls: S1 west, S2 north, S3 east, S4 south. */
    private static int[] inward(int section) {
        return switch (section) {
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            case 3 -> new int[]{1, 0};
            default -> new int[]{0, 1};
        };
    }

    /** Device platforms and walls, from BossDevices' own coordinates. */
    private static void devices(List<Fill> out) {
        // Simon Says (S1): the lantern wall x 111, the buttons x 110, a floor at 119 to press them from.
        box(out, 103, 119, 87, 109, 119, 98, "minecraft:stone_bricks");
        box(out, 111, 119, 89, 111, 124, 97, "minecraft:obsidian");
        platform(out, 106, 119, 92, 1, 2);
        // Lights (S2): the lever wall z 143, a floor at 130.
        box(out, 56, 131, 143, 64, 138, 143, "minecraft:stone_bricks");
        box(out, 55, 130, 135, 65, 130, 141, "minecraft:stone_bricks");
        platform(out, 60, 130, 138, 2, 2);
        // Arrows (S3): the frame wall x -3, a floor at 118.
        box(out, -3, 119, 73, -3, 126, 81, "minecraft:stone_bricks");
        box(out, -2, 118, 72, 3, 118, 82, "minecraft:stone_bricks");
        platform(out, 1, 118, 77, 3, 2);
        // Target (S4): the target wall z 50 (x 64..68, y 126..130) and a floor at 126 to shoot from (the plate on it at 127).
        box(out, 62, 124, 50, 70, 132, 50, "minecraft:stone_bricks");
        box(out, 60, 126, 32, 67, 126, 37, "minecraft:blue_terracotta");
        platform(out, 63, 126, 34, 4, 2);
    }

    /**
     * A capture reads the blocks as this client shows them, so one taken with I Hate Diorite on holds its client-side
     * stained glass in Storm's pillars (bossfloor-1104): put Hypixel's polished diorite back there.
     */
    private static BlockState[] undoDioriteGlass(int x0, int y0, int z0, int w, int d, BlockState[] states, short[] cells) {
        short diorite = -1;
        int undone = 0;
        for (int i = 0; i < cells.length; i++) {
            BlockState st = states[cells[i]];
            if (st.getBlock() instanceof net.minecraft.world.level.block.StainedGlassBlock
                    && com.killer560.hub.diorite.DioriteGlassFeature.isPillarGlass(
                            new BlockPos(x0 + i % w, y0 + i / (w * d), z0 + (i / w) % d), st)) {
                if (diorite < 0) {
                    diorite = (short) states.length;
                    states = java.util.Arrays.copyOf(states, states.length + 1);
                    states[diorite] = BossBlocks.of("minecraft:polished_diorite");
                }
                cells[i] = diorite;
                undone++;
            }
        }
        if (undone > 0) {
            LOGGER.info("[BossSim] capture: {} pillar glass block(s) back to diorite (taken with I Hate Diorite on)", undone);
        }
        return states;
    }

    /** A capture as read back: the box, its palette and one palette index per cell (x fastest, then z, then y). */
    /** {@code phase}: the boss phase it was taken in (a version-1 file has none: its file name's). */
    record Captured(String phase, int x0, int y0, int z0, int w, int h, int d, BlockState[] states, short[] cells) {
    }

    /** The bundled capture of {@code phase}: P1 and P3 are in the jar, every other phase has none. */
    static String bundled(String phase) {
        return "P1".equals(phase) ? "/assets/killer560smod/sim-boss/sim-bossarena.bin"
                : "P3".equals(phase) ? "/assets/killer560smod/sim-boss/sim-bossarena-p3.bin" : null;
    }

    /** The P1 capture, or null when it cannot be read (an ERROR: the plan is then used). */
    static Captured readCapture() {
        return readCapture("P1");
    }

    /** The bundled capture taken in {@code phase}, or null (none bundled for it, or unreadable: logged as an ERROR). */
    static Captured readCapture(String phase) {
        String name = bundled(phase);
        if (name == null) {
            return null;
        }
        Captured c;
        try (InputStream raw = BossArena.class.getResourceAsStream(name)) {
            c = raw == null ? null : readStream(raw, name, phase);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[BossSim] could not read the bundled boss capture {}", name, e);
            return null;
        }
        if (c == null || !phase.equals(c.phase)) {
            LOGGER.error("[BossSim] the bundled boss capture {} is missing or not {} - using the built-in plan", name, phase);
            return null;
        }
        return c;
    }

    /** {@code filePhase}: the phase a version-1 file (no phase of its own) is taken for. */
    private static Captured readStream(InputStream raw, String p, String filePhase) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw, 1 << 16)))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            int version = "K5BA".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)) ? in.readInt() : -1;
            if (version != 1 && version != 2) {
                return null;
            }
            String phase = version == 2 ? in.readUTF() : filePhase;
            int x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt(), w = in.readInt(), h = in.readInt(), d = in.readInt();
            int n = in.readInt();
            BlockState[] states = new BlockState[n];
            for (int i = 0; i < n; i++) {
                String s = in.readUTF();
                BlockState st;
                try {
                    st = BossBlocks.of(s);
                } catch (IllegalArgumentException e) {
                    st = BossBlocks.AIR;
                }
                states[i] = st;
            }
            short[] cells = new short[w * h * d];
            int i = 0;
            while (i < cells.length) {
                int run = in.readInt();
                short v = in.readShort();
                java.util.Arrays.fill(cells, i, Math.min(cells.length, i + run), v);
                i += run;
            }
            states = undoDioriteGlass(x0, y0, z0, w, d, states, cells);
            return new Captured(phase, x0, y0, z0, w, h, d, states, cells);
        }
    }
}
