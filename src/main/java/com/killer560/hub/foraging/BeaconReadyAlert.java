package com.killer560.hub.foraging;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.islands.Islands;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.TabText;
import com.killer560.hub.miningqol.BlockScan;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Beacon Ready Alert: the tab list's foraging beacon widget ("Moonglade Beacon: 3 Stacks") says "Cooldown: AVAILABLE"
 * when the beacon can be tuned again; that puts a "Beacon Ready" title and a sound on screen, at most once every Repeat
 * After minutes while it stays available. While it cools the widget says only whole minutes ("Cooldown: 7m", recorded
 * 2026-11-22..24, never an absolute time), so the Cooldown Timer shows "~7m" until the minute drops once, then counts
 * seconds from that moment; nothing is remembered across a world change. Keep Showing Until Tuned swaps the fading
 * title for a "Beacon Ready" line. The cooldown and that line are text drawn over the beacon block itself (found by
 * scanning the loaded chunks near you for it), never a HUD element. Reading only.
 */
public final class BeaconReadyAlert {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "beaconready")
            .flag("sound", true)
            .flag("keep", false)
            .flag("cooldownHud", false)
            .num("repeat", 9, 1, 60)
            .num("color", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

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
    /** The beacon block once found (null: not known or not loaded), when to look again, the level it was found in. */
    private static BlockPos beacon;
    private static int scanIn;
    private static Object level;
    /** The drawn text as a Component, rebuilt only when its string or color changes; the color refreshes once a second. */
    private static Component shown;
    private static String shownFor;
    private static int shownColor;
    private static int drawColor;
    private static volatile String drawn;

    private BeaconReadyAlert() {
    }

    public static void register() {
        int bit = FeatureBits.register("Beacon Ready Alert", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BeaconReadyAlert.tick", bit, BeaconReadyAlert::tick));
        LevelRenderEvents.COLLECT_SUBMITS.register(BeaconReadyAlert::render);
    }

    /** The alert's color: the player's pick, or (Auto, the setting 0) the active menu theme's accent. */
    public static int color() {
        int c = CFG.num("color");
        return c != 0 ? c | 0xFF000000 : ThemeAccent.onDark(ThemeAccent.accent(0xFF000000 | ModChat.ORANGE));
    }

    /** The "Beacon Ready" title in the alert color (testkit reads its style). */
    public static Component title() {
        return Component.literal("Beacon Ready").withStyle(Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(color() & 0xFFFFFF)));
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
            Islands.alert(CFG.on("keep") ? null : title(), "§7Your foraging beacon can be tuned", CFG.on("sound"));
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

    /** What the beacon text says now, or null for nothing; rebuilt only when the shown second changes. */
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

    /** Looks for the beacon block near you every 5 s while there is text to show, and checks a found one each second. */
    public static void findBeacon(Minecraft mc) {
        if (mc.level != level) {
            level = mc.level;
            beacon = null;
            scanIn = 0;
        }
        if (state == NONE || !CFG.on("keep") && !CFG.on("cooldownHud")) {
            beacon = null;
        } else if (beacon != null && mc.level.getBlockState(beacon).is(Blocks.BEACON)) {
            return;
        } else {
            beacon = null;
            if (scanIn-- <= 0) {
                scanIn = 4;
                List<BlockPos> found = BlockScan.scan(mc.level, mc.player.blockPosition(), 96, 64, s -> s.is(Blocks.BEACON), (p, s) -> true, 1);
                beacon = found.isEmpty() ? null : found.get(0);
            }
        }
        drawColor = color();
    }

    /** The beacon block found near you, null when not known or loaded (testkit). */
    public static BlockPos beaconPos() {
        return beacon;
    }

    /** The text the last frame drew over the beacon, null when it drew nothing (testkit). */
    public static String lastDrawn() {
        return drawn;
    }

    private static void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        BlockPos at = beacon;
        String t = at == null ? null : hudText(System.currentTimeMillis());
        drawn = null;
        if (t != null && com.killer560.hub.util.SkyblockGate.allows()) {
            drawn = t;
            if (!t.equals(shownFor) || drawColor != shownColor) {
                shownFor = t;
                shownColor = drawColor;
                shown = Component.literal(t).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(drawColor & 0xFFFFFF)));
            }
            com.killer560.hub.dungeonalerts.DungeonAlertsFeature.renderWorldText(context, shown, at.getX() + 0.5, at.getY() + 1.7, at.getZ() + 0.5, 1.5f);
        }
    }

    private static void tick(Minecraft mc) {
        if (ticks++ % 20 != 0) {
            return;
        }
        if (!CFG.isEnabled() || mc.player == null || mc.level == null) {
            beacon = null;
            clear();
            return;
        }
        onTab(tabLines(mc), System.currentTimeMillis());
        findBeacon(mc);
    }
}
