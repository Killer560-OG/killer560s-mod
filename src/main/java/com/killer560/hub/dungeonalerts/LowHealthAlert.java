package com.killer560.hub.dungeonalerts;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.scoreboard.ScoreboardPattern;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Teammate Low Health Alert - SkyHanni's {@code features/dungeon/LowHealthAlert.kt}: the dungeon sidebar lists every
 * teammate as {@code [H] Name 1,234❤}, and Hypixel colors the number red when that teammate is low. A title
 * ("Name is low", the health under it) and a sound when a teammate's number turns red, optionally only while you are
 * playing Healer (SkyHanni's default). The sidebar line is matched whole with {@link ScoreboardPattern#TEAMMATES}
 * (bounded name group); the health is shown as text, never parsed. Fires once each time a teammate goes red, again
 * only after that teammate has left red.
 */
public final class LowHealthAlert {

    private static final int POLL_TICKS = 5;
    private static final Map<String, Boolean> wasLow = new HashMap<>();
    private static int poll = 0;
    private static int alertsFired = 0;
    private static String lastAlertTitle = null;

    private LowHealthAlert() {
    }

    static void tick(Minecraft client) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.isLowHealthEnabled() || client.player == null || !DungeonState.isInDungeon()
                || !com.killer560.hub.util.SkyblockGate.allows()) {
            wasLow.clear();
            return;
        }
        if (++poll < POLL_TICKS) {
            return;
        }
        poll = 0;
        if (cfg.isLowHealthOnlyHealer() && selfClass() != DungeonClass.HEALER) {
            wasLow.clear();
            return;
        }
        String self = client.player.getGameProfile().name();
        List<String> lines = ScoreboardData.sidebarNow(client);
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            Matcher m = ScoreboardPattern.TEAMMATES.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group("username");
            String health = m.group("health");
            if (name == null || health == null || name.equals(self)) {
                continue;
            }
            seen.add(name);
            boolean low = "c".equals(m.group("color")) && !"DEAD".equals(health);
            Boolean was = wasLow.put(name, low);
            if (low && !Boolean.TRUE.equals(was)) {
                alert(name, health.length() > 12 ? health.substring(0, 12) : health);
            }
        }
        wasLow.keySet().retainAll(seen);
    }

    private static void alert(String name, String health) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        String title = "§c" + name + " §ais low";
        lastAlertTitle = title;
        alertsFired++;
        DungeonAlertsFeature.showTitle(Component.literal(title), Component.literal("§c" + health + "❤"), 0, 25, 5);
        if (cfg.isLowHealthSound()) {
            DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.6f);
        }
        DungeonAlertsFeature.LOGGER.info("[LowHealth] {} is low ({})", name, health);
    }

    /** Your class: the mod-wide override for your own IGN, else the tab list. */
    static DungeonClass selfClass() {
        Minecraft client = Minecraft.getInstance();
        String name = client.player == null ? null : client.player.getGameProfile().name();
        return ClassOverrides.classOf(name, PartyTracker.selfClass());
    }

    static void onWorldChange() {
        wasLow.clear();
    }

    /** For the testkit: alerts shown since the client started. */
    public static int alertsFired() {
        return alertsFired;
    }

    /** For the testkit: the last alert's title text. */
    public static String lastAlertTitle() {
        return lastAlertTitle;
    }
}
