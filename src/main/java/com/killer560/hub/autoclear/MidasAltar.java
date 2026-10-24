package com.killer560.hub.autoclear;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The King Midas altar: the gold-trimmed stone tomb in the middle of King Midas, which a Superboom has to open before the
 * room's mob spawns (killer560, 2026-10-19/20: "for king midas yellow room it needs to clear the room", "it still needs
 * to learn to superboom the crypt"; his screenshot: the central altar between the two red banners, at the end of the red
 * carpet, gold trim left and right and a skull on its front).
 *
 * <h2>Where it is (the room capture, {@code rooms/King_Midas.json}, taken in a real Hypixel dungeon)</h2>
 * A 5 x 7 stone-brick tomb at capture x 14..18, y 67..69, z 9..15 (the capture is 33 x 33 with a 1-block margin, so the
 * room's own x/z are one less: 13..17 and 8..14 of 0..30, centered on the room's x and on the carpet's axis). Its top is
 * a lid of 35 bottom smooth stone slabs at y 69 - the only smooth stone slabs in the whole capture. Gold blocks stand at
 * its four foot corners ((14|18, 67, 9|15)) and three more under the lid's front row at y 68 (x 15..17, z 14); the skull
 * (player_head, rotation 8) is at (16, 68, 15) on the front face, and the red carpet runs from the front out to z 20
 * (and a stub of it behind, z 7..8). The room database counts one crypt in King Midas: this is it.
 *
 * <h2>How it is found at run time</h2>
 * The room can be pasted at any rotation, so it is found by its blocks rather than by translated coordinates: the
 * largest horizontal run of bottom smooth stone slabs in the room's bounds that is bigger than any crypt lid
 * ({@code CryptScanner} stops at 20; this one is 35) and has a gold block directly under one of its slabs. Nothing else
 * in King Midas comes close, and the test is only ever asked inside King Midas.
 */
final class MidasAltar {

    static final String ROOM = "King Midas";

    /** Fewer slabs than this is an ordinary crypt lid (CryptScanner's MAX_LID is 20); the altar's lid is 35. */
    static final int MIN_SLABS = 21;

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private MidasAltar() {
    }

    static boolean isLidSlab(BlockState state) {
        return state.is(Blocks.SMOOTH_STONE_SLAB) && state.hasProperty(SlabBlock.TYPE)
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * The altar's lid inside {@code bounds} ({@code [minX, minZ, maxX, maxZ]}, LiveMapFeature.roomWorldBounds), or null
     * when there is none standing. Only chunk sections whose palette can hold a smooth stone slab are read.
     */
    static List<BlockPos> find(Level level, int[] bounds) {
        if (level == null || bounds == null) {
            return null;
        }
        Set<BlockPos> slabs = new HashSet<>();
        for (int cx = bounds[0] >> 4; cx <= (bounds[2] >> 4); cx++) {
            for (int cz = bounds[1] >> 4; cz <= (bounds[3] >> 4); cz++) {
                if (!com.killer560.hub.util.ClientChunks.isLoaded(level, cx, cz)) {
                    continue;
                }
                var chunk = level.getChunk(cx, cz);
                int x0 = Math.max(bounds[0], cx << 4);
                int x1 = Math.min(bounds[2], (cx << 4) + 15);
                int z0 = Math.max(bounds[1], cz << 4);
                int z1 = Math.min(bounds[3], (cz << 4) + 15);
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(MidasAltar::isLidSlab)) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int ly = 0; ly < 16; ly++) {
                                if (isLidSlab(section.getBlockState(x & 15, ly, z & 15))) {
                                    slabs.add(new BlockPos(x, baseY + ly, z));
                                }
                            }
                        }
                    }
                }
            }
        }
        List<BlockPos> best = null;
        Set<BlockPos> left = new HashSet<>(slabs);
        while (!left.isEmpty()) {
            BlockPos seed = left.iterator().next();
            left.remove(seed);
            List<BlockPos> run = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos here = queue.poll();
                run.add(here);
                for (Direction d : HORIZONTAL) {
                    BlockPos next = here.relative(d);
                    if (left.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            if (run.size() < MIN_SLABS || (best != null && run.size() <= best.size())) {
                continue;
            }
            boolean gold = false;
            for (BlockPos p : run) {
                if (level.getBlockState(p.below()).is(Blocks.GOLD_BLOCK)) {
                    gold = true;
                    break;
                }
            }
            if (gold) {
                best = run;
            }
        }
        return best;
    }

    /** How many of the lid's slabs are still standing. */
    static int standing(Level level, List<BlockPos> lid) {
        int n = 0;
        for (BlockPos p : lid) {
            if (level.getBlockState(p).is(Blocks.SMOOTH_STONE_SLAB)) {
                n++;
            }
        }
        return n;
    }
}
