package com.killer560.hub.gui;

import java.util.Locale;
import java.util.Map;

/** Short names players type in the menu search that don't appear in any tab or setting label. The search
 *  box still shows what was typed; only the text tabs are matched against is rewritten. */
public final class SearchAliases {
    private SearchAliases() {}

    // killer560 (2026-10-12): "etable" should find every Experimentation Table feature.
    private static final Map<String, String> ALIASES = Map.of(
            "etable", "experiment",
            "e table", "experiment",
            "exp table", "experiment");

    public static String apply(String query) {
        String q = query.toLowerCase(Locale.US).trim();
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (q.contains(e.getKey())) {
                q = q.replace(e.getKey(), e.getValue());
            }
        }
        return q;
    }
}
