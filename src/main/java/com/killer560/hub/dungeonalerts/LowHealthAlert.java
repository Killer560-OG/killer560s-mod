package com.killer560.hub.dungeonalerts;

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
 * only after that teammate has left red. The tier above red (yellow, "getting low", a higher sound) alerts too, with
 * Alert At Yellow Health on (default); the sidebar is read every tick so the alert comes as soon as Hypixel sends the
 * line (alerts also fire for red after yellow). Gated on the switch, a dungeon and (optionally) Healer.
 */
public final class LowHealthAlert {

    private static final Map<String, Integer> wasTier = new HashMap<>();
    private static int alertsFired = 0;
    private static String lastAlertTitle = null;

    private LowHealthAlert() {
    }

    static void tick(Minecraft client) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        if (!cfg.isLowHealthEnabled() || client.player == null || !DungeonState.isInDungeon()
                || !com.killer560.hub.util.SkyblockGate.allows()) {
            wasTier.clear();
            return;
        }
        if (cfg.isLowHealthOnlyHealer() && selfClass() != DungeonClass.HEALER) {
            wasTier.clear();
            return;
        }
        boolean yellow = cfg.isLowHealthYellow();
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
            String color = m.group("color");
            int tier = "DEAD".equals(health) ? 0 : "c".equals(color) ? 2 : yellow && "e".equals(color) ? 1 : 0;
            Integer was = wasTier.put(name, tier);
            if (tier > (was == null ? 0 : was)) {
                alert(name, health.length() > 12 ? health.substring(0, 12) : health, tier);
            }
        }
        wasTier.keySet().retainAll(seen);
    }

    private static void alert(String name, String health, int tier) {
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        String title = tier == 2 ? "§c" + name + " §ais low" : "§e" + name + " §ais getting low";
        lastAlertTitle = title;
        alertsFired++;
        DungeonAlertsFeature.showTitle(Component.literal(title), Component.literal((tier == 2 ? "§c" : "§e") + health + "❤"), 0, 25, 5);
        if (cfg.isLowHealthSound()) {
            DungeonAlertsFeature.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, tier == 2 ? 0.6f : 1.2f);
        }
        DungeonAlertsFeature.LOGGER.info("[LowHealth] {} is {} ({})", name, tier == 2 ? "low" : "getting low", health);
    }

    /** Your class: the tab list, else a pick on your own IGN ({@link PartyTracker#selfClass}, leaporder-1102). */
    static DungeonClass selfClass() {
        return PartyTracker.selfClass();
    }

    static void onWorldChange() {
        wasTier.clear();
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
