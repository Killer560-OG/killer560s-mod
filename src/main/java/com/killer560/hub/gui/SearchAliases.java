package com.killer560.hub.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Short names players type in the menu search that don't appear in any tab or setting label. The search
 *  box still shows what was typed; a tab shows if it matches the typed text OR any alias it expands to. */
public final class SearchAliases {
    private SearchAliases() {}

    // killer560 (2026-10-12): "etable" should find every Experimentation Table feature, and so should any start
    // of it ("eta", "etab") - not only the full word.
    private static final Map<String, String> ALIASES = Map.of(
            "etable", "experiment",
            "e table", "experiment",
            "exp table", "experiment",
            // killer560 (2026-10-18): "make chat keybinds also pop up if I search something like chat commands" - the tab was
            // renamed from Command Shortcuts, so the old names find it too.
            "chat command", "chat keybind",
            "command shortcut", "chat keybind",
            "command keybind", "chat keybind",
            // killer560 (2026-10-18): "cf" should find the Chocolate Factory tab.
            "cf", "chocolate factory");

    /** A typed prefix shorter than this only matches the alias when typed in full ("e" alone stays plain). */
    private static final int MIN_PREFIX = 2;

    /**
     * Whether {@code name} answers the typed {@code query} (killer560, 2026-10-18: "make copy chat also pop up in the search
     * bar when i search chat copy"): the query as it is typed, or, for a query of several words, every word somewhere in
     * the name in any order. "chat copy" finds Copy Chat; one word behaves exactly as a plain contains.
     */
    public static boolean nameMatches(String name, String query) {
        String n = name.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty() || n.contains(q)) {
            return true;
        }
        String[] words = q.split("\\s+");
        if (words.length < 2) {
            return false;
        }
        for (String w : words) {
            if (!n.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** The typed query first, then each alias target the query names - in full, inside a longer query, or as
     *  the start of the alias word. */
    public static List<String> variants(String query) {
        List<String> out = new ArrayList<>();
        out.add(query);
        String q = query.toLowerCase(Locale.US).trim();
        if (q.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            String key = e.getKey();
            String target = null;
            if (q.contains(key)) {
                target = q.replace(key, e.getValue());
            } else if (q.length() >= MIN_PREFIX && key.startsWith(q)) {
                target = e.getValue();
            }
            if (target != null && !out.contains(target)) {
                out.add(target);
            }
        }
        return out;
    }
}
