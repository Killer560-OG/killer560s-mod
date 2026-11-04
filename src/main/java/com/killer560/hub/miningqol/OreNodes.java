package com.killer560.hub.miningqol;

import com.killer560.hub.pathfinding.IslandGraph;
import com.killer560.hub.util.ClientChunks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which graph nodes of an area actually have ore beside them (killer560, 2026-11-03: "sometimes the pathfinding to an area
 * takes me to an area but there are no mithril things there. make it take me to an actual node"). Commission Route's goal
 * for a mithril or titanium area commission is the nodes with at least {@link #MIN_ORE} exposed ore blocks within
 * {@link #RADIUS} blocks sideways and {@link #DOWN}/{@link #UP} vertically. Six: a real vein or a cluster of ore is dozens
 * of blocks, a stray block or two is decoration he cannot do a commission with. Exposed means a face touches air, so
 * ore buried in solid rock does not count.
 *
 * <p>Counted from the loaded world only, chunk section by section (a section whose palette holds no ore is skipped
 * without reading a block). Never per frame and never all at once: {@link #tick} recounts {@link #PER_TICK} nodes a tick
 * round-robin, waits {@link #REST_TICKS} after a full pass, and recounts the nodes near the player faster (blocks get
 * mined). A node in an unloaded chunk is "unknown" and left out of the answer.
 */
final class OreNodes {

    static final int MIN_ORE = 6;
    static final int RADIUS = 7;
    static final int DOWN = 4;
    static final int UP = 5;
    private static final int PER_TICK = 6;
    private static final int REST_TICKS = 60;
    private static final int NEAR = 14;
    private static final int NEAR_TICKS = 20;

    private static String key = "";
    private static int[] nodes = new int[0];
    private static final Map<Integer, Integer> counts = new HashMap<>();
    private static int cursor = 0;
    private static int rest = 0;
    private static int nearClock = 0;
    private static int scans = 0;

    private OreNodes() {
    }

    /** Test hook: how many node counts were taken. */
    public static int scans() { return scans; }

    static void reset() {
        key = "";
        nodes = new int[0];
        counts.clear();
        cursor = 0;
        rest = 0;
    }

    /**
     * Keeps the counts for {@code area}'s nodes ({@code areaNodes}) of ore kind {@code ore} fresh; call once a tick while a
     * route to that area is wanted.
     */
    static void tick(ClientLevel level, IslandGraph g, String area, String ore, int[] areaNodes, double px, double py,
                     double pz) {
        String k = area + "|" + ore + "|" + areaNodes.length + "|" + MinesAreaMap.version();
        if (!k.equals(key)) {
            reset();
            key = k;
            nodes = areaNodes;
        }
        if (nodes.length == 0) {
            return;
        }
        Predicate<BlockState> isOre = predicate(ore);
        if (++nearClock >= NEAR_TICKS) {
            nearClock = 0;
            for (int n : nodes) {
                IslandGraph.Node nd = g.nodes[n];
                if (Math.abs(nd.x - px) <= NEAR && Math.abs(nd.z - pz) <= NEAR && Math.abs(nd.y - py) <= NEAR) {
                    count(level, g, n, isOre);
                }
            }
        }
        if (rest > 0) {
            rest--;
            return;
        }
        for (int i = 0; i < PER_TICK && cursor < nodes.length; i++, cursor++) {
            count(level, g, nodes[cursor], isOre);
        }
        if (cursor >= nodes.length) {
            cursor = 0;
            rest = REST_TICKS;
        }
    }

    private static void count(ClientLevel level, IslandGraph g, int n, Predicate<BlockState> isOre) {
        IslandGraph.Node nd = g.nodes[n];
        int c = countAround(level, (int) Math.floor(nd.x), (int) Math.floor(nd.y), (int) Math.floor(nd.z), isOre);
        scans++;
        if (c < 0) {
            counts.remove(n);
        } else {
            counts.put(n, c);
        }
    }

    static Predicate<BlockState> predicate(String ore) {
        return "Titanium".equals(ore) ? MiningHighlights::isTitanium : MithrilStone::isMithril;
    }

    /**
     * Exposed ore blocks in the box around {@code x, y, z}; -1 when a chunk of the box is not loaded (unknown). Palette
     * first: a section without ore is never read.
     */
    static int countAround(ClientLevel level, int x, int y, int z, Predicate<BlockState> isOre) {
        int x0 = x - RADIUS;
        int x1 = x + RADIUS;
        int z0 = z - RADIUS;
        int z1 = z + RADIUS;
        int y0 = y - DOWN;
        int y1 = y + UP;
        List<BlockPos> found = new ArrayList<>();
        for (int cx = x0 >> 4; cx <= (x1 >> 4); cx++) {
            for (int cz = z0 >> 4; cz <= (z1 >> 4); cz++) {
                if (!ClientChunks.isLoaded(level, cx, cz)) {
                    return -1;
                }
                var chunk = level.getChunk(cx, cz);
                int bx0 = Math.max(x0, cx << 4);
                int bx1 = Math.min(x1, (cx << 4) + 15);
                int bz0 = Math.max(z0, cz << 4);
                int bz1 = Math.min(z1, (cz << 4) + 15);
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < y0 || baseY > y1 || section == null || section.hasOnlyAir()
                            || !section.maybeHas(isOre)) {
                        continue;
                    }
                    int ly0 = Math.max(y0, baseY) - baseY;
                    int ly1 = Math.min(y1, baseY + 15) - baseY;
                    for (int bx = bx0; bx <= bx1; bx++) {
                        for (int bz = bz0; bz <= bz1; bz++) {
                            for (int ly = ly0; ly <= ly1; ly++) {
                                if (isOre.test(section.getBlockState(bx & 15, ly, bz & 15))) {
                                    found.add(new BlockPos(bx, baseY + ly, bz));
                                }
                            }
                        }
                    }
                }
            }
        }
        int exposed = 0;
        for (BlockPos p : found) {
            for (Direction d : Direction.values()) {
                if (level.getBlockState(p.relative(d)).isAir()) {
                    exposed++;
                    break;
                }
            }
        }
        return exposed;
    }

    /** The nodes with enough ore; when none has, those with some; empty when no node is known to (the caller falls back). */
    static int[] goals() {
        List<Integer> good = new ArrayList<>();
        List<Integer> some = new ArrayList<>();
        for (int n : nodes) {
            Integer c = counts.get(n);
            if (c == null) {
                continue;
            }
            if (c >= MIN_ORE) {
                good.add(n);
            } else if (c > 0) {
                some.add(n);
            }
        }
        List<Integer> use = good.isEmpty() ? some : good;
        return use.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The ore a commission for {@code area} wants: "Mithril", "Titanium", or null. */
    static String oreFor(List<MiningState.Commission> coms, String area) {
        for (MiningState.Commission c : coms) {
            if (c.done() || !c.name().startsWith(area + " ")) {
                continue;
            }
            if (c.name().endsWith(" Mithril")) {
                return "Mithril";
            }
            if (c.name().endsWith(" Titanium")) {
                return "Titanium";
            }
        }
        return null;
    }
}
