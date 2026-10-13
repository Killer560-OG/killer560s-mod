package com.killer560.hub.trail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * One trail's look, as a supporter shares it through the relay (killer560, 2026-10-13: "it should be toggleable to see
 * in cosmetics for whether it is shared or not and it can only be shared by a supporter"). The relay field is
 * {@code trail: {style, mode, colors, width, length, opacity}} - see the relay's PROTOCOL.md.
 * <p>
 * {@code colors} runs head (at the player) to tail, two or three 0xRRGGBB. {@code width} is the ribbon's width or a
 * square's size in blocks; {@code length} is how long a ribbon point lasts in seconds, or how many squares are kept;
 * {@code opacity} is a percentage. {@link #fromJson} applies the relay's own rules and returns null for anything else,
 * so a malformed or hostile entry draws nothing rather than something huge.
 */
public record TrailSpec(TrailConfig.Style style, TrailConfig.ColorMode mode, int[] colors, float width, float length,
                        int opacity) {

    private static final Set<String> KEYS = Set.of("style", "mode", "colors", "width", "length", "opacity");

    /** What the local player's own Trail settings share. */
    public static TrailSpec fromConfig(TrailConfig cfg) {
        boolean ribbon = cfg.getStyle() == TrailConfig.Style.RIBBON;
        int[] colors = cfg.getColorCount() >= 3
                ? new int[]{cfg.getHeadColor() & 0xFFFFFF, cfg.getMidColor() & 0xFFFFFF, cfg.getTailColor() & 0xFFFFFF}
                : new int[]{cfg.getHeadColor() & 0xFFFFFF, cfg.getTailColor() & 0xFFFFFF};
        float width = hundredths(ribbon ? cfg.getRibbonWidth() : cfg.getSquareSize());
        float length = ribbon ? hundredths(cfg.getRibbonSeconds()) : cfg.getLength();
        return new TrailSpec(cfg.getStyle(), cfg.getColorMode(), colors, width, length, cfg.getOpacityPercent());
    }

    private static float hundredths(float v) {
        return Math.round(v * 100f) / 100f;
    }

    static String wire(TrailConfig.Style s) {
        return s == TrailConfig.Style.RIBBON ? "ribbon" : "squares";
    }

    static String wire(TrailConfig.ColorMode m) {
        return switch (m) {
            case NAME_FADE -> "namefade";
            case CHROMA -> "chroma";
            default -> "gradient";
        };
    }

    /** The relay's shape, keys in wire order. Floats go out as their shortest decimal (0.6, not 0.6000000238). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("style", wire(style));
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
            TrailConfig.Style style = switch (string(o.get("style"))) {
                case "squares" -> TrailConfig.Style.SQUARES;
                case "ribbon" -> TrailConfig.Style.RIBBON;
                default -> null;
            };
            TrailConfig.ColorMode mode = switch (string(o.get("mode"))) {
                case "gradient" -> TrailConfig.ColorMode.GRADIENT;
                case "namefade" -> TrailConfig.ColorMode.NAME_FADE;
                case "chroma" -> TrailConfig.ColorMode.CHROMA;
                default -> null;
            };
            if (style == null || mode == null || !o.get("colors").isJsonArray()) {
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
            boolean ribbon = style == TrailConfig.Style.RIBBON;
            double maxWidth = ribbon ? TrailConfig.MAX_RIBBON_WIDTH : TrailConfig.MAX_SQUARE_SIZE;
            double minWidth = ribbon ? TrailConfig.MIN_RIBBON_WIDTH : TrailConfig.MIN_SQUARE_SIZE;
            if (!(width >= minWidth - 1e-6 && width <= maxWidth + 1e-6)) {
                return null;
            }
            if (ribbon ? !(length >= TrailConfig.MIN_RIBBON_SECONDS - 1e-6 && length <= TrailConfig.MAX_RIBBON_SECONDS + 1e-6)
                    : !(length == Math.rint(length) && length >= TrailConfig.MIN_LENGTH && length <= TrailConfig.MAX_LENGTH)) {
                return null;
            }
            if (opacity != Math.rint(opacity) || opacity < TrailConfig.MIN_OPACITY_PERCENT
                    || opacity > TrailConfig.MAX_OPACITY_PERCENT) {
                return null;
            }
            return new TrailSpec(style, mode, colors, (float) width, (float) length, (int) opacity);
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
        return o instanceof TrailSpec t && t.style == style && t.mode == mode && Arrays.equals(t.colors, colors)
                && Float.compare(t.width, width) == 0 && Float.compare(t.length, length) == 0 && t.opacity == opacity;
    }

    @Override
    public int hashCode() {
        return ((style.hashCode() * 31 + mode.hashCode()) * 31 + Arrays.hashCode(colors)) * 31
                + Float.hashCode(width) * 17 + Float.hashCode(length) * 7 + opacity;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    /** For log lines and the test hooks: "ribbon gradient 3 colours 0.60x1.00 60%". */
    public String describe() {
        return String.format(Locale.ROOT, "%s %s %d colours %.2fx%.2f %d%%", wire(style), wire(mode), colors.length,
                width, length, opacity);
    }
}
