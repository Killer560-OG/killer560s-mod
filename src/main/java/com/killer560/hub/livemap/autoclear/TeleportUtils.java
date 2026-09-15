package com.killer560.hub.livemap.autoclear;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;

/**
 * Java port of QUOI's {@code TeleportUtils} (etherwarp voxel traversal, transmission prediction), the parts of
 * {@code VecUtils} it uses ({@code getLook}, {@code getDirection}, {@code getVisiblePoint}), {@code WorldUtils.etherwarpable}
 * and {@code CachedSphere}. Pure world reads - safe from the pathfinder worker threads the same way QUOI reads them.
 */
public final class TeleportUtils {

    public static final int PASSABLE = 1;
    public static final int BLOCKS_FEET = 2;

    private static volatile int[] blockFlags;

    private TeleportUtils() {
    }

    /** QUOI {@code Direction}: yaw/pitch in wrapped degrees plus distance. */
    public record Rotation(float yaw, float pitch, double distance) {
    }

    /** QUOI {@code RaycastResult}. */
    public record RaycastResult(boolean succeeded, BlockPos pos, BlockState state) {
        public static final RaycastResult NONE = new RaycastResult(false, null, null);
    }

    public static double eyeHeight(boolean sneak) {
        return sneak ? 1.27 : 1.62;
    }

    public static Vec3 getLook(float yaw, float pitch) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        return new Vec3(Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2, Math.sin(-pitch * 0.017453292f),
                Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2);
    }

    public static Rotation getDirection(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double dist = Math.sqrt(distXZ * distXZ + dy * dy);
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch), dist);
    }

    // ------------------------------------------------------------------------------------------- block flags

    private static int[] flags() {
        int[] f = blockFlags;
        if (f == null) {
            synchronized (TeleportUtils.class) {
                if (blockFlags == null) {
                    blockFlags = buildFlags();
                }
                f = blockFlags;
            }
        }
        return f;
    }

    private static int[] buildFlags() {
        int[] out = new int[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            Block block = state.getBlock();
            boolean passable = block instanceof AirBlock
                    || block instanceof FlowerBlock || block instanceof TallGrassBlock || block instanceof BushBlock
                    || block instanceof TallFlowerBlock || block instanceof ShortDryGrassBlock
                    || block instanceof TorchBlock || block instanceof RedstoneTorchBlock
                    || block instanceof TripWireBlock || block instanceof TripWireHookBlock
                    || block instanceof RailBlock || block instanceof FireBlock || block instanceof VineBlock
                    || block instanceof LiquidBlock || block instanceof SaplingBlock
                    || block instanceof CropBlock || block instanceof StemBlock
                    || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock
                    || block instanceof SugarCaneBlock || block instanceof MushroomBlock || block instanceof NetherWartBlock
                    || block instanceof RedStoneWireBlock || block instanceof ComparatorBlock || block instanceof RepeaterBlock
                    || block instanceof SmallDripleafBlock || block instanceof BigDripleafStemBlock
                    || block instanceof DoublePlantBlock || block instanceof LeverBlock || block instanceof SnowLayerBlock
                    || block instanceof BubbleColumnBlock || block instanceof GrowingPlantBlock
                    || block instanceof PistonHeadBlock || block instanceof DryVegetationBlock
                    || block instanceof ButtonBlock || block instanceof LanternBlock
                    || block instanceof SkullBlock || block instanceof WallSkullBlock
                    || block instanceof LadderBlock || block instanceof FlowerPotBlock || block instanceof WebBlock
                    || block instanceof NetherPortalBlock || block instanceof CandleBlock;
            boolean blocksFeet = block instanceof SkullBlock || block instanceof WallSkullBlock
                    || block instanceof FlowerPotBlock || block instanceof LadderBlock;
            int id = Block.getId(state);
            if (id >= 0 && id < out.length) {
                out[id] = (passable ? PASSABLE : 0) | (blocksFeet ? BLOCKS_FEET : 0);
            }
        }
        return out;
    }

    private static int flagsOf(BlockState state) {
        int[] f = flags();
        int id = Block.getId(state);
        return id >= 0 && id < f.length ? f[id] : 0;
    }

    private static final Set<Block> PASSABLE_BLOCKS = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.WATER, Blocks.LAVA, Blocks.TALL_GRASS, Blocks.SHORT_GRASS,
            Blocks.FERN, Blocks.LARGE_FERN, Blocks.DANDELION, Blocks.POPPY,
            Blocks.BLUE_ORCHID, Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP,
            Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY,
            Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.WITHER_ROSE, Blocks.SUNFLOWER,
            Blocks.TORCH, Blocks.WALL_TORCH, Blocks.REDSTONE_WIRE, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.SNOW, Blocks.VINE, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
            Blocks.SUGAR_CANE, Blocks.KELP, Blocks.CARROTS, Blocks.POTATOES, Blocks.WHEAT,
            Blocks.BEETROOTS, Blocks.SWEET_BERRY_BUSH, Blocks.DEAD_BUSH, Blocks.CANDLE);

    // ------------------------------------------------------------------------------------------- raycasts

    private static Level level() {
        return Minecraft.getInstance().level;
    }

    /** QUOI {@code WorldUtils.etherwarpable}. */
    public static boolean etherwarpable(BlockPos pos) {
        Level level = level();
        if (level == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if ((flagsOf(state) & PASSABLE) != 0) {
            return false;
        }
        double collisionTop = state.getCollisionShape(level, pos).max(Direction.Axis.Y);
        int feetY = pos.getY() + (int) Math.max(1.0, Math.ceil(collisionTop));
        int feet = flagsOf(level.getBlockState(new BlockPos(pos.getX(), feetY, pos.getZ())));
        if ((feet & PASSABLE) == 0 || (feet & BLOCKS_FEET) != 0) {
            return false;
        }
        int head = flagsOf(level.getBlockState(new BlockPos(pos.getX(), feetY + 1, pos.getZ())));
        return !((head & PASSABLE) == 0 || (head & BLOCKS_FEET) != 0);
    }

    public static RaycastResult getEtherPos(Vec3 from, float yaw, float pitch) {
        return getEtherPos(from, yaw, pitch, 61.0);
    }

    public static RaycastResult getEtherPos(Vec3 from, float yaw, float pitch, double distance) {
        Vec3 to = getLook(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch)).scale(distance).add(from);
        return traverseVoxels(from.x, from.y, from.z, to.x, to.y, to.z, true);
    }

    public static RaycastResult getTeleportPos(Vec3 from, float yaw, float pitch, double distance) {
        Vec3 look = getLook(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
        return predictTransmission(from.x, from.y, from.z, look.x, look.y, look.z, distance);
    }

    /** QUOI {@code getEtherwarpDirection} (Aton). */
    public static Rotation getEtherwarpDirection(Vec3 from, BlockPos to, double dist) {
        if (from.distanceToSqr(Vec3.atLowerCornerOf(to)) > (dist + 2) * (dist + 2)) {
            return null;
        }
        Vec3 visible = getVisiblePoint(from, to);
        return visible == null ? null : getDirection(from, visible);
    }

    private static final double[][] VISIBLE_OFFSETS = {
            {0.5, 1.0, 0.5}, {0.0, 0.5, 0.5}, {1.0, 0.5, 0.5}, {0.5, 0.5, 0.0}, {0.5, 0.5, 1.0}, {0.5, 0.0, 0.5},
            {0.0, 0.001, 0.001}, {0.0, 0.001, 0.999}, {0.0, 0.999, 0.001}, {0.0, 0.999, 0.999},
            {1.0, 0.001, 0.001}, {1.0, 0.001, 0.999}, {1.0, 0.999, 0.001}, {1.0, 0.999, 0.999},
            {0.001, 0.001, 0.0}, {0.001, 0.999, 0.0}, {0.999, 0.001, 0.0}, {0.999, 0.999, 0.0},
            {0.001, 0.001, 1.0}, {0.001, 0.999, 1.0}, {0.999, 0.001, 1.0}, {0.999, 0.999, 1.0},
            {0.001, 0.0, 0.001}, {0.001, 0.0, 0.999}, {0.999, 0.0, 0.001}, {0.999, 0.0, 0.999},
            {0.001, 1.0, 0.001}, {0.001, 1.0, 0.999}, {0.999, 1.0, 0.001}, {0.999, 1.0, 0.999}
    };

    /** QUOI {@code getVisiblePoint}: first of 30 sample points on the block the etherwarp ray actually lands on. */
    public static Vec3 getVisiblePoint(Vec3 from, BlockPos to) {
        for (double[] o : VISIBLE_OFFSETS) {
            double tx = to.getX() + o[0];
            double ty = to.getY() + o[1];
            double tz = to.getZ() + o[2];
            RaycastResult hit = traverseVoxels(from.x, from.y, from.z, tx, ty, tz, true);
            if (to.equals(hit.pos())) {
                return new Vec3(tx, ty, tz);
            }
        }
        return null;
    }

    /** QUOI {@code traverseVoxels} (unclambomb6): first solid block along the ray with etherwarp head/feet clearance. */
    public static RaycastResult traverseVoxels(double x0, double y0, double z0, double x1, double y1, double z1, boolean etherwarp) {
        Level level = level();
        if (level == null) {
            return RaycastResult.NONE;
        }
        double x = Math.floor(x0);
        double y = Math.floor(y0);
        double z = Math.floor(z0);
        double endX = Math.floor(x1);
        double endY = Math.floor(y1);
        double endZ = Math.floor(z1);
        double dirX = x1 - x0;
        double dirY = y1 - y0;
        double dirZ = z1 - z0;
        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);
        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;

        for (int iter = 0; iter < 1000; iter++) {
            mut.set(x, y, z);
            int cx = ((int) x) >> 4;
            int cz = ((int) z) >> 4;
            if (cx != lastChunkX || cz != lastChunkZ) {
                chunk = level.getChunk(cx, cz);
                lastChunkX = cx;
                lastChunkZ = cz;
            }
            if (chunk == null) {
                return RaycastResult.NONE;
            }
            BlockState state = chunk.getBlockState(mut);
            int id = Block.getId(state);
            boolean passable = (flagsOf(state) & PASSABLE) != 0;
            if ((etherwarp && !passable) || (!etherwarp && id != 0)) {
                BlockPos hitPos = mut.immutable();
                if (!etherwarp && passable) {
                    return new RaycastResult(false, hitPos, state);
                }
                double collisionTop = state.getCollisionShape(level, hitPos).max(Direction.Axis.Y);
                double clearanceBaseY = hitPos.getY() + Math.max(1.0, Math.ceil(collisionTop));
                mut.set(x, clearanceBaseY, z);
                int feet = flagsOf(chunk.getBlockState(mut));
                if ((feet & PASSABLE) == 0 || (feet & BLOCKS_FEET) != 0) {
                    return new RaycastResult(false, hitPos, state);
                }
                mut.set(x, clearanceBaseY + 1, z);
                int head = flagsOf(chunk.getBlockState(mut));
                if ((head & PASSABLE) == 0 || (head & BLOCKS_FEET) != 0) {
                    return new RaycastResult(false, hitPos, state);
                }
                return new RaycastResult(true, hitPos, state);
            }
            if (x == endX && y == endY && z == endZ) {
                return RaycastResult.NONE;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return RaycastResult.NONE;
    }

    /** QUOI {@code predictTransmission} (NoammAddons InstantTransmissionHelper / rsm EtherUtils / soshimee zph). */
    public static RaycastResult predictTransmission(double x0, double y0, double z0, double dx, double dy, double dz, double distance) {
        Level level = level();
        if (level == null) {
            return RaycastResult.NONE;
        }
        double x = Math.floor(x0);
        double y = Math.floor(y0);
        double z = Math.floor(z0);
        double rayX = dx * distance;
        double rayY = dy * distance;
        double rayZ = dz * distance;
        double x1 = x0 + rayX;
        double y1 = y0 + rayY;
        double z1 = z0 + rayZ;
        double endX = Math.floor(x1);
        double endY = Math.floor(y1);
        double endZ = Math.floor(z1);
        double dirX = x1 - x0;
        double dirY = y1 - y0;
        double dirZ = z1 - z0;
        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);
        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double invRayX = rayX != 0.0 ? 1.0 / rayX : Double.MAX_VALUE;
        double invRayY = rayY != 0.0 ? 1.0 / rayY : Double.MAX_VALUE;
        double invRayZ = rayZ != 0.0 ? 1.0 / rayZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        double lastX = x;
        double lastY = y;
        double lastZ = z;
        BlockState lastState = null;
        int stepCount = 0;

        for (int iter = 0; iter < 1000; iter++) {
            mut.set(x, y, z);
            int cx = ((int) x) >> 4;
            int cz = ((int) z) >> 4;
            if (cx != lastChunkX || cz != lastChunkZ) {
                chunk = level.getChunk(cx, cz);
                lastChunkX = cx;
                lastChunkZ = cz;
            }
            if (chunk == null) {
                return RaycastResult.NONE;
            }
            BlockState stateFeet = chunk.getBlockState(mut);
            boolean hitFeet = checkBlockCollision(level, mut, stateFeet, x0, y0, z0, invRayX, invRayY, invRayZ);
            mut.set(x, y + 1.0, z);
            BlockState stateHead = chunk.getBlockState(mut);
            boolean hitHead = checkBlockCollision(level, mut, stateHead, x0, y0, z0, invRayX, invRayY, invRayZ);
            if (hitFeet || hitHead) {
                return stepCount == 0
                        ? new RaycastResult(false, mut.immutable(), hitFeet ? stateFeet : stateHead)
                        : new RaycastResult(true, BlockPos.containing(lastX, lastY, lastZ), lastState);
            }
            if (x == endX && y == endY && z == endZ) {
                return new RaycastResult(true, BlockPos.containing(x, y, z), stateFeet);
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            lastState = stateFeet;
            stepCount++;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return RaycastResult.NONE;
    }

    private static boolean checkBlockCollision(Level level, BlockPos.MutableBlockPos mut, BlockState state,
                                               double x0, double y0, double z0, double invRayX, double invRayY, double invRayZ) {
        if (PASSABLE_BLOCKS.contains(state.getBlock())) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, mut);
        if (shape.isEmpty()) {
            return false;
        }
        AABB bounds = shape.bounds();
        if (bounds.maxX - bounds.minX >= 1.0 && bounds.maxY - bounds.minY >= 1.0 && bounds.maxZ - bounds.minZ >= 1.0) {
            return true;
        }
        double minX = mut.getX() + bounds.minX;
        double minY = mut.getY() + bounds.minY;
        double minZ = mut.getZ() + bounds.minZ;
        double maxX = mut.getX() + bounds.maxX;
        double maxY = mut.getY() + bounds.maxY;
        double maxZ = mut.getZ() + bounds.maxZ;
        double t1X = (minX - x0) * invRayX;
        double t2X = (maxX - x0) * invRayX;
        double tMin = Math.min(t1X, t2X);
        double tMax = Math.max(t1X, t2X);
        double t1Y = (minY - y0) * invRayY;
        double t2Y = (maxY - y0) * invRayY;
        tMin = Math.max(tMin, Math.min(t1Y, t2Y));
        tMax = Math.min(tMax, Math.max(t1Y, t2Y));
        double t1Z = (minZ - z0) * invRayZ;
        double t2Z = (maxZ - z0) * invRayZ;
        tMin = Math.max(tMin, Math.min(t1Z, t2Z));
        tMax = Math.min(tMax, Math.max(t1Z, t2Z));
        return tMax >= Math.max(0.0, tMin) && tMin <= 1.0;
    }

    // ------------------------------------------------------------------------------------------- CachedSphere

    private static final int SPHERE_RADIUS = 25;
    private static volatile long[] sphere;

    /** QUOI {@code BlockPos.nearbyBlocks(25f) { it.etherwarpable }.firstOrNull()}: closest etherwarpable block. */
    public static BlockPos nearestEtherwarpable(BlockPos center) {
        long[] table = sphere;
        if (table == null) {
            table = buildSphere();
            sphere = table;
        }
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (long packed : table) {
            mut.set(center.getX() + BlockPos.getX(packed), center.getY() + BlockPos.getY(packed), center.getZ() + BlockPos.getZ(packed));
            if (etherwarpable(mut)) {
                return mut.immutable();
            }
        }
        return null;
    }

    private static long[] buildSphere() {
        int r = SPHERE_RADIUS;
        java.util.List<long[]> list = new java.util.ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    int d = x * x + y * y + z * z;
                    if (d <= r * r) {
                        list.add(new long[]{d, BlockPos.asLong(x, y, z)});
                    }
                }
            }
        }
        list.sort(java.util.Comparator.comparingLong(a -> a[0]));
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i)[1];
        }
        return out;
    }
}
