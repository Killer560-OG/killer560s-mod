package com.killer560.hub.autocorrect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure typo logic for typed command names and the one argument with a fixed value list (/warp destinations);
 * {@link AutoCorrectFeature#correctOutgoingCommand} adds the Minecraft side. A name or value that is already valid is
 * never touched, and a typo equally close to two valid ones is left alone rather than guessed.
 * <p>
 * Limit (Damerau-Levenshtein, a swap of two adjacent letters counts 1): typed length 2-3 fixes only an adjacent swap
 * ("zb" -> "bz") or a doubled letter ("bzz" -> "bz"), because one general edit of a 2-3 letter word is too close to too
 * many real commands; length 4-7 fixes one edit (swap, extra, missing or wrong letter, doubled letter); length 8 or more
 * fixes up to two. The closest valid name must be unique. Also fixed: all-caps, leading slashes ("//bz"), and a missing
 * space after "warp" ("warpbase").
 */
public final class CommandFixer {

    /** Common Hypixel commands the client's brigadier tree might not list (Hypixel's server tree isn't guaranteed
     *  complete). Only names in real use: a wrong name here would let a real command be "corrected" into it. */
    private static final Set<String> HYPIXEL_COMMANDS = Set.of(
            "warp", "pc", "party", "p", "pl", "visit", "ah", "bz", "bazaar", "pets", "pet", "wardrobe", "sbmenu",
            "hub", "dh", "dhub", "play", "is", "island", "ec", "enderchest", "storage", "craft", "recipe",
            "viewrecipe", "collection", "collections", "skills", "hotm", "bestiary", "sacks", "trades", "trade", "garden",
            "lobby", "l", "msg", "w", "r", "tell", "reply", "ac", "gc", "oc", "cc", "g", "guild", "f",
            "friend", "fl", "boop", "locraw", "whereami", "calendar", "equipment", "accessories",
            "potionbag", "quiver", "fishingbag", "joininstance", "rejoin", "chat", "pv", "gfs", "bp", "backpack", "et",
            "coop", "coopadd", "skyblock", "sb", "auction", "auctions", "stash", "pickupstash", "viewstash", "wiki");

    /** Destinations after "/warp". */
    private static final Set<String> WARP_DESTINATIONS = Set.of(
            "hub", "park", "spider", "end", "gold", "deep", "dwarves", "basecamp", "crystals", "barn", "dungeon_hub",
            "dhub", "dh", "isle", "jerry", "bayou", "moonglade", "lotus", "safari", "torrhus", "base", "desert", "castle",
            "crypt", "da", "museum", "wizard", "trapper", "forge", "nest", "void", "drag", "howl");

    private CommandFixer() {
    }

    public static Set<String> baseCommands() {
        return HYPIXEL_COMMANDS;
    }

    public static Set<String> warpDestinations() {
        return WARP_DESTINATIONS;
    }

    /** Result text: "VALID", "FIXED:target", "AMBIGUOUS:a,b" or "NONE". {@code lower} is lower case. */
    public static String classify(String lower, Set<String> valid) {
        if (valid.contains(lower)) {
            return "VALID";
        }
        int len = lower.length();
        int limit = len <= 3 ? 1 : len <= 7 ? 1 : 2;
        int best = Integer.MAX_VALUE;
        TreeSet<String> found = new TreeSet<>();
        for (String c : valid) {
            int d;
            if (len <= 3) {
                d = c.length() >= 2 && (swapOf(lower, c) || doubledOf(lower, c)) ? 1 : Integer.MAX_VALUE;
            } else if (c.length() < 3 || Math.abs(c.length() - len) > limit) {
                continue;
            } else {
                d = distance(lower, c);
            }
            if (d > limit) {
                continue;
            }
            if (d < best) {
                best = d;
                found.clear();
            }
            if (d == best) {
                found.add(c);
            }
        }
        if (found.isEmpty()) {
            return "NONE";
        }
        return found.size() == 1 ? "FIXED:" + found.first() : "AMBIGUOUS:" + String.join(",", found);
    }

    private static boolean swapOf(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i + 1 < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                        && a.substring(i + 2).equals(b.substring(i + 2));
            }
        }
        return false;
    }

    /** {@code typed} is {@code c} with one letter written twice. */
    private static boolean doubledOf(String typed, String c) {
        if (typed.length() != c.length() + 1) {
            return false;
        }
        for (int i = 0; i + 1 < typed.length(); i++) {
            if (typed.charAt(i) == typed.charAt(i + 1) && (typed.substring(0, i) + typed.substring(i + 1)).equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** Optimal string alignment distance: insert, delete, substitute and adjacent swap each cost 1. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** The value {@code arg} stands for among {@code values}: itself when valid, the unique fix, or null. */
    private static String resolve(String arg, Set<String> values) {
        String lower = arg.toLowerCase(Locale.US);
        String r = classify(lower, values);
        return r.equals("VALID") ? lower : r.startsWith("FIXED:") ? r.substring(6) : null;
    }

    /** {@code rest} is " dest"; several words are tried joined ("d h" -> "dh", "dungeon hub" -> "dungeon_hub"), and
     *  anything that does not resolve stays as typed. */
    private static String fixWarpArgument(String rest) {
        if (rest.length() < 2 || rest.charAt(0) != ' ') {
            return rest;
        }
        String arg = rest.substring(1);
        String[] words = arg.split(" ", -1);
        if (words.length > 3 || arg.endsWith(" ") || arg.contains("  ")) {
            return rest;
        }
        String fix = resolve(arg.replace(' ', '_'), WARP_DESTINATIONS);
        if (fix == null && words.length > 1) {
            fix = resolve(String.join("", words), WARP_DESTINATIONS);
        }
        return fix == null ? rest : " " + fix;
    }

    /**
     * {@code command} has no leading "/". Returns it with only the name (and a /warp destination) fixed, or
     * {@code command} itself when nothing is safe to change.
     */
    public static String fix(String command, Set<String> known) {
        if (known.isEmpty() || command.isBlank()) {
            return command;
        }
        String stripped = command;
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        int space = stripped.indexOf(' ');
        String name = space < 0 ? stripped : stripped.substring(0, space);
        String rest = stripped.substring(name.length());
        if (name.isEmpty() || name.indexOf(':') >= 0) {
            return command;
        }
        String lower = name.toLowerCase(Locale.US);
        String target = null;
        if (known.contains(lower)) {
            target = name.equals(name.toUpperCase(Locale.US)) || known.contains(name) ? lower : name;
        } else if (rest.isEmpty() && lower.startsWith("warp") && lower.length() > 5
                && resolve(lower.substring(4), WARP_DESTINATIONS) != null) {
            return "warp " + resolve(lower.substring(4), WARP_DESTINATIONS);
        } else {
            String r = classify(lower, known);
            target = r.startsWith("FIXED:") ? r.substring(6) : null;
        }
        if (target == null) {
            return command;
        }
        return target + (target.equals("warp") ? fixWarpArgument(rest) : rest);
    }

    /** Candidate names a typed name is equally close to (empty when none or one), for the log. */
    public static List<String> ambiguousBetween(String name, Set<String> known) {
        String r = classify(name.toLowerCase(Locale.US), known);
        return r.startsWith("AMBIGUOUS:") ? new ArrayList<>(List.of(r.substring(10).split(","))) : List.of();
    }
}
