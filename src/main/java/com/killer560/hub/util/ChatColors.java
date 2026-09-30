package com.killer560.hub.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;

/**
 * The five {@link ChatFormatting} accessors Minecraft 26.2 deleted, rebuilt from what both versions still have.
 *
 * <p>26.2 strips {@code ChatFormatting} down to {@code code}, {@code toString()} and {@code getByCode(char)}.
 * Gone are {@code getChar()}, {@code isColor()}, {@code getColor()}, {@code getName()} and
 * {@code getByName(String)} - 29 of the errors a 26.2 compile produced.
 *
 * <p><b>This is deliberately NOT in the per-version compat layer.</b> Four of the five are derivable from
 * {@code toString()}, {@code ordinal()} and {@code name()}, which 26.1.2 and 26.2 have identically, so a
 * per-version copy would be the same code twice and the second copy would be the one that drifts. Only
 * {@link #color} needs data the newer jar no longer carries, and that is a table of constants, not an API.
 *
 * <p><b>What was verified, and how.</b> Both jars' {@code ChatFormatting} were disassembled
 * ({@code javap -c -p} against {@code .gradle/loom-cache/.../26.1.2/} and the extracted 26.2 classes) and the
 * enum's static initialiser read off directly rather than guessed:
 *
 * <ul>
 *   <li>The 22 constants sit in the same order with the same {@code code} characters in both versions -
 *       {@code BLACK}..{@code WHITE} at ordinals 0-15 taking '0'..'9','a'..'f', then {@code OBFUSCATED},
 *       {@code BOLD}, {@code STRIKETHROUGH}, {@code UNDERLINE}, {@code ITALIC} and {@code RESET}. Byte for
 *       byte the same; that is what makes {@link #isColor} and {@link #code} safe to derive.</li>
 *   <li>Both constructors end with {@code toString = "§" + code}, so {@code toString().charAt(1)} IS the
 *       old {@code getChar()}, on both versions.</li>
 *   <li>26.1.2's {@code isColor()} is {@code !isFormat && this != RESET}, and {@code isFormat} is true for
 *       exactly ordinals 16-20 with {@code RESET} at 21 - so {@code ordinal() < 16}.</li>
 *   <li>26.1.2's {@code getName()} is {@code name().toLowerCase(Locale.ROOT)}. Not the raw enum name: the
 *       one caller in this mod lowercases the result again, which would have hidden getting this backwards.</li>
 *   <li>26.1.2's {@code getByName(String)} looks the name up after {@code cleanName}, which is
 *       {@code toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")} - so it strips the underscore too and
 *       {@code "dark blue"}, {@code "DARK_BLUE"} and {@code "darkblue"} all resolve. {@link #byName} keeps
 *       that, because its caller feeds it colour names out of Hypixel's API and a stricter match would
 *       silently fall back to a default instead.</li>
 * </ul>
 *
 * <p>The {@link #RGB} table is the one thing here that cannot be derived, so it is also the one thing checked
 * at runtime: {@code McCompat.verifyChatColors()} compares it against the real {@code getColor()} on 26.1.2,
 * where that method still exists, and {@code Killer560ModClient} runs that check once at start-up in a dev or
 * cheat build and logs an ERROR naming every mismatch. A wrong entry here would compile, draw a plausible
 * colour and never be noticed otherwise.
 */
public final class ChatColors {

    /**
     * RGB of the 16 colour formats, indexed by {@link ChatFormatting#ordinal()}.
     *
     * <p>Read out of 26.1.2's {@code ChatFormatting.<clinit>} as decimal literals and converted:
     * 170, 43520, 43690, 11141120, 11141290, 16755200, 11184810, 5592405, 5592575, 5635925, 5636095,
     * 16733525, 16733695, 16777045, 16777215. Not copied from a wiki table.
     */
    public static final int[] RGB = {
            0x000000, // BLACK
            0x0000AA, // DARK_BLUE
            0x00AA00, // DARK_GREEN
            0x00AAAA, // DARK_AQUA
            0xAA0000, // DARK_RED
            0xAA00AA, // DARK_PURPLE
            0xFFAA00, // GOLD
            0xAAAAAA, // GRAY
            0x555555, // DARK_GRAY
            0x5555FF, // BLUE
            0x55FF55, // GREEN
            0x55FFFF, // AQUA
            0xFF5555, // RED
            0xFF55FF, // LIGHT_PURPLE
            0xFFFF55, // YELLOW
            0xFFFFFF, // WHITE
    };

    /** {@code cleanName(name)} of every constant, to the constant - 26.1.2's {@code FORMATTING_BY_NAME}. */
    private static final Map<String, ChatFormatting> BY_NAME = new HashMap<>();

    static {
        for (ChatFormatting f : ChatFormatting.values()) {
            BY_NAME.put(cleanName(f.name()), f);
        }
    }

    private ChatColors() {
    }

    /** The legacy code character, as {@code ChatFormatting.getChar()} gave it. */
    public static char code(ChatFormatting f) {
        // Both versions build toString as "§" + code in the constructor, so index 1 is the code.
        return f.toString().charAt(1);
    }

    /** Whether this is one of the 16 colours rather than a style or RESET, as {@code isColor()} gave it. */
    public static boolean isColor(ChatFormatting f) {
        return f.ordinal() < RGB.length;
    }

    /** RGB of a colour format, or null for a style or RESET - as {@code getColor()} gave it, Integer and all. */
    public static Integer color(ChatFormatting f) {
        return isColor(f) ? RGB[f.ordinal()] : null;
    }

    /** The lower-case name, as {@code getName()} gave it: "dark_blue", underscore kept. */
    public static String name(ChatFormatting f) {
        return f.name().toLowerCase(Locale.ROOT);
    }

    /** The constant with this name, or null - as {@code getByName(String)} gave it, punctuation-insensitive. */
    public static ChatFormatting byName(String name) {
        return name == null ? null : BY_NAME.get(cleanName(name));
    }

    /** 26.1.2's private {@code cleanName}: lower-case, then everything that is not a-z removed. */
    private static String cleanName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
