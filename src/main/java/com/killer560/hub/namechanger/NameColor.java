package com.killer560.hub.namechanger;

import net.minecraft.ChatFormatting;

import java.util.Locale;

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
                .filter(f -> f.isColor() && f.getColor() != null)
                .toArray(ChatFormatting[]::new);
    }

    /** ARGB of a legacy colour code character, or {@link #NONE} if it isn't one of the 16 colours. */
    public static int argbForCode(char code) {
        char lower = Character.toLowerCase(code);
        for (ChatFormatting f : COLORS) {
            if (f.getChar() == lower) {
                return 0xFF000000 | f.getColor();
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
            int c = f.getColor();
            long dr = r - ((c >> 16) & 0xFF);
            long dg = g - ((c >> 8) & 0xFF);
            long db = b - (c & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = f;
            }
        }
        return best.getChar();
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

    /** Human-readable name of the nearest legacy colour, for the settings tab. */
    public static String label(int argb) {
        if (argb == NONE) {
            return "Default";
        }
        char code = codeFor(argb);
        for (ChatFormatting f : COLORS) {
            if (f.getChar() == code) {
                return f.getName().replace('_', ' ').toLowerCase(Locale.US);
            }
        }
        return "Default";
    }
}
