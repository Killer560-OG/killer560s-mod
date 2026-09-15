package com.killer560.hub.fastleap;

import com.killer560.hub.fastleap.FastLeapConfig.LeapTarget;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Target Mode "Posmsg": remembers, per leap target, the party member who most recently announced that position in
 * party chat this world. Catches both this mod's own Posmsg lines ("[PM]EE2 (High - Lever Device)|x|y|z|r" - only the
 * entry name is matched) and plain position messages from Odin's Positional Messages / other mods ("At EE2!",
 * "At Core!", "Inside Tunnel!"). Keywords are comma-separated, case-insensitive, and must match as whole words; when a
 * line matches several targets, only the targets with the longest matching keyword take it (so "Inside Core!" goes to
 * S4's "inside core", not S3's "core"). Your own messages are ignored. Cleared on world change.
 */
public final class PosmsgTargets {

    // "Party > [MVP+] Name: msg" (optional ranks, optional trailing tag/emblem before the colon); "P >" for compacted lines.
    private static final Pattern PARTY_LINE =
            Pattern.compile("^(?:Party|P) > (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16})(?: [^:]{0,24})?: (.*)$");
    private static final Pattern PM_LINE = Pattern.compile("\\[PM]([^|]+)\\|");

    private static final Map<LeapTarget, String> SENDERS = new EnumMap<>(LeapTarget.class);

    private PosmsgTargets() {
    }

    static void onChat(String unformatted) {
        Matcher m = PARTY_LINE.matcher(unformatted);
        if (!m.matches()) {
            return;
        }
        String sender = m.group(1);
        if (sender.equalsIgnoreCase(Teammates.selfName())) {
            return;
        }
        String message = m.group(2);
        Matcher pm = PM_LINE.matcher(message);
        String text = (pm.find() ? pm.group(1) : message).toLowerCase(Locale.ROOT);

        FastLeapConfig cfg = FastLeapConfig.getInstance();
        int best = 0;
        List<LeapTarget> winners = new ArrayList<>();
        for (LeapTarget target : LeapTarget.values()) {
            int len = longestMatch(text, cfg.getTargetKeywords(target));
            if (len <= 0 || len < best) {
                continue;
            }
            if (len > best) {
                best = len;
                winners.clear();
            }
            winners.add(target);
        }
        for (LeapTarget target : winners) {
            SENDERS.put(target, sender);
        }
        if (!winners.isEmpty()) {
            FastLeapFeature.LOGGER.info("[FastLeap] Posmsg: {} announced \"{}\" -> {}", sender, message, winners);
        }
    }

    /** @return the sender recorded for {@code target} this world, or null. */
    public static String sender(LeapTarget target) {
        return SENDERS.get(target);
    }

    static void clear() {
        SENDERS.clear();
    }

    /** @return the length of the longest keyword in {@code keywords} found in {@code text} as a whole word, else 0. */
    static int longestMatch(String text, String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return 0;
        }
        int best = 0;
        for (String raw : keywords.split(",")) {
            String kw = raw.trim().toLowerCase(Locale.ROOT);
            if (!kw.isEmpty() && kw.length() > best && containsWord(text, kw)) {
                best = kw.length();
            }
        }
        return best;
    }

    private static boolean containsWord(String text, String kw) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(kw, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + kw.length();
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(kw.charAt(0)) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(kw.charAt(kw.length() - 1))
                    || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = idx + 1;
        }
    }
}
