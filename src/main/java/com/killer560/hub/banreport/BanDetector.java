package com.killer560.hub.banreport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes Hypixel's ban disconnect screen. Hypixel tells a banned player only by kicking them (or refusing the join)
 * with a screen shaped like this, one line each:
 * <pre>
 * You are temporarily banned for 29d 23h 59m 59s from this server!
 *
 * Reason: Cheating through the use of unfair game advantages.
 * Find out more: https://www.hypixel.net/appeal
 *
 * Ban ID: #1A2B3C4D
 * Sharing your Ban ID may affect the processing of your appeal!
 * </pre>
 * or "You are permanently banned from this server!" as the first line. Only the disconnect REASON is read, never chat:
 * the text arrives from the server in a disconnect packet that no other player can write, so a player typing these
 * words in chat cannot start a report. The first line and the Reason line are matched whole (anchored, bounded), so a
 * kick that merely mentions a ban ("You were kicked: ban evasion is not allowed") is not one.
 *
 * <p>Pure Java, no Minecraft class: the testkit calls {@link #parse} directly.
 */
public final class BanDetector {

    /** What a ban screen said, with the Ban ID kept apart: Hypixel warns that sharing it may affect the appeal. */
    public record Ban(boolean permanent, String duration, String reason, String banId, String maskedMessage) {

        /** SHA-256 hex identifying this ban for {@code uuid}: by Ban ID when there is one, else by kind and reason
         *  (never by the duration, which counts down between joins). */
        public String key(String uuid) {
            String basis = (uuid == null ? "" : uuid.toLowerCase(Locale.ROOT)) + "|"
                    + (banId != null ? "id:" + banId.toUpperCase(Locale.ROOT) : (permanent ? "perm:" : "temp:") + reason);
            try {
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(d.digest(basis.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final Pattern FIRST_LINE = Pattern.compile(
            "^You are (?:(permanently) banned|temporarily banned for ((?:\\d{1,4}[dhms] ?){1,4})) from this server!$");
    private static final Pattern REASON = Pattern.compile("^Reason: (.{1,200})$");
    private static final Pattern BAN_ID = Pattern.compile("^Ban ID: #?([A-Za-z0-9]{1,16})$");
    private static final Pattern LEGACY_CODES = Pattern.compile("§.");
    private static final int MAX_LINES = 20;

    private BanDetector() {
    }

    /** The ban on {@code reason} (a disconnect screen's text), or null when it is not a Hypixel ban screen. */
    public static Ban parse(String reason) {
        if (reason == null || reason.length() > 2000) {
            return null;
        }
        String text = LEGACY_CODES.matcher(reason).replaceAll("").replace("\r", "");
        String[] lines = text.split("\n", -1);
        if (lines.length > MAX_LINES) {
            return null;
        }
        Matcher first = FIRST_LINE.matcher(lines[0].strip());
        if (!first.matches()) {
            return null;
        }
        String why = null;
        String id = null;
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (i > 0) {
                Matcher r = REASON.matcher(line);
                if (why == null && r.matches()) {
                    why = r.group(1).strip();
                }
                Matcher b = BAN_ID.matcher(line);
                if (b.matches()) {
                    id = b.group(1);
                    line = "Ban ID: [hidden]";
                }
            }
            if (masked.length() > 0) {
                masked.append('\n');
            }
            masked.append(line);
        }
        if (why == null) {
            return null;
        }
        boolean permanent = first.group(1) != null;
        String duration = permanent ? null : first.group(2).strip();
        return new Ban(permanent, duration, why, id, masked.toString().strip());
    }
}
