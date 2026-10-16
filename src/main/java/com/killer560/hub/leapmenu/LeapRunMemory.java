package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
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
 *       newcomer is pinned there; their class is the tab list's (nothing is carried over from the departed; an override
 *       you had already set for the newcomer yourself stays). Extra newcomers follow the Default Order.</li>
 *   <li>more than two left: every class override and every pin is cleared, so the Default Order applies fresh.</li>
 * </ol>
 * <b>Run start</b> is Mort's opening line, the same anchored pattern the Split Timers use, so nobody's chat can fake it
 * (a player's line starts with their rank or name, a party line with "Party &gt;"); the party is read from the dungeon tab
 * list a second later, once the tab list has been read at least twice. Never in the room sim or the {@code /killer560 sim}
 * override: those are not runs with a party.
 */
public final class LeapRunMemory {

    private static final Logger LOGGER = ModLog.get("killer560smod-leapmenu");

    /** Odin SplitsManager.MORT_REGEX - the run's first line (Split Timers' RUN_START). */
    private static final Pattern RUN_START = Pattern.compile(
            "^\\[NPC\\] Mort: (?:Here, I found this map when I first entered the dungeon\\.|Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!)$");
    /** Ticks between Mort's line and reading the party: the tab list is re-read every 10 ticks. */
    private static final int SETTLE_TICKS = 20;

    private static int pendingTicks = -1;
    private static volatile String lastOutcome = "";
    private static volatile int runsProcessed = 0;

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
        }
    }

    private static void tick() {
        if (pendingTicks < 0) {
            return;
        }
        if (++pendingTicks < SETTLE_TICKS) {
            return;
        }
        pendingTicks = -1;
        if (!DungeonState.isInDungeon() || DungeonState.isRoomSim() || DungeonState.isSimOverrideActive()) {
            lastOutcome = "skipped: not a real dungeon run";
            return;
        }
        List<String> party = PartyTracker.teammatesInLeapOrder();
        if (party.isEmpty()) {
            lastOutcome = "skipped: no teammates known";
            return;
        }
        onRunStart(party, LeapMenuFeature.playingClass());
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
        String outcome;
        if (departed.size() > 2) {
            // Rule 3: a new party - nothing of the old layout means anything any more.
            ClassOverrides.clearAll();
            cfg.clearAllPins();
            outcome = departed.size() + " left " + departed + ": every class pick and pin reset";
        } else {
            // Where the departed stood last run, before anything of theirs is cleared.
            String[] before = LeapMenuFeature.layout(previous, playing);
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
            for (String name : cfg.allPinnedNames()) {
                if (!containsIgnoreCase(party, name)) {
                    cfg.unpinEverywhere(name);
                    if (!containsIgnoreCase(cleared, name)) {
                        cleared.add(name);
                    }
                }
            }
            // Rule 2: newcomers take the vacated spots, spot order against leap-menu order.
            List<String> placed = new ArrayList<>();
            for (int i = 0; i < joined.size() && i < vacated.size(); i++) {
                cfg.pinAt(playing, joined.get(i), vacated.get(i));
                placed.add(joined.get(i) + "->" + vacated.get(i));
            }
            outcome = previous.isEmpty() ? "first run " + party + ", cleared " + cleared
                    : departed.isEmpty() && joined.isEmpty() ? "same party " + party + ", picks kept, cleared " + cleared
                    : "left " + departed + ", joined " + joined + ", cleared " + cleared + ", placed " + placed;
        }
        cfg.setLastRunParty(party);
        cfg.save();
        lastOutcome = outcome;
        runsProcessed++;
        LOGGER.info("[LeapRunMemory] run start: {}", outcome);
    }

    private static String selfName() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : client.player.getGameProfile().name();
    }

    private static List<String> minus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String n : a) {
            if (!containsIgnoreCase(b, n)) {
                out.add(n);
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        String n = name.toLowerCase(Locale.US);
        for (String s : names) {
            if (s.toLowerCase(Locale.US).equals(n)) {
                return true;
            }
        }
        return false;
    }
}
