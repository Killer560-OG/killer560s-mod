package com.killer560.hub.miningqol;

import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.TabOrder;
import com.killer560.hub.util.TabText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the pre-2.0 mining features read about where the player is and what his commissions are.
 *
 * <p>The island and the sidebar area come from {@link IslandDetector}, which {@code PathfindingFeature} ticks for everyone
 * four times a second. The commissions come from the tab list's "Commissions:" widget: a header line followed by indented
 * lines such as {@code " Lava Springs Mithril: 45%"} or {@code " Goblin Slayer: DONE"} (the shape Skyblocker's
 * CommissionLabels and SkyHanni's MiningCommissionsBlocksColor read; read for behavior only). The "Pickaxe Ability:"
 * widget the same way ({@code " Pickobulus: Available"}). Tab entries are set by the server only, so another player cannot
 * forge one. Re-read twice a second on a mining island; everything here is a snapshot the render thread may read.
 *
 * <p>Never guesses: with no "Commissions:" widget in the tab list, {@link #commissionsKnown()} is false and the features
 * that depend on commissions leave the world as it is (Mithril As Stone turns nothing to stone).
 */
public final class MiningState {

    public static final String COMMISSIONS = "Commissions:";
    public static final String PICKAXE_ABILITY = "Pickaxe Ability:";

    /** The five areas a Dwarven Mines area commission can name (hypixelskyblock.minecraft.wiki, Commissions). */
    public static final List<String> COMMISSION_AREAS = List.of(
            "Lava Springs", "Royal Mines", "Cliffside Veins", "Rampart's Quarry", "Upper Mines");

    /** "Goblin Slayer: 45%", "Mithril Miner: 12.5%", "Lucky Raffle: DONE". Bounded: tab text only, but cheap to bound. */
    private static final Pattern LINE = Pattern.compile("^(.{1,48}?):\\s*(DONE|\\d{1,3}(?:\\.\\d{1,2})?%)$");

    /** One commission from the widget. */
    public record Commission(String name, String progress, boolean done) {
    }

    private static volatile List<Commission> commissions = List.of();
    private static volatile boolean commissionsKnown = false;
    private static volatile List<String> pickaxeAbility = List.of();
    private static int ticks = 0;
    /** Bumped whenever what the features derive from (island, area, commissions) changes. */
    private static volatile int version = 0;
    private static String lastKey = "";

    private MiningState() {
    }

    static void tick(Minecraft client) {
        if (ticks++ % 10 != 0) {
            return;
        }
        List<Commission> coms = List.of();
        boolean known = false;
        List<String> ability = List.of();
        if (client.getConnection() != null && onMiningIsland()) {
            List<PlayerInfo> listed = TabOrder.listed(client);
            List<String> section = readSection(listed, COMMISSIONS);
            known = section != null;
            if (section != null) {
                coms = parse(section);
            }
            List<String> ab = readSection(listed, PICKAXE_ABILITY);
            ability = ab == null ? List.of() : ab;
        }
        commissions = coms;
        commissionsKnown = known;
        pickaxeAbility = ability;
        String key = island() + "|" + IslandDetector.scoreboardArea() + "|" + known + "|" + coms;
        if (!key.equals(lastKey)) {
            lastKey = key;
            version++;
        }
    }

    /** Parses the widget lines; testkit and the tab both come through here. */
    public static List<Commission> parse(List<String> lines) {
        List<Commission> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LINE.matcher(line.trim());
            if (m.matches()) {
                String value = m.group(2);
                out.add(new Commission(m.group(1).trim(), value, value.equals("DONE")));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The lines under {@code header} in the tab list (color codes stripped, trimmed), or null when the header is not in
     * the tab list. A widget ends at a blank line, an unindented line or another header.
     */
    static List<String> readSection(List<PlayerInfo> listed, String header) {
        List<String> out = null;
        for (PlayerInfo info : listed) {
            String plain = TabText.plain(info);
            if (plain == null) {
                if (out != null) {
                    break;
                }
                continue;
            }
            if (out == null) {
                if (plain.trim().equals(header)) {
                    out = new ArrayList<>();
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

    public static List<Commission> commissions() {
        return commissions;
    }

    /** Whether the tab list shows the Commissions widget at all (false: nothing is known, so nothing is assumed). */
    public static boolean commissionsKnown() {
        return commissionsKnown;
    }

    public static List<String> pickaxeAbilityLines() {
        return pickaxeAbility;
    }

    public static int version() {
        return version;
    }

    public static boolean anyDone() {
        return anyDone(commissions);
    }

    public static boolean anyDone(List<Commission> coms) {
        for (Commission c : coms) {
            if (c.done()) {
                return true;
            }
        }
        return false;
    }

    /** {@link IslandDetector}'s graph island, or "". */
    public static String island() {
        String s = IslandDetector.graphIsland();
        return s == null ? "" : s;
    }

    /** The Dwarven Mines, including its Glacite Tunnels part (one island to Hypixel). */
    public static boolean dwarvenMines() {
        String s = island();
        return s.equals("DWARVEN_MINES") || s.equals("GLACITE_TUNNELS");
    }

    public static boolean glaciteTunnels() {
        return island().equals("GLACITE_TUNNELS");
    }

    public static boolean crystalHollows() {
        return island().equals("CRYSTAL_HOLLOWS");
    }

    public static boolean onMiningIsland() {
        String s = island();
        return dwarvenMines() || s.equals("CRYSTAL_HOLLOWS") || s.equals("GOLD_MINES") || s.equals("DEEP_CAVERNS")
                || mineshaft();
    }

    /** A Glacite Mineshaft instance (the tab's "Area: Mineshaft"; it has no graph island). */
    public static boolean mineshaft() {
        String n = IslandDetector.islandName();
        return n != null && n.contains("Mineshaft");
    }

    /** The sidebar's area line ("Lava Springs"), or "". */
    public static String area() {
        String a = IslandDetector.scoreboardArea();
        return a == null ? "" : a;
    }

    /** Whether the sidebar area is the commission area {@code name} (case and the apostrophe's form ignored). */
    static boolean areaIs(String area, String name) {
        return norm(area).equals(norm(name));
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('’', '\'').trim();
    }

    /**
     * Whether an open commission wants {@code ore} ("Mithril" or "Titanium") mined where the player stands: an anywhere
     * commission ("Mithril Miner", "Titanium Miner"), or the area commission for his sidebar area ("Lava Springs Mithril"
     * while in Lava Springs). {@code alsoTitaniumForMithril}: a titanium commission needs mithril mined too (titanium
     * comes out of mithril).
     */
    public static boolean openCommissionHere(String ore, boolean alsoTitaniumForMithril) {
        return wants(commissions, area(), ore, alsoTitaniumForMithril);
    }

    /** The rule behind {@link #openCommissionHere}, on given commissions and area (also the testkit's hook). */
    public static boolean wants(List<Commission> coms, String area, String ore, boolean alsoTitaniumForMithril) {
        for (Commission c : coms) {
            if (c.done()) {
                continue;
            }
            String n = c.name();
            boolean ofOre = n.endsWith(" " + ore) || n.equals(ore + " Miner")
                    || (alsoTitaniumForMithril && (n.endsWith(" Titanium") || n.equals("Titanium Miner")));
            if (!ofOre) {
                continue;
            }
            if (n.endsWith(" Miner")) {
                return true;
            }
            for (String a : COMMISSION_AREAS) {
                if (n.startsWith(a + " ") && areaIs(area, a)) {
                    return true;
                }
            }
        }
        return false;
    }
}
