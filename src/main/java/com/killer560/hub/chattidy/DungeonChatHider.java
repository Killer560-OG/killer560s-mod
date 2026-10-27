package com.killer560.hub.chattidy;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat Hider's dungeon pack (2026-10-25): six families of dungeon chat, each its own switch in {@link ChatTidyConfig},
 * hidden at display time from {@link ChatTidy#shouldHide} and only while in a dungeon. Every line shape was checked
 * against his own 26.1.2 (Dungeons) chat log (2026-09 to 2026-10-25) and against SkyHanni {@code DungeonChatFilter} /
 * {@code DungeonBossMessages} and Skyblocker {@code DungeonBreakerFilter}. Every pattern matches the WHOLE plain line,
 * and a name is {@code [A-Za-z0-9_]{1,16}}, so a player's chat ("Party > [MVP+] Eve: [BOSS] Maxor: ...") never matches:
 * every player channel carries a prefix with a colon none of these lines can start with.
 */
public final class DungeonChatHider {

    public enum Family {
        BOSS, MORT, BLESSINGS, PICKUPS, ABILITY, BREAKER
    }

    private static final String NAME = "[A-Za-z0-9_]{1,16}";
    private static final String RANK = "(?:\\[[A-Z+]{1,10}\\] )?";
    private static final String BLESSING = "Blessing of [A-Z][a-z]{1,10}";
    private static final String TIME = "(?: \\((?:\\d{1,2}m )?\\d{1,2}s\\))?";
    private static final String ABILITY_NAME = "[A-Z][A-Za-z' ]{1,30}";

    /** Dungeon bosses only ("[BOSS] Bal" is the Crimson Isle and stays). F5's fake Livids and Endermen carry a word. */
    static final Pattern BOSS = Pattern.compile("^\\[BOSS\\] (?:The Watcher|Bonzo|Scarf|The Professor|Thorn|Sadan|Maxor"
            + "|Storm|Goldor|Necron|Wither King|(?:[A-Z][a-z]{1,10} )?Livid|[A-Z][a-z]{1,10} Enderman): .{1,250}$");
    /** Kept (SkyHanni keeps it too): the Watcher's "blood is done" line. */
    static final String WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass.";

    static final Pattern MORT = Pattern.compile("^\\[NPC\\] Mort: .{1,200}$");

    static final Pattern[] BLESSINGS = {
            Pattern.compile("^DUNGEON BUFF! (?:You|" + NAME + ") found a " + BLESSING + "(?: [IVX]{1,4})?!" + TIME + "$"),
            Pattern.compile("^DUNGEON BUFF! A " + BLESSING + "(?: [IVX]{1,4})? was found!" + TIME + "$"),
            Pattern.compile("^A " + BLESSING + " was picked up!$"),
            Pattern.compile("^" + RANK + NAME + " has obtained " + BLESSING + "!$"),
            // The stat line(s) under a blessing, indented five spaces: "     Granted you +1.18x HP and ..."
            Pattern.compile("^ {5}(?:Also granted|Granted|Grants) you \\+.{1,160}\\.$"),
    };

    /** "[MVP+] Eve has obtained Wither Key!" / "Superboom TNT x2!" - judged against your own name by the caller. */
    static final Pattern OBTAINED = Pattern.compile("^" + RANK + "(" + NAME + ") has obtained ([A-Z][A-Za-z' ]{1,40}?)(?: x\\d{1,3})?!$");
    static final Pattern KEY_PICKED_UP = Pattern.compile("^A (?:Wither|Blood) Key was picked up!$");

    static final Pattern[] ABILITY = {
            Pattern.compile("^This ability is on cooldown for \\d{1,3}s\\.$"),
            Pattern.compile("^" + ABILITY_NAME + " is now available!$"),
            Pattern.compile("^" + ABILITY_NAME + " is ready to use! Press DROP to activate it!$"),
            Pattern.compile("^Used " + ABILITY_NAME + "!$"),
            Pattern.compile("^Your [A-Z][a-z]{1,10} ULTIMATE " + ABILITY_NAME + " is now available!$"),
            Pattern.compile("^Creeper Veil (?:Activated|De-activated)!(?: \\(Expired\\))?$"),
            Pattern.compile("^Not enough vitality! Creeper Veil De-activated!$"),
            Pattern.compile("^◕ " + NAME + " picked up your [A-Z][A-Za-z ]{1,24} Orb!$"),
            Pattern.compile("^◕ You picked up an? [A-Z][A-Za-z ]{1,24} Orb from " + NAME + " healing you for .{1,80} seconds\\.$"),
            Pattern.compile("^" + NAME + " formed a tether with you!$"),
            Pattern.compile("^You formed a tether with " + NAME + "!$"),
    };

    /** Skyblocker {@code DungeonBreakerFilter}, verbatim. */
    static final Pattern BREAKER = Pattern.compile("^(?:A mystical force prevents you (?:from digging that block|digging in this "
            + "room|digging there)|You don't have enough charges to break this block right now)!$");

    private static final int MAX_LINE = 300;

    private static final Map<Family, Long> HIDDEN = new EnumMap<>(Family.class);

    private DungeonChatHider() {
    }

    /** The family {@code plain} belongs to, or null. {@code self} is your own name (your own pickups are not "others'"). */
    public static Family family(String plain, String self) {
        if (plain == null || plain.isEmpty() || plain.length() > MAX_LINE) {
            return null;
        }
        char first = plain.charAt(0);
        if (first == '[') {
            if (plain.startsWith("[BOSS] ")) {
                return !WATCHER_DONE.equals(plain) && BOSS.matcher(plain).matches() ? Family.BOSS : null;
            }
            if (plain.startsWith("[NPC] Mort: ")) {
                return MORT.matcher(plain).matches() ? Family.MORT : null;
            }
        }
        if (BREAKER.matcher(plain).matches()) {
            return Family.BREAKER;
        }
        if (plain.contains("Blessing of ") || plain.startsWith("     ")) {
            for (Pattern p : BLESSINGS) {
                if (p.matcher(plain).matches()) {
                    return Family.BLESSINGS;
                }
            }
        }
        if (plain.contains(" has obtained ")) {
            Matcher m = OBTAINED.matcher(plain);
            if (m.matches() && !m.group(2).startsWith("Blessing of ") && !m.group(1).equals(self)) {
                return Family.PICKUPS;
            }
        }
        if (KEY_PICKED_UP.matcher(plain).matches()) {
            return Family.PICKUPS;
        }
        for (Pattern p : ABILITY) {
            if (p.matcher(plain).matches()) {
                return Family.ABILITY;
            }
        }
        return null;
    }

    /** Whether that family's switch is on (gated by Chat Hider's master switch and Skyblock Only). */
    public static boolean on(Family f, ChatTidyConfig cfg) {
        return switch (f) {
            case BOSS -> cfg.isHideBossDialogue();
            case MORT -> cfg.isHideMort();
            case BLESSINGS -> cfg.isHideBlessings();
            case PICKUPS -> cfg.isHideOthersPickups();
            case ABILITY -> cfg.isHideAbilitySpam();
            case BREAKER -> cfg.isHideDungeonBreaker();
        };
    }

    /** Any family switched on (so a line is only matched when something could hide it). */
    public static boolean anyOn(ChatTidyConfig cfg) {
        return cfg.isHideBossDialogue() || cfg.isHideMort() || cfg.isHideBlessings() || cfg.isHideOthersPickups()
                || cfg.isHideAbilitySpam() || cfg.isHideDungeonBreaker();
    }

    static void counted(Family f) {
        synchronized (HIDDEN) {
            HIDDEN.merge(f, 1L, Long::sum);
        }
    }

    /** Test hook: lines hidden per family since start. */
    public static long testHidden(String family) {
        synchronized (HIDDEN) {
            return HIDDEN.getOrDefault(Family.valueOf(family), 0L);
        }
    }

    /** Test hook: the family a line would fall in (as {@link #family}), or "" for none. */
    public static String testFamily(String plain, String self) {
        Family f = family(plain, self);
        return f == null ? "" : f.name();
    }
}
