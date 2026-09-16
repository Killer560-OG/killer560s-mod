package com.killer560.hub.splittimers;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per-floor dungeon split timers - killer560's "Implement Noamm split timers" request. Every regex
 * below is ported directly from Odin's own real, confirmed {@code SplitsManager.kt}
 * ({@code dungeonSplits}/{@code floor1SplitGroup}...{@code floor7SplitGroup}, plus the shared
 * Mort/Blood Open/Portal Entry prefix and the "☠ Defeated" Total suffix) - real dialogue lines, not
 * guessed. A run is armed on the real "Starting in 1 second." countdown message.
 * <p>
 * Odin's split model (ported exactly): each split's NAME is the phase that STARTS at its trigger line,
 * so when split N's line fires the message is "&lt;split N-1's name&gt; took X". The first split (Mort's
 * line) only starts the clock; the last split ("Total", the real "☠ Defeated" end-of-run line) ends the
 * final boss segment and prints the end-of-run summary.
 * <p>
 * Deliberately simpler than Odin's own version: no personal-best tracking/comparison (that needs a
 * persisted history this session has no design for yet), no Kuudra splits (out of scope for this
 * request), and no per-split tick time (Odin counts server ticks from ping packets, which this mod has
 * no hook for).
 * <p>
 * M7 Phase 5 (2026-09-15): optional dragon (spawn -&gt; kill) and relic (spawn / placed) lines from {@link P5Splits},
 * fed by {@code com.killer560.hub.witherdragons}, drawn in a column to the right of the split rows (or under them).
 * <p>
 * Clear phase (2026-09-16, gap analysis 2.1): three optional additions, all off by default -
 * <b>Clear Splits</b> (Devonian {@code dungeons/clear/RunSplits.kt} + {@code api/dungeon/Stages.kt}) cuts the two
 * clear rows into Blood Rush / Blood Open / Watcher Dialogue / Blood Clear; <b>Watcher Move</b>
 * ({@link WatcherMoveTracker}, Devonian {@code dungeons/clear/WatcherSplits.kt}) adds an entity-movement row; and
 * <b>Core Entry Times</b> ({@link CoreEntryTimes}, killer560's own request) times each player into the core after
 * terminals. The synthesised Boss Entry row (Devonian {@code Stages.BossEntry}) now sums however many clear
 * segments exist instead of assuming exactly three.
 * <p>
 * Re-verified 2026-09-15 against Odin main @38ddc1b (SplitsManager.kt / Splits.kt): split names, the "&lt;previous&gt;
 * took X" chat, currentRows' segment = next split - this split, and the Boss Entry row after index 2 all match -
 * the "split labels off by one" report was the pre-c571b1d behaviour (each split used to be labelled with the
 * phase that ENDED at its line) and is already fixed; nothing further changed there.
 */
public final class SplitTimersFeature {

    static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-splittimers");

    private record SplitDef(Pattern pattern, String label) {
    }

    // Odin SplitsManager.MORT_REGEX / BLOOD_OPEN_REGEX / PORTAL_ENTRY_REGEX / DUNGEON_CLEARED_REGEX.
    private static final SplitDef BLOOD_OPEN = def(
            "\\[NPC\\] Mort: Here, I found this map when I first entered the dungeon\\.|\\[NPC\\] Mort: Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!",
            "§2Blood Open");
    private static final SplitDef BLOOD_CLEAR = def(
            "^\\[BOSS\\] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$|^The BLOOD DOOR has been opened!$",
            "§bBlood Clear");
    private static final SplitDef PORTAL_ENTRY = def(
            "\\[BOSS\\] The Watcher: You have proven yourself\\. You may pass\\.", "§dPortal Entry");
    private static final SplitDef TOTAL = def(
            "^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$", "§1Total");

    // ---------------------------------------------------------------------------------------------
    // Clear-phase splits (2026-09-16) - optional, off by default (SplitTimersConfig.isClearSplits()).
    // Ported from Devonian api/dungeon/Stages.kt's Clear tree + features/dungeons/clear/RunSplits.kt
    // ("Displays how long your party has take to complete Blood Rush, Blood Open & Boss Enter") and
    // features/dungeons/clear/WatcherSplits.kt ("Displays Dialog Time, Watcher Move and Blood Clear").
    //
    // With this off the list is exactly the three Odin rows this feature shipped with:
    //   Blood Open  = Mort's line          -> blood door opened / Watcher greeting
    //   Blood Clear = blood door opened    -> "You have proven yourself. You may pass."
    //   Portal Entry= "You have proven..." -> the boss's own entry dialogue
    // With it on, those same two spans are cut into Devonian's four:
    //   Blood Rush      = Mort's line              -> Blood Key obtained (Devonian: Clear's start ->
    //                     RunSplits' "Blood Rush"; the rush itself)
    //   Blood Open      = Blood Key obtained       -> blood door opened (Devonian Stages.BloodOpen "&4Blood")
    //   Watcher Dialogue= blood door opened        -> "Let's see how you can handle this."
    //                     (Devonian Stages.WatcherDialog "&cWatcher Dialog")
    //   Blood Clear     = "Let's see how you..."   -> "You have proven yourself." (Devonian Stages.WatcherClear)
    // Boss Entry (Devonian Stages.BossEntry "&9Boss Entry", = Mort's line -> boss entry dialogue) is the row the
    // HUD already synthesises by summing every clear segment; it now sums however many there are.
    //
    // Blood Key line: the three real forms this repo already matches in doorhelpers/LookAtDoorFeature.onChat
    // ("has obtained Blood Key", "Blood Key was picked up", "RIGHT CLICK on the BLOOD DOOR ...").
    private static final SplitDef BLOOD_RUSH = def(BLOOD_OPEN.pattern().pattern(), "§2Blood Rush");
    private static final SplitDef BLOOD_KEY = def(
            "^(?:.*(?:has obtained Blood Key|Blood Key was picked up).*|RIGHT CLICK on the BLOOD DOOR.*)$",
            "§2Blood Open");
    private static final SplitDef WATCHER_DIALOGUE = def(BLOOD_CLEAR.pattern().pattern(), "§cWatcher Dialogue");
    // Devonian WatcherSplits: WatcherDialog's stop trigger, i.e. the line the blood mobs start on.
    private static final SplitDef WATCHER_FIGHT = def(
            "^\\[BOSS\\] The Watcher: Let's see how you can handle this\\.$", "§bBlood Clear");

    /** Devonian's four clear segments, or the original three, depending on {@code clearSplits}. */
    private static List<SplitDef> clearPrefix(boolean clearSplits) {
        return clearSplits
                ? List.of(BLOOD_RUSH, BLOOD_KEY, WATCHER_DIALOGUE, WATCHER_FIGHT, PORTAL_ENTRY)
                : List.of(BLOOD_OPEN, BLOOD_CLEAR, PORTAL_ENTRY);
    }

    /** Index in {@link #clearPrefix} of the split that fires when the blood door opens - the moment the Watcher
     *  Move clock starts, and so the row the Watcher Move row is inserted after. */
    private static int doorOpenIndex(boolean clearSplits) {
        return clearSplits ? 2 : 1;
    }

    private static final List<SplitDef> ENTRANCE_SPLITS = List.of();

    private static final List<SplitDef> FLOOR1 = List.of(
            def("^\\[BOSS\\] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\.$", "§cBonzo's Sike"),
            def("\\[BOSS\\] Bonzo: Oh I'm dead!", "§4Cleared"));

    private static final List<SplitDef> FLOOR2 = List.of(
            def("^\\[BOSS\\] Scarf: This is where the journey ends for you, Adventurers\\.$", "§cScarf's minions"),
            def("^\\[BOSS\\] Scarf: Did you forget\\? I was taught by the best! Let's dance\\.$", "§4Cleared"));

    private static final List<SplitDef> FLOOR3 = List.of(
            def("^\\[BOSS\\] The Professor: I was burdened with terrible news recently\\.\\.\\.$", "§cThe Guardians"),
            def("^\\[BOSS\\] The Professor: Oh\\? You found my Guardians' one weakness\\?$", "§aThe Professor"),
            def("^\\[BOSS\\] The Professor: What\\?! My Guardian power is unbeatable!$", "§4Cleared"));

    private static final List<SplitDef> FLOOR4 = List.of(
            def("^\\[BOSS\\] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$", "§4Cleared"));

    private static final List<SplitDef> FLOOR5 = List.of(
            def("^\\[BOSS\\] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$", "§4Cleared"));

    private static final List<SplitDef> FLOOR6 = List.of(
            def("^\\[BOSS\\] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!$", "§cTerracottas"),
            def("^\\[BOSS\\] Sadan: ENOUGH!$", "§aGiants"),
            def("^\\[BOSS\\] Sadan: You did it\\. I understand now, you have earned my respect\\.$", "§4Cleared"));

    private static final List<SplitDef> FLOOR7 = List.of(
            def("^\\[BOSS\\] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$", "§5Maxor"),
            def("\\[BOSS\\] Storm: Pathetic Maxor, just like expected\\.", "§3Storm"),
            def("\\[BOSS\\] Goldor: Who dares trespass into my domain\\?", "§6Terminals"),
            def("The Core entrance is opening!", "§7Goldor"),
            def("\\[BOSS\\] Necron: You went further than any human before, congratulations\\.", "§cNecron"),
            def("\\[BOSS\\] Necron: All this, for nothing\\.\\.\\.", "§4Cleared"));

    private static SplitDef def(String regex, String label) {
        return new SplitDef(Pattern.compile(regex), label);
    }

    /** Odin's buildDungeonSplits: [Blood Open, Blood Clear, Portal Entry] + floor splits + [Total] - or, with Clear
     *  Splits on, Devonian's finer clear prefix in place of those three ({@link #clearPrefix}).
     *  Empty when the floor is unknown (Odin returns early in that case too). */
    private static List<SplitDef> splitsForFloor(String floor, boolean clearSplits) {
        if (floor == null || floor.isEmpty()) {
            return List.of();
        }
        List<SplitDef> bossSplits;
        if (floor.startsWith("E")) {
            bossSplits = ENTRANCE_SPLITS;
        } else {
            int number;
            try {
                number = Integer.parseInt(floor.substring(1));
            } catch (Exception e) {
                return List.of();
            }
            bossSplits = switch (number) {
                case 1 -> FLOOR1;
                case 2 -> FLOOR2;
                case 3 -> FLOOR3;
                case 4 -> FLOOR4;
                case 5 -> FLOOR5;
                case 6 -> FLOOR6;
                case 7 -> FLOOR7;
                default -> null;
            };
            if (bossSplits == null) {
                return List.of();
            }
        }
        List<SplitDef> all = new ArrayList<>(clearPrefix(clearSplits));
        all.addAll(bossSplits);
        all.add(TOTAL);
        return List.copyOf(all);
    }

    private static final class RunState {
        List<SplitDef> splits = List.of();
        /** Wall-clock time each split's trigger line fired (0 = not yet). Parallel to {@link #splits}. */
        long[] timeMs = new long[0];
        /** How many of {@link #splits} are clear-phase splits (3, or 5 with Clear Splits on) - the Boss Entry row
         *  is their sum and is inserted after them. Snapshotted per run so a mid-run toggle can't desync it. */
        int clearCount = 3;
        /** Index of the split that fires when the blood door opens - see {@link #doorOpenIndex}. */
        int doorOpenIndex = 1;
    }

    private record SplitRow(String name, long timeMs, boolean isCurrent) {
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
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(SplitTimersFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        // Odin resets its splits on LevelEvent.Load - same here, on any real level change.
        if (client.level != lastLevel) {
            if (lastLevel != null && run.splits.size() > 0) {
                LOGGER.info("[SplitTimers] Level changed - run state reset (had {} recorded splits)", recordedCount());
            }
            if (lastLevel != null) {
                run = new RunState();
                P5Splits.reset();
                WatcherMoveTracker.reset();
                CoreEntryTimes.reset();
            }
            lastLevel = client.level;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        if (inDungeon != wasInDungeon) {
            LOGGER.info("[SplitTimers] DungeonState.isInDungeon {} -> {} (floor={}){}", wasInDungeon, inDungeon,
                    DungeonState.getFloor(), !inDungeon && wasInDungeon
                            ? " - run state reset (had " + recordedCount() + " recorded splits)" : "");
        }
        if (!inDungeon && wasInDungeon) {
            run = new RunState();
            P5Splits.reset();
            WatcherMoveTracker.reset();
            CoreEntryTimes.reset();
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

    private static int recordedCount() {
        int n = 0;
        for (long t : run.timeMs) {
            if (t != 0L) {
                n++;
            }
        }
        return n;
    }

    private static void onChatMessage(Component message) {
        if (!SplitTimersConfig.getInstance().isEnabled()) {
            // Diagnostic (2026-09-14): only the run-start line is worth a "why nothing happened" note.
            String plainOff = ChatFormatting.stripFormatting(message.getString());
            if ("Starting in 1 second.".equals(plainOff != null ? plainOff : message.getString())) {
                LOGGER.info("[SplitTimers] Saw \"Starting in 1 second.\" but Split Timers is disabled - no run started.");
            }
            return;
        }
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to match the raw
        // un-stripped string - real Hypixel boss/sidebar lines are confirmed (DungeonState's own
        // BOSS_START_PATTERN fix) to embed §-codes mid-word. The exact-equality "Starting in 1 second."
        // check is the single trigger that starts an entire run - if Hypixel ever embeds a stray code in
        // that line, Split Timers silently never starts at all, and the same risk applies to every real
        // boss-dialogue split trigger below.
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();

        if ("Starting in 1 second.".equals(raw)) {
            boolean clearSplits = SplitTimersConfig.getInstance().isClearSplits();
            RunState fresh = new RunState();
            fresh.splits = splitsForFloor(DungeonState.getFloor(), clearSplits);
            fresh.timeMs = new long[fresh.splits.size()];
            fresh.clearCount = clearPrefix(clearSplits).size();
            fresh.doorOpenIndex = doorOpenIndex(clearSplits);
            run = fresh;
            P5Splits.reset();
            WatcherMoveTracker.reset();
            CoreEntryTimes.reset();
            finishDelayTicks = -1;
            if (fresh.splits.isEmpty()) {
                LOGGER.warn("[SplitTimers] Run START line seen but NO splits resolved for floor={} (inDungeon={}) - nothing will be timed this run",
                        DungeonState.getFloor(), DungeonState.isInDungeon());
            } else {
                LOGGER.info("[SplitTimers] Run STARTED: floor={}, {} splits, clock starts on \"{}\"",
                        DungeonState.getFloor(), fresh.splits.size(), plainLabel(fresh.splits.get(0)));
            }
            return;
        }

        // Side hooks that are NOT splits: they read the same real lines but must work whether or not Clear Splits
        // put that line in the split list, so they are matched before (and independently of) the split loop.
        if (BLOOD_CLEAR.pattern().matcher(raw).matches()) {
            WatcherMoveTracker.onBloodDoorOpened();
        } else if (WATCHER_FIGHT.pattern().matcher(raw).matches()) {
            WatcherMoveTracker.onDialogueEnd();
        } else if ("The Core entrance is opening!".equals(raw)) {
            CoreEntryTimes.onCoreOpening();
        } else if ("[BOSS] Necron: You went further than any human before, congratulations.".equals(raw)) {
            CoreEntryTimes.onPhaseFourStarted();
        }

        if (run.splits.isEmpty()) {
            return;
        }
        // Real bug found and fixed (2026-09-14, real-run log analysis): every split used to record its OWN
        // label as the segment that just ENDED ("Maxor took" = Starting-in-1-second -> Maxor's entry line,
        // i.e. the whole clear; "Storm took" = Maxor's fight...) and the pre-boss/Total splits were missing
        // entirely. Odin's SplitsManager names each split after the phase that STARTS at its line and
        // reports "<previous split> took X" - ported exactly now, including Odin's any-order full-line match
        // (Kotlin Regex.matches) with first-match-only per split.
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
        long now = System.currentTimeMillis();
        run.timeMs[index] = now;
        SplitDef split = run.splits.get(index);

        // Diagnostic (2026-09-14): an earlier split never fired - its line was missed (dialogue skipped /
        // regex mismatch / joined late). The segment before this one then spans the missed phase too.
        for (int i = 0; i < index; i++) {
            if (run.timeMs[i] == 0L) {
                LOGGER.warn("[SplitTimers] OUT-OF-ORDER split line: \"{}\" (index {}) fired while \"{}\" (index {}) never did. Line: \"{}\"",
                        plainLabel(split), index, plainLabel(run.splits.get(i)), i, raw);
                break;
            }
        }

        if (index == 0) {
            LOGGER.info("[SplitTimers] Run clock STARTED on \"{}\" line: \"{}\"", plainLabel(split), raw);
            return;
        }
        int prev = previousRecorded(index);
        if (prev < 0) {
            // Odin would compute against a zero timestamp here and print garbage - skip the announce.
            LOGGER.warn("[SplitTimers] SPLIT \"{}\" hit with no earlier split recorded - nothing to time. Line: \"{}\"",
                    plainLabel(split), raw);
            return;
        }
        long segment = now - run.timeMs[prev];
        long total = now - run.timeMs[firstRecorded()];
        boolean last = index == run.splits.size() - 1;
        LOGGER.info("[SplitTimers] SPLIT \"{}\" took {}ms (total={}ms), now running \"{}\", trigger line: \"{}\"",
                plainLabel(run.splits.get(prev)), segment, total, last ? "(run complete)" : plainLabel(split), raw);

        Minecraft client = Minecraft.getInstance();
        if (!SplitTimersConfig.getInstance().isAnnounceInChat() || client.player == null) {
            return;
        }
        // Orange-themed (2026-09-14): split names and times in light orange, "took" in the neutral body color.
        // Chat uses the plain split label - Odin's per-split §-colors stay on the HUD only.
        Component tookLine = tookLine(plainLabel(run.splits.get(prev)),
                String.format(Locale.US, "%.2fs", segment / 1000.0), "!");
        if (!last) {
            client.player.sendSystemMessage(tookLine);
            return;
        }
        // Odin's finishRun: rows snapshotted now, printed 10 ticks later after Hypixel's own summary.
        List<Component> lines = new ArrayList<>();
        lines.add(tookLine);
        lines.add(tookLine("Total time", String.format(Locale.US, "%.2fs", total / 1000.0), "!"));
        List<SplitRow> rows = currentRows();
        for (int i = 0; i < rows.size(); i++) {
            SplitRow row = rows.get(i);
            String rowName = i == rows.size() - 1 ? "Total" : ChatFormatting.stripFormatting(row.name());
            lines.add(tookLine(rowName, formatTime(row.timeMs()), "."));
        }
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();
        long watcherMoveMs = cfg.isWatcherMoveSplit() ? WatcherMoveTracker.getMoveMs() : 0L;
        if (watcherMoveMs != 0L) {
            lines.add(tookLine("Watcher Move", formatTime(watcherMoveMs), "."));
        }
        for (String core : CoreEntryTimes.lines()) {
            lines.add(Component.empty().append(ModChat.text("Core ")).append(Component.literal(core)));
        }
        for (String p5 : P5Splits.lines(cfg.isP5DragonLines(), cfg.isP5RelicLines(), true)) {
            lines.add(Component.empty().append(ModChat.text("P5 ")).append(Component.literal(p5)));
        }
        pendingFinishMessages = lines;
        finishDelayTicks = 10;
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

    /** Odin's SplitsManager.currentRows: one row per split (segment time = next split's time - this one's,
     *  live for the running segment), last row = total. A missed split line (time 0) keeps a 0 row and the
     *  segment before it runs on to the next split that did fire. Empty before the clock starts. */
    private static List<SplitRow> currentRows() {
        int n = run.splits.size();
        int first = firstRecorded();
        if (n == 0 || first < 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long lastTime = run.timeMs[n - 1];
        long latest = lastTime != 0L ? lastTime : now;
        int current = currentIndex();
        List<SplitRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n - 1; i++) {
            long t = 0L;
            if (run.timeMs[i] != 0L) {
                long end = latest;
                for (int j = i + 1; j < n; j++) {
                    if (run.timeMs[j] != 0L) {
                        end = run.timeMs[j];
                        break;
                    }
                }
                t = end - run.timeMs[i];
            }
            rows.add(new SplitRow(run.splits.get(i).label(), t, i == current));
        }
        rows.add(new SplitRow(run.splits.get(n - 1).label(), latest - run.timeMs[first], false));
        return rows;
    }

    /** Odin's Utils.formatTime(time, 2): "1h 2m 3.45s". */
    private static String formatTime(long timeMs) {
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

    private static String plainLabel(SplitDef def) {
        String stripped = ChatFormatting.stripFormatting(def.label());
        return stripped != null ? stripped : def.label();
    }

    /** For {@code DeviceTimesFeature}'s own "next to it" completion-time messages (killer560's own
     *  request: "use the split timer to figure out when p2 started") - the real wall-clock moment the
     *  CURRENTLY in-progress split segment began, i.e. exactly when the line that started it fired. 0 if
     *  no segment is running. */
    public static long getCurrentSegmentStartedAtMs() {
        int current = currentIndex();
        return current < 0 ? 0L : run.timeMs[current];
    }

    /** The plain (formatting-stripped) name of the split segment currently in progress - e.g. "Terminals"
     *  from Goldor's "Who dares trespass" line until "The Core entrance is opening!" - the one
     *  {@link #getCurrentSegmentStartedAtMs()}'s timestamp belongs to. Null if no segment is running.
     *  <p>Real bug found and fixed (2026-09-14, real-run log analysis): this used to return the NEXT
     *  expected split's label, so during terminals it said "Goldor" (one phase ahead). */
    public static String getCurrentSegmentLabel() {
        int current = currentIndex();
        return current < 0 ? null : plainLabel(run.splits.get(current));
    }

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
            return 320;
        }

        private static final int SPLIT_COLUMN_WIDTH = 160;
        private static final int P5_COLUMN_WIDTH = 130;

        @Override
        public int width() {
            return SPLIT_COLUMN_WIDTH + (p5Lines().isEmpty() || !SplitTimersConfig.getInstance().isP5LinesRight() ? 0 : P5_COLUMN_WIDTH);
        }

        @Override
        public int height() {
            int rows = displayRows().size();
            int p5 = p5Lines().size();
            int core = coreLines().size();
            int total = SplitTimersConfig.getInstance().isP5LinesRight() ? Math.max(rows, p5) + core : rows + p5 + core;
            return 12 * Math.max(1, total);
        }

        /** F7/M7 "time to enter the core after terms" rows ({@link CoreEntryTimes}), with a header when there are
         *  any. In the HUD editor a sample is shown while the toggle is on, so the block can be positioned. */
        private static List<String> coreLines() {
            if (!SplitTimersConfig.getInstance().isCoreEntryTimes()) {
                return List.of();
            }
            if (Minecraft.getInstance().screen instanceof com.killer560.hub.hud.HudEditorScreen) {
                return CoreEntryTimes.editorLines();
            }
            List<String> lines = CoreEntryTimes.lines();
            if (lines.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(lines.size() + 1);
            out.add("§6§lCore Entry");
            out.addAll(lines);
            return out;
        }

        /** M7 Phase 5 dragon/relic lines ({@link P5Splits}), with a header row when there are any. In the HUD
         *  editor a sample is shown while either toggle is on, so the column can be positioned before a run. */
        private static List<String> p5Lines() {
            SplitTimersConfig cfg = SplitTimersConfig.getInstance();
            if (!cfg.isP5DragonLines() && !cfg.isP5RelicLines()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            if (Minecraft.getInstance().screen instanceof com.killer560.hub.hud.HudEditorScreen) {
                out.add("§5§lP5");
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
            List<String> lines = P5Splits.lines(cfg.isP5DragonLines(), cfg.isP5RelicLines(), false);
            if (lines.isEmpty()) {
                return List.of();
            }
            out.add("§5§lP5");
            out.addAll(lines);
            return out;
        }

        /** Odin's Splits HUD: every row but the Total, with a "Boss Entry" row (Devonian {@code Stages.BossEntry} -
         *  the sum of every clear segment, however many Clear Splits made) after the last clear split, hiding rows
         *  still at 0. With Watcher Move on and measured, Devonian's {@code WatcherSplits} "Watcher Move" row is
         *  inserted right after the segment that starts when the blood door opens. */
        private static List<SplitRow> displayRows() {
            List<SplitRow> rows = currentRows();
            if (rows.isEmpty()) {
                return List.of();
            }
            List<SplitRow> segments = rows.subList(0, rows.size() - 1);
            int clearCount = Math.min(run.clearCount, segments.size());
            long watcherMoveMs = SplitTimersConfig.getInstance().isWatcherMoveSplit()
                    ? WatcherMoveTracker.getMoveMs() : 0L;
            List<SplitRow> out = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                SplitRow row = segments.get(i);
                if (row.timeMs() != 0L) {
                    out.add(row);
                }
                if (i == run.doorOpenIndex && watcherMoveMs != 0L) {
                    out.add(new SplitRow("§cWatcher Move", watcherMoveMs, false));
                }
                if (i == clearCount - 1 && segments.size() > clearCount) {
                    long bossTime = 0L;
                    for (int j = 0; j < clearCount; j++) {
                        bossTime += segments.get(j).timeMs();
                    }
                    if (bossTime != 0L) {
                        out.add(new SplitRow("§9Boss Entry", bossTime, false));
                    }
                }
            }
            return out;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            var screen = Minecraft.getInstance().screen;
            if (!SplitTimersConfig.getInstance().isEnabled()
                    || (screen != null && !(screen instanceof com.killer560.hub.hud.HudEditorScreen))) {
                return;
            }
            int lineY = y;
            if (screen == null) {
                for (SplitRow row : displayRows()) {
                    String text = row.name() + "§f: " + formatTime(row.timeMs());
                    graphics.text(Minecraft.getInstance().font, text, x, lineY, 0xFFFFFFFF, false);
                    lineY += 12;
                }
            }
            boolean right = SplitTimersConfig.getInstance().isP5LinesRight();
            int p5X = right ? x + SPLIT_COLUMN_WIDTH : x;
            int p5Y = right ? y : lineY;
            for (String line : p5Lines()) {
                graphics.text(Minecraft.getInstance().font, line, p5X, p5Y, 0xFFFFFFFF, false);
                p5Y += 12;
            }
            // Core entry rows always sit in the left column, under whatever is already there.
            int coreY = right ? lineY : p5Y;
            for (String line : coreLines()) {
                graphics.text(Minecraft.getInstance().font, line, x, coreY, 0xFFFFFFFF, false);
                coreY += 12;
            }
        }
    }
}
