package com.killer560.hub.splittimers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M7 Phase 5 split lines (killer560's M7 request: "split timers lines to the right ... relic stuff"). Filled by
 * {@code com.killer560.hub.witherdragons} (dragon spawn/kill, relic spawn/placement) and drawn by
 * {@link SplitTimersFeature.SplitTimersHudElement} in a column to the right of the normal split rows (or below
 * them). Timing sources mirror the reference mods:
 * <ul>
 * <li>Dragon line = spawn -&gt; kill, i.e. Odin's "X was alive for Ns" ({@code WitherDragonsEnum.setAlive/setDead},
 * https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/features/impl/boss/WitherDragonsEnum.kt).
 * A dragon still alive shows a live, grey time.
 * <li>Relic spawn = Necron's "All this, for nothing..." -&gt; first relic head equipped on an armor stand (Odin
 * {@code KingRelics.kt} "relic spawned in").
 * <li>Relic placed = same P5 start -&gt; placement (Odin "relic placed in" for yours; NoammAddons
 * {@code M7Relics.kt} armor-stand-at-cauldron check for the whole party).
 * </ul>
 * Cleared on every new run start / world change together with the normal splits.
 */
public final class P5Splits {

    private record DragonRow(String key, String label, int number, long spawnMs, long killMs) {
    }

    private record RelicRow(String key, String label, long placedMs, String who) {
    }

    private static final List<DragonRow> DRAGONS = new ArrayList<>();
    private static final List<RelicRow> RELICS = new ArrayList<>();
    private static long p5StartMs = 0L;
    private static long relicSpawnMs = 0L;

    private P5Splits() {
    }

    public static synchronized void reset() {
        DRAGONS.clear();
        RELICS.clear();
        p5StartMs = 0L;
        relicSpawnMs = 0L;
    }

    /** Necron's "All this, for nothing..." line. */
    public static synchronized void p5Started(long nowMs) {
        if (p5StartMs == 0L) {
            p5StartMs = nowMs;
        }
    }

    public static synchronized long p5StartMs() {
        return p5StartMs;
    }

    public static synchronized void dragonSpawned(String key, String coloredLabel, int number, long nowMs) {
        DRAGONS.add(new DragonRow(key, coloredLabel, number, nowMs, 0L));
    }

    /** Closes the newest still-open row for that dragon. */
    public static synchronized void dragonKilled(String key, long nowMs) {
        for (int i = DRAGONS.size() - 1; i >= 0; i--) {
            DragonRow row = DRAGONS.get(i);
            if (row.key().equals(key) && row.killMs() == 0L) {
                DRAGONS.set(i, new DragonRow(row.key(), row.label(), row.number(), row.spawnMs(), nowMs));
                return;
            }
        }
    }

    public static synchronized void relicSpawned(long nowMs) {
        if (relicSpawnMs == 0L && p5StartMs != 0L) {
            relicSpawnMs = nowMs;
        }
    }

    /** First placement per relic colour wins. {@code who} may be null. */
    public static synchronized void relicPlaced(String key, String coloredLabel, long nowMs, String who) {
        for (RelicRow row : RELICS) {
            if (row.key().equals(key)) {
                return;
            }
        }
        RELICS.add(new RelicRow(key, coloredLabel, nowMs, who));
    }

    public static synchronized boolean hasAny() {
        return !DRAGONS.isEmpty() || !RELICS.isEmpty() || relicSpawnMs != 0L;
    }

    /** HUD lines ("name§f: time"), dragons first then relics, filtered by the two Split Timers toggles. */
    public static synchronized List<String> lines(boolean dragons, boolean relics, boolean forChat) {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (dragons) {
            for (DragonRow row : DRAGONS) {
                boolean alive = row.killMs() == 0L;
                long t = (alive ? now : row.killMs()) - row.spawnMs();
                if (forChat && alive) {
                    continue;
                }
                out.add(row.label() + " §8#" + row.number() + "§f: " + (alive ? "§7" : "") + seconds(t));
            }
        }
        if (relics && p5StartMs != 0L) {
            if (relicSpawnMs != 0L) {
                out.add("§3Relic Spawn§f: " + seconds(relicSpawnMs - p5StartMs));
            }
            for (RelicRow row : RELICS) {
                out.add(row.label() + " Relic§f: " + seconds(row.placedMs() - p5StartMs)
                        + (forChat && row.who() != null ? " §7(" + row.who() + ")" : ""));
            }
        }
        return out;
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%.2fs", Math.max(0L, ms) / 1000.0);
    }
}
