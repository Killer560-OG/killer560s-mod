package com.killer560.hub.f7spots;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F7 Spots - F7/M7 walk-to waypoints and Last Breath aim spots. Entry point; everything is default OFF
 * ({@link F7SpotsConfig}) and both coordinate lists ship empty.
 * <ul>
 * <li>{@link WalkWaypoint} - killer560's own "walk here" positions per phase/floor, box + beam + label + distance
 * ({@link F7SpotsRenderer}). Added in-game with "Add Waypoint Here" or by hand-editing the JSON.</li>
 * <li>{@link AimSpot} - aim markers per situation and per class; only NoammAddons' arrow-stack points ship
 * built in ({@link AimSpots}), everything else is killer560's to fill in.</li>
 * </ul>
 * <b>2026-09-21:</b> Storm's crush timer/pad highlight used to live here too ({@code CrushTimer}) - killer560
 * asked for it to move to Tick Timers ("One home per timer. F7 Spots keeps its waypoints; every countdown lives
 * on Tick Timers"), so it's now {@code ticktimers.CrushTimer}; this class no longer has a HUD element of its own.
 * <p>
 * Floor/phase detection is entirely reused: {@code DungeonState} for the floor, {@link Floor7Tracker} for the
 * boss phase (chat-driven, y-level fallback), and {@code witherdragons/P5State} for your dungeon class - so this
 * feature adds no detection of its own and works on p3sim.net wherever those already do.
 */
public final class F7SpotsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-f7spots");

    private F7SpotsFeature() {
    }

    public static void register() {
        F7SpotsConfig.getInstance();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!SkyblockGate.allows()) {
                return;
            }
            F7SpotsRenderer.render(context);
        });
        LOGGER.info("[F7Spots] Registered (all features default OFF, waypoint/aim lists empty)");
    }

    /** In the F7/M7 boss arena - {@link Floor7Tracker#inF7Boss()} (floor from DungeonState + arena bounds). */
    public static boolean inF7Boss() {
        return Floor7Tracker.inF7Boss();
    }
}
