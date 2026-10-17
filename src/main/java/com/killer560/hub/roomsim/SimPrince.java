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
 *
 * <p>There are four signatures now - gold on a slab, a lantern walled in andesite, a smooth stone lid and an obsidian
 * top (each below, with the rooms it picks out). Rooms the wiki says have a prince that none of them finds: Skull
 * (its one crypt is a plain 3x4 slab lid with nothing to tell it from an ordinary crypt).
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

    /**
     * The FOURTH signature: an obsidian top on a bottom smooth stone slab rim.
     *
     * <p>killer560, 2026-10-16, standing in Supertall: "this is a prince" - a raised tomb against a stone brick wall
     * under two braziers with obsidian lying flat on top. In the capture that is the tomb at x 48..53, y 142, z 44..48:
     * a 4x4 ring of obsidian round a 2x2 of double smooth stone slabs, three sides rimmed by bottom smooth stone slabs
     * (the fourth is the wall at z 43), on stone brick stair legs at y 141, the two hoppers (the braziers) at
     * (50..51, 145, 44) and an oak log at (54, 141, 52). Supertall has a second one, at x 4..6, y 99, z 11..14: a 1x2 of
     * obsidian in a ring of ten bottom slabs on a stepped stone brick stair base - the one the wiki puts "on Level 2,
     * behind a Cracked Stone Bricks wall". Until now the ordinary crypt rule took that one for a plain crypt.
     *
     * <p>"Obsidian with air above and a bottom smooth stone slab beside it" ({@code CryptScanner.obsidianPrince}) is
     * matched by six structures in all 135 captures (census 2026-10-16), and each is a raised tomb of this shape:
     * Supertall's two, Withermancer (2x2 at 29..30, 73, 34..35), Grass Ruin (3x2 at 3..5, 95, 16..17), Pirate (1x2 at
     * 58..59, 61, 6, a player head beside it like Leaves') and Waterfall (1x2 at 106..107, 40, 4). The wiki's rooms
     * table gives Supertall 2 princes ("6 Crypts (4 Crypt Undeads, 2 Princes)") and Withermancer 1, exactly the
     * obsidian tombs in each; it leaves the princes column blank for Grass Ruin, Pirate and Waterfall, as it does for
     * Big Red Flag, Leaves and every other room he has named, so those three are an inference. With them as princes, crypt
     * lids plus princes meet the room database's crypt count in Withermancer, Pirate, Waterfall and Grass Ruin (whose
     * ring is too sparse for the crypt rule, so 3 becomes its 4); Supertall's real 4 crypts plus 2 princes are the
     * wiki's 6, though the crypt rule also takes its three chest platforms at y 141. 12 rooms hold obsidian at all, so obsidian alone would be the
     * gold-alone trap again; the slab beside it is what makes it a tomb.
     */
    private static final net.minecraft.world.level.block.Block OBSIDIAN = Blocks.OBSIDIAN;

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

    /** Where every prince on this floor is (one block of each), for the build log and for tests. */
    public static List<BlockPos> positions() {
        return new ArrayList<>(PRINCES);
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

    /** How far below the lowest smooth stone of a tomb its body may still reach before the floor. */
    private static final int TOMB_DESCENT = 2;

    /** A ceiling on one tomb, so a smooth stone floor can never turn a prince into a crater. */
    private static final int TOMB_MAX = 256;

    /**
     * EVERYTHING the prince containing {@code pos} is made of: its own blocks, its whole tomb, and {@link #extrasOf}.
     *
     * <p>killer560, 2026-10-14, of Big Red Flag's prince after one Superboom: "that whole structure should be gone". Only
     * the lid, its slab ring and the one layer under them went, which left the tomb's eave slabs, its smooth stone and
     * cobble walls and its andesite feet standing (capture x 12..17, y 69..72, z 22..26). The tomb is found from the
     * prince's blocks: the smooth stone / smooth stone slab joined to them (six-way, within 2 of them across and 3 below)
     * gives its footprint and top, and every layer of that footprint from the top down is taken until a layer that is
     * entirely solid - the room's floor - below the lowest smooth stone, and never more than {@value #TOMB_DESCENT}
     * layers below it. Census over all 135 captures (2026-10-14): Big Red Flag's tomb is 86 blocks, x 12..17 z 22..26,
     * y 69..72 - the room wall behind it at z 27 and x 18 and the floor at y 68 are outside it; every other prince
     * (Chambers, Doors, Leaves, Market, Pirate, Red Blue, Sloth, Stairs) stops at its own layer, plus the gold plinth's
     * smooth stone slabs. Never takes what a blast never takes (air, block entities other than a head, bedrock,
     * barriers, levers, soul lanterns).
     */
    public static List<BlockPos> tombOf(ServerLevel level, BlockPos pos) {
        List<BlockPos> blocks = blocksOf(pos);
        Set<BlockPos> out = new java.util.LinkedHashSet<>(blocks);
        if (blocks.isEmpty()) {
            return new ArrayList<>(out);
        }
        int bx0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE, bz0 = Integer.MAX_VALUE, bz1 = Integer.MIN_VALUE;
        int by0 = Integer.MAX_VALUE, by1 = Integer.MIN_VALUE;
        for (BlockPos b : blocks) {
            bx0 = Math.min(bx0, b.getX());
            bx1 = Math.max(bx1, b.getX());
            bz0 = Math.min(bz0, b.getZ());
            bz1 = Math.max(bz1, b.getZ());
            by0 = Math.min(by0, b.getY());
            by1 = Math.max(by1, b.getY());
        }
        // The smooth stone skin joined to the prince.
        Set<BlockPos> skin = new HashSet<>(blocks);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(blocks);
        while (!queue.isEmpty() && skin.size() < TOMB_MAX) {
            BlockPos here = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos next = here.relative(dir).immutable();
                if (skin.contains(next) || next.getX() < bx0 - 2 || next.getX() > bx1 + 2 || next.getZ() < bz0 - 2
                        || next.getZ() > bz1 + 2 || next.getY() < by0 - 3 || next.getY() > by1) {
                    continue;
                }
                var state = level.getBlockState(next);
                if (state.is(LID) || state.is(PLINTH)) {
                    skin.add(next);
                    queue.add(next);
                }
            }
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        int top = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
        for (BlockPos b : skin) {
            x0 = Math.min(x0, b.getX());
            x1 = Math.max(x1, b.getX());
            z0 = Math.min(z0, b.getZ());
            z1 = Math.max(z1, b.getZ());
            top = Math.max(top, b.getY());
            low = Math.min(low, b.getY());
        }
        for (int y = top; y >= low - TOMB_DESCENT && out.size() < TOMB_MAX; y--) {
            boolean solid = true;
            List<BlockPos> layer = new ArrayList<>();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var state = level.getBlockState(p);
                    if (state.isAir()) {
                        solid = false;
                        continue;
                    }
                    if ((state.hasBlockEntity() && !isHead(state)) || state.is(Blocks.BEDROCK)
                            || state.is(Blocks.BARRIER) || state.is(Blocks.LEVER) || state.is(Blocks.SOUL_LANTERN)) {
                        continue;
                    }
                    layer.add(p);
                }
            }
            if (y < low && solid) {
                break;   // the floor
            }
            out.addAll(layer);
        }
        out.addAll(extrasOf(level, pos));
        return new ArrayList<>(out);
    }

    /**
     * Every block of every not-yet-blown prince any block of whose {@link #tombOf} satisfies {@code inReach} - one
     * Superboom anywhere within reach of a tomb opens the whole of it.
     */
    public static List<BlockPos> tombsInReach(ServerLevel level, BlockPos near,
                                              java.util.function.Predicate<BlockPos> inReach) {
        Set<BlockPos> out = new java.util.LinkedHashSet<>();
        scanNear(level, near);
        for (int i = 0; i < PRINCES.size(); i++) {
            BlockPos seed = PRINCES.get(i);
            // Only princes nearby: a tomb is far smaller than this, and reading a far one could load its chunk.
            if (CLAIMED.contains(i) || Math.abs(seed.getX() - near.getX()) > 24 || Math.abs(seed.getZ() - near.getZ()) > 24
                    || Math.abs(seed.getY() - near.getY()) > 24) {
                continue;
            }
            List<BlockPos> tomb = tombOf(level, seed);
            for (BlockPos b : tomb) {
                if (inReach.test(b)) {
                    out.addAll(tomb);
                    break;
                }
            }
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
                            || !section.maybeHas(st -> st.is(CROWN) || st.is(LANTERN) || st.is(LID) || st.is(OBSIDIAN))) {
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
                                } else if (state.is(OBSIDIAN)) {
                                    if (lidChecked.add(at)) {
                                        obsidianTop(level, at, lidChecked, crowns);
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

        group(crowns);
        return PRINCES.size();
    }

    /** Grouped: one prince per connected run of crown blocks, so a floor carrying two princes scores each on its own.
     *  Blocks already part of a known prince are left alone. */
    private static void group(List<BlockPos> crowns) {
        Set<BlockPos> unassigned = new HashSet<>();
        for (BlockPos c : crowns) {
            if (!BLOCKS.containsKey(c)) {
                unassigned.add(c.immutable());
            }
        }
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
    }

    /** Blocks around a Superboom asked again for a prince the floor scan did not register. */
    static final int NEAR_SCAN = 8;

    /**
     * The same four signatures as {@link #scan}, asked of the {@value #NEAR_SCAN}-block cube round {@code center} - every
     * Superboom asks before it decides what it opens. killer560 (2026-10-13), in Leaves through route practice: "this
     * structure in leaves is a prince", and his log showed Superbooms on its polished andesite with prince=false while
     * the same room built by itself in the testkit (646) found it - the floor scan reads only chunks already loaded,
     * and missed it there. A prince registered here is new; one already known (blown or not) is left as it is.
     *
     * @return how many new princes were found
     */
    public static int scanNear(ServerLevel level, BlockPos center) {
        int before = PRINCES.size();
        List<BlockPos> crowns = new ArrayList<>();
        Set<BlockPos> lidChecked = new HashSet<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -NEAR_SCAN; dx <= NEAR_SCAN; dx++) {
            for (int dy = -NEAR_SCAN; dy <= NEAR_SCAN; dy++) {
                for (int dz = -NEAR_SCAN; dz <= NEAR_SCAN; dz++) {
                    at.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (BLOCKS.containsKey(at) || !level.isLoaded(at)) {
                        continue;
                    }
                    var state = level.getBlockState(at);
                    BlockPos p = at.immutable();
                    if (state.is(CROWN)) {
                        if (onAPlinth(level, p)) {
                            crowns.add(p);
                        }
                    } else if (state.is(LID)) {
                        if (lidChecked.add(p)) {
                            ringedLid(level, p, lidChecked, crowns);
                        }
                    } else if (state.is(OBSIDIAN)) {
                        if (lidChecked.add(p)) {
                            obsidianTop(level, p, lidChecked, crowns);
                        }
                    } else if (state.is(LANTERN) && walledIn(level, p)) {
                        crowns.add(p);
                        for (int rx = -1; rx <= 1; rx++) {
                            for (int rz = -1; rz <= 1; rz++) {
                                BlockPos ring = p.offset(rx, 0, rz);
                                if (level.getBlockState(ring).is(LANTERN_WALL)) {
                                    crowns.add(ring);
                                }
                            }
                        }
                    }
                }
            }
        }
        group(crowns);
        int found = PRINCES.size() - before;
        if (found > 0) {
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                    "Sim prince: {} more found near {} at the Superboom (the floor scan had missed it)", found,
                    center.toShortString());
        }
        return found;
    }

    /** The {@link #LID} rule, asked of {@code CryptScanner} so the sim and Secret Waypoints' prince waypoints cannot
     *  disagree (one room, one answer). */
    private static void ringedLid(ServerLevel level, BlockPos start, Set<BlockPos> checked, List<BlockPos> crowns) {
        crowns.addAll(com.killer560.hub.secretwaypoints.CryptScanner.lidPrince(level, start, checked));
    }

    /** The {@link #OBSIDIAN} rule, asked of {@code CryptScanner} for the same reason as {@link #ringedLid}. */
    private static void obsidianTop(ServerLevel level, BlockPos start, Set<BlockPos> checked, List<BlockPos> crowns) {
        crowns.addAll(com.killer560.hub.secretwaypoints.CryptScanner.obsidianPrince(level, start, checked));
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
