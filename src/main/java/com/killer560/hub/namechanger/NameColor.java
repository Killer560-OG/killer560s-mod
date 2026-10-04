package com.killer560.hub.namechanger;

import net.minecraft.ChatFormatting;

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
     * Cosmetics tab: "allow them to fade the color" (killer560). Builds a per-letter true-RGB gradient from
     * {@code fromArgb} to {@code toArgb} across {@code plainText}, using vanilla's own {@code §x} hex-colour
     * extension - ONE {@code §x§R§R§G§G§B§B} prefix per visible character, which {@code StringDecomposer}
     * (and therefore {@link com.killer560.hub.namechanger.NameReplacer}'s Font-level replace, both the
     * String and FormattedCharSequence paths) already understands, so this needs no new rendering machinery.
     * <p>
     * LOCAL rendering only: {@code SupporterNameValidator} only accepts the 16 plain {@code &0-9a-fk-or}
     * legacy codes, so a fade like this would be rejected outright by the supporters relay - {@code
     * SupportersAutoShare} deliberately sends just {@code fromArgb} (via {@link #prefix}) instead of a fade
     * whenever it pushes your own name to the relay. Your own client still sees the full gradient everywhere
     * your own name is drawn; other players only ever see your fade if they don't have this mod's Cosmetics
     * tab pointed at a relay that supports it.
     */
    public static String buildFade(String plainText, int fromArgb, int toArgb) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText == null ? "" : plainText;
        }
        int n = plainText.codePointCount(0, plainText.length());
        if (n <= 1) {
            return prefix(fromArgb) + plainText;
        }
        StringBuilder sb = new StringBuilder(plainText.length() * 15);
        int i = 0;
        int idx = 0;
        while (i < plainText.length()) {
            int cp = plainText.codePointAt(i);
            float t = idx / (float) (n - 1);
            sb.append(hexPrefix(lerpArgb(fromArgb, toArgb, t)));
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            idx++;
        }
        return sb.toString();
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

    /** Vanilla's {@code §x} hex-colour escape: {@code §x} followed by 6 {@code §<hex digit>} pairs, one per
     *  RGB nibble - the same format Hypixel ranks like MVP++ already use for their own gradient names. */
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
