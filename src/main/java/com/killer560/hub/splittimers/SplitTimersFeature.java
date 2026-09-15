package com.killer560.hub.splittimers;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
 */
public final class SplitTimersFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-splittimers");

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

    /** Odin's buildDungeonSplits: [Blood Open, Blood Clear, Portal Entry] + floor splits + [Total].
     *  Empty when the floor is unknown (Odin returns early in that case too). */
    private static List<SplitDef> splitsForFloor(String floor) {
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
        List<SplitDef> all = new ArrayList<>();
        all.add(BLOOD_OPEN);
        all.add(BLOOD_CLEAR);
        all.add(PORTAL_ENTRY);
        all.addAll(bossSplits);
        all.add(TOTAL);
        return List.copyOf(all);
    }

    private static final class RunState {
        List<SplitDef> splits = List.of();
        /** Wall-clock time each split's trigger line fired (0 = not yet). Parallel to {@link #splits}. */
        long[] timeMs = new long[0];
    }

    private record SplitRow(String name, long timeMs, boolean isCurrent) {
    }

    private static RunState run = new RunState();
    /** Client ticks left before the end-of-run summary prints (Odin: {@code schedule(10)}); -1 = none. */
    private static int finishDelayTicks = -1;
    private static List<String> pendingFinishMessages = List.of();

    private SplitTimersFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
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
        }
        wasInDungeon = inDungeon;

        if (finishDelayTicks >= 0 && --finishDelayTicks < 0) {
            if (SplitTimersConfig.getInstance().isAnnounceInChat() && client.player != null) {
                for (String line : pendingFinishMessages) {
                    client.player.sendSystemMessage(Component.literal(line));
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
            RunState fresh = new RunState();
            fresh.splits = splitsForFloor(DungeonState.getFloor());
            fresh.timeMs = new long[fresh.splits.size()];
            run = fresh;
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
        String tookLine = String.format(Locale.US, "§6%s §7took §6%.2fs§7!", run.splits.get(prev).label(), segment / 1000.0);
        if (!last) {
            client.player.sendSystemMessage(Component.literal(tookLine));
            return;
        }
        // Odin's finishRun: rows snapshotted now, printed 10 ticks later after Hypixel's own summary.
        List<String> lines = new ArrayList<>();
        lines.add(tookLine);
        lines.add(String.format(Locale.US, "§6Total time §7took §6%.2fs§7!", total / 1000.0));
        List<SplitRow> rows = currentRows();
        for (int i = 0; i < rows.size(); i++) {
            SplitRow row = rows.get(i);
            lines.add("§6" + (i == rows.size() - 1 ? "Total" : row.name()) + " §7took §6" + formatTime(row.timeMs()) + "§7.");
        }
        pendingFinishMessages = lines;
        finishDelayTicks = 10;
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

        @Override
        public int width() {
            return 160;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, displayRows().size());
        }

        /** Odin's Splits HUD: every row but the Total, with a "Boss Entry" row (sum of the first three
         *  segments) after Portal Entry, hiding rows still at 0. */
        private static List<SplitRow> displayRows() {
            List<SplitRow> rows = currentRows();
            if (rows.isEmpty()) {
                return List.of();
            }
            List<SplitRow> segments = rows.subList(0, rows.size() - 1);
            List<SplitRow> out = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                SplitRow row = segments.get(i);
                if (row.timeMs() != 0L) {
                    out.add(row);
                }
                if (i == 2 && rows.size() > 3) {
                    long bossTime = segments.get(0).timeMs() + segments.get(1).timeMs() + segments.get(2).timeMs();
                    if (bossTime != 0L) {
                        out.add(new SplitRow("§9Boss Entry", bossTime, false));
                    }
                }
            }
            return out;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!SplitTimersConfig.getInstance().isEnabled() || Minecraft.getInstance().screen != null) {
                return;
            }
            int lineY = y;
            for (SplitRow row : displayRows()) {
                String text = row.name() + "§f: " + formatTime(row.timeMs());
                graphics.text(Minecraft.getInstance().font, text, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
        }
    }
}
