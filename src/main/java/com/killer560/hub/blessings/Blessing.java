package com.killer560.hub.blessings;

import java.util.regex.Pattern;

/**
 * The five dungeon blessings, their tab-footer patterns and their display colours.
 * <ul>
 * <li>Names + the fact that there are exactly five: NoammAddons
 * {@code utils/dungeons/enums/Blessing.kt} (POWER/LIFE/WISDOM/STONE/TIME) and Devonian
 * {@code features/dungeons/BlessingsDisplay.kt} (Power/Time/Wisdom/Stone/Life).</li>
 * <li>Line format: NoammAddons' per-blessing regex {@code "Blessing of Power (X{0,3}(IX|IV|V?I{0,3}))"} and
 * Devonian's generic per-footer-line regex {@code "^Blessing of (\\w+) ([IVX]+)$"}. This mod uses the generic
 * Roman-numeral group for every blessing (Noamm hardcodes {@code (V)} for TIME, which silently stops matching if
 * Hypixel ever raises Time's cap) - the same text, just a wider numeral group.</li>
 * <li>Display order and default colours: Devonian's {@code format()} - Power &4 (dark red), Time &6 (gold),
 * Wisdom &b (aqua), Stone &7 (gray), Life &2 (dark green). NoammAddons' {@code BlessingDisplay.kt} uses the same
 * idea with Time dark purple and Life red; Devonian's set was kept because it matches the in-game footer colours.</li>
 * </ul>
 * <b>Not included:</b> no stat table. Neither source (nor anything else cited in this repo) maps a blessing level
 * to the stats it grants, so this feature shows LEVELS ONLY and never a derived stat value.
 */
public enum Blessing {

    POWER("Power", 0xFFAA0000),
    TIME("Time", 0xFFFFAA00),
    WISDOM("Wisdom", 0xFF55FFFF),
    STONE("Stone", 0xFFAAAAAA),
    LIFE("Life", 0xFF00AA00);

    private final String displayName;
    private final int defaultColor;
    private final Pattern pattern;

    Blessing(String displayName, int defaultColor) {
        this.displayName = displayName;
        this.defaultColor = defaultColor;
        this.pattern = Pattern.compile("Blessing of " + displayName + " ([IVXLC]+)");
    }

    public String displayName() {
        return displayName;
    }

    public int defaultColor() {
        return defaultColor;
    }

    Pattern pattern() {
        return pattern;
    }

    /** Roman numeral -> int, same conversion as NoammAddons' {@code NumbersUtils.romanToDecimal}. Returns 0 for
     *  anything that isn't a well-formed numeral, so a garbled footer line can never lower a real level. */
    public static int parseRoman(String roman) {
        if (roman == null || roman.isEmpty()) {
            return 0;
        }
        int total = 0;
        int prev = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int value = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            if (value == 0) {
                return 0;
            }
            total += value < prev ? -value : value;
            prev = Math.max(prev, value);
        }
        return total;
    }

    /** 1 -> "I" ... - the optional "Roman Numerals" HUD style (Devonian's {@code SETTING_ROMAN} /
     *  {@code StringUtils.formatRoman}). Falls back to the plain number above 3999 / at 0. */
    public static String toRoman(int value) {
        if (value <= 0 || value > 3999) {
            return String.valueOf(value);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int left = value;
        for (int i = 0; i < values.length; i++) {
            while (left >= values[i]) {
                left -= values[i];
                sb.append(symbols[i]);
            }
        }
        return sb.toString();
    }
}
