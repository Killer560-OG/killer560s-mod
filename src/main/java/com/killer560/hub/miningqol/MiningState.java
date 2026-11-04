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
    /** Commissions Hypixel's chat said are complete, before the tab shows DONE: lower-case name -> {expiry ms, tab percent when it was announced (-1: unknown)}. */
    private static final java.util.Map<String, double[]> DONE_OVERRIDE = new java.util.concurrent.ConcurrentHashMap<>();
    /** How long a chat-announced completion stands in for the tab (it only has to bridge the tab's lag). */
    static final long DONE_OVERRIDE_MS = 15_000;

    /**
     * "ROYAL MINES MITHRIL Commission Complete!": that commission is DONE now, not when the tab next refreshes (killer560,
     * 2026-11-03). Lasts until the tab shows it DONE itself, the Commissions menu is closed, or {@link #DONE_OVERRIDE_MS}.
     */
    static void markDone(String upperName, long now) {
        double at = -1;
        for (Commission c : commissions) {
            if (c.name().equalsIgnoreCase(upperName)) {
                at = CommissionProgress.pct(c.progress());
            }
        }
        DONE_OVERRIDE.put(upperName.toLowerCase(java.util.Locale.ROOT), new double[]{now + DONE_OVERRIDE_MS, at});
        List<Commission> now2 = applyDone(commissions, now);
        if (now2 != commissions) {
            commissions = now2;
            version++;
        }
    }

    static void clearDoneOverrides() {
        DONE_OVERRIDE.clear();
    }

    private static List<Commission> applyDone(List<Commission> coms, long now) {
        DONE_OVERRIDE.values().removeIf(e -> e[0] < now);
        if (DONE_OVERRIDE.isEmpty()) {
            return coms;
        }
        List<Commission> out = new ArrayList<>(coms.size());
        boolean changed = false;
        for (Commission c : coms) {
            double[] e = c.done() ? null : DONE_OVERRIDE.get(c.name().toLowerCase(java.util.Locale.ROOT));
            if (e != null && e[1] >= 0) {
                double now2 = CommissionProgress.pct(c.progress());
                if (now2 >= 0 && now2 < e[1] - 0.5) {
                    // The tab reads LOWER than when it was announced: a new commission under that name, so it was claimed.
                    DONE_OVERRIDE.remove(c.name().toLowerCase(java.util.Locale.ROOT));
                    e = null;
                }
            }
            if (e != null) {
                out.add(new Commission(c.name(), "DONE", true));
                changed = true;
            } else {
                out.add(c);
            }
        }
        return changed ? List.copyOf(out) : coms;
    }

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
                coms = applyDone(parse(section), System.currentTimeMillis());
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

    /**
     * What the open commissions want of {@code ore}, block by block: {@code anywhere} (a "Mithril Miner"-style commission,
     * every block counts) and the commission areas that want it. A block in a listed area counts; a block whose area is
     * unknown takes the player's own area.
     */
    public record Wanted(boolean anywhere, java.util.Set<String> areas) {
        /** Whether a block in {@code blockArea} (null: unknown, so {@code playerArea} decides) is wanted. */
        public boolean at(String blockArea, String playerArea) {
            if (anywhere) {
                return true;
            }
            String a = blockArea != null ? blockArea : playerArea;
            return a != null && areas.contains(norm(a));
        }

        public boolean any() {
            return anywhere || !areas.isEmpty();
        }
    }

    /** {@link Wanted} for the current commissions. */
    public static Wanted wanted(String ore, boolean alsoTitaniumForMithril) {
        return wanted(commissions, ore, alsoTitaniumForMithril);
    }

    public static Wanted wanted(List<Commission> coms, String ore, boolean alsoTitaniumForMithril) {
        boolean anywhere = wants(coms, "", ore, alsoTitaniumForMithril);
        java.util.Set<String> areas = new java.util.TreeSet<>();
        for (String a : COMMISSION_AREAS) {
            if (wants(coms, a, ore, alsoTitaniumForMithril)) {
                areas.add(norm(a));
            }
        }
        return new Wanted(anywhere, java.util.Set.copyOf(areas));
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
