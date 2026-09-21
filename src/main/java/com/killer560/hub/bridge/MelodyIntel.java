package com.killer560.hub.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Melody terminal progress, keyed by player name, from every source this mod knows: Odin users (spec 3.4,
 * slot meanings resolved in {@link BridgeTables}), our own relay's users
 * ({@code PartyDataFeature}'s {@code dg.v1.melody}), and - since {@code com.killer560.hub.melody} shipped
 * (2026-09-21) - our own local player's own real-time reading of their own open terminal
 * ({@code MelodyTrackerFeature}), stored here under their own real name so the Team Melody HUD, and
 * {@code OdinAdapter}'s outbound half, both have one place to read. Client thread only.
 * <p>
 * Shown on the Party Interop tab's bridge status lines (Odin-sourced only, historically) and on the Team
 * Melody HUD ({@code com.killer560.hub.melody.MelodyTrackerFeature.HUD}), which reads {@link #get} for every
 * current teammate.
 */
public final class MelodyIntel {

    /** @param clayRow   1-4, or -1 unknown (Odin type 1)
     *  @param target    0-4 target column, or -1 (type 2, purple)
     *  @param current   0-4 moving column, or -1 (type 5, lime pane) */
    public record Progress(String player, int clayRow, int target, int current, long atMs) {
        /** Odin's own label: clay row 1 = 0%, 2 = 25%, 3 = 50%, 4 = 75% ({@code clayProgress}). */
        public int percent() {
            return BridgeTables.melodyPercentForClayRow(clayRow);
        }
    }

    private static final int MAX_PLAYERS = 8;
    private static final Map<String, Progress> BY_PLAYER = new LinkedHashMap<>();

    private MelodyIntel() {
    }

    /** Public (2026-09-21, was package-private) so {@code com.killer560.hub.partydata} (relay-received
     *  teammate progress) and {@code com.killer560.hub.melody} (our own local reading) can feed the same
     *  store {@code BridgeFeature.applyOdin} already does - one place, three sources, never re-derived. */
    public static void offer(String player, int type, int slot) {
        String key = player.toLowerCase(Locale.ROOT);
        if (type == BridgeTables.MELODY_TYPE_REMOVE) {
            BY_PLAYER.remove(key);
            return;
        }
        Progress held = BY_PLAYER.get(key);
        int clay = held == null ? -1 : held.clayRow();
        int target = held == null ? -1 : held.target();
        int current = held == null ? -1 : held.current();
        switch (type) {
            case BridgeTables.MELODY_TYPE_CLAY -> clay = slot;
            case BridgeTables.MELODY_TYPE_PURPLE -> target = slot;
            case BridgeTables.MELODY_TYPE_PANE -> current = slot;
            default -> {
                return;
            }
        }
        BY_PLAYER.put(key, new Progress(player, clay, target, current, System.currentTimeMillis()));
        while (BY_PLAYER.size() > MAX_PLAYERS) {
            BY_PLAYER.remove(BY_PLAYER.keySet().iterator().next());
        }
    }

    static void clear() {
        BY_PLAYER.clear();
    }

    public static List<Progress> snapshot() {
        return new ArrayList<>(BY_PLAYER.values());
    }

    /** @return the known progress for this exact player name (case-insensitive), or null if none yet. Used
     *  by the Team Melody HUD to look up each of {@code PartyTracker.teammates()} in turn. */
    public static Progress get(String player) {
        return player == null ? null : BY_PLAYER.get(player.toLowerCase(Locale.ROOT));
    }
}
