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
            "exp table", "experiment");

    /** A typed prefix shorter than this only matches the alias when typed in full ("e" alone stays plain). */
    private static final int MIN_PREFIX = 2;

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
