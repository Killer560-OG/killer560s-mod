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
 * its words are spread evenly over the line's time - an ESTIMATE, wrong for a held word, a fast run of short words
 * or a pause inside the line. The line's time runs to the next line's timestamp, capped at {@link #MAX_SECONDS_PER_WORD}
 * a word so the instrumental gap after a line is not spread over its words too.
 *
 * <p>No Minecraft types, so it can be checked on its own.
 */
public final class LyricTiming {

    /** An enhanced-LRC word tag, {@code <01:02.34>}. Bounded digits: the text comes from a public lyrics site. */
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{1,2}(?:\\.\\d{1,3})?)>");

    /** The longest an estimated word is given, in seconds. */
    static final double MAX_SECONDS_PER_WORD = 0.75;

    private LyricTiming() {
    }

    /**
     * One parsed line: the text without word tags, its words, and each word's start in seconds when the line had
     * word tags ({@code wordStarts == null} when it did not).
     */
    public record Parsed(String text, List<String> words, double[] wordStarts) {
        public boolean timed() {
            return wordStarts != null;
        }
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
        double cap = n * MAX_SECONDS_PER_WORD;
        double span = (Double.isNaN(lineEnd) || lineEnd <= lineStart) ? cap : Math.min(lineEnd - lineStart, cap);
        if (span <= 0) {
            return n - 1;
        }
        int idx = (int) Math.floor((t - lineStart) / span * n);
        return Math.min(n - 1, Math.max(0, idx));
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
