package com.killer560.hub.autocorrect;

import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

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
        return collapseDoubledRun(lower, DICTIONARY);
    }

    /** Shortest word the collapse is allowed to produce. killer560 (2026-09-20): "make it so i can type
     *  hee that currently gets corrected to he." Every correction into a one- or two-letter word is an
     *  intentionally stretched chat word, never a typo - "hee"/"bee"->"he"/"be", "noo"->"no", "sooo"->"so",
     *  "tooo"->"to", "okk"->"ok" - so the whole class is refused rather than patched word by word. */
    private static final int MIN_COLLAPSE_RESULT_LENGTH = 3;

    /** The collapse half of {@link #fixDoubledLetter}, against any set of valid words - shared with
     *  command-name correction ({@link #correctCommand}) so both use exactly the same rule.
     *  <p>
     *  A run that ends the word is never collapsed (killer560's "hee" report): a stuck/repeated key
     *  lands anywhere in a word ("wwork", "leetter"), but a doubled LAST letter is how people stretch a
     *  word on purpose ("hee", "yess", "heyy", "okk", "lolll", "nahh"), so word-final runs are left alone. */
    private static String collapseDoubledRun(String lower, Set<String> valid) {
        String found = null;
        int i = 0;
        while (i < lower.length()) {
            int runStart = i;
            while (i + 1 < lower.length() && lower.charAt(i + 1) == lower.charAt(i)) {
                i++;
            }
            boolean runEndsWord = i == lower.length() - 1;
            if (i > runStart && !runEndsWord) {
                String collapsed = lower.substring(0, runStart + 1) + lower.substring(i + 1);
                if (collapsed.length() >= MIN_COLLAPSE_RESULT_LENGTH && valid.contains(collapsed)) {
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

    // ------------------------------------------------------------------------------------------
    // Command-name correction (2026-09-15 roadmap: "Extend Auto Correct to also fix typos in
    // commands"), behind AutoCorrectConfig#isCorrectCommands (default OFF). Hooked from
    // experiments.mixin.AutoCorrectCommandMixin, a @ModifyArg on the sendCommand(String) call inside
    // ChatScreen.handleChatInput (verified via javap against the 26.1.2 merged jar: handleChatInput
    // normalizes, adds to history, then calls ClientPacketListener.sendCommand(message.substring(1))
    // for a leading "/") - so only commands the player actually TYPES are ever touched, never ones
    // this mod or other mods send programmatically.
    // ------------------------------------------------------------------------------------------

    /** Common Hypixel commands the client's brigadier tree might not list (Hypixel's server tree
     *  isn't guaranteed complete). Only names confirmed in real use - an extra wrong name here would
     *  let a real command get "corrected" into it, so this deliberately stays small. */
    private static final Set<String> HYPIXEL_COMMANDS = Set.of(
            "warp", "pc", "party", "p", "pl", "visit", "ah", "bz", "bazaar", "pets", "wardrobe", "sbmenu",
            "hub", "dh", "play", "is", "island", "ec", "enderchest", "storage", "craft", "recipe",
            "viewrecipe", "collection", "skills", "hotm", "bestiary", "sacks", "trades", "trade", "garden",
            "lobby", "l", "msg", "w", "r", "tell", "reply", "ac", "gc", "oc", "cc", "g", "guild", "f",
            "friend", "fl", "boop", "locraw", "whereami", "calendar", "equipment", "accessories",
            "potionbag", "quiver", "fishingbag", "joininstance", "rejoin", "chat");

    /** Minimum typed command-name length before the one-edit match is even attempted - shorter names
     *  ("pv", "pw", "ahs") are too close to too many real commands for a single edit to mean anything.
     *  The doubled-letter collapse (same rule as chat) still applies from length 3. */
    private static final int MIN_EDIT_CORRECTION_LENGTH = 4;

    /**
     * Entry point for the command-send mixin: returns {@code command} (no leading "/") with only its
     * NAME corrected when Correct Commands is on and a single unambiguous fix exists - arguments are
     * never touched. Posts an orange local-only note ("/wardorbe -> /wardrobe") whenever it changes.
     */
    public static String correctOutgoingCommand(String command) {
        if (command == null || !AutoCorrectConfig.getInstance().isCorrectCommands()) {
            return command;
        }
        try {
            String corrected = correctCommand(command, knownCommandNames());
            if (!corrected.equals(command)) {
                String oldName = commandName(command);
                String newName = commandName(corrected);
                ModChat.send("Auto Correct",
                        ModChat.value("/" + oldName),
                        ModChat.text(" -> "),
                        ModChat.value("/" + newName));
            }
            return corrected;
        } catch (Exception e) {
            return command;
        }
    }

    /** Pure logic (no Minecraft access) - see {@link #correctOutgoingCommand}. */
    static String correctCommand(String command, Set<String> known) {
        if (known.isEmpty() || command.isBlank()) {
            return command;
        }
        String name = commandName(command);
        String rest = command.substring(name.length());
        if (name.isEmpty() || name.indexOf(':') >= 0) {
            return command;
        }
        String lower = name.toLowerCase(Locale.US);
        if (known.contains(name) || known.contains(lower)) {
            return command;
        }
        String fix = lower.length() >= 3 ? collapseDoubledRun(lower, known) : null;
        if (fix == null && lower.length() >= MIN_EDIT_CORRECTION_LENGTH) {
            fix = uniqueOneEditMatch(lower, known);
        }
        return fix == null ? command : fix + rest;
    }

    private static String commandName(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    /** @return the only known name exactly one edit away (insert/delete/substitute/swap-adjacent) from
     *  {@code lower}, or null if there are none or more than one - never guesses between candidates. */
    private static String uniqueOneEditMatch(String lower, Set<String> known) {
        String found = null;
        for (String candidate : known) {
            if (candidate.length() < 3 || Math.abs(candidate.length() - lower.length()) > 1) {
                continue;
            }
            if (isOneEditAway(lower, candidate)) {
                if (found != null && !found.equals(candidate)) {
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }

    private static boolean isOneEditAway(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        int la = a.length();
        int lb = b.length();
        if (la == lb) {
            int first = -1;
            int diffs = 0;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) != b.charAt(i)) {
                    if (diffs == 0) first = i;
                    diffs++;
                }
            }
            if (diffs == 1) {
                return true;
            }
            // Adjacent transposition, e.g. "wardorbe" -> "wardrobe".
            return diffs == 2 && first + 1 < la
                    && a.charAt(first) == b.charAt(first + 1) && a.charAt(first + 1) == b.charAt(first);
        }
        String shorter = la < lb ? a : b;
        String longer = la < lb ? b : a;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < shorter.length() && j < longer.length()) {
            if (shorter.charAt(i) == longer.charAt(j)) {
                i++;
                j++;
            } else {
                if (skipped) return false;
                skipped = true;
                j++;
            }
        }
        return true;
    }

    /** Root literal names of the client's current command tree (server commands plus Fabric client
     *  commands, which Fabric merges into the same dispatcher) together with {@link #HYPIXEL_COMMANDS}.
     *  Empty until a real command tree has been received, so nothing is ever corrected blind. */
    private static Set<String> knownCommandNames() {
        ClientPacketListener connection =
                Minecraft.getInstance().getConnection();
        if (connection == null) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (var node : connection.getCommands().getRoot().getChildren()) {
            names.add(node.getName().toLowerCase(Locale.US));
        }
        if (names.isEmpty()) {
            return Set.of();
        }
        names.addAll(HYPIXEL_COMMANDS);
        return names;
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
        // Real double-letter words that were missing above, so the doubled-letter collapse can't "fix"
        // them into something else. Found by auditing the table for this failure mode after killer560's
        // "hee" report (2026-09-20) - "loot" -> "lot" was the other live one, which matters in Skyblock.
        // Chat interjections ("hee", "aww", "hmm", ...) are listed as words for the same reason.
        String doubles = "loot loots boot boots root roots moon moons soon tool tools wool spoon spoons "
                + "sweet speed street teeth tooth wheel wheels steel cheese coffee cookie cookies dinner "
                + "summer winner runner hammer hammers arrow arrows mirror error bottle middle rabbit "
                + "kitten funny bunny penny hurry carry worry pretty grass glass class press dress "
                + "cross boss bless bee bees hee hmm aww brb gg ggs ok okay yeah nah bruh";
        Set<String> dictionary = new HashSet<>(Arrays.asList(words.split("\\s+")));
        dictionary.addAll(Arrays.asList(doubles.split("\\s+")));

        return dictionary;
    }

    private AutoCorrectFeature() {
    }
}
