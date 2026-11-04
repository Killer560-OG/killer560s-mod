package com.killer560.hub.livemap.predict;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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
 * </ul>
 * Client thread only.
 */
final class PredictClip {

    private PredictClip() {
    }

    /**
     * The block hit from {@code from} to {@code to}, or null for a miss or a ray that left the box [minX..maxX] x
     * [minZ..maxZ] (block coordinates, inclusive) in its direction of travel.
     */
    static BlockHitResult clip(Level level, ClipContext ctx, Vec3 from, Vec3 to, int minX, int maxX, int minZ, int maxZ) {
        if (from.equals(to)) {
            return null;
        }
        double toX = Mth.lerp(-1.0E-7, to.x, from.x);
        double toY = Mth.lerp(-1.0E-7, to.y, from.y);
        double toZ = Mth.lerp(-1.0E-7, to.z, from.z);
        double fromX = Mth.lerp(-1.0E-7, from.x, to.x);
        double fromY = Mth.lerp(-1.0E-7, from.y, to.y);
        double fromZ = Mth.lerp(-1.0E-7, from.z, to.z);
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
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(); // a hit keeps it, as vanilla's does
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        boolean first = true;
        while (true) {
            if (!first) {
                if (!(tx <= 1.0 || ty <= 1.0 || tz <= 1.0)) {
                    return null;
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
                return null;
            }
            pos.set(x, y, z);
            BlockState state;
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
            state = chunk.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            VoxelShape shape = ctx.getBlockShape(state, level, pos);
            BlockHitResult hit = level.clipWithInteractionOverride(from, to, pos, shape, state);
            if (hit != null) {
                return hit;
            }
        }
    }
}
