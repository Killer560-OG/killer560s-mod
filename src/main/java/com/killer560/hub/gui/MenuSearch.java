package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.MenuCategories;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The mod menu's search (killer560, 2026-11-02: "i keep searching for things we have and I cant find them" - 'star mob'
 * and 'wither esp' found nothing, though Dungeon ESP boxes starred mobs and the withers).
 * <p>
 * Every query word must be found, in any order, somewhere in a feature: its card name, its card description and hover
 * text, the label of every row it builds (with its collapsed sections and switched-off rows laid open, see
 * {@link BaseTab#searchScanWidgets}) and each row's tooltip. A word matches the start of a word ("star" finds Starred),
 * the same word with a plural or -ed/-ing ending ("hitboxes", "glowing"), or a word from {@link #SYNONYMS} ("esp" finds a
 * Highlight row, "effects" a Potion one); a word of four letters or more inside a longer word counts a little. The phrase
 * aliases of {@link SearchAliases} still apply. Features rank by where their words were found: a card name equal to the
 * query first, then names that start with it or hold every word, then descriptions; settings follow, best first.
 * <p>
 * Fast: the text of every tab is read once per search (the first keystroke after the box was empty), kept, and matched
 * from memory on every later keystroke; nothing is rebuilt per key. {@link #lastIndexNanos} and {@link #lastQueryNanos}
 * are for the testkit.
 */
public final class MenuSearch {

    private MenuSearch() {
    }

    /** One hit: a feature card ({@code feature}) or one of its rows. */
    public record Hit(BaseTab tab, String label, boolean feature, double score) {
    }

    /** Words a player types for the same thing. Each group is equivalent both ways; words are matched stemmed. */
    private static final String[][] SYNONYMS = {
            {"esp", "highlight", "hitbox", "glow", "outline", "box", "wallhack", "xray", "chams"},
            {"effect", "potion", "buff"},
            {"dump", "deposit", "stash", "unload"},
            {"mob", "monster", "enemy", "entity"},
            {"waypoint", "marker", "beacon", "pin"},
            {"prefix", "alias", "command name", "shortcut name"},
            {"hud", "overlay", "display"},
            {"keybind", "key", "hotkey", "bind", "shortcut", "keybinding"},
            {"sound", "audio", "mute", "noise", "volume", "sfx"},
            {"hide", "hider", "remove", "invisible", "hidden"},
            {"color", "colour", "tint"},
            {"gray", "grey"},
            {"armor", "armour"},
            {"timer", "countdown", "clock", "split", "stopwatch"},
            {"chat", "message", "msg"},
            {"auction", "ah"},
            {"bazaar", "bz"},
            {"teleport", "tp", "warp"},
            {"etherwarp", "ew", "aotv"},
            {"dungeon", "catacomb", "cata"},
            {"terminal", "term"},
            {"inventory", "inv"},
            {"fps", "performance", "lag", "optimize"},
            {"brightness", "fullbright", "gamma"},
            {"cooldown", "cd"},
            {"health", "hp", "heart"},
            {"experience", "xp", "exp"},
            {"nick", "nickname", "nametag"},
            {"map", "minimap"},
            {"party", "pt"},
            {"damage", "dmg"},
            {"profit", "money", "coin"},
            {"solver", "solve", "puzzle"},
            {"trigger", "triggerbot", "tb"},
            {"auto", "automatic", "macro"},
            {"leap", "spirit"},
            {"route", "path", "pathfind"},
            {"tracker", "track", "log", "logger"},
            {"cape", "cosmetic"},
            {"skin", "reskin", "theme"},
            {"tooltip", "lore"},
            {"warning", "alert", "notify", "notification", "alarm"},
            {"zoom", "magnify"},
            {"ghost", "phantom", "desync"},
    };

    /** Stemmed word -> the stemmed words it stands for (itself excluded). */
    private static final Map<String, Set<String>> SYN = new HashMap<>();

    static {
        for (String[] group : SYNONYMS) {
            for (String a : group) {
                String sa = stem(a);
                Set<String> set = SYN.computeIfAbsent(sa, k -> new LinkedHashSet<>());
                for (String b : group) {
                    String sb = stem(b);
                    if (!sb.equals(sa)) {
                        set.add(sb);
                    }
                }
            }
        }
    }

    /** Lower case, plural and -ed/-ing endings off, a doubled last letter undone ("starred" -> "star"). */
    static String stem(String w) {
        int n = w.length();
        if (n <= 3) {
            return w;
        }
        String s = w;
        if (s.endsWith("ies") && n > 4) {
            s = s.substring(0, n - 3) + "y";
        } else if (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("ches") || s.endsWith("shes") || s.endsWith("zes")) {
            s = s.substring(0, n - 2);
        } else if (s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is")) {
            s = s.substring(0, n - 1);
        } else if (s.endsWith("ing") && n > 5) {
            s = s.substring(0, n - 3);
        } else if (s.endsWith("ed") && n > 4) {
            s = s.substring(0, n - 2);
        } else {
            return s;
        }
        int m = s.length();
        if (m > 3 && s.charAt(m - 1) == s.charAt(m - 2) && "lsz".indexOf(s.charAt(m - 1)) < 0) {
            s = s.substring(0, m - 1);
        }
        return s;
    }

    /** The words of {@code text} (lower case, split on anything not a letter or digit), each with its stem. */
    static String[][] words(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0][];
        }
        String plain = ChatFormatting.stripFormatting(text);
        String[] raw = (plain == null ? text : plain).toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String[]> out = new ArrayList<>(raw.length);
        for (String r : raw) {
            if (!r.isEmpty()) {
                out.add(new String[]{r, stem(r)});
            }
        }
        return out.toArray(new String[0][]);
    }

    /** The words joined by single spaces ("Hide Effect Icons (HUD)" -> "hide effect icons hud"). */
    static String norm(String[][] words) {
        return java.util.Arrays.stream(words).map(w -> w[0]).collect(java.util.stream.Collectors.joining(" "));
    }

    /** One typed word's forms: itself, its stem and each synonym, with how much a match through each counts. */
    private record Form(String text, String stem, double weight) {
    }

    private static List<Form> forms(String word) {
        List<Form> out = new ArrayList<>();
        String s = stem(word);
        out.add(new Form(word, s, 1.0));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(s);
        for (String key : new String[]{word, s}) {
            Set<String> syn = SYN.get(key);
            if (syn != null) {
                for (String t : syn) {
                    if (seen.add(t)) {
                        out.add(new Form(t, t, 0.8));
                    }
                }
            }
        }
        return out;
    }

    /** How well one typed word (its forms) is found among {@code text}'s words: 0 when it is not. */
    private static double wordScore(List<Form> forms, String[][] text) {
        double best = 0;
        for (Form f : forms) {
            boolean typed = f.weight >= 1.0;
            for (String[] t : text) {
                double s;
                if (t[0].equals(f.text) || t[1].equals(f.stem)) {
                    s = 1.0;
                } else if (t[0].startsWith(f.text) && (typed || f.text.length() >= 3)) {
                    s = 0.85;
                } else if (typed && f.text.length() >= 4 && t[0].contains(f.text)) {
                    s = 0.4;
                } else {
                    continue;
                }
                s *= f.weight;
                if (s > best) {
                    best = s;
                    if (best >= 1.0) {
                        return best;
                    }
                }
            }
        }
        return best;
    }

    private record Row(String label, String labelNorm, String[][] labelWords, String[][] tipWords) {
    }

    private static final class Doc {
        final BaseTab tab;
        final String name;
        final String[][] nameWords;
        String[][] descWords;
        final List<Row> rows = new ArrayList<>();
        /** The chat commands this card owns ({@link SearchCommands}), lower case, no "/". */
        final List<String> commands = new ArrayList<>();

        Doc(BaseTab tab) {
            this.tab = tab;
            this.name = tab.name;
            this.nameWords = words(tab.name);
        }
    }

    private static final Map<BaseTab, Doc> INDEX = new IdentityHashMap<>();
    private static List<BaseTab> indexedLeaves = List.of();

    /** Testkit: how long the last index build and the last query took. */
    public static volatile long lastIndexNanos;
    public static volatile long lastQueryNanos;
    /** Testkit: how many times the index was built. */
    public static volatile int indexBuilds;

    /** Drops the read text (the menu opened again, or the search box was emptied). */
    public static void invalidate() {
        INDEX.clear();
        indexedLeaves = List.of();
    }

    private static void ensureIndex(List<BaseTab> leaves) {
        if (!INDEX.isEmpty() && sameLeaves(leaves)) {
            return;
        }
        long t0 = System.nanoTime();
        INDEX.clear();
        Map<String, List<String>> commandsByTab = new HashMap<>();
        try {
            for (Map.Entry<String, String> e : SearchCommands.owners().entrySet()) {
                if (e.getValue() != null) {
                    commandsByTab.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey().toLowerCase(Locale.ROOT));
                }
            }
        } catch (RuntimeException ignored) {
        }
        for (BaseTab leaf : leaves) {
            Doc d = read(leaf);
            List<String> cmds = commandsByTab.get(leaf.getClass().getSimpleName());
            if (cmds != null) {
                d.commands.addAll(cmds);
            }
            INDEX.put(leaf, d);
        }
        indexedLeaves = new ArrayList<>(leaves);
        indexBuilds++;
        lastIndexNanos = System.nanoTime() - t0;
    }

    private static boolean sameLeaves(List<BaseTab> leaves) {
        return leaves.equals(indexedLeaves);
    }

    private static Doc read(BaseTab leaf) {
        StringBuilder desc = new StringBuilder();
        try {
            append(desc, FeatureCards.description(leaf));
            append(desc, FeatureCards.longDescription(leaf));
            append(desc, FeatureDescriptions.get(leaf));
            append(desc, CardBlurbs.get(leaf));
        } catch (RuntimeException ignored) {
        }
        Doc d = new Doc(leaf);
        String category = MenuCategories.legacyCategory(leaf);
        Set<String> seen = new LinkedHashSet<>();
        List<AbstractWidget> scan;
        try {
            scan = leaf.searchScanWidgets();
        } catch (RuntimeException e) {
            scan = List.of();
        }
        for (AbstractWidget w : scan) {
            String text;
            try {
                text = ChatFormatting.stripFormatting(w.getMessage().getString());
            } catch (RuntimeException e) {
                continue;
            }
            addRow(d, text, w, category, seen, desc);
        }
        for (String extra : leaf.searchExtraTexts()) {
            addRow(d, extra, null, category, seen, desc);
        }
        d.descWords = words(desc.toString());
        return d;
    }

    /** A row this long, or holding a sentence break, is a line of explanation, not a setting: its words count for the
     *  feature (like its description) but it is never listed as a setting hit. */
    private static final int MAX_LABEL = 48;

    private static void addRow(Doc d, String text, AbstractWidget w, String category, Set<String> seen, StringBuilder notes) {
        if (text == null) {
            return;
        }
        text = text.trim();
        if (!text.isEmpty() && (text.charAt(0) == '▶' || text.charAt(0) == '▼')) {
            text = text.substring(1).trim();
        }
        int colon = text.indexOf(':');
        String label = colon > 0 ? text.substring(0, colon).trim() : text;
        if (label.isEmpty() || !seen.add(label.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (label.length() > MAX_LABEL || label.contains(". ")) {
            append(notes, text);
            return;
        }
        String tip = null;
        try {
            String scope = w == null ? null : SettingTooltips.scopeOf(w);
            tip = SettingTooltips.describeScoped(scope != null ? scope : d.name, category, text);
        } catch (RuntimeException ignored) {
        }
        d.rows.add(new Row(label, norm(words(label)), words(text), words(tip)));
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) {
            sb.append(' ').append(s);
        }
    }

    /**
     * Every hit for {@code query} among {@code leaves} (skipping those {@code hidden} says to), features first, best first.
     */
    public static List<Hit> search(String query, List<BaseTab> leaves, Predicate<BaseTab> hidden) {
        List<Hit> features = new ArrayList<>();
        List<Hit> settings = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return features;
        }
        ensureIndex(leaves);
        long t0 = System.nanoTime();
        Map<BaseTab, Double> featureScore = new LinkedHashMap<>();
        Map<BaseTab, Map<String, Double>> rowScore = new LinkedHashMap<>();
        for (String variant : SearchAliases.variants(query)) {
            String[][] qw = words(variant);
            if (qw.length == 0) {
                continue;
            }
            List<List<Form>> qf = java.util.Arrays.stream(qw).map(w -> forms(w[0])).toList();
            String norm = norm(qw);
            for (BaseTab leaf : leaves) {
                if (hidden.test(leaf)) {
                    continue;
                }
                Doc d = INDEX.get(leaf);
                if (d == null) {
                    continue;
                }
                double f = Math.max(featureScore(d, qf, norm), commandScore(d, qw));
                if (f > 0) {
                    featureScore.merge(leaf, f, Math::max);
                }
                for (Row r : d.rows) {
                    double s = rowScore(d, r, qf, norm);
                    if (s > 0) {
                        rowScore.computeIfAbsent(leaf, k -> new LinkedHashMap<>()).merge(r.label, s, Math::max);
                    }
                }
            }
        }
        // A feature whose words are spread over its name, description and rows (no single place holds them all).
        for (String variant : SearchAliases.variants(query)) {
            String[][] qw = words(variant);
            if (qw.length < 2) {
                continue;
            }
            List<List<Form>> qf = java.util.Arrays.stream(qw).map(w -> forms(w[0])).toList();
            for (BaseTab leaf : leaves) {
                if (hidden.test(leaf) || featureScore.containsKey(leaf) || rowScore.containsKey(leaf)) {
                    continue;
                }
                Doc d = INDEX.get(leaf);
                if (d == null) {
                    continue;
                }
                double total = 0;
                boolean all = true;
                for (List<Form> forms : qf) {
                    double best = Math.max(wordScore(forms, d.nameWords) * 10, wordScore(forms, d.descWords) * 4);
                    for (Row r : d.rows) {
                        best = Math.max(best, Math.max(wordScore(forms, r.labelWords) * 5, wordScore(forms, r.tipWords) * 2));
                    }
                    if (best <= 0) {
                        all = false;
                        break;
                    }
                    total += best;
                }
                if (all) {
                    featureScore.put(leaf, total * 0.5);
                }
            }
        }
        for (Map.Entry<BaseTab, Double> e : featureScore.entrySet()) {
            features.add(new Hit(e.getKey(), e.getKey().name, true, e.getValue()));
        }
        for (Map.Entry<BaseTab, Map<String, Double>> e : rowScore.entrySet()) {
            BaseTab leaf = e.getKey();
            List<Map.Entry<String, Double>> rows = new ArrayList<>(e.getValue().entrySet());
            rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            int n = 0;
            for (Map.Entry<String, Double> r : rows) {
                if (featureScore.containsKey(leaf) && r.getKey().equalsIgnoreCase(leaf.name)) {
                    continue;
                }
                settings.add(new Hit(leaf, r.getKey(), false, r.getValue()));
                if (++n == 3) {
                    break;
                }
            }
        }
        Comparator<Hit> best = Comparator.comparingDouble(Hit::score).reversed();
        features.sort(best);
        settings.sort(best);
        List<Hit> out = new ArrayList<>(features.size() + settings.size());
        out.addAll(features);
        out.addAll(settings);
        lastQueryNanos = System.nanoTime() - t0;
        return out;
    }

    /** A feature card's score: every word in its name or description, 0 when one is missing from both. */
    private static double featureScore(Doc d, List<List<Form>> qf, String norm) {
        double total = 0;
        boolean allInName = true;
        for (List<Form> forms : qf) {
            double n = wordScore(forms, d.nameWords);
            double s = Math.max(n * 10, wordScore(forms, d.descWords) * 4);
            if (s <= 0) {
                return 0;
            }
            if (n < 0.8) {
                allInName = false;
            }
            total += s;
        }
        String name = d.name.toLowerCase(Locale.ROOT);
        String plainName = norm(d.nameWords);
        if (plainName.equals(norm)) {
            total += 1000;
        } else if (plainName.startsWith(norm) || name.startsWith(norm)) {
            total += 200;
        } else if (allInName) {
            total += 100;
        }
        return total;
    }

    /** A card owning the typed chat command: like an exact card name ("invsort", "/invsort"); the command and more words
     *  ("/ar add") a little less; the start of a command of four letters or more less again. */
    private static double commandScore(Doc d, String[][] qw) {
        if (d.commands.isEmpty() || qw.length == 0) {
            return 0;
        }
        String first = qw[0][0];
        if (d.commands.contains(first)) {
            // A one-letter command (/k, /c) is also every first keystroke: it ranks, but not over everything.
            return first.length() < 2 ? 300 : qw.length == 1 ? 1000 : 500;
        }
        return qw.length == 1 && first.length() >= 4 && d.commands.stream().anyMatch(c -> c.startsWith(first)) ? 150 : 0;
    }

    /** A row's score: every word in its label, its tooltip or the feature's name, and at least one in the row itself. */
    private static double rowScore(Doc d, Row r, List<List<Form>> qf, String norm) {
        double total = 0;
        boolean own = false;
        for (List<Form> forms : qf) {
            double l = wordScore(forms, r.labelWords) * 5;
            double t = wordScore(forms, r.tipWords) * 2;
            double n = wordScore(forms, d.nameWords) * 1.5;
            double s = Math.max(l, Math.max(t, n));
            if (s <= 0) {
                return 0;
            }
            if (l > 0 || t > 0) {
                own = true;
            }
            total += s;
        }
        if (!own) {
            return 0;
        }
        if (r.labelNorm.equals(norm)) {
            total += 50;
        } else if (r.labelNorm.startsWith(norm)) {
            total += 20;
        }
        return total;
    }
}
