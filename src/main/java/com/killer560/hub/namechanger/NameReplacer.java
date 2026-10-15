package com.killer560.hub.namechanger;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The actual text rewriting behind {@link NameChangerFeature}, called only from the render-side Font hooks
 * ({@code NameChangerFontMixin}). Never touches components, chat history or anything sent to the server.
 * <p>
 * Performance: the no-match path (nearly every string, every frame) does a single allocation-free pass - a reusable
 * per-thread scanner walks the glyphs, splits them into name-shaped words and probes {@link NameTable} with a rolling
 * hash. Only text that actually contains a name builds a replacement, and that result is cached: per sequence
 * identity (two bounded generations, so chat lines / cached component visual-order text are rebuilt at most once) and per string
 * (small LRU) for the plain-String path. Caches are dropped whenever the table is rebuilt.
 */
public final class NameReplacer {

    /** Render-thread counter: while > 0 (inside an EditBox) text is left exactly as typed. */
    private static int suppressDepth = 0;

    /**
     * Per sequence identity: what {@link #replaceSeq} answered. Two generations of a strong identity map instead of a
     * weak-keyed one (nametag perf, 2026-10-13): vanilla rebuilds every tab row's and every nametag's Component each
     * frame, so most sequences seen are new, and each one cost a WeakReference, a ReferenceQueue poll and a locked put in
     * the old Guava map - about a quarter of the name hooks' time in a busy hub (testkit 975's JFR). A sequence found in
     * the old generation is promoted, so the stable ones (chat lines) stay; at most {@code 2 * SEQ_GEN} sequences are
     * held, a few frames' worth, and nothing is kept alive longer than that.
     */
    private static final int SEQ_GEN = 1024;
    private static java.util.IdentityHashMap<FormattedCharSequence, FormattedCharSequence> seqNew =
            new java.util.IdentityHashMap<>(2 * SEQ_GEN);
    private static java.util.IdentityHashMap<FormattedCharSequence, FormattedCharSequence> seqOld =
            new java.util.IdentityHashMap<>(2 * SEQ_GEN);
    private static final Object SEQ_LOCK = new Object();

    private static FormattedCharSequence seqCacheGet(FormattedCharSequence key) {
        synchronized (SEQ_LOCK) {
            FormattedCharSequence v = seqNew.get(key);
            if (v == null) {
                v = seqOld.get(key);
                if (v != null) {
                    seqCachePutLocked(key, v);
                }
            }
            return v;
        }
    }

    private static void seqCachePut(FormattedCharSequence key, FormattedCharSequence value) {
        synchronized (SEQ_LOCK) {
            seqCachePutLocked(key, value);
        }
    }

    private static void seqCachePutLocked(FormattedCharSequence key, FormattedCharSequence value) {
        if (seqNew.size() >= SEQ_GEN) {
            java.util.IdentityHashMap<FormattedCharSequence, FormattedCharSequence> t = seqOld;
            t.clear();
            seqOld = seqNew;
            seqNew = t;
        }
        seqNew.put(key, value);
    }

    private static final int STRING_CACHE_SIZE = 512;
    private static final Map<String, String> STRING_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(STRING_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > STRING_CACHE_SIZE;
                }
            });

    /** Sequence-cache value for a sequence that holds no name to replace. */
    private static final FormattedCharSequence NO_MATCH = sink -> true;

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
        screenSuppressed = false;
    }

    /** Set while one of this mod's own menus is drawing - see {@link #beginScreen}. */
    private static boolean screenSuppressed = false;

    /**
     * killer560 (2026-10-04): "do not have the colors affect anything in my mods menu. So if I make my name
     * killer560 and red it shouldn't make anything in my mod saying killer560 be red." Called from
     * {@code NameChangerScreenMixin} around {@code Screen.extractRenderStateWithTooltipAndSubtitles}: while one
     * of the mod's own settings screens is drawing, text is left exactly as written, through the same
     * suppression the edit boxes use. GUI text is prepared later than this call, so it is the {@link Skip}
     * marking at submission ({@code text(...)} and the widget text collector) that actually carries it; the HUD
     * and chat behind the menu are submitted outside this window and keep their replaced names.
     */
    public static void beginScreen(Object screen) {
        if (screen != null && !screenSuppressed && isModMenuScreen(screen)) {
            screenSuppressed = true;
            suppressDepth++;
        }
    }

    public static void endScreen() {
        if (screenSuppressed) {
            screenSuppressed = false;
            popSuppress();
        }
    }

    private static boolean isModMenuScreen(Object screen) {
        String name = screen.getClass().getName();
        return name.startsWith("com.killer560.hub.gui.")
                || name.startsWith("com.killer560.hub.hud.HudEditorScreen")
                || name.startsWith("com.killer560.hub.scoreboard.ScoreboardEditorScreen");
    }

    /**
     * GUI text is prepared lazily after the widget pass ends, so an EditBox can't just suppress for the duration of
     * its own render call - the text it submits is wrapped in a {@link Skip} marker instead, which the Font hook
     * unwraps without replacing.
     */
    public static FormattedCharSequence markIfSuppressed(FormattedCharSequence seq) {
        if (suppressDepth <= 0 || seq == null || seq instanceof Skip || !NameChangerConfig.getInstance().isActive()) {
            return seq;
        }
        return new Skip(seq);
    }

    static void clearCaches() {
        synchronized (SEQ_LOCK) {
            seqNew.clear();
            seqOld.clear();
        }
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
        FormattedCharSequence cached = seqCacheGet(seq);
        if (cached != null) {
            return cached == NO_MATCH ? seq : cached;
        }
        Scanner sc = SCANNER.get();
        sc.begin(table);
        seq.accept(sc);
        sc.flush();
        if (sc.matchCount == 0) {
            // Remembered too: text with no name in it is nearly all text, and it was rescanned glyph by glyph on every
            // draw and width call - every chat line, tab entry and sidebar line, every frame, while Name Changer is on
            // (FPS sweep, 2026-10-07). Stored as NO_MATCH, so a hit needs no second identity test. A table rebuild
            // clears this cache like any other.
            seqCachePut(seq, NO_MATCH);
            return seq;
        }
        Replaced out = sc.buildSequence(seq);
        seqCachePut(seq, out);
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
            STRING_CACHE.put(s, s); // see replaceSeq: a miss is remembered as itself
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
        // The unstyled visit: the same content strings in the same order, without merging a Style per part (the scan
        // never reads styles) - this ran for every tab row and nametag width each frame.
        text.visit(sc.contents);
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

    private static final class Scanner implements FormattedCharSink {
        private NameTable table;
        private final char[] buf = new char[NameTable.MAX_LEN];
        private int len;
        private int hash;
        private boolean overflow;
        private int wordStart;
        private int index;

        /** True once the first visible glyph was a slash: the whole line is a command, which is never renamed (killer560,
         *  2026-10-15: a name typed inside "/killer560" took the custom colour, partly black). */
        private boolean commandLine;
        private boolean seenGlyph;
        /** Rank-tag tracking: 0 none, 1 inside "[...", 2 after "]", 3 after "] " (the next word may be the name). */
        private int rankPhase;
        private int rankOpen;
        private int rankChars;
        /** Where the rank tag directly before the word being read opened, or -1. */
        private int wordRankOpen;
        private int pendingRank = -1;

        int matchCount;
        private int[] starts = new int[8];
        private int[] lens = new int[8];
        private boolean[] ranked = new boolean[8];
        private NameTable.Entry[] entries = new NameTable.Entry[8];

        void begin(NameTable table) {
            this.table = table;
            len = 0;
            hash = 0;
            overflow = false;
            index = 0;
            matchCount = 0;
            commandLine = false;
            seenGlyph = false;
            rankPhase = 0;
            wordRankOpen = -1;
            pendingRank = -1;
        }

        /** Advances the "[RANK] name" tracker by one glyph; see {@link #rankPhase}. Runs before the word logic. */
        private void trackRank(int c, int at) {
            if (!seenGlyph && c > ' ') {
                seenGlyph = true;
                commandLine = c == '/';
            }
            switch (rankPhase) {
                case 1 -> {
                    if (NameRank.isRankChar(c)) {
                        rankChars++;
                    } else if (c == ']' && rankChars >= 2 && rankChars <= 12) {
                        rankPhase = 2;
                    } else {
                        rankPhase = 0;
                    }
                }
                case 2 -> rankPhase = c == ' ' ? 3 : 0;
                case 3 -> {
                    if (NameTable.isNameChar(c) && len == 0) {
                        pendingRank = rankOpen;
                    }
                    rankPhase = 0;
                }
                default -> {
                }
            }
            if (c == '[') {
                rankPhase = 1;
                rankOpen = at;
                rankChars = 0;
            }
        }

        private void feed(int c, int at) {
            trackRank(c, at);
            if (NameTable.isNameChar(c)) {
                if (len == 0) {
                    wordStart = at;
                    wordRankOpen = pendingRank;
                    pendingRank = -1;
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
            if (len > 0 && !overflow && !commandLine) {
                NameTable.Entry e = table.lookup(buf, len, hash);
                if (e != null) {
                    if (matchCount == starts.length) {
                        int n = matchCount * 2;
                        starts = java.util.Arrays.copyOf(starts, n);
                        lens = java.util.Arrays.copyOf(lens, n);
                        ranked = java.util.Arrays.copyOf(ranked, n);
                        entries = java.util.Arrays.copyOf(entries, n);
                    }
                    // A rank tag directly before your own name is replaced with it ("[MVP++] name" -> custom tag + name).
                    boolean withRank = e.rankReplacement != null && wordRankOpen >= 0
                            && (matchCount == 0 || starts[matchCount - 1] + lens[matchCount - 1] <= wordRankOpen);
                    int start = withRank ? wordRankOpen : wordStart;
                    starts[matchCount] = start;
                    lens[matchCount] = wordStart + len - start;
                    ranked[matchCount] = withRank;
                    entries[matchCount] = e;
                    matchCount++;
                }
            }
            len = 0;
            hash = 0;
            overflow = false;
        }

        /** What replaces match {@code m}: the new rank tag and the name, or just the name. */
        private String replacementFor(int m) {
            NameTable.Entry e = entries[m];
            return ranked[m] ? e.rankReplacement + e.replacement : e.replacement;
        }

        /** FormattedCharSequence pass: indices are glyph (codepoint) positions. */
        @Override
        public boolean accept(int position, Style style, int codepoint) {
            feed(codepoint, index++);
            return true;
        }

        /** FormattedText pass (width detection only - positions unused). § codes act as word breaks. */
        final FormattedText.ContentConsumer<Object> contents = text -> {
            feedLegacyString(text, false);
            return Optional.empty();
        };

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
                String repl = replacementFor(m);
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
                    // NameColor's decoder, not StringDecomposer's: a fade's §x hex escapes are misread by vanilla.
                    NameColor.iterateFormatted(sc.replacementFor(m), style, adder);
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
