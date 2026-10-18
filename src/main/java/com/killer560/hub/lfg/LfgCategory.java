package com.killer560.hub.lfg;

import java.util.List;
import java.util.Locale;

/**
 * The Custom Party Finder's three tabs (killer560's to-do item 1, 2026-10-18; Kuudra is left out until he asks for it), in
 * his order. Each names the relay's category id, the largest party it allows (the relay checks the same numbers), the
 * activities a listing picks from, and the numeric requirements a listing can set.
 * <p>
 * Only requirements this mod can read for another player are CHECKED against a joiner ({@link LfgRequirements}): Catacombs
 * level and total secrets, from the Party Finder stats cache. The rest are shown to everyone but never checked, and say so.
 */
public enum LfgCategory {
    DUNGEONS("dungeons", "Dungeons", 5,
            List.of("Entrance", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"),
            List.of(new Field("cata", "Cata Level", 50), new Field("secrets", "Secrets", 200000))),
    DIANA("diana", "Diana", 5, List.of("Diana"), List.of()),
    FISHING("fishing", "Fishing", 5, List.of("Fishing"), List.of());

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
