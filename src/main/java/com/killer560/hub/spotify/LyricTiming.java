package com.killer560.hub.spotify;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which word of a lyric line is being sung, for the Spotify HUD's highlight.
 *
 * <p>lrclib's {@code syncedLyrics} is LRC: one timestamp per LINE. A few entries are "enhanced" LRC, with a
 * {@code <mm:ss.xx>} tag before each word; those words get their real start times. Every other line has none, and
 * its time is shared out by {@link #weight}: each word's syllables (vowel groups, a silent final e and the common
 * contractions handled) plus a little per letter, so "beautiful" gets about three times what "a" does. That is still
 * an ESTIMATE - a held note, a fast run or a pause inside the line puts it ahead or behind - and only per-word data
 * from the source (which lrclib rarely has) is exact. (killer560, 2026-10-20: an even split was right on the first word
 * and then drifted.) The line's time runs to the next line's timestamp, capped at {@link #MAX_SECONDS_PER_WORD} a word
 * so the instrumental gap after a line is not spread over its words too; no single word is given more than the cap.
 *
 * <p>No Minecraft types, so it can be checked on its own.
 */
public final class LyricTiming {

    /** An enhanced-LRC word tag, {@code <01:02.34>}. Bounded digits: the text comes from a public lyrics site. */
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{1,2}(?:\\.\\d{1,3})?)>");

    /** The longest an estimated word is given, in seconds. */
    static final double MAX_SECONDS_PER_WORD = 0.75;

    /** What each letter adds to a word's weight, on top of its syllables. */
    static final double LETTER_WEIGHT = 0.1;

    private LyricTiming() {
    }

    /**
     * One parsed line: the text without word tags, its words, each word's start in seconds when the line had word tags
     * ({@code wordStarts == null} when it did not), and each word's {@link #weight}.
     */
    public record Parsed(String text, List<String> words, double[] wordStarts, double[] weights) {
        public Parsed(String text, List<String> words, double[] wordStarts) {
            this(text, words, wordStarts, LyricTiming.weights(words));
        }

        public boolean timed() {
            return wordStarts != null;
        }
    }

    private static double[] weights(List<String> words) {
        double[] w = new double[words.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = weight(words.get(i));
        }
        return w;
    }

    /** How long a word takes to sing, relative to the others in its line: syllables plus {@link #LETTER_WEIGHT} a letter. */
    public static double weight(String word) {
        int letters = 0;
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetterOrDigit(word.charAt(i))) {
                letters++;
            }
        }
        return syllables(word) + LETTER_WEIGHT * letters;
    }

    /**
     * Syllables in an English word, by vowel groups ({@code y} counts after the first letter), with a silent final
     * {@code e} / {@code ed} / {@code es} taken off and contractions handled: {@code 's 're 'll 've 'd 'm} add nothing,
     * {@code n't} adds one after a consonant (couldn't, didn't) and none after a vowel (don't, can't). At least 1.
     */
    public static int syllables(String raw) {
        String w = raw.toLowerCase(java.util.Locale.ROOT).replace((char) 0x2019, '\'');
        StringBuilder keep = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ((c >= 'a' && c <= 'z') || c == '\'') {
                keep.append(c);
            }
        }
        w = keep.toString();
        int extra = 0;
        if (w.endsWith("n't")) {
            String base = w.substring(0, w.length() - 3);
            if (!base.isEmpty() && !vowel(base, base.length() - 1)) {
                extra = 1;
            }
            w = base;
        } else {
            int ap = w.indexOf('\'');
            if (ap > 0) {
                String suffix = w.substring(ap + 1);
                if (suffix.equals("s") || suffix.equals("re") || suffix.equals("ll") || suffix.equals("ve")
                        || suffix.equals("d") || suffix.equals("m")) {
                    w = w.substring(0, ap);
                }
            }
        }
        w = w.replace("'", "");
        if (w.isEmpty()) {
            return Math.max(1, extra);
        }
        int count = 0;
        boolean prevVowel = false;
        for (int i = 0; i < w.length(); i++) {
            boolean v = vowel(w, i);
            if (v && !prevVowel) {
                count++;
            }
            prevVowel = v;
        }
        int n = w.length();
        if (count > 1 && n > 2) {
            if (w.endsWith("e") && !w.endsWith("ee") && !(w.endsWith("le") && !vowel(w, n - 3))
                    && !vowel(w, n - 2)) {
                count--;                                    // make, love, fire
            } else if (w.endsWith("ed") && n > 3 && !vowel(w, n - 3) && "td".indexOf(w.charAt(n - 3)) < 0) {
                count--;                                    // loved, turned (but wanted, needed keep it)
            } else if (w.endsWith("es") && n > 3 && !vowel(w, n - 3) && "sxzcgh".indexOf(w.charAt(n - 3)) < 0) {
                count--;                                    // loves, times (but boxes, places, wishes keep it)
            }
        }
        return Math.max(1, count) + extra;
    }

    private static boolean vowel(String w, int i) {
        if (i < 0 || i >= w.length()) {
            return false;
        }
        char c = w.charAt(i);
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || (c == 'y' && i > 0);
    }

    /**
     * How long each word of an untimed line is given, in seconds: the line's span (to {@code lineEnd}, capped at
     * {@link #MAX_SECONDS_PER_WORD} a word) shared in proportion to {@link #weight}, with no word over the cap - any
     * excess goes to the uncapped words in the same proportion. The shares always sum to the span.
     */
    public static double[] shares(Parsed line, double lineStart, double lineEnd) {
        int n = line.words().size();
        double[] out = new double[n];
        if (n == 0) {
            return out;
        }
        double cap = n * MAX_SECONDS_PER_WORD;
        double span = (Double.isNaN(lineEnd) || lineEnd <= lineStart) ? cap : Math.min(lineEnd - lineStart, cap);
        double[] w = line.weights();
        boolean[] capped = new boolean[n];
        double remaining = span;
        boolean changed = true;
        while (changed) {
            changed = false;
            double free = 0;
            for (int i = 0; i < n; i++) {
                if (!capped[i]) {
                    free += w[i];
                }
            }
            if (free <= 0) {
                break;
            }
            for (int i = 0; i < n; i++) {
                if (!capped[i]) {
                    out[i] = remaining * w[i] / free;
                }
            }
            for (int i = 0; i < n; i++) {
                if (!capped[i] && out[i] > MAX_SECONDS_PER_WORD + 1e-9) {
                    capped[i] = true;
                    out[i] = MAX_SECONDS_PER_WORD;
                    remaining -= MAX_SECONDS_PER_WORD;
                    changed = true;
                }
            }
        }
        return out;
    }

    /** Index of the last entry of {@code n} whose start ({@code startOf}) is at or before {@code t}, or -1. */
    public static int lineAt(java.util.function.IntToDoubleFunction startOf, int n, double t) {
        int idx = -1;
        for (int i = 0; i < n; i++) {
            if (startOf.applyAsDouble(i) <= t) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    /** Parses a line's text (after its {@code [mm:ss.xx]} line tag); {@code lineStart} fills an untagged first word. */
    public static Parsed parse(String raw, double lineStart) {
        String s = raw == null ? "" : raw;
        Matcher m = WORD_TAG.matcher(s);
        if (!m.find()) {
            String text = collapse(s);
            return new Parsed(text, split(text), null);
        }
        StringBuilder plain = new StringBuilder(s.length());
        List<Double> starts = new ArrayList<>();
        double pending = Double.NaN;
        boolean inWord = false;
        int i = 0;
        m.reset();
        while (i < s.length()) {
            if (m.find(i) && m.start() == i) {
                // A tag between words times the next word; one inside a word (syllable timing) is ignored.
                if (!inWord) {
                    pending = Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2));
                }
                i = m.end();
                continue;
            }
            char c = s.charAt(i++);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    plain.append(' ');
                }
                inWord = false;
                continue;
            }
            if (!inWord) {
                double prev = starts.isEmpty() ? lineStart : starts.get(starts.size() - 1);
                starts.add(Double.isNaN(pending) ? prev : pending);
                pending = Double.NaN;
                inWord = true;
            }
            plain.append(c);
        }
        String text = plain.toString().trim();
        List<String> words = split(text);
        if (words.size() != starts.size()) {
            return new Parsed(text, words, null); // cannot happen for well-formed input; fall back to the estimate
        }
        double[] out = new double[starts.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = starts.get(k);
        }
        return new Parsed(text, words, out);
    }

    /**
     * Index of the word being sung at {@code t} seconds, or -1 before the line starts.
     *
     * @param lineStart this line's timestamp
     * @param lineEnd   the next line's timestamp (or the track's end for the last line); NaN or not after
     *                  {@code lineStart} means "unknown", and the per-word cap alone sets the span
     */
    public static int wordAt(Parsed line, double lineStart, double lineEnd, double t) {
        int n = line.words().size();
        if (n == 0 || t < lineStart) {
            return -1;
        }
        if (line.timed()) {
            double[] st = line.wordStarts();
            int idx = -1;
            for (int k = 0; k < st.length; k++) {
                if (st[k] <= t) {
                    idx = k;
                } else {
                    break;
                }
            }
            return idx;
        }
        double[] share = shares(line, lineStart, lineEnd);
        double end = lineStart;
        for (int k = 0; k < n; k++) {
            end += share[k];
            if (t < end) {
                return k;
            }
        }
        return n - 1;
    }

    private static String collapse(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static List<String> split(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split(" "));
    }
}
