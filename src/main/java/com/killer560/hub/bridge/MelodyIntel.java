package com.killer560.hub.bridge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Melody terminal progress, keyed by player name, from every source this mod knows: Odin users (spec 3.4,
 * slot meanings resolved in {@link BridgeTables}), our own relay's users
 * ({@code PartyDataFeature}'s {@code dg.v1.melody}), our own local player's reading of their own open terminal
 * ({@code MelodyTrackerFeature}), and (2026-11-01) party-chat progress lines ("Melody 1/3 - 33%", "Melody 67%") from
 * teammates on no shared socket at all. Client thread only.
 * <p>
 * A relay or Odin source carries the board itself (target column, moving column, button row), so the Team Melody HUD
 * draws that player's board; a chat-only source carries just a percent, drawn as a bar.
 * <p>
 * <b>Lifetime (killer560, 2026-11-01: "once someone finishes melody it stays there for forever it never goes to 100%.
 * Also make a timeout phase so if a section ends then it removes it or at max it is there for 15s without an
 * update").</b> A finished Melody reads 100% ({@link #markDone}: our relay's done message, or the player's own
 * "activated a terminal!" line while their Melody entry is live). An entry with no update for {@link #STALE_MS} is
 * dropped ({@link #expire}), every entry is dropped when the P3 section changes ({@link #clear}, from
 * {@code MelodyTrackerFeature}), and an Odin "forget" without a completion line is dropped after
 * {@link #CLOSED_GRACE_MS}.
 */
public final class MelodyIntel {

    /** No update for this long and an entry is gone. */
    public static final long STALE_MS = 15_000L;
    /** An Odin user closed the terminal: kept this long for the completion line that says it was finished. */
    public static final long CLOSED_GRACE_MS = 2_500L;

    /** @param clayRow     1-4, or -1 unknown (Odin type 1)
     *  @param target      0-4 target column, or -1 (type 2, purple)
     *  @param current     0-4 moving column, or -1 (type 5, lime pane)
     *  @param atMs        the last update
     *  @param done        finished: reads 100%
     *  @param chatPercent percent from a party-chat progress line, or -1
     *  @param closedAtMs  when an Odin "forget" arrived without a completion, or 0 */
    public record Progress(String player, int clayRow, int target, int current, long atMs, boolean done, int chatPercent,
                           long closedAtMs) {
        /** 100 when done; else from the button row (Odin's clayProgress, scaled to the row count); else the chat percent. */
        public int percent() {
            if (done) {
                return 100;
            }
            int fromRow = BridgeTables.melodyPercentForClayRow(clayRow);
            return fromRow >= 0 ? fromRow : chatPercent;
        }

        /** True when this source showed the board itself (a teammate on this mod or Odin), not just a chat percent. */
        public boolean hasBoard() {
            return target >= 0 || current >= 0;
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
        long now = System.currentTimeMillis();
        Progress held = BY_PLAYER.get(key);
        if (type == BridgeTables.MELODY_TYPE_REMOVE) {
            // Odin sends this when the terminal closes, finished or not: wait a moment for the completion line.
            if (held != null && !held.done()) {
                BY_PLAYER.put(key, new Progress(held.player(), held.clayRow(), held.target(), held.current(), held.atMs(),
                        false, held.chatPercent(), now));
            }
            return;
        }
        if (type == BridgeTables.MELODY_TYPE_DONE) {
            markDone(player);
            return;
        }
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
        put(key, new Progress(player, clay, target, current, now, false, held == null ? -1 : held.chatPercent(), 0L));
    }

    /** A party-chat progress line from {@code player} (a percent 0-99). A board source, when there is one, still wins. */
    public static void offerChat(String player, int percent) {
        if (percent < 0 || percent > 100) {
            return;
        }
        String key = player.toLowerCase(Locale.ROOT);
        Progress held = BY_PLAYER.get(key);
        long now = System.currentTimeMillis();
        if (held != null && held.done()) {
            return;
        }
        put(key, held == null
                ? new Progress(player, -1, -1, -1, now, false, percent, 0L)
                : new Progress(held.player(), held.clayRow(), held.target(), held.current(), now, false, percent, 0L));
    }

    /** {@code player} finished their Melody: 100% until the section ends or {@link #STALE_MS} passes. */
    public static void markDone(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        Progress held = BY_PLAYER.get(key);
        long now = System.currentTimeMillis();
        put(key, held == null
                ? new Progress(player, -1, -1, -1, now, true, 100, 0L)
                : new Progress(held.player(), held.clayRow(), held.target(), held.current(), now, true, 100, 0L));
    }

    /** "{@code player} activated a terminal!": if they have a live Melody entry, that terminal was the Melody. */
    public static void onTerminalCompleted(String player) {
        Progress held = get(player);
        if (held != null && !held.done() && System.currentTimeMillis() - held.atMs() <= STALE_MS) {
            markDone(held.player());
        }
    }

    private static void put(String key, Progress p) {
        BY_PLAYER.put(key, p);
        while (BY_PLAYER.size() > MAX_PLAYERS) {
            BY_PLAYER.remove(BY_PLAYER.keySet().iterator().next());
        }
    }

    /** Drops entries with no update for {@link #STALE_MS}, and Odin closes that no completion line followed. */
    public static void expire(long now) {
        Iterator<Progress> it = BY_PLAYER.values().iterator();
        while (it.hasNext()) {
            Progress p = it.next();
            if (now - p.atMs() > STALE_MS || (p.closedAtMs() > 0 && now - p.closedAtMs() > CLOSED_GRACE_MS)) {
                it.remove();
            }
        }
    }

    /** Every entry gone: a new P3 section, the Core opening, a new run. */
    public static void clear() {
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
