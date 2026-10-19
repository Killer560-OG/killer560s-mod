package com.killer560.hub.lfg;

import java.util.List;
import java.util.Locale;

/**
 * The Custom Party Finder's activities (killer560's to-do item 1, 2026-10-18; Kuudra is left out until he asks for it; Misc
 * added the same day), in his order: the start screen offers each. Each names the relay's category id, the largest party it allows (the relay checks the same numbers), the
 * activities a listing picks from, and the numeric requirements a listing can set.
 * <p>
 * Only requirements this mod can read for another player are CHECKED against a joiner ({@link LfgRequirements}): Catacombs
 * level, total secrets and the floor's S+ PB, from the Party Finder stats cache (the relay checks the same three). The rest are shown to everyone but never checked, and say so.
 */
public enum LfgCategory {
    DUNGEONS("dungeons", "Dungeons", 5,
            List.of("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"),
            List.of(new Field("cata", "Cata Level", 50), new Field("secrets", "Secrets", 200000),
                    new Field("pb", "S+ PB", 3599))), // "pb" is PB below (an enum constant cannot name it yet)
    DIANA("diana", "Diana", 5, List.of("Diana"), List.of()),
    FISHING("fishing", "Fishing", 5, List.of("Fishing"), List.of()),
    /** Any party that fits none of the others (killer560, 2026-10-18); its activity is typed, not picked. */
    MISC("misc", "Misc", 5, List.of("Misc"), List.of());

    /** Entrance was a floor choice until 2026-10-18; no new listing may pick it now. Existing listings that say it still
     *  parse and show (the listing check takes any activity text), they just are not offered in the floor dropdown. */
    public static final String RETIRED_ENTRANCE = "Entrance";

    /** The PB requirement's key: the floor's S+ personal best, in whole seconds, at most this fast (lower is harder). */
    public static final String PB = "pb";
    /** What a typed activity may hold (the relay's own rule). */
    public static final java.util.regex.Pattern ACTIVITY = java.util.regex.Pattern.compile("^[A-Za-z0-9 +\\-]{1,24}$");

    /** True when a listing's activity is one this category allows: one of its list, or for Misc any typed one. */
    public boolean allowsActivity(String activity) {
        return activity != null && (activities.contains(activity) || (this == MISC && ACTIVITY.matcher(activity).matches()));
    }

    /** One requirement a listing can set: the relay key, the label, and the largest value the box accepts. */
    public record Field(String key, String label, int max) {
    }

    public final String id;
    public final String label;
    public final int maxParty;
    public final List<String> activities;
    public final List<Field> fields;

    LfgCategory(String id, String label, int maxParty, List<String> activities, List<Field> fields) {
        this.id = id;
        this.label = label;
        this.maxParty = maxParty;
        this.activities = activities;
        this.fields = fields;
    }

    public static LfgCategory fromId(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (LfgCategory c : values()) {
            if (c.id.equals(lower)) {
                return c;
            }
        }
        return null;
    }

    /** The label for a requirement key, or the key itself for one this category does not define. */
    public String labelFor(String key) {
        for (Field f : fields) {
            if (f.key.equals(key)) {
                return f.label;
            }
        }
        return key;
    }
}
