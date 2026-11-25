package com.killer560.hub.foraging;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Reach classes for {@link SweepRule#plan(long[], int, java.util.function.LongToIntFunction)} (optimal-blocks-reachable-1124,
 * killer560 2026-11-24: "all need to be touching air and easy to break not these very edges that are super hard to reach").
 * A log is open when a face touches a block with no collision shape that is not a log of the tree. A ground spot is two
 * free blocks over a block that is no log and no leaves (so never a branch), and a log is easy when an eye 1.62 above a
 * ground spot sees a point of an open face within 4.5 blocks with nothing that collides in between. Run only in a plan
 * rebuild; at most {@link #CLIP_BUDGET} raycasts a tree, a log not settled by then counts as out of reach.
 */
final class SweepReach {

    static final int NEAR = 0, GROUND = 1, HARD = 2, CLOSED = 3;
    private static final double REACH = 4.5, EYE = 1.62, PAD = REACH + 0.87;
    private static final int CLIP_BUDGET = 3000;
    private static final Direction[] DIRS = Direction.values();
    private static final double[][] FACE_PTS = {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}};

    private SweepReach() {
    }

    /** The class of every log of {@code tree}; {@code me} (his block, or null for a tree he is not at) makes class NEAR. */
    static Long2IntOpenHashMap classes(Minecraft mc, LongOpenHashSet tree, BlockPos me) {
        ClientLevel lv = mc.level;
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (long c : tree) {
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
        }
        LongArrayList near = new LongArrayList(), far = new LongArrayList();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = Math.max(y0 - 6, lv.getMinY() + 1); y <= Math.min(y1 + 3, lv.getMaxY() - 1); y++) {
            for (int x = x0 - 5; x <= x1 + 5; x++) {
                for (int z = z0 - 5; z <= z1 + 5; z++) {
                    LevelChunkSection sec = lv.getChunk(x >> 4, z >> 4).getSection(lv.getSectionIndex(y));
                    if ((y & 15) != 0 && sec.hasOnlyAir()) {
                        continue;   // no floor inside an empty section
                    }
                    if (free(lv, tree, m.set(x, y, z)) && free(lv, tree, m.set(x, y + 1, z)) && floor(lv, m.set(x, y - 1, z))) {
                        boolean n = me != null && Math.abs(x - me.getX()) <= 2 && Math.abs(z - me.getZ()) <= 2 && Math.abs(y - me.getY()) <= 1;
                        (n ? near : far).add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        Long2IntOpenHashMap out = new Long2IntOpenHashMap(tree.size());
        int[] budget = {CLIP_BUDGET};
        for (long c : tree) {
            int open = 0;
            for (Direction d : DIRS) {
                open |= free(lv, tree, m.set(BlockPos.getX(c) + d.getStepX(), BlockPos.getY(c) + d.getStepY(), BlockPos.getZ(c) + d.getStepZ())) ? 1 << d.ordinal() : 0;
            }
            out.put(c, open == 0 ? CLOSED : sees(mc, c, open, near, budget) ? NEAR : sees(mc, c, open, far, budget) ? GROUND : HARD);
        }
        return out;
    }

    private static boolean free(ClientLevel lv, LongOpenHashSet tree, BlockPos p) {
        return !tree.contains(p.asLong()) && lv.getBlockState(p).getCollisionShape(lv, p).isEmpty();
    }

    private static boolean floor(ClientLevel lv, BlockPos p) {
        BlockState s = lv.getBlockState(p);
        return SweepRule.kind(s.getBlock()) < 0 && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS) && !s.getCollisionShape(lv, p).isEmpty();
    }

    /** True when an eye over one of {@code stands} sees a point of an open face of {@code c} within reach. */
    private static boolean sees(Minecraft mc, long c, int open, LongArrayList stands, int[] budget) {
        double cx = BlockPos.getX(c) + 0.5, cy = BlockPos.getY(c) + 0.5, cz = BlockPos.getZ(c) + 0.5;
        for (int i = 0; i < stands.size(); i++) {
            long s = stands.getLong(i);
            double ex = BlockPos.getX(s) + 0.5, ey = BlockPos.getY(s) + EYE, ez = BlockPos.getZ(s) + 0.5;
            if ((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy) + (ez - cz) * (ez - cz) > PAD * PAD) {
                continue;
            }
            Vec3 eye = new Vec3(ex, ey, ez);
            for (Direction d : DIRS) {
                double nx = d.getStepX(), ny = d.getStepY(), nz = d.getStepZ();
                double fx = cx + nx * 0.5, fy = cy + ny * 0.5, fz = cz + nz * 0.5;
                if ((open & 1 << d.ordinal()) == 0 || (ex - fx) * nx + (ey - fy) * ny + (ez - fz) * nz <= 0) {
                    continue;   // closed, or faces away from this eye
                }
                for (double[] o : FACE_PTS) {
                    double px = fx + (nx == 0 ? o[0] : 0), py = fy + (ny == 0 ? (nx == 0 ? o[1] : o[0]) : 0), pz = fz + (nz == 0 ? o[1] : 0);
                    if ((ex - px) * (ex - px) + (ey - py) * (ey - py) + (ez - pz) * (ez - pz) > REACH * REACH) {
                        continue;
                    }
                    if (--budget[0] < 0) {
                        return false;
                    }
                    HitResult h = mc.level.clip(new ClipContext(eye, new Vec3(px - nx * 0.05, py - ny * 0.05, pz - nz * 0.05),
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                    if (h.getType() == HitResult.Type.BLOCK && ((BlockHitResult) h).getBlockPos().asLong() == c) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
