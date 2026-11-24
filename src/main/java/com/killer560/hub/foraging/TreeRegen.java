package com.killer560.hub.foraging;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regenerating Trees (Galatea). What his recordings show (logger sessions of 11-23, 1,589 log positions): a log that is
 * chopped becomes air, and about 600-1000 ticks later (median 795, 30-50 s) the same log is put back by a block update,
 * in the order it was broken. The highlight marks only the wood that is THERE (killer560, 11-24: "only highlight wood
 * that is there not the wood coming back"): the logs still standing in a tree he has started chopping, each dropping out
 * the moment it is broken and coming back when it regrows. A chopped log's position is remembered ({@link #GONE}, for
 * {@link #MAX_AGE} ticks) only to know which tree is regenerating: on the tick after a break the logs joined to it are
 * seeded into {@link #PRESENT}, and they stay drawn while a tracked chop is in their chunk column or the next one.
 * Event-driven from block updates (the old state is read in {@code ClientLevel.setServerVerifiedBlockState} and
 * {@code setBlock}, so a log he breaks himself counts too; a return as chunk data is caught by the 10-tick recheck);
 * the draw arrays are rebuilt only on a change or every 10 ticks, and nothing is allocated per frame.
 */
public final class TreeRegen {

    /** The saved color while the player has not picked one: it follows the menu theme's accent. */
    public static final int DEFAULT_COLOR = 0xFFCC6600;

    public static final IslandConfig CFG = IslandConfig.of("foraging", "treeregen")
            .num("color", DEFAULT_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .num("style", 2, 0, 3);

    /** The Style row's labels in saved order (index 2, Outline, is the default). */
    public static final String[] STYLES = {"Fill", "Fill + Outline", "Outline", "Recolor Block"};

    /** Ticks a chop still marks its tree (the 95th percentile of the returns is under 1,000). */
    static final int MAX_AGE = 1400;
    private static final int MAX_LOGS = 1500;
    private static final int MAX_DRAWN = 400;
    private static final int SEED_LOGS = 400;
    private static final int SEED_RADIUS = 16;
    private static final double RANGE_SQ = 64.0 * 64.0;

    /** Where a log left and when: only says which trees are regenerating. */
    private static final Map<Long, Integer> GONE = new LinkedHashMap<>();
    /** The logs standing now in those trees: the only blocks drawn. */
    private static final Set<Long> PRESENT = new LinkedHashSet<>();
    private static final List<Long> SEEDS = new ArrayList<>();
    private static int clock;
    private static boolean dirty;
    private static Object level;
    private static volatile AABB[] boxes = new AABB[0];
    private static volatile float[] rgba = new float[0];
    private static int boxFrames;
    private static int fillFrames;
    private static int lineFrames;
    private static int recolorFrames;
    private static int style = 2;
    private static int counted;
    private static boolean testIsland;

    private TreeRegen() {
    }

    public static void register() {
        int bit = FeatureBits.register("Regenerating Trees", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TreeRegen.tick", bit, TreeRegen::tick));
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(bit, TreeRegen::render));
    }

    /** A block set on the client level (client thread, before it is applied; from {@code TreeRegenLevelMixin}): the server's, and his own predicted break. */
    public static void onChange(Object lv, BlockPos pos, BlockState old, BlockState now) {
        if (!CFG.isEnabled()) {
            return;
        }
        bind(lv);
        boolean was = old.is(BlockTags.LOGS);
        boolean is = now.is(BlockTags.LOGS);
        if (was == is) {
            return;
        }
        long key = pos.asLong();
        if (is) {
            if (GONE.containsKey(key)) {
                add(key);
            }
            return;
        }
        PRESENT.remove(key);
        dirty = true;
        if (now.isAir() && (testIsland || "GALATEA".equals(String.valueOf(IslandDetector.graphIsland())))) {
            if (GONE.size() >= MAX_LOGS) {
                GONE.remove(GONE.keySet().iterator().next());
            }
            GONE.put(key, clock);
            if (SEEDS.size() < 64) {
                SEEDS.add(key);
            }
        }
    }

    private static void add(long key) {
        if (PRESENT.size() >= MAX_LOGS) {
            PRESENT.remove(PRESENT.iterator().next());
        }
        PRESENT.add(key);
        dirty = true;
    }

    /** A new world drops what the old one tracked. */
    private static void bind(Object lv) {
        if (level != lv) {
            level = lv;
            GONE.clear();
            PRESENT.clear();
            SEEDS.clear();
            dirty = true;
        }
    }

    private static void tick(Minecraft mc) {
        clock++;
        if (mc.level == null || mc.player == null) {
            return;
        }
        bind(mc.level);
        if (GONE.isEmpty() && !dirty) {
            return;
        }
        if (!dirty && clock % 10 != 0) {
            return;
        }
        dirty = false;
        style = CFG.num("style");
        for (long seed : SEEDS) {
            seed(mc, BlockPos.of(seed));
        }
        SEEDS.clear();
        // An expired chop drops its tree; a tracked log back as chunk data (no block update) joins the highlight.
        Set<Long> columns = new HashSet<>();
        for (Iterator<Map.Entry<Long, Integer>> it = GONE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            if (clock - e.getValue() > MAX_AGE) {
                it.remove();
                continue;
            }
            BlockPos g = BlockPos.of(e.getKey());
            columns.add(column(g.getX() >> 4, g.getZ() >> 4));
            if (!PRESENT.contains(e.getKey()) && mc.level.getBlockState(g).is(BlockTags.LOGS)) {
                add(e.getKey());
            }
        }
        AABB[] b = new AABB[Math.min(PRESENT.size(), MAX_DRAWN)];
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int n = 0;
        // A standing log is drawn while it is a log and a tracked chop is in its chunk column or a neighbor.
        for (Iterator<Long> it = PRESENT.iterator(); it.hasNext(); ) {
            BlockPos p = BlockPos.of(it.next());
            if (!mc.level.getBlockState(p).is(BlockTags.LOGS) || !nearColumn(columns, p)) {
                it.remove();
                continue;
            }
            double dx = p.getX() + 0.5 - px, dy = p.getY() + 0.5 - py, dz = p.getZ() + 0.5 - pz;
            if (n < b.length && dx * dx + dy * dy + dz * dz <= RANGE_SQ) {
                b[n++] = style == 3 ? new AABB(p).inflate(0.002) : new AABB(p);
            }
        }
        int argb = CFG.num("color");
        float[] k = WorldRenderUtils.argbToFloats(argb == DEFAULT_COLOR ? ThemeAccent.accent(argb) : argb);
        float[] c = new float[n * 4];
        for (int i = 0; i < n; i++) {
            c[i * 4] = k[0];
            c[i * 4 + 1] = k[1];
            c[i * 4 + 2] = k[2];
            c[i * 4 + 3] = k[3] <= 0f ? 1f : k[3];
        }
        rgba = c;
        boxes = b;
        counted = n;
    }

    private static long column(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static boolean nearColumn(Set<Long> columns, BlockPos p) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (columns.contains(column((p.getX() >> 4) + dx, (p.getZ() >> 4) + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The tree that lost {@code from} (now air): every log joined to it, up to {@link #SEED_LOGS}. */
    private static void seed(Minecraft mc, BlockPos from) {
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        todo.add(from);
        int found = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        while (!todo.isEmpty() && found < SEED_LOGS) {
            BlockPos c = todo.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        q.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                        if (Math.abs(q.getX() - from.getX()) > SEED_RADIUS || Math.abs(q.getZ() - from.getZ()) > SEED_RADIUS
                                || Math.abs(q.getY() - from.getY()) > 2 * SEED_RADIUS
                                || PRESENT.contains(q.asLong()) || !seen.add(q.asLong())
                                || !mc.level.getBlockState(q).is(BlockTags.LOGS)) {
                            continue;
                        }
                        add(q.asLong());
                        todo.add(q.immutable());
                        found++;
                    }
                }
            }
        }
    }

    private static void render(LevelRenderContext context) {
        int n = counted;
        if (n <= 0 || !CFG.isEnabled()) {
            return;
        }
        if (style == 3) {
            // Recolor Block: an opaque-by-setting layer flush on the block's own six faces, depth-tested.
            WorldRenderUtils.renderFilledBoxes(context, boxes, rgba, n, 1f, false);
            recolorFrames++;
        } else {
            if (style < 2) {
                WorldRenderUtils.renderFilledBoxes(context, boxes, rgba, n, 0.3f, false);
                fillFrames++;
            }
            if (style > 0) {
                WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 1.5f, false);
                lineFrames++;
            }
        }
        boxFrames++;
    }

    // ---- testkit hooks

    public static int trackedLogs() {
        return GONE.size();
    }

    public static int presentLogs() {
        return PRESENT.size();
    }

    public static int drawnBoxes() {
        return counted;
    }

    public static int boxFrames() {
        return boxFrames;
    }

    /** Frames drawn per layer: fill quads, outline lines, recolor layer. */
    public static int[] layerFrames() {
        return new int[]{fillFrames, lineFrames, recolorFrames};
    }

    /** Test only: is this position in the drawn set. */
    public static boolean drawsAt(BlockPos p) {
        AABB[] b = boxes;
        for (int i = 0; i < counted; i++) {
            if (b[i].contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) {
                return true;
            }
        }
        return false;
    }

    /** Test only: act as if on Galatea. */
    public static void setTestIsland(boolean on) {
        testIsland = on;
    }

    /** Test only: age every tracked chop by this many ticks, so expiry can be checked without waiting. */
    public static void ageAll(int ticks) {
        GONE.replaceAll((k, v) -> v - ticks);
        dirty = true;
    }
}
