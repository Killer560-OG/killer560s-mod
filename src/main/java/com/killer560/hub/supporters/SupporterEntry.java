package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameColor;

import java.util.UUID;

/**
 * One row of {@code GET /supporters} - see {@code SUPPORTERS-CONTRACT.md}. {@code rawName} is exactly what the
 * relay sent (plain text, {@code &} colour codes, up to 32 visible chars) - unfiltered and un-colourised;
 * {@link SupportersFeature} is what turns it into something safe to paint. {@code scale} is always clamped
 * into the contract's 0.5-2.0 range here so nothing downstream has to re-check it.
 * <p>
 * {@code colors} (relay field {@code colors}, 2026-10-11): the supporter's fade / letter colours, one {@code 0xRRGGBB}
 * per visible letter of {@code rawName}, or {@code null} for an entry without styling - every entry from before that
 * date, and any staff-set one, which then shows its single {@code &} colour as always. A list whose length does not
 * match the letters is dropped here, never half-applied.
 */
record SupporterEntry(UUID uuid, String rawName, float scale, int[] colors) {

    SupporterEntry {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid");
        }
        rawName = rawName == null ? "" : rawName;
        if (!Float.isFinite(scale) || scale < 0.5f || scale > 2.0f) {
            scale = 1.0f;
        }
        if (colors != null) {
            int letters = NameColor.visibleLetters(SupportersFeature.colorize(rawName));
            if (colors.length == 0 || colors.length != letters || colors.length > SupporterNameValidator.MAX_VISIBLE_CHARS) {
                colors = null;
            } else {
                int[] masked = new int[colors.length];
                for (int i = 0; i < colors.length; i++) {
                    masked[i] = colors[i] & 0xFFFFFF;
                }
                colors = masked;
            }
        }
    }

    SupporterEntry(UUID uuid, String rawName, float scale) {
        this(uuid, rawName, scale, null);
    }

    /** {@code colors} from a JSON array, or null when absent / not an array of numbers. */
    static int[] colorsFrom(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return null;
        }
        com.google.gson.JsonArray arr = el.getAsJsonArray();
        if (arr.size() > SupporterNameValidator.MAX_VISIBLE_CHARS) {
            return null;
        }
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) {
            com.google.gson.JsonElement c = arr.get(i);
            if (c == null || !c.isJsonPrimitive() || !c.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            out[i] = c.getAsInt();
        }
        return out;
    }

    static com.google.gson.JsonArray colorsJson(int[] colors) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int c : colors) {
            arr.add(c & 0xFFFFFF);
        }
        return arr;
    }
}
