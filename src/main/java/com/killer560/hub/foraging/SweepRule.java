package com.killer560.hub.foraging;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.function.LongPredicate;

/**
 * The Sweep rule (sweepoverlay-build-1124), pure so a test can drive it. A swing fells the hit log plus one log for every
 * Sweep threshold of the tree's kind that the player's Sweep stat reaches (the wiki's "Required Sweep" table, 20 entries a
 * kind), spreading outward from the hit log through all 26 neighbors, nearest layer first. Checked against his hand swings of
 * 11-23 on fig at Sweep 285 (every threshold reached, so 21 logs): the predicted 21 matched 20 to 21 of the first 21 logs the
 * server removed on three recorded trees. A thrown axe halves the count.
 */
public final class SweepRule {

    public static final int NONE = -1, FIG = 0, MANGROVE = 1, HELIX = 2;

    /** Sweep needed for the 1st..20th extra log. */
    private static final int[][] THRESHOLDS = {
            {7, 9, 11, 16, 21, 26, 34, 42, 50, 62, 74, 86, 102, 118, 134, 154, 174, 194, 214, 234},
            {50, 55, 60, 75, 90, 105, 130, 155, 180, 230, 280, 330, 405, 480, 555, 655, 755, 855, 955, 1055},
            {150, 160, 170, 200, 230, 260, 320, 380, 440, 540, 640, 740, 890, 1040, 1190, 1390, 1590, 1790, 1990, 2190}};

    private static final int[] D = {-1, 0, 1};

    private SweepRule() {
    }

    /** The tree kind a log block belongs to, or {@link #NONE}. */
    public static int kind(Block b) {
        if (b == Blocks.STRIPPED_SPRUCE_LOG || b == Blocks.STRIPPED_SPRUCE_WOOD) {
            return FIG;
        }
        if (b == Blocks.MANGROVE_LOG || b == Blocks.MANGROVE_WOOD) {
            return MANGROVE;
        }
        if (b == Blocks.STRIPPED_BIRCH_LOG || b == Blocks.STRIPPED_BIRCH_WOOD
                || b == Blocks.STRIPPED_MANGROVE_LOG || b == Blocks.STRIPPED_MANGROVE_WOOD) {
            return HELIX;
        }
        return NONE;
    }

    /** Logs one swing fells (the hit log included) at this Sweep stat; 0 for an unknown kind. */
    public static int logs(int kind, double sweep, boolean thrown) {
        if (kind < 0) {
            return 0;
        }
        int n = 1;
        for (int t : THRESHOLDS[kind]) {
            if (sweep >= t) {
                n++;
            }
        }
        return thrown ? Math.max(1, n / 2) : n;
    }

    /** Up to {@code max} positions reached from {@code seed} over the 26 neighbors that {@code same} accepts, in visit order. */
    public static long[] fill(long seed, int max, LongPredicate same) {
        LongArrayList out = new LongArrayList();
        if (max <= 0 || !same.test(seed)) {
            return out.toLongArray();
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        seen.add(seed);
        out.add(seed);
        for (int i = 0; i < out.size() && out.size() < max; i++) {
            BlockPos p = BlockPos.of(out.getLong(i));
            for (int dx : D) {
                for (int dy : D) {
                    for (int dz : D) {
                        long q = BlockPos.asLong(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                        if (out.size() < max && seen.add(q) && same.test(q)) {
                            out.add(q);
                        }
                    }
                }
            }
        }
        return out.toLongArray();
    }
}
