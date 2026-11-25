package com.killer560.hub.foraging;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Highlight Optimal Blocks on every tree of the kind he last broke a log of on this island (optimal-blocks-all-trees-1124,
 * killer560 2026-11-24: "those should always show for any tree of the type that i broke last on the island I am currently
 * on"). His break is a log he attacked that the server then removed within 5 s. The trees are found once (chunks within 48
 * blocks, section palettes first, 4 chunks a tick) and grouped; a log change only regroups the trees it touches (after 4
 * quiet ticks), a move of 24 blocks rescans. At most the 24 nearest trees are planned, one a tick, each with the ground
 * reach rule ({@link SweepReach}, no NEAR class: he is not standing at them). Cleared by a new world or leaving Galatea.
 */
final class SweepForest {

    private static final int RADIUS = 48, TREE_MAX = 24, CHUNKS_PER_TICK = 4, TREE_CAP = 300, DIRTY_CAP = 1024;
    private static final long NONE = Long.MIN_VALUE;

    private static final class Tree {
        final long[] logs;
        long[] plan;
        int easy;

        Tree(long[] logs) {
            this.logs = logs;
        }
    }

    private static int kind = -1, n = -1, pendKind = -1;
    private static long pendPos = NONE, pendGt;
    private static int[] chunks = new int[0];
    private static int scanAt = -1, cx0, cz0;
    private static final LongOpenHashSet found = new LongOpenHashSet();
    private static final LongOpenHashSet dirty = new LongOpenHashSet();
    private static boolean overflow, changed;
    private static long dirtyGt;
    private static final List<Tree> trees = new ArrayList<>();
    private static final Long2ObjectOpenHashMap<Tree> owner = new Long2ObjectOpenHashMap<>();

    private SweepForest() {
    }

    static int kind() {
        return kind;
    }

    static void reset() {
        kind = pendKind = -1;
        pendPos = NONE;
        clear();
    }

    private static void clear() {
        trees.clear();
        owner.clear();
        found.clear();
        dirty.clear();
        chunks = new int[0];
        scanAt = -1;
        overflow = false;
        changed = true;
    }

    /** He started hitting a log. */
    static void onAttack(long pos, int k, long gt) {
        pendPos = pos;
        pendKind = k;
        pendGt = gt;
    }

    /** A block change (mixin hook, before it is applied); {@code gt} the level's game time. */
    static void onChange(long pos, int oldKind, int nowKind, long gt) {
        if (pos == pendPos && oldKind >= 0 && nowKind < 0 && gt - pendGt <= 100) {
            pendPos = NONE;
            if (oldKind != kind) {
                kind = oldKind;
                clear();
            }
        }
        if (kind >= 0 && (oldKind == kind || nowKind == kind)) {
            if (dirty.size() < DIRTY_CAP) {
                dirty.add(pos);
            } else {
                overflow = true;
            }
            dirtyGt = gt;
        }
    }

    /** One tick of work at {@code logs} a swing; true when the published boxes changed. */
    static boolean tick(Minecraft mc, long gt, int logs) {
        if (kind < 0) {
            return false;
        }
        BlockPos me = mc.player.blockPosition();
        if (logs != n) {
            n = logs;
            trees.forEach(t -> t.plan = null);
        }
        boolean far = Math.max(Math.abs((me.getX() >> 4) - cx0), Math.abs((me.getZ() >> 4) - cz0)) > 1;
        if ((scanAt < 0 && trees.isEmpty() && found.isEmpty() && chunks.length == 0) || overflow || (scanAt < 0 && far)) {
            startScan(me);
        }
        if (scanAt >= 0) {
            scan(mc.level);
        } else if (!dirty.isEmpty() && gt - dirtyGt >= 4) {
            regroup(mc);
        }
        for (Tree t : trees) {
            if (t.plan == null) {
                Long2IntOpenHashMap r = SweepReach.classes(mc, new LongOpenHashSet(t.logs), null);
                t.plan = SweepRule.plan(t.logs, n, r::get);
                t.easy = 0;
                while (t.easy < t.plan.length && r.get(t.plan[t.easy]) <= SweepReach.GROUND) {
                    t.easy++;
                }
                changed = true;
                break;   // one tree a tick
            }
        }
        boolean c = changed;
        changed = false;
        return c;
    }

    private static void startScan(BlockPos me) {
        cx0 = me.getX() >> 4;
        cz0 = me.getZ() >> 4;
        int r = RADIUS >> 4;
        chunks = new int[(2 * r + 1) * (2 * r + 1) * 2];
        int i = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                chunks[i++] = cx0 + dx;
                chunks[i++] = cz0 + dz;
            }
        }
        scanAt = 0;
        overflow = false;
        found.clear();
        dirty.clear();
    }

    private static void scan(ClientLevel lv) {
        for (int k = 0; k < CHUNKS_PER_TICK && scanAt < chunks.length; k++, scanAt += 2) {
            int cx = chunks[scanAt], cz = chunks[scanAt + 1];
            if (!com.killer560.hub.util.ClientChunks.isLoaded(lv, cx, cz)) {
                continue;
            }
            LevelChunk ch = lv.getChunk(cx, cz);
            LevelChunkSection[] secs = ch.getSections();
            for (int i = 0; i < secs.length; i++) {
                LevelChunkSection s = secs[i];
                if (s == null || s.hasOnlyAir() || !s.maybeHas(b -> SweepRule.kind(b.getBlock()) == kind)) {
                    continue;
                }
                int by = ch.getSectionYFromSectionIndex(i) << 4;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (SweepRule.kind(s.getBlockState(x, y, z).getBlock()) == kind) {
                                found.add(BlockPos.asLong((cx << 4) + x, by + y, (cz << 4) + z));
                            }
                        }
                    }
                }
            }
        }
        if (scanAt < chunks.length) {
            return;
        }
        scanAt = -1;
        trees.clear();
        owner.clear();
        for (long l : found) {
            if (!owner.containsKey(l)) {
                add(SweepRule.fill(l, TREE_CAP, found::contains));
            }
        }
        found.clear();
        cap(Minecraft.getInstance().player.blockPosition());
        changed = true;
    }

    /** Regroup only the trees a changed log touches. */
    private static void regroup(Minecraft mc) {
        Set<Tree> stale = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        LongArrayList seeds = new LongArrayList();
        for (long p : dirty) {
            int px = BlockPos.getX(p), py = BlockPos.getY(p), pz = BlockPos.getZ(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Tree t = owner.get(BlockPos.asLong(px + dx, py + dy, pz + dz));
                        if (t != null) {
                            stale.add(t);
                        }
                    }
                }
            }
            seeds.add(p);
        }
        dirty.clear();
        for (Tree t : stale) {
            trees.remove(t);
            for (long l : t.logs) {
                owner.remove(l);
                seeds.add(l);
            }
        }
        for (int i = 0; i < seeds.size(); i++) {
            long s = seeds.getLong(i);
            if (!owner.containsKey(s) && kindAt(mc, s) == kind) {
                add(SweepRule.fill(s, TREE_CAP, q -> kindAt(mc, q) == kind));
            }
        }
        cap(mc.player.blockPosition());
        changed = true;
    }

    private static void add(long[] logs) {
        Tree t = new Tree(logs);
        trees.add(t);
        for (long l : logs) {
            owner.put(l, t);
        }
    }

    /** Keep the {@link #TREE_MAX} nearest trees. */
    private static void cap(BlockPos me) {
        if (trees.size() <= TREE_MAX) {
            return;
        }
        trees.sort(Comparator.comparingDouble(t -> me.distSqr(BlockPos.of(t.logs[0]))));
        while (trees.size() > TREE_MAX) {
            for (long l : trees.remove(trees.size() - 1).logs) {
                owner.remove(l);
            }
        }
    }

    private static int kindAt(Minecraft mc, long pos) {
        return SweepRule.kind(mc.level.getBlockState(BlockPos.of(pos)).getBlock());
    }

    /** The planned boxes: [0] easy picks, [1] hard picks, [2] each tree's next pick. */
    static AABB[][] boxes() {
        List<AABB> easy = new ArrayList<>(), hard = new ArrayList<>(), next = new ArrayList<>();
        for (Tree t : trees) {
            if (t.plan == null) {
                continue;
            }
            for (int i = 0; i < t.plan.length; i++) {
                (i < t.easy ? easy : hard).add(new AABB(BlockPos.of(t.plan[i])));
            }
            if (t.plan.length > 0) {
                next.add(new AABB(BlockPos.of(t.plan[0])));
            }
        }
        return new AABB[][]{easy.toArray(new AABB[0]), hard.toArray(new AABB[0]), next.toArray(new AABB[0])};
    }

    /** Test hook: every planned tree's plan. */
    static long[][] plans() {
        return trees.stream().filter(t -> t.plan != null).map(t -> t.plan.clone()).toArray(long[][]::new);
    }
}
