package com.killer560.hub.foraging;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.Islands;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.TabText;
import com.killer560.hub.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Beacon Ready Alert: the tab list's foraging beacon widget ("Moonglade Beacon: 3 Stacks") says "Cooldown: AVAILABLE"
 * when the beacon can be tuned again; that puts a "Beacon Ready" title and a sound on screen, at most once every Repeat
 * After minutes while it stays available. While it cools the widget says only whole minutes ("Cooldown: 7m", recorded
 * 2026-11-22..24, never an absolute time), so the Cooldown HUD shows "~7m" until the minute drops once, then counts
 * seconds from that moment; nothing is remembered across a world change. Keep Showing Until Tuned swaps the fading
 * title for the HUD line. Reading only.
 */
public final class BeaconReadyAlert {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "beaconready")
            .flag("sound", true)
            .flag("keep", false)
            .flag("cooldownHud", false)
            .num("repeat", 9, 1, 60);

    /** The widget's header line: "Moonglade Beacon: 3 Stacks". */
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z]{2,20} Beacon: \\d{1,4} Stacks?$");
    private static final Pattern COOL = Pattern.compile("^Cooldown: (?:(\\d+)h ?)?(?:(\\d+)m ?)?(?:(\\d+)s)?$");
    private static final int NONE = 0;
    private static final int READY = 1;
    private static final int COOLING = 2;

    private static long lastAlertAt;
    private static boolean wasAvailable;
    private static int alerts;
    private static int ticks;
    private static int state;
    /** Cooling: the whole minutes the widget last said (-1 none), and when it hits zero (0 = not known yet). */
    private static int lastSecs = -1;
    private static long deadline;
    private static long hudKey = Long.MIN_VALUE;
    private static String hudText;

    private BeaconReadyAlert() {
    }

    public static void register() {
        int bit = FeatureBits.register("Beacon Ready Alert", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BeaconReadyAlert.tick", bit, BeaconReadyAlert::tick));
        HudElementRegistry.register(new BeaconReadyHud());
    }

    /** Alerts shown this session (read by the testkit). */
    public static int alerts() {
        return alerts;
    }

    /** The widget's cooldown in seconds: -1 for AVAILABLE, -2 when the tab shows no beacon widget (testkit entry point too). */
    public static int cooldownSecs(List<String> tab) {
        boolean inWidget = false;
        for (String raw : tab) {
            if (raw == null) {
                continue;
            }
            String t = raw.trim();
            if (!raw.startsWith(" ")) {
                inWidget = HEADER.matcher(t).matches();
            } else if (inWidget && t.startsWith("Cooldown:")) {
                if (t.equals("Cooldown: AVAILABLE")) {
                    return -1;
                }
                Matcher m = COOL.matcher(t);
                if (m.matches()) {
                    return secs(m.group(1)) * 3600 + secs(m.group(2)) * 60 + secs(m.group(3));
                }
            }
        }
        return -2;
    }

    private static int secs(String g) {
        return g == null ? 0 : Integer.parseInt(g);
    }

    /** Whether the widget in these tab lines says the beacon is available (testkit entry point too). */
    public static boolean available(List<String> tab) {
        return cooldownSecs(tab) == -1;
    }

    /** One read of the tab list (testkit entry point too). */
    public static void onTab(List<String> tab, long now) {
        int cd = cooldownSecs(tab);
        boolean avail = cd == -1;
        if (avail && (!wasAvailable || now - lastAlertAt >= CFG.num("repeat") * 60_000L)) {
            lastAlertAt = now;
            alerts++;
            Islands.alert(CFG.on("keep") ? null : "§a§lBeacon Ready", "§7Your foraging beacon can be tuned", CFG.on("sound"));
        }
        wasAvailable = avail;
        state = cd == -2 ? NONE : avail ? READY : COOLING;
        if (cd < 0) {
            lastSecs = -1;
            deadline = 0;
        } else if (cd % 60 != 0) {
            deadline = now + cd * 1000L;
            lastSecs = cd;
        } else if (cd != lastSecs) {
            // The minute count just dropped: the widget flips at the whole minute, so zero is exactly that many minutes away.
            deadline = lastSecs > cd ? now + cd * 1000L : 0;
            lastSecs = cd;
        }
    }

    /** Nothing is cooling or ready any more (feature off, no player). */
    private static void clear() {
        wasAvailable = false;
        state = NONE;
        lastSecs = -1;
        deadline = 0;
    }

    /** What the HUD says now, or null for nothing; rebuilt only when the shown second changes. */
    public static String hudText(long now) {
        boolean keep = CFG.on("keep");
        boolean cool = CFG.on("cooldownHud");
        if (!CFG.isEnabledRaw() || state == NONE || state == COOLING && !cool || state == READY && !keep && !cool) {
            return null;
        }
        long rem = deadline > 0 ? Math.max(0, (deadline - now + 999) / 1000) : lastSecs / 60;
        long key = ((state * 2L + (keep ? 1 : 0)) * 2 + (deadline > 0 ? 1 : 0)) << 32 | rem;
        if (key != hudKey) {
            hudKey = key;
            hudText = state == READY ? (keep ? "Beacon Ready" : "Beacon: Ready")
                    : deadline > 0 ? (rem == 0 ? "Beacon: Ready" : "Beacon: " + clock(rem)) : "Beacon: ~" + rem + "m";
        }
        return hudText;
    }

    private static String clock(long s) {
        return s >= 3600 ? s / 3600 + "h " + s % 3600 / 60 + "m" : s >= 60 ? s / 60 + "m " + s % 60 + "s" : s + "s";
    }

    /** The plain tab lines: the Custom Scoreboard's copy when it keeps one, else the list read here. */
    private static List<String> tabLines(Minecraft mc) {
        List<String> cached = ScoreboardData.tabPlain();
        if (!cached.isEmpty() || mc.getConnection() == null) {
            return cached;
        }
        List<PlayerInfo> infos = new ArrayList<>(mc.getConnection().getListedOnlinePlayers());
        infos.sort(java.util.Comparator.comparing(i -> i.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
        List<String> out = new ArrayList<>(infos.size());
        for (PlayerInfo info : infos) {
            String p = TabText.plain(info);
            out.add(p == null ? "" : p);
        }
        return out;
    }

    private static void tick(Minecraft mc) {
        if (ticks++ % 20 != 0) {
            return;
        }
        if (!CFG.isEnabled() || mc.player == null) {
            clear();
            return;
        }
        onTab(tabLines(mc), System.currentTimeMillis());
    }
}
