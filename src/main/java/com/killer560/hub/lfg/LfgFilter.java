package com.killer560.hub.lfg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The browse page's search, filters and sort (killer560, 2026-10-18: "sortable, filterable and searchable"). Local to the
 * menu: it only reorders and hides what the relay already sent, and the relay never sees any of it.
 * <ul>
 * <li>Search matches the leader's name, the activity and the note, ignoring case; every word must match one of them.</li>
 * <li>Activity: inside a category with several (Dungeons), only that floor.</li>
 * <li>"I meet": only parties none of whose checked requirements (Cata Level, Secrets, S+ PB) he is under. A requirement
 * not known yet for him is not held against the party.</li>
 * <li>Hide full: a party at its size limit is hidden.</li>
 * <li>Sort: newest or oldest first, lowest or highest Cata Level requirement, easiest or hardest S+ PB requirement (no
 * PB requirement counts as the easiest).</li>
 * </ul>
 */
public final class LfgFilter {

    public enum Sort {
        NEWEST("newest", "Newest"),
        OLDEST("oldest", "Oldest"),
        LOWEST_REQ("lowest", "Lowest Req"),
        HIGHEST_REQ("highest", "Highest Req"),
        PB_EASIEST("pbeasy", "Easiest PB"),
        PB_HARDEST("pbhard", "Hardest PB");

        public final String id;
        public final String label;

        Sort(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Sort fromId(String id) {
            for (Sort s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return NEWEST;
        }
    }

    private LfgFilter() {
    }

    /** True when every word of {@code query} appears in the leader's name, the activity or the note. */
    public static boolean matches(LfgListing l, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String hay = (l.leader() + " " + l.activity() + " " + l.note()).toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(word)) {
                return false;
            }
        }
        return true;
    }

    public static List<LfgListing> apply(List<LfgListing> all, String query, String activity, boolean meetsOnly,
                                         boolean hideFull, Sort sort, String self) {
        List<LfgListing> out = new ArrayList<>();
        for (LfgListing l : all) {
            if (!matches(l, query)) {
                continue;
            }
            if (activity != null && !activity.isEmpty() && !activity.equals(l.activity())) {
                continue;
            }
            if (hideFull && l.size() >= l.max()) {
                continue;
            }
            if (meetsOnly && LfgRequirements.anyUnmet(LfgRequirements.check(l.category(), l.activity(), l.reqs(), self))) {
                continue;
            }
            out.add(l);
        }
        out.sort(comparator(sort == null ? Sort.NEWEST : sort));
        return out;
    }

    static Comparator<LfgListing> comparator(Sort sort) {
        Comparator<LfgListing> newest = Comparator.comparingLong(LfgListing::ageMs);
        return switch (sort) {
            case NEWEST -> newest;
            case OLDEST -> newest.reversed();
            case LOWEST_REQ -> Comparator.<LfgListing>comparingInt(l -> req(l, "cata")).thenComparing(newest);
            case HIGHEST_REQ -> Comparator.<LfgListing>comparingInt(l -> req(l, "cata")).reversed().thenComparing(newest);
            case PB_EASIEST -> Comparator.<LfgListing>comparingInt(LfgFilter::pbHardness).thenComparing(newest);
            case PB_HARDEST -> Comparator.<LfgListing>comparingInt(LfgFilter::pbHardness).reversed().thenComparing(newest);
        };
    }

    static int req(LfgListing l, String key) {
        Integer v = l.reqs().get(key);
        return v == null ? 0 : Math.max(0, v);
    }

    /** 0 for no PB requirement, else larger the faster the PB asked for. */
    static int pbHardness(LfgListing l) {
        int pb = req(l, LfgCategory.PB);
        return pb <= 0 ? 0 : 100_000 - pb;
    }
}
