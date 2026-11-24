package com.killer560.hub.foraging;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.TabText;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sweep Overlay and Highlight Optimal Blocks (Galatea). Both read {@link SweepRule}: the overlay outlines the logs the next
 * swing on the looked-at log fells; the optimal highlight boxes the log within reach of the tree he is at whose swing fells
 * the most (ties: the nearest). Cached: the sets are rebuilt only when the looked-at log, the Sweep value, a setting, a log
 * block anywhere (mixin hook) or a 3-block step of the player changes, and the Sweep stat is read from the tab list once a
 * second. Render draws the published arrays only. The wrong-order rule (50 percent fewer) is not modeled: trunk versus
 * branch cannot be told from the blocks.
 */
public final class SweepOverlay {

    public static final int DEFAULT_COLOR = 0xFFCC6600;

    public static final IslandConfig CFG = IslandConfig.of("foraging", "sweepoverlay")
            .num("color", DEFAULT_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .num("sweep", 0, 0, 3000)
            .flag("thrown", false);

    public static final IslandConfig OPT = IslandConfig.of("foraging", "sweepoptimal")
            .num("color", DEFAULT_COLOR, Integer.MIN_VALUE, Integer.MAX_VALUE);

    private static final Pattern SWEEP = Pattern.compile("Sweep:\\D*?([\\d,]+(?:\\.\\d+)?)");
    private static final int TREE_CAP = 300;
    private static final double REACH_SQ = 36.0;
    private static final long NONE = Long.MIN_VALUE;

    private static Object level;
    private static long lastGt = NONE;
    private static boolean galatea, testIsland;
    private static double tabSweep;
    private static int version;
    private static boolean ready;
    private static long keySeed = NONE, keyLast = NONE;
    private static double keySweep = -1;
    private static int keyVersion = -1, keyPx, keyPy, keyPz;
    private static boolean keyThrown, keyOv, keyOpt;
    private static long lastSeed = NONE;

    private static volatile AABB[] ovBoxes = new AABB[0];
    private static volatile AABB[] optBoxes = new AABB[0];
    private static volatile float[] ovRgba = new float[4];
    private static volatile float[] optRgba = new float[4];
    private static int ovCount, optCount, frames, rebuilds;
    private static long[] lastSet = new long[0];
    private static long lastBest = NONE;

    private SweepOverlay() {
    }

    public static void register() {
        int b1 = FeatureBits.register("Sweep Overlay", CFG::isEnabledRaw);
        int b2 = FeatureBits.register("Highlight Optimal Blocks", OPT::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SweepOverlay.tick", b1, SweepOverlay::update));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SweepOptimal.tick", b2, SweepOverlay::update));
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(b1, ctx -> draw(ctx, true)));
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(b2, ctx -> draw(ctx, false)));
    }

    /** A block the server set (from the level mixin, before it is applied): any log change dirties the cache. */
    public static void onChange(BlockState old, BlockState now) {
        if ((CFG.isEnabledRaw() || OPT.isEnabledRaw()) && (SweepRule.kind(old.getBlock()) >= 0 || SweepRule.kind(now.getBlock()) >= 0)) {
            version++;
        }
    }

    private static void update(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        long gt = mc.level.getGameTime();
        if (gt == lastGt) {
            return;
        }
        lastGt = gt;
        if (level != mc.level) {
            level = mc.level;
            lastSeed = NONE;
            ready = false;
            tabSweep = 0;
            publish(new AABB[0], 0, new AABB[0], 0);
        }
        if (gt % 20 == 0) {
            galatea = testIsland || "GALATEA".equals(String.valueOf(IslandDetector.graphIsland()));
            tabSweep = galatea ? readSweep(mc) : 0;
        }
        if (!galatea) {
            if (ovCount + optCount > 0) {
                publish(new AABB[0], 0, new AABB[0], 0);
                ready = false;
            }
            return;
        }
        boolean ov = CFG.isEnabled(), opt = OPT.isEnabled();
        double sw = CFG.num("sweep") > 0 ? CFG.num("sweep") : tabSweep;
        boolean thrown = CFG.on("thrown");
        long seed = NONE;
        if (mc.hitResult instanceof BlockHitResult b && mc.hitResult.getType() == HitResult.Type.BLOCK
                && kindAt(mc, b.getBlockPos().asLong()) >= 0) {
            seed = b.getBlockPos().asLong();
            lastSeed = seed;
        }
        BlockPos pp = mc.player.blockPosition();
        boolean moved = opt && (Math.abs(pp.getX() - keyPx) >= 3 || Math.abs(pp.getY() - keyPy) >= 3 || Math.abs(pp.getZ() - keyPz) >= 3);
        if (ready && seed == keySeed && lastSeed == keyLast && sw == keySweep && version == keyVersion && thrown == keyThrown
                && ov == keyOv && opt == keyOpt && !moved) {
            return;
        }
        ready = true;
        keySeed = seed;
        keyLast = lastSeed;
        keySweep = sw;
        keyVersion = version;
        keyThrown = thrown;
        keyOv = ov;
        keyOpt = opt;
        keyPx = pp.getX();
        keyPy = pp.getY();
        keyPz = pp.getZ();
        rebuilds++;
        AABB[] a = new AABB[0], o = new AABB[0];
        lastSet = new long[0];
        if (ov && seed != NONE) {
            lastSet = predict(mc, seed, sw, thrown);
            a = boxes(lastSet);
        }
        lastBest = NONE;
        if (opt && lastSeed != NONE) {
            lastBest = best(mc, lastSeed, sw, thrown);
            if (lastBest != NONE) {
                o = new AABB[]{new AABB(BlockPos.of(lastBest))};
            }
        }
        publish(a, a.length, o, o.length);
    }

    /** The logs a swing on {@code seed} fells at this Sweep (empty for a non-log). */
    private static long[] predict(Minecraft mc, long seed, double sweep, boolean thrown) {
        int k = kindAt(mc, seed);
        return SweepRule.fill(seed, SweepRule.logs(k, sweep, thrown), q -> kindAt(mc, q) == k);
    }

    /** The log near him whose swing fells the most logs of the tree (ties: nearest), or NONE. */
    private static long best(Minecraft mc, long seed, double sweep, boolean thrown) {
        int k = kindAt(mc, seed);
        if (k < 0) {
            return NONE;
        }
        int n = SweepRule.logs(k, sweep, thrown);
        double ex = mc.player.getX(), ey = mc.player.getEyeY(), ez = mc.player.getZ();
        long bestPos = NONE;
        int bestN = 0;
        double bestD = 0;
        for (long c : SweepRule.fill(seed, TREE_CAP, q -> kindAt(mc, q) == k)) {
            double d = BlockPos.of(c).distToCenterSqr(ex, ey, ez);
            if (d > REACH_SQ) {
                continue;
            }
            int size = SweepRule.fill(c, n, q -> kindAt(mc, q) == k).length;
            if (size > bestN || (size == bestN && d < bestD)) {
                bestPos = c;
                bestN = size;
                bestD = d;
            }
        }
        return bestPos;
    }

    private static int kindAt(Minecraft mc, long pos) {
        return SweepRule.kind(mc.level.getBlockState(BlockPos.of(pos)).getBlock());
    }

    private static AABB[] boxes(long[] set) {
        AABB[] b = new AABB[set.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = new AABB(BlockPos.of(set[i]));
        }
        return b;
    }

    private static void publish(AABB[] a, int an, AABB[] o, int on) {
        ovRgba = colors(CFG.num("color"), an);
        optRgba = colors(OPT.num("color"), on);
        ovBoxes = a;
        optBoxes = o;
        ovCount = an;
        optCount = on;
    }

    private static float[] colors(int argb, int n) {
        float[] k = WorldRenderUtils.argbToFloats(argb == DEFAULT_COLOR ? ThemeAccent.accent(argb) : argb);
        float[] c = new float[Math.max(n, 1) * 4];
        for (int i = 0; i < n; i++) {
            c[i * 4] = k[0];
            c[i * 4 + 1] = k[1];
            c[i * 4 + 2] = k[2];
            c[i * 4 + 3] = k[3] <= 0f ? 1f : k[3];
        }
        return c;
    }

    /** The Sweep stat line of the tab list, 0 when it is not shown. */
    private static double readSweep(Minecraft mc) {
        if (mc.getConnection() == null) {
            return 0;
        }
        for (PlayerInfo pi : mc.getConnection().getListedOnlinePlayers()) {
            String t = TabText.plain(pi);
            if (t != null && t.contains("Sweep:")) {
                Matcher m = SWEEP.matcher(t);
                if (m.find()) {
                    try {
                        return Double.parseDouble(m.group(1).replace(",", ""));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    private static void draw(LevelRenderContext ctx, boolean overlay) {
        if (overlay) {
            if (ovCount > 0 && CFG.isEnabled()) {
                WorldRenderUtils.renderOutlineBoxes(ctx, ovBoxes, ovRgba, ovCount, 1.5f, false);
                frames++;
            }
        } else if (optCount > 0 && OPT.isEnabled()) {
            WorldRenderUtils.renderOutlineBoxes(ctx, optBoxes, optRgba, optCount, 4f, false);
            frames++;
        }
    }

    // ---- testkit hooks

    public static int overlayBoxes() {
        return ovCount;
    }

    public static int optimalBoxes() {
        return optCount;
    }

    public static int boxFrames() {
        return frames;
    }

    public static long optimalPos() {
        return lastBest;
    }

    /** Test only: act as if on Galatea. */
    public static void setTestIsland(boolean on) {
        testIsland = on;
        galatea = on;
        ready = false;
    }

    /** Test only: the predicted set for a log, through the same path the overlay uses. */
    public static long[] predictAt(long seed, double sweep, boolean thrown) {
        return predict(Minecraft.getInstance(), seed, sweep, thrown);
    }

    /** Test only: the optimal log for a tree, through the same path the overlay uses. */
    public static long bestAt(long seed, double sweep, boolean thrown) {
        return best(Minecraft.getInstance(), seed, sweep, thrown);
    }
}
