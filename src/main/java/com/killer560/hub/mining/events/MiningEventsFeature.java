package com.killer560.hub.mining.events;

import com.killer560.hub.mining.MiningAlerts;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.mining.MiningTextHud;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mining Events (legit, both builds): a title and sound when a Dwarven Mines or Crystal Hollows event is about to start,
 * and a timer HUD of the events running (RSM's MiningEvent alerts and Gourmand timer, for every event). Read from chat
 * only, so nothing depends on a version-specific boss-bar API:
 * <ul>
 *   <li>"⚑ The 2x Powder event starts in 20 seconds!" - RSM's pattern; the alert;</li>
 *   <li>"        2X POWDER STARTED!" / "... ENDED!" - SkyHanni's centered lines; the timer starts and stops, with
 *       SkyHanni's length for each event as the bound.</li>
 * </ul>
 * Each pattern is anchored at the start of the line and a player's chat line always starts with a rank or a name, so
 * another player cannot set it off. Never clicks or sends anything. Off by default.
 */
public final class MiningEventsFeature {

    public static final String HUD_ID = "mining_events";
    private static final Pattern UPCOMING = Pattern.compile("^⚑ The (.{1,40}?) event starts in 20 seconds!");
    private static final Pattern STARTED = Pattern.compile("^\\s{1,64}([A-Z0-9 ]{2,40}) STARTED!$");
    private static final Pattern ENDED = Pattern.compile("^\\s{1,64}([A-Z0-9 ]{2,40}) ENDED!$");
    private static final long ENDING_SOON_MS = 20_000L;

    /** Running event -> when it ends (epoch ms). Render/client thread only. */
    private static final Map<MiningEvent, Long> RUNNING = new EnumMap<>(MiningEvent.class);
    private static final Map<MiningEvent, Boolean> WARNED = new EnumMap<>(MiningEvent.class);
    private static List<String> hudLines = List.of();

    public static final MiningTextHud HUD = new MiningTextHud(HUD_ID, "Mining Events", 10, 170,
            () -> MiningEventsConfig.getInstance().isEnabled() && MiningEventsConfig.getInstance().isTimerHud(),
            () -> hudLines, List.of("§6§lMining Events", "§b2x Powder: §a12:34"));

    private MiningEventsFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(MiningEventsFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MiningEventsFeature", MiningEventsFeature::onTick));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            RUNNING.clear();
            WARNED.clear();
            hudLines = List.of();
        }));
    }

    private static boolean active() {
        return MiningEventsConfig.getInstance().isEnabled()
                && (MiningIsland.dwarvenMines() || MiningIsland.crystalHollows());
    }

    static void onChat(Component message) {
        if (!active()) {
            return;
        }
        String s = ChatObserver.strip(message);
        if (s == null) {
            return;
        }
        String first = s.split("\n", 2)[0];
        MiningEventsConfig cfg = MiningEventsConfig.getInstance();
        Matcher m = UPCOMING.matcher(first);
        if (m.find()) {
            MiningEvent e = MiningEvent.byName(m.group(1));
            if (e != null && cfg.isAlerting(e)) {
                alert("§b" + e.label + "!", "§7starts in 20 seconds");
            }
            return;
        }
        m = STARTED.matcher(first);
        if (m.matches()) {
            MiningEvent e = MiningEvent.byName(m.group(1));
            if (e != null) {
                RUNNING.put(e, System.currentTimeMillis() + e.seconds * 1000L);
                WARNED.remove(e);
            }
            return;
        }
        m = ENDED.matcher(first);
        if (m.matches()) {
            MiningEvent e = MiningEvent.byName(m.group(1));
            if (e != null) {
                RUNNING.remove(e);
                WARNED.remove(e);
            }
        }
    }

    private static void alert(String title, String subtitle) {
        MiningEventsConfig cfg = MiningEventsConfig.getInstance();
        if (cfg.isStartTitle()) {
            MiningAlerts.title(title, subtitle, 40);
        }
        if (cfg.isStartSound()) {
            MiningAlerts.sound(cfg.getSound());
        }
    }

    private static void onTick(Minecraft client) {
        if (!active()) {
            hudLines = List.of();
            return;
        }
        long now = System.currentTimeMillis();
        RUNNING.values().removeIf(end -> end <= now);
        MiningEventsConfig cfg = MiningEventsConfig.getInstance();
        List<String> out = new ArrayList<>();
        for (Map.Entry<MiningEvent, Long> e : RUNNING.entrySet()) {
            long left = e.getValue() - now;
            if (cfg.isEndingSoon() && left <= ENDING_SOON_MS && !WARNED.containsKey(e.getKey())
                    && cfg.isAlerting(e.getKey())) {
                WARNED.put(e.getKey(), true);
                alert("§e" + e.getKey().label + " ends soon", "");
            }
            String color = left > 60_000 ? "§a" : left > 30_000 ? "§e" : "§c";
            out.add("§b" + e.getKey().label + ": " + color + mmss(left));
        }
        if (!out.isEmpty()) {
            out.add(0, "§6§lMining Events");
        }
        hudLines = out;
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
