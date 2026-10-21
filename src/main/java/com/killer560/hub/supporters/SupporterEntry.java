package com.killer560.hub.supporters;

import com.killer560.hub.namechanger.NameAnimation;
import com.killer560.hub.namechanger.NameColor;
import com.killer560.hub.trail.TrailSpec;

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
 * <p>
 * {@code animation} (relay field {@code animation}, 2026-10-13): Wave or Pulse for those colours ({@link NameAnimation}),
 * or {@code null} - always null without {@code colors}, since there is nothing to animate.
 * <p>
 * {@code trail} (relay field {@code trail}, 2026-10-13): the supporter's shared movement trail ({@link TrailSpec}), or
 * {@code null}. Independent of the name; an entry may carry only a trail, with an empty name.
 * <p>
 * {@code mob} (relay field {@code mob}, 2026-10-18): the mob the supporter plays as ({@code MobSkins} id), or
 * {@code null}. Anything not on the allowlist is dropped here. Like a trail, it may come with an empty name.
 */
record SupporterEntry(UUID uuid, String rawName, float scale, int[] colors, NameAnimation.Spec animation,
                      TrailSpec trail, String mob) {

    // Room for 32 visible characters each carrying several &-codes; only a far longer name is rejected.
    private static final int MAX_RAW_NAME = 1024;

    SupporterEntry {
        mob = com.killer560.hub.mobskin.MobSkins.sanitize(mob);
        if (uuid == null) {
            throw new IllegalArgumentException("uuid");
        }
        // The relay caps a name at 32 visible characters (plus &-codes); anything far past that is not from it.
        rawName = rawName == null || rawName.length() > MAX_RAW_NAME ? "" : rawName;
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
        if (colors == null) {
            animation = null;
        }
    }

    SupporterEntry(UUID uuid, String rawName, float scale, int[] colors, NameAnimation.Spec animation,
                   TrailSpec trail) {
        this(uuid, rawName, scale, colors, animation, trail, null);
    }

    SupporterEntry(UUID uuid, String rawName, float scale, int[] colors, NameAnimation.Spec animation) {
        this(uuid, rawName, scale, colors, animation, null, null);
    }

    SupporterEntry(UUID uuid, String rawName, float scale, int[] colors) {
        this(uuid, rawName, scale, colors, null, null, null);
    }

    SupporterEntry(UUID uuid, String rawName, float scale) {
        this(uuid, rawName, scale, null, null, null, null);
    }

    /** {@code mob} from JSON: a string, or null when absent / not a string (the constructor then checks the allowlist). */
    static String mobFrom(com.google.gson.JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    /** {@code animation} from JSON ({@code {"mode":"wave"|"pulse","speed":0.25-3}}), or null when absent, Off or not
     *  that shape. The speed is snapped onto the mod's own grid. */
    static NameAnimation.Spec animationFrom(com.google.gson.JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        com.google.gson.JsonObject o = el.getAsJsonObject();
        com.google.gson.JsonElement mode = o.get("mode");
        com.google.gson.JsonElement speed = o.get("speed");
        if (mode == null || !mode.isJsonPrimitive() || !mode.getAsJsonPrimitive().isString()
                || speed == null || !speed.isJsonPrimitive() || !speed.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double s = speed.getAsDouble();
        if (!Double.isFinite(s) || s < NameAnimation.MIN_SPEED || s > NameAnimation.MAX_SPEED) {
            return null;
        }
        return NameAnimation.Spec.of(NameAnimation.Mode.fromWire(mode.getAsString()), s);
    }

    static com.google.gson.JsonObject animationJson(NameAnimation.Spec spec) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("mode", spec.mode().wire);
        o.addProperty("speed", spec.speed());
        return o;
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
