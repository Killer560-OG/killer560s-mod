package com.killer560.hub.rngmeter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the player's current effective Magic Find off the chat messages Hypixel already sends
 * whenever a Magic-Find-affected item drops (format: "...(164% Magic Find)" at the end of the
 * message) - per killer560, this is the source to use instead of trying to compute Magic Find from
 * inventory/pet/potion state ourselves.
 */
public final class MagicFindTracker {

    // Matches "(<percent>% ... Magic Find)" - the text between the percent and "Magic Find"
    // is left loose (symbols like the star glyph, color codes already stripped by getString())
    // since the exact surrounding wording isn't confirmed and may vary by drop type.
    private static final Pattern MAGIC_FIND_PATTERN =
            Pattern.compile("\\((\\d+(?:\\.\\d+)?)%[^)]*Magic Find\\)");

    private static volatile Integer lastMagicFind = null;
    private static volatile long lastUpdatedAtMs = 0;

    private MagicFindTracker() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
    }

    private static void onMessage(String text) {
        Matcher m = MAGIC_FIND_PATTERN.matcher(text);
        if (m.find()) {
            try {
                lastMagicFind = (int) Math.round(Double.parseDouble(m.group(1)));
                lastUpdatedAtMs = System.currentTimeMillis();
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** Last observed Magic Find percentage, or null if no drop message has been seen yet this session. */
    public static Integer getLastMagicFind() {
        return lastMagicFind;
    }

    public static long getLastUpdatedAtMs() {
        return lastUpdatedAtMs;
    }
}
