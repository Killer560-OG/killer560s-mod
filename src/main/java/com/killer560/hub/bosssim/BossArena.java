package com.killer560.hub.bosssim;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The F7 boss room in the sim world, at Hypixel's own coordinates (the boss lives at x -8..144, y 0..255, z -8..160, on the
 * positive side of the dungeon's grid; the sim's clear is on the negative side, so both fit in one world).
 *
 * <p>Two sources, best first:
 * <ol>
 * <li><b>A capture of the real room</b> ({@code config/killer560/dungeons/sim/killer560smod-sim-bossarena.bin}), written by
 * {@link BossCapture} on Hypixel in an F7/M7 boss. Block for block, so every route and landing matches.</li>
 * <li><b>The built-in plan</b> ({@link #plan}): floors, walls, the P3 ring and core, gates, the terminal, lever and device
 * spots, the P4 and P5 floors, built from the coordinates this mod already reads Hypixel's boss by (the terminal, lever
 * and device positions, the section boxes, the gates, the Storm pillars and pads, the Necron platforms, the dragon and
 * relic spots). An approximation: every station and device sits exactly where Hypixel's does, the rooms around them are
 * plain boxes.</li>
 * </ol>
 * Reference mod ADM (undonecoffee/adm) ships its own capture of the room; it is All-Rights-Reserved and is NOT used here -
 * only facts about Hypixel's boss (coordinates, lines, timings) were read from it.
 *
 * <p>Blocks are written a slice per server tick ({@link #step}), so the integrated server keeps ticking while it builds.
 */
public final class BossArena {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");

    /** The capture's box (the same box ADM measured the room in: x -8..143, z -8..159, y 0..255). */
    public static final int X0 = -8, Y0 = 0, Z0 = -8, W = 152, H = 256, D = 168;

    /** P1's floor top block (Maxor), P2's (Storm), P3's ring (Goldor), P4's (Necron), P5's (the Wither King). */
    static final int P1_FLOOR = 220, P2_FLOOR = 168, P3_FLOOR = 106, P4_FLOOR = 62, P5_FLOOR = 4;

    private static final int BLOCKS_PER_TICK = 60_000;

    /** One fill of the plan: an inclusive box of one block state. */
    record Fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        long volume() {
            return (long) (Math.abs(x2 - x1) + 1) * (Math.abs(y2 - y1) + 1) * (Math.abs(z2 - z1) + 1);
        }
    }

    // ---- the slicer ----------------------------------------------------------------------------------------------
    private static List<Fill> jobs = List.of();
    private static int jobIndex;
    private static long jobCursor;
    private static Runnable onDone;
    private static Captured captured;
    private static long capturedCursor;
    private static String source = "none";

    private BossArena() {
    }

    /** "capture" or "plan": what the last build was made from (the testkit and the report read it). */
    public static String source() {
        return source;
    }

    public static Path capturePath() {
        return ModPaths.config("killer560smod-sim-bossarena.bin");
    }

    static boolean building() {
        return onDone != null;
    }

    /** Starts a build; {@code done} runs on the server thread once every block is written. */
    static void build(ServerLevel level, Runnable done) {
        captured = readCapture();
        capturedCursor = 0;
        jobs = plan(captured != null);
        jobIndex = 0;
        jobCursor = 0;
        onDone = done;
        source = captured != null ? "capture" : "plan";
        LOGGER.info("[BossSim] arena build from the {} ({} plan fill(s))", source, jobs.size());
    }

    /** One server tick of building. */
    static void step(ServerLevel level) {
        if (onDone == null) {
            return;
        }
        int budget = BLOCKS_PER_TICK;
        if (captured != null) {
            long total = (long) captured.w * captured.h * captured.d;
            while (budget > 0 && capturedCursor < total) {
                long i = capturedCursor++;
                int x = (int) (i % captured.w);
                int z = (int) ((i / captured.w) % captured.d);
                int y = (int) (i / ((long) captured.w * captured.d));
                BlockState s = captured.states[captured.cells[(int) i]];
                BlockPos pos = new BlockPos(captured.x0 + x, captured.y0 + y, captured.z0 + z);
                if (level.getBlockState(pos) != s) {
                    level.setBlock(pos, s, BossBlocks.FLAGS);
                    budget -= 4;
                } else {
                    budget--;
                }
            }
            if (capturedCursor < total) {
                return;
            }
        }
        while (budget > 0 && jobIndex < jobs.size()) {
            Fill f = jobs.get(jobIndex);
            int minX = Math.min(f.x1, f.x2), minY = Math.min(f.y1, f.y2), minZ = Math.min(f.z1, f.z2);
            int sx = Math.abs(f.x2 - f.x1) + 1, sy = Math.abs(f.y2 - f.y1) + 1, sz = Math.abs(f.z2 - f.z1) + 1;
            long vol = f.volume();
            while (budget > 0 && jobCursor < vol) {
                long i = jobCursor++;
                int x = minX + (int) (i % sx);
                int z = minZ + (int) ((i / sx) % sz);
                int y = minY + (int) (i / ((long) sx * sz));
                BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockState(pos) != f.state) {
                    level.setBlock(pos, f.state, BossBlocks.FLAGS);
                    budget -= 4;
                } else {
                    budget--;
                }
            }
            if (jobCursor >= vol) {
                jobIndex++;
                jobCursor = 0;
            }
        }
        if (jobIndex >= jobs.size()) {
            Runnable done = onDone;
            onDone = null;
            captured = null;
            done.run();
        }
    }

    // ---- the plan ------------------------------------------------------------------------------------------------

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
     * The built-in room. With a capture, only the parts every fight rewrites (gates, holes, levers, device blocks) are
     * re-laid on top, so a rebuild resets them; without one, the whole room.
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
            // The laser's column under Maxor.
            box(out, 73, P1_FLOOR + 1, 73, 73, P1_FLOOR + 1, 73, "minecraft:sea_lantern");
            // ---- P2, Storm (floor 168) -------------------------------------------------------------------------
            box(out, 20, P2_FLOOR, 5, 126, P2_FLOOR, 110, "minecraft:polished_andesite");
            walls(out, 19, P2_FLOOR, 4, 127, P2_FLOOR + 22, 111, "minecraft:stone_bricks");
            for (BossFight.Pillar p : BossFight.PILLARS) {
                // A diorite pillar hangs over its 7x7 square; the crush drops it to the floor.
                box(out, p.minX(), p.restBottom(), p.minZ(), p.minX() + 6, BossFight.PILLAR_TOP, p.minZ() + 6, "minecraft:diorite");
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
            for (int[] pl : BossFight.NECRON_PLATFORMS) {
                box(out, pl[0] - 3, P4_FLOOR, pl[1] - 3, pl[0] + 3, P4_FLOOR, pl[1] + 3, "minecraft:obsidian");
            }
            box(out, 53, P4_FLOOR, 113, 55, P4_FLOOR, 115, "minecraft:obsidian");        // P4 platform (53..56, 113..116)
            // ---- P5, the Wither King (floor 4) -----------------------------------------------------------------
            box(out, 4, P5_FLOOR, 28, 104, P5_FLOOR, 140, "minecraft:stone_bricks");
            walls(out, 3, P5_FLOOR, 27, 105, 44, 141, "minecraft:stone_bricks");
            for (int[] st : BossFight.DRAGON_STATUES) {
                box(out, st[0] - 1, P5_FLOOR + 1, st[2] - 1, st[0] + 1, st[1], st[2] + 1, "minecraft:stone_bricks");
            }
        }
        // ---- what every fight rewrites (also over a capture) -----------------------------------------------------
        // Maxor's floor over Storm: whole again (Maxor's death opens it).
        box(out, BossFight.P1_HOLE[0], P1_FLOOR, BossFight.P1_HOLE[1], BossFight.P1_HOLE[2], P1_FLOOR, BossFight.P1_HOLE[3],
                "minecraft:quartz_block");
        // Storm's floor over S1: whole again (Storm's death opens it).
        box(out, BossFight.P2_HOLE[0], P2_FLOOR, BossFight.P2_HOLE[1], BossFight.P2_HOLE[2], P2_FLOOR, BossFight.P2_HOLE[3],
                "minecraft:polished_andesite");
        for (BossFight.Pillar p : BossFight.PILLARS) {
            box(out, p.minX(), P2_FLOOR + 1, p.minZ(), p.minX() + 6, p.restBottom() - 1, p.minZ() + 6, "minecraft:air");
            box(out, p.minX(), p.restBottom(), p.minZ(), p.minX() + 6, BossFight.PILLAR_TOP, p.minZ() + 6, "minecraft:diorite");
        }
        for (int[] g : BossStations.GATES) {
            box(out, g[0], g[1], g[2], g[3], g[4], g[5], "minecraft:iron_block");
        }
        box(out, 52, P3_FLOOR + 1, 58, 57, P3_FLOOR + 6, 58, "minecraft:gold_block");  // the core entrance
        box(out, BossFight.CORE_HOLE[0], P3_FLOOR, BossFight.CORE_HOLE[1], BossFight.CORE_HOLE[2], P3_FLOOR,
                BossFight.CORE_HOLE[3], "minecraft:polished_blackstone_bricks");
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
        // Target (S4): the target wall z 50 (x 64..68, y 126..130) and a floor at 125 to shoot from.
        box(out, 62, 124, 50, 70, 132, 50, "minecraft:stone_bricks");
        box(out, 60, 125, 32, 67, 125, 37, "minecraft:stone_bricks");
        platform(out, 63, 125, 34, 4, 2);
    }

    // ---- the capture ---------------------------------------------------------------------------------------------

    /** A capture as read back: the box, its palette and one palette index per cell (x fastest, then z, then y). */
    record Captured(int x0, int y0, int z0, int w, int h, int d, BlockState[] states, short[] cells) {
    }

    /** The saved capture, or null when there is none (or it cannot be read - then the plan is used and it is logged). */
    static Captured readCapture() {
        Path p = capturePath();
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try (InputStream raw = Files.newInputStream(p);
             DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw, 1 << 16)))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!"K5BA".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)) || in.readInt() != 1) {
                LOGGER.warn("[BossSim] {} is not a boss capture (bad header) - using the built-in plan", p);
                return null;
            }
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
            return new Captured(x0, y0, z0, w, h, d, states, cells);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[BossSim] could not read the boss capture {} - using the built-in plan", p, e);
            return null;
        }
    }
}
