package com.killer560.hub.autocorrect;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes common English typos in outgoing chat before it's sent - a VolcAddons-style "message
 * corrector", per killer560's request. Two mechanisms, both deliberately narrow rather than a general
 * fuzzy/dictionary spellchecker (which would happily "fix" usernames, Skyblock item names, and
 * slang like "gg"/"afk"/"hotm" into nonsense - far worse than leaving a genuine typo alone):
 * <ol>
 *   <li>{@link #TYPO_MAP} - a fixed table of specific known misspellings that are essentially
 *   never valid words on their own (e.g. "teh", "definately").</li>
 *   <li>{@link #fixDoubledLetter} - catches the "held a key too long" typo class (e.g. "wwork" -&gt;
 *   "work"), but ONLY when the typed word isn't already in {@link #DICTIONARY} AND collapsing
 *   exactly one doubled-letter run unambiguously produces a word that IS - so real double-letter
 *   words ("book", "letter", "committee", ...) are never touched, since the gate never opens for a
 *   word already recognized as valid.</li>
 * </ol>
 */
public final class AutoCorrectFeature {

    private static final Pattern WORD = Pattern.compile("[A-Za-z']+");
    private static final Map<String, String> TYPO_MAP = buildTypoMap();
    private static final Set<String> DICTIONARY = buildDictionary();

    public static String correct(String text) {
        Matcher m = WORD.matcher(text);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            String word = m.group();
            String fix = resolveFix(word.toLowerCase(Locale.US));
            m.appendReplacement(result, Matcher.quoteReplacement(fix == null ? word : matchCase(word, fix)));
        }
        m.appendTail(result);
        return result.toString();
    }

    private static String resolveFix(String lower) {
        String mapped = TYPO_MAP.get(lower);
        return mapped != null ? mapped : fixDoubledLetter(lower);
    }

    /**
     * Only fires when {@code lower} isn't already a real word. Tries collapsing each run of 2+
     * identical consecutive letters down to one, one run at a time; if exactly one distinct
     * collapse lands on a real word, returns it - if more than one run works (ambiguous) or none
     * do, returns null rather than guess.
     */
    private static String fixDoubledLetter(String lower) {
        if (lower.length() < 3 || DICTIONARY.contains(lower)) {
            return null;
        }
        String found = null;
        int i = 0;
        while (i < lower.length()) {
            int runStart = i;
            while (i + 1 < lower.length() && lower.charAt(i + 1) == lower.charAt(i)) {
                i++;
            }
            if (i > runStart) {
                String collapsed = lower.substring(0, runStart + 1) + lower.substring(i + 1);
                if (DICTIONARY.contains(collapsed)) {
                    if (found != null && !found.equals(collapsed)) {
                        return null;
                    }
                    found = collapsed;
                }
            }
            i++;
        }
        return found;
    }

    private static String matchCase(String original, String replacement) {
        if (original.length() > 1 && original.equals(original.toUpperCase(Locale.US))) {
            return replacement.toUpperCase(Locale.US);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    private static Map<String, String> buildTypoMap() {
        Map<String, String> m = new LinkedHashMap<>();

        // Common general English typos/misspellings - none of these are valid words on their own,
        // so replacing them can't misfire on real usage the way e.g. "form"->"from" would.
        put(m, "teh", "the");
        put(m, "hte", "the");
        put(m, "taht", "that");
        put(m, "wiht", "with");
        put(m, "recieve", "receive");
        put(m, "seperate", "separate");
        put(m, "definately", "definitely");
        put(m, "occured", "occurred");
        put(m, "untill", "until");
        put(m, "becuase", "because");
        put(m, "wierd", "weird");
        put(m, "thier", "their");
        put(m, "wich", "which");
        put(m, "freind", "friend");
        put(m, "beleive", "believe");
        put(m, "acheive", "achieve");
        put(m, "adress", "address");
        put(m, "agressive", "aggressive");
        put(m, "apparant", "apparent");
        put(m, "arguement", "argument");
        put(m, "calender", "calendar");
        put(m, "camoflage", "camouflage");
        put(m, "cemetary", "cemetery");
        put(m, "changable", "changeable");
        put(m, "collegue", "colleague");
        put(m, "comming", "coming");
        put(m, "commited", "committed");
        put(m, "concious", "conscious");
        put(m, "dilemna", "dilemma");
        put(m, "disapoint", "disappoint");
        put(m, "embarass", "embarrass");
        put(m, "enviroment", "environment");
        put(m, "existance", "existence");
        put(m, "familar", "familiar");
        put(m, "finaly", "finally");
        put(m, "foriegn", "foreign");
        put(m, "goverment", "government");
        put(m, "gaurd", "guard");
        put(m, "happend", "happened");
        put(m, "harrass", "harass");
        put(m, "humerous", "humorous");
        put(m, "immediatly", "immediately");
        put(m, "independant", "independent");
        put(m, "jewelery", "jewelry");
        put(m, "knowlege", "knowledge");
        put(m, "liason", "liaison");
        put(m, "libary", "library");
        put(m, "lisence", "license");
        put(m, "maintainance", "maintenance");
        put(m, "mispell", "misspell");
        put(m, "neccessary", "necessary");
        put(m, "noticable", "noticeable");
        put(m, "occassion", "occasion");
        put(m, "paralel", "parallel");
        put(m, "peice", "piece");
        put(m, "persistant", "persistent");
        put(m, "posession", "possession");
        put(m, "prefered", "preferred");
        put(m, "priviledge", "privilege");
        put(m, "probaly", "probably");
        put(m, "pronounciation", "pronunciation");
        put(m, "publically", "publicly");
        put(m, "questionaire", "questionnaire");
        put(m, "reccommend", "recommend");
        put(m, "refered", "referred");
        put(m, "relevent", "relevant");
        put(m, "religous", "religious");
        put(m, "remeber", "remember");
        put(m, "reccurring", "recurring");
        put(m, "similiar", "similar");
        put(m, "sincerly", "sincerely");
        put(m, "speach", "speech");
        put(m, "succesful", "successful");
        put(m, "suprise", "surprise");
        put(m, "temperture", "temperature");
        put(m, "tommorow", "tomorrow");
        put(m, "tounge", "tongue");
        put(m, "truely", "truly");
        put(m, "unfortunatly", "unfortunately");
        put(m, "writen", "written");
        put(m, "yeild", "yield");
        put(m, "alot", "a lot");

        // Language-name typos - directly useful for /translate and /language, though findByName()
        // there also has its own edit-distance fallback, so this list doesn't need to be exhaustive.
        put(m, "spansih", "Spanish");
        put(m, "spanihs", "Spanish");
        put(m, "jappanese", "Japanese");
        put(m, "japanse", "Japanese");
        put(m, "japanease", "Japanese");
        put(m, "chinesse", "Chinese");
        put(m, "chinees", "Chinese");
        put(m, "germsn", "German");
        put(m, "jerman", "German");
        put(m, "frnech", "French");
        put(m, "franch", "French");
        put(m, "italain", "Italian");
        put(m, "itallian", "Italian");
        put(m, "portugese", "Portuguese");
        put(m, "rusian", "Russian");
        put(m, "russain", "Russian");
        put(m, "koren", "Korean");
        put(m, "vietnamiese", "Vietnamese");
        put(m, "englsh", "English");
        put(m, "englihs", "English");

        // A few more common English typos, checked for and added per killer560's request - same
        // "essentially never a valid word on its own" bar as the rest of this table.
        put(m, "accomodate", "accommodate");
        put(m, "basicly", "basically");
        put(m, "buisness", "business");
        put(m, "excercise", "exercise");
        put(m, "grammer", "grammar");
        put(m, "greatful", "grateful");
        put(m, "occurance", "occurrence");
        put(m, "beggining", "beginning");
        put(m, "definitly", "definitely");
        put(m, "recieved", "received");
        put(m, "recieving", "receiving");
        put(m, "seperated", "separated");

        // Pulled from a real pass over killer560's own chat logs (2026-09-06) - genuine typos he
        // actually made, not slang/abbreviations (which are left alone on purpose - "arch",
        // "bers", "rq", "ss", etc. are intentional Skyblock shorthand, not misspellings).
        put(m, "dotn", "dont");
        put(m, "swearp", "swear");
        put(m, "awufl", "awful");
        put(m, "quites", "quiets");
        put(m, "gusy", "guys");
        put(m, "btww", "btw");
        put(m, "wawnan", "wanna");
        put(m, "yoru", "your");
        put(m, "cooldownw", "cooldown");
        put(m, "backwwards", "backwards");
        put(m, "timew", "time");
        put(m, "familier", "familiar");
        put(m, "embezelment", "embezzlement");
        put(m, "somethign", "something");
        put(m, "thea", "they");
        put(m, "baisically", "basically");
        put(m, "thjose", "those");
        put(m, "thte", "the");
        put(m, "tunnell", "tunnel");
        put(m, "woudl", "would");
        put(m, "swaping", "swapping");
        put(m, "blcko", "block");
        put(m, "tankj", "tank");
        put(m, "damnnnn", "damn");

        return Map.copyOf(m);
    }

    private static void put(Map<String, String> m, String typo, String correct) {
        m.put(typo, correct);
    }

    /**
     * Common-English-word gate for {@link #fixDoubledLetter}. Not exhaustive - it doesn't need to
     * be, since a word missing from here just means a real typo goes uncorrected (safe failure),
     * never that a correctly-spelled word gets mangled (that would need the word to be BOTH
     * missing here AND have some collapsed form that happens to also be listed here).
     */
    private static Set<String> buildDictionary() {
        String words = "a about above after again against all am an and any are aren't around as ask at away "
                + "back bad be because been before behind below best better between big block boat book boss "
                + "both bow bring build but buy by call came can can't cant cannot cast catch chat check chest "
                + "city claim clan class close club coin coins cold come coming cool could couldn't craft "
                + "crystal cut dad damage dark day days deal dear death did didn't die do does doesn't doing "
                + "done don't door down draw dream drink drive drop dungeon each ear early earn easy eat egg "
                + "eight either else end enemy enjoy enough even ever every everybody everyone everything "
                + "except eye face fair fall family far farm fast father feel feet fell felt few field fight "
                + "find fine finish fire first fish five floor fly follow food for form found four free "
                + "friend friends from front full fun game games gave get gift girl give go goal god going "
                + "gold gone good got grab great green grind group grow guard guild guy had hand hands happy "
                + "hard has hat hate have haven't he head heal hear heard heart heavy hello help her here hi "
                + "high him his hit hold home hope host hot hour house how huge hunt hurt idea if in inside "
                + "into invite is island isn't it its itself join joined joke jump just keep kept key kid "
                + "kill killed kind king knew know known lag land large last late later laugh lead learn "
                + "least leave left less let letter level life light like line list little live lol long "
                + "look looking lose loss lost lot love low lucky made magic make man many map mark mate "
                + "matter maybe me mean meet member men message met might mine minute miss mob more morning "
                + "most move much must my myself name near need never new next nice night no none not note "
                + "nothing now number of off ok okay old on once one only onto open or order other our out "
                + "outside over own party pass past pay people pet pick place plan play played player please "
                + "point pool poor power price probably profile pull push put quest queue quick quit quite "
                + "rain raise ran rare rate read ready real really reason red remember rest right ring rise "
                + "room round run runs said sale same save saw say says school sea see seed seem seen sell "
                + "sent set seven several shall she ship shop short should show shut side sign since sing sir "
                + "sit six size skill sky slayer sleep slow small so some someone something sometimes son "
                + "soon sorry sound speak special spell spend stand star start stat state stay still stop "
                + "store story strong such sure system take talk team tell temp ten test than thank thanks "
                + "that that's the their them then there these they thing think this those though thought "
                + "three through time to today told too took top total town trade tree trust try turn two "
                + "type under up upon us use used using vote wait walk want war warm was wasn't watch water "
                + "way we wear week weird well went were weren't what what's when where which while white who "
                + "who's whole whose why will win wind wish with within without won won't wood word work "
                + "world worse would wouldn't write wrong yard year yell yes yet you you're your yours "
                + "yourself";
        Set<String> dictionary = new HashSet<>(Arrays.asList(words.split("\\s+")));

        return dictionary;
    }

    private AutoCorrectFeature() {
    }
}
