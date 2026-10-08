package com.killer560.hub.splittimers;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

/**
 * Per-floor dungeon split timers. The trigger lines were first ported from Odin's {@code SplitsManager.kt}; the
 * LAYOUT is killer560's own (2026-10-08):
 *
 * <pre>
 *   Blood Open    run start (Mort's line)              -> blood door opened (Watcher's greeting)
 *   Watcher       blood door opened                    -> last blood mob killed ("You have proven yourself.")
 *   Portal Entry  last blood mob killed                -> the boss's first line
 *   Boss Entry    the sum of those three
 *   ----------
 *   Maxor / Storm / Terminals / Goldor / Necron / Dragons (M7 only)
 *   Boss          the boss's first line                -> the run's "☠ Defeated" line
 *   ----------
 *   Total         the whole run
 *   Lag           how much of the run was server lag, in seconds
 * </pre>
 *
 * Every time reads "real (lagless)", e.g. "22.39s (19.59s)" - see {@link SplitLagClock} for "lagless". Floors other
 * than F7/M7 have one boss segment, so their boss section is a single row named after the boss (it IS the Boss row;
 * a second identical "Boss" row would only repeat it).
 *
 * <p>Each split's NAME is the segment that STARTS at its trigger line (Odin's model), so when split N's line fires
 * the chat message is "&lt;split N-1's name&gt; took X". The run is armed on "Starting in 1 second."; Mort's line
 * starts the clock, and if Mort's line never arrives (a changed line, a late join) the clock starts one second after
 * the arming line, the moment Mort normally speaks. A missed boundary line leaves its row out and the segment before
 * it runs on to the next line that did fire.
 *
 * <p>M7 Phase 5 extras ({@link P5Splits}), Core Entry Times ({@link CoreEntryTimes}) and Watcher Move
 * ({@link WatcherMoveTracker}) are optional blocks around this layout, each off by default.
 */
public final class SplitTimersFeature {

    static final Logger LOGGER = ModLog.get("killer560smod-splittimers");

    private record SplitDef(Pattern pattern, String label) {
    }

    // ---- clear boundaries ------------------------------------------------------------------------------------------
    // Odin SplitsManager.MORT_REGEX: the run's first line. Starts "Blood Open".
    private static final SplitDef RUN_START = def(
            "^\\[NPC\\] Mort: (?:Here, I found this map when I first entered the dungeon\\.|Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!)$",
            "§2Blood Open");
    /** The blood door opening: the Watcher's greeting (one per floor) or Hypixel's own door line. Starts "Watcher".
     *  Odin SplitsManager.BLOOD_OPEN_REGEX. */
    private static final SplitDef BLOOD_OPENED = def(
            "^\\[BOSS\\] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|Ah, we meet again\\.\\.\\.|Ah, we meet again\\. As I foresaw\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$|^The BLOOD DOOR has been opened!$",
            "§cWatcher");
    /** The last blood mob dies and the Watcher lets the party through. Starts "Portal Entry". */
    private static final SplitDef WATCHER_DONE = def(
            "^\\[BOSS\\] The Watcher: You have proven yourself\\. You may pass\\.$", "§dPortal Entry");
    /** The run's end line. Ends the last boss segment, the Boss row and the Total. */
    private static final SplitDef TOTAL = def(
            "^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$", "§eTotal");
    /** Devonian WatcherSplits: the line the blood mobs start on - only Watcher Move's clock reads it. */
    private static final Pattern WATCHER_FIGHT = Pattern.compile(
            "^\\[BOSS\\] The Watcher: Let's see how you can handle this\\.$");

    /** How many clear boundaries every floor's list starts with (Blood Open, Watcher, Portal Entry). */
    static final int CLEAR_COUNT = 3;

    // ---- F7 / M7 boss boundaries -------------------------------------------------------------------------------------
    private static final List<SplitDef> FLOOR7 = List.of(
            def("^\\[BOSS\\] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$", "§5Maxor"),
            def("^\\[BOSS\\] Storm: Pathetic Maxor, just like expected\\.$", "§3Storm"),
            def("^\\[BOSS\\] Goldor: Who dares trespass into my domain\\?$", "§6Terminals"),
            def("^The Core entrance is opening!$", "§7Goldor"),
            // Necron's first line in P4 is "Finally, I heard so much about you..." (Floor7Tracker takes either); the first
            // of the two that arrives starts the segment, the other is then ignored (first write wins).
            def("^\\[BOSS\\] Necron: (?:Finally, I heard so much about you\\. The Eye likes you very much\\.|You went further than any human before, congratulations\\.)$",
                    "§cNecron"));
    /** M7 only: Phase 5, the dragons - Necron's death line or the Wither King's first line, whichever comes first
     *  ({@link com.killer560.hub.fastleap.Floor7Tracker#P5_START}: since 0.27.2 the reference mods stopped trusting
     *  Necron's line alone). On F7 Necron's line is just Necron dying, so the Necron row runs on to the end there. */
    private static final SplitDef DRAGONS = new SplitDef(com.killer560.hub.fastleap.Floor7Tracker.P5_START, "§4Dragons");

    // ---- other floors: the boss's first line, one row named after the boss -------------------------------------------
    private static final SplitDef FLOOR1 = def("^\\[BOSS\\] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\.$", "§cBonzo");
    private static final SplitDef FLOOR2 = def("^\\[BOSS\\] Scarf: This is where the journey ends for you, Adventurers\\.$", "§cScarf");
    private static final SplitDef FLOOR3 = def("^\\[BOSS\\] The Professor: I was burdened with terrible news recently\\.\\.\\.$", "§cThe Professor");
    private static final SplitDef FLOOR4 = def("^\\[BOSS\\] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$", "§cThorn");
    private static final SplitDef FLOOR5 = def("^\\[BOSS\\] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$", "§cLivid");
    private static final SplitDef FLOOR6 = def("^\\[BOSS\\] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!$", "§cSadan");

    private static SplitDef def(String regex, String label) {
        return new SplitDef(Pattern.compile(regex), label);
    }

    /** The boundary list for a floor ("F7", "M3", "E"): the three clear boundaries, the floor's boss boundaries, then
     *  Total. Empty when the floor is unknown or is the Entrance (no blood room, no boss split worth a layout). */
    static List<SplitDef> splitsForFloor(String floor) {
        if (floor == null || floor.length() < 2) {
            return List.of();
        }
        int number;
        try {
            number = Integer.parseInt(floor.substring(1));
        } catch (NumberFormatException e) {
            return List.of();
        }
        boolean master = floor.charAt(0) == 'M';
        List<SplitDef> boss = new ArrayList<>();
        switch (number) {
            case 1 -> boss.add(FLOOR1);
            case 2 -> boss.add(FLOOR2);
            case 3 -> boss.add(FLOOR3);
            case 4 -> boss.add(FLOOR4);
            case 5 -> boss.add(FLOOR5);
            case 6 -> boss.add(FLOOR6);
            case 7 -> {
                boss.addAll(FLOOR7);
                if (master) {
                    boss.add(DRAGONS);
                }
            }
            default -> {
                return List.of();
            }
        }
        List<SplitDef> all = new ArrayList<>(List.of(RUN_START, BLOOD_OPENED, WATCHER_DONE));
        all.addAll(boss);
        all.add(TOTAL);
        return List.copyOf(all);
    }

    private static final class RunState {
        List<SplitDef> splits = List.of();
        /** Wall-clock time each split's trigger line fired (0 = not yet). Parallel to {@link #splits}. */
        long[] timeMs = new long[0];
        /** {@link SplitLagClock#accumulatedLagMs()} snapshotted at the same instant as {@link #timeMs}[i]. */
        long[] lagMs = new long[0];
        /** When "Starting in 1 second." armed this run, and the lag clock then - the fallback run start. */
        long armedAtMs = 0L;
        long armedLagMs = 0L;
    }

    /** One line of the layout. {@code lagLessMs} is -1 where it does not apply. */
    enum Kind { SEGMENT, DIVIDER, LAG }

    record SplitRow(String name, long timeMs, long lagLessMs, Kind kind) {
        static SplitRow segment(String name, long timeMs, long lagLessMs) {
            return new SplitRow(name, timeMs, lagLessMs, Kind.SEGMENT);
        }
    }

    private static RunState run = new RunState();
    /** Client ticks left before the end-of-run summary prints (Odin: {@code schedule(10)}); -1 = none. */
    private static int finishDelayTicks = -1;
    private static List<Component> pendingFinishMessages = List.of();

    private SplitTimersFeature() {
    }

    public static void register() {
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Every trigger is a full-line match of a server-format line, so a player's chat line can't match.
        ChatObserver.subscribe(SplitTimersFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SplitTimersFeature", client -> tick()));
        SplitLagClock.register();
    }

    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;

    private static void resetAll() {
        run = new RunState();
        P5Splits.reset();
        WatcherMoveTracker.reset();
        CoreEntryTimes.reset();
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        // Odin resets its splits on LevelEvent.Load - same here, on any real level change.
        if (client.level != lastLevel) {
            if (lastLevel != null) {
                resetAll();
            }
            lastLevel = client.level;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            resetAll();
        }
        wasInDungeon = inDungeon;

        if (SplitTimersConfig.getInstance().isEnabled()) {
            WatcherMoveTracker.tick();
            CoreEntryTimes.tick();
        }

        if (finishDelayTicks >= 0 && --finishDelayTicks < 0) {
            if (SplitTimersConfig.getInstance().isAnnounceInChat() && client.player != null) {
                for (Component line : pendingFinishMessages) {
                    client.player.sendSystemMessage(line);
                }
            }
            pendingFinishMessages = List.of();
        }
    }

    private static void onChatMessage(Component message) {
        if (!SplitTimersConfig.getInstance().isEnabled()) {
            return;
        }
        // Stripped: real Hypixel lines can embed §-codes mid-word.
        String plain = ChatFormatting.stripFormatting(message.getString());
        onLine(plain != null ? plain : message.getString(), System.currentTimeMillis());
    }

    /** One chat line at {@code now}. Package-private so the testkit can drive a run with exact times. */
    static void onLine(String raw, long now) {
        if ("Starting in 1 second.".equals(raw)) {
            RunState fresh = new RunState();
            fresh.splits = splitsForFloor(DungeonState.getFloor());
            fresh.timeMs = new long[fresh.splits.size()];
            fresh.lagMs = new long[fresh.splits.size()];
            fresh.armedAtMs = now;
            fresh.armedLagMs = SplitLagClock.accumulatedLagMs();
            run = fresh;
            P5Splits.reset();
            WatcherMoveTracker.reset();
            CoreEntryTimes.reset();
            finishDelayTicks = -1;
            if (fresh.splits.isEmpty()) {
                LOGGER.warn("[SplitTimers] Run START line seen but NO splits resolved for floor={} (inDungeon={}) - nothing will be timed this run",
                        DungeonState.getFloor(), DungeonState.isInDungeon());
            }
            return;
        }

        // Side hooks that are NOT splits: they read the same lines but must work whatever the split list holds.
        if (BLOOD_OPENED.pattern().matcher(raw).matches()) {
            WatcherMoveTracker.onBloodDoorOpened();
        } else if (WATCHER_FIGHT.matcher(raw).matches()) {
            WatcherMoveTracker.onDialogueEnd();
        } else if ("The Core entrance is opening!".equals(raw)) {
            CoreEntryTimes.onCoreOpening();
        } else if ("[BOSS] Necron: You went further than any human before, congratulations.".equals(raw)) {
            CoreEntryTimes.onPhaseFourStarted();
        }

        if (run.splits.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < run.splits.size(); i++) {
            if (run.splits.get(i).pattern().matcher(raw).matches()) {
                index = i;
                break;
            }
        }
        if (index < 0 || run.timeMs[index] != 0L) {
            return;
        }
        // Mort's line never came (changed wording, joined late): the run started when Mort normally speaks, one
        // second after "Starting in 1 second.", so every later row and the Total still have their real start.
        if (index > 0 && run.timeMs[0] == 0L && run.armedAtMs > 0L) {
            run.timeMs[0] = run.armedAtMs + 1000L;
            run.lagMs[0] = run.armedLagMs;
            LOGGER.warn("[SplitTimers] Mort's line was not seen; run clock started 1 s after \"Starting in 1 second.\"");
        }
        run.timeMs[index] = now;
        run.lagMs[index] = SplitLagClock.accumulatedLagMs();
        SplitDef split = run.splits.get(index);

        for (int i = 0; i < index; i++) {
            if (run.timeMs[i] == 0L) {
                LOGGER.warn("[SplitTimers] OUT-OF-ORDER split line: \"{}\" (index {}) fired while \"{}\" (index {}) never did. Line: \"{}\"",
                        plainLabel(split), index, plainLabel(run.splits.get(i)), i, raw);
                break;
            }
        }

        if (index == 0) {
            return;
        }
        int prev = previousRecorded(index);
        if (prev < 0) {
            LOGGER.warn("[SplitTimers] SPLIT \"{}\" hit with no earlier split recorded - nothing to time. Line: \"{}\"",
                    plainLabel(split), raw);
            return;
        }
        long segment = now - run.timeMs[prev];
        long segmentLagless = Math.max(0L, segment - (run.lagMs[index] - run.lagMs[prev]));
        boolean last = index == run.splits.size() - 1;

        Minecraft client = Minecraft.getInstance();
        if (!SplitTimersConfig.getInstance().isAnnounceInChat() || client.player == null) {
            return;
        }
        boolean lagless = SplitTimersConfig.getInstance().isLaglessTimes();
        Component tookLine = tookLine(plainLabel(run.splits.get(prev)),
                String.format(Locale.US, "%.2fs", segment / 1000.0) + laglessSuffix(lagless, segmentLagless, true), "!");
        if (!last) {
            client.player.sendSystemMessage(tookLine);
            return;
        }
        // Odin's finishRun: the summary is snapshotted now and printed 10 ticks later, after Hypixel's own. It is the
        // HUD's layout line for line (dividers left out), then the optional extra blocks.
        List<Component> lines = new ArrayList<>();
        lines.add(tookLine);
        for (SplitRow row : layoutRows(now)) {
            switch (row.kind()) {
                case SEGMENT -> lines.add(tookLine(plain(row.name()), formatTime(row.timeMs())
                        + laglessSuffix(lagless, row.lagLessMs(), false), "."));
                case LAG -> lines.add(Component.empty().append(ModChat.value("Lag")).append(ModChat.text(": "))
                        .append(ModChat.value(lagSeconds(row.timeMs()))).append(ModChat.text(".")));
                default -> {
                }
            }
        }
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();
        for (String core : CoreEntryTimes.lines()) {
            lines.add(Component.empty().append(ModChat.text("Core ")).append(Component.literal(core)));
        }
        for (String p5 : P5Splits.lines(cfg.isP5DragonLines(), cfg.isP5RelicLines(), true)) {
            lines.add(Component.empty().append(ModChat.text("P5 ")).append(Component.literal(p5)));
        }
        pendingFinishMessages = lines;
        finishDelayTicks = 10;
    }

    /**
     * " (lagless)" after a time, or "" (2026-10-07, killer560: "have the no lag in parentheses to the right of the total
     * and we will know that is the lagless time"). Every split and run time the mod shows, HUD and chat, reads the same
     * way: the real time, then the lagless one in parentheses. Only while Lagless Times is on, the lagless value exists
     * ({@code >= 0}) and {@link SplitLagClock} has seen real server ticks (else it would be a dishonest copy).
     */
    static String laglessSuffix(boolean on, long laglessMs, boolean seconds) {
        if (!on || laglessMs < 0L || !SplitLagClock.isTrustworthy()) {
            return "";
        }
        return " (" + (seconds ? String.format(Locale.US, "%.2fs", laglessMs / 1000.0) : formatTime(laglessMs)) + ")";
    }

    private static Component tookLine(String name, String time, String end) {
        return Component.empty().append(ModChat.value(name)).append(ModChat.text(" took "))
                .append(ModChat.value(time)).append(ModChat.text(end));
    }

    private static int previousRecorded(int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (run.timeMs[i] != 0L) {
                return i;
            }
        }
        return -1;
    }

    private static int firstRecorded() {
        for (int i = 0; i < run.timeMs.length; i++) {
            if (run.timeMs[i] != 0L) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the split whose segment is running right now (the latest recorded one), or -1 if the clock
     *  hasn't started or the final (Total) split already fired. */
    private static int currentIndex() {
        int latest = -1;
        for (int i = 0; i < run.timeMs.length; i++) {
            if (run.timeMs[i] != 0L) {
                latest = i;
            }
        }
        return latest == run.timeMs.length - 1 ? -1 : latest;
    }

    /** Time and lag at the end of segment {@code i}: the next recorded boundary, else the run's end, else now. */
    private static long[] segmentEnd(int i, long now, long nowLag) {
        for (int j = i + 1; j < run.timeMs.length; j++) {
            if (run.timeMs[j] != 0L) {
                return new long[]{run.timeMs[j], run.lagMs[j]};
            }
        }
        return new long[]{now, nowLag};
    }

    /** A span from boundary {@code from}'s time to {@code end}, as a row. */
    private static SplitRow span(String name, int from, long[] end) {
        long t = Math.max(0L, end[0] - run.timeMs[from]);
        long lagless = Math.max(0L, t - (end[1] - run.lagMs[from]));
        return SplitRow.segment(name, t, lagless);
    }

    /**
     * killer560's layout (2026-10-08), as rows: the clear segments that have started, Boss Entry (live until the boss
     * starts), a divider; once the boss has started its segments and, on F7/M7, the overall Boss row, another divider;
     * then Total and Lag. Empty before the run clock starts. The HUD draws exactly these; the end-of-run chat summary
     * prints them without the dividers.
     */
    static List<SplitRow> layoutRows(long now) {
        int n = run.splits.size();
        int first = firstRecorded();
        if (n == 0 || first < 0) {
            return List.of();
        }
        long nowLag = SplitLagClock.accumulatedLagMs();
        int last = n - 1;
        boolean ended = run.timeMs[last] != 0L;
        long[] runEnd = ended ? new long[]{run.timeMs[last], run.lagMs[last]} : new long[]{now, nowLag};
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();
        List<SplitRow> out = new ArrayList<>();

        int bossStart = CLEAR_COUNT;
        for (int i = 0; i < Math.min(CLEAR_COUNT, last); i++) {
            if (run.timeMs[i] != 0L) {
                out.add(span(run.splits.get(i).label(), i, segmentEnd(i, now, nowLag)));
            }
            if (i == 1 && cfg.isWatcherMoveSplit()) {
                long watcherMoveMs = WatcherMoveTracker.getMoveMs();
                if (watcherMoveMs != 0L) {
                    // Not a split boundary, so it has no lag snapshot and no lagless time.
                    out.add(new SplitRow("§cWatcher Move", watcherMoveMs, -1L, Kind.SEGMENT));
                }
            }
        }
        boolean bossStarted = bossStart < last && run.timeMs[bossStart] != 0L;
        // Boss Entry: run start -> the boss's first line (live while the clear is still running).
        long[] bossEntryEnd = bossStarted ? new long[]{run.timeMs[bossStart], run.lagMs[bossStart]} : runEnd;
        out.add(span("§9Boss Entry", first, bossEntryEnd));

        if (bossStarted) {
            out.add(new SplitRow(DIVIDER_TEXT, 0L, -1L, Kind.DIVIDER));
            int bossRows = 0;
            for (int i = bossStart; i < last; i++) {
                if (run.timeMs[i] != 0L) {
                    out.add(span(run.splits.get(i).label(), i, segmentEnd(i, now, nowLag)));
                    bossRows++;
                }
            }
            // One boss segment (F1-F6) IS the boss time; F7/M7 get the overall row under their phases.
            if (last - bossStart > 1 || bossRows > 1) {
                out.add(span("§4Boss", bossStart, runEnd));
            }
        }
        out.add(new SplitRow(DIVIDER_TEXT, 0L, -1L, Kind.DIVIDER));
        out.add(span("§eTotal", first, runEnd));
        if (SplitLagClock.isTrustworthy()) {
            out.add(new SplitRow("§cLag", Math.max(0L, runEnd[1] - run.lagMs[first]), -1L, Kind.LAG));
        }
        return out;
    }

    /** "3.35s": lag is always shown in seconds. */
    static String lagSeconds(long ms) {
        return String.format(Locale.US, "%.2fs", ms / 1000.0);
    }

    /** Odin's Utils.formatTime(time, 2): "1h 2m 3.45s". */
    static String formatTime(long timeMs) {
        if (timeMs == 0L) {
            return "0s";
        }
        long remaining = timeMs;
        long hours = remaining / 3600000L;
        remaining -= hours * 3600000L;
        long minutes = remaining / 60000L;
        remaining -= minutes * 60000L;
        return (hours > 0 ? hours + "h " : "") + (minutes > 0 ? minutes + "m " : "")
                + String.format(Locale.US, "%.2fs", remaining / 1000f);
    }

    private static String plain(String label) {
        String stripped = ChatFormatting.stripFormatting(label);
        return stripped != null ? stripped : label;
    }

    private static String plainLabel(SplitDef def) {
        return plain(def.label());
    }

    /** For {@code RunSummaryFeature}/{@code TerminalTimersFeature}: the wall-clock moment the CURRENTLY running split
     *  segment began. 0 if no segment is running. */
    public static long getCurrentSegmentStartedAtMs() {
        int current = currentIndex();
        return current < 0 ? 0L : run.timeMs[current];
    }

    /** For {@code autokick.AutoKickFeature}: the wall-clock moment this run's clock started, or 0. */
    public static long getRunStartedAtMs() {
        int first = firstRecorded();
        return first < 0 ? 0L : run.timeMs[first];
    }

    /** Milliseconds elapsed since {@link #getRunStartedAtMs()}, or 0 if no run is in progress. */
    public static long getElapsedMs() {
        long startedAt = getRunStartedAtMs();
        return startedAt <= 0L ? 0L : System.currentTimeMillis() - startedAt;
    }

    /** The plain name of the segment currently in progress - e.g. "Terminals" from Goldor's "Who dares trespass"
     *  line until "The Core entrance is opening!". Null if no segment is running. */
    public static String getCurrentSegmentLabel() {
        int current = currentIndex();
        return current < 0 ? null : plainLabel(run.splits.get(current));
    }

    /** A strikethrough run of spaces - a plain horizontal divider without knowing the font's pixel widths. */
    static final String DIVIDER_TEXT = "§8§m" + " ".repeat(34);

    public static final class SplitTimersHudElement implements HudElement {
        @Override
        public String id() {
            return "split_timers";
        }

        @Override
        public String displayName() {
            return "Split Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Under Score Calculator's seven rows (160..230), not 320: the Dungeon Map's default sits clamped at the
            // bottom left, and with its Extra Info lines it reached 320 at GUI scale 2 on 1080p (2026-10-07).
            return 240;
        }

        /** In game, never on top of the Dungeon Map (and its Extra Info lines): see {@link com.killer560.hub.hud.HudAvoid}.
         *  In the HUD editor its own saved position, which this never changes. */
        @Override
        public int[] layoutPosition() {
            if (HudVisibility.editorOpen()) {
                return null;
            }
            return com.killer560.hub.hud.HudAvoid.clear(this, "live_map");
        }

        private static final int SPLIT_COLUMN_WIDTH = 160;

        /** One drawn line: its text and where it goes, relative to the element's top-left. */
        private record Line(String text, int dx, int dy) {
        }

        private static boolean inEditor() {
            return McCompat.screen(Minecraft.getInstance()) instanceof com.killer560.hub.hud.HudEditorScreen;
        }

        /**
         * Every line this element draws right now, placed. render() draws exactly this list and width()/height()
         * measure exactly this list, so the HUD editor's box is the drawn text and nothing else. In the editor the
         * split block is a sample M7 run, so the whole layout can be positioned before a run.
         */
        private static List<Line> layout() {
            List<Line> out = new ArrayList<>();
            int lineY = 0;
            List<String> rows = inEditor() ? editorRows() : rowTexts(layoutRows(System.currentTimeMillis()));
            for (String row : rows) {
                out.add(new Line(row, 0, lineY));
                lineY += ROW_HEIGHT;
            }
            boolean right = SplitTimersConfig.getInstance().isP5LinesRight();
            int p5X = right ? SPLIT_COLUMN_WIDTH : 0;
            int p5Y = right ? 0 : lineY;
            for (String line : p5Lines()) {
                out.add(new Line(line, p5X, p5Y));
                p5Y += ROW_HEIGHT;
            }
            int extraY = right ? lineY : p5Y;
            for (String line : coreLines()) {
                out.add(new Line(line, 0, extraY));
                extraY += ROW_HEIGHT;
            }
            for (String line : slowestCoreLine()) {
                out.add(new Line(line, 0, extraY));
                extraY += ROW_HEIGHT;
            }
            return out;
        }

        @Override
        public int width() {
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            int w = 0;
            for (Line line : layout()) {
                w = Math.max(w, line.dx() + font.width(line.text()));
            }
            return Math.max(20, w);
        }

        @Override
        public int height() {
            int h = 0;
            for (Line line : layout()) {
                h = Math.max(h, line.dy() + ROW_HEIGHT);
            }
            return Math.max(ROW_HEIGHT, h);
        }

        /** Tighter than vanilla's 9px line height would allow overlap; this is the least padding that still
         *  reads cleanly at scale 1. */
        private static final int ROW_HEIGHT = 9;

        /** The layout rows as HUD text. */
        static List<String> rowTexts(List<SplitRow> rows) {
            boolean lagless = SplitTimersConfig.getInstance().isLaglessTimes() && SplitLagClock.isTrustworthy();
            List<String> out = new ArrayList<>(rows.size());
            for (SplitRow row : rows) {
                out.add(switch (row.kind()) {
                    case DIVIDER -> row.name();
                    case LAG -> row.name() + "§f: " + lagSeconds(row.timeMs());
                    case SEGMENT -> row.name() + "§f: " + formatTime(row.timeMs())
                            + (lagless && row.lagLessMs() >= 0L ? " §7(" + formatTime(row.lagLessMs()) + ")" : "");
                });
            }
            return out;
        }

        /** A finished M7 run, for positioning the element in the HUD editor. */
        private static List<String> editorRows() {
            boolean lagless = SplitTimersConfig.getInstance().isLaglessTimes();
            String[][] sample = {
                    {"§2Blood Open", "22.39s", "19.59s"}, {"§cWatcher", "34.10s", "33.20s"},
                    {"§dPortal Entry", "6.05s", "5.90s"}, {"§9Boss Entry", "1m 2.54s", "58.69s"}, null,
                    {"§5Maxor", "28.40s", "27.95s"}, {"§3Storm", "40.12s", "39.70s"}, {"§6Terminals", "38.55s", "37.10s"},
                    {"§7Goldor", "12.30s", "12.05s"}, {"§cNecron", "31.80s", "31.25s"}, {"§4Dragons", "1m 5.20s", "1m 4.40s"},
                    {"§4Boss", "3m 36.37s", "3m 32.45s"}, null, {"§eTotal", "4m 38.91s", "4m 31.14s"}};
            List<String> out = new ArrayList<>();
            for (String[] s : sample) {
                out.add(s == null ? DIVIDER_TEXT : s[0] + "§f: " + s[1] + (lagless ? " §7(" + s[2] + ")" : ""));
            }
            out.add("§cLag§f: 7.77s");
            return out;
        }

        /** F7/M7 "time to enter the core after terms" rows ({@link CoreEntryTimes}). In the HUD editor a
         *  sample is shown while the toggle is on, so the block can be positioned. */
        private static List<String> coreLines() {
            if (!SplitTimersConfig.getInstance().isCoreEntryTimes()) {
                return List.of();
            }
            return inEditor() ? CoreEntryTimes.editorLines() : CoreEntryTimes.lines();
        }

        /** M7 Phase 5 dragon/relic lines ({@link P5Splits}). In the HUD editor a sample is shown while
         *  either toggle is on, so the column can be positioned before a run. */
        private static List<String> p5Lines() {
            SplitTimersConfig cfg = SplitTimersConfig.getInstance();
            if (!cfg.isP5DragonLines() && !cfg.isP5RelicLines()) {
                return List.of();
            }
            if (inEditor()) {
                List<String> out = new ArrayList<>();
                if (cfg.isP5DragonLines()) {
                    out.add("§5Purple §8#1§f: 11.35s");
                    out.add("§cRed §8#1§f: §79.80s");
                }
                if (cfg.isP5RelicLines()) {
                    out.add("§3Relic Spawn§f: 1.90s");
                    out.add("§6Orange Relic§f: 8.45s");
                }
                return out;
            }
            return P5Splits.lines(cfg.isP5DragonLines(), cfg.isP5RelicLines(), false);
        }

        /** "at the very bottom of the split timers show the slowest person into core and their time" - killer560,
         *  2026-09-20. Independent of Core Entry's chat/party announce toggles. */
        private static List<String> slowestCoreLine() {
            if (!SplitTimersConfig.getInstance().isCoreEntrySlowestHud()) {
                return List.of();
            }
            if (inEditor()) {
                return List.of("§6Slowest§f: §cTeammate§f: 6.20s");
            }
            String line = CoreEntryTimes.slowestHudLine();
            return line == null ? List.of() : List.of("§6Slowest§f: " + line);
        }

        @Override
        public boolean isEnabledInSettings() {
            return SplitTimersConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!SplitTimersConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            List<Line> lines = layout();
            for (Line line : lines) {
                graphics.text(Minecraft.getInstance().font, line.text(), x + line.dx(), y + line.dy(), 0xFFFFFFFF, false);
            }
            if (!lines.isEmpty()) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
