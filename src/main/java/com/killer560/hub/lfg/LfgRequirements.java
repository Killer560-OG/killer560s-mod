package com.killer560.hub.lfg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.partyfinder.PartyFinderStatsApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A listing's requirements checked against one player's stats (default (b), 2026-10-18: requirements are copied from the
 * listing and checked against the joiner). Only what the Party Finder stats cache knows for ANY player can be checked:
 * {@code cata} (Catacombs level, whole levels), {@code secrets} (total secrets) and {@code pb} (the listing floor's S+
 * personal best in whole seconds, at most the requirement - lower is harder; no S+ on that floor is unmet, as Auto Kick
 * judges it). Anything else is {@link Result#UNCHECKED} and shown as such, never as met. The relay (src/lfg.ts
 * {@code meetsReqs}) applies the same three rules to its own copy of the same stats.
 */
public final class LfgRequirements {

    public enum Result { MET, UNMET, UNKNOWN, UNCHECKED }

    private static final Pattern MM_SS = Pattern.compile("^(\\d{1,3}):(\\d{2})$");
    private static final Pattern FLOOR = Pattern.compile("^([FM])([1-7])$");

    /** One requirement: what the listing asks, what the player has (null when not known), and the verdict. */
    public record Check(String key, String label, int need, Double have, Result result) {
        public String describe() {
            boolean pb = key.equals(LfgCategory.PB);
            String needText = pb ? mmSs(need) : String.valueOf(need);
            String haveText = have == null ? "?" : pb ? (have <= 0 ? "none" : mmSs((int) Math.round(have)))
                    : (key.equals("cata") ? String.valueOf((int) Math.floor(have)) : String.valueOf(Math.round(have)));
            return switch (result) {
                case MET -> "§a" + label + " " + needText + " (" + haveText + ")";
                case UNMET -> "§c" + label + " " + needText + " (" + haveText + ")";
                case UNKNOWN -> "§7" + label + " " + needText + " (checking)";
                case UNCHECKED -> "§7" + label + " " + needText + " (not checked)";
            };
        }

        /** Plain words for a chat line or a refusal: "Cata Level 30 (has 25)". */
        public String plain() {
            boolean pb = key.equals(LfgCategory.PB);
            String needText = pb ? mmSs(need) : String.valueOf(need);
            String haveText = have == null ? "unknown" : pb ? (have <= 0 ? "none" : mmSs((int) Math.round(have)))
                    : (key.equals("cata") ? String.valueOf((int) Math.floor(have)) : String.valueOf(Math.round(have)));
            return label + " " + needText + " (has " + haveText + ")";
        }
    }

    private LfgRequirements() {
    }

    public static String mmSs(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Requirements whose key the checks understand; the rest are shown but never checked. */
    public static boolean checkable(String key) {
        return key.equals("cata") || key.equals("secrets") || key.equals(LfgCategory.PB);
    }

    /** As {@link #check(LfgCategory, String, Map, String)} without a floor (a PB requirement is then unchecked). */
    public static List<Check> check(LfgCategory category, Map<String, Integer> reqs, String player) {
        return check(category, null, reqs, player);
    }

    public static List<Check> check(LfgCategory category, String activity, Map<String, Integer> reqs, String player) {
        return check(category, activity, reqs, player == null ? null : PartyFinderStatsApi.get(player));
    }

    public static List<Check> check(LfgCategory category, String activity, Map<String, Integer> reqs,
                                    PartyFinderStatsApi.PlayerStats stats) {
        List<Check> out = new ArrayList<>();
        if (reqs == null || reqs.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Integer> e : reqs.entrySet()) {
            String key = e.getKey();
            int need = e.getValue();
            String label = category == null ? key : category.labelFor(key);
            if (need <= 0) {
                continue;
            }
            boolean pb = key.equals(LfgCategory.PB);
            boolean checkable = key.equals("cata") || key.equals("secrets") || (pb && floorKey(activity) != null);
            Double have = null;
            if (checkable && stats != null) {
                have = key.equals("cata") ? stats.level() : key.equals("secrets") ? (double) stats.secrets()
                        : (double) pbSeconds(stats, activity);
            }
            Result r;
            if (!checkable) {
                r = Result.UNCHECKED;
            } else if (have == null) {
                r = Result.UNKNOWN;
            } else if (pb) {
                r = have > 0 && have <= need ? Result.MET : Result.UNMET;
            } else {
                r = (key.equals("cata") ? Math.floor(have) : have) >= need ? Result.MET : Result.UNMET;
            }
            out.add(new Check(key, label, need, have, r));
        }
        return out;
    }

    /** "floor_7" with master true for "M7", "floor_0" for "Entrance"; null for anything that is not a floor. */
    static String[] floorKey(String activity) {
        if (activity == null) {
            return null;
        }
        if (activity.equals("Entrance")) {
            return new String[]{"normal", "floor_0"};
        }
        Matcher m = FLOOR.matcher(activity);
        if (!m.matches()) {
            return null;
        }
        return new String[]{m.group(1).equals("M") ? "master" : "normal", "floor_" + m.group(2)};
    }

    /** The player's S+ PB on that floor in seconds, 0 when he has none. */
    static int pbSeconds(PartyFinderStatsApi.PlayerStats stats, String activity) {
        String[] fk = floorKey(activity);
        if (fk == null || stats == null) {
            return 0;
        }
        JsonObject map = fk[0].equals("master") ? stats.pbMaster() : stats.pbNormal();
        if (map == null || !map.has("s_plus") || !map.get("s_plus").isJsonObject()) {
            return 0;
        }
        JsonElement t = map.getAsJsonObject("s_plus").get(fk[1]);
        if (t == null || !t.isJsonPrimitive()) {
            return 0;
        }
        Matcher m = MM_SS.matcher(t.getAsString().trim());
        return m.matches() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : 0;
    }

    public static boolean anyUnmet(List<Check> checks) {
        for (Check c : checks) {
            if (c.result() == Result.UNMET) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyUnknown(List<Check> checks) {
        for (Check c : checks) {
            if (c.result() == Result.UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    /** A create-form box's starting value from the player's own stats (killer560, 2026-10-18): Cata Level and Secrets from
     *  the stats, and the S+ PB on the floor picked from the same stats. Null when the stats do not give it, or the value is
     *  above what the box holds (a PB slower than the field's maximum is left off). */
    public static Integer ownValue(String key, PartyFinderStatsApi.PlayerStats s, String activity, int max) {
        if (s == null) {
            return null;
        }
        int v;
        if (key.equals("cata")) {
            v = (int) Math.floor(s.level());
        } else if (key.equals("secrets")) {
            v = s.secrets();
        } else if (key.equals(LfgCategory.PB)) {
            v = pbSeconds(s, activity);
        } else {
            return null;
        }
        return v <= 0 || v > max ? null : v;
    }

    /** Parses a PB box: "4:30" or a plain number of seconds; 0 when empty or unreadable. */
    public static int parsePb(String text) {
        if (text == null) {
            return 0;
        }
        String t = text.trim();
        Matcher m = MM_SS.matcher(t);
        if (m.matches()) {
            int s = Integer.parseInt(m.group(2));
            return s < 60 ? Integer.parseInt(m.group(1)) * 60 + s : 0;
        }
        return t.matches("^\\d{1,4}$") ? Integer.parseInt(t) : 0;
    }
}
