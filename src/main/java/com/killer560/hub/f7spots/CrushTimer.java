package com.killer560.hub.f7spots;

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
 * Storm's crush timer (F7/M7 <b>Phase 2</b>) and the purple-pad highlight.
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

    // NoammAddons' "Storm Pad Timer" (features/impl/floor7/TickTimers.kt, local copy): padTickTime = 20 on P2's
    // start line, then decremented every SERVER tick and reset to 20 whenever it hits 0, i.e. a repeating 20-tick
    // (1s) pad cycle that runs for as long as Storm is up and is cleared on his death line. killer560 confirmed
    // (2026-09-15) this is the reference for the purple-pad countdown.
    private static final String P2_START_LINE = "[BOSS] Storm: Pathetic Maxor, just like expected.";
    private static final String P2_END_LINE = "[BOSS] Storm: I should have known that I stood no chance.";
    private static final int PAD_CYCLE_TICKS = 20;

    // FastLeapFeature's P2 pad boxes (QUOI AutoLeap.kt).
    private static final AABB PURPLE_PAD = new AABB(95.0, 165.0, 86.0, 123.0, 172.0, 103.0);
    private static final AABB GREEN_PAD = new AABB(24.0, 170.0, 4.0, 41.0, 172.0, 21.0);
    private static final AABB YELLOW_PAD = new AABB(24.0, 170.0, 86.0, 41.0, 172.0, 103.0);

    private static long p2StartMs = 0L;
    private static long lastTriggerMs = 0L;
    private static int crushCount = 0;
    private static boolean readyTitleShown = true;
    private static int padTicks = -1;
    private static boolean padCycleRunning = false;
    private static boolean tickSubscribed = false;

    private CrushTimer() {
    }

    static void reset() {
        padTicks = -1;
        padCycleRunning = false;
        p2StartMs = 0L;
        lastTriggerMs = 0L;
        crushCount = 0;
        readyTitleShown = true;
    }

    /** Subscribed to the shared server-tick clock so the pad cycle counts real server ticks, like NoammAddons. */
    static void ensureTickSubscribed() {
        if (tickSubscribed) {
            return;
        }
        tickSubscribed = true;
        com.killer560.hub.witherdragons.ServerTickClock.register();
        com.killer560.hub.witherdragons.ServerTickClock.subscribe(CrushTimer::onServerTick);
    }

    private static void onServerTick() {
        if (!padCycleRunning || padTicks < 0) {
            return;
        }
        padTicks--;
        if (padTicks <= 0) {
            padTicks = PAD_CYCLE_TICKS;
        }
    }

    /** Ticks remaining in the current 20-tick pad cycle, or -1 when the cycle isn't running. */
    static int padTicksLeft() {
        return padCycleRunning ? padTicks : -1;
    }

    static void tick(Minecraft client) {
        F7SpotsConfig cfg = F7SpotsConfig.getInstance();
        if (!F7SpotsFeature.inF7Boss() || F7SpotsRenderer.currentPhase() != Floor7Tracker.Phase.P2) {
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
        F7SpotsConfig cfg = F7SpotsConfig.getInstance();
        if (!F7SpotsFeature.inF7Boss()) {
            return;
        }
        if (P2_START_LINE.equals(unformatted)) {
            padTicks = PAD_CYCLE_TICKS;
            padCycleRunning = true;
            ensureTickSubscribed();
        } else if (P2_END_LINE.equals(unformatted)) {
            padCycleRunning = false;
            padTicks = -1;
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

    /** @return the HUD line, or null when there is nothing to show. */
    static String hudText() {
        F7SpotsConfig cfg = F7SpotsConfig.getInstance();
        if (!F7SpotsFeature.inF7Boss() || F7SpotsRenderer.currentPhase() != Floor7Tracker.Phase.P2) {
            return null;
        }
        float interval = cfg.getCrushIntervalSeconds();
        String count = " §8(" + crushCount + ")";
        if (cfg.isPadCycleTimer() && padCycleRunning && padTicks >= 0) {
            // NoammAddons' pad cycle: a repeating 20-server-tick window while Storm is up.
            String padColor = padTicks <= 5 ? "§a" : "§b";
            String pad = "§6Pad: " + padColor + padTicks + "t";
            if (interval <= 0f) {
                return pad + count;
            }
        }
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
    static void renderPads(LevelRenderContext context, F7SpotsConfig cfg) {
        if (F7SpotsRenderer.currentPhase() != Floor7Tracker.Phase.P2) {
            return;
        }
        float[] c = WorldRenderUtils.argbToFloats(cfg.getCrushPadColor());
        WorldRenderUtils.renderOutlineBox(context, PURPLE_PAD, c[0], c[1], c[2], 1f, 2f);
        if (!cfg.isCrushAllPads()) {
            return;
        }
        for (AABB pad : List.of(GREEN_PAD, YELLOW_PAD)) {
            WorldRenderUtils.renderOutlineBox(context, pad, c[0], c[1], c[2], 0.7f, 2f);
        }
    }
}
