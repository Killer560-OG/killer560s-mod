package com.killer560.hub.bosshealth;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure half of Boss Health: which names count as a boss on which floor, their max health, the name-tag pattern, and
 * the number formats. No Minecraft types, so it reads the same in the client and in a test.
 *
 * <h2>Where the numbers come from</h2>
 * Name-tag bosses (F1-F6): the armor stand over the boss, "﴾ Bonzo 250k❤ ﴿" for the boss itself and "Healthy Guardian
 * 1M❤" for a minion (Dungeons Guide {@code BossfightProcessor*.java} match exactly these stands; Skytils
 * {@code BossHPDisplays.kt} reads the Sadan and giant stands the same way). The tag's number is the current health.
 * Boss-bar bosses (F4 Thorn, F7 withers): the bar's name and fill times a per-floor max (NoammAddons
 * {@code dungeon/BossBarHealth.kt}, Skytils {@code DungeonFeatures.onBossBarSet}; the two agree on every number).
 * <p>
 * Max health: hypixelskyblock.minecraft.wiki, read 2026-10-25 (Bonzo, Scarf, The Professor, Livid, Sadan pages), which
 * agrees with Dungeons Guide's table except the Reinforced Guardian (wiki 1.2M on F3, DG 1M; the wiki is used). Where
 * neither gives a number (M6 Sadan) the max is the highest health seen this run, and a shown max is never below a
 * seen current, so a wrong table entry cannot read "300M/250M".
 */
public final class BossHealthText {

    /** What one floor shows: its main boss names (shown always) and its minions (behind "Show Minions"). */
    record FloorBosses(List<String> bosses, List<String> minions) {
    }

    private BossHealthText() {
    }

    /** The floor number of "F3"/"M3"/"E", 0 for the Entrance or anything unknown. */
    static int floorNumber(String floor) {
        if (floor == null || floor.isEmpty()) {
            return 0;
        }
        char c = floor.charAt(floor.length() - 1);
        return Character.isDigit(c) ? c - '0' : 0;
    }

    static boolean isMaster(String floor) {
        return floor != null && floor.startsWith("M");
    }

    /** Name-tag names per floor (boss-bar bosses are in {@link #barMax}). */
    static FloorBosses tagNames(int floor) {
        return switch (floor) {
            case 1 -> new FloorBosses(List.of("Bonzo"), List.of());
            case 2 -> new FloorBosses(List.of("Scarf"),
                    List.of("Undead Warrior", "Undead Priest", "Undead Mage", "Undead Archer"));
            case 3 -> new FloorBosses(List.of("The Professor"),
                    List.of("Healthy Guardian", "Reinforced Guardian", "Chaos Guardian", "Laser Guardian"));
            case 5 -> new FloorBosses(List.of("Livid"), List.of());
            case 6 -> new FloorBosses(List.of("Sadan"),
                    List.of("The Diamond Giant", "Bigfoot", "L.A.S.R.", "Jolly Pink Giant", "Mutant Giant"));
            default -> new FloorBosses(List.of(), List.of());
        };
    }

    /**
     * A boss or minion name tag, whole line, color codes already stripped: up to two runs of icon characters (no letter,
     * digit, space, bracket or colon, so a chat-style "[VIP] Bob:" cannot pass), an optional "﴾ ", optional "[Lv123] ",
     * one optional capitalized word before Livid ("Hockey Livid"), the name, the health ("250k", "1.2M", "0") with an
     * optional "/max", the heart, and an optional " ﴿". Every quantifier is bounded.
     */
    private static final Pattern TAG = Pattern.compile(
            "^(?:[^\\sA-Za-z0-9\\[\\]:]{1,4} ){0,2}(?:\\[Lv\\d{1,4}] )?((?:[A-Z][a-z]{1,10} )?[A-Z][A-Za-z.' ]{1,24}?) "
                    + "(\\d{1,4}(?:[.,]\\d{1,3})?[kMB]?)(?:/(\\d{1,4}(?:[.,]\\d{1,3})?[kMB]?))?❤(?: ﴿)?$");

    /** A parsed name tag: the boss name as listed in {@link #tagNames}, the health, and the tag's own max (0 if none). */
    record Tag(String name, long health, long tagMax, boolean boss) {
    }

    /** Parses a stripped name-tag line for {@code floor}; null when it is not one of that floor's bosses or minions. */
    static Tag parseTag(String plain, int floor) {
        if (plain == null || plain.length() > 80 || plain.indexOf('❤') < 0) {
            return null;
        }
        Matcher m = TAG.matcher(plain);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1);
        FloorBosses fb = tagNames(floor);
        String listed = null;
        boolean boss = false;
        for (String b : fb.bosses()) {
            if (name.equals(b) || ("Livid".equals(b) && name.endsWith(" Livid"))) {
                listed = b;
                boss = true;
            }
        }
        if (listed == null) {
            for (String b : fb.minions()) {
                if (name.equals(b)) {
                    listed = b;
                }
            }
        }
        if (listed == null) {
            return null;
        }
        long hp = parseNumber(m.group(2));
        long tagMax = m.group(3) == null ? 0L : parseNumber(m.group(3));
        return hp < 0 ? null : new Tag(listed, hp, Math.max(0L, tagMax), boss);
    }

    /** "250k" -> 250000, "1.2M" -> 1200000, "7" -> 7; -1 when it is not a number. A comma is a decimal point here. */
    static long parseNumber(String s) {
        if (s == null || s.isEmpty()) {
            return -1L;
        }
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        String digits = s;
        if (last == 'k' || last == 'M' || last == 'B') {
            mult = last == 'k' ? 1e3 : last == 'M' ? 1e6 : 1e9;
            digits = s.substring(0, s.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(digits.replace(',', '.')) * mult);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** Max health of a name-tag boss on this floor, 0 when no source gives one. */
    static long tagMax(String name, boolean master) {
        return switch (name) {
            case "Bonzo" -> master ? 200_000_000L : 250_000L;
            case "Scarf" -> master ? 375_000_000L : 1_000_000L;
            case "Undead Warrior" -> master ? 75_000_000L : 500_000L;
            case "Undead Priest" -> master ? 90_000_000L : 600_000L;
            case "Undead Mage", "Undead Archer" -> master ? 60_000_000L : 400_000L;
            case "The Professor" -> master ? 600_000_000L : 3_000_000L;
            case "Healthy Guardian", "Chaos Guardian", "Laser Guardian" -> master ? 120_000_000L : 1_000_000L;
            case "Reinforced Guardian" -> master ? 140_000_000L : 1_200_000L;
            case "Livid" -> master ? 600_000_000L : 7_000_000L;
            case "Sadan" -> master ? 0L : 40_000_000L;
            case "The Diamond Giant", "Bigfoot", "L.A.S.R.", "Jolly Pink Giant" -> 25_000_000L;
            case "Mutant Giant" -> 600_000_000L;
            default -> 0L;
        };
    }

    /** The boss-bar boss a bar's stripped name names on this floor, or null. F4 Thorn and the four F7 withers only. */
    static String barBoss(String barName, int floor) {
        if (barName == null) {
            return null;
        }
        String n = barName.replace("﴾", "").replace("﴿", "").trim();
        if (floor == 4 && n.equals("Thorn")) {
            return "Thorn";
        }
        if (floor == 7 && (n.equals("Maxor") || n.equals("Storm") || n.equals("Goldor") || n.equals("Necron"))) {
            return n;
        }
        return null;
    }

    /** Max of a boss-bar boss: health for the withers, LIVES for Thorn (NoammAddons BossBarHealth.kt, Skytils). */
    static long barMax(String boss, boolean master) {
        return switch (boss) {
            case "Thorn" -> master ? 6L : 4L;
            case "Maxor" -> master ? 800_000_000L : 100_000_000L;
            case "Storm" -> master ? 1_000_000_000L : 400_000_000L;
            case "Goldor" -> master ? 1_200_000_000L : 750_000_000L;
            case "Necron" -> master ? 1_400_000_000L : 1_000_000_000L;
            default -> 0L;
        };
    }

    /** 1234 -> "1.2k", 25000000 -> "25M", 1400000000 -> "1.4B"; under 1000 as it is. */
    static String format(long v) {
        if (v >= 1_000_000_000L) {
            return trim(v / 1e9) + "B";
        }
        if (v >= 1_000_000L) {
            return trim(v / 1e6) + "M";
        }
        if (v >= 1_000L) {
            return trim(v / 1e3) + "k";
        }
        return Long.toString(Math.max(0L, v));
    }

    private static String trim(double d) {
        String s = String.format(Locale.US, "%.1f", Math.floor(d * 10) / 10);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** One HUD line: name in the boss's color, health colored by how much is left, optional max and percent. */
    static String line(String name, long current, long max, boolean showMax, boolean showPercent, boolean lives) {
        double frac = max > 0 ? Math.max(0, Math.min(1, current / (double) max)) : -1;
        String hpColor = frac < 0 ? "§a" : frac > 0.5 ? "§a" : frac > 0.25 ? "§e" : "§c";
        StringBuilder sb = new StringBuilder();
        sb.append("§c§l").append(name).append("§r ").append(hpColor).append(format(current));
        if (showMax && max > 0) {
            sb.append("§7/§a").append(format(max));
        }
        sb.append(lives ? " §clives" : "§c❤");
        if (showPercent && frac >= 0) {
            sb.append(" §7(").append(Math.round(frac * 100)).append("%)");
        }
        return sb.toString();
    }
}
