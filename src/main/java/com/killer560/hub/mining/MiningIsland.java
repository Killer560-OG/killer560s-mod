package com.killer560.hub.mining;

import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.TabOrder;
import com.killer560.hub.util.TabText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the player is, for the Mining category (staging-mining), and the tab-list widgets mining features read.
 *
 * <p>The island comes from {@link IslandDetector}, which {@code pathfinding.PathfindingFeature} ticks for everyone, so
 * nothing here needs its own read of the tab's "Area:" line. The widgets ("Commissions:", "Crystals:", "Powders:" -
 * SkyHanni's {@code TabWidget} names) are read from the tab list in the order the tab list draws it ({@link TabOrder}):
 * a widget is its header line followed by the indented lines under it, up to the next blank or unindented line.
 * They are re-read twice a second while on a mining island, so a HUD drawing every frame never walks the tab list.
 */
public final class MiningIsland {

    public static final String COMMISSIONS = "Commissions:";
    public static final String CRYSTALS = "Crystals:";
    public static final String POWDERS = "Powders:";
    private static final String[] WIDGETS = {COMMISSIONS, CRYSTALS, POWDERS};

    private static final Map<String, List<String>> SECTIONS = new ConcurrentHashMap<>();
    private static int ticks = 0;
    private static boolean registered = false;

    private MiningIsland() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                com.killer560.hub.util.FeatureGuard.end("MiningIsland", MiningIsland::tick));
    }

    private static void tick(Minecraft client) {
        if (ticks++ % 10 != 0) {
            return;
        }
        if (client.getConnection() == null || !onMiningIsland()) {
            SECTIONS.clear();
            com.killer560.hub.powdermining.PowderTracker.onPowdersWidget(List.of());
            return;
        }
        List<PlayerInfo> listed = TabOrder.listed(client);
        for (String header : WIDGETS) {
            SECTIONS.put(header, readSection(listed, header));
        }
        com.killer560.hub.powdermining.PowderTracker.onPowdersWidget(section(POWDERS));
    }

    /** The lines under {@code header} in the tab list, color codes stripped and trimmed; empty when it is not shown. */
    public static List<String> section(String header) {
        List<String> s = SECTIONS.get(header);
        return s == null ? List.of() : s;
    }

    static List<String> readSection(List<PlayerInfo> listed, String header) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (PlayerInfo info : listed) {
            String plain = TabText.plain(info);
            if (plain == null) {
                if (in) {
                    break;
                }
                continue;
            }
            if (!in) {
                if (plain.trim().equals(header)) {
                    in = true;
                }
                continue;
            }
            if (plain.isBlank() || !Character.isWhitespace(plain.charAt(0))) {
                break;
            }
            out.add(plain.trim());
            if (out.size() >= 12) {
                break;
            }
        }
        return out;
    }

    /** {@link IslandDetector}'s graph name, or "" off any known island. */
    public static String island() {
        String s = IslandDetector.graphIsland();
        return s == null ? "" : s;
    }

    /** The Dwarven Mines, including its Glacite Tunnels part (one island to Hypixel). */
    public static boolean dwarvenMines() {
        String s = island();
        return s.equals("DWARVEN_MINES") || s.equals("GLACITE_TUNNELS");
    }

    public static boolean crystalHollows() {
        return island().equals("CRYSTAL_HOLLOWS");
    }

    public static boolean onMiningIsland() {
        String s = island();
        return dwarvenMines() || s.equals("CRYSTAL_HOLLOWS") || s.equals("GOLD_MINES") || s.equals("DEEP_CAVERNS");
    }

    /** Whether the sidebar area line contains {@code part} (case ignored), e.g. "Mines of Divan". */
    public static boolean inArea(String part) {
        String area = IslandDetector.scoreboardArea();
        return area != null && area.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }
}
