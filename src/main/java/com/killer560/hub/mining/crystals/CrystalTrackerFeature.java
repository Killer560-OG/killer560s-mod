package com.killer560.hub.mining.crystals;

import com.killer560.hub.mining.MiningAlerts;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.mining.MiningTextHud;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crystal Tracker (legit, both builds): the Crystal Hollows tab list's "Crystals:" widget on a HUD, each crystal colored
 * by what the widget says about it (placed green, found yellow, missing red), and a title once every crystal is found
 * or placed. The widget's wording is read as it stands: a status containing "Placed" is placed, one containing "Found"
 * without "Not" is found, anything else is still needed. {@link #needed()} is what Auto Nucleus Run's planner routes by.
 * Reads only. Off by default.
 */
public final class CrystalTrackerFeature {

    /** The five Nucleus crystals in the order the planner falls back to. */
    public static final List<String> NUCLEUS_CRYSTALS = List.of("Jade", "Amber", "Amethyst", "Sapphire", "Topaz");

    public enum State { NEEDED, FOUND, PLACED }

    private static final Map<String, State> STATES = new LinkedHashMap<>();
    private static List<String> hudLines = List.of();
    private static boolean wasAll = false;
    private static int tick = 0;

    public static final MiningTextHud HUD = new MiningTextHud("mining_crystals", "Crystal Tracker", 10, 320,
            () -> CrystalTrackerConfig.getInstance().isEnabled(), () -> hudLines,
            List.of("§6§lCrystals", "§aJade: Placed", "§eAmber: Found", "§cTopaz: Not Found"));

    private CrystalTrackerFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CrystalTrackerFeature", CrystalTrackerFeature::onTick));
    }

    private static void onTick(Minecraft client) {
        if (tick++ % 10 != 0) {
            return;
        }
        List<String> section = MiningIsland.crystalHollows() ? MiningIsland.section(MiningIsland.CRYSTALS) : List.of();
        STATES.clear();
        List<String> out = new ArrayList<>();
        for (String line : section) {
            int colon = line.indexOf(':');
            if (colon <= 0 || colon > 24) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String status = line.substring(colon + 1).trim();
            State st = parse(status);
            STATES.put(name, st);
            String c = st == State.PLACED ? "§a" : st == State.FOUND ? "§e" : "§c";
            out.add(c + name + ": " + status);
        }
        boolean all = !STATES.isEmpty() && STATES.values().stream().noneMatch(s -> s == State.NEEDED);
        CrystalTrackerConfig cfg = CrystalTrackerConfig.getInstance();
        if (cfg.isEnabled() && cfg.isAllFoundAlert() && all && !wasAll) {
            MiningAlerts.title("§dAll Crystals Found", "§7place them at the Crystal Nucleus", 50);
        }
        wasAll = all;
        if (!out.isEmpty()) {
            out.add(0, "§6§lCrystals");
        }
        hudLines = cfg.isEnabled() ? out : List.of();
    }

    static State parse(String status) {
        String s = status.toLowerCase(Locale.ROOT);
        if (s.contains("placed")) {
            return State.PLACED;
        }
        if (s.contains("found") && !s.contains("not")) {
            return State.FOUND;
        }
        return State.NEEDED;
    }

    /** What the widget says about each crystal right now (empty off the Crystal Hollows or with the widget hidden). */
    public static Map<String, State> states() {
        return Map.copyOf(STATES);
    }

    /** The Nucleus crystals the widget says are still needed, in {@link #NUCLEUS_CRYSTALS} order. Empty when unknown. */
    public static List<String> needed() {
        List<String> out = new ArrayList<>();
        for (String c : NUCLEUS_CRYSTALS) {
            State s = STATES.get(c);
            if (s == State.NEEDED) {
                out.add(c);
            }
        }
        return out;
    }
}
