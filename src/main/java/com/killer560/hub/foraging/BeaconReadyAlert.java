package com.killer560.hub.foraging;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.Islands;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.TabText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Beacon Ready Alert: the tab list's foraging beacon widget ("Moonglade Beacon: 3 Stacks") says "Cooldown: AVAILABLE"
 * when the beacon can be tuned again; that puts a "Beacon Ready" title and a sound on screen, at most once every Repeat
 * After minutes while it stays available. Reading only.
 */
public final class BeaconReadyAlert {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "beaconready")
            .flag("sound", true)
            .num("repeat", 9, 1, 60);

    /** The widget's header line: "Moonglade Beacon: 3 Stacks". */
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z]{2,20} Beacon: \\d{1,4} Stacks?$");
    private static final Pattern AVAILABLE = Pattern.compile("^Cooldown: AVAILABLE$");

    private static long lastAlertAt;
    private static boolean wasAvailable;
    private static int alerts;
    private static int ticks;

    private BeaconReadyAlert() {
    }

    public static void register() {
        int bit = FeatureBits.register("Beacon Ready Alert", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BeaconReadyAlert.tick", bit, BeaconReadyAlert::tick));
    }

    /** Alerts shown this session (read by the testkit). */
    public static int alerts() {
        return alerts;
    }

    /** Whether the widget in these tab lines says the beacon is available (testkit entry point too). */
    public static boolean available(List<String> tab) {
        boolean inWidget = false;
        for (String raw : tab) {
            if (raw == null) {
                continue;
            }
            if (!raw.startsWith(" ")) {
                inWidget = HEADER.matcher(raw.trim()).matches();
            } else if (inWidget && AVAILABLE.matcher(raw.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** One read of the tab list (testkit entry point too). */
    public static void onTab(List<String> tab, long now) {
        boolean avail = available(tab);
        if (avail && (!wasAvailable || now - lastAlertAt >= CFG.num("repeat") * 60_000L)) {
            lastAlertAt = now;
            alerts++;
            Islands.alert("§a§lBeacon Ready", "§7Your foraging beacon can be tuned", CFG.on("sound"));
        }
        wasAvailable = avail;
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
            wasAvailable = false;
            return;
        }
        onTab(tabLines(mc), System.currentTimeMillis());
    }
}
