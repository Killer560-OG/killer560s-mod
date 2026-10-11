package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The prince: the golden crypt that is already in the room.
 *
 * <p>killer560 (2026-09-29): "the prince is a crypt that only exists in specific rooms and it should already
 * be there not something you create", and, on where to look: "for instance red blue has one straight above
 * the lever about 10-20 blocks."
 *
 * <p>That was the whole of it. In his captured Red Blue the levers stand at y 69-70, and fourteen blocks above
 * them at y 84 there is a short line of gold block set between smooth stone slabs in an alcove - a plinth,
 * with open air above it and stone brick under it. Gold on its own is no use as a signature: it appears in
 * thirty of his 135 rooms and is decoration in nearly all of them, 132 blocks of it in King Midas alone. Gold
 * TOUCHING SMOOTH STONE SLAB is the plinth, and across the whole library it picks out four rooms and no
 * others - Chambers, Sloth, Red Blue and Market - which is exactly the "only exists in specific rooms" he
 * described.
 *
 * <p>So nothing is built here. The floor is scanned after it is pasted, the plinths in it are found, and each
 * becomes a prince that can be blown once for a crypt and a zombie. A floor with none of those four rooms on
 * it simply has no prince, which is correct rather than a failure.
 *
 * <p>An earlier version CREATED one by turning an ordinary crypt gold. That was wrong and he said so. It is
 * recorded here because the reasoning behind it - "there is no signature in the data" - was a failure to look
 * hard enough rather than a fact about the data.
 */
public final class SimPrince {

    /** The plinth's two blocks: a gold crown with a smooth stone slab beside it. */
    private static final net.minecraft.world.level.block.Block CROWN = Blocks.GOLD_BLOCK;
    private static final net.minecraft.world.level.block.Block PLINTH = Blocks.SMOOTH_STONE_SLAB;

    /**
     * The SECOND prince, and the second signature: a sea lantern walled in by polished andesite.
     *
     * <p>killer560, 2026-10-01, standing in Leaves: "in leaves the structure with the head on it by the chest
     * is a princel". His {@code /simwhere} put it at capture {@code (10, 82, 16)}, and decoding around that
     * gives a 3x3 of polished andesite at {@code y=82} with a sea lantern in the middle, a player head on its
     * side and a 3x3 of stone brick stairs under it. Nothing about it is gold, so the plinth rule above could
     * never have found it.
     *
     * <p>"Sea lantern with polished andesite on all FOUR horizontal sides" is the test, and it is as sharp as
     * the gold one: across all 135 captures it picks out exactly two rooms, Leaves and Stairs, both with the
     * full eight-block ring. Relaxing it to "andesite anywhere beside a lantern" picks 23 rooms and is
     * useless, which is the same trap the gold-alone rule fell into.
     *
     * <p>Stairs is an inference, not his word - he only named Leaves. It is reported in the build log by room,
     * so a wrong second room is visible rather than silently worth a bonus point.
     */
    private static final net.minecraft.world.level.block.Block LANTERN = Blocks.SEA_LANTERN;
    private static final net.minecraft.world.level.block.Block LANTERN_WALL = Blocks.POLISHED_ANDESITE;

    /**
     * The THIRD signature: a lid of full smooth stone ringed by bottom smooth stone slabs.
     *
     * <p>killer560, 2026-10-09, of a screenshot in Big Red Flag: "This structure in big red flag is a prince". The
     * structure is Big Red Flag's one crypt (the room database says 1): a 4x5 tomb at capture x 12..17, y 69..72,
     * z 22..26 whose top is a 2x3 of full {@code smooth_stone} (x 14..15, z 23..25, y 72) with a ring of bottom
     * smooth stone slabs round it and air above. Neither older rule could see it - no gold, no sea lantern - so the
     * sim's Superboom treated its slab ring as an ordinary crypt.
     *
     * <p>"A connected run of 2 to {@value #LID_MAX} full smooth stone blocks, air above every one, every horizontal
     * neighbour either another lid block or a bottom smooth stone slab" picks out exactly one structure in all 135
     * captures: this one (census 2026-10-11 over the shipped library). The lid and its slab ring are the prince's
     * blocks, so a Superboom aimed at any of them finds it.
     */
    private static final net.minecraft.world.level.block.Block LID = Blocks.SMOOTH_STONE;
    private static final int LID_MAX = 16;

    /** Which prince each block belongs to - a floor can hold more than one. */
    private static final Map<BlockPos, Integer> BLOCKS = new HashMap<>();

    /** Princes already blown, by index, so each scores once and only once. */
    private static final Set<Integer> CLAIMED = new HashSet<>();

    /** Where each prince is, for the build log and for tests. */
    private static final List<BlockPos> PRINCES = new ArrayList<>();

    /**
     * Whether the run has already taken its prince point.
     *
     * <p>killer560 (2026-09-29): "Multiple princes still the first one only gives 1 score." So the point is
     * once per RUN, not once per prince: a floor that happens to carry two of the four rooms that have one
     * does not pay twice. Each prince still opens - it is a crypt, and blowing it drops its zombie - but only
     * the first adds to the score.
     */
    private static boolean scored;

    private SimPrince() {
    }

    public static void reset() {
        BLOCKS.clear();
        CLAIMED.clear();
        PRINCES.clear();
        scored = false;
    }

    /** Where the first prince on this floor is, or null when the floor has none. */
    public static BlockPos position() {
        return PRINCES.isEmpty() ? null : PRINCES.get(0);
    }

    /** How many princes this floor has. Usually none, occasionally one. */
    public static int count() {
        return PRINCES.size();
    }

    /** How many blocks all of this floor's princes are made of, for the build log and for tests. */
    public static int size() {
        return BLOCKS.size();
    }

    /** Whether a block is part of a prince that has not been blown yet. */
    public static boolean isPrince(BlockPos pos) {
        Integer which = BLOCKS.get(pos);
        return which != null && !CLAIMED.contains(which);
    }

    /** Whether a block is part of any prince, blown or not - so its slabs are never also counted as a crypt. */
    public static boolean isPart(BlockPos pos) {
        return BLOCKS.containsKey(pos);
    }

    /** Every block of the prince containing {@code pos}, or nothing when it is not a prince block. */
    public static List<BlockPos> blocksOf(BlockPos pos) {
        Integer which = BLOCKS.get(pos);
        List<BlockPos> out = new ArrayList<>();
        if (which == null) {
            return out;
        }
        for (Map.Entry<BlockPos, Integer> e : BLOCKS.entrySet()) {
            if (e.getValue().equals(which)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * What else goes when the prince containing {@code pos} is opened: the player head attached to it, and the layer
     * directly under it.
     *
     * <p>killer560, 2026-10-09: "For princes make sure you get rid of the player head attached to the crypt and the
     * stair layer below that." Leaves' prince is a 3x3 of andesite round a sea lantern with a player head on its side
     * and a 3x3 of stone brick stairs under it; Big Red Flag's lid sits on a course of stairs and smooth stone. So: any
     * player head touching a prince block (all 26 neighbours - a wall head sits beside the ring, a floor head on top),
     * and every block directly under the prince's footprint at the layer below its lowest block, minus what a blast
     * never takes (air, block entities other than a head, bedrock, barriers, levers). Asked BEFORE anything is broken.
     * Not checked against Hypixel - his word, not the wiki's (the wiki's Prince page does not describe the tomb).
     */
    public static List<BlockPos> extrasOf(ServerLevel level, BlockPos pos) {
        List<BlockPos> blocks = blocksOf(pos);
        Set<BlockPos> out = new java.util.LinkedHashSet<>();
        if (blocks.isEmpty()) {
            return new ArrayList<>(out);
        }
        Set<BlockPos> own = new HashSet<>(blocks);
        int minY = Integer.MAX_VALUE;
        for (BlockPos b : blocks) {
            minY = Math.min(minY, b.getY());
        }
        for (BlockPos b : blocks) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = b.offset(dx, dy, dz);
                        if (!own.contains(n) && isHead(level.getBlockState(n))) {
                            out.add(n.immutable());
                        }
                    }
                }
            }
        }
        for (BlockPos b : blocks) {
            BlockPos under = new BlockPos(b.getX(), minY - 1, b.getZ());
            var state = level.getBlockState(under);
            if (own.contains(under) || state.isAir() || (state.hasBlockEntity() && !isHead(state))
                    || state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER) || state.is(Blocks.LEVER)) {
                continue;
            }
            out.add(under);
        }
        return new ArrayList<>(out);
    }

    private static boolean isHead(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD);
    }

    /**
     * Marks the prince containing this block as blown.
     *
     * @return true the first time THAT prince is blown - so its zombie appears once, and a second superboom
     *     through the same hole does nothing
     */
    public static boolean blow(BlockPos pos) {
        Integer which = BLOCKS.get(pos);
        return which != null && CLAIMED.add(which);
    }

    /**
     * Takes the run's single prince point.
     *
     * @return true exactly once per run, for the first prince blown; false for every one after it
     */
    public static boolean takeScore() {
        if (scored) {
            return false;
        }
        scored = true;
        return true;
    }

    /** Whether the run has already taken its prince point. For tests. */
    public static boolean hasScored() {
        return scored;
    }

    /**
     * Finds the golden crypts in a floor that has already been pasted.
     *
     * <p>Chunk sections whose palette holds no gold block are skipped, which on a dungeon floor is almost all
     * of them - the same treatment the lever scan and the gate sweep get, and for the same reason: the grid is
     * about three million blocks and reading all of it on the server thread is a freeze this mod has paid for
     * once already.
     *
     * <p>Only chunks that are already loaded are read. This runs moments after the build, so the floor is
     * still hot, and force-loading the whole grid on the server thread froze the client badly enough for the
     * test harness to kill it.
     *
     * @return how many princes were found
     */
    public static int scan(ServerLevel level) {
        reset();
        List<BlockPos> crowns = new ArrayList<>();
        Set<BlockPos> lidChecked = new HashSet<>();
        BlockPos min = DungeonLayout.cellCenter(0);
        BlockPos max = DungeonLayout.cellCenter(DungeonLayout.GRID * DungeonLayout.GRID - 1);
        int x0 = Math.min(min.getX(), max.getX()) - RoomLibrary.TILE;
        int x1 = Math.max(min.getX(), max.getX()) + RoomLibrary.TILE;
        int z0 = Math.min(min.getZ(), max.getZ()) - RoomLibrary.TILE;
        int z1 = Math.max(min.getZ(), max.getZ()) + RoomLibrary.TILE;
        for (int cx = x0 >> 4; cx <= (x1 >> 4); cx++) {
            for (int cz = z0 >> 4; cz <= (z1 >> 4); cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                var chunk = level.getChunk(cx, cz);
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    if (section == null || section.hasOnlyAir()
                            || !section.maybeHas(st -> st.is(CROWN) || st.is(LANTERN) || st.is(LID))) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < SimAltitude.minWorldY() || baseY > SimAltitude.maxWorldY()) {
                        continue;
                    }
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int ly = 0; ly < 16; ly++) {
                                var state = section.getBlockState(lx, ly, lz);
                                BlockPos at = new BlockPos((cx << 4) + lx, baseY + ly, (cz << 4) + lz);
                                if (state.is(CROWN)) {
                                    if (onAPlinth(level, at)) {
                                        crowns.add(at);
                                    }
                                } else if (state.is(LID)) {
                                    if (lidChecked.add(at)) {
                                        ringedLid(level, at, lidChecked, crowns);
                                    }
                                } else if (state.is(LANTERN) && walledIn(level, at)) {
                                    // The lantern AND its ring, so a superboom aimed at any face of the
                                    // structure finds a prince block - he is looking at andesite, not at the
                                    // lantern buried inside it.
                                    crowns.add(at);
                                    for (int dx = -1; dx <= 1; dx++) {
                                        for (int dz = -1; dz <= 1; dz++) {
                                            BlockPos ring = at.offset(dx, 0, dz);
                                            if (level.getBlockState(ring).is(LANTERN_WALL)) {
                                                crowns.add(ring);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Grouped: one prince per connected run of gold, so a floor carrying two of those four rooms has two
        // princes and each scores on its own.
        Set<BlockPos> unassigned = new HashSet<>(crowns);
        while (!unassigned.isEmpty()) {
            BlockPos seed = unassigned.iterator().next();
            int index = PRINCES.size();
            PRINCES.add(seed);
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            unassigned.remove(seed);
            BLOCKS.put(seed, index);
            while (!queue.isEmpty()) {
                BlockPos here = queue.poll();
                for (Direction dir : Direction.values()) {
                    BlockPos next = here.relative(dir);
                    if (unassigned.remove(next)) {
                        BLOCKS.put(next, index);
                        queue.add(next);
                    }
                }
            }
        }
        return PRINCES.size();
    }

    /** The {@link #LID} rule, asked of {@code CryptScanner} so the sim and Secret Waypoints' prince waypoints cannot
     *  disagree (one room, one answer). */
    private static void ringedLid(ServerLevel level, BlockPos start, Set<BlockPos> checked, List<BlockPos> crowns) {
        crowns.addAll(com.killer560.hub.secretwaypoints.CryptScanner.lidPrince(level, start, checked));
    }

    /** Whether a sea lantern is walled in by polished andesite on all four sides - see {@link #LANTERN}.
     *  Asked of {@code CryptScanner}, so the sim and Secret Waypoints' prince waypoints cannot disagree. */
    private static boolean walledIn(ServerLevel level, BlockPos at) {
        return com.killer560.hub.secretwaypoints.CryptScanner.isPrinceCrown(level, at);
    }

    /** Whether a gold block has a smooth stone slab beside it - the plinth the crown sits in. Same source as
     *  {@link #walledIn}. */
    private static boolean onAPlinth(ServerLevel level, BlockPos at) {
        return com.killer560.hub.secretwaypoints.CryptScanner.isPrinceCrown(level, at);
    }
}
