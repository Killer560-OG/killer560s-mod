package com.killer560.hub.translate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The languages Google Translate's public endpoint supports, display name -> ISO code. */
public final class TranslateLanguages {

    public record Lang(String name, String code) {
    }

    public static final List<Lang> ALL = build();

    public static List<Lang> search(String query) {
        String q = query.toLowerCase(Locale.US).trim();
        if (q.isEmpty()) {
            return ALL;
        }
        List<Lang> result = new ArrayList<>();
        for (Lang lang : ALL) {
            if (lang.name().toLowerCase(Locale.US).contains(q)) {
                result.add(lang);
            }
        }
        return result;
    }

    /** Exact match, then unique-prefix match, then a typo-tolerant fallback (e.g. "spansih" -> "Spanish"). */
    public static Optional<Lang> findByName(String name) {
        String trimmed = name.trim();
        for (Lang lang : ALL) {
            if (lang.name().equalsIgnoreCase(trimmed)) {
                return Optional.of(lang);
            }
        }
        List<Lang> prefixMatches = ALL.stream()
                .filter(l -> l.name().toLowerCase(Locale.US).startsWith(trimmed.toLowerCase(Locale.US)))
                .toList();
        if (prefixMatches.size() == 1) {
            return Optional.of(prefixMatches.get(0));
        }
        return fuzzyMatch(trimmed);
    }

    /** Picks the single closest language name within edit-distance 2, if there's a clear winner. */
    private static Optional<Lang> fuzzyMatch(String trimmed) {
        String needle = trimmed.toLowerCase(Locale.US);
        Lang best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestCount = 0;
        for (Lang lang : ALL) {
            int distance = levenshtein(needle, lang.name().toLowerCase(Locale.US));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = lang;
                bestCount = 1;
            } else if (distance == bestDistance) {
                bestCount++;
            }
        }
        return (best != null && bestDistance <= 2 && bestCount == 1) ? Optional.of(best) : Optional.empty();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    public static String nameForCode(String code) {
        for (Lang lang : ALL) {
            if (lang.code().equals(code)) {
                return lang.name();
            }
        }
        return code;
    }

    private TranslateLanguages() {
    }

    private static List<Lang> build() {
        List<Lang> list = new ArrayList<>();
        list.add(new Lang("Afrikaans", "af"));
        list.add(new Lang("Albanian", "sq"));
        list.add(new Lang("Amharic", "am"));
        list.add(new Lang("Arabic", "ar"));
        list.add(new Lang("Armenian", "hy"));
        list.add(new Lang("Azerbaijani", "az"));
        list.add(new Lang("Basque", "eu"));
        list.add(new Lang("Belarusian", "be"));
        list.add(new Lang("Bengali", "bn"));
        list.add(new Lang("Bosnian", "bs"));
        list.add(new Lang("Bulgarian", "bg"));
        list.add(new Lang("Catalan", "ca"));
        list.add(new Lang("Cebuano", "ceb"));
        list.add(new Lang("Chichewa", "ny"));
        list.add(new Lang("Chinese (Simplified)", "zh-CN"));
        list.add(new Lang("Chinese (Traditional)", "zh-TW"));
        list.add(new Lang("Corsican", "co"));
        list.add(new Lang("Croatian", "hr"));
        list.add(new Lang("Czech", "cs"));
        list.add(new Lang("Danish", "da"));
        list.add(new Lang("Dutch", "nl"));
        list.add(new Lang("English", "en"));
        list.add(new Lang("Esperanto", "eo"));
        list.add(new Lang("Estonian", "et"));
        list.add(new Lang("Filipino", "tl"));
        list.add(new Lang("Finnish", "fi"));
        list.add(new Lang("French", "fr"));
        list.add(new Lang("Frisian", "fy"));
        list.add(new Lang("Galician", "gl"));
        list.add(new Lang("Georgian", "ka"));
        list.add(new Lang("German", "de"));
        list.add(new Lang("Greek", "el"));
        list.add(new Lang("Gujarati", "gu"));
        list.add(new Lang("Haitian Creole", "ht"));
        list.add(new Lang("Hausa", "ha"));
        list.add(new Lang("Hawaiian", "haw"));
        list.add(new Lang("Hebrew", "iw"));
        list.add(new Lang("Hindi", "hi"));
        list.add(new Lang("Hmong", "hmn"));
        list.add(new Lang("Hungarian", "hu"));
        list.add(new Lang("Icelandic", "is"));
        list.add(new Lang("Igbo", "ig"));
        list.add(new Lang("Indonesian", "id"));
        list.add(new Lang("Irish", "ga"));
        list.add(new Lang("Italian", "it"));
        list.add(new Lang("Japanese", "ja"));
        list.add(new Lang("Javanese", "jw"));
        list.add(new Lang("Kannada", "kn"));
        list.add(new Lang("Kazakh", "kk"));
        list.add(new Lang("Khmer", "km"));
        list.add(new Lang("Kinyarwanda", "rw"));
        list.add(new Lang("Korean", "ko"));
        list.add(new Lang("Kurdish", "ku"));
        list.add(new Lang("Kyrgyz", "ky"));
        list.add(new Lang("Lao", "lo"));
        list.add(new Lang("Latin", "la"));
        list.add(new Lang("Latvian", "lv"));
        list.add(new Lang("Lithuanian", "lt"));
        list.add(new Lang("Luxembourgish", "lb"));
        list.add(new Lang("Macedonian", "mk"));
        list.add(new Lang("Malagasy", "mg"));
        list.add(new Lang("Malay", "ms"));
        list.add(new Lang("Malayalam", "ml"));
        list.add(new Lang("Maltese", "mt"));
        list.add(new Lang("Maori", "mi"));
        list.add(new Lang("Marathi", "mr"));
        list.add(new Lang("Mongolian", "mn"));
        list.add(new Lang("Myanmar (Burmese)", "my"));
        list.add(new Lang("Nepali", "ne"));
        list.add(new Lang("Norwegian", "no"));
        list.add(new Lang("Pashto", "ps"));
        list.add(new Lang("Persian", "fa"));
        list.add(new Lang("Polish", "pl"));
        list.add(new Lang("Portuguese", "pt"));
        list.add(new Lang("Punjabi", "pa"));
        list.add(new Lang("Romanian", "ro"));
        list.add(new Lang("Russian", "ru"));
        list.add(new Lang("Samoan", "sm"));
        list.add(new Lang("Scots Gaelic", "gd"));
        list.add(new Lang("Serbian", "sr"));
        list.add(new Lang("Sesotho", "st"));
        list.add(new Lang("Shona", "sn"));
        list.add(new Lang("Sindhi", "sd"));
        list.add(new Lang("Sinhala", "si"));
        list.add(new Lang("Slovak", "sk"));
        list.add(new Lang("Slovenian", "sl"));
        list.add(new Lang("Somali", "so"));
        list.add(new Lang("Spanish", "es"));
        list.add(new Lang("Sundanese", "su"));
        list.add(new Lang("Swahili", "sw"));
        list.add(new Lang("Swedish", "sv"));
        list.add(new Lang("Tajik", "tg"));
        list.add(new Lang("Tamil", "ta"));
        list.add(new Lang("Tatar", "tt"));
        list.add(new Lang("Telugu", "te"));
        list.add(new Lang("Thai", "th"));
        list.add(new Lang("Turkish", "tr"));
        list.add(new Lang("Turkmen", "tk"));
        list.add(new Lang("Ukrainian", "uk"));
        list.add(new Lang("Urdu", "ur"));
        list.add(new Lang("Uyghur", "ug"));
        list.add(new Lang("Uzbek", "uz"));
        list.add(new Lang("Vietnamese", "vi"));
        list.add(new Lang("Welsh", "cy"));
        list.add(new Lang("Xhosa", "xh"));
        list.add(new Lang("Yiddish", "yi"));
        list.add(new Lang("Yoruba", "yo"));
        list.add(new Lang("Zulu", "zu"));
        list.sort(Comparator.comparing(Lang::name));
        return List.copyOf(list);
    }
}
