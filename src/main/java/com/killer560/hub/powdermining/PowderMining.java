package com.killer560.hub.powdermining;

import com.killer560.hub.miningqol.MiningState;

/**
 * Whether he is powder mining right now (killer560, 2026-11-03: "add an option to hide commissions while powder
 * mining"): in the Crystal Hollows, or within {@link #RECENT_MS} of a treasure chest being uncovered or looted. The
 * chest tracking only runs in the Crystal Hollows, so the recent-chest part keeps it true for two minutes after
 * leaving (a lobby swap, a quick trip out).
 */
public final class PowderMining {

    public static final long RECENT_MS = 120_000L;
    private static volatile long lastChestAt = -1L;
    private static volatile Boolean forced = null;

    private PowderMining() {
    }

    /** A treasure chest was uncovered or looted. */
    static void markChest() { lastChestAt = System.currentTimeMillis(); }

    public static boolean activeRecently() {
        Boolean f = forced;
        if (f != null) return f;
        if (MiningState.crystalHollows()) return true;
        long at = lastChestAt;
        return at >= 0 && System.currentTimeMillis() - at <= RECENT_MS;
    }

}
