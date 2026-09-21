package com.killer560.hub.secrets;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;

/**
 * Builds Full Block's "Custom" hitboxes (killer560, 2026-09-21): every block type gets Width / Height / Length
 * sliders running from its normal vanilla size (0) to a full block along that axis (100). Axes are relative to the
 * block, not the world, so one set of sliders works whichever way it faces:
 * <ul>
 * <li><b>Attached</b> blocks (buttons, levers, wall skulls): Width runs side to side across the face, Height up and
 *     down across the face, Length out from the surface it is attached to. On a floor or ceiling the face lies flat,
 *     with Width perpendicular to the block's facing and Height along it - the same way vanilla lays those out.</li>
 * <li><b>Standing</b> blocks (chests, floor skulls): Height grows up from the floor, Width and Length grow evenly
 *     around the centre (Width side to side as you face it, Length front to back).</li>
 * </ul>
 * One instance per block type caches its shapes per orientation and only rebuilds them when a slider moves, so the
 * per-frame {@code getShape} calls cost an array read.
 */
public final class HitboxSizer {

    /** Slots 0-7 attached orientations ({@link #attachedIndex}), 8-17 standing orientations. */
    private final double baseW;
    private final double baseH;
    private final double baseL;
    private final VoxelShape[] cache = new VoxelShape[18];
    private int cacheKey = -1;

    /** @param w normal width, @param h normal height, @param l normal length - all in pixels (1/16 block). */
    public HitboxSizer(double w, double h, double l) {
        this.baseW = w / 16.0;
        this.baseH = h / 16.0;
        this.baseL = l / 16.0;
    }

    public enum Mount { FLOOR, CEILING, WALL }

    /** Attached block (button, lever, wall skull). {@code facing} is the direction the block points away from its
     *  support - for a wall block the support is on the opposite side. */
    public VoxelShape attached(Mount mount, Direction facing, int wPct, int hPct, int lPct) {
        int idx = attachedIndex(mount, facing);
        VoxelShape cached = lookup(idx, wPct, hPct, lPct);
        if (cached != null) {
            return cached;
        }
        double w = lerp(baseW, wPct), h = lerp(baseH, hPct), l = lerp(baseL, lPct);
        double w0 = 0.5 - w / 2, w1 = 0.5 + w / 2, h0 = 0.5 - h / 2, h1 = 0.5 + h / 2;
        boolean ew = facing.getAxis() == Direction.Axis.X;
        VoxelShape shape = switch (mount) {
            case FLOOR -> ew ? Shapes.box(h0, 0.0, w0, h1, l, w1) : Shapes.box(w0, 0.0, h0, w1, l, h1);
            case CEILING -> ew ? Shapes.box(h0, 1.0 - l, w0, h1, 1.0, w1) : Shapes.box(w0, 1.0 - l, h0, w1, 1.0, h1);
            case WALL -> switch (facing) {
                case NORTH -> Shapes.box(w0, h0, 1.0 - l, w1, h1, 1.0);
                case SOUTH -> Shapes.box(w0, h0, 0.0, w1, h1, l);
                case WEST -> Shapes.box(1.0 - l, h0, w0, 1.0, h1, w1);
                default -> Shapes.box(0.0, h0, w0, l, h1, w1);
            };
        };
        cache[idx] = shape;
        return shape;
    }

    /** Standing block (chest, floor skull). {@code facing} may be null for rotation-free blocks (skulls).
     *  {@code joinedSide} extends the box to the block edge on that side (double chests reaching their other half);
     *  null for none. */
    public VoxelShape standing(Direction facing, Direction joinedSide, int wPct, int hPct, int lPct) {
        int joined = joinedSide == null || joinedSide.getAxis() == Direction.Axis.Y ? 0 : 1 + joinedSide.get2DDataValue();
        int idx = 8 + (facing != null && facing.getAxis() == Direction.Axis.X ? 1 : 0) + 2 * joined;
        VoxelShape cached = lookup(idx, wPct, hPct, lPct);
        if (cached != null) {
            return cached;
        }
        double w = lerp(baseW, wPct), h = lerp(baseH, hPct), l = lerp(baseL, lPct);
        boolean ew = facing != null && facing.getAxis() == Direction.Axis.X;
        double xs = ew ? l : w, zs = ew ? w : l;
        double x0 = 0.5 - xs / 2, x1 = 0.5 + xs / 2, z0 = 0.5 - zs / 2, z1 = 0.5 + zs / 2;
        if (joinedSide != null) {
            switch (joinedSide) {
                case NORTH -> z0 = 0.0;
                case SOUTH -> z1 = 1.0;
                case WEST -> x0 = 0.0;
                case EAST -> x1 = 1.0;
                default -> { }
            }
        }
        VoxelShape shape = Shapes.box(x0, 0.0, z0, x1, h, z1);
        cache[idx] = shape;
        return shape;
    }

    private VoxelShape lookup(int idx, int wPct, int hPct, int lPct) {
        int key = clamp(wPct) | (clamp(hPct) << 8) | (clamp(lPct) << 16);
        if (key != cacheKey) {
            Arrays.fill(cache, null);
            cacheKey = key;
        }
        return cache[idx];
    }

    private static int attachedIndex(Mount mount, Direction facing) {
        boolean ew = facing.getAxis() == Direction.Axis.X;
        return switch (mount) {
            case FLOOR -> ew ? 1 : 0;
            case CEILING -> ew ? 3 : 2;
            case WALL -> switch (facing) {
                case NORTH -> 4;
                case SOUTH -> 5;
                case WEST -> 6;
                default -> 7;
            };
        };
    }

    private static double lerp(double base, int pct) {
        return base + (1.0 - base) * (clamp(pct) / 100.0);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
