package com.killer560.hub.namechanger;

/**
 * The rank tag shown before your own display name (killer560, 2026-10-15: "add the option for custom prefix instead of
 * the mvp++ or the vip or whatnot. You should be able to quick change the color of it, select a different rank, or create
 * your own rank").
 * <p>
 * Hypixel writes a rank as {@code [MVP++] } directly before the name. {@link NameReplacer} swaps that whole tag together
 * with the name when the rank is set to anything but {@link Preset#DEFAULT}; this class only builds the replacement text
 * (a legacy-coded string, like every other display name) and knows what a rank tag looks like.
 */
public final class NameRank {

    /** Which rank to show. {@link #DEFAULT} leaves whatever the text already says; {@link #NONE} removes the tag. */
    public enum Preset {
        DEFAULT("Keep Original", "", 0, 'f', 'f'),
        NONE("None", "", 0, 'f', 'f'),
        VIP("VIP", "VIP", 0, 'a', 'a'),
        VIP_PLUS("VIP+", "VIP", 1, 'a', '6'),
        MVP("MVP", "MVP", 0, 'b', 'b'),
        MVP_PLUS("MVP+", "MVP", 1, 'b', 'c'),
        MVP_PLUS_PLUS("MVP++", "MVP", 2, '6', 'c'),
        CUSTOM("Custom", "", 0, 'f', 'f');

        public final String label;
        final String text;
        final int pluses;
        final char main;
        final char plus;

        Preset(String label, String text, int pluses, char main, char plus) {
            this.label = label;
            this.text = text;
            this.pluses = pluses;
            this.main = main;
            this.plus = plus;
        }

        public static Preset fromWire(String s) {
            if (s != null) {
                for (Preset p : values()) {
                    if (p.name().equalsIgnoreCase(s)) {
                        return p;
                    }
                }
            }
            return DEFAULT;
        }
    }

    /** The 16 chat colours the quick-colour button steps through, after "the rank's own". */
    public static final String COLOR_CODES = "0123456789abcdef";

    public static final int MAX_TEXT = 12;

    private NameRank() {
    }

    /** What a custom rank's text may hold: no brackets (they are added) and no colour/format markers. */
    public static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (c != '[' && c != ']' && c != '§' && c != '&' && c >= ' ') {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    /**
     * The replacement for the original rank tag, with its trailing space, or {@code null} to leave the tag alone
     * ({@link Preset#DEFAULT}). {@link Preset#NONE} gives "" - the tag is simply removed.
     */
    public static String build(NameChangerConfig cfg) {
        Preset p = cfg.getOwnRank();
        if (p == Preset.DEFAULT) {
            return null;
        }
        if (p == Preset.NONE) {
            return "";
        }
        String text = p.text;
        int pluses = p.pluses;
        if (p == Preset.CUSTOM) {
            String custom = cleanText(cfg.getOwnRankCustomText());
            int end = custom.length();
            while (end > 0 && custom.charAt(end - 1) == '+') {
                end--;
            }
            text = custom.substring(0, end);
            pluses = custom.length() - end;
            if (text.isEmpty() && pluses == 0) {
                return ""; // a custom rank with no text shows nothing, the same as None
            }
        }
        int pickedMain = cfg.getOwnRankColor();
        int pickedPlus = cfg.getOwnRankPlusColor();
        char main = pickedMain != NameColor.NONE ? NameColor.codeFor(pickedMain) : p.main;
        // A custom rank's plus follows its colour unless picked; a Hypixel rank's plus keeps its own colour.
        char plus = pickedPlus != NameColor.NONE ? NameColor.codeFor(pickedPlus) : (p == Preset.CUSTOM ? main : p.plus);
        StringBuilder sb = new StringBuilder();
        sb.append('§').append(main).append('[').append(text);
        if (pluses > 0) {
            sb.append('§').append(plus).append("+".repeat(pluses)).append('§').append(main);
        }
        sb.append("] ");
        return sb.toString();
    }

    /** Whether {@code c} can be part of a Hypixel rank's own text ({@code MVP}, {@code YOUTUBE}, {@code +}). */
    static boolean isRankChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '+';
    }
}
