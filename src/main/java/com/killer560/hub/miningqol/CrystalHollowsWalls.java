package com.killer560.hub.miningqol;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Crystal Hollows Walls (killer560, 2026-11-04: "add section walls (other mods have this)"): draws each Hollows zone's
 * boundary as translucent colored walls, in the Crystal Hollows only.
 *
 * <p>Bounds (shelved/mining/docs/CRYSTAL-HOLLOWS.md 3.2-3.3, not SkyHanni's looser box): the Hollows fill X 202..823,
 * Z 202..823, Y 31..188 (Skyblocker and QUOI hardcode them; the wiki's 621 x 157 x 621), so the block edges are 202 and 824,
 * 31 and 189. The four surface zones are the quadrants split at X and Z 513 (the Jungle low/low, Mithril Deposits
 * high X/low Z, Goblin Holdout low X/high Z, Precursor Remnants high/high), Magma Fields is the layer at or below Y 64
 * under the whole footprint, and the Crystal Nucleus is the wiki's box (465,64,465)-(561,188,561). The real biome edge is cave
 * terrain, not a plane (QUOI's comment), so a wall marks the nominal line.
 *
 * <p>Each zone draws only its own side of a shared line, a slab {@link #T} thick, so two zones never fight over one
 * plane. A wall is clipped to a cube round the player ("near only", per zone; 0 = whole wall) and drawn as a filled box,
 * depth-tested (no through-walls in either build).
 */
public final class CrystalHollowsWalls {

    public static final int MIN_OPACITY = 5;
    public static final int DEFAULT_OPACITY = 25;
    public static final int DEFAULT_NEAR = 96;
    /** Near-only choices a cycling button steps through (0 = always). */
    public static final int[] NEAR_STEPS = {0, 32, 64, 96, 128, 192, 256};

    /** Block edges of the Hollows (inclusive blocks 202..823, 31..188). */
    public static final double X0 = 202, X1 = 824, Z0 = 202, Z1 = 824, Y0 = 31, Y1 = 189;
    /** The line between the quadrants, and the top of the Magma Fields layer. */
    public static final double MID = 513, MAGMA_TOP = 64;
    /** The Nucleus box's block edges. */
    public static final double NX0 = 465, NX1 = 562, NZ0 = 465, NZ1 = 562;
    /** Wall thickness. */
    public static final double T = 0.1;

    public enum Zone {
        JUNGLE("Jungle", "Jungle", 0x55FF55),
        MITHRIL("Mithril Deposits", "Mithril", 0x00D0D0),
        GOBLIN("Goblin Holdout", "Goblin", 0xFFAA00),
        PRECURSOR("Precursor Remnants", "Precursor", 0xAA55FF),
        MAGMA("Magma Fields", "Magma", 0xFF4422),
        NUCLEUS("Crystal Nucleus", "Nucleus", 0xFFFFFF);

        public final String label;
        /** Config key part. */
        public final String key;
        public final int defaultColor;

        Zone(String label, String key, int defaultColor) {
            this.label = label;
            this.key = key;
            this.defaultColor = defaultColor;
        }
    }

    /** One wall as drawn: its zone, the (clipped) slab and its RGBA. */
    public record Wall(Zone zone, AABB box, float[] rgba) {
    }

    private static volatile List<Wall> lastDrawn = List.of();
    private static volatile long frames = 0;

    private CrystalHollowsWalls() {
    }

    public static int[] defaultColors() {
        int[] a = new int[Zone.values().length];
        for (Zone z : Zone.values()) {
            a[z.ordinal()] = z.defaultColor;
        }
        return a;
    }

    public static int[] defaultFill(int v) {
        int[] a = new int[Zone.values().length];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** The unclipped slabs of one zone. */
    public static List<AABB> slabs(Zone z) {
        List<AABB> out = new ArrayList<>();
        switch (z) {
            case JUNGLE -> quadrant(out, X0, MID, Z0, MID, true, true);
            case MITHRIL -> quadrant(out, MID, X1, Z0, MID, false, true);
            case GOBLIN -> quadrant(out, X0, MID, MID, Z1, true, false);
            case PRECURSOR -> quadrant(out, MID, X1, MID, Z1, false, false);
            case MAGMA -> {
                out.add(new AABB(X0, Y0, Z0, X0 + T, MAGMA_TOP, Z1));
                out.add(new AABB(X1 - T, Y0, Z0, X1, MAGMA_TOP, Z1));
                out.add(new AABB(X0, Y0, Z0, X1, MAGMA_TOP, Z0 + T));
                out.add(new AABB(X0, Y0, Z1 - T, X1, MAGMA_TOP, Z1));
                out.add(new AABB(X0, MAGMA_TOP - T, Z0, X1, MAGMA_TOP, Z1)); // the layer's top
            }
            case NUCLEUS -> {
                out.add(new AABB(NX0 - T, MAGMA_TOP, NZ0 - T, NX0, Y1, NZ1 + T));
                out.add(new AABB(NX1, MAGMA_TOP, NZ0 - T, NX1 + T, Y1, NZ1 + T));
                out.add(new AABB(NX0, MAGMA_TOP, NZ0 - T, NX1, Y1, NZ0));
                out.add(new AABB(NX0, MAGMA_TOP, NZ1, NX1, Y1, NZ1 + T));
                out.add(new AABB(NX0 - T, Y1, NZ0 - T, NX1 + T, Y1 + T, NZ1 + T));
            }
        }
        return out;
    }

    /** A surface quadrant: its two outer walls on the Hollows' edge and its two inner walls on its own side of the line. */
    private static void quadrant(List<AABB> out, double x0, double x1, double z0, double z1, boolean lowX, boolean lowZ) {
        double y0 = MAGMA_TOP;
        // The inner walls stop at the Nucleus box: the quadrant keeps only the part outside its footprint.
        double iz0 = lowZ ? z0 : NZ1, iz1 = lowZ ? NZ0 : z1, ix0 = lowX ? x0 : NX1, ix1 = lowX ? NX0 : x1;
        if (lowX) {
            out.add(new AABB(X0, y0, z0, X0 + T, Y1, z1));
            out.add(new AABB(MID - T, y0, iz0, MID, Y1, iz1));
        } else {
            out.add(new AABB(X1 - T, y0, z0, X1, Y1, z1));
            out.add(new AABB(MID, y0, iz0, MID + T, Y1, iz1));
        }
        if (lowZ) {
            out.add(new AABB(x0, y0, Z0, x1, Y1, Z0 + T));
            out.add(new AABB(ix0, y0, MID - T, ix1, Y1, MID));
        } else {
            out.add(new AABB(x0, y0, Z1 - T, x1, Y1, Z1));
            out.add(new AABB(ix0, y0, MID, ix1, Y1, MID + T));
        }
    }

    /** Every wall to draw for a player at (px,py,pz): each slab clipped to the zone's near-only cube. Pure, for the testkit. */
    public static List<Wall> walls(MiningQolConfig cfg, double px, double py, double pz) {
        List<Wall> out = new ArrayList<>();
        for (Zone z : Zone.values()) {
            int near = cfg.getChWallNear(z);
            int rgb = cfg.getChWallColor(z);
            float[] rgba = {((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f,
                    cfg.getChWallOpacity(z) / 100f};
            AABB cube = near <= 0 ? null : new AABB(px - near, py - near, pz - near, px + near, py + near, pz + near);
            for (AABB s : slabs(z)) {
                AABB b = s;
                if (cube != null) {
                    if (!s.intersects(cube)) {
                        continue;
                    }
                    b = new AABB(Math.max(s.minX, cube.minX), Math.max(s.minY, cube.minY), Math.max(s.minZ, cube.minZ),
                            Math.min(s.maxX, cube.maxX), Math.min(s.maxY, cube.maxY), Math.min(s.maxZ, cube.maxZ));
                }
                out.add(new Wall(z, b, rgba));
            }
        }
        return out;
    }

    static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (client.level == null || client.player == null || !cfg.isChWalls() || !MiningState.crystalHollows()) {
            lastDrawn = List.of();
            return;
        }
        var cam = com.killer560.hub.compat.McRender.cameraPos(context);
        List<Wall> walls = walls(cfg, cam.x, cam.y, cam.z);
        lastDrawn = walls;
        if (walls.isEmpty()) {
            return;
        }
        // Only each slab's two big faces, pulled in to 0.01 either side of its middle: the slab's own faces sit on
        // whole block coordinates and z-fought every block face they touched, and its thin rim faces did the same
        // (killer560, 2026-11-04; the Color Portal's fix, ScoreWarnings).
        for (Wall w : walls) {
            AABB b = w.box();
            float[] c = w.rgba();
            double dx = b.getXsize(), dy = b.getYsize(), dz = b.getZsize();
            net.minecraft.core.Direction face;
            AABB plane;
            if (dx <= dy && dx <= dz) {
                double m = (b.minX + b.maxX) / 2;
                plane = new AABB(m - 0.01, b.minY, b.minZ, m + 0.01, b.maxY, b.maxZ);
                face = net.minecraft.core.Direction.WEST;
            } else if (dz <= dy) {
                double m = (b.minZ + b.maxZ) / 2;
                plane = new AABB(b.minX, b.minY, m - 0.01, b.maxX, b.maxY, m + 0.01);
                face = net.minecraft.core.Direction.NORTH;
            } else {
                double m = (b.minY + b.maxY) / 2;
                plane = new AABB(b.minX, m - 0.01, b.minZ, b.maxX, m + 0.01, b.maxZ);
                face = net.minecraft.core.Direction.DOWN;
            }
            WorldRenderUtils.renderFilledFace(context, plane, face, c[0], c[1], c[2], c[3]);
            WorldRenderUtils.renderFilledFace(context, plane, face.getOpposite(), c[0], c[1], c[2], c[3]);
        }
        frames++;
    }

    // ---- testkit hooks

    /** What the last frame drew (empty when it drew nothing). */
    public static List<Wall> drawnLastFrame() {
        return lastDrawn;
    }

    /** The last frame's walls as "ZONE minX minY minZ maxX maxY maxZ r g b a" lines. */
    public static List<String> drawnLines() {
        List<String> out = new ArrayList<>();
        for (Wall w : lastDrawn) {
            AABB b = w.box();
            float[] c = w.rgba();
            out.add(String.format(java.util.Locale.ROOT, "%s %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f", w.zone().name(),
                    b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, c[0], c[1], c[2], c[3]));
        }
        return out;
    }

    /** Frames that drew at least one wall. */
    public static long framesDrawn() {
        return frames;
    }

    /** Hollows-bounds check for a position (the nominal box), for the testkit. */
    public static boolean inBounds(double x, double y, double z) {
        return x >= X0 && x < X1 && z >= Z0 && z < Z1 && y >= Y0 && y < Y1;
    }
}
