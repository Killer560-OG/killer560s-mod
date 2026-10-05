package com.killer560.hub.secretwaypoints;

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
 * Finds the crypts and princes in one room by their blocks.
 *
 * <p>killer560, 2026-10-05: "add crypt and prince waypoints as toggleables for secret waypoints". Neither can come
 * from where the secret waypoints come from. The room database's {@code crypts} is a COUNT, with no positions, and
 * so is every other room file he has installed (Odin, quoi, rsm, devonian). And neither exists as an entity until
 * it is opened: the Crypt Undead and the Prince are both spawned BY the Superboom that opens their tomb
 * (hypixelskyblock.minecraft.wiki, Crypt Undead and Prince). So the only thing in the world to point at before a
 * crypt is opened is the tomb itself.
 *
 * <p><b>A crypt</b> is the tomb's lid: a horizontal run of smooth stone slabs, mostly bottom slabs, at least two
 * wide both ways, 6 to 20 slabs, filling at least three quarters of its own bounding box. That is his own reading
 * ("a section of smooth stone slabs ... those are crypts", 2026-10-01, the rule the sim's Superboom uses),
 * tightened against the data: run over all 134 room captures, which were taken in real Hypixel dungeons, crypts
 * plus princes found this way equal the room database's crypt count exactly in 112 rooms. "Any run of four or more"
 * gets 82. The prince is one of the database's crypts: Red Blue, Sloth and Leaves (database 1) have no lid and one
 * prince, Chambers (6) has five lids and a prince. The misses are listed in
 * {@code killer560s-mod-logs/crypt-waypoints.md}: some decorative slab panels (Hallway is the worst, thirteen 2x3
 * panels for one crypt), a few tombs of another shape, and Pirate's three gold-on-slab spots, which match the prince
 * rule without being princes as far as the count says.
 *
 * <p><b>A prince</b> is the golden crypt: a gold block with a smooth stone slab beside it, or a sea lantern walled
 * in by polished andesite on all four sides - the two shapes {@code roomsim.SimPrince} was given by him. A slab run
 * that touches a prince's gold is the prince, not an ordinary crypt as well.
 *
 * <p>One answer for the sim and for Hypixel: {@code SimPrince} asks {@link #isPrinceCrown} too, and this reads the
 * world the client sees, wherever that world came from.
 */
public final class CryptScanner {

    /** What was found: a crypt lid or a prince, as the blocks it is made of. */
    public enum Type { CRYPT, PRINCE }

    /**
     * One crypt or prince.
     *
     * @param anchor the smallest member block (x, then z, then y) - stable from scan to scan, so it is the key
     * @param blocks every block it is made of; when they are gone, it has been opened
     */
    public record Found(Type type, BlockPos anchor, List<BlockPos> blocks,
                        int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    static final int MIN_LID = 6;
    static final int MAX_LID = 20;

    private CryptScanner() {
    }

    /** A bottom smooth stone slab - what a crypt lid is made of. */
    static boolean isLidSlab(BlockState state) {
        return state.is(Blocks.SMOOTH_STONE_SLAB) && state.hasProperty(SlabBlock.TYPE)
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * Whether this block is a prince's crown: a gold block with a smooth stone slab beside it, or a sea lantern
     * with polished andesite on all four sides. See {@code SimPrince} for how each shape was found.
     */
    public static boolean isPrinceCrown(Level level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if (state.is(Blocks.GOLD_BLOCK)) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(at.relative(dir)).is(Blocks.SMOOTH_STONE_SLAB)) {
                    return true;
                }
            }
            return false;
        }
        if (state.is(Blocks.SEA_LANTERN)) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!level.getBlockState(at.relative(dir)).is(Blocks.POLISHED_ANDESITE)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Whether a member block of a {@link Found} is still standing. */
    static boolean stillThere(Level level, Type type, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (type == Type.CRYPT) {
            return state.is(Blocks.SMOOTH_STONE_SLAB);
        }
        return state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.POLISHED_ANDESITE);
    }

    /**
     * Every crypt and prince inside {@code [minX, minZ, maxX, maxZ]}, at any height.
     *
     * <p>Chunk sections whose palette cannot hold a slab, gold or sea lantern are skipped, the same treatment as
     * the lever scan; only chunks the client already has are read.
     */
    public static List<Found> scan(Level level, int[] bounds) {
        List<Found> out = new ArrayList<>();
        if (level == null || bounds == null) {
            return out;
        }
        Set<BlockPos> slabs = new HashSet<>();
        Set<BlockPos> bottoms = new HashSet<>();
        List<BlockPos> crowns = new ArrayList<>();
        for (int cx = bounds[0] >> 4; cx <= (bounds[2] >> 4); cx++) {
            for (int cz = bounds[1] >> 4; cz <= (bounds[3] >> 4); cz++) {
                if (!level.hasChunk(cx, cz)) {
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
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(st ->
                            st.is(Blocks.SMOOTH_STONE_SLAB) || st.is(Blocks.GOLD_BLOCK) || st.is(Blocks.SEA_LANTERN))) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int ly = 0; ly < 16; ly++) {
                                BlockState state = section.getBlockState(x & 15, ly, z & 15);
                                if (state.is(Blocks.SMOOTH_STONE_SLAB)) {
                                    BlockPos at = new BlockPos(x, baseY + ly, z);
                                    slabs.add(at);
                                    if (isLidSlab(state)) {
                                        bottoms.add(at);
                                    }
                                } else if (state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.SEA_LANTERN)) {
                                    BlockPos at = new BlockPos(x, baseY + ly, z);
                                    if (isPrinceCrown(level, at)) {
                                        crowns.add(at);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Princes: one per connected group of crown blocks, with a lantern's andesite ring as part of it.
        Set<BlockPos> princeBlocks = new HashSet<>();
        for (BlockPos c : crowns) {
            princeBlocks.add(c);
            if (level.getBlockState(c).is(Blocks.SEA_LANTERN)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos ring = c.offset(dx, 0, dz);
                        if (level.getBlockState(ring).is(Blocks.POLISHED_ANDESITE)) {
                            princeBlocks.add(ring);
                        }
                    }
                }
            }
        }
        for (List<BlockPos> group : groups(princeBlocks, false)) {
            out.add(found(Type.PRINCE, group));
        }

        // Crypts: lid-shaped slab runs that do not belong to a prince.
        for (List<BlockPos> run : groups(slabs, true)) {
            Found f = found(Type.CRYPT, run);
            int w = f.maxX() - f.minX() + 1;
            int d = f.maxZ() - f.minZ() + 1;
            int n = run.size();
            int bottom = 0;
            for (BlockPos p : run) {
                if (bottoms.contains(p)) {
                    bottom++;
                }
            }
            if (bottom * 2 <= n) {
                continue; // mostly top or double slabs: a ledge or a ceiling, not a lid
            }
            if (n < MIN_LID || n > MAX_LID || Math.min(w, d) < 2 || n * 4 < w * d * 3) {
                continue;
            }
            if (touchesAny(run, princeBlocks)) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static boolean touchesAny(List<BlockPos> run, Set<BlockPos> others) {
        if (others.isEmpty()) {
            return false;
        }
        for (BlockPos p : run) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (others.contains(p.offset(dx, dy, dz))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Connected groups: horizontal neighbours only for a slab lid (one layer), all six for a prince. */
    private static List<List<BlockPos>> groups(Set<BlockPos> cells, boolean horizontalOnly) {
        List<List<BlockPos>> out = new ArrayList<>();
        Set<BlockPos> left = new HashSet<>(cells);
        while (!left.isEmpty()) {
            BlockPos seed = left.iterator().next();
            left.remove(seed);
            List<BlockPos> group = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos here = queue.poll();
                group.add(here);
                for (Direction dir : horizontalOnly ? HORIZONTAL : Direction.values()) {
                    BlockPos next = here.relative(dir);
                    if (left.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            out.add(group);
        }
        return out;
    }

    private static Found found(Type type, List<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos anchor = null;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
            if (anchor == null || p.getX() < anchor.getX()
                    || (p.getX() == anchor.getX() && (p.getZ() < anchor.getZ()
                    || (p.getZ() == anchor.getZ() && p.getY() < anchor.getY())))) {
                anchor = p;
            }
        }
        return new Found(type, anchor, List.copyOf(blocks), minX, minY, minZ, maxX, maxY, maxZ);
    }
}
