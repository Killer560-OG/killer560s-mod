package com.killer560.hub.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Party mates' Melody terminal progress, as Odin users broadcast it (spec 3.4, slot meanings resolved in
 * {@link BridgeTables}). Client thread only.
 * <p>
 * <b>Gap:</b> nothing in this mod can display a teammate's Melody progress today - the terminal solver and
 * Termism only ever look at the player's own open terminal, and there is no Melody HUD. So this is stored and
 * shown on the Party Interop tab's bridge status lines only; a future Melody HUD (or the terminal timers) can
 * read {@link #snapshot()}.
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

    static void offer(String player, int type, int slot) {
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
}
