package com.killer560.hub.translate;

import java.util.Locale;
import java.util.Map;

/**
 * Turns chat typed in Latin letters back into the language's own script, so the translator can read it. Tables are the
 * common informal chat spellings (4 = ch, 6 = sh, q = ya on a Bulgarian phonetic keyboard) and are deliberately small.
 */
final class RomanizedText {
    private static final String VOWELS = "aeiouy";
    private static final Map<String, String> SLAVIC = Map.ofEntries(
            Map.entry("sht", "щ"), Map.entry("sch", "щ"), Map.entry("sh", "ш"), Map.entry("ch", "ч"), Map.entry("zh", "ж"),
            Map.entry("ts", "ц"), Map.entry("yu", "ю"), Map.entry("ya", "я"), Map.entry("a", "а"), Map.entry("b", "б"),
            Map.entry("v", "в"), Map.entry("w", "в"), Map.entry("g", "г"), Map.entry("d", "д"), Map.entry("e", "е"),
            Map.entry("z", "з"), Map.entry("i", "и"), Map.entry("j", "й"), Map.entry("k", "к"), Map.entry("l", "л"),
            Map.entry("m", "м"), Map.entry("n", "н"), Map.entry("o", "о"), Map.entry("p", "п"), Map.entry("r", "р"),
            Map.entry("s", "с"), Map.entry("t", "т"), Map.entry("u", "у"), Map.entry("f", "ф"), Map.entry("h", "х"),
            Map.entry("c", "ц"), Map.entry("y", "й"), Map.entry("q", "я"), Map.entry("x", "ь"));
    private static final Map<String, String> BG = Map.of("6", "ш", "4", "ч", "q", "я", "y", "ъ", "x", "ь", "c", "ц");
    private static final Map<String, String> RU = Map.of("shch", "щ", "kh", "х", "yo", "ё", "6", "ш", "4", "ч",
            "y", "ы", "e", "е", "j", "й");
    private static final Map<String, String> UK = Map.of("shch", "щ", "kh", "х", "ye", "є", "yi", "ї", "y", "и",
            "i", "і", "6", "ш", "4", "ч", "h", "г", "g", "ґ");
    private static final Map<String, String> SR = Map.of("lj", "љ", "nj", "њ", "dj", "ђ", "dz", "џ", "j", "ј",
            "gj", "ѓ", "kj", "ќ", "6", "ш", "4", "ч");
    private static final Map<String, String> EL = Map.ofEntries(
            Map.entry("th", "θ"), Map.entry("ch", "χ"), Map.entry("ps", "ψ"), Map.entry("a", "α"), Map.entry("b", "β"),
            Map.entry("v", "β"), Map.entry("g", "γ"), Map.entry("d", "δ"), Map.entry("e", "ε"), Map.entry("z", "ζ"),
            Map.entry("h", "η"), Map.entry("i", "ι"), Map.entry("k", "κ"), Map.entry("l", "λ"), Map.entry("m", "μ"),
            Map.entry("n", "ν"), Map.entry("x", "ξ"), Map.entry("o", "ο"), Map.entry("p", "π"), Map.entry("r", "ρ"),
            Map.entry("s", "σ"), Map.entry("t", "τ"), Map.entry("y", "υ"), Map.entry("f", "φ"), Map.entry("w", "ω"),
            Map.entry("u", "υ"), Map.entry("j", "ι"));

    /** True when the language's own script is not Latin and a table exists for it. */
    static boolean supports(String lang) {
        return lang != null && switch (base(lang)) {
            case "bg", "ru", "uk", "sr", "mk", "el" -> true;
            default -> false;
        };
    }

    /** The text in the language's script, or null when there is no table or nothing changed. */
    static String toNative(String text, String lang) {
        if (!supports(lang)) {
            return null;
        }
        String l = base(lang);
        Map<String, String> over = switch (l) {
            case "bg" -> BG;
            case "ru" -> RU;
            case "uk" -> UK;
            case "el" -> Map.of();
            default -> SR;
        };
        Map<String, String> base = "el".equals(l) ? EL : SLAVIC;
        String s = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length();) {
            String piece = null;
            int len = 0;
            for (int n = 4; n >= 1 && piece == null; n--) {
                if (i + n > s.length()) {
                    continue;
                }
                String key = s.substring(i, i + n);
                piece = over.get(key);
                if (piece == null) {
                    piece = base.get(key);
                }
                len = n;
            }
            char ch = s.charAt(i);
            if (Character.isDigit(ch) && !(i > 0 && Character.isLetter(s.charAt(i - 1)) || i + 1 < s.length() && Character.isLetter(s.charAt(i + 1)))) {
                piece = null; // a lone digit is a number, not ч or ш
            }
            if ("bg".equals(l) && ch == 'u') {
                // u between two consonants is the schwa (purviq -> първия); next to a vowel or alone it is у
                boolean schwa = i > 0 && isConsonant(s.charAt(i - 1)) && i + 1 < s.length() && isConsonant(s.charAt(i + 1));
                piece = schwa ? "ъ" : "у";
                len = 1;
            }
            if (piece == null) {
                out.append(ch);
                i++;
            } else {
                out.append(piece);
                i += len;
            }
        }
        String result = out.toString();
        return result.equals(s) ? null : result;
    }

    private static boolean isConsonant(char c) {
        return c >= 'a' && c <= 'z' && VOWELS.indexOf(c) < 0;
    }

    private static String base(String lang) {
        int dash = lang.indexOf('-');
        return (dash > 0 ? lang.substring(0, dash) : lang).toLowerCase(Locale.ROOT);
    }

    private RomanizedText() {
    }
}
