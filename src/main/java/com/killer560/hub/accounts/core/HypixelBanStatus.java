package com.killer560.hub.accounts.core;

import java.time.Instant;

/**
 * Result of actually connecting to mc.hypixel.net and reading whatever kick message comes back -
 * there is no public API for Hypixel ban status, so this is the only legitimate way to learn it
 * (the same way a real player finds out: by trying to connect).
 */
public record HypixelBanStatus(Status status, String rawMessage, Instant estimatedExpiry) {

    public enum Status {
        NOT_BANNED,
        BANNED,
        UNKNOWN
    }

    public static HypixelBanStatus notBanned() {
        return new HypixelBanStatus(Status.NOT_BANNED, null, null);
    }

    public static HypixelBanStatus unknown(String reason) {
        return new HypixelBanStatus(Status.UNKNOWN, reason, null);
    }

    /** Classifies a login/configuration/play disconnect message and best-effort extracts a ban length. */
    public static HypixelBanStatus fromKickMessage(String message) {
        if (message == null) {
            return new HypixelBanStatus(Status.UNKNOWN, null, null);
        }
        String lower = message.toLowerCase(java.util.Locale.US);
        if (!lower.contains("ban")) {
            return new HypixelBanStatus(Status.UNKNOWN, message, null);
        }
        Instant expiry = estimateExpiry(message);
        return new HypixelBanStatus(Status.BANNED, message, expiry);
    }

    /**
     * Hypixel doesn't expose a structured expiry timestamp, only free text like
     * "Length: 29 days 23 hours 12 minutes" in the kick message - this parses that if present.
     * Returns null (permanent ban, or a format that didn't match) rather than guessing.
     */
    private static Instant estimateExpiry(String message) {
        java.util.regex.Matcher dayMatch = java.util.regex.Pattern
                .compile("(\\d+)\\s*day", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        java.util.regex.Matcher hourMatch = java.util.regex.Pattern
                .compile("(\\d+)\\s*hour", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        java.util.regex.Matcher minuteMatch = java.util.regex.Pattern
                .compile("(\\d+)\\s*minute", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);

        long totalSeconds = 0;
        boolean found = false;
        if (dayMatch.find()) {
            totalSeconds += Long.parseLong(dayMatch.group(1)) * 86400L;
            found = true;
        }
        if (hourMatch.find()) {
            totalSeconds += Long.parseLong(hourMatch.group(1)) * 3600L;
            found = true;
        }
        if (minuteMatch.find()) {
            totalSeconds += Long.parseLong(minuteMatch.group(1)) * 60L;
            found = true;
        }
        if (!found) {
            return null;
        }
        return Instant.now().plusSeconds(totalSeconds);
    }
}
