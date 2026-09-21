package com.killer560.hub.ticktimers;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Storm's crush timer (F7/M7 <b>Phase 2</b>): the purple-pad "step on it and crush" countdown/title, and the pad
 * outline render. The 20-tick pad-cycle counter itself ("Pad:") is NOT here - see the class doc below.
 *
 * <h2>2026-09-21 moved here from {@code f7spots.CrushTimer}</h2>
 * killer560, asked directly: "the pad timer and the purple-pad 'step on it and crush' timer currently live on
 * the F7 Spots tab... Move them to Tick Timers. One home per timer. F7 Spots keeps its waypoints; every countdown
 * lives on Tick Timers." F7 Spots keeps its walk waypoints, beams, labels and Last Breath aim spots; only this
 * class (and the "Storm Crush Timer (P2)" section that showed it) moved. Settings carried over from
 * {@code killer560smod-f7spots.json} - see {@link TickTimersConfig#migrateFromF7SpotsCrush()}.
 * <p>
 * <b>Duplication resolved by the move.</b> {@link TickTimersFeature} already ran its OWN repeating 20-server-tick
 * {@code padTickTime} (the "Storm" bundle's "Pad:" line) off the exact same two Storm P2 start/death chat lines
 * this class used to key its own separate {@code padTicks}/{@code padCycleRunning} cycle on - same trigger, same
 * 20-tick length, just implemented twice (already flagged as a known overlap in {@code TickTimersFeature}'s class
 * doc before this move, and confirmed here: the two P2 start/end lines below are byte-for-byte the same ones
 * {@code TickTimersFeature}'s {@code STORM_START_REGEX}/{@code STORM_END_REGEX} already matched). Per "there must
 * be ONE timer afterwards, not two", the duplicate copy is deleted: this class no longer tracks the pad cycle at
 * all. {@link TickTimersFeature#padTickTime} is now the ONE pad-cycle counter, split out from the "Storm" bundle
 * into its own {@link TickTimersConfig#isPadCycleTimer()} toggle so his old F7 Spots "Pad Cycle Timer" preference
 * still means something specific (just the pad line) after the merge, instead of dragging Lightning/PY/the Storm
 * counter along with it.
 *
 * <h2>What the mechanic actually is (checked, not assumed)</h2>
 * "Crush" is a <b>Storm (P2)</b> mechanic, not a Wither King / P5 one - the Wither King phase has no pads at all
 * (hypixelskyblock.minecraft.wiki/w/The_Wither_King). Storm's arena has diorite pillars, each with a matching
 * pressure pad on an outer corner platform; purple, green and yellow work, the fourth (red) is broken. A player
 * lures Storm under a raised pillar and a teammate steps on the matching pad to drop it on him, which stuns him.
 * Storm has to use Giga Lightning at least once before he can be crushed, and he has to be crushed under at least
 * two of the three pillars to progress (hypixelskyblock.minecraft.wiki/w/Storm and .../The_Catacombs_-_Floor_VII).
 * <p>
 * <b>Crush detection</b> uses the two lines Storm says when he is actually crushed - the same pair this mod's Fast
 * Leap already triggers its 1st/2nd crush auto-leaps on (QUOI {@code AutoLeap.kt}), and the same pair NoammAddons'
 * {@code F7Titles.kt} titles "Storm Crushed!" on:
 * <pre>[BOSS] Storm: Oof
 * [BOSS] Storm: Ouch, that hurt!</pre>
 * <p>
 * <b>Pad box</b>: the purple pad is {@code AABB(95, 165, 86 -> 123, 172, 103)}, green
 * {@code (24, 170, 4 -> 41, 172, 21)}, yellow {@code (24, 170, 86 -> 41, 172, 103)} - the P2 pad boxes this mod
 * already ships in {@code fastleap/FastLeapFeature.java} (ported from QUOI's {@code AutoLeap.kt}).
 *
 * <h2>What could NOT be confirmed - hence the configurable trigger</h2>
 * No source (wiki, forums, Odin, NoammAddons, Skytils, BloomCore) gives a real cooldown/interval for a pillar
 * coming back up, and there is no chat line that announces a pillar being ready. So the countdown is OFF by
 * default: with "Crush Interval" at 0 the HUD only counts UP since the last crush (and shows how many crushes
 * have landed). Set an interval and it counts down from every crush, and from any extra chat line killer560
 * puts in "Crush Trigger Text" (e.g. Storm's Giga Lightning line, if that turns out to be the real cue).
 */
public final class CrushTimer {

    /** QUOI AutoLeap's {@code STORM_CRUSH_MESSAGES} / NoammAddons F7Titles' "Storm Crushed!" pair. */
    private static final Set<String> CRUSH_LINES = Set.of("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!");

    // FastLeapFeature's P2 pad boxes (QUOI AutoLeap.kt).
    private static final AABB PURPLE_PAD = new AABB(95.0, 165.0, 86.0, 123.0, 172.0, 103.0);
    private static final AABB GREEN_PAD = new AABB(24.0, 170.0, 4.0, 41.0, 172.0, 21.0);
    private static final AABB YELLOW_PAD = new AABB(24.0, 170.0, 86.0, 41.0, 172.0, 103.0);

    private static long p2StartMs = 0L;
    private static long lastTriggerMs = 0L;
    private static int crushCount = 0;
    private static boolean readyTitleShown = true;

    private CrushTimer() {
    }

    static void reset() {
        p2StartMs = 0L;
        lastTriggerMs = 0L;
        crushCount = 0;
        readyTitleShown = true;
    }

    static void tick(Minecraft client) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        if (!Floor7Tracker.inF7Boss() || currentPhase() != Floor7Tracker.Phase.P2) {
            p2StartMs = 0L;
            return;
        }
        if (p2StartMs == 0L) {
            p2StartMs = System.currentTimeMillis();
        }
        float interval = cfg.getCrushIntervalSeconds();
        if (interval <= 0f || readyTitleShown || lastTriggerMs == 0L) {
            return;
        }
        if (remainingSeconds(interval) <= 0.0) {
            readyTitleShown = true;
            if (cfg.isCrushTitleEnabled()) {
                title("§a§lPad Ready");
            }
        }
    }

    static void onChat(String unformatted) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        if (!Floor7Tracker.inF7Boss()) {
            return;
        }
        boolean crushed = CRUSH_LINES.contains(unformatted);
        String extra = cfg.getCrushExtraTrigger();
        boolean extraHit = !extra.isBlank() && unformatted != null
                && unformatted.toLowerCase(Locale.ROOT).contains(extra.toLowerCase(Locale.ROOT));
        if (!crushed && !extraHit) {
            return;
        }
        lastTriggerMs = System.currentTimeMillis();
        if (crushed) {
            crushCount++;
        }
        readyTitleShown = cfg.getCrushIntervalSeconds() <= 0f;
        if (cfg.isCrushTitleEnabled() && crushed) {
            title("§d§lCrush " + crushCount);
        }
    }

    private static void title(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null) {
            return;
        }
        client.gui.setTimes(0, 25, 5);
        client.gui.setTitle(Component.literal(text));
    }

    private static double remainingSeconds(float interval) {
        return interval - (System.currentTimeMillis() - lastTriggerMs) / 1000.0;
    }

    /** @return the Crush interval/count HUD line, or null when there is nothing to show. The pad-cycle "Pad:"
     *  line is separate now - see {@link TickTimersFeature.TickTimersHudElement}. */
    static String hudText() {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        if (!Floor7Tracker.inF7Boss() || currentPhase() != Floor7Tracker.Phase.P2) {
            return null;
        }
        float interval = cfg.getCrushIntervalSeconds();
        String count = " §8(" + crushCount + ")";
        if (interval > 0f && lastTriggerMs != 0L) {
            double left = remainingSeconds(interval);
            if (left <= 0.0) {
                return "§6Crush: §aREADY" + count;
            }
            String color = left <= cfg.getCrushWarnSeconds() ? "§c" : "§e";
            return "§6Crush: " + color + String.format(Locale.US, "%.1fs", left) + count;
        }
        long since = lastTriggerMs != 0L ? lastTriggerMs : p2StartMs;
        if (since == 0L) {
            return "§6Crush: §7-" + count;
        }
        double elapsed = (System.currentTimeMillis() - since) / 1000.0;
        return "§6Crush: §e+" + String.format(Locale.US, "%.1fs", elapsed) + count;
    }

    /** Outlines the pads while you're in P2 (purple only unless "Show All Pads" is on). */
    static void renderPads(LevelRenderContext context) {
        if (currentPhase() != Floor7Tracker.Phase.P2) {
            return;
        }
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        float[] c = WorldRenderUtils.argbToFloats(cfg.getCrushPadColor());
        WorldRenderUtils.renderOutlineBox(context, PURPLE_PAD, c[0], c[1], c[2], 1f, 2f);
        if (!cfg.isCrushAllPads()) {
            return;
        }
        for (AABB pad : List.of(GREEN_PAD, YELLOW_PAD)) {
            WorldRenderUtils.renderOutlineBox(context, pad, c[0], c[1], c[2], 0.7f, 2f);
        }
    }

    /** Chat-driven phase first ({@link Floor7Tracker#getPhase()}), falling back to the y-level phase - same
     *  order {@code F7SpotsRenderer.currentPhase()} used before this class moved out of that package. */
    private static Floor7Tracker.Phase currentPhase() {
        Floor7Tracker.Phase phase = Floor7Tracker.getPhase();
        return phase == Floor7Tracker.Phase.UNKNOWN ? Floor7Tracker.getPhaseAt() : phase;
    }
}
