package com.killer560.hub.miningqol;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commission HUD progress between tab refreshes (killer560, 2026-11-03: "the tab being slow to update commission
 * progress, I want you to interpolate that too so it feels like it updates every block mined").
 *
 * <p>Hypixel's Commissions widget refreshes every few seconds. Each mithril or titanium block he breaks that an open
 * commission counts adds one step to that commission's shown progress right away: the block must be the one he was
 * mining (the block under his crosshair while attack was held, within the last second) and the SERVER must change it
 * from that ore to something else (bedrock, air), so a break the server refused never counts. A block counts for an
 * area commission only if it lies in that area ({@link MinesAreaMap}, else the area he stands in), for "Mithril Miner" /
 * "Titanium Miner" anywhere on the island.
 *
 * <p>One step is 100 / goal percent. Goals (hypixelskyblock.minecraft.wiki, Commissions, read 2026-11-03): Mithril Miner
 * 350, an area's Mithril 250, Titanium Miner 15, an area's Titanium 10. The wiki also says Compact and Efficient Miner count
 * raw amounts and the starter commissions ask less (Mithril Miner 50 then 150, Titanium Miner 2), so the step is LEARNED
 * once the tab has moved with blocks counted in between ({@link #learnedStep}), and the learned step replaces the wiki one.
 * Gemstone, Glacite, Tungsten and Umber collectors count drops (fortune, Pristine), not blocks, and mob commissions are
 * left on the tab's own number.
 *
 * <p>Resync: a tab reading above the shown value moves it up at once. One below it is normal (the tab lags the blocks of
 * the last few seconds), so the shown value may lead the tab by the steps counted in the last {@link #LAG_MS} plus one;
 * beyond that it eases down one step per tick, never a visible jump back. It never reads 100% until the tab says DONE.
 * Client thread only; the HUD reads {@link #shown}.
 */
public final class CommissionProgress {

    private static final org.slf4j.Logger LOG = com.killer560.hub.util.ModLog.get("killer560smod-miningqol");
    static final long LAG_MS = 5000;
    private static final long TARGET_MS = 1000;
    private static final double MAX_SHOWN = 99.9;

    /** Per commission name. */
    private static final class Track {
        double tab = -1;        // last tab percent
        double shown = -1;
        double learnedStep = 0; // percent per counted block, 0 = not learned
        int countedSinceTab = 0;
        double firstTab = -1;   // tab percent when counting began
        int totalCounted = 0;
        long lastLogMs = 0;
        final ArrayDeque<Long> recent = new ArrayDeque<>();
    }

    private static final Map<String, Track> TRACKS = new HashMap<>();
    /** Blocks under his crosshair while attack was held: pos -> {state, time}. */
    private static final Map<BlockPos, Object[]> TARGETS = new HashMap<>();
    /** Ore blocks within {@link #SPREAD_RADIUS} of the block he is digging: pos -> {state, time seen}. */
    private static final Map<BlockPos, Object[]> NEIGHBOURS = new HashMap<>();
    private static final int SPREAD_RADIUS = 6;
    private static final long SPREAD_MS = 1500;
    private static final long NEIGHBOUR_MS = 4000;
    private static final double RESYNC_STEPS = 3;
    private static BlockPos snapCenter = null;
    private static long lastAimMs = 0;
    private static int spreadCounted = 0;
    private static int resyncs = 0;
    private static int version = 0;
    private static int counted = 0;
    /** Testkit: the hooks below drive the tab instead of the live tick. */
    private static volatile boolean holdForTest = false;

    private CommissionProgress() {
    }

    /** Client tick: remembers the block he is mining, reads the tab, eases. */
    static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.level != null && client.player != null && client.options.keyAttack.isDown()
                && client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockState st = client.level.getBlockState(hit.getBlockPos());
            if (oreOf(st) != null) {
                BlockPos hp = hit.getBlockPos().immutable();
                TARGETS.put(hp, new Object[]{st, now});
                lastAimMs = now;
                snapshotNeighbours(client, hp, now);
            }
        }
        TARGETS.values().removeIf(v -> now - (Long) v[1] > TARGET_MS);
        NEIGHBOURS.values().removeIf(v -> now - (Long) v[1] > NEIGHBOUR_MS);
        if (!holdForTest) {
            update(MiningState.commissions(), now);
        }
    }

    /** The tab's commissions at {@code now}: resync and ease each tracked one (testkit calls it too). */
    static void update(List<MiningState.Commission> coms, long now) {
        boolean changed = false;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (MiningState.Commission c : coms) {
            live.add(c.name());
            if (c.done() || stepWiki(c.name()) == 0) continue;
            double t = pct(c.progress());
            if (t < 0) continue;
            Track tr = TRACKS.computeIfAbsent(c.name(), k -> new Track());
            if (tr.tab < 0 || tr.shown < 0) {
                tr.tab = t;
                tr.shown = t;
                tr.firstTab = t;
                tr.totalCounted = 0;
                tr.countedSinceTab = 0;
                changed = true;
                continue;
            }
            if (t != tr.tab) {
                if (now - tr.lastLogMs >= 1000) {
                    tr.lastLogMs = now;
                    LOG.info("[CommissionProgress] refresh {}: predicted {} tab {} (was {}) counted {} (in lag window {}) step {}{}",
                            c.name(), format(tr.shown), format(t), format(tr.tab), tr.countedSinceTab, tr.recent.size(),
                            String.format(Locale.ROOT, "%.3f", step(c.name(), tr)), tr.learnedStep > 0 ? " learned" : "");
                }
                if (t < tr.tab - 1e-9) {
                    tr.shown = t; // a new commission under the same name
                    tr.firstTab = t;
                    tr.totalCounted = 0;
                    tr.recent.clear();
                } else {
                    learn(c.name(), tr, t);
                }
                tr.tab = t;
                tr.countedSinceTab = 0;
                changed = true;
            }
            while (!tr.recent.isEmpty() && now - tr.recent.peekFirst() > LAG_MS) {
                tr.recent.pollFirst();
            }
            double step = step(c.name(), tr);
            if (tr.shown < tr.tab) {
                tr.shown = tr.tab;
                changed = true;
            }
            double ceiling = Math.min(MAX_SHOWN, tr.tab + step * (tr.recent.size() + 1));
            if (tr.shown > ceiling + RESYNC_STEPS * step + 0.5) {
                // Too far off the tab (Mining Spread blocks counted that the tab never credited, a wrong step): back to the
                // tab's number plus what it has not caught up with yet, at once (killer560, 2026-11-03: "it needs to
                // resync sometimes with the main one if it gets too far off").
                tr.shown = Math.min(MAX_SHOWN, tr.tab + step * tr.recent.size());
                resyncs++;
                changed = true;
            } else if (tr.shown > ceiling + 1e-9) {
                tr.shown = Math.max(ceiling, tr.shown - step);
                changed = true;
            }
        }
        if (TRACKS.keySet().retainAll(live)) {
            changed = true;
        }
        if (changed) {
            version++;
        }
    }

    /** Remembers the ore around a newly dug block, so a Mining Spread break next to it can be told from any other change. */
    private static void snapshotNeighbours(Minecraft client, BlockPos center, long now) {
        if (center.equals(snapCenter) && !NEIGHBOURS.isEmpty()) return;
        snapCenter = center;
        for (BlockPos p : BlockScan.scan(client.level, center, SPREAD_RADIUS, SPREAD_RADIUS,
                s -> oreOf(s) != null, (pos, s) -> true, 3000)) {
            NEIGHBOURS.putIfAbsent(p, new Object[]{client.level.getBlockState(p), now});
        }
    }

    /** Ore breaks counted that were not under his crosshair (Mining Spread; testkit). */
    public static int spreadCounted() { return spreadCounted; }

    /** Times the shown value was snapped back to the tab for being too far off (testkit). */
    public static int resyncs() { return resyncs; }

    /** Testkit: a dig at {@code target} with ore {@code neighbours} next to it, as the live tick would remember them. */
    public static void aimForTest(BlockPos target, List<BlockPos> neighbours, long now) {
        var level = Minecraft.getInstance().level;
        TARGETS.put(target, new Object[]{level.getBlockState(target), now});
        lastAimMs = now;
        for (BlockPos p : neighbours) {
            NEIGHBOURS.put(p, new Object[]{level.getBlockState(p), now});
        }
    }

    /** A block the server changed (client thread, after the change; from MiningBlockUpdateMixin). */
    public static void onBlockChanged(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !MiningState.dwarvenMines()) return;
        Object[] t = TARGETS.get(pos);
        boolean spread = false;
        if (t == null) {
            // Mining Spread breaks the blocks around the one he dug in the same moment (killer560, 2026-11-03: it "doesn't
            // do a good job for when stuff is broken by mining spread"): an ore block seen near his target a moment ago
            // that the server now changes counts too, while he is still digging.
            long nowMs = System.currentTimeMillis();
            t = nowMs - lastAimMs <= SPREAD_MS ? NEIGHBOURS.get(pos) : null;
            if (t == null) return;
            spread = true;
        }
        BlockState was = (BlockState) t[0];
        BlockState now = client.level.getBlockState(pos);
        if (now.getBlock() == was.getBlock()) {
            return; // a refused break, or the same ore again
        }
        TARGETS.remove(pos);
        NEIGHBOURS.remove(pos);
        if (spread) {
            spreadCounted++;
        }
        String ore = oreOf(was);
        if (ore != null) {
            countBreak(ore, MinesAreaMap.areaAt(pos.getX(), pos.getY(), pos.getZ()), MiningState.area(),
                    MiningState.commissions(), System.currentTimeMillis());
        }
    }

    /** "Mithril", "Titanium", or null. */
    static String oreOf(BlockState s) {
        if (MithrilStone.isMithril(s)) return "Mithril";
        if (s != null && MiningHighlights.isTitanium(s)) return "Titanium";
        return null;
    }

    /** One counted break of {@code ore} in {@code blockArea} (null: unknown, so {@code playerArea}). */
    static void countBreak(String ore, String blockArea, String playerArea, List<MiningState.Commission> coms, long now) {
        String area = blockArea != null ? blockArea : playerArea;
        boolean any = false;
        for (MiningState.Commission c : coms) {
            if (c.done() || !counts(c.name(), ore, area)) continue;
            Track tr = TRACKS.get(c.name());
            if (tr == null || tr.shown < 0) continue;
            double step = step(c.name(), tr);
            tr.shown = Math.min(MAX_SHOWN, tr.shown + step);
            tr.countedSinceTab++;
            tr.totalCounted++;
            tr.recent.addLast(now);
            any = true;
        }
        if (any) {
            counted++;
            version++;
        }
    }

    /** Whether commission {@code name} counts a block of {@code ore} in {@code area}. */
    static boolean counts(String name, String ore, String area) {
        if (name.equals(ore + " Miner")) return true;
        if (!name.endsWith(" " + ore)) return false;
        for (String a : MiningState.COMMISSION_AREAS) {
            if (name.equals(a + " " + ore)) return area != null && MiningState.areaIs(area, a);
        }
        return false;
    }

    /** The wiki's step in percent, 0 for a commission not counted per block. */
    static double stepWiki(String name) {
        if (name.equals("Mithril Miner")) return 100.0 / 350;
        if (name.equals("Titanium Miner")) return 100.0 / 15;
        for (String a : MiningState.COMMISSION_AREAS) {
            if (name.equals(a + " Mithril")) return 100.0 / 250;
            if (name.equals(a + " Titanium")) return 100.0 / 10;
        }
        return 0;
    }

    /**
     * The step learned from the tab: everything the tab gained since counting began over the blocks counted, less those
     * of the last {@link #LAG_MS} (the tab has not shown them yet). Used only once 20 blocks are in it and only when it
     * is clearly not the wiki's (more than 25% off: Compact or Efficient Miner, a starter commission).
     */
    private static void learn(String name, Track tr, double t) {
        int settled = tr.totalCounted - tr.recent.size();
        if (settled < 20 || t <= tr.firstTab) return;
        double learned = (t - tr.firstTab) / settled;
        double wiki = stepWiki(name);
        tr.learnedStep = Math.abs(learned - wiki) > wiki * 0.25 ? learned : 0;
    }

    private static double step(String name, Track tr) { return tr.learnedStep > 0 ? tr.learnedStep : stepWiki(name); }

    /** "45%" -> 45, "12.5%" -> 12.5, else -1. */
    static double pct(String p) {
        if (p == null || !p.endsWith("%")) return -1;
        try {
            return Double.parseDouble(p.substring(0, p.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** What the HUD shows for {@code c}: the interpolated percent, or the tab's own text. */
    public static String shown(MiningState.Commission c) {
        if (c.done() || !MiningQolConfig.getInstance().isCommissionInterpolate()) return c.progress();
        Track tr = TRACKS.get(c.name());
        return tr == null || tr.shown < 0 ? c.progress() : format(tr.shown);
    }

    /** 42 -> "42%", 40.4 -> "40.4%" (one decimal, as the tab writes it). */
    static String format(double v) {
        double r = Math.floor(v * 10 + 1e-6) / 10;
        return Math.abs(r - Math.rint(r)) < 1e-9 ? (long) Math.rint(r) + "%" : String.format(Locale.ROOT, "%.1f%%", r);
    }

    static int version() { return version; }

    static void reset() {
        TRACKS.clear();
        TARGETS.clear();
        NEIGHBOURS.clear();
        snapCenter = null;
        version++;
    }


    /** Feeds the tab's commission lines ("Upper Mines Mithril: 40%") at {@code now}. */
    public static void tabForTest(List<String> lines, long now) { update(MiningState.parse(lines), now); }

    /** Counts a break of {@code ore} at {@code now} against the commissions in {@code lines}. */
    public static void breakForTest(String ore, String blockArea, String playerArea, List<String> lines, long now) {
        countBreak(ore, blockArea, playerArea, MiningState.parse(lines), now);
    }

    /** The shown text for commission {@code name} given the tab lines (null when not present). */
    public static String shownForTest(String name, List<String> lines) {
        for (MiningState.Commission c : MiningState.parse(lines)) {
            if (c.name().equals(name)) return shown(c);
        }
        return null;
    }

    public static void resetForTest() { reset(); }

    /** Testkit: while true the live tab is not read, so only the hooks' tab lines and times count. */
    public static void holdLiveForTest(boolean hold) { holdForTest = hold; }

    public static int countedBreaks() { return counted; }

    /** The block positions now remembered as being mined (testkit). */
    public static int targets() { return TARGETS.size(); }

    public static Double learnedStep(String name) {
        Track tr = TRACKS.get(name);
        return tr == null ? null : tr.learnedStep;
    }
}
