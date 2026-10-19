package com.killer560.hub.chocolatefactory;

import com.killer560.hub.cheatutils.CheatUtils;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's Chocolate Factory menu - one reader for both the legit helper and the cheat Auto Chocolate Factory,
 * so the two can never disagree about which slot is what.
 * <p>
 * The layout is SkyHanni-REPO's {@code constants/HoppityEggLocations.json} (commit ca80e4eb, read 2026-10-11 from
 * killer560's own SkyHanni repo copy): employees 28-34 (+1..+7 raw chocolate per second), prestige 27, barn 35,
 * hand-baked chocolate 37, <b>Time Tower 38, Rabbit Shrine 39, Coach Jackrabbit 41</b>, production 45, the chocolate
 * itself 13, and "destructive" slots 42, 43, 47-51, 53 (menus, leaderboard, close). The auto clicker was written for
 * the older layout (Time Tower 39, Coach 42), which is why it clicked the chocolate and nothing else worked
 * (killer560, 2026-10-11): its Time Tower read the Shrine and its Coach upgrade pointed at a menu button. Items are
 * also found by NAME where Hypixel names them, so a further shuffle of the menu moves the reader with it.
 */
public final class ChocolateFactoryMenu {

    public static final String TITLE = "Chocolate Factory";
    public static final int INFO_SLOT = 13;
    public static final int PRODUCTION_SLOT = 45;
    public static final int TIME_TOWER_SLOT = 38;
    public static final int SHRINE_SLOT = 39;
    public static final int COACH_SLOT = 41;
    public static final int HAND_BAKED_SLOT = 37;
    public static final Map<Integer, Integer> RABBIT_SLOT_GAINS = Map.of(28, 1, 29, 2, 30, 3, 31, 4, 32, 5, 33, 6, 34, 7);
    /** Never clicked by the auto: they open other menus, the leaderboard, or close the factory. */
    public static final Set<Integer> DESTRUCTIVE_SLOTS = Set.of(42, 43, 47, 48, 49, 50, 51, 53);

    private static final Pattern CPS = Pattern.compile("([\\d.,]+)\\s+(?:Chocolate\\s+)?per second");
    private static final Pattern TOTAL_MULT = Pattern.compile("Total Multiplier:\\s+([\\d.,]+)x");
    private static final Pattern TT_STATUS = Pattern.compile("Status:\\s+(ACTIVE|INACTIVE)");
    private static final Pattern TT_CHARGES = Pattern.compile("Charges:\\s*(\\d{1,3})\\s*/\\s*(\\d{1,3})");
    private static final Pattern DIGITS = Pattern.compile("([\\d,]{1,25})");

    /** One upgrade the reader can value. {@code effectiveCost} = chocolate per extra chocolate-per-second. */
    public record Upgrade(int slot, String name, long cost, double extraCps, double effectiveCost) {
    }

    /** What the open menu says. {@code slots} is the top container only (54 stacks). */
    public record State(long chocolate, double cps, double multiplier, List<Upgrade> upgrades, Upgrade best,
                        List<Integer> strays, int timeTowerSlot, int timeTowerCharges, boolean timeTowerActive) {
        public boolean timeTowerReady() {
            return timeTowerSlot >= 0 && timeTowerCharges > 0 && !timeTowerActive;
        }

        /** Seconds until {@code u} is affordable at this production, 0 when it already is, -1 when unknown. */
        public long secondsUntil(Upgrade u) {
            if (u == null) {
                return -1;
            }
            if (chocolate >= u.cost()) {
                return 0;
            }
            return cps > 0 ? (long) Math.ceil((u.cost() - chocolate) / cps) : -1;
        }
    }

    private ChocolateFactoryMenu() {
    }

    public static boolean isFactoryTitle(String title) {
        return TITLE.equals(title);
    }

    public static State read(List<ItemStack> slots) {
        long chocolate = parseLong(name(slots, INFO_SLOT));
        Double cps = firstMatch(lore(slots, INFO_SLOT), CPS);
        int prodSlot = findByName(slots, "Chocolate Production", PRODUCTION_SLOT);
        List<String> prodLore = lore(slots, prodSlot);
        Double mult = firstMatch(prodLore, TOTAL_MULT);
        if (mult == null && firstMatch(prodLore, CPS) != null) {
            // Hypixel leaves the "Total Multiplier" line off while the factory is at 1x (the 22:49:02 open of
            // 2026-10-18: production says "1 Chocolate per second" and no multiplier). No line means 1x, not
            // "unreadable", or every upgrade is dropped and nothing is highlighted.
            mult = 1.0;
        }
        int tt = findByName(slots, "Time Tower", TIME_TOWER_SLOT);
        int coach = findByName(slots, "Coach Jackrabbit", COACH_SLOT);
        List<String> ttLore = lore(slots, tt);
        boolean ttActive = false;
        int ttCharges = 0;
        boolean ttFound = false;
        for (String line : ttLore) {
            Matcher m = TT_STATUS.matcher(line);
            if (m.find()) {
                ttActive = "ACTIVE".equals(m.group(1));
                ttFound = true;
            }
            Matcher c = TT_CHARGES.matcher(line);
            if (c.find()) {
                ttCharges = Integer.parseInt(c.group(1));
                ttFound = true;
            }
        }

        List<Upgrade> upgrades = new ArrayList<>();
        Upgrade best = null;
        if (cps != null && mult != null && cps > 0 && mult > 0) {
            double raw = cps / mult;
            int ttLevel = romanTier(name(slots, tt));
            double baseMult = Math.max(0.0, mult - (ttActive ? ttLevel * 0.1 : 0.0));
            double average = raw * baseMult;
            for (Map.Entry<Integer, Integer> e : RABBIT_SLOT_GAINS.entrySet()) {
                best = add(upgrades, best, slots, e.getKey(), average, (raw + e.getValue()) * baseMult);
            }
            // The Time Tower adds +0.1x for 1 h of every 8 (QUOI's average); the Coach +0.01x for good.
            best = add(upgrades, best, slots, tt, average, raw * baseMult + raw * 0.1 / 8.0);
            best = add(upgrades, best, slots, coach, average, raw * (baseMult + 0.01));
        }

        List<Integer> strays = new ArrayList<>();
        for (int i = 0; i < Math.min(27, slots.size()); i++) {
            if (i == INFO_SLOT) {
                continue;
            }
            String n = name(slots, i);
            if (n.contains("CLICK ME!") || n.contains("Golden Rabbit")) {
                strays.add(i);
            }
        }
        return new State(chocolate, cps == null ? 0 : cps, mult == null ? 0 : mult, upgrades, best, strays,
                ttFound ? tt : -1, ttCharges, ttActive);
    }

    private static Upgrade add(List<Upgrade> list, Upgrade best, List<ItemStack> slots, int slot, double average,
                               double newAverage) {
        if (slot < 0 || DESTRUCTIVE_SLOTS.contains(slot)) {
            return best;
        }
        Long cost = parseCost(lore(slots, slot));
        double extra = newAverage - average;
        if (cost == null || extra <= 0) {
            return best;
        }
        Upgrade u = new Upgrade(slot, name(slots, slot), cost, extra, cost / extra);
        list.add(u);
        return best == null || u.effectiveCost() < best.effectiveCost() ? u : best;
    }

    /** The slot whose name contains {@code part}, else {@code fallback}. */
    private static int findByName(List<ItemStack> slots, String part, int fallback) {
        for (int i = 0; i < Math.min(54, slots.size()); i++) {
            if (name(slots, i).contains(part)) {
                return i;
            }
        }
        return fallback;
    }

    static String name(List<ItemStack> slots, int i) {
        return i < 0 || i >= slots.size() ? "" : CheatUtils.plainName(slots.get(i));
    }

    static List<String> lore(List<ItemStack> slots, int i) {
        return i < 0 || i >= slots.size() ? List.of() : CheatUtils.lore(slots.get(i));
    }

    /** The amount on the line after "Cost" ("123,456 Chocolate"). */
    static Long parseCost(List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).trim().startsWith("Cost")) {
                if (i + 1 >= lore.size()) {
                    return null;
                }
                Matcher m = DIGITS.matcher(lore.get(i + 1));
                return m.find() ? safeLong(m.group(1).replace(",", "")) : null;
            }
        }
        return null;
    }

    private static Double firstMatch(List<String> lines, Pattern p) {
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static long parseLong(String s) {
        Matcher m = DIGITS.matcher(s);
        Long v = m.find() ? safeLong(m.group(1).replace(",", "")) : null;
        return v == null ? 0L : v;
    }

    private static Long safeLong(String digits) {
        if (digits.isEmpty() || digits.length() > 18) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Roman numeral after the last space of an item name, 0 if none. */
    static int romanTier(String name) {
        int idx = name.lastIndexOf(' ');
        if (idx < 0 || idx == name.length() - 1) {
            return 0;
        }
        String roman = name.substring(idx + 1);
        int total = 0;
        int prev = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> -1;
            };
            if (v < 0) {
                return 0;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /** "3m 20s", "1h 05m", "now". */
    public static String duration(long seconds) {
        if (seconds <= 0) {
            return "now";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h " + String.format("%02dm", (seconds % 3600) / 60);
        }
        return (seconds / 86400) + "d " + ((seconds % 86400) / 3600) + "h";
    }

    /** "1.25M" style. */
    public static String compact(long v) {
        if (v >= 1_000_000_000L) {
            return String.format(java.util.Locale.US, "%.2fB", v / 1e9);
        }
        if (v >= 1_000_000L) {
            return String.format(java.util.Locale.US, "%.2fM", v / 1e6);
        }
        if (v >= 1_000L) {
            return String.format(java.util.Locale.US, "%.1fk", v / 1e3);
        }
        return Long.toString(v);
    }
}
