package com.killer560.hub.realtime;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Sorted {@link ZoneId#getAvailableZoneIds()} list, grouped by region prefix ("America/", "Europe/", ...),
 *  plus the search/filter used by the Real Time tab's zone picker. IDs with no '/' (UTC, GMT, EST5EDT, ...)
 *  are grouped under {@link #OTHER}. */
public final class RealTimeZones {

    public static final String ALL = "All";
    public static final String OTHER = "Other";

    private static List<String> zones;
    private static List<String> regions;

    private RealTimeZones() {
    }

    public static synchronized List<String> zones() {
        if (zones == null) {
            zones = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(ZoneId.getAvailableZoneIds())));
        }
        return zones;
    }

    public static synchronized List<String> regions() {
        if (regions == null) {
            TreeSet<String> set = new TreeSet<>();
            boolean hasOther = false;
            for (String id : zones()) {
                String region = regionOf(id);
                if (OTHER.equals(region)) {
                    hasOther = true;
                } else {
                    set.add(region);
                }
            }
            List<String> list = new ArrayList<>();
            list.add(ALL);
            list.addAll(set);
            if (hasOther) {
                list.add(OTHER);
            }
            regions = Collections.unmodifiableList(list);
        }
        return regions;
    }

    public static String regionOf(String id) {
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : OTHER;
    }

    /** Zones in {@code region} whose ID contains {@code query} (case-insensitive; spaces match '_', so
     *  "new york" finds America/New_York). Zones whose ID or city part starts with the query come first. */
    public static List<String> filter(String region, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String id : zones()) {
            if (region != null && !ALL.equals(region) && !region.equals(regionOf(id))) {
                continue;
            }
            if (q.isEmpty()) {
                prefix.add(id);
                continue;
            }
            String lower = id.toLowerCase(Locale.ROOT);
            String city = lower.substring(lower.lastIndexOf('/') + 1);
            if (lower.startsWith(q) || city.startsWith(q)) {
                prefix.add(id);
            } else if (lower.contains(q)) {
                contains.add(id);
            }
        }
        prefix.addAll(contains);
        return prefix;
    }

    /** @return the zone for {@code id}, or the system default if blank/unknown. */
    public static ZoneId resolve(String id) {
        if (id != null && !id.isBlank()) {
            try {
                return ZoneId.of(id);
            } catch (DateTimeException ignored) {
            }
        }
        return ZoneId.systemDefault();
    }
}
