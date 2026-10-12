package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameColor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

import java.util.Arrays;

/**
 * Replaces name-shaped spans of a {@link Component} with a supporter's display text, keeping every other glyph's exact
 * style - so click/hover events elsewhere on the line survive. Shared by the chat rewriter (the sender token) and the
 * tab list's text rows ({@link SupportersTabText}). Each replacement is laid down on top of the style of the first glyph
 * it replaces, the same convention Name Changer's replacer uses.
 */
final class NameSplice {

    private NameSplice() {
    }

    /**
     * Replaces visual-order codepoints {@code [starts[k], ends[k])} with {@code replacements[k]} (a string that may carry
     * {@code §} codes). The ranges must be ascending and not overlap. Returns {@code null} when they do not fit the text.
     */
    static Component splice(Component original, int[] starts, int[] ends, String[] replacements) {
        Collector all = new Collector();
        FormattedCharSequence seq = original.getVisualOrderText();
        seq.accept(all);
        int n = all.count;
        Collector out = new Collector();
        int at = 0;
        for (int k = 0; k < starts.length; k++) {
            int start = starts[k];
            int end = ends[k];
            if (start < at || end > n || start > end) {
                return null; // indices didn't line up with the visual-order text - leave the line alone
            }
            for (int i = at; i < start; i++) {
                out.add(all.codepoints[i], all.styles[i]);
            }
            Style base = start < n ? all.styles[start] : Style.EMPTY;
            NameColor.iterateFormatted(replacements[k], base, out);
            at = end;
        }
        for (int i = at; i < n; i++) {
            out.add(all.codepoints[i], all.styles[i]);
        }
        return out.toComponent();
    }

    /** The visual-order text of {@code c} as a plain string, one char per BMP codepoint (what splice indexes). */
    static String plain(Component c, int[] lengthOut) {
        Collector all = new Collector();
        c.getVisualOrderText().accept(all);
        StringBuilder sb = new StringBuilder(all.count);
        for (int i = 0; i < all.count; i++) {
            int cp = all.codepoints[i];
            sb.append(Character.isBmpCodePoint(cp) ? (char) cp : '�');
        }
        if (lengthOut != null) {
            lengthOut[0] = all.count;
        }
        return sb.toString();
    }

    /** Flattens a {@link FormattedCharSequence} into parallel codepoint/style arrays, then rebuilds a {@link Component}
     *  from consecutive equal-style runs. */
    private static final class Collector implements FormattedCharSink {
        int[] codepoints = new int[64];
        Style[] styles = new Style[64];
        int count;

        void add(int codepoint, Style style) {
            if (count == codepoints.length) {
                codepoints = Arrays.copyOf(codepoints, count * 2);
                styles = Arrays.copyOf(styles, count * 2);
            }
            codepoints[count] = codepoint;
            styles[count] = style;
            count++;
        }

        @Override
        public boolean accept(int position, Style style, int codepoint) {
            add(codepoint, style);
            return true;
        }

        Component toComponent() {
            MutableComponent result = Component.empty();
            int i = 0;
            while (i < count) {
                Style style = styles[i];
                int j = i + 1;
                while (j < count && styles[j].equals(style)) {
                    j++;
                }
                StringBuilder sb = new StringBuilder(j - i);
                for (int k = i; k < j; k++) {
                    sb.appendCodePoint(codepoints[k]);
                }
                result.append(Component.literal(sb.toString()).withStyle(style));
                i = j;
            }
            return result;
        }
    }
}
