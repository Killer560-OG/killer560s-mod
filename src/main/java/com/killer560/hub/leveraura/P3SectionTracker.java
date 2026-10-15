package com.killer560.hub.leveraura;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F7/M7 P3 section progress read from chat, shared by In Boss ({@link LeverAuraFeature}, S2's section levers) and the
 * Lights Device ({@code lightsdevice.LightsDeviceFeature}). One tracker, so the two features can never disagree about
 * whether S2 is open or the Lights device is done ("one room must not have two answers"). It was Lever Aura's own until
 * the Lights Device became its own feature (killer560, 2026-10-15); the rules are unchanged:
 * <ul>
 * <li>Goldor's opening line starts a run (section 1); a world change resets it.
 * <li>A section advances once its objectives read "(n/n)" AND "The gate has been destroyed!" was seen, or when a line of
 * a different count arrives after the section was already complete (a missed gate line). "The Core entrance is opening!"
 * is section 5.
 * <li>p3sim.net (and a mid-run join) can skip Goldor's line: with no start line seen this world, the first objective line
 * takes the section from where the player stands (Floor7Tracker's S1..S4 boxes).
 * <li>S2 has exactly one device, the Lights device: a "... completed a device! (x/y)" line in section 2 marks it done.
 * </ul>
 * Every run gets a new {@link #generation()}, so a feature keeping per-run click sets clears them when it changes. Not
 * gated on any feature's switch: the progress is tracked whatever is on, so turning a feature on mid-run knows where P3 is.
 */
public final class P3SectionTracker {

    // Same completion regex as Floor7Tracker / QUOI REGEX_TERM_COMPLETED (optional trailing suffix from other mods).
    // The name group is a REAL username, not any 16 characters.
    //
    // ".{1,16}" is anchored but still matches "[VIP] Bob: a" - twelve characters - so any player typing
    // "a completed a terminal! (7/7)" forged a stage completion. For Fast Leap that means an automated leap
    // in P3; for the lever features it advances or ends the section counter and levers get clicked out of sequence.
    // A Minecraft name is letters, digits and underscore, which no chat prefix can be.
    private static final Pattern TERM_COMPLETED =
            Pattern.compile("^([A-Za-z0-9_]{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final String GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final String GATE_DESTROYED = "The gate has been destroyed!";
    private static final String CORE_OPENING = "The Core entrance is opening!";

    private static int section = 1;
    private static int sectionCurrent = 0;
    private static int sectionTotal = 0;
    private static boolean sectionGate = false;
    private static boolean lightsCompleted = false;
    /** Whether Goldor's opening line was seen this run - p3sim.net can skip it (see {@link #onChat}). */
    private static boolean sawGoldorStart = false;
    private static int generation = 0;
    private static Object lastLevel = null;
    private static boolean registered = false;

    private P3SectionTracker() {
    }

    /** Subscribes to chat once (both features call it; only the first does anything). */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message)));
    }

    /** Called at the top of each user's tick: a new world is a new run. Idempotent within a tick. */
    public static void poll(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetRun();
        }
    }

    /** 1..4 for P3's sections, 5 once the core is opening. */
    public static int section() {
        return section;
    }

    /** True once the S2 Lights device was completed this run (by anyone). */
    public static boolean lightsCompleted() {
        return lightsCompleted;
    }

    /** Bumped on every run reset (Goldor's line, a world change). */
    public static int generation() {
        return generation;
    }

    static void onChat(String plain) {
        if (plain == null || !DungeonState.isF7OrM7()) {
            return;
        }
        plain = plain.trim();
        if (GOLDOR_START.equals(plain)) {
            resetRun();
            sawGoldorStart = true;
            return;
        }
        if (CORE_OPENING.equals(plain)) {
            setSection(5);
            return;
        }
        if (GATE_DESTROYED.equals(plain)) {
            sectionGate = true;
            checkSectionAdvance();
            return;
        }
        Matcher m = TERM_COMPLETED.matcher(plain);
        if (!m.matches()) {
            return;
        }
        int cur = parse(m.group(4));
        int tot = parse(m.group(5));
        // p3sim.net (and a mid-run join) can skip Goldor's opening line: with no start line seen this world, the
        // first objective line tells us P3 is running, so take the section from where the player is actually
        // standing (Floor7Tracker's own S1..S4 boxes) instead of assuming S1 - otherwise the S2 "finish the
        // device" behaviour never runs there. Same rule Floor7Tracker uses for its own phase inference.
        if (!sawGoldorStart && section == 1 && sectionTotal == 0) {
            Floor7Tracker.Stage at = Floor7Tracker.getStageAt();
            if (at.number >= 2 && at.number <= 4) {
                setSection(at.number);
            }
        }
        // Missed gate line fallback: this section was already complete and a line from a different count arrives.
        if (sectionTotal > 0 && sectionCurrent == sectionTotal && (tot != sectionTotal || cur < sectionCurrent)) {
            setSection(section + 1);
        }
        sectionCurrent = cur;
        sectionTotal = tot;
        if (section == 2 && "device".equals(m.group(3)) && !lightsCompleted) {
            // S2 has exactly one device - the Lights device.
            lightsCompleted = true;
        }
        checkSectionAdvance();
    }

    private static void checkSectionAdvance() {
        if (section <= 3 && sectionGate && sectionTotal > 0 && sectionCurrent == sectionTotal) {
            setSection(section + 1);
        }
    }

    private static void setSection(int s) {
        if (s == section) {
            return;
        }
        section = s;
        sectionCurrent = 0;
        sectionTotal = 0;
        sectionGate = false;
    }

    private static void resetRun() {
        section = 1;
        sectionCurrent = 0;
        sectionTotal = 0;
        sectionGate = false;
        sawGoldorStart = false;
        lightsCompleted = false;
        generation++;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
