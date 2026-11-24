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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regenerating Trees (Galatea). What his recordings show (logger sessions of 11-23, 1,589 log positions): a log that is
 * chopped becomes air, and about 600-1000 ticks later (median 795, 30-50 s) the same log is put back by a block update,
 * in the order it was broken. So a felled tree is nothing but air until it returns, and the highlight outlines the logs
 * that left, until each one comes back or {@link #MAX_AGE} ticks pass. Event-driven from block updates: the old state is
 * read in {@code ClientLevel.setServerVerifiedBlockState} and {@code setBlock} (mixin, so a log he breaks himself counts too; a return that arrives as chunk data is caught by the 10-tick recheck), the boxes are rebuilt only when a log left or came
 * back, or twice a second for the distance cut, and drawn from that published array; nothing is allocated per frame.
 */
public final class TreeRegen {

    /** The saved color while the player has not picked one: it follows the menu theme's accent. */
    public static final int DEFAULT_COLOR = 0xFFCC6600;

    public static final IslandConfig CFG = IslandConfig.of("foraging", "treeregen")
            .num("color", DEFAULT_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE);

    /** Ticks a vanished log is still expected back (the 95th percentile of the returns is under 1,000). */
    static final int MAX_AGE = 1400;
    private static final int MAX_LOGS = 1500;
    private static final int MAX_DRAWN = 400;
    private static final double RANGE_SQ = 64.0 * 64.0;

    private static final Map<Long, Integer> GONE = new LinkedHashMap<>();
    private static int clock;
    private static boolean dirty;
    private static Object level;
    private static volatile AABB[] boxes = new AABB[0];
    private static volatile float[] rgba = new float[0];
    private static int boxFrames;
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
        if (is) {
            dirty |= GONE.remove(pos.asLong()) != null;
        } else if (now.isAir() && (testIsland || "GALATEA".equals(String.valueOf(IslandDetector.graphIsland())))) {
            if (GONE.size() >= MAX_LOGS) {
                GONE.remove(GONE.keySet().iterator().next());
            }
            GONE.put(pos.asLong(), clock);
            dirty = true;
        }
    }

    /** A new world drops what the old one tracked. */
    private static void bind(Object lv) {
        if (level != lv) {
            level = lv;
            GONE.clear();
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
        // Expired, or already a log again: a return can arrive as chunk data, with no block update to hook.
        for (Iterator<Map.Entry<Long, Integer>> it = GONE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            if (clock - e.getValue() > MAX_AGE || mc.level.getBlockState(BlockPos.of(e.getKey())).is(BlockTags.LOGS)) {
                it.remove();
            }
        }
        AABB[] b = new AABB[Math.min(GONE.size(), MAX_DRAWN)];
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int n = 0;
        for (long l : GONE.keySet()) {
            BlockPos p = BlockPos.of(l);
            double dx = p.getX() + 0.5 - px, dy = p.getY() + 0.5 - py, dz = p.getZ() + 0.5 - pz;
            if (n < b.length && dx * dx + dy * dy + dz * dz <= RANGE_SQ) {
                b[n++] = new AABB(p);
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

    private static void render(LevelRenderContext context) {
        int n = counted;
        if (n > 0 && CFG.isEnabled()) {
            WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 1.5f, false);
            boxFrames++;
        }
    }

    // ---- testkit hooks

    public static int trackedLogs() {
        return GONE.size();
    }

    public static int drawnBoxes() {
        return counted;
    }

    public static int boxFrames() {
        return boxFrames;
    }

    /** Test only: act as if on Galatea. */
    public static void setTestIsland(boolean on) {
        testIsland = on;
    }

    /** Test only: age every tracked log by this many ticks, so expiry can be checked without waiting. */
    public static void ageAll(int ticks) {
        GONE.replaceAll((k, v) -> v - ticks);
        dirty = true;
    }
}
