package com.killer560.hub.bloodcamp;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.mixin.BossHealthOverlayAccessor;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

import java.util.Locale;
import java.util.LinkedHashSet;

/**
 * The Watcher's boss bar during the Blood Camp, read as the number of blood mobs the camp still needs (killer560,
 * 2026-11-02, F7 screenshot with the HUD on "Mobs Left: 1+" and the bar about 20% full: "if you can see that top bar that
 * is how many mobs are left. i dont want it to say 1 if one mob is on the ground unless that 1 mob is the very last in
 * the camp").
 * <p>
 * The mapping, and what it rests on:
 * <ul>
 *   <li>The bar is named "The Watcher" ({@code §c§lThe Watcher}) and its progress is the share of the camp's mobs still to
 *   go: Odin's and Noamm's "Watcher Bar" both print {@code round(progress * amount) + "/" + amount}.</li>
 *   <li>The camp's size: the wiki (hypixelskyblock.minecraft.wiki/w/The_Watcher) says he summons "9 in Entrance and
 *   increasing by 1 with each floor", quoting Hypixel staff "9 + (floorTier)" - F7 16. Odin and Noamm use 8 + floor
 *   (F7 15). The two disagree by one, so the size is not trusted from either: it is CALIBRATED from the bar itself. The
 *   bar steps by whole mobs (k of N), so every settled value times the right N is a whole number, and a wrong N by one
 *   is off by at least 1/N. Until the first step the floor's default (the wiki's 9 + tier) is used.</li>
 *   <li>Rounding: up, never down - a value between two steps (the client eases the bar over 100 ms, or a partly drained
 *   step) reads the higher count. So "1" only when one mob is left in the whole camp.</li>
 * </ul>
 * Master floors use the same rule (the wiki gives no separate Master Mode count; the calibration covers a difference).
 * Every settled value is logged once with the size it was read against, so a real camp's log shows the mapping.
 */
final class WatcherBar {

    /** Settled values (read the same two client ticks running, so not mid-ease) seen this camp, below full, oldest first. */
    private static final LinkedHashSet<Float> SETTLED = new LinkedHashSet<>();
    private static float lastRead = Float.NaN;
    private static float lastLogged = Float.NaN;
    private static int calibrated = -1;
    private static boolean misfitLogged;
    /** Testkit: the floor the size is read for when not in a run (null = DungeonState's). */
    private static String floorForTest;

    private WatcherBar() {
    }

    static void reset() {
        SETTLED.clear();
        lastRead = Float.NaN;
        lastLogged = Float.NaN;
        calibrated = -1;
        misfitLogged = false;
    }

    /** The Watcher's bar progress (0..1) now, or NaN when no bar of his is shown. */
    static float progress(Minecraft client) {
        BossHealthOverlay overlay = McCompat.bossOverlay(client);
        if (overlay == null) {
            return Float.NaN;
        }
        for (LerpingBossEvent event : ((BossHealthOverlayAccessor) overlay).killer560smod$getEvents().values()) {
            String name = ChatObserver.stripCodes(event.getName().getString()).trim();
            if (name.startsWith("The Watcher")) {
                return Math.max(0f, Math.min(1f, event.getProgress()));
            }
        }
        return Float.NaN;
    }

    /** The default camp size for a floor ("F7", "M3", "E"): the wiki's 9 + tier. */
    static int defaultTotal(String floor) {
        int tier = 0;
        if (floor != null && !floor.isEmpty() && Character.isDigit(floor.charAt(floor.length() - 1))) {
            tier = floor.charAt(floor.length() - 1) - '0';
        }
        return 9 + tier;
    }

    private static String floor() {
        return floorForTest != null ? floorForTest : DungeonState.getFloor();
    }

    /** Once a client tick during a camp: notes settled values and calibrates the size. */
    static void tick(Minecraft client) {
        float p = progress(client);
        if (Float.isNaN(p)) {
            lastRead = Float.NaN;
            return;
        }
        boolean settled = p == lastRead;
        lastRead = p;
        if (!settled || p >= 0.9999f || p <= 0.0001f) {
            return;
        }
        SETTLED.remove(p);
        SETTLED.add(p);
        if (SETTLED.size() > 64) {
            SETTLED.remove(SETTLED.iterator().next());
        }
        int def = defaultTotal(floor());
        int fit = fitSize(def);
        // No size fits every value: the oldest ones may be another camp's bar (a camp seen from the next room, a camp
        // whose greeting was missed). The newest value always decides: drop from the oldest until a size fits it all.
        while (fit < 0 && SETTLED.size() > 1) {
            SETTLED.remove(SETTLED.iterator().next());
            fit = fitSize(def);
        }
        if (fit < 0 && !misfitLogged) {
            misfitLogged = true;
            BloodCampFeature.LOGGER.info("[BloodCampStats] Watcher bar does not step by whole mobs of {}..{}: {}", def - 2,
                    def + 2, SETTLED);
        }
        if (fit > 0 && fit != calibrated) {
            BloodCampFeature.LOGGER.info("[BloodCampStats] Watcher bar size {} (floor {}, default {}) from {}", fit, floor(),
                    def, SETTLED);
            calibrated = fit;
        }
        if (p != lastLogged) {
            lastLogged = p;
            int n = total();
            BloodCampFeature.LOGGER.info("[BloodCampStats] Watcher bar {} -> {} mobs left of {}",
                    String.format(Locale.ROOT, "%.4f", p), left(p, n), n);
        }
    }

    /** The size nearest the floor's default (within 2) that every settled value fits, or -1. */
    private static int fitSize(int def) {
        for (int d : new int[]{0, -1, 1, -2, 2}) {
            int n = def + d;
            if (n >= 1 && fits(n)) {
                return n;
            }
        }
        return -1;
    }

    private static boolean fits(int n) {
        for (float v : SETTLED) {
            double x = v * (double) n;
            if (Math.abs(x - Math.rint(x)) > 0.02) {
                return false;
            }
        }
        return true;
    }

    /** The camp's size: calibrated from the bar, else the floor's default. */
    static int total() {
        return calibrated > 0 ? calibrated : defaultTotal(floor());
    }

    static int left(float progress, int total) {
        return Math.max(0, (int) Math.ceil(progress * (double) total - 0.02));
    }

    /** Mobs the camp still needs, from the bar, or -1 when no Watcher bar is shown. */
    static int mobsLeft(Minecraft client) {
        float p = progress(client);
        return Float.isNaN(p) ? -1 : left(p, total());
    }

    // ---- testkit ----

    static int calibratedForTest() {
        return calibrated;
    }

    static void setFloorForTest(String floor) {
        floorForTest = floor == null || floor.isEmpty() ? null : floor;
    }
}
