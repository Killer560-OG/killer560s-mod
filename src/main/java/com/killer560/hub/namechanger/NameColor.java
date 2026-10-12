package com.killer560.hub.namechanger;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSink;

import java.util.Locale;
import com.killer560.hub.util.ChatColors;

/**
 * Colour handling for Name Changer's display names. killer560 (2026-09-20): "instead of using color codes I
 * select a color for it" - the tab now opens this mod's own {@code ColorPickerScreen} and stores an ARGB int
 * per name.
 * <p>
 * Names still travel through {@link NameReplacer} as plain strings with legacy {@code §} codes (that's what
 * makes the replacement keep the original text's click/hover events), and legacy codes only cover the 16 chat
 * colours - so a picked colour is snapped to the nearest of those, not written as a true RGB style.
 * {@link #migrate} pulls the colour out of a display name that was typed with {@code &} / {@code §} codes
 * before this change, so an existing setup keeps looking exactly the same.
 */
public final class NameColor {

    /** "No colour picked" - the name is shown in whatever colour the surrounding text already had. */
    public static final int NONE = 0;

    private static final ChatFormatting[] COLORS = buildColors();

    private NameColor() {
    }

    private static ChatFormatting[] buildColors() {
        return java.util.Arrays.stream(ChatFormatting.values())
                .filter(f -> ChatColors.isColor(f))
                .toArray(ChatFormatting[]::new);
    }

    /** ARGB of a legacy colour code character, or {@link #NONE} if it isn't one of the 16 colours. */
    public static int argbForCode(char code) {
        char lower = Character.toLowerCase(code);
        for (ChatFormatting f : COLORS) {
            if (ChatColors.code(f) == lower) {
                return 0xFF000000 | ChatColors.color(f);
            }
        }
        return NONE;
    }

    /** The legacy code whose colour is closest to {@code argb} (plain RGB distance). */
    public static char codeFor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        ChatFormatting best = ChatFormatting.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (ChatFormatting f : COLORS) {
            int c = ChatColors.color(f);
            long dr = r - ((c >> 16) & 0xFF);
            long dg = g - ((c >> 8) & 0xFF);
            long db = b - (c & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = f;
            }
        }
        return ChatColors.code(best);
    }

    /** "" for {@link #NONE}, otherwise the "§x" prefix to put in front of a display name. */
    public static String prefix(int argb) {
        return argb == NONE ? "" : "§" + codeFor(argb);
    }

    /** A display name split into its plain text and the colour it was typed with. */
    public record Migrated(String text, int argb) {
    }

    /** Pulls the first {@code &}/{@code §} colour code out of {@code display} and removes every colour code
     *  from the text, leaving the non-colour format codes (bold, italic, ...) alone so those keep working. */
    public static Migrated migrate(String display) {
        if (display == null || display.isEmpty()) {
            return new Migrated("", NONE);
        }
        StringBuilder text = new StringBuilder(display.length());
        int argb = NONE;
        for (int i = 0; i < display.length(); i++) {
            char c = display.charAt(i);
            boolean marker = c == '&' || c == '§';
            if (marker && i + 1 < display.length()) {
                char code = Character.toLowerCase(display.charAt(i + 1));
                int coded = "0123456789abcdef".indexOf(code) >= 0 ? argbForCode(code) : NONE;
                if (coded != NONE) {
                    if (argb == NONE) {
                        argb = coded;
                    }
                    i++;
                    continue;
                }
            }
            text.append(c);
        }
        return new Migrated(text.toString(), argb);
    }

    /**
     * Cosmetics tab: "allow them to fade the color" (killer560). A per-letter true-RGB gradient from {@code fromArgb}
     * to {@code toArgb} across the letters of {@code text}, written as one {@code §x§R§R§G§G§B§B} prefix per letter.
     * <p>
     * <b>Vanilla does not read that escape.</b> {@code StringDecomposer.iterateFormatted} (javap, 26.1.2 and 26.2) skips
     * {@code §x} as an unknown code and then applies each {@code §<hex digit>} as a LEGACY colour, so every letter took
     * the 16-colour code of its blue channel's low nibble - the "random blacks, whites and pinks" between the two picked
     * colours (killer560, 2026-10-11). Every place this mod turns one of these strings into styled glyphs therefore goes
     * through {@link #iterateFormatted} ({@code NameReplacer}, the {@code Font.prepareText(String)} redirect in
     * {@code NameChangerFontMixin}, {@link #toComponent}). {@code §} codes already in the text (bold, ...) are copied
     * through and are not letters.
     * <p>
     * LOCAL rendering only: {@code SupporterNameValidator} only accepts the 16 plain legacy codes, so {@code
     * SupportersAutoShare} shares just {@code fromArgb} (via {@link #prefix}).
     */
    public static String buildFade(String text, int fromArgb, int toArgb) {
        return buildPerChar(text, fromArgb, toArgb, i -> NONE);
    }

    /**
     * {@code StringDecomposer.iterateFormatted(s, base, sink)} plus Bungee's {@code §x§R§R§G§G§B§B} true-colour escape,
     * which vanilla misreads (see {@link #buildFade}). A hex colour keeps the bold/italic/... already active, so a
     * gradient over a bold name stays bold. Every other code behaves exactly as vanilla: {@code §r} goes back to
     * {@code base}, a legacy colour or format goes through {@code Style.applyLegacyFormat}, an unknown code is skipped.
     */
    public static boolean iterateFormatted(String s, Style base, FormattedCharSink sink) {
        if (s == null) {
            return true;
        }
        int n = s.length();
        Style style = base;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '§') {
                if (i + 1 >= n) {
                    break;
                }
                char code = s.charAt(i + 1);
                if ((code == 'x' || code == 'X') && i + 13 < n) {
                    int rgb = hexEscape(s, i + 2);
                    if (rgb >= 0) {
                        style = style.withColor(TextColor.fromRgb(rgb));
                        i += 14;
                        continue;
                    }
                }
                ChatFormatting f = ChatFormatting.getByCode(code);
                if (f != null) {
                    style = f == ChatFormatting.RESET ? base : style.applyLegacyFormat(f);
                }
                i += 2;
                continue;
            }
            int cp = s.codePointAt(i);
            if (!sink.accept(i, style, cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /** The RGB of the six {@code §<hex>} pairs starting at {@code from}, or -1 when they are not six such pairs. */
    private static int hexEscape(String s, int from) {
        int rgb = 0;
        for (int k = 0; k < 6; k++) {
            int at = from + 2 * k;
            if (s.charAt(at) != '§') {
                return -1;
            }
            int d = Character.digit(s.charAt(at + 1), 16);
            if (d < 0) {
                return -1;
            }
            rgb = (rgb << 4) | d;
        }
        return rgb;
    }

    /** The letters a player sees in {@code s}: every {@code §} code, hex escapes included, removed. */
    public static String plain(String s) {
        StringBuilder sb = new StringBuilder(s == null ? 0 : s.length());
        iterateFormatted(s, Style.EMPTY, (pos, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    /** The colour of each letter {@code s} paints (0xRRGGBB, -1 where a letter has no colour of its own). */
    public static int[] letterColours(String s) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        iterateFormatted(s, Style.EMPTY, (pos, style, cp) -> {
            TextColor c = style.getColor();
            out.add(c == null ? -1 : c.getValue() & 0xFFFFFF);
            return true;
        });
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** A legacy-coded string (hex escapes included) as a {@link Component} with real styles, one run per style. */
    public static Component toComponent(String s) {
        MutableComponent out = Component.empty();
        if (s == null || s.isEmpty()) {
            return out;
        }
        StringBuilder run = new StringBuilder();
        Style[] runStyle = {null};
        iterateFormatted(s, Style.EMPTY, (pos, style, cp) -> {
            if (runStyle[0] != null && !style.equals(runStyle[0])) {
                out.append(Component.literal(run.toString()).withStyle(runStyle[0]));
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(cp);
            return true;
        });
        if (runStyle[0] != null && run.length() > 0) {
            out.append(Component.literal(run.toString()).withStyle(runStyle[0]));
        }
        return out;
    }

    /**
     * Your own name with a colour per letter (Cosmetics tab "Letter Colours"). Letter {@code i} gets
     * {@code perChar.applyAsInt(i)} when that is not {@link #NONE}, otherwise the {@code fromArgb}-to-{@code
     * toArgb} gradient's colour at that letter (pass the same colour twice for a flat name). Existing {@code §}
     * codes in the text are copied through and do not count as letters.
     */
    public static String buildPerChar(String text, int fromArgb, int toArgb,
                                      java.util.function.IntUnaryOperator perChar) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        int letters = visibleLetters(text);
        StringBuilder sb = new StringBuilder(text.length() * 15);
        int idx = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                sb.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            int cp = text.codePointAt(i);
            int own = perChar.applyAsInt(idx);
            int argb = own != NONE ? own
                    : lerpArgb(fromArgb, toArgb, letters <= 1 ? 0f : idx / (float) (letters - 1));
            sb.append(hexPrefix(argb));
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            idx++;
        }
        return sb.toString();
    }

    /** Letters a player sees in {@code text}: code points, not counting {@code §x} format pairs. */
    public static int visibleLetters(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '§' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            i += Character.charCount(text.codePointAt(i));
            n++;
        }
        return n;
    }

    private static int lerpArgb(int from, int to, float t) {
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int r = Math.round(fr + (tr - fr) * t);
        int g = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Bungee's {@code §x} hex-colour escape: {@code §x} followed by 6 {@code §<hex digit>} pairs, one per RGB nibble.
     *  Vanilla does NOT understand it - only {@link #iterateFormatted} does (see {@link #buildFade}). */
    private static String hexPrefix(int argb) {
        String hex = String.format(Locale.ROOT, "%06x", argb & 0xFFFFFF);
        StringBuilder sb = new StringBuilder(14);
        sb.append("§x");
        for (int i = 0; i < 6; i++) {
            sb.append('§').append(hex.charAt(i));
        }
        return sb.toString();
    }

    /** Human-readable name of the nearest legacy colour, for the settings tab. */
    public static String label(int argb) {
        if (argb == NONE) {
            return "Default";
        }
        char code = codeFor(argb);
        for (ChatFormatting f : COLORS) {
            if (ChatColors.code(f) == code) {
                return ChatColors.name(f).replace('_', ' ').toLowerCase(Locale.US);
            }
        }
        return "Default";
    }
}
