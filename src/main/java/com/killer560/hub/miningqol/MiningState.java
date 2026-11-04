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


    // ---- Commissions menu read (killer560, 2026-11-03: "read the comission from inside of the comissions menu") ----

    private static final org.slf4j.Logger LOG = com.killer560.hub.util.ModLog.get("killer560smod-miningqol");
    /** "Progress: 45.2%", "45%": bounded. */
    private static final Pattern MENU_PCT = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)\\s{0,2}%");
    private static final Pattern MENU_PCT_ONLY = Pattern.compile("^[^A-Za-z0-9]{0,6}(\\d{1,3}(?:\\.\\d{1,2})?)\\s{0,2}%\\s{0,3}$");
    /** How long a menu read stands in for a tab that has not caught up. */
    static final long MENU_MS = 30_000;

    /** One commission as read from the menu, and the tab's value when it was read. */
    private record MenuEntry(double pct, boolean done, double snapPct, long expiry) {
    }

    private static final java.util.Map<String, MenuEntry> MENU = new java.util.concurrent.ConcurrentHashMap<>();
    private static List<Commission> menuSeen = List.of();
    private static List<Commission> rawTab = List.of();
    private static int menuTicks = 0;
    private static boolean menuDumped = false;

    /**
     * One menu item to a commission: name from the item, progress from its lore. Hypixel's exact lore is not on record
     * (no log carries it), so this is tolerant: finished = COMPLETED / "Click to claim" / 100% (the highlight's rule);
     * otherwise the percent on a "Progress" line, else on a line that is only a percent. Null: not a commission.
     */
    public static Commission parseMenuItem(String name, List<String> lore) {
        try {
            if (name == null || name.isBlank() || name.length() > 48 || lore == null) {
                return null;
            }
            String n = name.trim();
            if (CommissionMenuHighlight.completed(lore)) {
                return new Commission(n, "DONE", true);
            }
            for (String raw : lore) {
                String l = raw == null ? "" : raw.trim();
                if (l.length() > 120 || !l.toLowerCase(Locale.ROOT).startsWith("progress")) {
                    continue;
                }
                Matcher m = MENU_PCT.matcher(l);
                if (m.find()) {
                    return menuPct(n, m.group(1));
                }
            }
            for (String raw : lore) {
                String l = raw == null ? "" : raw.trim();
                if (l.length() > 24) {
                    continue;
                }
                Matcher m = MENU_PCT_ONLY.matcher(l);
                if (m.matches()) {
                    return menuPct(n, m.group(1));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("[Commissions] menu item parse failed: {}", e.toString());
        }
        return null;
    }

    private static Commission menuPct(String name, String num) {
        double v = Double.parseDouble(num);
        return v > 100 ? null : new Commission(name, CommissionProgress.format(v), false);
    }

    /** The commissions the open Commissions menu shows now, or null when there is no such menu or nothing changed. */
    private static List<Commission> pollMenu(Minecraft client) {
        if (com.killer560.hub.compat.McCompat.screen(client) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen
                && CommissionMenuHighlight.isMenu(screen)) {
            if (menuTicks++ % 4 != 0) {
                return null;
            }
            try {
                List<Commission> out = new ArrayList<>();
                StringBuilder dump = menuDumped ? null : new StringBuilder();
                int top = Math.min(screen.getMenu().slots.size() - 36, 54);
                for (int i = 0; i < top; i++) {
                    net.minecraft.world.item.ItemStack st = screen.getMenu().slots.get(i).getItem();
                    if (st.isEmpty()) {
                        continue;
                    }
                    String name = st.getHoverName().getString();
                    List<String> lore = com.killer560.hub.croesus.DungeonChestValuer.cleanLore(st);
                    if (dump != null && lore.size() > 0 && dump.length() < 6000) {
                        dump.append("\n  slot ").append(i).append(' ').append(name).append(" | ")
                                .append(String.join(" / ", lore));
                    }
                    Commission c = parseMenuItem(name, lore);
                    if (c != null) {
                        out.add(c);
                    }
                }
                if (dump != null && dump.length() > 0) {
                    menuDumped = true;
                    LOG.info("[Commissions] menu lore (first open, codes stripped):{}", dump);
                }
                if (out.equals(menuSeen)) {
                    return null;
                }
                menuSeen = List.copyOf(out);
                return menuSeen;
            } catch (RuntimeException e) {
                LOG.warn("[Commissions] menu read failed: {}", e.toString());
            }
        } else {
            menuTicks = 0;
            menuSeen = List.of();
        }
        return null;
    }

    /** Remembers {@code menu} against the tab as it reads right now ({@code tab}); only commissions the tab lists count. */
    static void storeMenu(List<Commission> menu, List<Commission> tab, long now) {
        for (Commission m : menu) {
            for (Commission t : tab) {
                if (!t.name().equalsIgnoreCase(m.name()) || t.done()) {
                    continue;
                }
                double tp = CommissionProgress.pct(t.progress());
                if (tp < 0) {
                    continue;
                }
                MENU.put(t.name().toLowerCase(Locale.ROOT),
                        new MenuEntry(m.done() ? 100 : CommissionProgress.pct(m.progress()), m.done(), tp, now + MENU_MS));
            }
        }
    }

    /**
     * A menu read wins over the tab until the tab shows a newer state: the tab reaching the menu's value (or DONE), the tab
     * reading lower than it did at the read (a new commission under that name), or {@link #MENU_MS}.
     */
    static List<Commission> applyMenu(List<Commission> coms, long now) {
        MENU.values().removeIf(e -> e.expiry() < now);
        if (MENU.isEmpty()) {
            return coms;
        }
        List<Commission> out = new ArrayList<>(coms.size());
        boolean changed = false;
        for (Commission c : coms) {
            String key = c.name().toLowerCase(Locale.ROOT);
            MenuEntry e = MENU.get(key);
            if (e != null) {
                double t = CommissionProgress.pct(c.progress());
                if (c.done() || (t >= 0 && (t < e.snapPct() - 0.05 || (!e.done() && t >= e.pct())))) {
                    MENU.remove(key);
                    e = null;
                }
            }
            if (e != null) {
                out.add(e.done() ? new Commission(c.name(), "DONE", true)
                        : new Commission(c.name(), CommissionProgress.format(e.pct()), false));
                changed = true;
            } else {
                out.add(c);
            }
        }
        return changed ? List.copyOf(out) : coms;
    }

    /** Testkit: what the open Commissions menu read as on the last change. */
    public static List<Commission> menuSeen() {
        return menuSeen;
    }

    private MiningState() {
    }

    static void tick(Minecraft client) {
        List<Commission> menuNew = client.getConnection() != null ? pollMenu(client) : null;
        if (ticks++ % 10 != 0 && menuNew == null) {
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
                long nowMs = System.currentTimeMillis();
                List<Commission> raw = parse(section);
                rawTab = raw;
                if (menuNew != null) {
                    storeMenu(menuNew, raw, nowMs);
                }
                coms = applyDone(applyMenu(raw, nowMs), nowMs);
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
