package com.killer560.hub.runstats;

import com.killer560.hub.dungeonclass.DungeonClass;

import java.util.Collections;
import java.util.List;

/**
 * One party member's per-run numbers, as printed by {@link RunStatsFeature}'s end-of-run summary.
 *
 * <p>Read this through {@link RunStatsTracker#lastRun()} rather than re-parsing chat: a {@code runsummary}
 * feature that wants per-player rows should consume these instead of building its own attribution.
 *
 * <p>What each field really is:
 * <ul>
 * <li>{@link #soloRooms} / {@link #stackedRooms} - <b>derived</b> room-clear attribution from the dungeon map
 *     (see {@link com.killer560.hub.livemap.RunStatsBridge}). Hypixel publishes no per-player room count, so a
 *     room finished while two people stood in it counts as "stacked" for both and is shown as a range
 *     ({@code solo}-{@code solo+stacked}), exactly like NoammAddons. {@code -1} means "not tracked".
 * <li>{@link #secrets} - lifetime-secret delta over the run from Hypixel's own player data (the
 *     {@code skyblock_treasure_hunter} achievement), not from the end-of-run chat page, which carries no
 *     per-player numbers at all. {@code -1} means "unknown" (API unavailable, or the option is off).
 * <li>{@link #deaths} - death reasons from the run's "{@code ☠}" chat lines, which <i>are</i> per player.
 * </ul>
 */
public record PlayerRunStats(String name, DungeonClass dungeonClass, int soloRooms, int stackedRooms, int secrets,
                             List<String> deaths) {

    public PlayerRunStats {
        deaths = deaths == null ? List.of() : List.copyOf(deaths);
    }

    public static PlayerRunStats unknown(String name) {
        return new PlayerRunStats(name, null, -1, -1, -1, Collections.emptyList());
    }

    public boolean hasRooms() {
        return soloRooms >= 0;
    }

    public boolean hasSecrets() {
        return secrets >= 0;
    }

    /** "{@code 4}" when nobody stacked with them, otherwise "{@code 4-7}" (certain - possible). */
    public String roomsLabel() {
        if (!hasRooms()) {
            return "?";
        }
        return stackedRooms <= 0 ? String.valueOf(soloRooms) : soloRooms + "-" + (soloRooms + stackedRooms);
    }
}
