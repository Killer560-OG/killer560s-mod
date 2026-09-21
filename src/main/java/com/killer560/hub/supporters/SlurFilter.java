package com.killer560.hub.supporters;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client-side half of killer560's slur filter for supporter display names (item 8.5). The relay already
 * rejects a bad name when staff try to set it (POST /discord/interactions), but per
 * {@code SUPPORTERS-CONTRACT.md} the mod checks again on display too, "so a bad entry can never render even
 * if one slips in" - a stale relay word list, a hand-edited KV entry, or this filter simply disagreeing with
 * that one. A hit falls back to the player's real IGN (see {@link SupportersFeature#displayNameFor}); it
 * never hides the player or drops their scale.
 * <p>
 * Normalisation: lower-case, common leetspeak digits/symbols folded back to letters, everything that isn't
 * {@code a-z} stripped (color codes, spaces, punctuation, digits that weren't leetspeak), then runs of the
 * same letter collapsed to one - so "n1gg3r", "n-i-g-g-e-r" and "niiiggeerrr" all normalise identically.
 * Matching is "contains" for anything that normalises to 4+ letters (a slur can be buried inside an
 * otherwise ordinary-looking name), but WHOLE-TOKEN ONLY for anything shorter, so a short entry can't
 * condemn every name that merely contains those few letters in a row for an unrelated reason.
 */
final class SlurFilter {

    // Deliberately small: this exists to stop the obvious cases (killer560's spec), not to be a general
    // profanity filter - and every entry here is normalised once at class-init via {@link #normalise}, so it
    // is compared on equal footing with the name being checked.
    private static final Set<String> WORDS = normaliseAll(Set.of(
            "nigger", "nigga", "faggot", "fag", "retard", "retarded", "tranny", "chink", "spic", "kike",
            "wetback", "coon", "gook", "dyke", "beaner", "cracker", "paki", "cunt"
    ));

    private SlurFilter() {
    }

    /** @return true if {@code rawName} (as stored - {@code &} codes and all) trips the filter and must not
     *  be shown as-is. */
    static boolean isBlocked(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return false;
        }
        String norm = normalise(rawName);
        if (norm.isEmpty()) {
            return false;
        }
        for (String word : WORDS) {
            boolean hit = word.length() <= 3 ? norm.equals(word) : norm.contains(word);
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normaliseAll(Set<String> words) {
        return words.stream().map(SlurFilter::normalise).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalise(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder letters = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char folded = foldLeet(lower.charAt(i));
            if (folded >= 'a' && folded <= 'z') {
                letters.append(folded);
            }
        }
        StringBuilder collapsed = new StringBuilder(letters.length());
        char last = 0;
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            if (c != last) {
                collapsed.append(c);
                last = c;
            }
        }
        return collapsed.toString();
    }

    private static char foldLeet(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1', '!', '|' -> 'i';
            case '3' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7', '+' -> 't';
            case '8' -> 'b';
            case '9' -> 'g';
            default -> c;
        };
    }
}
