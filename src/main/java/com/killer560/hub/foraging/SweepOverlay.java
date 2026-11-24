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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sweep Overlay and Highlight Optimal Blocks (Galatea). Both read {@link SweepRule}: the overlay outlines the logs the next
 * swing on the looked-at log fells; the optimal highlight boxes EVERY log of the plan that fells the tree he is at in the
 * fewest swings ({@link SweepRule#plan}), the next one to hit also filled. Cached: the sets are rebuilt only when the
 * looked-at log, the Sweep value, a setting or a log block anywhere (mixin hook; the plan waits 4 ticks) changes, and the Sweep stat is read from the tab list once a
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
    private static final long NONE = Long.MIN_VALUE;

    private static Object level;
    private static long lastGt = NONE;
    private static boolean galatea, testIsland;
    private static double tabSweep;
    private static int version;
    private static boolean ovReady, optReady;
    private static long keySeed = NONE, optKeySeed = NONE, optGt = NONE;
    private static double keySweep = -1, optSweep = -1;
    private static int keyVersion = -1, optVersion = -1;
    private static boolean keyThrown, keyOv, optThrown, optOn;
    private static long lastSeed = NONE;

    private static volatile AABB[] ovBoxes = new AABB[0];
    private static volatile AABB[] optBoxes = new AABB[0];
    private static volatile float[] ovRgba = new float[4];
    private static volatile float[] optRgba = new float[4];
    private static volatile AABB[] optNext = new AABB[0];
    private static int ovCount, optCount, frames, rebuilds;
    private static long[] lastSet = new long[0];
    private static long[] plan = new long[0];
    private static LongOpenHashSet planTree = new LongOpenHashSet();

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
            ovReady = optReady = false;
            tabSweep = 0;
            plan = new long[0];
            planTree = new LongOpenHashSet();
            setOv(new AABB[0]);
            setOpt(new AABB[0]);
        }
        if (gt % 20 == 0) {
            galatea = testIsland || "GALATEA".equals(String.valueOf(IslandDetector.graphIsland()));
            tabSweep = galatea ? readSweep(mc) : 0;
        }
        if (!galatea) {
            if (ovCount + optCount > 0) {
                setOv(new AABB[0]);
                setOpt(new AABB[0]);
                plan = new long[0];
                ovReady = optReady = false;
            }
            return;
        }
        double sw = CFG.num("sweep") > 0 ? CFG.num("sweep") : tabSweep;
        boolean thrown = CFG.on("thrown");
        long seed = NONE;
        if (mc.hitResult instanceof BlockHitResult b && mc.hitResult.getType() == HitResult.Type.BLOCK
                && kindAt(mc, b.getBlockPos().asLong()) >= 0) {
            seed = b.getBlockPos().asLong();
            lastSeed = seed;
        }
        boolean ov = CFG.isEnabled();
        if (!(ovReady && seed == keySeed && sw == keySweep && version == keyVersion && thrown == keyThrown && ov == keyOv)) {
            ovReady = true;
            keySeed = seed;
            keySweep = sw;
            keyVersion = version;
            keyThrown = thrown;
            keyOv = ov;
            rebuilds++;
            lastSet = ov && seed != NONE ? predict(mc, seed, sw, thrown) : new long[0];
            setOv(boxes(lastSet));
        }
        updatePlan(mc, gt, sw, thrown);
    }

    /**
     * The whole plan for the tree he is at, rebuilt only when the tree's blocks, the Sweep value or the tree change (a log
     * change is waited out 4 ticks, since a falling tree changes a log every 1 to 2 ticks). The tree is the logs joined to
     * the log he last looked at, plus those joined to the old plan's targets that still stand (a felled tree can split).
     */
    private static void updatePlan(Minecraft mc, long gt, double sw, boolean thrown) {
        boolean opt = OPT.isEnabled();
        boolean same = optReady && (lastSeed == optKeySeed || planTree.contains(lastSeed)) && sw == optSweep
                && thrown == optThrown && opt == optOn;
        if (same && (version == optVersion || gt - optGt < 4)) {
            return;
        }
        optReady = true;
        optKeySeed = lastSeed;
        optSweep = sw;
        optThrown = thrown;
        optOn = opt;
        optVersion = version;
        optGt = gt;
        rebuilds++;
        long[] seeds = new long[(same ? plan.length : 0) + 1];
        seeds[0] = lastSeed;
        if (same) {
            System.arraycopy(plan, 0, seeds, 1, plan.length);
        }
        LongOpenHashSet tree = new LongOpenHashSet();
        int k = -1;
        for (long s : opt ? seeds : new long[0]) {
            int ks = s == NONE ? -1 : kindAt(mc, s);
            if (ks >= 0 && !tree.contains(s) && (k < 0 || ks == k)) {
                k = ks;
                final int kk = ks;
                for (long c : SweepRule.fill(s, TREE_CAP, q -> kindAt(mc, q) == kk)) {
                    tree.add(c);
                }
            }
        }
        planTree = tree;
        plan = k < 0 ? new long[0] : SweepRule.plan(tree.toLongArray(), SweepRule.logs(k, sw, thrown));
        setOpt(boxes(plan));
    }

    /** The logs a swing on {@code seed} fells at this Sweep (empty for a non-log). */
    private static long[] predict(Minecraft mc, long seed, double sweep, boolean thrown) {
        int k = kindAt(mc, seed);
        return SweepRule.fill(seed, SweepRule.logs(k, sweep, thrown), q -> kindAt(mc, q) == k);
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

    private static void setOv(AABB[] a) {
        ovRgba = colors(CFG.num("color"), a.length);
        ovBoxes = a;
        ovCount = a.length;
    }

    private static void setOpt(AABB[] o) {
        optRgba = colors(OPT.num("color"), o.length);
        optBoxes = o;
        optNext = o.length > 0 ? new AABB[]{o[0]} : o;
        optCount = o.length;
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
            WorldRenderUtils.renderOutlineBoxes(ctx, optBoxes, optRgba, optCount, 2f, false);
            WorldRenderUtils.renderFilledBoxes(ctx, optNext, optRgba, 1, 0.35f, false);
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

    /** The next log to hit (first of the plan), or none. */
    public static long optimalPos() {
        return plan.length > 0 ? plan[0] : NONE;
    }

    /** The whole plan, next log first. */
    public static long[] optimalPlan() {
        return plan.clone();
    }

    /** Test only: act as if on Galatea. */
    public static void setTestIsland(boolean on) {
        testIsland = on;
        galatea = on;
        ovReady = optReady = false;
    }

    /** Test only: the predicted set for a log, through the same path the overlay uses. */
    public static long[] predictAt(long seed, double sweep, boolean thrown) {
        return predict(Minecraft.getInstance(), seed, sweep, thrown);
    }
}
