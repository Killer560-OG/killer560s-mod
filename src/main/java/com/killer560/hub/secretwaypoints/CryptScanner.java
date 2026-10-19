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
 * panels for one crypt) and a few tombs of another shape. Pirate's three lantern tables and the Stairs room's slab
 * staircase used to match too; since 2026-10-19 a crown needs air above it ({@link #isPrinceCrown}) and a slab
 * staircase is not a lid ({@link #isStaircase}), which brings the census to 118 of 134 rooms exact. Also since
 * 2026-10-19, a run with full blocks on top is a wall panel ({@link #isCovered}) and a run with no floor within two
 * blocks beside it is a wall ledge ({@link #isLedge}): 121 of 134 (Banners, Doors and Hallway now exact). Later on
 * 2026-10-19, a run set flush into a walkway is not a lid ({@link #isWalkway}) and a lid between two braziers on
 * anvils is a prince ({@link #brazierPrince}): 122 of 134 (Flags now exact). The census is
 * {@code killer560s-mod-logs/crypt-census/crypt_scan_port.py}, a port of this class.
 *
 * <p><b>A prince</b> is the golden crypt: a gold block with a smooth stone slab beside it, a sea lantern walled
 * in by polished andesite on all four sides, or a lid of full smooth stone ringed by bottom smooth stone slabs
 * ({@link #lidPrince}, Big Red Flag, 2026-10-09) - the three shapes {@code roomsim.SimPrince} was given by him - or an
 * obsidian top with a bottom smooth stone slab rim ({@link #obsidianPrince}, Supertall, 2026-10-16), or a line of bottom
 * slabs on a polished andesite tomb with a head beside it ({@link #sarcophagusPrince}, Sarcophagus, 2026-10-18), or a lid
 * between two braziers standing on anvils ({@link #brazierPrince}, Flags, 2026-10-19). A slab run that
 * touches a prince's blocks is the prince, not an ordinary crypt as well.
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

    /** Most full smooth stone blocks a prince lid may have (Big Red Flag's is 6). */
    static final int LID_MAX = 16;

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
     *
     * <p>Either way the crown is the tomb's open TOP: air directly above it. killer560, 2026-10-19: "these are not
     * princes in pirates". Pirate's three matches are 3x3 polished andesite tables with a sea lantern in the middle and
     * a grey carpet ON it (capture x 22..24, y 64, z 25..37); Stairs has a fourth (light grey carpet, x 16..18, y 89,
     * z 12..14), and Doors' gold has more gold on top of it. Every real crown in the 135 captures (Chambers, Leaves,
     * Market, Red Blue, Sloth) has air above. Census: crypts + princes equal the database's count in 118 rooms, up from
     * 116, and no room that matched before stops matching.
     */
    public static boolean isPrinceCrown(Level level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        if ((state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.SEA_LANTERN)) && !level.getBlockState(at.above()).isAir()) {
            return false;
        }
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
        return state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.SMOOTH_STONE_SLAB) || state.is(Blocks.OBSIDIAN);
    }

    /**
     * The prince LID rule (see {@code roomsim.SimPrince}: Big Red Flag's tomb) from one smooth stone block: its horizontal run of smooth stone (members go into
     * {@code checked}, so each run is judged once), and when the run is a lid, returns the lid and its whole slab ring (else empty). Capped at {@value #LID_MAX} + 1 so a smooth stone floor fails fast.
     */
    public static List<BlockPos> lidPrince(Level level, BlockPos start, Set<BlockPos> checked) {
        List<BlockPos> crowns = new ArrayList<>();
        List<BlockPos> lid = new ArrayList<>();
        Set<BlockPos> ring = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        boolean ok = true;
        while (!queue.isEmpty()) {
            BlockPos here = queue.poll();
            lid.add(here);
            checked.add(here);
            if (lid.size() > LID_MAX) {
                ok = false;
                break;
            }
            if (!level.getBlockState(here.above()).isAir()) {
                ok = false;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                var state = level.getBlockState(next);
                if (state.is(Blocks.SMOOTH_STONE)) {
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                } else if (state.is(Blocks.SMOOTH_STONE_SLAB)
                        && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                    ring.add(next);
                } else {
                    ok = false;
                }
            }
        }
        if (ok && lid.size() >= 2) {
            crowns.addAll(lid);
            // The WHOLE ring: the slabs touching the lid are its sides, and the corner slabs join them on the same
            // layer (Big Red Flag's ring is 14 slabs, 10 touching the lid and 4 corners). Bottom slabs only, capped.
            ArrayDeque<BlockPos> walk = new ArrayDeque<>(ring);
            Set<BlockPos> all = new HashSet<>(ring);
            while (!walk.isEmpty() && all.size() < 4 * LID_MAX) {
                BlockPos here = walk.poll();
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos next = here.relative(dir).immutable();
                    var state = level.getBlockState(next);
                    if (!all.contains(next) && state.is(Blocks.SMOOTH_STONE_SLAB)
                            && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                        all.add(next);
                        walk.add(next);
                    }
                }
            }
            crowns.addAll(all);
        }
        return crowns;
    }

    /**
     * The prince OBSIDIAN rule (see {@code roomsim.SimPrince}: Supertall's two tombs) from one obsidian block: its
     * horizontal run of obsidian (members go into {@code checked}, so each run is judged once, capped at
     * {@value #LID_MAX} + 1), which is a prince when any member has air above it and a bottom smooth stone slab beside
     * it. Returns the run, every smooth stone slab touching it (Supertall's upper tomb has a 2x2 of double slabs inside
     * its obsidian ring) and the whole bottom-slab ring on that layer, or nothing.
     */
    public static List<BlockPos> obsidianPrince(Level level, BlockPos start, Set<BlockPos> checked) {
        List<BlockPos> crowns = new ArrayList<>();
        List<BlockPos> run = new ArrayList<>();
        Set<BlockPos> touching = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        boolean rimmed = false;
        while (!queue.isEmpty()) {
            BlockPos here = queue.poll();
            run.add(here);
            checked.add(here);
            if (run.size() > LID_MAX) {
                return crowns;
            }
            boolean open = level.getBlockState(here.above()).isAir();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                BlockState state = level.getBlockState(next);
                if (state.is(Blocks.OBSIDIAN)) {
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                } else if (state.is(Blocks.SMOOTH_STONE_SLAB)) {
                    touching.add(next);
                    if (open && isLidSlab(state)) {
                        rimmed = true;
                    }
                }
            }
        }
        if (!rimmed) {
            return crowns;
        }
        crowns.addAll(run);
        // The slabs touching the run, and the rest of the ring on the same layer (its corners touch only slabs).
        ArrayDeque<BlockPos> walk = new ArrayDeque<>(touching);
        Set<BlockPos> all = new HashSet<>(touching);
        while (!walk.isEmpty() && all.size() < 4 * LID_MAX) {
            BlockPos here = walk.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                if (!all.contains(next) && isLidSlab(level.getBlockState(next))) {
                    all.add(next);
                    walk.add(next);
                }
            }
        }
        crowns.addAll(all);
        return crowns;
    }

    /**
     * The prince SARCOPHAGUS rule (see {@code roomsim.SimPrince}: Sarcophagus, 2026-10-18) from one smooth stone slab: its
     * horizontal run of smooth stone slabs (members go into {@code checked}, capped at {@value #LID_MAX} + 1), which is a
     * prince when it is 2 or more bottom slabs, EVERY one lying on polished andesite, with a player head touching the run on
     * its own layer. Returns the run and the polished andesite body on the layer directly under it (joined to the run's
     * footprint, at most two blocks out), or nothing.
     *
     * <p>killer560, 2026-10-18: "this is a prince in sarcophogous". Sarcophagus' one crypt (the room database says 1) is a
     * 5x3 polished andesite tomb on stone brick stair legs with a line of five bottom smooth stone slabs along its top and
     * two player heads beside them (capture x 14..18, y 69..71, z 15..17). Census over all 135 captures: slab runs lying
     * wholly on polished andesite are found in Catwalk, Deathmite, Doors, Flags, Hallway, Pressure Plates and Prison Cell
     * too (walkways and ledges), and only Sarcophagus' has a head touching it.
     */
    public static List<BlockPos> sarcophagusPrince(Level level, BlockPos start, Set<BlockPos> checked) {
        List<BlockPos> crowns = new ArrayList<>();
        List<BlockPos> run = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        boolean ok = true;
        boolean head = false;
        while (!queue.isEmpty()) {
            BlockPos here = queue.poll();
            run.add(here);
            checked.add(here);
            if (run.size() > LID_MAX) {
                return crowns;
            }
            if (!isLidSlab(level.getBlockState(here)) || !level.getBlockState(here.below()).is(Blocks.POLISHED_ANDESITE)) {
                ok = false;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockState n = level.getBlockState(here.offset(dx, 0, dz));
                    if (n.is(Blocks.PLAYER_HEAD) || n.is(Blocks.PLAYER_WALL_HEAD)) {
                        head = true;
                    }
                }
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                if (level.getBlockState(next).is(Blocks.SMOOTH_STONE_SLAB) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        if (!ok || !head || run.size() < 2) {
            return crowns;
        }
        crowns.addAll(run);
        // The tomb's body: the polished andesite on the layer under the slabs, joined to the run's footprint.
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : run) {
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
        }
        ArrayDeque<BlockPos> walk = new ArrayDeque<>();
        Set<BlockPos> body = new HashSet<>();
        for (BlockPos p : run) {
            BlockPos under = p.below().immutable();
            if (body.add(under)) {
                walk.add(under);
            }
        }
        while (!walk.isEmpty() && body.size() < 4 * LID_MAX) {
            BlockPos here = walk.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                if (next.getX() < x0 - 2 || next.getX() > x1 + 2 || next.getZ() < z0 - 2 || next.getZ() > z1 + 2
                        || body.contains(next) || !level.getBlockState(next).is(Blocks.POLISHED_ANDESITE)) {
                    continue;
                }
                body.add(next);
                walk.add(next);
            }
        }
        crowns.addAll(body);
        return crowns;
    }

    /** How far (across and up or down) from a lid a brazier may stand for {@link #brazierPrince}. */
    static final int BRAZIER_REACH = 3;

    /**
     * The prince BRAZIER rule from one smooth stone slab: its horizontal run of smooth stone slabs (members go into
     * {@code checked}, capped at {@value #MAX_LID} + 1), which is a prince when it has the shape of an ordinary crypt lid
     * ({@link #lidShaped}) and two or more braziers on pedestals - a hopper standing on an anvil - within
     * {@value #BRAZIER_REACH} blocks of it. Returns the run, or nothing. The fire on the hopper is NOT asked for: it is the
     * one block a player knocks out by hitting it, and captures hold it or not at random ({@code roomsim.RoomBraziers}).
     *
     * <p>killer560, 2026-10-19, of the tomb at the end of Flags' cobblestone corridor (screenshots 14:49:57 and 14:53:11, "A
     * Prince falls" in chat after it was opened): the mod marks it a crypt and it is a prince. It is a plain 3x4 lid of bottom smooth stone
     * slabs on stone brick stair legs (capture x 5..7, y 70, z 27..30), the same as Flags' other crypts, with nothing gold
     * and no lantern, so no older rule could see it. What it alone has is the pair of braziers flanking it on anvil
     * pedestals (hoppers at (3, 70, 31) and (9, 70, 31), fire on each, anvils under). The wiki's rooms table gives Flags 8
     * crypts and 1 prince. Census over all 135 captures: braziers beside a lid are common (Dip, Steps, Red Green, Purple
     * Flags, Banners, Rail Track and Flags' own crypts); a pair of hoppers on anvils beside one is found only here and in
     * Skull, whose tomb is the same design (3x4 lid against the back wall, x 53..55, y 63, z 20..23, pedestals at x 50 and
     * 58). The room database gives Skull 0 crypts, so its run was a wrong crypt before this and is a wrong-or-unconfirmed
     * prince now; its count does not move. Not checked in game.
     */
    public static List<BlockPos> brazierPrince(Level level, BlockPos start, Set<BlockPos> checked) {
        List<BlockPos> run = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos here = queue.poll();
            run.add(here);
            checked.add(here);
            if (run.size() > MAX_LID) {
                return new ArrayList<>();
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                if (level.getBlockState(next).is(Blocks.SMOOTH_STONE_SLAB) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        if (!lidShaped(level, run)) {
            return new ArrayList<>();
        }
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : run) {
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
        }
        int y = start.getY();
        int braziers = 0;
        for (int x = x0 - BRAZIER_REACH; x <= x1 + BRAZIER_REACH; x++) {
            for (int z = z0 - BRAZIER_REACH; z <= z1 + BRAZIER_REACH; z++) {
                for (int yy = y - BRAZIER_REACH; yy <= y + BRAZIER_REACH; yy++) {
                    BlockPos p = new BlockPos(x, yy, z);
                    if (level.getBlockState(p).is(Blocks.HOPPER)
                            && level.getBlockState(p.below()).is(net.minecraft.tags.BlockTags.ANVIL)) {
                        braziers++;
                    }
                }
            }
        }
        return braziers >= 2 ? run : new ArrayList<>();
    }

    /** Whether a horizontal slab run has an ordinary crypt lid's shape: mostly bottom slabs, {@value #MIN_LID} to
     *  {@value #MAX_LID} of them, at least two wide both ways, filling three quarters of its bounding box. */
    private static boolean lidShaped(Level level, List<BlockPos> run) {
        int n = run.size();
        int bottom = 0;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : run) {
            if (isLidSlab(level.getBlockState(p))) {
                bottom++;
            }
            x0 = Math.min(x0, p.getX());
            x1 = Math.max(x1, p.getX());
            z0 = Math.min(z0, p.getZ());
            z1 = Math.max(z1, p.getZ());
        }
        int w = x1 - x0 + 1;
        int d = z1 - z0 + 1;
        return bottom * 2 > n && n >= MIN_LID && n <= MAX_LID && Math.min(w, d) >= 2 && n * 4 >= w * d * 3;
    }


    /**
     * Every crypt and prince inside {@code [minX, minZ, maxX, maxZ]}, at any height.
     *
     * <p>Chunk sections whose palette cannot hold a slab, gold or sea lantern are skipped, the same treatment as
     * the lever scan; only chunks the client already has are read, and a find that reaches into a chunk it does not
     * have is left out until it can be read whole ({@link #whole}).
     */
    public static List<Found> scan(Level level, int[] bounds) {
        List<Found> out = new ArrayList<>();
        if (level == null || bounds == null) {
            return out;
        }
        Set<BlockPos> slabs = new HashSet<>();
        Set<BlockPos> bottoms = new HashSet<>();
        List<BlockPos> crowns = new ArrayList<>();
        Set<BlockPos> lidChecked = new HashSet<>();
        Set<BlockPos> brazierChecked = new HashSet<>();
        List<BlockPos> lidPrinces = new ArrayList<>();
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
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(st ->
                            st.is(Blocks.SMOOTH_STONE_SLAB) || st.is(Blocks.GOLD_BLOCK) || st.is(Blocks.SEA_LANTERN)
                                    || st.is(Blocks.SMOOTH_STONE) || st.is(Blocks.OBSIDIAN))) {
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
                                    if (lidChecked.add(at)) {
                                        lidPrinces.addAll(sarcophagusPrince(level, at, lidChecked));
                                    }
                                    if (brazierChecked.add(at)) {
                                        lidPrinces.addAll(brazierPrince(level, at, brazierChecked));
                                    }
                                } else if (state.is(Blocks.SMOOTH_STONE)) {
                                    BlockPos at = new BlockPos(x, baseY + ly, z);
                                    if (lidChecked.add(at)) {
                                        lidPrinces.addAll(lidPrince(level, at, lidChecked));
                                    }
                                } else if (state.is(Blocks.OBSIDIAN)) {
                                    BlockPos at = new BlockPos(x, baseY + ly, z);
                                    if (lidChecked.add(at)) {
                                        lidPrinces.addAll(obsidianPrince(level, at, lidChecked));
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
        Set<BlockPos> princeBlocks = new HashSet<>(lidPrinces);
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
            Found f = found(Type.PRINCE, group);
            if (whole(level, f)) {
                out.add(f);
            }
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
            if (isStaircase(level, run) || isCovered(level, run) || isLedge(level, run) || isWalkway(level, run)) {
                continue;
            }
            if (!whole(level, f)) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /**
     * Whether every chunk this find (and the block ring around it) lies in is loaded. A tomb that runs into a chunk the
     * client does not have reads as cut off there - that chunk is air - so what is left is the wrong shape and the
     * wrong anchor: a 4x4 lid half in an unloaded chunk passes as a 2x4 crypt keyed on the wrong block, and its real
     * self arrives later as a second one. Such a find is unknown, not found; the next scan sees it whole.
     */
    private static boolean whole(Level level, Found f) {
        return com.killer560.hub.util.ClientChunks.allLoaded(level, f.minX() - 1, f.minZ() - 1, f.maxX() + 1,
                f.maxZ() + 1);
    }

    /**
     * Whether a slab run is one step of a slab STAIRCASE: a double slab of the run with a smooth stone slab beside it one
     * block up - the next step. killer560, 2026-10-19: "this is not a crypt in stairs". The Stairs room climbs on rows of
     * smooth stone slabs, a row of bottom slabs then a row of double slabs, one block higher each pair (capture x 31..35,
     * y 87..89, z 19..22), and one pair passed as a 5x2 lid. A lid has nothing stacked beside it: over all 135
     * captures this drops only that one (Stairs now matches its database count of 1, its real crypt being the
     * slab-and-carpet lid on stone brick stairs at x 10..14, y 72) and no other room's count moves.
     */
    private static boolean isStaircase(Level level, List<BlockPos> run) {
        for (BlockPos p : run) {
            BlockState state = level.getBlockState(p);
            if (!state.hasProperty(SlabBlock.TYPE) || state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
                continue;
            }
            for (Direction dir : HORIZONTAL) {
                if (level.getBlockState(p.relative(dir).above()).is(Blocks.SMOOTH_STONE_SLAB)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How far below a lid the floor beside it may lie before the run is a wall ledge ({@link #isLedge}). */
    static final int LEDGE_DROP = 3;

    /**
     * Whether a slab run is a panel set INTO a wall: more than half its slabs have a full block directly on top. A lid is
     * the open top of a tomb; killer560, 2026-10-19, of slab runs along stone brick corridor walls: "none of these are
     * crypts" / "or these". Hallway's twelve 2x3 / 3x2 panels (capture x 2..29, y 72 and 74, under polished andesite,
     * stone and andesite) and Banners' three stacked 3x2 panels (x 8..10, y 70..74, under stone bricks) are all of this
     * shape, and both rooms then match the database (Hallway 1, Banners 1). No other capture's lid has more than a
     * quarter of its slabs covered (stairs, slabs, carpet, chests and hoppers on a lid are not full blocks).
     */
    private static boolean isCovered(Level level, List<BlockPos> run) {
        int covered = 0;
        for (BlockPos p : run) {
            BlockPos up = p.above();
            if (level.getBlockState(up).isCollisionShapeFullBlock(level, up)) {
                covered++;
            }
        }
        return covered * 2 > run.size();
    }

    /**
     * Whether a slab run is a LEDGE hung on a wall rather than a tomb standing on a floor: beside every open side of the
     * run (a horizontal neighbour with nothing to collide with), the nearest floor is {@value #LEDGE_DROP} or more blocks
     * below the slabs. killer560, 2026-10-19, of the two landings up Doors' wall stairs: "this is not a crypt in doors".
     * Both are 5x2 runs at capture y 73 (x 14..18, z 2..3 and z 29..30), four blocks over the room floor at y 69 with
     * nothing under their open side, and Doors then matches its database count of 7 (the seven lids at y 59). Over all
     * 135 captures every lid the database confirms has a floor within two blocks beside it (most at 0 or 1; Catwalk's
     * and Shadow Assassin's at 2); the only runs at 3 or more are these two, and Hallway's and Banners' upper wall
     * panels, which {@link #isCovered} drops as well.
     */
    private static boolean isLedge(Level level, List<BlockPos> run) {
        Set<BlockPos> cells = new HashSet<>(run);
        int nearest = Integer.MAX_VALUE;
        for (BlockPos p : run) {
            for (Direction dir : HORIZONTAL) {
                BlockPos side = p.relative(dir);
                if (cells.contains(side) || !level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
                    continue; // part of the run, or a wall / step beside it: not an open side
                }
                int drop = 0;
                BlockPos under = side.below();
                while (drop < LEDGE_DROP && level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                    drop++;
                    under = under.below();
                }
                nearest = Math.min(nearest, drop);
                if (nearest < LEDGE_DROP) {
                    return false;
                }
            }
        }
        return nearest != Integer.MAX_VALUE && nearest >= LEDGE_DROP;
    }

    /**
     * Whether a slab run is a stretch of WALKWAY rather than a tomb's lid: a third or more of the cells round it (its
     * horizontal neighbours on its own layer) are full blocks with air on top - floor flush with the slabs that leads on
     * across them. A lid is the top of a tomb standing proud of the floor, so what touches it is air, its walls, or a wall
     * behind it with more wall above. killer560, 2026-10-19, of the slab platform between Flags' two lavafalls (screenshot
     * 14:50:27): this is not a crypt. It is a 3x5 of bottom slabs on a polished andesite pier (capture x 31..33, y 86,
     * z 53..57), and the polished andesite causeway at z 52 and z 58 runs straight on at its own height: 6 of its 16
     * neighbours are such floor. Census over all 135 captures: the next most is 3 of 14 (Skull's slab run, which the
     * database does not count either); no lid the database confirms has more than a fifth (Diagonal's 2 of 10). Dropping it makes Flags match
     * its count of 8 (seven crypts and the {@link #brazierPrince}), and no other room's count moves.
     */
    private static boolean isWalkway(Level level, List<BlockPos> run) {
        Set<BlockPos> cells = new HashSet<>(run);
        Set<BlockPos> around = new HashSet<>();
        int flush = 0;
        for (BlockPos p : run) {
            for (Direction dir : HORIZONTAL) {
                BlockPos side = p.relative(dir);
                if (cells.contains(side) || !around.add(side)) {
                    continue;
                }
                if (level.getBlockState(side).isCollisionShapeFullBlock(level, side)
                        && level.getBlockState(side.above()).isAir()) {
                    flush++;
                }
            }
        }
        return !around.isEmpty() && flush * 3 >= around.size();
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
