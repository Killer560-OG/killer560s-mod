package com.killer560.hub.trail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * One Ribbon Trail's look, as a supporter shares it through the relay (killer560, 2026-10-13: "it should be toggleable
 * to see in cosmetics for whether it is shared or not and it can only be shared by a supporter"). The relay field is
 * {@code trail: {style, mode, colors, width, length, opacity}} - see the relay's PROTOCOL.md.
 * <p>
 * Only the ribbon is ever shared (killer560, 2026-10-14: "The other trail option should not be shared even for the
 * squares"): {@code style} always goes out as "ribbon" (older clients require the key), and an entry with any other
 * style - a squares trail an older client may still send - reads as no trail at all.
 * <p>
 * {@code colors} runs head (at the player) to tail, two or three 0xRRGGBB. {@code width} is the band's height in blocks
 * (the field kept its name when the band stood up, 2026-10-14); {@code length} is how long a point lasts in seconds;
 * {@code opacity} is a percentage. {@link #fromJson} applies the relay's own rules and returns null for anything else,
 * so a malformed or hostile entry draws nothing rather than something huge.
 */
public record TrailSpec(RibbonTrailConfig.ColorMode mode, int[] colors, float width, float length, int opacity) {

    private static final Set<String> KEYS = Set.of("style", "mode", "colors", "width", "length", "opacity");

    /** What the local player's own Ribbon Trail settings share. */
    public static TrailSpec fromConfig(RibbonTrailConfig cfg) {
        int[] colors = cfg.getColorCount() >= 3
                ? new int[]{cfg.getHeadColor() & 0xFFFFFF, cfg.getMidColor() & 0xFFFFFF, cfg.getTailColor() & 0xFFFFFF}
                : new int[]{cfg.getHeadColor() & 0xFFFFFF, cfg.getTailColor() & 0xFFFFFF};
        return new TrailSpec(cfg.getColorMode(), colors, hundredths(cfg.getRibbonHeight()),
                hundredths(cfg.getRibbonSeconds()), cfg.getOpacityPercent());
    }

    private static float hundredths(float v) {
        return Math.round(v * 100f) / 100f;
    }

    static String wire(RibbonTrailConfig.ColorMode m) {
        return switch (m) {
            case NAME_FADE -> "namefade";
            case CHROMA -> "chroma";
            default -> "gradient";
        };
    }

    /** The relay's shape, keys in wire order. Floats go out as their shortest decimal (0.6, not 0.6000000238). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("style", "ribbon");
        o.addProperty("mode", wire(mode));
        JsonArray arr = new JsonArray();
        for (int c : colors) {
            arr.add(c & 0xFFFFFF);
        }
        o.add("colors", arr);
        o.add("width", number(width));
        o.add("length", number(length));
        o.addProperty("opacity", opacity);
        return o;
    }

    private static JsonPrimitive number(float v) {
        if (v == Math.rint(v)) {
            return new JsonPrimitive((long) v);
        }
        return new JsonPrimitive(new java.math.BigDecimal(Float.toString(v)));
    }

    /** {@code trail} from a relay entry, or null when absent or not exactly the relay's shape and ranges. */
    public static TrailSpec fromJson(JsonElement el) {
        try {
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.keySet().equals(KEYS)) {
                return null;
            }
            // Only a ribbon is drawn; a squares trail (older clients) is ignored.
            if (!"ribbon".equals(string(o.get("style")))) {
                return null;
            }
            RibbonTrailConfig.ColorMode mode = switch (string(o.get("mode"))) {
                case "gradient" -> RibbonTrailConfig.ColorMode.GRADIENT;
                case "namefade" -> RibbonTrailConfig.ColorMode.NAME_FADE;
                case "chroma" -> RibbonTrailConfig.ColorMode.CHROMA;
                default -> null;
            };
            if (mode == null || !o.get("colors").isJsonArray()) {
                return null;
            }
            JsonArray arr = o.getAsJsonArray("colors");
            if (arr.size() < 2 || arr.size() > 3) {
                return null;
            }
            int[] colors = new int[arr.size()];
            for (int i = 0; i < colors.length; i++) {
                double c = number(arr.get(i));
                if (c != Math.rint(c) || c < 0 || c > 0xFFFFFF) {
                    return null;
                }
                colors[i] = (int) c;
            }
            double width = number(o.get("width"));
            double length = number(o.get("length"));
            double opacity = number(o.get("opacity"));
            if (!(width >= RibbonTrailConfig.MIN_RIBBON_HEIGHT - 1e-6 && width <= RibbonTrailConfig.MAX_RIBBON_HEIGHT + 1e-6)) {
                return null;
            }
            if (!(length >= RibbonTrailConfig.MIN_RIBBON_SECONDS - 1e-6
                    && length <= RibbonTrailConfig.MAX_RIBBON_SECONDS + 1e-6)) {
                return null;
            }
            if (opacity != Math.rint(opacity) || opacity < RibbonTrailConfig.MIN_OPACITY_PERCENT
                    || opacity > RibbonTrailConfig.MAX_OPACITY_PERCENT) {
                return null;
            }
            return new TrailSpec(mode, colors, (float) width, (float) length, (int) opacity);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }

    /** A JSON number as a double, or NaN for anything else (which every range check above refuses). */
    private static double number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Double.NaN;
        }
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : Double.NaN;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TrailSpec t && t.mode == mode && Arrays.equals(t.colors, colors)
                && Float.compare(t.width, width) == 0 && Float.compare(t.length, length) == 0 && t.opacity == opacity;
    }

    @Override
    public int hashCode() {
        return (mode.hashCode() * 31 + Arrays.hashCode(colors)) * 31
                + Float.hashCode(width) * 17 + Float.hashCode(length) * 7 + opacity;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    /** For log lines and the test hooks: "ribbon gradient 3 colours 0.60x1.00 60%". */
    public String describe() {
        return String.format(Locale.ROOT, "ribbon %s %d colors %.2fx%.2f %d%%", wire(mode), colors.length,
                width, length, opacity);
    }
}
