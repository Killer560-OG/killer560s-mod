package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How long class picks and Leap Order pins last (killer560, 2026-10-12: "have them save until I do a run without that
 * person. Then reset that individual back to default. If a new person joins and everyone else stays the same put the new
 * person in that new spot. If more than 2 people leave then make it reset everything.").
 * <p>
 * At each dungeon run START the party is compared with the previous run's ({@link LeapMenuConfig#getLastRunParty},
 * persisted):
 * <ol>
 *   <li>a class override or pin on anyone NOT in this run is cleared - they are back to their tab-list class and the
 *       Default Order. Everyone in the run keeps theirs.</li>
 *   <li>teammates who joined take the leap spots the departed held in the previous run's layout (for the class you are
 *       playing): the vacated spots in spot order (top-left, top-right, bottom-left, bottom-right), given to the
 *       newcomers in leap-menu order (the order Hypixel's own Spirit Leap menu shows them, else the tab list's). A
 *       newcomer is pinned there. Extra newcomers follow the Default Order.</li>
 *   <li>more than two left: every class override and every pin is cleared, so the Default Order applies fresh.</li>
 * </ol>
 * <b>One-for-one swap</b> (killer560, 2026-10-23: "if 1 person leaves a party and a new person joins give them that old
 * person's role, assuming no other classes change. If other classes change, have a popup saying the leap order was reset
 * and that the owner needs to update it"). The class the mod had for each teammate at the last run's start (their pick,
 * else the tab list's; a pick set or cleared during that run counts) is kept in {@link LeapMenuConfig#getLastRunClasses}.
 * A run start is a <b>swap</b> when all of these hold:
 * <ul>
 *   <li>there was a previous run, exactly one of its teammates is missing and exactly one teammate is new;</li>
 *   <li>the leaver's class is known: their pick if they still have one, else the class recorded for them last run;</li>
 *   <li>no other class changed: for every teammate in both runs, the class now ({@link PartyTracker#classOf}) equals the
 *       one recorded last run. A teammate counts as unchanged when either class is unknown (not recorded, or the tab list
 *       has not shown it), and when their pick was set by hand outside a run since the last start (you set it for this
 *       run, so it is not a change you need telling about).</li>
 * </ul>
 * On a swap, rules 1 and 2 run as above and the newcomer is also given the leaver's class as a pick, so both the leap spot
 * and the class follow (a pick you already gave the newcomer yourself stays). Any other run whose party differs from the
 * previous one (more than one left or joined, someone left with nobody new, someone joined with nobody gone, a stayer's
 * class changed, or the leaver's class is unknown) runs rules 1-3 exactly as above and shows the <b>reset notice</b>: a
 * title on screen and a chat line saying the Leap Order was reset and needs updating with /leaporder. The same party as
 * last run, and a first run, show nothing. The notice never waits and never holds the run start up.
 * <p>
 * A pick or pin set by hand OUTSIDE a run since the last run start (in the Hub, or in the dungeon before Mort's line) was
 * set for the coming run, so for anyone in it rules 2 and 3 leave it alone: rule 3 keeps it, rule 2 places no newcomer
 * who is already pinned and into no spot someone was pinned into (killer560, 2026-10-22: picks set in the Hub were reset
 * when the run started, because the Hub party was compared with the run before it). Rule 1 still clears it for anyone
 * who did not come.
 * <b>Run start</b> is Mort's opening line, the same anchored pattern the Split Timers use, so nobody's chat can fake it
 * (a player's line starts with their rank or name, a party line with "Party &gt;"); the party is read from the dungeon tab
 * list a second later, once the tab list has been read at least twice. Never in the room sim or the {@code /killer560 sim}
 * override: those are not runs with a party.
 * <p>
 * <b>leaporder-1102</b> (killer560, 2026-11-02: "someone leaves then a new person joins ... they will get moved to the wrong
 * position on the menu and swapped with someone else"; Reset All fixed it). Three causes, all fixed here:
 * <ul>
 *   <li>The departed's spot was found by laying the OLD party out with TODAY's classes, where the departed has none (gone
 *       from the tab list, which is cleared between runs) - not the layout the last run showed. It is now laid out with the
 *       class each had last run ({@link LeapMenuConfig#getLastRunClasses}) and for the class you played then
 *       ({@link LeapMenuConfig#getLastRunPlaying}).</li>
 *   <li>Rule 2 pinned newcomers into the vacated spots even when a stayer had changed class or you play another class,
 *       when those spots belong to another layout; the pin then held the newcomer in the wrong corner and pushed a stayer
 *       out of theirs. Now a newcomer is placed only while the old layout holds; otherwise the Default Order places them.</li>
 *   <li>Class changes were compared on the class WITH picks, so a teammate with a pick who switched class never read as
 *       changed. The tab list's own class is now kept per run ({@link LeapMenuConfig#getLastRunTabClasses}), and a stayer
 *       the tab list shows on another class loses their pick and pins (not those set before the run), with the notice.</li>
 * </ul>
 */
public final class LeapRunMemory {

    private static final Logger LOGGER = ModLog.get("killer560smod-leapmenu");

    /** Odin SplitsManager.MORT_REGEX - the run's first line (Split Timers' RUN_START). */
    private static final Pattern RUN_START = Pattern.compile(
            "^\\[NPC\\] Mort: (?:Here, I found this map when I first entered the dungeon\\.|Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!)$");
    /** Ticks between Mort's line and reading the party: the tab list is re-read every 10 ticks. */
    private static final int SETTLE_TICKS = 20;

    private static int pendingTicks = -1;
    /** The party read at the last settle, and how many reads this run start has taken. A snapshot that is still changing
     *  (the tab list fills in after Mort's line) must not count anyone as having left: the rules below only act once two
     *  reads agree (killer560, 2026-10-20: picks reset after a Hub setup, when the party was read before everyone was in). */
    private static List<String> lastRead;
    private static int reads;
    /** Reads taken at most before a run's party is taken as it is (about 10 s of tab-list refreshes). */
    private static final int MAX_READS = 20;
    /** Ticks between the tab-list re-reads while the party is still changing. */
    private static final int REREAD_TICKS = 10;
    private static volatile String lastOutcome = "";
    private static volatile int runsProcessed = 0;
    /** From a processed run start until the dungeon is left: a pick or pin set meanwhile belongs to this run, one set
     *  outside it (the Hub, or the dungeon before Mort's line) to the next ({@link LeapMenuConfig#wasSetBeforeRun}). */
    private static boolean inRun = false;

    private LeapRunMemory() {
    }

    public static void register() {
        ChatObserver.subscribe(LeapRunMemory::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LeapRunMemory", client -> tick()));
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);
        if (plain != null && plain.contains("[NPC] Mort: ") && RUN_START.matcher(plain.trim()).matches()) {
            pendingTicks = 0;
            lastRead = null;
            reads = 0;
            midRunSeen = null;
        }
    }

    /** True from a processed run start until the dungeon is left. */
    public static boolean inRun() {
        return inRun;
    }

    private static void tick() {
        // A run ends with its dungeon: leaving it, or a new world (a requeue straight into the next dungeon), so the next
        // dungeon's party is never read as a mid-run change of this one.
        if (inRun && (!DungeonState.isInDungeon() || Minecraft.getInstance().level != runLevel)) {
            inRun = false;
            midRunSeen = null;
        }
        if (pendingTicks < 0) {
            if (inRun && ++midRunTicks >= REREAD_TICKS) {
                midRunTicks = 0;
                watchMidRun();
            }
            return;
        }
        if (++pendingTicks < SETTLE_TICKS) {
            return;
        }
        pendingTicks = -1;
        if (!DungeonState.isInDungeon() || DungeonState.isRoomSim() || DungeonState.isSimOverrideActive()) {
            lastOutcome = "skipped: not a real dungeon run";
            lastRead = null;
            reads = 0;
            return;
        }
        List<String> party = PartyTracker.teammatesInLeapOrder();
        if (party.isEmpty()) {
            lastOutcome = "skipped: no teammates known";
            return;
        }
        reads++;
        boolean stable = party.equals(lastRead);
        lastRead = party;
        if (!stable && reads < MAX_READS) {
            // Still changing: read again shortly, and decide only on a snapshot two reads agree on.
            pendingTicks = SETTLE_TICKS - REREAD_TICKS;
            return;
        }
        if (!stable) {
            lastOutcome = "party never settled after " + reads + " reads - no picks changed this run";
            lastRead = null;
            reads = 0;
            return;
        }
        lastRead = null;
        reads = 0;
        onRunStart(party, LeapMenuFeature.playingClass());
    }

    /** The party the mid-run watch last read, and ticks since it last read (leaporder-1102). */
    private static List<String> midRunSeen;
    /** The world the current run started in (identity only). */
    private static Object runLevel;
    private static int midRunTicks = 0;
    private static volatile String lastMidRunOutcome = "";

    /** What the last mid-run party change did (testkit and the log). */
    public static String lastMidRunOutcome() {
        return lastMidRunOutcome;
    }

    /** During a run, a teammate who leaves and one who joins (the tab list changes, no Mort line): once two reads agree, the
     *  newcomer takes the leaver's spot in the layout the run had, and the run's party becomes this one, so the next run
     *  start compares with who actually finished it (leaporder-1102: the Default Order alone gave an Archer newcomer the
     *  top-left a Mage stayer had held, pushing the Mage into the leaver's spot). */
    private static void watchMidRun() {
        if (DungeonState.isRoomSim() || DungeonState.isSimOverrideActive()) {
            return;
        }
        List<String> party = PartyTracker.teammatesInLeapOrder();
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        List<String> runParty = cfg.getLastRunParty();
        if (party.isEmpty() || runParty.isEmpty() || sameMembers(party, runParty)) {
            midRunSeen = null;
            return;
        }
        boolean stable = midRunSeen != null && sameMembers(party, midRunSeen);
        midRunSeen = party;
        if (!stable) {
            return;
        }
        midRunSeen = null;
        onMidRunChange(party, LeapMenuFeature.playingClass());
    }

    /** Applies a mid-run party change (see {@link #watchMidRun}). Public for the testkit. */
    public static void onMidRunChange(List<String> party, DungeonClass playing) {
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        List<String> runParty = cfg.getLastRunParty();
        List<String> departed = minus(runParty, party);
        List<String> joined = minus(party, runParty);
        Map<String, DungeonClass> lastClasses = cfg.getLastRunClasses();
        Map<String, DungeonClass> lastTab = cfg.getLastRunTabClasses();
        boolean changed = false;
        for (String n : party) {
            if (!containsIgnoreCase(runParty, n)) {
                continue;
            }
            DungeonClass was = lastTab.get(n.toLowerCase(Locale.ROOT));
            DungeonClass now = PartyTracker.detectedClassOf(n);
            if (was != null && now != null && was != now) {
                changed = true;
            }
        }
        List<String> placed = new ArrayList<>();
        String took = "";
        if (!joined.isEmpty() && !departed.isEmpty() && !changed && playing != null) {
            String[] before = LeapMenuFeature.layout(runParty, cfg.getClassOrder(playing), cfg.getDefaultOrder(),
                    lastClassOf(lastClasses));
            List<String> pinsNow = cfg.getClassOrder(playing);
            List<Integer> free = new ArrayList<>();
            for (int spot = 0; spot < 4; spot++) {
                if (before[spot] != null && containsIgnoreCase(departed, before[spot]) && pinsNow.get(spot).isEmpty()) {
                    free.add(spot);
                }
            }
            List<String> placeable = joined.stream().filter(n -> !cfg.isPinned(playing, n)).toList();
            for (int i = 0; i < placeable.size() && i < free.size(); i++) {
                cfg.pinAt(playing, placeable.get(i), free.get(i));
                placed.add(placeable.get(i) + "->" + free.get(i));
            }
            if (departed.size() == 1 && joined.size() == 1 && cfg.pickOf(joined.get(0)) == null) {
                DungeonClass pick = cfg.pickOf(departed.get(0));
                DungeonClass leaverClass = pick != null ? pick : lastClasses.get(departed.get(0).toLowerCase(Locale.ROOT));
                if (leaverClass != null) {
                    cfg.setPick(joined.get(0), leaverClass);
                    took = ", " + joined.get(0) + " takes " + departed.get(0) + "'s " + leaverClass;
                }
            }
        }
        // The run's party is now this one: classes recorded for everyone in it, newcomers included.
        Map<String, DungeonClass> classesNow = new java.util.LinkedHashMap<>();
        Map<String, DungeonClass> tabNow = new java.util.LinkedHashMap<>();
        for (String n : party) {
            String key = n.toLowerCase(Locale.ROOT);
            DungeonClass eff = PartyTracker.classOf(n);
            DungeonClass tab = PartyTracker.detectedClassOf(n);
            classesNow.put(n, containsIgnoreCase(runParty, n) && lastClasses.containsKey(key) && !changed
                    ? lastClasses.get(key) : eff);
            tabNow.put(n, containsIgnoreCase(runParty, n) && lastTab.containsKey(key) ? lastTab.get(key) : tab);
        }
        cfg.setLastRunClasses(classesNow);
        cfg.setLastRunTabClasses(tabNow);
        cfg.setLastRunParty(party);
        cfg.save();
        lastMidRunOutcome = "mid-run: left " + departed + ", joined " + joined + ", placed " + placed + took
                + (changed ? ", a class changed: Default Order" : "") + ", playing " + playing;
        LOGGER.info("[LeapRunMemory] {}", lastMidRunOutcome);
    }

    private static boolean sameMembers(List<String> a, List<String> b) {
        return a.size() == b.size() && minus(a, b).isEmpty();
    }

    /** What the last run start did (testkit and the log). */
    public static String lastOutcome() {
        return lastOutcome;
    }

    public static int runsProcessed() {
        return runsProcessed;
    }

    /** Applies the three rules for a run starting with {@code party} (teammates, leap-menu order) while playing
     *  {@code playing}, then remembers {@code party} for the next run. */
    public static void onRunStart(List<String> party, DungeonClass playing) {
        LeapMenuConfig cfg = LeapMenuConfig.getInstance();
        List<String> previous = cfg.getLastRunParty();
        List<String> departed = minus(previous, party);
        List<String> joined = minus(party, previous);
        String self = selfName();
        // Picks and pins set by hand outside a run (the Hub, or the dungeon before Mort's line) since the last run start
        // were set FOR this run: compared with an older party they read as departures and joins, and rules 3 and 2 used
        // to wipe or move them (killer560, 2026-10-22: "picks set in the Hub are reset when the run starts").
        List<String> setBefore = party.stream().filter(cfg::wasSetBeforeRun).toList();
        // The one-for-one swap (see the class javadoc), decided before rule 1 clears the leaver's pick.
        Map<String, DungeonClass> lastClasses = cfg.getLastRunClasses();
        Map<String, DungeonClass> lastTab = cfg.getLastRunTabClasses();
        DungeonClass lastPlaying = cfg.getLastRunPlaying();
        List<String> classChanges = new ArrayList<>();
        // Stayers the TAB LIST shows on another class than last run (leaporder-1102). Compared on the tab list itself,
        // because a pick masks it: a teammate given a pick (by hand, or a swap's) who then switched class never read as
        // changed, and kept a pick and a spot for the class they had stopped playing.
        List<String> tabChanged = new ArrayList<>();
        for (String n : party) {
            if (!containsIgnoreCase(previous, n) || containsIgnoreCase(setBefore, n)) {
                continue;
            }
            String key = n.toLowerCase(Locale.ROOT);
            DungeonClass wasTab = lastTab.get(key);
            DungeonClass nowTab = PartyTracker.detectedClassOf(n);
            if (wasTab != null && nowTab != null && wasTab != nowTab) {
                tabChanged.add(n);
                classChanges.add(n + " " + wasTab + "->" + nowTab);
                continue;
            }
            DungeonClass was = lastClasses.get(key);
            DungeonClass now = PartyTracker.classOf(n);
            if (was != null && now != null && was != now) {
                classChanges.add(n + " " + was + "->" + now);
            }
        }
        boolean playingChanged = lastPlaying != null && playing != null && lastPlaying != playing;
        DungeonClass leaverClass = null;
        if (departed.size() == 1) {
            DungeonClass pick = cfg.pickOf(departed.get(0));
            leaverClass = pick != null ? pick : lastClasses.get(departed.get(0).toLowerCase(Locale.ROOT));
        }
        boolean partyChanged = !previous.isEmpty() && (!departed.isEmpty() || !joined.isEmpty());
        boolean swap = partyChanged && departed.size() == 1 && joined.size() == 1 && leaverClass != null
                && classChanges.isEmpty();
        String outcome;
        List<String> stale = new ArrayList<>();
        if (departed.size() > 2) {
            // Rule 3: a new party - nothing of the old layout means anything any more, except what was set for this one.
            List<String> cleared = new ArrayList<>();
            for (String name : ClassOverrides.all().keySet()) {
                if (!containsIgnoreCase(setBefore, name)) {
                    ClassOverrides.clear(name);
                    cleared.add(name);
                }
            }
            unpinOthers(cfg, setBefore, cleared);
            outcome = departed.size() + " left " + departed + ": every class pick and pin reset " + cleared
                    + ", kept (set before the run) " + setBefore;
        } else {
            // Where the departed stood last run, before anything of theirs is cleared: the layout of the class you played
            // then, with everyone on the class they had then (leaporder-1102). It used to be laid out with TODAY's
            // classes, where the departed (gone from the tab list) has none and a stayer may have switched, so it was not
            // the layout the last run showed: with a Tank and a Mage both wanting bottom-right, a departed Tank read as
            // standing top-left, the newcomer was pinned there and the Mage was pushed into the Tank's corner.
            DungeonClass beforeClass = lastPlaying != null ? lastPlaying : playing;
            String[] before = LeapMenuFeature.layout(previous, cfg.getClassOrder(beforeClass), cfg.getDefaultOrder(),
                    lastClassOf(lastClasses));
            List<Integer> vacated = new ArrayList<>();
            for (int spot = 0; spot < 4; spot++) {
                if (before[spot] != null && containsIgnoreCase(departed, before[spot])) {
                    vacated.add(spot);
                }
            }
            // Rule 1: anyone not in this run loses their pick and pins (only them).
            List<String> cleared = new ArrayList<>();
            for (Map.Entry<String, DungeonClass> e : ClassOverrides.all().entrySet()) {
                String name = e.getKey();
                if (!containsIgnoreCase(party, name) && (self == null || !self.equalsIgnoreCase(name))) {
                    ClassOverrides.clear(name);
                    cleared.add(name);
                }
            }
            unpinOthers(cfg, party, cleared);
            // A stayer now on another class keeps nothing they had for the old one (leaporder-1102): their pick and
            // their pins were for a role they no longer play, and a pin holds them in that role's spot over the Default
            // Order. Not what was set before this run.
            for (String n : tabChanged) {
                // Your own pick stays (killer560 11-13): it is what you told the Leap Order you play.
                boolean had = (self == null || !self.equalsIgnoreCase(n)) && ClassOverrides.clear(n);
                if (cfg.unpinEverywhere(n) || had) {
                    stale.add(n);
                }
            }
            // Rule 2: newcomers take the vacated spots, spot order against leap-menu order - except a newcomer already
            // pinned by hand, and a vacated spot someone was pinned into by hand since (both set before this run).
            // Only when the old layout still means something (leaporder-1102): nobody who stayed changed class and you
            // play the class you played. Otherwise the vacated spots are another class's spots, and pinning a newcomer
            // into one held them there over the Default Order and pushed a stayer out of their own corner (his log,
            // 2026-10-31 23:55: a Healer left, a Tank joined, a stayer went Tank to Healer, and the new Tank was pinned
            // into the Healer's spot); then they follow the Default Order, as the reset notice says.
            List<String> placed = new ArrayList<>();
            boolean layoutHolds = classChanges.isEmpty() && !playingChanged;
            List<String> pinsNow = cfg.getClassOrder(playing);
            List<String> placeable = joined.stream().filter(n -> !cfg.isPinned(playing, n)).toList();
            List<Integer> free = new ArrayList<>();
            for (int spot : vacated) {
                if (pinsNow.get(spot).isEmpty()) {
                    free.add(spot);
                }
            }
            for (int i = 0; layoutHolds && i < placeable.size() && i < free.size(); i++) {
                cfg.pinAt(playing, placeable.get(i), free.get(i));
                placed.add(placeable.get(i) + "->" + free.get(i));
            }
            if (!layoutHolds && !placeable.isEmpty() && !free.isEmpty()) {
                placed.add("none, Default Order (" + (playingChanged ? "you played " + lastPlaying : "a class changed") + ")");
            }
            if (!stale.isEmpty()) {
                cleared.add("class changed " + stale);
            }
            // The swap: the newcomer takes the leaver's class too, unless you already picked one for them.
            String took = "";
            if (swap) {
                String newcomer = joined.get(0);
                if (cfg.pickOf(newcomer) == null) {
                    cfg.setPick(newcomer, leaverClass);
                    took = ", swap: " + newcomer + " takes " + departed.get(0) + "'s " + leaverClass;
                } else {
                    took = ", swap: " + newcomer + " keeps their pick " + cfg.pickOf(newcomer);
                }
            }
            String kept = setBefore.isEmpty() ? "" : ", set before the run " + setBefore;
            outcome = (previous.isEmpty() ? "first run " + party + ", cleared " + cleared
                    : departed.isEmpty() && joined.isEmpty() ? "same party " + party + ", picks kept, cleared " + cleared
                    : "left " + departed + ", joined " + joined + ", cleared " + cleared + ", placed " + placed) + took + kept;
        }
        // The notice also when the party is the same but a stayer's switch cleared a pick or pin of theirs: their spot
        // changed without anyone leaving, and he set it by hand.
        if (partyChanged && !swap || !stale.isEmpty()) {
            String why = !classChanges.isEmpty() ? "class changed: " + classChanges
                    : departed.size() == 1 && joined.size() == 1 ? departed.get(0) + "'s class is not known"
                    : departed.size() + " left, " + joined.size() + " joined";
            outcome += ", reset notice (" + why + ")";
            showResetNotice();
        }
        outcome += ", playing " + playing;
        Map<String, DungeonClass> classesNow = new java.util.LinkedHashMap<>();
        Map<String, DungeonClass> tabNow = new java.util.LinkedHashMap<>();
        for (String n : party) {
            classesNow.put(n, PartyTracker.classOf(n));
            tabNow.put(n, PartyTracker.detectedClassOf(n));
        }
        cfg.setLastRunClasses(classesNow);
        cfg.setLastRunTabClasses(tabNow);
        cfg.setLastRunPlaying(playing);
        cfg.clearSetBeforeRun();
        cfg.setLastRunParty(party);
        cfg.save();
        inRun = true;
        runLevel = Minecraft.getInstance().level;
        midRunSeen = null;
        lastOutcome = outcome;
        runsProcessed++;
        LOGGER.info("[LeapRunMemory] run start: previous {}, party {}: {}", previous, party, outcome);
    }

    /** The chat line of the reset notice (testkit finds it in the chat window). */
    public static final String RESET_NOTICE = "The Leap Order was reset because the party changed. Update it with /leaporder.";
    private static volatile int noticesShown = 0;

    /** How many reset notices have been shown this session (testkit). */
    public static int noticesShown() {
        return noticesShown;
    }

    /** The reset notice: a title on screen and a chat line, at once (nothing waits on it). */
    private static void showResetNotice() {
        if (!LeapMenuConfig.getInstance().isShowResetNotice()) {
            return;
        }
        noticesShown++;
        Minecraft client = Minecraft.getInstance();
        Runnable show = () -> {
            McCompat.setTimes(client, 5, 60, 15);
            McCompat.setTitle(client, ModChat.colored("Leap Order reset", ThemeAccent.onDark(ThemeAccent.accent(ModChat.ORANGE))));
            McCompat.setSubtitle(client, ModChat.text("Update it with /leaporder"));
            ModChat.send("Leap Order", ModChat.text(RESET_NOTICE));
        };
        if (client.isSameThread()) {
            show.run();
        } else {
            client.execute(show);
        }
    }

    /** Clears the pins of everyone not in {@code keep}, adding each to {@code cleared} once. */
    private static void unpinOthers(LeapMenuConfig cfg, List<String> keep, List<String> cleared) {
        for (String name : cfg.allPinnedNames()) {
            if (!containsIgnoreCase(keep, name)) {
                cfg.unpinEverywhere(name);
                if (!containsIgnoreCase(cleared, name)) {
                    cleared.add(name);
                }
            }
        }
    }

    /** A teammate's class as recorded last run, else as the mod reads it now. */
    private static java.util.function.Function<String, DungeonClass> lastClassOf(Map<String, DungeonClass> lastClasses) {
        return n -> {
            DungeonClass c = lastClasses.get(n.toLowerCase(Locale.ROOT));
            return c != null ? c : PartyTracker.classOf(n);
        };
    }

    private static String selfName() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : client.player.getGameProfile().name();
    }

    private static List<String> minus(List<String> a, List<String> b) {
        return a.stream().filter(n -> !containsIgnoreCase(b, n)).toList();
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        return names.stream().anyMatch(name::equalsIgnoreCase);
    }
}
