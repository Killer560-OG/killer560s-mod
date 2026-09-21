package com.killer560.hub.runsummary;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.runstats.PlayerRunStats;
import com.killer560.hub.runstats.RunStatsTracker;
import com.killer560.hub.scorecalc.ScoreCalculator;
import com.killer560.hub.scorecalc.ScoreCalculatorFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.splittimers.SplitTimersConfig;
import com.killer560.hub.splittimers.SplitTimersFeature;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon Run Summary - one record per finished run, kept in {@link RunHistoryStore}, viewable any time
 * from {@code gui/tab/RunSummaryTab}. Informational only: it reads chat, the tab list and other features'
 * public state, and never clicks, moves or sends anything to the server.
 * <p>
 * Built on top of what already exists rather than re-deriving it:
 * <ul>
 * <li><b>Per-phase splits</b> come from {@code splittimers/SplitTimersFeature}'s public
 * {@link SplitTimersFeature#getCurrentSegmentLabel()} / {@link SplitTimersFeature#getCurrentSegmentStartedAtMs()}
 * - polled every tick, so a change of segment closes the previous one. Not a single split regex is
 * duplicated here. The consequence: <b>splits are only recorded while Split Timers itself is enabled</b>
 * (it does not track a run otherwise); everything else in the record works either way.</li>
 * <li><b>The score estimate</b> is {@code scorecalc/ScoreCalculatorFeature.currentResult()} - the same
 * live estimate the Score HUD shows, sampled every tick and kept as it stood when the run ended (that
 * method returns null once you are out of the dungeon, which is after the summary is assembled).
 * {@code totalSecrets} is taken from that same {@link ScoreCalculator.Result} instead of recomputing
 * Odin's secret-count formula.</li>
 * <li><b>Class and party size</b> are {@code leapmenu/PartyTracker.selfClass()} / {@code teammates()}.</li>
 * <li><b>End-of-run detection</b> is the pair the mod already keys on: Split Timers' own "☠ Defeated ... in ..."
 * Total line, and {@code dungeonqueue/DungeonQueueFeature}'s "&gt; EXTRA STATS &lt;" line. Hypixel's real
 * "Team Score: N (S+)" is read the same way {@code ScoreCalculatorFeature} reads it (that class only
 * LOGS it - see the report note about exposing it instead of matching it twice).</li>
 * <li><b>Chat</b> goes through {@code util/ChatObserver} + {@code util/ModChat}, like every other feature.</li>
 * </ul>
 * The raw per-run counters that no feature exposes publicly (secrets found, crypts, deaths, failed
 * puzzles) are read straight off the dungeon tab list here, with the same anchored patterns
 * {@code ScoreCalculatorFeature} uses - see the report for the one-line getter that would let this
 * reader be deleted outright.
 */
public final class RunSummaryFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-runsummary");
    public static final String FEATURE = "Run Summary";

    /** Split Timers' own run-arm line, matched by exact equality exactly as it does. */
    private static final String RUN_START_LINE = "Starting in 1 second.";
    /** Split Timers' TOTAL split line. */
    private static final Pattern DEFEATED =
            Pattern.compile("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$");
    /** Auto Requeue's own end-of-run trigger. */
    private static final Pattern EXTRA_STATS = Pattern.compile("(?m)^\\s*> EXTRA STATS <\\s*$");
    /** Same line ScoreCalculatorFeature matches (it only logs it). */
    private static final Pattern TEAM_SCORE = Pattern.compile("^\\s*Team Score: ([\\d,]+) \\(([A-DS+]+)\\)");
    /** Same completion line TerminalTimersFeature times, with its optional already-annotated suffix. */
    private static final Pattern DEVICE_COMPLETE =
            Pattern.compile("^(\\w{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d+)/(\\d+)\\)(?:\\s.*)?$");

    // Tab list - the same anchored lines ScoreCalculatorFeature reads.
    private static final Pattern TAB_SECRETS_COUNT = Pattern.compile("^\\s*Secrets Found: (\\d+)\\s*$");
    private static final Pattern TAB_CRYPTS = Pattern.compile("^\\s*Crypts: (\\d+)\\s*$");
    private static final Pattern TAB_DEATHS = Pattern.compile("^\\s*(?:Team )?Deaths: \\(?(\\d+)\\)?\\s*$");
    private static final Pattern TAB_PUZZLE_COUNT = Pattern.compile("^\\s*Puzzles: \\((\\d+)\\)\\s*$");
    private static final Pattern TAB_PUZZLE = Pattern.compile("^\\s*(\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?\\s*$");
    /** Same dungeon party row {@code leapmenu/PartyTracker} reads: "[lvl] Name (Class Lvl)". */
    private static final Pattern TAB_PARTY =
            Pattern.compile("^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((\\w+)(?: (\\w+))?\\)$");

    /** Ticks after the end-of-run line before the record is assembled - long enough for Hypixel's own
     *  score block (which prints AFTER "> EXTRA STATS <") to arrive. */
    private static final int FINISH_DELAY_TICKS = 100;
    private static final int FINISH_DELAY_AFTER_SCORE_TICKS = 20;
    private static final long DEVICE_DEDUPE_MS = 500L;

    // ---- per-run state ----
    private static boolean active = false;
    private static long runStartMs = 0L;
    private static long runEndMs = 0L;
    private static String floor = null;
    private static String hypixelTime = null;
    private static boolean hypixelNewRecord = false;
    private static int hypixelScore = RunRecord.UNKNOWN_INT;
    private static String hypixelRank = null;
    private static final List<RunRecord.Split> SPLITS = new ArrayList<>();
    private static final List<RunRecord.Device> DEVICES = new ArrayList<>();
    private static String currentSplitLabel = null;
    private static long currentSplitStartMs = 0L;
    private static ScoreCalculator.Result lastEstimate = null;
    private static int secretsFound = RunRecord.UNKNOWN_INT;
    private static int crypts = RunRecord.UNKNOWN_INT;
    private static int deaths = RunRecord.UNKNOWN_INT;
    private static int puzzleCount = RunRecord.UNKNOWN_INT;
    private static int puzzlesFailed = RunRecord.UNKNOWN_INT;
    private static String dungeonClass = null;
    private static int partySize = RunRecord.UNKNOWN_INT;
    /** Last map worth keeping, re-taken once a second while the dungeon is being scanned (see pollMap). */
    private static RunRecord.MapSnapshot mapSnapshot = null;
    /** Class level per lower-case IGN, straight off the dungeon tab list. */
    private static final java.util.Map<String, Integer> CLASS_LEVELS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, String> TAB_NAMES = new java.util.LinkedHashMap<>();

    private static int finishDelayTicks = -1;
    private static int pollCounter = 0;
    private static Object lastLevel = null;
    private static String lastDevicePlain = null;
    private static long lastDeviceAtMs = 0L;

    private RunSummaryFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(RunSummaryFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(RunSummaryFeature::tick);
    }

    // ------------------------------------------------------------------ tick

    private static void tick(Minecraft client) {
        if (finishDelayTicks >= 0 && --finishDelayTicks < 0) {
            finishRun();
            return;
        }
        if (!RunSummaryConfig.getInstance().isEnabled()) {
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (active) {
                if (runEndMs > 0L) {
                    // The end-of-run line was already seen - Auto Requeue or a warp just beat the assembly
                    // delay. Keep the completed run instead of throwing it away (2026-09-16 review).
                    LOGGER.info("[RunSummary] World changed after the run ended - saving it now (floor={})", floor);
                    finishRun();
                } else {
                    LOGGER.info("[RunSummary] World changed before the run ended - discarding the partial record (floor={})", floor);
                    resetRun();
                }
            }
        }
        if (!active) {
            return;
        }
        pollSplits();
        String liveFloor = DungeonState.getFloor();
        if (liveFloor != null && !liveFloor.isBlank()) {
            floor = liveFloor;
        }
        ScoreCalculator.Result estimate = ScoreCalculatorFeature.currentResult();
        if (estimate != null) {
            lastEstimate = estimate;
        }
        if (++pollCounter >= 20) {
            pollCounter = 0;
            readTabList(client);
            readParty();
            pollMap();
        }
    }

    /**
     * killer560, 2026-09-20: the run log "should log the map that was shown". Taken once a second from
     * {@code livemap/DungeonLayout} (a per-tick cached snapshot, so this is a read, not a re-scan) and kept
     * as the last non-empty one, because by the time the record is assembled the boss room has replaced the
     * map and a warp may already have wiped the grid.
     * <p>
     * Room scanning itself only runs while something consumes it (Live Map, Secret Waypoints, a puzzle
     * solver), so with all of those off there is simply no map to log and the record stores none.
     */
    private static void pollMap() {
        if (!DungeonState.isInDungeon()) {
            return;
        }
        try {
            com.killer560.hub.livemap.DungeonLayout layout = com.killer560.hub.livemap.DungeonLayout.current();
            int roomCount = layout.roomCount();
            if (roomCount <= 0) {
                return;
            }
            int cells = RunRecord.MapSnapshot.GRID * RunRecord.MapSnapshot.GRID;
            int[] rooms = new int[cells];
            int[] doors = new int[cells];
            for (int i = 0; i < cells; i++) {
                rooms[i] = layout.roomOfCell(i);
                int type = layout.doorType(i);
                doors[i] = type == 0 ? 0 : type | (layout.isLocked(i) ? RunRecord.MapSnapshot.LOCKED_BIT : 0);
            }
            List<RunRecord.MapRoom> roomList = new ArrayList<>(roomCount);
            for (int id = 0; id < roomCount; id++) {
                com.killer560.hub.roomdatabase.RoomEntry entry = layout.entry(id);
                roomList.add(new RunRecord.MapRoom(layout.name(id), entry == null ? null : entry.type));
            }
            RunRecord.MapSnapshot snapshot = new RunRecord.MapSnapshot(rooms, doors, roomList);
            // Keep the richest map seen: a late warp can reset the grid to a handful of rooms.
            if (mapSnapshot == null || roomList.size() >= mapSnapshot.rooms().size()) {
                mapSnapshot = snapshot;
            }
        } catch (Exception e) {
            LOGGER.debug("[RunSummary] Map snapshot failed", e);
        }
    }

    /** Closes a split segment whenever Split Timers moves on to the next one. No split regex here - the
     *  segment boundaries come straight from that feature's own public getters. */
    private static void pollSplits() {
        String label = SplitTimersFeature.getCurrentSegmentLabel();
        long start = SplitTimersFeature.getCurrentSegmentStartedAtMs();
        if (label == null || start <= 0L) {
            return;
        }
        if (label.equals(currentSplitLabel) && start == currentSplitStartMs) {
            return;
        }
        closeCurrentSplit(start);
        currentSplitLabel = label;
        currentSplitStartMs = start;
    }

    private static void closeCurrentSplit(long endMs) {
        if (currentSplitLabel != null && currentSplitStartMs > 0L && endMs > currentSplitStartMs) {
            SPLITS.add(new RunRecord.Split(currentSplitLabel, endMs - currentSplitStartMs));
        }
        currentSplitLabel = null;
        currentSplitStartMs = 0L;
    }

    private static void readTabList(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }
        int failed = 0;
        boolean sawPuzzleHeader = false;
        boolean sawPuzzleRow = false;
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null || plain.isBlank()) {
                continue;
            }
            Matcher m;
            if ((m = TAB_SECRETS_COUNT.matcher(plain)).matches()) {
                secretsFound = parseInt(m.group(1), secretsFound);
            } else if ((m = TAB_CRYPTS.matcher(plain)).matches()) {
                crypts = parseInt(m.group(1), crypts);
            } else if ((m = TAB_DEATHS.matcher(plain)).matches()) {
                deaths = parseInt(m.group(1), deaths);
            } else if ((m = TAB_PUZZLE_COUNT.matcher(plain)).matches()) {
                puzzleCount = parseInt(m.group(1), puzzleCount);
                sawPuzzleHeader = true;
            } else if ((m = TAB_PUZZLE.matcher(plain)).matches()) {
                sawPuzzleRow = true;
                if ("✖".equals(m.group(2))) {
                    failed++;
                }
            } else if ((m = TAB_PARTY.matcher(plain.trim())).matches()) {
                // "DEAD"/"EMPTY" rows carry no class level - keep whatever was read before, like PartyTracker.
                String key = m.group(1).toLowerCase(Locale.US);
                TAB_NAMES.putIfAbsent(key, m.group(1));
                Integer level = romanOrNumber(m.group(3));
                if (level != null) {
                    CLASS_LEVELS.put(key, level);
                }
            }
        }
        if (sawPuzzleHeader || sawPuzzleRow) {
            puzzlesFailed = failed;
        }
    }

    /**
     * The party as the run log stores it: one row per member, <b>keyed on UUID, not the IGN</b> (killer560's
     * standing rule - a name change must never lose history), with the per-player numbers from
     * {@code runstats/RunStatsTracker} merged in by name.
     * <p>
     * The UUID comes from the real tab-list entry for that IGN, which Hypixel keeps alongside the dungeon
     * display rows; a member whose entry can't be found is stored with a null UUID and shows their recorded
     * name forever, which is the honest answer rather than a made-up id.
     */
    private static List<RunRecord.PartyMember> buildPartyRows(Minecraft client) {
        java.util.Map<String, PlayerRunStats> stats = new java.util.LinkedHashMap<>();
        try {
            for (PlayerRunStats row : RunStatsTracker.buildRows(client)) {
                stats.put(row.name().toLowerCase(Locale.US), row);
            }
        } catch (Exception e) {
            LOGGER.debug("[RunSummary] Run Stats rows unavailable", e);
        }
        // Case-insensitive dedupe: the tab list, Run Stats and PartyTracker can each spell the same IGN
        // slightly differently, and one row per player is the whole point.
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> e : TAB_NAMES.entrySet()) {
            names.putIfAbsent(e.getKey(), e.getValue());
        }
        for (PlayerRunStats row : stats.values()) {
            names.putIfAbsent(row.name().toLowerCase(Locale.US), row.name());
        }
        if (client.player != null) {
            String self = client.player.getGameProfile().name();
            names.putIfAbsent(self.toLowerCase(Locale.US), self);
        }
        for (String teammate : PartyTracker.teammates()) {
            names.putIfAbsent(teammate.toLowerCase(Locale.US), teammate);
        }
        List<RunRecord.PartyMember> out = new ArrayList<>();
        for (String name : names.values()) {
            String key = name.toLowerCase(Locale.US);
            PlayerRunStats row = stats.get(key);
            DungeonClass cls = PartyTracker.classOf(name);
            Integer level = CLASS_LEVELS.get(key);
            out.add(new RunRecord.PartyMember(
                    uuidOf(client, name),
                    name,
                    cls != null ? cls.displayName() : (row != null && row.dungeonClass() != null
                            ? row.dungeonClass().displayName() : null),
                    level == null ? RunRecord.UNKNOWN_INT : level,
                    row != null ? row.soloRooms() : RunRecord.UNKNOWN_INT,
                    row != null ? row.stackedRooms() : RunRecord.UNKNOWN_INT,
                    row != null ? row.secrets() : RunRecord.UNKNOWN_INT,
                    row != null ? row.deaths().size() : RunRecord.UNKNOWN_INT));
        }
        return out;
    }

    /** The real account UUID behind an IGN, from the tab list. Hypixel's filler/NPC entries use non-v4
     *  UUIDs, so those are rejected the same way {@code profileviewer/ProfileViewerFeature} rejects them. */
    private static String uuidOf(Minecraft client, String name) {
        if (client.getConnection() == null) {
            return null;
        }
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            java.util.UUID id = info.getProfile().id();
            if (id != null && id.version() == 4 && name.equalsIgnoreCase(info.getProfile().name())) {
                return id.toString();
            }
        }
        return null;
    }

    /** The CURRENT IGN for a stored party member: looked up by UUID so a rename still resolves, falling back
     *  to the name the run was logged with. Used by the {@code /log} run log. */
    public static String currentIgn(RunRecord.PartyMember member) {
        if (member == null) {
            return "?";
        }
        if (member.uuid() != null) {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client.getConnection() != null) {
                    java.util.UUID id = java.util.UUID.fromString(member.uuid());
                    PlayerInfo info = client.getConnection().getPlayerInfo(id);
                    if (info != null && info.getProfile().name() != null && !info.getProfile().name().isBlank()) {
                        return info.getProfile().name();
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the recorded name.
            }
        }
        return member.name();
    }

    private static Integer romanOrNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return com.killer560.hub.croesus.DungeonChestValuer.roman(text.trim());
    }

    private static void readParty() {
        DungeonClass self = PartyTracker.selfClass();
        if (self != null) {
            dungeonClass = self.displayName();
        }
        int size = PartyTracker.teammates().size() + 1;
        if (size > partySize) {
            // Someone dying/disconnecting shouldn't retroactively shrink the party the run was done with.
            partySize = size;
        }
    }

    // ------------------------------------------------------------------ chat

    private static void onChat(Component message) {
        if (!RunSummaryConfig.getInstance().isEnabled()) {
            return;
        }
        String raw = ChatObserver.strip(message);
        String trimmed = raw.trim();

        if (RUN_START_LINE.equals(raw)) {
            startRun();
            return;
        }
        if (!active) {
            return;
        }

        Matcher defeated = DEFEATED.matcher(raw);
        if (defeated.matches()) {
            long now = System.currentTimeMillis();
            closeCurrentSplit(now);
            runEndMs = now;
            hypixelTime = defeated.group(2) == null ? null : defeated.group(2).trim();
            hypixelNewRecord = defeated.group(3) != null;
            if (finishDelayTicks < 0) {
                finishDelayTicks = FINISH_DELAY_TICKS;
            }
            LOGGER.info("[RunSummary] Run ended (\"{}\") - assembling the record in {} ticks", trimmed, finishDelayTicks);
            return;
        }
        if (EXTRA_STATS.matcher(raw).find()) {
            if (runEndMs <= 0L) {
                long now = System.currentTimeMillis();
                closeCurrentSplit(now);
                runEndMs = now;
            }
            if (finishDelayTicks < 0) {
                finishDelayTicks = FINISH_DELAY_TICKS;
            }
            return;
        }
        Matcher score = TEAM_SCORE.matcher(trimmed);
        if (score.find()) {
            hypixelScore = parseInt(score.group(1).replace(",", ""), RunRecord.UNKNOWN_INT);
            hypixelRank = score.group(2);
            if (finishDelayTicks > FINISH_DELAY_AFTER_SCORE_TICKS) {
                finishDelayTicks = FINISH_DELAY_AFTER_SCORE_TICKS;
            }
            return;
        }
        if (RunSummaryConfig.getInstance().isRecordDeviceTimes()) {
            recordDevice(trimmed);
        }
    }

    private static void recordDevice(String plain) {
        Matcher m = DEVICE_COMPLETE.matcher(plain);
        if (!m.matches()) {
            return;
        }
        long now = System.currentTimeMillis();
        // The same real line can reach chat twice (another mod re-adding its own annotated copy) - the
        // annotated one is a prefix-extension of the original, so compare both ways inside a short window.
        if (lastDevicePlain != null && now - lastDeviceAtMs <= DEVICE_DEDUPE_MS
                && (plain.startsWith(lastDevicePlain) || lastDevicePlain.startsWith(plain))) {
            return;
        }
        lastDevicePlain = plain;
        lastDeviceAtMs = now;
        long segmentStart = SplitTimersFeature.getCurrentSegmentStartedAtMs();
        String phase = SplitTimersFeature.getCurrentSegmentLabel();
        DEVICES.add(new RunRecord.Device(m.group(1), m.group(3), parseInt(m.group(4), 0), parseInt(m.group(5), 0),
                phase, segmentStart > 0L ? now - segmentStart : -1L));
    }

    // ------------------------------------------------------------------ run lifecycle

    private static void startRun() {
        resetRun();
        active = true;
        runStartMs = System.currentTimeMillis();
        floor = DungeonState.getFloor();
        LOGGER.info("[RunSummary] Run started (floor={})", floor);
    }

    private static void resetRun() {
        active = false;
        runStartMs = 0L;
        runEndMs = 0L;
        floor = null;
        hypixelTime = null;
        hypixelNewRecord = false;
        hypixelScore = RunRecord.UNKNOWN_INT;
        hypixelRank = null;
        SPLITS.clear();
        DEVICES.clear();
        currentSplitLabel = null;
        currentSplitStartMs = 0L;
        lastEstimate = null;
        secretsFound = RunRecord.UNKNOWN_INT;
        crypts = RunRecord.UNKNOWN_INT;
        deaths = RunRecord.UNKNOWN_INT;
        puzzleCount = RunRecord.UNKNOWN_INT;
        puzzlesFailed = RunRecord.UNKNOWN_INT;
        dungeonClass = null;
        partySize = RunRecord.UNKNOWN_INT;
        mapSnapshot = null;
        CLASS_LEVELS.clear();
        TAB_NAMES.clear();
        finishDelayTicks = -1;
        pollCounter = 0;
        lastDevicePlain = null;
    }

    private static void finishRun() {
        if (!active) {
            resetRun();
            return;
        }
        long end = runEndMs > 0L ? runEndMs : System.currentTimeMillis();
        closeCurrentSplit(end);
        String resolvedFloor = floor;
        if ((resolvedFloor == null || resolvedFloor.isBlank()) && lastEstimate != null) {
            resolvedFloor = DungeonState.getFloor();
        }
        ScoreCalculator.Result estimate = lastEstimate;
        RunRecord record = new RunRecord(
                runStartMs,
                end,
                resolvedFloor,
                resolvedFloor != null && resolvedFloor.startsWith("M"),
                runStartMs > 0L ? end - runStartMs : 0L,
                hypixelTime,
                hypixelNewRecord,
                List.copyOf(SPLITS),
                RunSummaryConfig.getInstance().isRecordDeviceTimes() ? List.copyOf(DEVICES) : List.of(),
                secretsFound,
                estimate != null ? estimate.totalSecrets() : RunRecord.UNKNOWN_INT,
                crypts,
                deaths,
                puzzlesFailed,
                puzzleCount,
                estimate != null ? estimate.total() : RunRecord.UNKNOWN_INT,
                estimate != null ? estimate.rank() : null,
                hypixelScore,
                hypixelRank,
                dungeonClass,
                partySize,
                // Croesus chest profit is claimed at the Croesus NPC AFTER the run, so nothing is known here
                // yet - see the report for the one-line CroesusProfitLog hook that would fill this in.
                RunRecord.UNKNOWN_PROFIT,
                0,
                false,
                false,
                buildPartyRows(Minecraft.getInstance()),
                mapSnapshot);
        RunHistoryStore.BestFlags flags = RunHistoryStore.add(record);
        announce(RunHistoryStore.latest(), flags);
        resetRun();
    }

    // ------------------------------------------------------------------ chat output

    private static void announce(RunRecord stored, RunHistoryStore.BestFlags flags) {
        if (stored == null) {
            return;
        }
        RunSummaryConfig cfg = RunSummaryConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (cfg.isAnnouncePersonalBests() && flags.any()) {
            ModChat.send(FEATURE, ModChat.good("Personal best! "),
                    ModChat.text(flags.time() && flags.score() ? "Fastest time AND best score on "
                            : flags.time() ? "Fastest time on " : "Best score on "),
                    ModChat.value(stored.floorLabel()), ModChat.text("."));
        }
        if (!cfg.isChatSummary()) {
            return;
        }
        // Split Timers already prints its own per-phase "X took Ys" block at the end of a run. When it is
        // doing that, our compact summary drops its phase lines and prints only the stats Split Timers
        // never covers, so the two never post the same thing twice.
        boolean splitTimersPrints = cfg.isAvoidDuplicateChat()
                && SplitTimersConfig.getInstance().isEnabled()
                && SplitTimersConfig.getInstance().isAnnounceInChat();
        for (String line : chatLines(stored, !splitTimersPrints)) {
            client.player.sendSystemMessage(ModChat.prefix(FEATURE).append(Component.literal(line)));
        }
    }

    /** The compact chat summary body. {@code includePhases} is false while Split Timers is printing its
     *  own phase block. */
    private static List<String> chatLines(RunRecord r, boolean includePhases) {
        List<String> out = new ArrayList<>();
        out.add("§6" + r.floorLabel() + "§f in §6" + RunRecord.formatTime(r.totalMs())
                + (r.hypixelTime() != null ? " §7(Hypixel: " + r.hypixelTime() + ")" : ""));
        out.add(scoreLine(r));
        out.add(statsLine(r));
        if (includePhases) {
            for (RunRecord.Split split : r.splits()) {
                out.add("§7" + split.name() + "§f: §6" + RunRecord.formatTime(split.ms()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ summary view

    /** The full "Open Last Run" body, as § formatted lines - used by the settings tab's detail view and by
     *  the chat summary, so both always say the same thing in the same order. */
    public static List<String> summaryLines(RunRecord r) {
        List<String> out = new ArrayList<>();
        if (r == null) {
            out.add("§7No runs recorded yet.");
            return out;
        }
        out.add("§6" + r.floorLabel() + "§7  -  §f" + r.dateText());
        out.add("§7Time: §6" + RunRecord.formatTime(r.totalMs())
                + (r.hypixelTime() != null ? " §7(Hypixel: §f" + r.hypixelTime() + "§7)" : "")
                + (r.hypixelNewRecord() ? " §a(NEW RECORD!)" : "")
                + (r.bestTime() ? " §a*PB*" : ""));
        out.add(scoreLine(r) + (r.bestScore() ? " §a*PB*" : ""));
        out.add(statsLine(r));
        out.add("§7Class: §f" + orDash(r.dungeonClass()) + "   §7Party: §f"
                + (r.partySize() > 0 ? String.valueOf(r.partySize()) : "-"));
        if (r.chestProfit() != RunRecord.UNKNOWN_PROFIT) {
            out.add("§7Chest profit: §6" + formatCoins(r.chestProfit()) + " §7(" + r.chestCount() + " chest(s))");
        }
        if (!r.splits().isEmpty()) {
            out.add("");
            out.add("§6§lPhases");
            for (RunRecord.Split split : r.splits()) {
                out.add("§7" + split.name() + "§f: §6" + RunRecord.formatTime(split.ms()));
            }
        }
        if (!r.devices().isEmpty()) {
            out.add("");
            out.add("§6§lDevices / Terminals");
            for (RunRecord.Device d : r.devices()) {
                out.add("§7" + d.player() + " §f" + d.kind() + " §7(" + d.index() + "/" + d.total() + ")§f: §6"
                        + (d.msIntoPhase() >= 0 ? RunRecord.formatTime(d.msIntoPhase()) : "-")
                        + (d.phase() != null ? " §7into " + d.phase() : ""));
            }
        }
        return out;
    }

    private static String scoreLine(RunRecord r) {
        String hypixel = r.hypixelScore() >= 0
                ? "§6" + r.hypixelScore() + " §7(" + orDash(r.hypixelRank()) + ")"
                : "§7-";
        String estimate = r.estimatedScore() >= 0
                ? "§f" + r.estimatedScore() + " §7(" + orDash(r.estimatedRank()) + ")"
                : "§7-";
        return "§7Score: " + hypixel + "  §8| §7estimate: " + estimate;
    }

    private static String statsLine(RunRecord r) {
        String secrets = r.secretsFound() >= 0
                ? r.secretsFound() + (r.totalSecrets() > 0 ? "/" + r.totalSecrets() : "")
                : "-";
        return "§7Secrets §f" + secrets + "   §7Crypts §f" + orDashInt(r.crypts())
                + "   §7Deaths §f" + orDashInt(r.deaths())
                + "   §7Failed puzzles §f" + orDashInt(r.puzzlesFailed());
    }

    /** One line per run for the history list: floor, time, score, date. */
    public static String listLine(RunRecord r) {
        String score = r.effectiveScore() >= 0 ? String.valueOf(r.effectiveScore()) : "-";
        String rank = r.effectiveRank() == null ? "" : " (" + r.effectiveRank() + ")";
        return "§6" + r.floorLabel() + " §8| §f" + RunRecord.formatTime(r.totalMs())
                + " §8| §f" + score + rank + (r.bestTime() || r.bestScore() ? " §a*" : "")
                + " §8| §7" + r.dateText();
    }

    private static String formatCoins(long coins) {
        if (Math.abs(coins) >= 1_000_000L) {
            return String.format(Locale.US, "%.2fM", coins / 1_000_000.0);
        }
        if (Math.abs(coins) >= 1_000L) {
            return String.format(Locale.US, "%.1fk", coins / 1_000.0);
        }
        return String.valueOf(coins);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String orDashInt(int value) {
        return value < 0 ? "-" : String.valueOf(value);
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** True while a run is being tracked - the tab shows "recording..." instead of a stale last run. */
    public static boolean isTrackingRun() {
        return active;
    }
}
