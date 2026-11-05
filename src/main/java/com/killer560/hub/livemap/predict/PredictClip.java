package com.killer560.hub.livemap.predict;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * {@code level.clip(new ClipContext(from, to, VISUAL, Fluid.NONE, player))} for {@link RoomPredictor}'s rays, with the
 * same answer and less work (fps-1104: the predictor's rays were the mod's largest per-tick cost in a dungeon, 95-perf
 * features: ~400 us and ~220 KB a tick at defaults, nearly all of it inside vanilla's clip).
 *
 * <p>The walk is {@code BlockGetter.traverseBlocks} step for step (javap, identical on 26.2 and 26.3: the same -1.0E-7
 * lerp of both ends, the same tie order between the axes), and a block that is not air is judged exactly as vanilla's
 * {@code clip} lambda judges it ({@code getBlockShape}, then {@code clipWithInteractionOverride} on the unlerped ends).
 * What it leaves out, each provably without effect:
 * <ul>
 * <li>Air: its visual shape is empty (AirBlock has no collision and {@code getVisualShape} is the collision shape), and
 * {@code VoxelShape.clip} on an empty shape is null, so vanilla's step returns null there. Skipped on {@code isAir()}.
 * <li>The fluid: {@code Fluid.NONE} never picks a fluid, so its shape is {@code Shapes.empty()} and its clip null; the
 * block answer always wins. Never read.
 * <li>{@code Level.getBlockState} is {@code isInValidBounds ? getChunk(x >> 4, z >> 4).getBlockState(pos) : VOID_AIR}
 * (javap, both versions); the chunk is looked up once per column the ray enters instead of once per block (the walk
 * never returns to a column it left, and nothing changes the world during it).
 * <li>{@code stop}: the caller's box of tiles a hit can count in. The walk never turns back on an axis, so once a block
 * lies beyond that box in the direction of travel every later one does too, and any hit there is thrown away by
 * {@link RoomPredictor}'s {@code record} - the ray ends as a miss, which the caller treats the same way.
 * <li>One {@link ClipContext} per sweep instead of per ray: its shape answer reads only the block and its collision
 * context, which {@code CollisionContext.of(player)} builds from where he stands, and he does not move during a sweep.
 * <li>No allocation (perfalloc-1105: ~550 B a ray, 159 KB a tick, nearly all of it {@code clipWithInteractionOverride}'s
 * {@code toAabbs()} list, moved boxes, Vec3s and hit results): the answer goes into this class's fields instead of a
 * {@link BlockHitResult}, each shape's boxes are read once ({@link #boxes}), and {@code VoxelShape.clip},
 * {@code AABB.clip}, {@code getDirection} and {@code clipPoint} are ported operation for operation (javap, identical on
 * 26.2 and 26.3). Their one other branch, a start point already inside the shape ({@code isFullWide}), is left to
 * vanilla whenever the test point lies within the shape's bounds.
 * </ul>
 * Client thread only.
 */
final class PredictClip {

    private PredictClip() {
    }

    /** The last hit (valid until the next {@link #clip}): block, point, face, inside flag. */
    static final BlockPos.MutableBlockPos HIT_POS = new BlockPos.MutableBlockPos();
    static double hitX;
    static double hitY;
    static double hitZ;
    static Direction hitDir;
    static boolean hitInside;

    /**
     * Whether a block is hit from {@code from} to {@code to}; false for a miss or a ray that left the box [minX..maxX] x
     * [minZ..maxZ] (block coordinates, inclusive) in its direction of travel. A hit is in {@link #HIT_POS} and the hit
     * fields: what {@code level.clip}'s {@link BlockHitResult} would hold.
     */
    static boolean clip(Level level, ClipContext ctx, Vec3 from, double toX0, double toY0, double toZ0, int minX, int maxX,
                        int minZ, int maxZ) {
        if (Double.compare(from.x, toX0) == 0 && Double.compare(from.y, toY0) == 0 && Double.compare(from.z, toZ0) == 0) {
            return false; // from.equals(to)
        }
        double toX = Mth.lerp(-1.0E-7, toX0, from.x);
        double toY = Mth.lerp(-1.0E-7, toY0, from.y);
        double toZ = Mth.lerp(-1.0E-7, toZ0, from.z);
        double fromX = Mth.lerp(-1.0E-7, from.x, toX0);
        double fromY = Mth.lerp(-1.0E-7, from.y, toY0);
        double fromZ = Mth.lerp(-1.0E-7, from.z, toZ0);
        int x = Mth.floor(fromX);
        int y = Mth.floor(fromY);
        int z = Mth.floor(fromZ);
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        int sx = Mth.sign(dx);
        int sy = Mth.sign(dy);
        int sz = Mth.sign(dz);
        double tdx = sx == 0 ? Double.MAX_VALUE : sx / dx;
        double tdy = sy == 0 ? Double.MAX_VALUE : sy / dy;
        double tdz = sz == 0 ? Double.MAX_VALUE : sz / dz;
        double tx = tdx * (sx > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
        double ty = tdy * (sy > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
        double tz = tdz * (sz > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
        BlockPos.MutableBlockPos pos = HIT_POS;
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        boolean first = true;
        while (true) {
            if (!first) {
                if (!(tx <= 1.0 || ty <= 1.0 || tz <= 1.0)) {
                    return false;
                }
                if (tx < ty) {
                    if (tx < tz) {
                        x += sx;
                        tx += tdx;
                    } else {
                        z += sz;
                        tz += tdz;
                    }
                } else if (ty < tz) {
                    y += sy;
                    ty += tdy;
                } else {
                    z += sz;
                    tz += tdz;
                }
            }
            first = false;
            if ((x > maxX && sx >= 0) || (x < minX && sx <= 0) || (z > maxZ && sz >= 0) || (z < minZ && sz <= 0)) {
                return false;
            }
            pos.set(x, y, z);
            if (!level.isInValidBounds(pos)) {
                continue; // VOID_AIR
            }
            int cx = x >> 4;
            int cz = z >> 4;
            if (chunk == null || cx != chunkX || cz != chunkZ) {
                chunk = level.getChunk(cx, cz);
                chunkX = cx;
                chunkZ = cz;
            }
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            // BlockGetter.clipWithInteractionOverride: the block shape's hit, taking the interaction shape's face when
            // that shape is hit nearer (BlockHitResult.withDirection keeps the point and the inside flag).
            if (!shapeClip(ctx.getBlockShape(state, level, pos), from, toX0, toY0, toZ0, pos)) {
                continue;
            }
            double bx = hitX;
            double by = hitY;
            double bz = hitZ;
            Direction face = hitDir;
            boolean inside = hitInside;
            if (shapeClip(state.getInteractionShape(level, pos), from, toX0, toY0, toZ0, pos)) {
                double ix = hitX + -from.x; // getLocation().subtract(from).lengthSqr()
                double iy = hitY + -from.y;
                double iz = hitZ + -from.z;
                double ox = bx + -from.x;
                double oy = by + -from.y;
                double oz = bz + -from.z;
                if (ix * ix + iy * iy + iz * iz < ox * ox + oy * oy + oz * oz) {
                    face = hitDir;
                }
            }
            hitX = bx;
            hitY = by;
            hitZ = bz;
            hitDir = face;
            hitInside = inside;
            return true;
        }
    }

    /** {@code shape.clip(from, to, pos)} into the hit fields (true for a hit), allocating nothing. */
    private static boolean shapeClip(VoxelShape shape, Vec3 from, double toX, double toY, double toZ, BlockPos pos) {
        if (shape.isEmpty()) {
            return false;
        }
        double vx = toX + -from.x; // to.subtract(from)
        double vy = toY + -from.y;
        double vz = toZ + -from.z;
        if (vx * vx + vy * vy + vz * vz < 1.0E-7) {
            return false;
        }
        double[] b = boxes(shape);
        double rx = from.x + vx * 0.001 - (double) pos.getX();
        double ry = from.y + vy * 0.001 - (double) pos.getY();
        double rz = from.z + vz * 0.001 - (double) pos.getZ();
        if (b[0] <= rx && rx <= b[3] && b[1] <= ry && ry <= b[4] && b[2] <= rz && rz <= b[5]) {
            // The test point may lie inside the shape (isFullWide): vanilla's own answer, Vec3s and all.
            BlockHitResult r = shape.clip(from, new Vec3(toX, toY, toZ), pos);
            if (r == null) {
                return false;
            }
            hitX = r.getLocation().x;
            hitY = r.getLocation().y;
            hitZ = r.getLocation().z;
            hitDir = r.getDirection();
            hitInside = r.isInside();
            return true;
        }
        // AABB.clip(toAabbs(), from, to, pos): every box moved to the block, the nearest face entered.
        scale = 1.0;
        dir = null;
        double px = pos.getX();
        double py = pos.getY();
        double pz = pos.getZ();
        for (int i = 6; i < b.length; i += 6) {
            boxDirection(b[i] + px, b[i + 1] + py, b[i + 2] + pz, b[i + 3] + px, b[i + 4] + py, b[i + 5] + pz,
                    from.x, from.y, from.z, vx, vy, vz);
        }
        if (dir == null) {
            return false;
        }
        double s = scale;
        hitX = from.x + s * vx;
        hitY = from.y + s * vy;
        hitZ = from.z + s * vz;
        hitDir = dir;
        hitInside = false;
        return true;
    }

    /** AABB.clip's running answer: its {@code scaleReference[0]} and direction. */
    private static double scale;
    private static Direction dir;

    /** {@code AABB.getDirection} for one moved box. */
    private static void boxDirection(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     double fx, double fy, double fz, double dx, double dy, double dz) {
        if (dx > 1.0E-7) {
            clipPoint(dx, dy, dz, minX, minY, maxY, minZ, maxZ, Direction.WEST, fx, fy, fz);
        } else if (dx < -1.0E-7) {
            clipPoint(dx, dy, dz, maxX, minY, maxY, minZ, maxZ, Direction.EAST, fx, fy, fz);
        }
        if (dy > 1.0E-7) {
            clipPoint(dy, dz, dx, minY, minZ, maxZ, minX, maxX, Direction.DOWN, fy, fz, fx);
        } else if (dy < -1.0E-7) {
            clipPoint(dy, dz, dx, maxY, minZ, maxZ, minX, maxX, Direction.UP, fy, fz, fx);
        }
        if (dz > 1.0E-7) {
            clipPoint(dz, dx, dy, minZ, minX, maxX, minY, maxY, Direction.NORTH, fz, fx, fy);
        } else if (dz < -1.0E-7) {
            clipPoint(dz, dx, dy, maxZ, minX, maxX, minY, maxY, Direction.SOUTH, fz, fx, fy);
        }
    }

    /** {@code AABB.clipPoint}. */
    private static void clipPoint(double da, double db, double dc, double begin, double minB, double maxB, double minC,
                                  double maxC, Direction newDir, double startA, double startB, double startC) {
        double t = (begin - startA) / da;
        double pb = startB + t * db;
        double pc = startC + t * dc;
        if (0.0 < t && t < scale && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc
                && pc < maxC + 1.0E-7) {
            scale = t;
            dir = newDir;
        }
    }

    /** Each shape's {@code toAabbs()}, read once: {bounds min xyz, bounds max xyz, then each box's min xyz, max xyz}.
     *  Shapes are immutable and shared by block states; capped, and cleared with the predictor. */
    private static final IdentityHashMap<VoxelShape, double[]> BOXES = new IdentityHashMap<>();
    private static final int MAX_SHAPES = 1024;

    private static double[] boxes(VoxelShape shape) {
        double[] b = BOXES.get(shape);
        if (b != null) {
            return b;
        }
        List<AABB> list = shape.toAabbs();
        b = new double[6 + 6 * list.size()];
        b[0] = b[1] = b[2] = Double.POSITIVE_INFINITY;
        b[3] = b[4] = b[5] = Double.NEGATIVE_INFINITY;
        int i = 6;
        for (AABB a : list) {
            b[i++] = a.minX;
            b[i++] = a.minY;
            b[i++] = a.minZ;
            b[i++] = a.maxX;
            b[i++] = a.maxY;
            b[i++] = a.maxZ;
            b[0] = Math.min(b[0], a.minX);
            b[1] = Math.min(b[1], a.minY);
            b[2] = Math.min(b[2], a.minZ);
            b[3] = Math.max(b[3], a.maxX);
            b[4] = Math.max(b[4], a.maxY);
            b[5] = Math.max(b[5], a.maxZ);
        }
        if (BOXES.size() >= MAX_SHAPES) {
            BOXES.clear();
        }
        BOXES.put(shape, b);
        return b;
    }

    /** Drops the shape cache (a new floor or world). */
    static void reset() {
        BOXES.clear();
    }
}
