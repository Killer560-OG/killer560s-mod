package com.killer560.hub.emotes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hypixel's own chat-emote shorthand, pulled directly from the real in-game {@code /emotes}
 * command output at killer560's request (2026-09-02) rather than guessed - the full list, both the
 * "Available to MVP++" and "Available through Rank Gifting" sections. Per killer560, this mod sends
 * the real emote text itself rather than sending the trigger and relying on Hypixel's own
 * server-side conversion to fire - so the trigger is replaced client-side, exactly like Auto
 * Correct replaces a typo, before Translate ever sees the message.
 */
public final class ChatEmoteFeature {

    private static final Map<String, String> EMOTE_MAP = buildMap();

    public record Split(String prefix, String body, String suffix) {
        public boolean hasBody() {
            return !body.isEmpty();
        }
    }

    /**
     * Messages are normalized to single-space-separated tokens before this runs (vanilla's own
     * {@code normalizeChatMessage}). Peels off any leading/trailing run of exact trigger tokens
     * (case-sensitive, matching Hypixel's own real trigger text) and replaces each with its real
     * emote text - the remaining "body" (if any) is everything in between, left untouched, for
     * Auto Correct/Translate to still process normally - e.g. "o/ tysm" becomes prefix
     * "( ﾟ◡ﾟ)/" + body "tysm" (still translatable).
     */
    public static Split split(String text) {
        if (text.isEmpty()) {
            return new Split("", "", "");
        }
        String[] tokens = text.split(" ");

        int start = 0;
        while (start < tokens.length && EMOTE_MAP.containsKey(tokens[start])) {
            start++;
        }
        int end = tokens.length;
        while (end > start && EMOTE_MAP.containsKey(tokens[end - 1])) {
            end--;
        }

        String prefix = joinReplaced(tokens, 0, start);
        String body = String.join(" ", Arrays.copyOfRange(tokens, start, end));
        String suffix = joinReplaced(tokens, end, tokens.length);
        return new Split(prefix, body, suffix);
    }

    /** Joins {@code prefix}/{@code body}/{@code suffix} back into one message, skipping empties. */
    public static String join(String prefix, String body, String suffix) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{prefix, body, suffix}) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static String joinReplaced(String[] tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(EMOTE_MAP.get(tokens[i]));
        }
        return sb.toString();
    }

    private static Map<String, String> buildMap() {
        Map<String, String> m = new LinkedHashMap<>();
        // Available to MVP++
        m.put("<3", "❤");
        m.put(":star:", "✮");
        m.put(":yes:", "✔");
        m.put(":no:", "✖");
        m.put(":java:", "☕");
        m.put(":arrow:", "➜");
        m.put(":shrug:", "¯\\_(ツ)_/¯");
        m.put(":tableflip:", "(╯°□°）╯︵ ┻━┻");
        m.put("o/", "( ﾟ◡ﾟ)/");
        m.put(":123:", "123");
        m.put(":totem:", "☉_☉");
        m.put(":typing:", "✎...");
        m.put(":maths:", "√(π+x)=L");
        m.put(":snail:", "@'-'");
        m.put(":thinking:", "(0.o?)");
        m.put(":gimme:", "༼つ◕_◕༽つ");
        m.put(":wizard:", "('-')⊃━☆ﾟ.*･｡ﾟ");
        m.put(":pvp:", "⚔");
        m.put(":peace:", "✌");
        m.put(":oof:", "OOF");
        m.put(":puffer:", "<('O')>");
        // Available through Rank Gifting
        m.put("h/", "ヽ(^◇^*)/");
        m.put(":snow:", "☃");
        m.put(":dab:", "<o/");
        m.put(":cat:", "= ＾● ⋏ ●＾ =");
        m.put(":dj:", "ヽ(⌐■_■)ノ♬");
        m.put(":dog:", "(ᵔᴥᵔ)");
        m.put(":yey:", "ヽ (◕◡◕) ﾉ");
        m.put(":sloth:", "(・⊝・)");
        m.put(":cute:", "(✿◠‿◠)");
        m.put("^_^", "^_^");
        m.put("^-^", "^-^");
        return Map.copyOf(m);
    }

    private ChatEmoteFeature() {
    }
}
