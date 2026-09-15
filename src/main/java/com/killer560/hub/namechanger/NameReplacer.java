package com.killer560.hub.namechanger;

import com.google.common.collect.MapMaker;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * The actual text rewriting behind {@link NameChangerFeature}, called only from the render-side Font hooks
 * ({@code NameChangerFontMixin}). Never touches components, chat history or anything sent to the server.
 * <p>
 * Performance: the no-match path (nearly every string, every frame) does a single allocation-free pass - a reusable
 * per-thread scanner walks the glyphs, splits them into name-shaped words and probes {@link NameTable} with a rolling
 * hash. Only text that actually contains a name builds a replacement, and that result is cached: per sequence
 * identity (weak keys, so chat lines / cached component visual-order text are rebuilt at most once) and per string
 * (small LRU) for the plain-String path. Caches are dropped whenever the table is rebuilt.
 */
public final class NameReplacer {

    /** Render-thread counter: while > 0 (inside an EditBox) text is left exactly as typed. */
    private static int suppressDepth = 0;

    private static final ConcurrentMap<FormattedCharSequence, FormattedCharSequence> SEQ_CACHE =
            new MapMaker().weakKeys().concurrencyLevel(2).makeMap();

    private static final int STRING_CACHE_SIZE = 512;
    private static final Map<String, String> STRING_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(STRING_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > STRING_CACHE_SIZE;
                }
            });

    private static final ThreadLocal<Scanner> SCANNER = ThreadLocal.withInitial(Scanner::new);

    private NameReplacer() {
    }

    // ------------------------------------------------------------------ suppression (edit boxes)

    public static void pushSuppress() {
        suppressDepth++;
    }

    public static void popSuppress() {
        if (suppressDepth > 0) {
            suppressDepth--;
        }
    }

    static void resetSuppress() {
        suppressDepth = 0;
    }

    /**
     * GUI text is prepared lazily after the widget pass ends, so an EditBox can't just suppress for the duration of
     * its own render call - the text it submits is wrapped in a {@link Skip} marker instead, which the Font hook
     * unwraps without replacing.
     */
    public static FormattedCharSequence markIfSuppressed(FormattedCharSequence seq) {
        if (suppressDepth <= 0 || seq == null || seq instanceof Skip || !NameChangerConfig.getInstance().isEnabled()) {
            return seq;
        }
        return new Skip(seq);
    }

    static void clearCaches() {
        SEQ_CACHE.clear();
        STRING_CACHE.clear();
    }

    // ------------------------------------------------------------------ entry points

    /** Replacement for {@code Font.prepareText(FormattedCharSequence, ...)} / {@code Font.width(FormattedCharSequence)}. */
    public static FormattedCharSequence replace(FormattedCharSequence seq) {
        try {
            return replaceSeq(seq);
        } catch (RuntimeException e) {
            return seq instanceof Skip skip ? skip.inner() : seq; // never let a rendering hook crash the game
        }
    }

    private static FormattedCharSequence replaceSeq(FormattedCharSequence seq) {
        if (seq == null || seq instanceof Replaced) {
            return seq;
        }
        if (seq instanceof Skip skip) {
            return skip.inner();
        }
        if (suppressDepth > 0) {
            return seq;
        }
        NameTable table = NameChangerFeature.currentTable();
        if (table == null) {
            return seq;
        }
        FormattedCharSequence cached = SEQ_CACHE.get(seq);
        if (cached != null) {
            return cached;
        }
        Scanner sc = SCANNER.get();
        sc.begin(table);
        seq.accept(sc);
        sc.flush();
        if (sc.matchCount == 0) {
            return seq;
        }
        Replaced out = sc.buildSequence(seq);
        SEQ_CACHE.put(seq, out);
        return out;
    }

    /** Replacement for {@code Font.prepareText(String, ...)} / {@code Font.width(String)} (legacy § codes allowed). */
    public static String replace(String s) {
        try {
            return replaceString(s);
        } catch (RuntimeException e) {
            return s;
        }
    }

    private static String replaceString(String s) {
        if (s == null || s.isEmpty() || suppressDepth > 0) {
            return s;
        }
        NameTable table = NameChangerFeature.currentTable();
        if (table == null) {
            return s;
        }
        String cached = STRING_CACHE.get(s);
        if (cached != null) {
            return cached;
        }
        Scanner sc = SCANNER.get();
        sc.begin(table);
        sc.feedLegacyString(s, true);
        sc.flush();
        if (sc.matchCount == 0) {
            return s;
        }
        String out = sc.buildString(s);
        STRING_CACHE.put(s, out);
        return out;
    }

    /**
     * For {@code Font.width(FormattedText)}: returns the replaced visual-order sequence to measure instead, or
     * {@code null} when the text contains no replaceable name (the common, allocation-free case).
     */
    public static FormattedCharSequence replaceForWidth(FormattedText text) {
        try {
            return replaceForWidthInternal(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static FormattedCharSequence replaceForWidthInternal(FormattedText text) {
        if (text == null || suppressDepth > 0) {
            return null;
        }
        NameTable table = NameChangerFeature.currentTable();
        if (table == null) {
            return null;
        }
        Scanner sc = SCANNER.get();
        sc.begin(table);
        text.visit(sc, Style.EMPTY);
        sc.flush();
        if (sc.matchCount == 0) {
            return null;
        }
        FormattedCharSequence seq = text instanceof Component c ? c.getVisualOrderText()
                : Language.getInstance().getVisualOrder(text);
        FormattedCharSequence out = replaceSeq(seq);
        return out == seq ? null : out;
    }

    // ------------------------------------------------------------------ sequence types

    /** Marker: text that must be drawn exactly as-is (edit box contents). */
    public record Skip(FormattedCharSequence inner) implements FormattedCharSequence {
        @Override
        public boolean accept(FormattedCharSink sink) {
            return inner.accept(sink);
        }
    }

    /** A flattened, already-replaced sequence (never re-processed, so A=B + B=C mappings don't chain). */
    static final class Replaced implements FormattedCharSequence {
        private final int[] codepoints;
        private final Style[] styles;

        Replaced(int[] codepoints, Style[] styles) {
            this.codepoints = codepoints;
            this.styles = styles;
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            for (int i = 0; i < codepoints.length; i++) {
                if (!sink.accept(i, styles[i], codepoints[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ scanner

    private static final class Scanner implements FormattedCharSink, FormattedText.StyledContentConsumer<Object> {
        private NameTable table;
        private final char[] buf = new char[NameTable.MAX_LEN];
        private int len;
        private int hash;
        private boolean overflow;
        private int wordStart;
        private int index;

        int matchCount;
        private int[] starts = new int[8];
        private int[] lens = new int[8];
        private NameTable.Entry[] entries = new NameTable.Entry[8];

        void begin(NameTable table) {
            this.table = table;
            len = 0;
            hash = 0;
            overflow = false;
            index = 0;
            matchCount = 0;
        }

        private void feed(int c, int at) {
            if (NameTable.isNameChar(c)) {
                if (len == 0) {
                    wordStart = at;
                }
                if (len < NameTable.MAX_LEN) {
                    buf[len++] = (char) c;
                    hash = NameTable.step(hash, (char) c);
                } else {
                    overflow = true;
                }
            } else {
                flush();
            }
        }

        void flush() {
            if (len > 0 && !overflow) {
                NameTable.Entry e = table.lookup(buf, len, hash);
                if (e != null) {
                    if (matchCount == starts.length) {
                        int n = matchCount * 2;
                        starts = java.util.Arrays.copyOf(starts, n);
                        lens = java.util.Arrays.copyOf(lens, n);
                        entries = java.util.Arrays.copyOf(entries, n);
                    }
                    starts[matchCount] = wordStart;
                    lens[matchCount] = len;
                    entries[matchCount] = e;
                    matchCount++;
                }
            }
            len = 0;
            hash = 0;
            overflow = false;
        }

        /** FormattedCharSequence pass: indices are glyph (codepoint) positions. */
        @Override
        public boolean accept(int position, Style style, int codepoint) {
            feed(codepoint, index++);
            return true;
        }

        /** FormattedText pass (width detection only - positions unused). § codes act as word breaks. */
        @Override
        public Optional<Object> accept(Style style, String contents) {
            feedLegacyString(contents, false);
            return Optional.empty();
        }

        void feedLegacyString(String s, boolean charIndices) {
            int n = s.length();
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c == '§') {
                    flush();
                    i++;
                    continue;
                }
                feed(c, charIndices ? i : index++);
            }
        }

        Replaced buildSequence(FormattedCharSequence original) {
            Builder b = new Builder(this);
            original.accept(b);
            return new Replaced(java.util.Arrays.copyOf(b.cps, b.n), java.util.Arrays.copyOf(b.styles, b.n));
        }

        String buildString(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 16);
            String active = "";
            int last = 0;
            for (int m = 0; m < matchCount; m++) {
                int start = starts[m];
                active = updateActiveFormatting(active, s, last, start);
                sb.append(s, last, start);
                String repl = entries[m].replacement;
                sb.append(repl);
                if (repl.indexOf('§') >= 0) {
                    // Restore whatever colour/format was active before the name so the rest of the line is unchanged.
                    sb.append("§r").append(active);
                }
                last = start + lens[m];
            }
            sb.append(s, last, s.length());
            return sb.toString();
        }

        private static String updateActiveFormatting(String active, String s, int from, int to) {
            for (int j = from; j < to - 1; j++) {
                if (s.charAt(j) != '§') {
                    continue;
                }
                char f = Character.toLowerCase(s.charAt(j + 1));
                if ((f >= '0' && f <= '9') || (f >= 'a' && f <= 'f')) {
                    active = "§" + f;
                } else if (f >= 'k' && f <= 'o') {
                    active = active + "§" + f;
                } else if (f == 'r') {
                    active = "";
                }
                j++;
            }
            return active;
        }
    }

    /** Second pass over a matched sequence, splicing replacements in with the name's own style as the base. */
    private static final class Builder implements FormattedCharSink {
        private final Scanner sc;
        int[] cps = new int[64];
        Style[] styles = new Style[64];
        int n;
        private int index;
        private int m;
        private final FormattedCharSink adder = (position, style, codepoint) -> {
            add(codepoint, style);
            return true;
        };

        Builder(Scanner sc) {
            this.sc = sc;
        }

        private void add(int cp, Style style) {
            if (n == cps.length) {
                cps = java.util.Arrays.copyOf(cps, n * 2);
                styles = java.util.Arrays.copyOf(styles, n * 2);
            }
            cps[n] = cp;
            styles[n] = style;
            n++;
        }

        @Override
        public boolean accept(int position, Style style, int codepoint) {
            int i = index++;
            if (m < sc.matchCount && i >= sc.starts[m]) {
                int start = sc.starts[m];
                if (i == start) {
                    // § codes in the display name layer on top of the original style (click/hover events survive).
                    StringDecomposer.iterateFormatted(sc.entries[m].replacement, style, adder);
                }
                if (i == start + sc.lens[m] - 1) {
                    m++;
                }
                return true;
            }
            add(codepoint, style);
            return true;
        }
    }
}
