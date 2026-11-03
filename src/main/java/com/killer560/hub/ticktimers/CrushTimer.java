package com.killer560.hub.ticktimers;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * Storm's crush pads in F7/M7 <b>Phase 2</b>: the crush lines and the pad outline render.
 * <p>
 * Moved here from {@code f7spots.CrushTimer} on 2026-09-21 (killer560: "One home per timer"). On 2026-11-02 the crush
 * countdown, its count, its "Crush N" / "Pad Ready" titles and their settings (interval, warning, trigger text) were removed
 * (killer560: "for storm there should be no option for crush or storm there, remove those as settings"); Storm's timing
 * now lives in the one Tick Timers line, {@link StormLine}, which this class only tells about a crush. The pad outlines
 * stay.
 * <p>
 * Storm is crushed when a teammate steps on a pad while he is under its raised pillar; he says one of the two lines below
 * (QUOI {@code AutoLeap.kt}'s and NoammAddons {@code F7Titles}' pair). Pad boxes: the purple pad is
 * {@code AABB(95, 165, 86 -> 123, 172, 103)}, green {@code (24, 170, 4 -> 41, 172, 21)}, yellow
 * {@code (24, 170, 86 -> 41, 172, 103)} - the P2 pad boxes {@code fastleap/FastLeapFeature.java} ships (QUOI).
 */
public final class CrushTimer {

    /** QUOI AutoLeap's {@code STORM_CRUSH_MESSAGES} / NoammAddons F7Titles' "Storm Crushed!" pair. */
    static final Set<String> CRUSH_LINES = Set.of("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!");

    private static final AABB PURPLE_PAD = new AABB(95.0, 165.0, 86.0, 123.0, 172.0, 103.0);
    private static final AABB GREEN_PAD = new AABB(24.0, 170.0, 4.0, 41.0, 172.0, 21.0);
    private static final AABB YELLOW_PAD = new AABB(24.0, 170.0, 86.0, 41.0, 172.0, 103.0);

    private CrushTimer() {
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
        WorldRenderUtils.renderOutlineBox(context, GREEN_PAD, c[0], c[1], c[2], 0.7f, 2f);
        WorldRenderUtils.renderOutlineBox(context, YELLOW_PAD, c[0], c[1], c[2], 0.7f, 2f);
    }

    /** Chat-driven phase first ({@link Floor7Tracker#getPhase()}), falling back to the y-level phase - same
     *  order {@code F7SpotsRenderer.currentPhase()} used before this class moved out of that package. */
    private static Floor7Tracker.Phase currentPhase() {
        Floor7Tracker.Phase phase = Floor7Tracker.getPhase();
        return phase == Floor7Tracker.Phase.UNKNOWN ? Floor7Tracker.getPhaseAt() : phase;
    }
}
