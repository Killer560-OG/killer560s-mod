package com.killer560.hub.cringe;

import com.killer560.hub.translate.TranslateFeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Picks a random line from {@link CringeLines} and sends it - routed through the same Auto
 *  Correct/Chat Emotes/Translate pipeline real typed chat uses, so it still comes out in whatever
 *  language killer560 currently has Translate set to. Optionally routed to a specific Hypixel chat
 *  channel, e.g. "/cringe party" or "/cringe r", instead of whatever the default channel is. */
public final class CringeFeature {

    /** Alias (as typed after "/cringe") -&gt; the real channel command word to send through. */
    private static final Map<String, String> CHANNEL_ALIASES = buildAliases();

    public static void sendRandom() {
        sendRandom(null);
    }

    public static void sendRandom(String channelCommandWord) {
        TranslateFeature.sendGenerated(randomLine(), channelCommandWord);
    }

    public static String randomLine() {
        List<String> lines = CringeLines.all();
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    /** @return the real channel command word for {@code alias} (e.g. "party" -&gt; "pc"), or null
     *  if it isn't a recognized channel. */
    public static String resolveChannel(String alias) {
        return CHANNEL_ALIASES.get(alias.toLowerCase(Locale.US));
    }

    public static Iterable<String> channelAliases() {
        return CHANNEL_ALIASES.keySet();
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pc", "pc");
        m.put("party", "pc");
        m.put("p", "pc");
        m.put("gc", "gc");
        m.put("guild", "gc");
        m.put("g", "gc");
        m.put("oc", "oc");
        m.put("officer", "oc");
        m.put("cc", "cc");
        m.put("coop", "cc");
        m.put("ac", "ac");
        m.put("all", "ac");
        m.put("a", "ac");
        m.put("r", "r");
        m.put("reply", "r");
        return m;
    }

    private CringeFeature() {
    }
}
