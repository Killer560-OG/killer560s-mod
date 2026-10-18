package com.killer560.hub.lfg;

import com.killer560.hub.partyfinder.PartyFinderStatsApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A listing's requirements checked against one player's stats (default (b), 2026-10-18: requirements are copied from the
 * listing and checked against the joiner). Only what the Party Finder stats cache knows for ANY player can be checked:
 * {@code cata} (Catacombs level, whole levels) and {@code secrets} (total secrets). Anything else is {@link
 * Result#UNCHECKED} and shown as such, never as met.
 */
public final class LfgRequirements {

    public enum Result { MET, UNMET, UNKNOWN, UNCHECKED }

    /** One requirement: what the listing asks, what the player has (null when not known), and the verdict. */
    public record Check(String key, String label, int need, Double have, Result result) {
        public String describe() {
            String haveText = have == null ? "?" : (key.equals("cata") ? String.valueOf((int) Math.floor(have))
                    : String.valueOf(Math.round(have)));
            return switch (result) {
                case MET -> "§a" + label + " " + need + " (" + haveText + ")";
                case UNMET -> "§c" + label + " " + need + " (" + haveText + ")";
                case UNKNOWN -> "§7" + label + " " + need + " (checking)";
                case UNCHECKED -> "§7" + label + " " + need + " (not checked)";
            };
        }
    }

    private LfgRequirements() {
    }

    public static List<Check> check(LfgCategory category, Map<String, Integer> reqs, String player) {
        List<Check> out = new ArrayList<>();
        if (reqs == null || reqs.isEmpty()) {
            return out;
        }
        PartyFinderStatsApi.PlayerStats stats = player == null ? null : PartyFinderStatsApi.get(player);
        for (Map.Entry<String, Integer> e : reqs.entrySet()) {
            String key = e.getKey();
            int need = e.getValue();
            String label = category == null ? key : category.labelFor(key);
            if (need <= 0) {
                continue;
            }
            Double have = null;
            boolean checkable = key.equals("cata") || key.equals("secrets");
            if (checkable && stats != null) {
                have = key.equals("cata") ? stats.level() : (double) stats.secrets();
            }
            Result r;
            if (!checkable) {
                r = Result.UNCHECKED;
            } else if (have == null) {
                r = Result.UNKNOWN;
            } else {
                r = (key.equals("cata") ? Math.floor(have) : have) >= need ? Result.MET : Result.UNMET;
            }
            out.add(new Check(key, label, need, have, r));
        }
        return out;
    }

    public static boolean anyUnmet(List<Check> checks) {
        for (Check c : checks) {
            if (c.result() == Result.UNMET) {
                return true;
            }
        }
        return false;
    }
}
