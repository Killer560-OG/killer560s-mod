package com.killer560.hub.supporters;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat half of killer560's item 8.5. Hypixel formats almost all of its chat (public, party, guild, /msg) as
 * plain, unstructured system-chat text rather than vanilla's signed player-chat packets - the same reason
 * {@code ChatObserver}, {@code InteropChatParser} and Click Translate all work on raw chat lines instead of a
 * structured sender field. So instead of inventing a new chat hook, this reuses the exact one those features
 * already use: {@link ChatObserver#addRewriter}, the shared "rewrite a line right before it's added to chat"
 * funnel wired through {@code ChatComponentMixin} - confirmed (see that mixin's own doc) to catch every real
 * add path Hypixel actually uses.
 * <p>
 * Per {@code SUPPORTERS-CONTRACT.md} ("for chat, replace only the sender name token, and only when you can
 * tie the message to that player's UUID or exact current IGN"): {@link #SENDER} is anchored at the very
 * start of the line (mirrors {@code InteropChatParser.PARTY_LINE}'s precedent for Hypixel's own
 * {@code Channel > [rank] Name: message} shape, generalised to cover public/{@code /msg} chat too), so it can
 * only ever capture the SENDER position - never a supporter's name merely mentioned later in someone else's
 * message. The captured token must then exactly equal a supporter's CURRENT resolved ign
 * ({@link SupportersFeature#findChatSender}, never their stored vanity name) before anything is replaced.
 * Only the captured span is spliced; the rest of the line's styling (rank colour, click/hover events) is
 * left completely alone.
 */
final class SupportersChatRewriter {

    // Optional channel prefix, any number of "[rank] " tags, the sender token, then ": ". Deliberately a
    // superset of Hypixel's known formats rather than one exact regex per channel - a false NEGATIVE here
    // just means a supporter's chat shows their real IGN for that one line, which is harmless; the exact-ign
    // check below is what keeps a false POSITIVE from ever mattering.
    private static final Pattern SENDER = Pattern.compile(
            "^(?:(?:Guild|Party|Co-op|Officer) > |From |To )?(?:\\[[^\\]]{1,24}] )*(\\w{1,16}): ");

    private SupportersChatRewriter() {
    }

    static void register() {
        ChatObserver.addRewriter(SupportersChatRewriter::rewrite);
    }

    private static Component rewrite(Component message, String plain) {
        if (plain.isEmpty() || !SupportersFeature.cosmeticsActive()) {
            return null;
        }
        Matcher m = SENDER.matcher(plain);
        if (!m.find()) {
            return null;
        }
        SupportersFeature.ChatMatch match = SupportersFeature.findChatSender(m.group(1));
        if (match == null) {
            return null;
        }
        try {
            return splice(message, m.start(1), m.end(1), match.displayText());
        } catch (RuntimeException e) {
            return null; // never let a cosmetic rewrite break the player's actual chat
        }
    }

    /** Replaces visual-order codepoints {@code [start, end)} of {@code original} with {@code replacement}
     *  (a plain string that may carry {@code §} codes), keeping every other codepoint's exact style - so
     *  click/hover events elsewhere on the line (item links, coordinates, the rest of the message) survive
     *  untouched. The replacement is laid down on top of the base style of the first replaced codepoint,
     *  same convention {@code NameChangerFeature}'s own replacer uses, so a name that Hypixel gave a
     *  click-to-view-profile event keeps that event even though the visible text changes. */
    private static Component splice(Component original, int start, int end, String replacement) {
        Collector all = new Collector();
        FormattedCharSequence seq = original.getVisualOrderText();
        seq.accept(all);
        int n = all.count;
        if (start < 0 || end > n || start > end) {
            return null; // indices didn't line up with the visual-order text - leave the line alone
        }
        Style base = start < n ? all.styles[start] : Style.EMPTY;
        Collector out = new Collector();
        for (int i = 0; i < start; i++) {
            out.add(all.codepoints[i], all.styles[i]);
        }
        StringDecomposer.iterateFormatted(replacement, base, out);
        for (int i = end; i < n; i++) {
            out.add(all.codepoints[i], all.styles[i]);
        }
        return out.toComponent();
    }

    /** Flattens a {@link FormattedCharSequence} (or receives one from {@link StringDecomposer}) into parallel
     *  codepoint/style arrays, then rebuilds a {@link Component} from consecutive equal-style runs. */
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
