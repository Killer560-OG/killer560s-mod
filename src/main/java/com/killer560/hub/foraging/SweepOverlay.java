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
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
 * fewest swings ({@link SweepRule#plan}) whose picks he can hit from the ground ({@link SweepReach}; hard picks dim), the
 * next one to hit also filled, and once he broke a log there on every tree of that kind ({@link SweepForest}). Cached: the sets are rebuilt only when the
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
    private static volatile AABB[] optHard = new AABB[0];
    private static volatile float[] optHardRgba = new float[4];
    private static volatile int optEasy;
    private static boolean forest;
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
        AttackBlockCallback.EVENT.register((player, lv, hand, pos, dir) -> {
            int k;
            if (lv.isClientSide() && galatea && OPT.isEnabledRaw() && (k = SweepRule.kind(lv.getBlockState(pos).getBlock())) >= 0) {
                SweepForest.onAttack(pos.asLong(), k, lv.getGameTime());
            }
            return InteractionResult.PASS;
        });
    }

    /** A block the server set (from the level mixin, before it is applied): any log change dirties the cache. */
    public static void onChange(ClientLevel lv, BlockPos pos, BlockState old, BlockState now) {
        int ko = SweepRule.kind(old.getBlock()), kn = SweepRule.kind(now.getBlock());
        if ((CFG.isEnabledRaw() || OPT.isEnabledRaw()) && (ko >= 0 || kn >= 0)) {
            version++;
            if (OPT.isEnabledRaw() && lv == level) {
                SweepForest.onChange(pos.asLong(), ko, kn, lv.getGameTime());
            }
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
            SweepForest.reset();
            setOv(new AABB[0]);
            setOpt(new AABB[0], new AABB[0], new AABB[0]);
        }
        if (gt % 20 == 0) {
            galatea = testIsland || "GALATEA".equals(String.valueOf(IslandDetector.graphIsland()));
            tabSweep = galatea ? readSweep(mc) : 0;
        }
        if (!galatea) {
            if (SweepForest.kind() >= 0) {
                SweepForest.reset();
            }
            if (ovCount + optCount > 0) {
                setOv(new AABB[0]);
                setOpt(new AABB[0], new AABB[0], new AABB[0]);
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
        int fk = opt ? SweepForest.kind() : -1;
        if (fk >= 0) {
            forest = optReady = true;
            if (SweepForest.tick(mc, gt, SweepRule.logs(fk, sw, thrown))) {
                AABB[][] b = SweepForest.boxes();
                plan = new long[0];
                setOpt(b[0], b[1], b[2]);
            }
            return;
        }
        if (forest) {
            forest = optReady = false;   // back to the tree he is at
        }
        boolean sameTree = optReady && (lastSeed == optKeySeed || planTree.contains(lastSeed));
        boolean same = sameTree && sw == optSweep && thrown == optThrown && opt == optOn;
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
        long[] seeds = new long[(sameTree ? plan.length : 0) + 1];
        seeds[0] = lastSeed;
        if (sameTree) {   // a Sweep change keeps the tree even when the log he looked at is already felled
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
        int easy = 0;
        if (k < 0) {
            plan = new long[0];
        } else {
            Long2IntOpenHashMap r = SweepReach.classes(mc, tree, mc.player.blockPosition());
            plan = SweepRule.plan(tree.toLongArray(), SweepRule.logs(k, sw, thrown), r::get);
            while (easy < plan.length && r.get(plan[easy]) <= SweepReach.GROUND) {
                easy++;
            }
        }
        AABB[] all = boxes(plan);
        setOpt(java.util.Arrays.copyOf(all, easy), java.util.Arrays.copyOfRange(all, easy, all.length),
                all.length > 0 ? new AABB[]{all[0]} : all);
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

    /** Easy picks, hard picks (no easy set fells the rest: drawn dim and thin) and the next pick of each tree. */
    private static void setOpt(AABB[] easy, AABB[] hard, AABB[] next) {
        int n = Math.max(easy.length, Math.max(hard.length, next.length));
        optRgba = colors(OPT.num("color"), n);
        optHardRgba = colors(OPT.num("color"), hard.length, 0.35f);
        optBoxes = easy;
        optHard = hard;
        optNext = next;
        optEasy = easy.length;
        optCount = easy.length + hard.length;
    }

    private static float[] colors(int argb, int n) {
        return colors(argb, n, 1f);
    }

    private static float[] colors(int argb, int n, float dim) {
        float[] k = WorldRenderUtils.argbToFloats(argb == DEFAULT_COLOR ? ThemeAccent.accent(argb) : argb);
        float[] c = new float[Math.max(n, 1) * 4];
        for (int i = 0; i < n; i++) {
            c[i * 4] = k[0];
            c[i * 4 + 1] = k[1];
            c[i * 4 + 2] = k[2];
            c[i * 4 + 3] = (k[3] <= 0f ? 1f : k[3]) * dim;
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
            AABB[] hard = optHard;
            WorldRenderUtils.renderOutlineBoxes(ctx, optBoxes, optRgba, optEasy, 2f, false);
            WorldRenderUtils.renderOutlineBoxes(ctx, hard, optHardRgba, hard.length, 1f, false);
            WorldRenderUtils.renderFilledBoxes(ctx, optNext, optRgba, optNext.length, 0.35f, false);
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

    /** Plan picks no ground spot reaches (drawn dim). */
    public static int optimalHard() {
        return optCount - optEasy;
    }

    /** The tree kind he last broke on this island (every tree of it is planned), or -1. */
    public static int forestKind() {
        return SweepForest.kind();
    }

    /** Every planned tree's plan while {@link #forestKind} is set. */
    public static long[][] forestPlans() {
        return SweepForest.plans();
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
