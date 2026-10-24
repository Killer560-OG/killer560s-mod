package com.killer560.hub.gui;

import java.util.Locale;

/**
 * Color text for the color picker's Copy / Paste / hex box (killer560, 2026-10-13: "a button to copy the hex id of that
 * color ... and an easy way to paste it between menus").
 * <p>
 * An opaque color is written {@code #RRGGBB}; a translucent one {@code #AARRGGBB}, so a fill's alpha travels with it.
 * Read back: {@code #RRGGBB}, {@code RRGGBB}, {@code #RGB}, {@code 0xRRGGBB}, {@code #AARRGGBB} / {@code 0xAARRGGBB} /
 * {@code AARRGGBB}, any whitespace ignored. A form without alpha leaves the target's own alpha alone
 * ({@link #applyTo}), so pasting a plain color into a translucent setting keeps it translucent. Never throws.
 */
public final class ColourHex {

    /** A color read from text; {@code hasAlpha} when the text gave one (eight digits). */
    public record Parsed(int argb, boolean hasAlpha) {
    }

    private ColourHex() {
    }

    public static String format(int argb) {
        int a = (argb >>> 24) & 0xFF;
        return a == 0xFF ? String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF)
                : String.format(Locale.ROOT, "#%08X", argb);
    }

    /** The color {@code text} names, or null when it is not one of the accepted forms. */
    public static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < text.length() && sb.length() <= 16; i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                sb.append(ch);
            }
        }
        if (sb.length() > 16) {
            return null;
        }
        String s = sb.toString();
        boolean hash = false;
        if (s.startsWith("#")) {
            s = s.substring(1);
            hash = true;
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return null;
            }
        }
        try {
            switch (s.length()) {
                case 3: {
                    if (!hash) {
                        return null;
                    }
                    int r = Character.digit(s.charAt(0), 16);
                    int g = Character.digit(s.charAt(1), 16);
                    int b = Character.digit(s.charAt(2), 16);
                    return new Parsed(0xFF000000 | (r * 17) << 16 | (g * 17) << 8 | (b * 17), false);
                }
                case 6:
                    return new Parsed(0xFF000000 | Integer.parseInt(s, 16), false);
                case 8:
                    return new Parsed((int) Long.parseLong(s, 16), true);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code current} turned into {@code p}: the whole ARGB when {@code p} carries alpha, otherwise only the RGB. */
    public static int applyTo(int current, Parsed p) {
        return p.hasAlpha() ? p.argb() : (current & 0xFF000000) | (p.argb() & 0xFFFFFF);
    }
}
