package com.killer560.hub.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What the walker reads from the WORLD instead of from the graph's heights (killer560, 2026-10-11: "the path it follows
 * is typically a hair higher than where the player actually runs so they are just jumping the whole time"). SkyHanni's
 * graph positions are the feet block, and the path points sit half a block above them, so a jump rule keyed on "the
 * path is higher than me" fired on every rise. Jumps now come from the block in front: only an obstacle taller than the
 * step height and no taller than this player's real jump is jumped.
 * <p>
 * The jump height is the player's OWN: jump strength attribute plus the Jump Boost effect, flown through vanilla's
 * gravity and drag - so a God Potion's Jump Boost (which Hypixel applies as the vanilla effect, or the client could not
 * jump higher at all) is sensed without reading any menu, and a ledge only a boosted jump clears is jumped only with it.
 */
final class WalkTerrain {

    /** Furthest drop the walker will choose to walk off (Skyblock has no fall damage, but a long fall looks wrong). */
    static final double MAX_DROP = 3.5;

    private WalkTerrain() {
    }

    /** Initial upward speed of this player's jump, as {@code LivingEntity.getJumpPower(1)} computes it. */
    static double jumpVelocity(LocalPlayer player) {
        return player.getAttributeValue(Attributes.JUMP_STRENGTH) + player.getJumpBoostPower();
    }

    /** How high this player's jump lifts the feet: vanilla's per-tick {@code y += v; v = (v - 0.08) * 0.98}. */
    static double jumpHeight(LocalPlayer player) {
        double v = jumpVelocity(player);
        double h = 0;
        for (int i = 0; i < 60 && v > 0; i++) {
            h += v;
            v = (v - 0.08) * 0.98;
        }
        return h;
    }

    /** Jump Boost level (1 = Jump Boost I), or 0. A God Potion includes it. */
    static int jumpBoostLevel(LocalPlayer player) {
        var effect = player.getEffect(MobEffects.JUMP_BOOST);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    static boolean climbable(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    /** A block a player collides with (a ladder or vine is climbed, not walked into). */
    static boolean solid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.is(BlockTags.CLIMBABLE) && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static double top(Level level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? pos.getY() : pos.getY() + shape.max(Direction.Axis.Y);
    }

    /**
     * How far above {@code feetY} the ground is in the column at {@code (x, z)}: the top of the solid stack starting at
     * the feet block. 0 or less means nothing to climb.
     */
    static double rise(Level level, double x, double z, double feetY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int by = (int) Math.floor(feetY + 0.01);
        double surface = feetY;
        for (int i = 0; i < 5; i++) {
            BlockPos p = new BlockPos(bx, by + i, bz);
            if (!solid(level, p)) {
                break;
            }
            surface = Math.max(surface, top(level, p));
        }
        return surface - feetY;
    }

    /** Room for a 1.8-tall player standing at {@code feetY} in that column. */
    static boolean headroom(Level level, double x, double z, double feetY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int lo = (int) Math.floor(feetY + 0.01);
        int hi = (int) Math.floor(feetY + 1.79);
        for (int y = lo; y <= hi; y++) {
            BlockPos p = new BlockPos(bx, y, bz);
            if (solid(level, p) && top(level, p) > feetY + 0.01) {
                return false;
            }
        }
        return true;
    }

    /** Distance down to the ground under {@code (x, z)} from {@code feetY}, or {@code Double.MAX_VALUE} past 6 blocks. */
    static double dropBelow(Level level, double x, double z, double feetY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int start = (int) Math.floor(feetY - 0.01);
        for (int y = start; y >= start - 6; y--) {
            BlockPos p = new BlockPos(bx, y, bz);
            if (solid(level, p) || climbable(level, p)) {
                return Math.max(0.0, feetY - top(level, p));
            }
        }
        return Double.MAX_VALUE;
    }

    /**
     * Can a player walk (stepping, jumping no more than {@code jumpHeight}, dropping no more than {@link #MAX_DROP})
     * in a straight line from feet {@code from} to {@code to}? Checks the center line and both shoulders (0.3 either
     * side) at body height, so a corner the player's box would catch is not called clear. A ladder on the way is
     * trusted (the graph put the path up it).
     */
    static boolean walkable(Level level, Vec3 from, Vec3 to, double jumpHeight) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.05) {
            return true;
        }
        double ux = dx / length;
        double uz = dz / length;
        double px = -uz * 0.3;
        double pz = ux * 0.3;
        double y = from.y;
        int steps = (int) Math.ceil(length / 0.4);
        for (int i = 1; i <= steps; i++) {
            double t = Math.min(length, i * 0.4);
            double x = from.x + ux * t;
            double z = from.z + uz * t;
            BlockPos feet = BlockPos.containing(x, y + 0.01, z);
            if (climbable(level, feet) || climbable(level, feet.above())) {
                return true;
            }
            double r = rise(level, x, z, y);
            if (r > 0.01) {
                if (r > jumpHeight - 0.05) {
                    return false;
                }
                y += r;
            } else {
                double drop = dropBelow(level, x, z, y);
                if (drop > MAX_DROP) {
                    return false;
                }
                y -= drop;
            }
            if (!headroom(level, x, z, y)) {
                return false;
            }
            for (int side = -1; side <= 1; side += 2) {
                double sx = x + px * side;
                double sz = z + pz * side;
                BlockPos body = BlockPos.containing(sx, y + 1.2, sz);
                if (solid(level, body)) {
                    return false;
                }
            }
        }
        // Arrived on a different level than the path point (under a bridge, on a roof): not the same place.
        return Math.abs(y - to.y) <= 1.6;
    }

    /** The nearest climbable block within {@code radius} horizontally of {@code pos}, from the feet up two blocks. */
    static BlockPos climbableNear(Level level, Vec3 pos, double radius) {
        int r = (int) Math.ceil(radius);
        BlockPos base = BlockPos.containing(pos);
        BlockPos best = null;
        double bestD = radius * radius;
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (!climbable(level, p)) {
                        continue;
                    }
                    double cx = p.getX() + 0.5 - pos.x;
                    double cz = p.getZ() + 0.5 - pos.z;
                    double d = cx * cx + cz * cz;
                    if (d <= bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The yaw that faces INTO a ladder (toward the wall it hangs on), or NaN for a climbable block with no facing
     * (vines, scaffolding) - then the caller faces the block itself.
     */
    static float ladderYaw(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(LadderBlock.FACING)) {
            return Float.NaN;
        }
        Direction into = state.getValue(LadderBlock.FACING).getOpposite();
        return (float) -Math.toDegrees(Math.atan2(into.getStepX(), into.getStepZ()));
    }
}
